package com.grabbly.downloader.ui

import com.grabbly.downloader.data.db.DownloadEntity
import com.grabbly.downloader.data.db.DownloadStatus
import com.grabbly.downloader.ui.downloads.DownloadFilter
import com.grabbly.downloader.ui.downloads.groupByDay
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

class DownloadGroupingTest {
    private val today = LocalDate.of(2026, 11, 5)

    private fun item(id: Long, daysAgo: Long, status: DownloadStatus = DownloadStatus.COMPLETE) = DownloadEntity(
        id = id,
        sourceUrl = "https://x.com/a/status/$id",
        platform = "x",
        title = "Clip $id",
        uploader = null,
        thumbnailUrl = null,
        durationSeconds = null,
        optionId = "best",
        optionLabel = "MP4 · Best",
        isAudio = false,
        status = status,
        createdAt = today.minusDays(daysAgo).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
    )

    @Test
    fun groupsByDayInOrder() {
        val groups = groupByDay(listOf(item(4, 0), item(3, 0), item(2, 1), item(1, 3)), today, ZoneOffset.UTC, Locale.US)
        assertEquals(listOf("Today", "Yesterday", "Nov 2"), groups.map { it.label })
        assertEquals(listOf(4L, 3L), groups[0].items.map { it.id })
    }

    @Test
    fun filtersByStatus() {
        val all = listOf(
            item(1, 0, DownloadStatus.COMPLETE),
            item(2, 0, DownloadStatus.FAILED),
            item(3, 0, DownloadStatus.RUNNING),
        )
        assertEquals(listOf(1L, 2L, 3L), all.filter(DownloadFilter.All::matches).map { it.id })
        assertEquals(listOf(1L), all.filter(DownloadFilter.Complete::matches).map { it.id })
        assertEquals(listOf(2L), all.filter(DownloadFilter.Failed::matches).map { it.id })
    }
}
