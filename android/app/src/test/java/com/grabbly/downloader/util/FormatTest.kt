package com.grabbly.downloader.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

class FormatTest {

    @Test
    fun formatsSizes() {
        assertEquals("0 KB", Format.size(0))
        assertEquals("1 KB", Format.size(200))
        assertEquals("532 KB", Format.size(532 * 1024))
        assertEquals("8.1 MB", Format.size((8.1 * 1_048_576).toLong()))
        assertEquals("14 MB", Format.size(14L * 1_048_576))
        assertEquals("1.2 GB", Format.size((1.2 * 1_073_741_824).toLong()))
    }

    @Test
    fun formatsDurations() {
        assertEquals("0:38", Format.duration(38))
        assertEquals("12:05", Format.duration(725))
        assertEquals("1:02:05", Format.duration(3725))
        assertNull(Format.duration(null))
        assertNull(Format.duration(-1))
    }

    @Test
    fun labelsDays() {
        val today = LocalDate.of(2026, 11, 5)
        fun at(date: LocalDate) = date.atTime(15, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
        fun label(date: LocalDate) = Format.dayLabel(at(date), today, ZoneOffset.UTC, Locale.US)

        assertEquals("Today", label(today))
        assertEquals("Yesterday", label(today.minusDays(1)))
        assertEquals("Nov 3", label(today.minusDays(2)))
        assertEquals("Dec 31, 2025", label(LocalDate.of(2025, 12, 31)))
    }
}
