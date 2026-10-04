package com.choidev.choifile

import com.choidev.choifile.oplog.FileOps
import com.choidev.choifile.oplog.NoUndo
import com.choidev.choifile.oplog.OperationLog
import com.choidev.choifile.oplog.OpType
import com.choidev.choifile.oplog.SkipReason
import com.choidev.choifile.oplog.UndoState
import com.choidev.choifile.storage.LocalFileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OperationLogTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var store: LocalFileStore
    private lateinit var logFile: File
    private lateinit var ops: FileOps
    private lateinit var base: File

    @Before fun setUp() {
        base = tmp.newFolder("storage")
        store = LocalFileStore(base)
        logFile = File(tmp.root, "ops.json")
        ops = FileOps(store, OperationLog(logFile))
    }

    private fun file(dir: File, name: String, text: String = name) = File(dir, name).also { it.writeText(text) }

    @Test fun moveIsRecordedAndUndone() {
        val a = file(base, "a.txt")
        val dst = store.createFolder(base, "d")
        val items = ops.move(listOf(a), dst)
        assertEquals(1, items.size)
        assertFalse(a.exists())
        val rec = ops.log.all().first()
        assertEquals(OpType.MOVE, rec.type)
        assertEquals(UndoState.AVAILABLE, rec.undoState)

        val report = ops.undo(rec.id)!!
        assertEquals(1, report.restored)
        assertEquals(listOf(File(dst, "a.txt").absolutePath to a.absolutePath), report.moved)
        assertTrue(a.exists())
        assertEquals(UndoState.DONE, ops.log.get(rec.id)!!.undoState)
    }

    @Test fun moveUndoRefusesToOverwriteSomethingNew() {
        val a = file(base, "a.txt", "old")
        val dst = store.createFolder(base, "d")
        ops.move(listOf(a), dst)
        file(base, "a.txt", "new content") // user created a new a.txt meanwhile
        val report = ops.undo(ops.log.all().first().id)!!
        assertEquals(0, report.restored)
        assertEquals(SkipReason.TARGET_EXISTS, report.skipped.single().reason)
        assertEquals("new content", File(base, "a.txt").readText())
        assertTrue(File(dst, "a.txt").exists())
        assertEquals(UndoState.AVAILABLE, ops.log.all().first().undoState)
    }

    @Test fun renameIsUndone() {
        val a = file(base, "a.txt")
        val b = ops.rename(a, "b.txt")
        assertTrue(b.exists())
        ops.undo(ops.log.all().first().id)
        assertTrue(a.exists())
        assertFalse(b.exists())
    }

    @Test fun trashIsUndoneUnlessTrashWasEmptied() {
        val a = file(base, "a.txt")
        val b = file(base, "b.txt")
        val id = ops.trash(listOf(a))!!
        assertFalse(a.exists())
        assertEquals(1, ops.undo(id)!!.restored)
        assertTrue(a.exists())

        val id2 = ops.trash(listOf(b))!!
        store.emptyTrash()
        val report = ops.undo(id2)!!
        assertEquals(0, report.restored)
        assertEquals(SkipReason.MISSING, report.skipped.single().reason)
        assertFalse(b.exists())
    }

    @Test fun copyUndoRemovesOnlyWhatTheCopyCreated() {
        val src = store.createFolder(base, "src")
        file(src, "one.txt"); file(src, "two.txt")
        val dstDir = store.createFolder(base, "dst")
        file(dstDir, "mine.txt", "pre-existing")
        ops.copy(listOf(src), dstDir)
        val copied = File(dstDir, "src")
        assertTrue(File(copied, "one.txt").exists())

        // The user edits one copied file and adds a new file inside the copied folder afterwards.
        File(copied, "two.txt").writeText("edited, longer than before")
        file(copied, "extra.txt")

        val report = ops.undo(ops.log.all().first().id)!!
        assertEquals(1, report.restored)                     // only one.txt was still untouched
        assertFalse(File(copied, "one.txt").exists())
        assertTrue(File(copied, "two.txt").exists())          // modified: kept
        assertTrue(File(copied, "extra.txt").exists())        // not created by the operation: kept
        assertTrue(File(dstDir, "mine.txt").exists())         // pre-existing: untouched
        assertTrue(File(src, "one.txt").exists())             // the original is never touched
        assertTrue(report.skipped.any { it.reason == SkipReason.MODIFIED })
        assertTrue(report.skipped.any { it.reason == SkipReason.NOT_EMPTY })
        assertEquals(UndoState.PARTIAL, ops.log.all().first().undoState)
    }

    @Test fun cleanCopyUndoRemovesFolderToo() {
        val src = store.createFolder(base, "src")
        file(src, "one.txt")
        val dstDir = store.createFolder(base, "dst")
        ops.copy(listOf(src), dstDir)
        val report = ops.undo(ops.log.all().first().id)!!
        assertTrue(report.skipped.isEmpty())
        assertFalse(File(dstDir, "src").exists())
        assertTrue(src.exists())
    }

    @Test fun permanentDeleteIsLoggedAsNotUndoable() {
        val a = file(base, "a.txt")
        ops.deletePermanently(listOf(a), secure = false)
        val rec = ops.log.all().first()
        assertEquals(OpType.DELETE, rec.type)
        assertEquals(UndoState.UNAVAILABLE, rec.undoState)
        assertEquals(NoUndo.PERMANENT, rec.reason)
        assertEquals(0, ops.undo(rec.id)!!.restored)

        val b = file(base, "b.txt")
        ops.deletePermanently(listOf(b), secure = true)
        assertEquals(NoUndo.SECURE, ops.log.all().first().reason)
    }

    @Test fun extractUndoRemovesExtractedFiles() {
        val dest = store.createFolder(base, "arc")
        file(dest, "x.txt")
        ops.recordExtract(File(base, "arc.zip"), dest)
        assertEquals(OpType.EXTRACT, ops.log.all().first().type)
        ops.undo(ops.log.all().first().id)
        assertFalse(dest.exists())
    }

    @Test fun logSurvivesRestartAndIsCapped() {
        val a = file(base, "a.txt")
        ops.trash(listOf(a))
        val reread = OperationLog(logFile)
        assertEquals(1, reread.all().size)
        assertNotNull(reread.all().first().items.first().trashId)

        val small = OperationLog(File(tmp.root, "small.json"), max = 3)
        val o = FileOps(store, small)
        repeat(5) { i -> o.trash(listOf(file(base, "f$i.txt"))) }
        assertEquals(3, small.all().size)
        assertEquals(3, OperationLog(File(tmp.root, "small.json")).all().size)
    }

    @Test fun corruptLogFileStartsEmpty() {
        File(tmp.root, "bad.json").writeText("{not json")
        assertTrue(OperationLog(File(tmp.root, "bad.json")).all().isEmpty())
    }
}
