package com.grably.downloader.util

import java.net.URLDecoder

/** Reads the file name from a Content-Disposition header, as sent by the Grabbly server. */
object ContentDisposition {
    private val extended = Regex("""filename\*\s*=\s*utf-8''([^;]+)""", RegexOption.IGNORE_CASE)
    private val plain = Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE)

    fun filename(header: String?): String? {
        if (header.isNullOrBlank()) return null
        extended.find(header)?.let { match ->
            // Keep a literal "+" as "+": URLDecoder would otherwise turn it into a space
            val encoded = match.groupValues[1].trim().replace("+", "%2B")
            try {
                return URLDecoder.decode(encoded, "UTF-8")
            } catch (e: IllegalArgumentException) {
                // fall through to the plain filename
            }
        }
        return plain.find(header)?.groupValues?.get(1)?.trim()?.ifEmpty { null }
    }
}

object FileNames {
    private val illegal = Regex("""[\\/:*?"<>|\u0000-\u001F]+""")
    private val spaces = Regex("""\s+""")

    /** Makes a file name safe to save: no path separators or reserved characters, sensible length. */
    fun sanitize(name: String, fallback: String = "video"): String {
        val dot = name.lastIndexOf('.')
        val hasExtension = dot > 0 && name.length - dot in 2..6
        val base = if (hasExtension) name.substring(0, dot) else name
        val ext = if (hasExtension) name.substring(dot + 1).lowercase().filter { it.isLetterOrDigit() } else ""
        val safeBase = clean(base, fallback)
        return if (ext.isNotEmpty()) "$safeBase.$ext" else safeBase
    }

    /** A file name from the video title when the server didn't send one. */
    fun fromTitle(title: String, mimeType: String): String {
        val fallback = if (mimeType.startsWith("audio/")) "Grabbly audio" else "Grabbly video"
        return clean(title, fallback) + "." + extensionFor(mimeType)
    }

    private fun clean(text: String, fallback: String): String =
        text.replace(illegal, " ").replace(spaces, " ").trim(' ', '.', '-', '_').take(100).trimEnd().ifEmpty { fallback }

    fun extensionFor(mimeType: String): String = when (mimeType.substringBefore(';').trim().lowercase()) {
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        "video/quicktime" -> "mov"
        "video/x-matroska" -> "mkv"
        "audio/mpeg" -> "mp3"
        "audio/mp4" -> "m4a"
        "audio/ogg" -> "opus"
        else -> "bin"
    }
}
