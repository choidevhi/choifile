package com.choidev.choifile.ebook.reader

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 종이 모서리를 손가락(touch) 쪽으로 접어 넘기는 효과.
 *
 * 접힌 선은 원래 모서리(corner)와 손가락 위치를 잇는 선분의 수직이등분선이다.
 * 선의 모서리 쪽 부분이 접혀서 선 반대편으로 뒤집혀 보이고(뒷면), 그 자리에는 다음 쪽이 드러난다.
 */
class PageCurl(private val density: Float) {

    private val frontPath = Path()
    private val flapSrcPath = Path()
    private val flapPath = Path()
    private val reflect = Matrix()
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flapPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pts = FloatArray(16)
    private val outA = FloatArray(16)
    private val outB = FloatArray(16)
    private val matrixValues = FloatArray(9)
    private val shaderMatrix = Matrix()

    // 그라데이션은 (0,0)→(1,0) 단위 길이로 한 번만 만들고, 프레임마다 행렬로 위치·방향·길이만 바꾼다.
    private val foldShadow = LinearGradient(
        0f, 0f, 1f, 0f,
        intArrayOf(0x66000000, 0x22000000, 0x00000000), floatArrayOf(0f, 0.35f, 1f),
        Shader.TileMode.CLAMP,
    )
    private val flapShade = LinearGradient(
        0f, 0f, 1f, 0f,
        intArrayOf(0x30000000, 0x00000000, 0x14FFFFFF, 0x1A000000), floatArrayOf(0f, 0.12f, 0.35f, 1f),
        Shader.TileMode.CLAMP,
    )

    fun draw(
        canvas: Canvas,
        width: Float,
        height: Float,
        touchX: Float,
        touchY: Float,
        cornerY: Float,
        paperColor: Int,
        drawFront: (Canvas) -> Unit,
        drawBack: (Canvas) -> Unit,
        drawUnder: (Canvas) -> Unit,
    ) {
        val cx = width
        val dx = cx - touchX
        val dy = cornerY - touchY
        val dist = hypot(dx, dy)
        if (dist < 1f) {
            drawFront(canvas)
            return
        }

        val nx = dx / dist
        val ny = dy / dist
        val mx = (cx + touchX) / 2f
        val my = (cornerY + touchY) / 2f
        val k = mx * nx + my * ny

        pts[0] = 0f; pts[1] = 0f
        pts[2] = width; pts[3] = 0f
        pts[4] = width; pts[5] = height
        pts[6] = 0f; pts[7] = height

        val frontN = clip(pts, 4, nx, ny, k, keepCornerSide = false, out = outA)
        val flapN = clip(pts, 4, nx, ny, k, keepCornerSide = true, out = outB)
        toPath(frontPath, outA, frontN)
        toPath(flapSrcPath, outB, flapN)

        // 접힌 선을 거울로 삼는 반사 변환.
        matrixValues[0] = 1 - 2 * nx * nx
        matrixValues[1] = -2 * nx * ny
        matrixValues[2] = 2 * k * nx
        matrixValues[3] = -2 * nx * ny
        matrixValues[4] = 1 - 2 * ny * ny
        matrixValues[5] = 2 * k * ny
        matrixValues[6] = 0f
        matrixValues[7] = 0f
        matrixValues[8] = 1f
        reflect.setValues(matrixValues)
        flapSrcPath.transform(reflect, flapPath)

        // 1. 드러나는 다음 쪽.
        drawUnder(canvas)

        // 그림자 진하기. 종이가 다 넘어가 책등에 붙을수록(아직 안 넘어간 면이 작을수록) 옅어져서,
        // 넘김이 끝나는 순간 그림자가 남았다가 한 번에 사라지지 않는다. 이전 쪽을 꺼낼 때는 반대로 서서히 짙어진다.
        val frontShare = (polygonArea(outA, frontN) / (width * height) / FADE_SHARE).coerceIn(0f, 1f)
        val strength = frontShare * frontShare * (3f - 2f * frontShare)

        // 2. 접힌 선 아래로 떨어지는 그림자(다음 쪽 위).
        val shadowLen = (dist * 0.22f).coerceIn(6f * density, 44f * density)
        canvas.save()
        canvas.clipPath(flapSrcPath)
        placeGradient(foldShadow, mx, my, nx, ny, shadowLen)
        shadePaint.alpha = (255 * strength).toInt()
        canvas.drawPaint(shadePaint)
        shadePaint.alpha = 255
        canvas.restore()

        // 3. 아직 넘어가지 않은 앞면.
        canvas.save()
        canvas.clipPath(frontPath)
        drawFront(canvas)
        canvas.restore()

        if (flapN < 3) return

        // 4. 뒤집힌 종이 조각이 앞면에 드리우는 그림자. 번지는 그림자(blur)는 매 프레임 비싸서,
        //    조각을 바깥쪽으로 조금씩 밀어 옅은 색을 겹쳐 칠하는 것으로 대신한다.
        canvas.save()
        canvas.clipPath(frontPath)
        for (i in ShadowSteps.indices) {
            val d = ShadowSteps[i] * density
            canvas.save()
            canvas.translate(-nx * d, -ny * d)
            flapPaint.color = ShadowColors[i]
            flapPaint.alpha = (flapPaint.alpha * strength).toInt()
            canvas.drawPath(flapPath, flapPaint)
            canvas.restore()
        }
        canvas.restore()

        // 5. 뒤집힌 종이 조각(뒷면).
        flapPaint.color = ColorUtils.blendARGB(paperColor, 0xFF000000.toInt(), 0.05f)
        canvas.drawPath(flapPath, flapPaint)

        canvas.save()
        canvas.clipPath(flapPath)
        canvas.concat(reflect)
        drawBack(canvas)
        canvas.restore()

        // 6. 종이가 휘어 보이도록 뒷면에 명암을 준다. 접힌 선 근처는 밝고, 끝으로 갈수록 살짝 어둡다.
        val depth = dist / 2f
        canvas.save()
        canvas.clipPath(flapPath)
        placeGradient(flapShade, mx, my, -nx, -ny, depth)
        canvas.drawPaint(shadePaint)
        canvas.restore()
    }

    /** 단위 그라데이션을 (x, y)에서 (dirX, dirY) 방향으로 length만큼 뻗게 놓는다. */
    private fun placeGradient(shader: LinearGradient, x: Float, y: Float, dirX: Float, dirY: Float, length: Float) {
        shaderMatrix.setScale(length, length)
        shaderMatrix.postRotate(Math.toDegrees(atan2(dirY, dirX).toDouble()).toFloat())
        shaderMatrix.postTranslate(x, y)
        shader.setLocalMatrix(shaderMatrix)
        shadePaint.shader = shader
    }

    /** 다각형 넓이(신발끈 공식). */
    private fun polygonArea(p: FloatArray, n: Int): Float {
        if (n < 3) return 0f

        var sum = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            sum += p[i * 2] * p[j * 2 + 1] - p[j * 2] * p[i * 2 + 1]
        }

        return abs(sum) / 2f
    }

    private companion object {
        // 앞면이 화면의 이 비율보다 작아지면 그림자를 줄이기 시작한다.
        const val FADE_SHARE = 0.12f

        val ShadowSteps = floatArrayOf(12f, 9f, 6.5f, 4.5f, 2.5f, 1f)
        val ShadowColors = intArrayOf(0x05000000, 0x06000000, 0x07000000, 0x08000000, 0x0A000000, 0x0C000000)
    }

    /** 다각형을 직선 한쪽만 남기고 자른다(Sutherland–Hodgman, 평면 하나). 꼭짓점 수를 돌려준다. */
    private fun clip(src: FloatArray, n: Int, nx: Float, ny: Float, k: Float, keepCornerSide: Boolean, out: FloatArray): Int {
        var count = 0
        val sign = if (keepCornerSide) 1f else -1f

        for (i in 0 until n) {
            val ax = src[i * 2]
            val ay = src[i * 2 + 1]
            val j = (i + 1) % n
            val bx = src[j * 2]
            val by = src[j * 2 + 1]
            val sa = (ax * nx + ay * ny - k) * sign
            val sb = (bx * nx + by * ny - k) * sign

            if (sa >= 0f) {
                out[count * 2] = ax
                out[count * 2 + 1] = ay
                count++
            }

            if ((sa >= 0f) != (sb >= 0f)) {
                val t = sa / (sa - sb)
                out[count * 2] = ax + (bx - ax) * t
                out[count * 2 + 1] = ay + (by - ay) * t
                count++
            }
        }

        return count
    }

    private fun toPath(path: Path, p: FloatArray, n: Int) {
        path.reset()
        if (n < 3) return

        path.moveTo(p[0], p[1])
        for (i in 1 until n) path.lineTo(p[i * 2], p[i * 2 + 1])
        path.close()
    }
}
