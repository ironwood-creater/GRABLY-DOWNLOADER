package com.grably.downloader.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Response of POST /api/info on the Grably server. */
@Serializable
data class VideoInfo(
    val platform: String,
    @SerialName("platform_name") val platformName: String,
    /** The resolved link to download (short links already followed by the server) */
    val url: String,
    val title: String,
    val uploader: String? = null,
    val duration: Int? = null,
    /** Absolute thumbnail URL (made absolute by [GrablyApi]) */
    val thumbnail: String? = null,
    val options: List<DownloadOption> = emptyList(),
)

/** One quality choice, for example "MP4 · 1080p" or "MP3 · Audio only". */
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
    val label: String get() = "$format · $quality"
}

@Serializable
internal data class InfoRequest(val url: String)

@Serializable
internal data class ErrorBody(val error: String? = null)
