package com.choidev.choifile

import com.choidev.choifile.storage.PositionStore
import com.choidev.choifile.storage.ScrollPos
import com.choidev.choifile.util.AssociationCodec
import com.choidev.choifile.util.Associations
import com.choidev.choifile.util.OpenChoice
import com.choidev.choifile.util.extensionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StoresTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 0L
    private fun store(file: File, max: Int = 300) = PositionStore(file, max) { ++now }

    // ---- reading / scroll positions ----

    @Test fun positionsSurviveARestart() {
        val f = File(tmp.root, "reading_positions.json")
        store(f).apply {
            put("/sd/Comics/One", ScrollPos(42, 310, "042.jpg#1"))
            put("/sd/Comics/Two.cbz", ScrollPos(3, 0))
        }
        val reopened = store(f)
        assertEquals(ScrollPos(42, 310, "042.jpg#1", reopened.get("/sd/Comics/One")!!.time), reopened.get("/sd/Comics/One"))
        assertEquals(3, reopened.get("/sd/Comics/Two.cbz")!!.index)
        assertNull(reopened.get("/sd/other"))
    }

    @Test fun putOverwritesAndRemoveForgets() {
        val s = store(File(tmp.root, "p.json"))
        s.put("a", ScrollPos(1, 1))
        s.put("a", ScrollPos(7, 9))
        assertEquals(7, s.get("a")!!.index)
        assertEquals(1, s.size)
        s.remove("a")
        assertNull(s.get("a"))
    }

    @Test fun clearingWipesMemoryAndDisk() {
        // This is what Settings > 캐시 삭제 does to the comic reading positions.
        val f = File(tmp.root, "reading_positions.json")
        val s = store(f)
        s.put("/sd/c1", ScrollPos(10, 5))
        s.put("/sd/c2", ScrollPos(20, 5))
        assertTrue(f.isFile)
        s.clear()
        assertNull(s.get("/sd/c1"))
        assertEquals(0, s.size)
        assertFalse("the file is deleted, not just emptied in memory", f.exists())
        assertNull("a restart after clearing does not bring positions back", store(f).get("/sd/c2"))
        s.put("/sd/c3", ScrollPos(1, 0))
        assertEquals(1, store(f).size)
    }

    @Test fun oldestEntriesAreDroppedBeyondTheCap() {
        val f = File(tmp.root, "cap.json")
        val s = store(f, max = 3)
        listOf("a", "b", "c").forEach { s.put(it, ScrollPos(1, 0)) }
        s.put("a", ScrollPos(2, 0)) // touching "a" makes it the newest
        s.put("d", ScrollPos(1, 0))
        assertNull("b was the oldest", s.get("b"))
        assertEquals(setOf("a", "c", "d"), listOf("a", "b", "c", "d").filter { s.get(it) != null }.toSet())
        // Age order survives a reload, so the next eviction is still the oldest.
        val r = store(f, max = 3)
        r.put("e", ScrollPos(1, 0))
        assertNull(r.get("c"))
        assertEquals(2, r.get("a")!!.index)
    }

    @Test fun corruptFileStartsEmptyAndNegativeValuesAreClamped() {
        val f = File(tmp.root, "bad.json").also { it.writeText("{not json") }
        val s = store(f)
        assertEquals(0, s.size)
        s.put("x", ScrollPos(1, 2))
        assertEquals(1, store(f).size)
        File(tmp.root, "neg.json").writeText("""{"k":{"i":-5,"o":-1,"t":1},"bad":{"o":3}}""")
        val n = store(File(tmp.root, "neg.json"))
        assertEquals(0, n.get("k")!!.index)
        assertEquals(0, n.get("k")!!.offset)
        assertNull("entries without an index are skipped", n.get("bad"))
    }

    // ---- "open with" associations ----

    @Test fun extensionKeys() {
        assertEquals("jpg", extensionKey("Photo.JPG"))
        assertEquals("gz", extensionKey("a.tar.gz"))
        assertEquals("", extensionKey("Makefile"))
        assertEquals("", extensionKey(".bashrc"))
        assertEquals("", extensionKey("trailing."))
        assertEquals("mkv", extensionKey("/sd/Movies/a.b/clip.MKV"))
    }

    @Test fun associationsRememberPerExtension() {
        val a = Associations()
        a.set(".MP4", OpenChoice.App("org.videolan.vlc", "org.videolan.vlc.StartActivity", "VLC"))
        a.set("pdf", OpenChoice.BuiltIn)
        a.set("txt", OpenChoice.Ask)
        a.set("", OpenChoice.BuiltIn) // ignored: nothing to key on
        assertEquals("VLC", (a.get("/sd/clip.mp4") as OpenChoice.App).label)
        assertEquals(OpenChoice.BuiltIn, a.get("doc.PDF"))
        assertEquals(OpenChoice.Ask, a.get("notes.txt"))
        assertNull(a.get("README"))
        assertNull(a.get("song.mp3"))
        assertEquals(listOf("mp4", "pdf", "txt"), a.all().map { it.first })
    }

    @Test fun associationsRoundTripAndClear() {
        val a = Associations()
        a.set("mkv", OpenChoice.App("com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.ad.ActivityScreen", "MX Player"))
        a.set("cbz", OpenChoice.BuiltIn)
        a.set("epub", OpenChoice.Ask)
        val back = Associations.decode(a.encode())
        assertEquals(a.all(), back.all())

        back.clear("MKV")
        assertNull(back.get("x.mkv"))
        back.forgetPackage("nope")
        assertEquals(2, back.all().size)
        back.clearAll()
        assertTrue(back.all().isEmpty())
    }

    @Test fun forgetPackageDropsOnlyThatApp() {
        val a = Associations()
        a.set("mp4", OpenChoice.App("p.one", "p.one.A", "One"))
        a.set("mkv", OpenChoice.App("p.one", "p.one.A", "One"))
        a.set("avi", OpenChoice.App("p.two", "p.two.A", "Two"))
        a.forgetPackage("p.one")
        assertEquals(listOf("avi"), a.all().map { it.first })
    }

    @Test fun badAssociationJsonIsIgnored() {
        assertTrue(AssociationCodec.decode(null).isEmpty())
        assertTrue(AssociationCodec.decode("garbage").isEmpty())
        val partial = AssociationCodec.decode("""{"mp4":{"k":"app","p":"","a":"x"},"pdf":{"k":"builtin"},"zip":{"k":"weird"},"txt":5}""")
        assertEquals(mapOf("pdf" to OpenChoice.BuiltIn), partial)
    }
}
