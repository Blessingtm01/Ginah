"""Detects overdue acknowledgements and escalates them according to council rules."""
import logging
import threading
from datetime import datetime

from sqlalchemy import select
from sqlalchemy.orm import Session

from . import audit
from . import models as m
from .db import utcnow
from .notify import notify, users_with_roles
from .workflow import council_setting, escalation_level_due

log = logging.getLogger("accisafe.escalation")


def _already(db: Session, incident_id: int, assignment_id: int | None, level: int) -> bool:
    q = select(m.EscalationEvent.id).where(m.EscalationEvent.incident_id == incident_id, m.EscalationEvent.level == level)
    q = q.where(m.EscalationEvent.assignment_id.is_(None) if assignment_id is None else m.EscalationEvent.assignment_id == assignment_id)
    return db.scalar(q) is not None


def run_escalations(db: Session, now: datetime | None = None, council_id: int | None = None) -> list[m.EscalationEvent]:
    """Creates any escalation events that are due. Safe to call repeatedly: each level fires once."""
    now = now or utcnow()
    created: list[m.EscalationEvent] = []
    councils = db.scalars(select(m.Council) if council_id is None else select(m.Council).where(m.Council.id == council_id)).all()
    for council in councils:
        levels = council_setting(council.settings, "escalation_levels")

        # 1. Emergency incidents nobody has acknowledged.
        waiting = db.scalars(select(m.Incident).where(
            m.Incident.council_id == council.id, m.Incident.kind == "EMERGENCY", m.Incident.status == "AWAITING_REVIEW"
        )).all()
        for inc in waiting:
            prio = inc.priority or inc.suggested_priority
            due = escalation_level_due(inc.received_at, now, prio, council.settings)
            for level in range(1, due + 1):
                if _already(db, inc.id, None, level):
                    continue
                created.append(_escalate(db, council, inc, None, level, levels[level - 1],
                                         f"Incident {inc.reference} not acknowledged since {inc.received_at:%H:%M} UTC"))

        # 2. Assignments the team has not accepted or declined.
        pending = db.scalars(select(m.Assignment).join(m.Incident).where(
            m.Incident.council_id == council.id, m.Assignment.status == "PENDING"
        )).all()
        for a in pending:
            inc = db.get(m.Incident, a.incident_id)
            due = escalation_level_due(a.created_at, now, inc.priority or inc.suggested_priority, council.settings)
            for level in range(1, due + 1):
                if _already(db, inc.id, a.id, level):
                    continue
                created.append(_escalate(db, council, inc, a, level, levels[level - 1],
                                         f"{a.team.name} has not responded to the assignment for {inc.reference}"))
    db.commit()
    return created


def _escalate(db, council, inc, assignment, level, roles, reason) -> m.EscalationEvent:
    recipients = users_with_roles(db, council.id, roles)
    ev = m.EscalationEvent(incident_id=inc.id, assignment_id=assignment.id if assignment else None, level=level,
                           reason=reason, notified_roles=",".join(roles), notified_count=len(recipients))
    db.add(ev)
    db.flush()
    notify(db, council, recipients, event="ESCALATION", incident_id=inc.id,
           title=f"Escalation level {level}: {inc.reference}", body=reason,
           sms_body=f"AcciSafe RDC escalation L{level}: {reason}. Open the app to act.")
    audit.record(db, council_id=council.id, user_id=None, action="ESCALATION", entity="incident",
                 entity_id=inc.id, new={"level": level, "assignment_id": ev.assignment_id}, reason=reason)
    return ev


class EscalationWorker:
    """Background loop that runs the escalation check periodically."""

    def __init__(self, session_factory, interval_seconds: int):
        self.session_factory = session_factory
        self.interval = interval_seconds
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def start(self):
        self._thread = threading.Thread(target=self._loop, name="escalation", daemon=True)
        self._thread.start()

    def stop(self):
        self._stop.set()

    def _loop(self):
        while not self._stop.wait(self.interval):
            try:
                with self.session_factory() as db:
                    events = run_escalations(db)
                    if events:
                        log.info("Created %d escalation event(s)", len(events))
            except Exception:
                log.exception("Escalation check failed")
