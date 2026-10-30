package zw.co.accisafe.rdc.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import dagger.hilt.android.lifecycle.HiltViewModel
import zw.co.accisafe.rdc.data.ConfigRepository
import zw.co.accisafe.rdc.data.SessionStore
import zw.co.accisafe.rdc.data.net.ApiClient
import zw.co.accisafe.rdc.data.net.IncidentSummary
import zw.co.accisafe.rdc.data.net.Me
import zw.co.accisafe.rdc.data.net.MyAssignment
import zw.co.accisafe.rdc.data.net.ReportSummary
import zw.co.accisafe.rdc.data.net.Resource
import zw.co.accisafe.rdc.data.net.Team
import zw.co.accisafe.rdc.detection.MonitoringService
import zw.co.accisafe.rdc.ui.RemoteViewModel
import zw.co.accisafe.rdc.ui.Routes
import zw.co.accisafe.rdc.ui.components.EmptyState
import zw.co.accisafe.rdc.ui.components.ErrorBanner
import zw.co.accisafe.rdc.ui.components.GradientScaffold
import zw.co.accisafe.rdc.ui.components.LoadingBox
import zw.co.accisafe.rdc.ui.components.PriorityBadge
import zw.co.accisafe.rdc.ui.components.SectionCard
import zw.co.accisafe.rdc.ui.components.StatCard
import zw.co.accisafe.rdc.ui.components.StatusBadge
import zw.co.accisafe.rdc.ui.components.formatTime
import zw.co.accisafe.rdc.ui.components.humanise
import zw.co.accisafe.rdc.ui.theme.Critical
import zw.co.accisafe.rdc.ui.theme.Emerald
import zw.co.accisafe.rdc.ui.theme.High
import zw.co.accisafe.rdc.ui.theme.Low
import zw.co.accisafe.rdc.ui.theme.Resolved
import javax.inject.Inject

data class HomeData(
    val summary: ReportSummary? = null,
    val recent: List<IncidentSummary> = emptyList(),
    val teams: List<Team> = emptyList(),
    val resources: List<Resource> = emptyList(),
    val assignments: List<MyAssignment> = emptyList(),
    val myTeams: List<Team> = emptyList(),
    val councilPhone: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(client: ApiClient, private val session: SessionStore, private val config: ConfigRepository) :
    RemoteViewModel<HomeData>(client) {
    override suspend fun fetch(): HomeData {
        val me = session.me.value ?: return HomeData()
        val phone = config.load().getOrNull()?.council?.phone
        return when {
            me.can("incidents.view_all") -> HomeData(
                summary = if (me.can("reports.view")) api.reportSummary() else null,
                recent = api.incidents(pageSize = 6).items,
                teams = if (me.can("teams.view")) api.teams() else emptyList(),
                resources = if (me.can("resources.view")) api.resources() else emptyList(),
                councilPhone = phone,
            )
            me.can("incidents.view_assigned") -> HomeData(
                assignments = api.myAssignments(), myTeams = api.myTeams(), councilPhone = phone,
                recent = api.incidents(pageSize = 6, openOnly = true).items,
            )
            else -> HomeData(recent = api.incidents(pageSize = 3, mine = true).items, councilPhone = phone)
        }
    }

    init { reload() }
}

@Composable
fun HomeScreen(me: Me, nav: NavHostController, vm: HomeViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.reload() }
    val canReport = me.can("incidents.create")
    GradientScaffold(
        title = "Hello, ${me.fullName.substringBefore(' ')}",
        subtitle = me.councilName,
        actions = { IconButton(onClick = { vm.reload() }) { Icon(Icons.Default.Refresh, "Refresh", tint = Color.White) } },
        floatingActionButton = {
            if (canReport && !me.isCommunityOnly) {
                ExtendedFloatingActionButton(
                    onClick = { nav.navigate(Routes.report()) }, containerColor = Critical, contentColor = Color.White,
                    icon = { Icon(Icons.Default.Add, null) }, text = { Text("Report emergency") },
                )
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            state.error?.let { item { ErrorBanner(it) { vm.reload() } } }
            val data = state.data
            if (state.loading && data == null) item { LoadingBox() }
            if (data != null) {
                when {
                    me.can("incidents.view_all") -> coordinatorHome(me, data, nav)
                    me.can("incidents.view_assigned") -> teamHome(data, nav)
                    else -> communityHome(data, nav)
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.coordinatorHome(me: Me, d: HomeData, nav: NavHostController) {
    val t = d.summary?.totals
    if (t != null) {
        item {
            Text("Live situation", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            StatGrid(listOf(
                Stat("Awaiting acknowledgement", t["awaiting_acknowledgement"] ?: 0, High, Icons.Default.HourglassTop),
                Stat("Critical / high open", t["critical_or_high_open"] ?: 0, Critical, Icons.Default.PriorityHigh),
                Stat("In progress", t["in_progress"] ?: 0, Emerald, Icons.Default.DirectionsCar),
                Stat("Referred externally", t["referred_externally"] ?: 0, Low, Icons.Default.Share),
                Stat("Open incidents", t["open"] ?: 0, Emerald, Icons.Default.Warning),
                Stat("Resolved", t["resolved"] ?: 0, Resolved, Icons.Default.CheckCircle),
            )) { nav.navigate(Routes.INCIDENTS) }
        }
        item {
            val available = d.teams.count { it.active && it.availability == "AVAILABLE" }
            val unavailable = d.resources.count { it.status in listOf("UNDER_MAINTENANCE", "OUT_OF_SERVICE") }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Teams available", "$available of ${d.teams.count { it.active }}", Emerald, Icons.Default.Groups,
                    Modifier.weight(1f)) { nav.navigate(Routes.TEAMS) }
                StatCard("Resources unavailable", "$unavailable", if (unavailable > 0) High else Resolved, Icons.Default.Build,
                    Modifier.weight(1f)) { nav.navigate(Routes.RESOURCES) }
            }
        }
    }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickAction("Incident map", Icons.Default.Map, Modifier.weight(1f)) { nav.navigate(Routes.MAP) }
            if (me.can("reports.view")) QuickAction("Reports", Icons.Default.Assignment, Modifier.weight(1f)) { nav.navigate(Routes.REPORTS) }
        }
    }
    item { Text("Recent incidents", style = MaterialTheme.typography.titleMedium) }
    if (d.recent.isEmpty()) item { EmptyState("No incidents recorded", "New reports will appear here as soon as they reach the server.") }
    items(d.recent.size) { i -> IncidentRow(d.recent[i]) { nav.navigate(Routes.incident(d.recent[i].id)) } }
}

private fun androidx.compose.foundation.lazy.LazyListScope.teamHome(d: HomeData, nav: NavHostController) {
    val pending = d.assignments.count { it.status == "PENDING" }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Pending assignments", "$pending", if (pending > 0) Critical else Resolved, Icons.Default.NotificationsActive,
                Modifier.weight(1f)) { nav.navigate(Routes.ASSIGNMENTS) }
            StatCard("Active assignments", "${d.assignments.count { it.status == "ACCEPTED" }}", Emerald, Icons.Default.DirectionsCar,
                Modifier.weight(1f)) { nav.navigate(Routes.ASSIGNMENTS) }
        }
    }
    items(d.myTeams.size) { i ->
        val team = d.myTeams[i]
        SectionCard(team.name, Modifier.clickable { nav.navigate(Routes.team(team.id)) }) {
            Text("Availability: ${humanise(team.availability)}")
            Text("Tap to update availability", color = MaterialTheme.colorScheme.primary)
        }
    }
    item { Text("Incident updates", style = MaterialTheme.typography.titleMedium) }
    if (d.recent.isEmpty()) item { EmptyState("Nothing assigned", "Incidents assigned to your team will appear here.") }
    items(d.recent.size) { i -> IncidentRow(d.recent[i]) { nav.navigate(Routes.incident(d.recent[i].id)) } }
}

private fun androidx.compose.foundation.lazy.LazyListScope.communityHome(d: HomeData, nav: NavHostController) {
    item {
        Button(
            onClick = { nav.navigate(Routes.report("EMERGENCY")) },
            modifier = Modifier.fillMaxWidth().height(96.dp), shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Critical),
        ) {
            Icon(Icons.Default.Report, null, Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Column { Text("Report an emergency", style = MaterialTheme.typography.titleLarge); Text("Accident, fire, flood, collapse…") }
        }
    }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickAction("Report a road or service problem", Icons.Default.Build, Modifier.weight(1f)) { nav.navigate(Routes.report("ROUTINE")) }
            QuickAction(if (MonitoringService.status.value.active) "Accident monitoring: ON" else "Accident monitoring", Icons.Default.Sensors,
                Modifier.weight(1f)) { nav.navigate(Routes.MONITOR) }
        }
    }
    item {
        SectionCard("Emergency contacts") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Phone, null, tint = Emerald)
                Spacer(Modifier.width(8.dp))
                Text("Council: ${d.councilPhone ?: "number not configured"}")
            }
            Spacer(Modifier.height(4.dp))
            Text("If someone's life is in danger, phone the police or ambulance directly as well as reporting here.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    item {
        SectionCard("Safety guidance") {
            listOf(
                "Keep yourself safe first. Do not enter fire, flood water or damaged buildings.",
                "At a road accident, warn other traffic and do not move injured people unless they are in danger.",
                "Give a landmark if GPS is weak, for example the nearest school, shop or bridge.",
            ).forEach { Text("• $it", Modifier.padding(vertical = 2.dp)) }
        }
    }
    item {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("My recent reports", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            androidx.compose.material3.TextButton(onClick = { nav.navigate(Routes.SYNC) }) { Text("Unsent reports") }
        }
    }
    if (d.recent.isEmpty()) item { EmptyState("No reports yet", "Reports you submit will appear here with their status.") }
    items(d.recent.size) { i -> IncidentRow(d.recent[i]) { nav.navigate(Routes.incident(d.recent[i].id)) } }
}

private data class Stat(val label: String, val value: Int, val color: Color, val icon: ImageVector)

@Composable
private fun StatGrid(stats: List<Stat>, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        stats.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { StatCard(it.label, it.value.toString(), it.color, it.icon, Modifier.weight(1f), onClick) }
            }
        }
    }
}

@Composable
fun QuickAction(label: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.primaryContainer).clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(label, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelLarge, maxLines = 2)
    }
}

@Composable
fun IncidentRow(i: IncidentSummary, onClick: () -> Unit) {
    SectionCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(zw.co.accisafe.rdc.ui.theme.priorityColor(i.effectivePriority).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center) {
                Icon(if (i.category.contains("fire", true)) Icons.Default.LocalFireDepartment else Icons.Default.Warning, null,
                    tint = zw.co.accisafe.rdc.ui.theme.priorityColor(i.effectivePriority))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(i.category, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${i.reference} · ${i.ward ?: i.landmark ?: "location on map"}", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatTime(i.receivedAt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusBadge(i.status)
            if (i.kind == "EMERGENCY") PriorityBadge(i.effectivePriority, suggested = i.priority == null && i.suggestedPriority != null)
            else zw.co.accisafe.rdc.ui.components.Badge("Service request", Low)
        }
    }
}
