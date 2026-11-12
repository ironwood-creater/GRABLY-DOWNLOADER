package com.grabbly.downloader.util

import android.content.ActivityNotFoundException
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.net.toUri
import com.grabbly.downloader.data.Platform
import com.grabbly.downloader.data.db.DownloadEntity
import java.io.FileNotFoundException

/** Things that hand off to other apps or the system. */
object SystemActions {

    /** Opens TikTok/Instagram/… so the user can copy a link, or its website if the app isn't installed. */
    fun openPlatform(context: Context, platform: Platform) {
        val pm = context.packageManager
        val intent = platform.appPackages.firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
            ?: Intent(Intent.ACTION_VIEW, platform.website.toUri())
        start(context, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "Couldn't open ${platform.displayName}.")
    }

    fun play(context: Context, item: DownloadEntity): Boolean {
        val uri = savedFile(context, item) ?: return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, item.mimeType ?: if (item.isAudio) "audio/*" else "video/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(context, intent, "No app on this phone can play this file.")
    }

    fun share(context: Context, item: DownloadEntity): Boolean {
        val uri = savedFile(context, item) ?: return false
        val send = Intent(Intent.ACTION_SEND)
            .setType(item.mimeType ?: if (item.isAudio) "audio/*" else "video/*")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return start(context, Intent.createChooser(send, item.title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), null)
    }

    /** The saved file, or null (with a message) if it was deleted from the phone, e.g. in the Gallery app. */
    private fun savedFile(context: Context, item: DownloadEntity): Uri? {
        val uri = item.contentUri?.toUri() ?: return null
        val exists = try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (e: FileNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
        if (!exists) toast(context, "This file is no longer on your phone. Download it again to watch it.")
        return uri.takeIf { exists }
    }

    /** Text on the clipboard (used by the Paste button). */
    fun readClipboard(context: Context): String? {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()?.trim()?.ifEmpty { null }
    }

    /**
     * Text on the clipboard if it changed since [lastStamp], with its new timestamp.
     * Only reads the clipboard when something new was copied, so Android's
     * "Grabbly pasted from your clipboard" notice appears just once per copied link.
     */
    fun newClipboardText(context: Context, lastStamp: Long): Pair<String, Long>? {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
        val description = clipboard.primaryClipDescription ?: return null
        val isText = description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
            description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
        if (!isText) return null
        val stamp = description.timestamp
        if (stamp == lastStamp) return null
        val text = readClipboard(context) ?: return null
        return text to stamp
    }

    private fun start(context: Context, intent: Intent, notFoundMessage: String? = "Couldn't open that."): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        notFoundMessage?.let { toast(context, it) }
        false
    }

    private fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}
