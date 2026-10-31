package zw.co.accisafe.rdc.data.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Wire types for the AcciSafe RDC API. Field names are mapped to snake_case by the Json config.

@Serializable data class LoginRequest(val councilCode: String, val username: String, val password: String, val device: String? = null)
@Serializable data class RefreshRequest(val refreshToken: String)
@Serializable data class RegisterRequest(val councilCode: String, val username: String, val fullName: String, val phone: String? = null, val password: String)
@Serializable data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

@Serializable
data class Me(
    val id: Int, val councilId: Int, val councilName: String, val councilCode: String, val councilIsDemo: Boolean,
    val username: String, val fullName: String, val phone: String? = null, val roles: List<String>,
    val permissions: List<String>, val mustChangePassword: Boolean = false,
) {
    fun can(permission: String) = permission in permissions
    val isCommunityOnly get() = roles == listOf("COMMUNITY_REPORTER")
}

@Serializable data class TokenResponse(val accessToken: String, val refreshToken: String, val expiresIn: Int, val user: Me)
@Serializable data class CouncilPublic(val code: String, val name: String, val phone: String? = null, val email: String? = null, val isDemo: Boolean, val publicRegistration: Boolean)

@Serializable data class CouncilInfo(val id: Int, val code: String, val name: String, val phone: String? = null, val email: String? = null, val address: String? = null, val isDemo: Boolean, val publicRegistration: Boolean)
@Serializable data class Ward(val id: Int, val name: String, val number: Int? = null, val active: Boolean)
@Serializable data class Category(val id: Int, val name: String, val kind: String, val defaultPriority: String, val active: Boolean)
@Serializable data class Organisation(val id: Int, val name: String, val orgType: String, val phone: String? = null, val active: Boolean)
@Serializable
data class ConfigResponse(
    val council: CouncilInfo, val wards: List<Ward>, val categories: List<Category>,
    val organisations: List<Organisation> = emptyList(), val settings: JsonObject? = null,
)

@Serializable
data class IncidentCreate(
    val idempotencyKey: String, val localId: String, val categoryId: Int, val description: String,
    val source: String = "APP", val reportedAt: String, val occurredAt: String? = null, val occurredAtUnknown: Boolean = false,
    val latitude: Double? = null, val longitude: Double? = null, val accuracyM: Float? = null, val locationSource: String = "NONE",
    val wardId: Int? = null, val landmark: String? = null, val injuries: String? = null, val immediateDanger: String? = null,
    val threatToLife: String? = null, val mayWorsen: String? = null, val affectedPeople: Int? = null,
    val affectedPeopleUnknown: Boolean = false, val reporterName: String? = null, val reporterPhone: String? = null,
    val fatalities: String? = null,
)

@Serializable
data class IncidentSummary(
    val id: Int, val reference: String, val kind: String, val categoryId: Int, val category: String, val status: String,
    val priority: String? = null, val suggestedPriority: String? = null, val source: String, val reportedAt: String,
    val receivedAt: String, val updatedAt: String, val latitude: Double? = null, val longitude: Double? = null,
    val accuracyM: Double? = null, val wardId: Int? = null, val ward: String? = null, val landmark: String? = null,
    val description: String, val version: Int, val isDemo: Boolean = false, val localId: String? = null,
    val duplicate: Boolean? = null, val suggestionReasons: List<String>? = null,
) {
    val effectivePriority get() = priority ?: suggestedPriority
}

@Serializable data class Page<T>(val items: List<T>, val total: Int, val page: Int, val pageSize: Int)

@Serializable data class HistoryItem(val from: String? = null, val to: String, val by: String? = null, val note: String? = null, val at: String)
@Serializable data class AttachmentDto(val id: Int, val filename: String, val contentType: String, val sizeBytes: Int, val at: String)
@Serializable data class AssessmentDto(val original: String? = null, val new: String, val suggested: String? = null, val reason: String? = null, val officer: String? = null, val at: String)
@Serializable
data class AssignmentDto(
    val id: Int, val teamId: Int, val team: String, val status: String, val instructions: String? = null,
    val assignedBy: String? = null, val ackDueAt: String? = null, val respondedAt: String? = null,
    val declineReason: String? = null, val reassignReason: String? = null, val departedAt: String? = null,
    val arrivedAt: String? = null, val completedAt: String? = null, val createdAt: String,
)
@Serializable data class UpdateDto(val id: Int, val kind: String, val text: String, val by: String? = null, val at: String)
@Serializable
data class ReferralDto(
    val id: Int, val organisation: String, val contactPerson: String? = null, val referredAt: String, val method: String,
    val infoShared: String, val externalReference: String? = null, val ackStatus: String, val followUpNotes: String? = null,
    val outcome: String? = null, val by: String? = null,
)
@Serializable data class AllocationDto(val id: Int, val resourceId: Int, val resource: String, val allocatedAt: String, val returnedAt: String? = null, val notes: String? = null)
@Serializable data class EscalationDto(val level: Int, val reason: String, val notifiedRoles: String, val notifiedCount: Int, val at: String)
@Serializable data class AttemptDto(val channel: String, val status: String, val response: String? = null)
@Serializable data class IncidentNotificationDto(val event: String, val title: String, val recipient: String, val read: Boolean, val at: String, val attempts: List<AttemptDto>)

@Serializable
data class IncidentDetail(
    val id: Int, val reference: String, val kind: String, val categoryId: Int, val category: String, val status: String,
    val priority: String? = null, val suggestedPriority: String? = null, val source: String, val reportedAt: String,
    val receivedAt: String, val updatedAt: String, val latitude: Double? = null, val longitude: Double? = null,
    val accuracyM: Double? = null, val wardId: Int? = null, val ward: String? = null, val landmark: String? = null,
    val description: String, val version: Int, val isDemo: Boolean = false,
    val occurredAt: String? = null, val occurredAtUnknown: Boolean = false, val acknowledgedAt: String? = null,
    val resolvedAt: String? = null, val closedAt: String? = null, val locationSource: String = "NONE",
    val injuries: String? = null, val fatalities: String? = null, val immediateDanger: String? = null, val threatToLife: String? = null,
    val mayWorsen: String? = null, val affectedPeople: Int? = null, val affectedPeopleUnknown: Boolean = false,
    val escalatedFromRoutine: Boolean = false, val reporterName: String? = null, val reporterPhone: String? = null,
    val statusHistory: List<HistoryItem> = emptyList(), val attachments: List<AttachmentDto> = emptyList(),
    val assessments: List<AssessmentDto> = emptyList(), val assignments: List<AssignmentDto> = emptyList(),
    val updates: List<UpdateDto> = emptyList(), val referrals: List<ReferralDto> = emptyList(),
    val resources: List<AllocationDto> = emptyList(), val escalations: List<EscalationDto> = emptyList(),
    val notifications: List<IncidentNotificationDto> = emptyList(),
    val allowedStatusChanges: List<String> = emptyList(), val canAssess: Boolean = false, val canAssign: Boolean = false,
    val canRefer: Boolean = false, val canEscalateToEmergency: Boolean = false, val canAddUpdate: Boolean = false,
    val canAllocateResources: Boolean = false, val canUpdateReferrals: Boolean = false, val pendingAssignmentId: Int? = null,
) {
    val effectivePriority get() = priority ?: suggestedPriority
}

@Serializable data class StatusChange(val to: String, val note: String? = null, val expectedVersion: Int? = null)
@Serializable
data class AssessRequest(
    val priority: String, val reason: String? = null, val threatToLife: String? = null, val injuries: String? = null,
    val immediateDanger: String? = null, val mayWorsen: String? = null, val affectedPeople: Int? = null, val expectedVersion: Int? = null,
)
@Serializable data class AssignRequest(val teamId: Int, val instructions: String? = null, val ackMinutes: Int? = null, val reassignReason: String? = null, val expectedVersion: Int? = null)
@Serializable data class AssignResponse(val assignmentId: Int, val ackDueAt: String, val incident: IncidentSummary)
@Serializable data class RespondRequest(val accept: Boolean, val reason: String? = null)
@Serializable data class RespondResponse(val status: String, val incident: IncidentSummary)
@Serializable data class MyAssignment(val assignmentId: Int, val team: String, val status: String, val instructions: String? = null, val ackDueAt: String, val incident: IncidentSummary)
@Serializable data class UpdateRequest(val kind: String, val text: String)
@Serializable data class ReferralRequest(val organisationId: Int, val contactPerson: String? = null, val method: String, val infoShared: String, val externalReference: String? = null)
@Serializable data class ReferralPatch(val ackStatus: String? = null, val externalReference: String? = null, val followUpNotes: String? = null, val outcome: String? = null)
@Serializable data class EscalateRequest(val reason: String, val categoryId: Int)
@Serializable data class AllocateRequest(val resourceId: Int, val notes: String? = null)
@Serializable data class IdResponse(val id: Int)

@Serializable data class Member(val id: Int, val fullName: String)
@Serializable data class TeamAssignment(val assignmentId: Int, val status: String, val incidentId: Int, val reference: String, val category: String, val priority: String? = null, val incidentStatus: String)
@Serializable
data class Team(
    val id: Int, val name: String, val category: String, val availability: String, val operationalArea: String? = null,
    val capabilities: String? = null, val active: Boolean, val activeAssignments: Int, val leaderId: Int? = null,
    val leader: String? = null, val contactPhone: String? = null, val notes: String? = null,
    val members: List<Member>? = null, val assignments: List<TeamAssignment>? = null,
)
@Serializable data class AvailabilityRequest(val availability: String)
@Serializable
data class TeamRequest(
    val name: String, val category: String, val leaderId: Int? = null, val memberIds: List<Int> = emptyList(),
    val contactPhone: String? = null, val operationalArea: String? = null, val capabilities: String? = null,
    val notes: String? = null, val active: Boolean = true,
)

@Serializable data class CurrentAssignment(val allocationId: Int, val incidentId: Int, val reference: String)
@Serializable data class Maintenance(val performedAt: String, val description: String)
@Serializable data class ResourceAllocationItem(val incidentId: Int, val allocatedAt: String, val returnedAt: String? = null)
@Serializable
data class Resource(
    val id: Int, val name: String, val category: String, val assetNumber: String? = null, val department: String? = null,
    val location: String? = null, val status: String, val inspectionDate: String? = null, val notes: String? = null,
    val currentAssignment: CurrentAssignment? = null, val maintenance: List<Maintenance>? = null,
    val allocations: List<ResourceAllocationItem>? = null,
)
@Serializable
data class ResourceRequest(
    val name: String, val category: String, val assetNumber: String? = null, val department: String? = null,
    val location: String? = null, val status: String = "AVAILABLE", val notes: String? = null,
)
@Serializable data class ResourceStatusRequest(val status: String, val reason: String? = null)
@Serializable data class MaintenanceRequest(val performedAt: String, val description: String)

@Serializable data class ChannelDto(val channel: String, val status: String)
@Serializable
data class NotificationDto(
    val id: Int, val event: String, val title: String, val body: String, val incidentId: Int? = null, val read: Boolean,
    val createdAt: String, val noRecipient: Boolean = false, val channels: List<ChannelDto> = emptyList(),
)
@Serializable data class NotificationsResponse(val unread: Int, val items: List<NotificationDto>)

@Serializable data class Timing(val count: Int, val averageMinutes: Double? = null, val medianMinutes: Double? = null)
@Serializable
data class ReportSummary(
    val totals: Map<String, Int>, val byCategory: Map<String, Int>, val byWard: Map<String, Int>,
    val byMonth: Map<String, Int>, val byPriority: Map<String, Int>, val byStatus: Map<String, Int>,
    val bySource: Map<String, Int>, val timings: Map<String, Timing>, val assignments: JsonObject,
    val referrals: JsonObject, val resources: JsonObject, val accidentDetection: JsonObject, val synchronisation: JsonObject,
)

@Serializable
data class DetectionEventRequest(
    val idempotencyKey: String, val detectedAt: String, val peakG: Float, val peakRotation: Float? = null,
    val speedKmh: Float? = null, val score: Int? = null, val outcome: String, val isSimulation: Boolean,
    val incidentId: Int? = null, val evidence: String? = null,
)

@Serializable data class UserDto(val id: Int, val username: String, val fullName: String, val phone: String? = null, val email: String? = null, val active: Boolean, val roles: List<String>, val createdAt: String)
@Serializable data class UserCreate(val username: String, val fullName: String, val phone: String? = null, val email: String? = null, val roles: List<String>, val temporaryPassword: String)
@Serializable data class UserPatch(val fullName: String? = null, val phone: String? = null, val roles: List<String>? = null, val active: Boolean? = null, val resetPassword: String? = null, val reason: String? = null)
@Serializable data class DirectoryUser(val id: Int, val fullName: String, val roles: List<String>)
@Serializable data class WardRequest(val name: String, val number: Int? = null, val active: Boolean = true)
@Serializable data class CategoryRequest(val name: String, val kind: String, val defaultPriority: String, val active: Boolean = true)
@Serializable
data class AuditItem(
    val id: Int, val user: String, val action: String, val entity: String, val entityId: String? = null,
    val oldValue: JsonElement? = null, val newValue: JsonElement? = null, val reason: String? = null,
    val correlationId: String? = null, val createdAt: String,
)
@Serializable data class EscalationRunResponse(val created: Int)

// --- live council map ---------------------------------------------------------------------------

@Serializable data class LegendEntry(val code: String, val label: String, val color: String)
@Serializable
data class MapCouncil(
    val name: String, val areaName: String? = null, val center: List<Double>? = null,
    val boundary: JsonObject? = null, val boundarySource: String? = null,
)
@Serializable
data class MapIncident(
    val id: Int, val reference: String, val kind: String, val type: String, val color: String, val category: String,
    val status: String, val active: Boolean, val priority: String? = null, val latitude: Double, val longitude: Double,
    val ward: String? = null, val landmark: String? = null, val description: String, val injuries: String? = null,
    val fatalities: String? = null, val affectedPeople: Int? = null, val reportedAt: String, val receivedAt: String,
    val updatedAt: String, val photoIds: List<Int> = emptyList(), val mine: Boolean = false, val isDemo: Boolean = false,
)
@Serializable
data class LiveMapResponse(
    val council: MapCouncil, val legend: List<LegendEntry>, val days: Int, val generatedAt: String,
    val incidents: List<MapIncident>,
)
