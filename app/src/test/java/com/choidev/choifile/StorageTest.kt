package com.choidev.choifile

import com.choidev.choifile.storage.FileItem
import com.choidev.choifile.storage.LocalFileStore
import com.choidev.choifile.storage.SortKey
import com.choidev.choifile.storage.sortItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun item(name: String, dir: Boolean = false, size: Long = 0, mod: Long = 0) =
        FileItem("/$name", name, dir, size, mod, false)

    @Test fun sortKeepsFoldersFirst() {
        val items = listOf(item("b.txt", size = 1), item("a.txt", size = 9), item("z", dir = true))
        assertEquals(listOf("z", "a.txt", "b.txt"), sortItems(items, SortKey.NAME, true).map { it.name })
        assertEquals(listOf("z", "a.txt", "b.txt"), sortItems(items, SortKey.SIZE, false).map { it.name })
        assertEquals(listOf("z", "b.txt", "a.txt"), sortItems(items, SortKey.SIZE, true).map { it.name })
    }

    @Test fun copyAvoidsOverwriteAndMoveWorks() {
        val store = LocalFileStore(tmp.root)
        val a = store.createTextFile(tmp.root, "a.txt").also { it.writeText("hi") }
        val dir = store.createFolder(tmp.root, "d")
        val copied = store.copy(a, tmp.root)
        assertEquals("a (1).txt", copied.name)
        val moved = store.move(a, dir)
        assertTrue(moved.exists() && !a.exists())
        assertEquals("hi", moved.readText())
    }

    @Test fun cannotCopyFolderIntoItself() {
        val store = LocalFileStore(tmp.root)
        val d = store.createFolder(tmp.root, "d")
        val sub = store.createFolder(d, "sub")
        assertTrue(runCatching { store.copy(d, sub) }.isFailure)
    }

    @Test fun trashAndRestore() {
        val store = LocalFileStore(tmp.root)
        val f = store.createTextFile(tmp.root, "x.txt")
        val entry = store.moveToTrash(f)
        assertFalse(f.exists())
        assertEquals(listOf("x.txt"), store.listTrash().map { it.name })
        assertTrue(store.list(tmp.root).none { it.name == ".choifile_trash" })
        val back = store.restore(entry)
        assertTrue(back.exists())
        assertTrue(store.listTrash().isEmpty())
    }

    @Test fun dummyFileHasRequestedSize() {
        val store = LocalFileStore(tmp.root)
        assertEquals(2L * 1024 * 1024, store.createDummyFile(tmp.root, "d.bin", 2).length())
    }

    @Test fun renameRejectsExistingAndInvalid() {
        val store = LocalFileStore(tmp.root)
        val a = store.createTextFile(tmp.root, "a")
        store.createTextFile(tmp.root, "b")
        assertTrue(runCatching { store.rename(a, "b") }.isFailure)
        assertTrue(runCatching { store.rename(a, "x/y") }.isFailure)
        assertEquals("c", store.rename(a, "c").name)
    }
}

