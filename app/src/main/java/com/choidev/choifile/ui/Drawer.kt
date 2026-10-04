package com.choidev.choifile.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.choidev.choifile.R
import com.choidev.choifile.storage.Favorite
import kotlin.math.abs

/** Everything the drawer can open besides folders. */
enum class DrawerTool { HIDDEN, TRASH, CONNECTIONS, SEARCH, ANALYSIS, DUPLICATES, RULES, OPERATIONS, SERVER, SETTINGS }

/**
 * Opens the drawer when a horizontal drag starts within [edge] of the left side. It watches the gesture before the
 * list and the tab pager do (initial pass) and only claims it once the finger has clearly moved right, so taps,
 * long presses and vertical scrolls near the edge behave as before, and tab swipes that start further in are
 * untouched. [blocked] (drag-selecting, drawer already open) turns it off mid-gesture as well.
 */
fun Modifier.edgeSwipeToOpen(edge: Dp, blocked: () -> Boolean, onOpen: () -> Unit): Modifier = pointerInput(Unit) {
    val edgePx = edge.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.position.x > edgePx || blocked()) return@awaitEachGesture
        var dx = 0f
        var dy = 0f
        val slop = viewConfiguration.touchSlop
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed || blocked() || event.changes.size > 1) return@awaitEachGesture
            val d = change.positionChange()
            dx += d.x
            dy += d.y
            if (abs(dy) > slop && abs(dy) > abs(dx)) return@awaitEachGesture // a vertical scroll
            if (dx < -slop) return@awaitEachGesture
            if (dx > slop * 2 && dx > abs(dy) * 1.5f) {
                change.consume()
                onOpen()
                // Swallow the rest of this gesture so the pager underneath does not also move.
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    e.changes.forEach { it.consume() }
                    if (e.changes.none { it.pressed }) break
                }
                return@awaitEachGesture
            }
        }
    }
}

@Composable
fun BrowserDrawerContent(
    favorites: List<Favorite>,
    missing: Set<String>,
    storage: List<Pair<Int, String>>,
    currentPath: String,
    onOpenFolder: (String) -> Unit,
    onRemoveFavorite: (String) -> Unit,
    onRenameFavorite: (String, String) -> Unit,
    onMoveFavorite: (Int, Int) -> Unit,
    onTool: (DrawerTool) -> Unit,
) {
    val c = ChoiTheme.colors
    var renaming by remember { mutableStateOf<Favorite?>(null) }
    LazyColumn(
        Modifier.fillMaxHeight().statusBarsPadding().navigationBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.sm, vertical = Space.md),
    ) {
        item {
            Text(
                stringResource(R.string.app_name), Modifier.padding(start = Space.md, top = Space.sm, bottom = Space.sm),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        item { DrawerHeading(stringResource(R.string.drawer_favorites)) }
        if (favorites.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.drawer_favorites_empty), Modifier.padding(horizontal = Space.md, vertical = Space.xs),
                    style = MaterialTheme.typography.bodySmall, color = c.mutedForeground,
                )
            }
        }
        itemsIndexed(favorites, key = { _, f -> "fav:" + f.path }) { index, f ->
            FavoriteRow(
                f, index, favorites.size, gone = f.path in missing, active = f.path == currentPath,
                onOpen = { onOpenFolder(f.path) },
                onRemove = { onRemoveFavorite(f.path) },
                onRename = { renaming = f },
                onMove = { to -> onMoveFavorite(index, to) },
            )
        }
        item { DrawerHeading(stringResource(R.string.drawer_storage)) }
        itemsIndexed(storage, key = { _, s -> "st:" + s.second }) { i, (label, path) ->
            val icon = when (i) {
                0 -> Icons.Default.PhoneAndroid
                1 -> Icons.Default.Download
                2 -> Icons.Default.CameraAlt
                3 -> Icons.Default.Image
                else -> Icons.Default.Description
            }
            DrawerItem(icon, stringResource(label), active = path == currentPath) { onOpenFolder(path) }
        }
        item { DrawerHeading(stringResource(R.string.drawer_tools)) }
        val tools = listOf(
            Triple(DrawerTool.SEARCH, Icons.Default.Search, R.string.search),
            Triple(DrawerTool.HIDDEN, Icons.Default.VisibilityOff, R.string.hidden_items),
            Triple(DrawerTool.TRASH, Icons.Default.DeleteOutline, R.string.trash),
            Triple(DrawerTool.CONNECTIONS, Icons.Default.CloudQueue, R.string.connections),
            Triple(DrawerTool.ANALYSIS, Icons.Default.Analytics, R.string.analysis_title),
            Triple(DrawerTool.DUPLICATES, Icons.Default.ContentCopy, R.string.dup_title),
            Triple(DrawerTool.RULES, Icons.Default.AutoMode, R.string.rules_title),
            Triple(DrawerTool.OPERATIONS, Icons.AutoMirrored.Filled.ListAlt, R.string.ops_title),
            Triple(DrawerTool.SERVER, Icons.Default.Dns, R.string.srv_title),
        )
        tools.forEach { (tool, icon, label) -> item(key = tool.name) { DrawerItem(icon, stringResource(label)) { onTool(tool) } } }
        item {
            HorizontalDivider(Modifier.padding(vertical = Space.sm), thickness = Hairline, color = c.border)
            DrawerItem(Icons.Default.Settings, stringResource(R.string.settings)) { onTool(DrawerTool.SETTINGS) }
        }
    }

    renaming?.let { f ->
        var text by remember(f.path) { mutableStateOf(f.label) }
        ChoiDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.fav_rename_title)) },
            text = {
                OutlinedTextField(
                    text, { text = it }, singleLine = true,
                    label = { Text(stringResource(R.string.name_label)) },
                    supportingText = { Text(stringResource(R.string.fav_rename_hint)) },
                )
            },
            confirmButton = { PrimaryButton({ onRenameFavorite(f.path, text); renaming = null }) { Text(stringResource(R.string.save)) } },
            dismissButton = { OutlineButton({ renaming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun DrawerHeading(text: String) {
    Text(
        text, Modifier.padding(start = Space.md, top = Space.lg, bottom = Space.xs),
        style = MaterialTheme.typography.labelMedium, color = ChoiTheme.colors.mutedForeground,
    )
}

/** shadcn sidebar item: 40dp, rounded-md, muted icon, the current location highlighted. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerItem(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    sub: String? = null,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = ChoiTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(Radius.md))
            .background(if (active) c.secondary else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = Space.md, end = Space.xs, top = Space.xs, bottom = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(IconSize.md), tint = if (active) c.foreground else c.mutedForeground)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
            sub?.let { Text(it, maxLines = 1, overflow = TextOverflow.StartEllipsis, style = MaterialTheme.typography.bodySmall, color = c.mutedForeground) }
        }
        trailing?.invoke()
    }
}

@Composable
private fun FavoriteRow(
    f: Favorite,
    index: Int,
    count: Int,
    gone: Boolean,
    active: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    onRename: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val c = ChoiTheme.colors
    var menu by remember { mutableStateOf(false) }
    val open by rememberUpdatedState(onOpen)
    Box {
        DrawerItem(
            if (gone) Icons.Default.Folder else Icons.Default.Star, f.label,
            Modifier.alpha(if (gone) 0.5f else 1f),
            active = active,
            sub = if (gone) stringResource(R.string.fav_missing) else null,
            onLongClick = { menu = true },
            trailing = if (gone) {
                {
                    Box(
                        Modifier.size(32.dp).clip(RoundedCornerShape(Radius.md)).combinedClickable(onClick = onRemove),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Default.Close, stringResource(R.string.fav_remove), Modifier.size(IconSize.sm), tint = c.mutedForeground) }
                }
            } else null,
            onClick = { if (gone) menu = true else open() },
        )
        ChoiMenu(menu, { menu = false }) {
            @Composable fun item(label: Int, enabled: Boolean = true, action: () -> Unit) =
                DropdownMenuItem({ Text(stringResource(label)) }, { menu = false; action() }, enabled = enabled)
            item(R.string.fav_rename) { onRename() }
            item(R.string.fav_move_up, enabled = index > 0) { onMove(index - 1) }
            item(R.string.fav_move_down, enabled = index < count - 1) { onMove(index + 1) }
            HorizontalDivider(thickness = Hairline, color = c.border)
            item(R.string.fav_remove) { onRemove() }
        }
    }
}
