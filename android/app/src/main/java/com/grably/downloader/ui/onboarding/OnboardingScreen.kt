package com.grably.downloader.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.grably.downloader.data.Platform
import com.grably.downloader.ui.components.GrablyMark
import com.grably.downloader.ui.components.PlatformBadge
import com.grably.downloader.ui.icons.GrablyIcons
import com.grably.downloader.ui.theme.Brand
import com.grably.downloader.ui.theme.grably

/** First-run welcome screen. */
@Composable
fun OnboardingScreen(onContinue: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // The picture takes whatever height is left, so the text and button below always fit
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            CurvedHeader(Modifier.fillMaxSize())
            Box(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(bottom = 64.dp)
                    .clipToBounds(),
                contentAlignment = Alignment.Center,
            ) {
                CardStack()
            }
            GrablyMark(
                size = 76.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 20.dp)
                    .shadow(12.dp, RoundedCornerShape(20.dp)),
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(44.dp))
            Text(
                "All Videos Download",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Save videos from TikTok, Instagram, Twitch and more straight to your phone. Copy a link, open Grabbly, done.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.grably.muted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Platform.featured.forEach { PlatformBadge(it, size = 30.dp) }
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "+${Platform.entries.size - Platform.featured.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
            FilledIconButton(
                onClick = onContinue,
                modifier = Modifier
                    .padding(bottom = 28.dp)
                    .size(64.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(GrablyIcons.ArrowRight, contentDescription = "Get started", modifier = Modifier.size(26.dp))
            }
        }
    }
}

/** Teal-to-navy gradient with a soft curve along the bottom edge. */
@Composable
private fun CurvedHeader(modifier: Modifier) {
    Canvas(modifier) {
        val curve = 56.dp.toPx()
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height - curve)
            quadraticTo(size.width / 2f, size.height + curve * 0.6f, 0f, size.height - curve)
            close()
        }
        drawPath(
            path,
            Brush.linearGradient(
                listOf(Brand.Teal400, Brand.Teal600, Brand.Navy800),
                start = Offset(0f, 0f),
                end = Offset(size.width * 0.6f, size.height),
            ),
        )
    }
}

/** Three tilted "video" cards, like a feed of saved clips. */
@Composable
private fun CardStack() {
    Box(contentAlignment = Alignment.Center) {
        VideoCard(
            Modifier
                .offset(x = (-78).dp, y = 14.dp)
                .rotate(-10f),
            width = 120.dp,
            colors = listOf(Color(0xFF5FD3C6), Color(0xFF18325A)),
            platform = Platform.Instagram,
        )
        VideoCard(
            Modifier
                .offset(x = 78.dp, y = 14.dp)
                .rotate(10f),
            width = 120.dp,
            colors = listOf(Color(0xFF2A4166), Color(0xFF10B3A3)),
            platform = Platform.Pinterest,
        )
        VideoCard(
            Modifier.offset(y = (-6).dp),
            width = 146.dp,
            colors = listOf(Color(0xFF0E9A8D), Color(0xFF0B1A33)),
            platform = Platform.TikTok,
            highlighted = true,
        )
    }
}

@Composable
private fun VideoCard(modifier: Modifier, width: Dp, colors: List<Color>, platform: Platform, highlighted: Boolean = false) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier
            .width(width)
            .height(width * 1.55f)
            .shadow(if (highlighted) 18.dp else 8.dp, shape)
            .clip(shape)
            .background(Brush.verticalGradient(colors))
            .border(1.dp, Color.White.copy(alpha = 0.25f), shape),
    ) {
        PlatformBadge(platform, size = 26.dp, modifier = Modifier.padding(10.dp))
        Box(
            Modifier
                .align(Alignment.Center)
                .size(if (highlighted) 50.dp else 40.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.22f))
                .border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(GrablyIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(if (highlighted) 22.dp else 18.dp))
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(0.8f)
                    .height(7.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.7f)),
            )
            Box(
                Modifier
                    .fillMaxWidth(0.5f)
                    .height(7.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.4f)),
            )
            if (highlighted) {
                Row(
                    Modifier
                        .padding(top = 4.dp)
                        .clip(CircleShape)
                        .background(Brand.Teal500)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(GrablyIcons.Download, contentDescription = null, tint = Brand.Navy900, modifier = Modifier.size(12.dp))
                    Text("Saved", style = MaterialTheme.typography.labelSmall, color = Brand.Navy900)
                }
            }
        }
    }
}
