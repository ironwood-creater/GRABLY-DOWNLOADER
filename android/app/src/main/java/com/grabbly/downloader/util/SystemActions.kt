package com.grabbly.downloader.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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

    /** Opens a web page, such as the privacy policy, in the browser. */
    fun openWebsite(context: Context, url: String) {
        start(context, Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "No browser on this phone can open that.")
    }

    /** Opens the saved file in another app (the phone's own player or gallery). Not for private files. */
    fun openWith(context: Context, item: DownloadEntity): Boolean {
        if (item.isPrivate) return false
        val uri = savedFile(context, item) ?: return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, item.mimeOrDefault)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(context, intent, "No app on this phone can open this file.")
    }

    fun share(context: Context, item: DownloadEntity): Boolean {
        if (item.isPrivate) return false
        val uri = savedFile(context, item) ?: return false
        val send = Intent(Intent.ACTION_SEND)
            .setType(item.mimeOrDefault)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return start(context, Intent.createChooser(send, item.title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), null)
    }

    /** True if the saved file is still there (it may have been deleted, for example in the Gallery app). */
    fun fileExists(context: Context, item: DownloadEntity): Boolean {
        val uri = item.contentUri?.toUri() ?: return false
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (e: FileNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /** The saved file, or null (with a message) if it was deleted from the phone. */
    private fun savedFile(context: Context, item: DownloadEntity): Uri? {
        if (!fileExists(context, item)) {
            toast(context, "This file is no longer on your phone. Download it again to open it.")
            return null
        }
        return item.contentUri?.toUri()
    }

    /**
     * Puts [text] on the clipboard. Returns the clipboard's new timestamp, so Grabbly doesn't pick up
     * its own copy as a "copied link" when the user comes back.
     */
    fun copyText(context: Context, label: String, text: String): Long? {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        // Android 13 and later show their own "Copied" confirmation
        if (Build.VERSION.SDK_INT < 33) toast(context, "$label copied")
        return clipboard.primaryClipDescription?.timestamp
    }

    /** Text on the clipboard (used by the Paste buttons). */
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

    /** Opens the phone's screen lock settings (for the private folder). */
    fun openLockSettings(context: Context) {
        start(context, Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun start(context: Context, intent: Intent, notFoundMessage: String? = "Couldn't open that."): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        notFoundMessage?.let { toast(context, it) }
        false
    }

    fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}
