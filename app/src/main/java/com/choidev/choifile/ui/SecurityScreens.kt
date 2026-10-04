package com.choidev.choifile.ui

import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.choidev.choifile.R
import com.choidev.choifile.browser.BrowserViewModel
import com.choidev.choifile.security.Authenticator
import com.choidev.choifile.security.GRACE_CHOICES
import com.choidev.choifile.util.OpenChoice
import kotlinx.coroutines.launch

val LocalAuthenticator = compositionLocalOf<Authenticator?> { null }

/**
 * Returns a function that shows the system prompt and calls its callback only after success.
 * With strict = true a device without any screen lock/biometric gets a message instead of silently passing.
 */
@Composable
fun rememberRequestAuth(): (subtitle: Int, strict: Boolean, onOk: () -> Unit) -> Unit {
    val auth = LocalAuthenticator.current
    val context = LocalContext.current
    return { subtitle, strict, onOk ->
        if (auth != null) {
            auth.authenticate(context.getString(R.string.auth_title), context.getString(subtitle), strict) { ok ->
                if (ok) {
                    onOk()
                } else if (strict && !auth.isAvailable()) {
                    Toast.makeText(context, R.string.msg_auth_unavailable, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

/** Lock view for the app lock or a locked protected folder; prompts automatically once when shown. */
@Composable
fun LockedView(subtitle: Int, modifier: Modifier = Modifier, autoPrompt: Boolean = true, onUnlock: () -> Unit) {
    if (autoPrompt) LaunchedEffect(Unit) { onUnlock() }
    val c = ChoiTheme.colors
    Column(modifier.fillMaxSize().padding(Space.xxl), Arrangement.Center, Alignment.CenterHorizontally) {
        Box(
            Modifier.size(56.dp).border(Hairline, c.border, RoundedCornerShape(Radius.xl)).background(c.muted, RoundedCornerShape(Radius.xl)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Lock, null, Modifier.size(IconSize.lg), tint = c.foreground)
        }
        Spacer(Modifier.height(Space.lg))
        Text(stringResource(R.string.locked_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(Space.xs))
        Text(stringResource(subtitle), Modifier.widthIn(max = 320.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = c.mutedForeground)
        Spacer(Modifier.height(Space.xl))
        PrimaryButton(onUnlock) { Text(stringResource(R.string.unlock)) }
    }
}

@Composable
private fun RadioOption(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, onClick = onClick, role = Role.RadioButton).padding(horizontal = Space.sm, vertical = Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, null)
        Text(label, Modifier.padding(start = Space.sm), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SettingRow(title: String, description: String?, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = Space.md)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (description != null) {
                Spacer(Modifier.height(Space.xxs))
                MutedText(description)
            }
        }
        trailing()
    }
}

@Composable
fun SettingsScreen(
    appLock: Boolean,
    onAppLock: (Boolean) -> Unit,
    graceSeconds: Int,
    onGrace: (Int) -> Unit,
    relockSeconds: Int,
    onRelock: (Int) -> Unit,
    protectedPaths: Set<String>,
    rootPath: String,
    onUnprotect: (String) -> Unit,
    associations: List<Pair<String, OpenChoice>>,
    onClearAssociation: (String) -> Unit,
    onClearAllAssociations: () -> Unit,
    onClearCache: suspend () -> Long,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmCache by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    val c = ChoiTheme.colors
    val cardModifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg)

    Scaffold(topBar = {
        ChoiTopBar(
            title = { Text(stringResource(R.string.settings)) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                SectionLabel(stringResource(R.string.settings_section_security))
                ChoiCard(cardModifier) {
                    SettingRow(stringResource(R.string.setting_app_lock), stringResource(R.string.setting_app_lock_desc)) { Switch(appLock, onAppLock) }
                    HorizontalDivider(Modifier.padding(vertical = Space.md), thickness = Hairline, color = c.border)
                    Text(stringResource(R.string.setting_grace), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(Space.xxs))
                    MutedText(stringResource(R.string.setting_grace_desc))
                    Spacer(Modifier.height(Space.sm))
                    SegmentedChoice(
                        options = GRACE_CHOICES.map { it to if (it == 0) stringResource(R.string.grace_none) else stringResource(R.string.grace_seconds, it) },
                        selected = graceSeconds,
                        onSelect = onGrace,
                    )
                }

                SectionLabel(stringResource(R.string.setting_relock))
                ChoiCard(cardModifier, padding = Space.sm) {
                    listOf(0 to R.string.relock_immediate, 60 to R.string.relock_1min, 300 to R.string.relock_5min).forEach { (sec, label) ->
                        RadioOption(relockSeconds == sec, stringResource(label)) { onRelock(sec) }
                    }
                    MutedText(stringResource(R.string.relock_grace_note, graceSeconds), Modifier.padding(horizontal = Space.sm, vertical = Space.sm))
                }

                SectionLabel(stringResource(R.string.setting_protected_list))
            }
            item {
                ChoiCard(cardModifier, padding = if (protectedPaths.isEmpty()) Space.lg else Space.sm) {
                    if (protectedPaths.isEmpty()) MutedText(stringResource(R.string.setting_protected_none))
                    protectedPaths.sorted().forEachIndexed { i, p ->
                        if (i > 0) HorizontalDivider(thickness = Hairline, color = c.border)
                        Row(Modifier.fillMaxWidth().padding(start = Space.sm), verticalAlignment = Alignment.CenterVertically) {
                            Text(p.removePrefix(rootPath).ifEmpty { "/" }, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.StartEllipsis, style = MaterialTheme.typography.bodyMedium)
                            GhostButton({ onUnprotect(p) }) { Text(stringResource(R.string.remove_protection)) }
                        }
                    }
                }
            }
            item {
                SectionLabel(stringResource(R.string.setting_associations))
                ChoiCard(cardModifier, padding = Space.sm) {
                    if (associations.isEmpty()) {
                        MutedText(stringResource(R.string.setting_associations_none), Modifier.padding(Space.sm))
                    }
                    associations.forEachIndexed { i, (ext, choice) ->
                        if (i > 0) HorizontalDivider(thickness = Hairline, color = c.border)
                        Row(Modifier.fillMaxWidth().padding(start = Space.sm), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.widthIn(min = 56.dp).border(Hairline, c.border, RoundedCornerShape(Radius.md)).padding(horizontal = Space.sm, vertical = Space.xxs)) {
                                Text(".$ext", style = MaterialTheme.typography.labelMedium)
                            }
                            Spacer(Modifier.width(Space.md))
                            Text(
                                when (choice) {
                                    OpenChoice.BuiltIn -> stringResource(R.string.assoc_builtin)
                                    OpenChoice.Ask -> stringResource(R.string.assoc_ask)
                                    is OpenChoice.App -> choice.label
                                },
                                Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                            )
                            GhostButton({ onClearAssociation(ext) }) { Text(stringResource(R.string.assoc_clear)) }
                        }
                    }
                    MutedText(stringResource(R.string.setting_associations_desc), Modifier.padding(Space.sm))
                    if (associations.isNotEmpty()) {
                        OutlineButton(onClearAllAssociations, Modifier.padding(start = Space.sm, bottom = Space.sm)) { Text(stringResource(R.string.assoc_clear_all)) }
                    }
                }
            }
            item {
                SectionLabel(stringResource(R.string.settings_section_storage))
                ChoiCard(cardModifier) {
                    SettingRow(stringResource(R.string.setting_clear_cache), stringResource(R.string.setting_clear_cache_desc)) {
                        OutlineButton({ confirmCache = true }, enabled = !clearing) { Text(stringResource(R.string.clear_cache_action)) }
                    }
                }

                SectionLabel(stringResource(R.string.setting_honesty_title))
                ChoiCard(cardModifier) {
                    Text(stringResource(R.string.setting_honesty_body), style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
                }
                Spacer(Modifier.height(Space.xxl))
            }
        }
    }

    if (confirmCache) {
        ChoiDialog(
            onDismissRequest = { confirmCache = false },
            title = { Text(stringResource(R.string.clear_cache_title)) },
            text = { Text(stringResource(R.string.clear_cache_message)) },
            confirmButton = {
                DestructiveButton({
                    confirmCache = false
                    clearing = true
                    scope.launch {
                        val freed = onClearCache()
                        clearing = false
                        Toast.makeText(context, context.getString(R.string.clear_cache_done, Formatter.formatShortFileSize(context, freed)), Toast.LENGTH_LONG).show()
                    }
                }) { Text(stringResource(R.string.clear_cache_action)) }
            },
            dismissButton = { OutlineButton({ confirmCache = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** shadcn "toggle group": bordered row of equal segments, the selected one filled. */
@Composable
fun <T> SegmentedChoice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = ChoiTheme.colors
    Row(
        modifier.fillMaxWidth().border(Hairline, c.border, RoundedCornerShape(Radius.lg)).padding(Space.xxs + 1.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.xxs),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .background(if (on) c.primary else c.background, RoundedCornerShape(Radius.md))
                    .toggleable(on, role = Role.RadioButton, onValueChange = { onSelect(value) })
                    .padding(vertical = Space.sm),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) c.primaryForeground else c.foreground, maxLines = 1)
            }
        }
    }
}

@Composable
fun VaultScreen(vm: BrowserViewModel, onBack: () -> Unit) {
    val hidden by vm.hidden.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadHidden() }
    BackHandler(onBack = onBack)
    val c = ChoiTheme.colors
    Scaffold(topBar = {
        ChoiTopBar(
            title = { Text(stringResource(R.string.hidden_items)) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                ChoiCard(Modifier.fillMaxWidth().padding(Space.lg)) {
                    Text(stringResource(R.string.hidden_honesty), style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
                }
                if (hidden.isEmpty()) {
                    Text(stringResource(R.string.hidden_empty), Modifier.fillMaxWidth().padding(Space.xxl), textAlign = TextAlign.Center, color = c.mutedForeground)
                }
            }
            items(hidden, key = { it.id }) { e ->
                Row(Modifier.fillMaxWidth().padding(start = Space.lg, end = Space.sm, top = Space.xs, bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        MutedText(e.origin)
                    }
                    GhostButton({ vm.unhide(e) }) { Text(stringResource(R.string.unhide)) }
                }
                HorizontalDivider(Modifier.padding(start = Space.lg), thickness = Hairline, color = c.border)
            }
        }
    }
}
