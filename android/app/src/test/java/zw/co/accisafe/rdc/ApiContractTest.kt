package zw.co.accisafe.rdc

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import zw.co.accisafe.rdc.data.local.SubmissionStatus
import zw.co.accisafe.rdc.data.net.ConfigResponse
import zw.co.accisafe.rdc.data.net.IncidentCreate
import zw.co.accisafe.rdc.data.net.IncidentDetail
import zw.co.accisafe.rdc.data.net.IncidentSummary
import zw.co.accisafe.rdc.data.net.NotificationsResponse
import zw.co.accisafe.rdc.data.net.Page
import zw.co.accisafe.rdc.data.net.ReportSummary
import zw.co.accisafe.rdc.data.net.Resource
import zw.co.accisafe.rdc.data.net.Team
import zw.co.accisafe.rdc.data.net.TokenResponse
import zw.co.accisafe.rdc.data.net.appJson

/**
 * The fixtures in src/test/resources are real responses captured from the AcciSafe RDC backend
 * (demo council). These tests fail if the app and server disagree about the wire format.
 */
class ApiContractTest {

    private fun fixture(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test
    fun loginResponseParses() {
        val t = appJson.decodeFromString<TokenResponse>(fixture("login.json"))
        assertEquals("COMMUNITY_REPORTER", t.user.roles.single())
        assertTrue(t.user.isCommunityOnly)
        assertTrue(t.user.can("incidents.create"))
        assertFalse(t.user.can("incidents.view_all"))
        assertTrue(t.user.councilIsDemo)
    }

    @Test
    fun configParses() {
        val c = appJson.decodeFromString<ConfigResponse>(fixture("config.json"))
        assertTrue(c.categories.any { it.name == "Road traffic accident" && it.kind == "EMERGENCY" })
        assertTrue(c.categories.any { it.kind == "ROUTINE" })
        assertTrue(c.wards.isNotEmpty())
    }

    @Test
    fun incidentResponsesParse() {
        val created = appJson.decodeFromString<IncidentSummary>(fixture("incident_created.json"))
        assertEquals("AWAITING_REVIEW", created.status)
        assertNull(created.priority)
        assertEquals("HIGH", created.effectivePriority) // suggested only, never final
        assertEquals(false, created.duplicate)

        val d = appJson.decodeFromString<IncidentDetail>(fixture("incident_detail.json"))
        assertEquals(created.id, d.id)
        assertEquals(listOf("ACKNOWLEDGED", "CANCELLED"), d.allowedStatusChanges)
        assertEquals(listOf("SUBMITTED", "AWAITING_REVIEW"), d.statusHistory.map { it.to })
        assertNotNull(d.reporterPhone) // coordinator may see personal details
        assertTrue(d.canAssess)

        val page = appJson.decodeFromString<Page<IncidentSummary>>(fixture("incident_page.json"))
        assertTrue(page.total >= page.items.size)
    }

    @Test
    fun operationalResponsesParse() {
        val s = appJson.decodeFromString<ReportSummary>(fixture("report_summary.json"))
        assertTrue((s.totals["incidents"] ?: 0) > 0)
        assertTrue("acknowledgement" in s.timings)
        val n = appJson.decodeFromString<NotificationsResponse>(fixture("notifications.json"))
        assertTrue(n.items.all { it.channels.isNotEmpty() })
        assertTrue(appJson.decodeFromString<List<Team>>(fixture("teams.json")).isNotEmpty())
        assertTrue(appJson.decodeFromString<List<Resource>>(fixture("resources.json")).isNotEmpty())
    }

    @Test
    fun outgoingReportUsesServerFieldNamesAndOmitsUnknowns() {
        val json = appJson.encodeToString(IncidentCreate(
            idempotencyKey = "abc12345", localId = "local-1", categoryId = 3, description = "Test",
            reportedAt = "2026-10-29T08:00:00Z", latitude = -17.6, longitude = 31.8, injuries = "UNKNOWN",
        ))
        val obj = appJson.parseToJsonElement(json).jsonObject
        assertTrue("idempotency_key" in obj && "reported_at" in obj && "category_id" in obj)
        assertEquals("\"UNKNOWN\"", obj["injuries"].toString()) // "unknown" is sent explicitly
        assertFalse("immediate_danger" in obj) // not answered: omitted, so the server records "not provided"
    }

    @Test
    fun submissionLabelsNeverOverstate() {
        assertEquals("Saved on this device", SubmissionStatus.label(SubmissionStatus.SAVED))
        assertEquals("Waiting to synchronise", SubmissionStatus.label(SubmissionStatus.WAITING))
        assertEquals("Received by the server", SubmissionStatus.label(SubmissionStatus.RECEIVED))
    }
}
