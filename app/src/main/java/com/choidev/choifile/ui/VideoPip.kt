package com.choidev.choifile.ui

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import com.choidev.choifile.R

/**
 * Picture-in-picture for the video player: the window parameters (aspect ratio, the three buttons in the small
 * window) and what [com.choidev.choifile.MainActivity] needs to enter PiP itself below Android 12, where the
 * system cannot do it automatically.
 */
object VideoPip {
    const val ACTION = "com.choidev.choifile.PIP_CONTROL"
    const val EXTRA = "command"
    const val PLAY_PAUSE = 1
    const val BACK = 2
    const val FORWARD = 3

    /** Set while a video plays in the viewer; read by MainActivity.onUserLeaveHint below Android 12. */
    @Volatile var autoEnter: Boolean = false
    @Volatile var lastParams: PictureInPictureParams? = null

    /** PiP accepts aspect ratios between 1:2.39 and 2.39:1 only; anything outside is clamped to the edge. */
    fun aspect(width: Int, height: Int): Rational? {
        if (width <= 0 || height <= 0) return null
        val r = width.toFloat() / height
        return when {
            r > 2.39f -> Rational(239, 100)
            r < 1f / 2.39f -> Rational(100, 239)
            else -> Rational(width, height)
        }
    }

    fun params(context: Context, aspect: Rational?, source: Rect?, playing: Boolean): PictureInPictureParams {
        fun action(cmd: Int, icon: Int, title: Int) = RemoteAction(
            Icon.createWithResource(context, icon), context.getString(title), context.getString(title),
            PendingIntent.getBroadcast(
                context, cmd, Intent(ACTION).setPackage(context.packageName).putExtra(EXTRA, cmd),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        val b = PictureInPictureParams.Builder()
            .setActions(
                listOf(
                    action(BACK, R.drawable.ic_pip_replay, R.string.vp_pip_back),
                    if (playing) action(PLAY_PAUSE, R.drawable.ic_pip_pause, R.string.vp_pause) else action(PLAY_PAUSE, R.drawable.ic_pip_play, R.string.vp_play),
                    action(FORWARD, R.drawable.ic_pip_forward, R.string.vp_pip_forward),
                ),
            )
        aspect?.let(b::setAspectRatio)
        source?.let(b::setSourceRectHint)
        if (Build.VERSION.SDK_INT >= 31) {
            b.setAutoEnterEnabled(playing)
            b.setSeamlessResizeEnabled(true)
        }
        return b.build()
    }

    /** Leaves the viewer's settings behind: no automatic PiP once the player is gone. */
    fun reset(activity: Activity?) {
        autoEnter = false
        lastParams = null
        if (Build.VERSION.SDK_INT >= 31) runCatching { activity?.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build()) }
    }

    fun enter(activity: Activity?): Boolean {
        val p = lastParams ?: return false
        return runCatching { activity?.enterPictureInPictureMode(p) == true }.getOrDefault(false)
    }
}

/** Whether the activity is currently shown as a picture-in-picture window. */
@Composable
fun rememberIsInPip(): Boolean {
    val activity = LocalContext.current.findActivity() as? ComponentActivity ?: return false
    var pip by remember { mutableStateOf(activity.isInPictureInPictureMode) }
    DisposableEffect(activity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { pip = it.isInPictureInPictureMode }
        activity.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return pip
}
