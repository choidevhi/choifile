package com.choidev.choifile.ebook.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.choidev.choifile.R
import kotlin.math.roundToInt

/** iOS 시스템 색. 밝은/어두운 모드 두 벌. */
@Immutable
data class IosColors(
    val dark: Boolean,
    val background: Color,
    val groupedBackground: Color,
    val cell: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val separator: Color,
    val fill: Color,
    val segmentSelected: Color,
    val accent: Color,
    val destructive: Color,
    val bar: Color,
) {
    companion object {
        val Light = IosColors(
            dark = false,
            background = Color.White,
            groupedBackground = Color(0xFFF2F2F7),
            cell = Color.White,
            label = Color.Black,
            secondaryLabel = Color(0x993C3C43),
            tertiaryLabel = Color(0x4D3C3C43),
            separator = Color(0x4A3C3C43),
            fill = Color(0x1F767680),
            segmentSelected = Color.White,
            accent = Color(0xFF007AFF),
            destructive = Color(0xFFFF3B30),
            bar = Color(0xF2F9F9F9),
        )
        val Dark = IosColors(
            dark = true,
            background = Color.Black,
            groupedBackground = Color(0xFF1C1C1E),
            cell = Color(0xFF2C2C2E),
            label = Color.White,
            secondaryLabel = Color(0x99EBEBF5),
            tertiaryLabel = Color(0x4DEBEBF5),
            separator = Color(0xA6545458),
            fill = Color(0x3D767680),
            segmentSelected = Color(0xFF636366),
            accent = Color(0xFF0A84FF),
            destructive = Color(0xFFFF453A),
            bar = Color(0xF01C1C1E),
        )
    }
}

val LocalIos = staticCompositionLocalOf { IosColors.Light }

/** 물결(ripple) 대신 iOS처럼 눌린 동안 흐려진다. */
fun Modifier.pressable(
    enabled: Boolean = true,
    pressedAlpha: Float = 0.35f,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val alpha by animateFloatAsState(if (pressed) pressedAlpha else 1f, tween(if (pressed) 0 else 220), label = "press")

    this
        .alpha(alpha)
        .combinedClickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            onLongClick = onLongClick,
            onClick = onClick,
        )
}

/** 카드처럼 큰 요소는 흐려지는 대신 살짝 작아진다. */
fun Modifier.pressScale(onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), label = "scale")

    this
        .scale(scale)
        .combinedClickable(interactionSource = source, indication = null, onLongClick = onLongClick, onClick = onClick)
}

@Composable
fun IosSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val c = LocalIos.current
    val knob by animateDpAsState(if (checked) 22.dp else 2.dp, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow), label = "knob")
    val track = when {
        checked -> Color(0xFF34C759)
        c.dark -> Color(0xFF39393D)
        else -> Color(0xFFE9E9EA)
    }

    Box(
        Modifier
            .size(51.dp, 31.dp)
            .clip(CircleShape)
            .background(track)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onCheckedChange(!checked) },
    ) {
        Box(
            Modifier
                .offset { IntOffset(knob.roundToPx(), 2.dp.roundToPx()) }
                .size(27.dp)
                .shadow(3.dp, CircleShape)
                .background(Color.White, CircleShape),
        )
    }
}

/** 얇은 트랙과 흰 동그라미 손잡이. 손가락 위치로 바로 이동한다. */
@Composable
fun IosSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: () -> Unit = {},
    trackColor: Color = LocalIos.current.fill,
    activeColor: Color = LocalIos.current.accent,
) {
    var widthPx by remember { mutableIntStateOf(1) }
    val thumb = 28.dp
    val thumbPx = with(LocalDensity.current) { thumb.toPx() }
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)

    Box(
        modifier
            .height(36.dp)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(Unit) {
                fun valueAt(x: Float) = ((x - thumbPx / 2) / (widthPx - thumbPx).coerceAtLeast(1f)).coerceIn(0f, 1f)

                awaitEachGesture {
                    val down = awaitFirstDown()
                    change(valueAt(down.position.x))
                    while (true) {
                        val event = awaitPointerEvent()
                        val p = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!p.pressed) break

                        p.consume()
                        change(valueAt(p.position.x))
                    }
                    finished()
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val v = value.coerceIn(0f, 1f)
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = thumb / 2)
                .height(4.dp)
                .clip(CircleShape)
                .background(trackColor),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(v)
                    .background(activeColor),
            )
        }
        val travelPx = (widthPx - thumbPx).coerceAtLeast(0f)
        Box(
            Modifier
                .offset { IntOffset((travelPx * v).roundToInt(), 0) }
                .size(thumb)
                .shadow(4.dp, CircleShape)
                .background(Color.White, CircleShape),
        )
    }
}

@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalIos.current

    Row(
        modifier
            .height(32.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(c.fill)
            .padding(2.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(
                        if (isSelected) {
                            Modifier
                                .shadow(2.dp, RoundedCornerShape(7.dp))
                                .background(c.segmentSelected, RoundedCornerShape(7.dp))
                        } else {
                            Modifier
                        },
                    )
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    color = c.label,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 모서리가 둥근 묶음 목록(설정 앱 모양). */
@Composable
fun GroupedSection(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalIos.current

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.cell),
        content = content,
    )
}

@Composable
fun GroupedRow(
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val c = LocalIos.current

    Column(modifier.then(if (onClick != null) Modifier.pressable(pressedAlpha = 0.5f, onClick = onClick) else Modifier)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 46.dp)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        if (showDivider) {
            Box(
                Modifier
                    .padding(start = 16.dp)
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(c.separator),
            )
        }
    }
}

/** 아래에서 올라오는 시트. 손잡이 막대와 흐린 배경을 갖는다. */
@Composable
fun BottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    maxHeightFraction: Float = 0.85f,
    background: Color = LocalIos.current.groupedBackground,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (visible) BackHandler(onBack = onDismiss)

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(tween(220)), exit = fadeOut(tween(220))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            )
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 420f)) { it },
            exit = slideOutVertically(tween(240)) { it },
        ) {
            val maxH = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } * maxHeightFraction
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxH)
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .background(background)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    // 키보드가 올라오면(검색) 시트도 그 위로 올라간다.
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
            ) {
                Box(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(36.dp, 5.dp)
                            .clip(CircleShape)
                            .background(LocalIos.current.tertiaryLabel),
                    )
                }
                content()
            }
        }
    }
}

data class SheetAction(val title: String, val destructive: Boolean = false, val onClick: () -> Unit)

/** iOS 액션 시트: 위 묶음에 동작들, 아래 따로 떨어진 취소 버튼. */
@Composable
fun ActionSheet(
    visible: Boolean,
    title: String?,
    actions: List<SheetAction>,
    onDismiss: () -> Unit,
) {
    val c = LocalIos.current
    if (visible) BackHandler(onBack = onDismiss)

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x55000000))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            )
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spring(dampingRatio = 0.9f, stiffness = 500f)) { it },
            exit = slideOutVertically(tween(200)) { it },
        ) {
            val sheetBg = if (c.dark) Color(0xFF2C2C2E) else Color(0xF7F9F9F9)
            Column(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(sheetBg)) {
                    if (title != null) {
                        Text(
                            title,
                            color = c.secondaryLabel,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Hairline()
                    }
                    actions.forEachIndexed { i, action ->
                        SheetButton(action.title, if (action.destructive) c.destructive else c.accent, FontWeight.Normal) {
                            onDismiss()
                            action.onClick()
                        }
                        if (i < actions.lastIndex) Hairline()
                    }
                }
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (c.dark) Color(0xFF2C2C2E) else Color.White)) {
                    SheetButton(stringResource(R.string.eb_cancel), c.accent, FontWeight.SemiBold, onDismiss)
                }
            }
        }
    }
}

@Composable
private fun SheetButton(text: String, color: Color, weight: FontWeight, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(57.dp)
            .pressable(pressedAlpha = 0.4f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, fontSize = 19.sp, fontWeight = weight)
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = LocalIos.current.separator) {
    Box(modifier.fillMaxWidth().height(0.5.dp).background(color))
}

/** 가운데 뜨는 검은 반투명 안내 상자(HUD). */
@Composable
fun BoxScope.Hud(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(visible, modifier = Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
        Column(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xD9262628))
                .padding(horizontal = 26.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

@Composable
fun HSpace(w: Dp) = Spacer(Modifier.width(w))

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))
