"""Append-only audit trail."""
from fastapi import Request
from sqlalchemy.orm import Session

from . import models as m


def record(
    db: Session, *, council_id: int | None, user_id: int | None, action: str, entity: str,
    entity_id=None, old=None, new=None, reason: str | None = None, request: Request | None = None,
) -> None:
    db.add(m.AuditLog(
        council_id=council_id, user_id=user_id, action=action, entity=entity,
        entity_id=None if entity_id is None else str(entity_id), old_value=old, new_value=new, reason=reason,
        correlation_id=getattr(request.state, "correlation_id", None) if request else None,
        device=(request.headers.get("X-Device") or request.headers.get("User-Agent", ""))[:200] if request else None,
    ))
