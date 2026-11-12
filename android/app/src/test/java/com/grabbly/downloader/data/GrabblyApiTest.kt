package com.grabbly.downloader.data

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

class GrabblyApiTest {

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

    private fun api(server: Interceptor, base: String = "https://grabbly.example.com/") =
        GrabblyApi(base, OkHttpClient.Builder().addInterceptor(server).build())

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
        assertEquals("https://grabbly.example.com/api/thumb?u=abc&s=def", video.thumbnail)
        assertEquals(listOf("v1080", "audio"), video.options.map { it.id })
        assertEquals("MP4 · 1080p", video.options[0].label)
        assertFalse(video.options[0].isAudio)
        assertTrue(video.options[1].isAudio)
        assertNull(video.options[1].size)

        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://grabbly.example.com/api/info", request.url.toString())
        assertEquals("""{"url":"https://vm.tiktok.com/abc","items":true}""", server.bodies.single())
        // An answer without "items" (an older server) still gives one item to download
        assertEquals(1, video.allItems.size)
        assertEquals(listOf("v1080", "audio"), video.allItems[0].options.map { it.id })
        assertFalse(video.hasSeveral)
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
        expectError(GrabblyApi.GENERIC_ERROR) {
            api(FakeServer { 200 to "not json" }).info("https://x.com/a/status/1")
        }
    }

    @Test
    fun reportsConnectionProblems() {
        val offline = Interceptor { throw IOException("no network") }
        expectError(GrabblyApi.NETWORK_ERROR) { api(offline).info("https://x.com/a/status/1") }
    }

    @Test
    fun needsAServerAddress() {
        val server = FakeServer { 200 to sample }
        val unset = api(server, base = "")
        assertFalse(unset.isConfigured)
        expectError(GrabblyApi.NOT_CONFIGURED) { unset.info("https://x.com/a/status/1") }
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun readsEveryItemOfAPost() {
        val carousel = """
            {
              "platform": "instagram", "platform_name": "Instagram", "url": "https://www.instagram.com/p/Cx/",
              "title": "Post by ava", "thumbnail": "/api/thumb?u=1", "caption": "Sunset #roadtrip",
              "options": [{"id": "v720", "kind": "video", "format": "MP4", "quality": "720p"}],
              "items": [
                {"index": 1, "kind": "video", "thumbnail": "/api/thumb?u=1", "duration": 12,
                 "options": [{"id": "v720", "kind": "video", "format": "MP4", "quality": "720p"}]},
                {"index": 2, "kind": "photo", "thumbnail": "/api/thumb?u=2", "width": 1080, "height": 1350,
                 "options": [{"id": "photo", "kind": "photo", "format": "JPG", "quality": "1080 × 1350"}]}
              ]
            }
        """.trimIndent()
        val video = runBlocking { api(FakeServer { 200 to carousel }).info("https://www.instagram.com/p/Cx/") }
        assertTrue(video.hasSeveral)
        assertEquals("Sunset #roadtrip", video.caption)
        assertEquals(listOf("video", "photo"), video.items.map { it.kind })
        assertTrue(video.items[1].isPhoto)
        assertTrue(video.items[1].options.single().isPhoto)
        assertEquals("JPG · 1080 × 1350", video.items[1].options.single().label)
        assertEquals("https://grabbly.example.com/api/thumb?u=2", video.items[1].thumbnail)
    }

    @Test
    fun busyAnswersCanBeTriedAgain() {
        try {
            runBlocking { api(FakeServer { 429 to "" }).info("https://x.com/a/status/1") }
            fail("expected an error")
        } catch (e: ApiException) {
            assertTrue(e.isBusy)
        }
        try {
            runBlocking { api(FakeServer { 422 to """{"error": "Private"}""" }).info("https://x.com/a/status/1") }
            fail("expected an error")
        } catch (e: ApiException) {
            assertFalse(e.isBusy)
        }
    }

    @Test
    fun buildsDownloadAddresses() {
        val url = api(FakeServer { 200 to "" }).downloadUrl("https://x.com/a/status/1?s=20&t=a b", "v720")
        assertEquals("/api/download", url.encodedPath)
        assertEquals("https://x.com/a/status/1?s=20&t=a b", url.queryParameter("url"))
        assertEquals("v720", url.queryParameter("option"))
        assertNull(url.queryParameter("item"))
        val photo = api(FakeServer { 200 to "" }).downloadUrl("https://www.instagram.com/p/Cx/", "photo", item = 2)
        assertEquals("2", photo.queryParameter("item"))
    }
}
