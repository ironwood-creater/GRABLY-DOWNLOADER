package com.grably.downloader.data

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class GrablyApiTest {

    /** Answers requests in-process instead of over the network, and remembers what was asked. */
    private class FakeServer(private val respond: (Request) -> Pair<Int, String>) : Interceptor {
        val requests = mutableListOf<Request>()
        val bodies = mutableListOf<String>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requests += request
            bodies += request.body?.let { body -> Buffer().also { body.writeTo(it) }.readUtf8() }.orEmpty()
            val (code, text) = respond(request)
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("Test")
                .body(text.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private fun api(server: Interceptor, base: String = "https://grably.example.com/") =
        GrablyApi(base, OkHttpClient.Builder().addInterceptor(server).build())

    private fun expectError(expected: String, block: suspend () -> Unit) {
        try {
            runBlocking { block() }
            fail("expected an error")
        } catch (e: ApiException) {
            assertEquals(expected, e.message)
        }
    }

    private val sample = """
        {
          "platform": "tiktok",
          "platform_name": "TikTok",
          "url": "https://www.tiktok.com/@ava/video/7311",
          "title": "Golden hour drive",
          "uploader": "ava",
          "duration": 38,
          "thumbnail": "/api/thumb?u=abc&s=def",
          "something_new": true,
          "options": [
            {"id": "v1080", "kind": "video", "format": "MP4", "quality": "1080p", "size": 14680064},
            {"id": "audio", "kind": "audio", "format": "MP3", "quality": "Audio only", "size": null}
          ]
        }
    """.trimIndent()

    @Test
    fun readsVideoInfo() {
        val server = FakeServer { 200 to sample }
        val video = runBlocking { api(server).info("https://vm.tiktok.com/abc") }

        assertEquals("Golden hour drive", video.title)
        assertEquals("TikTok", video.platformName)
        assertEquals(38, video.duration)
        assertEquals("https://grably.example.com/api/thumb?u=abc&s=def", video.thumbnail)
        assertEquals(listOf("v1080", "audio"), video.options.map { it.id })
        assertEquals("MP4 · 1080p", video.options[0].label)
        assertFalse(video.options[0].isAudio)
        assertTrue(video.options[1].isAudio)
        assertNull(video.options[1].size)

        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://grably.example.com/api/info", request.url.toString())
        assertEquals("""{"url":"https://vm.tiktok.com/abc"}""", server.bodies.single())
    }

    @Test
    fun showsTheServersErrorMessage() {
        val server = FakeServer { 422 to """{"error": "This video is private."}""" }
        expectError("This video is private.") { api(server).info("https://x.com/a/status/1") }
    }

    @Test
    fun hasFriendlyDefaultsWhenTheServerSaysNothing() {
        expectError("Grabbly is starting up or busy. Please try again in a moment.") {
            api(FakeServer { 503 to "<html>Service Unavailable</html>" }).info("https://x.com/a/status/1")
        }
        expectError("You're going a bit fast. Wait a minute and try again.") {
            api(FakeServer { 429 to "" }).info("https://x.com/a/status/1")
        }
        expectError(GrablyApi.GENERIC_ERROR) {
            api(FakeServer { 200 to "not json" }).info("https://x.com/a/status/1")
        }
    }

    @Test
    fun reportsConnectionProblems() {
        val offline = Interceptor { throw IOException("no network") }
        expectError(GrablyApi.NETWORK_ERROR) { api(offline).info("https://x.com/a/status/1") }
    }

    @Test
    fun needsAServerAddress() {
        val server = FakeServer { 200 to sample }
        val unset = api(server, base = "")
        assertFalse(unset.isConfigured)
        expectError(GrablyApi.NOT_CONFIGURED) { unset.info("https://x.com/a/status/1") }
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun buildsDownloadAddresses() {
        val url = api(FakeServer { 200 to "" }).downloadUrl("https://x.com/a/status/1?s=20&t=a b", "v720")
        assertEquals("/api/download", url.encodedPath)
        assertEquals("https://x.com/a/status/1?s=20&t=a b", url.queryParameter("url"))
        assertEquals("v720", url.queryParameter("option"))
    }
}
