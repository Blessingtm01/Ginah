package zw.co.accisafe.rdc.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContactPhone
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import zw.co.accisafe.rdc.BuildConfig
import zw.co.accisafe.rdc.data.AuthRepository
import zw.co.accisafe.rdc.data.SessionStore
import zw.co.accisafe.rdc.data.apiCall
import zw.co.accisafe.rdc.data.net.ApiClient
import zw.co.accisafe.rdc.data.net.ChangePasswordRequest
import zw.co.accisafe.rdc.data.net.Me
import zw.co.accisafe.rdc.ui.Routes
import zw.co.accisafe.rdc.ui.components.ErrorBanner
import zw.co.accisafe.rdc.ui.components.GradientScaffold
import zw.co.accisafe.rdc.ui.components.InfoRow
import zw.co.accisafe.rdc.ui.components.SectionCard
import zw.co.accisafe.rdc.ui.components.humanise
import javax.inject.Inject

private data class MenuItem(val label: String, val icon: ImageVector, val route: String)

@Composable
fun MoreScreen(me: Me, nav: NavHostController, onLogout: () -> Unit) {
    var confirmLogout by remember { mutableStateOf(false) }
    val sections = buildList {
        add("Safety" to buildList {
            add(MenuItem("Accident detection", Icons.Default.Sensors, Routes.MONITOR))
            add(MenuItem("Personal emergency contacts", Icons.Default.ContactPhone, Routes.CONTACTS))
            add(MenuItem("Simulation and testing", Icons.Default.Science, Routes.SIMULATION))
            add(MenuItem("Synchronisation status", Icons.Default.Sync, Routes.SYNC))
        })
        add("Operations" to buildList {
            if (me.can("incidents.view_assigned")) add(MenuItem("Team assignment inbox", Icons.Default.Assignment, Routes.ASSIGNMENTS))
            if (me.can("teams.view")) add(MenuItem("Response teams", Icons.Default.Groups, Routes.TEAMS))
            if (me.can("resources.view")) add(MenuItem("Resources and equipment", Icons.Default.Inventory, Routes.RESOURCES))
            if (me.can("reports.view")) add(MenuItem("Reports and analytics", Icons.Default.Analytics, Routes.REPORTS))
        })
        add("Administration" to buildList {
            if (me.can("users.manage")) add(MenuItem("Users and roles", Icons.Default.AdminPanelSettings, Routes.USERS))
            if (me.can("config.manage")) add(MenuItem("Wards and incident categories", Icons.Default.LocationCity, Routes.CONFIG))
            if (me.can("audit.view")) add(MenuItem("Audit log", Icons.Default.History, Routes.AUDIT))
        })
        add("Account" to listOf(
            MenuItem("Profile and settings", Icons.Default.Person, Routes.PROFILE),
            MenuItem("Privacy information", Icons.Default.PrivacyTip, Routes.PRIVACY),
        ))
    }.filter { it.second.isNotEmpty() }

    GradientScaffold("More", subtitle = "${me.fullName} · ${me.roles.joinToString { humanise(it) }}") { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            sections.forEach { (title, items) ->
                item {
                    SectionCard(title) {
                        items.forEachIndexed { i, m ->
                            if (i > 0) HorizontalDivider()
                            Row(Modifier.fillMaxWidth().clickable { nav.navigate(m.route) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                                    Icon(m.icon, null, tint = MaterialTheme.colorScheme.primary)
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(m.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                Icon(Icons.Default.ChevronRight, null)
                            }
                        }
                    }
                }
            }
            item {
                TextButton(onClick = { confirmLogout = true }, Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Filled.Logout, null); Spacer(Modifier.width(8.dp)); Text("Sign out")
                }
            }
            item { Text("AcciSafe RDC ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false }, title = { Text("Sign out?") },
        text = { Text("Reports not yet sent stay on this phone and are sent after you sign in again.") },
        confirmButton = { Button(onClick = { confirmLogout = false; onLogout() }) { Text("Sign out") } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
    )
}

@HiltViewModel
class ProfileViewModel @Inject constructor(private val client: ApiClient, private val auth: AuthRepository, val session: SessionStore) : ViewModel() {
    val message = MutableStateFlow<String?>(null)
    fun changePassword(current: String, new: String, onDone: () -> Unit) = viewModelScope.launch {
        apiCall { client.api.changePassword(ChangePasswordRequest(current, new)).also { if (!it.isSuccessful) throw retrofit2.HttpException(it) } }
            .onSuccess { message.value = "Password changed. Other devices have been signed out."; auth.refreshProfile(); onDone() }
            .onFailure { message.value = it.message }
    }
}

@Composable
fun ProfileScreen(me: Me, nav: NavHostController, vm: ProfileViewModel = hiltViewModel()) {
    val message by vm.message.collectAsState()
    var changing by remember { mutableStateOf(me.mustChangePassword) }
    GradientScaffold("Profile and settings", onBack = { nav.popBackStack() }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (me.mustChangePassword) ErrorBanner("You are using a temporary password. Please change it now.")
            message?.let { ErrorBanner(it) }
            SectionCard("Account") {
                InfoRow("Name", me.fullName)
                InfoRow("Username", me.username)
                InfoRow("Phone", me.phone)
                InfoRow("Council", "${me.councilName} (${me.councilCode})")
                InfoRow("Roles", me.roles.joinToString { humanise(it) })
                TextButton(onClick = { changing = true }) { Text("Change password") }
            }
            SectionCard("This device") {
                InfoRow("Server", vm.session.serverUrl)
                InfoRow("Language", "English (Shona translation can be added)")
                TextButton(onClick = { nav.navigate(Routes.SIMULATION) }) { Text("Detection and countdown settings") }
                TextButton(onClick = { nav.navigate(Routes.SYNC) }) { Text("Offline synchronisation status") }
            }
        }
    }
    if (changing) {
        var current by remember { mutableStateOf("") }
        var new by remember { mutableStateOf("") }
        var repeat by remember { mutableStateOf("") }
        val weak = new.isNotEmpty() && (new.length < 10 || new.all(Char::isDigit) || new.all(Char::isLetter))
        AlertDialog(onDismissRequest = { changing = false }, title = { Text("Change password") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(current, { current = it }, label = { Text("Current password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                OutlinedTextField(new, { new = it }, label = { Text("New password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    isError = weak, supportingText = { Text("At least 10 characters with letters and numbers") })
                OutlinedTextField(repeat, { repeat = it }, label = { Text("Repeat new password") }, visualTransformation = PasswordVisualTransformation(),
                    singleLine = true, isError = repeat.isNotEmpty() && repeat != new)
            }
        }, confirmButton = {
            Button(onClick = { vm.changePassword(current, new) { changing = false } }, enabled = current.isNotEmpty() && !weak && new.isNotEmpty() && new == repeat) { Text("Change") }
        }, dismissButton = { TextButton(onClick = { changing = false }) { Text("Cancel") } })
    }
}

@Composable
fun PrivacyScreen(nav: NavHostController) {
    GradientScaffold("Privacy information", onBack = { nav.popBackStack() }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard("What is collected") {
                listOf(
                    "Incident reports: type, description, time, location (GPS or as described), photos you choose to attach.",
                    "Your name and phone number, so the council can contact you about a report.",
                    "Accident-detection events: time, impact strength and the outcome (for example, cancelled as a false alarm).",
                    "Personal emergency contacts are stored only on this phone and are never sent to the council.",
                ).forEach { Text("• $it") }
            }
            SectionCard("Who can see it") {
                Text("Community reporters see only their own reports. Your contact details are visible only to authorised coordinators, " +
                    "administrators and the team assigned to your report. Managers and auditors see statistics without personal details. " +
                    "Every important action is recorded in an audit log.")
            }
            SectionCard("Permissions") {
                listOf(
                    "Location: to record where an incident is. Asked when you report or start monitoring.",
                    "Notifications: to show the accident countdown and monitoring status.",
                    "SMS: only if you switch on SMS to your personal emergency contacts.",
                    "Photos: chosen through Android's photo picker; the app cannot see your other photos.",
                ).forEach { Text("• $it") }
            }
            SectionCard("Important") {
                Text("AcciSafe RDC does not contact the police, hospitals or ambulance services automatically. A report or referral " +
                    "does not guarantee that help will arrive. Before real deployment the council must assess applicable Zimbabwean " +
                    "data-protection and cybersecurity requirements.")
            }
        }
    }
}
