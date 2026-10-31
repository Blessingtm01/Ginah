"""Live council map: every signed-in user of a council sees what has been reported across the council area.

The map feed leaves out the reporter's identity and contact details. Photos are served only as images
and only to users of the same council.
"""
import json
import os
from datetime import timedelta

from fastapi import APIRouter, Depends, HTTPException, Response
from sqlalchemy import select
from sqlalchemy.orm import Session

from .. import models as m
from ..db import get_db, utcnow
from ..deps import CurrentUser, current_user
from .incidents import _dt

router = APIRouter(tags=["live map"])

CLOSED = {"RESOLVED", "CLOSED", "CANCELLED", "COMPLETED", "REJECTED", "UNABLE_TO_RESPOND"}

# Map key. Order is the order shown in the legend; "FATAL" overrides the incident type.
LEGEND = [
    {"code": "FATAL", "label": "Fatal accident (deaths reported)", "color": "#D32F2F"},
    {"code": "CRASH_INJURY", "label": "Road accident with injuries", "color": "#FB8C00"},
    {"code": "CRASH", "label": "Road accident (no injuries reported)", "color": "#FDD835"},
    {"code": "FIRE", "label": "Fire (building, bush or veld)", "color": "#8E24AA"},
    {"code": "FLOOD_STORM", "label": "Flooding or storm damage", "color": "#1E88E5"},
    {"code": "STRUCTURE", "label": "Collapsed structure", "color": "#212121"},
    {"code": "ROAD_HAZARD", "label": "Road, bridge, tree or drainage hazard", "color": "#6D4C41"},
    {"code": "WATER_ENV", "label": "Water, sewerage or environmental hazard", "color": "#00ACC1"},
    {"code": "OTHER", "label": "Other incident", "color": "#757575"},
]
_COLOR = {x["code"]: x["color"] for x in LEGEND}

_BOUNDARY_FILE = os.path.join(os.path.dirname(os.path.dirname(__file__)), "geo", "murewa_boundary.json")


def map_type(inc: m.Incident) -> str:
    """Classify an incident for the map key from its category name and the reported casualties."""
    if inc.fatalities == "YES":
        return "FATAL"
    name = inc.category.name.lower()
    if "traffic" in name or "accident" in name or "collision" in name or "crash" in name:
        return "CRASH_INJURY" if inc.injuries == "YES" else "CRASH"
    if "fire" in name:
        return "FIRE"
    if "flood" in name or "storm" in name:
        return "FLOOD_STORM"
    if "collapse" in name:
        return "STRUCTURE"
    if any(w in name for w in ("road", "bridge", "tree", "drain")):
        return "ROAD_HAZARD"
    if any(w in name for w in ("water", "sewer", "sanit", "environment")):
        return "WATER_ENV"
    return "OTHER"


def council_area(council: m.Council) -> dict:
    """The council's boundary: from council settings when configured, otherwise the bundled Murehwa District outline."""
    area = (council.settings or {}).get("map_area")
    if area:
        return area
    with open(_BOUNDARY_FILE, encoding="utf-8") as f:
        return json.load(f)


@router.get("/map/live")
def live_map(days: int = 30, active_only: bool = False, cu: CurrentUser = Depends(current_user), db: Session = Depends(get_db)):
    days = max(1, min(days, 365))
    council = db.get(m.Council, cu.council_id)
    since = utcnow() - timedelta(days=days)
    qry = select(m.Incident).where(m.Incident.council_id == cu.council_id, m.Incident.latitude.is_not(None),
                                   m.Incident.received_at >= since)
    if active_only:
        qry = qry.where(m.Incident.status.not_in(CLOSED))
    rows = db.scalars(qry.order_by(m.Incident.received_at.desc()).limit(500)).all()
    photos: dict[int, list[int]] = {}
    if rows:
        for att_id, inc_id in db.execute(
                select(m.IncidentAttachment.id, m.IncidentAttachment.incident_id)
                .where(m.IncidentAttachment.incident_id.in_([i.id for i in rows]),
                       m.IncidentAttachment.content_type.like("image/%"))
                .order_by(m.IncidentAttachment.id)):
            photos.setdefault(inc_id, []).append(att_id)
    items = []
    for inc in rows:
        t = map_type(inc)
        items.append({
            "id": inc.id, "reference": inc.reference, "kind": inc.kind, "type": t, "color": _COLOR[t],
            "category": inc.category.name, "status": inc.status, "active": inc.status not in CLOSED,
            "priority": inc.priority or inc.suggested_priority, "latitude": inc.latitude, "longitude": inc.longitude,
            "ward": inc.ward.name if inc.ward else None, "landmark": inc.landmark, "description": inc.description,
            "injuries": inc.injuries, "fatalities": inc.fatalities, "affected_people": inc.affected_people,
            "reported_at": _dt(inc.reported_at), "received_at": _dt(inc.received_at), "updated_at": _dt(inc.updated_at),
            "photo_ids": photos.get(inc.id, []), "mine": inc.reporter_id == cu.id, "is_demo": inc.is_demo,
        })
    area = council_area(council)
    return {
        "council": {"name": council.name, "area_name": area.get("name"), "center": area.get("center"),
                    "boundary": area.get("geometry"), "boundary_source": area.get("source")},
        "legend": LEGEND, "days": days, "generated_at": _dt(utcnow()), "incidents": items,
    }


@router.get("/map/photos/{attachment_id}")
def map_photo(attachment_id: int, cu: CurrentUser = Depends(current_user), db: Session = Depends(get_db)):
    a = db.get(m.IncidentAttachment, attachment_id)
    inc = db.get(m.Incident, a.incident_id) if a else None
    if a is None or inc is None or inc.council_id != cu.council_id or not a.content_type.startswith("image/") or a.storage_path != "db":
        raise HTTPException(404, "Photo not found")
    return Response(content=a.content or b"", media_type=a.content_type,
                    headers={"Cache-Control": "private, max-age=86400"})
