package com.choidev.choifile.ebook.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.choidev.choifile.MainActivity
import com.choidev.choifile.R
import com.choidev.choifile.ebook.data.Book
import com.choidev.choifile.ebook.data.Bookmark
import com.choidev.choifile.ebook.data.Fonts
import com.choidev.choifile.ebook.data.Library
import com.choidev.choifile.ebook.data.SettingsStore
import com.choidev.choifile.ebook.data.TurnMode
import com.choidev.choifile.ebook.ui.BottomSheet
import com.choidev.choifile.ebook.ui.Hairline
import com.choidev.choifile.ebook.ui.IosColors
import com.choidev.choifile.ebook.ui.IosSlider
import com.choidev.choifile.ebook.ui.LocalIos
import com.choidev.choifile.ebook.ui.pressable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class ReaderSheet { NONE, SETTINGS, TOC, SEARCH }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReaderScreen(
    book: Book,
    library: Library,
    settingsStore: SettingsStore,
    fonts: Fonts,
    onBack: () -> Unit,
    // choifile: switches to the plain text viewer; null hides the button.
    onPlainText: (() -> Unit)? = null,
) {
    val settings = settingsStore.value
    val theme = settings.theme
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    val chrome = if (theme.dark) IosColors.Dark else IosColors.Light

    // 본문과 그 본문이 줄바꿈 정리본인지를 함께 들고 다닌다. 정리 스위치를 바꾼 직후에는 새 본문을 읽기 전까지
    // 옛 본문이 남아 있어서, 스위치 값이 아니라 실제로 읽어 온 본문 기준으로 쪽을 나눠야 쪽이 어긋나지 않는다.
    val loaded by produceState<LoadedText?>(null, book.id, book.reflow) {
        value = LoadedText(library.readText(book.id, book.reflow), book.reflow)
    }
    val text = loaded?.text
    val chapters by produceState(emptyList<Chapter>(), text) {
        val t = text ?: return@produceState
        value = withContext(Dispatchers.Default) { Chapters.detect(t) }
    }

    // 지금 보는 쪽의 첫 글자 위치. 쪽 번호는 이 값에서 계산한다.
    var anchor by rememberSaveable(book.id) { mutableIntStateOf(book.offset) }
    var menuVisible by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(ReaderSheet.NONE) }
    var notice by remember { mutableStateOf<String?>(null) }
    var fontsVersion by remember { mutableIntStateOf(0) }
    // 검색 결과로 들어온 쪽에서 찾은 글자를 칠해 둔다. 쪽을 넘기면 지운다.
    var highlight by remember { mutableStateOf<IntRange?>(null) }

    // 읽던 위치 자동 저장: 쪽이 바뀌면 잠깐 뒤에, 앱이 가려질 때와 화면을 떠날 때는 바로.
    val latestAnchor by rememberUpdatedState(anchor)
    LaunchedEffect(anchor) {
        delay(300)
        withContext(Dispatchers.IO) { library.savePosition(book.id, anchor) }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, book.id) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) library.savePosition(book.id, latestAnchor)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            library.savePosition(book.id, latestAnchor)
        }
    }

    WindowEffects(activity, menuVisible || sheet != ReaderSheet.NONE, theme.dark, settings.keepScreenOn, settings.brightness)

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(1400)
            notice = null
        }
    }

    BackHandler {
        when {
            sheet != ReaderSheet.NONE -> sheet = ReaderSheet.NONE
            menuVisible -> menuVisible = false
            else -> onBack()
        }
    }

    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching { fonts.import(uri) }
                    .onSuccess { option ->
                        fontsVersion++
                        settingsStore.update { it.copy(fontId = option.id) }
                    }
                    .onFailure { Toast.makeText(context, it.message ?: context.getString(R.string.eb_error_font_add), Toast.LENGTH_SHORT).show() }
            }
        }
    }

    CompositionLocalProvider(LocalIos provides chrome) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Color(theme.background)),
        ) {
            val w = constraints.maxWidth
            val h = constraints.maxHeight
            val density = LocalDensity.current
            // 메뉴를 열고 닫을 때 상태 표시줄이 숨었다 나타나도 쪽 크기가 바뀌지 않게, 보이는지와 상관없는 값을 쓴다.
            val topInset = maxOf(
                WindowInsets.statusBarsIgnoringVisibility.getTop(density),
                WindowInsets.displayCutout.getTop(density),
            )
            val bottomInset = WindowInsets.navigationBarsIgnoringVisibility.getBottom(density)
            val typeface = remember(settings.fontId, fontsVersion) { fonts.typeface(settings.fontId) }

            val cfg = remember(w, h, topInset, bottomInset, settings, typeface) {
                with(density) {
                    val margin = settings.marginDp.dp.roundToPx()
                    PageConfig(
                        width = w,
                        height = h,
                        paddingLeft = margin,
                        paddingRight = margin,
                        paddingTop = topInset + 40.dp.roundToPx(),
                        paddingBottom = bottomInset + 46.dp.roundToPx(),
                        textSizePx = settings.fontSizeSp.sp.toPx(),
                        lineSpacing = settings.lineSpacing,
                        typeface = typeface,
                        fontId = settings.fontId + "#" + fontsVersion,
                        justify = settings.justify,
                        textColor = theme.text.toInt(),
                        background = theme.background.toInt(),
                        chromeTextSizePx = 11.sp.toPx(),
                        headerBaseline = topInset + 24.dp.toPx(),
                        footerBaseline = h - bottomInset - 18.dp.toPx(),
                    )
                }
            }

            var pages by remember(book.id) { mutableStateOf<IntArray?>(null) }
            var pagesKey by remember(book.id) { mutableStateOf("") }
            var progress by remember { mutableFloatStateOf(0f) }

            // 줄바꿈 정리를 켜고 끄면 같은 크기라도 쪽이 달라지므로 캐시 이름에 넣는다.
            val pagesCacheKey = cfg.cacheKey + if (loaded?.reflow == true) "_r" else "_o"
            LaunchedEffect(text, pagesCacheKey) {
                val t = text ?: return@LaunchedEffect
                val key = pagesCacheKey
                if (key == pagesKey && pages != null) return@LaunchedEffect

                pages = null
                progress = 0f
                val file = File(context.cacheDir, "pages/${book.id}_$key.bin")
                val config = cfg
                val result = withContext(Dispatchers.Default) {
                    Paginator.readCache(file)?.takeIf { it.isNotEmpty() && it.last() < t.length }
                        ?: Paginator.paginate(t, config, isActive = { isActive }, onProgress = { progress = it })
                            .also { Paginator.writeCache(file, it) }
                }
                pages = result
                pagesKey = key
            }

            // 전체 쪽 계산이 끝나기 전에는 읽던 곳부터 몇 쪽만 먼저 나눠서 바로 보여 준다.
            val provisional = pages == null
            val shownPages = pages ?: remember(text, cfg, anchor) {
                text?.let { Paginator.paginate(it, cfg, from = anchor, maxChars = 8000) }
            }
            val pageCount = shownPages?.size ?: 0
            val index = when {
                shownPages == null -> 0
                provisional -> 0
                else -> Paginator.indexFor(shownPages, anchor)
            }

            val pageStart = shownPages?.getOrNull(index) ?: anchor
            val pageEnd = shownPages?.getOrNull(index + 1) ?: Int.MAX_VALUE
            val pageBookmark = book.bookmarks.firstOrNull { it.offset in pageStart until pageEnd }

            fun toggleBookmark() {
                val t = text ?: return
                if (pageBookmark != null) {
                    library.removeBookmark(book.id, pageBookmark.offset)
                    notice = context.getString(R.string.eb_bookmark_removed)
                } else {
                    val snippet = t.substring(pageStart.coerceIn(0, t.length), (pageStart + 80).coerceAtMost(t.length)).cleanSnippet()
                    library.addBookmark(book.id, Bookmark(pageStart, System.currentTimeMillis(), snippet))
                    notice = context.getString(R.string.eb_bookmark_added)
                }
            }

            fun jumpTo(offset: Int) {
                val p = pages
                anchor = if (p != null) p[Paginator.indexFor(p, offset)] else offset
                sheet = ReaderSheet.NONE
                menuVisible = false
            }

            fun headerFor(offset: Int): String {
                val i = Chapters.at(chapters, offset)
                return if (i >= 0) chapters[i].title else book.title
            }

            val renderer = remember(text, cfg, shownPages, chapters, highlight) {
                val t = text
                if (t == null || shownPages == null) {
                    null
                } else {
                    PageRenderer(
                        text = t,
                        cfg = cfg,
                        pages = shownPages,
                        headerFor = ::headerFor,
                        footerFor = { i -> if (pages == null) context.getString(R.string.eb_paginating) else "${i + 1} / ${shownPages.size}" },
                        highlight = highlight,
                    )
                }
            }

            // 지금 쪽 앞뒤를 미리 그려 둬서, 넘기기 시작할 때 그리느라 멈칫하지 않게 한다.
            LaunchedEffect(renderer, index) {
                val r = renderer ?: return@LaunchedEffect
                withContext(Dispatchers.Default) { r.prefetch(index) }
            }
            DisposableEffect(renderer) {
                onDispose { renderer?.release() }
            }

            val turner = remember { PageTurner(scope) }
            val pageSound = remember { PageSound(context.applicationContext) }
            DisposableEffect(pageSound) {
                onDispose { pageSound.release() }
            }
            val soundOn by rememberUpdatedState(settings.pageSound)
            turner.onTurnStart = { if (soundOn) pageSound.play() }
            turner.width = w.toFloat()
            turner.height = h.toFloat()
            turner.mode = settings.turnMode
            turner.canTurn = { dir ->
                val p = pages
                if (p == null) {
                    false
                } else {
                    val i = Paginator.indexFor(p, anchor)
                    if (dir == TurnDir.NEXT) i < p.size - 1 else i > 0
                }
            }
            turner.onCommit = { dir ->
                val p = pages
                if (p != null) {
                    val i = Paginator.indexFor(p, anchor)
                    val next = (i + if (dir == TurnDir.NEXT) 1 else -1).coerceIn(0, p.size - 1)
                    anchor = p[next]
                    highlight = null
                }
            }

            fun requestTurn(dir: TurnDir) {
                when {
                    pages == null -> notice = context.getString(R.string.eb_paginating_notice)
                    turner.isAnimating || turner.canTurn(dir) -> turner.tap(dir)
                    dir == TurnDir.NEXT -> notice = context.getString(R.string.eb_last_page)
                    else -> notice = context.getString(R.string.eb_first_page)
                }
            }

            val onTap by rememberUpdatedState<(Offset) -> Unit> { pos ->
                when {
                    sheet != ReaderSheet.NONE -> sheet = ReaderSheet.NONE
                    menuVisible -> menuVisible = false
                    pos.x < w * 0.3f -> requestTurn(TurnDir.PREV)
                    pos.x > w * 0.7f -> requestTurn(TurnDir.NEXT)
                    else -> menuVisible = true
                }
            }
            val onBlocked by rememberUpdatedState<(TurnDir) -> Unit> { dir ->
                notice = when {
                    pages == null -> context.getString(R.string.eb_paginating_notice)
                    dir == TurnDir.NEXT -> context.getString(R.string.eb_last_page)
                    else -> context.getString(R.string.eb_first_page)
                }
            }

            DisposableEffect(activity, settings.volumeKeys) {
                val main = activity as? MainActivity
                main?.volumeKeyHandler = if (settings.volumeKeys) {
                    { down -> if (sheet == ReaderSheet.NONE) requestTurn(if (down) TurnDir.NEXT else TurnDir.PREV); true }
                } else {
                    null
                }
                onDispose { main?.volumeKeyHandler = null }
            }

            val curl = remember(density) { PageCurl(density.density) }

            // GPU는 넘김 효과를 처음 그릴 때 그리는 방법을 준비하느라 한 프레임(약 20ms)을 더 쓴다.
            // 그래서 책을 연 직후 넘김 효과를 화면에 1픽셀만 보이게 잘라 한 번 미리 그려 둬서 첫 넘김에서 멈칫하지 않게 한다.
            var curlWarmedUp by remember(renderer) { mutableStateOf(false) }
            LaunchedEffect(renderer) {
                withFrameNanos { }
                withFrameNanos { }
                curlWarmedUp = true
            }

            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val tracker = VelocityTracker()
                            tracker.addPosition(down.uptimeMillis, down.position)
                            val slop = viewConfiguration.touchSlop
                            // 0: 아직 모름, 1: 넘기는 중, 2: 이번 손동작은 무시, 3: 움직이던 종이를 잡고만 있음
                            var state = if (turner.grab()) 3 else 0
                            // 최근 손가락 위치(시각, x). 떼기 직전 0.1초 동안 어느 쪽으로 움직였는지 보려고 둔다.
                            val history = ArrayDeque<Pair<Long, Float>>()
                            history.addLast(down.uptimeMillis to down.position.x)
                            val dirSlop = 6.dp.toPx()

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                tracker.addPosition(change.uptimeMillis, change.position)
                                history.addLast(change.uptimeMillis to change.position.x)
                                // 0.1초보다 오래된 위치는 가장 최근 것 하나만 남긴다(기준점).
                                while (history.size > 2 && history[1].first <= change.uptimeMillis - 100) history.removeFirst()

                                if (!change.pressed) {
                                    when (state) {
                                        0 -> onTap(change.position)
                                        1 -> {
                                            val moved = change.position.x - history.first().second
                                            val recentDir = when {
                                                moved <= -dirSlop -> -1
                                                moved >= dirSlop -> 1
                                                else -> 0
                                            }
                                            val v = tracker.calculateVelocity()
                                            turner.release(Offset(v.x, v.y), recentDir)
                                        }
                                        // 넘어가던 종이를 톡 건드리기만 했으면 하던 넘김을 마저 하고, 탭은 그대로 처리한다.
                                        3 -> {
                                            turner.resume()
                                            onTap(change.position)
                                        }
                                    }
                                    break
                                }

                                val d = change.position - down.position
                                if (state == 3 && (abs(d.x) > slop || abs(d.y) > slop)) {
                                    turner.follow(change.position)
                                    state = 1
                                } else if (state == 0 && abs(d.x) > slop) {
                                    state = 2
                                    if (abs(d.x) > abs(d.y)) {
                                        val dir = if (d.x < 0) TurnDir.NEXT else TurnDir.PREV
                                        when {
                                            turner.mode == TurnMode.NONE -> if (turner.canTurn(dir)) turner.tap(dir) else onBlocked(dir)
                                            turner.beginDrag(dir, down.position, change.position) -> state = 1
                                            else -> onBlocked(dir)
                                        }
                                    }
                                }

                                if (state == 1) {
                                    change.consume()
                                    turner.dragTo(change.position)
                                }
                            }

                            if ((state == 1 || state == 3) && turner.turn != null && !turner.isAnimating) turner.release(Offset.Zero)
                        }
                    },
            ) {
                val r = renderer ?: return@Canvas
                drawIntoCanvas { c ->
                    val nc = c.nativeCanvas
                    val t = turner.turn
                    if (t == null || provisional) {
                        r.draw(nc, index)
                        if (!curlWarmedUp && !provisional && index + 1 < r.pageCount) {
                            // 실제 크기로 그려야 GPU가 실제 넘김과 같은 방법을 준비한다. 화면에는 1픽셀씩만 남도록 잘라서,
                            // 다음 쪽이 드러나는 자리(오른쪽 아래 구석) 한 점과 뒤집힌 종이 자리 한 점에 그린다.
                            val wx = size.width
                            val hy = size.height
                            for ((px, py) in listOf(wx - 2f to hy - 2f, wx * 0.4f to hy * 0.85f)) {
                                nc.save()
                                nc.clipRect(px, py, px + 1f, py + 1f)
                                curl.draw(
                                    nc, wx, hy, wx * 0.2f, hy * 0.8f, hy, cfg.background,
                                    drawFront = { r.draw(it, index) },
                                    drawBack = { r.draw(it, index, back = true) },
                                    drawUnder = { r.draw(it, index + 1) },
                                )
                                nc.restore()
                            }
                        }
                        return@drawIntoCanvas
                    }

                    val front = if (t.dir == TurnDir.NEXT) index else index - 1
                    val under = if (t.dir == TurnDir.NEXT) index + 1 else index
                    val touch = turner.touch

                    when (turner.mode) {
                        TurnMode.CURL -> curl.draw(
                            nc, size.width, size.height, touch.x, touch.y, t.cornerY, cfg.background,
                            drawFront = { r.draw(it, front) },
                            drawBack = { r.draw(it, front, back = true) },
                            drawUnder = { r.draw(it, under) },
                        )

                        TurnMode.SLIDE -> {
                            val shift = (touch.x - size.width) / 2f
                            nc.save()
                            nc.translate(shift + size.width, 0f)
                            r.draw(nc, under)
                            nc.restore()
                            nc.save()
                            nc.translate(shift, 0f)
                            r.draw(nc, front)
                            nc.restore()
                        }

                        TurnMode.NONE -> r.draw(nc, index)
                    }
                }
            }

            // 책갈피가 있는 쪽: 오른쪽 위에 리본. 넘기는 동안에는 숨긴다(종이와 함께 움직이지 않으므로).
            AnimatedVisibility(
                pageBookmark != null && turner.turn == null && !provisional,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = with(density) { cfg.paddingRight.toDp() }),
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(120)),
            ) {
                Box(
                    Modifier
                        .size(width = 16.dp, height = with(density) { (topInset + 30.dp.roundToPx()).toDp() })
                        .background(BookmarkRed, RibbonShape),
                )
            }

            // 위 막대: 서재로 돌아가기, 제목, 검색, 책갈피.
            AnimatedVisibility(
                menuVisible,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { -it / 2 },
                exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { -it / 2 },
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(chrome.bar.copy(alpha = 1f))
                        .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility),
                ) {
                    Box(Modifier.fillMaxWidth().height(48.dp)) {
                        Row(
                            Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 6.dp)
                                .pressable(onClick = onBack)
                                .padding(horizontal = 6.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBackIos, null, tint = chrome.accent, modifier = Modifier.size(20.dp))
                            Text(stringResource(R.string.back), color = chrome.accent, fontSize = 17.sp)
                        }
                        Text(
                            book.title,
                            color = chrome.label,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.Center).padding(horizontal = if (onPlainText != null) 128.dp else 96.dp),
                        )
                        Row(Modifier.align(Alignment.CenterEnd).padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (onPlainText != null) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.Notes,
                                    stringResource(R.string.ebook_view_as_text),
                                    tint = chrome.accent,
                                    modifier = Modifier
                                        .pressable(onClick = onPlainText)
                                        .padding(8.dp)
                                        .size(24.dp),
                                )
                            }
                            Icon(
                                Icons.Rounded.Search,
                                stringResource(R.string.eb_search),
                                tint = chrome.accent,
                                modifier = Modifier
                                    .pressable { sheet = ReaderSheet.SEARCH }
                                    .padding(8.dp)
                                    .size(24.dp),
                            )
                            Icon(
                                if (pageBookmark != null) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                                stringResource(if (pageBookmark != null) R.string.eb_bookmark_remove else R.string.eb_bookmark_add),
                                tint = if (pageBookmark != null) BookmarkRed else chrome.accent,
                                modifier = Modifier
                                    .pressable(enabled = !provisional) { toggleBookmark() }
                                    .padding(8.dp)
                                    .size(24.dp),
                            )
                        }
                    }
                    Hairline()
                }
            }

            // 아래 막대: 쪽 이동 슬라이더, 쪽 번호, 보기 설정.
            AnimatedVisibility(
                menuVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 2 },
                exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 2 },
            ) {
                var scrub by remember { mutableStateOf<Float?>(null) }
                val last = (pageCount - 1).coerceAtLeast(1)
                val scrubIndex = scrub?.let { (it * last).roundToInt() }
                val shownIndex = scrubIndex ?: index

                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(chrome.bar.copy(alpha = 1f))
                        .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility),
                ) {
                    Hairline()
                    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 10.dp)) {
                        IosSlider(
                            value = if (provisional) 0f else (scrub ?: (index.toFloat() / last)),
                            onValueChange = { if (!provisional) scrub = it },
                            onValueChangeFinished = {
                                val p = pages
                                val target = scrubIndex
                                if (p != null && target != null) {
                                    anchor = p[target.coerceIn(0, p.size - 1)]
                                    highlight = null
                                }
                                scrub = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.AutoMirrored.Rounded.FormatListBulleted,
                                stringResource(R.string.eb_toc),
                                tint = chrome.label,
                                modifier = Modifier
                                    .pressable { sheet = ReaderSheet.TOC }
                                    .padding(6.dp)
                                    .size(22.dp),
                            )
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                val label = when {
                                    provisional -> stringResource(R.string.eb_paginating_progress, (progress * 100).roundToInt())
                                    else -> stringResource(
                                        R.string.eb_page_position,
                                        shownIndex + 1,
                                        pageCount,
                                        ((shownIndex + 1) * 100f / pageCount).roundToInt(),
                                    )
                                }
                                Text(label, color = chrome.secondaryLabel, fontSize = 13.sp)
                                val ch = shownPages?.getOrNull(shownIndex)?.let { Chapters.at(chapters, it) } ?: -1
                                if (scrub != null && ch >= 0) {
                                    Text(chapters[ch].title, color = chrome.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                }
                            }
                            Text(
                                stringResource(R.string.eb_settings_button),
                                color = chrome.label,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .pressable { sheet = ReaderSheet.SETTINGS }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }

            // 첫 쪽·마지막 쪽 안내.
            AnimatedVisibility(
                notice != null,
                modifier = Modifier.align(Alignment.Center),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Text(
                    notice.orEmpty(),
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0xCC1C1C1E))
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }

            // 바꾼 설정이 본문에 바로 보이도록 시트를 화면 아래쪽 일부만 덮게 한다.
            BottomSheet(visible = sheet == ReaderSheet.SETTINGS, onDismiss = { sheet = ReaderSheet.NONE }, maxHeightFraction = 0.6f) {
                SettingsSheet(
                    settings = settings,
                    onChange = settingsStore::update,
                    fontOptions = remember(fontsVersion) { fonts.options() },
                    typefaceOf = fonts::typeface,
                    isCustomFont = fonts::isCustom,
                    onAddFont = { fontPicker.launch(arrayOf("*/*")) },
                    onDeleteFont = { id ->
                        fonts.delete(id)
                        fontsVersion++
                        if (settings.fontId == id) settingsStore.update { it.copy(fontId = Fonts.SYSTEM) }
                    },
                    onClose = { sheet = ReaderSheet.NONE },
                    reflow = book.reflow,
                    onReflowChange = { on -> library.setReflow(book.id, on) },
                )
            }

            BottomSheet(visible = sheet == ReaderSheet.TOC, onDismiss = { sheet = ReaderSheet.NONE }) {
                TocSheet(
                    chapters = chapters,
                    current = shownPages?.getOrNull(index)?.let { Chapters.at(chapters, it) } ?: -1,
                    bookmarks = book.bookmarks,
                    pageLabel = { offset ->
                        val p = pages
                        if (p == null) "" else "${Paginator.indexFor(p, offset) + 1}"
                    },
                    onSelect = { offset ->
                        highlight = null
                        jumpTo(offset)
                    },
                    onDeleteBookmark = { offset -> library.removeBookmark(book.id, offset) },
                    onClose = { sheet = ReaderSheet.NONE },
                )
            }

            BottomSheet(visible = sheet == ReaderSheet.SEARCH, onDismiss = { sheet = ReaderSheet.NONE }) {
                SearchSheet(
                    text = text.orEmpty(),
                    pageLabel = { offset ->
                        val p = pages
                        if (p == null) "" else context.getString(R.string.eb_page_label, Paginator.indexFor(p, offset) + 1)
                    },
                    onSelect = { hit ->
                        highlight = hit
                        jumpTo(hit.first)
                    },
                    onClose = { sheet = ReaderSheet.NONE },
                )
            }
        }
    }
}

/** 읽는 동안의 창 설정: 전체 화면, 상태 표시줄 아이콘 색, 화면 켜짐 유지, 밝기. 화면을 떠나면 되돌린다. */
@Composable
private fun WindowEffects(activity: Activity?, barsVisible: Boolean, darkTheme: Boolean, keepScreenOn: Boolean, brightness: Float) {
    val view = LocalView.current
    val systemDark = isSystemInDarkTheme()
    val window = activity?.window ?: return
    val controller = remember(window, view) { WindowCompat.getInsetsController(window, view) }

    DisposableEffect(barsVisible) {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (barsVisible) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { }
    }
    DisposableEffect(Unit) {
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
    DisposableEffect(darkTheme) {
        controller.isAppearanceLightStatusBars = !darkTheme
        controller.isAppearanceLightNavigationBars = !darkTheme
        onDispose {
            controller.isAppearanceLightStatusBars = !systemDark
            controller.isAppearanceLightNavigationBars = !systemDark
        }
    }
    DisposableEffect(keepScreenOn) {
        if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    DisposableEffect(brightness) {
        val attrs = window.attributes
        attrs.screenBrightness = if (brightness < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else brightness.coerceIn(0.02f, 1f)
        window.attributes = attrs
        onDispose {
            val reset = window.attributes
            reset.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = reset
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private class LoadedText(val text: String, val reflow: Boolean)

private val BookmarkRed = Color(0xFFFF3B30)

// 아래 끝이 V자로 파인 리본.
private val RibbonShape = GenericShape { size, _ ->
    moveTo(0f, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width, size.height)
    lineTo(size.width / 2f, size.height - size.width * 0.45f)
    lineTo(0f, size.height)
    close()
}

// 책갈피 미리보기 글: 폭 없는 공백을 빼고 줄바꿈과 연속 공백을 한 칸으로.
private fun String.cleanSnippet(): String = filterNot { it.code == 0x200B }.replace(Regex("\\s+"), " ").trim()
