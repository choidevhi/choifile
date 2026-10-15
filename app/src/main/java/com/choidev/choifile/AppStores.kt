package com.choidev.choifile

import android.content.Context
import com.choidev.choifile.oplog.OperationLog
import com.choidev.choifile.rules.RuleStore
import com.choidev.choifile.storage.FavoritesStore
import com.choidev.choifile.storage.FolderViewModes
import com.choidev.choifile.storage.KeyedLongStore
import com.choidev.choifile.storage.PositionStore
import com.choidev.choifile.storage.VideoResume
import java.io.File

/**
 * Process-wide instances shared by the UI and the background worker, so two writers never overwrite each other's
 * copy of the same file.
 */
object AppStores {
    @Volatile private var log: OperationLog? = null
    @Volatile private var rules: RuleStore? = null
    @Volatile private var reading: PositionStore? = null
    @Volatile private var scroll: PositionStore? = null
    @Volatile private var favorites: FavoritesStore? = null
    @Volatile private var viewModes: FolderViewModes? = null
    @Volatile private var videoResume: VideoResume? = null
    @Volatile private var subtitleDelays: KeyedLongStore? = null
    @Volatile private var folderSorts: KeyedLongStore? = null

    fun log(context: Context): OperationLog = log ?: synchronized(this) {
        log ?: OperationLog(File(context.applicationContext.filesDir, "operations.json")).also { log = it }
    }

    fun rules(context: Context): RuleStore = rules ?: synchronized(this) {
        rules ?: RuleStore(File(context.applicationContext.filesDir, "rules.json")).also { rules = it }
    }

    /** Comic viewer: last position per folder or archive. Cleared together with the cache in settings. */
    fun readingPositions(context: Context): PositionStore = reading ?: synchronized(this) {
        reading ?: PositionStore(File(context.applicationContext.filesDir, "reading_positions.json"), max = 500).also { reading = it }
    }

    /** File list: last scroll position per folder, so tabs and "up" land where the user left off. */
    fun scrollPositions(context: Context): PositionStore = scroll ?: synchronized(this) {
        scroll ?: PositionStore(File(context.applicationContext.filesDir, "scroll_positions.json"), max = 300).also { scroll = it }
    }

    /** Drawer: the user's starred folders, in the user's order. */
    fun favorites(context: Context): FavoritesStore = favorites ?: synchronized(this) {
        favorites ?: FavoritesStore(File(context.applicationContext.filesDir, "favorites.json")).also { favorites = it }
    }

    /** File list: grid or list per folder. */
    fun viewModes(context: Context): FolderViewModes = viewModes ?: synchronized(this) {
        viewModes ?: FolderViewModes(File(context.applicationContext.filesDir, "view_modes.json")).also { viewModes = it }
    }

    /** Video player: where each video was left. Cleared together with the cache in settings. */
    fun videoResume(context: Context): VideoResume = videoResume ?: synchronized(this) {
        videoResume ?: VideoResume(File(context.applicationContext.filesDir, "video_resume.json")).also { videoResume = it }
    }

    /** File list: folders the user gave an order of their own (SortSpec.pack()). */
    fun folderSorts(context: Context): KeyedLongStore = folderSorts ?: synchronized(this) {
        folderSorts ?: KeyedLongStore(File(context.applicationContext.filesDir, "folder_sorts.json"), max = 1000).also { folderSorts = it }
    }

    /** Video player: subtitle delay in ms per video, for videos where it was changed. Cleared with the cache. */
    fun subtitleDelays(context: Context): KeyedLongStore = subtitleDelays ?: synchronized(this) {
        subtitleDelays ?: KeyedLongStore(File(context.applicationContext.filesDir, "subtitle_delays.json")).also { subtitleDelays = it }
    }
}
