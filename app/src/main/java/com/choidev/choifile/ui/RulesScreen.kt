package com.choidev.choifile.ui

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.choidev.choifile.R
import com.choidev.choifile.browser.BrowserViewModel
import com.choidev.choifile.rules.Rule
import com.choidev.choifile.rules.RuleAction
import com.choidev.choifile.rules.RuleCondition
import com.choidev.choifile.rules.RuleProblem
import com.choidev.choifile.rules.RuleTrigger
import com.choidev.choifile.search.parseExtensions
import java.io.File
import java.text.DateFormat
import java.util.Date

private enum class PreviewMode { ENABLE, RUN }

private data class PreviewRequest(val rule: Rule, val mode: PreviewMode)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(vm: BrowserViewModel, onOpenLog: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val rules by vm.rules.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<Rule?>(null) }
    var preview by remember { mutableStateOf<PreviewRequest?>(null) }
    var deleting by remember { mutableStateOf<Rule?>(null) }
    val undoLabel = stringResource(R.string.undo)
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    LaunchedEffect(Unit) {
        vm.uiEvents.collect { e ->
            if (e.message.isEmpty()) return@collect
            val r = snackbar.showSnackbar(e.message, actionLabel = undoLabel.takeIf { e.undoRecord != null })
            if (r == SnackbarResult.ActionPerformed) e.undoRecord?.let(vm::undoRecord)
        }
    }

    Scaffold(
        topBar = {
            ChoiTopBar(
                title = { Text(stringResource(R.string.rules_title)) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBackIos, stringResource(R.string.back)) } },
                actions = {
                    IconButton(onOpenLog) { Icon(Icons.AutoMirrored.Outlined.List, stringResource(R.string.rules_history)) }
                    IconButton({ editing = Rule(name = "", condition = RuleCondition(vm.rootPath + "/Download"), action = RuleAction.MOVE) }) { Icon(Icons.Outlined.Add, stringResource(R.string.rules_add)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(stringResource(R.string.rules_safety), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                if (rules.isEmpty()) Text(stringResource(R.string.rules_empty), Modifier.fillMaxWidth().padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(rules, key = { it.id }) { r ->
                ListItem(
                    modifier = Modifier.clickable { editing = r },
                    headlineContent = { Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Column {
                            Text(ruleSummary(r, vm.rootPath), maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            val last = if (r.lastRun == 0L) stringResource(R.string.rules_never_run) else df.format(Date(r.lastRun)) + " · " + lastResultText(r.lastResult)
                            Text(last, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (r.trigger == RuleTrigger.DAILY && !r.reviewed) Text(stringResource(R.string.rules_needs_review), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                GhostButton({ preview = PreviewRequest(r, PreviewMode.RUN) }) { Text(stringResource(R.string.rules_run)) }
                                GhostButton({ deleting = r }) { Text(stringResource(R.string.delete)) }
                            }
                        }
                    },
                    trailingContent = {
                        if (r.trigger == RuleTrigger.DAILY) {
                            ChoiSwitch(r.enabled, { on -> if (!on) vm.setRuleEnabled(r.id, false) else if (r.reviewed) vm.setRuleEnabled(r.id, true) else preview = PreviewRequest(r, PreviewMode.ENABLE) })
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }

    editing?.let { r ->
        RuleEditor(vm, r, onSave = { vm.saveRule(it); editing = null }, onDismiss = { editing = null })
    }
    preview?.let { req ->
        RulePreviewDialog(vm, req.rule, req.mode,
            onConfirm = { if (req.mode == PreviewMode.ENABLE) vm.confirmRule(req.rule.id) else vm.runRule(req.rule.id); preview = null },
            onDismiss = { preview = null })
    }
    deleting?.let { r ->
        ChoiDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.rules_delete_title)) },
            text = { Text(stringResource(R.string.rules_delete_message, r.name)) },
            confirmButton = { DestructiveButton({ vm.deleteRule(r.id); deleting = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { OutlineButton({ deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun lastResultText(code: String): String = when {
    code.startsWith("ok:") -> stringResource(R.string.rules_result_ok, code.removePrefix("ok:"))
    code == "many" -> stringResource(R.string.rules_result_many, RuleEngineLimit)
    code == "error" -> stringResource(R.string.rules_result_error)
    else -> ""
}

private const val RuleEngineLimit = com.choidev.choifile.rules.RuleEngine.MAX_AUTOMATIC_ITEMS

@Composable
private fun ruleSummary(r: Rule, root: String): String {
    val c = r.condition
    val parts = ArrayList<String>()
    parts += c.folder.removePrefix(root).ifEmpty { "/" } + if (c.recursive) " (" + stringResource(R.string.rules_sub) + ")" else ""
    if (c.namePattern.isNotBlank()) parts += c.namePattern
    if (c.extensions.isNotEmpty()) parts += c.extensions.sorted().joinToString(",") { ".$it" }
    c.olderThanDays?.let { parts += stringResource(R.string.rules_older_than, it) }
    c.minSize?.let { parts += "≥ " + mb(it) }
    c.maxSize?.let { parts += "≤ " + mb(it) }
    val action = when (r.action) {
        RuleAction.MOVE -> stringResource(R.string.rules_act_move_to, r.target.removePrefix(root).ifEmpty { "/" })
        RuleAction.TRASH -> stringResource(R.string.rules_act_trash)
        RuleAction.COMPRESS -> stringResource(R.string.rules_act_compress_to, r.target.removePrefix(root).ifEmpty { "/" })
    }
    val trig = stringResource(if (r.trigger == RuleTrigger.DAILY) R.string.rules_trig_daily else R.string.rules_trig_manual)
    return parts.joinToString(" · ") + "\n→ " + action + " · " + trig
}

private fun mb(bytes: Long): String = if (bytes % (1L shl 20) == 0L) "${bytes shr 20} MB" else "%.1f MB".format(bytes / 1048576.0)

@Composable
private fun RuleEditor(vm: BrowserViewModel, initial: Rule, onSave: (Rule) -> Unit, onDismiss: () -> Unit) {
    val c = initial.condition
    var name by remember { mutableStateOf(initial.name) }
    var folder by remember { mutableStateOf(c.folder) }
    var recursive by remember { mutableStateOf(c.recursive) }
    var pattern by remember { mutableStateOf(c.namePattern) }
    var exts by remember { mutableStateOf(c.extensions.sorted().joinToString(", ")) }
    var days by remember { mutableStateOf(c.olderThanDays?.toString() ?: "") }
    var minMb by remember { mutableStateOf(c.minSize?.let { (it / 1048576.0).toString().removeSuffix(".0") } ?: "") }
    var maxMb by remember { mutableStateOf(c.maxSize?.let { (it / 1048576.0).toString().removeSuffix(".0") } ?: "") }
    var action by remember { mutableStateOf(initial.action) }
    var target by remember { mutableStateOf(initial.target) }
    var trigger by remember { mutableStateOf(initial.trigger) }
    var pick by remember { mutableStateOf<String?>(null) } // "folder" or "target"
    val numeric = KeyboardOptions(keyboardType = KeyboardType.Number)

    val minBytes = minMb.toDoubleOrNull()?.let { (it * 1048576).toLong() }
    val maxBytes = maxMb.toDoubleOrNull()?.let { (it * 1048576).toLong() }
    val valid = name.isNotBlank() && folder.isNotBlank() && (action == RuleAction.TRASH || target.isNotBlank())

    ChoiDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial.name.isEmpty()) R.string.rules_new else R.string.rules_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.name_label)) }, singleLine = true)
                Text(stringResource(R.string.rules_section_when), style = MaterialTheme.typography.titleSmall)
                FolderField(R.string.rules_folder, folder, vm.rootPath) { pick = "folder" }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(recursive, { recursive = it })
                    Text(stringResource(R.string.rules_recursive))
                }
                OutlinedTextField(pattern, { pattern = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.rules_pattern)) }, supportingText = { Text(stringResource(R.string.rules_pattern_hint)) }, singleLine = true)
                OutlinedTextField(exts, { exts = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.rules_extensions)) }, singleLine = true)
                OutlinedTextField(days, { days = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.rules_age_days)) }, singleLine = true, keyboardOptions = numeric)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(minMb, { minMb = it.filter { ch -> ch.isDigit() || ch == '.' } }, Modifier.weight(1f), label = { Text(stringResource(R.string.rules_min_mb)) }, singleLine = true, keyboardOptions = numeric)
                    OutlinedTextField(maxMb, { maxMb = it.filter { ch -> ch.isDigit() || ch == '.' } }, Modifier.weight(1f), label = { Text(stringResource(R.string.rules_max_mb)) }, singleLine = true, keyboardOptions = numeric)
                }
                Text(stringResource(R.string.rules_section_do), style = MaterialTheme.typography.titleSmall)
                listOf(RuleAction.MOVE to R.string.rules_act_move, RuleAction.TRASH to R.string.rules_act_trash, RuleAction.COMPRESS to R.string.rules_act_compress).forEach { (a, label) ->
                    Row(Modifier.fillMaxWidth().selectable(action == a, onClick = { action = a }, role = Role.RadioButton), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(action == a, null); Text(stringResource(label), Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp))
                    }
                }
                if (action != RuleAction.TRASH) FolderField(if (action == RuleAction.MOVE) R.string.rules_target_move else R.string.rules_target_zip, target, vm.rootPath) { pick = "target" }
                if (action == RuleAction.COMPRESS) Text(stringResource(R.string.rules_compress_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.rules_section_trigger), style = MaterialTheme.typography.titleSmall)
                listOf(RuleTrigger.MANUAL to R.string.rules_trig_manual, RuleTrigger.DAILY to R.string.rules_trig_daily).forEach { (t, label) ->
                    Row(Modifier.fillMaxWidth().selectable(trigger == t, onClick = { trigger = t }, role = Role.RadioButton), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(trigger == t, null); Text(stringResource(label), Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp))
                    }
                }
                if (trigger == RuleTrigger.DAILY) Text(stringResource(R.string.rules_daily_note, com.choidev.choifile.rules.RuleEngine.MAX_AUTOMATIC_ITEMS), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.rules_edit_resets), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            GhostButton({
                onSave(initial.copy(
                    name = name.trim(),
                    condition = RuleCondition(folder, recursive, pattern.trim(), parseExtensions(exts), days.toIntOrNull(), minBytes, maxBytes),
                    action = action, target = if (action == RuleAction.TRASH) "" else target, trigger = trigger,
                ))
            }, enabled = valid) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { OutlineButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )

    pick?.let { which ->
        FolderPickerDialog(vm.rootPath, if (which == "folder") folder else target.ifEmpty { folder },
            onPick = { if (which == "folder") folder = it else target = it; pick = null }, onDismiss = { pick = null })
    }
}

@Composable
private fun FolderField(label: Int, value: String, root: String, onClick: () -> Unit) {
    OutlineButton(onClick, Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
            Text(value.removePrefix(root).ifEmpty { if (value.isEmpty()) stringResource(R.string.rules_pick_folder) else "/" }, maxLines = 1, overflow = TextOverflow.StartEllipsis)
        }
    }
}

/** Dry run: lists exactly what the rule would touch and changes nothing until the user confirms. */
@Composable
private fun RulePreviewDialog(vm: BrowserViewModel, rule: Rule, mode: PreviewMode, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val preview by produceState<BrowserViewModel.RulePreview?>(null, rule) { value = vm.previewRule(rule) }
    val p = preview
    ChoiDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rules_preview_title, rule.name)) },
        text = {
            Column {
                Text(stringResource(R.string.rules_preview_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                when {
                    p == null -> Text(stringResource(R.string.rules_preview_loading))
                    p.problem != null -> Text(stringResource(problemText(p.problem)), color = MaterialTheme.colorScheme.error)
                    p.items.isEmpty() -> Text(stringResource(R.string.rules_preview_none))
                    else -> {
                        Text(stringResource(R.string.rules_preview_count, p.items.size, Formatter.formatShortFileSize(context, p.items.sumOf { it.size })), style = MaterialTheme.typography.titleSmall)
                        Text(
                            when (rule.action) {
                                RuleAction.TRASH -> stringResource(R.string.rules_preview_trash)
                                RuleAction.MOVE -> stringResource(R.string.rules_preview_move)
                                RuleAction.COMPRESS -> stringResource(R.string.rules_preview_zip)
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        LazyColumn(Modifier.height(240.dp)) {
                            items(p.items.take(300), key = { it.path }) { i ->
                                Text(i.path.removePrefix(vm.rootPath), maxLines = 1, overflow = TextOverflow.StartEllipsis, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                            }
                            if (p.items.size > 300) item { Text(stringResource(R.string.rules_preview_more, p.items.size - 300), style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val ok = p != null && p.problem == null && (mode == PreviewMode.ENABLE || p.items.isNotEmpty())
            GhostButton(onConfirm, enabled = ok) { Text(stringResource(if (mode == PreviewMode.ENABLE) R.string.rules_confirm_enable else R.string.rules_confirm_run)) }
        },
        dismissButton = { OutlineButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun problemText(p: RuleProblem): Int = when (p) {
    RuleProblem.NO_NAME -> R.string.rules_err_name
    RuleProblem.NO_FOLDER -> R.string.rules_err_folder
    RuleProblem.FOLDER_MISSING -> R.string.rules_err_missing
    RuleProblem.FOLDER_FORBIDDEN -> R.string.rules_err_forbidden
    RuleProblem.NO_TARGET -> R.string.rules_err_target
    RuleProblem.TARGET_FORBIDDEN -> R.string.rules_err_target_forbidden
    RuleProblem.TARGET_IS_SOURCE -> R.string.rules_err_same
}
