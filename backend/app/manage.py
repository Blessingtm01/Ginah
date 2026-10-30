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


def seed_demo():
    """Demonstration data only. Names are generic and the council is flagged as DEMO everywhere."""
    with SessionLocal() as db:
        if db.scalar(select(m.Council).where(m.Council.code == "DEMO")):
            print("The DEMO council already exists.")
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
        cats = {x.name: x for x in db.scalars(select(m.IncidentCategory).where(m.IncidentCategory.council_id == c.id))}
        now = utcnow()
        samples = [
            ("Road traffic accident", "Demo: two vehicles collided near the demo business centre.", "AWAITING_REVIEW", None, "HIGH", 0, 25),
            ("Bush or veld fire", "Demo: grass fire spreading towards homesteads.", "ASSIGNED", "CRITICAL", "HIGH", 1, 90),
            ("Fallen tree", "Demo: tree blocking the gravel road.", "RESOLVED", "MEDIUM", "MEDIUM", 2, 60 * 26),
            ("Blocked drainage", "Demo: culvert blocked, water pooling on road.", "SUBMITTED", None, None, 3, 60 * 50),
        ]
        for n, (cat, desc, status, prio, sugg, w, minutes_ago) in enumerate(samples, start=1):
            t = now - timedelta(minutes=minutes_ago)
            category = cats[cat]
            inc = m.Incident(council_id=c.id, reference=f"DEMO-{t:%Y}-{n:06d}", kind=category.kind, category_id=category.id,
                             status=status, priority=prio, suggested_priority=sugg, source="APP", reported_at=t, received_at=t,
                             latitude=-17.65 - 0.02 * n, longitude=31.78 + 0.03 * n, accuracy_m=15, location_source="GPS",
                             ward_id=wards[w].id, description=desc, reporter_id=reporter.id,
                             reporter_name=reporter.full_name, reporter_phone=reporter.phone, idempotency_key=f"demo-seed-{n:04d}",
                             is_demo=True, injuries="UNKNOWN")
            if status in ("ASSIGNED", "RESOLVED"):
                inc.acknowledged_at = t + timedelta(minutes=4)
            if status == "RESOLVED":
                inc.resolved_at = t + timedelta(hours=3)
            db.add(inc)
            db.flush()
            db.add(m.IncidentStatusHistory(incident_id=inc.id, from_status=None, to_status=status, user_id=coord.id,
                                           note="Demonstration record created by seed-demo"))
            if status == "ASSIGNED":
                db.add(m.Assignment(incident_id=inc.id, team_id=fire.id, assigned_by=coord.id, instructions="Demo assignment",
                                    ack_due_at=now + timedelta(minutes=5)))
                fire.availability = "AWAITING_CONFIRMATION"
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
