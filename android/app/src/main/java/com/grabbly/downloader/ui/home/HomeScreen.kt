package com.grabbly.downloader.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grabbly.downloader.data.Platform
import com.grabbly.downloader.data.db.DownloadEntity
import com.grabbly.downloader.ui.components.GrabblyMark
import com.grabbly.downloader.ui.components.PlatformBadge
import com.grabbly.downloader.ui.components.Thumbnail
import com.grabbly.downloader.ui.icons.GrabblyIcons
import com.grabbly.downloader.ui.theme.grabbly
import com.grabbly.downloader.util.Format
import com.grabbly.downloader.util.SystemActions
import kotlinx.coroutines.flow.filter

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBatch: () -> Unit,
    onOpenPlayer: (Long) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val latest by viewModel.latestDownload.collectAsStateWithLifecycle()
    val activeCount by viewModel.activeCount.collectAsStateWithLifecycle()
    val quality by viewModel.quality.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val snackbar = remember { SnackbarHostState() }
    val openDownloads by rememberUpdatedState(onOpenDownloads)

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is HomeEvent.DownloadStarted -> {
                    if (Build.VERSION.SDK_INT >= 33 && !viewModel.askedForNotifications &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        viewModel.askedForNotifications = true
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    val result = snackbar.showSnackbar(
                        message = if (event.count > 1) "Downloading ${event.count} files from “${event.title}”" else "Downloading “${event.title}”",
                        actionLabel = "View",
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) openDownloads()
                }
            }
        }
    }

    // Pick up a link the user just copied in TikTok, Instagram… when they come back to Grabbly.
    // Android only lets apps read the clipboard while they're in front, so check when the window gets focus.
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(windowInfo, viewModel) {
        snapshotFlow { windowInfo.isWindowFocused }
            .filter { it }
            .collect {
                SystemActions.newClipboardText(context, viewModel.lastClipboardStamp)?.let { (text, stamp) ->
                    viewModel.lastClipboardStamp = stamp
                    viewModel.onClipboard(text)
                }
            }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Header(activeCount = activeCount, onOpenDownloads = onOpenDownloads, onOpenSettings = onOpenSettings)
            Spacer(Modifier.height(22.dp))
            Text(
                "Download any video",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Paste a link from TikTok, Instagram, Facebook, X, Pinterest, Twitch or Tumblr.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.grabbly.muted,
            )
            Spacer(Modifier.height(20.dp))
            LinkField(
                value = state.input,
                loading = state.loading,
                onValueChange = viewModel::onInputChange,
                onPaste = { viewModel.onPasted(SystemActions.readClipboard(context)) },
                onClear = viewModel::clear,
                onSubmit = {
                    focusManager.clearFocus()
                    viewModel.lookup()
                },
            )
            Spacer(Modifier.height(10.dp))
            StatusLine(state)
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onOpenBatch) {
                Icon(GrabblyIcons.Batch, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Paste several links")
            }
            Spacer(Modifier.height(16.dp))
            SectionTitle("Copy a link from")
            Spacer(Modifier.height(12.dp))
            PlatformRow(onOpen = { SystemActions.openPlatform(context, it) })

            AnimatedVisibility(visible = latest != null, enter = fadeIn(), exit = fadeOut()) {
                latest?.let { item ->
                    Column {
                        Spacer(Modifier.height(28.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionTitle("Recently downloaded", Modifier.weight(1f))
                            TextButton(onClick = onOpenDownloads) { Text("See all") }
                        }
                        Spacer(Modifier.height(8.dp))
                        RecentCard(
                            item = item,
                            onPlay = { onOpenPlayer(item.id) },
                            onShare = { SystemActions.share(context, item) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            ShareTip()
            Spacer(Modifier.height(24.dp))
        }
    }

    state.video?.let { video ->
        if (video.hasSeveral) {
            PostSheet(
                video = video,
                quality = quality,
                onDismiss = viewModel::dismissVideo,
                onDownload = viewModel::downloadItems,
            )
        } else {
            VideoSheet(
                video = video,
                onDismiss = viewModel::dismissVideo,
                onDownload = viewModel::download,
            )
        }
    }
}

@Composable
private fun Header(activeCount: Int, onOpenDownloads: () -> Unit, onOpenSettings: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GrabblyMark(size = 38.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            "Grabbly",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onOpenSettings) {
            Icon(GrabblyIcons.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(4.dp))
        FilledTonalIconButton(
            onClick = onOpenDownloads,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            BadgedBox(
                badge = {
                    if (activeCount > 0) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ) { Text("$activeCount") }
                    }
                },
            ) {
                Icon(GrabblyIcons.Download, contentDescription = "Downloads", modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun LinkField(
    value: String,
    loading: Boolean,
    onValueChange: (String) -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(GrabblyIcons.Link, contentDescription = null, tint = MaterialTheme.grabbly.muted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                "Paste video link",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.grabbly.muted,
                                maxLines = 1,
                            )
                        }
                        inner()
                    }
                },
            )
            if (value.isEmpty()) {
                TextButton(onClick = onPaste, enabled = !loading) {
                    Icon(GrabblyIcons.Clipboard, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Paste")
                }
            } else {
                IconButton(onClick = onClear) {
                    Icon(GrabblyIcons.Close, contentDescription = "Clear", tint = MaterialTheme.grabbly.muted, modifier = Modifier.size(18.dp))
                }
            }
            FilledIconButton(
                onClick = onSubmit,
                enabled = !loading,
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(16.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    disabledContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                AnimatedContent(loading, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "go") { busy ->
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(GrabblyIcons.Download, contentDescription = "Find video", modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(state: HomeUiState) {
    val message = state.message
    when {
        message != null -> Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.grabbly.muted,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        state.detected != null -> Row(
            Modifier
                .clip(CircleShape)
                .background(MaterialTheme.grabbly.accentSoft)
                .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlatformBadge(state.detected, size = 20.dp, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                "${state.detected.displayName} link found. Tap download.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.grabbly.onAccentSoft,
            )
        }
        else -> Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(GrabblyIcons.Shield, contentDescription = null, tint = MaterialTheme.grabbly.muted, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                "Only download videos you own or have permission to save.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.grabbly.muted,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, modifier = modifier)
}

@Composable
private fun PlatformRow(onOpen: (Platform) -> Unit) {
    // All supported sites; swipe sideways for more. Starts with the five most used.
    val ordered = remember { Platform.featured + Platform.entries.filterNot { it in Platform.featured } }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items(ordered, key = { it.key }) { platform ->
            Column(
                Modifier
                    .width(68.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(role = Role.Button, onClickLabel = "Open ${platform.displayName}") { onOpen(platform) }
                    .padding(horizontal = 2.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PlatformBadge(platform, size = 48.dp, contentDescription = null)
                Spacer(Modifier.height(6.dp))
                Text(
                    platform.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.grabbly.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RecentCard(item: DownloadEntity, onPlay: () -> Unit, onShare: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .clickable(onClick = onPlay)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumbnail(
                url = if (item.isPhoto) item.contentUri ?: item.thumbnailUrl else item.thumbnailUrl,
                isAudio = item.isAudio,
                showPlay = !item.isPhoto,
                modifier = Modifier.size(width = 72.dp, height = 92.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    listOfNotNull(item.optionLabel, Format.size(item.totalBytes).takeIf { item.totalBytes > 0 }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.grabbly.muted,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(GrabblyIcons.Check, contentDescription = null, tint = MaterialTheme.grabbly.success, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Saved to your phone", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.grabbly.success)
                }
            }
            IconButton(onClick = onShare) {
                Icon(GrabblyIcons.Share, contentDescription = "Share", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun ShareTip() {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.grabbly.accentSoft,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(GrabblyIcons.Share, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Even quicker: share to Grabbly", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(
                    "In TikTok, Instagram or X, tap Share, then pick Grabbly. The video opens here, ready to download.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.grabbly.muted,
                )
            }
        }
    }
}
