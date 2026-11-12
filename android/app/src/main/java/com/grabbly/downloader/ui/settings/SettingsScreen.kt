package com.grabbly.downloader.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grabbly.downloader.BuildConfig
import com.grabbly.downloader.ui.icons.GrabblyIcons
import com.grabbly.downloader.ui.theme.grabbly
import com.grabbly.downloader.util.QualityPreference
import com.grabbly.downloader.util.Settings
import com.grabbly.downloader.util.SystemActions

private const val WEBSITE = "https://grabbly-downloader-server.vercel.app"

@Composable
fun SettingsScreen(settings: Settings, onBack: () -> Unit, onOpenPrivate: () -> Unit) {
    val quality by settings.quality.collectAsStateWithLifecycle()
    val autoDownload by settings.autoDownload.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var choosingQuality by remember { mutableStateOf(false) }
    val site = BuildConfig.API_BASE_URL.takeIf { it.startsWith("https://") } ?: WEBSITE

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
            Text("Settings", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Downloads") {
                SettingRow(
                    icon = GrabblyIcons.Play,
                    title = "Video quality",
                    detail = if (quality == QualityPreference.Ask) {
                        "Ask every time"
                    } else {
                        "${quality.label}. Downloads start straight away."
                    },
                    onClick = { choosingQuality = true },
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = autoDownload, role = Role.Switch, onValueChange = settings::setAutoDownload)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconBubble(GrabblyIcons.Clipboard)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Download copied links automatically", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "When you copy or share a link and open Grabbly, it downloads right away in your video quality " +
                                "(the best quality if it's set to Ask). Posts with several videos or photos are saved in full.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.grabbly.muted,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(
                        checked = autoDownload,
                        onCheckedChange = null, // the whole row toggles it
                        colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
                    )
                }
            }

            Section("Private folder") {
                SettingRow(
                    icon = GrabblyIcons.Lock,
                    title = "Open private folder",
                    detail = "Hide videos and photos from your gallery. Opens with your fingerprint, face or phone PIN.",
                    onClick = onOpenPrivate,
                )
            }

            Section("About") {
                SettingRow(icon = GrabblyIcons.Shield, title = "Privacy policy", detail = "What Grabbly does with the links you paste", onClick = {
                    SystemActions.openWebsite(context, "$site/privacy")
                })
                SettingRow(icon = GrabblyIcons.Link, title = "Grabbly website", detail = "Download videos on your computer too", onClick = {
                    SystemActions.openWebsite(context, site)
                })
                SettingRow(icon = GrabblyIcons.Check, title = "Version", detail = BuildConfig.VERSION_NAME, onClick = null)
            }

            Text(
                "Only download videos and photos you own or have permission to save, and respect creators' rights.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.grabbly.muted,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }

    if (choosingQuality) {
        AlertDialog(
            onDismissRequest = { choosingQuality = false },
            title = { Text("Video quality") },
            text = {
                Column {
                    QualityPreference.entries.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .selectable(selected = option == quality, role = Role.RadioButton) {
                                    settings.setQuality(option)
                                    choosingQuality = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option == quality, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(option.label, style = MaterialTheme.typography.bodyLarge)
                                Text(option.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.grabbly.muted)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingQuality = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.grabbly.muted,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
            Column { content() }
        }
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, detail: String, onClick: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBubble(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.grabbly.muted)
        }
        if (onClick != null) {
            Icon(GrabblyIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.grabbly.muted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun IconBubble(icon: ImageVector) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.grabbly.accentSoft),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.grabbly.onAccentSoft, modifier = Modifier.size(18.dp))
    }
}
