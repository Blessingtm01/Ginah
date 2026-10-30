package zw.co.accisafe.rdc.ui.notify

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import dagger.hilt.android.lifecycle.HiltViewModel
import zw.co.accisafe.rdc.data.net.ApiClient
import zw.co.accisafe.rdc.data.net.NotificationsResponse
import zw.co.accisafe.rdc.ui.RemoteViewModel
import zw.co.accisafe.rdc.ui.Routes
import zw.co.accisafe.rdc.ui.components.Badge
import zw.co.accisafe.rdc.ui.components.EmptyState
import zw.co.accisafe.rdc.ui.components.ErrorBanner
import zw.co.accisafe.rdc.ui.components.GradientScaffold
import zw.co.accisafe.rdc.ui.components.LoadingBox
import zw.co.accisafe.rdc.ui.components.SectionCard
import zw.co.accisafe.rdc.ui.components.formatTime
import zw.co.accisafe.rdc.ui.components.humanise
import zw.co.accisafe.rdc.ui.theme.Critical
import zw.co.accisafe.rdc.ui.theme.Emerald
import zw.co.accisafe.rdc.ui.theme.High
import zw.co.accisafe.rdc.ui.theme.Low
import javax.inject.Inject

@HiltViewModel
class NotificationsViewModel @Inject constructor(client: ApiClient) : RemoteViewModel<NotificationsResponse>(client) {
    override suspend fun fetch() = api.notifications()
    fun open(id: Int) = act(null) { api.markRead(id) }
    fun readAll() = act(null) { api.markAllRead() }
}

/** Channel statuses are shown exactly as recorded: "Simulated" never means sent. */
private fun channelColor(status: String) = when (status) {
    "SENT", "DELIVERED", "CREATED" -> Emerald
    "SIMULATED" -> Low
    "FAILED", "NO_RECIPIENT", "NOT_CONFIGURED" -> Critical
    else -> High
}

@Composable
fun NotificationsScreen(nav: NavHostController, vm: NotificationsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.reload() }
    GradientScaffold("Notification centre", subtitle = state.data?.let { "${it.unread} unread" },
        actions = { IconButton(onClick = { vm.readAll() }) { Icon(Icons.Default.DoneAll, "Mark all read", tint = Color.White) } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.error?.let { item { ErrorBanner(it) { vm.reload() } } }
            if (state.loading && state.data == null) item { LoadingBox() }
            if (state.data?.items?.isEmpty() == true) item { EmptyState("No notifications", "Assignments, escalations and status changes will appear here.", Icons.Default.NotificationsNone) }
            items(state.data?.items.orEmpty(), key = { it.id }) { n ->
                SectionCard(modifier = Modifier.clickable {
                    if (!n.read && !n.noRecipient) vm.open(n.id)
                    n.incidentId?.let { nav.navigate(Routes.incident(it)) }
                }) {
                    Text(n.title, style = MaterialTheme.typography.titleMedium, fontWeight = if (n.read) FontWeight.Normal else FontWeight.Bold,
                        color = if (n.noRecipient) Critical else MaterialTheme.colorScheme.onSurface)
                    if (n.body.isNotBlank()) Text(n.body, style = MaterialTheme.typography.bodyMedium)
                    Text(formatTime(n.createdAt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        n.channels.forEach { c -> Badge("${c.channel.replace("_", "-")}: ${humanise(c.status)}", channelColor(c.status)) }
                    }
                }
            }
        }
    }
}
