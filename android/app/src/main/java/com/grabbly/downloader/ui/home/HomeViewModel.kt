package com.grabbly.downloader.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grabbly.downloader.AppContainer
import com.grabbly.downloader.data.ApiException
import com.grabbly.downloader.data.DownloadOption
import com.grabbly.downloader.data.GrabblyApi
import com.grabbly.downloader.data.LinkCheck
import com.grabbly.downloader.data.LinkDetector
import com.grabbly.downloader.data.MediaItem
import com.grabbly.downloader.data.Platform
import com.grabbly.downloader.data.VideoInfo
import com.grabbly.downloader.data.db.DownloadEntity
import com.grabbly.downloader.util.QualityPicker
import com.grabbly.downloader.util.QualityPreference
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
    /** The platform of the link in the field, when it's one Grabbly supports */
    val detected: Platform? = null,
    val message: String? = null,
    val isError: Boolean = false,
    val loading: Boolean = false,
    /** Set when a post was found and the user chooses what to save: shows the quality or item picker */
    val video: VideoInfo? = null,
)

sealed interface HomeEvent {
    data class DownloadStarted(val title: String, val count: Int = 1) : HomeEvent
}

class HomeViewModel(container: AppContainer) : ViewModel() {
    private val api = container.api
    private val downloads = container.downloads
    private val settings = container.settings

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events: Flow<HomeEvent> = _events.receiveAsFlow()

    /** The quality chosen in Settings (shown on the album picker) */
    val quality: StateFlow<QualityPreference> = settings.quality

    /** The newest finished download outside the private folder, for the "Recently downloaded" card. */
    val latestDownload: StateFlow<DownloadEntity?> = downloads.all
        .map { list -> list.firstOrNull { it.isComplete && !it.isPrivate } }
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
        lookup(copied = true)
    }

    /** A link shared to Grabbly from another app's Share menu. */
    fun onShared(text: String) {
        lookupJob?.cancel()
        _state.update { it.copy(video = null, loading = false) }
        onInputChange(text)
        lookup(copied = true)
    }

    fun clear() {
        lookupJob?.cancel()
        _state.value = HomeUiState()
    }

    /**
     * Looks the link up. [copied] is true for a link copied or shared in another app: with
     * "Download copied links automatically" on, it then downloads without asking anything.
     */
    fun lookup(copied: Boolean = false) {
        val current = _state.value
        if (current.loading) return
        val url = when (val check = LinkDetector.check(current.input)) {
            LinkCheck.Empty -> return fail("Paste a video link first.")
            LinkCheck.Invalid -> return fail("That doesn't look like a link. Copy the video's link and paste it here.")
            is LinkCheck.Unsupported -> return fail(
                "Grabbly doesn't support ${check.host} yet. It works with ${Platform.allNames}.",
            )
            is LinkCheck.Supported -> check.url
        }
        _state.update { it.copy(loading = true, message = "Finding your video…", isError = false) }
        lookupJob = viewModelScope.launch {
            try {
                val video = api.info(url)
                val items = video.allItems
                val automatic = copied && settings.autoDownload.value
                when {
                    items.all { it.options.isEmpty() } -> fail("This video can't be downloaded. It may be private or removed.")
                    video.hasSeveral -> if (automatic) startItems(video, items) else show(video)
                    else -> {
                        val item = items.first()
                        val option = QualityPicker.pick(item.options, settings.quality.value, canAsk = !automatic)
                        if (option == null) show(video) else startOne(video, option, item)
                    }
                }
            } catch (e: ApiException) {
                fail(e.message ?: GrabblyApi.GENERIC_ERROR)
            }
        }
    }

    fun dismissVideo() {
        _state.update { it.copy(video = null) }
    }

    /** A choice from the quality sheet (a post with one video or photo). */
    fun download(option: DownloadOption) {
        val video = _state.value.video ?: return
        startOne(video, option, video.allItems.first())
    }

    /** The videos and photos picked from an album or carousel. */
    fun downloadItems(selected: List<MediaItem>) {
        val video = _state.value.video ?: return
        startItems(video, selected)
    }

    private fun show(video: VideoInfo) {
        _state.update { it.copy(loading = false, message = null, video = video) }
    }

    private fun startOne(video: VideoInfo, option: DownloadOption, item: MediaItem) {
        resetAfterStart()
        viewModelScope.launch {
            downloads.start(video, option, item)
            _events.send(HomeEvent.DownloadStarted(video.title))
        }
    }

    private fun startItems(video: VideoInfo, selected: List<MediaItem>) {
        val preference = settings.quality.value
        val picks = selected.mapNotNull { item -> QualityPicker.pick(item.options, preference, canAsk = false)?.let { item to it } }
        if (picks.isEmpty()) return fail("Nothing in this post can be downloaded.")
        resetAfterStart()
        viewModelScope.launch {
            picks.forEach { (item, option) -> downloads.start(video, option, item) }
            _events.send(HomeEvent.DownloadStarted(video.title, picks.size))
        }
    }

    private fun resetAfterStart() {
        _state.update { it.copy(video = null, input = "", detected = null, message = null, isError = false, loading = false) }
    }

    private fun fail(message: String) {
        _state.update { it.copy(loading = false, message = message, isError = true) }
    }
}
