package com.grabbly.downloader.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Response of POST /api/info on the Grabbly server. */
@Serializable
data class VideoInfo(
    val platform: String,
    @SerialName("platform_name") val platformName: String,
    /** The resolved link to download (short links already followed by the server) */
    val url: String,
    val title: String,
    val uploader: String? = null,
    val duration: Int? = null,
    /** Absolute thumbnail URL (made absolute by [GrabblyApi]) */
    val thumbnail: String? = null,
    /** Choices for the first video or photo of the post */
    val options: List<DownloadOption> = emptyList(),
    /** The post's text, for "Copy caption" */
    val caption: String? = null,
    /** Every video and photo in the post: one for most posts, more for albums and carousels */
    val items: List<MediaItem> = emptyList(),
) {
    /** The items to choose from; one made from the fields above if the server sent no list. */
    val allItems: List<MediaItem>
        get() = items.ifEmpty {
            listOf(
                MediaItem(
                    index = 1,
                    kind = if (options.isNotEmpty() && options.all { it.isAudio }) "audio" else "video",
                    thumbnail = thumbnail,
                    duration = duration,
                    options = options,
                ),
            )
        }

    /** An album or carousel with more than one video or photo */
    val hasSeveral: Boolean get() = items.size > 1
}

/** One video or photo in a post. */
@Serializable
data class MediaItem(
    /** The server's number for it, sent back when downloading it */
    val index: Int,
    /** "video", "photo" or "audio" */
    val kind: String,
    val thumbnail: String? = null,
    val duration: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val options: List<DownloadOption> = emptyList(),
) {
    val isPhoto: Boolean get() = kind == "photo"
}

/** One quality choice, for example "MP4 · 1080p", "MP3 · Audio only" or "JPG · 1080 × 1350". */
@Serializable
data class DownloadOption(
    val id: String,
    val kind: String,
    val format: String,
    val quality: String,
    /** Estimated size in bytes, when the server knows it */
    val size: Long? = null,
) {
    val isAudio: Boolean get() = kind == "audio"
    val isPhoto: Boolean get() = kind == "photo"
    val label: String get() = "$format · $quality"
}

/** [items] asks the server for every video and photo in the post, and its caption. */
@Serializable
internal data class InfoRequest(val url: String, val items: Boolean)

@Serializable
internal data class ErrorBody(val error: String? = null)
