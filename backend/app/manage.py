"""Command-line administration.

    python -m app.manage init-db                       apply database migrations
    python -m app.manage create-council CODE "Name"    register a council and its default categories
    python -m app.manage create-admin CODE USERNAME "Full Name"   (prompts for the password)
    python -m app.manage seed-demo                     create the clearly-labelled DEMO council
"""
import getpass
import os
import sys
from datetime import timedelta

from sqlalchemy import select

from . import models as m
from .db import SessionLocal, utcnow
from .permissions import ROLES
from .security import hash_password, validate_password_strength

EMERGENCY_CATEGORIES = [
    ("Road traffic accident", "HIGH"), ("Building fire", "CRITICAL"), ("Bush or veld fire", "HIGH"),
    ("Flooding", "HIGH"), ("Storm damage", "MEDIUM"), ("Fallen tree", "MEDIUM"), ("Damaged road", "MEDIUM"),
    ("Damaged bridge", "HIGH"), ("Collapsed structure", "CRITICAL"), ("Water supply emergency", "HIGH"),
    ("Sewerage or sanitation emergency", "MEDIUM"), ("Environmental hazard", "MEDIUM"), ("Other emergency", "MEDIUM"),
]
ROUTINE_CATEGORIES = [
    "Damaged road (routine)", "Damaged bridge (routine)", "Blocked drainage", "Fallen tree (routine)",
    "Water supply problem", "Sewerage problem", "Environmental hazard (routine)", "Other infrastructure issue",
]


def init_db():
    from alembic import command
    from alembic.config import Config
    cfg = Config(os.path.join(os.path.dirname(os.path.dirname(__file__)), "alembic.ini"))
    command.upgrade(cfg, "head")
    with SessionLocal() as db:
        ensure_roles(db)
        db.commit()
    print("Database is up to date.")


def ensure_roles(db):
    for code, name in ROLES.items():
        if db.get(m.Role, code) is None:
            db.add(m.Role(code=code, name=name))


def create_council(db, code: str, name: str, is_demo=False) -> m.Council:
    ensure_roles(db)
    c = m.Council(code=code.upper(), name=name, is_demo=is_demo, settings={})
    db.add(c)
    db.flush()
    for cat, prio in EMERGENCY_CATEGORIES:
        db.add(m.IncidentCategory(council_id=c.id, name=cat, kind="EMERGENCY", default_priority=prio))
    for cat in ROUTINE_CATEGORIES:
        db.add(m.IncidentCategory(council_id=c.id, name=cat, kind="ROUTINE", default_priority="LOW"))
    return c


def create_user(db, council: m.Council, username: str, full_name: str, password: str, roles: list[str], phone=None,
                must_change=False) -> m.User:
    u = m.User(council_id=council.id, username=username.lower(), full_name=full_name, phone=phone,
               password_hash=hash_password(password), must_change_password=must_change)
    for r in roles:
        u.roles.append(m.UserRole(role_code=r))
    db.add(u)
    db.flush()
    return u


# (key, category, description, status, priority, suggested, ward index, minutes ago, lat, lon, injuries, fatalities)
DEMO_SAMPLES = [
    ("0001", "Road traffic accident", "Demo: two vehicles collided near the demo business centre.", "AWAITING_REVIEW", None, "HIGH", 0, 25, -17.67, 31.81, "UNKNOWN", None),
    ("0002", "Bush or veld fire", "Demo: grass fire spreading towards homesteads.", "ASSIGNED", "CRITICAL", "HIGH", 1, 90, -17.69, 31.84, "UNKNOWN", None),
    ("0003", "Fallen tree", "Demo: tree blocking the gravel road.", "RESOLVED", "MEDIUM", "MEDIUM", 2, 60 * 26, -17.71, 31.87, None, None),
    ("0004", "Blocked drainage", "Demo: culvert blocked, water pooling on road.", "SUBMITTED", None, None, 3, 60 * 50, -17.73, 31.90, None, None),
    ("0005", "Road traffic accident", "Demo: kombi overturned on the main road; two people died at the scene.", "ACKNOWLEDGED", "CRITICAL", "CRITICAL", 4, 40, -17.62, 31.76, "YES", "YES"),
    ("0006", "Road traffic accident", "Demo: car hit a cyclist, cyclist injured and conscious.", "AWAITING_REVIEW", None, "HIGH", 5, 15, -17.83, 31.95, "YES", "NO"),
    ("0007", "Flooding", "Demo: river burst its banks, low-lying homesteads cut off.", "AWAITING_REVIEW", None, "HIGH", 6, 120, -17.92, 31.70, "NO", "NO"),
    ("0008", "Building fire", "Demo: kitchen hut on fire, family outside and safe.", "AWAITING_REVIEW", None, "CRITICAL", 7, 10, -17.60, 31.86, "NO", "NO"),
    ("0009", "Collapsed structure", "Demo: classroom wall collapsed after heavy rain, nobody inside.", "AWAITING_REVIEW", None, "CRITICAL", 2, 300, -17.78, 32.05, "NO", "NO"),
    ("0010", "Water supply emergency", "Demo: borehole contaminated, residents told not to drink.", "AWAITING_REVIEW", None, "HIGH", 3, 600, -17.98, 31.88, None, None),
]


def add_demo_samples(db, c) -> int:
    """Adds any missing demonstration incidents to the DEMO council (safe to run again)."""
    cats = {x.name: x for x in db.scalars(select(m.IncidentCategory).where(m.IncidentCategory.council_id == c.id))}
    wards = db.scalars(select(m.Ward).where(m.Ward.council_id == c.id).order_by(m.Ward.number)).all()
    users = {u.username: u for u in db.scalars(select(m.User).where(m.User.council_id == c.id))}
    coord, reporter = users.get("coordinator"), users.get("reporter")
    fire = db.scalar(select(m.ResponseTeam).where(m.ResponseTeam.council_id == c.id, m.ResponseTeam.name == "Demo Fire Team"))
    have = set(db.scalars(select(m.Incident.idempotency_key).where(m.Incident.council_id == c.id)))
    now = utcnow()
    added = 0
    for key, cat, desc, status, prio, sugg, w, minutes_ago, lat, lon, injuries, fatalities in DEMO_SAMPLES:
        if f"demo-seed-{key}" in have or cat not in cats or not wards or reporter is None:
            continue
        t = now - timedelta(minutes=minutes_ago)
        category = cats[cat]
        inc = m.Incident(council_id=c.id, reference=f"TMP-DEMO-{key}", kind=category.kind, category_id=category.id,
                         status=status, priority=prio, suggested_priority=sugg, source="APP", reported_at=t, received_at=t,
                         latitude=lat, longitude=lon, accuracy_m=15, location_source="GPS",
                         ward_id=wards[w % len(wards)].id, description=desc, reporter_id=reporter.id,
                         reporter_name=reporter.full_name, reporter_phone=reporter.phone, idempotency_key=f"demo-seed-{key}",
                         is_demo=True, injuries=injuries, fatalities=fatalities)
        if status in ("ACKNOWLEDGED", "ASSIGNED", "RESOLVED"):
            inc.acknowledged_at = t + timedelta(minutes=4)
        if status == "RESOLVED":
            inc.resolved_at = t + timedelta(hours=3)
        db.add(inc)
        db.flush()
        inc.reference = f"DEMO-{t:%Y}-{inc.id:06d}"
        db.add(m.IncidentStatusHistory(incident_id=inc.id, from_status=None, to_status=status,
                                       user_id=coord.id if coord else None, note="Demonstration record created by seed-demo"))
        if status == "ASSIGNED" and fire is not None:
            db.add(m.Assignment(incident_id=inc.id, team_id=fire.id, assigned_by=coord.id, instructions="Demo assignment",
                                ack_due_at=now + timedelta(minutes=5)))
            fire.availability = "AWAITING_CONFIRMATION"
        added += 1
    return added


def seed_demo():
    """Demonstration data only. Names are generic and the council is flagged as DEMO everywhere."""
    with SessionLocal() as db:
        existing = db.scalar(select(m.Council).where(m.Council.code == "DEMO"))
        if existing:
            added = add_demo_samples(db, existing)
            db.commit()
            print(f"The DEMO council already exists. {added} new demonstration incident(s) added.")
            return
        c = create_council(db, "DEMO", "Murewa RDC – DEMONSTRATION DATA", is_demo=True)
        c.phone = "+263 000 000 000 (demo)"
        c.settings = {"sms_enabled": True}  # SMS go through the 'log' provider: simulated, not sent
        wards = [m.Ward(council_id=c.id, name=f"Demo Ward {n}", number=n) for n in range(1, 9)]
        db.add_all(wards)
        for name, kind in [("Demo Police Post", "Police"), ("Demo District Hospital", "Health facility / ambulance"),
                           ("Demo Civil Protection Unit", "Civil Protection")]:
            db.add(m.ExternalOrganisation(council_id=c.id, name=name, org_type=kind, phone="+263 000 000 000"))
        pw = "Demo-Pass-2026"
        admin = create_user(db, c, "admin", "Demo Administrator", pw, ["SYSTEM_ADMIN"], "+263770000001")
        coord = create_user(db, c, "coordinator", "Demo Coordinator", pw, ["COORDINATOR"], "+263770000002")
        create_user(db, c, "manager", "Demo Manager", pw, ["MANAGEMENT"], "+263770000003")
        roads_lead = create_user(db, c, "roadslead", "Demo Roads Team Leader", pw, ["TEAM_LEADER"], "+263770000004")
        fire_lead = create_user(db, c, "firelead", "Demo Fire Team Leader", pw, ["TEAM_LEADER"], "+263770000005")
        officer = create_user(db, c, "officer", "Demo Field Officer", pw, ["FIELD_OFFICER"], "+263770000006")
        reporter = create_user(db, c, "reporter", "Demo Community Reporter", pw, ["COMMUNITY_REPORTER"], "+263770000007")
        create_user(db, c, "auditor", "Demo Auditor", pw, ["AUDITOR"])
        roads = m.ResponseTeam(council_id=c.id, name="Demo Roads Team", category="Roads and infrastructure",
                               leader_id=roads_lead.id, contact_phone="+263770000004", operational_area="Demo Wards 1–4")
        roads.members.append(m.TeamMember(user_id=officer.id))
        fire = m.ResponseTeam(council_id=c.id, name="Demo Fire Team", category="Fire and rescue",
                              leader_id=fire_lead.id, contact_phone="+263770000005", operational_area="All demo wards")
        drm = m.ResponseTeam(council_id=c.id, name="Demo Disaster-Risk Team", category="Disaster-risk management",
                             operational_area="All demo wards", availability="OFF_DUTY")
        db.add_all([roads, fire, drm])
        db.add_all([
            m.Resource(council_id=c.id, name="Demo Truck 1", category="Vehicle", asset_number="DEMO-V-001", department="Works", status="AVAILABLE"),
            m.Resource(council_id=c.id, name="Demo Pickup 2", category="Vehicle", asset_number="DEMO-V-002", department="Works", status="UNDER_MAINTENANCE"),
            m.Resource(council_id=c.id, name="Demo Water Bowser", category="Firefighting equipment", asset_number="DEMO-F-001", status="AVAILABLE"),
            m.Resource(council_id=c.id, name="Demo Chainsaw", category="Tools", asset_number="DEMO-T-001", status="AVAILABLE"),
            m.Resource(council_id=c.id, name="Demo Radio Set", category="Communication device", status="AVAILABLE"),
        ])
        db.flush()
        add_demo_samples(db, c)
        db.commit()
        print("DEMO council created. Council code: DEMO. Password for every demo account:", pw)
        print("Accounts: admin, coordinator, manager, roadslead, firelead, officer, reporter, auditor")
        _ = admin


def main(argv):
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__)
        return
    cmd = argv[0]
    if cmd == "init-db":
        init_db()
    elif cmd == "seed-demo":
        seed_demo()
    elif cmd == "create-council" and len(argv) == 3:
        with SessionLocal() as db:
            c = create_council(db, argv[1], argv[2])
            db.commit()
            print(f"Council {c.code} created with default categories.")
    elif cmd == "create-admin" and len(argv) == 4:
        with SessionLocal() as db:
            c = db.scalar(select(m.Council).where(m.Council.code == argv[1].upper()))
            if c is None:
                sys.exit("Unknown council code")
            pw = getpass.getpass("Password: ")
            if problem := validate_password_strength(pw):
                sys.exit(problem)
            if pw != getpass.getpass("Repeat password: "):
                sys.exit("Passwords do not match")
            create_user(db, c, argv[2], argv[3], pw, ["SYSTEM_ADMIN"])
            db.commit()
            print("Administrator created.")
    else:
        print(__doc__)
        sys.exit(1)


if __name__ == "__main__":
    main(sys.argv[1:])
