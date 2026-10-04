package com.choidev.choifile.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext

@Composable
fun ChoiTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** The content layer that glass bars blur. [origin] is where that content sits in the window. */
@Stable
class GlassBackdrop(val layer: GraphicsLayer) {
    var origin by mutableStateOf(Offset.Zero)
}

/**
 * Translucent bar. On Android 12+ it draws a blurred copy of the backdrop layer behind a tint;
 * below that it falls back to a nearly opaque tint (no blur available).
 */
@Composable
fun GlassSurface(backdrop: GlassBackdrop, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var position by remember { mutableStateOf(Offset.Zero) }
    val blurred = Build.VERSION.SDK_INT >= 31
    val tint = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = if (blurred) 0.6f else 0.95f)
    Box(modifier.onGloballyPositioned { position = it.positionInRoot() }) {
        if (blurred) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        clip = true
                        renderEffect = BlurEffect(48f, 48f, TileMode.Clamp)
                    }
                    .drawBehind {
                        translate(backdrop.origin.x - position.x, backdrop.origin.y - position.y) {
                            drawLayer(backdrop.layer)
                        }
                    },
            )
        }
        Box(Modifier.matchParentSize().background(tint))
        content()
    }
}
