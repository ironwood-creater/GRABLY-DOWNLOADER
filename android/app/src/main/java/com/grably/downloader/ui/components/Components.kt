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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
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
    val style = brandStyle(platform)
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(style.background)
            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (style.icon != null) {
            Icon(style.icon, contentDescription = contentDescription, tint = style.tint, modifier = Modifier.size(size * 0.5f))
        } else {
            // LinkedIn's logo isn't in Simple Icons: plain lettering instead
            val label = contentDescription
            Text(
                "in",
                color = style.tint,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.42f).sp,
                modifier = Modifier.semantics { if (label != null) this.contentDescription = label },
            )
        }
    }
}

private class BrandStyle(val background: Brush, val icon: ImageVector?, val tint: Color = Color.White)

private fun solid(hex: Long) = Brush.linearGradient(listOf(Color(hex), Color(hex)))

private fun brandStyle(platform: Platform): BrandStyle = when (platform) {
    Platform.TikTok -> BrandStyle(solid(0xFF000000), GrablyIcons.BrandTikTok)
    Platform.Instagram -> BrandStyle(
        Brush.linearGradient(listOf(Color(0xFFFEDA75), Color(0xFFFA7E1E), Color(0xFFD62976), Color(0xFF962FBF), Color(0xFF4F5BD5))),
        GrablyIcons.BrandInstagram,
    )
    Platform.Facebook -> BrandStyle(solid(0xFF1877F2), GrablyIcons.BrandFacebook)
    Platform.X -> BrandStyle(solid(0xFF000000), GrablyIcons.BrandX)
    Platform.Pinterest -> BrandStyle(solid(0xFFE60023), GrablyIcons.BrandPinterest)
    Platform.Reddit -> BrandStyle(solid(0xFFFF4500), GrablyIcons.BrandReddit)
    Platform.Vimeo -> BrandStyle(solid(0xFF1AB7EA), GrablyIcons.BrandVimeo)
    Platform.Dailymotion -> BrandStyle(solid(0xFF0A0A0A), GrablyIcons.BrandDailymotion)
    Platform.Snapchat -> BrandStyle(solid(0xFFFFFC00), GrablyIcons.BrandSnapchat, tint = Color.Black)
    Platform.Twitch -> BrandStyle(solid(0xFF9146FF), GrablyIcons.BrandTwitch)
    Platform.LinkedIn -> BrandStyle(solid(0xFF0A66C2), icon = null)
    Platform.Tumblr -> BrandStyle(solid(0xFF36465D), GrablyIcons.BrandTumblr)
    Platform.SoundCloud -> BrandStyle(solid(0xFFFF5500), GrablyIcons.BrandSoundCloud)
    Platform.Bilibili -> BrandStyle(solid(0xFF00A1D6), GrablyIcons.BrandBilibili)
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
