package com.grabbly.downloader.data

import android.content.Context
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.grabbly.downloader.data.db.DownloadDao
import com.grabbly.downloader.data.db.DownloadEntity
import com.grabbly.downloader.data.db.DownloadStatus
import com.grabbly.downloader.download.DownloadWorker
import kotlinx.coroutines.flow.Flow

/** Starts, retries, cancels and removes downloads. Downloads run in [DownloadWorker]. */
class DownloadRepository(private val context: Context, private val dao: DownloadDao) {

    val all: Flow<List<DownloadEntity>> = dao.observeAll()

    suspend fun start(video: VideoInfo, option: DownloadOption): Long {
        val id = dao.insert(
            DownloadEntity(
                sourceUrl = video.url,
                platform = video.platform,
                title = video.title,
                uploader = video.uploader,
                thumbnailUrl = video.thumbnail,
                durationSeconds = video.duration,
                optionId = option.id,
                optionLabel = option.label,
                isAudio = option.isAudio,
                status = DownloadStatus.QUEUED,
                totalBytes = option.size ?: 0,
                createdAt = System.currentTimeMillis(),
            ),
        )
        enqueue(id)
        return id
    }

    suspend fun retry(id: Long) {
        dao.resetForRetry(id)
        enqueue(id)
    }

    suspend fun cancel(id: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        dao.markFailed(id, "Cancelled")
    }

    /** Removes a download from the list; with [deleteFile] also deletes the saved video. */
    suspend fun remove(item: DownloadEntity, deleteFile: Boolean) {
        if (item.isActive) WorkManager.getInstance(context).cancelUniqueWork(workName(item.id))
        if (deleteFile) {
            item.contentUri?.let { uri ->
                try {
                    context.contentResolver.delete(uri.toUri(), null, null)
                } catch (e: Exception) {
                    // Already gone, or saved by an earlier install of the app: nothing more to do
                }
            }
        }
        dao.delete(item.id)
    }

    private fun enqueue(id: Long) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_ID to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(DownloadWorker.TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(id), ExistingWorkPolicy.REPLACE, request)
    }

    private fun workName(id: Long) = "download-$id"
}
