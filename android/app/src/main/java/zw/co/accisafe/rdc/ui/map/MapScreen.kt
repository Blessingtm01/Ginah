package zw.co.accisafe.rdc.ui.map

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import zw.co.accisafe.rdc.R
import zw.co.accisafe.rdc.data.net.ApiClient
import zw.co.accisafe.rdc.data.net.IncidentSummary
import zw.co.accisafe.rdc.ui.RemoteViewModel
import zw.co.accisafe.rdc.ui.Routes
import zw.co.accisafe.rdc.ui.components.ErrorBanner
import zw.co.accisafe.rdc.ui.components.GradientScaffold
import zw.co.accisafe.rdc.ui.components.humanise
import zw.co.accisafe.rdc.ui.theme.priorityColor
import javax.inject.Inject

data class MapFilter(val openOnly: Boolean = true, val priority: String? = null, val kind: String? = null)

@HiltViewModel
class MapViewModel @Inject constructor(client: ApiClient) : RemoteViewModel<List<IncidentSummary>>(client) {
    val filter = MutableStateFlow(MapFilter())
    override suspend fun fetch(): List<IncidentSummary> {
        val f = filter.value
        return api.incidents(pageSize = 100, openOnly = f.openOnly, priority = f.priority, kind = f.kind).items
    }
    fun apply(f: MapFilter) { filter.value = f; reload() }
}

/** OpenStreetMap view of incidents with a GPS position. Rural roads may be incomplete on the map. */
@Composable
fun MapScreen(nav: NavHostController, vm: MapViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    val f by vm.filter.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.reload() }
    val incidents = state.data.orEmpty()
    val located = incidents.filter { it.latitude != null && it.longitude != null }
    var map by remember { mutableStateOf<MapView?>(null) }

    GradientScaffold("Incident map", subtitle = "${located.size} of ${incidents.size} incidents have a GPS position") { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(f.openOnly, { vm.apply(f.copy(openOnly = !f.openOnly)) }, { Text("Open only") })
                listOf("CRITICAL", "HIGH", "MEDIUM", "LOW").forEach { p ->
                    FilterChip(f.priority == p, { vm.apply(f.copy(priority = if (f.priority == p) null else p)) }, { Text(humanise(p)) })
                }
                FilterChip(f.kind == "EMERGENCY", { vm.apply(f.copy(kind = if (f.kind == "EMERGENCY") null else "EMERGENCY")) }, { Text("Emergencies") })
            }
            state.error?.let { Box(Modifier.padding(horizontal = 12.dp)) { ErrorBanner(it) { vm.reload() } } }
            Box(Modifier.weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        MapView(ctx).apply {
                            setTileSource(TileSourceFactory.MAPNIK)
                            setMultiTouchControls(true)
                            controller.setZoom(10.0)
                            controller.setCenter(GeoPoint(-17.65, 31.78)) // initial view; recentred on incidents
                            map = this
                        }
                    },
                    update = { mv ->
                        mv.overlays.clear()
                        located.forEach { inc ->
                            val marker = Marker(mv).apply {
                                position = GeoPoint(inc.latitude!!, inc.longitude!!)
                                title = "${inc.category} · ${inc.reference}"
                                snippet = "${humanise(inc.status)} · ${inc.effectivePriority ?: "not assessed"}"
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                icon = ContextCompat.getDrawable(context, R.drawable.ic_stat_alert)?.mutate()?.apply {
                                    setTint(priorityColor(inc.effectivePriority).toArgb())
                                }
                                setOnMarkerClickListener { _, _ -> nav.navigate(Routes.incident(inc.id)); true }
                            }
                            mv.overlays.add(marker)
                        }
                        located.firstOrNull()?.let { mv.controller.setCenter(GeoPoint(it.latitude!!, it.longitude!!)) }
                        mv.invalidate()
                    },
                )
                Surface(Modifier.align(Alignment.BottomStart).padding(12.dp), shape = MaterialTheme.shapes.medium, tonalElevation = 3.dp) {
                    Text("Map © OpenStreetMap contributors. Roads shown may be incomplete; do not assume a route is passable.",
                        Modifier.padding(8.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { map?.onDetach() } }
}
