package com.choidev.choifile.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.choidev.choifile.R
import com.choidev.choifile.playback.AudioPlayback
import com.choidev.choifile.util.ScrubMath
import com.choidev.choifile.util.VideoMath
import kotlinx.coroutines.delay

private val PLAYER_SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

/** Cover art decoded once per song, plus a dark tint taken from it for the background (iOS Music style). */
private class Cover(val image: ImageBitmap?, val tint: Color)

private fun decodeCover(bytes: ByteArray?): Cover {
    val bitmap = bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() } ?: return Cover(null, Color(0xFF2C2C2E))
    val one = Bitmap.createScaledBitmap(bitmap, 1, 1, true).getPixel(0, 0)
    // The average color, darkened so white text always reads on it.
    val tint = Color(one).let { Color(it.red * 0.55f, it.green * 0.55f, it.blue * 0.55f) }
    return Cover(bitmap.asImageBitmap(), tint)
}

/** Full-screen "Now Playing" in the style of iOS Music: cover, title, scrubber, controls, the play list. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AudioPlayerScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val s by AudioPlayback.state.collectAsStateWithLifecycle()
    val cover = remember(s.artwork) { decodeCover(s.artwork) }
    var position by remember { mutableLongStateOf(AudioPlayback.positionMs) }
    var dragging by remember { mutableStateOf<Long?>(null) }
    var showQueue by remember { mutableStateOf(false) }
    var sleepMenu by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            if (dragging == null) position = AudioPlayback.positionMs
            delay(250)
        }
    }
    // Nothing left to show once the music was stopped.
    LaunchedEffect(s.current) { if (s.current == null) onClose() }

    val white = Color.White
    val dim = Color.White.copy(alpha = 0.6f)
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(cover.tint, Color.Black))),
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(Icons.Default.KeyboardArrowDown, stringResource(R.string.close), white, onClick = onClose)
                Text(
                    stringResource(R.string.ap_now_playing), Modifier.weight(1f), color = dim,
                    style = MaterialTheme.typography.labelLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                RoundIcon(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.ap_queue), if (showQueue) white else dim) { showQueue = !showQueue }
            }

            if (showQueue) {
                val listState = rememberLazyListState(s.index.coerceAtLeast(0))
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
                    itemsIndexed(s.queue, key = { _, f -> f.path }) { i, f ->
                        val on = i == s.index
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Radius.md))
                                .background(if (on) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                                .clickable { AudioPlayback.skipTo(i) }
                                .padding(horizontal = Space.md, vertical = Space.md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${i + 1}", Modifier.width(32.dp), color = dim, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                            Text(f.nameWithoutExtension, Modifier.weight(1f), color = white, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = if (on) FontWeight.SemiBold else null)
                            if (on) Icon(if (s.playing) Icons.Default.PlayArrow else Icons.Default.Pause, null, Modifier.size(IconSize.md), tint = white)
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(Space.lg))
                // The cover shrinks a little while paused, as in iOS Music.
                val scale by animateFloatAsState(if (s.playing) 1f else 0.86f, label = "cover")
                Box(
                    Modifier
                        .weight(1f, fill = false)
                        .widthIn(max = 420.dp)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .scale(scale)
                        .shadow(24.dp, RoundedCornerShape(Radius.lg))
                        .clip(RoundedCornerShape(Radius.lg))
                        .background(Color(0xFF3A3A3C)),
                    contentAlignment = Alignment.Center,
                ) {
                    val img = cover.image
                    if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Icon(Icons.Default.MusicNote, null, Modifier.size(96.dp), tint = Color.White.copy(alpha = 0.5f))
                }
                Spacer(Modifier.weight(0.15f))
            }

            Spacer(Modifier.height(Space.xl))
            Column(Modifier.fillMaxWidth()) {
                Text(
                    s.title.orEmpty(), Modifier.basicMarquee(), color = white, maxLines = 1,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    listOfNotNull(s.artist, s.album).joinToString(" — ").ifEmpty { s.current?.parentFile?.name.orEmpty() },
                    color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                )
            }

            Spacer(Modifier.height(Space.lg))
            val shown = dragging ?: position
            AudioScrubber(shown, s.durationMs, onDrag = { dragging = it }, onDone = { AudioPlayback.seekTo(it); position = it; dragging = null })
            Row(Modifier.fillMaxWidth()) {
                Text(ScrubMath.format(shown), color = dim, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.weight(1f))
                Text(VideoMath.remaining(shown, s.durationMs), color = dim, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
            }

            Spacer(Modifier.height(Space.lg))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(Icons.Default.Shuffle, stringResource(R.string.ap_shuffle), if (s.shuffle) white else dim.copy(alpha = 0.4f)) { AudioPlayback.setShuffle(!s.shuffle) }
                RoundIcon(Icons.Default.FastRewind, stringResource(R.string.vp_prev), white, size = 44) { AudioPlayback.previous() }
                Box(
                    Modifier.size(76.dp).clip(CircleShape).clickable { AudioPlayback.toggle() },
                    contentAlignment = Alignment.Center,
                ) { Icon(if (s.playing) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(if (s.playing) R.string.vp_pause else R.string.vp_play), Modifier.size(64.dp), tint = white) }
                RoundIcon(Icons.Default.FastForward, stringResource(R.string.vp_next), white, size = 44) { AudioPlayback.next() }
                RoundIcon(
                    if (s.repeat == AudioPlayback.Repeat.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    stringResource(R.string.ap_repeat), if (s.repeat == AudioPlayback.Repeat.OFF) dim.copy(alpha = 0.4f) else white,
                ) { AudioPlayback.cycleRepeat() }
            }

            Spacer(Modifier.height(Space.lg))
            Row(Modifier.fillMaxWidth().padding(bottom = Space.lg), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                val speed = s.speed
                DarkPill(if (speed == 1f) "1x" else "${speed}x", active = speed != 1f) {
                    AudioPlayback.setSpeed(PLAYER_SPEEDS[(PLAYER_SPEEDS.indexOf(speed) + 1).mod(PLAYER_SPEEDS.size)])
                }
                Spacer(Modifier.width(Space.md))
                Box {
                    val left = s.sleepAt?.let { ((it - android.os.SystemClock.uptimeMillis()) / 60_000 + 1).toInt() }
                    DarkPill(
                        when {
                            left != null -> stringResource(R.string.vp_sleep_left, left)
                            s.sleepAtEnd -> stringResource(R.string.ap_sleep_song)
                            else -> stringResource(R.string.vp_sleep)
                        },
                        icon = Icons.Default.Bedtime, active = left != null || s.sleepAtEnd,
                    ) { sleepMenu = true }
                    ChoiMenu(sleepMenu, { sleepMenu = false }) {
                        ChoiMenuItem(stringResource(R.string.vp_sleep_off), { AudioPlayback.setSleep(null); sleepMenu = false }, checked = s.sleepAt == null && !s.sleepAtEnd)
                        listOf(15, 30, 45, 60, 90).forEach { m -> ChoiMenuItem(stringResource(R.string.vp_sleep_min, m), { AudioPlayback.setSleep(m); sleepMenu = false }) }
                        ChoiMenuItem(stringResource(R.string.ap_sleep_song), { AudioPlayback.setSleepAtEnd(); sleepMenu = false }, checked = s.sleepAtEnd)
                    }
                }
                Spacer(Modifier.width(Space.md))
                DarkPill(stringResource(R.string.ap_stop), icon = Icons.Default.Close) { AudioPlayback.stop() }
            }
            s.error?.let { Text(stringResource(R.string.vp_error, it), color = Color(0xFFFF6961), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Thin white scrub bar that thickens while held. Reports the dragged position, seeks once on release. */
@Composable
private fun AudioScrubber(position: Long, duration: Long, onDrag: (Long) -> Unit, onDone: (Long) -> Unit) {
    var held by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(duration) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val w = size.width.toFloat()
                    var pos = ScrubMath.positionAt(down.position.x, w, duration)
                    held = true
                    onDrag(pos)
                    drag(down.id) { c ->
                        pos = ScrubMath.positionAt(c.position.x, w, duration)
                        c.consume()
                        onDrag(pos)
                    }
                    held = false
                    onDone(pos)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val h = (if (held) 10.dp else 6.dp).toPx()
            val y = size.height / 2
            val frac = if (duration > 0) (position / duration.toFloat()).coerceIn(0f, 1f) else 0f
            drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2))
            drawRoundRect(Color.White.copy(alpha = if (held) 1f else 0.85f), Offset(0f, y - h / 2), Size(size.width * frac, h), CornerRadius(h / 2))
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, label: String, tint: Color, size: Int = 28, onClick: () -> Unit) {
    Box(Modifier.size((size + 20).dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(size.dp), tint = tint)
    }
}

@Composable
private fun DarkPill(text: String, icon: ImageVector? = null, active: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (active) Color.White else Color.White.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (active) Color.Black else Color.White
        icon?.let {
            Icon(it, null, Modifier.size(IconSize.sm), tint = tint)
            Spacer(Modifier.width(Space.xs))
        }
        Text(text, color = tint, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * The mini player above the bottom of the file list while music plays: cover, title, play/pause, next. Tapping it
 * opens [AudioPlayerScreen].
 */
@Composable
fun MiniPlayer(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val s by AudioPlayback.state.collectAsStateWithLifecycle()
    if (s.current == null) return
    val c = ChoiTheme.colors
    val cover = remember(s.artwork) { decodeCover(s.artwork) }
    Surface(
        modifier.fillMaxWidth().padding(horizontal = Space.md),
        shape = RoundedCornerShape(Radius.xl),
        color = c.popover,
        shadowElevation = 12.dp,
    ) {
        Row(Modifier.clickable(onClick = onOpen).padding(Space.sm), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(Radius.md)).background(c.secondary), contentAlignment = Alignment.Center) {
                val img = cover.image
                if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(Icons.Default.MusicNote, null, Modifier.size(IconSize.lg), tint = c.mutedForeground)
            }
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(s.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                s.artist?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = c.mutedForeground) }
            }
            Box(Modifier.size(44.dp).clip(CircleShape).clickable { AudioPlayback.toggle() }, contentAlignment = Alignment.Center) {
                Icon(if (s.playing) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(if (s.playing) R.string.vp_pause else R.string.vp_play), Modifier.size(28.dp), tint = c.foreground)
            }
            Box(Modifier.size(44.dp).clip(CircleShape).clickable { AudioPlayback.next() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.FastForward, stringResource(R.string.vp_next), Modifier.size(26.dp), tint = c.foreground)
            }
        }
    }
}
