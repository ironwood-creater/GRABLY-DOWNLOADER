package com.grabbly.downloader.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.grabbly.downloader.data.MediaItem
import com.grabbly.downloader.data.Platform
import com.grabbly.downloader.data.VideoInfo
import com.grabbly.downloader.ui.components.CaptionActions
import com.grabbly.downloader.ui.components.PlatformBadge
import com.grabbly.downloader.ui.components.Thumbnail
import com.grabbly.downloader.ui.icons.GrabblyIcons
import com.grabbly.downloader.ui.theme.grabbly
import com.grabbly.downloader.util.Format
import com.grabbly.downloader.util.QualityPreference
import kotlinx.coroutines.launch

/** "3 photos and 2 videos" */
fun describeItems(items: List<MediaItem>): String {
    val photos = items.count { it.isPhoto }
    val others = items.size - photos
    fun count(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"
    return listOfNotNull(
        count(photos, "photo").takeIf { photos > 0 },
        count(others, "video").takeIf { others > 0 },
    ).joinToString(" and ")
}

/** Bottom sheet for an album or carousel: pick which videos and photos to save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostSheet(
    video: VideoInfo,
    quality: QualityPreference,
    onDismiss: () -> Unit,
    onDownload: (List<MediaItem>) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val platform = Platform.fromKey(video.platform)
    var selected by remember(video) { mutableStateOf(video.items.map { it.index }.toSet()) }
    var chosen by remember { mutableStateOf(false) }
    val picked = video.items.filter { it.index in selected }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
        ) {
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
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "This post has ${describeItems(video.items)}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.grabbly.muted,
            )
            if (!video.caption.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                CaptionActions(video.caption)
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Choose what to save",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                val all = selected.size == video.items.size
                TextButton(onClick = { selected = if (all) emptySet() else video.items.map { it.index }.toSet() }) {
                    Text(if (all) "Select none" else "Select all")
                }
            }
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                video.items.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { item ->
                            ItemTile(
                                item = item,
                                number = video.items.indexOf(item) + 1,
                                selected = item.index in selected,
                                onToggle = { on -> selected = if (on) selected + item.index else selected - item.index },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            if (picked.any { !it.isPhoto }) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Videos are saved in " + when (quality) {
                        QualityPreference.Ask, QualityPreference.Best -> "the best quality available."
                        QualityPreference.Audio -> "sound only (MP3), as set in Settings."
                        else -> "${quality.label} or the closest, as set in Settings."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.grabbly.muted,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    if (!chosen && picked.isNotEmpty()) {
                        chosen = true
                        scope.launch { sheetState.hide() }.invokeOnCompletion { onDownload(picked) }
                    }
                },
                enabled = picked.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Icon(GrabblyIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (picked.isEmpty()) "Choose at least one" else "Download ${picked.size}")
            }
        }
    }
}

@Composable
private fun ItemTile(item: MediaItem, number: Int, selected: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(14.dp)
    val kind = if (item.isPhoto) "Photo" else "Video"
    Box(
        modifier
            .aspectRatio(0.8f)
            .clip(shape)
            .border(if (selected) 2.5.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = onToggle)
            .semantics { contentDescription = "$kind $number" },
    ) {
        Thumbnail(
            url = item.thumbnail,
            shape = shape,
            isAudio = item.kind == "audio",
            duration = if (item.isPhoto) null else Format.duration(item.duration),
            modifier = Modifier.matchParentSize(),
        )
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (item.isPhoto) GrabblyIcons.Image else GrabblyIcons.Play,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(13.dp),
            )
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.3f))
                .border(1.5.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(GrabblyIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
            }
        }
    }
}
