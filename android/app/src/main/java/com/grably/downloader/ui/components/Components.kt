package com.grably.downloader.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.grably.downloader.data.Platform
import com.grably.downloader.ui.icons.GrablyIcons
import com.grably.downloader.ui.theme.Brand
import com.grably.downloader.ui.theme.grably

/** The app logo: teal G on a navy rounded square. */
@Composable
fun GrablyMark(modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val shape = RoundedCornerShape(size * 0.27f)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(Brand.Navy900)
            .border(1.dp, Brand.Teal500.copy(alpha = 0.35f), shape),
    ) {
        Icon(GrablyIcons.LogoG, contentDescription = null, tint = Brand.Teal500, modifier = Modifier.fillMaxSize())
    }
}

/** Round platform logo in the platform's brand colour. */
@Composable
fun PlatformBadge(
    platform: Platform,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    contentDescription: String? = platform.displayName,
) {
    val background: Brush = when (platform) {
        Platform.TikTok, Platform.X -> Brush.linearGradient(listOf(Color.Black, Color.Black))
        Platform.Instagram -> Brush.linearGradient(
            listOf(Color(0xFFFEDA75), Color(0xFFFA7E1E), Color(0xFFD62976), Color(0xFF962FBF), Color(0xFF4F5BD5)),
        )
        Platform.Facebook -> Brush.linearGradient(listOf(Color(0xFF1877F2), Color(0xFF1877F2)))
        Platform.Pinterest -> Brush.linearGradient(listOf(Color(0xFFE60023), Color(0xFFE60023)))
    }
    val icon = when (platform) {
        Platform.TikTok -> GrablyIcons.BrandTikTok
        Platform.Instagram -> GrablyIcons.BrandInstagram
        Platform.Facebook -> GrablyIcons.BrandFacebook
        Platform.X -> GrablyIcons.BrandX
        Platform.Pinterest -> GrablyIcons.BrandPinterest
    }
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

/** A video thumbnail with a soft gradient behind it while it loads (or if there is none). */
@Composable
fun Thumbnail(
    url: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(14.dp),
    isAudio: Boolean = false,
    duration: String? = null,
    showPlay: Boolean = false,
) {
    Box(
        modifier
            .clip(shape)
            .background(Brush.linearGradient(listOf(Brand.Teal400, Brand.Navy700))),
        contentAlignment = Alignment.Center,
    ) {
        if (isAudio && url == null) {
            Icon(GrablyIcons.Music, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (showPlay) {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f))
                    .border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(GrablyIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        if (duration != null) {
            Text(
                duration,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** Thin progress bar; pass null for "still preparing". */
@Composable
fun ProgressLine(fraction: Float?, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val track = MaterialTheme.grably.track
    if (fraction == null) {
        LinearProgressIndicator(
            modifier = modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
            color = color,
            trackColor = track,
        )
    } else {
        Box(modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(track)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(color))
        }
    }
}

/** All / Complete / Failed style switch. */
@Composable
fun SegmentedControl(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth(),
        shadowElevation = 1.dp,
    ) {
        Row(Modifier.padding(4.dp)) {
            options.forEachIndexed { index, label ->
                val isSelected = index == selected
                val background by animateColorAsState(
                    if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    label = "segment",
                )
                Text(
                    label,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.grably.muted,
                    modifier = Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .background(background)
                        .semantics { this.selected = isSelected }
                        .clickable(role = Role.Tab) { onSelect(index) }
                        .padding(vertical = 10.dp),
                )
            }
        }
    }
}
