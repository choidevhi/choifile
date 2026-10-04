package com.choidev.choifile.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.choidev.choifile.R
import com.choidev.choifile.browser.BrowserViewModel
import com.choidev.choifile.security.Authenticator

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
fun LockedView(subtitle: Int, modifier: Modifier = Modifier, onUnlock: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    Column(modifier.fillMaxSize().padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Icon(Icons.Default.Lock, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.locked_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(subtitle), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onUnlock) { Text(stringResource(R.string.unlock)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    appLock: Boolean,
    onAppLock: (Boolean) -> Unit,
    relockSeconds: Int,
    onRelock: (Int) -> Unit,
    protectedPaths: Set<String>,
    rootPath: String,
    onUnprotect: (String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.settings)) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.setting_app_lock)) },
                    supportingContent = { Text(stringResource(R.string.setting_app_lock_desc)) },
                    trailingContent = { Switch(appLock, onAppLock) },
                )
                HorizontalDivider()
                Text(stringResource(R.string.setting_relock), Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp), style = MaterialTheme.typography.titleSmall)
            }
            items(listOf(0 to R.string.relock_immediate, 60 to R.string.relock_1min, 300 to R.string.relock_5min)) { (sec, label) ->
                Row(
                    Modifier.fillMaxWidth().selectable(relockSeconds == sec, onClick = { onRelock(sec) }, role = Role.RadioButton).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(relockSeconds == sec, null)
                    Text(stringResource(label), Modifier.padding(start = 12.dp))
                }
            }
            item {
                HorizontalDivider(Modifier.padding(top = 8.dp))
                Text(stringResource(R.string.setting_protected_list), Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp), style = MaterialTheme.typography.titleSmall)
                if (protectedPaths.isEmpty()) {
                    Text(stringResource(R.string.setting_protected_none), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(protectedPaths.sorted(), key = { it }) { p ->
                ListItem(
                    headlineContent = { Text(p.removePrefix(rootPath).ifEmpty { "/" }, maxLines = 2) },
                    trailingContent = { TextButton({ onUnprotect(p) }) { Text(stringResource(R.string.remove_protection)) } },
                )
            }
            item {
                HorizontalDivider(Modifier.padding(top = 8.dp))
                Text(stringResource(R.string.setting_honesty_title), Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.setting_honesty_body), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(vm: BrowserViewModel, onBack: () -> Unit) {
    val hidden by vm.hidden.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadHidden() }
    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.hidden_items)) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(stringResource(R.string.hidden_honesty), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                if (hidden.isEmpty()) {
                    Text(stringResource(R.string.hidden_empty), Modifier.fillMaxWidth().padding(32.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(hidden, key = { it.id }) { e ->
                ListItem(
                    headlineContent = { Text(e.name, maxLines = 1) },
                    supportingContent = { Text(e.origin, maxLines = 1) },
                    trailingContent = { TextButton({ vm.unhide(e) }) { Text(stringResource(R.string.unhide)) } },
                )
            }
        }
    }
}
