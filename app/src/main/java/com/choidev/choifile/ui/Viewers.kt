package com.choidev.choifile.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
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
import androidx.compose.ui.graphics.SolidColor
import coil3.compose.AsyncImage
import com.choidev.choifile.R
import com.choidev.choifile.util.FileKind
import com.choidev.choifile.util.HighlightTransformation
import com.choidev.choifile.util.NaturalOrder
import com.choidev.choifile.util.codeExt
import com.choidev.choifile.util.extensionLower
import com.choidev.choifile.util.kindOf
import com.choidev.choifile.util.openWithOtherApp
import com.choidev.choifile.util.shareFiles
import com.choidev.choifile.util.ZoomMath
import com.choidev.choifile.util.SubtitleText
import com.choidev.choifile.ebook.EBOOK_DEFAULT_MIN_BYTES
import com.choidev.choifile.ebook.EBOOK_EXTENSIONS
import com.choidev.choifile.ebook.EbookScreen
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_TEXT_BYTES = 1_000_000L

/**
 * Full-screen viewer. Images get a swipeable pager over the other images of the same folder (natural name order);
 * the title, share and "open with" always follow the image on screen. [onOpenWith] shows choifile's own
 * app picker; without it the system chooser is used.
 */
@Composable
fun ViewerScreen(file: File, temporary: Boolean = false, onOpenWith: ((File) -> Unit)? = null, onClose: () -> Unit) {
    val context = LocalContext.current
    val kind = kindOf(file)
    var shown by remember(file) { mutableStateOf(file) }
    var dirty by remember { mutableStateOf(false) }
    var saveRequest by remember { mutableIntStateOf(0) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var mdSource by remember { mutableStateOf(false) }

    if (temporary) LaunchedEffect(Unit) { Toast.makeText(context, R.string.temp_view_notice, Toast.LENGTH_LONG).show() }

    fun close() = if (dirty) confirmDiscard = true else onClose()
    BackHandler(onBack = ::close)

    // Music from an archive or a server (a temporary copy): the app-wide player, stopped with the viewer.
    if (kind == FileKind.AUDIO) {
        LaunchedEffect(file) { com.choidev.choifile.playback.AudioPlayback.play(context, file, listOf(file)) }
        AudioPlayerScreen {
            com.choidev.choifile.playback.AudioPlayback.stop()
            onClose()
        }
        return
    }

    // Prose saved as text (a novel, a long .txt) reads as a book; smaller text files open in the text viewer, which
    // offers the book view from its bar.
    var asBook by remember(file) {
        mutableStateOf(kind == FileKind.TEXT && file.extensionLower() in EBOOK_EXTENSIONS && file.length() >= EBOOK_DEFAULT_MIN_BYTES)
    }
    if (asBook) {
        EbookScreen(file, onClose = ::close)
        return
    }

    // Video takes the whole screen and draws its own floating top bar.
    if (kind == FileKind.VIDEO) {
        VideoPlayer(file, siblings = !temporary, onFile = { shown = it }, onClose = ::close) {
            if (!temporary) {
                IconButton({ onOpenWith?.invoke(shown) ?: openWithOtherApp(context, shown) }) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.open_with), tint = Color.White)
                }
                IconButton({ shareFiles(context, listOf(shown)) }) { Icon(Icons.Outlined.Share, stringResource(R.string.share), tint = Color.White) }
            }
        }
        return
    }

    val media = kind == FileKind.IMAGE
    Scaffold(
        containerColor = if (media) Color.Black else MaterialTheme.colorScheme.background,
        topBar = {
            ChoiTopBar(
                title = { Text(shown.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
                navigationIcon = { IconButton(::close) { Icon(Icons.AutoMirrored.Outlined.ArrowBackIos, stringResource(R.string.back)) } },
                actions = {
                    if (kind == FileKind.MARKDOWN) {
                        IconButton({ mdSource = !mdSource }, enabled = !dirty) {
                            Icon(if (mdSource) Icons.Outlined.Visibility else Icons.Outlined.Code, stringResource(if (mdSource) R.string.md_show_rendered else R.string.md_show_source), Modifier.padding(0.dp))
                        }
                    }
                    if (kind == FileKind.TEXT && file.extensionLower() in EBOOK_EXTENSIONS && !dirty) {
                        IconButton({ asBook = true }) { Icon(Icons.AutoMirrored.Outlined.MenuBook, stringResource(R.string.ebook_read_as_book)) }
                    }
                    if ((kind == FileKind.TEXT || (kind == FileKind.MARKDOWN && mdSource)) && !temporary && file.length() <= MAX_TEXT_BYTES) {
                        IconButton({ saveRequest++ }, enabled = dirty) { Icon(Icons.Outlined.Save, stringResource(R.string.save)) }
                    }
                    if (!temporary) {
                        IconButton({ onOpenWith?.invoke(shown) ?: openWithOtherApp(context, shown) }) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.open_with)) }
                        IconButton({ shareFiles(context, listOf(shown)) }) { Icon(Icons.Outlined.Share, stringResource(R.string.share)) }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (kind) {
                FileKind.IMAGE -> ImagePager(file, siblings = !temporary) { shown = it }
                FileKind.PDF -> PdfViewer(file)
                FileKind.MARKDOWN -> if (mdSource) TextViewer(file, saveRequest, readOnly = temporary, onDirty = { dirty = it }) else MarkdownViewer(file)
                else -> TextViewer(file, saveRequest, readOnly = temporary, onDirty = { dirty = it })
            }
        }
    }

    if (confirmDiscard) {
        ChoiDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.unsaved_title)) },
            text = { Text(stringResource(R.string.unsaved_message)) },
            confirmButton = { DestructiveButton(onClose) { Text(stringResource(R.string.discard)) } },
            dismissButton = { OutlineButton({ confirmDiscard = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Zoom and pan of one image page; kept per file so swiping away and back keeps the zoom. */
@Stable
private class ZoomState {
    var view by mutableStateOf(ZoomMath.View())
    val zoomed: Boolean get() = view.scale > 1.01f
}

/** Images next to [file] (same folder, natural order). Hidden files only when the opened one is hidden. */
private fun imageSiblings(file: File): List<File> {
    val dir = file.parentFile ?: return listOf(file)
    val showHidden = file.name.startsWith(".")
    val list = dir.listFiles()?.filter { it.isFile && kindOf(it) == FileKind.IMAGE && (showHidden || !it.name.startsWith(".")) }.orEmpty()
    val sorted = list.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
    return if (sorted.any { it.path == file.path }) sorted else listOf(file)
}

@Composable
private fun ImagePager(file: File, siblings: Boolean, onPage: (File) -> Unit) {
    val files by produceState(listOf(file), file) {
        if (siblings) value = withContext(Dispatchers.IO) { runCatching { imageSiblings(file) }.getOrDefault(listOf(file)) }
    }
    val start = files.indexOfFirst { it.path == file.path }.coerceAtLeast(0)
    // Re-created once the folder listing arrives so the pager starts on the opened image.
    androidx.compose.runtime.key(files.size) {
        val pager = rememberPagerState(start) { files.size }
        val zooms = remember { HashMap<String, ZoomState>() }
        fun zoomOf(f: File) = zooms.getOrPut(f.path) { ZoomState() }
        LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { files.getOrNull(it)?.let(onPage) } }
        val currentZoomed = files.getOrNull(pager.currentPage)?.let { zoomOf(it).zoomed } == true
        HorizontalPager(
            pager,
            Modifier.fillMaxSize().background(Color.Black),
            // While zoomed in, one-finger drags pan the image instead of turning the page.
            userScrollEnabled = !currentZoomed,
            beyondViewportPageCount = 1,
            key = { files[it].path },
        ) { page ->
            ZoomableImage(files[page], zoomOf(files[page]))
        }
    }
}

@Composable
private fun ZoomableImage(file: File, z: ZoomState) {
    var size by remember { mutableStateOf(IntSize.Zero) }

    fun zoomAround(focus: Offset, newScale: Float, pan: Offset = Offset.Zero) {
        z.view = ZoomMath.zoomAround(z.view, size.width.toFloat(), size.height.toFloat(), focus.x, focus.y, newScale, pan.x, pan.y)
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(z) {
                detectTapGestures(onDoubleTap = { pos -> if (z.zoomed) z.view = ZoomMath.View() else zoomAround(pos, 2.5f) })
            }
            .pointerInput(z) {
                // Pinch always zooms; one finger pans only while zoomed, otherwise the event is left to the pager.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val cancelled = event.changes.any { it.isConsumed }
                        // The lift event has no finger still down, so its centroid is Offset.Unspecified (NaN).
                        // Feeding that into zoomAround poisoned the stored offset and blanked the page until reopen.
                        val fingers = event.changes.count { it.pressed && it.previousPressed }
                        if (!cancelled && fingers > 0 && (fingers >= 2 || z.zoomed)) {
                            val centroid = event.calculateCentroid(useCurrent = true)
                            if (centroid.isSpecified) {
                                zoomAround(centroid, z.view.scale * event.calculateZoom(), event.calculatePan())
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (!cancelled && event.changes.any { it.pressed })
                }
            },
    ) {
        AsyncImage(
            model = file,
            contentDescription = file.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = z.view.scale; scaleY = z.view.scale
                translationX = z.view.offsetX; translationY = z.view.offsetY
            },
        )
    }
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

    // Too big to edit as one string: a read-only view that only reads what is on screen.
    if (file.length() > MAX_TEXT_BYTES) {
        LargeTextViewer(file, monospace = file.extensionLower() in codeExt || file.extensionLower() == "log")
        return
    }
    // The file's own encoding (UTF-8, or CP949 for older Korean files), kept when saving.
    var charset by remember(file) { mutableStateOf("UTF-8") }
    LaunchedEffect(file) {
        val (cs, loaded) = withContext(Dispatchers.IO) {
            val bytes = file.readBytes()
            val cs = SubtitleText.detect(bytes)
            cs to String(SubtitleText.toUtf8(bytes, cs), Charsets.UTF_8)
        }
        charset = cs
        saved = loaded
        text = loaded
    }
    LaunchedEffect(saveRequest) {
        val current = text
        if (saveRequest == 0 || current == null) return@LaunchedEffect
        val ok = withContext(Dispatchers.IO) { runCatching { file.writeText(current, charset(charset)) }.isSuccess }
        if (ok) { saved = current; onDirty(false) }
        Toast.makeText(context, if (ok) R.string.msg_saved else R.string.msg_save_failed, Toast.LENGTH_SHORT).show()
    }

    val current = text ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.lg)) {
        BasicTextField(
            value = current,
            onValueChange = { text = it; onDirty(it != saved) },
            readOnly = readOnly,
            modifier = Modifier.fillMaxSize(),
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onBackground),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = transformation,
        )
    }
}
