package com.grabbly.downloader.ui.batch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grabbly.downloader.AppContainer
import com.grabbly.downloader.data.ApiException
import com.grabbly.downloader.data.FoundLinks
import com.grabbly.downloader.data.GrabblyApi
import com.grabbly.downloader.data.LinkDetector
import com.grabbly.downloader.data.Platform
import com.grabbly.downloader.data.VideoInfo
import com.grabbly.downloader.util.QualityPicker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BatchStatus { Waiting, Finding, Added, Failed }

data class BatchRow(val url: String, val platform: Platform, val status: BatchStatus = BatchStatus.Waiting, val detail: String? = null)

data class BatchUiState(
    val text: String = "",
    val found: FoundLinks = FoundLinks(emptyList(), 0, false),
    /** One row per link once downloading started */
    val rows: List<BatchRow> = emptyList(),
    val running: Boolean = false,
    val message: String? = null,
) {
    val added: Int get() = rows.count { it.status == BatchStatus.Added }
    val failed: Int get() = rows.count { it.status == BatchStatus.Failed }
}

/**
 * "Paste several links": looks each link up in turn and starts its downloads in the quality set in
 * Settings. Lives as long as the app (not just the screen), so leaving the screen doesn't stop it.
 */
class BatchViewModel(container: AppContainer) : ViewModel() {
    private val api = container.api
    private val downloads = container.downloads
    private val settings = container.settings

    private val _state = MutableStateFlow(BatchUiState())
    val state: StateFlow<BatchUiState> = _state.asStateFlow()

    private var job: Job? = null

    fun onTextChange(text: String) {
        _state.update { it.copy(text = text, found = LinkDetector.findAll(text), message = null) }
    }

    /** Adds what's on the clipboard to the box, on a new line. */
    fun paste(clipboard: String?) {
        if (clipboard.isNullOrBlank()) {
            _state.update { it.copy(message = "Your clipboard is empty. Copy a link first.") }
            return
        }
        val current = _state.value.text.trimEnd()
        onTextChange(if (current.isEmpty()) clipboard else "$current\n$clipboard")
    }

    fun clearText() = onTextChange("")

    fun start() {
        val links = _state.value.found.supported
        if (links.isEmpty() || _state.value.running) return
        _state.update { it.copy(rows = links.map { link -> BatchRow(link.url, link.platform) }, running = true, message = null) }
        job = viewModelScope.launch {
            links.forEachIndexed { index, link ->
                setRow(index, BatchStatus.Finding, null)
                try {
                    val video = lookUp(index, link.url)
                    val preference = settings.quality.value
                    val picks = video.allItems.mapNotNull { item ->
                        QualityPicker.pick(item.options, preference, canAsk = false)?.let { item to it }
                    }
                    if (picks.isEmpty()) {
                        setRow(index, BatchStatus.Failed, "Nothing in this post can be downloaded.")
                    } else {
                        picks.forEach { (item, option) -> downloads.start(video, option, item) }
                        setRow(index, BatchStatus.Added, if (picks.size > 1) "${picks.size} files: ${video.title}" else video.title)
                    }
                } catch (e: ApiException) {
                    setRow(index, BatchStatus.Failed, e.message ?: GrabblyApi.GENERIC_ERROR)
                }
            }
            _state.update { it.copy(running = false) }
        }
    }

    /** Looks a link up; if the server asks to slow down, waits and tries again (twice). */
    private suspend fun lookUp(index: Int, url: String): VideoInfo {
        var attempt = 0
        while (true) {
            try {
                return api.info(url)
            } catch (e: ApiException) {
                if (!e.isBusy || attempt >= 2) throw e
                attempt++
                setRow(index, BatchStatus.Finding, "Waiting a moment…")
                delay(BUSY_WAIT_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        _state.update { state ->
            state.copy(
                running = false,
                rows = state.rows.map {
                    if (it.status == BatchStatus.Waiting || it.status == BatchStatus.Finding) it.copy(status = BatchStatus.Failed, detail = "Stopped") else it
                },
            )
        }
    }

    /** Back to an empty box for another list. */
    fun reset() {
        if (_state.value.running) return
        _state.value = BatchUiState()
    }

    private fun setRow(index: Int, status: BatchStatus, detail: String?) {
        _state.update { state ->
            state.copy(rows = state.rows.mapIndexed { i, row -> if (i == index) row.copy(status = status, detail = detail) else row })
        }
    }

    private companion object {
        const val BUSY_WAIT_MS = 20_000L
    }
}
