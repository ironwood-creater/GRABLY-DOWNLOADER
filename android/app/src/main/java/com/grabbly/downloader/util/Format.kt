package com.grabbly.downloader.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

object Format {
    /** 532 KB, 8.1 MB, 14 MB, 1.2 GB */
    fun size(bytes: Long): String {
        if (bytes <= 0) return "0 KB"
        val mb = bytes / 1_048_576.0
        return when {
            mb < 1 -> "${max(1, (bytes / 1024.0).roundToInt())} KB"
            mb >= 1000 -> String.format(Locale.US, "%.1f GB", mb / 1024)
            mb >= 10 -> "${mb.roundToInt()} MB"
            else -> String.format(Locale.US, "%.1f MB", mb)
        }
    }

    /** 0:38, 12:05, 1:02:05 */
    fun duration(seconds: Int?): String? {
        if (seconds == null || seconds < 0) return null
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** "Today", "Yesterday", "Nov 3", or "Dec 31, 2025" for other years */
    fun dayLabel(
        epochMillis: Long,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        return when (date) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "MMM d" else "MMM d, yyyy", locale))
        }
    }
}
