package com.grably.downloader.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grably.downloader.data.Platform
import com.grably.downloader.data.db.DownloadEntity
import com.grably.downloader.data.db.DownloadStatus
import com.grably.downloader.ui.components.PlatformBadge
import com.grably.downloader.ui.components.ProgressLine
import com.grably.downloader.ui.components.SegmentedControl
import com.grably.downloader.ui.components.Thumbnail
import com.grably.downloader.ui.icons.GrablyIcons
import com.grably.downloader.ui.theme.grably
import com.grably.downloader.util.Format
import com.grably.downloader.util.SystemActions

@Composable
fun DownloadsScreen(viewModel: DownloadsViewModel, onBack: () -> Unit, onFindVideo: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf<DownloadEntity?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 20.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(GrablyIcons.ChevronLeft, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
            }
            Text(
                "Downloads",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
        }
        SegmentedControl(
            options = DownloadFilter.entries.map { it.label },
            selected = state.filter.ordinal,
            onSelect = { viewModel.setFilter(DownloadFilter.entries[it]) },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )

        if (state.loaded && state.groups.isEmpty()) {
            EmptyState(filter = state.filter, hasAny = state.totalCount > 0, onFindVideo = onFindVideo)
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
            ) {
                state.groups.forEach { group ->
                    item(key = "day-${group.label}", contentType = "header") {
                        Text(
                            group.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.grably.muted,
                            modifier = Modifier
                                .animateItem()
                                .padding(top = 10.dp, bottom = 2.dp, start = 4.dp),
                        )
                    }
                    items(group.items, key = { it.id }, contentType = { "download" }) { item ->
                        DownloadRow(
                            item = item,
                            modifier = Modifier.animateItem(),
                            onPlay = { SystemActions.play(context, item) },
                            onShare = { SystemActions.share(context, item) },
                            onRetry = { viewModel.retry(item) },
                            onCancel = { viewModel.cancel(item) },
                            onRemove = { viewModel.remove(item, deleteFile = false) },
                            onDelete = { confirmDelete = item },
                        )
                    }
                }
            }
        }
    }

    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this video?") },
            text = { Text("“${item.title}” will be removed from your phone and from this list.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.remove(item, deleteFile = true)
                        confirmDelete = null
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun DownloadRow(
    item: DownloadEntity,
    modifier: Modifier,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onDelete: () -> Unit,
) {
    val complete = item.status == DownloadStatus.COMPLETE
    val failed = item.status == DownloadStatus.FAILED
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .clickable(enabled = complete, onClick = onPlay)
                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Thumbnail(
                    url = item.thumbnailUrl,
                    isAudio = item.isAudio,
                    showPlay = complete,
                    modifier = Modifier.size(width = 64.dp, height = 84.dp),
                )
                Platform.fromKey(item.platform)?.let {
                    PlatformBadge(
                        it,
                        size = 20.dp,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                when (item.status) {
                    DownloadStatus.COMPLETE -> {
                        Text(
                            listOfNotNull(item.optionLabel, Format.size(item.totalBytes).takeIf { item.totalBytes > 0 })
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.grably.muted,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(GrablyIcons.Check, contentDescription = null, tint = MaterialTheme.grably.success, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Saved", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.grably.success)
                        }
                    }
                    DownloadStatus.FAILED -> {
                        Text(
                            item.error ?: "Download failed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onRetry,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Icon(GrablyIcons.Retry, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Try again", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    DownloadStatus.QUEUED, DownloadStatus.RUNNING -> {
                        val fraction = if (item.status == DownloadStatus.RUNNING && item.totalBytes > 0) {
                            item.bytesDownloaded.toFloat() / item.totalBytes
                        } else {
                            null
                        }
                        Text(
                            when {
                                item.status == DownloadStatus.QUEUED -> "Waiting to start…"
                                item.totalBytes > 0 -> "${Format.size(item.bytesDownloaded)} of ${Format.size(item.totalBytes)}"
                                item.bytesDownloaded > 0 -> "${Format.size(item.bytesDownloaded)} downloaded"
                                else -> "Preparing your video…"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.grably.muted,
                        )
                        Spacer(Modifier.height(8.dp))
                        ProgressLine(fraction)
                    }
                }
            }
            RowMenu(item = item, onPlay = onPlay, onShare = onShare, onRetry = onRetry, onCancel = onCancel, onRemove = onRemove, onDelete = onDelete)
        }
    }
}

@Composable
private fun RowMenu(
    item: DownloadEntity,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(GrablyIcons.More, contentDescription = "More options", tint = MaterialTheme.grably.muted, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val close = { open = false }
            when (item.status) {
                DownloadStatus.COMPLETE -> {
                    MenuAction("Play", close, onPlay)
                    MenuAction("Share", close, onShare)
                    MenuAction("Remove from list", close, onRemove)
                    MenuAction("Delete video", close, onDelete)
                }
                DownloadStatus.FAILED -> {
                    MenuAction("Try again", close, onRetry)
                    MenuAction("Remove from list", close, onRemove)
                }
                DownloadStatus.QUEUED, DownloadStatus.RUNNING -> {
                    MenuAction("Cancel download", close, onCancel)
                }
            }
        }
    }
}

@Composable
private fun MenuAction(label: String, close: () -> Unit, run: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = {
            close()
            run()
        },
    )
}

@Composable
private fun EmptyState(filter: DownloadFilter, hasAny: Boolean, onFindVideo: () -> Unit) {
    val (title, body) = when {
        !hasAny -> "No downloads yet" to "Videos you download appear here, and in your phone's gallery."
        filter == DownloadFilter.Failed -> "Nothing failed" to "Every download worked. Nice."
        else -> "Nothing here yet" to "Finished downloads will show up here."
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp)
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.grably.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(GrablyIcons.Inbox, contentDescription = null, tint = MaterialTheme.grably.onAccentSoft, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.grably.muted, textAlign = TextAlign.Center)
        if (!hasAny) {
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onFindVideo,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(GrablyIcons.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Paste a link")
            }
        }
        Spacer(Modifier.height(60.dp))
    }
}
