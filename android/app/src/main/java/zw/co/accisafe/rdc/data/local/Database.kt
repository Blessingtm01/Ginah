package zw.co.accisafe.rdc.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Submission states shown to the user. A report only counts as reaching the council at RECEIVED,
 * when the server has answered with its reference.
 */
object SubmissionStatus {
    const val SAVED = "SAVED"         // Saved on this device
    const val WAITING = "WAITING"     // Waiting to synchronise
    const val SENDING = "SENDING"     // Submitted to the server, awaiting its answer
    const val RECEIVED = "RECEIVED"   // Received by the server
    const val FAILED = "FAILED"       // Submission failed (needs attention)

    fun label(status: String) = when (status) {
        SAVED -> "Saved on this device"
        WAITING -> "Waiting to synchronise"
        SENDING -> "Submitted to the server"
        RECEIVED -> "Received by the server"
        FAILED -> "Submission failed"
        else -> status
    }
}

@Entity(tableName = "pending_reports")
data class PendingReport(
    @PrimaryKey val localId: String,
    val idempotencyKey: String,
    val councilCode: String,
    val userId: Int,
    val payloadJson: String,
    val categoryName: String,
    val createdAt: Long,
    val status: String = SubmissionStatus.SAVED,
    val serverId: Int? = null,
    val reference: String? = null,
    val receivedAt: String? = null,
    val error: String? = null,
    val attempts: Int = 0,
    val lastAttemptAt: Long? = null,
    val photoPaths: String = "",          // newline-separated files in app storage
    val photoCount: Int = 0,
    val photosUploaded: Int = 0,
    val source: String = "APP",
)

@Entity(tableName = "pending_detections")
data class PendingDetection(
    @PrimaryKey val idempotencyKey: String,
    val userId: Int,
    val payloadJson: String,
    val createdAt: Long,
    val sent: Boolean = false,
    val error: String? = null,
)

/** Personal emergency contacts for the accident-alert SMS. Separate from council dispatch. */
@Entity(tableName = "emergency_contacts")
data class EmergencyContact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String,
    val confirmed: Boolean,
)

@Entity(tableName = "cache")
data class CacheEntry(@PrimaryKey val key: String, val json: String, val savedAt: Long)

@Dao
interface PendingReportDao {
    @Insert suspend fun insert(r: PendingReport)
    @Update suspend fun update(r: PendingReport)
    @Query("SELECT * FROM pending_reports WHERE localId = :id") suspend fun get(id: String): PendingReport?
    @Query("SELECT * FROM pending_reports WHERE localId = :id") fun observe(id: String): Flow<PendingReport?>
    @Query("SELECT * FROM pending_reports WHERE userId = :userId ORDER BY createdAt DESC") fun observeAll(userId: Int): Flow<List<PendingReport>>
    @Query("SELECT * FROM pending_reports WHERE status IN ('SAVED','WAITING','SENDING') OR (status = 'RECEIVED' AND photosUploaded < photoCount) ORDER BY createdAt")
    suspend fun needingSync(): List<PendingReport>
    @Query("SELECT COUNT(*) FROM pending_reports WHERE userId = :userId AND status != 'RECEIVED'") fun unsentCount(userId: Int): Flow<Int>
    @Query("DELETE FROM pending_reports WHERE localId = :id AND status != 'RECEIVED'") suspend fun deleteUnsent(id: String)
}

@Dao
interface PendingDetectionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(d: PendingDetection)
    @Update suspend fun update(d: PendingDetection)
    @Query("SELECT * FROM pending_detections WHERE sent = 0 ORDER BY createdAt") suspend fun unsent(): List<PendingDetection>
    @Query("SELECT * FROM pending_detections WHERE userId = :userId ORDER BY createdAt DESC") fun observeAll(userId: Int): Flow<List<PendingDetection>>
}

@Dao
interface EmergencyContactDao {
    @Query("SELECT * FROM emergency_contacts ORDER BY id") fun observe(): Flow<List<EmergencyContact>>
    @Query("SELECT * FROM emergency_contacts WHERE confirmed = 1 ORDER BY id") suspend fun confirmed(): List<EmergencyContact>
    @Insert suspend fun insert(c: EmergencyContact)
    @Update suspend fun update(c: EmergencyContact)
    @Query("DELETE FROM emergency_contacts WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface CacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(e: CacheEntry)
    @Query("SELECT * FROM cache WHERE `key` = :key") suspend fun get(key: String): CacheEntry?
    @Query("DELETE FROM cache") suspend fun clear()
}

@Database(
    entities = [PendingReport::class, PendingDetection::class, EmergencyContact::class, CacheEntry::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun reports(): PendingReportDao
    abstract fun detections(): PendingDetectionDao
    abstract fun contacts(): EmergencyContactDao
    abstract fun cache(): CacheDao
}
