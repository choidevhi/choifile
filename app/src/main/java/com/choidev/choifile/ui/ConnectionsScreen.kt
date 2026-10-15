package com.choidev.choifile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.choidev.choifile.R
import com.choidev.choifile.browser.BrowserViewModel
import com.choidev.choifile.browser.ConnectionTest
import com.choidev.choifile.browser.HostKeyRequest
import com.choidev.choifile.remote.ConnectionConfig
import com.choidev.choifile.remote.HostKeyDecision
import com.choidev.choifile.remote.Protocol
import com.choidev.choifile.remote.Tailscale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(vm: BrowserViewModel, onOpened: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val connections by vm.connections.collectAsStateWithLifecycle()
    val vaultError by vm.vaultError.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ConnectionConfig?>(null) }
    var preset by remember { mutableStateOf(false) }
    var apiKeyDialog by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ConnectionConfig?>(null) }
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.loadConnections() }

    Scaffold(topBar = {
        ChoiTopBar(
            title = { Text(stringResource(R.string.connections)) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBackIos, stringResource(R.string.back)) } },
            actions = {
                IconButton({ editing = newConnection() }) { Icon(Icons.Outlined.Add, stringResource(R.string.conn_add)) }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.more)) }
                    ChoiMenu(menu, { menu = false }) {
                        ChoiMenuItem(stringResource(R.string.ts_preset), { menu = false; preset = true })
                        ChoiMenuItem(stringResource(R.string.ts_api_key_title), { menu = false; apiKeyDialog = true })
                    }
                }
            },
        )
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                OutlinedCard(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.conn_honesty), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
                vaultError?.let { Text(stringResource(R.string.conn_vault_error, it), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
                if (connections.isEmpty() && vaultError == null) {
                    Text(stringResource(R.string.conn_empty), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(connections, key = { it.id }) { c ->
                ListItem(
                    modifier = Modifier.clickable { vm.openConnection(c.id); onOpened() },
                    leadingContent = { Icon(Icons.Outlined.Cloud, null) },
                    headlineContent = { Text(c.name) },
                    supportingContent = { Text("${c.protocol.label} · ${c.host}:${c.port}" + if (c.tailscale) " · Tailscale" else "") },
                    trailingContent = {
                        Row {
                            IconButton({ editing = c }) { Icon(Icons.Outlined.Edit, stringResource(R.string.edit)) }
                            IconButton({ deleting = c }) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.delete)) }
                        }
                    },
                )
            }
        }
    }

    editing?.let { c ->
        ConnectionEditor(vm, c, isNew = connections.none { it.id == c.id }, onDismiss = { editing = null; vm.clearConnectionTest() }) {
            vm.saveConnection(it); editing = null; vm.clearConnectionTest()
        }
    }
    if (preset) {
        TailscalePreset(vm, onDismiss = { preset = false; vm.clearReach() }) { c -> preset = false; vm.clearReach(); editing = c }
    }
    if (apiKeyDialog) TailscaleKeyDialog(vm) { apiKeyDialog = false }
    deleting?.let { c ->
        ChoiDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.conn_delete_title, c.name)) },
            text = { Text(stringResource(R.string.conn_delete_msg)) },
            confirmButton = { DestructiveButton({ vm.deleteConnection(c.id); deleting = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { OutlineButton({ deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun newConnection() = ConnectionConfig(UUID.randomUUID().toString(), "", Protocol.SFTP, "", Protocol.SFTP.defaultPort, useTls = false)

@Composable
private fun ConnectionEditor(vm: BrowserViewModel, initial: ConnectionConfig, isNew: Boolean, onDismiss: () -> Unit, onSave: (ConnectionConfig) -> Unit) {
    var c by remember { mutableStateOf(initial) }
    var portText by remember { mutableStateOf(initial.port.toString()) }
    val test by vm.connectionTest.collectAsStateWithLifecycle()

    fun built() = c.copy(name = c.name.trim().ifEmpty { c.host.trim() }, host = c.host.trim(), port = portText.toIntOrNull() ?: c.protocol.defaultPort)
    val valid = c.host.isNotBlank() && portText.toIntOrNull() in 1..65535 && (c.protocol != Protocol.SMB || c.share.isNotBlank())

    fun setProtocol(p: Protocol) {
        val oldDefault = defaultPortFor(c.protocol, c.useTls)
        val tls = if (p == Protocol.WEBDAV || p == Protocol.FTP) c.useTls && p == Protocol.WEBDAV else false
        c = c.copy(protocol = p, useTls = tls)
        if (portText.toIntOrNull() == oldDefault) portText = defaultPortFor(p, tls).toString()
    }

    ChoiDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.conn_add else R.string.conn_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    Protocol.entries.forEach { p -> FilterChip(c.protocol == p, { setProtocol(p) }, { Text(p.label) }) }
                }
                Field(c.name, { c = c.copy(name = it) }, R.string.conn_name)
                Field(c.host, { c = c.copy(host = it) }, R.string.conn_host, keyboard = KeyboardType.Uri)
                Field(portText, { portText = it.filter(Char::isDigit).take(5) }, R.string.conn_port, keyboard = KeyboardType.Number)
                if (c.protocol == Protocol.SMB) Field(c.share, { c = c.copy(share = it) }, R.string.conn_share)
                Field(c.username, { c = c.copy(username = it) }, R.string.conn_user)
                Field(c.password, { c = c.copy(password = it) }, R.string.conn_password, secret = true)
                if (c.protocol == Protocol.SFTP) {
                    Field(c.privateKey, { c = c.copy(privateKey = it) }, R.string.conn_private_key, singleLine = false)
                    Field(c.keyPassphrase, { c = c.copy(keyPassphrase = it) }, R.string.conn_key_passphrase, secret = true)
                }
                if (c.protocol == Protocol.WEBDAV || c.protocol == Protocol.FTP) {
                    Field(c.basePath, { c = c.copy(basePath = it) }, R.string.conn_base_path)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(if (c.protocol == Protocol.WEBDAV) R.string.conn_https else R.string.conn_ftps), Modifier.weight(1f))
                        ChoiSwitch(c.useTls, { on ->
                            val old = defaultPortFor(c.protocol, c.useTls)
                            c = c.copy(useTls = on)
                            if (portText.toIntOrNull() == old) portText = defaultPortFor(c.protocol, on).toString()
                        })
                    }
                    if (!c.useTls) Text(stringResource(R.string.conn_plain_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (c.protocol == Protocol.SFTP) Text(stringResource(R.string.conn_hostkey_note), style = MaterialTheme.typography.bodySmall)
                if (c.protocol == Protocol.SMB) Text(stringResource(R.string.conn_smb_note), style = MaterialTheme.typography.bodySmall)
                if (Tailscale.classify(c.host, c.tailscale) != Tailscale.HostKind.OTHER) {
                    Text(stringResource(R.string.ts_needs_app), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                when (val t = test) {
                    ConnectionTest.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.conn_testing), Modifier.padding(start = 8.dp))
                    }
                    ConnectionTest.Ok -> Text(stringResource(R.string.conn_test_ok), color = MaterialTheme.colorScheme.primary)
                    is ConnectionTest.Failed -> Text(stringResource(R.string.conn_test_failed, t.message), color = MaterialTheme.colorScheme.error)
                    null -> Unit
                }
            }
        },
        confirmButton = { PrimaryButton({ onSave(built()) }, enabled = valid) { Text(stringResource(R.string.save)) } },
        dismissButton = {
            Row {
                GhostButton({ vm.testConnection(built()) }, enabled = valid && test != ConnectionTest.Running) { Text(stringResource(R.string.conn_test)) }
                GhostButton(onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

private fun defaultPortFor(p: Protocol, tls: Boolean) = when (p) {
    Protocol.WEBDAV -> if (tls) 443 else 80
    else -> p.defaultPort
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: Int, secret: Boolean = false, singleLine: Boolean = true, keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), label = { Text(stringResource(label)) }, singleLine = singleLine,
        minLines = if (singleLine) 1 else 3, maxLines = if (singleLine) 1 else 6,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
    )
}

@Composable
private fun TailscalePreset(vm: BrowserViewModel, onDismiss: () -> Unit, onNext: (ConnectionConfig) -> Unit) {
    var host by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf(Protocol.SFTP) }
    val reach by vm.reach.collectAsStateWithLifecycle()
    val keySet by vm.tailscaleKeySet.collectAsStateWithLifecycle()
    val devices by vm.tailscaleDevices.collectAsStateWithLifecycle()
    val status by vm.tailscaleStatus.collectAsStateWithLifecycle()
    val port = when (protocol) { Protocol.WEBDAV -> 443; else -> protocol.defaultPort }
    DisposableEffect(Unit) { onDispose { vm.clearReach() } }

    ChoiDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ts_preset)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedCard { Text(stringResource(R.string.ts_explain), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
                Field(host, { host = it }, R.string.ts_host, keyboard = KeyboardType.Uri)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Protocol.SFTP, Protocol.SMB, Protocol.WEBDAV).forEach { p -> FilterChip(protocol == p, { protocol = p }, { Text(p.label) }) }
                }
                OutlineButton({ vm.checkReachable(host.trim(), port) }, enabled = host.isNotBlank()) { Text(stringResource(R.string.ts_check, port)) }
                when (val r = reach) {
                    is Tailscale.Reach.Ok -> Text(stringResource(R.string.ts_reach_ok, r.millis), color = MaterialTheme.colorScheme.primary)
                    is Tailscale.Reach.Failed -> Text(stringResource(R.string.ts_reach_failed, r.reason), color = MaterialTheme.colorScheme.error)
                    null -> Unit
                }
                if (keySet) {
                    HorizontalDivider()
                    OutlineButton(vm::loadTailscaleDevices) { Text(stringResource(R.string.ts_list_devices)) }
                    status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    devices?.forEach { d ->
                        Text(
                            "${d.hostname.ifEmpty { d.target }} · ${d.ipv4 ?: ""} · ${d.os}",
                            Modifier.fillMaxWidth().clickable { host = d.target }.padding(vertical = 6.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            GhostButton({
                onNext(
                    ConnectionConfig(
                        UUID.randomUUID().toString(), host.trim().substringBefore('.'), protocol, host.trim(), port,
                        useTls = protocol == Protocol.WEBDAV, tailscale = true,
                        basePath = "/", share = "",
                    ),
                )
            }, enabled = host.isNotBlank()) { Text(stringResource(R.string.ts_next)) }
        },
        dismissButton = { OutlineButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun TailscaleKeyDialog(vm: BrowserViewModel, onDismiss: () -> Unit) {
    val keySet by vm.tailscaleKeySet.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf("") }
    ChoiDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ts_api_key_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ts_api_key_explain), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(if (keySet) R.string.ts_key_stored else R.string.ts_key_none), color = MaterialTheme.colorScheme.primary)
                Field(key, { key = it }, R.string.ts_api_key_label, secret = true)
                Spacer(Modifier.height(0.dp))
            }
        },
        confirmButton = { PrimaryButton({ vm.setTailscaleApiKey(key); onDismiss() }, enabled = key.isNotBlank()) { Text(stringResource(R.string.save)) } },
        dismissButton = {
            Row {
                if (keySet) GhostButton({ vm.setTailscaleApiKey(""); onDismiss() }) { Text(stringResource(R.string.ts_key_remove)) }
                GhostButton(onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

/** Blocks until the user answers: shows the fingerprint, with a loud warning when the key changed. */
@Composable
fun HostKeyDialog(req: HostKeyRequest) {
    val changed = req.previous != null
    ChoiDialog(
        onDismissRequest = {},
        title = { Text(stringResource(if (changed) R.string.hk_title_changed else R.string.hk_title_new), color = if (changed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${req.host}:${req.port}", style = MaterialTheme.typography.titleSmall)
                if (changed) {
                    Text(stringResource(R.string.hk_changed_warning), color = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.hk_old), style = MaterialTheme.typography.labelMedium)
                    Text(req.previous!!, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(stringResource(R.string.hk_new_explain))
                }
                Text(stringResource(R.string.hk_fingerprint), style = MaterialTheme.typography.labelMedium)
                Text(req.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { PrimaryButton({ req.reply(HostKeyDecision.TRUST) }) { Text(stringResource(if (changed) R.string.hk_trust_anyway else R.string.hk_trust)) } },
        dismissButton = { OutlineButton({ req.reply(HostKeyDecision.REJECT) }) { Text(stringResource(R.string.hk_reject)) } },
    )
}
