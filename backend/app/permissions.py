"""Role-based access control. Every protected endpoint checks these on the server."""

ROLES = {
    "SYSTEM_ADMIN": "System Administrator",
    "COORDINATOR": "Emergency Response Coordinator",
    "MANAGEMENT": "Council Management",
    "TEAM_LEADER": "Response Team Leader",
    "FIELD_OFFICER": "Field Officer",
    "COMMUNITY_REPORTER": "Community Reporter",
    "AUDITOR": "Read-Only Auditor",
}

_ALL_VIEW = {"incidents.view_all", "teams.view", "resources.view", "reports.view"}

PERMISSIONS: dict[str, set[str]] = {
    "SYSTEM_ADMIN": _ALL_VIEW | {
        "users.manage", "config.manage", "audit.view", "teams.manage", "resources.manage",
        "notifications.configure", "escalation.run", "incidents.create", "personal.view",
        "notifications.view", "sync.view",
    },
    "COORDINATOR": _ALL_VIEW | {
        "incidents.create", "incidents.assess", "incidents.assign", "incidents.coordinate",
        "incidents.refer", "incidents.close", "resources.allocate", "reports.export",
        "escalation.run", "personal.view", "notifications.view", "incidents.update_field",
        "sync.view",
    },
    "MANAGEMENT": _ALL_VIEW | {"reports.export", "notifications.view"},
    "TEAM_LEADER": {
        "incidents.create", "incidents.view_assigned", "incidents.update_field", "assignments.respond",
        "teams.view", "teams.update_availability", "resources.view", "resources.request",
        "notifications.view", "attachments.upload",
    },
    "FIELD_OFFICER": {
        "incidents.create", "incidents.view_assigned", "incidents.update_field", "teams.view",
        "notifications.view", "attachments.upload",
    },
    "COMMUNITY_REPORTER": {"incidents.create", "incidents.view_own", "notifications.view", "attachments.upload"},
    "AUDITOR": _ALL_VIEW | {"audit.view", "reports.export"},
}


def permissions_for(roles: list[str]) -> set[str]:
    result: set[str] = set()
    for r in roles:
        result |= PERMISSIONS.get(r, set())
    return result


def has(roles: list[str], permission: str) -> bool:
    return permission in permissions_for(roles)
