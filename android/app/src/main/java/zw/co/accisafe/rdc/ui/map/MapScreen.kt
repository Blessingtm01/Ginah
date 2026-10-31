package zw.co.accisafe.rdc.ui.map

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAlert
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import coil.ImageLoader
import coil.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import zw.co.accisafe.rdc.data.SessionStore
import zw.co.accisafe.rdc.data.net.ApiClient
import zw.co.accisafe.rdc.data.net.LiveMapResponse
import zw.co.accisafe.rdc.data.net.MapIncident
import zw.co.accisafe.rdc.data.net.Me
import zw.co.accisafe.rdc.ui.RemoteViewModel
import zw.co.accisafe.rdc.ui.Routes
import zw.co.accisafe.rdc.ui.components.ErrorBanner
import zw.co.accisafe.rdc.ui.components.GradientScaffold
import zw.co.accisafe.rdc.ui.components.InfoRow
import zw.co.accisafe.rdc.ui.components.PriorityBadge
import zw.co.accisafe.rdc.ui.components.StatusBadge
import zw.co.accisafe.rdc.ui.components.formatTime
import zw.co.accisafe.rdc.ui.theme.Critical
import zw.co.accisafe.rdc.ui.theme.Forest
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

data class MapFilter(val days: Int = 30, val activeOnly: Boolean = false)

@HiltViewModel
class MapViewModel @Inject constructor(client: ApiClient, private val session: SessionStore) : RemoteViewModel<LiveMapResponse>(client) {
    val filter = MutableStateFlow(MapFilter())
    val hiddenTypes = MutableStateFlow<Set<String>>(emptySet())
    val serverUrl get() = session.serverUrl
    val okHttp by lazy { client.okHttp }

    override suspend fun fetch(): LiveMapResponse = filter.value.let { api.liveMap(days = it.days, activeOnly = it.activeOnly) }
    fun apply(f: MapFilter) { filter.value = f; reload() }
    fun toggle(type: String) { hiddenTypes.value = hiddenTypes.value.let { if (type in it) it - type else it + type } }
}

private const val REFRESH_MS = 30_000L

/**
 * Live council map: the council boundary and every report in the council area, coloured by the map key.
 * Tapping a dot shows what was reported and the photos taken at the scene. Reporter identities are never shown here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(me: Me, nav: NavHostController, vm: MapViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    val f by vm.filter.collectAsState()
    val hidden by vm.hiddenTypes.collectAsState()
    val context = LocalContext.current
    val data = state.data
    var map by remember { mutableStateOf<MapView?>(null) }
    var fitted by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<MapIncident?>(null) }
    var legendOpen by remember { mutableStateOf(false) }
    val loader = remember { ImageLoader.Builder(context).okHttpClient(vm.okHttp).build() }

    // Keep the map live while the screen is open.
    LaunchedEffect(Unit) {
        while (true) { vm.reload(); delay(REFRESH_MS) }
    }

    // Redraw overlays whenever the data or the hidden types change.
    LaunchedEffect(map, data, hidden) {
        val mv = map ?: return@LaunchedEffect
        val d = data ?: return@LaunchedEffect
        mv.overlays.removeAll { it is Marker || it is Polygon }
        boundaryPolygons(d.council.boundary).forEach { ring ->
            mv.overlays.add(0, Polygon(mv).apply {
                points = ring
                outlinePaint.color = android.graphics.Color.parseColor("#14532D")
                outlinePaint.strokeWidth = 6f
                outlinePaint.pathEffect = DashPathEffect(floatArrayOf(24f, 14f), 0f)
                fillPaint.color = android.graphics.Color.argb(22, 46, 158, 91)
                setOnClickListener { _, _, _ -> false }
                infoWindow = null
            })
        }
        val density = context.resources.displayMetrics.density
        d.incidents.filter { it.type !in hidden }.sortedBy { it.active }.forEach { inc ->
            mv.overlays.add(Marker(mv).apply {
                position = GeoPoint(inc.latitude, inc.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = BitmapDrawable(context.resources, dotBitmap(inc, density))
                title = inc.category
                setOnMarkerClickListener { _, _ -> selected = inc; true }
            })
        }
        if (!fitted) {
            val ring = boundaryPolygons(d.council.boundary).flatten()
            if (ring.isNotEmpty()) {
                mv.post { mv.zoomToBoundingBox(BoundingBox.fromGeoPoints(ring), false, (24 * density).toInt()) }
                fitted = true
            }
        }
        mv.invalidate()
    }

    val subtitle = data?.let { d ->
        val open = d.incidents.count { it.active }
        "${d.council.areaName ?: d.council.name} · ${d.incidents.size} reports · $open open"
    } ?: "Loading the council map…"

    GradientScaffold(
        title = "Live council map",
        subtitle = subtitle,
        floatingActionButton = {
            if (me.can("incidents.create")) {
                ExtendedFloatingActionButton(
                    onClick = { nav.navigate(Routes.report()) },
                    icon = { Icon(Icons.Default.AddAlert, null) },
                    text = { Text("Report what's happening") },
                    containerColor = Critical, contentColor = Color.White,
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1 to "24 hours", 7 to "7 days", 30 to "30 days", 90 to "90 days").forEach { (days, label) ->
                    FilterChip(f.days == days, { vm.apply(f.copy(days = days)) }, { Text(label) })
                }
                FilterChip(f.activeOnly, { vm.apply(f.copy(activeOnly = !f.activeOnly)) }, { Text("Open only") })
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
                            controller.setCenter(GeoPoint(-17.80, 31.84))
                            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                                overlays.add(MyLocationNewOverlay(GpsMyLocationProvider(ctx), this).apply { enableMyLocation() })
                            }
                            map = this
                        }
                    },
                )
                data?.let { d ->
                    Legend(d, hidden, legendOpen, { legendOpen = !legendOpen }, vm::toggle,
                        Modifier.align(Alignment.TopStart).padding(10.dp))
                }
                Text("© OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomStart).background(Color.White.copy(alpha = .8f)).padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
    }

    selected?.let { inc ->
        ModalBottomSheet(onDismissRequest = { selected = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            IncidentSheet(inc, data?.legend?.firstOrNull { it.code == inc.type }?.label, "${vm.serverUrl}api/v1/map/photos/", loader,
                canOpen = inc.mine || me.can("incidents.view_all") || me.can("incidents.view_assigned"),
                onOpen = { selected = null; nav.navigate(Routes.incident(inc.id)) })
        }
    }
    DisposableEffect(Unit) { onDispose { map?.onDetach() } }
}

@Composable
private fun Legend(d: LiveMapResponse, hidden: Set<String>, open: Boolean, onToggleOpen: () -> Unit, onToggle: (String) -> Unit, modifier: Modifier) {
    val counts = d.incidents.groupingBy { it.type }.eachCount()
    Surface(modifier.widthIn(max = 280.dp), shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.clickable(onClick = onToggleOpen), verticalAlignment = Alignment.CenterVertically) {
                Text("Map key", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                if (!open) {
                    d.legend.filter { (counts[it.code] ?: 0) > 0 }.forEach { l ->
                        Box(Modifier.padding(end = 4.dp).size(12.dp).clip(CircleShape).background(parse(l.color)))
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (open) "Hide key" else "Show key")
            }
            AnimatedVisibility(open) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    d.legend.forEach { l ->
                        val off = l.code in hidden
                        Row(Modifier.fillMaxWidth().clickable { onToggle(l.code) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(14.dp).clip(CircleShape).background(parse(l.color).copy(alpha = if (off) .3f else 1f)))
                            Spacer(Modifier.width(8.dp))
                            Text(l.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                                textDecoration = if (off) TextDecoration.LineThrough else null,
                                color = if (off) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                            Text("${counts[l.code] ?: 0}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text("Tap a type to hide or show it. Faded dots are resolved.", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun IncidentSheet(inc: MapIncident, typeLabel: String?, photoBase: String, loader: ImageLoader, canOpen: Boolean, onOpen: () -> Unit) {
    var viewer by remember { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(parse(inc.color), Forest))).padding(18.dp)) {
            Text(typeLabel ?: inc.category, color = Color.White.copy(alpha = .9f), style = MaterialTheme.typography.labelLarge)
            Text(inc.category, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("${inc.reference} · reported ${ago(inc.receivedAt)}", color = Color.White.copy(alpha = .9f))
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusBadge(inc.status)
                if (inc.priority != null) PriorityBadge(inc.priority)
                if (inc.mine) Text("Your report", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PhotoCamera, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(if (inc.photoIds.isEmpty()) "No photos were added" else "${inc.photoIds.size} photo(s) from the scene",
                    style = MaterialTheme.typography.titleSmall)
            }
            if (inc.photoIds.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(inc.photoIds) { i, id ->
                        AsyncImage(model = "$photoBase$id", contentDescription = "Photo ${i + 1}", imageLoader = loader,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(140.dp).clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant).clickable { viewer = i })
                    }
                }
            }
            Text(inc.description, style = MaterialTheme.typography.bodyLarge)
            InfoRow("Deaths reported", tri(inc.fatalities))
            InfoRow("Injuries", tri(inc.injuries))
            inc.affectedPeople?.let { InfoRow("People affected", it.toString()) }
            InfoRow("Where", listOfNotNull(inc.ward, inc.landmark).joinToString(" · ").ifBlank { "GPS position" })
            InfoRow("Reported", formatTime(inc.reportedAt))
            InfoRow("Last update", formatTime(inc.updatedAt))
            if (canOpen) Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text("Open full report") }
            Text("Do not go to the scene unless it is safe. Call emergency services if lives are at risk.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
    }
    viewer?.let { start -> PhotoViewer(inc.photoIds.map { "$photoBase$it" }, start, loader) { viewer = null } }
}

@Composable
private fun PhotoViewer(urls: List<String>, start: Int, loader: ImageLoader, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = start) { urls.size }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                AsyncImage(model = urls[page], contentDescription = "Photo ${page + 1}", imageLoader = loader,
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                Icon(Icons.Default.Close, "Close", tint = Color.White)
            }
            Text("${pager.currentPage + 1} / ${urls.size}", color = Color.White,
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp))
        }
    }
}

// --- helpers ---------------------------------------------------------------------------------

private fun parse(hex: String) = Color(android.graphics.Color.parseColor(hex))

private fun tri(v: String?) = when (v) { "YES" -> "Yes"; "NO" -> "No"; "UNKNOWN" -> "Don't know"; else -> "Not answered" }

private fun ago(iso: String): String = runCatching {
    val m = Duration.between(Instant.parse(iso), Instant.now()).toMinutes()
    when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 60 * 24 -> "${m / 60} h ago"
        else -> "${m / (60 * 24)} d ago"
    }
}.getOrDefault(formatTime(iso))

/** A coloured dot with a white ring; faded when resolved, with a small photo count badge when photos exist. */
private fun dotBitmap(inc: MapIncident, density: Float): Bitmap {
    val r = 11f * density
    val pad = 8f * density
    val size = (2 * (r + pad)).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val cx = size / 2f
    val alpha = if (inc.active) 255 else 115
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.argb(70, 0, 0, 0)
    c.drawCircle(cx, cx + density, r + 2.5f * density, paint)
    paint.color = android.graphics.Color.WHITE; paint.alpha = alpha
    c.drawCircle(cx, cx, r + 2.5f * density, paint)
    paint.color = android.graphics.Color.parseColor(inc.color); paint.alpha = alpha
    c.drawCircle(cx, cx, r, paint)
    if (inc.photoIds.isNotEmpty()) {
        val bx = cx + r * .85f
        val by = cx - r * .85f
        paint.color = android.graphics.Color.WHITE; paint.alpha = 255
        c.drawCircle(bx, by, 7f * density, paint)
        paint.color = android.graphics.Color.parseColor("#14532D")
        paint.textSize = 9f * density
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true
        c.drawText(inc.photoIds.size.toString(), bx, by + 3.2f * density, paint)
    }
    return bmp
}

/** Outer rings of a GeoJSON Polygon or MultiPolygon as map points (GeoJSON order is longitude, latitude). */
private fun boundaryPolygons(geometry: JsonObject?): List<List<GeoPoint>> {
    geometry ?: return emptyList()
    val type = geometry["type"]?.jsonPrimitive?.content
    val coords = geometry["coordinates"]?.jsonArray ?: return emptyList()
    fun ring(a: JsonArray) = a.map { p -> p.jsonArray.let { GeoPoint(it[1].jsonPrimitive.double, it[0].jsonPrimitive.double) } }
    return runCatching {
        when (type) {
            "Polygon" -> listOf(ring(coords[0].jsonArray))
            "MultiPolygon" -> coords.map { ring(it.jsonArray[0].jsonArray) }
            else -> emptyList()
        }
    }.getOrDefault(emptyList())
}
