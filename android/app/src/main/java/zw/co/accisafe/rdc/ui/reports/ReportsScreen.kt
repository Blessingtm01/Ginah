package zw.co.accisafe.rdc.ui.reports

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import zw.co.accisafe.rdc.data.apiCall
import zw.co.accisafe.rdc.data.net.ApiClient
import zw.co.accisafe.rdc.data.net.Me
import zw.co.accisafe.rdc.data.net.ReportSummary
import zw.co.accisafe.rdc.data.net.Timing
import zw.co.accisafe.rdc.ui.RemoteViewModel
import zw.co.accisafe.rdc.ui.components.ErrorBanner
import zw.co.accisafe.rdc.ui.components.GradientScaffold
import zw.co.accisafe.rdc.ui.components.InfoRow
import zw.co.accisafe.rdc.ui.components.LoadingBox
import zw.co.accisafe.rdc.ui.components.SectionCard
import zw.co.accisafe.rdc.ui.components.humanise
import zw.co.accisafe.rdc.ui.theme.Leaf
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject

enum class Period(val label: String, val days: Long?) { WEEK("7 days", 7), MONTH("30 days", 30), QUARTER("90 days", 90), ALL("All time", null) }

@HiltViewModel
class ReportsViewModel @Inject constructor(client: ApiClient) : RemoteViewModel<ReportSummary>(client) {
    val period = MutableStateFlow(Period.MONTH)
    private fun from() = period.value.days?.let { LocalDate.now().minusDays(it).atStartOfDay().toInstant(ZoneOffset.UTC).toString() }
    override suspend fun fetch() = api.reportSummary(from())
    fun setPeriod(p: Period) { period.value = p; reload() }

    fun exportCsv(context: Context, uri: Uri) = viewModelScope.launch {
        apiCall {
            val body = api.exportCsv(from())
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { out -> body.byteStream().copyTo(out) } }
        }.onSuccess { exportMessage.value = "CSV exported. The export has been recorded in the audit log." }
            .onFailure { exportMessage.value = it.message }
    }

    fun exportPdf(context: Context, uri: Uri, council: String) = viewModelScope.launch {
        val s = state.value.data ?: return@launch
        runCatching { withContext(Dispatchers.IO) { writePdf(context, uri, s, council, period.value.label) } }
            .onSuccess { exportMessage.value = "PDF report saved." }
            .onFailure { exportMessage.value = "PDF could not be written: ${it.message}" }
    }

    val exportMessage = MutableStateFlow<String?>(null)
}

private fun minutes(t: Timing?) = when {
    t == null || t.count == 0 -> "No data"
    else -> "avg %.0f min, median %.0f min (%d records)".format(t.averageMinutes ?: 0.0, t.medianMinutes ?: 0.0, t.count)
}

private fun JsonObject.flat(prefix: String = ""): List<Pair<String, String>> = entries.flatMap { (k, v) ->
    when (v) {
        is JsonObject -> v.flat("$prefix${humanise(k)} › ")
        is JsonPrimitive -> listOf("$prefix${humanise(k)}" to (if (v.content == "null") "No data" else v.content))
        else -> emptyList()
    }
}

@Composable
fun ReportsScreen(me: Me, nav: NavHostController, vm: ReportsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    val period by vm.period.collectAsState()
    val exportMessage by vm.exportMessage.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.reload() }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { it?.let { u -> vm.exportCsv(context, u) } }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { it?.let { u -> vm.exportPdf(context, u, me.councilName) } }

    GradientScaffold("Reports and analytics", subtitle = "Calculated from recorded data only", onBack = { nav.popBackStack() },
        bottomBar = { exportMessage?.let { Snackbar(Modifier.padding(12.dp), action = { TextButton(onClick = { vm.exportMessage.value = null }) { Text("OK") } }) { Text(it) } } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Period.entries.forEach { p -> FilterChip(period == p, { vm.setPeriod(p) }, { Text(p.label) }) }
                }
            }
            if (me.can("reports.export")) item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { csv.launch("incidents-${LocalDate.now()}.csv") }) { Text("Export CSV") }
                    OutlinedButton(onClick = { pdf.launch("accisafe-report-${LocalDate.now()}.pdf") }, enabled = state.data != null) { Text("Save PDF") }
                }
            }
            state.error?.let { item { ErrorBanner(it) { vm.reload() } } }
            val s = state.data
            if (s == null) { if (state.loading) item { LoadingBox() }; return@LazyColumn }
            item {
                SectionCard("Totals") {
                    s.totals.forEach { (k, v) -> InfoRow(humanise(k), v.toString()) }
                    if ((s.totals["demo_records"] ?: 0) > 0) Text("Includes demonstration records.", color = MaterialTheme.colorScheme.error)
                }
            }
            item {
                SectionCard("Response times") {
                    InfoRow("Acknowledgement", minutes(s.timings["acknowledgement"]))
                    Text("From server receipt of the report to acknowledgement by a coordinator.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    InfoRow("Assignment → arrival", minutes(s.timings["assignment_to_arrival"]))
                    Text("From assignment to the team recording arrival on scene.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    InfoRow("Resolution", minutes(s.timings["resolution"]))
                    Text("From server receipt to resolved or completed.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            listOf("By category" to s.byCategory, "By ward" to s.byWard, "By month" to s.byMonth, "By priority" to s.byPriority,
                "By status" to s.byStatus, "By source" to s.bySource).forEach { (title, data) ->
                item { BarCard(title, data) }
            }
            listOf("Assignments" to s.assignments, "Referrals" to s.referrals, "Resources" to s.resources,
                "Accident detection (incl. false alarms)" to s.accidentDetection, "Synchronisation" to s.synchronisation).forEach { (title, obj) ->
                item { SectionCard(title) { obj.flat().ifEmpty { listOf("Records" to "No data") }.forEach { (k, v) -> InfoRow(k, v) } } }
            }
        }
    }
}

@Composable
private fun BarCard(title: String, data: Map<String, Int>) {
    SectionCard(title) {
        if (data.isEmpty()) { Text("No records in this period."); return@SectionCard }
        val max = data.values.maxOrNull()?.coerceAtLeast(1) ?: 1
        data.forEach { (k, v) ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Row { Text(humanise(k), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Text(v.toString(), style = MaterialTheme.typography.labelLarge) }
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth(v.toFloat() / max).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(Leaf))
                }
            }
        }
    }
}

/** Writes a simple A4 PDF summary. Contains aggregates only, no personal information. */
private fun writePdf(context: Context, uri: Uri, s: ReportSummary, council: String, period: String) {
    val doc = PdfDocument()
    val title = Paint().apply { textSize = 18f; typeface = Typeface.DEFAULT_BOLD; color = 0xFF14532D.toInt() }
    val head = Paint().apply { textSize = 13f; typeface = Typeface.DEFAULT_BOLD }
    val body = Paint().apply { textSize = 11f }
    var pageNo = 1
    var page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNo).create())
    var y = 50f
    fun line(text: String, p: Paint = body, gap: Float = 16f) {
        if (y > 800) { doc.finishPage(page); pageNo++; page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNo).create()); y = 50f }
        page.canvas.drawText(text, 40f, y, p); y += gap
    }
    line("AcciSafe RDC – Operational report", title, 24f)
    line("$council · Period: $period · Generated ${LocalDate.now()}", body, 22f)
    line("Totals", head, 18f); s.totals.forEach { (k, v) -> line("${humanise(k)}: $v") }
    y += 8; line("Response times", head, 18f)
    s.timings.forEach { (k, t) -> line("${humanise(k)}: ${minutes(t)}") }
    listOf("By category" to s.byCategory, "By ward" to s.byWard, "By priority" to s.byPriority, "By status" to s.byStatus, "By month" to s.byMonth)
        .forEach { (h, m) -> y += 8; line(h, head, 18f); if (m.isEmpty()) line("No records") else m.forEach { (k, v) -> line("${humanise(k)}: $v") } }
    listOf("Accident detection" to s.accidentDetection, "Referrals" to s.referrals, "Resources" to s.resources)
        .forEach { (h, o) -> y += 8; line(h, head, 18f); o.flat().forEach { (k, v) -> line("$k: $v") } }
    y += 12; line("Figures are calculated from records stored in the AcciSafe RDC database for this period.", body)
    doc.finishPage(page)
    context.contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) }
    doc.close()
}
