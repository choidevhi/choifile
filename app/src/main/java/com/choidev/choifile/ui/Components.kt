package com.choidev.choifile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

enum class ButtonVariant { PRIMARY, SECONDARY, OUTLINE, GHOST, DESTRUCTIVE }

/** True inside [ChoiDialog]'s button row: buttons draw as iOS alert actions (plain tinted text, full height). */
private val LocalAlertActions = staticCompositionLocalOf { false }

/**
 * iOS-style button. PRIMARY is the filled tint, SECONDARY and OUTLINE the gray "tinted" style with tint-colored
 * text, GHOST plain tint text, DESTRUCTIVE red. Inside a [ChoiDialog] every variant becomes an alert action.
 */
@Composable
fun ChoiButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: ButtonVariant = ButtonVariant.PRIMARY,
    compact: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val c = ChoiTheme.colors
    val alert = LocalAlertActions.current
    val (bg, fg) = when {
        alert -> Color.Transparent to (if (variant == ButtonVariant.DESTRUCTIVE) c.destructive else c.primary)
        variant == ButtonVariant.PRIMARY -> c.primary to c.primaryForeground
        variant == ButtonVariant.SECONDARY || variant == ButtonVariant.OUTLINE -> c.secondary to c.primary
        variant == ButtonVariant.GHOST -> Color.Transparent to c.primary
        else -> c.destructive to c.destructiveForeground
    }
    val weight = if (alert && variant == ButtonVariant.PRIMARY || !alert && variant != ButtonVariant.GHOST) FontWeight.SemiBold else FontWeight.Normal
    Button(
        onClick = onClick,
        // In an alert the button fills its whole cell, so a tap anywhere in it counts.
        modifier = (if (alert) modifier.fillMaxWidth() else modifier).heightIn(min = if (alert) 44.dp else if (compact) 34.dp else 44.dp),
        enabled = enabled,
        shape = RoundedCornerShape(if (alert) Radius.md else Radius.lg),
        colors = ButtonDefaults.buttonColors(
            containerColor = bg, contentColor = fg,
            disabledContainerColor = if (bg == Color.Transparent) bg else bg.copy(alpha = 0.5f),
            disabledContentColor = (if (bg == Color.Transparent) c.mutedForeground else fg).copy(alpha = 0.6f),
        ),
        elevation = null,
        contentPadding = PaddingValues(horizontal = if (compact) Space.md else Space.lg, vertical = 0.dp),
    ) {
        ProvideTextStyle(MaterialTheme.typography.bodyLarge.copy(fontWeight = weight), content = { content() })
    }
}

@Composable
fun PrimaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    ChoiButton(onClick, modifier, enabled, ButtonVariant.PRIMARY, content = content)

@Composable
fun SecondaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    ChoiButton(onClick, modifier, enabled, ButtonVariant.SECONDARY, content = content)

@Composable
fun OutlineButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    ChoiButton(onClick, modifier, enabled, ButtonVariant.OUTLINE, content = content)

@Composable
fun GhostButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    ChoiButton(onClick, modifier, enabled, ButtonVariant.GHOST, compact = true, content = content)

@Composable
fun DestructiveButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    ChoiButton(onClick, modifier, enabled, ButtonVariant.DESTRUCTIVE, content = content)

/**
 * Drop-in replacement for Material's AlertDialog with the iOS alert look: a rounded translucent card, centered
 * title, the content under it, and the actions in a row under a hairline as plain tinted text (the dismiss action
 * first, like iOS's Cancel on the left). Same parameter names as AlertDialog.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    val c = ChoiTheme.colors
    Dialog(onDismissRequest, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier.padding(horizontal = Space.xl, vertical = Space.xl).widthIn(max = 420.dp).fillMaxWidth(),
            shape = RoundedCornerShape(Radius.xl),
            color = c.popover,
            contentColor = c.foreground,
            shadowElevation = 16.dp,
        ) {
            Column {
                // Long content scrolls inside its own area; the action row below always stays on screen.
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().padding(start = Space.lg, end = Space.lg, top = Space.lg + Space.xs, bottom = Space.lg)) {
                    icon?.let { Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = Space.md)) { it() } }
                    title?.let {
                        Box(Modifier.align(Alignment.CenterHorizontally)) {
                            ProvideTextStyle(MaterialTheme.typography.titleMedium) { it() }
                        }
                        Spacer(Modifier.height(Space.sm))
                    }
                    text?.let {
                        Box(Modifier.weight(1f, fill = false)) {
                            CompositionLocalProvider(LocalContentColor provides c.foreground) {
                                ProvideTextStyle(MaterialTheme.typography.bodyMedium) { it() }
                            }
                        }
                    }
                }
                HorizontalDivider(thickness = Hairline, color = c.border)
                CompositionLocalProvider(LocalAlertActions provides true) {
                    AlertActions(Modifier.fillMaxWidth()) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
            }
        }
    }
}

/**
 * The action row of an iOS alert: every button gets an equal share of the width (so the whole cell is the touch
 * target, not just the label) with a hairline between them; three or more, or labels too long for half the width,
 * stack full width one above the other, like iOS.
 */
@Composable
private fun AlertActions(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val line = ChoiTheme.colors.border
    // What the last measure decided, for the separators drawn after it: [side by side (1/0), button count].
    val shape = remember { intArrayOf(1, 0) }
    Layout(
        content = content,
        modifier = modifier.drawWithContent {
            drawContent()
            val n = shape[1]
            for (i in 1 until n) {
                if (shape[0] == 1) {
                    val x = size.width * i / n
                    drawLine(line, Offset(x, 0f), Offset(x, size.height), Hairline.toPx())
                } else {
                    val y = size.height * i / n
                    drawLine(line, Offset(0f, y), Offset(size.width, y), Hairline.toPx())
                }
            }
        },
    ) { measurables, constraints ->
        val n = measurables.size
        val width = constraints.maxWidth
        val minHeight = 44.dp.roundToPx()
        val sideBySide = n in 1..2 && measurables.all { it.maxIntrinsicWidth(minHeight) <= width / n }
        shape[0] = if (sideBySide) 1 else 0
        shape[1] = n
        if (sideBySide) {
            val cell = width / n
            val placeables = measurables.mapIndexed { i, m ->
                val w = if (i == n - 1) width - cell * (n - 1) else cell
                m.measure(Constraints(minWidth = w, maxWidth = w, minHeight = minHeight))
            }
            layout(width, placeables.maxOfOrNull { it.height } ?: 0) {
                var x = 0
                placeables.forEach { p -> p.placeRelative(x, 0); x += p.width }
            }
        } else {
            val placeables = measurables.map { it.measure(Constraints(minWidth = width, maxWidth = width, minHeight = minHeight)) }
            layout(width, placeables.sumOf { it.height }) {
                var y = 0
                placeables.forEach { p -> p.placeRelative(0, y); y += p.height }
            }
        }
    }
}

/** iOS context menu: a rounded translucent card with a soft shadow; fill it with [ChoiMenuItem]s. */
@Composable
fun ChoiMenu(expanded: Boolean, onDismissRequest: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = ChoiTheme.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(min = 232.dp),
        shape = RoundedCornerShape(Radius.xl),
        containerColor = c.popover,
        tonalElevation = 0.dp,
        shadowElevation = 16.dp,
        border = BorderStroke(Hairline, c.border.copy(alpha = 0.5f)),
        content = content,
    )
}

/**
 * One row of a [ChoiMenu] the iOS way: the label on the left, its symbol (or a check when [checked]) on the
 * right, 44dp tall, red when [destructive].
 */
@Composable
fun ChoiMenuItem(
    text: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    checked: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    subtitle: String? = null,
) {
    val c = ChoiTheme.colors
    val color = when {
        !enabled -> c.mutedForeground
        destructive -> c.destructive
        else -> c.foreground
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Space.lg, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.mutedForeground) }
        }
        when {
            checked -> {
                Spacer(Modifier.width(Space.md))
                Icon(Icons.Outlined.Check, null, Modifier.size(IconSize.md), tint = if (enabled) c.primary else color)
            }
            icon != null -> {
                Spacer(Modifier.width(Space.md))
                Icon(icon, null, Modifier.size(IconSize.md), tint = color)
            }
        }
    }
}

/** Separator between groups of [ChoiMenuItem]s (iOS uses a thicker gap between sections). */
@Composable
fun ChoiMenuDivider() {
    HorizontalDivider(Modifier.padding(vertical = Space.xs), thickness = 6.dp, color = ChoiTheme.colors.border.copy(alpha = 0.25f))
}

/**
 * iOS navigation bar: the title centered in Headline weight, the back chevron and actions in the tint color, a
 * hairline underneath.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChoiTopBar(
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    colors: TopAppBarColors? = null,
) {
    val c = ChoiTheme.colors
    Column {
        CenterAlignedTopAppBar(
            title = { ProvideTextStyle(MaterialTheme.typography.titleMedium) { title() } },
            navigationIcon = { CompositionLocalProvider(LocalContentColor provides c.primary) { navigationIcon() } },
            actions = { CompositionLocalProvider(LocalContentColor provides c.primary) { actions() } },
            colors = colors ?: TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = c.background, scrolledContainerColor = c.background,
                navigationIconContentColor = c.primary, actionIconContentColor = c.primary,
            ),
        )
        HorizontalDivider(thickness = Hairline, color = c.border)
    }
}

/** Inset grouped section (iOS Settings): a rounded card on the grouped gray background. */
@Composable
fun ChoiCard(modifier: Modifier = Modifier, padding: Dp = Space.lg, content: @Composable ColumnScope.() -> Unit) {
    val c = ChoiTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(Radius.lg))
            .background(c.card)
            .padding(padding),
        content = content,
    )
}

/** Section header above an inset group: footnote size, secondary color, lined up with the card's content. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(start = Space.xl + Space.xs, end = Space.lg, top = Space.xl, bottom = Space.sm),
        style = MaterialTheme.typography.bodySmall,
        color = ChoiTheme.colors.mutedForeground,
    )
}

/** Muted helper paragraph (an iOS section footer). */
@Composable
fun MutedText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = ChoiTheme.colors.mutedForeground)
}

/** iOS switch: green when on, a full-size white knob in both states, no outline. */
@Composable
fun ChoiSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = ChoiTheme.colors
    Switch(
        checked, onCheckedChange, modifier, enabled = enabled,
        // Any thumb content makes Material draw the knob at full size even when off, as iOS does.
        thumbContent = { Spacer(Modifier.size(SwitchDefaults.IconSize)) },
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White, checkedTrackColor = c.success, checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color.White, uncheckedTrackColor = c.input, uncheckedBorderColor = Color.Transparent,
            disabledCheckedTrackColor = c.success.copy(alpha = 0.4f), disabledUncheckedTrackColor = c.input.copy(alpha = 0.5f),
            disabledCheckedThumbColor = Color.White, disabledUncheckedThumbColor = Color.White,
        ),
    )
}
