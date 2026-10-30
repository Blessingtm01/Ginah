"""Incident reporting, assessment, assignment, response tracking and referrals."""
import uuid
from datetime import datetime

from fastapi import APIRouter, Depends, File, HTTPException, Request, Response, UploadFile, status
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field, model_validator
from sqlalchemy import func, or_, select
from sqlalchemy.orm import Session

from .. import audit
from .. import models as m
from ..config import settings
from ..db import get_db, to_utc_naive, utcnow
from ..deps import CurrentUser, current_user, load_incident, require, team_actor_for, user_team_ids, visible_incidents_filter
from ..notify import notify, team_users, users_with_roles
from ..schemas import Priority, TriState, UtcDt
from ..workflow import (
    ASSIGNABLE, REFERRABLE, TransitionError, ack_deadline, allowed_manual_targets, check_transition,
    initial_statuses, suggest_priority,
)

router = APIRouter(tags=["incidents"])

NOTIFY_REPORTER = {"ACKNOWLEDGED", "ASSIGNED", "RESOLVED", "CLOSED", "CANCELLED", "REJECTED", "COMPLETED",
                   "REFERRED_EXTERNALLY", "UNABLE_TO_RESPOND"}


# --- helpers -------------------------------------------------------------------------

def _set_status(db: Session, inc: m.Incident, to: str, user_id: int | None, note: str | None = None):
    db.add(m.IncidentStatusHistory(incident_id=inc.id, from_status=inc.status, to_status=to, user_id=user_id, note=note))
    inc.status = to
    inc.version += 1
    now = utcnow()
    if to == "ACKNOWLEDGED" and inc.acknowledged_at is None:
        inc.acknowledged_at = now
    if to in ("RESOLVED", "COMPLETED") and inc.resolved_at is None:
        inc.resolved_at = now
    if to == "CLOSED":
        inc.closed_at = now


def _system_transition(db, inc, to, user_id, note=None):
    if inc.status == to and to != "ASSIGNED":
        return
    try:
        check_transition(inc.kind, inc.status, to, "system")
    except TransitionError as e:
        raise HTTPException(status.HTTP_409_CONFLICT, str(e))
    _set_status(db, inc, to, user_id, note)


def _check_version(inc: m.Incident, expected: int | None):
    if expected is not None and expected != inc.version:
        raise HTTPException(status.HTTP_409_CONFLICT, {
            "message": "This incident was changed by someone else. Reload it and try again.",
            "current_version": inc.version,
        })


def _notify_reporter(db, council, inc, to):
    if to in NOTIFY_REPORTER and inc.reporter_id:
        reporter = db.get(m.User, inc.reporter_id)
        if reporter and reporter.active:
            label = to.replace("_", " ").lower()
            notify(db, council, [reporter], event="INCIDENT_STATUS_CHANGED", incident_id=inc.id,
                   title=f"Your report {inc.reference}: {label}", body=f"The status of your report is now: {label}.")


def _personal_visible(db, cu: CurrentUser, inc: m.Incident) -> bool:
    if cu.can("personal.view") or inc.reporter_id == cu.id:
        return True
    teams = user_team_ids(db, cu.id)
    return bool(teams) and db.scalar(select(m.Assignment.id).where(
        m.Assignment.incident_id == inc.id, m.Assignment.team_id.in_(teams),
        m.Assignment.status.in_(("PENDING", "ACCEPTED")))) is not None


def _dt(d: datetime | None) -> str | None:
    return None if d is None else d.isoformat(timespec="seconds") + "Z"


def incident_summary(inc: m.Incident) -> dict:
    return {
        "id": inc.id, "reference": inc.reference, "kind": inc.kind, "category_id": inc.category_id,
        "category": inc.category.name, "status": inc.status, "priority": inc.priority,
        "suggested_priority": inc.suggested_priority, "source": inc.source, "reported_at": _dt(inc.reported_at),
        "received_at": _dt(inc.received_at), "updated_at": _dt(inc.updated_at), "latitude": inc.latitude,
        "longitude": inc.longitude, "accuracy_m": inc.accuracy_m, "ward_id": inc.ward_id,
        "ward": inc.ward.name if inc.ward else None, "landmark": inc.landmark,
        "description": inc.description[:200], "version": inc.version, "is_demo": inc.is_demo,
        "local_id": inc.local_id,
    }


# --- create ------------------------------------------------------------------------------

class IncidentIn(BaseModel):
    idempotency_key: str = Field(min_length=8, max_length=64, pattern=r"^[A-Za-z0-9_-]+$")
    local_id: str | None = Field(default=None, max_length=64)
    category_id: int
    description: str = Field(min_length=3, max_length=5000)
    source: str = Field(default="APP", pattern="^(APP|ACCIDENT_DETECTION|PHONE_CALL|WALK_IN|OTHER)$")
    reported_at: datetime
    occurred_at: datetime | None = None
    occurred_at_unknown: bool = False
    latitude: float | None = Field(default=None, ge=-90, le=90)
    longitude: float | None = Field(default=None, ge=-180, le=180)
    accuracy_m: float | None = Field(default=None, ge=0, le=100000)
    location_source: str = Field(default="NONE", pattern="^(GPS|MAP|MANUAL|NONE)$")
    ward_id: int | None = None
    landmark: str | None = Field(default=None, max_length=300)
    injuries: TriState | None = None
    immediate_danger: TriState | None = None
    threat_to_life: TriState | None = None
    may_worsen: TriState | None = None
    affected_people: int | None = Field(default=None, ge=0, le=1_000_000)
    affected_people_unknown: bool = False
    reporter_name: str | None = Field(default=None, max_length=200)
    reporter_phone: str | None = Field(default=None, pattern=r"^\+?[0-9 ]{7,20}$")

    @model_validator(mode="after")
    def _check(self):
        if (self.latitude is None) != (self.longitude is None):
            raise ValueError("Latitude and longitude must be given together")
        if self.latitude is None and not (self.landmark or "").strip() and self.ward_id is None:
            raise ValueError("Give a GPS position, a ward or a landmark so responders can find the incident")
        if self.affected_people is not None and self.affected_people_unknown:
            raise ValueError("Affected people cannot be both a number and unknown")
        return self


@router.post("/incidents", status_code=201)
def create_incident(body: IncidentIn, request: Request, response: Response,
                    cu: CurrentUser = Depends(require("incidents.create")), db: Session = Depends(get_db)):
    existing = db.scalar(select(m.Incident).where(m.Incident.council_id == cu.council_id,
                                                  m.Incident.idempotency_key == body.idempotency_key))
    if existing:
        # A retry of a report that already arrived: return it instead of creating a duplicate.
        db.add(m.SyncRecord(council_id=cu.council_id, user_id=cu.id, entity="incident",
                            idempotency_key=body.idempotency_key, result="DUPLICATE", detail=existing.reference))
        db.commit()
        response.status_code = status.HTTP_200_OK
        return {**incident_summary(existing), "duplicate": True}

    cat = db.get(m.IncidentCategory, body.category_id)
    if cat is None or cat.council_id != cu.council_id or not cat.active:
        raise HTTPException(422, "Unknown or inactive incident category")
    if body.ward_id is not None:
        ward = db.get(m.Ward, body.ward_id)
        if ward is None or ward.council_id != cu.council_id:
            raise HTTPException(422, "Unknown ward")
    council = db.get(m.Council, cu.council_id)
    suggested, reasons = suggest_priority(
        category_default=cat.default_priority, threat_to_life=body.threat_to_life, injuries=body.injuries,
        immediate_danger=body.immediate_danger, may_worsen=body.may_worsen, affected_people=body.affected_people,
    )
    data = body.model_dump(exclude={"reported_at", "occurred_at"})
    inc = m.Incident(
        council_id=cu.council_id, reference=f"TMP-{uuid.uuid4().hex[:12]}", kind=cat.kind, status="DRAFT",
        suggested_priority=suggested if cat.kind == "EMERGENCY" else None,
        reported_at=to_utc_naive(body.reported_at), occurred_at=to_utc_naive(body.occurred_at),
        reporter_id=cu.id, is_demo=council.is_demo,
        **{k: v for k, v in data.items() if k not in ("reporter_name", "reporter_phone")},
    )
    inc.reporter_name = body.reporter_name or cu.user.full_name
    inc.reporter_phone = body.reporter_phone or cu.user.phone
    db.add(inc)
    db.flush()
    inc.reference = f"{council.code}-{inc.received_at:%Y}-{inc.id:06d}"
    for s in initial_statuses(inc.kind):
        db.add(m.IncidentStatusHistory(incident_id=inc.id, from_status=inc.status if inc.status != "DRAFT" else None,
                                       to_status=s, user_id=cu.id))
        inc.status = s
    audit.record(db, council_id=cu.council_id, user_id=cu.id, action="INCIDENT_CREATE", entity="incident", entity_id=inc.id,
                 new={"reference": inc.reference, "category": cat.name, "suggested_priority": suggested, "source": inc.source},
                 request=request)
    db.add(m.SyncRecord(council_id=cu.council_id, user_id=cu.id, entity="incident", idempotency_key=body.idempotency_key,
                        result="CREATED", detail=inc.reference))
    coordinators = users_with_roles(db, cu.council_id, ["COORDINATOR"])
    where = inc.ward.name if inc.ward else (inc.landmark or "location on map")
    if inc.kind == "EMERGENCY":
        event = "CRITICAL_INCIDENT" if suggested == "CRITICAL" else "NEW_INCIDENT"
        notify(db, council, coordinators, event=event, incident_id=inc.id,
               title=f"{'CRITICAL: ' if suggested == 'CRITICAL' else ''}New {cat.name} {inc.reference}",
               body=f"{cat.name} reported at {where}. Suggested priority {suggested} ({'; '.join(reasons)}).",
               sms_body=f"AcciSafe RDC: new {cat.name} {inc.reference} at {where}, suggested {suggested}. Open the app to acknowledge.")
    else:
        notify(db, council, coordinators, event="NEW_SERVICE_REQUEST", incident_id=inc.id,
               title=f"New service request {inc.reference}", body=f"{cat.name} reported at {where}.")
    db.commit()
    return {**incident_summary(inc), "duplicate": False, "suggestion_reasons": reasons}


# --- list and detail ----------------------------------------------------------------------

@router.get("/incidents")
def list_incidents(
    page: int = 1, page_size: int = 30, q: str | None = None, kind: str | None = None, status_: str | None = None,
    priority: str | None = None, category_id: int | None = None, ward_id: int | None = None, team_id: int | None = None,
    source: str | None = None, referred: bool | None = None, date_from: datetime | None = None,
    date_to: datetime | None = None, updated_since: datetime | None = None, mine: bool = False, open_only: bool = False,
    cu: CurrentUser = Depends(current_user), db: Session = Depends(get_db),
):
    page_size = max(1, min(page_size, 100))
    qry = select(m.Incident).where(visible_incidents_filter(db, cu))
    if q:
        like = f"%{q.strip()}%"
        qry = qry.where(or_(m.Incident.reference.ilike(like), m.Incident.description.ilike(like), m.Incident.landmark.ilike(like)))
    if kind:
        qry = qry.where(m.Incident.kind == kind)
    if status_:
        qry = qry.where(m.Incident.status.in_(status_.split(",")))
    if open_only:
        qry = qry.where(m.Incident.status.not_in(("RESOLVED", "CLOSED", "CANCELLED", "COMPLETED", "REJECTED")))
    if priority:
        qry = qry.where(func.coalesce(m.Incident.priority, m.Incident.suggested_priority).in_(priority.split(",")))
    if category_id:
        qry = qry.where(m.Incident.category_id == category_id)
    if ward_id:
        qry = qry.where(m.Incident.ward_id == ward_id)
    if source:
        qry = qry.where(m.Incident.source == source)
    if team_id:
        qry = qry.where(m.Incident.id.in_(select(m.Assignment.incident_id).where(m.Assignment.team_id == team_id)))
    if referred is not None:
        sub = select(m.Referral.incident_id)
        qry = qry.where(m.Incident.id.in_(sub) if referred else m.Incident.id.not_in(sub))
    if date_from:
        qry = qry.where(m.Incident.received_at >= to_utc_naive(date_from))
    if date_to:
        qry = qry.where(m.Incident.received_at <= to_utc_naive(date_to))
    if updated_since:
        qry = qry.where(m.Incident.updated_at > to_utc_naive(updated_since))
    if mine:
        qry = qry.where(m.Incident.reporter_id == cu.id)
    total = db.scalar(select(func.count()).select_from(qry.subquery()))
    rows = db.scalars(qry.order_by(m.Incident.received_at.desc()).offset((page - 1) * page_size).limit(page_size)).all()
    return {"items": [incident_summary(i) for i in rows], "total": total, "page": page, "page_size": page_size}


@router.get("/incidents/{incident_id}")
def incident_detail(incident_id: int, cu: CurrentUser = Depends(current_user), db: Session = Depends(get_db)):
    inc = load_incident(db, cu, incident_id)
    names = dict(db.execute(select(m.User.id, m.User.full_name).where(m.User.council_id == cu.council_id)).all())
    name = lambda uid: names.get(uid) if uid else None  # noqa: E731
    internal = cu.can("incidents.view_all") or cu.can("incidents.view_assigned")
    out = incident_summary(inc)
    out.update({
        "description": inc.description, "occurred_at": _dt(inc.occurred_at), "occurred_at_unknown": inc.occurred_at_unknown,
        "acknowledged_at": _dt(inc.acknowledged_at), "resolved_at": _dt(inc.resolved_at), "closed_at": _dt(inc.closed_at),
        "location_source": inc.location_source, "injuries": inc.injuries, "immediate_danger": inc.immediate_danger,
        "threat_to_life": inc.threat_to_life, "may_worsen": inc.may_worsen, "affected_people": inc.affected_people,
        "affected_people_unknown": inc.affected_people_unknown, "escalated_from_routine": inc.escalated_from_routine,
    })
    if _personal_visible(db, cu, inc):
        out.update({"reporter_name": inc.reporter_name, "reporter_phone": inc.reporter_phone})
    hist = db.scalars(select(m.IncidentStatusHistory).where(m.IncidentStatusHistory.incident_id == inc.id).order_by(m.IncidentStatusHistory.id)).all()
    out["status_history"] = [{"from": h.from_status, "to": h.to_status, "by": name(h.user_id) if internal else None,
                              "note": h.note if internal else None, "at": _dt(h.created_at)} for h in hist]
    atts = db.scalars(select(m.IncidentAttachment).where(m.IncidentAttachment.incident_id == inc.id)).all()
    out["attachments"] = [{"id": a.id, "filename": a.filename, "content_type": a.content_type, "size_bytes": a.size_bytes,
                           "at": _dt(a.created_at)} for a in atts]
    if internal:
        out["assessments"] = [{"original": a.original_priority, "new": a.new_priority, "suggested": a.suggested_priority,
                               "reason": a.reason, "officer": name(a.officer_id), "at": _dt(a.created_at)}
                              for a in db.scalars(select(m.IncidentAssessment).where(m.IncidentAssessment.incident_id == inc.id).order_by(m.IncidentAssessment.id))]
        out["assignments"] = [{
            "id": a.id, "team_id": a.team_id, "team": a.team.name, "status": a.status, "instructions": a.instructions,
            "assigned_by": name(a.assigned_by), "ack_due_at": _dt(a.ack_due_at), "responded_at": _dt(a.responded_at),
            "decline_reason": a.decline_reason, "reassign_reason": a.reassign_reason, "departed_at": _dt(a.departed_at),
            "arrived_at": _dt(a.arrived_at), "completed_at": _dt(a.completed_at), "created_at": _dt(a.created_at),
        } for a in db.scalars(select(m.Assignment).where(m.Assignment.incident_id == inc.id).order_by(m.Assignment.id))]
        out["updates"] = [{"id": u.id, "kind": u.kind, "text": u.text, "by": name(u.user_id), "at": _dt(u.created_at)}
                          for u in db.scalars(select(m.ResponseUpdate).where(m.ResponseUpdate.incident_id == inc.id).order_by(m.ResponseUpdate.id))]
        out["referrals"] = [{
            "id": r.id, "organisation": r.organisation.name, "contact_person": r.contact_person, "referred_at": _dt(r.referred_at),
            "method": r.method, "info_shared": r.info_shared, "external_reference": r.external_reference,
            "ack_status": r.ack_status, "follow_up_notes": r.follow_up_notes, "outcome": r.outcome, "by": name(r.created_by),
        } for r in db.scalars(select(m.Referral).where(m.Referral.incident_id == inc.id).order_by(m.Referral.id))]
        out["resources"] = [{"id": a.id, "resource_id": a.resource_id, "resource": a.resource.name,
                             "allocated_at": _dt(a.allocated_at), "returned_at": _dt(a.returned_at), "notes": a.notes}
                            for a in db.scalars(select(m.ResourceAllocation).where(m.ResourceAllocation.incident_id == inc.id).order_by(m.ResourceAllocation.id))]
        out["escalations"] = [{"level": e.level, "reason": e.reason, "notified_roles": e.notified_roles,
                               "notified_count": e.notified_count, "at": _dt(e.created_at)}
                              for e in db.scalars(select(m.EscalationEvent).where(m.EscalationEvent.incident_id == inc.id).order_by(m.EscalationEvent.id))]
    if cu.can("incidents.view_all"):
        notes = db.scalars(select(m.Notification).where(m.Notification.incident_id == inc.id).order_by(m.Notification.id)).all()
        out["notifications"] = [{"event": n.event, "title": n.title, "recipient": name(n.user_id) or "No recipient",
                                 "read": n.read_at is not None, "at": _dt(n.created_at),
                                 "attempts": [{"channel": a.channel, "status": a.status, "response": a.provider_response} for a in n.attempts]}
                                for n in notes]
    # What this user may do next.
    actors = set()
    if cu.can("incidents.coordinate"):
        actors.add("coordinator")
    if team_actor_for(db, cu, inc):
        actors.add("team")
    out["allowed_status_changes"] = allowed_manual_targets(inc.kind, inc.status, actors)
    out["can_assess"] = cu.can("incidents.assess") and inc.kind == "EMERGENCY" and inc.status not in ("CLOSED", "CANCELLED")
    out["can_assign"] = cu.can("incidents.assign") and inc.status in ASSIGNABLE[inc.kind]
    out["can_refer"] = cu.can("incidents.refer") and inc.status in REFERRABLE
    out["can_escalate_to_emergency"] = cu.can("incidents.coordinate") and inc.kind == "ROUTINE" and inc.status not in ("CLOSED", "REJECTED", "COMPLETED")
    out["can_add_update"] = bool(actors)
    out["can_allocate_resources"] = cu.can("resources.allocate") and inc.status not in ("CLOSED", "CANCELLED")
    out["can_update_referrals"] = cu.can("incidents.refer")
    pending = db.scalar(select(m.Assignment).where(m.Assignment.incident_id == inc.id, m.Assignment.status == "PENDING",
                                                   m.Assignment.team_id.in_(user_team_ids(db, cu.id) or {-1})))
    out["pending_assignment_id"] = pending.id if pending and cu.can("assignments.respond") else None
    return out


# --- status changes ----------------------------------------------------------------------

class StatusIn(BaseModel):
    to: str
    note: str | None = Field(default=None, max_length=2000)
    expected_version: int | None = None


@router.patch("/incidents/{incident_id}/status")
def change_status(incident_id: int, body: StatusIn, request: Request, cu: CurrentUser = Depends(current_user),
                  db: Session = Depends(get_db)):
    inc = load_incident(db, cu, incident_id)
    _check_version(inc, body.expected_version)
    assignment = team_actor_for(db, cu, inc)
    actors = ({"coordinator"} if cu.can("incidents.coordinate") else set()) | ({"team"} if assignment else set())
    if not actors:
        raise HTTPException(403, "You cannot change the status of this incident")
    if body.to in ("RESOLVED", "CLOSED") and not cu.can("incidents.close"):
        raise HTTPException(403, "Only authorised coordinators can resolve or close incidents")
    allowed = allowed_manual_targets(inc.kind, inc.status, actors)
    if body.to not in allowed:
        raise HTTPException(409, f"Cannot change status from {inc.status} to {body.to}. Allowed: {', '.join(allowed) or 'none'}")
    if body.to in ("CANCELLED", "UNABLE_TO_RESPOND", "REJECTED") and not (body.note or "").strip():
        raise HTTPException(422, "Give a reason for this status")
    old = inc.status
    _set_status(db, inc, body.to, cu.id, body.note)
    now = utcnow()
    if assignment:
        if body.to == "DISPATCHED":
            assignment.departed_at = now
        elif body.to == "ON_SCENE" and assignment.arrived_at is None:
            assignment.arrived_at = now
        elif body.to in ("RESPONSE_COMPLETED", "COMPLETED"):
            assignment.completed_at = now
            assignment.status = "COMPLETED"
    if body.to == "ASSISTANCE_REQUESTED":
        notify(db, db.get(m.Council, inc.council_id), users_with_roles(db, inc.council_id, ["COORDINATOR"]),
               event="ASSISTANCE_REQUESTED", incident_id=inc.id, title=f"Assistance requested: {inc.reference}",
               body=body.note or "The team on scene has requested assistance.")
    if body.to in ("RESOLVED", "COMPLETED"):
        notify(db, db.get(m.Council, inc.council_id), users_with_roles(db, inc.council_id, ["COORDINATOR"]),
               event="INCIDENT_RESOLVED", incident_id=inc.id, title=f"{inc.reference} {body.to.lower()}", body=body.note or "")
    _notify_reporter(db, db.get(m.Council, inc.council_id), inc, body.to)
    audit.record(db, council_id=inc.council_id, user_id=cu.id, action="STATUS_CHANGE", entity="incident", entity_id=inc.id,
                 old={"status": old}, new={"status": body.to}, reason=body.note, request=request)
    db.commit()
    return incident_summary(inc)


# --- assessment ------------------------------------------------------------------------

class AssessIn(BaseModel):
    priority: Priority
    reason: str | None = Field(default=None, max_length=2000)
    threat_to_life: TriState | None = None
    injuries: TriState | None = None
    immediate_danger: TriState | None = None
    may_worsen: TriState | None = None
    affected_people: int | None = Field(default=None, ge=0)
    expected_version: int | None = None


@router.post("/incidents/{incident_id}/assess")
def assess(incident_id: int, body: AssessIn, request: Request, cu: CurrentUser = Depends(require("incidents.assess")),
           db: Session = Depends(get_db)):
    inc = load_incident(db, cu, incident_id)
    _check_version(inc, body.expected_version)
    if inc.kind != "EMERGENCY" or inc.status in ("CLOSED", "CANCELLED"):
        raise HTTPException(409, "This incident cannot be assessed")
    for k in ("threat_to_life", "injuries", "immediate_danger", "may_worsen", "affected_people"):
        if (v := getattr(body, k)) is not None:
            setattr(inc, k, v)
    suggested, reasons = suggest_priority(
        category_default=inc.category.default_priority, threat_to_life=inc.threat_to_life, injuries=inc.injuries,
        immediate_danger=inc.immediate_danger, may_worsen=inc.may_worsen, affected_people=inc.affected_people)
    original = inc.priority
    if original is not None and original != body.priority and not (body.reason or "").strip():
        raise HTTPException(422, "Give a reason for changing the priority")
    db.add(m.IncidentAssessment(incident_id=inc.id, original_priority=original, new_priority=body.priority,
                                suggested_priority=suggested, reason=body.reason,
                                factors={"suggestion_reasons": reasons}, officer_id=cu.id))
    inc.priority = body.priority
    inc.suggested_priority = suggested
    inc.version += 1
    if inc.status == "ACKNOWLEDGED":
        _set_status(db, inc, "UNDER_ASSESSMENT", cu.id, "Assessment recorded")
    audit.record(db, council_id=inc.council_id, user_id=cu.id, action="PRIORITY_CHANGE", entity="incident", entity_id=inc.id,
                 old={"priority": original}, new={"priority": body.priority, "suggested": suggested}, reason=body.reason, request=request)
    if body.priority == "CRITICAL" and original != "CRITICAL":
        council = db.get(m.Council, inc.council_id)
        notify(db, council, users_with_roles(db, inc.council_id, ["COORDINATOR", "MANAGEMENT"]), event="CRITICAL_INCIDENT",
               incident_id=inc.id, title=f"CRITICAL: {inc.reference} ({inc.category.name})",
               body=f"Priority set to CRITICAL. {body.reason or ''}".strip())
    db.commit()
    return {**incident_summary(inc), "suggestion_reasons": reasons}


# --- assignment ---------------------------------------------------------------------------

class AssignIn(BaseModel):
    team_id: int
    instructions: str | None = Field(default=None, max_length=2000)
    ack_minutes: int | None = Field(default=None, ge=1, le=10080)
    reassign_reason: str | None = Field(default=None, max_length=1000)
    expected_version: int | None = None


@router.post("/incidents/{incident_id}/assignments", status_code=201)
def assign(incident_id: int, body: AssignIn, request: Request, cu: CurrentUser = Depends(require("incidents.assign")),
           db: Session = Depends(get_db)):
    inc = load_incident(db, cu, incident_id)
    _check_version(inc, body.expected_version)
    if inc.status not in ASSIGNABLE[inc.kind]:
        raise HTTPException(409, f"An incident that is {inc.status} cannot be assigned")
    team = db.get(m.ResponseTeam, body.team_id)
    if team is None or team.council_id != cu.council_id or not team.active:
        raise HTTPException(422, "Unknown or inactive team")
    if team.availability in ("UNAVAILABLE", "OFF_DUTY"):
        raise HTTPException(409, f"{team.name} is {team.availability.replace('_', ' ').lower()}")
    live = db.scalars(select(m.Assignment).where(m.Assignment.incident_id == inc.id, m.Assignment.status.in_(("PENDING", "ACCEPTED")))).all()
    if live and not (body.reassign_reason or "").strip():
        raise HTTPException(422, "This incident is already assigned. Give a reason to reassign it.")
    for a in live:
        a.status = "REASSIGNED"
        a.reassign_reason = body.reassign_reason
    now = utcnow()
    council = db.get(m.Council, inc.council_id)
    due = ack_deadline(now, inc.priority or inc.suggested_priority, council.settings)
    if body.ack_minutes:
        from datetime import timedelta
        due = now + timedelta(minutes=body.ack_minutes)
    a = m.Assignment(incident_id=inc.id, team_id=team.id, assigned_by=cu.id, instructions=body.instructions, ack_due_at=due)
    db.add(a)
    db.flush()
    _system_transition(db, inc, "ASSIGNED", cu.id, f"Assigned to {team.name}")
    if team.availability == "AVAILABLE":
        team.availability = "AWAITING_CONFIRMATION"
        db.add(m.TeamAvailabilityLog(team_id=team.id, availability=team.availability, user_id=cu.id))
    where = inc.ward.name if inc.ward else (inc.landmark or "see map")
    notify(db, council, team_users(db, team), event="ASSIGNMENT_CREATED", incident_id=inc.id,
           title=f"New assignment: {inc.category.name} {inc.reference}",
           body=f"{inc.priority or inc.suggested_priority or ''} priority at {where}. Accept or decline by {due:%H:%M} UTC. {body.instructions or ''}".strip(),
           sms_body=f"AcciSafe RDC: {team.name} assigned to {inc.category.name} {inc.reference} at {where}. Accept in the app.")
    _notify_reporter(db, council, inc, "ASSIGNED")
    audit.record(db, council_id=inc.council_id, user_id=cu.id, action="ASSIGN" if not live else "REASSIGN", entity="incident",
                 entity_id=inc.id, old={"teams": [x.team_id for x in live]}, new={"team": team.id, "assignment": a.id},
                 reason=body.reassign_reason, request=request)
    db.commit()
    return {"assignment_id": a.id, "ack_due_at": _dt(due), "incident": incident_summary(inc)}


class RespondIn(BaseModel):
    accept: bool
    reason: str | None = Field(default=None, max_length=1000)


@router.post("/assignments/{assignment_id}/respond")
def respond(assignment_id: int, body: RespondIn, request: Request, cu: CurrentUser = Depends(require("assignments.respond")),
            db: Session = Depends(get_db)):
    a = db.get(m.Assignment, assignment_id)
    if a is None or a.team_id not in user_team_ids(db, cu.id):
        raise HTTPException(404, "Assignment not found")
    if a.status != "PENDING":
        raise HTTPException(409, f"This assignment is already {a.status.lower()}")
    if not body.accept and not (body.reason or "").strip():
        raise HTTPException(422, "Give a reason for declining")
    inc = db.get(m.Incident, a.incident_id)
    council = db.get(m.Council, inc.council_id)
    a.status = "ACCEPTED" if body.accept else "DECLINED"
    a.responded_at = utcnow()
    a.responded_by = cu.id
    a.decline_reason = None if body.accept else body.reason
    team = a.team
    if body.accept:
        if inc.kind == "EMERGENCY":
            _system_transition(db, inc, "ACCEPTED", cu.id, f"Accepted by {team.name}")
        team.availability = "ASSIGNED"
    else:
        _system_transition(db, inc, "UNDER_ASSESSMENT" if inc.kind == "EMERGENCY" else "UNDER_REVIEW", cu.id,
                           f"Declined by {team.name}: {body.reason}")
        if team.availability == "AWAITING_CONFIRMATION":
            team.availability = "AVAILABLE"
    db.add(m.TeamAvailabilityLog(team_id=team.id, availability=team.availability, user_id=cu.id))
    notify(db, council, users_with_roles(db, inc.council_id, ["COORDINATOR"]),
           event="ASSIGNMENT_ACKNOWLEDGED" if body.accept else "ASSIGNMENT_DECLINED", incident_id=inc.id,
           title=f"{team.name} {'accepted' if body.accept else 'declined'} {inc.reference}",
           body=body.reason or "", sms_body=None)
    audit.record(db, council_id=inc.council_id, user_id=cu.id, action="ASSIGNMENT_ACCEPT" if body.accept else "ASSIGNMENT_DECLINE",
                 entity="assignment", entity_id=a.id, new={"status": a.status}, reason=body.reason, request=request)
    db.commit()
    return {"status": a.status, "incident": incident_summary(inc)}


@router.get("/assignments/mine")
def my_assignments(cu: CurrentUser = Depends(require("incidents.view_assigned")), db: Session = Depends(get_db)):
    teams = user_team_ids(db, cu.id)
    if not teams:
        return []
    rows = db.scalars(select(m.Assignment).where(m.Assignment.team_id.in_(teams),
                                                 m.Assignment.status.in_(("PENDING", "ACCEPTED"))).order_by(m.Assignment.id.desc())).all()
    out = []
    for a in rows:
        inc = db.get(m.Incident, a.incident_id)
        out.append({"assignment_id": a.id, "team": a.team.name, "status": a.status, "instructions": a.instructions,
                    "ack_due_at": _dt(a.ack_due_at), "incident": incident_summary(inc)})
    return out


# --- field updates --------------------------------------------------------------------

class UpdateIn(BaseModel):
    kind: str = Field(pattern="^(PROGRESS|ACTION|OBSERVATION|ASSISTANCE_REQUEST|RESOURCE_REQUEST|COMPLETION|NOTE)$")
    text: str = Field(min_length=2, max_length=4000)


@router.post("/incidents/{incident_id}/updates", status_code=201)
def add_update(incident_id: int, body: UpdateIn, request: Request, cu: CurrentUser = Depends(current_user),
               db: Session = Depends(get_db)):
    inc = load_incident(db, cu, incident_id)
    assignment = team_actor_for(db, cu, inc)
    if not (assignment or cu.can("incidents.coordinate")):
        raise HTTPException(403, "Only the responding team or a coordinator can add updates")
    if body.kind == "RESOURCE_REQUEST" and not (cu.can("resources.request") or cu.can("incidents.coordinate")):
        raise HTTPException(403, "You cannot request resources")
    u = m.ResponseUpdate(incident_id=inc.id, assignment_id=assignment.id if assignment else None, user_id=cu.id,
                         kind=body.kind, text=body.text)
    db.add(u)
    inc.version += 1
    if body.kind in ("ASSISTANCE_REQUEST", "RESOURCE_REQUEST"):
        notify(db, db.get(m.Council, inc.council_id), users_with_roles(db, inc.council_id, ["COORDINATOR"]),
               event="RESOURCES_REQUESTED" if body.kind == "RESOURCE_REQUEST" else "ASSISTANCE_REQUESTED",
               incident_id=inc.id, title=f"{body.kind.replace('_', ' ').title()}: {inc.reference}", body=body.text[:300])
    audit.record(db, council_id=inc.council_id, user_id=cu.id, action="RESPONSE_UPDATE", entity="incident", entity_id=inc.id,
                 new={"kind": body.kind}, request=request)
    db.commit()
    return {"id": u.id}


# --- referrals --------------------------------------------------------------------------

class ReferralIn(BaseModel):
    organisation_id: int
    contact_person: str | None = Field(default=None, max_length=200)
    referred_at: datetime | None = None
    method: str = Field(pattern="^(PHONE|IN_PERSON|RADIO|LETTER|OTHER)$")
    info_shared: str = Field(min_length=3, max_length=4000)
    external_reference: str | None = Field(default=None, max_length=100)


class ReferralPatch(BaseModel):
    ack_status: str | None = Field(default=None, pattern="^(NOT_CONFIRMED|ACKNOWLEDGED|DECLINED)$")
    external_reference: str | None = Field(default=None, max_length=100)
    follow_up_notes: str | None = Field(default=None, max_length=4000)
    outcome: str | None = Field(default=None, max_length=4000)


@router.post("/incidents/{incident_id}/referrals", status_code=201)
def refer(incident_id: int, body: ReferralIn, request: Request, cu: CurrentUser = Depends(require("incidents.refer")),
          db: Session = Depends(get_db)):
    inc = load_incident(db, cu, incident_id)
    if inc.status not in REFERRABLE:
        raise HTTPException(409, f"An incident that is {inc.status} cannot be referred")
    org = db.get(m.ExternalOrganisation, body.organisation_id)
    if org is None or org.council_id != cu.council_id or not org.active:
        raise HTTPException(422, "Unknown organisation")
    r = m.Referral(incident_id=inc.id, organisation_id=org.id, contact_person=body.contact_person,
                   referred_at=to_utc_naive(body.referred_at) or utcnow(), method=body.method, info_shared=body.info_shared,
                   external_reference=body.external_reference, created_by=cu.id)
    db.add(r)
    db.flush()
    _system_transition(db, inc, "REFERRED_EXTERNALLY", cu.id, f"Referred to {org.name} by {body.method.lower()}")
    notify(db, db.get(m.Council, inc.council_id), users_with_roles(db, inc.council_id, ["COORDINATOR"]),
           event="REFERRAL_RECORDED", incident_id=inc.id, title=f"{inc.reference} referred to {org.name}",
           body="Recorded as a referral. This does not confirm that the organisation has responded.")
    audit.record(db, council_id=inc.council_id, user_id=cu.id, action="REFERRAL", entity="incident", entity_id=inc.id,
                 new={"organisation": org.name, "method": body.method}, request=request)
    db.commit()
    return {"id": r.id}


@router.patch("/referrals/{referral_id}")
def update_referral(referral_id: int, body: ReferralPatch, request: Request, cu: CurrentUser = Depends(require("incidents.refer")),
                    db: Session = Depends(get_db)):
    r = db.get(m.Referral, referral_id)
    if r is None:
        raise HTTPException(404, "Referral not found")
    inc = load_incident(db, cu, r.incident_id)
    changes = body.model_dump(exclude_unset=True)
    old = {k: getattr(r, k) for k in changes}
    for k, v in changes.items():
        setattr(r, k, v)
    inc.version += 1
    audit.record(db, council_id=inc.council_id, user_id=cu.id, action="REFERRAL_UPDATE", entity="referral", entity_id=r.id,
                 old=old, new=changes, request=request)
    db.commit()
    return {"ok": True}


# --- routine to emergency ----------------------------------------------------------------

class EscalateIn(BaseModel):
    reason: str = Field(min_length=5, max_length=2000)
    category_id: int


@router.post("/incidents/{incident_id}/escalate-to-emergency")
def escalate_to_emergency(incident_id: int, body: EscalateIn, request: Request,
                          cu: CurrentUser = Depends(require("incidents.coordinate")), db: Session = Depends(get_db)):
    inc = load_incident(db, cu, incident_id)
    if inc.kind != "ROUTINE" or inc.status in ("CLOSED", "REJECTED", "COMPLETED"):
        raise HTTPException(409, "Only an open routine service request can be escalated")
    cat = db.get(m.IncidentCategory, body.category_id)
    if cat is None or cat.council_id != cu.council_id or cat.kind != "EMERGENCY":
        raise HTTPException(422, "Choose an emergency category")
    for a in db.scalars(select(m.Assignment).where(m.Assignment.incident_id == inc.id, m.Assignment.status.in_(("PENDING", "ACCEPTED")))):
        a.status = "CANCELLED"
    old = {"kind": inc.kind, "status": inc.status, "category": inc.category.name}
    inc.kind = "EMERGENCY"
    inc.category_id = cat.id
    inc.escalated_from_routine = True
    suggested, _ = suggest_priority(category_default=cat.default_priority, threat_to_life=inc.threat_to_life,
                                    injuries=inc.injuries, immediate_danger="YES", may_worsen=inc.may_worsen,
                                    affected_people=inc.affected_people)
    inc.suggested_priority = suggested
    _set_status(db, inc, "AWAITING_REVIEW", cu.id, f"Escalated from routine request: {body.reason}")
    inc.received_at = utcnow()  # acknowledgement clock starts now
    council = db.get(m.Council, inc.council_id)
    notify(db, council, users_with_roles(db, inc.council_id, ["COORDINATOR"]), event="NEW_INCIDENT", incident_id=inc.id,
           title=f"Escalated to emergency: {inc.reference}", body=body.reason)
    audit.record(db, council_id=inc.council_id, user_id=cu.id, action="ESCALATE_TO_EMERGENCY", entity="incident",
                 entity_id=inc.id, old=old, new={"kind": "EMERGENCY", "category": cat.name}, reason=body.reason, request=request)
    db.commit()
    return incident_summary(inc)


# --- attachments --------------------------------------------------------------------------

ALLOWED_TYPES = {"image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp"}


@router.post("/incidents/{incident_id}/attachments", status_code=201)
async def upload_attachment(incident_id: int, request: Request, file: UploadFile = File(...),
                            cu: CurrentUser = Depends(current_user), db: Session = Depends(get_db)):
    inc = load_incident(db, cu, incident_id)
    if not (inc.reporter_id == cu.id or team_actor_for(db, cu, inc) or cu.can("incidents.coordinate")):
        raise HTTPException(403, "You cannot add attachments to this incident")
    ext = ALLOWED_TYPES.get(file.content_type or "")
    if ext is None:
        raise HTTPException(415, "Only JPEG, PNG or WebP photographs can be attached")
    data = await file.read(settings.max_attachment_bytes + 1)
    if len(data) > settings.max_attachment_bytes:
        raise HTTPException(413, "The photograph is too large")
    # Stored in the database so photos survive restarts on hosts with temporary disks.
    a = m.IncidentAttachment(incident_id=inc.id, filename=(file.filename or f"photo{ext}")[:200], content_type=file.content_type,
                             storage_path="db", content=data, size_bytes=len(data), uploaded_by=cu.id)
    db.add(a)
    inc.version += 1
    db.flush()
    audit.record(db, council_id=inc.council_id, user_id=cu.id, action="ATTACHMENT_ADD", entity="incident", entity_id=inc.id,
                 new={"attachment": a.id}, request=request)
    db.commit()
    return {"id": a.id}


@router.get("/attachments/{attachment_id}")
def download_attachment(attachment_id: int, cu: CurrentUser = Depends(current_user), db: Session = Depends(get_db)):
    a = db.get(m.IncidentAttachment, attachment_id)
    if a is None:
        raise HTTPException(404, "Attachment not found")
    load_incident(db, cu, a.incident_id)  # access check
    if a.storage_path == "db":
        return Response(content=a.content or b"", media_type=a.content_type,
                        headers={"Content-Disposition": f'inline; filename="{a.filename}"'})
    return FileResponse(a.storage_path, media_type=a.content_type, filename=a.filename)


# --- resource allocation --------------------------------------------------------------------

class AllocateIn(BaseModel):
    resource_id: int
    notes: str | None = Field(default=None, max_length=1000)


@router.post("/incidents/{incident_id}/resources", status_code=201)
def allocate(incident_id: int, body: AllocateIn, request: Request, cu: CurrentUser = Depends(require("resources.allocate")),
             db: Session = Depends(get_db)):
    inc = load_incident(db, cu, incident_id)
    res = db.get(m.Resource, body.resource_id)
    if res is None or res.council_id != cu.council_id:
        raise HTTPException(422, "Unknown resource")
    if res.status != "AVAILABLE":
        raise HTTPException(409, f"{res.name} is {res.status.replace('_', ' ').lower()}")
    alloc = m.ResourceAllocation(resource_id=res.id, incident_id=inc.id, allocated_by=cu.id, notes=body.notes)
    res.status = "ASSIGNED"
    db.add(alloc)
    inc.version += 1
    db.flush()
    audit.record(db, council_id=inc.council_id, user_id=cu.id, action="RESOURCE_ALLOCATE", entity="incident", entity_id=inc.id,
                 new={"resource": res.id, "allocation": alloc.id}, request=request)
    db.commit()
    return {"id": alloc.id}


@router.post("/allocations/{allocation_id}/return")
def return_resource(allocation_id: int, request: Request, cu: CurrentUser = Depends(require("resources.allocate")),
                    db: Session = Depends(get_db)):
    alloc = db.get(m.ResourceAllocation, allocation_id)
    if alloc is None:
        raise HTTPException(404, "Allocation not found")
    inc = load_incident(db, cu, alloc.incident_id)
    if alloc.returned_at:
        raise HTTPException(409, "Already returned")
    alloc.returned_at = utcnow()
    if alloc.resource.status == "ASSIGNED":
        alloc.resource.status = "AVAILABLE"
    inc.version += 1
    audit.record(db, council_id=inc.council_id, user_id=cu.id, action="RESOURCE_RETURN", entity="incident", entity_id=inc.id,
                 new={"resource": alloc.resource_id}, request=request)
    db.commit()
    return {"ok": True}
