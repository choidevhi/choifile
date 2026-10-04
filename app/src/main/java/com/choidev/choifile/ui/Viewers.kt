package com.choidev.choifile.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.SolidColor
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.choidev.choifile.R
import com.choidev.choifile.util.FileKind
import com.choidev.choifile.util.HighlightTransformation
import com.choidev.choifile.util.codeExt
import com.choidev.choifile.util.extensionLower
import com.choidev.choifile.util.kindOf
import com.choidev.choifile.util.openWithOtherApp
import com.choidev.choifile.util.shareFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_TEXT_BYTES = 1_000_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(file: File, temporary: Boolean = false, onClose: () -> Unit) {
    val context = LocalContext.current
    val kind = kindOf(file)
    var dirty by remember { mutableStateOf(false) }
    var saveRequest by remember { mutableIntStateOf(0) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var mdSource by remember { mutableStateOf(false) }

    if (temporary) LaunchedEffect(Unit) { Toast.makeText(context, R.string.temp_view_notice, Toast.LENGTH_LONG).show() }

    fun close() = if (dirty) confirmDiscard = true else onClose()
    BackHandler(onBack = ::close)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(::close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    if (kind == FileKind.MARKDOWN) {
                        IconButton({ mdSource = !mdSource }, enabled = !dirty) {
                            Icon(if (mdSource) Icons.Default.Visibility else Icons.Default.Code, stringResource(if (mdSource) R.string.md_show_rendered else R.string.md_show_source))
                        }
                    }
                    if ((kind == FileKind.TEXT || (kind == FileKind.MARKDOWN && mdSource)) && !temporary) {
                        IconButton({ saveRequest++ }, enabled = dirty) { Icon(Icons.Default.Save, stringResource(R.string.save)) }
                    }
                    if (!temporary) {
                        IconButton({ openWithOtherApp(context, file) }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.open_with)) }
                        IconButton({ shareFiles(context, listOf(file)) }) { Icon(Icons.Default.Share, stringResource(R.string.share)) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.9f)),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (kind) {
                FileKind.IMAGE -> ImageViewer(file)
                FileKind.VIDEO -> VideoViewer(file)
                FileKind.PDF -> PdfViewer(file)
                FileKind.MARKDOWN -> if (mdSource) TextViewer(file, saveRequest, readOnly = temporary, onDirty = { dirty = it }) else MarkdownViewer(file)
                else -> TextViewer(file, saveRequest, readOnly = temporary, onDirty = { dirty = it })
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.unsaved_title)) },
            text = { Text(stringResource(R.string.unsaved_message)) },
            confirmButton = { TextButton(onClose) { Text(stringResource(R.string.discard)) } },
            dismissButton = { TextButton({ confirmDiscard = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ImageViewer(file: File) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(o: Offset, s: Float): Offset {
        val mx = size.width * (s - 1) / 2
        val my = size.height * (s - 1) / 2
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero })
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    offset = clamp(offset + pan * scale, scale)
                }
            },
    ) {
        AsyncImage(
            model = file,
            contentDescription = file.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = offset.x; translationY = offset.y
            },
        )
    }
}

@Composable
private fun VideoViewer(file: File) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    AndroidView({ PlayerView(it).apply { this.player = player } }, Modifier.fillMaxSize().background(Color.Black))
}

@Composable
private fun TextViewer(file: File, saveRequest: Int, readOnly: Boolean, onDirty: (Boolean) -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf("") }
    val dark = isSystemInDarkTheme()
    val transformation = remember(dark, file) {
        if (file.extensionLower() in codeExt) HighlightTransformation(dark) else androidx.compose.ui.text.input.VisualTransformation.None
    }

    LaunchedEffect(file) {
        if (file.length() > MAX_TEXT_BYTES) {
            Toast.makeText(context, R.string.msg_too_large, Toast.LENGTH_LONG).show()
            return@LaunchedEffect
        }
        val loaded = withContext(Dispatchers.IO) { file.readText() }
        saved = loaded
        text = loaded
    }
    LaunchedEffect(saveRequest) {
        val current = text
        if (saveRequest == 0 || current == null) return@LaunchedEffect
        val ok = withContext(Dispatchers.IO) { runCatching { file.writeText(current) }.isSuccess }
        if (ok) { saved = current; onDirty(false) }
        Toast.makeText(context, if (ok) R.string.msg_saved else R.string.msg_save_failed, Toast.LENGTH_SHORT).show()
    }

    val current = text ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        BasicTextField(
            value = current,
            onValueChange = { text = it; onDirty(it != saved) },
            readOnly = readOnly,
            modifier = Modifier.fillMaxSize(),
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = transformation,
        )
    }
}
