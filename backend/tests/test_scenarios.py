"""End-to-end scenarios from the project specification, run against the real API."""
import uuid
from datetime import timedelta

from sqlalchemy import select

from app import models as m
from app.db import utcnow
from app.escalation import run_escalations
from app.notify import SmsProvider, SmsResult, set_sms_provider

API = "/api/v1"


def report(w, headers, **over):
    body = {
        "idempotency_key": uuid.uuid4().hex, "category_id": w["ids"]["cat_rta"],
        "description": "Two cars collided at the bridge", "reported_at": "2026-10-29T06:00:00Z",
        "latitude": -17.65, "longitude": 31.78, "accuracy_m": 12, "location_source": "GPS",
        "injuries": "YES", "immediate_danger": "UNKNOWN",
    }
    body.update(over)
    return w["client"].post(f"{API}/incidents", json=body, headers=headers)


def test_1_community_member_reports_road_accident(world):
    rep = world["login"]("community_reporter")
    r = report(world, rep)
    assert r.status_code == 201, r.text
    inc = r.json()
    assert inc["reference"].startswith("MRDC-")
    assert inc["status"] == "AWAITING_REVIEW"
    assert inc["suggested_priority"] == "HIGH"
    assert inc["priority"] is None  # never final until an officer assesses
    # Original report time preserved; server receipt time recorded separately.
    assert inc["reported_at"] == "2026-10-29T06:00:00Z"
    assert inc["received_at"] != inc["reported_at"]
    # Coordinator was notified; SMS went through the simulated provider only.
    coord = world["login"]("coordinator")
    notes = world["client"].get(f"{API}/notifications", headers=coord).json()["items"]
    assert any(n["event"] == "NEW_INCIDENT" for n in notes)
    # SMS is reserved for critical incidents, assignments and escalations, so none for this one.
    assert not [c for n in notes for c in n["channels"] if c["channel"] == "SMS"]


def test_4_critical_incident_notifies_as_critical(world):
    rep = world["login"]("community_reporter")
    r = report(world, rep, threat_to_life="YES")
    assert r.json()["suggested_priority"] == "CRITICAL"
    coord = world["login"]("coordinator")
    notes = world["client"].get(f"{API}/notifications", headers=coord).json()["items"]
    assert "CRITICAL_INCIDENT" in [n["event"] for n in notes]
    # Development SMS provider: recorded as SIMULATED, never claimed as sent.
    sms = [c for n in notes for c in n["channels"] if c["channel"] == "SMS"]
    assert sms and all(c["status"] == "SIMULATED" for c in sms)
    assert world["sms"].outbox


def test_5_to_7_full_lifecycle(world):
    c, ids = world["client"], world["ids"]
    inc = report(world, world["login"]("community_reporter")).json()
    coord, lead, officer = world["login"]("coordinator"), world["login"]("team_leader"), world["login"]("field_officer")
    url = f"{API}/incidents/{inc['id']}"

    assert c.patch(f"{url}/status", json={"to": "ACKNOWLEDGED"}, headers=coord).status_code == 200
    r = c.post(f"{url}/assess", json={"priority": "HIGH"}, headers=coord)
    assert r.status_code == 200 and r.json()["status"] == "UNDER_ASSESSMENT"
    # Changing an assessed priority requires a reason.
    assert c.post(f"{url}/assess", json={"priority": "CRITICAL"}, headers=coord).status_code == 422
    assert c.post(f"{url}/assess", json={"priority": "CRITICAL", "reason": "Fuel leak"}, headers=coord).status_code == 200
    r = c.post(f"{url}/assignments", json={"team_id": ids["team"], "instructions": "Close the road"}, headers=coord)
    assert r.status_code == 201, r.text
    aid = r.json()["assignment_id"]

    # The team sees it in its inbox and accepts.
    inbox = c.get(f"{API}/assignments/mine", headers=lead).json()
    assert [x["assignment_id"] for x in inbox] == [aid]
    assert c.post(f"{API}/assignments/{aid}/respond", json={"accept": True}, headers=lead).json()["status"] == "ACCEPTED"
    # Accepting is not resolving.
    detail = c.get(url, headers=coord).json()
    assert detail["status"] == "ACCEPTED" and detail["resolved_at"] is None

    # Field officer (team member) records departure and arrival.
    assert c.patch(f"{url}/status", json={"to": "DISPATCHED"}, headers=officer).status_code == 200
    assert c.patch(f"{url}/status", json={"to": "ON_SCENE"}, headers=officer).status_code == 200
    assert c.post(f"{url}/updates", json={"kind": "ACTION", "text": "Road closed, casualties assisted"}, headers=officer).status_code == 201
    # The team cannot resolve; only a coordinator can.
    assert c.patch(f"{url}/status", json={"to": "RESPONSE_COMPLETED"}, headers=lead).status_code == 200
    assert c.patch(f"{url}/status", json={"to": "RESOLVED"}, headers=lead).status_code in (403, 409)
    assert c.patch(f"{url}/status", json={"to": "RESOLVED"}, headers=coord).status_code == 200
    assert c.patch(f"{url}/status", json={"to": "CLOSED"}, headers=coord).status_code == 200

    detail = c.get(url, headers=coord).json()
    a = detail["assignments"][0]
    assert a["departed_at"] and a["arrived_at"] and a["completed_at"] and a["status"] == "COMPLETED"
    statuses = [h["to"] for h in detail["status_history"]]
    assert statuses == ["SUBMITTED", "AWAITING_REVIEW", "ACKNOWLEDGED", "UNDER_ASSESSMENT", "ASSIGNED", "ACCEPTED",
                        "DISPATCHED", "ON_SCENE", "RESPONSE_COMPLETED", "RESOLVED", "CLOSED"]
    assert [x["new"] for x in detail["assessments"]] == ["HIGH", "CRITICAL"]
    # Every change is audited.
    with world["db"]() as db:
        actions = set(db.scalars(select(m.AuditLog.action).where(m.AuditLog.entity_id == str(inc["id"]))))
    assert {"INCIDENT_CREATE", "STATUS_CHANGE", "PRIORITY_CHANGE", "ASSIGN", "RESPONSE_UPDATE"} <= actions


def test_arbitrary_status_change_is_rejected(world):
    inc = report(world, world["login"]("community_reporter")).json()
    r = world["client"].patch(f"{API}/incidents/{inc['id']}/status", json={"to": "RESOLVED"}, headers=world["login"]("coordinator"))
    assert r.status_code == 409


def test_concurrent_update_is_not_silently_overwritten(world):
    c = world["client"]
    inc = report(world, world["login"]("community_reporter")).json()
    coord = world["login"]("coordinator")
    url = f"{API}/incidents/{inc['id']}/status"
    assert c.patch(url, json={"to": "ACKNOWLEDGED", "expected_version": inc["version"]}, headers=coord).status_code == 200
    stale = c.patch(url, json={"to": "CANCELLED", "note": "dup", "expected_version": inc["version"]}, headers=coord)
    assert stale.status_code == 409


def test_8_referral_is_recorded_without_claiming_acceptance(world):
    c, ids = world["client"], world["ids"]
    inc = report(world, world["login"]("community_reporter")).json()
    coord = world["login"]("coordinator")
    c.patch(f"{API}/incidents/{inc['id']}/status", json={"to": "ACKNOWLEDGED"}, headers=coord)
    r = c.post(f"{API}/incidents/{inc['id']}/referrals", json={
        "organisation_id": ids["org"], "method": "PHONE", "info_shared": "Location and number of casualties"}, headers=coord)
    assert r.status_code == 201
    d = c.get(f"{API}/incidents/{inc['id']}", headers=coord).json()
    assert d["status"] == "REFERRED_EXTERNALLY"
    assert d["referrals"][0]["ack_status"] == "NOT_CONFIRMED"


def test_9_unacknowledged_incident_escalates_once_per_level(world):
    inc = report(world, world["login"]("community_reporter"), threat_to_life="YES").json()  # CRITICAL: 5 min target
    with world["db"]() as db:
        later = utcnow() + timedelta(minutes=6)
        assert len(run_escalations(db, now=later)) == 1
        assert run_escalations(db, now=later) == []  # no repeated escalation
        assert len(run_escalations(db, now=later + timedelta(minutes=5))) == 1  # level 2
        levels = db.scalars(select(m.EscalationEvent.level).where(m.EscalationEvent.incident_id == inc["id"])).all()
    assert sorted(levels) == [1, 2]
    mgr_notes = world["client"].get(f"{API}/notifications", headers=world["login"]("management")).json()["items"]
    assert any(n["event"] == "ESCALATION" for n in mgr_notes)  # level 2 includes management


def test_unaccepted_assignment_escalates(world):
    c, ids = world["client"], world["ids"]
    inc = report(world, world["login"]("community_reporter")).json()
    coord = world["login"]("coordinator")
    c.patch(f"{API}/incidents/{inc['id']}/status", json={"to": "ACKNOWLEDGED"}, headers=coord)
    c.post(f"{API}/incidents/{inc['id']}/assignments", json={"team_id": ids["team"]}, headers=coord)
    with world["db"]() as db:
        events = run_escalations(db, now=utcnow() + timedelta(minutes=20))
    assert any(e.assignment_id is not None for e in events)


def test_10_11_14_offline_retry_does_not_duplicate(world):
    rep = world["login"]("community_reporter")
    key = uuid.uuid4().hex
    first = report(world, rep, idempotency_key=key, local_id="local-1")
    retry = report(world, rep, idempotency_key=key, local_id="local-1")
    assert first.status_code == 201 and retry.status_code == 200
    assert retry.json()["duplicate"] is True and retry.json()["id"] == first.json()["id"]
    with world["db"]() as db:
        assert db.scalar(select(m.SyncRecord.result).where(m.SyncRecord.result == "DUPLICATE")) == "DUPLICATE"


def test_12_unauthorised_access_is_refused(world):
    c = world["client"]
    inc = report(world, world["login"]("community_reporter")).json()
    # Another community member cannot see it (404 hides that it exists).
    assert c.get(f"{API}/incidents/{inc['id']}", headers=world["login"]("reporter2")).status_code == 404
    assert c.get(f"{API}/incidents", headers=world["login"]("reporter2")).json()["total"] == 0
    # A team leader whose team is not assigned cannot see it.
    assert c.get(f"{API}/incidents/{inc['id']}", headers=world["login"]("otherlead")).status_code == 404
    # Another council's coordinator cannot see it.
    assert c.get(f"{API}/incidents/{inc['id']}", headers=world["login"]("coordinator", "OTHER")).status_code == 404
    # A reporter cannot change status, list users or read the audit log.
    rep = world["login"]("community_reporter")
    assert c.patch(f"{API}/incidents/{inc['id']}/status", json={"to": "ACKNOWLEDGED"}, headers=rep).status_code == 403
    assert c.get(f"{API}/users", headers=rep).status_code == 403
    assert c.get(f"{API}/audit", headers=rep).status_code == 403
    # Management sees the incident but not the reporter's personal details.
    d = c.get(f"{API}/incidents/{inc['id']}", headers=world["login"]("management")).json()
    assert "reporter_phone" not in d
    assert "reporter_phone" in c.get(f"{API}/incidents/{inc['id']}", headers=world["login"]("coordinator")).json()


def test_13_sms_provider_failure_is_recorded_with_one_retry(world):
    class Failing(SmsProvider):
        calls = 0

        def send(self, to, message):
            Failing.calls += 1
            return SmsResult("FAILED", "Insufficient credit")

    set_sms_provider(Failing())
    report(world, world["login"]("community_reporter"), threat_to_life="YES")
    notes = world["client"].get(f"{API}/notifications", headers=world["login"]("coordinator")).json()["items"]
    sms = [ch for n in notes for ch in n["channels"] if ch["channel"] == "SMS"]
    assert [ch["status"] for ch in sms] == ["FAILED", "FAILED"]
    assert Failing.calls == 2


def test_15_unavailable_resource_cannot_be_allocated(world):
    c, ids = world["client"], world["ids"]
    inc = report(world, world["login"]("community_reporter")).json()
    coord = world["login"]("coordinator")
    url = f"{API}/incidents/{inc['id']}/resources"
    assert c.post(url, json={"resource_id": ids["truck2"]}, headers=coord).status_code == 409
    r = c.post(url, json={"resource_id": ids["truck1"]}, headers=coord)
    assert r.status_code == 201
    assert c.post(url, json={"resource_id": ids["truck1"]}, headers=coord).status_code == 409  # already assigned
    assert c.post(f"{API}/allocations/{r.json()['id']}/return", headers=coord).status_code == 200


def test_16_routine_report_escalated_to_emergency(world):
    c, ids = world["client"], world["ids"]
    r = report(world, world["login"]("community_reporter"), category_id=ids["cat_drain"], injuries=None)
    inc = r.json()
    assert inc["kind"] == "ROUTINE" and inc["status"] == "SUBMITTED"
    coord = world["login"]("coordinator")
    r = c.post(f"{API}/incidents/{inc['id']}/escalate-to-emergency",
               json={"reason": "Water now flooding houses", "category_id": ids["cat_rta"]}, headers=coord)
    assert r.status_code == 200
    assert r.json()["kind"] == "EMERGENCY" and r.json()["status"] == "AWAITING_REVIEW"


def test_17_manager_export_is_audited_and_has_no_personal_data(world):
    report(world, world["login"]("community_reporter"))
    r = world["client"].get(f"{API}/reports/incidents.csv", headers=world["login"]("management"))
    assert r.status_code == 200
    assert "Community_Reporter" not in r.text and "+263771234567" not in r.text
    with world["db"]() as db:
        assert db.scalar(select(m.AuditLog.id).where(m.AuditLog.action == "DATA_EXPORT")) is not None
    assert world["client"].get(f"{API}/reports/incidents.csv", headers=world["login"]("community_reporter")).status_code == 403


def test_reports_are_empty_not_invented(world):
    s = world["client"].get(f"{API}/reports/summary", headers=world["login"]("management")).json()
    assert s["totals"]["incidents"] == 0
    assert s["timings"]["acknowledgement"]["average_minutes"] is None


def test_2_3_18_detection_events_are_recorded_and_idempotent(world):
    rep = world["login"]("community_reporter")
    body = {"idempotency_key": uuid.uuid4().hex, "detected_at": "2026-10-29T06:00:00Z", "peak_g": 7.2,
            "outcome": "CANCELLED_BY_USER", "is_simulation": True}
    assert world["client"].post(f"{API}/detection-events", json=body, headers=rep).status_code == 201
    assert world["client"].post(f"{API}/detection-events", json=body, headers=rep).json()["duplicate"] is True
    s = world["client"].get(f"{API}/reports/summary", headers=world["login"]("management")).json()
    assert s["accident_detection"] == {"events": 1, "simulations": 1, "by_outcome": {"CANCELLED_BY_USER": 1},
                                       "false_alarms_cancelled": 1}


def test_declined_assignment_requires_reason(world):
    c, ids = world["client"], world["ids"]
    inc = report(world, world["login"]("community_reporter")).json()
    coord, lead = world["login"]("coordinator"), world["login"]("team_leader")
    c.patch(f"{API}/incidents/{inc['id']}/status", json={"to": "ACKNOWLEDGED"}, headers=coord)
    aid = c.post(f"{API}/incidents/{inc['id']}/assignments", json={"team_id": ids["team"]}, headers=coord).json()["assignment_id"]
    assert c.post(f"{API}/assignments/{aid}/respond", json={"accept": False}, headers=lead).status_code == 422
    r = c.post(f"{API}/assignments/{aid}/respond", json={"accept": False, "reason": "Vehicle broken down"}, headers=lead)
    assert r.json()["incident"]["status"] == "UNDER_ASSESSMENT"
    # Reassignment needs a reason only when a live assignment exists; this one was declined.
    assert c.post(f"{API}/incidents/{inc['id']}/assignments", json={"team_id": ids["other_team"]}, headers=coord).status_code == 201


def test_auth_refresh_rotation_and_deactivation(world):
    c = world["client"]
    r = c.post(f"{API}/auth/login", json={"council_code": "MRDC", "username": "field_officer", "password": "Test-Pass-1234"}).json()
    new = c.post(f"{API}/auth/refresh", json={"refresh_token": r["refresh_token"]})
    assert new.status_code == 200
    assert c.post(f"{API}/auth/refresh", json={"refresh_token": r["refresh_token"]}).status_code == 401  # used once
    admin = world["login"]("system_admin")
    uid = world["ids"]["users"]["FIELD_OFFICER"]
    assert c.patch(f"{API}/users/{uid}", json={"active": False}, headers=admin).status_code == 200
    assert c.post(f"{API}/auth/login", json={"council_code": "MRDC", "username": "field_officer", "password": "Test-Pass-1234"}).status_code == 403
    assert c.post(f"{API}/auth/refresh", json={"refresh_token": new.json()["refresh_token"]}).status_code == 401


def test_login_is_rate_limited(world):
    c = world["client"]
    codes = [c.post(f"{API}/auth/login", json={"council_code": "MRDC", "username": "nobody", "password": "wrong"}).status_code for _ in range(7)]
    assert codes[:5] == [401] * 5 and codes[-1] == 429


def test_validation_requires_some_location(world):
    r = report(world, world["login"]("community_reporter"), latitude=None, longitude=None)
    assert r.status_code == 422
    r = report(world, world["login"]("community_reporter"), latitude=None, longitude=None, landmark="Next to the school")
    assert r.status_code == 201


def test_photo_attachment_round_trip_and_access(world):
    c = world["client"]
    rep = world["login"]("community_reporter")
    inc = report(world, rep).json()
    jpeg = b"\xff\xd8\xff\xe0fake-jpeg-bytes"
    r = c.post(f"{API}/incidents/{inc['id']}/attachments", files={"file": ("p.jpg", jpeg, "image/jpeg")}, headers=rep)
    assert r.status_code == 201, r.text
    got = c.get(f"{API}/attachments/{r.json()['id']}", headers=world["login"]("coordinator"))
    assert got.status_code == 200 and got.content == jpeg
    assert c.get(f"{API}/attachments/{r.json()['id']}", headers=world["login"]("reporter2")).status_code == 404
    bad = c.post(f"{API}/incidents/{inc['id']}/attachments", files={"file": ("x.exe", b"MZ", "application/octet-stream")}, headers=rep)
    assert bad.status_code == 415
