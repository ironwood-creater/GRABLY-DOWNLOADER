package com.grabbly.downloader.ui.player

import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.grabbly.downloader.data.DownloadRepository
import com.grabbly.downloader.data.db.DownloadEntity
import com.grabbly.downloader.ui.components.Thumbnail
import com.grabbly.downloader.ui.icons.GrabblyIcons
import com.grabbly.downloader.ui.theme.Brand
import com.grabbly.downloader.util.Format
import com.grabbly.downloader.util.SystemActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** null while loading; [item] null when the download no longer exists. */
private class Loaded(val item: DownloadEntity?)

/**
 * Grabbly's own player: plays saved videos and sound, and shows saved photos (pinch to zoom).
 * A private file only opens when [unlocked] (that is, from the unlocked private folder).
 */
@Composable
fun PlayerScreen(downloadId: Long, unlocked: Boolean, repository: DownloadRepository, onBack: () -> Unit) {
    val loaded by remember(downloadId) { repository.observe(downloadId).map { Loaded(it) } }
        .collectAsStateWithLifecycle(initialValue = null)
    val context = LocalContext.current
    var controlsVisible by remember { mutableStateOf(true) }
    val item = loaded?.item

    var exists by remember(item?.contentUri) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(item?.contentUri) {
        if (item != null) exists = withContext(Dispatchers.IO) { SystemActions.fileExists(context, item) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when {
            loaded == null || (item != null && exists == null) -> Unit // loading: just black
            item == null || exists == false -> Message(
                "This file is no longer on your phone.",
                "It may have been deleted in another app. Download it again to watch it.",
            )
            item.isPrivate && !unlocked -> Message(
                "This file is in your private folder.",
                "Open it from Downloads → Private folder.",
            )
            item.isPhoto -> ZoomablePhoto(item.contentUri.orEmpty(), onTap = { controlsVisible = !controlsVisible })
            else -> MediaPlayback(
                uri = item.contentUri.orEmpty().toUri(),
                soundOnly = item.isAudio,
                artwork = item.thumbnailUrl,
                controlsVisible = controlsVisible,
                onToggleControls = { controlsVisible = !controlsVisible },
                onHideControls = { controlsVisible = false },
                onCantPlay = if (item.isPrivate) null else ({ SystemActions.openWith(context, item); Unit }),
            )
        }

        AnimatedVisibility(
            visible = controlsVisible || item == null || exists == false,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(GrabblyIcons.ChevronLeft, contentDescription = "Back", tint = Color.White)
                }
                Text(
                    item?.title.orEmpty(),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (item != null && !item.isPrivate && exists == true) {
                    IconButton(onClick = { SystemActions.share(context, item) }) {
                        Icon(GrabblyIcons.Share, contentDescription = "Share", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(title: String, body: String) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

/** A photo that fills the screen; pinch or double-tap to zoom. */
@Composable
private fun ZoomablePhoto(model: String, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    AsyncImage(
        model = model,
        contentDescription = "Photo",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    },
                )
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}

/** What the controls need from either player (a VideoView for videos, a MediaPlayer for sound). */
private interface Playback {
    fun play()
    fun pause()
    fun seekTo(ms: Int)
    val position: Int
}

@Composable
private fun MediaPlayback(
    uri: Uri,
    soundOnly: Boolean,
    artwork: String?,
    controlsVisible: Boolean,
    onToggleControls: () -> Unit,
    onHideControls: () -> Unit,
    onCantPlay: (() -> Unit)?,
) {
    val context = LocalContext.current
    var playback by remember { mutableStateOf<Playback?>(null) }
    var playing by remember { mutableStateOf(false) }
    var duration by remember { mutableIntStateOf(0) }
    var position by remember { mutableIntStateOf(0) }
    var ratio by remember { mutableFloatStateOf(0f) }
    var failed by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    // Where to continue after Android tore the video down (for example the app went to the background)
    var resumeAt by remember { mutableIntStateOf(-1) }

    if (soundOnly) {
        DisposableEffect(uri) {
            val player = MediaPlayer()
            var prepared = false
            player.setOnPreparedListener {
                prepared = true
                duration = it.duration
                it.start()
                playing = true
            }
            player.setOnCompletionListener { playing = false }
            player.setOnErrorListener { _, _, _ ->
                failed = true
                true
            }
            try {
                player.setDataSource(context, uri)
                player.prepareAsync()
            } catch (e: Exception) {
                failed = true
            }
            playback = object : Playback {
                override fun play() {
                    if (prepared) player.start()
                }

                override fun pause() {
                    if (prepared && player.isPlaying) player.pause()
                }

                override fun seekTo(ms: Int) {
                    if (prepared) player.seekTo(ms)
                }

                override val position: Int get() = if (prepared) player.currentPosition else 0
            }
            onDispose {
                playback = null
                player.release()
            }
        }
    }

    LaunchedEffect(playing, playback) {
        while (playing) {
            if (seeking == null) position = playback?.position ?: 0
            delay(250)
        }
    }
    LaunchedEffect(controlsVisible, playing) {
        if (controlsVisible && playing) {
            delay(3_000)
            onHideControls()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        resumeAt = playback?.position ?: position
        playback?.pause()
        playing = false
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (soundOnly) {
            Thumbnail(url = artwork, isAudio = true, modifier = Modifier.size(220.dp), shape = RoundedCornerShape(28.dp))
        } else {
            AndroidView(
                factory = { ctx ->
                    val view = VideoView(ctx)
                    view.setOnPreparedListener { mp ->
                        duration = mp.duration
                        if (mp.videoWidth > 0 && mp.videoHeight > 0) ratio = mp.videoWidth.toFloat() / mp.videoHeight
                        if (resumeAt >= 0) {
                            view.seekTo(resumeAt) // back from the background: wait for the user to press play
                            position = resumeAt
                        } else {
                            view.start()
                            playing = true
                        }
                    }
                    view.setOnCompletionListener { playing = false }
                    view.setOnErrorListener { _, _, _ ->
                        failed = true
                        true // no system "Can't play this video" dialog: we show our own message
                    }
                    playback = object : Playback {
                        override fun play() = view.start()
                        override fun pause() = view.pause()
                        override fun seekTo(ms: Int) = view.seekTo(ms)
                        override val position: Int get() = view.currentPosition
                    }
                    view.setVideoURI(uri)
                    view
                },
                onRelease = { it.stopPlayback() },
                modifier = if (ratio > 0f) Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = true) else Modifier.fillMaxSize(),
            )
        }

        // Catches taps above the video (show or hide the controls)
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(Unit) { detectTapGestures(onTap = { onToggleControls() }) },
        )

        if (failed) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .padding(40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Grabbly can't play this file.",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    if (onCantPlay != null) {
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = onCantPlay) { Text("Open in another app", color = Color.White) }
                    }
                }
            }
        }
    }

    AnimatedVisibility(
        visible = controlsVisible && !failed && duration > 0,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Slider(
                    value = seeking ?: position.toFloat().coerceAtMost(duration.toFloat()),
                    onValueChange = { seeking = it },
                    onValueChangeFinished = {
                        seeking?.let {
                            playback?.seekTo(it.toInt())
                            position = it.toInt()
                        }
                        seeking = null
                    },
                    valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Brand.Teal400,
                        activeTrackColor = Brand.Teal400,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    ),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledIconButton(
                        onClick = {
                            if (playing) {
                                playback?.pause()
                                playing = false
                            } else {
                                playback?.play()
                                playing = true
                            }
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Brand.Teal500, contentColor = Brand.Navy900),
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp)),
                    ) {
                        Icon(
                            if (playing) GrabblyIcons.Pause else GrabblyIcons.Play,
                            contentDescription = if (playing) "Pause" else "Play",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${Format.duration((seeking?.toInt() ?: position) / 1000)} / ${Format.duration(duration / 1000)}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}
