package com.choidev.choifile.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.choidev.choifile.R
import com.choidev.choifile.util.AppTarget
import com.choidev.choifile.util.OpenChoice
import com.choidev.choifile.util.appsFor
import com.choidev.choifile.util.extensionKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Open with" sheet: choifile's own viewer (when it handles the type) plus every app that accepts the file.
 * With "always" ticked the choice is remembered for the extension; settings can clear it again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenWithSheet(
    file: File,
    builtInLabel: Int?,
    current: OpenChoice?,
    onBuiltIn: (remember: Boolean) -> Unit,
    onApp: (AppTarget, remember: Boolean) -> Unit,
    onAlwaysAsk: () -> Unit,
    onSystemChooser: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val c = ChoiTheme.colors
    val ext = extensionKey(file.name)
    var always by remember { mutableStateOf(false) }
    val apps by produceState<List<AppTarget>?>(null, file) { value = withContext(Dispatchers.IO) { runCatching { appsFor(context, file) }.getOrDefault(emptyList()) } }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        shape = RoundedCornerShape(topStart = Radius.xl, topEnd = Radius.xl),
        containerColor = c.popover,
        contentColor = c.foreground,
        tonalElevation = 0.dp,
        scrimColor = c.scrim,
        dragHandle = { Box(Modifier.padding(top = Space.md, bottom = Space.xs).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(c.border)) },
    ) {
        Column(Modifier.navigationBarsPadding()) {
            Column(Modifier.padding(horizontal = Space.xl, vertical = Space.md)) {
                Text(stringResource(R.string.ow_title), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.size(Space.xs))
                Text(file.name, style = MaterialTheme.typography.bodySmall, color = c.mutedForeground, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            }
            HorizontalDivider(thickness = Hairline, color = c.border)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                if (builtInLabel != null) {
                    item {
                        OpenRow(
                            icon = { Box(Modifier.size(36.dp).clip(RoundedCornerShape(Radius.lg)).background(c.primary), contentAlignment = Alignment.Center) { Icon(Icons.Default.AutoAwesome, null, Modifier.size(IconSize.md), tint = c.primaryForeground) } },
                            title = stringResource(builtInLabel),
                            subtitle = stringResource(R.string.ow_builtin_sub),
                            selected = current == OpenChoice.BuiltIn,
                        ) { onBuiltIn(always) }
                    }
                }
                val list = apps
                if (list == null) {
                    item { MutedText(stringResource(R.string.ow_loading), Modifier.padding(Space.xl)) }
                } else if (list.isEmpty()) {
                    item { MutedText(stringResource(R.string.ow_no_apps), Modifier.padding(Space.xl)) }
                } else {
                    items(list, key = { it.packageName + "/" + it.activity }) { app ->
                        OpenRow(
                            icon = {
                                val bmp = remember(app) { runCatching { app.icon?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull() }
                                if (bmp != null) Image(bmp, null, Modifier.size(36.dp))
                                else Box(Modifier.size(36.dp).clip(RoundedCornerShape(Radius.lg)).background(c.muted))
                            },
                            title = app.label,
                            subtitle = app.packageName,
                            selected = (current as? OpenChoice.App)?.let { it.packageName == app.packageName && it.activity == app.activity } == true,
                        ) { onApp(app, always) }
                    }
                }
            }
            HorizontalDivider(thickness = Hairline, color = c.border)
            if (ext.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().toggleable(always, role = Role.Checkbox, onValueChange = { always = it }).padding(horizontal = Space.md, vertical = Space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(always, null)
                    Text(stringResource(R.string.ow_always, ext), Modifier.padding(start = Space.xs), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.sm), horizontalArrangement = Arrangement.spacedBy(Space.sm, Alignment.End)) {
                if (ext.isNotEmpty()) GhostButton(onAlwaysAsk) { Text(stringResource(R.string.ow_always_ask)) }
                OutlineButton(onSystemChooser) { Text(stringResource(R.string.ow_system)) }
            }
        }
    }
}

@Composable
private fun OpenRow(icon: @Composable () -> Unit, title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    val c = ChoiTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Space.xl, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(Space.lg))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) {
            Box(Modifier.border(Hairline, c.border, RoundedCornerShape(Radius.md)).padding(horizontal = Space.sm, vertical = Space.xxs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Check, null, Modifier.size(IconSize.xs), tint = c.mutedForeground)
                    Spacer(Modifier.width(Space.xs))
                    Text(stringResource(R.string.ow_default), style = MaterialTheme.typography.labelSmall, color = c.mutedForeground)
                }
            }
        }
    }
}
