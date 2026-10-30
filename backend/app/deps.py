"""Request dependencies: the authenticated user, permission checks and incident visibility."""
from dataclasses import dataclass, field

import jwt
from fastapi import Depends, HTTPException, Request, status
from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from . import models as m
from .db import get_db
from .permissions import permissions_for


@dataclass
class CurrentUser:
    user: m.User
    roles: list[str]
    perms: set[str] = field(default_factory=set)

    @property
    def id(self) -> int:
        return self.user.id

    @property
    def council_id(self) -> int:
        return self.user.council_id

    def can(self, permission: str) -> bool:
        return permission in self.perms


def current_user(request: Request, db: Session = Depends(get_db)) -> CurrentUser:
    header = request.headers.get("Authorization", "")
    if not header.startswith("Bearer "):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Not signed in")
    from .security import decode_access_token
    try:
        payload = decode_access_token(header[7:])
    except jwt.ExpiredSignatureError:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Session expired")
    except jwt.InvalidTokenError:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid session")
    user = db.get(m.User, int(payload["sub"]))
    if user is None or not user.active:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Account is not active")
    # Roles are re-read from the database so that removed roles take effect immediately.
    roles = user.role_codes
    return CurrentUser(user=user, roles=roles, perms=permissions_for(roles))


def require(*permissions: str):
    """Dependency: the user must hold at least one of the given permissions."""
    def check(cu: CurrentUser = Depends(current_user)) -> CurrentUser:
        if not any(cu.can(p) for p in permissions):
            raise HTTPException(status.HTTP_403_FORBIDDEN, "You do not have permission for this action")
        return cu
    return check


def user_team_ids(db: Session, user_id: int) -> set[int]:
    member = db.scalars(select(m.TeamMember.team_id).where(m.TeamMember.user_id == user_id)).all()
    led = db.scalars(select(m.ResponseTeam.id).where(m.ResponseTeam.leader_id == user_id)).all()
    return set(member) | set(led)


LIVE_ASSIGNMENT = ("PENDING", "ACCEPTED", "COMPLETED")


def visible_incidents_filter(db: Session, cu: CurrentUser):
    """SQL condition limiting incidents to what this user may see within their council."""
    conds = []
    if cu.can("incidents.view_all"):
        return m.Incident.council_id == cu.council_id
    if cu.can("incidents.view_assigned"):
        teams = user_team_ids(db, cu.id)
        if teams:
            assigned = select(m.Assignment.incident_id).where(
                m.Assignment.team_id.in_(teams), m.Assignment.status.in_(LIVE_ASSIGNMENT)
            )
            conds.append(m.Incident.id.in_(assigned))
    # Everyone may see what they reported themselves.
    conds.append(m.Incident.reporter_id == cu.id)
    return (m.Incident.council_id == cu.council_id) & or_(*conds)


def load_incident(db: Session, cu: CurrentUser, incident_id: int) -> m.Incident:
    inc = db.scalar(select(m.Incident).where(m.Incident.id == incident_id, visible_incidents_filter(db, cu)))
    if inc is None:
        # 404 rather than 403 so that the existence of other incidents is not revealed.
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Incident not found")
    return inc


def team_actor_for(db: Session, cu: CurrentUser, inc: m.Incident) -> m.Assignment | None:
    """The live assignment through which this user acts as the responding team, if any."""
    teams = user_team_ids(db, cu.id)
    if not teams or not cu.can("incidents.update_field"):
        return None
    wanted = ("ACCEPTED",) if inc.kind == "EMERGENCY" else ("PENDING", "ACCEPTED")
    return db.scalar(
        select(m.Assignment).where(
            m.Assignment.incident_id == inc.id, m.Assignment.team_id.in_(teams), m.Assignment.status.in_(wanted)
        ).order_by(m.Assignment.id.desc())
    )
