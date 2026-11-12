package com.grabbly.downloader.download

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.grabbly.downloader.MainActivity
import com.grabbly.downloader.R
import com.grabbly.downloader.util.Format

object Notifications {
    private const val CHANNEL_ACTIVE = "downloads_active"
    private const val CHANNEL_DONE = "downloads_done"

    fun createChannels(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_ACTIVE, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName("Downloads in progress")
                    .setDescription("Shows progress while a video downloads")
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_DONE, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName("Finished downloads")
                    .setDescription("Tells you when a video is saved or a download fails")
                    .build(),
            ),
        )
    }

    fun progressId(downloadId: Long): Int = 10_000 + (downloadId % 100_000).toInt()
    private fun resultId(downloadId: Long): Int = 200_000 + (downloadId % 100_000).toInt()

    fun progress(context: Context, title: String, done: Long, total: Long): Notification {
        val percent = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
        val text = when {
            total > 0 -> "${Format.size(done)} of ${Format.size(total)}"
            done > 0 -> Format.size(done)
            else -> "Preparing your video…"
        }
        return NotificationCompat.Builder(context, CHANNEL_ACTIVE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, percent, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openDownloads(context))
            .build()
    }

    fun showSaved(context: Context, downloadId: Long, title: String, uri: Uri, mimeType: String) {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val tap = PendingIntent.getActivity(
            context,
            resultId(downloadId),
            view,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Saved to your phone")
            .setContentText(title)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        notify(context, resultId(downloadId), notification)
    }

    fun showFailed(context: Context, downloadId: Long, title: String, message: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Download failed: $title")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openDownloads(context))
            .setAutoCancel(true)
            .build()
        notify(context, resultId(downloadId), notification)
    }

    private fun openDownloads(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    @SuppressLint("MissingPermission") // checked just below
    private fun notify(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
