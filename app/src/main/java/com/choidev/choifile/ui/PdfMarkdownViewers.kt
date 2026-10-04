package com.choidev.choifile.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.choidev.choifile.R
import com.choidev.choifile.util.MdBlock
import com.choidev.choifile.util.MdSpan
import com.choidev.choifile.util.Markdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_MD_BYTES = 2_000_000L
private const val MAX_RENDER_PX = 2400

/** PDF pages through the platform [PdfRenderer]: one page at a time, previous / next / jump, pinch or button zoom. */
@Composable
fun PdfViewer(file: File) {
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var errorRes by remember { mutableIntStateOf(0) }
    var pageCount by remember { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var jump by remember { mutableStateOf(false) }

    DisposableEffect(file) {
        var pfd: ParcelFileDescriptor? = null
        var r: PdfRenderer? = null
        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            r = PdfRenderer(pfd)
            renderer = r
            pageCount = r.pageCount
            if (pageCount == 0) errorRes = R.string.pdf_error_open
        } catch (e: SecurityException) {
            errorRes = R.string.pdf_error_password
        } catch (e: Exception) {
            errorRes = R.string.pdf_error_open
        }
        onDispose {
            renderer = null
            runCatching { r?.close() }
            runCatching { pfd?.close() }
        }
    }

    // Render at up to twice the view width so zooming in stays reasonably sharp; the bitmap is rebuilt per page.
    LaunchedEffect(renderer, page, viewSize.width) {
        val r = renderer ?: return@LaunchedEffect
        if (viewSize.width == 0 || page !in 0 until pageCount) return@LaunchedEffect
        scale = 1f; offset = Offset.Zero
        bitmap = withContext(Dispatchers.IO) {
            try {
                synchronized(r) {
                    r.openPage(page).use { p ->
                        val w = (viewSize.width * 2).coerceIn(200, MAX_RENDER_PX)
                        val h = (w.toLong() * p.height / p.width).toInt().coerceIn(1, MAX_RENDER_PX * 3)
                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(AndroidColor.WHITE)
                            p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    fun clamp(o: Offset, s: Float) = Offset(
        o.x.coerceIn(-viewSize.width * (s - 1) / 2, viewSize.width * (s - 1) / 2),
        o.y.coerceIn(-viewSize.height * (s - 1) / 2, viewSize.height * (s - 1) / 2),
    )

    fun zoom(to: Float) { scale = to.coerceIn(1f, 5f); offset = clamp(offset, scale) }

    Column(Modifier.fillMaxSize().background(Color(0xFF303030))) {
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .onSizeChanged { viewSize = it }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero }) }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, z, _ ->
                        scale = (scale * z).coerceIn(1f, 5f)
                        offset = clamp(offset + pan * scale, scale)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmap
            if (errorRes != 0) {
                Text(stringResource(errorRes), Modifier.padding(24.dp), color = Color.White)
            } else if (bmp != null) {
                Image(
                    bmp.asImageBitmap(), null,
                    Modifier.fillMaxSize().graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationX = offset.x; translationY = offset.y
                    },
                    contentScale = ContentScale.Fit,
                )
            }
        }
        if (errorRes == 0 && pageCount > 0) {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ page = (page - 1).coerceAtLeast(0) }, enabled = page > 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.pdf_prev)) }
                    TextButton({ jump = true }) { Text(stringResource(R.string.pdf_page_of, page + 1, pageCount)) }
                    IconButton({ page = (page + 1).coerceAtMost(pageCount - 1) }, enabled = page < pageCount - 1) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.pdf_next)) }
                    Box(Modifier.weight(1f))
                    IconButton({ zoom(scale - 0.5f) }, enabled = scale > 1f) { Icon(Icons.Default.Remove, stringResource(R.string.pdf_zoom_out)) }
                    Text("${(scale * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                    IconButton({ zoom(scale + 0.5f) }, enabled = scale < 5f) { Icon(Icons.Default.Add, stringResource(R.string.pdf_zoom_in)) }
                }
            }
        }
    }

    if (jump) {
        var text by remember { mutableStateOf((page + 1).toString()) }
        val target = text.toIntOrNull()?.takeIf { it in 1..pageCount }
        AlertDialog(
            onDismissRequest = { jump = false },
            title = { Text(stringResource(R.string.pdf_goto)) },
            text = {
                OutlinedTextField(
                    text, { text = it.filter(Char::isDigit) }, singleLine = true, isError = target == null,
                    label = { Text("1 – $pageCount") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = { TextButton({ page = target!! - 1; jump = false }, enabled = target != null) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton({ jump = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun spansToText(spans: List<MdSpan>, base: SpanStyle = SpanStyle()): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    return buildAnnotatedString {
        for (s in spans) {
            var style = base
            if (s.bold) style = style.merge(SpanStyle(fontWeight = FontWeight.Bold))
            if (s.italic) style = style.merge(SpanStyle(fontStyle = FontStyle.Italic))
            if (s.strike) style = style.merge(SpanStyle(textDecoration = TextDecoration.LineThrough))
            if (s.code) style = style.merge(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg))
            val link = s.link
            if (link != null) {
                withLink(LinkAnnotation.Url(link, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) {
                    withStyle(style) { append(s.text) }
                }
            } else withStyle(style) { append(s.text) }
        }
    }
}

/** Renders the basic Markdown subset of [Markdown]. Raw HTML stays text, images show their alt text, only http(s)/mailto links are tappable. */
@Composable
fun MarkdownViewer(file: File) {
    var text by remember { mutableStateOf<String?>(null) }
    var tooLarge by remember { mutableStateOf(false) }
    LaunchedEffect(file) {
        if (file.length() > MAX_MD_BYTES) { tooLarge = true; return@LaunchedEffect }
        text = withContext(Dispatchers.IO) { file.readText() }
    }
    if (tooLarge) { Text(stringResource(R.string.msg_too_large), Modifier.padding(24.dp)); return }
    val src = text ?: return
    val blocks = remember(src) { Markdown.parse(src) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        items(blocks) { b -> MdBlockView(b); Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun MdBlockView(b: MdBlock) {
    val t = MaterialTheme.typography
    when (b) {
        is MdBlock.Heading -> Text(
            spansToText(b.spans),
            style = when (b.level) { 1 -> t.headlineMedium; 2 -> t.headlineSmall; 3 -> t.titleLarge; 4 -> t.titleMedium; else -> t.titleSmall }.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(top = if (b.level <= 2) 8.dp else 4.dp),
        )
        is MdBlock.Paragraph -> Text(spansToText(b.spans), style = t.bodyLarge)
        is MdBlock.Code -> Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(
                b.text, Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
                style = t.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp), softWrap = false,
            )
        }
        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline))
            Text(spansToText(b.spans, SpanStyle(fontStyle = FontStyle.Italic)), Modifier.padding(start = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = t.bodyLarge)
        }
        is MdBlock.ListItem -> Row(Modifier.padding(start = (b.indent * 16).dp)) {
            val marker = when {
                b.checked == true -> "☑"
                b.checked == false -> "☐"
                b.number != null -> "${b.number}."
                else -> "•"
            }
            Text(marker, Modifier.padding(end = 8.dp), style = t.bodyLarge)
            Text(spansToText(b.spans), style = t.bodyLarge)
        }
        is MdBlock.Table -> Column(Modifier.horizontalScroll(rememberScrollState())) {
            @Composable fun row(cells: List<List<MdSpan>>, header: Boolean) {
                Row {
                    cells.forEach { c ->
                        Text(
                            spansToText(c, if (header) SpanStyle(fontWeight = FontWeight.Bold) else SpanStyle()),
                            Modifier.widthIn(min = 90.dp).padding(horizontal = 8.dp, vertical = 4.dp), style = t.bodyMedium,
                        )
                    }
                }
            }
            row(b.header, true)
            HorizontalDivider()
            b.rows.forEach { row(it, false) }
        }
        MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
    }
}
