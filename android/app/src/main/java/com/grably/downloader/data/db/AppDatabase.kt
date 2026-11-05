package com.grably.downloader.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
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
    /** content:// address of the saved file in the phone's gallery/music */
    val contentUri: String? = null,
    val mimeType: String? = null,
    val error: String? = null,
    val createdAt: Long,
    val completedAt: Long? = null,
) {
    val isActive: Boolean get() = status == DownloadStatus.QUEUED || status == DownloadStatus.RUNNING
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

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

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [DownloadEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "grably.db").build()
    }
}
