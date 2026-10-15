package com.choidev.choifile.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.provider.Settings
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.exoplayer.ExoPlayer
import com.choidev.choifile.util.VideoMath

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Screen brightness for this window only (no system setting is changed, no permission needed). The window goes
 * back to following the system brightness when the player closes.
 */
class PlayerBrightness(private val activity: Activity?) {
    private val original = activity?.window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    /** Current level 0..1; the system brightness until the user first changes it. */
    fun level(): Float {
        val a = activity ?: return 0.5f
        val own = a.window.attributes.screenBrightness
        if (own >= 0f) return own
        val system = runCatching { Settings.System.getInt(a.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)
        return (system / 255f).coerceIn(0f, 1f)
    }

    fun set(level: Float) {
        val window = activity?.window ?: return
        window.attributes = window.attributes.apply { screenBrightness = level.coerceIn(0.01f, 1f) }
    }

    fun restore() {
        val window = activity?.window ?: return
        window.attributes = window.attributes.apply { screenBrightness = original }
    }
}

/**
 * Media volume 0..[VideoMath.MAX_VOLUME]: up to 1 it is the system media volume, above that a LoudnessEnhancer on
 * the player's audio session boosts quiet videos up to +10 dB. The boost is dropped when the player closes.
 */
class PlayerVolume(context: Context, private val player: () -> ExoPlayer) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val maxSteps = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15
    private var enhancer: LoudnessEnhancer? = null
    private var enhancerSession = 0
    private var boostMb = 0

    fun level(): Float {
        val steps = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: return 1f
        // The boost only counts while the system volume is still at its maximum (a hardware key may have lowered it).
        val boost = if (steps >= maxSteps) boostMb / 1000f else 0f
        return steps.toFloat() / maxSteps + boost
    }

    fun set(level: Float) {
        audio?.setStreamVolume(AudioManager.STREAM_MUSIC, VideoMath.volumeSteps(level, maxSteps), 0)
        applyBoost(VideoMath.boostMillibels(level))
    }

    private fun applyBoost(mb: Int) {
        boostMb = mb
        if (mb == 0 && enhancer == null) return
        runCatching {
            val session = player().audioSessionId
            if (enhancer == null || enhancerSession != session) {
                enhancer?.release()
                enhancer = LoudnessEnhancer(session)
                enhancerSession = session
            }
            enhancer?.setTargetGain(mb)
            enhancer?.enabled = mb > 0
        }.onFailure {
            // Some devices refuse audio effects; the system volume still works, only the boost is lost.
            enhancer = null
            boostMb = 0
        }
    }

    /** The player switched audio sessions (new decoder); move the boost over. */
    fun onSessionChanged() {
        if (boostMb > 0) applyBoost(boostMb)
    }

    fun release() {
        runCatching { enhancer?.release() }
        enhancer = null
    }
}

/** Hides or shows the status and navigation bars; a swipe from the edge brings them back briefly. */
fun Activity.setSystemBarsHidden(hidden: Boolean) {
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    if (hidden) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
}
