package com.grabbly.downloader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grabbly.downloader.ui.GrabblyAppContent
import com.grabbly.downloader.ui.theme.GrabblyTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** A link shared to Grabbly from another app (Share → Grabbly) */
    private val sharedText = MutableStateFlow<String?>(null)

    /** Set when a download notification is tapped */
    private val openDownloads = MutableStateFlow(false)

    /** Set when a "Saved" notification is tapped: the download to open in the player */
    private val openPlayer = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Only handle the launch intent once, not again after rotation
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            val shared by sharedText.collectAsStateWithLifecycle()
            val showDownloads by openDownloads.collectAsStateWithLifecycle()
            val playId by openPlayer.collectAsStateWithLifecycle()
            GrabblyTheme {
                GrabblyAppContent(
                    container = appContainer,
                    sharedText = shared,
                    onSharedTextHandled = { sharedText.value = null },
                    openDownloads = showDownloads,
                    onOpenDownloadsHandled = { openDownloads.value = false },
                    openPlayer = playId,
                    onOpenPlayerHandled = { openPlayer.value = null },
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
            intent.getLongExtra(EXTRA_PLAY_ID, -1L) >= 0 -> openPlayer.value = intent.getLongExtra(EXTRA_PLAY_ID, -1L)
        }
    }

    companion object {
        const val EXTRA_OPEN_DOWNLOADS = "open_downloads"
        const val EXTRA_PLAY_ID = "play_id"
    }
}
