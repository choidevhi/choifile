package com.choidev.choifile.ebook.reader

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.choidev.choifile.ebook.data.TurnMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

enum class TurnDir { NEXT, PREV }

data class Turn(val dir: TurnDir, val cornerY: Float)

/**
 * 쪽 넘김의 진행 상태. touch는 접히는 종이 모서리의 위치다.
 * 다음 쪽: 모서리가 (W, y)에서 (-W, y)로 간다. 이전 쪽: 반대로 (-W, y)에서 (W, y)로 펼쳐진다.
 * 슬라이드 모드도 같은 값을 쓰고, 쪽의 가로 이동량을 (touch.x - W) / 2로 계산한다.
 */
@Stable
class PageTurner(private val scope: CoroutineScope) {

    var turn by mutableStateOf<Turn?>(null)
        private set
    var touch by mutableStateOf(Offset.Zero)
        private set

    var width = 1f
    var height = 1f
    var mode = TurnMode.CURL
    var canTurn: (TurnDir) -> Boolean = { false }
    var onCommit: (TurnDir) -> Unit = {}

    /** 넘김이 확정된 순간(소리 낼 때). 한 번 넘길 때 한 번만 부른다. */
    var onTurnStart: () -> Unit = {}

    private var job: Job? = null
    private var pendingCommit = false
    private var lastTarget = Offset.Zero
    private var dragStart = Offset.Zero
    private var touchStart = Offset.Zero
    private var announced = false

    // 넘기는 도중 들어온 탭·볼륨 키. 지금 넘김이 끝나면 차례로 이어서 넘긴다(중간에 끊고 건너뛰지 않는다).
    private val queued = ArrayDeque<TurnDir>()

    // 잡기 직전 종이 모서리의 속도. 톡 건드리고 놓으면 이 속도로 이어 가서 멈칫하지 않는다.
    private var lastVelocity = Offset.Zero

    val isAnimating: Boolean get() = job?.isActive == true

    // 슬라이드는 쪽이 손가락을 그대로 따라오게 2배(이동량이 touch의 절반이라서), 책넘김은 조금 덜 민감하게.
    private val factor: Float get() = if (mode == TurnMode.SLIDE) 2f else 1.6f

    /** 진행 중이던 넘김을 바로 끝낸다. 새로 드래그를 시작할 때만 쓴다. */
    fun finishNow() {
        queued.clear()
        val t = turn ?: return
        job?.cancel()
        job = null
        if (pendingCommit) onCommit(t.dir)
        pendingCommit = false
        turn = null
    }

    /** cornerFrom: 처음 닿은 곳(위·아래 어느 모서리를 접을지), from: 넘기기 시작한 곳(여기서부터 따라간다). */
    fun beginDrag(dir: TurnDir, cornerFrom: Offset, from: Offset): Boolean {
        finishNow()
        if (!canTurn(dir)) return false

        val cornerY = if (cornerFrom.y > height / 2) height else 0f
        turn = Turn(dir, cornerY)
        announced = false
        touch = if (dir == TurnDir.NEXT) Offset(width, cornerY) else Offset(-width, cornerY)
        follow(from)
        return true
    }

    /** 움직이고 있는 종이를 그 자리에서 잡는다. 잡을 것이 없으면 false. */
    fun grab(): Boolean {
        if (turn == null || !isAnimating) return false

        job?.cancel()
        job = null
        return true
    }

    /** 잡은 종이를 지금 위치에서부터 손가락으로 따라가게 한다. 시작할 때 종이가 튀지 않는다. */
    fun follow(from: Offset) {
        dragStart = from
        touchStart = touch
    }

    /** 잡았다가 움직이지 않고 뗐으면(탭), 하던 넘김을 마저 한다. */
    fun resume() {
        if (turn == null || isAnimating) return

        settle(lastTarget, pendingCommit, lastVelocity)
    }

    fun dragTo(p: Offset) {
        val t = turn ?: return
        val d = p - dragStart
        val dy = if (mode == TurnMode.SLIDE) 0f else d.y * 0.6f
        touch = constrain(Offset(touchStart.x + d.x * factor, touchStart.y + dy), t.cornerY)
    }

    /**
     * 손을 뗐을 때 넘길지 되돌릴지 정하고, 손가락 속도를 그대로 이어받아 부드럽게 마무리한다.
     * 떼기 직전에 실제로 움직인 방향을 가장 먼저 본다. 넘기다가 되돌아오는 중이었다면 많이 끌었어도 넘기지 않는다.
     * 속도는 이벤트 간격에 따라 거꾸로 나올 때가 있어서, 움직인 방향을 알 수 없을 때만 쓴다.
     * 멈춘 채로 떼면 종이가 절반 넘게 넘어갔을 때만 넘긴다(touch.x가 0이면 접힌 선이 화면 가운데).
     *
     * @param velocity 떼는 순간 손가락 속도(px/s)
     * @param recentDirX 떼기 직전 손가락의 가로 이동 방향(-1 왼쪽, 1 오른쪽, 0 멈춰 있었거나 아주 조금만 움직임)
     */
    fun release(velocity: Offset, recentDirX: Int = 0) {
        val t = turn ?: return
        val forward = if (t.dir == TurnDir.NEXT) -1 else 1
        val moving = 150f
        val commit = when {
            recentDirX != 0 -> recentDirX == forward
            abs(velocity.x) > moving -> sign(velocity.x).toInt() == forward
            t.dir == TurnDir.NEXT -> touch.x < 0f
            else -> touch.x > 0f
        }
        val flat = Offset(width, t.cornerY)
        val gone = Offset(-width, t.cornerY)
        val target = when (t.dir) {
            TurnDir.NEXT -> if (commit) gone else flat
            TurnDir.PREV -> if (commit) flat else gone
        }

        // 손가락이 목표 쪽으로 움직이고 있었다면 그 속도로 이어서 간다. 반대쪽이면 버린다(엉뚱하게 튕기지 않게).
        val vx = velocity.x * factor
        val carried = if (sign(vx) == sign(target.x - touch.x)) vx else 0f
        if (commit) announce()
        settle(target, commit, Offset(carried, 0f))
    }

    /** 탭이나 볼륨 키로 넘길 때: 아래 모서리를 살짝 들어 올리며 넘긴다. */
    fun tap(dir: TurnDir) {
        // 손가락으로 종이를 잡고 있는 동안에는 무시한다.
        if (turn != null) {
            if (isAnimating && queued.size < 5) queued.addLast(dir)
            return
        }
        if (!canTurn(dir)) return

        announced = false
        announce()
        if (mode == TurnMode.NONE) {
            onCommit(dir)
            return
        }

        val y = height
        turn = Turn(dir, y)
        pendingCommit = true
        val fromX = if (dir == TurnDir.NEXT) width else -width
        val toX = -fromX
        val lift = if (mode == TurnMode.CURL) height * 0.12f else 0f
        touch = Offset(fromX, y)
        lastTarget = Offset(toX, y)
        lastVelocity = Offset.Zero

        job = scope.launch {
            animate(0f, 1f, animationSpec = tween(if (mode == TurnMode.CURL) 460 else 300, easing = FastOutSlowInEasing)) { v, speed ->
                val x = fromX + (toX - fromX) * v
                touch = constrain(Offset(x, y - sin(v * PI).toFloat() * lift), y)
                lastVelocity = Offset((toX - fromX) * speed, 0f)
            }
            finish(dir, true)
        }
    }

    private fun announce() {
        if (announced) return

        announced = true
        onTurnStart()
    }

    // 감쇠가 딱 맞는 스프링이라 출렁이지 않고 미끄러지듯 멈춘다.
    private fun settle(target: Offset, commit: Boolean, initialVelocity: Offset) {
        val t = turn ?: return
        pendingCommit = commit
        lastTarget = target
        lastVelocity = initialVelocity

        job = scope.launch {
            animate(
                typeConverter = Offset.VectorConverter,
                initialValue = touch,
                targetValue = target,
                initialVelocity = initialVelocity,
                animationSpec = spring(dampingRatio = 1f, stiffness = 520f, visibilityThreshold = Offset(0.5f, 0.5f)),
            ) { value, velocity ->
                touch = constrain(value, t.cornerY)
                lastVelocity = velocity
            }
            finish(t.dir, commit)
        }
    }

    private fun finish(dir: TurnDir, commit: Boolean) {
        pendingCommit = false
        job = null
        // 쪽 번호 변경과 넘김 상태 해제를 한 프레임에 같이 반영해서 깜빡임이 없게 한다.
        if (commit) onCommit(dir)
        turn = null

        // 넘길 수 없는 쪽(첫·마지막 쪽)으로 가는 탭은 건너뛰고 다음 것을 본다.
        while (turn == null) {
            val next = queued.removeFirstOrNull() ?: return
            tap(next)
        }
    }

    // 종이는 책등(왼쪽 가장자리)에 붙어 있으므로, 모서리가 책등에서 쪽 너비보다 멀어질 수 없다.
    // 모서리가 쪽 바깥(오른쪽 끝, 위·아래 끝 너머)으로 나가면 접힌 모양이 뒤집히므로 그 안으로 묶어 둔다.
    private fun constrain(p: Offset, cornerY: Float): Offset {
        val x = min(p.x, width)
        val y = if (cornerY > 0f) min(p.y, cornerY) else max(p.y, 0f)
        val dx = x
        val dy = y - cornerY
        val d = hypot(dx, dy)
        if (d <= width) return Offset(x, y)

        return Offset(dx * width / d, cornerY + dy * width / d)
    }
}
