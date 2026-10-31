"""Relational schema. Every council-owned row carries council_id so councils stay isolated."""
from datetime import datetime

from sqlalchemy import (
    JSON, Boolean, DateTime, Float, ForeignKey, Index, Integer, LargeBinary, String, Text, UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from .db import Base, utcnow


def _created():
    return mapped_column(DateTime, default=utcnow, nullable=False)


# --- councils, users, roles -------------------------------------------------

class Council(Base):
    __tablename__ = "councils"
    id: Mapped[int] = mapped_column(primary_key=True)
    code: Mapped[str] = mapped_column(String(16), unique=True)
    name: Mapped[str] = mapped_column(String(200))
    phone: Mapped[str | None] = mapped_column(String(40))
    email: Mapped[str | None] = mapped_column(String(200))
    address: Mapped[str | None] = mapped_column(String(300))
    logo_path: Mapped[str | None] = mapped_column(String(300))
    public_registration: Mapped[bool] = mapped_column(Boolean, default=True)
    is_demo: Mapped[bool] = mapped_column(Boolean, default=False)
    # Escalation targets, SMS switches and other tunables; see workflow.DEFAULT_SETTINGS.
    settings: Mapped[dict] = mapped_column(JSON, default=dict)
    created_at: Mapped[datetime] = _created()


class Role(Base):
    __tablename__ = "roles"
    code: Mapped[str] = mapped_column(String(32), primary_key=True)
    name: Mapped[str] = mapped_column(String(100))


class User(Base):
    __tablename__ = "users"
    __table_args__ = (UniqueConstraint("council_id", "username"),)
    id: Mapped[int] = mapped_column(primary_key=True)
    council_id: Mapped[int] = mapped_column(ForeignKey("councils.id"), index=True)
    username: Mapped[str] = mapped_column(String(80))
    full_name: Mapped[str] = mapped_column(String(200))
    phone: Mapped[str | None] = mapped_column(String(40))
    email: Mapped[str | None] = mapped_column(String(200))
    password_hash: Mapped[str] = mapped_column(String(300))
    active: Mapped[bool] = mapped_column(Boolean, default=True)
    must_change_password: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = _created()
    roles: Mapped[list["UserRole"]] = relationship(cascade="all, delete-orphan", lazy="selectin",
                                                   foreign_keys="UserRole.user_id")

    @property
    def role_codes(self) -> list[str]:
        return sorted(r.role_code for r in self.roles)


class UserRole(Base):
    __tablename__ = "user_roles"
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), primary_key=True)
    role_code: Mapped[str] = mapped_column(ForeignKey("roles.code"), primary_key=True)
    assigned_by: Mapped[int | None] = mapped_column(ForeignKey("users.id"))
    assigned_at: Mapped[datetime] = _created()


class RefreshToken(Base):
    __tablename__ = "refresh_tokens"
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    token_hash: Mapped[str] = mapped_column(String(128), unique=True)
    device: Mapped[str | None] = mapped_column(String(200))
    expires_at: Mapped[datetime] = mapped_column(DateTime)
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime)
    created_at: Mapped[datetime] = _created()


class Ward(Base):
    __tablename__ = "wards"
    __table_args__ = (UniqueConstraint("council_id", "name"),)
    id: Mapped[int] = mapped_column(primary_key=True)
    council_id: Mapped[int] = mapped_column(ForeignKey("councils.id"), index=True)
    name: Mapped[str] = mapped_column(String(120))
    number: Mapped[int | None] = mapped_column(Integer)
    active: Mapped[bool] = mapped_column(Boolean, default=True)


class IncidentCategory(Base):
    __tablename__ = "incident_categories"
    __table_args__ = (UniqueConstraint("council_id", "name"),)
    id: Mapped[int] = mapped_column(primary_key=True)
    council_id: Mapped[int] = mapped_column(ForeignKey("councils.id"), index=True)
    name: Mapped[str] = mapped_column(String(120))
    kind: Mapped[str] = mapped_column(String(16))  # EMERGENCY | ROUTINE
    default_priority: Mapped[str] = mapped_column(String(16), default="MEDIUM")
    active: Mapped[bool] = mapped_column(Boolean, default=True)


# --- incidents -----------------------------------------------------------------

class Incident(Base):
    __tablename__ = "incidents"
    __table_args__ = (
        UniqueConstraint("council_id", "idempotency_key"),
        Index("ix_incidents_council_status", "council_id", "status"),
        Index("ix_incidents_council_updated", "council_id", "updated_at"),
    )
    id: Mapped[int] = mapped_column(primary_key=True)
    council_id: Mapped[int] = mapped_column(ForeignKey("councils.id"), index=True)
    reference: Mapped[str] = mapped_column(String(40), unique=True)
    kind: Mapped[str] = mapped_column(String(16))  # EMERGENCY | ROUTINE
    category_id: Mapped[int] = mapped_column(ForeignKey("incident_categories.id"))
    status: Mapped[str] = mapped_column(String(32))
    priority: Mapped[str | None] = mapped_column(String(16))
    suggested_priority: Mapped[str | None] = mapped_column(String(16))
    source: Mapped[str] = mapped_column(String(32))  # APP | ACCIDENT_DETECTION | PHONE_CALL | WALK_IN | OTHER
    reported_at: Mapped[datetime] = mapped_column(DateTime)  # device time of the report
    occurred_at: Mapped[datetime | None] = mapped_column(DateTime)
    occurred_at_unknown: Mapped[bool] = mapped_column(Boolean, default=False)
    received_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)  # server receipt time
    acknowledged_at: Mapped[datetime | None] = mapped_column(DateTime)
    resolved_at: Mapped[datetime | None] = mapped_column(DateTime)
    closed_at: Mapped[datetime | None] = mapped_column(DateTime)
    # Location
    latitude: Mapped[float | None] = mapped_column(Float)
    longitude: Mapped[float | None] = mapped_column(Float)
    accuracy_m: Mapped[float | None] = mapped_column(Float)
    location_source: Mapped[str] = mapped_column(String(16), default="NONE")  # GPS | MAP | MANUAL | NONE
    ward_id: Mapped[int | None] = mapped_column(ForeignKey("wards.id"))
    landmark: Mapped[str | None] = mapped_column(String(300))
    # Details. Tri-state answers: YES | NO | UNKNOWN; NULL means "not provided".
    description: Mapped[str] = mapped_column(Text)
    injuries: Mapped[str | None] = mapped_column(String(8))
    fatalities: Mapped[str | None] = mapped_column(String(8))  # has anyone died?
    immediate_danger: Mapped[str | None] = mapped_column(String(8))
    threat_to_life: Mapped[str | None] = mapped_column(String(8))
    may_worsen: Mapped[str | None] = mapped_column(String(8))
    affected_people: Mapped[int | None] = mapped_column(Integer)
    affected_people_unknown: Mapped[bool] = mapped_column(Boolean, default=False)
    # Reporter (personal data, shown only to roles with personal.view)
    reporter_id: Mapped[int | None] = mapped_column(ForeignKey("users.id"))
    reporter_name: Mapped[str | None] = mapped_column(String(200))
    reporter_phone: Mapped[str | None] = mapped_column(String(40))
    # Sync
    idempotency_key: Mapped[str] = mapped_column(String(64))
    local_id: Mapped[str | None] = mapped_column(String(64))
    version: Mapped[int] = mapped_column(Integer, default=1)
    escalated_from_routine: Mapped[bool] = mapped_column(Boolean, default=False)
    is_demo: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = _created()
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, onupdate=utcnow)

    category: Mapped[IncidentCategory] = relationship(lazy="joined")
    ward: Mapped[Ward | None] = relationship(lazy="joined")


class IncidentStatusHistory(Base):
    __tablename__ = "incident_status_history"
    id: Mapped[int] = mapped_column(primary_key=True)
    incident_id: Mapped[int] = mapped_column(ForeignKey("incidents.id", ondelete="CASCADE"), index=True)
    from_status: Mapped[str | None] = mapped_column(String(32))
    to_status: Mapped[str] = mapped_column(String(32))
    user_id: Mapped[int | None] = mapped_column(ForeignKey("users.id"))
    note: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = _created()


class IncidentAssessment(Base):
    __tablename__ = "incident_assessments"
    id: Mapped[int] = mapped_column(primary_key=True)
    incident_id: Mapped[int] = mapped_column(ForeignKey("incidents.id", ondelete="CASCADE"), index=True)
    original_priority: Mapped[str | None] = mapped_column(String(16))
    new_priority: Mapped[str] = mapped_column(String(16))
    suggested_priority: Mapped[str | None] = mapped_column(String(16))
    reason: Mapped[str | None] = mapped_column(Text)
    factors: Mapped[dict] = mapped_column(JSON, default=dict)
    officer_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    created_at: Mapped[datetime] = _created()


class IncidentAttachment(Base):
    __tablename__ = "incident_attachments"
    id: Mapped[int] = mapped_column(primary_key=True)
    incident_id: Mapped[int] = mapped_column(ForeignKey("incidents.id", ondelete="CASCADE"), index=True)
    filename: Mapped[str] = mapped_column(String(200))
    content_type: Mapped[str] = mapped_column(String(80))
    storage_path: Mapped[str] = mapped_column(String(400))  # "db" when stored in content
    content: Mapped[bytes | None] = mapped_column(LargeBinary, deferred=True)
    size_bytes: Mapped[int] = mapped_column(Integer)
    uploaded_by: Mapped[int] = mapped_column(ForeignKey("users.id"))
    created_at: Mapped[datetime] = _created()


# --- response teams --------------------------------------------------------------

class ResponseTeam(Base):
    __tablename__ = "response_teams"
    __table_args__ = (UniqueConstraint("council_id", "name"),)
    id: Mapped[int] = mapped_column(primary_key=True)
    council_id: Mapped[int] = mapped_column(ForeignKey("councils.id"), index=True)
    name: Mapped[str] = mapped_column(String(120))
    category: Mapped[str] = mapped_column(String(60))
    leader_id: Mapped[int | None] = mapped_column(ForeignKey("users.id"))
    contact_phone: Mapped[str | None] = mapped_column(String(40))
    operational_area: Mapped[str | None] = mapped_column(String(200))
    availability: Mapped[str] = mapped_column(String(32), default="AVAILABLE")
    capabilities: Mapped[str | None] = mapped_column(Text)
    notes: Mapped[str | None] = mapped_column(Text)
    active: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = _created()
    members: Mapped[list["TeamMember"]] = relationship(cascade="all, delete-orphan", lazy="selectin")


class TeamMember(Base):
    __tablename__ = "team_members"
    team_id: Mapped[int] = mapped_column(ForeignKey("response_teams.id", ondelete="CASCADE"), primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), primary_key=True)


class TeamAvailabilityLog(Base):
    __tablename__ = "team_availability_log"
    id: Mapped[int] = mapped_column(primary_key=True)
    team_id: Mapped[int] = mapped_column(ForeignKey("response_teams.id", ondelete="CASCADE"), index=True)
    availability: Mapped[str] = mapped_column(String(32))
    user_id: Mapped[int | None] = mapped_column(ForeignKey("users.id"))
    created_at: Mapped[datetime] = _created()


class Assignment(Base):
    __tablename__ = "incident_assignments"
    id: Mapped[int] = mapped_column(primary_key=True)
    incident_id: Mapped[int] = mapped_column(ForeignKey("incidents.id", ondelete="CASCADE"), index=True)
    team_id: Mapped[int] = mapped_column(ForeignKey("response_teams.id"), index=True)
    assigned_by: Mapped[int] = mapped_column(ForeignKey("users.id"))
    instructions: Mapped[str | None] = mapped_column(Text)
    ack_due_at: Mapped[datetime] = mapped_column(DateTime)
    # PENDING | ACCEPTED | DECLINED | REASSIGNED | CANCELLED | COMPLETED
    status: Mapped[str] = mapped_column(String(16), default="PENDING")
    responded_at: Mapped[datetime | None] = mapped_column(DateTime)
    responded_by: Mapped[int | None] = mapped_column(ForeignKey("users.id"))
    decline_reason: Mapped[str | None] = mapped_column(Text)
    reassign_reason: Mapped[str | None] = mapped_column(Text)
    departed_at: Mapped[datetime | None] = mapped_column(DateTime)
    arrived_at: Mapped[datetime | None] = mapped_column(DateTime)
    completed_at: Mapped[datetime | None] = mapped_column(DateTime)
    created_at: Mapped[datetime] = _created()
    team: Mapped[ResponseTeam] = relationship(lazy="joined")


class ResponseUpdate(Base):
    __tablename__ = "response_updates"
    id: Mapped[int] = mapped_column(primary_key=True)
    incident_id: Mapped[int] = mapped_column(ForeignKey("incidents.id", ondelete="CASCADE"), index=True)
    assignment_id: Mapped[int | None] = mapped_column(ForeignKey("incident_assignments.id"))
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    # PROGRESS | ACTION | OBSERVATION | ASSISTANCE_REQUEST | RESOURCE_REQUEST | COMPLETION | NOTE
    kind: Mapped[str] = mapped_column(String(24))
    text: Mapped[str] = mapped_column(Text)
    created_at: Mapped[datetime] = _created()


# --- resources -------------------------------------------------------------------

class Resource(Base):
    __tablename__ = "resources"
    id: Mapped[int] = mapped_column(primary_key=True)
    council_id: Mapped[int] = mapped_column(ForeignKey("councils.id"), index=True)
    name: Mapped[str] = mapped_column(String(160))
    category: Mapped[str] = mapped_column(String(60))
    asset_number: Mapped[str | None] = mapped_column(String(60))
    department: Mapped[str | None] = mapped_column(String(120))
    location: Mapped[str | None] = mapped_column(String(200))
    # AVAILABLE | ASSIGNED | UNDER_MAINTENANCE | OUT_OF_SERVICE | RETIRED
    status: Mapped[str] = mapped_column(String(24), default="AVAILABLE")
    inspection_date: Mapped[datetime | None] = mapped_column(DateTime)
    notes: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = _created()


class ResourceAllocation(Base):
    __tablename__ = "resource_allocations"
    id: Mapped[int] = mapped_column(primary_key=True)
    resource_id: Mapped[int] = mapped_column(ForeignKey("resources.id"), index=True)
    incident_id: Mapped[int] = mapped_column(ForeignKey("incidents.id"), index=True)
    allocated_by: Mapped[int] = mapped_column(ForeignKey("users.id"))
    allocated_at: Mapped[datetime] = _created()
    returned_at: Mapped[datetime | None] = mapped_column(DateTime)
    notes: Mapped[str | None] = mapped_column(Text)
    resource: Mapped[Resource] = relationship(lazy="joined")


class MaintenanceRecord(Base):
    __tablename__ = "maintenance_records"
    id: Mapped[int] = mapped_column(primary_key=True)
    resource_id: Mapped[int] = mapped_column(ForeignKey("resources.id"), index=True)
    performed_at: Mapped[datetime] = mapped_column(DateTime)
    description: Mapped[str] = mapped_column(Text)
    recorded_by: Mapped[int] = mapped_column(ForeignKey("users.id"))
    created_at: Mapped[datetime] = _created()


# --- referrals ---------------------------------------------------------------------

class ExternalOrganisation(Base):
    __tablename__ = "external_organisations"
    __table_args__ = (UniqueConstraint("council_id", "name"),)
    id: Mapped[int] = mapped_column(primary_key=True)
    council_id: Mapped[int] = mapped_column(ForeignKey("councils.id"), index=True)
    name: Mapped[str] = mapped_column(String(200))
    org_type: Mapped[str] = mapped_column(String(60))
    phone: Mapped[str | None] = mapped_column(String(40))
    active: Mapped[bool] = mapped_column(Boolean, default=True)


class Referral(Base):
    __tablename__ = "incident_referrals"
    id: Mapped[int] = mapped_column(primary_key=True)
    incident_id: Mapped[int] = mapped_column(ForeignKey("incidents.id", ondelete="CASCADE"), index=True)
    organisation_id: Mapped[int] = mapped_column(ForeignKey("external_organisations.id"))
    contact_person: Mapped[str | None] = mapped_column(String(200))
    referred_at: Mapped[datetime] = mapped_column(DateTime)
    method: Mapped[str] = mapped_column(String(32))  # PHONE | IN_PERSON | RADIO | LETTER | OTHER
    info_shared: Mapped[str] = mapped_column(Text)
    external_reference: Mapped[str | None] = mapped_column(String(100))
    # NOT_CONFIRMED | ACKNOWLEDGED | DECLINED
    ack_status: Mapped[str] = mapped_column(String(16), default="NOT_CONFIRMED")
    follow_up_notes: Mapped[str | None] = mapped_column(Text)
    outcome: Mapped[str | None] = mapped_column(Text)
    created_by: Mapped[int] = mapped_column(ForeignKey("users.id"))
    created_at: Mapped[datetime] = _created()
    organisation: Mapped[ExternalOrganisation] = relationship(lazy="joined")


# --- notifications and escalation -----------------------------------------------------

class Notification(Base):
    __tablename__ = "notifications"
    __table_args__ = (Index("ix_notifications_user_created", "user_id", "created_at"),)
    id: Mapped[int] = mapped_column(primary_key=True)
    council_id: Mapped[int] = mapped_column(ForeignKey("councils.id"), index=True)
    user_id: Mapped[int | None] = mapped_column(ForeignKey("users.id"))
    incident_id: Mapped[int | None] = mapped_column(ForeignKey("incidents.id", ondelete="SET NULL"))
    event: Mapped[str] = mapped_column(String(40))
    title: Mapped[str] = mapped_column(String(200))
    body: Mapped[str] = mapped_column(Text)
    read_at: Mapped[datetime | None] = mapped_column(DateTime)
    created_at: Mapped[datetime] = _created()
    attempts: Mapped[list["NotificationAttempt"]] = relationship(lazy="selectin", cascade="all, delete-orphan")


class NotificationAttempt(Base):
    __tablename__ = "notification_attempts"
    id: Mapped[int] = mapped_column(primary_key=True)
    notification_id: Mapped[int] = mapped_column(ForeignKey("notifications.id", ondelete="CASCADE"), index=True)
    channel: Mapped[str] = mapped_column(String(16))  # IN_APP | SMS | PUSH | EMAIL
    # CREATED | SENT | DELIVERED | FAILED | SIMULATED | NO_RECIPIENT | NOT_CONFIGURED
    status: Mapped[str] = mapped_column(String(16))
    destination: Mapped[str | None] = mapped_column(String(80))
    provider_response: Mapped[str | None] = mapped_column(Text)
    attempt_number: Mapped[int] = mapped_column(Integer, default=1)
    attempted_at: Mapped[datetime] = _created()


class EscalationEvent(Base):
    __tablename__ = "escalation_events"
    __table_args__ = (UniqueConstraint("incident_id", "assignment_id", "level"),)
    id: Mapped[int] = mapped_column(primary_key=True)
    incident_id: Mapped[int] = mapped_column(ForeignKey("incidents.id", ondelete="CASCADE"), index=True)
    assignment_id: Mapped[int | None] = mapped_column(ForeignKey("incident_assignments.id"))
    level: Mapped[int] = mapped_column(Integer)
    reason: Mapped[str] = mapped_column(Text)
    notified_roles: Mapped[str] = mapped_column(String(200))
    notified_count: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[datetime] = _created()


# --- audit, detection, sync --------------------------------------------------------------

class AuditLog(Base):
    """Append-only. No API updates or deletes these rows."""
    __tablename__ = "audit_logs"
    __table_args__ = (Index("ix_audit_council_created", "council_id", "created_at"),)
    id: Mapped[int] = mapped_column(primary_key=True)
    council_id: Mapped[int | None] = mapped_column(ForeignKey("councils.id"))
    user_id: Mapped[int | None] = mapped_column(ForeignKey("users.id"))
    action: Mapped[str] = mapped_column(String(60))
    entity: Mapped[str] = mapped_column(String(40))
    entity_id: Mapped[str | None] = mapped_column(String(40))
    old_value: Mapped[dict | None] = mapped_column(JSON)
    new_value: Mapped[dict | None] = mapped_column(JSON)
    reason: Mapped[str | None] = mapped_column(Text)
    correlation_id: Mapped[str | None] = mapped_column(String(64))
    device: Mapped[str | None] = mapped_column(String(200))
    created_at: Mapped[datetime] = _created()


class DetectionEvent(Base):
    __tablename__ = "detection_events"
    __table_args__ = (UniqueConstraint("council_id", "idempotency_key"),)
    id: Mapped[int] = mapped_column(primary_key=True)
    council_id: Mapped[int] = mapped_column(ForeignKey("councils.id"), index=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    idempotency_key: Mapped[str] = mapped_column(String(64))
    detected_at: Mapped[datetime] = mapped_column(DateTime)
    peak_g: Mapped[float] = mapped_column(Float)
    peak_rotation: Mapped[float | None] = mapped_column(Float)
    speed_kmh: Mapped[float | None] = mapped_column(Float)
    score: Mapped[int | None] = mapped_column(Integer)
    # CANCELLED_BY_USER | CONFIRMED_BY_USER | CONFIRMED_NO_RESPONSE | INTERRUPTED
    outcome: Mapped[str] = mapped_column(String(32))
    is_simulation: Mapped[bool] = mapped_column(Boolean, default=False)
    incident_id: Mapped[int | None] = mapped_column(ForeignKey("incidents.id", ondelete="SET NULL"))
    evidence: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = _created()


class SyncRecord(Base):
    __tablename__ = "sync_records"
    id: Mapped[int] = mapped_column(primary_key=True)
    council_id: Mapped[int] = mapped_column(ForeignKey("councils.id"), index=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"))
    entity: Mapped[str] = mapped_column(String(40))
    idempotency_key: Mapped[str] = mapped_column(String(64))
    result: Mapped[str] = mapped_column(String(24))  # CREATED | DUPLICATE | REJECTED
    detail: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = _created()
