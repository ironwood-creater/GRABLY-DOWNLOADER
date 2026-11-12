package com.grabbly.downloader.data

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.work.BackoffPolicy
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
import com.grabbly.downloader.download.MediaStoreFiles
import com.grabbly.downloader.util.FileNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Starts, retries, cancels, moves and removes downloads. Downloads run in [DownloadWorker]. */
class DownloadRepository(private val context: Context, private val dao: DownloadDao) {

    val all: Flow<List<DownloadEntity>> = dao.observeAll()

    fun observe(id: Long): Flow<DownloadEntity?> = dao.observe(id)

    /** Downloads [option] of [video]; [item] is the video or photo of a post with several. */
    suspend fun start(video: VideoInfo, option: DownloadOption, item: MediaItem? = null): Long {
        val several = video.hasSeveral && item != null
        val position = if (several) video.items.indexOf(item) + 1 else 0
        val id = dao.insert(
            DownloadEntity(
                sourceUrl = video.url,
                platform = video.platform,
                title = if (several) "${video.title} · $position of ${video.items.size}" else video.title,
                uploader = video.uploader,
                thumbnailUrl = item?.thumbnail ?: video.thumbnail,
                durationSeconds = item?.duration ?: video.duration,
                optionId = option.id,
                optionLabel = option.label,
                isAudio = option.isAudio,
                status = DownloadStatus.QUEUED,
                totalBytes = option.size ?: 0,
                createdAt = System.currentTimeMillis(),
                // Only needed to pick one item out of several (and photos, which the server finds by number)
                itemIndex = item?.index?.takeIf { several || option.isPhoto },
                caption = video.caption,
                isPhoto = option.isPhoto,
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

    /** Removes a download from the list; with [deleteFile] also deletes the saved file. */
    suspend fun remove(item: DownloadEntity, deleteFile: Boolean) {
        if (item.isActive) WorkManager.getInstance(context).cancelUniqueWork(workName(item.id))
        if (deleteFile || item.isPrivate) { // a private file has no other home: it goes with the list entry
            item.contentUri?.toUri()?.let { uri ->
                withContext(Dispatchers.IO) {
                    try {
                        if (uri.scheme == "file") File(uri.path.orEmpty()).delete() else context.contentResolver.delete(uri, null, null)
                    } catch (e: Exception) {
                        // Already gone, or saved by an earlier install of the app: nothing more to do
                    }
                }
            }
        }
        dao.delete(item.id)
    }

    /**
     * Moves a saved file out of the gallery into Grabbly's private folder, where only Grabbly can
     * open it. Returns false if it couldn't (for example, the file was deleted in the Gallery app).
     */
    suspend fun moveToPrivate(item: DownloadEntity): Boolean = withContext(Dispatchers.IO) {
        val source = item.contentUri?.toUri() ?: return@withContext false
        if (item.isPrivate) return@withContext true
        val folder = File(context.filesDir, PRIVATE_FOLDER).apply { mkdirs() }
        val target = File(folder, "${item.id}.${FileNames.extensionFor(item.mimeOrDefault)}")
        try {
            val input = context.contentResolver.openInputStream(source) ?: return@withContext false
            input.use { from -> target.outputStream().use { from.copyTo(it) } }
            context.contentResolver.delete(source, null, null)
        } catch (e: Exception) {
            target.delete()
            return@withContext false
        }
        dao.setLocation(item.id, Uri.fromFile(target).toString(), isPrivate = true)
        true
    }

    /** Puts a private file back in the phone's gallery. */
    suspend fun moveToGallery(item: DownloadEntity): Boolean = withContext(Dispatchers.IO) {
        val file = item.contentUri?.toUri()?.takeIf { it.scheme == "file" }?.path?.let(::File)
            ?.takeIf { it.isFile } ?: return@withContext false
        val mime = item.mimeOrDefault
        var created: Uri? = null
        try {
            val uri = MediaStoreFiles.create(context, item.fileName ?: FileNames.fromTitle(item.title, mime), mime)
            created = uri
            val output = context.contentResolver.openOutputStream(uri) ?: throw IOException("Couldn't open the file")
            output.use { to -> file.inputStream().use { it.copyTo(to) } }
            MediaStoreFiles.publish(context, uri)
            dao.setLocation(item.id, uri.toString(), isPrivate = false)
        } catch (e: Exception) {
            created?.let { MediaStoreFiles.discard(context, it) }
            return@withContext false
        }
        file.delete()
        true
    }

    private fun enqueue(id: Long) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_ID to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            // When the server is busy (several downloads at once), try again a little later
            .setBackoffCriteria(BackoffPolicy.LINEAR, 20, TimeUnit.SECONDS)
            .addTag(DownloadWorker.TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(id), ExistingWorkPolicy.REPLACE, request)
    }

    private fun workName(id: Long) = "download-$id"

    companion object {
        /** Inside the app's own storage: not in the gallery, not backed up, removed with the app */
        const val PRIVATE_FOLDER = "private"
    }
}
