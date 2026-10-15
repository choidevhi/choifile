package com.choidev.choifile.ebook

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.choidev.choifile.R
import com.choidev.choifile.ebook.data.Fonts
import com.choidev.choifile.ebook.data.Library
import com.choidev.choifile.ebook.data.SettingsStore
import com.choidev.choifile.ebook.reader.ReaderScreen
import com.choidev.choifile.ebook.ui.IosColors
import com.choidev.choifile.ebook.ui.LocalIos
import com.choidev.choifile.ui.ChoiTheme
import java.io.File

/** The e-reader's stores, one per process (they hold the reading positions and settings of every book). */
object EbookStores {
    @Volatile private var library: Library? = null
    @Volatile private var settings: SettingsStore? = null
    @Volatile private var fonts: Fonts? = null

    fun library(context: Context): Library = library ?: synchronized(this) {
        library ?: Library(context.applicationContext).also { library = it }
    }

    fun settings(context: Context): SettingsStore = settings ?: synchronized(this) {
        settings ?: SettingsStore(context.applicationContext).also { settings = it }
    }

    fun fonts(context: Context): Fonts = fonts ?: synchronized(this) {
        fonts ?: Fonts(context.applicationContext).also { fonts = it }
    }
}

/** Plain-text files the reader opens: novels and other prose saved as text. */
val EBOOK_EXTENSIONS = setOf("txt")

/** Text files at least this large open in the reader by default; smaller ones open in the text viewer. */
const val EBOOK_DEFAULT_MIN_BYTES = 100L * 1024

/**
 * A text file read as a book (the e-reader from choireader, built in): pages with page-turn effects, fonts, themes,
 * table of contents, search and bookmarks. The file is decoded once (any encoding), kept as a UTF-8 copy in the
 * app's library and found again by name and length, so reopening it continues where the reading stopped.
 */
@Composable
fun EbookScreen(file: File, onClose: () -> Unit) {
    val context = LocalContext.current
    val library = remember { EbookStores.library(context) }
    val settings = remember { EbookStores.settings(context) }
    val fonts = remember { EbookStores.fonts(context) }
    val books by library.books.collectAsState()
    // null while importing, "" when the file could not be read.
    val id by produceState<String?>(null, file.path, file.lastModified()) {
        value = runCatching { library.import(Uri.fromFile(file)).id }.getOrDefault("")
    }
    val colors = if (isSystemInDarkTheme()) IosColors.Dark else IosColors.Light
    CompositionLocalProvider(LocalIos provides colors) {
        val book = id?.takeIf { it.isNotEmpty() }?.let { i -> books.firstOrNull { it.id == i } }
        if (book != null) {
            ReaderScreen(book = book, library = library, settingsStore = settings, fonts = fonts, onBack = onClose)
        } else {
            BackHandler(onBack = onClose)
            Box(Modifier.fillMaxSize().background(ChoiTheme.colors.background), contentAlignment = Alignment.Center) {
                if (id == null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = ChoiTheme.colors.primary)
                        Text(stringResource(R.string.ebook_opening), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyMedium, color = ChoiTheme.colors.mutedForeground)
                    }
                } else {
                    Text(stringResource(R.string.ebook_unreadable), style = MaterialTheme.typography.bodyLarge, color = ChoiTheme.colors.destructive)
                }
            }
        }
    }
}
