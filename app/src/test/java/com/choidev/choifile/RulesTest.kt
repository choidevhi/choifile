package com.choidev.choifile

import com.choidev.choifile.oplog.FileOps
import com.choidev.choifile.oplog.OperationLog
import com.choidev.choifile.oplog.OpType
import com.choidev.choifile.oplog.UndoState
import com.choidev.choifile.rules.Rule
import com.choidev.choifile.rules.RuleAction
import com.choidev.choifile.rules.RuleCodec
import com.choidev.choifile.rules.RuleCondition
import com.choidev.choifile.rules.RuleEngine
import com.choidev.choifile.rules.RuleProblem
import com.choidev.choifile.rules.RuleStore
import com.choidev.choifile.rules.RuleTrigger
import com.choidev.choifile.security.PathGuard
import com.choidev.choifile.storage.LocalFileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RulesTest {
    @get:org.junit.Rule val tmp = TemporaryFolder()
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L
    private lateinit var base: File
    private lateinit var downloads: File
    private lateinit var archive: File
    private lateinit var ops: FileOps
    private val open = PathGuard(emptySet())

    @Before fun setUp() {
        base = tmp.newFolder("storage")
        downloads = File(base, "Download").also { it.mkdirs() }
        archive = File(base, "Archive").also { it.mkdirs() }
        ops = FileOps(LocalFileStore(base), OperationLog(File(tmp.root, "ops.json")))
    }

    private fun f(rel: String, size: Int = 10, ageDays: Int = 0): File {
        val file = File(base, rel); file.parentFile.mkdirs(); file.writeBytes(ByteArray(size))
        file.setLastModified(now - ageDays * day); return file
    }

    private fun rule(cond: RuleCondition = RuleCondition(downloads.absolutePath), action: RuleAction = RuleAction.MOVE, target: String = archive.absolutePath) =
        Rule(name = "r", condition = cond, action = action, target = target, enabled = true, reviewed = true, trigger = RuleTrigger.DAILY)

    @Test fun matchingCombinesAllConditions() {
        val c = RuleCondition(downloads.absolutePath, namePattern = "*.PDF", olderThanDays = 30, minSize = 100, maxSize = 1000)
        assertTrue(RuleEngine.matches(c, f("Download/a.pdf", 500, 40), now))
        assertFalse(RuleEngine.matches(c, f("Download/b.pdf", 500, 5), now))     // too new
        assertFalse(RuleEngine.matches(c, f("Download/c.pdf", 50, 40), now))     // too small
        assertFalse(RuleEngine.matches(c, f("Download/d.pdf", 5000, 40), now))   // too big
        assertFalse(RuleEngine.matches(c, f("Download/e.txt", 500, 40), now))    // wrong name
        assertTrue(RuleEngine.matches(RuleCondition("x", extensions = setOf("jpg", "png")), f("Download/p.PNG"), now))
        assertFalse(RuleEngine.matches(RuleCondition("x", extensions = setOf("jpg")), f("Download/p.png"), now))
        assertFalse(RuleEngine.matches(RuleCondition("x"), downloads, now)) // folders never match
    }

    @Test fun globAnchorsAndEscapes() {
        val re = RuleEngine.globToRegex("report_??.v1*")
        assertTrue(re.matches("Report_01.v1.pdf"))
        assertFalse(re.matches("report_1.v1"))
        assertFalse(re.matches("my report_01.v1")) // whole name must match
        assertFalse(re.matches("report_01xv1"))     // '.' is literal
    }

    @Test fun dryRunListsExactlyWhatWillRunAndChangesNothing() {
        f("Download/old1.zip", ageDays = 60); f("Download/old2.zip", ageDays = 90); f("Download/new.zip", ageDays = 1); f("Download/old.txt", ageDays = 90)
        val r = rule(RuleCondition(downloads.absolutePath, extensions = setOf("zip"), olderThanDays = 30))
        val plan = RuleEngine.plan(r, open, now)
        assertEquals(setOf("old1.zip", "old2.zip"), plan.map { File(it.path).name }.toSet())
        assertEquals(4, downloads.listFiles()!!.size) // untouched
        assertTrue(ops.log.all().isEmpty())

        val result = RuleEngine.run(r, ops, open, now)
        assertEquals(2, result.acted)
        assertEquals(setOf("old1.zip", "old2.zip"), archive.list()!!.toSet())
        assertEquals(setOf("new.zip", "old.txt"), downloads.list()!!.toSet())
        // the run is in the log, tagged with the rule, and undoable
        val rec = ops.log.all().first()
        assertEquals(OpType.MOVE, rec.type); assertEquals("r", rec.ruleName); assertTrue(rec.isRule)
        ops.undo(rec.id)
        assertEquals(4, downloads.list()!!.size)
    }

    @Test fun trashActionGoesThroughTrashAndIsUndoable() {
        f("Download/a.tmp"); f("Download/b.tmp"); f("Download/keep.txt")
        val r = rule(RuleCondition(downloads.absolutePath, extensions = setOf("tmp")), RuleAction.TRASH, "")
        assertEquals(2, RuleEngine.run(r, ops, open, now).acted)
        assertEquals(listOf("keep.txt"), downloads.list()!!.toList())
        assertEquals(2, LocalFileStore(base).listTrash().size) // really in the trash, not deleted
        assertEquals(OpType.TRASH, ops.log.all().first().type)
        ops.undo(ops.log.all().first().id)
        assertEquals(3, downloads.list()!!.size)
    }

    @Test fun compressZipsMatchesAndKeepsOriginals() {
        f("Download/a.log", 100); f("Download/b.log", 100)
        val r = rule(RuleCondition(downloads.absolutePath, extensions = setOf("log")), RuleAction.COMPRESS)
        assertEquals(2, RuleEngine.run(r, ops, open, now).acted)
        assertEquals(2, downloads.list()!!.size)
        val zip = archive.listFiles()!!.single()
        assertTrue(zip.name.endsWith(".zip") && zip.length() > 0)
        assertEquals(OpType.ARCHIVE, ops.log.all().first().type)
        ops.undo(ops.log.all().first().id)
        assertEquals(0, archive.list()!!.size)
    }

    @Test fun protectedAndVaultFoldersAreNeverTouched() {
        f("Download/ok.tmp"); f("Download/Private/secret.tmp"); f(".choifile_vault/v.tmp")
        val guard = PathGuard(setOf(File(downloads, "Private").absolutePath), isLocked = { false }, excludeAllProtected = true)
        val r = rule(RuleCondition(downloads.absolutePath, recursive = true, extensions = setOf("tmp")), RuleAction.TRASH, "")
        assertEquals(listOf("ok.tmp"), RuleEngine.plan(r, guard, now).map { File(it.path).name }) // even though "unlocked"
        RuleEngine.run(r, ops, guard, now)
        assertTrue(File(downloads, "Private/secret.tmp").exists())
        assertTrue(File(base, ".choifile_vault/v.tmp").exists())

        // A rule pointed straight at a protected or vault folder is invalid and does nothing.
        val direct = rule(RuleCondition(File(downloads, "Private").absolutePath, extensions = setOf("tmp")), RuleAction.TRASH, "")
        assertEquals(RuleProblem.FOLDER_FORBIDDEN, RuleEngine.validate(direct, guard))
        assertTrue(RuleEngine.plan(direct, guard, now).isEmpty())
        val vault = rule(RuleCondition(File(base, ".choifile_vault").absolutePath), RuleAction.TRASH, "")
        assertEquals(RuleProblem.FOLDER_FORBIDDEN, RuleEngine.validate(vault, guard))
        // ...and neither may a protected folder be the move destination.
        val into = rule(RuleCondition(downloads.absolutePath), RuleAction.MOVE, File(downloads, "Private").absolutePath)
        assertEquals(RuleProblem.TARGET_FORBIDDEN, RuleEngine.validate(into, guard))
    }

    @Test fun validation() {
        assertNull(RuleEngine.validate(rule(), open))
        assertEquals(RuleProblem.FOLDER_MISSING, RuleEngine.validate(rule(RuleCondition(File(base, "nope").absolutePath)), open))
        assertEquals(RuleProblem.NO_TARGET, RuleEngine.validate(rule(target = ""), open))
        assertEquals(RuleProblem.TARGET_IS_SOURCE, RuleEngine.validate(rule(target = downloads.absolutePath), open))
        assertEquals(RuleProblem.NO_NAME, RuleEngine.validate(rule().copy(name = " "), open))
    }

    @Test fun recursiveMoveDoesNotRescanItsDestination() {
        val inner = File(downloads, "sorted").also { it.mkdirs() }
        f("Download/a.bin"); f("Download/sorted/already.bin")
        val r = rule(RuleCondition(downloads.absolutePath, recursive = true, extensions = setOf("bin")), RuleAction.MOVE, inner.absolutePath)
        assertEquals(listOf("a.bin"), RuleEngine.plan(r, open, now).map { File(it.path).name })
    }

    @Test fun automaticRunsRefuseHugeMatches() {
        repeat(RuleEngine.MAX_AUTOMATIC_ITEMS + 5) { f("Download/f$it.tmp", 1) }
        val r = rule(RuleCondition(downloads.absolutePath, extensions = setOf("tmp")), RuleAction.TRASH, "")
        val auto = RuleEngine.run(r, ops, open, now, automatic = true)
        assertTrue(auto.skippedTooMany)
        assertEquals(RuleEngine.MAX_AUTOMATIC_ITEMS + 5, downloads.list()!!.size)
        assertTrue(ops.log.all().isEmpty())
        assertFalse(RuleEngine.run(r, ops, open, now, automatic = false).skippedTooMany)
    }

    @Test fun onlyEnabledReviewedDailyRulesRunAutomatically() {
        val base = rule()
        assertTrue(base.runsAutomatically)
        assertFalse(base.copy(reviewed = false).runsAutomatically)
        assertFalse(base.copy(enabled = false).runsAutomatically)
        assertFalse(base.copy(trigger = RuleTrigger.MANUAL).runsAutomatically)
    }

    @Test fun editsResetSafetyFlagsAndNewRulesStartOff() {
        val saved = rule()
        // a brand-new rule can never arrive enabled or reviewed
        val fresh = RuleEngine.applyEdit(null, saved)
        assertFalse(fresh.enabled); assertFalse(fresh.reviewed)
        // renaming keeps the flags
        assertTrue(RuleEngine.applyEdit(saved, saved.copy(name = "other")).runsAutomatically)
        // changing what it matches, does, or when it runs clears them
        assertFalse(RuleEngine.applyEdit(saved, saved.copy(condition = saved.condition.copy(olderThanDays = 1))).reviewed)
        assertFalse(RuleEngine.applyEdit(saved, saved.copy(action = RuleAction.TRASH)).enabled)
        assertFalse(RuleEngine.applyEdit(saved, saved.copy(target = "/elsewhere")).reviewed)
        assertFalse(RuleEngine.applyEdit(saved, saved.copy(trigger = RuleTrigger.MANUAL)).reviewed)
        // an unreviewed rule cannot be switched on by an edit
        assertFalse(RuleEngine.applyEdit(saved.copy(enabled = false, reviewed = false), saved.copy(enabled = true, reviewed = true)).enabled)
    }

    @Test fun codecAndStoreRoundTrip() {
        val r = rule(RuleCondition("/s/Download", true, "*.apk", setOf("apk"), 30, 10, 99)).copy(lastRun = 5, lastResult = "ok:3")
        assertEquals(listOf(r), RuleCodec.decode(RuleCodec.encode(listOf(r))))
        val file = File(tmp.root, "rules.json")
        RuleStore(file).upsert(r)
        RuleStore(file).upsert(r.copy(name = "renamed"))
        assertEquals(listOf("renamed"), RuleStore(file).all().map { it.name })
        RuleStore(file).delete(r.id)
        assertTrue(RuleStore(file).all().isEmpty())
        assertTrue(RuleCodec.decode("garbage").isEmpty())
        assertEquals(UndoState.AVAILABLE, UndoState.valueOf("AVAILABLE"))
    }
}
