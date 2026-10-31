package zw.co.accisafe.rdc.ui.incidents

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import coil.ImageLoader
import coil.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import zw.co.accisafe.rdc.data.ConfigRepository
import zw.co.accisafe.rdc.data.SessionStore
import zw.co.accisafe.rdc.data.apiCall
import zw.co.accisafe.rdc.data.net.AllocateRequest
import zw.co.accisafe.rdc.data.net.ApiClient
import zw.co.accisafe.rdc.data.net.AssessRequest
import zw.co.accisafe.rdc.data.net.AssignRequest
import zw.co.accisafe.rdc.data.net.ConfigResponse
import zw.co.accisafe.rdc.data.net.EscalateRequest
import zw.co.accisafe.rdc.data.net.IncidentDetail
import zw.co.accisafe.rdc.data.net.IncidentSummary
import zw.co.accisafe.rdc.data.net.Me
import zw.co.accisafe.rdc.data.net.ReferralPatch
import zw.co.accisafe.rdc.data.net.ReferralRequest
import zw.co.accisafe.rdc.data.net.RespondRequest
import zw.co.accisafe.rdc.data.net.Resource
import zw.co.accisafe.rdc.data.net.StatusChange
import zw.co.accisafe.rdc.data.net.Team
import zw.co.accisafe.rdc.data.net.UpdateRequest
import zw.co.accisafe.rdc.ui.RemoteViewModel
import zw.co.accisafe.rdc.ui.Routes
import zw.co.accisafe.rdc.ui.components.Badge
import zw.co.accisafe.rdc.ui.components.Dropdown
import zw.co.accisafe.rdc.ui.components.EmptyState
import zw.co.accisafe.rdc.ui.components.ErrorBanner
import zw.co.accisafe.rdc.ui.components.GradientScaffold
import zw.co.accisafe.rdc.ui.components.InfoRow
import zw.co.accisafe.rdc.ui.components.LoadingBox
import zw.co.accisafe.rdc.ui.components.PriorityBadge
import zw.co.accisafe.rdc.ui.components.SectionCard
import zw.co.accisafe.rdc.ui.components.StatusBadge
import zw.co.accisafe.rdc.ui.components.TriStateField
import zw.co.accisafe.rdc.ui.components.formatTime
import zw.co.accisafe.rdc.ui.components.humanise
import zw.co.accisafe.rdc.ui.home.IncidentRow
import zw.co.accisafe.rdc.ui.theme.Critical
import zw.co.accisafe.rdc.ui.theme.Emerald
import zw.co.accisafe.rdc.ui.theme.High
import zw.co.accisafe.rdc.ui.theme.Low
import javax.inject.Inject

// --- list -----------------------------------------------------------------------------------

data class IncidentFilters(
    val q: String = "", val openOnly: Boolean = true, val priority: String? = null, val kind: String? = null,
    val categoryId: Int? = null, val wardId: Int? = null, val referred: Boolean? = null,
)

data class IncidentPage(val items: List<IncidentSummary>, val total: Int, val page: Int)

@HiltViewModel
class IncidentListViewModel @Inject constructor(client: ApiClient, private val session: SessionStore, private val config: ConfigRepository) :
    RemoteViewModel<IncidentPage>(client) {
    val filters = MutableStateFlow(IncidentFilters(openOnly = session.me.value?.isCommunityOnly != true))
    val cfg = MutableStateFlow<ConfigResponse?>(null)
    private var page = 1
    private var accumulated = emptyList<IncidentSummary>()

    init { viewModelScope.launch { cfg.value = config.load().getOrNull() } }

    override suspend fun fetch(): IncidentPage {
        val f = filters.value
        val res = api.incidents(
            page = page, pageSize = 30, q = f.q.ifBlank { null }, kind = f.kind, priority = f.priority,
            categoryId = f.categoryId, wardId = f.wardId, referred = f.referred, openOnly = f.openOnly,
        )
        accumulated = if (page == 1) res.items else accumulated + res.items
        return IncidentPage(accumulated, res.total, page)
    }

    fun apply(f: IncidentFilters) { filters.value = f; page = 1; reload() }
    fun loadMore() { page++; reload() }
}

@Composable
fun IncidentListScreen(me: Me, nav: NavHostController, vm: IncidentListViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    val f by vm.filters.collectAsState()
    val cfg by vm.cfg.collectAsState()
    var showFilters by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.reload() }
    val staff = me.can("incidents.view_all")

    GradientScaffold(
        title = if (me.isCommunityOnly) "My reports" else "Incident register",
        subtitle = state.data?.let { "${it.total} matching" },
        actions = { if (staff) IconButton(onClick = { showFilters = !showFilters }) { Icon(Icons.Default.FilterList, "Filters", tint = Color.White) } },
        headerExtra = {
            OutlinedTextField(
                f.q, { vm.filters.value = f.copy(q = it) }, placeholder = { Text("Search reference, description, landmark", color = Color.White.copy(0.7f)) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = Color.White) }, singleLine = true,
                trailingIcon = { TextButton(onClick = { vm.apply(vm.filters.value) }) { Text("Go", color = Color.White) } },
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color.White,
                    unfocusedBorderColor = Color.White.copy(0.5f), cursorColor = Color.White,
                ),
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(f.openOnly, { vm.apply(f.copy(openOnly = !f.openOnly)) }, { Text("Open only") })
                    listOf("CRITICAL", "HIGH", "MEDIUM", "LOW").forEach { p ->
                        FilterChip(f.priority == p, { vm.apply(f.copy(priority = if (f.priority == p) null else p)) }, { Text(humanise(p)) })
                    }
                    FilterChip(f.kind == "ROUTINE", { vm.apply(f.copy(kind = if (f.kind == "ROUTINE") null else "ROUTINE")) }, { Text("Service requests") })
                    if (staff) FilterChip(f.referred == true, { vm.apply(f.copy(referred = if (f.referred == true) null else true)) }, { Text("Referred") })
                }
            }
            if (showFilters && cfg != null) item {
                SectionCard("More filters") {
                    Dropdown("Category", listOf(null) + cfg!!.categories, cfg!!.categories.firstOrNull { it.id == f.categoryId },
                        { it?.name ?: "Any" }, { vm.apply(f.copy(categoryId = it?.id)) }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Dropdown("Ward", listOf(null) + cfg!!.wards, cfg!!.wards.firstOrNull { it.id == f.wardId },
                        { it?.name ?: "Any" }, { vm.apply(f.copy(wardId = it?.id)) }, Modifier.fillMaxWidth())
                    TextButton(onClick = { vm.apply(IncidentFilters()) }) { Text("Clear all filters") }
                }
            }
            state.error?.let { item { ErrorBanner(it) { vm.reload() } } }
            val data = state.data
            if (state.loading && data == null) item { LoadingBox() }
            if (data != null && data.items.isEmpty()) item {
                EmptyState(if (me.isCommunityOnly) "No reports yet" else "No incidents match", "Change the filters or check again later.")
            }
            data?.items?.let { list -> items(list, key = { it.id }) { IncidentRow(it) { nav.navigate(Routes.incident(it.id)) } } }
            if (data != null && data.items.size < data.total) item {
                OutlinedButton(onClick = { vm.loadMore() }, Modifier.fillMaxWidth(), enabled = !state.loading) { Text("Load more (${data.total - data.items.size} remaining)") }
            }
        }
    }
}

// --- detail ---------------------------------------------------------------------------------

@HiltViewModel
class IncidentDetailViewModel @Inject constructor(client: ApiClient, private val config: ConfigRepository, private val session: SessionStore) :
    RemoteViewModel<IncidentDetail>(client) {
    var incidentId = 0
    val teams = MutableStateFlow<List<Team>>(emptyList())
    val resources = MutableStateFlow<List<Resource>>(emptyList())
    val cfg = MutableStateFlow<ConfigResponse?>(null)
    val serverUrl get() = session.serverUrl
    val imageLoader by lazy { client.okHttp }

    override suspend fun fetch() = api.incident(incidentId)

    fun loadOptions() = viewModelScope.launch {
        cfg.value = config.load().getOrNull()
        val me = session.me.value
        if (me?.can("teams.view") == true) apiCall { api.teams() }.onSuccess { teams.value = it }
        if (me?.can("resources.view") == true) apiCall { api.resources() }.onSuccess { resources.value = it }
    }

    private val v get() = state.value.data?.version

    fun status(to: String, note: String?) = act("Status changed to ${humanise(to)}") { api.changeStatus(incidentId, StatusChange(to, note, v)) }
    fun assess(r: AssessRequest) = act("Assessment recorded") { api.assess(incidentId, r.copy(expectedVersion = v)) }
    fun assign(teamId: Int, instructions: String?, reason: String?, ackMinutes: Int?) =
        act("Assignment sent to the team. It is not confirmed until they accept.") { api.assign(incidentId, AssignRequest(teamId, instructions, ackMinutes, reason, v)) }
    fun respond(assignmentId: Int, accept: Boolean, reason: String?) =
        act(if (accept) "Assignment accepted" else "Assignment declined") { api.respond(assignmentId, RespondRequest(accept, reason)) }
    fun update(kind: String, text: String) = act("Update recorded") { api.addUpdate(incidentId, UpdateRequest(kind, text)) }
    fun refer(r: ReferralRequest) = act("Referral recorded. This does not confirm that the organisation responded.") { api.refer(incidentId, r) }
    fun referralUpdate(id: Int, p: ReferralPatch) = act("Referral updated") { api.updateReferral(id, p) }
    fun escalate(reason: String, categoryId: Int) = act("Escalated to emergency") { api.escalateToEmergency(incidentId, EscalateRequest(reason, categoryId)) }
    fun allocate(resourceId: Int) = act("Resource allocated") { api.allocate(incidentId, AllocateRequest(resourceId)) }
    fun returnResource(allocationId: Int) = act("Return recorded") { api.returnResource(allocationId) }
    fun uploadPhoto(bytes: ByteArray) = act("Photo uploaded") {
        api.uploadAttachment(incidentId, MultipartBody.Part.createFormData("file", "photo.jpg", bytes.toRequestBody("image/jpeg".toMediaType())))
    }
}

private enum class Sheet { STATUS, ASSESS, ASSIGN, DECLINE, UPDATE, REFER, ESCALATE, ALLOCATE }

@Composable
fun IncidentDetailScreen(id: Int, nav: NavHostController, vm: IncidentDetailViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var statusTarget by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    LaunchedEffect(id) { vm.incidentId = id; vm.reload(); vm.loadOptions() }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let { context.contentResolver.openInputStream(it)?.use { s -> vm.uploadPhoto(s.readBytes()) } }
    }

    val d = state.data
    GradientScaffold(
        title = d?.reference ?: "Incident",
        subtitle = d?.category,
        onBack = { nav.popBackStack() },
        actions = {
            if (d?.latitude != null) IconButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${d.latitude},${d.longitude}?q=${d.latitude},${d.longitude}(${d.reference})")))
            }) { Icon(Icons.Default.Map, "Open in maps", tint = Color.White) }
        },
        bottomBar = { message?.let { Snackbar(Modifier.padding(12.dp), action = { TextButton(onClick = vm::clearMessage) { Text("OK") } }) { Text(it) } } },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.error?.let { item { ErrorBanner(it) { vm.reload() } } }
            if (d == null) { if (state.loading) item { LoadingBox() }; return@LazyColumn }

            item {
                SectionCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusBadge(d.status)
                        if (d.kind == "EMERGENCY") PriorityBadge(d.effectivePriority, suggested = d.priority == null && d.suggestedPriority != null)
                        else Badge("Service request", Low)
                        if (d.isDemo) Badge("Demo", High)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(d.description, style = MaterialTheme.typography.bodyLarge)
                    if (d.escalatedFromRoutine) Text("Escalated from a routine service request", color = High)
                }
            }
            item { ActionsCard(d, busy, onStatus = { statusTarget = it; sheet = Sheet.STATUS }, onSheet = { sheet = it },
                onAccept = { d.pendingAssignmentId?.let { a -> vm.respond(a, true, null) } },
                onPhoto = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) }
            item {
                SectionCard("Location") {
                    InfoRow("GPS", d.latitude?.let { "%.5f, %.5f (±%.0f m)".format(it, d.longitude, d.accuracyM ?: 0.0) } ?: "Not captured")
                    InfoRow("Source", humanise(d.locationSource))
                    InfoRow("Ward", d.ward)
                    InfoRow("Landmark", d.landmark)
                }
            }
            item {
                SectionCard("Details") {
                    InfoRow("Reported", formatTime(d.reportedAt))
                    InfoRow("Received", formatTime(d.receivedAt))
                    InfoRow("Occurred", if (d.occurredAtUnknown) "Unknown" else d.occurredAt?.let(::formatTime))
                    InfoRow("Acknowledged", d.acknowledgedAt?.let(::formatTime) ?: "Not yet")
                    InfoRow("Deaths reported", tri(d.fatalities))
                    InfoRow("Injuries", tri(d.injuries))
                    InfoRow("Immediate danger", tri(d.immediateDanger))
                    InfoRow("Threat to life", tri(d.threatToLife))
                    InfoRow("People affected", if (d.affectedPeopleUnknown) "Unknown" else d.affectedPeople?.toString())
                    InfoRow("Source", humanise(d.source))
                    if (d.reporterName != null || d.reporterPhone != null) {
                        InfoRow("Reporter", d.reporterName)
                        d.reporterPhone?.let { phone ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                InfoRow("Phone", phone)
                                IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))) }) { Icon(Icons.Default.Call, "Call") }
                            }
                        }
                    }
                }
            }
            if (d.attachments.isNotEmpty()) item {
                SectionCard("Photos") {
                    val loader = remember { ImageLoader.Builder(context).okHttpClient(vm.imageLoader).build() }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(d.attachments) { a ->
                            AsyncImage(model = "${vm.serverUrl}api/v1/attachments/${a.id}", contentDescription = a.filename, imageLoader = loader,
                                contentScale = ContentScale.Crop, modifier = Modifier.size(120.dp).clip(RoundedCornerShape(14.dp)))
                        }
                    }
                }
            }
            if (d.assignments.isNotEmpty()) item {
                SectionCard("Assignments") {
                    d.assignments.forEachIndexed { i, a ->
                        if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text("${a.team} · ${humanise(a.status)}", style = MaterialTheme.typography.titleMedium)
                        a.instructions?.let { Text(it) }
                        InfoRow("Assigned", "${formatTime(a.createdAt)} by ${a.assignedBy ?: "—"}")
                        InfoRow("Respond by", formatTime(a.ackDueAt))
                        a.respondedAt?.let { InfoRow("Responded", formatTime(it)) }
                        a.declineReason?.let { InfoRow("Declined because", it) }
                        a.reassignReason?.let { InfoRow("Reassigned because", it) }
                        a.departedAt?.let { InfoRow("Departed", formatTime(it)) }
                        a.arrivedAt?.let { InfoRow("Arrived", formatTime(it)) }
                        a.completedAt?.let { InfoRow("Completed", formatTime(it)) }
                    }
                }
            }
            if (d.updates.isNotEmpty()) item {
                SectionCard("Field updates") {
                    d.updates.forEach { u -> Text("${humanise(u.kind)} · ${u.by ?: ""} · ${formatTime(u.at)}", style = MaterialTheme.typography.labelLarge); Text(u.text); Spacer(Modifier.height(8.dp)) }
                }
            }
            if (d.referrals.isNotEmpty()) item {
                SectionCard("External referrals") {
                    d.referrals.forEach { r ->
                        Text(r.organisation, style = MaterialTheme.typography.titleMedium)
                        InfoRow("Referred", "${formatTime(r.referredAt)} by ${humanise(r.method)}")
                        InfoRow("Acknowledgement", humanise(r.ackStatus))
                        r.externalReference?.let { InfoRow("Their reference", it) }
                        InfoRow("Information shared", r.infoShared)
                        r.outcome?.let { InfoRow("Outcome", it) }
                        if (d.canUpdateReferrals) Row {
                            if (r.ackStatus == "NOT_CONFIRMED") {
                                TextButton(onClick = { vm.referralUpdate(r.id, ReferralPatch(ackStatus = "ACKNOWLEDGED")) }) { Text("Mark acknowledged") }
                                TextButton(onClick = { vm.referralUpdate(r.id, ReferralPatch(ackStatus = "DECLINED")) }) { Text("Mark declined") }
                            }
                        }
                    }
                }
            }
            if (d.resources.isNotEmpty()) item {
                SectionCard("Resources") {
                    d.resources.forEach { a ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(a.resource, style = MaterialTheme.typography.titleMedium)
                                Text("Allocated ${formatTime(a.allocatedAt)}" + (a.returnedAt?.let { " · returned ${formatTime(it)}" } ?: ""))
                            }
                            if (a.returnedAt == null && d.canAllocateResources) TextButton(onClick = { vm.returnResource(a.id) }) { Text("Record return") }
                        }
                    }
                }
            }
            if (d.assessments.isNotEmpty()) item {
                SectionCard("Priority assessments") {
                    d.assessments.forEach { a ->
                        Text("${a.original ?: "None"} → ${a.new}  (suggested ${a.suggested ?: "—"})", style = MaterialTheme.typography.titleMedium)
                        Text("${a.officer ?: ""} · ${formatTime(a.at)}" + (a.reason?.let { " · $it" } ?: ""))
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
            if (d.escalations.isNotEmpty()) item {
                SectionCard("Escalations") {
                    d.escalations.forEach { e -> Text("Level ${e.level} · ${formatTime(e.at)}", color = Critical); Text("${e.reason} (notified ${e.notifiedCount}: ${e.notifiedRoles})"); Spacer(Modifier.height(6.dp)) }
                }
            }
            if (d.notifications.isNotEmpty()) item {
                SectionCard("Notifications") {
                    d.notifications.forEach { n ->
                        Text(n.title, style = MaterialTheme.typography.labelLarge)
                        Text("${n.recipient} · ${if (n.read) "read" else "not read"} · " + n.attempts.joinToString { "${it.channel}: ${humanise(it.status)}" },
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
            item {
                SectionCard("Status history") {
                    d.statusHistory.forEach { h ->
                        Text("${humanise(h.to)} · ${formatTime(h.at)}", style = MaterialTheme.typography.labelLarge)
                        listOfNotNull(h.by, h.note).joinToString(" · ").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }

    val detail = d ?: return
    when (sheet) {
        Sheet.STATUS -> TextDialog("Change status to ${humanise(statusTarget ?: "")}", "Note or reason" +
            if (statusTarget in listOf("CANCELLED", "UNABLE_TO_RESPOND", "REJECTED")) " (required)" else " (optional)",
            required = statusTarget in listOf("CANCELLED", "UNABLE_TO_RESPOND", "REJECTED"),
            onDismiss = { sheet = null }) { vm.status(statusTarget!!, it.ifBlank { null }); sheet = null }
        Sheet.DECLINE -> TextDialog("Decline assignment", "Reason for declining (required)", required = true, onDismiss = { sheet = null }) {
            detail.pendingAssignmentId?.let { a -> vm.respond(a, false, it) }; sheet = null
        }
        Sheet.UPDATE -> UpdateDialog(onDismiss = { sheet = null }) { kind, text -> vm.update(kind, text); sheet = null }
        Sheet.ASSESS -> AssessDialog(detail, onDismiss = { sheet = null }) { vm.assess(it); sheet = null }
        Sheet.ASSIGN -> AssignDialog(detail, vm.teams.collectAsState().value, onDismiss = { sheet = null }) { t, i, r, m -> vm.assign(t, i, r, m); sheet = null }
        Sheet.REFER -> ReferDialog(vm.cfg.collectAsState().value, onDismiss = { sheet = null }) { vm.refer(it); sheet = null }
        Sheet.ESCALATE -> EscalateDialog(vm.cfg.collectAsState().value, onDismiss = { sheet = null }) { r, c -> vm.escalate(r, c); sheet = null }
        Sheet.ALLOCATE -> AllocateDialog(vm.resources.collectAsState().value, onDismiss = { sheet = null }) { vm.allocate(it); sheet = null }
        null -> Unit
    }
}

private fun tri(v: String?) = when (v) { "YES" -> "Yes"; "NO" -> "No"; "UNKNOWN" -> "Unknown"; else -> null }

@Composable
private fun ActionsCard(d: IncidentDetail, busy: Boolean, onStatus: (String) -> Unit, onSheet: (Sheet) -> Unit, onAccept: () -> Unit, onPhoto: () -> Unit) {
    val anything = d.allowedStatusChanges.isNotEmpty() || d.canAssess || d.canAssign || d.canRefer || d.canEscalateToEmergency ||
        d.canAddUpdate || d.canAllocateResources || d.pendingAssignmentId != null
    if (!anything) {
        SectionCard { OutlinedButton(onClick = onPhoto, enabled = !busy) { Text("Add photo") } }
        return
    }
    SectionCard("Actions") {
        if (d.pendingAssignmentId != null) {
            Text("Your team has been assigned this incident.", color = Critical)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = Emerald)) { Text("Accept") }
                OutlinedButton(onClick = { onSheet(Sheet.DECLINE) }, enabled = !busy) { Text("Decline") }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (d.allowedStatusChanges.isNotEmpty()) {
            Text("Move to:", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                d.allowedStatusChanges.forEach { s -> OutlinedButton(onClick = { onStatus(s) }, enabled = !busy) { Text(humanise(s)) } }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (d.canAssess) Button(onClick = { onSheet(Sheet.ASSESS) }, enabled = !busy) { Text("Assess priority") }
            if (d.canAssign) Button(onClick = { onSheet(Sheet.ASSIGN) }, enabled = !busy) { Text(if (d.assignments.any { it.status in listOf("PENDING", "ACCEPTED") }) "Reassign" else "Assign team") }
            if (d.canAddUpdate) OutlinedButton(onClick = { onSheet(Sheet.UPDATE) }, enabled = !busy) { Text("Add update") }
            if (d.canRefer) OutlinedButton(onClick = { onSheet(Sheet.REFER) }, enabled = !busy) { Text("Refer externally") }
            if (d.canAllocateResources) OutlinedButton(onClick = { onSheet(Sheet.ALLOCATE) }, enabled = !busy) { Text("Allocate resource") }
            if (d.canEscalateToEmergency) Button(onClick = { onSheet(Sheet.ESCALATE) }, enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Critical)) { Text("Escalate to emergency") }
            OutlinedButton(onClick = onPhoto, enabled = !busy) { Text("Add photo") }
        }
    }
}

@Composable
private fun TextDialog(title: String, label: String, required: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, label = { Text(label) }, minLines = 2, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { Button(onClick = { onConfirm(text.trim()) }, enabled = !required || text.isNotBlank()) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun UpdateDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    val kinds = listOf("PROGRESS", "ACTION", "OBSERVATION", "ASSISTANCE_REQUEST", "RESOURCE_REQUEST", "COMPLETION", "NOTE")
    var kind by remember { mutableStateOf("PROGRESS") }
    var text by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Field update") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Dropdown("Type", kinds, kind, ::humanise, { kind = it }, Modifier.fillMaxWidth())
            OutlinedTextField(text, { text = it }, label = { Text("What happened / what is needed") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { Button(onClick = { onConfirm(kind, text.trim()) }, enabled = text.trim().length >= 2) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun AssessDialog(d: IncidentDetail, onDismiss: () -> Unit, onConfirm: (AssessRequest) -> Unit) {
    var priority by remember { mutableStateOf(d.priority ?: d.suggestedPriority ?: "MEDIUM") }
    var reason by remember { mutableStateOf("") }
    var threat by remember { mutableStateOf(d.threatToLife) }
    var injuries by remember { mutableStateOf(d.injuries) }
    var danger by remember { mutableStateOf(d.immediateDanger) }
    var worsen by remember { mutableStateOf(d.mayWorsen) }
    val needsReason = d.priority != null && d.priority != priority
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Assess priority") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("System suggestion: ${d.suggestedPriority ?: "none"}. The final decision is yours.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TriStateField("Threat to life", threat) { threat = it }
            TriStateField("Injuries", injuries) { injuries = it }
            TriStateField("Immediate danger", danger) { danger = it }
            TriStateField("May get worse", worsen) { worsen = it }
            Dropdown("Priority", listOf("CRITICAL", "HIGH", "MEDIUM", "LOW"), priority, ::humanise, { priority = it }, Modifier.fillMaxWidth())
            OutlinedTextField(reason, { reason = it }, label = { Text(if (needsReason) "Reason for change (required)" else "Reason (optional)") }, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = {
        Button(onClick = { onConfirm(AssessRequest(priority, reason.ifBlank { null }, threat, injuries, danger, worsen)) },
            enabled = !needsReason || reason.isNotBlank()) { Text("Save assessment") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun AssignDialog(d: IncidentDetail, teams: List<Team>, onDismiss: () -> Unit, onConfirm: (Int, String?, String?, Int?) -> Unit) {
    var team by remember { mutableStateOf<Team?>(null) }
    var instructions by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("") }
    val reassign = d.assignments.any { it.status in listOf("PENDING", "ACCEPTED") }
    val usable = teams.filter { it.active }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (reassign) "Reassign incident" else "Assign a response team") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (usable.isEmpty()) Text("No response teams are registered.", color = High)
            Dropdown("Team", usable, team, { "${it.name} – ${humanise(it.availability)} (${it.activeAssignments} active)" }, { team = it }, Modifier.fillMaxWidth())
            team?.takeIf { it.availability in listOf("UNAVAILABLE", "OFF_DUTY") }?.let { Text("${it.name} is ${humanise(it.availability)} and cannot be assigned.", color = Critical) }
            OutlinedTextField(instructions, { instructions = it }, label = { Text("Instructions") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(4) }, label = { Text("Acknowledge within (minutes, optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (reassign) OutlinedTextField(reason, { reason = it }, label = { Text("Reason for reassignment (required)") }, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = {
        Button(onClick = { onConfirm(team!!.id, instructions.ifBlank { null }, reason.ifBlank { null }, minutes.toIntOrNull()) },
            enabled = team != null && team?.availability !in listOf("UNAVAILABLE", "OFF_DUTY") && (!reassign || reason.isNotBlank())) { Text("Assign") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun ReferDialog(cfg: ConfigResponse?, onDismiss: () -> Unit, onConfirm: (ReferralRequest) -> Unit) {
    val orgs = cfg?.organisations?.filter { it.active }.orEmpty()
    var org by remember { mutableStateOf(orgs.firstOrNull()) }
    var method by remember { mutableStateOf("PHONE") }
    var contact by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    var ref by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Record external referral") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Record a referral you have made by phone or another approved method. The app does not contact the organisation.",
                style = MaterialTheme.typography.bodyMedium)
            if (orgs.isEmpty()) Text("No external organisations are configured. Ask an administrator.", color = High)
            Dropdown("Organisation", orgs, org, { "${it.name} (${it.orgType})" }, { org = it }, Modifier.fillMaxWidth())
            Dropdown("Method", listOf("PHONE", "IN_PERSON", "RADIO", "LETTER", "OTHER"), method, ::humanise, { method = it }, Modifier.fillMaxWidth())
            OutlinedTextField(contact, { contact = it }, label = { Text("Contact person or office") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(info, { info = it }, label = { Text("Information shared (required)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(ref, { ref = it }, label = { Text("Their reference number (if given)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = {
        Button(onClick = { onConfirm(ReferralRequest(org!!.id, contact.ifBlank { null }, method, info.trim(), ref.ifBlank { null })) },
            enabled = org != null && info.trim().length >= 3) { Text("Record referral") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun EscalateDialog(cfg: ConfigResponse?, onDismiss: () -> Unit, onConfirm: (String, Int) -> Unit) {
    val cats = cfg?.categories?.filter { it.active && it.kind == "EMERGENCY" }.orEmpty()
    var cat by remember { mutableStateOf(cats.firstOrNull()) }
    var reason by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Escalate to emergency") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Use this when a routine problem now presents an immediate danger.")
            Dropdown("Emergency category", cats, cat, { it.name }, { cat = it }, Modifier.fillMaxWidth())
            OutlinedTextField(reason, { reason = it }, label = { Text("Why is this now an emergency?") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { Button(onClick = { onConfirm(reason.trim(), cat!!.id) }, enabled = cat != null && reason.trim().length >= 5) { Text("Escalate") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun AllocateDialog(resources: List<Resource>, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val available = resources.filter { it.status == "AVAILABLE" }
    var res by remember { mutableStateOf<Resource?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Allocate a resource") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (available.isEmpty()) Text("No registered resources are currently available.", color = High)
            Dropdown("Available resources", available, res, { "${it.name} (${it.category})" }, { res = it }, Modifier.fillMaxWidth())
        }
    }, confirmButton = { Button(onClick = { onConfirm(res!!.id) }, enabled = res != null) { Text("Allocate") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
