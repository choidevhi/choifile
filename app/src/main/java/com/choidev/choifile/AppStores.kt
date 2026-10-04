package com.choidev.choifile

import android.content.Context
import com.choidev.choifile.oplog.OperationLog
import com.choidev.choifile.rules.RuleStore
import com.choidev.choifile.storage.PositionStore
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
}
