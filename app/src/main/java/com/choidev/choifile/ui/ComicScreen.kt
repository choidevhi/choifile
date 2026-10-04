package com.choidev.choifile.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.choidev.choifile.AppStores
import com.choidev.choifile.R
import com.choidev.choifile.comic.ComicBook
import com.choidev.choifile.comic.ComicDecoder
import com.choidev.choifile.comic.ComicLayout
import com.choidev.choifile.comic.ComicSlice
import com.choidev.choifile.storage.ScrollPos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext

private class ComicData(val book: ComicBook, val slices: List<ComicSlice>, val anchors: List<String>)

/**
 * Webtoon-style reader: every image of a folder (or zip/cbz) in natural filename order, full width, one continuous
 * vertical scroll with no gaps. Pages are measured first (headers only) so every strip has its final height
 * before anything is decoded; that keeps the saved position exact. The position is saved per folder/archive.
 */
@OptIn(FlowPreview::class)
@Composable
fun ComicScreen(location: String, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val store = remember { AppStores.readingPositions(context) }
    val decoder = remember {
        val budget = (Runtime.getRuntime().maxMemory() / 5).coerceAtMost(128L * 1024 * 1024).toInt()
        ComicDecoder(budget)
    }
    var data by remember { mutableStateOf<ComicData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var measured by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var chrome by remember { mutableStateOf(true) }
    val failedText = stringResource(R.string.comic_failed)

    LaunchedEffect(location) {
        withContext(Dispatchers.IO) {
            try {
                val book = ComicBook.open(location, context.cacheDir)
                total = book.pages.size
                val slices = ArrayList<ComicSlice>()
                for (i in book.pages.indices) {
                    val (w, h) = book.bounds(i)
                    slices += ComicLayout.slices(i, w, h)
                    measured = i + 1
                }
                val anchors = slices.map { ComicLayout.anchor(book.pages[it.page], it) }
                data = ComicData(book, slices, anchors)
            } catch (e: Exception) {
                error = failedText + " (" + (e.message ?: e.javaClass.simpleName) + ")"
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { data?.book?.close() }
            decoder.clear()
        }
    }

    val c = ChoiTheme.colors
    Box(Modifier.fillMaxSize().background(c.background)) {
        val d = data
        when {
            error != null -> Text(error!!, Modifier.align(Alignment.Center).padding(Space.xl), color = c.mutedForeground, textAlign = TextAlign.Center)
            d == null -> Column(Modifier.align(Alignment.Center).padding(Space.xl), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.comic_preparing, measured, total), style = MaterialTheme.typography.bodyMedium, color = c.mutedForeground)
                Spacer(Modifier.height(Space.md))
                LinearProgressIndicator(progress = { if (total == 0) 0f else measured / total.toFloat() }, Modifier.width(200.dp))
            }
            d.slices.isEmpty() -> Text(stringResource(R.string.comic_empty), Modifier.align(Alignment.Center).padding(Space.xl), color = c.mutedForeground)
            else -> ComicList(d, store.get(d.book.key), decoder, onTap = { chrome = !chrome }) { pos -> store.put(d.book.key, pos) }
        }

        AnimatedVisibility(chrome, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            Surface(color = c.background.copy(alpha = 0.92f), contentColor = c.foreground) {
                Column(Modifier.statusBarsPadding()) {
                    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                        Text(d?.book?.title ?: "", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(Space.md))
                    }
                    androidx.compose.material3.HorizontalDivider(thickness = Hairline, color = c.border)
                }
            }
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun ComicList(d: ComicData, saved: ScrollPos?, decoder: ComicDecoder, onTap: () -> Unit, onSave: (ScrollPos) -> Unit) {
    val c = ChoiTheme.colors
    val start = remember { ComicLayout.resolve(saved?.index ?: 0, saved?.anchor, d.anchors) }
    val state = remember {
        val sameItem = saved != null && (saved.anchor == null || d.anchors.getOrNull(start) == saved.anchor)
        LazyListState(start, if (sameItem) saved!!.offset else 0)
    }
    fun current() = ScrollPos(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, d.anchors.getOrNull(state.firstVisibleItemIndex))

    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .debounce(500)
            .collect { withContext(Dispatchers.IO) { onSave(current()) } }
    }
    DisposableEffect(state) { onDispose { onSave(current()) } }

    val page by remember { derivedStateOf { d.slices.getOrNull(state.firstVisibleItemIndex)?.page ?: 0 } }

    BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        LazyColumn(Modifier.fillMaxSize(), state = state) {
            items(d.slices.size, key = { it }) { i ->
                val s = d.slices[i]
                val whole = s.top == 0 && d.slices.getOrNull(i + 1)?.page != s.page
                ComicStrip(d, s, whole, widthPx, decoder)
            }
        }
        Surface(
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(Space.md),
            shape = RoundedCornerShape(Radius.md),
            color = c.background.copy(alpha = 0.85f),
            contentColor = c.mutedForeground,
            border = androidx.compose.foundation.BorderStroke(Hairline, c.border),
        ) {
            Text(stringResource(R.string.comic_page, page + 1, d.book.pages.size), Modifier.padding(horizontal = Space.sm, vertical = Space.xs), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ComicStrip(d: ComicData, s: ComicSlice, whole: Boolean, widthPx: Int, decoder: ComicDecoder) {
    val c = ChoiTheme.colors
    if (s.width <= 0) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.4f).background(c.muted), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.comic_bad_page, d.book.pages[s.page]), Modifier.padding(Space.lg), color = c.mutedForeground, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
        return
    }
    val key = "${s.page}:${s.part}:$widthPx"
    val bitmap by produceState<Bitmap?>(decoder.cached(key), key) {
        if (value == null) value = withContext(Dispatchers.IO) { runCatching { decoder.decode(key, d.book.fileFor(s.page), s, widthPx, whole) }.getOrNull() }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(s.aspect)) {
        val b = bitmap
        if (b != null && !b.isRecycled) {
            Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
    }
}
