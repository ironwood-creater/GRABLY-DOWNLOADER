package com.grabbly.downloader.ui

import com.grabbly.downloader.data.MediaItem
import com.grabbly.downloader.ui.home.describeItems
import org.junit.Assert.assertEquals
import org.junit.Test

class PostSheetTest {
    private fun item(kind: String) = MediaItem(index = 1, kind = kind)

    @Test
    fun describesWhatsInAPost() {
        assertEquals("3 photos and 2 videos", describeItems(List(3) { item("photo") } + List(2) { item("video") }))
        assertEquals("1 photo", describeItems(listOf(item("photo"))))
        assertEquals("2 videos", describeItems(listOf(item("video"), item("video"))))
    }
}
