"""Council configuration and user administration."""
from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, Field
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from .. import audit
from .. import models as m
from ..db import get_db
from ..deps import CurrentUser, current_user, require
from ..permissions import ROLES
from ..schemas import Out, Priority, UtcDt
from ..security import hash_password, validate_password_strength
from ..workflow import DEFAULT_SETTINGS

router = APIRouter(tags=["administration"])


# --- public council information (login and password recovery screens) ---

@router.get("/councils/{code}/public")
def council_public(code: str, db: Session = Depends(get_db)):
    c = db.scalar(select(m.Council).where(m.Council.code == code.upper()))
    if c is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Unknown council code")
    return {"code": c.code, "name": c.name, "phone": c.phone, "email": c.email, "is_demo": c.is_demo,
            "public_registration": c.public_registration}


# --- configuration ---

class WardOut(Out):
    id: int
    name: str
    number: int | None
    active: bool


class CategoryOut(Out):
    id: int
    name: str
    kind: str
    default_priority: str
    active: bool


class OrganisationOut(Out):
    id: int
    name: str
    org_type: str
    phone: str | None
    active: bool


@router.get("/config")
def get_config(cu: CurrentUser = Depends(current_user), db: Session = Depends(get_db)):
    c = db.get(m.Council, cu.council_id)
    wards = db.scalars(select(m.Ward).where(m.Ward.council_id == c.id).order_by(m.Ward.number, m.Ward.name)).all()
    cats = db.scalars(select(m.IncidentCategory).where(m.IncidentCategory.council_id == c.id).order_by(m.IncidentCategory.name)).all()
    orgs = db.scalars(select(m.ExternalOrganisation).where(m.ExternalOrganisation.council_id == c.id).order_by(m.ExternalOrganisation.name)).all()
    out = {
        "council": {"id": c.id, "code": c.code, "name": c.name, "phone": c.phone, "email": c.email,
                    "address": c.address, "is_demo": c.is_demo, "public_registration": c.public_registration},
        "wards": [WardOut.model_validate(w).model_dump() for w in wards],
        "categories": [CategoryOut.model_validate(x).model_dump() for x in cats],
        "organisations": [OrganisationOut.model_validate(o).model_dump() for o in orgs] if cu.can("incidents.view_all") else [],
    }
    if cu.can("config.manage"):
        out["settings"] = {**DEFAULT_SETTINGS, **(c.settings or {})}
    return out


class CouncilPatch(BaseModel):
    name: str | None = Field(default=None, min_length=3, max_length=200)
    phone: str | None = Field(default=None, max_length=40)
    email: str | None = Field(default=None, max_length=200)
    address: str | None = Field(default=None, max_length=300)
    public_registration: bool | None = None


@router.patch("/config/council")
def patch_council(body: CouncilPatch, request: Request, cu: CurrentUser = Depends(require("config.manage")),
                  db: Session = Depends(get_db)):
    c = db.get(m.Council, cu.council_id)
    changes = body.model_dump(exclude_unset=True)
    old = {k: getattr(c, k) for k in changes}
    for k, v in changes.items():
        setattr(c, k, v)
    audit.record(db, council_id=c.id, user_id=cu.id, action="CONFIG_COUNCIL", entity="council", entity_id=c.id,
                 old=old, new=changes, request=request)
    db.commit()
    return {"ok": True}


class SettingsPatch(BaseModel):
    ack_minutes: dict[str, int] | None = None
    escalation_levels: list[list[str]] | None = None
    sms_enabled: bool | None = None
    sms_events: list[str] | None = None


@router.patch("/config/settings")
def patch_settings(body: SettingsPatch, request: Request, cu: CurrentUser = Depends(require("config.manage")),
                   db: Session = Depends(get_db)):
    c = db.get(m.Council, cu.council_id)
    changes = body.model_dump(exclude_unset=True)
    if "ack_minutes" in changes and any(v < 1 or v > 10080 for v in changes["ack_minutes"].values()):
        raise HTTPException(422, "Acknowledgement targets must be between 1 minute and 7 days")
    if "escalation_levels" in changes:
        if not changes["escalation_levels"] or any(not lvl or any(r not in ROLES for r in lvl) for lvl in changes["escalation_levels"]):
            raise HTTPException(422, "Each escalation level needs at least one valid role")
    old = dict(c.settings or {})
    c.settings = {**old, **changes}
    audit.record(db, council_id=c.id, user_id=cu.id, action="CONFIG_SETTINGS", entity="council", entity_id=c.id,
                 old=old, new=c.settings, request=request)
    db.commit()
    return {**DEFAULT_SETTINGS, **c.settings}


class WardIn(BaseModel):
    name: str = Field(min_length=1, max_length=120)
    number: int | None = None
    active: bool = True


class CategoryIn(BaseModel):
    name: str = Field(min_length=2, max_length=120)
    kind: str = Field(pattern="^(EMERGENCY|ROUTINE)$")
    default_priority: Priority = "MEDIUM"
    active: bool = True


class OrganisationIn(BaseModel):
    name: str = Field(min_length=2, max_length=200)
    org_type: str = Field(min_length=2, max_length=60)
    phone: str | None = Field(default=None, max_length=40)
    active: bool = True


def _upsert(db, request, cu, model, body, entity_id, entity_name):
    if entity_id is None:
        obj = model(council_id=cu.council_id, **body.model_dump())
        db.add(obj)
        old = None
    else:
        obj = db.get(model, entity_id)
        if obj is None or obj.council_id != cu.council_id:
            raise HTTPException(404, "Not found")
        old = {k: getattr(obj, k) for k in body.model_dump()}
        for k, v in body.model_dump().items():
            setattr(obj, k, v)
    try:
        db.flush()
    except Exception:
        db.rollback()
        raise HTTPException(409, f"A {entity_name} with that name already exists")
    audit.record(db, council_id=cu.council_id, user_id=cu.id, action=f"CONFIG_{entity_name.upper()}", entity=entity_name,
                 entity_id=obj.id, old=old, new=body.model_dump(), request=request)
    db.commit()
    return {"id": obj.id}


@router.post("/config/wards", status_code=201)
def add_ward(body: WardIn, request: Request, cu=Depends(require("config.manage")), db: Session = Depends(get_db)):
    return _upsert(db, request, cu, m.Ward, body, None, "ward")


@router.put("/config/wards/{ward_id}")
def edit_ward(ward_id: int, body: WardIn, request: Request, cu=Depends(require("config.manage")), db: Session = Depends(get_db)):
    return _upsert(db, request, cu, m.Ward, body, ward_id, "ward")


@router.post("/config/categories", status_code=201)
def add_category(body: CategoryIn, request: Request, cu=Depends(require("config.manage")), db: Session = Depends(get_db)):
    return _upsert(db, request, cu, m.IncidentCategory, body, None, "category")


@router.put("/config/categories/{cat_id}")
def edit_category(cat_id: int, body: CategoryIn, request: Request, cu=Depends(require("config.manage")), db: Session = Depends(get_db)):
    return _upsert(db, request, cu, m.IncidentCategory, body, cat_id, "category")


@router.post("/config/organisations", status_code=201)
def add_org(body: OrganisationIn, request: Request, cu=Depends(require("config.manage")), db: Session = Depends(get_db)):
    return _upsert(db, request, cu, m.ExternalOrganisation, body, None, "organisation")


@router.put("/config/organisations/{org_id}")
def edit_org(org_id: int, body: OrganisationIn, request: Request, cu=Depends(require("config.manage")), db: Session = Depends(get_db)):
    return _upsert(db, request, cu, m.ExternalOrganisation, body, org_id, "organisation")


# --- users ---

class UserOut(Out):
    id: int
    username: str
    full_name: str
    phone: str | None
    email: str | None
    active: bool
    roles: list[str]
    created_at: UtcDt


def user_out(u: m.User) -> UserOut:
    return UserOut(id=u.id, username=u.username, full_name=u.full_name, phone=u.phone, email=u.email,
                   active=u.active, roles=u.role_codes, created_at=u.created_at)


class UserIn(BaseModel):
    username: str = Field(min_length=3, max_length=80, pattern=r"^[A-Za-z0-9_.-]+$")
    full_name: str = Field(min_length=2, max_length=200)
    phone: str | None = Field(default=None, pattern=r"^\+?[0-9 ]{7,20}$")
    email: str | None = Field(default=None, max_length=200)
    roles: list[str] = Field(min_length=1)
    temporary_password: str = Field(min_length=10, max_length=200)


class UserPatch(BaseModel):
    full_name: str | None = Field(default=None, min_length=2, max_length=200)
    phone: str | None = Field(default=None, pattern=r"^\+?[0-9 ]{7,20}$")
    email: str | None = None
    roles: list[str] | None = None
    active: bool | None = None
    reset_password: str | None = Field(default=None, min_length=10, max_length=200)
    reason: str | None = Field(default=None, max_length=500)


@router.get("/users", response_model=list[UserOut])
def list_users(cu=Depends(require("users.manage")), db: Session = Depends(get_db)):
    users = db.scalars(select(m.User).where(m.User.council_id == cu.council_id).order_by(m.User.full_name)).all()
    return [user_out(u) for u in users]


@router.get("/users/directory")
def directory(cu=Depends(require("teams.manage", "incidents.assign")), db: Session = Depends(get_db)):
    """Names and roles only, for choosing team leaders and members."""
    users = db.scalars(select(m.User).where(m.User.council_id == cu.council_id, m.User.active.is_(True)).order_by(m.User.full_name)).all()
    return [{"id": u.id, "full_name": u.full_name, "roles": u.role_codes} for u in users]


@router.post("/users", response_model=UserOut, status_code=201)
def create_user(body: UserIn, request: Request, cu=Depends(require("users.manage")), db: Session = Depends(get_db)):
    if bad := [r for r in body.roles if r not in ROLES]:
        raise HTTPException(422, f"Unknown role(s): {', '.join(bad)}")
    if problem := validate_password_strength(body.temporary_password):
        raise HTTPException(422, problem)
    username = body.username.lower()
    if db.scalar(select(m.User.id).where(m.User.council_id == cu.council_id, m.User.username == username)):
        raise HTTPException(409, "That username is already taken")
    u = m.User(council_id=cu.council_id, username=username, full_name=body.full_name, phone=body.phone, email=body.email,
               password_hash=hash_password(body.temporary_password), must_change_password=True)
    for r in sorted(set(body.roles)):
        u.roles.append(m.UserRole(role_code=r, assigned_by=cu.id))
    db.add(u)
    db.flush()
    audit.record(db, council_id=cu.council_id, user_id=cu.id, action="USER_CREATE", entity="user", entity_id=u.id,
                 new={"username": username, "roles": sorted(set(body.roles))}, request=request)
    db.commit()
    return user_out(u)


@router.patch("/users/{user_id}", response_model=UserOut)
def patch_user(user_id: int, body: UserPatch, request: Request, cu=Depends(require("users.manage")), db: Session = Depends(get_db)):
    u = db.get(m.User, user_id)
    if u is None or u.council_id != cu.council_id:
        raise HTTPException(404, "User not found")
    old = {"roles": u.role_codes, "active": u.active}
    if body.roles is not None:
        if bad := [r for r in body.roles if r not in ROLES]:
            raise HTTPException(422, f"Unknown role(s): {', '.join(bad)}")
        if u.id == cu.id and "SYSTEM_ADMIN" not in body.roles:
            raise HTTPException(422, "You cannot remove your own administrator role")
        u.roles.clear()
        db.flush()
        for r in sorted(set(body.roles)):
            u.roles.append(m.UserRole(role_code=r, assigned_by=cu.id))
    if body.active is not None:
        if u.id == cu.id and not body.active:
            raise HTTPException(422, "You cannot deactivate your own account")
        u.active = body.active
    for k in ("full_name", "phone", "email"):
        if (v := getattr(body, k)) is not None:
            setattr(u, k, v)
    if body.reset_password:
        if problem := validate_password_strength(body.reset_password):
            raise HTTPException(422, problem)
        u.password_hash = hash_password(body.reset_password)
        u.must_change_password = True
    if body.active is False or body.reset_password or body.roles is not None:
        for t in db.scalars(select(m.RefreshToken).where(m.RefreshToken.user_id == u.id, m.RefreshToken.revoked_at.is_(None))):
            from ..db import utcnow
            t.revoked_at = utcnow()
    db.flush()
    audit.record(db, council_id=cu.council_id, user_id=cu.id, action="USER_UPDATE", entity="user", entity_id=u.id,
                 old=old, new={"roles": u.role_codes, "active": u.active, "password_reset": bool(body.reset_password)},
                 reason=body.reason, request=request)
    db.commit()
    return user_out(u)


# --- audit log ---

@router.get("/audit")
def audit_log(page: int = 1, page_size: int = 50, entity: str | None = None, entity_id: str | None = None,
              action: str | None = None, cu=Depends(require("audit.view")), db: Session = Depends(get_db)):
    page_size = max(1, min(page_size, 200))
    q = select(m.AuditLog).where(m.AuditLog.council_id == cu.council_id)
    if entity:
        q = q.where(m.AuditLog.entity == entity)
    if entity_id:
        q = q.where(m.AuditLog.entity_id == entity_id)
    if action:
        q = q.where(m.AuditLog.action == action)
    total = db.scalar(select(func.count()).select_from(q.subquery()))
    rows = db.scalars(q.order_by(m.AuditLog.id.desc()).offset((page - 1) * page_size).limit(page_size)).all()
    names = dict(db.execute(select(m.User.id, m.User.full_name).where(m.User.council_id == cu.council_id)).all())
    items = [{
        "id": r.id, "user": names.get(r.user_id, "System" if r.user_id is None else f"User {r.user_id}"),
        "action": r.action, "entity": r.entity, "entity_id": r.entity_id, "old_value": r.old_value,
        "new_value": r.new_value, "reason": r.reason, "correlation_id": r.correlation_id,
        "created_at": r.created_at.isoformat(timespec="seconds") + "Z",
    } for r in rows]
    audit.record(db, council_id=cu.council_id, user_id=cu.id, action="AUDIT_VIEW", entity="audit", request=None)
    db.commit()
    return {"items": items, "total": total, "page": page, "page_size": page_size}
