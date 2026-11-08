package com.grably.downloader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same cases as the server's tests (server/tests/test_downloader.py), so the app and server agree. */
class LinkDetectorTest {

    private fun supported(text: String): LinkCheck.Supported {
        val result = LinkDetector.check(text)
        assertTrue("expected a supported link for '$text' but got $result", result is LinkCheck.Supported)
        return result as LinkCheck.Supported
    }

    @Test
    fun findsSupportedLinks() {
        val cases = listOf(
            Triple("https://www.tiktok.com/@ava/video/7311", Platform.TikTok, "https://www.tiktok.com/@ava/video/7311"),
            Triple("tiktok.com/@ava/video/7311", Platform.TikTok, "https://tiktok.com/@ava/video/7311"),
            Triple("https://vm.tiktok.com/ZMabc/", Platform.TikTok, "https://vm.tiktok.com/ZMabc/"),
            Triple(
                "Look! https://www.instagram.com/reel/Cxyz/?igsh=1 so good",
                Platform.Instagram,
                "https://www.instagram.com/reel/Cxyz/?igsh=1",
            ),
            Triple("https://m.facebook.com/watch/?v=123", Platform.Facebook, "https://m.facebook.com/watch/?v=123"),
            Triple("https://fb.watch/abc/", Platform.Facebook, "https://fb.watch/abc/"),
            Triple("https://x.com/a/status/1", Platform.X, "https://x.com/a/status/1"),
            Triple("http://twitter.com/a/status/1", Platform.X, "http://twitter.com/a/status/1"),
            Triple("https://t.co/abc", Platform.X, "https://t.co/abc"),
            Triple("https://www.pinterest.co.uk/pin/123/", Platform.Pinterest, "https://www.pinterest.co.uk/pin/123/"),
            Triple("https://www.pinterest.com/pin/123/", Platform.Pinterest, "https://www.pinterest.com/pin/123/"),
            Triple("https://pin.it/3abc", Platform.Pinterest, "https://pin.it/3abc"),
        )
        for ((text, platform, url) in cases) {
            val result = supported(text)
            assertEquals(text, platform, result.platform)
            assertEquals(text, url, result.url)
        }
    }

    @Test
    fun findsTheNewerSites() {
        val cases = mapOf(
            "https://www.reddit.com/r/videos/comments/abc123/funny_cat/" to Platform.Reddit,
            "https://redd.it/abc123" to Platform.Reddit,
            "https://vimeo.com/76979871" to Platform.Vimeo,
            "https://dai.ly/x8abcd" to Platform.Dailymotion,
            "https://www.snapchat.com/spotlight/W7_EDlXWTBiXAEEniNoMPwAA" to Platform.Snapchat,
            "https://clips.twitch.tv/FunnyClipName" to Platform.Twitch,
            "https://www.linkedin.com/posts/someone_video-activity-7012345678901234567-AbCd" to Platform.LinkedIn,
            "https://staff.tumblr.com/post/123456789" to Platform.Tumblr,
            "https://on.soundcloud.com/AbCd" to Platform.SoundCloud,
            "https://b23.tv/AbCdEf" to Platform.Bilibili,
            "https://www.bilibili.tv/en/video/2010000000" to Platform.Bilibili,
        )
        for ((text, platform) in cases) assertEquals(text, platform, supported(text).platform)
    }

    @Test
    fun youTubeIsStillNotSupported() {
        assertEquals(LinkCheck.Unsupported("youtube.com"), LinkDetector.check("https://youtube.com/watch?v=1"))
        assertTrue(Platform.allNames.endsWith("SoundCloud and Bilibili"))
        assertEquals(5, Platform.featured.size)
    }

    @Test
    fun trimsPunctuationAfterTheLink() {
        assertEquals("https://x.com/a/status/1", supported("Watch this (https://x.com/a/status/1).").url)
    }

    @Test
    fun rejectsOtherSites() {
        val cases = mapOf(
            "https://youtube.com/watch?v=1" to "youtube.com",
            "https://example.com/tiktok.com/video" to "example.com",
            "https://tiktok.com.evil.com/@a/video/1" to "tiktok.com.evil.com",
            "https://eviltiktok.com/@a/video/1" to "eviltiktok.com",
            "https://127.0.0.1/video" to "127.0.0.1",
            "https://pinterest.evil.com/pin/1" to "pinterest.evil.com",
        )
        for ((text, host) in cases) {
            assertEquals(text, LinkCheck.Unsupported(host), LinkDetector.check(text))
        }
    }

    @Test
    fun rejectsInvalidText() {
        assertEquals(LinkCheck.Empty, LinkDetector.check(""))
        assertEquals(LinkCheck.Empty, LinkDetector.check("   "))
        for (text in listOf(
            "hello there",
            "https://user:pw@tiktok.com/@a/video/1",
            "https://tiktok.com:8080/@a/video/1",
            "https://tiktok.com/" + "x".repeat(3000),
            "x".repeat(3000),
        )) {
            assertEquals(text.take(60), LinkCheck.Invalid, LinkDetector.check(text))
        }
    }
}
