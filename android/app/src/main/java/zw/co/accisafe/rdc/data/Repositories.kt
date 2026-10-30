package zw.co.accisafe.rdc.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import zw.co.accisafe.rdc.data.local.AppDatabase
import zw.co.accisafe.rdc.data.local.CacheEntry
import zw.co.accisafe.rdc.data.local.EmergencyContact
import zw.co.accisafe.rdc.data.local.PendingDetection
import zw.co.accisafe.rdc.data.local.PendingReport
import zw.co.accisafe.rdc.data.local.SubmissionStatus
import zw.co.accisafe.rdc.data.net.ApiClient
import zw.co.accisafe.rdc.data.net.ApiProblem
import zw.co.accisafe.rdc.data.net.ConfigResponse
import zw.co.accisafe.rdc.data.net.DetectionEventRequest
import zw.co.accisafe.rdc.data.net.IncidentCreate
import zw.co.accisafe.rdc.data.net.LoginRequest
import zw.co.accisafe.rdc.data.net.Me
import zw.co.accisafe.rdc.data.net.RefreshRequest
import zw.co.accisafe.rdc.data.net.RegisterRequest
import zw.co.accisafe.rdc.data.net.appJson
import zw.co.accisafe.rdc.data.net.toProblem
import zw.co.accisafe.rdc.sync.SyncScheduler
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "accisafe_rdc.db").build()
}

/** Runs an API call and converts failures to [ApiProblem]. */
suspend fun <T> apiCall(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: Exception) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    Result.failure(e.toProblem())
}

@Singleton
class AuthRepository @Inject constructor(
    private val client: ApiClient,
    private val session: SessionStore,
    private val db: AppDatabase,
) {
    val me = session.me

    suspend fun login(council: String, username: String, password: String): Result<Me> = apiCall {
        val t = client.api.login(LoginRequest(council.trim().uppercase(), username.trim().lowercase(), password, android.os.Build.MODEL))
        session.saveSession(t.accessToken, t.refreshToken, t.user)
        t.user
    }

    suspend fun register(council: String, username: String, fullName: String, phone: String?, password: String): Result<Me> = apiCall {
        val t = client.api.register(RegisterRequest(council.trim().uppercase(), username.trim().lowercase(), fullName.trim(), phone, password))
        session.saveSession(t.accessToken, t.refreshToken, t.user)
        t.user
    }

    suspend fun refreshProfile() {
        apiCall { client.api.me() }.onSuccess { session.updateMe(it) }
    }

    suspend fun logout() {
        session.refreshToken()?.let { token -> apiCall { client.api.logout(RefreshRequest(token)) } }
        session.clearSession()
        db.cache().clear()
    }
}

/** Council configuration (categories, wards, organisations) cached for offline report forms. */
@Singleton
class ConfigRepository @Inject constructor(private val client: ApiClient, private val db: AppDatabase) {
    suspend fun load(forceRefresh: Boolean = false): Result<ConfigResponse> {
        if (!forceRefresh) cached()?.let { cachedCfg ->
            // Refresh in the background of the call, but answer from cache if offline.
            return apiCall { client.api.config() }.onSuccess { save(it) }.recoverCatching { cachedCfg }
        }
        return apiCall { client.api.config() }.onSuccess { save(it) }.recoverCatching { e ->
            cached() ?: throw e
        }
    }

    suspend fun cached(): ConfigResponse? =
        db.cache().get(KEY)?.let { runCatching { appJson.decodeFromString<ConfigResponse>(it.json) }.getOrNull() }

    private suspend fun save(cfg: ConfigResponse) = db.cache().put(CacheEntry(KEY, appJson.encodeToString(cfg), System.currentTimeMillis()))

    private companion object { const val KEY = "config" }
}

/** Incident reports are always saved locally first, then sent by the sync worker. */
@Singleton
class ReportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val session: SessionStore,
    private val scheduler: SyncScheduler,
) {
    fun observe(localId: String) = db.reports().observe(localId)
    fun observeMine(userId: Int) = db.reports().observeAll(userId)
    fun unsentCount(userId: Int): Flow<Int> = db.reports().unsentCount(userId)

    /** Saves the report and queues it. Returns the local id. Never claims the council received it. */
    suspend fun submit(draft: IncidentCreate, categoryName: String, photos: List<Uri>, source: String = draft.source): String {
        val me = session.me.value ?: throw ApiProblem("Sign in again to report")
        val localId = draft.localId
        val paths = photos.mapNotNull { copyPhoto(it) }
        db.reports().insert(
            PendingReport(
                localId = localId, idempotencyKey = draft.idempotencyKey, councilCode = me.councilCode, userId = me.id,
                payloadJson = appJson.encodeToString(draft), categoryName = categoryName, createdAt = System.currentTimeMillis(),
                photoPaths = paths.joinToString("\n"), photoCount = paths.size, source = source,
            )
        )
        scheduler.syncNow()
        return localId
    }

    suspend fun retry(localId: String) {
        db.reports().get(localId)?.let { db.reports().update(it.copy(status = SubmissionStatus.WAITING, error = null)) }
        scheduler.syncNow()
    }

    suspend fun discard(localId: String) = db.reports().deleteUnsent(localId)

    suspend fun queueDetection(event: DetectionEventRequest) {
        val me = session.me.value ?: return
        db.detections().insert(PendingDetection(event.idempotencyKey, me.id, appJson.encodeToString(event), System.currentTimeMillis()))
        scheduler.syncNow()
    }

    /** Copies a picked photo into private storage, downscaled to at most 1600 px, as JPEG. */
    private suspend fun copyPhoto(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
            val bmp = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@runCatching null
            val dir = File(context.filesDir, "report_photos").apply { mkdirs() }
            val file = File(dir, "${UUID.randomUUID()}.jpg")
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            file.absolutePath
        }.getOrNull()
    }

    companion object {
        fun newDraftIds(): Pair<String, String> =
            "local-" + UUID.randomUUID().toString().take(8) to UUID.randomUUID().toString().replace("-", "")

        fun nowIso(): String = Instant.now().toString()
    }
}

@Singleton
class ContactsRepository @Inject constructor(private val db: AppDatabase) {
    val contacts = db.contacts().observe()
    suspend fun add(name: String, phone: String) = db.contacts().insert(EmergencyContact(name = name, phone = phone, confirmed = false))
    suspend fun confirm(c: EmergencyContact, confirmed: Boolean) = db.contacts().update(c.copy(confirmed = confirmed))
    suspend fun delete(id: Long) = db.contacts().delete(id)
    suspend fun confirmed() = db.contacts().confirmed()
}
