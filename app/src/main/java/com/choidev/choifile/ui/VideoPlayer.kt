package com.choidev.choifile.ui

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Subtitles
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import com.choidev.choifile.R
import com.choidev.choifile.util.ScrubMath
import com.choidev.choifile.util.ScrubSeeker
import com.choidev.choifile.util.SidecarSubtitles
import com.choidev.choifile.util.TrackLabels
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.delay
import java.io.File

private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private val STEPS = listOf(1, 5, 10)

/** What the scrub bar is doing while a finger is on it. */
private data class Scrub(val positionMs: Long, val factor: Float)

/**
 * Builds the player: the device's MediaCodec decoders first, FFmpeg (NextLib) software decoders for whatever the
 * device cannot decode (DTS, AC3/E-AC3, TrueHD audio on most phones; HEVC/VP9/MPEG-2 on devices without them),
 * every Media3 container extractor, and subtitle files next to the video with the same base name.
 */
@OptIn(UnstableApi::class)
private fun buildPlayer(context: Context, file: File, subtitles: List<SidecarSubtitles.Match>): ExoPlayer {
    val renderers = NextRenderersFactory(context)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true)
    val extractors = DefaultExtractorsFactory()
        .setConstantBitrateSeekingEnabled(true)
        .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
    val item = MediaItem.Builder()
        .setUri(Uri.fromFile(file))
        .setSubtitleConfigurations(
            subtitles.mapIndexed { i, s ->
                MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(File(file.parentFile, s.fileName)))
                    .setMimeType(s.mimeType)
                    .setLanguage(s.language)
                    .setLabel(s.label)
                    .setSelectionFlags(if (i == 0) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()
            },
        )
        .build()
    return ExoPlayer.Builder(context, renderers, DefaultMediaSourceFactory(context, extractors)).build().apply {
        setMediaItem(item)
        prepare()
        playWhenReady = true
    }
}

/**
 * Video player with custom controls: dragging the scrub bar seeks the picture itself live (key frames while
 * moving, the exact frame when the finger rests or lifts), slower scrubbing when the finger moves away from the
 * bar (or always in fine mode), frame stepping, double-tap seeking by a selectable step, playback speed from 0.25x
 * (slow motion) to 2x, and a picker for audio and subtitle tracks.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(file: File) {
    val context = LocalContext.current
    val subtitles = remember(file) { SidecarSubtitles.match(file.name, file.parentFile?.list()?.toList().orEmpty()) }
    val player = remember { buildPlayer(context, file, subtitles) }
    val alive = remember { booleanArrayOf(true) }
    DisposableEffect(Unit) {
        onDispose {
            alive[0] = false
            player.release()
        }
    }

    val seeker = remember { ScrubSeeker() }
    var playing by remember { mutableStateOf(true) }
    var duration by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(1f) }
    var fps by remember { mutableFloatStateOf(30f) }
    var step by remember { mutableIntStateOf(5) }
    var fine by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var scrub by remember { mutableStateOf<Scrub?>(null) }
    var scrubbing by remember { mutableStateOf(false) } // from the first touch until the exact final frame is shown
    var resumeAfterScrub by remember { mutableStateOf(false) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var trackDialog by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var flash by remember { mutableStateOf<String?>(null) }
    var touched by remember { mutableIntStateOf(0) } // bumps on every interaction to restart the auto-hide timer

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) {
                if (player.duration != C.TIME_UNSET) duration = player.duration.coerceAtLeast(0)
                player.videoFormat?.frameRate?.takeIf { it > 0 }?.let { fps = it }
                // Audio-only media never renders a frame; a finished seek is all there is to wait for.
                if (state == Player.STATE_READY && player.videoFormat == null) seeker.seekDone()
            }
            override fun onRenderedFirstFrame() { seeker.seekDone() }
            override fun onTracksChanged(t: Tracks) { tracks = t }
            override fun onPlayerError(e: PlaybackException) { error = e.errorCodeName }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (true) {
            if (!scrubbing) position = player.currentPosition.coerceAtLeast(0)
            if (duration <= 0 && player.duration != C.TIME_UNSET) duration = player.duration.coerceAtLeast(0)
            delay(if (playing) 50 else 250)
        }
    }
    LaunchedEffect(controls, playing, scrub, touched) {
        if (controls && playing && scrub == null) { delay(3_500); controls = false }
    }
    LaunchedEffect(flash) { if (flash != null) { delay(700); flash = null } }

    // Live scrubbing: once per frame hand the player the newest finger position (one seek in flight at a time),
    // until the finger is up and the exact frame there is on screen; then playback resumes if it was running.
    LaunchedEffect(scrubbing) {
        if (!scrubbing) return@LaunchedEffect
        try {
            while (true) {
                withFrameMillis { }
                val now = SystemClock.uptimeMillis()
                seeker.next(now)?.let { s ->
                    player.setSeekParameters(if (s.exact) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
                    player.seekTo(s.positionMs)
                }
                if (scrub == null && seeker.settled(now)) break
            }
        } finally {
            if (alive[0]) {
                runCatching { player.setScrubbingModeEnabled(false) }
                player.setSeekParameters(SeekParameters.EXACT)
                if (resumeAfterScrub) player.play()
            }
            seeker.reset()
            scrubbing = false
        }
    }

    fun scrubTo(ms: Long, factor: Float) {
        if (!scrubbing) {
            resumeAfterScrub = player.playWhenReady
            player.pause()
            // Media3's scrubbing mode: skips audio and decoder flushes so frequent seeks render faster.
            runCatching { player.setScrubbingModeEnabled(true) }
            scrubbing = true
        }
        scrub = Scrub(ms, factor)
        position = ms
        seeker.move(ms, precise = factor < 1f, nowMs = SystemClock.uptimeMillis())
    }
    fun scrubEnd(ms: Long) {
        seeker.release(ms, SystemClock.uptimeMillis())
        position = ms
        scrub = null
    }
    fun seekTo(ms: Long) {
        val target = if (duration > 0) ms.coerceIn(0, duration) else ms.coerceAtLeast(0)
        player.setSeekParameters(SeekParameters.EXACT)
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
        error?.let {
            Surface(Modifier.align(Alignment.Center).padding(Space.xl), shape = RoundedCornerShape(Radius.lg), color = Color.Black.copy(alpha = 0.7f), contentColor = Color.White) {
                Text(stringResource(R.string.vp_error, it), Modifier.padding(Space.lg), style = MaterialTheme.typography.bodyMedium)
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
                if (s != null && s.factor < 1f) {
                    Text(
                        stringResource(R.string.vp_scrub_speed, ScrubMath.factorLabel(s.factor)),
                        Modifier.padding(bottom = Space.xs), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ScrubMath.format(s?.positionMs ?: position, tenths = fine || scrubbing) + " / " + ScrubMath.format(duration),
                        Modifier.weight(1f), color = Color.White,
                        style = if (s != null) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    if (hasPickableTracks(tracks)) {
                        IconPill(Icons.Default.Subtitles, stringResource(R.string.vp_tracks)) { trackDialog = true; touched++ }
                        Spacer(Modifier.width(Space.sm))
                    }
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
                    onStart = { ms, f -> scrubTo(ms, f); touched++ },
                    onMove = { ms, f -> scrubTo(ms, f) },
                    onEnd = { ms -> scrubEnd(ms); touched++ },
                    currentPosition = { if (scrubbing) position else player.currentPosition },
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

    if (trackDialog) TrackDialog(player, tracks) { trackDialog = false }
}

/** Worth showing the track button: more than one audio track, or any subtitle track. */
private fun hasPickableTracks(tracks: Tracks): Boolean =
    tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.sumOf { it.length } > 1 ||
        tracks.groups.any { it.type == C.TRACK_TYPE_TEXT }

/** Audio and subtitle tracks (embedded and sidecar files); picking one overrides the automatic choice. */
@OptIn(UnstableApi::class)
@Composable
private fun TrackDialog(player: ExoPlayer, tracks: Tracks, onDismiss: () -> Unit) {
    val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
    fun select(group: Tracks.Group, index: Int) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(group.type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .build()
    }
    ChoiDialog(
        onDismissRequest = onDismiss,
        confirmButton = { GhostButton(onDismiss) { Text(stringResource(R.string.close)) } },
        title = { Text(stringResource(R.string.vp_tracks)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (audio.isEmpty() && text.isEmpty()) Text(stringResource(R.string.vp_no_tracks))
                if (audio.isNotEmpty()) {
                    TrackHeader(stringResource(R.string.vp_audio))
                    TrackRows(audio, ::select)
                }
                if (text.isNotEmpty()) {
                    TrackHeader(stringResource(R.string.vp_subtitles))
                    val off = C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes || text.none { it.isSelected }
                    TrackRow(stringResource(R.string.vp_subtitles_off), selected = off, supported = true) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .build()
                    }
                    TrackRows(text, ::select)
                }
            }
        },
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun TrackRows(groups: List<Tracks.Group>, onSelect: (Tracks.Group, Int) -> Unit) {
    var n = 0
    groups.forEach { group ->
        for (i in 0 until group.length) {
            val f = group.getTrackFormat(i)
            val label = TrackLabels.label(f.label, f.language, f.sampleMimeType, f.channelCount, n++)
            val supported = group.isTrackSupported(i)
            TrackRow(if (supported) label else label + " · " + stringResource(R.string.vp_unsupported), group.isTrackSelected(i), supported) { onSelect(group, i) }
        }
    }
}

@Composable
private fun TrackHeader(text: String) {
    Text(text, Modifier.padding(top = Space.md, bottom = Space.xs), style = MaterialTheme.typography.labelMedium, color = ChoiTheme.colors.mutedForeground)
}

@Composable
private fun TrackRow(label: String, selected: Boolean, supported: Boolean, onClick: () -> Unit) {
    val c = ChoiTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(if (selected) c.selection else Color.Transparent)
            .clickable(enabled = supported, onClick = onClick)
            .padding(horizontal = Space.sm, vertical = Space.sm + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = if (supported) c.foreground else c.mutedForeground)
        if (selected) Icon(Icons.Default.Check, null, Modifier.size(IconSize.sm), tint = c.foreground)
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

@Composable
private fun IconPill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(Radius.md))
            .border(BorderStroke(Hairline, Color.White.copy(alpha = 0.25f)), RoundedCornerShape(Radius.md))
            .clickable(onClick = onClick)
            .padding(horizontal = Space.sm, vertical = Space.xs),
    ) {
        Icon(icon, label, Modifier.size(IconSize.sm), tint = Color.White)
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
