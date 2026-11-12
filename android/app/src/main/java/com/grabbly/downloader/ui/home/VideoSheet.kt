package com.grabbly.downloader.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.grabbly.downloader.data.DownloadOption
import com.grabbly.downloader.data.Platform
import com.grabbly.downloader.data.VideoInfo
import com.grabbly.downloader.ui.components.CaptionActions
import com.grabbly.downloader.ui.components.PlatformBadge
import com.grabbly.downloader.ui.components.Thumbnail
import com.grabbly.downloader.ui.icons.GrabblyIcons
import com.grabbly.downloader.ui.theme.grabbly
import com.grabbly.downloader.util.Format
import kotlinx.coroutines.launch

/** Bottom sheet that shows the found video or photo and its choices. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoSheet(video: VideoInfo, onDismiss: () -> Unit, onDownload: (DownloadOption) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val platform = Platform.fromKey(video.platform)
    var chosen by remember { mutableStateOf(false) } // ignore a second tap while the sheet closes

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumbnail(
                    url = video.thumbnail,
                    duration = Format.duration(video.duration),
                    modifier = Modifier.size(width = 92.dp, height = 120.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (platform != null) {
                            PlatformBadge(platform, size = 20.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(video.platformName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.grabbly.muted)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        video.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    video.uploader?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.grabbly.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (!video.caption.isNullOrBlank()) {
                Spacer(Modifier.height(14.dp))
                CaptionActions(video.caption)
            }
            Spacer(Modifier.height(20.dp))
            Text(
                if (video.options.all { it.isPhoto }) "Save the photo" else "Choose a quality",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                video.options.forEachIndexed { index, option ->
                    OptionRow(
                        option = option,
                        recommended = index == 0,
                        onClick = {
                            if (!chosen) {
                                chosen = true
                                scope.launch { sheetState.hide() }.invokeOnCompletion { onDownload(option) }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionRow(option: DownloadOption, recommended: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        shape = shape,
        color = if (recommended) primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (recommended) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (recommended) Color.Transparent else MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        Row(
            Modifier
                .clickable(role = Role.Button, onClickLabel = "Download ${option.label}", onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (recommended) Color.Black.copy(alpha = 0.12f) else MaterialTheme.grabbly.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    when {
                        option.isAudio -> GrabblyIcons.Music
                        option.isPhoto -> GrabblyIcons.Image
                        else -> GrabblyIcons.Play
                    },
                    contentDescription = null,
                    tint = if (recommended) MaterialTheme.colorScheme.onPrimary else MaterialTheme.grabbly.onAccentSoft,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(option.label, style = MaterialTheme.typography.titleSmall)
                val detail = listOfNotNull(
                    when {
                        option.isAudio -> "Sound only"
                        option.isPhoto -> "Full-size photo"
                        else -> "Video with sound"
                    },
                    option.size?.takeIf { it > 0 }?.let { "about ${Format.size(it)}" },
                ).joinToString(" · ")
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (recommended) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f) else MaterialTheme.grabbly.muted,
                )
            }
            Icon(GrabblyIcons.Download, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}
