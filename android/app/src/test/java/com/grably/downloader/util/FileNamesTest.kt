package com.grably.downloader.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileNamesTest {

    @Test
    fun readsTheUtf8FileName() {
        val header = "attachment; filename=\"Cafe vibes.mp4\"; filename*=utf-8''Caf%C3%A9%20vibes.mp4"
        assertEquals("Café vibes.mp4", ContentDisposition.filename(header))
    }

    @Test
    fun keepsPlusSigns() {
        assertEquals("A+B.mp4", ContentDisposition.filename("attachment; filename*=UTF-8''A+B.mp4"))
    }

    @Test
    fun fallsBackToThePlainFileName() {
        assertEquals("clip.mp4", ContentDisposition.filename("attachment; filename=\"clip.mp4\""))
        assertEquals("clip.mp3", ContentDisposition.filename("attachment; filename=clip.mp3"))
        assertNull(ContentDisposition.filename(null))
        assertNull(ContentDisposition.filename("attachment"))
    }

    @Test
    fun sanitizesNames() {
        assertEquals("Golden Hour Drive.mp4", FileNames.sanitize("Golden: \"Hour\" / Drive?.mp4"))
        assertEquals("video.mp4", FileNames.sanitize("///.mp4"))
        assertEquals("notes", FileNames.sanitize("notes"))
        assertEquals(100 + ".mp4".length, FileNames.sanitize("a".repeat(300) + ".mp4").length)
    }

    @Test
    fun namesFilesAfterTheTitle() {
        assertEquals("Sunset vs. sunrise.mp4", FileNames.fromTitle("Sunset vs. sunrise", "video/mp4"))
        assertEquals("Grabbly audio.mp3", FileNames.fromTitle("???", "audio/mpeg"))
        assertEquals("Grabbly video.webm", FileNames.fromTitle("", "video/webm; codecs=vp9"))
        assertEquals("bin", FileNames.extensionFor("application/octet-stream"))
    }
}
