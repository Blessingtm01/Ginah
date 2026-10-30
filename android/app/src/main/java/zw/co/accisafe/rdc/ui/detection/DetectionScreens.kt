package zw.co.accisafe.rdc.ui.detection

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import zw.co.accisafe.rdc.data.ContactsRepository
import zw.co.accisafe.rdc.data.DeviceSettings
import zw.co.accisafe.rdc.data.SessionStore
import zw.co.accisafe.rdc.data.local.EmergencyContact
import zw.co.accisafe.rdc.detection.DetectionCoordinator
import zw.co.accisafe.rdc.detection.MonitoringService
import zw.co.accisafe.rdc.ui.Routes
import zw.co.accisafe.rdc.ui.components.Badge
import zw.co.accisafe.rdc.ui.components.EmptyState
import zw.co.accisafe.rdc.ui.components.GradientScaffold
import zw.co.accisafe.rdc.ui.components.InfoRow
import zw.co.accisafe.rdc.ui.components.SectionCard
import zw.co.accisafe.rdc.ui.theme.Critical
import zw.co.accisafe.rdc.ui.theme.High
import zw.co.accisafe.rdc.ui.theme.Low
import zw.co.accisafe.rdc.ui.theme.Resolved
import javax.inject.Inject

@HiltViewModel
class DetectionViewModel @Inject constructor(
    private val session: SessionStore,
    private val contactsRepo: ContactsRepository,
    val coordinator: DetectionCoordinator,
) : ViewModel() {
    val settings = session.deviceSettings
    val contacts = contactsRepo.contacts
    val outcome = coordinator.lastOutcome
    fun save(s: DeviceSettings) = viewModelScope.launch { session.saveDeviceSettings(s) }
    fun addContact(name: String, phone: String) = viewModelScope.launch { contactsRepo.add(name, phone) }
    fun confirm(c: EmergencyContact, ok: Boolean) = viewModelScope.launch { contactsRepo.confirm(c, ok) }
    fun delete(id: Long) = viewModelScope.launch { contactsRepo.delete(id) }
}

@Composable
fun MonitorScreen(nav: NavHostController, vm: DetectionViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val status by MonitoringService.status.collectAsState()
    val outcome by vm.outcome.collectAsState()
    val contacts by vm.contacts.collectAsState(initial = emptyList())
    var disclosure by remember { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { MonitoringService.start(context) }
    val batteryOptimised = !context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    GradientScaffold("Accident detection", subtitle = if (status.active) "Monitoring is ACTIVE" else "Monitoring is OFF", onBack = { nav.popBackStack() }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard {
                    Badge(if (status.active) "Monitoring active" else "Monitoring inactive – accidents will NOT be detected", if (status.active) Resolved else Critical)
                    Spacer(Modifier.height(12.dp))
                    if (status.active) {
                        InfoRow("Acceleration", "%.2f g".format(status.accelG))
                        InfoRow("Speed", status.speedKmh?.let { "%.0f km/h".format(it) } ?: "No GPS speed")
                        InfoRow("Impact threshold", "%.1f g (experimental)".format(status.impactThresholdG))
                        if (!status.gyroAvailable) Text("This phone has no gyroscope; detection is less reliable.", color = High)
                        status.lastEvent?.let { InfoRow("Last event", it) }
                        Button(onClick = { MonitoringService.stop(context) }, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Stop monitoring") }
                    } else {
                        Button(onClick = { disclosure = true }, Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Resolved)) {
                            Text("Start accident monitoring")
                        }
                    }
                }
            }
            item {
                SectionCard("How it works") {
                    Text("While monitoring is on, the phone's motion sensors and GPS look for a sudden impact. If one is found, a 30-second alarm lets you cancel. If you do not respond, an accident report is queued for the council and your confirmed personal contacts receive an SMS.")
                    Spacer(Modifier.height(6.dp))
                    Text("It cannot detect every accident, and an alert may not reach anyone if there is no network. It never calls emergency services by itself. You can always report manually.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (batteryOptimised) item {
                SectionCard("Background limits") {
                    Text("Android battery optimisation may stop monitoring in the background on some phones.", color = High)
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("Open battery settings") }
                }
            }
            item {
                SectionCard("Personal emergency contacts", action = { TextButton(onClick = { nav.navigate(Routes.CONTACTS) }) { Text("Manage") } }) {
                    val confirmed = contacts.count { it.confirmed }
                    Text(if (confirmed == 0) "No confirmed contacts. No SMS will be sent." else "$confirmed confirmed contact(s) will receive an SMS.")
                }
            }
            outcome?.let { o -> item { SectionCard(o.title) { o.lines.forEach { Text("• $it") }
                o.reportLocalId?.let { id -> TextButton(onClick = { nav.navigate(Routes.reportDone(id)) }) { Text("View report status") } } } } }
            item { OutlinedButton(onClick = { nav.navigate(Routes.SIMULATION) }, Modifier.fillMaxWidth()) { Text("Simulation and testing") } }
        }
    }

    if (disclosure) AlertDialog(
        onDismissRequest = { disclosure = false },
        title = { Text("Before you start") },
        text = {
            Text("AcciSafe RDC will use the motion sensors and your location continuously while monitoring is on, and show a permanent notification. " +
                "This uses extra battery. Location is only sent if an accident alert or report is made.")
        },
        confirmButton = {
            Button(onClick = {
                disclosure = false
                val perms = buildList {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                }
                permissions.launch(perms.toTypedArray())
            }) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = { disclosure = false }) { Text("Cancel") } },
    )
}

@Composable
fun ContactsScreen(nav: NavHostController, vm: DetectionViewModel = hiltViewModel()) {
    val contacts by vm.contacts.collectAsState(initial = emptyList())
    val settings by vm.settings.collectAsState(initial = DeviceSettings())
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.save(settings.copy(smsToPersonalContacts = it)) }
    val validPhone = phone.filter { it.isDigit() }.length in 7..15

    GradientScaffold("Emergency contacts", subtitle = "Your own contacts, separate from council dispatch", onBack = { nav.popBackStack() }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Send SMS to these contacts after a confirmed accident alert", style = MaterialTheme.typography.titleMedium)
                            Text("Uses your phone's SMS and airtime.", style = MaterialTheme.typography.bodyMedium)
                        }
                        Switch(settings.smsToPersonalContacts, { on -> if (on) smsPermission.launch(Manifest.permission.SEND_SMS) else vm.save(settings.copy(smsToPersonalContacts = false)) })
                    }
                }
            }
            item {
                SectionCard("Add a contact") {
                    Text("Contacts are typed in by you; the app never reads your phone book.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(phone, { phone = it }, label = { Text("Phone number (+263…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { vm.addContact(name.trim(), phone.filter { it.isDigit() || it == '+' }); name = ""; phone = "" },
                        enabled = name.isNotBlank() && validPhone, modifier = Modifier.padding(top = 8.dp)) { Text("Add") }
                }
            }
            if (contacts.isEmpty()) item { EmptyState("No contacts", "Add people who agreed to be contacted in an emergency.") }
            items(contacts, key = { it.id }) { c ->
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.titleMedium)
                            Text(c.phone)
                            Badge(if (c.confirmed) "Confirmed" else "Not confirmed – will not be messaged", if (c.confirmed) Resolved else Low)
                        }
                        IconButton(onClick = { vm.delete(c.id) }) { Icon(Icons.Default.Delete, "Remove") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(c.confirmed, { vm.confirm(c, it) })
                        Text("I checked this number and the person agreed to receive alerts", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun SimulationScreen(nav: NavHostController, vm: DetectionViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsState(initial = DeviceSettings())
    val contacts by vm.contacts.collectAsState(initial = emptyList())
    val outcome by vm.outcome.collectAsState()
    var confirmSms by remember { mutableStateOf(false) }
    var impact by remember(settings.impactG) { mutableStateOf(settings.impactG) }
    var cooldown by remember(settings.cooldownSeconds) { mutableStateOf(settings.cooldownSeconds.toFloat()) }
    var countdown by remember(settings.countdownSeconds) { mutableStateOf(settings.countdownSeconds.toFloat()) }
    val recipients = contacts.filter { it.confirmed }

    GradientScaffold("Simulation and testing", subtitle = "TEST MODE – nothing here is a real emergency", onBack = { nav.popBackStack() }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard("Simulated accident") {
                    Text("Runs the warning screen and countdown. A simulation never creates a council incident and sends no SMS unless you explicitly choose the test SMS below. The outcome is recorded as a simulation for the false-alarm statistics.")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { vm.coordinator.start(null, simulated = true) }, Modifier.fillMaxWidth()) { Text("Run simulation (no SMS)") }
                    OutlinedButton(onClick = { confirmSms = true }, enabled = recipients.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                        Text("Run simulation and send a TEST SMS")
                    }
                    if (recipients.isEmpty()) Text("Add and confirm a personal contact to test SMS.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            outcome?.let { o -> item { SectionCard(o.title) { o.lines.forEach { Text("• $it") } } } }
            item {
                SectionCard("Detection settings (for authorised testing)") {
                    Text("Impact threshold: %.1f g".format(impact))
                    Text("4 g is an experimental starting value, not a proven accident threshold.", style = MaterialTheme.typography.bodyMedium, color = High)
                    Slider(impact, { impact = (it * 2).toInt() / 2f }, valueRange = 2f..12f)
                    Text("Cooldown between events: ${cooldown.toInt()} s")
                    Slider(cooldown, { cooldown = it }, valueRange = 5f..120f)
                    Text("Confirmation countdown: ${countdown.toInt()} s")
                    Slider(countdown, { countdown = it }, valueRange = 10f..120f)
                    Button(onClick = { vm.save(settings.copy(impactG = impact, cooldownSeconds = cooldown.toInt(), countdownSeconds = countdown.toInt())) }) {
                        Text("Save settings")
                    }
                    Text("Restart monitoring for new settings to take effect.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    if (confirmSms) AlertDialog(
        onDismissRequest = { confirmSms = false },
        title = { Text("Send a real TEST SMS?") },
        text = {
            Column {
                Text("A real SMS starting with \"[TEST – please ignore]\" will be sent from this phone to:")
                recipients.forEach { Text("• ${it.name} (${it.phone})") }
                Text("Normal SMS charges apply.", color = Critical)
            }
        },
        confirmButton = { Button(onClick = { confirmSms = false; vm.coordinator.start(null, simulated = true, sendTestSms = true) }) { Text("Send test") } },
        dismissButton = { TextButton(onClick = { confirmSms = false }) { Text("Cancel") } },
    )
}

