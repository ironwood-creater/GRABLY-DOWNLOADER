package com.grabbly.downloader

import android.Manifest
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.grabbly.downloader.data.Platform
import com.grabbly.downloader.data.db.DownloadEntity
import com.grabbly.downloader.data.db.DownloadStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Takes the Google Play screenshots from the real app: a clean status bar, sample downloads,
 * light and dark mode. Saves PNGs to /data/local/tmp/grabbly-shots on the device; CI pulls them
 * into android/store/screenshots/raw (see .github/workflows/android-screenshots.yml).
 *
 * Needs the stand-in server (android/ci/fake_server.py) for the sample videos and thumbnails.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext
    private val container get() = app.appContainer

    @Before
    fun prepare() {
        assumeTrue("needs the stand-in server", BuildConfig.API_BASE_URL.contains("10.0.2.2"))
        shell("mkdir -p $DIR")
        shell("cmd uimode night no")
        cleanStatusBar()
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        container.database.clearAllTables()
        runBlocking { sampleDownloads().forEach { container.database.downloadDao().insert(it) } }
        container.settings.onboardingDone = false
    }

    @After
    fun cleanUp() {
        shell("cmd uimode night no")
        shell("am broadcast -a com.android.systemui.demo -e command exit")
        container.database.clearAllTables()
    }

    @Test
    fun storeScreenshots() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // 1. Welcome
            compose.waitUntilExactlyOneExists(hasText("All Videos Download"), 15_000)
            capture("01-welcome")

            // 2. Home with the most recent download
            compose.onNodeWithContentDescription("Get started").performClick()
            compose.waitUntilExactlyOneExists(hasText("Golden hour drive along the coast"), 10_000)
            capture("02-home")

            // 3. A pasted TikTok link is recognised
            compose.onNode(hasSetTextAction()).performTextInput("https://www.tiktok.com/@ava.travels/video/7301234567890")
            scenario.onActivity {
                WindowCompat.getInsetsController(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.ime())
            }
            compose.waitUntilExactlyOneExists(hasText("TikTok link found", substring = true), 5_000)
            capture("03-link-found")

            // 4. Quality picker
            compose.onNodeWithContentDescription("Find video").performClick()
            compose.waitUntilExactlyOneExists(hasText("Choose a quality"), 15_000)
            capture("04-quality")
            shell("input keyevent 4") // back: close the sheet
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Choose a quality")).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithContentDescription("Clear").performClick()

            // 5. Downloads
            compose.onNodeWithContentDescription("Downloads").performClick()
            compose.waitUntilExactlyOneExists(hasText("Street food tour: 10 dishes under \$5"), 10_000)
            capture("05-downloads")

            // Dark mode
            shell("cmd uimode night yes")
            Thread.sleep(2_500) // the screen is rebuilt in dark colours
            compose.waitUntilExactlyOneExists(hasText("Complete"), 15_000)
            compose.onNodeWithText("Complete").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasText("Street food tour: 10 dishes under \$5")).fetchSemanticsNodes().isEmpty()
            }
            capture("08-downloads-dark")

            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitUntilExactlyOneExists(hasText("Download any video"), 10_000)
            capture("06-home-dark")

            compose.onNode(hasSetTextAction()).performTextInput("https://www.instagram.com/reel/Cstreetfood/")
            compose.onNodeWithContentDescription("Find video").performClick()
            compose.waitUntilExactlyOneExists(hasText("Choose a quality"), 15_000)
            capture("07-quality-dark")
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        Thread.sleep(1_500) // let thumbnails load and the keyboard or sheet finish moving
        shell("screencap -p $DIR/$name.png")
    }

    /** Full battery, full Wi-Fi, 9:41 and no notification icons. */
    private fun cleanStatusBar() {
        shell("settings put global sysui_demo_allowed 1")
        val demo = "am broadcast -a com.android.systemui.demo -e command"
        shell("$demo enter")
        shell("$demo clock -e hhmm 0941")
        shell("$demo battery -e level 100 -e plugged false")
        shell("$demo network -e wifi show -e level 4 -e fully true")
        shell("$demo network -e mobile hide")
        shell("$demo notifications -e visible false")
    }

    private fun shell(command: String) {
        val output = instrumentation.uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() } // wait until it's done
    }

    private fun sampleDownloads(): List<DownloadEntity> {
        val now = System.currentTimeMillis()
        val minute = 60_000L
        val day = 24 * 60 * minute
        val mb = 1_048_576L
        fun item(
            platform: Platform,
            title: String,
            thumb: String,
            option: String,
            status: DownloadStatus,
            size: Long,
            ago: Long,
            done: Long = size,
            error: String? = null,
            audio: Boolean = false,
            duration: Int = 45,
        ) = DownloadEntity(
            sourceUrl = "${platform.website}/sample/$thumb",
            platform = platform.key,
            title = title,
            uploader = null,
            thumbnailUrl = "${BuildConfig.API_BASE_URL}/thumbs/$thumb.jpg",
            durationSeconds = duration,
            optionId = if (audio) "audio" else "v720",
            optionLabel = option,
            isAudio = audio,
            status = status,
            bytesDownloaded = done,
            totalBytes = size,
            fileName = if (status == DownloadStatus.COMPLETE) "$title.mp4" else null,
            mimeType = if (audio) "audio/mpeg" else "video/mp4",
            error = error,
            createdAt = now - ago,
            completedAt = if (status == DownloadStatus.COMPLETE) now - ago + minute else null,
        )
        // Oldest first, so the newest gets the highest id
        return listOf(
            item(Platform.TikTok, "Lo-fi beats to study and relax to", "music", "MP3 · Audio only",
                DownloadStatus.COMPLETE, (4.1 * mb).toLong(), 3 * day, audio = true, duration = 180),
            item(Platform.Pinterest, "Ocean waves to fall asleep to", "ocean", "MP4 · 720p",
                DownloadStatus.FAILED, 0, day + 90 * minute, done = 0,
                error = "This video is private, so it can't be downloaded."),
            item(Platform.X, "City lights timelapse", "city", "MP4 · 1080p",
                DownloadStatus.COMPLETE, 31 * mb, day + 30 * minute),
            item(Platform.Facebook, "Morning yoga flow in 5 minutes", "yoga", "MP4 · 720p",
                DownloadStatus.COMPLETE, (11.2 * mb).toLong(), 3 * 60 * minute),
            item(Platform.TikTok, "Golden hour drive along the coast", "sunset", "MP4 · 1080p",
                DownloadStatus.COMPLETE, (24.3 * mb).toLong(), 20 * minute),
            item(Platform.Instagram, "Street food tour: 10 dishes under \$5", "food", "MP4 · 720p",
                DownloadStatus.RUNNING, 18 * mb, 2 * minute, done = (11.3 * mb).toLong()),
        )
    }

    private companion object {
        const val DIR = "/data/local/tmp/grabbly-shots"
    }
}
