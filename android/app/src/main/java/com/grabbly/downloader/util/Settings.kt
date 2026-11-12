package com.grabbly.downloader.util

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small app preferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("grabbly", Context.MODE_PRIVATE)

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

    private val _quality = MutableStateFlow(QualityPreference.fromKey(prefs.getString("quality", null)))

    /** Video quality used without asking (Settings → Video quality) */
    val quality: StateFlow<QualityPreference> = _quality.asStateFlow()

    fun setQuality(value: QualityPreference) {
        prefs.edit { putString("quality", value.key) }
        _quality.value = value
    }

    private val _autoDownload = MutableStateFlow(prefs.getBoolean("auto_download", false))

    /** Start downloading a copied or shared link straight away, without tapping anything */
    val autoDownload: StateFlow<Boolean> = _autoDownload.asStateFlow()

    fun setAutoDownload(value: Boolean) {
        prefs.edit { putBoolean("auto_download", value) }
        _autoDownload.value = value
    }
}
