package com.choidev.choifile

import com.choidev.choifile.security.ProtectedFolders
import com.choidev.choifile.security.SavedTab
import com.choidev.choifile.security.TabsCodec
import com.choidev.choifile.storage.LocalFileStore
import com.choidev.choifile.storage.overwriteWithRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SecurityStateTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private fun folders(timeout: Long, vararg paths: String) = ProtectedFolders(paths.toSet(), timeout) { now }

    @Test fun protectedFolderAndChildrenAreLockedUntilUnlocked() {
        val p = folders(60_000, "/sd/secret")
        assertEquals("/sd/secret", p.lockedRoot("/sd/secret"))
        assertEquals("/sd/secret", p.lockedRoot("/sd/secret/deep/er"))
        assertNull(p.lockedRoot("/sd/other"))
        assertNull("a name prefix is not inside", p.lockedRoot("/sd/secret2"))
        p.unlock("/sd/secret")
        assertNull(p.lockedRoot("/sd/secret/deep"))
    }

    @Test fun timedRelock() {
        val p = folders(60_000, "/sd/s")
        p.unlock("/sd/s")
        now += 59_000
        assertNull(p.lockedRoot("/sd/s"))
        now += 2_000
        assertEquals("/sd/s", p.lockedRoot("/sd/s"))
    }

    @Test fun immediateRelockWhenLeavingOrBackgrounding() {
        val p = folders(0, "/sd/s")
        p.unlock("/sd/s")
        assertNull(p.lockedRoot("/sd/s/a"))
        p.onNavigated(listOf("/sd/s/a", "/sd/x")) // still inside in one tab
        assertNull(p.lockedRoot("/sd/s"))
        p.onNavigated(listOf("/sd/x")) // left in every tab
        assertEquals("/sd/s", p.lockedRoot("/sd/s"))
        p.unlock("/sd/s")
        p.lockAll()
        assertEquals("/sd/s", p.lockedRoot("/sd/s"))
    }

    @Test fun nestedProtectionNeedsEveryLevel() {
        val p = folders(60_000, "/a", "/a/b")
        p.unlock("/a")
        assertEquals("/a/b", p.lockedRoot("/a/b/c"))
        p.unlock("/a/b")
        assertNull(p.lockedRoot("/a/b/c"))
    }

    @Test fun tabRestoreIntoProtectedFolderIsLockedOnStart() {
        // A fresh instance (as after a restart) has no unlocks even though the persisted set has the folder.
        val persisted = setOf("/sd/s")
        assertEquals("/sd/s", ProtectedFolders(persisted, 60_000) { now }.lockedRoot("/sd/s/photos"))
    }

    @Test fun unprotectAndRelocate() {
        val p = folders(60_000, "/a/b")
        p.unlock("/a/b")
        p.relocate("/a", "/z")
        assertEquals(setOf("/z/b"), p.paths)
        assertNull("unlock state follows the move", p.lockedRoot("/z/b"))
        p.unprotect("/z/b")
        assertNull(p.lockedRoot("/z/b"))
        assertTrue(p.paths.isEmpty())
    }

    @Test fun tabsCodecRoundTrip() {
        val tabs = listOf(SavedTab("/storage/emulated/0/DCIM", true), SavedTab("/sdcard/a b|c\nd/한글", false))
        assertEquals(tabs, TabsCodec.decode(TabsCodec.encode(tabs)))
        assertTrue(TabsCodec.decode(null).isEmpty())
        assertTrue(TabsCodec.decode("garbage").isEmpty())
    }

    @Test fun hideRemovesFromListingAndUnhideRestores() {
        val store = LocalFileStore(tmp.root)
        val f = store.createTextFile(tmp.root, "photo.jpg").also { it.writeText("x") }
        val dir = store.createFolder(tmp.root, "album")
        val e1 = store.hide(f)
        val e2 = store.hide(dir)
        assertFalse(f.exists())
        assertTrue("hidden items must not appear in normal listings", store.list(tmp.root).isEmpty())
        assertTrue(File(tmp.root, ".choifile_vault/.nomedia").isFile)
        assertEquals(setOf("photo.jpg", "album"), store.listHidden().map { it.name }.toSet())
        assertEquals("x", store.unhide(e1).readText())
        assertTrue(store.unhide(e2).isDirectory)
        assertEquals(setOf("photo.jpg", "album"), store.list(tmp.root).map { it.name }.toSet())
        assertTrue(store.listHidden().isEmpty())
    }

    @Test fun unhideAvoidsOverwritingNewFile() {
        val store = LocalFileStore(tmp.root)
        val f = store.createTextFile(tmp.root, "a.txt").also { it.writeText("old") }
        val e = store.hide(f)
        store.createTextFile(tmp.root, "a.txt").writeText("new")
        assertEquals("a (1).txt", store.unhide(e).name)
        assertEquals("new", File(tmp.root, "a.txt").readText())
    }

    @Test fun trashAndVaultDoNotInterfere() {
        val store = LocalFileStore(tmp.root)
        store.moveToTrash(store.createTextFile(tmp.root, "t.txt"))
        store.hide(store.createTextFile(tmp.root, "h.txt"))
        assertEquals(listOf("t.txt"), store.listTrash().map { it.name })
        assertEquals(listOf("h.txt"), store.listHidden().map { it.name })
        store.emptyTrash()
        assertEquals(listOf("h.txt"), store.listHidden().map { it.name })
    }

    @Test fun overwriteChangesContentKeepsLength() {
        val f = tmp.newFile("s.bin").also { it.writeBytes(ByteArray(200_000) { 7 }) }
        overwriteWithRandom(f)
        assertEquals(200_000L, f.length())
        assertFalse(f.readBytes().all { it == 7.toByte() })
    }

    @Test fun secureDeleteRemovesFilesAndFolders() {
        val store = LocalFileStore(tmp.root)
        val d = store.createFolder(tmp.root, "d")
        File(d, "a.bin").writeBytes(ByteArray(1000))
        File(d, "sub").mkdirs()
        File(d, "sub/b.bin").writeBytes(ByteArray(10))
        val single = tmp.newFile("one.bin").also { it.writeBytes(ByteArray(5)) }
        store.secureDelete(d)
        store.secureDelete(single)
        assertTrue(store.list(tmp.root).isEmpty())
    }
}
