package com.grably.downloader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grably.downloader.ui.GrablyAppContent
import com.grably.downloader.ui.theme.GrablyTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** A link shared to Grably from another app (Share → Grably) */
    private val sharedText = MutableStateFlow<String?>(null)

    /** Set when a download notification is tapped */
    private val openDownloads = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Only handle the launch intent once, not again after rotation
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            val shared by sharedText.collectAsStateWithLifecycle()
            val showDownloads by openDownloads.collectAsStateWithLifecycle()
            GrablyTheme {
                GrablyAppContent(
                    container = appContainer,
                    sharedText = shared,
                    onSharedTextHandled = { sharedText.value = null },
                    openDownloads = showDownloads,
                    onOpenDownloadsHandled = { openDownloads.value = false },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when {
            intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                if (!text.isNullOrBlank()) sharedText.value = text
            }
            intent.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false) -> openDownloads.value = true
        }
    }

    companion object {
        const val EXTRA_OPEN_DOWNLOADS = "open_downloads"
    }
}
