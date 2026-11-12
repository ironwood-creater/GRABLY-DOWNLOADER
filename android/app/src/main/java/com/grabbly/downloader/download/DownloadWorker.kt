package com.grabbly.downloader.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.grabbly.downloader.appContainer
import com.grabbly.downloader.data.ApiException
import com.grabbly.downloader.data.GrabblyApi
import com.grabbly.downloader.data.db.DownloadStatus
import com.grabbly.downloader.util.ContentDisposition
import com.grabbly.downloader.util.FileNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Downloads one video from the Grabbly server into the phone's gallery, with a progress
 * notification. Runs in the background and keeps going if the user leaves the app.
 */
class DownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    private val container = appContext.appContainer
    private val dao = container.database.downloadDao()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val id = inputData.getLong(KEY_ID, -1)
        val item = dao.get(id)
        return foregroundInfo(id, item?.title ?: "Downloading", 0, item?.totalBytes ?: 0)
    }

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, -1)
        val item = dao.get(id) ?: return Result.failure()
        dao.setProgress(id, DownloadStatus.RUNNING, 0, item.totalBytes)
        updateForeground(id, item.title, 0, item.totalBytes)

        var pending: Uri? = null
        return try {
            val saved = withContext(Dispatchers.IO) {
                val address = container.api.downloadUrl(item.sourceUrl, item.optionId, item.itemIndex)
                val request = Request.Builder().url(address).build()
                container.httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw ApiException(container.api.errorMessage(response.code, response.body.string()), response.code)
                    }
                    val body = response.body
                    val total = body.contentLength().coerceAtLeast(0)
                    val mimeType = response.header("Content-Type")?.substringBefore(';')?.trim()?.ifEmpty { null }
                        ?: item.mimeOrDefault
                    val fileName = ContentDisposition.filename(response.header("Content-Disposition"))
                        ?.let { FileNames.sanitize(it) }
                        ?: FileNames.fromTitle(item.title, mimeType)

                    val uri = MediaStoreFiles.create(applicationContext, fileName, mimeType)
                    pending = uri
                    var done = 0L
                    var lastUpdate = 0L
                    val output = applicationContext.contentResolver.openOutputStream(uri)
                        ?: throw IOException("Couldn't open the file on your phone")
                    output.use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                if (isStopped) throw CancellationException("Download stopped")
                                val read = input.read(buffer)
                                if (read < 0) break
                                out.write(buffer, 0, read)
                                done += read
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastUpdate >= PROGRESS_INTERVAL_MS) {
                                    lastUpdate = now
                                    dao.setProgress(id, DownloadStatus.RUNNING, done, total)
                                    updateForeground(id, item.title, done, total)
                                }
                            }
                        }
                    }
                    MediaStoreFiles.publish(applicationContext, uri)
                    pending = null
                    Saved(uri, fileName, mimeType, done)
                }
            }
            dao.markComplete(id, saved.uri.toString(), saved.fileName, saved.mimeType, saved.size, System.currentTimeMillis())
            Notifications.showSaved(applicationContext, id, item.title)
            Result.success()
        } catch (e: CancellationException) {
            // Cancelled by the user, or paused by Android (for example when the connection drops)
            pending?.let { MediaStoreFiles.discard(applicationContext, it) }
            withContext(NonCancellable) { dao.requeueIfRunning(id) }
            throw e
        } catch (e: ApiException) {
            pending?.let { MediaStoreFiles.discard(applicationContext, it) }
            if (e.isBusy && runAttemptCount < MAX_BUSY_RETRIES) {
                // Several downloads at once (for example a list of links): wait for a free turn
                withContext(NonCancellable) { dao.requeueIfRunning(id) }
                return Result.retry()
            }
            val message = e.message ?: GrabblyApi.GENERIC_ERROR
            withContext(NonCancellable) { dao.markFailed(id, message) }
            Notifications.showFailed(applicationContext, id, item.title, message)
            Result.failure()
        } catch (e: Exception) {
            pending?.let { MediaStoreFiles.discard(applicationContext, it) }
            val message = when (e) {
                is IOException -> "The download was interrupted. Check your connection and try again."
                else -> GrabblyApi.GENERIC_ERROR
            }
            withContext(NonCancellable) { dao.markFailed(id, message) }
            Notifications.showFailed(applicationContext, id, item.title, message)
            Result.failure()
        }
    }

    private suspend fun updateForeground(id: Long, title: String, done: Long, total: Long) {
        try {
            setForeground(foregroundInfo(id, title, done, total))
        } catch (e: IllegalStateException) {
            // Android may refuse a foreground service when the app is in the background;
            // the download still runs, just without the ongoing notification.
        }
    }

    private fun foregroundInfo(id: Long, title: String, done: Long, total: Long): ForegroundInfo =
        ForegroundInfo(
            Notifications.progressId(id),
            Notifications.progress(applicationContext, title, done, total),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

    private data class Saved(val uri: Uri, val fileName: String, val mimeType: String, val size: Long)

    companion object {
        const val KEY_ID = "download_id"
        const val TAG = "grabbly-download"
        private const val PROGRESS_INTERVAL_MS = 750L
        /** About 12 minutes of waiting in total, with the growing 20-second steps set in DownloadRepository */
        private const val MAX_BUSY_RETRIES = 8
    }
}
