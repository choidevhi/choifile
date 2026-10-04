package com.choidev.choifile

import com.choidev.choifile.storage.Favorite
import com.choidev.choifile.storage.FavoriteAdd
import com.choidev.choifile.storage.FavoritesStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FavoritesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val file get() = File(tmp.root, "favorites.json")
    private fun store() = FavoritesStore(file)

    @Test fun addKeepsOrderAndUsesFolderNameAsLabel() {
        val s = store()
        assertEquals(FavoriteAdd.ADDED, s.add("/sd/DCIM/Camera"))
        assertEquals(FavoriteAdd.ADDED, s.add("/sd/Download", "받은 파일"))
        assertEquals(listOf(Favorite("/sd/DCIM/Camera", "Camera"), Favorite("/sd/Download", "받은 파일")), s.all())
        assertTrue(s.contains("/sd/Download"))
    }

    @Test fun duplicatesAreRefusedEvenWithATrailingSlash() {
        val s = store()
        s.add("/sd/Music")
        assertEquals(FavoriteAdd.DUPLICATE, s.add("/sd/Music"))
        assertEquals(FavoriteAdd.DUPLICATE, s.add("/sd/Music/"))
        assertEquals(1, s.all().size)
        assertTrue(s.contains("/sd/Music/"))
    }

    @Test fun renameAndBlankRenameFallsBackToFolderName() {
        val s = store()
        s.add("/sd/Documents/Work")
        assertTrue(s.rename("/sd/Documents/Work", "  회사  "))
        assertEquals("회사", s.all()[0].label)
        s.rename("/sd/Documents/Work", "   ")
        assertEquals("Work", s.all()[0].label)
        assertFalse("unknown path", s.rename("/sd/nope", "x"))
    }

    @Test fun reorderMovesUpAndDownAndClamps() {
        val s = store()
        listOf("/sd/a", "/sd/b", "/sd/c").forEach { s.add(it) }
        assertTrue(s.move(2, 0))
        assertEquals(listOf("c", "a", "b"), s.all().map { it.label })
        assertTrue(s.move(0, 1))
        assertEquals(listOf("a", "c", "b"), s.all().map { it.label })
        assertTrue("past the end is clamped to the last slot", s.move(0, 99))
        assertEquals(listOf("c", "b", "a"), s.all().map { it.label })
        assertFalse(s.move(1, 1))
        assertFalse(s.move(7, 0))
    }

    @Test fun removeForgetsOnlyThatFolder() {
        val s = store()
        s.add("/sd/a"); s.add("/sd/b")
        assertTrue(s.remove("/sd/a/"))
        assertFalse(s.remove("/sd/a"))
        assertEquals(listOf("/sd/b"), s.all().map { it.path })
    }

    @Test fun everythingSurvivesARestart() {
        store().apply {
            add("/sd/a"); add("/sd/b"); add("/sd/c")
            rename("/sd/b", "비")
            move(2, 0)
            remove("/sd/a")
        }
        assertEquals(listOf(Favorite("/sd/c", "c"), Favorite("/sd/b", "비")), store().all())
    }

    @Test fun corruptOrHostileFileIsIgnoredSafely() {
        file.writeText("{not json")
        assertTrue(store().all().isEmpty())
        file.writeText("""[{"p":"/sd/a","l":"A"},{"p":"/sd/a","l":"dup"},{"p":"/sd/.choifile_vault/x","l":"v"},{"l":"no path"},5,{"p":"/sd/b"}]""")
        assertEquals(listOf(Favorite("/sd/a", "A"), Favorite("/sd/b", "b")), store().all())
    }

    @Test fun missingFoldersAreDetected() {
        val here = tmp.newFolder("here")
        val gone = File(tmp.root, "gone").also { it.mkdirs() }
        val s = store()
        s.add(here.path); s.add(gone.path)
        assertTrue(s.missing().isEmpty())
        gone.delete()
        assertEquals(setOf(FavoritesStore.normalize(gone.path)), s.missing())
        // A file in place of the folder counts as missing too.
        gone.writeText("x")
        assertEquals(1, s.missing().size)
        assertEquals("custom check", setOf("/sd/b"), FavoritesStore(File(tmp.root, "f2.json")).apply { add("/sd/a"); add("/sd/b") }.missing { it == "/sd/a" })
    }

    @Test fun vaultTrashAndVirtualLocationsCannotBeFavorited() {
        val s = store()
        assertEquals(FavoriteAdd.FORBIDDEN, s.add("/sd/.choifile_vault"))
        assertEquals(FavoriteAdd.FORBIDDEN, s.add("/sd/.choifile_vault/abc"))
        assertEquals(FavoriteAdd.FORBIDDEN, s.add("/sd/.choifile_trash"))
        assertEquals(FavoriteAdd.FORBIDDEN, s.add("/sd/.glass_trash/x"))
        assertEquals(FavoriteAdd.FORBIDDEN, s.add("archive:/sd/a.zip!dir"))
        assertEquals(FavoriteAdd.FORBIDDEN, s.add("remote:abc:/home"))
        assertEquals(FavoriteAdd.FORBIDDEN, s.add(""))
        assertTrue(s.all().isEmpty())
        assertTrue("a folder merely named like it is fine", FavoritesStore.isAllowed("/sd/choifile_vault_backup"))
    }

    @Test fun relocateFollowsRenamedFolders() {
        val s = store()
        s.add("/sd/Photos"); s.add("/sd/Photos/2024"); s.add("/sd/Other", "기타")
        s.add("/sd/PhotosOld")
        s.relocate("/sd/Photos", "/sd/Pictures")
        assertEquals(listOf("/sd/Pictures", "/sd/Pictures/2024", "/sd/Other", "/sd/PhotosOld"), s.all().map { it.path })
        assertEquals("default label follows the new name", "Pictures", s.all()[0].label)
        assertEquals("2024", s.all()[1].label)
        s.rename("/sd/Pictures", "사진")
        s.relocate("/sd/Pictures", "/sd/P")
        assertEquals("a custom label stays", "사진", store().all()[0].label)
    }
}
