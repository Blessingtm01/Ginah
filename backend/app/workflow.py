"""Incident lifecycle, priority suggestion and escalation timing. Pure functions, unit tested."""
from datetime import datetime, timedelta

PRIORITIES = ["CRITICAL", "HIGH", "MEDIUM", "LOW"]
TRI_STATE = {"YES", "NO", "UNKNOWN"}

EMERGENCY_STATUSES = [
    "DRAFT", "SUBMITTED", "AWAITING_REVIEW", "ACKNOWLEDGED", "UNDER_ASSESSMENT", "ASSIGNED",
    "ACCEPTED", "DISPATCHED", "ON_SCENE", "ASSISTANCE_REQUESTED", "REFERRED_EXTERNALLY",
    "RESPONSE_COMPLETED", "RESOLVED", "CLOSED", "CANCELLED", "UNABLE_TO_RESPOND",
]
ROUTINE_STATUSES = [
    "SUBMITTED", "UNDER_REVIEW", "INSPECTION_REQUIRED", "ASSIGNED", "WORK_SCHEDULED",
    "IN_PROGRESS", "COMPLETED", "REJECTED", "CLOSED",
]

# Who may make each transition:
#   "coordinator" - a user with incidents.coordinate, via PATCH /status
#   "team"        - a member of the team holding the active accepted assignment, via PATCH /status
#   "system"      - only as a side effect of a dedicated endpoint (assign, accept, refer...)
C, T, S = "coordinator", "team", "system"

EMERGENCY_TRANSITIONS: dict[tuple[str, str], set[str]] = {
    ("DRAFT", "SUBMITTED"): {S},
    ("SUBMITTED", "AWAITING_REVIEW"): {S},
    ("AWAITING_REVIEW", "ACKNOWLEDGED"): {C},
    ("AWAITING_REVIEW", "CANCELLED"): {C},
    ("ACKNOWLEDGED", "UNDER_ASSESSMENT"): {C},
    ("ACKNOWLEDGED", "ASSIGNED"): {S},
    ("ACKNOWLEDGED", "REFERRED_EXTERNALLY"): {S},
    ("ACKNOWLEDGED", "CANCELLED"): {C},
    ("UNDER_ASSESSMENT", "ASSIGNED"): {S},
    ("UNDER_ASSESSMENT", "REFERRED_EXTERNALLY"): {S},
    ("UNDER_ASSESSMENT", "UNABLE_TO_RESPOND"): {C},
    ("UNDER_ASSESSMENT", "CANCELLED"): {C},
    ("ASSIGNED", "ACCEPTED"): {S},
    ("ASSIGNED", "UNDER_ASSESSMENT"): {S},  # declined or reassignment pending
    ("ASSIGNED", "ASSIGNED"): {S},  # reassigned to another team
    ("ACCEPTED", "DISPATCHED"): {T},
    ("ACCEPTED", "ASSIGNED"): {S},
    ("DISPATCHED", "ON_SCENE"): {T},
    ("ON_SCENE", "ASSISTANCE_REQUESTED"): {T},
    ("ON_SCENE", "RESPONSE_COMPLETED"): {T},
    ("ON_SCENE", "REFERRED_EXTERNALLY"): {S},
    ("ASSISTANCE_REQUESTED", "ON_SCENE"): {T, C},
    ("ASSISTANCE_REQUESTED", "REFERRED_EXTERNALLY"): {S},
    ("ASSISTANCE_REQUESTED", "RESPONSE_COMPLETED"): {T},
    ("REFERRED_EXTERNALLY", "ASSIGNED"): {S},
    ("REFERRED_EXTERNALLY", "RESOLVED"): {C},
    ("REFERRED_EXTERNALLY", "UNABLE_TO_RESPOND"): {C},
    ("RESPONSE_COMPLETED", "RESOLVED"): {C},
    ("RESPONSE_COMPLETED", "ON_SCENE"): {C},  # reopened: work was not actually finished
    ("RESOLVED", "CLOSED"): {C},
    ("UNABLE_TO_RESPOND", "ASSIGNED"): {S},
    ("UNABLE_TO_RESPOND", "CLOSED"): {C},
    ("CANCELLED", "CLOSED"): {C},
}

ROUTINE_TRANSITIONS: dict[tuple[str, str], set[str]] = {
    ("SUBMITTED", "UNDER_REVIEW"): {C},
    ("SUBMITTED", "REJECTED"): {C},
    ("UNDER_REVIEW", "INSPECTION_REQUIRED"): {C},
    ("UNDER_REVIEW", "ASSIGNED"): {S},
    ("UNDER_REVIEW", "REJECTED"): {C},
    ("INSPECTION_REQUIRED", "ASSIGNED"): {S},
    ("INSPECTION_REQUIRED", "REJECTED"): {C},
    ("ASSIGNED", "ASSIGNED"): {S},
    ("ASSIGNED", "UNDER_REVIEW"): {S},
    ("ASSIGNED", "WORK_SCHEDULED"): {T, C},
    ("WORK_SCHEDULED", "IN_PROGRESS"): {T, C},
    ("IN_PROGRESS", "COMPLETED"): {T, C},
    ("COMPLETED", "IN_PROGRESS"): {C},
    ("COMPLETED", "CLOSED"): {C},
    ("REJECTED", "CLOSED"): {C},
}

TERMINAL = {"CLOSED"}
OPEN_EXCLUDED = {"RESOLVED", "CLOSED", "CANCELLED", "COMPLETED", "REJECTED"}
# Statuses from which an incident can be assigned to a team.
ASSIGNABLE = {
    "EMERGENCY": {"ACKNOWLEDGED", "UNDER_ASSESSMENT", "ASSIGNED", "ACCEPTED", "REFERRED_EXTERNALLY", "UNABLE_TO_RESPOND"},
    "ROUTINE": {"UNDER_REVIEW", "INSPECTION_REQUIRED", "ASSIGNED"},
}
REFERRABLE = {"ACKNOWLEDGED", "UNDER_ASSESSMENT", "ON_SCENE", "ASSISTANCE_REQUESTED"}


class TransitionError(ValueError):
    pass


def transitions(kind: str) -> dict[tuple[str, str], set[str]]:
    return EMERGENCY_TRANSITIONS if kind == "EMERGENCY" else ROUTINE_TRANSITIONS


def initial_statuses(kind: str) -> list[str]:
    """Statuses recorded on creation, in order. The last one is the incident's status."""
    return ["SUBMITTED", "AWAITING_REVIEW"] if kind == "EMERGENCY" else ["SUBMITTED"]


def check_transition(kind: str, current: str, target: str, actor: str) -> None:
    allowed = transitions(kind).get((current, target))
    if allowed is None:
        raise TransitionError(f"Cannot change status from {current} to {target}")
    if actor not in allowed:
        raise TransitionError(f"{current} to {target} is not a manual change for this user")


def allowed_manual_targets(kind: str, current: str, actors: set[str]) -> list[str]:
    return [to for (frm, to), who in transitions(kind).items() if frm == current and who & actors]


def suggest_priority(
    *, category_default: str, threat_to_life: str | None, injuries: str | None,
    immediate_danger: str | None, may_worsen: str | None, affected_people: int | None,
) -> tuple[str, list[str]]:
    """Advisory priority. An authorised officer always confirms or changes it."""
    reasons: list[str] = []
    if threat_to_life == "YES":
        return "CRITICAL", ["threat to life reported"]
    if injuries == "YES" and immediate_danger == "YES":
        return "CRITICAL", ["injuries and immediate danger reported"]
    level = PRIORITIES.index(category_default) if category_default in PRIORITIES else 2
    if injuries == "YES":
        level = min(level, 1); reasons.append("injuries reported")
    if immediate_danger == "YES":
        level = min(level, 1); reasons.append("immediate danger reported")
    if affected_people is not None and affected_people >= 10:
        level = min(level, 1); reasons.append(f"{affected_people} people affected")
    if may_worsen == "YES":
        level = max(0, level - 1); reasons.append("situation may worsen")
    if not reasons:
        reasons.append(f"category default ({category_default})")
    return PRIORITIES[level], reasons


DEFAULT_SETTINGS = {
    # Minutes allowed before an unacknowledged incident or assignment escalates.
    "ack_minutes": {"CRITICAL": 5, "HIGH": 15, "MEDIUM": 60, "LOW": 240, "UNASSESSED": 15},
    # Who is notified at each escalation level.
    "escalation_levels": [["COORDINATOR"], ["COORDINATOR", "MANAGEMENT"], ["SYSTEM_ADMIN", "MANAGEMENT"]],
    "sms_events": ["CRITICAL_INCIDENT", "ASSIGNMENT_CREATED", "ESCALATION"],
    "sms_enabled": False,
}


def council_setting(council_settings: dict | None, key: str):
    merged = {**DEFAULT_SETTINGS, **(council_settings or {})}
    return merged[key]


def ack_deadline(start: datetime, priority: str | None, council_settings: dict | None) -> datetime:
    minutes = council_setting(council_settings, "ack_minutes")
    return start + timedelta(minutes=minutes.get(priority or "UNASSESSED", minutes.get("UNASSESSED", 15)))


def escalation_level_due(start: datetime, now: datetime, priority: str | None, council_settings: dict | None) -> int:
    """Number of escalation levels due: level 1 after one target period, level 2 after two, and so on."""
    minutes = council_setting(council_settings, "ack_minutes")
    period = timedelta(minutes=minutes.get(priority or "UNASSESSED", 15))
    if now < start + period:
        return 0
    levels = len(council_setting(council_settings, "escalation_levels"))
    return min(levels, int((now - start) / period))
