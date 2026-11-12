package com.grably.downloader

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.grably.downloader.data.GrablyApi
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real app on a phone or emulator.
 *
 * [downloadsAVideo] needs a Grabbly server; CI starts a stand-in one (android/ci/fake_server.py)
 * and builds the app with -Pgrably.apiBaseUrl=http://10.0.2.2:8000.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AppTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun allowNotifications() {
        // So Android's permission pop-up doesn't cover the app during the test
        if (Build.VERSION.SDK_INT >= 33) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.grantRuntimePermission(
                instrumentation.targetContext.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
    }

    private fun skipWelcome() {
        compose.waitForIdle()
        if (compose.onAllNodes(hasText("All Videos Download")).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithContentDescription("Get started").performClick()
        }
        try {
            compose.waitUntilExactlyOneExists(hasText("Download any video"), 10_000)
        } catch (e: Throwable) {
            // Say what is on screen instead, so a failure can be understood from the log alone
            val screen = compose.onAllNodes(isRoot()).printToString(maxDepth = 12)
            throw AssertionError("The home screen didn't appear. On screen:\n$screen", e)
        }
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
            compose.waitUntilExactlyOneExists(hasText(GrablyApi.NOT_CONFIGURED), 5_000)
        }
        compose.onNodeWithContentDescription("Clear").performClick()

        compose.onNodeWithContentDescription("Downloads").performClick()
        compose.waitUntilExactlyOneExists(hasText("Complete"), 5_000)
        compose.onNodeWithText("Failed").performClick()
        compose.onNodeWithText("All").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntilExactlyOneExists(hasText("Download any video"), 5_000)
    }

    @Test
    fun downloadsAVideo() {
        assumeTrue("needs the stand-in server", BuildConfig.API_BASE_URL.contains("10.0.2.2"))
        skipWelcome()

        typeLink("https://www.tiktok.com/@grably/video/1234567890")
        compose.onNodeWithContentDescription("Find video").performClick()

        // Quality picker
        compose.waitUntilExactlyOneExists(hasText("Grabbly test clip"), 15_000)
        compose.onNodeWithText("MP4 · 720p").performClick()

        // Downloads list: wait for it to finish and land in the gallery
        compose.waitUntilExactlyOneExists(hasText("Download any video"), 5_000)
        compose.onNodeWithContentDescription("Downloads").performClick()
        compose.waitUntilExactlyOneExists(hasText("Saved"), 60_000)
        compose.onNodeWithText("Grabbly test clip").assertExists()

        // Delete it again
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Delete video").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntilExactlyOneExists(hasText("No downloads yet"), 10_000)
    }
}
