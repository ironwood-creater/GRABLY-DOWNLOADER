package com.grabbly.downloader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** An error with a message that can be shown to the user as-is. */
class ApiException(message: String) : Exception(message)

/**
 * Talks to the Grabbly server, the same one the website uses:
 *  - POST /api/info      → video title, thumbnail and quality options
 *  - GET  /api/download  → the video file
 */
class GrabblyApi(baseUrl: String, private val client: OkHttpClient) {
    private val base = baseUrl.trim().trimEnd('/')
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    val isConfigured: Boolean
        get() = base.startsWith("https://") || base.startsWith("http://")

    /** Looks up a video. Throws [ApiException] with a friendly message on any failure. */
    suspend fun info(link: String): VideoInfo = withContext(Dispatchers.IO) {
        ensureConfigured()
        val payload = json.encodeToString(InfoRequest.serializer(), InfoRequest(link))
        val request = Request.Builder()
            .url("$base/api/info")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val (code, text) = try {
            client.newCall(request).execute().use { response ->
                response.code to response.body.string()
            }
        } catch (e: IOException) {
            throw ApiException(NETWORK_ERROR)
        }
        if (code !in 200..299) throw ApiException(errorMessage(code, text))
        val info = try {
            json.decodeFromString(VideoInfo.serializer(), text)
        } catch (e: SerializationException) {
            throw ApiException(GENERIC_ERROR)
        } catch (e: IllegalArgumentException) {
            throw ApiException(GENERIC_ERROR)
        }
        info.copy(thumbnail = info.thumbnail?.let(::absoluteUrl))
    }

    /** Address that streams the chosen file. */
    fun downloadUrl(link: String, optionId: String): HttpUrl {
        ensureConfigured()
        return "$base/api/download".toHttpUrl().newBuilder()
            .addQueryParameter("url", link)
            .addQueryParameter("option", optionId)
            .build()
    }

    /** The server returns thumbnail paths like /api/thumb?…; make them absolute. */
    fun absoluteUrl(pathOrUrl: String): String = when {
        pathOrUrl.startsWith("https://") || pathOrUrl.startsWith("http://") -> pathOrUrl
        pathOrUrl.startsWith("/") -> base + pathOrUrl
        else -> "$base/$pathOrUrl"
    }

    /** The server's own message when it sent one (JSON {"error": "…"}), otherwise a sensible default. */
    fun errorMessage(code: Int, body: String): String {
        val fromServer = try {
            json.decodeFromString(ErrorBody.serializer(), body).error
        } catch (e: Exception) {
            null
        }
        if (!fromServer.isNullOrBlank()) return fromServer
        return when (code) {
            429 -> "You're going a bit fast. Wait a minute and try again."
            502, 503, 504 -> "Grabbly is starting up or busy. Please try again in a moment."
            else -> GENERIC_ERROR
        }
    }

    private fun ensureConfigured() {
        if (!isConfigured) throw ApiException(NOT_CONFIGURED)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val NETWORK_ERROR = "Couldn't reach Grabbly. Check your connection and try again."
        const val GENERIC_ERROR = "Something went wrong. Please try again."
        const val NOT_CONFIGURED = "Grabbly's server address isn't set in this version of the app."
    }
}
