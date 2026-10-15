package com.choidev.choifile.ui

import android.util.LruCache
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.choidev.choifile.R
import com.choidev.choifile.util.TextChunks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Read-only view of a text file of any size (logs, dumps, huge CSVs): only the chunks on screen are read and decoded,
 * a few kept in memory, so a 1 GB file opens as fast as a small one. The encoding is detected from the start.
 */
@Composable
fun LargeTextViewer(file: File, monospace: Boolean, modifier: Modifier = Modifier) {
    val chunks by produceState<TextChunks?>(null, file.path) {
        value = withContext(Dispatchers.IO) { runCatching { TextChunks.index(file) }.getOrNull() }
    }
    val c = ChoiTheme.colors
    val idx = chunks
    if (idx == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = c.primary)
                Text(stringResource(R.string.large_text_indexing), Modifier.padding(top = Space.lg), style = MaterialTheme.typography.bodyMedium, color = c.mutedForeground)
            }
        }
        return
    }
    val cache = remember(idx) { LruCache<Int, String>(24) }
    val style = TextStyle(
        fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
        fontSize = if (monospace) 13.sp else 16.sp, lineHeight = if (monospace) 19.sp else 25.sp, color = c.foreground,
    )
    Column(modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.large_text_note, idx.charset),
            Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.xs),
            style = MaterialTheme.typography.labelMedium, color = c.mutedForeground,
        )
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = androidx.compose.foundation.layout.PaddingValues(Space.lg)) {
                items(idx.size) { i ->
                    val text by produceState(cache.get(i), i) {
                        if (value == null) value = withContext(Dispatchers.IO) { runCatching { idx.read(i) }.getOrDefault("") }.also { cache.put(i, it) }
                    }
                    // A placeholder of roughly a chunk's height keeps the scroll bar steady while a chunk loads.
                    Text(text ?: "", Modifier.fillMaxWidth().heightIn(min = if (text == null) 400.dp else 0.dp), style = style)
                }
            }
        }
    }
}
