package com.choidev.choifile

import android.content.Context
import com.choidev.choifile.oplog.OperationLog
import com.choidev.choifile.rules.RuleStore
import java.io.File

/**
 * Process-wide instances shared by the UI and the background worker, so two writers never overwrite each other's
 * copy of the same file.
 */
object AppStores {
    @Volatile private var log: OperationLog? = null
    @Volatile private var rules: RuleStore? = null

    fun log(context: Context): OperationLog = log ?: synchronized(this) {
        log ?: OperationLog(File(context.applicationContext.filesDir, "operations.json")).also { log = it }
    }

    fun rules(context: Context): RuleStore = rules ?: synchronized(this) {
        rules ?: RuleStore(File(context.applicationContext.filesDir, "rules.json")).also { rules = it }
    }
}
