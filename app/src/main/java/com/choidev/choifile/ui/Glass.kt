package com.choidev.choifile.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

/** The content layer that glass bars blur. [origin] is where that content sits in the window. */
@Stable
class GlassBackdrop(val layer: GraphicsLayer) {
    var origin by mutableStateOf(Offset.Zero)
}

enum class GlassEdge { TOP, BOTTOM }

/**
 * Translucent bar with a hairline on the edge facing the content ([edge] = the side the bar is docked to).
 * On Android 12+ a lightly blurred copy of the backdrop shows through a mostly opaque tint, so the glass
 * effect stays a subtle hint that fits the flat shadcn look; below 12 the tint is simply opaque.
 */
@Composable
fun GlassSurface(backdrop: GlassBackdrop, modifier: Modifier = Modifier, edge: GlassEdge = GlassEdge.TOP, content: @Composable () -> Unit) {
    var position by remember { mutableStateOf(Offset.Zero) }
    val blurred = Build.VERSION.SDK_INT >= 31
    val colors = ChoiTheme.colors
    val tint = colors.background.copy(alpha = if (blurred) 0.82f else 1f)
    val line = colors.border
    Box(
        modifier
            .onGloballyPositioned { position = it.positionInRoot() }
            .drawWithContent {
                drawContent()
                val y = if (edge == GlassEdge.TOP) size.height - Hairline.toPx() / 2 else Hairline.toPx() / 2
                drawLine(line, Offset(0f, y), Offset(size.width, y), Hairline.toPx())
            },
    ) {
        if (blurred) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        clip = true
                        renderEffect = BlurEffect(28f, 28f, TileMode.Clamp)
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
