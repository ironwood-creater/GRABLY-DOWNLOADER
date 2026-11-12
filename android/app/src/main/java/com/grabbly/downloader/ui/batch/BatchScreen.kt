package com.grabbly.downloader.ui.batch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grabbly.downloader.appContainer
import com.grabbly.downloader.data.LinkDetector
import com.grabbly.downloader.ui.components.PlatformBadge
import com.grabbly.downloader.ui.icons.GrabblyIcons
import com.grabbly.downloader.ui.theme.grabbly
import com.grabbly.downloader.util.QualityPreference
import com.grabbly.downloader.util.SystemActions

@Composable
fun BatchScreen(viewModel: BatchViewModel, onBack: () -> Unit, onOpenDownloads: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val quality by context.appContainer.settings.quality.collectAsStateWithLifecycle()

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
                Icon(GrabblyIcons.ChevronLeft, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
            }
            Text("Several links", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
        }

        if (state.rows.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text(
                    "Paste links from TikTok, Instagram, Facebook, X, Pinterest, Twitch or Tumblr, one per line. " +
                        "Grabbly downloads them one after another.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.grabbly.muted,
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = state.text,
                    onValueChange = viewModel::onTextChange,
                    placeholder = { Text("Paste links here") },
                    minLines = 6,
                    maxLines = 12,
                    shape = MaterialTheme.shapes.medium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { viewModel.paste(SystemActions.readClipboard(context)) }) {
                        Icon(GrabblyIcons.Clipboard, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Paste")
                    }
                    if (state.text.isNotEmpty()) {
                        TextButton(onClick = viewModel::clearText) { Text("Clear") }
                    }
                }
                val found = state.found
                val summary = state.message ?: buildString {
                    val count = found.supported.size
                    append(if (count == 1) "1 link found." else "$count links found.")
                    if (found.unsupported > 0) {
                        append(if (found.unsupported == 1) " 1 link from another site will be skipped." else " ${found.unsupported} links from other sites will be skipped.")
                    }
                    if (found.tooMany) append(" Only the first ${LinkDetector.MAX_LINKS} are used.")
                }
                if (state.text.isNotBlank() || state.message != null) {
                    Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    "Videos are saved in " + when (quality) {
                        QualityPreference.Ask, QualityPreference.Best -> "the best quality available"
                        QualityPreference.Audio -> "sound only (MP3)"
                        else -> "${quality.label} or the closest"
                    } + ". You can change this in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.grabbly.muted,
                )
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = viewModel::start,
                    enabled = found.supported.isNotEmpty(),
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
                    Text(if (found.supported.isEmpty()) "Download all" else "Download all (${found.supported.size})")
                }
            }
        } else {
            Text(
                when {
                    state.running -> "Adding ${state.rows.size} links to your downloads…"
                    else -> "Done: ${state.added} added" + if (state.failed > 0) ", ${state.failed} couldn't be downloaded." else "."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(state.rows, key = { index, row -> "$index-${row.url}" }) { _, row -> LinkRow(row) }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.running) {
                    OutlinedButton(onClick = viewModel::stop, modifier = Modifier.weight(1f)) { Text("Stop") }
                } else {
                    OutlinedButton(onClick = viewModel::reset, modifier = Modifier.weight(1f)) { Text("More links") }
                }
                Button(
                    onClick = onOpenDownloads,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier.weight(1f),
                ) { Text("See downloads") }
            }
        }
    }
}

@Composable
private fun LinkRow(row: BatchRow) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PlatformBadge(row.platform, size = 32.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    row.url.removePrefix("https://").removePrefix("www."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.grabbly.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when (row.status) {
                        BatchStatus.Waiting -> "Waiting…"
                        BatchStatus.Finding -> row.detail ?: "Finding…"
                        BatchStatus.Added -> "Added: ${row.detail.orEmpty()}"
                        BatchStatus.Failed -> row.detail ?: "Couldn't download this link."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (row.status == BatchStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            when (row.status) {
                BatchStatus.Finding -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                BatchStatus.Added -> Icon(GrabblyIcons.Check, contentDescription = "Added", tint = MaterialTheme.grabbly.success, modifier = Modifier.size(18.dp))
                BatchStatus.Failed -> Icon(GrabblyIcons.Close, contentDescription = "Failed", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                BatchStatus.Waiting -> Unit
            }
        }
    }
}
