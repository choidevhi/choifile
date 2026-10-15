package com.choidev.choifile.ebook.reader

import android.graphics.Typeface
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.choidev.choifile.R
import com.choidev.choifile.ebook.data.Bookmark
import com.choidev.choifile.ebook.data.FontOption
import com.choidev.choifile.ebook.data.ReaderSettings
import com.choidev.choifile.ebook.data.ReaderThemes
import com.choidev.choifile.ebook.data.TurnMode
import com.choidev.choifile.ebook.ui.GroupedRow
import com.choidev.choifile.ebook.ui.GroupedSection
import com.choidev.choifile.ebook.ui.IosSlider
import com.choidev.choifile.ebook.ui.IosSwitch
import com.choidev.choifile.ebook.ui.LocalIos
import com.choidev.choifile.ebook.ui.SegmentedControl
import com.choidev.choifile.ebook.ui.VSpace
import com.choidev.choifile.ebook.ui.pressable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private val LineSpacings = listOf(1.4f, 1.7f, 2.0f, 2.3f)
private val LineSpacingLabels = listOf(
    R.string.eb_line_spacing_tight,
    R.string.eb_line_spacing_normal,
    R.string.eb_line_spacing_loose,
    R.string.eb_line_spacing_very_loose,
)
private val Margins = listOf(12, 24, 36)
private val MarginLabels = listOf(R.string.eb_margin_narrow, R.string.eb_margin_normal, R.string.eb_margin_wide)
private const val MIN_FONT = 12f
private const val MAX_FONT = 34f

@Composable
fun SettingsSheet(
    settings: ReaderSettings,
    onChange: ((ReaderSettings) -> ReaderSettings) -> Unit,
    fontOptions: List<FontOption>,
    typefaceOf: (String) -> Typeface,
    isCustomFont: (String) -> Boolean,
    onAddFont: () -> Unit,
    onDeleteFont: (String) -> Unit,
    onClose: () -> Unit,
    reflow: Boolean,
    onReflowChange: (Boolean) -> Unit,
) {
    var page by remember { mutableIntStateOf(0) }

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            if (targetState > initialState) {
                (slideInHorizontally(tween(260)) { it } + fadeIn()) togetherWith (slideOutHorizontally(tween(260)) { -it / 3 } + fadeOut())
            } else {
                (slideInHorizontally(tween(260)) { -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally(tween(260)) { it } + fadeOut())
            }
        },
        label = "settingsPage",
    ) { p ->
        if (p == 0) {
            MainSettings(settings, onChange, fontOptions, typefaceOf, reflow, onReflowChange, onOpenFonts = { page = 1 }, onClose = onClose)
        } else {
            FontSettings(settings, onChange, fontOptions, typefaceOf, isCustomFont, onAddFont, onDeleteFont, onBack = { page = 0 })
        }
    }
}

@Composable
private fun SheetHeader(title: String, onClose: (() -> Unit)? = null, onBack: (() -> Unit)? = null, backLabel: String = "") {
    val c = LocalIos.current

    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = 12.dp),
    ) {
        if (onBack != null) {
            Row(
                Modifier.align(Alignment.CenterStart).pressable(onClick = onBack).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBackIos, null, tint = c.accent, modifier = Modifier.size(18.dp))
                Text(backLabel, color = c.accent, fontSize = 17.sp)
            }
        }
        Text(title, color = c.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
        if (onClose != null) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(c.fill)
                    .pressable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Close, stringResource(R.string.eb_close), tint = c.secondaryLabel, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = LocalIos.current.secondaryLabel,
        fontSize = 13.sp,
        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 7.dp),
    )
}

@Composable
private fun MainSettings(
    settings: ReaderSettings,
    onChange: ((ReaderSettings) -> ReaderSettings) -> Unit,
    fontOptions: List<FontOption>,
    typefaceOf: (String) -> Typeface,
    reflow: Boolean,
    onReflowChange: (Boolean) -> Unit,
    onOpenFonts: () -> Unit,
    onClose: () -> Unit,
) {
    val c = LocalIos.current
    val fontName = fontOptions.firstOrNull { it.id == settings.fontId }?.name ?: stringResource(R.string.eb_font_system_sans)
    val currentFamily = remember(settings.fontId) { FontFamily(typefaceOf(settings.fontId)) }
    val lineSpacingLabels = LineSpacingLabels.map { stringResource(it) }
    val marginLabels = MarginLabels.map { stringResource(it) }
    val turnModeLabels = TurnMode.entries.associateWith { stringResource(it.label) }
    val glyphSample = stringResource(R.string.eb_glyph_sample)

    Column {
        SheetHeader(stringResource(R.string.eb_view_settings), onClose = onClose)
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
        ) {
            VSpace(6.dp)
            // 글자 크기: iOS 도서 앱처럼 작은 가 / 큰 가 두 버튼.
            GroupedSection {
                Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .pressable(enabled = settings.fontSizeSp > MIN_FONT) {
                                onChange { it.copy(fontSizeSp = (it.fontSizeSp - 1f).coerceAtLeast(MIN_FONT)) }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(glyphSample, color = c.label, fontSize = 15.sp, fontFamily = currentFamily)
                    }
                    Box(Modifier.width(0.5.dp).height(28.dp).background(c.separator))
                    Text(
                        "${settings.fontSizeSp.roundToInt()}",
                        color = c.secondaryLabel,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(48.dp),
                    )
                    Box(Modifier.width(0.5.dp).height(28.dp).background(c.separator))
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .pressable(enabled = settings.fontSizeSp < MAX_FONT) {
                                onChange { it.copy(fontSizeSp = (it.fontSizeSp + 1f).coerceAtMost(MAX_FONT)) }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(glyphSample, color = c.label, fontSize = 24.sp, fontFamily = currentFamily)
                    }
                }
            }

            VSpace(12.dp)
            GroupedSection {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.LightMode, null, tint = c.secondaryLabel, modifier = Modifier.size(16.dp))
                    IosSlider(
                        value = if (settings.brightness < 0f) 0.5f else settings.brightness,
                        onValueChange = { v -> onChange { it.copy(brightness = v.coerceAtLeast(0.02f)) } },
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Icon(Icons.Rounded.LightMode, null, tint = c.secondaryLabel, modifier = Modifier.size(24.dp))
                }
                Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
                GroupedRow(showDivider = false) {
                    Text(stringResource(R.string.eb_use_system_brightness), color = c.label, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    IosSwitch(settings.brightness < 0f) { on -> onChange { it.copy(brightness = if (on) -1f else 0.5f) } }
                }
            }

            SectionLabel(stringResource(R.string.eb_theme))
            val rows = ReaderThemes.chunked(3)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { theme ->
                            val selected = theme.id == settings.themeId
                            Column(
                                Modifier
                                    .weight(1f)
                                    .pressable { onChange { it.copy(themeId = theme.id) } },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1.6f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(theme.background))
                                        .border(
                                            if (selected) 2.5.dp else 0.5.dp,
                                            if (selected) c.accent else c.separator,
                                            RoundedCornerShape(12.dp),
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(stringResource(R.string.eb_theme_sample), color = Color(theme.text), fontSize = 19.sp, fontFamily = currentFamily)
                                }
                                Text(
                                    stringResource(theme.name),
                                    color = if (selected) c.accent else c.secondaryLabel,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier.padding(top = 5.dp),
                                )
                            }
                        }
                    }
                }
            }

            SectionLabel(stringResource(R.string.eb_font_and_layout))
            GroupedSection {
                GroupedRow(onClick = onOpenFonts) {
                    Text(stringResource(R.string.eb_font), color = c.label, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Text(fontName, color = c.secondaryLabel, fontSize = 16.sp, maxLines = 1)
                    Icon(Icons.Rounded.ChevronRight, null, tint = c.tertiaryLabel, modifier = Modifier.size(22.dp))
                }
                LabeledSegment(stringResource(R.string.eb_line_spacing)) {
                    val selected = LineSpacings.minBy { abs(it - settings.lineSpacing) }
                    SegmentedControl(
                        LineSpacings, selected, { lineSpacingLabels[LineSpacings.indexOf(it)] },
                        { v -> onChange { it.copy(lineSpacing = v) } },
                        Modifier.fillMaxWidth(),
                    )
                }
                LabeledSegment(stringResource(R.string.eb_margins)) {
                    val selected = Margins.minBy { abs(it - settings.marginDp) }
                    SegmentedControl(
                        Margins, selected, { marginLabels[Margins.indexOf(it)] },
                        { v -> onChange { it.copy(marginDp = v) } },
                        Modifier.fillMaxWidth(),
                    )
                }
                LabeledSegment(stringResource(R.string.eb_page_turn), divider = false) {
                    SegmentedControl(
                        TurnMode.entries, settings.turnMode, { turnModeLabels.getValue(it) },
                        { v -> onChange { it.copy(turnMode = v) } },
                        Modifier.fillMaxWidth(),
                    )
                }
            }

            SectionLabel(stringResource(R.string.eb_this_book))
            GroupedSection {
                GroupedRow(showDivider = false) {
                    Text(stringResource(R.string.eb_reflow), color = c.label, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    IosSwitch(reflow, onReflowChange)
                }
            }
            Text(
                stringResource(R.string.eb_reflow_footer),
                color = c.secondaryLabel,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )

            SectionLabel(stringResource(R.string.eb_reading))
            GroupedSection {
                GroupedRow {
                    Text(stringResource(R.string.eb_justify), color = c.label, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    IosSwitch(settings.justify) { on -> onChange { it.copy(justify = on) } }
                }
                GroupedRow {
                    Text(stringResource(R.string.eb_volume_keys), color = c.label, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    IosSwitch(settings.volumeKeys) { on -> onChange { it.copy(volumeKeys = on) } }
                }
                GroupedRow {
                    Text(stringResource(R.string.eb_keep_screen_on), color = c.label, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    IosSwitch(settings.keepScreenOn) { on -> onChange { it.copy(keepScreenOn = on) } }
                }
                GroupedRow(showDivider = false) {
                    Text(stringResource(R.string.eb_page_sound), color = c.label, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    IosSwitch(settings.pageSound) { on -> onChange { it.copy(pageSound = on) } }
                }
            }
            Text(
                stringResource(R.string.eb_tap_zones_footer),
                color = c.secondaryLabel,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }
    }
}

@Composable
private fun LabeledSegment(label: String, divider: Boolean = true, content: @Composable () -> Unit) {
    val c = LocalIos.current

    Column {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(label, color = c.label, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
            content()
        }
        if (divider) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
    }
}

@Composable
private fun FontSettings(
    settings: ReaderSettings,
    onChange: ((ReaderSettings) -> ReaderSettings) -> Unit,
    fontOptions: List<FontOption>,
    typefaceOf: (String) -> Typeface,
    isCustomFont: (String) -> Boolean,
    onAddFont: () -> Unit,
    onDeleteFont: (String) -> Unit,
    onBack: () -> Unit,
) {
    val c = LocalIos.current

    Column {
        SheetHeader(stringResource(R.string.eb_font), onBack = onBack, backLabel = stringResource(R.string.eb_view_settings))
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
        ) {
            VSpace(6.dp)
            GroupedSection {
                fontOptions.forEachIndexed { i, option ->
                    val family = remember(option.id) { FontFamily(typefaceOf(option.id)) }
                    GroupedRow(showDivider = i < fontOptions.lastIndex, onClick = { onChange { it.copy(fontId = option.id) } }) {
                        Text(
                            option.name,
                            color = c.label,
                            fontSize = 18.sp,
                            fontFamily = family,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (isCustomFont(option.id)) {
                            Text(
                                stringResource(R.string.eb_delete),
                                color = c.destructive,
                                fontSize = 15.sp,
                                modifier = Modifier
                                    .pressable { onDeleteFont(option.id) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                        if (option.id == settings.fontId) {
                            Icon(Icons.Rounded.Check, stringResource(R.string.eb_selected), tint = c.accent, modifier = Modifier.size(22.dp))
                        } else {
                            Spacer(Modifier.width(22.dp))
                        }
                    }
                }
            }
            VSpace(20.dp)
            GroupedSection {
                GroupedRow(showDivider = false, onClick = onAddFont) {
                    Text(stringResource(R.string.eb_add_font_file), color = c.accent, fontSize = 16.sp)
                }
            }
            Text(
                stringResource(R.string.eb_add_font_footer),
                color = c.secondaryLabel,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }
    }
}

@Composable
fun TocSheet(
    chapters: List<Chapter>,
    current: Int,
    bookmarks: List<Bookmark>,
    pageLabel: (Int) -> String,
    onSelect: (offset: Int) -> Unit,
    onDeleteBookmark: (offset: Int) -> Unit,
    onClose: () -> Unit,
) {
    val c = LocalIos.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tocLabel = stringResource(R.string.eb_toc)
    val bookmarksLabel = stringResource(R.string.eb_bookmarks_tab, bookmarks.size)
    val locale = LocalConfiguration.current.locales[0]
    val datePattern = stringResource(R.string.eb_bookmark_date_format)

    Column {
        SheetHeader(tocLabel, onClose = onClose)
        SegmentedControl(
            listOf(0, 1), tab, { if (it == 0) tocLabel else bookmarksLabel }, { tab = it },
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp),
        )

        if (tab == 0) {
            if (chapters.isEmpty()) {
                EmptyNote(stringResource(R.string.eb_toc_empty_title), stringResource(R.string.eb_toc_empty_body))
                return@Column
            }

            val state = rememberLazyListState(initialFirstVisibleItemIndex = (current - 3).coerceAtLeast(0))
            LazyColumn(
                state = state,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.cell),
            ) {
                itemsIndexed(chapters) { i, chapter ->
                    val selected = i == current
                    GroupedRow(showDivider = i < chapters.lastIndex, onClick = { onSelect(chapter.offset) }) {
                        Text(
                            chapter.title,
                            color = if (selected) c.accent else c.label,
                            fontSize = 16.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(pageLabel(chapter.offset), color = c.secondaryLabel, fontSize = 14.sp)
                    }
                }
            }
            return@Column
        }

        if (bookmarks.isEmpty()) {
            EmptyNote(stringResource(R.string.eb_bookmarks_empty_title), stringResource(R.string.eb_bookmarks_empty_body))
            return@Column
        }

        LazyColumn(
            // 시트 안에서 남은 높이만 쓰게 해서 목록 끝이 잘리지 않게 한다.
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(c.cell),
        ) {
            itemsIndexed(bookmarks, key = { _, m -> m.offset }) { i, mark ->
                GroupedRow(showDivider = i < bookmarks.lastIndex, onClick = { onSelect(mark.offset) }) {
                    Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                        Text(
                            mark.snippet.ifBlank { stringResource(R.string.eb_empty_page) },
                            color = c.label,
                            fontSize = 15.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 20.sp,
                        )
                        val page = pageLabel(mark.offset)
                        val date = dateLabel(mark.createdAt, datePattern, locale)
                        Text(
                            if (page.isEmpty()) date else stringResource(R.string.eb_bookmark_page_date, page, date),
                            color = c.secondaryLabel,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Icon(
                        Icons.Rounded.Close,
                        stringResource(R.string.eb_bookmark_remove),
                        tint = c.tertiaryLabel,
                        modifier = Modifier
                            .pressable { onDeleteBookmark(mark.offset) }
                            .padding(8.dp)
                            .size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyNote(title: String, body: String) {
    val c = LocalIos.current

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = c.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        VSpace(8.dp)
        Text(body, color = c.secondaryLabel, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

private fun dateLabel(millis: Long, pattern: String, locale: Locale): String =
    if (millis <= 0L) "" else SimpleDateFormat(pattern, locale).format(Date(millis))

@Composable
fun SearchSheet(
    text: String,
    pageLabel: (Int) -> String,
    onSelect: (IntRange) -> Unit,
    onClose: () -> Unit,
) {
    val c = LocalIos.current
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    // 글자를 칠 때마다 찾지 않고, 입력이 잠깐 멈추면 찾는다. 큰 책은 백그라운드에서.
    val hits by produceState<List<SearchHit>?>(emptyList(), query, text) {
        if (query.isBlank()) {
            value = emptyList()
            return@produceState
        }
        value = null
        delay(250)
        value = withContext(Dispatchers.Default) { Search.find(text, query, isActive = { isActive }) }
    }

    LaunchedEffect(Unit) { focus.requestFocus() }

    Column {
        SheetHeader(stringResource(R.string.eb_search), onClose = onClose)
        Row(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .height(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(c.fill)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, tint = c.secondaryLabel, modifier = Modifier.size(18.dp))
            Box(Modifier.weight(1f).padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text(stringResource(R.string.eb_search_placeholder), color = c.tertiaryLabel, fontSize = 16.sp)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = c.label, fontSize = 16.sp),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (query.isNotEmpty()) {
                Icon(
                    Icons.Rounded.Cancel,
                    stringResource(R.string.eb_clear),
                    tint = c.tertiaryLabel,
                    modifier = Modifier.size(18.dp).pressable { query = "" },
                )
            }
        }

        val found = hits
        val status = when {
            query.isBlank() -> null
            found == null -> stringResource(R.string.eb_searching)
            found.isEmpty() -> stringResource(R.string.eb_no_results)
            found.size >= 500 -> stringResource(R.string.eb_results_capped)
            else -> stringResource(R.string.eb_results_count, found.size)
        }
        if (status != null) {
            Text(status, color = c.secondaryLabel, fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp))
        } else {
            VSpace(12.dp)
        }

        if (!found.isNullOrEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.cell),
            ) {
                itemsIndexed(found, key = { _, h -> h.range.first }) { i, hit ->
                    GroupedRow(showDivider = i < found.lastIndex, onClick = { onSelect(hit.range) }) {
                        Text(
                            buildAnnotatedString {
                                append(hit.before)
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = c.accent)) { append(hit.match) }
                                append(hit.after)
                            },
                            color = c.label,
                            fontSize = 15.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 20.sp,
                            modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                        )
                        Text(pageLabel(hit.range.first), color = c.secondaryLabel, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}
