package com.choidev.choifile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.choidev.choifile.R
import com.choidev.choifile.util.FileHash
import com.choidev.choifile.util.HashAlgo
import com.choidev.choifile.util.HashCompare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Details of one local file or folder, plus MD5 / SHA-1 / SHA-256 with a "compare with a pasted hash" field. */
@Composable
fun PropertiesDialog(path: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val file = remember(path) { File(path) }
    val scope = rememberCoroutineScope()
    var size by remember { mutableLongStateOf(file.length()) }
    var count by remember { mutableLongStateOf(0) }
    var hashes by remember { mutableStateOf<Map<HashAlgo, String>?>(null) }
    var hashing by remember { mutableStateOf(false) }
    var hashed by remember { mutableLongStateOf(0) }
    var hashError by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    LaunchedEffect(path) {
        if (file.isDirectory) withContext(Dispatchers.IO) {
            var s = 0L; var n = 0L
            file.walkTopDown().forEach { if (it.isFile) { s += it.length(); n++ } }
            size = s; count = n
        }
    }
    DisposableEffect(path) { onDispose { job?.cancel() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.name.ifEmpty { "/" }, maxLines = 2) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SelectionContainer { Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(stringResource(R.string.prop_size, Formatter.formatFileSize(context, size)) + if (file.isDirectory) stringResource(R.string.prop_files, count) else "")
                Text(stringResource(R.string.prop_modified, df.format(Date(file.lastModified()))))
                if (file.isFile) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.prop_hash_title), style = MaterialTheme.typography.titleSmall)
                    val result = hashes
                    if (result == null) {
                        if (hashing) {
                            LinearProgressIndicator(progress = { if (file.length() > 0) (hashed.toFloat() / file.length()).coerceIn(0f, 1f) else 0f }, Modifier.fillMaxWidth())
                            TextButton({ job?.cancel(); hashing = false }) { Text(stringResource(R.string.cancel)) }
                        } else {
                            OutlinedButton({
                                hashing = true; hashError = false; hashed = 0
                                job = scope.launch(Dispatchers.IO) {
                                    try {
                                        val h = FileHash.all(file, cancelled = { job?.isActive == false }, onProgress = { hashed = it })
                                        hashes = h
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        hashError = true
                                    } finally {
                                        hashing = false
                                    }
                                }
                            }) { Text(stringResource(R.string.prop_hash_run)) }
                            if (hashError) Text(stringResource(R.string.prop_hash_failed), color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        result.forEach { (algo, value) ->
                            Text(algo.label, style = MaterialTheme.typography.labelMedium)
                            Row {
                                SelectionContainer(Modifier.weight(1f)) { Text(value, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                                TextButton({ copyText(context, value) }) { Text(stringResource(R.string.copy)) }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            pasted, { pasted = it }, Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.prop_hash_paste)) },
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                            trailingIcon = { TextButton({ pasted = readClipboard(context) }) { Text(stringResource(R.string.prop_paste)) } },
                        )
                        if (pasted.isNotBlank()) {
                            val (cmp, algo) = FileHash.compare(result, pasted)
                            when (cmp) {
                                HashCompare.MATCH -> Text(stringResource(R.string.prop_hash_match, algo!!.label), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
                                HashCompare.MISMATCH -> Text(stringResource(R.string.prop_hash_mismatch, algo!!.label), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
                                HashCompare.INVALID -> Text(stringResource(R.string.prop_hash_invalid), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("choifile", text))
}

private fun readClipboard(context: Context): String {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
}
