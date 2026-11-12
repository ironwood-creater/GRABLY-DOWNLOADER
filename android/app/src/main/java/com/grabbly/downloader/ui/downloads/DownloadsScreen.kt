package com.grabbly.downloader.ui.downloads

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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.grabbly.downloader.data.Platform
import com.grabbly.downloader.data.db.DownloadEntity
import com.grabbly.downloader.data.db.DownloadStatus
import com.grabbly.downloader.ui.components.PlatformBadge
import com.grabbly.downloader.ui.components.ProgressLine
import com.grabbly.downloader.ui.components.SegmentedControl
import com.grabbly.downloader.ui.components.Thumbnail
import com.grabbly.downloader.ui.components.copyForUser
import com.grabbly.downloader.ui.icons.GrabblyIcons
import com.grabbly.downloader.ui.theme.grabbly
import com.grabbly.downloader.util.Captions
import com.grabbly.downloader.util.Format
import com.grabbly.downloader.util.SystemActions

/**
 * The Downloads list. With [privateFolder] it is the private folder instead: files kept inside Grabbly,
 * out of the gallery, opened with the phone's fingerprint, face or PIN ([onOpenPrivate] unlocks it).
 */
@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    privateFolder: Boolean,
    onBack: () -> Unit,
    onFindVideo: () -> Unit,
    onOpen: (DownloadEntity) -> Unit,
    onOpenPrivate: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf<DownloadEntity?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(GrabblyIcons.ChevronLeft, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
                }
                Text(
                    if (privateFolder) "Private folder" else "Downloads",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                if (!privateFolder) {
                    IconButton(onClick = onOpenPrivate) {
                        Icon(GrabblyIcons.Lock, contentDescription = "Private folder", tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(22.dp))
                    }
                }
            }
            if (privateFolder) {
                Row(
                    Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(GrabblyIcons.Lock, contentDescription = null, tint = MaterialTheme.grabbly.muted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Only you can open these. They aren't in your gallery.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.grabbly.muted,
                    )
                }
            } else {
                SegmentedControl(
                    options = DownloadFilter.entries.map { it.label },
                    selected = state.filter.ordinal,
                    onSelect = { viewModel.setFilter(DownloadFilter.entries[it]) },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }

            if (state.loaded && state.groups.isEmpty()) {
                EmptyState(privateFolder = privateFolder, filter = state.filter, hasAny = state.totalCount > 0, onFindVideo = onFindVideo)
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
                                color = MaterialTheme.grabbly.muted,
                                modifier = Modifier
                                    .animateItem()
                                    .padding(top = 10.dp, bottom = 2.dp, start = 4.dp),
                            )
                        }
                        items(group.items, key = { it.id }, contentType = { "download" }) { item ->
                            DownloadRow(
                                item = item,
                                modifier = Modifier.animateItem(),
                                onOpen = { onOpen(item) },
                                actions = RowActions(
                                    openWith = { SystemActions.openWith(context, item) },
                                    share = { SystemActions.share(context, item) },
                                    copyCaption = { item.caption?.let { copyForUser(context, "Caption", it) } },
                                    copyHashtags = { Captions.hashtags(item.caption)?.let { copyForUser(context, "Hashtags", it) } },
                                    moveToPrivate = { viewModel.moveToPrivate(item) },
                                    moveToGallery = { viewModel.moveToGallery(item) },
                                    retry = { viewModel.retry(item) },
                                    cancel = { viewModel.cancel(item) },
                                    remove = { viewModel.remove(item, deleteFile = false) },
                                    delete = { confirmDelete = item },
                                ),
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )
    }

    confirmDelete?.let { item ->
        val what = kindOf(item)
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this $what?") },
            text = {
                Text(
                    if (item.isPrivate) {
                        "“${item.title}” will be deleted from your private folder."
                    } else {
                        "“${item.title}” will be removed from your phone and from this list."
                    },
                )
            },
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

private fun kindOf(item: DownloadEntity) = when {
    item.isPhoto -> "photo"
    item.isAudio -> "audio"
    else -> "video"
}

/** What the "More options" menu can do with a row. */
private class RowActions(
    val openWith: () -> Unit,
    val share: () -> Unit,
    val copyCaption: () -> Unit,
    val copyHashtags: () -> Unit,
    val moveToPrivate: () -> Unit,
    val moveToGallery: () -> Unit,
    val retry: () -> Unit,
    val cancel: () -> Unit,
    val remove: () -> Unit,
    val delete: () -> Unit,
)

@Composable
private fun DownloadRow(item: DownloadEntity, modifier: Modifier, onOpen: () -> Unit, actions: RowActions) {
    val complete = item.status == DownloadStatus.COMPLETE
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .clickable(enabled = complete, onClick = onOpen)
                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Thumbnail(
                    url = if (item.isPhoto && complete) item.contentUri ?: item.thumbnailUrl else item.thumbnailUrl,
                    isAudio = item.isAudio,
                    showPlay = complete && !item.isPhoto,
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
                            color = MaterialTheme.grabbly.muted,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (item.isPrivate) GrabblyIcons.Lock else GrabblyIcons.Check,
                                contentDescription = null,
                                tint = MaterialTheme.grabbly.success,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (item.isPrivate) "Private" else "Saved",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.grabbly.success,
                            )
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
                            onClick = actions.retry,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Icon(GrabblyIcons.Retry, contentDescription = null, modifier = Modifier.size(14.dp))
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
                                item.isPhoto -> "Getting the photo…"
                                else -> "Preparing your video…"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.grabbly.muted,
                        )
                        Spacer(Modifier.height(8.dp))
                        ProgressLine(fraction)
                    }
                }
            }
            RowMenu(item = item, onOpen = onOpen, actions = actions)
        }
    }
}

@Composable
private fun RowMenu(item: DownloadEntity, onOpen: () -> Unit, actions: RowActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(GrabblyIcons.More, contentDescription = "More options", tint = MaterialTheme.grabbly.muted, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val close = { open = false }
            when (item.status) {
                DownloadStatus.COMPLETE -> {
                    MenuAction(if (item.isPhoto) "View" else "Play", close, onOpen)
                    if (!item.isPrivate) {
                        MenuAction("Open in another app", close, actions.openWith)
                        MenuAction("Share", close, actions.share)
                    }
                    if (!item.caption.isNullOrBlank()) {
                        MenuAction("Copy caption", close, actions.copyCaption)
                        if (Captions.hashtags(item.caption) != null) MenuAction("Copy hashtags", close, actions.copyHashtags)
                    }
                    if (item.isPrivate) {
                        MenuAction("Move to gallery", close, actions.moveToGallery)
                    } else {
                        MenuAction("Move to private folder", close, actions.moveToPrivate)
                        MenuAction("Remove from list", close, actions.remove)
                    }
                    MenuAction("Delete ${kindOf(item)}", close, actions.delete)
                }
                DownloadStatus.FAILED -> {
                    MenuAction("Try again", close, actions.retry)
                    MenuAction("Remove from list", close, actions.remove)
                }
                DownloadStatus.QUEUED, DownloadStatus.RUNNING -> {
                    MenuAction("Cancel download", close, actions.cancel)
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
private fun EmptyState(privateFolder: Boolean, filter: DownloadFilter, hasAny: Boolean, onFindVideo: () -> Unit) {
    val (title, body) = when {
        privateFolder -> "Nothing private yet" to
            "In Downloads, tap ⋮ on a video or photo, then “Move to private folder” to hide it from your gallery."
        !hasAny -> "No downloads yet" to "Videos and photos you download appear here, and in your phone's gallery."
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
                .background(MaterialTheme.grabbly.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (privateFolder) GrabblyIcons.Lock else GrabblyIcons.Inbox,
                contentDescription = null,
                tint = MaterialTheme.grabbly.onAccentSoft,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.grabbly.muted, textAlign = TextAlign.Center)
        if (!hasAny && !privateFolder) {
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onFindVideo,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(GrabblyIcons.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Paste a link")
            }
        }
        Spacer(Modifier.height(60.dp))
    }
}
