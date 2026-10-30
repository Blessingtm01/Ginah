package zw.co.accisafe.rdc.data.net

import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

interface ApiService {
    // Auth
    @POST("api/v1/auth/login") suspend fun login(@Body body: LoginRequest): TokenResponse
    @POST("api/v1/auth/refresh") suspend fun refresh(@Body body: RefreshRequest): TokenResponse
    @POST("api/v1/auth/logout") suspend fun logout(@Body body: RefreshRequest): Response<Unit>
    @POST("api/v1/auth/register") suspend fun register(@Body body: RegisterRequest): TokenResponse
    @GET("api/v1/auth/me") suspend fun me(): Me
    @POST("api/v1/auth/change-password") suspend fun changePassword(@Body body: ChangePasswordRequest): Response<Unit>
    @GET("api/v1/councils/{code}/public") suspend fun councilPublic(@Path("code") code: String): CouncilPublic

    // Configuration
    @GET("api/v1/config") suspend fun config(): ConfigResponse
    @POST("api/v1/config/wards") suspend fun addWard(@Body body: WardRequest): IdResponse
    @PUT("api/v1/config/wards/{id}") suspend fun editWard(@Path("id") id: Int, @Body body: WardRequest): IdResponse
    @POST("api/v1/config/categories") suspend fun addCategory(@Body body: CategoryRequest): IdResponse
    @PUT("api/v1/config/categories/{id}") suspend fun editCategory(@Path("id") id: Int, @Body body: CategoryRequest): IdResponse

    // Incidents
    @POST("api/v1/incidents") suspend fun createIncident(@Body body: IncidentCreate): IncidentSummary
    @GET("api/v1/incidents") suspend fun incidents(
        @Query("page") page: Int = 1, @Query("page_size") pageSize: Int = 30, @Query("q") q: String? = null,
        @Query("kind") kind: String? = null, @Query("status_") status: String? = null,
        @Query("priority") priority: String? = null, @Query("category_id") categoryId: Int? = null,
        @Query("ward_id") wardId: Int? = null, @Query("team_id") teamId: Int? = null,
        @Query("source") source: String? = null, @Query("referred") referred: Boolean? = null,
        @Query("date_from") dateFrom: String? = null, @Query("date_to") dateTo: String? = null,
        @Query("mine") mine: Boolean = false, @Query("open_only") openOnly: Boolean = false,
    ): Page<IncidentSummary>
    @GET("api/v1/incidents/{id}") suspend fun incident(@Path("id") id: Int): IncidentDetail
    @PATCH("api/v1/incidents/{id}/status") suspend fun changeStatus(@Path("id") id: Int, @Body body: StatusChange): IncidentSummary
    @POST("api/v1/incidents/{id}/assess") suspend fun assess(@Path("id") id: Int, @Body body: AssessRequest): IncidentSummary
    @POST("api/v1/incidents/{id}/assignments") suspend fun assign(@Path("id") id: Int, @Body body: AssignRequest): AssignResponse
    @POST("api/v1/assignments/{id}/respond") suspend fun respond(@Path("id") id: Int, @Body body: RespondRequest): RespondResponse
    @GET("api/v1/assignments/mine") suspend fun myAssignments(): List<MyAssignment>
    @POST("api/v1/incidents/{id}/updates") suspend fun addUpdate(@Path("id") id: Int, @Body body: UpdateRequest): IdResponse
    @POST("api/v1/incidents/{id}/referrals") suspend fun refer(@Path("id") id: Int, @Body body: ReferralRequest): IdResponse
    @PATCH("api/v1/referrals/{id}") suspend fun updateReferral(@Path("id") id: Int, @Body body: ReferralPatch): Response<Unit>
    @POST("api/v1/incidents/{id}/escalate-to-emergency") suspend fun escalateToEmergency(@Path("id") id: Int, @Body body: EscalateRequest): IncidentSummary
    @POST("api/v1/incidents/{id}/resources") suspend fun allocate(@Path("id") id: Int, @Body body: AllocateRequest): IdResponse
    @POST("api/v1/allocations/{id}/return") suspend fun returnResource(@Path("id") id: Int): Response<Unit>
    @Multipart @POST("api/v1/incidents/{id}/attachments")
    suspend fun uploadAttachment(@Path("id") id: Int, @Part file: MultipartBody.Part): IdResponse

    // Teams and resources
    @GET("api/v1/teams") suspend fun teams(): List<Team>
    @GET("api/v1/teams/{id}") suspend fun team(@Path("id") id: Int): Team
    @GET("api/v1/teams/mine/list") suspend fun myTeams(): List<Team>
    @POST("api/v1/teams") suspend fun createTeam(@Body body: TeamRequest): IdResponse
    @PATCH("api/v1/teams/{id}/availability") suspend fun setAvailability(@Path("id") id: Int, @Body body: AvailabilityRequest): Response<Unit>
    @GET("api/v1/resources") suspend fun resources(): List<Resource>
    @GET("api/v1/resources/{id}") suspend fun resource(@Path("id") id: Int): Resource
    @POST("api/v1/resources") suspend fun createResource(@Body body: ResourceRequest): IdResponse
    @PATCH("api/v1/resources/{id}/status") suspend fun resourceStatus(@Path("id") id: Int, @Body body: ResourceStatusRequest): Response<Unit>
    @POST("api/v1/resources/{id}/maintenance") suspend fun addMaintenance(@Path("id") id: Int, @Body body: MaintenanceRequest): IdResponse

    // Notifications, reports, audit, detection
    @GET("api/v1/notifications") suspend fun notifications(@Query("unread_only") unreadOnly: Boolean = false): NotificationsResponse
    @POST("api/v1/notifications/{id}/read") suspend fun markRead(@Path("id") id: Int): Response<Unit>
    @POST("api/v1/notifications/read-all") suspend fun markAllRead(): Response<Unit>
    @POST("api/v1/escalations/run") suspend fun runEscalations(): EscalationRunResponse
    @GET("api/v1/reports/summary") suspend fun reportSummary(@Query("date_from") from: String? = null, @Query("date_to") to: String? = null): ReportSummary
    @Streaming @GET("api/v1/reports/incidents.csv") suspend fun exportCsv(@Query("date_from") from: String? = null, @Query("date_to") to: String? = null): ResponseBody
    @GET("api/v1/audit") suspend fun audit(@Query("page") page: Int = 1, @Query("page_size") pageSize: Int = 50, @Query("entity") entity: String? = null): Page<AuditItem>
    @POST("api/v1/detection-events") suspend fun detectionEvent(@Body body: DetectionEventRequest): Response<Unit>

    // Users
    @GET("api/v1/users") suspend fun users(): List<UserDto>
    @GET("api/v1/users/directory") suspend fun directory(): List<DirectoryUser>
    @POST("api/v1/users") suspend fun createUser(@Body body: UserCreate): UserDto
    @PATCH("api/v1/users/{id}") suspend fun patchUser(@Path("id") id: Int, @Body body: UserPatch): UserDto
}
