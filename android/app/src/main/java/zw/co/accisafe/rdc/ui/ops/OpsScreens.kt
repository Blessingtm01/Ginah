package zw.co.accisafe.rdc.ui.ops

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import zw.co.accisafe.rdc.data.apiCall
import zw.co.accisafe.rdc.data.net.ApiClient
import zw.co.accisafe.rdc.data.net.AvailabilityRequest
import zw.co.accisafe.rdc.data.net.DirectoryUser
import zw.co.accisafe.rdc.data.net.MaintenanceRequest
import zw.co.accisafe.rdc.data.net.Me
import zw.co.accisafe.rdc.data.net.MyAssignment
import zw.co.accisafe.rdc.data.net.Resource
import zw.co.accisafe.rdc.data.net.ResourceRequest
import zw.co.accisafe.rdc.data.net.ResourceStatusRequest
import zw.co.accisafe.rdc.data.net.RespondRequest
import zw.co.accisafe.rdc.data.net.Team
import zw.co.accisafe.rdc.data.net.TeamRequest
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
import zw.co.accisafe.rdc.ui.components.formatTime
import zw.co.accisafe.rdc.ui.components.humanise
import zw.co.accisafe.rdc.ui.theme.Critical
import zw.co.accisafe.rdc.ui.theme.Emerald
import zw.co.accisafe.rdc.ui.theme.High
import zw.co.accisafe.rdc.ui.theme.Low
import zw.co.accisafe.rdc.ui.theme.Resolved
import java.time.Instant
import javax.inject.Inject

fun availabilityColor(a: String) = when (a) {
    "AVAILABLE" -> Resolved
    "ASSIGNED", "BUSY" -> High
    "AWAITING_CONFIRMATION" -> Emerald
    else -> Low
}

fun resourceColor(s: String) = when (s) {
    "AVAILABLE" -> Resolved
    "ASSIGNED" -> Emerald
    "UNDER_MAINTENANCE" -> High
    else -> Critical
}

@Composable
private fun MessageBar(message: String?, clear: () -> Unit) {
    message?.let { Snackbar(Modifier.padding(12.dp), action = { TextButton(onClick = clear) { Text("OK") } }) { Text(it) } }
}

// --- assignment inbox ------------------------------------------------------------------------

@HiltViewModel
class InboxViewModel @Inject constructor(client: ApiClient) : RemoteViewModel<List<MyAssignment>>(client) {
    override suspend fun fetch() = api.myAssignments()
    fun respond(id: Int, accept: Boolean, reason: String?) =
        act(if (accept) "Accepted" else "Declined") { api.respond(id, RespondRequest(accept, reason)) }
}

@Composable
fun AssignmentInboxScreen(nav: NavHostController, vm: InboxViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    var declining by remember { mutableStateOf<MyAssignment?>(null) }
    LaunchedEffect(Unit) { vm.reload() }
    GradientScaffold("Team assignments", subtitle = "Accept or decline promptly", onBack = { nav.popBackStack() },
        bottomBar = { MessageBar(message, vm::clearMessage) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.error?.let { item { ErrorBanner(it) { vm.reload() } } }
            if (state.loading && state.data == null) item { LoadingBox() }
            if (state.data?.isEmpty() == true) item { EmptyState("No assignments", "Assignments for your team will appear here.") }
            items(state.data.orEmpty(), key = { it.assignmentId }) { a ->
                SectionCard("${a.incident.category} · ${a.incident.reference}", Modifier.clickable { nav.navigate(Routes.incident(a.incident.id)) }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Badge(humanise(a.status), if (a.status == "PENDING") Critical else Emerald)
                        PriorityBadge(a.incident.effectivePriority)
                    }
                    Spacer(Modifier.height(6.dp))
                    InfoRow("Team", a.team)
                    InfoRow("Where", a.incident.ward ?: a.incident.landmark ?: "See map")
                    InfoRow("Respond by", formatTime(a.ackDueAt))
                    a.instructions?.let { InfoRow("Instructions", it) }
                    if (a.status == "PENDING") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.respond(a.assignmentId, true, null) }, enabled = !busy) { Text("Accept") }
                        OutlinedButton(onClick = { declining = a }, enabled = !busy) { Text("Decline") }
                    }
                }
            }
        }
    }
    declining?.let { a ->
        var reason by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { declining = null }, title = { Text("Decline ${a.incident.reference}") },
            text = { OutlinedTextField(reason, { reason = it }, label = { Text("Reason (required)") }, minLines = 2) },
            confirmButton = { Button(onClick = { vm.respond(a.assignmentId, false, reason.trim()); declining = null }, enabled = reason.isNotBlank()) { Text("Decline") } },
            dismissButton = { TextButton(onClick = { declining = null }) { Text("Cancel") } })
    }
}

// --- teams -----------------------------------------------------------------------------------

@HiltViewModel
class TeamsViewModel @Inject constructor(client: ApiClient) : RemoteViewModel<List<Team>>(client) {
    val directory = MutableStateFlow<List<DirectoryUser>>(emptyList())
    override suspend fun fetch() = api.teams()
    fun loadDirectory() = viewModelScope.launch { apiCall { api.directory() }.onSuccess { directory.value = it } }
    fun create(r: TeamRequest) = act("Team registered") { api.createTeam(r) }
}

@Composable
fun TeamsScreen(me: Me, nav: NavHostController, vm: TeamsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    val message by vm.message.collectAsState()
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.reload() }
    GradientScaffold("Response teams", onBack = { nav.popBackStack() },
        floatingActionButton = { if (me.can("teams.manage")) FloatingActionButton(onClick = { vm.loadDirectory(); creating = true }) { Icon(Icons.Default.Add, "Register team") } },
        bottomBar = { MessageBar(message, vm::clearMessage) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.error?.let { item { ErrorBanner(it) { vm.reload() } } }
            if (state.loading && state.data == null) item { LoadingBox() }
            if (state.data?.isEmpty() == true) item { EmptyState("No teams registered", "Only teams the council actually has should be registered.") }
            items(state.data.orEmpty(), key = { it.id }) { t ->
                SectionCard(t.name, Modifier.clickable { nav.navigate(Routes.team(t.id)) }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Badge(humanise(t.availability), availabilityColor(t.availability))
                        if (!t.active) Badge("Inactive", Low)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("${t.category} · ${t.activeAssignments} active assignment(s)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    t.leader?.let { Text("Leader: $it") }
                }
            }
        }
    }
    if (creating) TeamDialog(vm.directory.collectAsState().value, onDismiss = { creating = false }) { vm.create(it); creating = false }
}

@Composable
private fun TeamDialog(directory: List<DirectoryUser>, onDismiss: () -> Unit, onSave: (TeamRequest) -> Unit) {
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Roads and infrastructure") }
    var area by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var leader by remember { mutableStateOf<DirectoryUser?>(null) }
    val members = remember { mutableStateListOf<Int>() }
    val categories = listOf("Fire and rescue", "Roads and infrastructure", "Disaster-risk management", "Water and sanitation",
        "Environmental health", "General technical services", "Other")
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Register a response team") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Team name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Dropdown("Category", categories, category, { it }, { category = it }, Modifier.fillMaxWidth())
            Dropdown("Team leader", directory.filter { "TEAM_LEADER" in it.roles }, leader, { it.fullName }, { leader = it }, Modifier.fillMaxWidth())
            OutlinedTextField(phone, { phone = it }, label = { Text("Contact phone") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(area, { area = it }, label = { Text("Operational area") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Members", style = MaterialTheme.typography.titleMedium)
            directory.filter { "FIELD_OFFICER" in it.roles || "TEAM_LEADER" in it.roles }.forEach { u ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(u.id in members, { if (it) members.add(u.id) else members.remove(u.id) })
                    Text(u.fullName)
                }
            }
        }
    }, confirmButton = {
        Button(onClick = { onSave(TeamRequest(name.trim(), category, leader?.id, members.toList(), phone.ifBlank { null }, area.ifBlank { null })) },
            enabled = name.trim().length >= 2) { Text("Save") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@HiltViewModel
class TeamDetailViewModel @Inject constructor(client: ApiClient) : RemoteViewModel<Team>(client) {
    var teamId = 0
    override suspend fun fetch() = api.team(teamId)
    fun setAvailability(a: String) = act("Availability updated") { api.setAvailability(teamId, AvailabilityRequest(a)) }
}

@Composable
fun TeamDetailScreen(id: Int, me: Me, nav: NavHostController, vm: TeamDetailViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    LaunchedEffect(id) { vm.teamId = id; vm.reload() }
    val t = state.data
    GradientScaffold(t?.name ?: "Team", subtitle = t?.category, onBack = { nav.popBackStack() }, bottomBar = { MessageBar(message, vm::clearMessage) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.error?.let { item { ErrorBanner(it) { vm.reload() } } }
            if (t == null) { if (state.loading) item { LoadingBox() }; return@LazyColumn }
            item {
                SectionCard("Availability") {
                    Badge(humanise(t.availability), availabilityColor(t.availability))
                    Spacer(Modifier.height(8.dp))
                    val canChange = me.can("teams.update_availability") || me.can("teams.manage") || me.can("incidents.assign")
                    if (canChange) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("AVAILABLE", "BUSY", "UNAVAILABLE", "OFF_DUTY").chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { a -> OutlinedButton(onClick = { vm.setAvailability(a) }, enabled = !busy && a != t.availability) { Text(humanise(a)) } }
                            }
                        }
                    }
                }
            }
            item {
                SectionCard("Details") {
                    InfoRow("Leader", t.leader)
                    InfoRow("Contact", t.contactPhone)
                    InfoRow("Area", t.operationalArea)
                    InfoRow("Capabilities", t.capabilities)
                    InfoRow("Notes", t.notes)
                    t.members?.let { InfoRow("Members", it.joinToString { m -> m.fullName }.ifBlank { "None" }) }
                }
            }
            item { Text("Active assignments", style = MaterialTheme.typography.titleMedium) }
            if (t.assignments.isNullOrEmpty()) item { EmptyState("No active assignments", "This team is not working on any incident.") }
            items(t.assignments.orEmpty()) { a ->
                SectionCard("${a.category} · ${a.reference}", Modifier.clickable { nav.navigate(Routes.incident(a.incidentId)) }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { StatusBadge(a.incidentStatus); PriorityBadge(a.priority) }
                    Text("Assignment: ${humanise(a.status)}")
                }
            }
        }
    }
}

// --- resources -------------------------------------------------------------------------------

@HiltViewModel
class ResourcesViewModel @Inject constructor(client: ApiClient) : RemoteViewModel<List<Resource>>(client) {
    override suspend fun fetch() = api.resources()
    fun create(r: ResourceRequest) = act("Resource registered") { api.createResource(r) }
}

@Composable
fun ResourcesScreen(me: Me, nav: NavHostController, vm: ResourcesViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    val message by vm.message.collectAsState()
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.reload() }
    GradientScaffold("Resource register", subtitle = state.data?.let { "${it.count { r -> r.status == "AVAILABLE" }} of ${it.size} available" },
        onBack = { nav.popBackStack() },
        floatingActionButton = { if (me.can("resources.manage")) FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Default.Add, "Register resource") } },
        bottomBar = { MessageBar(message, vm::clearMessage) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.error?.let { item { ErrorBanner(it) { vm.reload() } } }
            if (state.loading && state.data == null) item { LoadingBox() }
            if (state.data?.isEmpty() == true) item { EmptyState("No resources registered", "Register only equipment the council actually owns.") }
            items(state.data.orEmpty(), key = { it.id }) { r ->
                SectionCard(r.name, Modifier.clickable { nav.navigate(Routes.resource(r.id)) }) {
                    Badge(humanise(r.status), resourceColor(r.status))
                    Spacer(Modifier.height(4.dp))
                    Text(listOfNotNull(r.category, r.assetNumber, r.department).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    r.currentAssignment?.let { Text("On incident ${it.reference}") }
                }
            }
        }
    }
    if (creating) ResourceDialog(onDismiss = { creating = false }) { vm.create(it); creating = false }
}

@Composable
private fun ResourceDialog(onDismiss: () -> Unit, onSave: (ResourceRequest) -> Unit) {
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Vehicle") }
    var asset by remember { mutableStateOf("") }
    var dept by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    val categories = listOf("Vehicle", "Firefighting equipment", "Communication device", "Protective equipment", "Tools", "Emergency supplies", "Other")
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Register a resource") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Dropdown("Category", categories, category, { it }, { category = it }, Modifier.fillMaxWidth())
            OutlinedTextField(asset, { asset = it }, label = { Text("Registration / asset number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(dept, { dept = it }, label = { Text("Department") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(location, { location = it }, label = { Text("Current location") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = {
        Button(onClick = { onSave(ResourceRequest(name.trim(), category, asset.ifBlank { null }, dept.ifBlank { null }, location.ifBlank { null })) },
            enabled = name.trim().length >= 2) { Text("Save") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@HiltViewModel
class ResourceDetailViewModel @Inject constructor(client: ApiClient) : RemoteViewModel<Resource>(client) {
    var resourceId = 0
    override suspend fun fetch() = api.resource(resourceId)
    fun setStatus(s: String, reason: String?) = act("Status updated") { api.resourceStatus(resourceId, ResourceStatusRequest(s, reason)) }
    fun addMaintenance(text: String) = act("Maintenance recorded") { api.addMaintenance(resourceId, MaintenanceRequest(Instant.now().toString(), text)) }
}

@Composable
fun ResourceDetailScreen(id: Int, me: Me, nav: NavHostController, vm: ResourceDetailViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    var maintenance by remember { mutableStateOf(false) }
    LaunchedEffect(id) { vm.resourceId = id; vm.reload() }
    val r = state.data
    GradientScaffold(r?.name ?: "Resource", subtitle = r?.category, onBack = { nav.popBackStack() }, bottomBar = { MessageBar(message, vm::clearMessage) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.error?.let { item { ErrorBanner(it) { vm.reload() } } }
            if (r == null) { if (state.loading) item { LoadingBox() }; return@LazyColumn }
            item {
                SectionCard("Status") {
                    Badge(humanise(r.status), resourceColor(r.status))
                    r.currentAssignment?.let {
                        Text("Allocated to incident ${it.reference}. Record its return from the incident page.",
                            Modifier.clickable { nav.navigate(Routes.incident(it.incidentId)) }.padding(top = 6.dp), color = MaterialTheme.colorScheme.primary)
                    }
                    if (me.can("resources.manage") || me.can("resources.allocate")) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("AVAILABLE", "UNDER_MAINTENANCE", "OUT_OF_SERVICE").forEach { s ->
                                OutlinedButton(onClick = { vm.setStatus(s, null) }, enabled = !busy && s != r.status) { Text(humanise(s), maxLines = 1) }
                            }
                        }
                    }
                }
            }
            item {
                SectionCard("Details") {
                    InfoRow("Asset number", r.assetNumber)
                    InfoRow("Department", r.department)
                    InfoRow("Location", r.location)
                    InfoRow("Inspected", r.inspectionDate?.let(::formatTime))
                    InfoRow("Notes", r.notes)
                }
            }
            item {
                SectionCard("Maintenance history", action = {
                    if (me.can("resources.manage")) TextButton(onClick = { maintenance = true }) { Text("Add") }
                }) {
                    if (r.maintenance.isNullOrEmpty()) Text("No maintenance recorded.")
                    r.maintenance.orEmpty().forEach { Text("${formatTime(it.performedAt)}: ${it.description}") }
                }
            }
            item {
                SectionCard("Recent allocations") {
                    if (r.allocations.isNullOrEmpty()) Text("Never allocated.")
                    r.allocations.orEmpty().forEach {
                        Text("Incident #${it.incidentId}: ${formatTime(it.allocatedAt)} → ${it.returnedAt?.let(::formatTime) ?: "not returned"}",
                            Modifier.clickable { nav.navigate(Routes.incident(it.incidentId)) })
                    }
                }
            }
        }
    }
    if (maintenance) {
        var text by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { maintenance = false }, title = { Text("Record maintenance") },
            text = { OutlinedTextField(text, { text = it }, label = { Text("Work done") }, minLines = 2) },
            confirmButton = { Button(onClick = { vm.addMaintenance(text.trim()); maintenance = false }, enabled = text.trim().length >= 3) { Text("Save") } },
            dismissButton = { TextButton(onClick = { maintenance = false }) { Text("Cancel") } })
    }
}
