package com.choidev.choifile.playback

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.choidev.choifile.util.FileKind
import com.choidev.choifile.util.NaturalOrder
import com.choidev.choifile.util.kindOf
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Audio files next to [file] (same folder, natural order). Hidden files only when the opened one is hidden. */
fun audioSiblings(file: File): List<File> {
    val dir = file.parentFile ?: return listOf(file)
    val showHidden = file.name.startsWith(".")
    val list = dir.listFiles()?.filter { it.isFile && kindOf(it) == FileKind.AUDIO && (showHidden || !it.name.startsWith(".")) }.orEmpty()
    val sorted = list.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
    return if (sorted.any { it.path == file.path }) sorted else listOf(file)
}

/**
 * The app's one music player. It lives as long as the process, so music keeps playing while the user browses
 * files or leaves the app; the full-screen player and the mini player in the file list only show and steer it.
 * A Media3 session gives it the media notification, lock screen and headset controls. Main thread only.
 */
@OptIn(UnstableApi::class)
object AudioPlayback {
    enum class Repeat { OFF, ALL, ONE }

    data class State(
        val queue: List<File> = emptyList(),
        val index: Int = -1,
        val playing: Boolean = false,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        /** Embedded cover art (ID3 APIC, MP4 covr, FLAC picture), or null. */
        val artwork: ByteArray? = null,
        val durationMs: Long = 0,
        val shuffle: Boolean = false,
        val repeat: Repeat = Repeat.OFF,
        val speed: Float = 1f,
        val error: String? = null,
        /** Uptime at which the sleep timer pauses playback, or null. */
        val sleepAt: Long? = null,
        val sleepAtEnd: Boolean = false,
    ) {
        val current: File? get() = queue.getOrNull(index)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sleepJob: Job? = null

    val positionMs: Long get() = player?.currentPosition?.coerceAtLeast(0) ?: 0

    private fun build(context: Context): ExoPlayer {
        val renderers = NextRenderersFactory(context).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val audio = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
        return ExoPlayer.Builder(context, renderers)
            .setAudioAttributes(audio, true)
            .setHandleAudioBecomingNoisy(true)
            // Keeps the CPU awake while the screen is off, so a long playlist does not stall.
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .also { p -> p.addListener(listener) }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
        override fun onPlayerError(error: PlaybackException) {
            _state.value = _state.value.copy(error = error.errorCodeName)
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (_state.value.sleepAtEnd && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                player?.pauseAtEndOfMediaItems = false
                _state.value = _state.value.copy(sleepAtEnd = false)
            }
        }
    }

    private fun refresh() {
        val p = player ?: return
        val meta = p.mediaMetadata
        val index = p.currentMediaItemIndex
        val queue = _state.value.queue
        _state.value = _state.value.copy(
            index = if (p.mediaItemCount == 0) -1 else index,
            playing = p.isPlaying,
            title = meta.title?.toString()?.takeIf { it.isNotBlank() } ?: queue.getOrNull(index)?.nameWithoutExtension,
            artist = (meta.artist ?: meta.albumArtist)?.toString()?.takeIf { it.isNotBlank() },
            album = meta.albumTitle?.toString()?.takeIf { it.isNotBlank() },
            artwork = meta.artworkData,
            durationMs = p.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0,
            shuffle = p.shuffleModeEnabled,
            speed = p.playbackParameters.speed,
        )
    }

    // No title here: one set on the item would hide the file's own tags. refresh() falls back to the file name.
    private fun item(file: File) = MediaItem.Builder().setUri(Uri.fromFile(file)).setMediaId(file.path).build()

    /** Plays [file] with [queue] (by default the audio files of its folder) as the play list. */
    fun play(context: Context, file: File, queue: List<File> = audioSiblings(file)) {
        val app = context.applicationContext
        appContext = app
        val p = player ?: build(app).also { player = it }
        if (session == null) {
            session = MediaSession.Builder(app, p).setId("choifile-music").setSessionActivity(PlaybackService.sessionActivity(app)).build()
                .also { PlaybackSessions.add(app, it) }
        }
        val start = queue.indexOfFirst { it.path == file.path }.coerceAtLeast(0)
        _state.value = _state.value.copy(queue = queue, index = start, error = null)
        p.setMediaItems(queue.map(::item), start, 0)
        p.prepare()
        p.play()
        refresh()
    }

    fun toggle() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else {
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0, 0)
            p.play()
        }
    }

    fun pause() { player?.pause() }

    fun next() { player?.takeIf { it.hasNextMediaItem() }?.seekToNextMediaItem() }

    /** Like every music app: back to the start of the song, or to the previous song when already near its start. */
    fun previous() {
        val p = player ?: return
        if (p.currentPosition > 3_000 || !p.hasPreviousMediaItem()) p.seekTo(0) else p.seekToPreviousMediaItem()
    }

    fun seekTo(ms: Long) { player?.seekTo(ms.coerceAtLeast(0)) }

    fun skipTo(index: Int) {
        val p = player ?: return
        if (index in 0 until p.mediaItemCount) {
            p.seekTo(index, 0)
            p.play()
        }
    }

    fun setShuffle(on: Boolean) { player?.shuffleModeEnabled = on }

    fun cycleRepeat() {
        val next = when (_state.value.repeat) {
            Repeat.OFF -> Repeat.ALL
            Repeat.ALL -> Repeat.ONE
            Repeat.ONE -> Repeat.OFF
        }
        player?.repeatMode = when (next) {
            Repeat.OFF -> Player.REPEAT_MODE_OFF
            Repeat.ALL -> Player.REPEAT_MODE_ALL
            Repeat.ONE -> Player.REPEAT_MODE_ONE
        }
        _state.value = _state.value.copy(repeat = next)
    }

    fun setSpeed(speed: Float) { player?.playbackParameters = PlaybackParameters(speed) }

    /** Pauses after [minutes]; null cancels the timer. */
    fun setSleep(minutes: Int?) {
        sleepJob?.cancel()
        player?.pauseAtEndOfMediaItems = false
        val at = minutes?.let { SystemClock.uptimeMillis() + it * 60_000L }
        _state.value = _state.value.copy(sleepAt = at, sleepAtEnd = false)
        if (at != null) {
            sleepJob = scope.launch {
                delay(at - SystemClock.uptimeMillis())
                player?.pause()
                _state.value = _state.value.copy(sleepAt = null)
            }
        }
    }

    /** Pauses when the current song ends. */
    fun setSleepAtEnd() {
        sleepJob?.cancel()
        player?.pauseAtEndOfMediaItems = true
        _state.value = _state.value.copy(sleepAt = null, sleepAtEnd = true)
    }

    /** Stops the music and lets go of everything: no notification, no mini player. */
    fun stop() {
        sleepJob?.cancel()
        session?.let(PlaybackSessions::remove)
        session = null
        player?.removeListener(listener)
        player?.release()
        player = null
        _state.value = State()
    }
}
