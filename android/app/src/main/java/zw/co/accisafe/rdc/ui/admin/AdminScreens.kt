package zw.co.accisafe.rdc.ui.admin

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
import androidx.compose.material3.Switch
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
import androidx.navigation.NavHostController
import dagger.hilt.android.lifecycle.HiltViewModel
import zw.co.accisafe.rdc.data.net.ApiClient
import zw.co.accisafe.rdc.data.net.AuditItem
import zw.co.accisafe.rdc.data.net.CategoryRequest
import zw.co.accisafe.rdc.data.net.ConfigResponse
import zw.co.accisafe.rdc.data.net.Page
import zw.co.accisafe.rdc.data.net.UserCreate
import zw.co.accisafe.rdc.data.net.UserDto
import zw.co.accisafe.rdc.data.net.UserPatch
import zw.co.accisafe.rdc.data.net.WardRequest
import zw.co.accisafe.rdc.ui.RemoteViewModel
import zw.co.accisafe.rdc.ui.components.Badge
import zw.co.accisafe.rdc.ui.components.Dropdown
import zw.co.accisafe.rdc.ui.components.EmptyState
import zw.co.accisafe.rdc.ui.components.ErrorBanner
import zw.co.accisafe.rdc.ui.components.GradientScaffold
import zw.co.accisafe.rdc.ui.components.LoadingBox
import zw.co.accisafe.rdc.ui.components.SectionCard
import zw.co.accisafe.rdc.ui.components.formatTime
import zw.co.accisafe.rdc.ui.components.humanise
import zw.co.accisafe.rdc.ui.theme.Low
import zw.co.accisafe.rdc.ui.theme.Resolved
import javax.inject.Inject

val ALL_ROLES = listOf("SYSTEM_ADMIN", "COORDINATOR", "MANAGEMENT", "TEAM_LEADER", "FIELD_OFFICER", "COMMUNITY_REPORTER", "AUDITOR")

@Composable
private fun MessageBar(message: String?, clear: () -> Unit) {
    message?.let { Snackbar(Modifier.padding(12.dp), action = { TextButton(onClick = clear) { Text("OK") } }) { Text(it) } }
}

// --- users ----------------------------------------------------------------------------------

@HiltViewModel
class UsersViewModel @Inject constructor(client: ApiClient) : RemoteViewModel<List<UserDto>>(client) {
    override suspend fun fetch() = api.users()
    fun create(u: UserCreate) = act("Account created. Give the user their temporary password in person.") { api.createUser(u) }
    fun patch(id: Int, p: UserPatch) = act("Account updated") { api.patchUser(id, p) }
}

@Composable
fun UsersScreen(nav: NavHostController, vm: UsersViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    val message by vm.message.collectAsState()
    var editing by remember { mutableStateOf<UserDto?>(null) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.reload() }
    GradientScaffold("Users and roles", subtitle = state.data?.let { "${it.count { u -> u.active }} active accounts" }, onBack = { nav.popBackStack() },
        floatingActionButton = { FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Default.Add, "Create account") } },
        bottomBar = { MessageBar(message, vm::clearMessage) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.error?.let { item { ErrorBanner(it) { vm.reload() } } }
            if (state.loading && state.data == null) item { LoadingBox() }
            items(state.data.orEmpty(), key = { it.id }) { u ->
                SectionCard(u.fullName, Modifier.clickable { editing = u }) {
                    Text("@${u.username}" + (u.phone?.let { " · $it" } ?: ""), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Badge(if (u.active) "Active" else "Deactivated", if (u.active) Resolved else Low)
                        u.roles.forEach { Badge(humanise(it), MaterialTheme.colorScheme.primary) }
                    }
                }
            }
        }
    }
    if (creating) UserDialog(null, onDismiss = { creating = false }) { roles, name, username, phone, password, _, _ ->
        vm.create(UserCreate(username, name, phone, null, roles, password!!)); creating = false
    }
    editing?.let { u ->
        UserDialog(u, onDismiss = { editing = null }) { roles, name, _, phone, password, active, reason ->
            vm.patch(u.id, UserPatch(fullName = name, phone = phone, roles = roles, active = active, resetPassword = password, reason = reason)); editing = null
        }
    }
}

@Composable
private fun UserDialog(
    existing: UserDto?, onDismiss: () -> Unit,
    onSave: (roles: List<String>, name: String, username: String, phone: String?, password: String?, active: Boolean, reason: String?) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.fullName ?: "") }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var phone by remember { mutableStateOf(existing?.phone ?: "") }
    var password by remember { mutableStateOf("") }
    var active by remember { mutableStateOf(existing?.active ?: true) }
    var reason by remember { mutableStateOf("") }
    val roles = remember { mutableStateListOf<String>().apply { addAll(existing?.roles ?: listOf("COMMUNITY_REPORTER")) } }
    val weak = password.isNotEmpty() && (password.length < 10 || password.all(Char::isDigit) || password.all(Char::isLetter))
    val passwordOk = if (existing == null) password.isNotEmpty() && !weak else !weak
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (existing == null) "Create account" else "Edit ${existing.username}") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Full name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (existing == null) OutlinedTextField(username, { username = it.lowercase().filter { c -> c.isLetterOrDigit() || c in "._-" } },
                label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(phone, { phone = it }, label = { Text("Phone") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text(if (existing == null) "Temporary password" else "Reset password (optional)") },
                singleLine = true, isError = weak, supportingText = { Text("User must change it at first sign-in") }, modifier = Modifier.fillMaxWidth())
            Text("Roles", style = MaterialTheme.typography.titleMedium)
            ALL_ROLES.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(r in roles, { if (it) roles.add(r) else roles.remove(r) }); Text(humanise(r))
                }
            }
            if (existing != null) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Account active", Modifier.weight(1f)); Switch(active, { active = it }) }
                OutlinedTextField(reason, { reason = it }, label = { Text("Reason for change (recorded in audit log)") }, modifier = Modifier.fillMaxWidth())
            }
        }
    }, confirmButton = {
        Button(onClick = { onSave(roles.toList(), name.trim(), username.trim(), phone.ifBlank { null }, password.ifBlank { null }, active, reason.ifBlank { null }) },
            enabled = name.length >= 2 && roles.isNotEmpty() && passwordOk && (existing != null || username.length >= 3)) { Text("Save") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

// --- wards and categories ----------------------------------------------------------------------

@HiltViewModel
class ConfigAdminViewModel @Inject constructor(client: ApiClient) : RemoteViewModel<ConfigResponse>(client) {
    override suspend fun fetch() = api.config()
    fun addWard(name: String, number: Int?) = act("Ward added") { api.addWard(WardRequest(name, number)) }
    fun toggleWard(id: Int, name: String, number: Int?, active: Boolean) = act("Ward updated") { api.editWard(id, WardRequest(name, number, active)) }
    fun addCategory(r: CategoryRequest) = act("Category added") { api.addCategory(r) }
    fun editCategory(id: Int, r: CategoryRequest) = act("Category updated") { api.editCategory(id, r) }
}

@Composable
fun ConfigAdminScreen(nav: NavHostController, vm: ConfigAdminViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    val message by vm.message.collectAsState()
    var ward by remember { mutableStateOf("") }
    var wardNo by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("EMERGENCY") }
    var prio by remember { mutableStateOf("MEDIUM") }
    LaunchedEffect(Unit) { vm.reload() }
    GradientScaffold("Wards and categories", onBack = { nav.popBackStack() }, bottomBar = { MessageBar(message, vm::clearMessage) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.error?.let { item { ErrorBanner(it) { vm.reload() } } }
            val c = state.data
            if (c == null) { if (state.loading) item { LoadingBox() }; return@LazyColumn }
            item {
                SectionCard("Wards and localities") {
                    Text("Enter the council's official ward list.", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(ward, { ward = it }, label = { Text("Ward name") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(wardNo, { wardNo = it.filter(Char::isDigit).take(3) }, label = { Text("No.") }, singleLine = true, modifier = Modifier.weight(0.4f))
                    }
                    Button(onClick = { vm.addWard(ward.trim(), wardNo.toIntOrNull()); ward = ""; wardNo = "" }, enabled = ward.isNotBlank()) { Text("Add ward") }
                    Spacer(Modifier.height(8.dp))
                    if (c.wards.isEmpty()) Text("No wards configured yet.")
                    c.wards.forEach { w ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(w.name, Modifier.weight(1f))
                            Switch(w.active, { vm.toggleWard(w.id, w.name, w.number, it) })
                        }
                    }
                }
            }
            item {
                SectionCard("Incident categories") {
                    OutlinedTextField(cat, { cat = it }, label = { Text("New category") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Dropdown("Type", listOf("EMERGENCY", "ROUTINE"), kind, ::humanise, { kind = it }, Modifier.weight(1f))
                        Dropdown("Default priority", listOf("CRITICAL", "HIGH", "MEDIUM", "LOW"), prio, ::humanise, { prio = it }, Modifier.weight(1f))
                    }
                    Button(onClick = { vm.addCategory(CategoryRequest(cat.trim(), kind, prio)); cat = "" }, enabled = cat.trim().length >= 2) { Text("Add category") }
                    Spacer(Modifier.height(8.dp))
                    c.categories.forEach { k ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(k.name)
                                Text("${humanise(k.kind)} · default ${humanise(k.defaultPriority)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(k.active, { vm.editCategory(k.id, CategoryRequest(k.name, k.kind, k.defaultPriority, it)) })
                        }
                    }
                }
            }
            item {
                SectionCard("Not editable here") {
                    Text("Council name, logo and contact details, escalation targets and SMS settings are configured by the system administrator " +
                        "through the server API. Server secrets and SMS credentials are never shown in the app.")
                }
            }
        }
    }
}

// --- audit log --------------------------------------------------------------------------------

@HiltViewModel
class AuditViewModel @Inject constructor(client: ApiClient) : RemoteViewModel<Page<AuditItem>>(client) {
    var page = 1
    override suspend fun fetch() = api.audit(page = page)
    fun go(p: Int) { page = p; reload() }
}

@Composable
fun AuditScreen(nav: NavHostController, vm: AuditViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.reload() }
    val p = state.data
    GradientScaffold("Audit log", subtitle = p?.let { "${it.total} records · read-only" }, onBack = { nav.popBackStack() }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.error?.let { item { ErrorBanner(it) { vm.reload() } } }
            if (state.loading && p == null) item { LoadingBox() }
            if (p?.items?.isEmpty() == true) item { EmptyState("No audit records", "Actions will be recorded here.") }
            items(p?.items.orEmpty(), key = { it.id }) { a ->
                SectionCard {
                    Text("${humanise(a.action)} · ${a.entity}${a.entityId?.let { " #$it" } ?: ""}", style = MaterialTheme.typography.titleMedium)
                    Text("${a.user} · ${formatTime(a.createdAt)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    a.oldValue?.let { Text("Before: $it", style = MaterialTheme.typography.bodyMedium) }
                    a.newValue?.let { Text("After: $it", style = MaterialTheme.typography.bodyMedium) }
                    a.reason?.let { Text("Reason: $it", style = MaterialTheme.typography.bodyMedium) }
                }
            }
            if (p != null && p.total > p.pageSize) item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.go(vm.page - 1) }, enabled = vm.page > 1) { Text("Newer") }
                    OutlinedButton(onClick = { vm.go(vm.page + 1) }, enabled = vm.page * p.pageSize < p.total) { Text("Older") }
                }
            }
        }
    }
}
