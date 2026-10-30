import os

os.environ["ACCISAFE_DATABASE_URL"] = "sqlite://"
os.environ.setdefault("ACCISAFE_SECRET_KEY", "test-secret-key-not-for-production-0123456789")

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402
from sqlalchemy import create_engine  # noqa: E402
from sqlalchemy.orm import sessionmaker  # noqa: E402
from sqlalchemy.pool import StaticPool  # noqa: E402

from app import models as m  # noqa: E402
from app.db import Base, get_db  # noqa: E402
from app.main import create_app  # noqa: E402
from app.manage import create_council, create_user  # noqa: E402
from app.notify import LogSmsProvider, set_sms_provider  # noqa: E402
from app.security import login_limiter  # noqa: E402

PASSWORD = "Test-Pass-1234"


@pytest.fixture()
def session_factory():
    engine = create_engine("sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool)
    Base.metadata.create_all(engine)
    return sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


@pytest.fixture()
def world(session_factory):
    """Two councils with one user per role in council A, and a coordinator in council B."""
    login_limiter.reset()
    sms = LogSmsProvider()
    set_sms_provider(sms)
    with session_factory() as db:
        a = create_council(db, "MRDC", "Murewa Rural District Council")
        a.settings = {"sms_enabled": True}
        b = create_council(db, "OTHER", "Other Council")
        db.add(m.Ward(council_id=a.id, name="Ward 1", number=1))
        db.add(m.ExternalOrganisation(council_id=a.id, name="Police Post", org_type="Police"))
        users = {}
        for role in ["SYSTEM_ADMIN", "COORDINATOR", "MANAGEMENT", "TEAM_LEADER", "FIELD_OFFICER", "COMMUNITY_REPORTER", "AUDITOR"]:
            users[role] = create_user(db, a, role.lower(), role.title(), PASSWORD, [role], phone="+263771234567")
        users["REPORTER2"] = create_user(db, a, "reporter2", "Second Reporter", PASSWORD, ["COMMUNITY_REPORTER"])
        users["OTHER_LEADER"] = create_user(db, a, "otherlead", "Other Leader", PASSWORD, ["TEAM_LEADER"])
        users["B_COORD"] = create_user(db, b, "coordinator", "B Coordinator", PASSWORD, ["COORDINATOR"])
        team = m.ResponseTeam(council_id=a.id, name="Roads Team", category="Roads", leader_id=users["TEAM_LEADER"].id)
        team.members.append(m.TeamMember(user_id=users["FIELD_OFFICER"].id))
        other_team = m.ResponseTeam(council_id=a.id, name="Fire Team", category="Fire", leader_id=users["OTHER_LEADER"].id)
        db.add_all([team, other_team])
        db.add(m.Resource(council_id=a.id, name="Truck 1", category="Vehicle", status="AVAILABLE"))
        db.add(m.Resource(council_id=a.id, name="Truck 2", category="Vehicle", status="OUT_OF_SERVICE"))
        db.commit()
        ids = {
            "council_a": a.id, "council_b": b.id, "team": team.id, "other_team": other_team.id,
            "ward": db.query(m.Ward).first().id, "org": db.query(m.ExternalOrganisation).first().id,
            "users": {k: v.id for k, v in users.items()},
            "cat_rta": db.query(m.IncidentCategory).filter_by(council_id=a.id, name="Road traffic accident").one().id,
            "cat_drain": db.query(m.IncidentCategory).filter_by(council_id=a.id, name="Blocked drainage").one().id,
            "truck1": db.query(m.Resource).filter_by(name="Truck 1").one().id,
            "truck2": db.query(m.Resource).filter_by(name="Truck 2").one().id,
        }

    app = create_app(start_worker=False)

    def _db():
        db = session_factory()
        try:
            yield db
        finally:
            db.close()

    app.dependency_overrides[get_db] = _db
    client = TestClient(app)

    def login(username, council="MRDC"):
        r = client.post("/api/v1/auth/login", json={"council_code": council, "username": username, "password": PASSWORD})
        assert r.status_code == 200, r.text
        return {"Authorization": f"Bearer {r.json()['access_token']}"}

    return {"client": client, "ids": ids, "login": login, "sms": sms, "db": session_factory}
