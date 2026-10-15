package com.choidev.choifile.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.choidev.choifile.R
import com.choidev.choifile.browser.BrowserViewModel
import com.choidev.choifile.server.ServerKind
import com.choidev.choifile.server.ServerRuntime
import com.choidev.choifile.server.ServerService

/** Start / stop the built-in HTTP and FTP servers. Nothing listens until the user flips a switch here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(vm: BrowserViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val prefs = vm.prefs
    val http by ServerRuntime.http.collectAsStateWithLifecycle()
    val ftp by ServerRuntime.ftp.collectAsStateWithLifecycle()
    var root by remember { mutableStateOf(prefs.serverRoot.ifEmpty { vm.rootPath + "/Download" }) }
    var upload by remember { mutableStateOf(prefs.serverUpload) }
    var httpPort by remember { mutableStateOf(prefs.httpPort.toString()) }
    var ftpPort by remember { mutableStateOf(prefs.ftpPort.toString()) }
    var pickFolder by remember { mutableStateOf(false) }
    var warn by remember { mutableStateOf<ServerKind?>(null) }
    var pendingStart by remember { mutableStateOf<(() -> Unit)?>(null) }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // The service runs either way; without the permission only the status notification is hidden.
        pendingStart?.invoke(); pendingStart = null
    }

    fun port(text: String, default: Int) = text.toIntOrNull()?.takeIf { it in 1024..65535 } ?: default

    fun startNow(kind: ServerKind) {
        prefs.serverRoot = root; prefs.serverUpload = upload
        val p = if (kind == ServerKind.HTTP) port(httpPort, 8080).also { prefs.httpPort = it } else port(ftpPort, 2121).also { prefs.ftpPort = it }
        ServerService.start(context, kind, root, p, upload)
    }

    fun requestStart(kind: ServerKind) {
        val start = { startNow(kind) }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingStart = start
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else start()
    }

    Scaffold(topBar = {
        ChoiTopBar(
            title = { Text(stringResource(R.string.srv_title)) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBackIos, stringResource(R.string.back)) } },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.srv_intro), style = MaterialTheme.typography.bodyMedium)

            val anyRunning = http.running || ftp.running
            OutlineButton({ pickFolder = true }, Modifier.fillMaxWidth(), enabled = !anyRunning) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.srv_folder), style = MaterialTheme.typography.labelSmall)
                    Text(root.removePrefix(vm.rootPath).ifEmpty { "/" }, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(upload, { upload = it }, enabled = !anyRunning)
                Text(stringResource(R.string.srv_allow_upload))
            }

            ServerCard(ServerKind.HTTP, stringResource(R.string.srv_http), httpPort, { httpPort = it.filter(Char::isDigit) }, http, upload) { on ->
                if (on) warn = ServerKind.HTTP else ServerService.stop(context, ServerKind.HTTP)
            }
            ServerCard(ServerKind.FTP, stringResource(R.string.srv_ftp), ftpPort, { ftpPort = it.filter(Char::isDigit) }, ftp, upload) { on ->
                if (on) warn = ServerKind.FTP else ServerService.stop(context, ServerKind.FTP)
            }
            Text(stringResource(R.string.srv_footer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (pickFolder) FolderPickerDialog(vm.rootPath, root, onPick = { root = it; pickFolder = false }, onDismiss = { pickFolder = false })

    warn?.let { kind ->
        ChoiDialog(
            onDismissRequest = { warn = null },
            title = { Text(stringResource(R.string.srv_warn_title)) },
            text = { Text(stringResource(if (kind == ServerKind.HTTP) R.string.srv_warn_http else R.string.srv_warn_ftp)) },
            confirmButton = { PrimaryButton({ warn = null; requestStart(kind) }) { Text(stringResource(R.string.srv_start)) } },
            dismissButton = { OutlineButton({ warn = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ServerCard(
    kind: ServerKind, title: String, portText: String, onPort: (String) -> Unit,
    status: com.choidev.choifile.server.ServerStatus, upload: Boolean, onSwitch: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                ChoiSwitch(status.running, onSwitch)
            }
            if (!status.running) {
                OutlinedTextField(
                    portText, onPort, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.srv_port)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text(stringResource(R.string.srv_port_hint)) },
                )
                if (status.errorRes != 0) Text(stringResource(status.errorRes) + if (status.errorDetail.isNotEmpty()) " (${status.errorDetail})" else "", color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.srv_off), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(stringResource(R.string.srv_running, status.port), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                val addresses = remember(status.port) { ServerRuntime.localAddresses() }
                if (addresses.isEmpty()) Text(stringResource(R.string.srv_no_network), color = MaterialTheme.colorScheme.error)
                addresses.forEach { (ip, tailscale) ->
                    val url = if (kind == ServerKind.HTTP) "http://$ip:${status.port}/" else "ftp://$ip:${status.port}/"
                    SelectionContainer { Text(url + if (tailscale) "  (Tailscale)" else "", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) }
                }
                Text(stringResource(if (kind == ServerKind.HTTP) R.string.srv_token_label_http else R.string.srv_token_label_ftp), style = MaterialTheme.typography.labelMedium)
                SelectionContainer { Text(status.token, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlineButton({ copyText(context, status.token) }) { Text(stringResource(R.string.srv_copy_token)) }
                    val first = addresses.firstOrNull()?.first
                    if (kind == ServerKind.HTTP && first != null) {
                        OutlineButton({ copyText(context, "http://$first:${status.port}/?token=${status.token}") }) { Text(stringResource(R.string.srv_copy_link)) }
                    }
                }
                Text(stringResource(if (status.allowUpload) R.string.srv_upload_on else R.string.srv_upload_off), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.srv_shared, status.root.substringAfterLast('/')), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
