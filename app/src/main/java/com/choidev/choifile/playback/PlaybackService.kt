package com.choidev.choifile.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.choidev.choifile.MainActivity

/**
 * Keeps sound going outside the app: the media notification, lock screen and headset controls for whichever
 * player registered a session in [PlaybackSessions] (the music player, or a video the user lets keep playing).
 * Media3 moves the service in and out of the foreground as playback starts and stops.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    override fun onCreate() {
        super.onCreate()
        PlaybackSessions.service = this
        PlaybackSessions.all().forEach(::addIfMissing)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        PlaybackSessions.all().forEach(::addIfMissing)
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = PlaybackSessions.active()

    /** The app was swiped away: keep going only while something actually plays. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (PlaybackSessions.all().none { it.player.playWhenReady && it.player.playbackState != Player.STATE_ENDED }) stopSelf()
    }

    override fun onDestroy() {
        if (PlaybackSessions.service === this) PlaybackSessions.service = null
        super.onDestroy()
    }

    fun addIfMissing(session: MediaSession) {
        if (!isSessionAdded(session)) addSession(session)
    }

    companion object {
        fun start(context: Context) {
            runCatching { context.startService(Intent(context, PlaybackService::class.java)) }
        }

        /** Opens the app (where the player is) when the notification is tapped. */
        fun sessionActivity(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** The sessions the service shows, newest last. Main thread only. */
@OptIn(UnstableApi::class)
object PlaybackSessions {
    private val sessions = LinkedHashSet<MediaSession>()
    var service: PlaybackService? = null

    fun all(): List<MediaSession> = sessions.toList()

    /** The session for the notification: one that is playing, else the newest. */
    fun active(): MediaSession? = sessions.lastOrNull { it.player.isPlaying } ?: sessions.lastOrNull()

    fun add(context: Context, session: MediaSession) {
        sessions.add(session)
        service?.addIfMissing(session) ?: PlaybackService.start(context)
    }

    /** Takes [session] off the notification and releases it; the service ends when nothing is left. */
    fun remove(session: MediaSession) {
        sessions.remove(session)
        service?.let { s ->
            runCatching { s.removeSession(session) }
            if (sessions.isEmpty()) s.stopSelf()
        }
        session.release()
    }
}
