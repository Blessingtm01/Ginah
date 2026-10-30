package zw.co.accisafe.rdc.detection

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import zw.co.accisafe.rdc.R
import zw.co.accisafe.rdc.data.ConfigRepository
import zw.co.accisafe.rdc.data.ContactsRepository
import zw.co.accisafe.rdc.data.ReportRepository
import zw.co.accisafe.rdc.data.SessionStore
import zw.co.accisafe.rdc.data.net.DetectionEventRequest
import zw.co.accisafe.rdc.data.net.IncidentCreate
import zw.co.accisafe.rdc.location.LocationProvider
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A possible accident awaiting the user's decision. */
data class PendingAlert(
    val id: String,
    val detectedAt: Instant,
    val evidence: AccidentDetector.Evidence?,
    val simulated: Boolean,
    val sendTestSms: Boolean,
    val endsAtElapsed: Long,
)

/** What happened to the last alert, described without overstating it. */
data class AlertOutcome(val title: String, val lines: List<String>, val reportLocalId: String?)

/**
 * Runs the confirmation countdown and carries out the outcome:
 *  - council incident report queued (real detections only; simulations never create incidents);
 *  - SMS to the user's confirmed personal contacts, at most once per event;
 *  - a detection event recorded for the council's false-alarm statistics.
 * Emergency services are never called automatically.
 */
@Singleton
class DetectionCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: SessionStore,
    private val reports: ReportRepository,
    private val config: ConfigRepository,
    private val contacts: ContactsRepository,
    private val location: LocationProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _pending = MutableStateFlow<PendingAlert?>(null)
    val pending: StateFlow<PendingAlert?> = _pending
    private val _lastOutcome = MutableStateFlow<AlertOutcome?>(null)
    val lastOutcome: StateFlow<AlertOutcome?> = _lastOutcome
    private var timeout: Job? = null
    private var ringtone: Ringtone? = null

    fun start(evidence: AccidentDetector.Evidence?, simulated: Boolean, sendTestSms: Boolean = false) {
        if (_pending.value != null) return
        scope.launch {
            val seconds = session.currentDeviceSettings().countdownSeconds
            val alert = PendingAlert(
                id = UUID.randomUUID().toString().replace("-", ""), detectedAt = Instant.now(), evidence = evidence,
                simulated = simulated, sendTestSms = simulated && sendTestSms,
                endsAtElapsed = SystemClock.elapsedRealtime() + seconds * 1000L,
            )
            _pending.value = alert
            showCountdown(alert)
            startAlarm()
            timeout = launch {
                delay(seconds * 1000L)
                resolve(confirmed = true, byUser = false)
            }
        }
    }

    fun cancel() = scope.launch { resolve(confirmed = false, byUser = true) }
    fun sendNow() = scope.launch { resolve(confirmed = true, byUser = true) }

    private suspend fun resolve(confirmed: Boolean, byUser: Boolean) {
        val alert = _pending.value ?: return
        _pending.value = null
        timeout?.cancel()
        stopAlarm()
        context.getSystemService(NotificationManager::class.java).cancel(NOTIF_COUNTDOWN)

        val outcome = when {
            !confirmed -> "CANCELLED_BY_USER"
            byUser -> "CONFIRMED_BY_USER"
            else -> "CONFIRMED_NO_RESPONSE"
        }
        val lines = mutableListOf<String>()
        var localId: String? = null
        if (confirmed) {
            val fix = location.current(15_000)
            lines += if (fix != null) "Location recorded (±%.0f m).".format(fix.accuracyM) else "Location could not be obtained."
            if (!alert.simulated) {
                localId = queueCouncilReport(alert, fix)
                lines += if (localId != null) "Accident report saved and queued for the council. Check its status: it is only confirmed once the server receives it."
                else "Could not prepare a council report (sign in, or open the app once online to load categories). Report manually if you can."
            } else {
                lines += "Simulation: no council incident was created."
            }
            lines += sendPersonalSms(alert, fix)
        } else {
            lines += "Alert cancelled. Nothing was sent."
        }
        session.me.value?.let {
            reports.queueDetection(DetectionEventRequest(
                idempotencyKey = alert.id, detectedAt = alert.detectedAt.toString(), peakG = alert.evidence?.peakG ?: 0f,
                peakRotation = alert.evidence?.peakRotation, speedKmh = alert.evidence?.speedBeforeKmh,
                score = alert.evidence?.score, outcome = outcome, isSimulation = alert.simulated,
                evidence = (alert.evidence?.reasons?.joinToString("; ") ?: "manual simulation") + (localId?.let { "; report $it" } ?: ""),
            ))
        }
        _lastOutcome.value = AlertOutcome(
            title = when {
                !confirmed -> "Alert cancelled"
                alert.simulated -> "Simulation complete"
                else -> "Accident alert processed"
            },
            lines = lines, reportLocalId = localId,
        )
    }

    private suspend fun queueCouncilReport(alert: PendingAlert, fix: zw.co.accisafe.rdc.location.Fix?): String? {
        val me = session.me.value ?: return null
        val cfg = config.cached() ?: config.load().getOrNull() ?: return null
        val category = cfg.categories.firstOrNull { it.active && it.name.equals("Road traffic accident", true) }
            ?: cfg.categories.firstOrNull { it.active && it.kind == "EMERGENCY" } ?: return null
        val (localId, key) = ReportRepository.newDraftIds()
        val ev = alert.evidence
        val draft = IncidentCreate(
            idempotencyKey = key, localId = localId, categoryId = category.id, source = "ACCIDENT_DETECTION",
            description = "Possible road accident detected automatically by phone sensors" +
                (ev?.let { " (${it.reasons.joinToString(", ")})" } ?: "") + ". Not verified by a person.",
            reportedAt = alert.detectedAt.toString(), occurredAt = alert.detectedAt.toString(),
            latitude = fix?.latitude, longitude = fix?.longitude, accuracyM = fix?.accuracyM,
            locationSource = if (fix != null) "GPS" else "NONE",
            landmark = if (fix == null) "Location unavailable – detected by phone sensors" else null,
            injuries = "UNKNOWN", immediateDanger = "UNKNOWN", reporterName = me.fullName, reporterPhone = me.phone,
        )
        return reports.submit(draft, category.name, emptyList(), source = "ACCIDENT_DETECTION")
    }

    private suspend fun sendPersonalSms(alert: PendingAlert, fix: zw.co.accisafe.rdc.location.Fix?): String {
        val settings = session.currentDeviceSettings()
        if (alert.simulated && !alert.sendTestSms) return "Simulation: no SMS sent."
        if (!alert.simulated && !settings.smsToPersonalContacts) return "SMS to personal contacts is switched off."
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return "SMS permission not granted: personal contacts were not messaged."
        }
        val recipients = contacts.confirmed()
        if (recipients.isEmpty()) return "No confirmed personal emergency contacts: no SMS sent."
        val name = session.me.value?.fullName ?: "the owner of this phone"
        val where = fix?.let { "https://maps.google.com/?q=%.6f,%.6f (±%.0f m)".format(java.util.Locale.US, it.latitude, it.longitude, it.accuracyM) }
            ?: "location unavailable"
        val text = (if (alert.simulated) "[TEST – please ignore] " else "") +
            "AcciSafe RDC: a possible road accident involving $name was detected. Location: $where. Please call this number."
        var submitted = 0
        val sms = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
        for (c in recipients) {
            runCatching { sms.sendMultipartTextMessage(c.phone, null, sms.divideMessage(text), null, null) }
                .onSuccess { submitted++ }
        }
        // Handing a message to the phone's SMS service does not prove delivery.
        return "SMS handed to the phone for $submitted of ${recipients.size} personal contact(s); delivery is not confirmed."
    }

    private fun showCountdown(alert: PendingAlert) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Accident countdown", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
        })
        val intent = Intent(context, CountdownActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(if (alert.simulated) "SIMULATION: possible accident" else "Possible accident detected")
            .setContentText("Open to cancel or send help.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        runCatching { nm.notify(NOTIF_COUNTDOWN, n) }
        runCatching { context.startActivity(intent) }
    }

    private fun startAlarm() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
            audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            play()
        }
        context.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0))
    }

    private fun stopAlarm() {
        ringtone?.stop()
        ringtone = null
        context.getSystemService(Vibrator::class.java)?.cancel()
    }

    private companion object {
        const val CHANNEL = "countdown"
        const val NOTIF_COUNTDOWN = 42
    }
}
