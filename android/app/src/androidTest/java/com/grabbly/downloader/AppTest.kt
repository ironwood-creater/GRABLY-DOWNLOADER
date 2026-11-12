package com.grabbly.downloader

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.grabbly.downloader.data.DownloadRepository
import com.grabbly.downloader.data.GrabblyApi
import com.grabbly.downloader.util.DeviceLock
import com.grabbly.downloader.util.QualityPreference
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the real app on a phone or emulator.
 *
 * The download tests need a Grabbly server; CI starts a stand-in one (android/ci/fake_server.py)
 * and builds the app with -Pgrabbly.apiBaseUrl=http://10.0.2.2:8000.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AppTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext
    private val container get() = app.appContainer

    @Before
    fun prepare() {
        // So Android's permission pop-up doesn't cover the app during the test
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        startClean()
    }

    @After
    fun cleanUp() = startClean()

    /** Each test starts with no downloads and the default settings. */
    private fun startClean() {
        container.database.clearAllTables()
        File(app.filesDir, DownloadRepository.PRIVATE_FOLDER).deleteRecursively()
        container.settings.setQuality(QualityPreference.Ask)
        container.settings.setAutoDownload(false)
    }

    private val needsServer get() = BuildConfig.API_BASE_URL.contains("10.0.2.2")

    private fun skipWelcome() {
        compose.waitForIdle()
        if (compose.onAllNodes(hasText("All Videos Download")).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithContentDescription("Get started").performClick()
        }
        waitFor("the home screen") { hasText("Download any video") }
    }

    /** Waits for a node, and says what is on screen instead if it never appears. */
    private fun waitFor(what: String, timeoutMs: Long = 10_000, matcher: () -> androidx.compose.ui.test.SemanticsMatcher) {
        try {
            compose.waitUntilAtLeastOneExists(matcher(), timeoutMs)
        } catch (e: Throwable) {
            val screen = compose.onAllNodes(isRoot()).printToString(maxDepth = 12)
            throw AssertionError("$what didn't appear. On screen:\n$screen", e)
        }
    }

    private fun waitForSaved(count: Int, timeoutMs: Long = 60_000) {
        compose.waitUntil(timeoutMs) { compose.onAllNodes(hasText("Saved")).fetchSemanticsNodes().size == count }
    }

    private fun typeLink(text: String) {
        compose.onNode(hasSetTextAction()).performTextInput(text)
    }

    @Test
    fun checksLinksAndMovesBetweenScreens() {
        skipWelcome()

        typeLink("https://youtube.com/watch?v=1")
        compose.onNodeWithContentDescription("Find video").performClick()
        compose.waitUntilExactlyOneExists(hasText("Grabbly doesn't support youtube.com yet", substring = true), 5_000)

        compose.onNodeWithContentDescription("Clear").performClick()
        typeLink("Look at this https://www.tiktok.com/@ava/video/7311")
        compose.onNodeWithText("TikTok link found", substring = true).assertExists()

        if (BuildConfig.API_BASE_URL.isEmpty()) {
            compose.onNodeWithContentDescription("Find video").performClick()
            compose.waitUntilExactlyOneExists(hasText(GrabblyApi.NOT_CONFIGURED), 5_000)
        }
        compose.onNodeWithContentDescription("Clear").performClick()

        // Settings
        compose.onNodeWithContentDescription("Settings").performClick()
        waitFor("Settings") { hasText("Video quality") }
        compose.onNodeWithText("Video quality").performClick()
        compose.onNodeWithText("720p").performClick()
        compose.waitUntilExactlyOneExists(hasText("720p. Downloads start straight away."), 5_000)
        compose.onNodeWithContentDescription("Back").performClick()
        waitFor("the home screen") { hasText("Download any video") }

        // Several links
        compose.onNodeWithText("Paste several links").performClick()
        waitFor("the several links screen") { hasText("Paste links here") }
        typeLink("https://www.tiktok.com/@a/video/1\nhttps://youtube.com/watch?v=2\nhttps://x.com/a/status/3")
        compose.waitUntilExactlyOneExists(hasText("2 links found. 1 link from another site will be skipped."), 5_000)
        compose.onNodeWithText("Download all (2)").assertExists()
        compose.onNodeWithText("Clear").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        waitFor("the home screen") { hasText("Download any video") }

        // Downloads, and the private folder (the test phone has no screen lock to open it with)
        compose.onNodeWithContentDescription("Downloads").performClick()
        compose.waitUntilExactlyOneExists(hasText("Complete"), 5_000)
        compose.onNodeWithText("Failed").performClick()
        compose.onNodeWithText("All").performClick()
        if (!DeviceLock.isAvailable(app)) {
            compose.onNodeWithContentDescription("Private folder").performClick()
            compose.waitUntilExactlyOneExists(hasText("Set a screen lock first"), 5_000)
            compose.onNodeWithText("Not now").performClick()
        }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntilExactlyOneExists(hasText("Download any video"), 5_000)
    }

    @Test
    fun downloadsAVideoAndOpensItInThePlayer() {
        assumeTrue("needs the stand-in server", needsServer)
        skipWelcome()

        typeLink("https://www.tiktok.com/@grabbly/video/1234567890")
        compose.onNodeWithContentDescription("Find video").performClick()

        // Quality picker
        waitFor("the quality picker", 15_000) { hasText("Grabbly test clip") }
        compose.onNodeWithText("Copy caption").assertExists()
        compose.onNodeWithText("MP4 · 720p").performClick()

        // Downloads list: wait for it to finish and land in the gallery
        compose.waitUntilExactlyOneExists(hasText("Download any video"), 5_000)
        compose.onNodeWithContentDescription("Downloads").performClick()
        waitForSaved(1)

        // Grabbly's player (the stand-in "video" is random bytes, so it says it can't play it)
        compose.onNodeWithText("Grabbly test clip").performClick()
        waitFor("the player", 15_000) {
            hasText("Grabbly can't play this file.") or hasContentDescription("Pause") or hasContentDescription("Play")
        }
        compose.onNodeWithContentDescription("Back").performClick()
        waitForSaved(1, 5_000)

        // Delete it again
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Delete video").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntilExactlyOneExists(hasText("No downloads yet"), 10_000)
    }

    @Test
    fun savesTheVideosAndPhotosOfAnAlbum() {
        assumeTrue("needs the stand-in server", needsServer)
        skipWelcome()

        typeLink("https://www.instagram.com/p/Ccarousel/")
        compose.onNodeWithContentDescription("Find video").performClick()
        waitFor("the album picker", 15_000) { hasText("This post has 2 photos and 1 video.") }
        compose.onNodeWithText("Copy hashtags").assertExists()
        compose.onNodeWithText("Download 3").performClick()

        compose.waitUntilExactlyOneExists(hasText("Download any video"), 5_000)
        compose.onNodeWithContentDescription("Downloads").performClick()
        waitForSaved(3)

        // A photo opens in the player's photo viewer
        compose.onNodeWithText("Beach day album · 2 of 3").performClick()
        waitFor("the photo viewer") { hasContentDescription("Photo") }
        compose.onNodeWithContentDescription("Back").performClick()
        waitForSaved(3, 5_000)

        // Hide photo 3 of 3 in the private folder
        compose.onNode(hasContentDescription("More options") and hasAnyAncestor(hasText("Beach day album · 3 of 3"))).performClick()
        compose.onNodeWithText("Move to private folder").performClick()
        waitForSaved(2, 10_000)
        compose.onNodeWithText("Beach day album · 3 of 3").assertDoesNotExist()
    }

    @Test
    fun theQualityInSettingsSkipsTheChoice() {
        assumeTrue("needs the stand-in server", needsServer)
        container.settings.setQuality(QualityPreference.P720)
        skipWelcome()

        typeLink("https://www.tiktok.com/@grabbly/video/1234567890")
        compose.onNodeWithContentDescription("Find video").performClick()
        waitFor("the download to start", 15_000) { hasText("Downloading “Grabbly test clip”") }
        compose.onNodeWithContentDescription("Downloads").performClick()
        waitForSaved(1)
        compose.onNodeWithText("MP4 · 720p", substring = true).assertExists()
    }

    @Test
    fun downloadsSeveralLinks() {
        assumeTrue("needs the stand-in server", needsServer)
        skipWelcome()

        compose.onNodeWithText("Paste several links").performClick()
        waitFor("the several links screen") { hasText("Paste links here") }
        typeLink("https://www.tiktok.com/@grabbly/video/1234567890\nhttps://www.instagram.com/p/Ccarousel/")
        compose.onNodeWithText("Download all (2)").performClick()
        waitFor("the list to finish", 30_000) { hasText("Done: 2 added.") }
        compose.onNodeWithText("See downloads").performClick()
        waitForSaved(4) // the video, and the album's video and two photos
    }
}
