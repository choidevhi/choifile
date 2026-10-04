package com.choidev.choifile.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import com.choidev.choifile.R
import com.choidev.choifile.util.ScrubMath
import com.choidev.choifile.util.VideoFrames
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private val STEPS = listOf(1, 5, 10)

/** What the scrub bar is doing while a finger is on it. */
private data class Scrub(val positionMs: Long, val factor: Float)

/**
 * Video player with custom controls: a scrub bar that shows a frame preview and the exact time while dragging,
 * slower scrubbing when the finger moves away from the bar (or always in fine mode), frame stepping,
 * double-tap seeking by a selectable step, and playback speed from 0.25x (slow motion) to 2x.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(file: File) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            playWhenReady = true
        }
    }
    val frames = remember { VideoFrames(file) }
    DisposableEffect(Unit) {
        onDispose {
            player.release()
            scope.launch(NonCancellable) { frames.release() }
        }
    }

    var playing by remember { mutableStateOf(true) }
    var duration by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(1f) }
    var fps by remember { mutableFloatStateOf(30f) }
    var step by remember { mutableIntStateOf(5) }
    var fine by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var scrub by remember { mutableStateOf<Scrub?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var flash by remember { mutableStateOf<String?>(null) }
    var touched by remember { mutableIntStateOf(0) } // bumps on every interaction to restart the auto-hide timer

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) {
                if (player.duration != C.TIME_UNSET) duration = player.duration.coerceAtLeast(0)
                player.videoFormat?.frameRate?.takeIf { it > 0 }?.let { fps = it }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (true) {
            if (scrub == null) position = player.currentPosition.coerceAtLeast(0)
            if (duration <= 0 && player.duration != C.TIME_UNSET) duration = player.duration.coerceAtLeast(0)
            delay(if (playing) 50 else 250)
        }
    }
    LaunchedEffect(controls, playing, scrub, touched) {
        if (controls && playing && scrub == null) { delay(3_500); controls = false }
    }
    LaunchedEffect(flash) { if (flash != null) { delay(700); flash = null } }
    // Preview frames: coarse (key frame) while dragging at full speed, exact frame when scrubbing finely.
    LaunchedEffect(frames) {
        snapshotFlow { scrub?.let { ScrubMath.thumbBucket(it.positionMs, duration) to (it.factor < 1f) } }
            .filterNotNull()
            .distinctUntilChanged()
            .conflate()
            .collect { (ms, precise) -> frames.frameAt(ms, precise)?.let { preview = it } }
    }

    fun seekTo(ms: Long, exact: Boolean = true) {
        val target = if (duration > 0) ms.coerceIn(0, duration) else ms.coerceAtLeast(0)
        player.setSeekParameters(if (exact) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        player.seekTo(target)
        position = target
    }
    fun seekBy(deltaMs: Long) {
        seekTo(player.currentPosition + deltaMs)
        flash = (if (deltaMs < 0) "−" else "+") + (kotlin.math.abs(deltaMs) / 1000) + "s"
    }
    fun frameStep(forward: Boolean) {
        player.pause()
        val f = ScrubMath.frameMs(fps)
        seekTo(player.currentPosition + if (forward) f else -f)
        flash = if (forward) "+1f" else "−1f"
    }
    fun setSpeed(s: Float) {
        speed = s
        player.playbackParameters = PlaybackParameters(s)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ PlayerView(it).apply { useController = false; this.player = player } }, Modifier.fillMaxSize())

        // Gesture layer: single tap shows/hides the controls, double tap on either half seeks by the chosen step.
        Row(Modifier.fillMaxSize()) {
            listOf(-1, 1).forEach { dir ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().pointerInput(step, dir) {
                        detectTapGestures(
                            onTap = { controls = !controls; touched++ },
                            onDoubleTap = { seekBy(dir * step * 1000L); touched++ },
                        )
                    },
                )
            }
        }

        flash?.let {
            Surface(Modifier.align(Alignment.Center), shape = RoundedCornerShape(Radius.lg), color = Color.Black.copy(alpha = 0.6f), contentColor = Color.White) {
                Text(it, Modifier.padding(horizontal = Space.lg, vertical = Space.sm), style = MaterialTheme.typography.titleMedium)
            }
        }

        AnimatedVisibility(controls || scrub != null, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                    .navigationBarsPadding()
                    .padding(horizontal = Space.lg, vertical = Space.sm),
            ) {
                val s = scrub
                if (s != null) ScrubPreview(s, duration, preview)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ScrubMath.format(s?.positionMs ?: position, tenths = fine || s != null) + " / " + ScrubMath.format(duration),
                        Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace,
                    )
                    var speedMenu by remember { mutableStateOf(false) }
                    Box {
                        Pill(if (speed == 1f) "1x" else "${speed}x", active = speed != 1f) { speedMenu = true; touched++ }
                        ChoiMenu(speedMenu, { speedMenu = false }) {
                            SPEEDS.forEach { v ->
                                DropdownMenuItem(
                                    text = { Text((if (v == 1f) "1x" else "${v}x") + if (v < 1f) "  " + stringResource(R.string.vp_slow) else "") },
                                    onClick = { setSpeed(v); speedMenu = false },
                                    trailingIcon = { if (v == speed) Text("✓") },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(Space.sm))
                    Pill("±${step}s") { step = STEPS[(STEPS.indexOf(step) + 1) % STEPS.size]; touched++ }
                    Spacer(Modifier.width(Space.sm))
                    Pill(stringResource(R.string.vp_fine), active = fine) { fine = !fine; touched++ }
                }

                ScrubBar(
                    position = s?.positionMs ?: position,
                    duration = duration,
                    fine = fine,
                    onStart = { ms, f -> preview = null; scrub = Scrub(ms, f); touched++ },
                    onMove = { ms, f -> scrub = Scrub(ms, f) },
                    onEnd = { ms -> seekTo(ms); scrub = null; touched++ },
                    currentPosition = { player.currentPosition },
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    ControlButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.vp_frame_back)) { frameStep(false); touched++ }
                    ControlButton(Icons.Default.FastRewind, stringResource(R.string.vp_back_step, step)) { seekBy(-step * 1000L); touched++ }
                    IconButton(
                        { if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() }; touched++ },
                        Modifier.size(56.dp).background(Color.White, CircleShape),
                    ) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(if (playing) R.string.vp_pause else R.string.vp_play), Modifier.size(28.dp), tint = Color.Black)
                    }
                    ControlButton(Icons.Default.FastForward, stringResource(R.string.vp_fwd_step, step)) { seekBy(step * 1000L); touched++ }
                    ControlButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.vp_frame_fwd)) { frameStep(true); touched++ }
                }
            }
        }
    }
}

@Composable
private fun ControlButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick) { Icon(icon, label, Modifier.size(IconSize.lg + 4.dp), tint = Color.White) }
}

@Composable
private fun Pill(text: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (active) Color.White else Color.White.copy(alpha = 0.12f), RoundedCornerShape(Radius.md))
            .border(BorderStroke(Hairline, Color.White.copy(alpha = 0.25f)), RoundedCornerShape(Radius.md))
            .clickable(onClick = onClick)
            .padding(horizontal = Space.sm + 2.dp, vertical = Space.xs),
    ) {
        Text(text, color = if (active) Color.Black else Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

/** Frame preview + timestamp, placed above the finger's position on the bar. */
@Composable
private fun ScrubPreview(s: Scrub, duration: Long, frame: Bitmap?) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(150.dp)) {
        val density = LocalDensity.current
        val cardW = 168.dp
        val fraction = if (duration > 0) s.positionMs / duration.toFloat() else 0f
        val x = (maxWidth * fraction - cardW / 2).coerceIn(0.dp, maxWidth - cardW)
        Column(
            Modifier.offset { IntOffset(with(density) { x.roundToPx() }, 0) }.width(cardW),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.fillMaxWidth().height(96.dp).background(Color.Black, RoundedCornerShape(Radius.lg))
                    .border(Hairline, Color.White.copy(alpha = 0.35f), RoundedCornerShape(Radius.lg)),
                contentAlignment = Alignment.Center,
            ) {
                if (frame != null && !frame.isRecycled) {
                    Image(frame.asImageBitmap(), null, Modifier.fillMaxSize().padding(1.dp), contentScale = ContentScale.Fit)
                }
            }
            Spacer(Modifier.height(Space.xs))
            Text(ScrubMath.format(s.positionMs, tenths = true), color = Color.White, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
            if (s.factor < 1f) {
                Text(stringResource(R.string.vp_scrub_speed, ScrubMath.factorLabel(s.factor)), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * Seek bar. Touching jumps to that point (in fine mode it keeps the current position instead); dragging moves
 * proportionally, scaled by [ScrubMath.factor] of the finger's vertical distance from the bar.
 */
@Composable
private fun ScrubBar(
    position: Long,
    duration: Long,
    fine: Boolean,
    onStart: (Long, Float) -> Unit,
    onMove: (Long, Float) -> Unit,
    onEnd: (Long) -> Unit,
    currentPosition: () -> Long,
) {
    val density = LocalDensity.current
    val track = Color.White.copy(alpha = 0.25f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .pointerInput(duration, fine) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val width = size.width.toFloat()
                    var pos = if (fine) currentPosition() else ScrubMath.positionAt(down.position.x, width, duration)
                    var factor = ScrubMath.factor(0f, fine)
                    var lastX = down.position.x
                    down.consume()
                    onStart(pos, factor)
                    drag(down.id) { change ->
                        val awayDp = with(density) { (change.position.y - size.height / 2f).toDp().value }
                        factor = ScrubMath.factor(awayDp, fine)
                        pos = ScrubMath.advance(pos, change.position.x - lastX, width, duration, factor)
                        lastX = change.position.x
                        change.consume()
                        onMove(pos, factor)
                    }
                    onEnd(pos)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(20.dp)) {
            val h = 4.dp.toPx()
            val y = size.height / 2
            val frac = if (duration > 0) (position / duration.toFloat()).coerceIn(0f, 1f) else 0f
            drawRoundRect(track, Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2))
            drawRoundRect(Color.White, Offset(0f, y - h / 2), Size(size.width * frac, h), CornerRadius(h / 2))
            drawCircle(Color.White, radius = 7.dp.toPx(), center = Offset(size.width * frac, y))
        }
    }
}

