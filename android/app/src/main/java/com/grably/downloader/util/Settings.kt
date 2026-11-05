package com.grably.downloader.util

import android.content.Context
import androidx.core.content.edit

/** Small app preferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("grably", Context.MODE_PRIVATE)

    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboarding_done", false)
        set(value) = prefs.edit { putBoolean("onboarding_done", value) }

    /** When the clipboard was last checked for a link, so the same copied link isn't picked up twice */
    var lastClipboardStamp: Long
        get() = prefs.getLong("last_clipboard_stamp", 0L)
        set(value) = prefs.edit { putLong("last_clipboard_stamp", value) }

    var askedForNotifications: Boolean
        get() = prefs.getBoolean("asked_notifications", false)
        set(value) = prefs.edit { putBoolean("asked_notifications", value) }
}
