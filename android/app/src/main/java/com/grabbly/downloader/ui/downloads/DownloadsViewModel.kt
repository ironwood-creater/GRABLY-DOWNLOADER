package com.grabbly.downloader.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grabbly.downloader.data.DownloadRepository
import com.grabbly.downloader.data.db.DownloadEntity
import com.grabbly.downloader.data.db.DownloadStatus
import com.grabbly.downloader.util.Format
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

enum class DownloadFilter(val label: String) {
    All("All"),
    Complete("Complete"),
    Failed("Failed"),
    ;

    fun matches(item: DownloadEntity): Boolean = when (this) {
        All -> true
        Complete -> item.status == DownloadStatus.COMPLETE
        Failed -> item.status == DownloadStatus.FAILED
    }
}

data class DayGroup(val label: String, val items: List<DownloadEntity>)

/** Groups downloads (already newest first) under "Today", "Yesterday", "Nov 3"… */
fun groupByDay(
    items: List<DownloadEntity>,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): List<DayGroup> {
    val groups = LinkedHashMap<String, MutableList<DownloadEntity>>()
    for (item in items) {
        groups.getOrPut(Format.dayLabel(item.createdAt, today, zone, locale)) { mutableListOf() }.add(item)
    }
    return groups.map { (label, list) -> DayGroup(label, list) }
}

data class DownloadsUiState(
    val filter: DownloadFilter = DownloadFilter.All,
    val groups: List<DayGroup> = emptyList(),
    val totalCount: Int = 0,
    val loaded: Boolean = false,
)

/** The Downloads list, or with [privateFolder] the private folder's. */
class DownloadsViewModel(private val downloads: DownloadRepository, private val privateFolder: Boolean) : ViewModel() {
    private val filter = MutableStateFlow(DownloadFilter.All)

    private val _messages = Channel<String>(Channel.BUFFERED)

    /** Short confirmations and problems, shown as a snackbar */
    val messages: Flow<String> = _messages.receiveAsFlow()

    val state: StateFlow<DownloadsUiState> = combine(downloads.all, filter) { all, current ->
        val mine = all.filter { it.isPrivate == privateFolder }
        DownloadsUiState(
            filter = current,
            groups = groupByDay(mine.filter(current::matches)),
            totalCount = mine.size,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState())

    fun setFilter(value: DownloadFilter) {
        filter.value = value
    }

    fun retry(item: DownloadEntity) {
        viewModelScope.launch { downloads.retry(item.id) }
    }

    fun cancel(item: DownloadEntity) {
        viewModelScope.launch { downloads.cancel(item.id) }
    }

    fun remove(item: DownloadEntity, deleteFile: Boolean) {
        viewModelScope.launch { downloads.remove(item, deleteFile) }
    }

    fun moveToPrivate(item: DownloadEntity) {
        viewModelScope.launch {
            _messages.send(
                if (downloads.moveToPrivate(item)) {
                    "Moved to your private folder. It's no longer in your gallery."
                } else {
                    "Couldn't move this file. It may have been deleted from your phone."
                },
            )
        }
    }

    fun moveToGallery(item: DownloadEntity) {
        viewModelScope.launch {
            _messages.send(if (downloads.moveToGallery(item)) "Moved back to your gallery." else "Couldn't move this file.")
        }
    }
}
