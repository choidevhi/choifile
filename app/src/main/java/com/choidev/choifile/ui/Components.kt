package com.choidev.choifile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

enum class ButtonVariant { PRIMARY, SECONDARY, OUTLINE, GHOST, DESTRUCTIVE }

/** shadcn-style button: flat, rounded-md, 1px border on the outline variant, no elevation. */
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
    val (bg, fg) = when (variant) {
        ButtonVariant.PRIMARY -> c.primary to c.primaryForeground
        ButtonVariant.SECONDARY -> c.secondary to c.secondaryForeground
        ButtonVariant.OUTLINE -> c.background to c.foreground
        ButtonVariant.GHOST -> Color.Transparent to c.foreground
        ButtonVariant.DESTRUCTIVE -> c.destructive to c.destructiveForeground
    }
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = if (compact) 32.dp else 38.dp),
        enabled = enabled,
        shape = RoundedCornerShape(Radius.md),
        colors = ButtonDefaults.buttonColors(
            containerColor = bg, contentColor = fg,
            disabledContainerColor = if (bg == Color.Transparent) bg else bg.copy(alpha = 0.5f),
            disabledContentColor = fg.copy(alpha = 0.5f),
        ),
        elevation = null,
        border = if (variant == ButtonVariant.OUTLINE) BorderStroke(Hairline, c.input) else null,
        contentPadding = PaddingValues(horizontal = if (compact) Space.md else Space.lg, vertical = 0.dp),
    ) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge, content = { content() })
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
 * Drop-in replacement for Material's AlertDialog with the shadcn look: popover surface, 1px border, rounded-lg,
 * muted description, buttons right-aligned and wrapping when they do not fit. Same parameter names, so call
 * sites only change the function name.
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
            modifier.padding(horizontal = Space.lg, vertical = Space.xl).widthIn(max = 480.dp).fillMaxWidth(),
            shape = RoundedCornerShape(Radius.xl),
            color = c.popover,
            contentColor = c.foreground,
            border = BorderStroke(Hairline, c.border),
            shadowElevation = 12.dp,
        ) {
            Column(Modifier.padding(Space.xl)) {
                icon?.let { Box(Modifier.padding(bottom = Space.md)) { it() } }
                title?.let {
                    ProvideTextStyle(MaterialTheme.typography.titleLarge) { it() }
                    Spacer(Modifier.height(Space.sm))
                }
                text?.let {
                    Box(Modifier.weight(1f, fill = false)) {
                        CompositionLocalProvider(LocalContentColor provides c.mutedForeground) {
                            ProvideTextStyle(MaterialTheme.typography.bodyMedium) { it() }
                        }
                    }
                }
                Spacer(Modifier.height(Space.xl))
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space.sm, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}

/** Dropdown menu styled as a popover: border, rounded-lg, soft shadow, no tonal tint. */
@Composable
fun ChoiMenu(expanded: Boolean, onDismissRequest: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = ChoiTheme.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = RoundedCornerShape(Radius.lg),
        containerColor = c.popover,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(Hairline, c.border),
        content = content,
    )
}

/** Flat top bar with a hairline underneath; the title uses the compact titleMedium style. */
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
        TopAppBar(
            title = { ProvideTextStyle(MaterialTheme.typography.titleMedium) { title() } },
            navigationIcon = navigationIcon,
            actions = actions,
            colors = colors ?: TopAppBarDefaults.topAppBarColors(containerColor = c.background, scrolledContainerColor = c.background),
        )
        HorizontalDivider(thickness = Hairline, color = c.border)
    }
}

/** Bordered container (shadcn Card). */
@Composable
fun ChoiCard(modifier: Modifier = Modifier, padding: Dp = Space.lg, content: @Composable ColumnScope.() -> Unit) {
    val c = ChoiTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(Radius.xl))
            .background(c.card)
            .border(Hairline, c.border, RoundedCornerShape(Radius.xl))
            .padding(padding),
        content = content,
    )
}

/** Small muted heading above a group of settings or list rows. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(start = Space.lg, end = Space.lg, top = Space.xl, bottom = Space.sm),
        style = MaterialTheme.typography.labelMedium,
        color = ChoiTheme.colors.mutedForeground,
    )
}

/** Muted helper paragraph. */
@Composable
fun MutedText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = ChoiTheme.colors.mutedForeground)
}
