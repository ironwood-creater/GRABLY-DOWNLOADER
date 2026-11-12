package com.grabbly.downloader.data.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

enum class DownloadStatus { QUEUED, RUNNING, COMPLETE, FAILED }

/** One download in the Downloads list. */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceUrl: String,
    val platform: String,
    val title: String,
    val uploader: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Int?,
    val optionId: String,
    val optionLabel: String,
    val isAudio: Boolean,
    val status: DownloadStatus,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = 0,
    val fileName: String? = null,
    /** Where the saved file is: content:// in the gallery, or file:// in the private folder */
    val contentUri: String? = null,
    val mimeType: String? = null,
    val error: String? = null,
    val createdAt: Long,
    val completedAt: Long? = null,
    /** Which video or photo of a post with several (null: the post's only video) */
    val itemIndex: Int? = null,
    /** The post's text, for "Copy caption" */
    val caption: String? = null,
    @ColumnInfo(defaultValue = "0") val isPhoto: Boolean = false,
    /** In the private folder: kept inside Grabbly, not in the gallery */
    @ColumnInfo(defaultValue = "0") val isPrivate: Boolean = false,
) {
    val isActive: Boolean get() = status == DownloadStatus.QUEUED || status == DownloadStatus.RUNNING
    val isComplete: Boolean get() = status == DownloadStatus.COMPLETE

    /** The file type, guessed from what was downloaded when the server didn't say */
    val mimeOrDefault: String
        get() = mimeType ?: when {
            isPhoto -> "image/jpeg"
            isAudio -> "audio/mpeg"
            else -> "video/mp4"
        }
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    fun observe(id: Long): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadEntity?

    @Insert
    suspend fun insert(item: DownloadEntity): Long

    @Query("UPDATE downloads SET status = :status, bytesDownloaded = :done, totalBytes = :total, error = NULL WHERE id = :id")
    suspend fun setProgress(id: Long, status: DownloadStatus, done: Long, total: Long)

    @Query(
        "UPDATE downloads SET status = 'COMPLETE', contentUri = :uri, fileName = :fileName, mimeType = :mime, " +
            "bytesDownloaded = :size, totalBytes = :size, completedAt = :at, error = NULL WHERE id = :id",
    )
    suspend fun markComplete(id: Long, uri: String, fileName: String, mime: String, size: Long, at: Long)

    @Query("UPDATE downloads SET status = 'FAILED', error = :error WHERE id = :id")
    suspend fun markFailed(id: Long, error: String)

    @Query("UPDATE downloads SET status = 'QUEUED', bytesDownloaded = 0, error = NULL WHERE id = :id")
    suspend fun resetForRetry(id: Long)

    /** Back to the queue after Android paused the download, unless it was cancelled meanwhile (one atomic step). */
    @Query("UPDATE downloads SET status = 'QUEUED', bytesDownloaded = 0 WHERE id = :id AND status = 'RUNNING'")
    suspend fun requeueIfRunning(id: Long)

    /** After moving the file into or out of the private folder */
    @Query("UPDATE downloads SET contentUri = :uri, isPrivate = :isPrivate WHERE id = :id")
    suspend fun setLocation(id: Long, uri: String, isPrivate: Boolean)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [DownloadEntity::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    companion object {
        /** Version 1.3: posts with several items, captions, photos and the private folder */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN itemIndex INTEGER")
                db.execSQL("ALTER TABLE downloads ADD COLUMN caption TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN isPhoto INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN isPrivate INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "grabbly.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
