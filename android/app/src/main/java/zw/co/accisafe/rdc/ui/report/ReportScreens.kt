package zw.co.accisafe.rdc.ui.report

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.core.content.FileProvider
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import coil.compose.AsyncImage
import java.io.File
import java.util.UUID
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import zw.co.accisafe.rdc.data.ConfigRepository
import zw.co.accisafe.rdc.data.ReportRepository
import zw.co.accisafe.rdc.data.SessionStore
import zw.co.accisafe.rdc.data.local.AppDatabase
import zw.co.accisafe.rdc.data.local.PendingReport
import zw.co.accisafe.rdc.data.local.SubmissionStatus
import zw.co.accisafe.rdc.data.net.Category
import zw.co.accisafe.rdc.data.net.ConfigResponse
import zw.co.accisafe.rdc.data.net.IncidentCreate
import zw.co.accisafe.rdc.data.net.Me
import zw.co.accisafe.rdc.data.net.Ward
import zw.co.accisafe.rdc.location.Fix
import zw.co.accisafe.rdc.location.LocationProvider
import zw.co.accisafe.rdc.ui.Routes
import zw.co.accisafe.rdc.ui.components.Dropdown
import zw.co.accisafe.rdc.ui.components.EmptyState
import zw.co.accisafe.rdc.ui.components.ErrorBanner
import zw.co.accisafe.rdc.ui.components.GradientScaffold
import zw.co.accisafe.rdc.ui.components.InfoRow
import zw.co.accisafe.rdc.ui.components.LoadingBox
import zw.co.accisafe.rdc.ui.components.SectionCard
import zw.co.accisafe.rdc.ui.components.TriStateField
import zw.co.accisafe.rdc.ui.theme.Critical
import zw.co.accisafe.rdc.ui.theme.High
import zw.co.accisafe.rdc.ui.theme.Resolved
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject

@HiltViewModel
class ReportViewModel @Inject constructor(
    private val config: ConfigRepository,
    private val reports: ReportRepository,
    private val location: LocationProvider,
    private val session: SessionStore,
) : ViewModel() {
    val cfg = MutableStateFlow<ConfigResponse?>(null)
    val cfgError = MutableStateFlow<String?>(null)
    val fix = MutableStateFlow<Fix?>(null)
    val locating = MutableStateFlow(false)
    val locationMessage = MutableStateFlow<String?>(null)
    val submitting = MutableStateFlow(false)
    val submitError = MutableStateFlow<String?>(null)
    // Fixed for the lifetime of this form so that a double tap reuses the same idempotency key.
    private val ids = ReportRepository.newDraftIds()

    init {
        viewModelScope.launch {
            config.load().onSuccess { cfg.value = it }.onFailure { cfgError.value = it.message }
        }
    }

    fun locate() = viewModelScope.launch {
        if (!location.hasPermission()) { locationMessage.value = "Location permission was not granted. Describe the location instead."; return@launch }
        if (!location.locationEnabled()) { locationMessage.value = "Location is switched off on this phone."; return@launch }
        locating.value = true
        locationMessage.value = null
        val f = location.current()
        fix.value = f
        if (f == null) locationMessage.value = "Could not get a GPS position. Describe the location instead."
        locating.value = false
    }

    fun submit(draft: (localId: String, key: String) -> IncidentCreate, categoryName: String, photos: List<Uri>, onSaved: (String) -> Unit) =
        viewModelScope.launch {
            if (submitting.value) return@launch
            submitting.value = true
            submitError.value = null
            runCatching { reports.submit(draft(ids.first, ids.second), categoryName, photos) }
                .onSuccess(onSaved)
                .onFailure { submitError.value = it.message }
            submitting.value = false
        }

    val me get() = session.me.value
}

private const val MAX_PHOTOS = 5

private val occurredOptions = listOf("Just now" to 0L, "About 15 min ago" to 15L, "About 1 hour ago" to 60L, "Earlier today" to 240L)

@Composable
fun ReportIncidentScreen(me: Me, nav: NavHostController, kind: String, vm: ReportViewModel = hiltViewModel()) {
    val cfg by vm.cfg.collectAsState()
    val cfgError by vm.cfgError.collectAsState()
    val fix by vm.fix.collectAsState()
    val locating by vm.locating.collectAsState()
    val locMsg by vm.locationMessage.collectAsState()
    val submitting by vm.submitting.collectAsState()
    val submitError by vm.submitError.collectAsState()

    var category by remember { mutableStateOf<Category?>(null) }
    var description by rememberSaveable { mutableStateOf("") }
    var ward by remember { mutableStateOf<Ward?>(null) }
    var landmark by rememberSaveable { mutableStateOf("") }
    var occurredIndex by rememberSaveable { mutableStateOf<Int?>(0) }
    var injuries by rememberSaveable { mutableStateOf<String?>(null) }
    var fatalities by rememberSaveable { mutableStateOf<String?>(null) }
    var danger by rememberSaveable { mutableStateOf<String?>(null) }
    var threat by rememberSaveable { mutableStateOf<String?>(null) }
    var affected by rememberSaveable { mutableStateOf("") }
    var affectedUnknown by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf(me.fullName) }
    var phone by rememberSaveable { mutableStateOf(me.phone ?: "") }
    val photos = remember { mutableStateListOf<Uri>() }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.locate() }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS)) {
        photos.addAll(it.filter { u -> u !in photos }.take(MAX_PHOTOS - photos.size))
    }
    var pendingShot by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        pendingShot?.let { if (ok && photos.size < MAX_PHOTOS) photos.add(it) }
        pendingShot = null
    }
    fun takePhoto() {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", File(dir, "${UUID.randomUUID()}.jpg"))
        pendingShot = uri
        camera.launch(uri)
    }
    LaunchedEffect(Unit) {
        locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    val emergency = kind == "EMERGENCY"
    val categories = cfg?.categories?.filter { it.active && it.kind == kind }.orEmpty()
    val hasLocation = fix != null || ward != null || landmark.isNotBlank()
    val canSubmit = category != null && description.trim().length >= 3 && hasLocation && !submitting

    GradientScaffold(
        title = if (emergency) "Report an emergency" else "Report a service problem",
        subtitle = if (emergency) "Only the type, a short description and a location are required." else "Routine problems are reviewed by council staff.",
        onBack = { nav.popBackStack() },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (cfg == null && cfgError == null) LoadingBox()
            cfgError?.let { ErrorBanner("Incident types could not be loaded: $it. Connect once to download them.") }

            SectionCard("1. What happened? *") {
                Dropdown("Incident type", categories, category, { it.name }, { category = it }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(description, { description = it }, label = { Text("Short description") }, minLines = 3,
                    modifier = Modifier.fillMaxWidth(), supportingText = { Text("For example: two cars collided, one person trapped") })
                Text("When did it happen?", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    occurredOptions.forEachIndexed { i, (label, _) ->
                        FilterChip(selected = occurredIndex == i, onClick = { occurredIndex = i }, label = { Text(label) })
                    }
                    FilterChip(selected = occurredIndex == null, onClick = { occurredIndex = null }, label = { Text("Don't know") })
                }
            }

            SectionCard("2. Where? *") {
                OutlinedButton(onClick = { locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)) }, enabled = !locating) {
                    if (locating) CircularProgressIndicator(Modifier.size(18.dp)) else Icon(Icons.Default.MyLocation, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (fix == null) "Use my current GPS position" else "Update GPS position")
                }
                fix?.let {
                    Text("GPS: %.5f, %.5f (accuracy ±%.0f m)".format(it.latitude, it.longitude, it.accuracyM), color = Resolved)
                    if (it.accuracyM > 100) Text("Accuracy is poor. Please add a landmark.", color = High)
                }
                locMsg?.let { Text(it, color = High) }
                Spacer(Modifier.height(8.dp))
                Dropdown("Ward (if known)", cfg?.wards?.filter { it.active }.orEmpty(), ward, { it.name }, { ward = it }, Modifier.fillMaxWidth())
                OutlinedTextField(landmark, { landmark = it }, label = { Text("Landmark or address") }, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Nearest school, shop, bridge, road name…") })
                if (!hasLocation) Text("Give a GPS position, a ward or a landmark.", color = Critical)
            }

            if (emergency) {
                SectionCard("3. Danger and injuries (answer what you know)") {
                    TriStateField("Has anyone died?", fatalities) { fatalities = it }
                    TriStateField("Is anyone injured?", injuries) { injuries = it }
                    TriStateField("Is anyone in immediate danger?", danger) { danger = it }
                    TriStateField("Is anyone's life threatened?", threat) { threat = it }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(affected, { affected = it.filter(Char::isDigit).take(6); if (it.isNotEmpty()) affectedUnknown = false },
                            label = { Text("People affected") }, enabled = !affectedUnknown, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                        Checkbox(affectedUnknown, { affectedUnknown = it; if (it) affected = "" })
                        Text("Don't know")
                    }
                }
            }

            SectionCard("${if (emergency) 4 else 3}. Photos and contact") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { takePhoto() }, enabled = photos.size < MAX_PHOTOS, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.PhotoCamera, null); Spacer(Modifier.width(6.dp)); Text("Take photo")
                    }
                    OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = photos.size < MAX_PHOTOS, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.AddAPhoto, null); Spacer(Modifier.width(6.dp)); Text("From gallery")
                    }
                }
                Text(if (photos.isEmpty()) "Optional, up to $MAX_PHOTOS photos. They appear on the council map when people tap this report."
                     else "${photos.size} of $MAX_PHOTOS photos added", style = MaterialTheme.typography.bodyMedium)
                if (photos.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(photos.toList()) { uri ->
                            Box {
                                AsyncImage(model = uri, contentDescription = "Selected photo", contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)))
                                IconButton(onClick = { photos.remove(uri) }, modifier = Modifier.align(Alignment.TopEnd).size(30.dp),
                                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = .55f), contentColor = Color.White)) {
                                    Icon(Icons.Default.Close, "Remove photo", Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
                Text("Only take photos when it is safe to do so.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(name, { name = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(phone, { phone = it }, label = { Text("Phone number for call-back (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                Text("Your contact details are visible only to authorised council staff and the assigned team.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            submitError?.let { ErrorBanner(it) }
            Button(
                onClick = {
                    val cat = category ?: return@Button
                    val now = Instant.now()
                    vm.submit({ localId, key ->
                        IncidentCreate(
                            idempotencyKey = key, localId = localId, categoryId = cat.id, description = description.trim(),
                            reportedAt = now.toString(),
                            occurredAt = occurredIndex?.let { now.minus(occurredOptions[it].second, ChronoUnit.MINUTES).toString() },
                            occurredAtUnknown = occurredIndex == null,
                            latitude = fix?.latitude, longitude = fix?.longitude, accuracyM = fix?.accuracyM,
                            locationSource = if (fix != null) "GPS" else if (ward != null || landmark.isNotBlank()) "MANUAL" else "NONE",
                            wardId = ward?.id, landmark = landmark.trim().ifBlank { null },
                            injuries = injuries, fatalities = fatalities, immediateDanger = danger, threatToLife = threat,
                            affectedPeople = affected.toIntOrNull(), affectedPeopleUnknown = affectedUnknown,
                            reporterName = name.trim().ifBlank { null },
                            reporterPhone = phone.filter { it.isDigit() || it == '+' || it == ' ' }.trim().ifBlank { null },
                        )
                    }, cat.name, photos.toList()) { localId ->
                        nav.navigate(Routes.reportDone(localId)) { popUpTo(nav.graph.findStartDestination().id) }
                    }
                },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth().height(60.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (emergency) Critical else MaterialTheme.colorScheme.primary),
            ) { if (submitting) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White) else Text("Submit report") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// --- confirmation -----------------------------------------------------------------------

@HiltViewModel
class PendingReportViewModel @Inject constructor(private val reports: ReportRepository, private val db: AppDatabase, session: SessionStore) : ViewModel() {
    private val userId = session.me.value?.id ?: -1
    fun observe(localId: String) = reports.observe(localId)
    val all = if (userId >= 0) reports.observeMine(userId) else emptyFlow()
    val detections = if (userId >= 0) db.detections().observeAll(userId) else emptyFlow()
    fun retry(localId: String) = viewModelScope.launch { reports.retry(localId) }
    fun discard(localId: String) = viewModelScope.launch { reports.discard(localId) }
}

private val timeFmt = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault())

@Composable
fun ReportConfirmationScreen(localId: String, nav: NavHostController, vm: PendingReportViewModel = hiltViewModel()) {
    val report by vm.observe(localId).collectAsState(initial = null)
    GradientScaffold("Report status", onBack = { nav.popBackStack() }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val r = report ?: run { LoadingBox(); return@Column }
            StatusHero(r)
            SectionCard("Details") {
                InfoRow("Reference", r.reference ?: "Not yet issued (local ID ${r.localId})")
                InfoRow("Type", r.categoryName)
                InfoRow("Time recorded", timeFmt.format(Instant.ofEpochMilli(r.createdAt)))
                val draft = runCatching { zw.co.accisafe.rdc.data.net.appJson.decodeFromString<IncidentCreate>(r.payloadJson) }.getOrNull()
                InfoRow("Location", draft?.let {
                    listOfNotNull(
                        it.latitude?.let { lat -> "GPS %.5f, %.5f (±%.0f m)".format(lat, it.longitude, it.accuracyM ?: 0f) },
                        it.landmark,
                    ).joinToString(" · ").ifBlank { "Ward selected" }
                })
                if (r.photoCount > 0) InfoRow("Photos", "${r.photosUploaded} of ${r.photoCount} uploaded")
                r.error?.let { InfoRow("Last problem", it) }
            }
            SectionCard("Next steps") {
                Text(when (r.status) {
                    SubmissionStatus.RECEIVED -> "The council has received your report. A coordinator will review it. You will see status changes under My reports and Alerts."
                    SubmissionStatus.FAILED -> "The server rejected this report. Read the problem above, then discard it and report again, or contact the council by phone."
                    else -> "Your report is stored on this phone and will be sent automatically when there is a connection. The council has NOT received it yet. In a life-threatening emergency, phone for help now."
                })
            }
            if (r.status == SubmissionStatus.RECEIVED && r.serverId != null) {
                Button(onClick = { nav.navigate(Routes.incident(r.serverId)) }, Modifier.fillMaxWidth()) { Text("View incident") }
            }
            if (r.status == SubmissionStatus.WAITING || r.status == SubmissionStatus.FAILED) {
                OutlinedButton(onClick = { vm.retry(r.localId) }, Modifier.fillMaxWidth()) { Text("Try sending now") }
            }
            TextButton(onClick = { nav.popBackStack(nav.graph.findStartDestination().id, inclusive = false) }, Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}

@Composable
private fun StatusHero(r: PendingReport) {
    val (icon, color) = when (r.status) {
        SubmissionStatus.RECEIVED -> Icons.Default.CheckCircle to Resolved
        SubmissionStatus.FAILED -> Icons.Default.ErrorOutline to Critical
        SubmissionStatus.SENDING -> Icons.Default.CloudUpload to High
        SubmissionStatus.WAITING -> Icons.Default.CloudOff to High
        else -> Icons.Default.PhoneAndroid to High
    }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(44.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(SubmissionStatus.label(r.status), style = MaterialTheme.typography.titleLarge, color = color)
                r.reference?.let { Text("Reference $it", style = MaterialTheme.typography.titleMedium) }
            }
        }
    }
}

// --- synchronisation status ---------------------------------------------------------------

@Composable
fun SyncStatusScreen(me: Me, nav: NavHostController, vm: PendingReportViewModel = hiltViewModel()) {
    val reports by vm.all.collectAsState(initial = emptyList())
    val detections by vm.detections.collectAsState(initial = emptyList())
    GradientScaffold("Synchronisation", subtitle = "Reports and events stored on this phone", onBack = { nav.popBackStack() }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                val unsent = reports.count { it.status != SubmissionStatus.RECEIVED }
                SectionCard {
                    Text(if (unsent == 0) "All reports have reached the server." else "$unsent report(s) have not reached the server yet.",
                        style = MaterialTheme.typography.titleMedium, color = if (unsent == 0) Resolved else High)
                    Text("Sending happens automatically when a connection is available.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (reports.isEmpty()) item { EmptyState("Nothing stored", "Reports made on this phone will be listed here.") }
            items(reports, key = { it.localId }) { r ->
                SectionCard(r.categoryName) {
                    InfoRow("Status", SubmissionStatus.label(r.status))
                    InfoRow("Reference", r.reference ?: r.localId)
                    InfoRow("Created", timeFmt.format(Instant.ofEpochMilli(r.createdAt)))
                    InfoRow("Attempts", r.attempts.toString())
                    r.error?.let { InfoRow("Problem", it) }
                    Row {
                        TextButton(onClick = { nav.navigate(Routes.reportDone(r.localId)) }) { Text("Open") }
                        if (r.status == SubmissionStatus.WAITING || r.status == SubmissionStatus.FAILED) {
                            TextButton(onClick = { vm.retry(r.localId) }) { Text("Retry") }
                            TextButton(onClick = { vm.discard(r.localId) }) { Text("Discard", color = Critical) }
                        }
                    }
                }
            }
            if (detections.isNotEmpty()) {
                item { Text("Accident-detection events", style = MaterialTheme.typography.titleMedium) }
                items(detections, key = { it.idempotencyKey }) { d ->
                    SectionCard {
                        Text(timeFmt.format(Instant.ofEpochMilli(d.createdAt)))
                        Text(if (d.sent) "Recorded by the server" else "Waiting to synchronise" + (d.error?.let { " ($it)" } ?: ""),
                            color = if (d.sent) Resolved else High)
                    }
                }
            }
        }
    }
}
