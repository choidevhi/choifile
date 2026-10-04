package com.choidev.choifile.rules

import com.choidev.choifile.archive.ArchiveFormat
import com.choidev.choifile.archive.ArchiveWriter
import com.choidev.choifile.oplog.FileOps
import com.choidev.choifile.oplog.ruleOrigin
import com.choidev.choifile.security.PathGuard
import com.choidev.choifile.security.isSameOrInside
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** There is deliberately no "delete permanently" action: automation can only trash, move or archive. */
enum class RuleAction { MOVE, TRASH, COMPRESS }

enum class RuleTrigger { MANUAL, DAILY }

/** All set conditions must hold (AND). Only files are matched, never folders. */
data class RuleCondition(
    val folder: String,
    val recursive: Boolean = false,
    /** Glob over the file name: * and ? wildcards, case-insensitive. Blank matches every name. */
    val namePattern: String = "",
    val extensions: Set<String> = emptySet(),
    val olderThanDays: Int? = null,
    val minSize: Long? = null,
    val maxSize: Long? = null,
)

data class Rule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val condition: RuleCondition,
    val action: RuleAction,
    /** Destination folder for MOVE, and the folder the zip is written to for COMPRESS. */
    val target: String = "",
    val trigger: RuleTrigger = RuleTrigger.MANUAL,
    val enabled: Boolean = false,
    /** The user has seen the dry-run list for this exact definition. Any edit clears it. */
    val reviewed: Boolean = false,
    val lastRun: Long = 0,
    /** "ok:N", "many:N" (scheduled run refused because too many files matched), or "error". */
    val lastResult: String = "",
) {
    /** A scheduled run only ever happens for rules that are enabled and were reviewed. */
    val runsAutomatically: Boolean get() = enabled && reviewed && trigger == RuleTrigger.DAILY
}

enum class RuleProblem { NO_FOLDER, FOLDER_MISSING, FOLDER_FORBIDDEN, NO_TARGET, TARGET_FORBIDDEN, TARGET_IS_SOURCE, NO_NAME }

data class PlannedItem(val path: String, val size: Long, val modified: Long)

object RuleEngine {
    /** Scheduled runs that would touch more files than this are not executed; the user must run them manually. */
    const val MAX_AUTOMATIC_ITEMS = 500

    fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        for (c in glob) when (c) {
            '*' -> sb.append(".*")
            '?' -> sb.append('.')
            else -> sb.append(Regex.escape(c.toString()))
        }
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }

    /**
     * What a saved edit does to a rule's safety flags. A new rule starts disabled and unreviewed, and changing what a
     * rule matches, does or when it runs clears both flags, so the dry run has to be seen and accepted again.
     */
    fun applyEdit(old: Rule?, edited: Rule): Rule {
        val changed = old == null || old.condition != edited.condition || old.action != edited.action ||
            old.target != edited.target || old.trigger != edited.trigger
        return if (changed) edited.copy(reviewed = false, enabled = false) else edited.copy(reviewed = old!!.reviewed, enabled = edited.enabled && old.reviewed)
    }

    fun validate(rule: Rule, guard: PathGuard): RuleProblem? {
        if (rule.name.isBlank()) return RuleProblem.NO_NAME
        val folder = rule.condition.folder
        if (folder.isBlank()) return RuleProblem.NO_FOLDER
        if (!File(folder).isDirectory) return RuleProblem.FOLDER_MISSING
        if (guard.isExcluded(folder)) return RuleProblem.FOLDER_FORBIDDEN
        if (rule.action != RuleAction.TRASH) {
            if (rule.target.isBlank()) return RuleProblem.NO_TARGET
            if (guard.isExcluded(rule.target)) return RuleProblem.TARGET_FORBIDDEN
            if (rule.action == RuleAction.MOVE && File(rule.target).canonicalPath == File(folder).canonicalPath) return RuleProblem.TARGET_IS_SOURCE
        }
        return null
    }

    fun matches(c: RuleCondition, file: File, now: Long, regex: Regex? = null): Boolean {
        if (!file.isFile) return false
        val name = file.name
        val re = regex ?: c.namePattern.takeIf { it.isNotBlank() }?.let(::globToRegex)
        if (re != null && !re.matches(name)) return false
        if (c.extensions.isNotEmpty() && name.substringAfterLast('.', "").lowercase() !in c.extensions) return false
        c.olderThanDays?.let { if (file.lastModified() > now - it * 86_400_000L) return false }
        val size = file.length()
        c.minSize?.let { if (size < it) return false }
        c.maxSize?.let { if (size > it) return false }
        return true
    }

    /**
     * Dry run: the exact files [run] would act on, without changing anything. Files inside excluded (protected,
     * vault, trash) folders are never listed, and for MOVE and COMPRESS the destination folder is never scanned.
     */
    fun plan(rule: Rule, guard: PathGuard, now: Long = System.currentTimeMillis(), limit: Int = Int.MAX_VALUE): List<PlannedItem> {
        if (validate(rule, guard) != null) return emptyList()
        val c = rule.condition
        val regex = c.namePattern.takeIf { it.isNotBlank() }?.let(::globToRegex)
        val targetCanon = rule.target.takeIf { it.isNotBlank() }?.let { File(it).canonicalPath }
        val out = ArrayList<PlannedItem>()
        val stack = ArrayDeque<File>()
        stack.addLast(File(c.folder))
        while (stack.isNotEmpty() && out.size < limit) {
            val dir = stack.removeLast()
            val children = try { dir.listFiles() } catch (_: SecurityException) { null } ?: continue
            for (f in children.sortedBy { it.name }) {
                if (guard.isExcluded(f.absolutePath)) continue
                if (Files.isSymbolicLink(f.toPath())) continue
                if (f.isDirectory) {
                    val canon = f.canonicalPath
                    val insideTarget = targetCanon != null && rule.action != RuleAction.TRASH && isSameOrInside(canon, targetCanon)
                    if (c.recursive && !insideTarget) stack.addLast(f)
                } else if (matches(c, f, now, regex)) {
                    out += PlannedItem(f.absolutePath, f.length(), f.lastModified())
                    if (out.size >= limit) break
                }
            }
        }
        return out
    }

    data class RunResult(val acted: Int, val skippedTooMany: Boolean = false)

    /**
     * Executes [rule] through [ops] so every step is logged (origin "rule:NAME") and undoable.
     * [automatic] runs refuse to proceed past [MAX_AUTOMATIC_ITEMS] matches.
     */
    fun run(rule: Rule, ops: FileOps, guard: PathGuard, now: Long = System.currentTimeMillis(), automatic: Boolean = false, cancelled: () -> Boolean = { false }): RunResult {
        val items = plan(rule, guard, now, if (automatic) MAX_AUTOMATIC_ITEMS + 1 else Int.MAX_VALUE)
        if (items.isEmpty()) return RunResult(0)
        if (automatic && items.size > MAX_AUTOMATIC_ITEMS) return RunResult(0, skippedTooMany = true)
        val files = items.map { File(it.path) }
        val origin = ruleOrigin(rule.name)
        when (rule.action) {
            RuleAction.TRASH -> ops.trash(files, origin)
            RuleAction.MOVE -> {
                val dir = File(rule.target).also { it.mkdirs() }
                ops.move(files, dir, origin)
            }
            RuleAction.COMPRESS -> {
                val dir = File(rule.target).also { it.mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
                var out = File(dir, "${safeName(rule.name)}_$stamp.zip")
                var n = 1
                while (out.exists()) out = File(dir, "${safeName(rule.name)}_${stamp}_${n++}.zip")
                ArchiveWriter.create(ArchiveFormat.ZIP, out, files, cancelled = cancelled)
                ops.recordArchive(out, origin)
            }
        }
        return RunResult(files.size)
    }

    private fun safeName(s: String) = s.trim().replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").take(40).ifEmpty { "rule" }
}

object RuleCodec {
    fun encode(rules: List<Rule>): String = JSONArray().also { arr ->
        rules.forEach { r ->
            val c = r.condition
            arr.put(JSONObject().apply {
                put("id", r.id); put("name", r.name); put("action", r.action.name); put("target", r.target)
                put("trigger", r.trigger.name); put("enabled", r.enabled); put("reviewed", r.reviewed)
                put("lastRun", r.lastRun); put("lastResult", r.lastResult)
                put("folder", c.folder); put("recursive", c.recursive); put("pattern", c.namePattern)
                put("exts", JSONArray(c.extensions.toList()))
                c.olderThanDays?.let { put("days", it) }
                c.minSize?.let { put("min", it) }
                c.maxSize?.let { put("max", it) }
            })
        }
    }.toString()

    fun decode(raw: String?): List<Rule> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val o = arr.getJSONObject(i)
                    val exts = o.optJSONArray("exts")
                    Rule(
                        id = o.getString("id"), name = o.getString("name"),
                        condition = RuleCondition(
                            folder = o.getString("folder"), recursive = o.optBoolean("recursive"), namePattern = o.optString("pattern"),
                            extensions = if (exts == null) emptySet() else (0 until exts.length()).map { exts.getString(it) }.toSet(),
                            olderThanDays = if (o.has("days")) o.getInt("days") else null,
                            minSize = if (o.has("min")) o.getLong("min") else null,
                            maxSize = if (o.has("max")) o.getLong("max") else null,
                        ),
                        action = RuleAction.valueOf(o.getString("action")), target = o.optString("target"),
                        trigger = RuleTrigger.valueOf(o.getString("trigger")),
                        enabled = o.optBoolean("enabled"), reviewed = o.optBoolean("reviewed"),
                        lastRun = o.optLong("lastRun"), lastResult = o.optString("lastResult"),
                    )
                }.getOrNull()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/** Rules persisted as JSON in the app's private storage. */
class RuleStore(private val file: File) {
    private var rules: List<Rule> = RuleCodec.decode(runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull())

    @Synchronized fun all(): List<Rule> = rules

    @Synchronized fun get(id: String): Rule? = rules.firstOrNull { it.id == id }

    @Synchronized fun upsert(rule: Rule) {
        rules = if (rules.any { it.id == rule.id }) rules.map { if (it.id == rule.id) rule else it } else rules + rule
        save()
    }

    @Synchronized fun delete(id: String) {
        rules = rules.filter { it.id != id }
        save()
    }

    @Synchronized private fun save() {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(RuleCodec.encode(rules))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
