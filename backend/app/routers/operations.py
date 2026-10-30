"""Response teams, resources, notifications, escalations and accident-detection events."""
from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.orm import Session

from .. import audit
from .. import models as m
from ..db import get_db, to_utc_naive, utcnow
from ..deps import CurrentUser, current_user, require, user_team_ids
from ..escalation import run_escalations
from ..notify import notify, users_with_roles

router = APIRouter(tags=["operations"])


def _dt(d):
    return None if d is None else d.isoformat(timespec="seconds") + "Z"


# --- teams ------------------------------------------------------------------------------

TEAM_AVAILABILITY = ("AVAILABLE", "ASSIGNED", "BUSY", "UNAVAILABLE", "OFF_DUTY", "AWAITING_CONFIRMATION")


class TeamIn(BaseModel):
    name: str = Field(min_length=2, max_length=120)
    category: str = Field(min_length=2, max_length=60)
    leader_id: int | None = None
    member_ids: list[int] = []
    contact_phone: str | None = Field(default=None, max_length=40)
    operational_area: str | None = Field(default=None, max_length=200)
    capabilities: str | None = Field(default=None, max_length=2000)
    notes: str | None = Field(default=None, max_length=2000)
    active: bool = True


def team_out(db: Session, t: m.ResponseTeam, cu: CurrentUser, detail=False) -> dict:
    active = db.scalars(select(m.Assignment).where(m.Assignment.team_id == t.id, m.Assignment.status.in_(("PENDING", "ACCEPTED")))).all()
    out = {"id": t.id, "name": t.name, "category": t.category, "availability": t.availability,
           "operational_area": t.operational_area, "capabilities": t.capabilities, "active": t.active,
           "active_assignments": len(active)}
    staff = cu.can("teams.manage") or cu.can("incidents.assign") or t.id in user_team_ids(db, cu.id)
    if staff:
        names = {u.id: u for u in db.scalars(select(m.User).where(m.User.council_id == t.council_id))}
        leader = names.get(t.leader_id)
        out.update({"leader_id": t.leader_id, "leader": leader.full_name if leader else None,
                    "contact_phone": t.contact_phone, "notes": t.notes})
        if detail:
            out["members"] = [{"id": mem.user_id, "full_name": names[mem.user_id].full_name} for mem in t.members if mem.user_id in names]
    if detail:
        out["assignments"] = []
        for a in active:
            inc = db.get(m.Incident, a.incident_id)
            out["assignments"].append({"assignment_id": a.id, "status": a.status, "incident_id": inc.id,
                                       "reference": inc.reference, "category": inc.category.name,
                                       "priority": inc.priority or inc.suggested_priority, "incident_status": inc.status})
    return out


@router.get("/teams")
def list_teams(cu: CurrentUser = Depends(require("teams.view")), db: Session = Depends(get_db)):
    teams = db.scalars(select(m.ResponseTeam).where(m.ResponseTeam.council_id == cu.council_id).order_by(m.ResponseTeam.name)).all()
    return [team_out(db, t, cu) for t in teams]


@router.get("/teams/{team_id}")
def get_team(team_id: int, cu: CurrentUser = Depends(require("teams.view")), db: Session = Depends(get_db)):
    t = db.get(m.ResponseTeam, team_id)
    if t is None or t.council_id != cu.council_id:
        raise HTTPException(404, "Team not found")
    return team_out(db, t, cu, detail=True)


def _check_users(db, council_id, ids):
    for uid in ids:
        u = db.get(m.User, uid)
        if u is None or u.council_id != council_id:
            raise HTTPException(422, f"Unknown user {uid}")


@router.post("/teams", status_code=201)
def create_team(body: TeamIn, request: Request, cu: CurrentUser = Depends(require("teams.manage")), db: Session = Depends(get_db)):
    _check_users(db, cu.council_id, body.member_ids + ([body.leader_id] if body.leader_id else []))
    t = m.ResponseTeam(council_id=cu.council_id, **body.model_dump(exclude={"member_ids"}))
    for uid in set(body.member_ids):
        t.members.append(m.TeamMember(user_id=uid))
    db.add(t)
    try:
        db.flush()
    except Exception:
        db.rollback()
        raise HTTPException(409, "A team with that name already exists")
    audit.record(db, council_id=cu.council_id, user_id=cu.id, action="TEAM_CREATE", entity="team", entity_id=t.id,
                 new=body.model_dump(), request=request)
    db.commit()
    return {"id": t.id}


@router.put("/teams/{team_id}")
def update_team(team_id: int, body: TeamIn, request: Request, cu: CurrentUser = Depends(require("teams.manage")),
                db: Session = Depends(get_db)):
    t = db.get(m.ResponseTeam, team_id)
    if t is None or t.council_id != cu.council_id:
        raise HTTPException(404, "Team not found")
    _check_users(db, cu.council_id, body.member_ids + ([body.leader_id] if body.leader_id else []))
    old = {"name": t.name, "leader_id": t.leader_id, "members": sorted(x.user_id for x in t.members)}
    for k, v in body.model_dump(exclude={"member_ids"}).items():
        setattr(t, k, v)
    t.members.clear()
    db.flush()
    for uid in set(body.member_ids):
        t.members.append(m.TeamMember(user_id=uid))
    audit.record(db, council_id=cu.council_id, user_id=cu.id, action="TEAM_UPDATE", entity="team", entity_id=t.id,
                 old=old, new=body.model_dump(), request=request)
    db.commit()
    return {"ok": True}


class AvailabilityIn(BaseModel):
    availability: str = Field(pattern="^(AVAILABLE|ASSIGNED|BUSY|UNAVAILABLE|OFF_DUTY)$")


@router.patch("/teams/{team_id}/availability")
def set_availability(team_id: int, body: AvailabilityIn, request: Request, cu: CurrentUser = Depends(current_user),
                     db: Session = Depends(get_db)):
    t = db.get(m.ResponseTeam, team_id)
    if t is None or t.council_id != cu.council_id:
        raise HTTPException(404, "Team not found")
    own = cu.can("teams.update_availability") and t.id in user_team_ids(db, cu.id)
    if not (own or cu.can("teams.manage") or cu.can("incidents.assign")):
        raise HTTPException(403, "You cannot change this team's availability")
    old = t.availability
    t.availability = body.availability
    db.add(m.TeamAvailabilityLog(team_id=t.id, availability=t.availability, user_id=cu.id))
    audit.record(db, council_id=cu.council_id, user_id=cu.id, action="TEAM_AVAILABILITY", entity="team", entity_id=t.id,
                 old={"availability": old}, new={"availability": t.availability}, request=request)
    db.commit()
    return {"availability": t.availability}


@router.get("/teams/mine/list")
def my_teams(cu: CurrentUser = Depends(current_user), db: Session = Depends(get_db)):
    ids = user_team_ids(db, cu.id)
    return [team_out(db, db.get(m.ResponseTeam, i), cu) for i in sorted(ids)]


# --- resources ----------------------------------------------------------------------------

class ResourceIn(BaseModel):
    name: str = Field(min_length=2, max_length=160)
    category: str = Field(min_length=2, max_length=60)
    asset_number: str | None = Field(default=None, max_length=60)
    department: str | None = Field(default=None, max_length=120)
    location: str | None = Field(default=None, max_length=200)
    status: str = Field(default="AVAILABLE", pattern="^(AVAILABLE|ASSIGNED|UNDER_MAINTENANCE|OUT_OF_SERVICE|RETIRED)$")
    inspection_date: datetime | None = None
    notes: str | None = Field(default=None, max_length=2000)


class StatusPatch(BaseModel):
    status: str = Field(pattern="^(AVAILABLE|UNDER_MAINTENANCE|OUT_OF_SERVICE|RETIRED)$")
    reason: str | None = Field(default=None, max_length=500)


class MaintenanceIn(BaseModel):
    performed_at: datetime
    description: str = Field(min_length=3, max_length=2000)


def resource_out(db, r: m.Resource, detail=False) -> dict:
    out = {"id": r.id, "name": r.name, "category": r.category, "asset_number": r.asset_number, "department": r.department,
           "location": r.location, "status": r.status, "inspection_date": _dt(r.inspection_date), "notes": r.notes}
    current = db.scalar(select(m.ResourceAllocation).where(m.ResourceAllocation.resource_id == r.id, m.ResourceAllocation.returned_at.is_(None)))
    if current:
        inc = db.get(m.Incident, current.incident_id)
        out["current_assignment"] = {"allocation_id": current.id, "incident_id": inc.id, "reference": inc.reference}
    if detail:
        out["maintenance"] = [{"performed_at": _dt(x.performed_at), "description": x.description}
                              for x in db.scalars(select(m.MaintenanceRecord).where(m.MaintenanceRecord.resource_id == r.id).order_by(m.MaintenanceRecord.performed_at.desc()))]
        out["allocations"] = [{"incident_id": a.incident_id, "allocated_at": _dt(a.allocated_at), "returned_at": _dt(a.returned_at)}
                              for a in db.scalars(select(m.ResourceAllocation).where(m.ResourceAllocation.resource_id == r.id).order_by(m.ResourceAllocation.id.desc()).limit(20))]
    return out


@router.get("/resources")
def list_resources(status_: str | None = None, cu: CurrentUser = Depends(require("resources.view")), db: Session = Depends(get_db)):
    q = select(m.Resource).where(m.Resource.council_id == cu.council_id)
    if status_:
        q = q.where(m.Resource.status == status_)
    return [resource_out(db, r) for r in db.scalars(q.order_by(m.Resource.category, m.Resource.name))]


@router.get("/resources/{resource_id}")
def get_resource(resource_id: int, cu: CurrentUser = Depends(require("resources.view")), db: Session = Depends(get_db)):
    r = db.get(m.Resource, resource_id)
    if r is None or r.council_id != cu.council_id:
        raise HTTPException(404, "Resource not found")
    return resource_out(db, r, detail=True)


@router.post("/resources", status_code=201)
def create_resource(body: ResourceIn, request: Request, cu: CurrentUser = Depends(require("resources.manage")), db: Session = Depends(get_db)):
    data = body.model_dump()
    data["inspection_date"] = to_utc_naive(body.inspection_date)
    r = m.Resource(council_id=cu.council_id, **data)
    db.add(r)
    db.flush()
    audit.record(db, council_id=cu.council_id, user_id=cu.id, action="RESOURCE_CREATE", entity="resource", entity_id=r.id,
                 new=body.model_dump(mode="json"), request=request)
    db.commit()
    return {"id": r.id}


@router.patch("/resources/{resource_id}/status")
def resource_status(resource_id: int, body: StatusPatch, request: Request, cu: CurrentUser = Depends(require("resources.manage", "resources.allocate")),
                    db: Session = Depends(get_db)):
    r = db.get(m.Resource, resource_id)
    if r is None or r.council_id != cu.council_id:
        raise HTTPException(404, "Resource not found")
    if db.scalar(select(m.ResourceAllocation.id).where(m.ResourceAllocation.resource_id == r.id, m.ResourceAllocation.returned_at.is_(None))) and body.status == "AVAILABLE":
        raise HTTPException(409, "Record the return from the incident first")
    old = r.status
    r.status = body.status
    if body.status in ("OUT_OF_SERVICE", "UNDER_MAINTENANCE"):
        notify(db, db.get(m.Council, cu.council_id), users_with_roles(db, cu.council_id, ["COORDINATOR"]),
               event="RESOURCE_UNAVAILABLE", title=f"{r.name} is {body.status.replace('_', ' ').lower()}", body=body.reason or "")
    audit.record(db, council_id=cu.council_id, user_id=cu.id, action="RESOURCE_STATUS", entity="resource", entity_id=r.id,
                 old={"status": old}, new={"status": r.status}, reason=body.reason, request=request)
    db.commit()
    return {"status": r.status}


@router.post("/resources/{resource_id}/maintenance", status_code=201)
def add_maintenance(resource_id: int, body: MaintenanceIn, request: Request, cu: CurrentUser = Depends(require("resources.manage")),
                    db: Session = Depends(get_db)):
    r = db.get(m.Resource, resource_id)
    if r is None or r.council_id != cu.council_id:
        raise HTTPException(404, "Resource not found")
    rec = m.MaintenanceRecord(resource_id=r.id, performed_at=to_utc_naive(body.performed_at), description=body.description, recorded_by=cu.id)
    db.add(rec)
    db.flush()
    audit.record(db, council_id=cu.council_id, user_id=cu.id, action="RESOURCE_MAINTENANCE", entity="resource", entity_id=r.id,
                 new={"maintenance": rec.id}, request=request)
    db.commit()
    return {"id": rec.id}


# --- notifications --------------------------------------------------------------------------

@router.get("/notifications")
def list_notifications(page: int = 1, page_size: int = 50, unread_only: bool = False,
                       cu: CurrentUser = Depends(require("notifications.view")), db: Session = Depends(get_db)):
    page_size = max(1, min(page_size, 100))
    cond = m.Notification.user_id == cu.id
    if cu.can("incidents.coordinate") or cu.can("config.manage"):
        # Coordinators and administrators also see notifications that had no recipient.
        cond = cond | ((m.Notification.council_id == cu.council_id) & m.Notification.user_id.is_(None))
    q = select(m.Notification).where(cond)
    if unread_only:
        q = q.where(m.Notification.read_at.is_(None))
    rows = db.scalars(q.order_by(m.Notification.id.desc()).offset((page - 1) * page_size).limit(page_size)).all()
    unread = len(db.scalars(select(m.Notification.id).where(m.Notification.user_id == cu.id, m.Notification.read_at.is_(None))).all())
    return {"unread": unread, "items": [{
        "id": n.id, "event": n.event, "title": n.title, "body": n.body, "incident_id": n.incident_id,
        "read": n.read_at is not None, "created_at": _dt(n.created_at), "no_recipient": n.user_id is None,
        "channels": [{"channel": a.channel, "status": a.status} for a in n.attempts],
    } for n in rows]}


@router.post("/notifications/{notification_id}/read", status_code=204)
def mark_read(notification_id: int, cu: CurrentUser = Depends(current_user), db: Session = Depends(get_db)):
    n = db.get(m.Notification, notification_id)
    if n is None or n.user_id != cu.id:
        raise HTTPException(404, "Notification not found")
    if n.read_at is None:
        n.read_at = utcnow()
        db.commit()


@router.post("/notifications/read-all", status_code=204)
def mark_all_read(cu: CurrentUser = Depends(current_user), db: Session = Depends(get_db)):
    for n in db.scalars(select(m.Notification).where(m.Notification.user_id == cu.id, m.Notification.read_at.is_(None))):
        n.read_at = utcnow()
    db.commit()


# --- escalations ------------------------------------------------------------------------------

@router.post("/escalations/run")
def escalations_run(cu: CurrentUser = Depends(require("escalation.run")), db: Session = Depends(get_db)):
    events = run_escalations(db, council_id=cu.council_id)
    return {"created": len(events)}


@router.get("/escalations")
def escalations_list(cu: CurrentUser = Depends(require("incidents.view_all")), db: Session = Depends(get_db)):
    rows = db.scalars(select(m.EscalationEvent).join(m.Incident).where(m.Incident.council_id == cu.council_id)
                      .order_by(m.EscalationEvent.id.desc()).limit(200)).all()
    return [{"incident_id": e.incident_id, "assignment_id": e.assignment_id, "level": e.level, "reason": e.reason,
             "notified_roles": e.notified_roles, "notified_count": e.notified_count, "at": _dt(e.created_at)} for e in rows]


# --- accident detection events ------------------------------------------------------------------

class DetectionIn(BaseModel):
    idempotency_key: str = Field(min_length=8, max_length=64, pattern=r"^[A-Za-z0-9_-]+$")
    detected_at: datetime
    peak_g: float = Field(ge=0, le=100)
    peak_rotation: float | None = Field(default=None, ge=0, le=100)
    speed_kmh: float | None = Field(default=None, ge=0, le=500)
    score: int | None = Field(default=None, ge=0, le=10)
    outcome: str = Field(pattern="^(CANCELLED_BY_USER|CONFIRMED_BY_USER|CONFIRMED_NO_RESPONSE|INTERRUPTED)$")
    is_simulation: bool = False
    incident_id: int | None = None
    evidence: str | None = Field(default=None, max_length=1000)


@router.post("/detection-events", status_code=201)
def record_detection(body: DetectionIn, request: Request, response: Response, cu: CurrentUser = Depends(current_user),
                     db: Session = Depends(get_db)):
    existing = db.scalar(select(m.DetectionEvent).where(m.DetectionEvent.council_id == cu.council_id,
                                                        m.DetectionEvent.idempotency_key == body.idempotency_key))
    if existing:
        response.status_code = status.HTTP_200_OK
        return {"id": existing.id, "duplicate": True}
    if body.incident_id is not None:
        inc = db.get(m.Incident, body.incident_id)
        if inc is None or inc.council_id != cu.council_id or inc.reporter_id != cu.id:
            raise HTTPException(422, "Unknown incident")
    ev = m.DetectionEvent(council_id=cu.council_id, user_id=cu.id, **body.model_dump(exclude={"detected_at"}),
                          detected_at=to_utc_naive(body.detected_at))
    db.add(ev)
    db.flush()
    audit.record(db, council_id=cu.council_id, user_id=cu.id, action="DETECTION_EVENT", entity="detection", entity_id=ev.id,
                 new={"outcome": body.outcome, "simulation": body.is_simulation}, request=request)
    db.commit()
    return {"id": ev.id, "duplicate": False}


# --- synchronisation records ------------------------------------------------------------------

@router.get("/sync/records")
def sync_records(cu: CurrentUser = Depends(require("sync.view")), db: Session = Depends(get_db)):
    rows = db.scalars(select(m.SyncRecord).where(m.SyncRecord.council_id == cu.council_id).order_by(m.SyncRecord.id.desc()).limit(200)).all()
    return [{"user_id": r.user_id, "entity": r.entity, "result": r.result, "detail": r.detail, "at": _dt(r.created_at)} for r in rows]
