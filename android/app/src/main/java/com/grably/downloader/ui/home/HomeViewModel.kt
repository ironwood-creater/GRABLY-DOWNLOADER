package com.grably.downloader.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grably.downloader.AppContainer
import com.grably.downloader.data.ApiException
import com.grably.downloader.data.DownloadOption
import com.grably.downloader.data.GrablyApi
import com.grably.downloader.data.LinkCheck
import com.grably.downloader.data.LinkDetector
import com.grably.downloader.data.Platform
import com.grably.downloader.data.VideoInfo
import com.grably.downloader.data.db.DownloadEntity
import com.grably.downloader.data.db.DownloadStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val input: String = "",
    /** The platform of the link in the field, when it's one Grably supports */
    val detected: Platform? = null,
    val message: String? = null,
    val isError: Boolean = false,
    val loading: Boolean = false,
    /** Set when a video was found: shows the quality picker */
    val video: VideoInfo? = null,
)

sealed interface HomeEvent {
    data class DownloadStarted(val title: String) : HomeEvent
}

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    private val api = container.api
    private val downloads = container.downloads
    private val settings = container.settings

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events: Flow<HomeEvent> = _events.receiveAsFlow()

    /** The newest finished download, for the "Recently downloaded" card. */
    val latestDownload: StateFlow<DownloadEntity?> = downloads.all
        .map { list -> list.firstOrNull { it.status == DownloadStatus.COMPLETE } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** How many downloads are still running, for the badge on the Downloads button. */
    val activeCount: StateFlow<Int> = downloads.all
        .map { list -> list.count { it.isActive } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private var lookupJob: Job? = null

    var lastClipboardStamp: Long
        get() = settings.lastClipboardStamp
        set(value) {
            settings.lastClipboardStamp = value
        }

    var askedForNotifications: Boolean
        get() = settings.askedForNotifications
        set(value) {
            settings.askedForNotifications = value
        }

    fun onInputChange(text: String) {
        val check = LinkDetector.check(text)
        _state.update {
            it.copy(
                input = text,
                detected = (check as? LinkCheck.Supported)?.platform,
                message = null,
                isError = false,
            )
        }
    }

    /** Paste button: fill the field and look the video up straight away. */
    fun onPasted(text: String?) {
        if (text.isNullOrBlank()) {
            _state.update { it.copy(message = "Your clipboard is empty. Copy a video link first.", isError = true) }
            return
        }
        onInputChange(text)
        lookup()
    }

    /** A link the user just copied in another app: fill it in and look it up, but only if it's a supported one. */
    fun onClipboard(text: String) {
        val check = LinkDetector.check(text)
        if (check !is LinkCheck.Supported) return
        if (_state.value.loading || _state.value.video != null) return
        if (check.url == (LinkDetector.check(_state.value.input) as? LinkCheck.Supported)?.url) return
        onInputChange(text)
        lookup()
    }

    /** A link shared to Grably from another app's Share menu. */
    fun onShared(text: String) {
        lookupJob?.cancel()
        _state.update { it.copy(video = null, loading = false) }
        onInputChange(text)
        lookup()
    }

    fun clear() {
        lookupJob?.cancel()
        _state.value = HomeUiState()
    }

    fun lookup() {
        val current = _state.value
        if (current.loading) return
        val url = when (val check = LinkDetector.check(current.input)) {
            LinkCheck.Empty -> return fail("Paste a video link first.")
            LinkCheck.Invalid -> return fail("That doesn't look like a link. Copy the video's link and paste it here.")
            is LinkCheck.Unsupported -> return fail(
                "Grably doesn't support ${check.host} yet. It works with ${Platform.allNames}.",
            )
            is LinkCheck.Supported -> check.url
        }
        _state.update { it.copy(loading = true, message = "Finding your video…", isError = false) }
        lookupJob = viewModelScope.launch {
            try {
                val video = api.info(url)
                if (video.options.isEmpty()) {
                    fail("This video can't be downloaded. It may be private or removed.")
                } else {
                    _state.update { it.copy(loading = false, message = null, video = video) }
                }
            } catch (e: ApiException) {
                fail(e.message ?: GrablyApi.GENERIC_ERROR)
            }
        }
    }

    fun dismissVideo() {
        _state.update { it.copy(video = null) }
    }

    fun download(option: DownloadOption) {
        val video = _state.value.video ?: return
        _state.update { it.copy(video = null, input = "", detected = null, message = null, isError = false) }
        viewModelScope.launch {
            downloads.start(video, option)
            _events.send(HomeEvent.DownloadStarted(video.title))
        }
    }

    private fun fail(message: String) {
        _state.update { it.copy(loading = false, message = message, isError = true) }
    }
}
