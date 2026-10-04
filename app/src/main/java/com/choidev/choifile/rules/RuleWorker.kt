package com.choidev.choifile.rules

import android.content.Context
import android.os.Environment
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.choidev.choifile.AppStores
import com.choidev.choifile.oplog.FileOps
import com.choidev.choifile.security.AppPrefs
import com.choidev.choifile.security.PathGuard
import com.choidev.choifile.storage.LocalFileStore
import com.choidev.choifile.util.hasAllFilesAccess
import java.util.concurrent.TimeUnit

/**
 * Daily run of every enabled, reviewed rule with the daily trigger. It runs without the user, so it treats every
 * protected folder as off limits (it cannot authenticate), only trashes, moves or zips, and logs through the same
 * operation log as manual work.
 */
class RuleWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        if (!hasAllFilesAccess(ctx)) return Result.success()
        val store = AppStores.rules(ctx)
        val ops = FileOps(LocalFileStore(Environment.getExternalStorageDirectory()), AppStores.log(ctx))
        val guard = PathGuard(AppPrefs(ctx).protectedFolders, excludeAllProtected = true)
        val now = System.currentTimeMillis()
        for (rule in store.all().filter { it.runsAutomatically }) {
            if (isStopped) break
            val outcome = try {
                val r = RuleEngine.run(rule, ops, guard, now, automatic = true, cancelled = { isStopped })
                if (r.skippedTooMany) "many" else "ok:${r.acted}"
            } catch (e: Exception) {
                "error"
            }
            store.get(rule.id)?.let { store.upsert(it.copy(lastRun = now, lastResult = outcome)) }
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "choifile_rules"

        /** Schedules the daily worker while at least one rule runs automatically, and cancels it otherwise. */
        fun sync(context: Context, rules: List<Rule>) {
            val wm = WorkManager.getInstance(context)
            if (rules.any { it.runsAutomatically }) {
                wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<RuleWorker>(24, TimeUnit.HOURS).build())
            } else {
                wm.cancelUniqueWork(NAME)
            }
        }
    }
}
