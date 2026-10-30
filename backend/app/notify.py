"""In-app notifications and the SMS provider adapter.

Statuses are recorded honestly: SIMULATED when the development "log" provider is used,
SENT only when a provider accepted the message, DELIVERED only if a delivery report says so.
"""
import json
import logging
import urllib.error
import urllib.request

from sqlalchemy import select
from sqlalchemy.orm import Session

from . import models as m
from .config import settings
from .workflow import council_setting

log = logging.getLogger("accisafe.notify")


class SmsResult:
    def __init__(self, status: str, response: str):
        self.status = status
        self.response = response


class SmsProvider:
    def send(self, to: str, message: str) -> SmsResult:  # pragma: no cover - interface
        raise NotImplementedError


class LogSmsProvider(SmsProvider):
    """Development provider: records the message without sending anything."""

    def __init__(self):
        self.outbox: list[tuple[str, str]] = []

    def send(self, to: str, message: str) -> SmsResult:
        self.outbox.append((to, message))
        log.info("SIMULATED SMS to %s: %s", to, message)
        return SmsResult("SIMULATED", "Development mode: logged, not sent")


class HttpSmsProvider(SmsProvider):
    """Generic JSON-over-HTTPS gateway: POST {to, message, sender} with a bearer token."""

    def send(self, to: str, message: str) -> SmsResult:
        if not settings.sms_http_url or not settings.sms_http_token:
            return SmsResult("NOT_CONFIGURED", "SMS gateway URL or token not configured")
        body = json.dumps({"to": to, "message": message, "sender": settings.sms_sender_id}).encode()
        req = urllib.request.Request(settings.sms_http_url, data=body, method="POST", headers={
            "Content-Type": "application/json", "Authorization": f"Bearer {settings.sms_http_token}",
        })
        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                text = resp.read(2000).decode(errors="replace")
                return SmsResult("SENT" if 200 <= resp.status < 300 else "FAILED", text)
        except urllib.error.HTTPError as e:
            return SmsResult("FAILED", f"HTTP {e.code}: {e.read(500).decode(errors='replace')}")
        except Exception as e:  # network failure, timeout
            return SmsResult("FAILED", f"{type(e).__name__}: {e}")


_provider: SmsProvider = HttpSmsProvider() if settings.sms_provider == "http" else LogSmsProvider()


def sms_provider() -> SmsProvider:
    return _provider


def set_sms_provider(provider: SmsProvider) -> None:
    global _provider
    _provider = provider


def users_with_roles(db: Session, council_id: int, roles: list[str]) -> list[m.User]:
    return list(db.scalars(
        select(m.User).join(m.UserRole, m.UserRole.user_id == m.User.id).where(
            m.User.council_id == council_id, m.User.active.is_(True), m.UserRole.role_code.in_(roles)
        ).distinct()
    ))


def team_users(db: Session, team: m.ResponseTeam) -> list[m.User]:
    ids = {mem.user_id for mem in team.members}
    if team.leader_id:
        ids.add(team.leader_id)
    if not ids:
        return []
    return list(db.scalars(select(m.User).where(m.User.id.in_(ids), m.User.active.is_(True))))


def notify(
    db: Session, council: m.Council, recipients: list[m.User], *, event: str, title: str, body: str,
    incident_id: int | None = None, sms_body: str | None = None,
) -> list[m.Notification]:
    """Creates an in-app notification per recipient and, when enabled for the event, an SMS attempt."""
    if not recipients:
        n = m.Notification(council_id=council.id, user_id=None, incident_id=incident_id, event=event,
                           title=f"No recipient: {title}", body=body)
        n.attempts.append(m.NotificationAttempt(channel="IN_APP", status="NO_RECIPIENT",
                                                provider_response="No active user holds the required role"))
        db.add(n)
        return [n]

    use_sms = council_setting(council.settings, "sms_enabled") and event in council_setting(council.settings, "sms_events")
    created = []
    for user in recipients:
        duplicate = db.scalar(select(m.Notification.id).where(
            m.Notification.user_id == user.id, m.Notification.incident_id == incident_id,
            m.Notification.event == event, m.Notification.title == title,
        ))
        if duplicate:
            continue  # never alert the same person twice for the same event
        n = m.Notification(council_id=council.id, user_id=user.id, incident_id=incident_id, event=event,
                           title=title, body=body)
        n.attempts.append(m.NotificationAttempt(channel="IN_APP", status="CREATED"))
        if use_sms:
            n.attempts.extend(_send_sms(user.phone, sms_body or f"{title}. {body}"))
        db.add(n)
        created.append(n)
    return created


def _send_sms(phone: str | None, message: str) -> list[m.NotificationAttempt]:
    if not phone:
        return [m.NotificationAttempt(channel="SMS", status="NO_RECIPIENT", provider_response="No phone number")]
    attempts = []
    for number in (1, 2):  # one controlled retry on failure
        result = sms_provider().send(phone, message[:459])
        attempts.append(m.NotificationAttempt(channel="SMS", status=result.status, destination=phone,
                                              provider_response=result.response[:1000], attempt_number=number))
        if result.status != "FAILED":
            break
    return attempts
