package com.grabbly.downloader.util

import com.grabbly.downloader.data.DownloadOption

/** The quality chosen in Settings. [Ask] shows the choices for every video. */
enum class QualityPreference(val key: String, val label: String, val detail: String) {
    Ask("ask", "Ask every time", "Choose for each video"),
    Best("best", "Best available", "The highest quality the video has"),
    P1080("1080", "1080p", "Full HD"),
    P720("720", "720p", "HD, smaller files"),
    P480("480", "480p", "Smallest videos"),
    Audio("audio", "Sound only (MP3)", "Save just the sound"),
    ;

    companion object {
        fun fromKey(key: String?): QualityPreference = entries.firstOrNull { it.key == key } ?: Ask
    }
}

object QualityPicker {
    private val resolution = Regex("""^v(\d{3,4})$""")

    /**
     * The option to download for [preference], or null when the user should choose ([QualityPreference.Ask]
     * with [canAsk]). Photos are saved as they are; a video picks the closest quality at or below the
     * chosen one, or the smallest if all are bigger.
     */
    fun pick(options: List<DownloadOption>, preference: QualityPreference, canAsk: Boolean = true): DownloadOption? {
        if (options.isEmpty()) return null
        if (preference == QualityPreference.Ask && canAsk) return null
        options.firstOrNull { it.isPhoto }?.let { return it }
        val videos = options.filter { !it.isAudio && !it.isPhoto }
        val best = videos.firstOrNull() ?: options.first() // the server lists the best first
        return when (preference) {
            QualityPreference.Ask, QualityPreference.Best -> best
            QualityPreference.Audio -> options.firstOrNull { it.isAudio } ?: best
            else -> {
                val target = preference.key.toInt()
                val sized = videos.mapNotNull { option ->
                    resolution.find(option.id)?.groupValues?.get(1)?.toIntOrNull()?.let { it to option }
                }
                sized.filter { it.first <= target }.maxByOrNull { it.first }?.second
                    ?: sized.minByOrNull { it.first }?.second
                    ?: best
            }
        }
    }
}

object Captions {
    private val hashtag = Regex("""(?<![\p{L}\p{N}_&/])#[\p{L}\p{N}_]+""")

    /** "#roadtrip #sunset" from a caption, or null when it has none. */
    fun hashtags(caption: String?): String? {
        if (caption.isNullOrBlank()) return null
        return hashtag.findAll(caption).map { it.value }.distinctBy { it.lowercase() }.joinToString(" ").ifEmpty { null }
    }
}
