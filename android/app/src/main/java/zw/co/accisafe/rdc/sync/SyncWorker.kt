package zw.co.accisafe.rdc.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import zw.co.accisafe.rdc.data.SessionStore
import zw.co.accisafe.rdc.data.local.AppDatabase
import zw.co.accisafe.rdc.data.local.SubmissionStatus
import zw.co.accisafe.rdc.data.net.ApiClient
import zw.co.accisafe.rdc.data.net.DetectionEventRequest
import zw.co.accisafe.rdc.data.net.IncidentCreate
import zw.co.accisafe.rdc.data.net.appJson
import zw.co.accisafe.rdc.data.net.toProblem
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun syncNow() {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("sync-now", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    fun schedulePeriodic() {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(online).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

/**
 * Sends queued incident reports, their photos and detection events. Each report carries an
 * idempotency key, so a retry after a lost response returns the original incident instead of
 * creating a duplicate.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val db: AppDatabase,
    private val client: ApiClient,
    private val session: SessionStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val me = session.me.value ?: return Result.success() // nothing to send while signed out
        var retry = false

        for (report in db.reports().needingSync().filter { it.userId == me.id }) {
            var r = report
            if (r.status != SubmissionStatus.RECEIVED) {
                r = r.copy(status = SubmissionStatus.SENDING, attempts = r.attempts + 1, lastAttemptAt = System.currentTimeMillis(), error = null)
                db.reports().update(r)
                val draft = appJson.decodeFromString<IncidentCreate>(r.payloadJson)
                try {
                    val created = client.api.createIncident(draft)
                    r = r.copy(status = SubmissionStatus.RECEIVED, serverId = created.id, reference = created.reference, receivedAt = created.receivedAt)
                    db.reports().update(r)
                } catch (e: Exception) {
                    val p = e.toProblem()
                    val permanent = p.httpCode != null && p.httpCode in 400..499 && p.httpCode != 401 && p.httpCode != 408 && p.httpCode != 429
                    db.reports().update(r.copy(status = if (permanent) SubmissionStatus.FAILED else SubmissionStatus.WAITING, error = p.message))
                    if (!permanent) retry = true
                    continue
                }
            }
            // Photos follow the report; each is sent once.
            val paths = r.photoPaths.split("\n").filter { it.isNotBlank() }
            var uploaded = r.photosUploaded
            for (path in paths.drop(uploaded)) {
                val file = File(path)
                if (!file.exists()) { uploaded++; continue }
                try {
                    val part = MultipartBody.Part.createFormData("file", file.name, file.asRequestBody("image/jpeg".toMediaType()))
                    client.api.uploadAttachment(r.serverId!!, part)
                    uploaded++
                    db.reports().update(r.copy(photosUploaded = uploaded))
                } catch (e: Exception) {
                    db.reports().update(r.copy(photosUploaded = uploaded, error = "Photo upload: ${e.toProblem().message}"))
                    retry = true
                    break
                }
            }
        }

        for (d in db.detections().unsent().filter { it.userId == me.id }) {
            try {
                val res = client.api.detectionEvent(appJson.decodeFromString<DetectionEventRequest>(d.payloadJson))
                if (res.isSuccessful) db.detections().update(d.copy(sent = true, error = null))
                else db.detections().update(d.copy(error = "HTTP ${res.code()}")).also { if (res.code() >= 500) retry = true }
            } catch (e: Exception) {
                db.detections().update(d.copy(error = e.toProblem().message))
                retry = true
            }
        }
        return if (retry) Result.retry() else Result.success()
    }
}
