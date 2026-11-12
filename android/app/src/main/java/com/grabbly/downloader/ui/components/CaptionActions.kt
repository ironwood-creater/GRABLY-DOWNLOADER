package com.grabbly.downloader.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.grabbly.downloader.appContainer
import com.grabbly.downloader.ui.icons.GrabblyIcons
import com.grabbly.downloader.util.Captions
import com.grabbly.downloader.util.SystemActions

/**
 * Copies text for the user. Remembers the clipboard's new timestamp so Grabbly doesn't pick up its
 * own copy (a caption may contain a link) as a newly copied video link.
 */
fun copyForUser(context: Context, label: String, text: String) {
    SystemActions.copyText(context, label, text)?.let { context.appContainer.settings.lastClipboardStamp = it }
}

/** "Copy caption" and "Copy hashtags" buttons; shows nothing if the post has no text. */
@Composable
fun CaptionActions(caption: String?, modifier: Modifier = Modifier) {
    if (caption.isNullOrBlank()) return
    val context = LocalContext.current
    val hashtags = remember(caption) { Captions.hashtags(caption) }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(
            onClick = { copyForUser(context, "Caption", caption) },
            label = { Text("Copy caption") },
            leadingIcon = { Icon(GrabblyIcons.Copy, contentDescription = null, modifier = Modifier.size(16.dp)) },
        )
        if (hashtags != null) {
            AssistChip(
                onClick = { copyForUser(context, "Hashtags", hashtags) },
                label = { Text("Copy hashtags") },
                leadingIcon = { Icon(GrabblyIcons.Hash, contentDescription = null, modifier = Modifier.size(16.dp)) },
            )
        }
    }
}
