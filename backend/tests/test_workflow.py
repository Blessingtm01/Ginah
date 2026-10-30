from datetime import datetime, timedelta

import pytest

from app.workflow import (
    TransitionError, allowed_manual_targets, check_transition, escalation_level_due, suggest_priority,
)


def test_coordinator_can_acknowledge_but_team_cannot():
    check_transition("EMERGENCY", "AWAITING_REVIEW", "ACKNOWLEDGED", "coordinator")
    with pytest.raises(TransitionError):
        check_transition("EMERGENCY", "AWAITING_REVIEW", "ACKNOWLEDGED", "team")


def test_arbitrary_jump_is_rejected():
    with pytest.raises(TransitionError):
        check_transition("EMERGENCY", "AWAITING_REVIEW", "RESOLVED", "coordinator")


def test_assignment_is_system_only():
    with pytest.raises(TransitionError):
        check_transition("EMERGENCY", "ACKNOWLEDGED", "ASSIGNED", "coordinator")
    check_transition("EMERGENCY", "ACKNOWLEDGED", "ASSIGNED", "system")


def test_dispatched_is_not_resolved():
    assert "RESOLVED" not in allowed_manual_targets("EMERGENCY", "DISPATCHED", {"team", "coordinator"})
    assert allowed_manual_targets("EMERGENCY", "DISPATCHED", {"team"}) == ["ON_SCENE"]


def test_routine_workflow_is_separate():
    assert set(allowed_manual_targets("ROUTINE", "SUBMITTED", {"coordinator"})) == {"UNDER_REVIEW", "REJECTED"}
    with pytest.raises(TransitionError):
        check_transition("ROUTINE", "SUBMITTED", "ACKNOWLEDGED", "coordinator")


@pytest.mark.parametrize("kwargs,expected", [
    (dict(threat_to_life="YES"), "CRITICAL"),
    (dict(injuries="YES", immediate_danger="YES"), "CRITICAL"),
    (dict(injuries="YES"), "HIGH"),
    (dict(affected_people=25), "HIGH"),
    (dict(), "MEDIUM"),
    (dict(may_worsen="YES"), "HIGH"),
    (dict(injuries="UNKNOWN", immediate_danger="NO"), "MEDIUM"),
])
def test_priority_suggestion(kwargs, expected):
    base = dict(category_default="MEDIUM", threat_to_life=None, injuries=None, immediate_danger=None,
                may_worsen=None, affected_people=None)
    base.update(kwargs)
    assert suggest_priority(**base)[0] == expected


def test_escalation_levels_follow_priority_targets():
    start = datetime(2026, 1, 1, 8, 0)
    assert escalation_level_due(start, start + timedelta(minutes=4), "CRITICAL", None) == 0
    assert escalation_level_due(start, start + timedelta(minutes=5), "CRITICAL", None) == 1
    assert escalation_level_due(start, start + timedelta(minutes=11), "CRITICAL", None) == 2
    assert escalation_level_due(start, start + timedelta(hours=5), "CRITICAL", None) == 3  # capped at the last level
    assert escalation_level_due(start, start + timedelta(minutes=30), "LOW", None) == 0
    custom = {"ack_minutes": {"CRITICAL": 1}}
    assert escalation_level_due(start, start + timedelta(minutes=1), "CRITICAL", custom) == 1
