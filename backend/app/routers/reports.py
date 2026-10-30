"""Operational reports. Every figure is computed from stored records; empty data gives zeros, not estimates."""
import csv
import io
from collections import Counter
from datetime import datetime

from fastapi import APIRouter, Depends, Request
from fastapi.responses import StreamingResponse
from sqlalchemy import select
from sqlalchemy.orm import Session

from .. import audit
from .. import models as m
from ..db import get_db, to_utc_naive
from ..deps import CurrentUser, require

router = APIRouter(prefix="/reports", tags=["reports"])

OPEN_EXCLUDED = ("RESOLVED", "CLOSED", "CANCELLED", "COMPLETED", "REJECTED")


def _minutes(pairs):
    values = [(b - a).total_seconds() / 60 for a, b in pairs if a and b and b >= a]
    if not values:
        return {"count": 0, "average_minutes": None, "median_minutes": None}
    values.sort()
    mid = len(values) // 2
    median = values[mid] if len(values) % 2 else (values[mid - 1] + values[mid]) / 2
    return {"count": len(values), "average_minutes": round(sum(values) / len(values), 1), "median_minutes": round(median, 1)}


def _incidents(db, cu, date_from, date_to):
    q = select(m.Incident).where(m.Incident.council_id == cu.council_id)
    if date_from:
        q = q.where(m.Incident.received_at >= to_utc_naive(date_from))
    if date_to:
        q = q.where(m.Incident.received_at <= to_utc_naive(date_to))
    return db.scalars(q).all()


@router.get("/summary")
def summary(date_from: datetime | None = None, date_to: datetime | None = None,
            cu: CurrentUser = Depends(require("reports.view")), db: Session = Depends(get_db)):
    incs = _incidents(db, cu, date_from, date_to)
    ids = [i.id for i in incs]
    by = lambda f: dict(Counter(f(i) for i in incs).most_common())  # noqa: E731
    assignments = db.scalars(select(m.Assignment).where(m.Assignment.incident_id.in_(ids))).all() if ids else []
    referrals = db.scalars(select(m.Referral).where(m.Referral.incident_id.in_(ids))).all() if ids else []
    detections = db.scalars(select(m.DetectionEvent).where(m.DetectionEvent.council_id == cu.council_id)).all()
    if date_from:
        detections = [d for d in detections if d.detected_at >= to_utc_naive(date_from)]
    if date_to:
        detections = [d for d in detections if d.detected_at <= to_utc_naive(date_to)]
    allocations = db.scalars(select(m.ResourceAllocation).where(m.ResourceAllocation.incident_id.in_(ids))).all() if ids else []
    resources = db.scalars(select(m.Resource).where(m.Resource.council_id == cu.council_id)).all()
    sync = db.scalars(select(m.SyncRecord).where(m.SyncRecord.council_id == cu.council_id)).all()
    emergencies = [i for i in incs if i.kind == "EMERGENCY"]
    return {
        "period": {"from": date_from.isoformat() if date_from else None, "to": date_to.isoformat() if date_to else None},
        "totals": {
            "incidents": len(incs),
            "emergencies": len(emergencies),
            "service_requests": len(incs) - len(emergencies),
            "open": sum(1 for i in incs if i.status not in OPEN_EXCLUDED),
            "awaiting_acknowledgement": sum(1 for i in incs if i.status == "AWAITING_REVIEW"),
            "critical_or_high_open": sum(1 for i in incs if i.status not in OPEN_EXCLUDED and (i.priority or i.suggested_priority) in ("CRITICAL", "HIGH")),
            "in_progress": sum(1 for i in incs if i.status in ("ASSIGNED", "ACCEPTED", "DISPATCHED", "ON_SCENE", "ASSISTANCE_REQUESTED", "WORK_SCHEDULED", "IN_PROGRESS")),
            "referred_externally": len({r.incident_id for r in referrals}),
            "resolved": sum(1 for i in incs if i.status in ("RESOLVED", "CLOSED", "COMPLETED")),
            "demo_records": sum(1 for i in incs if i.is_demo),
        },
        "by_category": by(lambda i: i.category.name),
        "by_ward": by(lambda i: i.ward.name if i.ward else "Not recorded"),
        "by_month": dict(sorted(Counter(i.received_at.strftime("%Y-%m") for i in incs).items())),
        "by_priority": by(lambda i: i.priority or (f"{i.suggested_priority} (suggested)" if i.suggested_priority else "Not assessed")),
        "by_status": by(lambda i: i.status),
        "by_source": by(lambda i: i.source),
        "timings": {
            # Submission (server receipt) to acknowledgement by a coordinator.
            "acknowledgement": _minutes((i.received_at, i.acknowledged_at) for i in emergencies),
            # Assignment created to team arrival on scene.
            "assignment_to_arrival": _minutes((a.created_at, a.arrived_at) for a in assignments),
            # Submission to resolution.
            "resolution": _minutes((i.received_at, i.resolved_at) for i in incs),
        },
        "assignments": {
            "total": len(assignments),
            "by_status": dict(Counter(a.status for a in assignments)),
            "declined": sum(1 for a in assignments if a.status == "DECLINED"),
            "team_response": _minutes((a.created_at, a.responded_at) for a in assignments),
        },
        "referrals": {"total": len(referrals), "by_acknowledgement": dict(Counter(r.ack_status for r in referrals))},
        "resources": {
            "registered": len(resources),
            "by_status": dict(Counter(r.status for r in resources)),
            "allocations_in_period": len(allocations),
        },
        "accident_detection": {
            "events": len(detections),
            "simulations": sum(1 for d in detections if d.is_simulation),
            "by_outcome": dict(Counter(d.outcome for d in detections)),
            "false_alarms_cancelled": sum(1 for d in detections if d.outcome == "CANCELLED_BY_USER"),
        },
        "synchronisation": {"by_result": dict(Counter(s.result for s in sync))},
    }


@router.get("/incidents.csv")
def export_csv(request: Request, date_from: datetime | None = None, date_to: datetime | None = None,
               cu: CurrentUser = Depends(require("reports.export")), db: Session = Depends(get_db)):
    """Operational export without reporter names or phone numbers."""
    incs = sorted(_incidents(db, cu, date_from, date_to), key=lambda i: i.received_at)
    buf = io.StringIO()
    w = csv.writer(buf)
    w.writerow(["reference", "kind", "category", "status", "priority", "suggested_priority", "source", "ward",
                "landmark", "latitude", "longitude", "received_at_utc", "acknowledged_at_utc", "resolved_at_utc",
                "closed_at_utc", "demo"])
    fmt = lambda d: d.isoformat(timespec="seconds") + "Z" if d else ""  # noqa: E731
    for i in incs:
        w.writerow([i.reference, i.kind, i.category.name, i.status, i.priority or "", i.suggested_priority or "", i.source,
                    i.ward.name if i.ward else "", i.landmark or "", i.latitude or "", i.longitude or "",
                    fmt(i.received_at), fmt(i.acknowledged_at), fmt(i.resolved_at), fmt(i.closed_at), "yes" if i.is_demo else "no"])
    audit.record(db, council_id=cu.council_id, user_id=cu.id, action="DATA_EXPORT", entity="report", entity_id="incidents.csv",
                 new={"rows": len(incs), "from": str(date_from), "to": str(date_to)}, request=request)
    db.commit()
    return StreamingResponse(iter([buf.getvalue()]), media_type="text/csv",
                             headers={"Content-Disposition": "attachment; filename=incidents.csv"})
