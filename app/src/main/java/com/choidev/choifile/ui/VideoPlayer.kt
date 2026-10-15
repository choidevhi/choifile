package com.choidev.choifile.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.TextureView
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
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.SubtitleView
import com.choidev.choifile.AppStores
import com.choidev.choifile.R
import com.choidev.choifile.security.AppPrefs
import com.choidev.choifile.util.DragKind
import com.choidev.choifile.util.FileKind
import com.choidev.choifile.util.NaturalOrder
import com.choidev.choifile.util.ScrubMath
import com.choidev.choifile.util.ScrubSeeker
import com.choidev.choifile.util.SidecarSubtitles
import com.choidev.choifile.util.TrackLabels
import com.choidev.choifile.util.VideoEnd
import com.choidev.choifile.util.VideoFit
import com.choidev.choifile.util.VideoMath
import com.choidev.choifile.util.ZoomMath
import com.choidev.choifile.util.kindOf
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f)
private val STEPS = listOf(5, 10, 30)
private val SUBTITLE_SCALES = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
private const val HOLD_SPEED = 2f
private val CONTROL_TINT = Color.Black.copy(alpha = 0.45f)

/** What the scrub bar is doing while a finger is on it. */
private data class Scrub(val positionMs: Long, val factor: Float)

/** Feedback shown in the middle of the screen while a gesture changes something. */
private data class Hud(val icon: ImageVector, val text: String, val fraction: Float? = null)

/** Videos next to [file] (same folder, natural order). Hidden files only when the opened one is hidden. */
private fun videoSiblings(file: File): List<File> {
    val dir = file.parentFile ?: return listOf(file)
    val showHidden = file.name.startsWith(".")
    val list = dir.listFiles()?.filter { it.isFile && kindOf(it) == FileKind.VIDEO && (showHidden || !it.name.startsWith(".")) }.orEmpty()
    val sorted = list.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
    return if (sorted.any { it.path == file.path }) sorted else listOf(file)
}

/** One playlist entry: the video plus the subtitle files next to it with the same base name. */
@OptIn(UnstableApi::class)
private fun mediaItem(file: File, folderNames: List<String>): MediaItem {
    val subtitles = SidecarSubtitles.match(file.name, folderNames)
    return MediaItem.Builder()
        .setUri(Uri.fromFile(file))
        .setMediaId(file.path)
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
}

/**
 * NextLib's FFmpeg renderers plus a subtitle renderer that decodes a whole subtitle file at once. Media3's default
 * (cues parsed during extraction, one sample per line) only shows lines that start after a seek, so after any
 * seek the line already on screen there stayed blank until the next one.
 */
@OptIn(UnstableApi::class)
private class PlayerRenderers(context: Context) : NextRenderersFactory(context) {
    override fun buildTextRenderers(context: Context, output: TextOutput, outputLooper: Looper, extensionRendererMode: Int, out: ArrayList<Renderer>) {
        out.add(TextRenderer(output, outputLooper).apply { experimentalSetLegacyDecodingEnabled(true) })
    }
}

/**
 * Builds the player: the device's MediaCodec decoders first, FFmpeg (NextLib) software decoders for whatever the
 * device cannot decode (DTS, AC3/E-AC3, TrueHD audio on most phones; HEVC/VP9/MPEG-2 on devices without them),
 * every Media3 container extractor, audio focus, and a pause when headphones are unplugged.
 */
@OptIn(UnstableApi::class)
private fun buildPlayer(context: Context, item: MediaItem, startMs: Long): ExoPlayer {
    val renderers = PlayerRenderers(context)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true)
    val extractors = DefaultExtractorsFactory()
        .setConstantBitrateSeekingEnabled(true)
        .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
    val audio = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()
    val sources = DefaultMediaSourceFactory(context, extractors).experimentalParseSubtitlesDuringExtraction(false)
    return ExoPlayer.Builder(context, renderers, sources)
        .setAudioAttributes(audio, true)
        .setHandleAudioBecomingNoisy(true)
        .build()
        .apply {
            setMediaItem(item, startMs)
            prepare()
            playWhenReady = true
        }
}

@OptIn(UnstableApi::class)
private fun ExoPlayer.applyEnd(end: VideoEnd) {
    repeatMode = when (end) {
        VideoEnd.REPEAT_ONE -> Player.REPEAT_MODE_ONE
        VideoEnd.REPEAT_ALL -> Player.REPEAT_MODE_ALL
        VideoEnd.NEXT, VideoEnd.STOP -> Player.REPEAT_MODE_OFF
    }
    pauseAtEndOfMediaItems = end == VideoEnd.STOP
}

/**
 * Full-screen video player in the iOS style: controls float over the picture and fade away, the status and
 * navigation bars hide with them.
 *
 * Gestures (MX Player layout): tap shows or hides the controls; double tap on the left or right half seeks by the
 * chosen step; holding plays at 2x until released; a horizontal drag seeks live; a vertical drag on the left half
 * changes brightness, on the right half volume (up to 200% with a software boost); pinch zooms the picture.
 *
 * Also: the other videos of the folder as a playlist, what to do at the end, resume where each video was left,
 * picture modes, an A-B loop, a control lock, frame stepping with a fine scrub, speeds from 0.25x to 3x, and audio
 * and subtitle track selection with an adjustable subtitle size.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    file: File,
    siblings: Boolean,
    onFile: (File) -> Unit,
    onClose: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    val context = LocalContext.current
    val activity = remember { context.findActivity() }
    val view = LocalView.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val prefs = remember { AppPrefs(context) }
    val resume = remember { AppStores.videoResume(context) }
    val startMs = remember { resume.get(file.path) ?: 0L }
    val player = remember {
        buildPlayer(context, mediaItem(file, file.parentFile?.list()?.toList().orEmpty()), startMs).also { it.applyEnd(prefs.videoEnd) }
    }
    val brightness = remember { PlayerBrightness(activity) }
    val volume = remember { PlayerVolume(context, player) }
    val alive = remember { booleanArrayOf(true) }

    var current by remember { mutableStateOf(file) }
    var lastPosition by remember { mutableLongStateOf(startMs) }
    DisposableEffect(Unit) {
        val orientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose {
            alive[0] = false
            resume.put(current.path, player.currentPosition, player.duration)
            player.release()
            volume.release()
            brightness.restore()
            activity?.requestedOrientation = orientation
            activity?.setSystemBarsHidden(false)
        }
    }

    val seeker = remember { ScrubSeeker() }
    var playing by remember { mutableStateOf(true) }
    var ended by remember { mutableStateOf(false) }
    var duration by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(startMs) }
    var speed by remember { mutableFloatStateOf(1f) }
    var holdSpeed by remember { mutableStateOf<Float?>(null) } // the speed to go back to after a 2x hold
    var fps by remember { mutableFloatStateOf(30f) }
    var step by remember { mutableIntStateOf(prefs.videoStep.takeIf { it in STEPS } ?: 10) }
    var end by remember { mutableStateOf(prefs.videoEnd) }
    var fit by remember { mutableStateOf(prefs.videoFit) }
    var subtitleScale by remember { mutableFloatStateOf(prefs.subtitleScale) }
    var fine by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var scrub by remember { mutableStateOf<Scrub?>(null) }
    var scrubbing by remember { mutableStateOf(false) } // from the first touch until the exact final frame is shown
    var resumeAfterScrub by remember { mutableStateOf(false) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var videoSize by remember { mutableStateOf(VideoSize.UNKNOWN) }
    var hasPrev by remember { mutableStateOf(false) }
    var hasNext by remember { mutableStateOf(false) }
    var abA by remember { mutableStateOf<Long?>(null) }
    var abB by remember { mutableStateOf<Long?>(null) }
    var zoom by remember { mutableStateOf(ZoomMath.View()) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var bottomPanel by remember { mutableIntStateOf(0) } // height in px of the bottom controls, to keep subtitles above them
    var trackDialog by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var flash by remember { mutableStateOf<String?>(null) }
    var hud by remember { mutableStateOf<Hud?>(null) }
    var resumeOffer by remember { mutableStateOf(startMs > 0) }
    var oriented by remember { mutableStateOf(false) }
    var touched by remember { mutableIntStateOf(0) } // bumps on every interaction to restart the auto-hide timer
    val subtitleView = remember { SubtitleView(context) }

    fun refreshNeighbours() {
        hasPrev = player.hasPreviousMediaItem()
        hasNext = player.hasNextMediaItem()
    }

    // The rest of the folder joins the playlist once listed, around the playing video, without interrupting it.
    LaunchedEffect(Unit) {
        if (!siblings) return@LaunchedEffect
        val (all, names) = withContext(Dispatchers.IO) {
            runCatching { videoSiblings(file) to file.parentFile?.list()?.toList().orEmpty() }.getOrDefault(listOf(file) to emptyList())
        }
        val at = all.indexOfFirst { it.path == file.path }
        if (at < 0 || all.size < 2 || !alive[0]) return@LaunchedEffect
        val before = all.subList(0, at).map { mediaItem(it, names) }
        val after = all.subList(at + 1, all.size).map { mediaItem(it, names) }
        if (after.isNotEmpty()) player.addMediaItems(after)
        if (before.isNotEmpty()) player.addMediaItems(0, before)
        refreshNeighbours()
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (!isPlaying) resume.put(current.path, player.currentPosition, player.duration)
            }
            override fun onPlaybackStateChanged(state: Int) {
                ended = state == Player.STATE_ENDED
                if (player.duration != C.TIME_UNSET) duration = player.duration.coerceAtLeast(0)
                player.videoFormat?.frameRate?.takeIf { it > 0 }?.let { fps = it }
                // Audio-only media never renders a frame; a finished seek is all there is to wait for.
                if (state == Player.STATE_READY && player.videoFormat == null) seeker.seekDone()
                if (ended) resume.put(current.path, duration, duration)
            }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                val next = item?.mediaId?.let(::File) ?: return
                if (next.path == current.path) return
                // The video left behind: finished (forgotten) when playback ran into the next one, else kept where it was.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) resume.put(current.path, 0, 0) else resume.put(current.path, lastPosition, duration)
                current = next
                onFile(next)
                duration = 0
                abA = null
                abB = null
                zoom = ZoomMath.View()
                error = null
                val saved = resume.get(next.path)
                resumeOffer = saved != null
                if (saved != null) player.seekTo(saved)
                refreshNeighbours()
            }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = refreshNeighbours()
            override fun onVideoSizeChanged(size: VideoSize) { videoSize = size }
            override fun onRenderedFirstFrame() { seeker.seekDone() }
            override fun onTracksChanged(t: Tracks) { tracks = t }
            override fun onCues(cueGroup: CueGroup) { subtitleView.setCues(cueGroup.cues) }
            override fun onAudioSessionIdChanged(audioSessionId: Int) = volume.onSessionChanged()
            override fun onPlayerError(e: PlaybackException) { error = e.errorCodeName }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(player) {
        var lastSave = SystemClock.uptimeMillis()
        while (true) {
            val now = player.currentPosition.coerceAtLeast(0)
            if (!scrubbing) {
                position = now
                lastPosition = now
                VideoMath.abJump(now, abA, abB)?.let { player.seekTo(it) }
            }
            if (duration <= 0 && player.duration != C.TIME_UNSET) duration = player.duration.coerceAtLeast(0)
            if (playing && SystemClock.uptimeMillis() - lastSave > 5_000) {
                resume.put(current.path, now, duration)
                lastSave = SystemClock.uptimeMillis()
            }
            delay(if (playing) 50 else 250)
        }
    }

    // Landscape videos turn the screen once, when their size is first known; the rotate button overrides it.
    LaunchedEffect(videoSize) {
        if (oriented || videoSize.width <= 0 || videoSize.height <= 0) return@LaunchedEffect
        oriented = true
        if (videoSize.width * videoSize.pixelWidthHeightRatio > videoSize.height) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    LaunchedEffect(controls, playing, scrub, touched, locked) {
        if (controls && (playing || locked) && scrub == null) { delay(if (locked) 2_000 else 3_500); controls = false }
    }
    LaunchedEffect(controls, locked) { activity?.setSystemBarsHidden(!controls || locked) }
    LaunchedEffect(resumeOffer) { if (resumeOffer) { delay(5_000); resumeOffer = false } }
    LaunchedEffect(flash) { if (flash != null) { delay(700); flash = null } }
    DisposableEffect(playing) {
        view.keepScreenOn = playing
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(subtitleScale, controls, locked, bottomPanel, box) {
        subtitleView.setUserDefaultStyle()
        subtitleView.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * subtitleScale)
        // Lift the subtitles above the bottom controls while those are showing.
        val lifted = if (controls && !locked && box.height > 0) bottomPanel / box.height.toFloat() + 0.02f else 0f
        subtitleView.setBottomPaddingFraction(maxOf(SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION, lifted))
    }

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
        flash = (if (deltaMs < 0) "−" else "+") + (abs(deltaMs) / 1000) + "s"
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
    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
    }
    fun setFit(f: VideoFit) {
        fit = f
        prefs.videoFit = f
        zoom = ZoomMath.View()
    }
    fun setEnd(e: VideoEnd) {
        end = e
        prefs.videoEnd = e
        player.applyEnd(e)
    }
    fun rotate() {
        val a = activity ?: return
        val landscape = a.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        a.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        oriented = true
    }

    val fitLabels = mapOf(
        VideoFit.FIT to stringResource(R.string.vp_fit_fit),
        VideoFit.CROP to stringResource(R.string.vp_fit_crop),
        VideoFit.STRETCH to stringResource(R.string.vp_fit_stretch),
        VideoFit.RATIO_16_9 to "16:9",
        VideoFit.RATIO_4_3 to "4:3",
        VideoFit.ORIGINAL to stringResource(R.string.vp_fit_original),
    )
    val brightnessLabel = stringResource(R.string.vp_brightness)
    val volumeLabel = stringResource(R.string.vp_volume)
    val zoomLabel = stringResource(R.string.vp_zoom)
    val boostLabel = stringResource(R.string.vp_boost)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clipToBounds()
            .onSizeChanged { box = it },
        contentAlignment = Alignment.Center,
    ) {
        val (w, h) = VideoMath.fitSize(fit, videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio, box.width.toFloat(), box.height.toFloat())
        AndroidView(
            factory = { TextureView(it).also(player::setVideoTextureView) },
            modifier = with(density) { Modifier.requiredSize(w.toDp(), h.toDp()) }.graphicsLayer {
                scaleX = zoom.scale; scaleY = zoom.scale
                translationX = zoom.offsetX; translationY = zoom.offsetY
            },
        )
        AndroidView({ subtitleView }, Modifier.fillMaxSize())

        // Gesture layer. Taps, double taps and the 2x hold go through detectTapGestures; drags and pinches through
        // the second handler, which consumes the events so a drag never also counts as a tap.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(step, locked) {
                    detectTapGestures(
                        onTap = { controls = !controls; touched++ },
                        onDoubleTap = { pos ->
                            if (!locked) seekBy((if (pos.x < size.width / 2f) -1 else 1) * step * 1000L)
                            touched++
                        },
                        onLongPress = {
                            if (!locked && player.isPlaying) {
                                holdSpeed = speed
                                player.playbackParameters = PlaybackParameters(HOLD_SPEED)
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                        onPress = {
                            tryAwaitRelease()
                            holdSpeed?.let { player.playbackParameters = PlaybackParameters(it) }
                            holdSpeed = null
                        },
                    )
                }
                .pointerInput(locked, duration, fit) {
                    if (locked) return@pointerInput
                    val slop = viewConfiguration.touchSlop
                    // Drags starting at the very top or bottom belong to the system (notification shade, home).
                    val edge = 32.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (down.position.y < edge || down.position.y > size.height - edge) return@awaitEachGesture
                        var kind: DragKind? = null
                        var pinching = false
                        var dx = 0f
                        var dy = 0f
                        var seekBase = 0L
                        var seekTarget = 0L
                        var startLevel = 0f
                        do {
                            val event = awaitPointerEvent()
                            val fingers = event.changes.count { it.pressed && it.previousPressed }
                            if (fingers >= 2 && kind == null) {
                                pinching = true
                                val centroid = event.calculateCentroidSafe()
                                if (centroid != null) {
                                    zoom = ZoomMath.zoomAround(
                                        zoom, size.width.toFloat(), size.height.toFloat(), centroid.x, centroid.y,
                                        zoom.scale * event.calculateZoom(), event.calculatePan().x, event.calculatePan().y,
                                    )
                                    hud = Hud(Icons.Default.ZoomIn, zoomLabel + " " + VideoMath.percent(zoom.scale) + "%")
                                }
                                event.changes.forEach { it.consume() }
                            } else if (!pinching && fingers == 1) {
                                val change = event.changes.first { it.pressed }
                                dx += change.positionChange().x
                                dy += change.positionChange().y
                                if (kind == null) {
                                    kind = VideoMath.classify(dx, dy, slop, down.position.x, size.width.toFloat())
                                    when (kind) {
                                        DragKind.SEEK -> { seekBase = player.currentPosition; seekTarget = seekBase }
                                        DragKind.BRIGHTNESS -> startLevel = brightness.level()
                                        DragKind.VOLUME -> startLevel = volume.level()
                                        null -> Unit
                                    }
                                }
                                when (kind) {
                                    DragKind.SEEK -> {
                                        seekTarget = (seekBase + VideoMath.seekDelta(dx, size.width.toFloat(), duration)).coerceIn(0, duration.coerceAtLeast(0))
                                        scrubTo(seekTarget, 1f)
                                        val delta = seekTarget - seekBase
                                        hud = Hud(
                                            if (delta < 0) Icons.Default.Replay else Icons.Default.FastForward,
                                            (if (delta < 0) "−" else "+") + ScrubMath.format(abs(delta)) + "   " + ScrubMath.format(seekTarget) + " / " + ScrubMath.format(duration),
                                        )
                                    }
                                    DragKind.BRIGHTNESS -> {
                                        val level = VideoMath.dragLevel(startLevel, dy, size.height.toFloat())
                                        brightness.set(level)
                                        hud = Hud(Icons.Default.BrightnessMedium, brightnessLabel + " " + VideoMath.percent(level) + "%", level)
                                    }
                                    DragKind.VOLUME -> {
                                        val level = VideoMath.dragLevel(startLevel, dy, size.height.toFloat(), VideoMath.MAX_VOLUME)
                                        volume.set(level)
                                        val boosted = if (level > 1f) " · $boostLabel" else ""
                                        hud = Hud(Icons.AutoMirrored.Filled.VolumeUp, volumeLabel + " " + VideoMath.percent(level) + "%" + boosted, level / VideoMath.MAX_VOLUME)
                                    }
                                    null -> Unit
                                }
                                if (kind != null) change.consume()
                            }
                        } while (event.changes.any { it.pressed })
                        if (kind == DragKind.SEEK) scrubEnd(seekTarget)
                        if (kind != null || pinching) hud = null
                    }
                },
        )

        hud?.let { HudView(it, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = Space.xl)) }
        if (holdSpeed != null) HudView(Hud(Icons.Default.FastForward, "${HOLD_SPEED.toInt()}x"), Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = Space.xl))
        flash?.let {
            Surface(Modifier.align(Alignment.Center), shape = RoundedCornerShape(Radius.xl), color = CONTROL_TINT, contentColor = Color.White) {
                Text(it, Modifier.padding(horizontal = Space.lg, vertical = Space.sm), style = MaterialTheme.typography.titleMedium)
            }
        }
        error?.let {
            Surface(Modifier.align(Alignment.Center).padding(Space.xl), shape = RoundedCornerShape(Radius.xl), color = Color.Black.copy(alpha = 0.7f), contentColor = Color.White) {
                Text(stringResource(R.string.vp_error, it), Modifier.padding(Space.lg), style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Locked: only an unlock button, shown briefly after a tap.
        AnimatedVisibility(locked && controls, Modifier.align(Alignment.CenterStart).padding(start = Space.xl), enter = fadeIn(), exit = fadeOut()) {
            GlassCircle(Icons.Default.Lock, stringResource(R.string.vp_unlock), size = 52) { locked = false; touched++ }
        }

        val visible = (controls || scrub != null) && !locked
        // Top: close, title, the viewer's own actions, lock.
        AnimatedVisibility(visible, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(horizontal = Space.md, vertical = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassCircle(Icons.Default.Close, stringResource(R.string.back), onClick = onClose)
                Text(
                    current.name, Modifier.weight(1f).padding(horizontal = Space.md), color = Color.White,
                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                )
                GlassBar { actions() }
                Spacer(Modifier.width(Space.sm))
                GlassCircle(Icons.Default.LockOpen, stringResource(R.string.vp_lock)) { locked = true; touched++ }
            }
        }

        // Centre: previous, step back, play/pause, step forward, next.
        AnimatedVisibility(visible && scrub == null, Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.lg), verticalAlignment = Alignment.CenterVertically) {
                if (hasPrev || hasNext) CenterButton(Icons.Default.SkipPrevious, stringResource(R.string.vp_prev), 30, enabled = hasPrev) { player.seekToPreviousMediaItem(); touched++ }
                CenterButton(replayIcon(step), stringResource(R.string.vp_back_step, step), 36) { seekBy(-step * 1000L); touched++ }
                CenterButton(
                    if (ended) Icons.Default.Replay else if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    stringResource(if (playing) R.string.vp_pause else R.string.vp_play), 56, circle = true,
                ) { togglePlay(); touched++ }
                CenterButton(forwardIcon(step), stringResource(R.string.vp_fwd_step, step), 36) { seekBy(step * 1000L); touched++ }
                if (hasPrev || hasNext) CenterButton(Icons.Default.SkipNext, stringResource(R.string.vp_next), 30, enabled = hasNext) { player.seekToNextMediaItem(); touched++ }
            }
        }

        // Offer to start over when a video reopened where it was left.
        AnimatedVisibility(resumeOffer && !locked, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp), enter = fadeIn(), exit = fadeOut()) {
            Surface(shape = CircleShape, color = CONTROL_TINT, contentColor = Color.White, border = BorderStroke(Hairline, Color.White.copy(alpha = 0.15f))) {
                Row(Modifier.padding(start = Space.lg, end = Space.xs, top = Space.xs, bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.vp_resumed), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(Space.sm))
                    Pill(stringResource(R.string.vp_start_over), active = true) { seekTo(0); resumeOffer = false; touched++ }
                }
            }
        }

        // Bottom: scrub bar with elapsed and remaining time, then the option pills.
        AnimatedVisibility(visible, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { bottomPanel = it.height }
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                    .navigationBarsPadding()
                    .padding(horizontal = Space.md, vertical = Space.sm),
            ) {
                val s = scrub
                if (s != null && s.factor < 1f) {
                    Text(
                        stringResource(R.string.vp_scrub_speed, ScrubMath.factorLabel(s.factor)),
                        Modifier.align(Alignment.CenterHorizontally).padding(bottom = Space.xs), color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall,
                    )
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.xl + 4.dp))
                        .background(CONTROL_TINT)
                        .border(BorderStroke(Hairline, Color.White.copy(alpha = 0.12f)), RoundedCornerShape(Radius.xl + 4.dp))
                        .padding(horizontal = Space.md, vertical = Space.sm),
                ) {
                    val shown = s?.positionMs ?: position
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TimeText(ScrubMath.format(shown, tenths = fine || scrubbing))
                        ScrubBar(
                            position = shown,
                            duration = duration,
                            fine = fine,
                            ab = abA to abB,
                            onStart = { ms, f -> scrubTo(ms, f); touched++ },
                            onMove = { ms, f -> scrubTo(ms, f) },
                            onEnd = { ms -> scrubEnd(ms); touched++ },
                            currentPosition = { if (scrubbing) position else player.currentPosition },
                            modifier = Modifier.weight(1f).padding(horizontal = Space.sm),
                        )
                        TimeText(VideoMath.remaining(shown, duration))
                    }
                    if (fine) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            CenterButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.vp_frame_back), 28) { frameStep(false); touched++ }
                            Text(stringResource(R.string.vp_frame), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                            CenterButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.vp_frame_fwd), 28) { frameStep(true); touched++ }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = Space.xs),
                        horizontalArrangement = Arrangement.spacedBy(Space.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        var speedMenu by remember { mutableStateOf(false) }
                        Box {
                            Pill(if (speed == 1f) "1x" else "${speed}x", active = speed != 1f) { speedMenu = true; touched++ }
                            ChoiMenu(speedMenu, { speedMenu = false }) {
                                SPEEDS.forEach { v ->
                                    ChoiMenuItem(
                                        (if (v == 1f) "1x" else "${v}x") + if (v < 1f) "  " + stringResource(R.string.vp_slow) else "",
                                        { setSpeed(v); speedMenu = false },
                                        checked = v == speed,
                                    )
                                }
                            }
                        }
                        var fitMenu by remember { mutableStateOf(false) }
                        Box {
                            IconPill(Icons.Default.AspectRatio, fitLabels.getValue(fit), active = fit != VideoFit.FIT || zoom.scale > 1.01f) { fitMenu = true; touched++ }
                            ChoiMenu(fitMenu, { fitMenu = false }) {
                                VideoFit.entries.forEach { f ->
                                    ChoiMenuItem(fitLabels.getValue(f), { setFit(f); fitMenu = false }, checked = f == fit)
                                }
                            }
                        }
                        if (hasPickableTracks(tracks)) IconPill(Icons.Default.Subtitles, stringResource(R.string.vp_tracks)) { trackDialog = true; touched++ }
                        IconPill(
                            if (end == VideoEnd.REPEAT_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            endLabel(end), active = end == VideoEnd.REPEAT_ONE || end == VideoEnd.REPEAT_ALL,
                        ) { setEnd(VideoEnd.entries[(end.ordinal + 1) % VideoEnd.entries.size]); touched++ }
                        Pill(
                            when {
                                abA == null -> "A-B"
                                abB == null -> "A-"
                                else -> "A-B ✓"
                            },
                            active = abA != null,
                        ) {
                            val (a, b) = VideoMath.abNext(player.currentPosition, abA, abB)
                            abA = a
                            abB = b
                            touched++
                        }
                        Pill(stringResource(R.string.vp_fine), active = fine) { fine = !fine; touched++ }
                        var more by remember { mutableStateOf(false) }
                        Box {
                            IconPill(Icons.Default.MoreHoriz, stringResource(R.string.more)) { more = true; touched++ }
                            ChoiMenu(more, { more = false }) {
                                ChoiMenuItem(stringResource(R.string.vp_step, step), {
                                    step = STEPS[(STEPS.indexOf(step) + 1) % STEPS.size]
                                    prefs.videoStep = step
                                })
                                ChoiMenuItem(stringResource(R.string.vp_subtitle_size, VideoMath.percent(subtitleScale)), {
                                    subtitleScale = SUBTITLE_SCALES[(SUBTITLE_SCALES.indexOf(subtitleScale) + 1).mod(SUBTITLE_SCALES.size)]
                                    prefs.subtitleScale = subtitleScale
                                })
                                ChoiMenuItem(stringResource(R.string.vp_rotate), { rotate(); more = false }, icon = Icons.Default.ScreenRotation)
                                ChoiMenuDivider()
                                Text(
                                    stringResource(R.string.vp_gesture_help),
                                    Modifier.padding(horizontal = Space.lg, vertical = Space.sm),
                                    style = MaterialTheme.typography.bodySmall, color = ChoiTheme.colors.mutedForeground,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (trackDialog) TrackDialog(player, tracks) { trackDialog = false }
}

/** The centroid of the fingers still down, or null on the event that lifts the last one. */
private fun androidx.compose.ui.input.pointer.PointerEvent.calculateCentroidSafe(): Offset? {
    val down = changes.filter { it.pressed && it.previousPressed }
    if (down.isEmpty()) return null
    var x = 0f
    var y = 0f
    down.forEach { x += it.position.x; y += it.position.y }
    return Offset(x / down.size, y / down.size)
}

private fun replayIcon(step: Int) = when (step) {
    5 -> Icons.Default.Replay5
    30 -> Icons.Default.Replay30
    else -> Icons.Default.Replay10
}

private fun forwardIcon(step: Int) = when (step) {
    5 -> Icons.Default.Forward5
    30 -> Icons.Default.Forward30
    else -> Icons.Default.Forward10
}

@Composable
private fun endLabel(end: VideoEnd): String = stringResource(
    when (end) {
        VideoEnd.NEXT -> R.string.vp_end_next
        VideoEnd.REPEAT_ONE -> R.string.vp_end_repeat_one
        VideoEnd.REPEAT_ALL -> R.string.vp_end_repeat_all
        VideoEnd.STOP -> R.string.vp_end_stop
    },
)

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

/** Gesture feedback capsule: icon, text and, for levels, a thin bar. */
@Composable
private fun HudView(hud: Hud, modifier: Modifier = Modifier) {
    Surface(modifier, shape = CircleShape, color = CONTROL_TINT, contentColor = Color.White, border = BorderStroke(Hairline, Color.White.copy(alpha = 0.15f))) {
        Row(Modifier.padding(horizontal = Space.lg, vertical = Space.sm), verticalAlignment = Alignment.CenterVertically) {
            Icon(hud.icon, null, Modifier.size(IconSize.md))
            Spacer(Modifier.width(Space.sm))
            hud.fraction?.let { f ->
                Canvas(Modifier.width(96.dp).height(4.dp)) {
                    drawRoundRect(Color.White.copy(alpha = 0.25f), cornerRadius = CornerRadius(size.height / 2))
                    drawRoundRect(Color.White, size = Size(size.width * f.coerceIn(0f, 1f), size.height), cornerRadius = CornerRadius(size.height / 2))
                }
                Spacer(Modifier.width(Space.sm))
            }
            Text(hud.text, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun TimeText(text: String) {
    Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
}

/** Round translucent button for the top bar. */
@Composable
private fun GlassCircle(icon: ImageVector, label: String, size: Int = 40, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(CONTROL_TINT)
            .border(BorderStroke(Hairline, Color.White.copy(alpha = 0.15f)), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(IconSize.md), tint = Color.White)
    }
}

/** Capsule holding the viewer's own action buttons (open with, share). */
@Composable
private fun GlassBar(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(CONTROL_TINT)
            .border(BorderStroke(Hairline, Color.White.copy(alpha = 0.15f)), CircleShape),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Big white glyph in the middle of the screen; [circle] gives it the round backdrop of the play button. */
@Composable
private fun CenterButton(icon: ImageVector, label: String, size: Int, enabled: Boolean = true, circle: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size((size + 20).dp)
            .clip(CircleShape)
            .background(if (circle) CONTROL_TINT else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(size.dp), tint = Color.White.copy(alpha = if (enabled) 1f else 0.35f))
    }
}

@Composable
private fun Pill(text: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (active) Color.White else Color.White.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = Space.md, vertical = Space.xs + 2.dp),
    ) {
        Text(text, color = if (active) Color.Black else Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun IconPill(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (active) Color.White else Color.White.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = Space.md, vertical = Space.xs + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (active) Color.Black else Color.White
        Icon(icon, null, Modifier.size(IconSize.sm), tint = tint)
        Spacer(Modifier.width(Space.xs))
        Text(label, color = tint, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * Seek bar. Touching jumps to that point (in fine mode it keeps the current position instead); dragging moves
 * proportionally, scaled by [ScrubMath.factor] of the finger's vertical distance from the bar. The bar thickens
 * while held, like the iOS player, and marks an A-B loop.
 */
@Composable
private fun ScrubBar(
    position: Long,
    duration: Long,
    fine: Boolean,
    ab: Pair<Long?, Long?>,
    onStart: (Long, Float) -> Unit,
    onMove: (Long, Float) -> Unit,
    onEnd: (Long) -> Unit,
    currentPosition: () -> Long,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var held by remember { mutableStateOf(false) }
    Box(
        modifier
            .height(36.dp)
            .pointerInput(duration, fine) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val width = size.width.toFloat()
                    var pos = if (fine) currentPosition() else ScrubMath.positionAt(down.position.x, width, duration)
                    var factor = ScrubMath.factor(0f, fine)
                    var lastX = down.position.x
                    down.consume()
                    held = true
                    onStart(pos, factor)
                    drag(down.id) { change ->
                        val awayDp = with(density) { (change.position.y - size.height / 2f).toDp().value }
                        factor = ScrubMath.factor(awayDp, fine)
                        pos = ScrubMath.advance(pos, change.position.x - lastX, width, duration, factor)
                        lastX = change.position.x
                        change.consume()
                        onMove(pos, factor)
                    }
                    held = false
                    onEnd(pos)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(16.dp)) {
            val h = (if (held) 10.dp else 5.dp).toPx()
            val y = size.height / 2
            val r = CornerRadius(h / 2)
            fun x(ms: Long) = if (duration > 0) size.width * (ms / duration.toFloat()).coerceIn(0f, 1f) else 0f
            drawRoundRect(Color.White.copy(alpha = 0.28f), Offset(0f, y - h / 2), Size(size.width, h), r)
            val (a, b) = ab
            if (a != null) {
                val end = b?.let(::x) ?: x(position).coerceAtLeast(x(a))
                drawRoundRect(Color(0xFFFFD60A).copy(alpha = 0.55f), Offset(x(a), y - h / 2), Size((end - x(a)).coerceAtLeast(2f), h), r)
            }
            drawRoundRect(Color.White, Offset(0f, y - h / 2), Size(x(position), h), r)
        }
    }
}
