package com.choidev.choifile

import com.choidev.choifile.crypto.CfEnc
import com.choidev.choifile.crypto.CfEncAuthException
import com.choidev.choifile.crypto.CfEncFormatException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random

class CfEncTest {
    @get:Rule val tmp = TemporaryFolder()

    private val pw = "correct horse battery".toCharArray()
    private val iter = 1000 // low only for test speed; the app default is 600_000
    private val chunk = 64

    private fun enc(data: ByteArray, password: CharArray = pw): ByteArray {
        val out = ByteArrayOutputStream()
        CfEnc.encrypt(ByteArrayInputStream(data), out, password, iter, chunk)
        return out.toByteArray()
    }

    private fun dec(data: ByteArray, password: CharArray = pw): ByteArray {
        val out = ByteArrayOutputStream()
        CfEnc.decrypt(ByteArrayInputStream(data), out, password)
        return out.toByteArray()
    }

    private fun bytes(n: Int) = ByteArray(n).also { Random(n.toLong()).nextBytes(it) }

    @Test fun roundTripSmall() {
        val data = "hello 안녕".toByteArray()
        val sealed = enc(data)
        assertEquals(CfEnc.HEADER_SIZE + data.size + CfEnc.TAG_BYTES, sealed.size)
        assertArrayEquals(data, dec(sealed))
    }

    @Test fun emptyFile() {
        val sealed = enc(ByteArray(0))
        assertEquals(CfEnc.HEADER_SIZE + CfEnc.TAG_BYTES, sealed.size)
        assertArrayEquals(ByteArray(0), dec(sealed))
    }

    @Test fun multiChunkIncludingExactBoundaries() {
        for (n in listOf(chunk - 1, chunk, chunk + 1, chunk * 2, chunk * 2 + 1, chunk * 10 + 7, 100_000)) {
            val data = bytes(n)
            val sealed = enc(data)
            val chunks = (n + chunk - 1) / chunk
            assertEquals("size for $n", CfEnc.HEADER_SIZE + n + CfEnc.TAG_BYTES * chunks, sealed.size)
            assertArrayEquals("round trip for $n", data, dec(sealed))
        }
    }

    @Test fun wrongPasswordFailsWithAuthError() {
        val sealed = enc(bytes(500))
        val out = ByteArrayOutputStream()
        try {
            CfEnc.decrypt(ByteArrayInputStream(sealed), out, "wrong".toCharArray())
            throw AssertionError("expected failure")
        } catch (e: CfEncAuthException) {
            assertEquals("no plaintext may be released", 0, out.size())
        }
    }

    @Test fun tamperedCiphertextIsRejectedInEveryRegion() {
        val sealed = enc(bytes(300))
        // header (kdf params, salt, nonce prefix), first chunk, middle chunk, last tag byte
        for (pos in listOf(8, 15, 33, 35, CfEnc.HEADER_SIZE + 3, CfEnc.HEADER_SIZE + 100, sealed.size - 1)) {
            val bad = sealed.clone().also { it[pos] = (it[pos].toInt() xor 0x01).toByte() }
            val err = runCatching { dec(bad) }.exceptionOrNull()
            assertTrue("byte $pos -> $err", err is CfEncAuthException || err is CfEncFormatException)
        }
    }

    @Test fun truncationAndAppendingAreRejected() {
        val sealed = enc(bytes(chunk * 3))
        val perChunk = chunk + CfEnc.TAG_BYTES
        // drop the final chunk exactly at a chunk boundary
        assertTrue(runCatching { dec(sealed.copyOfRange(0, sealed.size - perChunk)) }.exceptionOrNull() is CfEncAuthException)
        // cut mid-chunk
        assertTrue(runCatching { dec(sealed.copyOfRange(0, sealed.size - 5)) }.exceptionOrNull() is CfEncAuthException)
        assertTrue(runCatching { dec(sealed + byteArrayOf(1, 2, 3)) }.exceptionOrNull() is CfEncAuthException)
    }

    @Test fun reorderedChunksAreRejected() {
        val sealed = enc(bytes(chunk * 3))
        val perChunk = chunk + CfEnc.TAG_BYTES
        val h = CfEnc.HEADER_SIZE
        val swapped = sealed.clone()
        System.arraycopy(sealed, h + perChunk, swapped, h, perChunk)
        System.arraycopy(sealed, h, swapped, h + perChunk, perChunk)
        assertTrue(runCatching { dec(swapped) }.exceptionOrNull() is CfEncAuthException)
    }

    @Test fun notACfencFile() {
        assertTrue(runCatching { dec("just some text, long enough to have a header....".toByteArray()) }.exceptionOrNull() is CfEncFormatException)
        assertTrue(runCatching { dec(ByteArray(3)) }.exceptionOrNull() is CfEncFormatException)
    }

    @Test fun eachEncryptionIsDifferent() {
        val data = bytes(100)
        assertFalse(enc(data).contentEquals(enc(data)))
    }

    @Test fun fileHelpersLeaveNothingBehindOnWrongPassword() {
        val src = tmp.newFile("a.txt").also { it.writeBytes(bytes(1000)) }
        val encFile = File(tmp.root, "a.txt.cfenc")
        CfEnc.encryptFile(src, encFile, pw, iter, chunk)
        val target = File(tmp.root, "out.txt")
        assertTrue(runCatching { CfEnc.decryptFile(encFile, target, "nope".toCharArray()) }.exceptionOrNull() is CfEncAuthException)
        assertFalse(target.exists())
        assertEquals(listOf("a.txt", "a.txt.cfenc"), tmp.root.list()!!.sorted())
        CfEnc.decryptFile(encFile, target, pw)
        assertArrayEquals(src.readBytes(), target.readBytes())
    }

    @Test fun names() {
        assertEquals("a.jpg", CfEnc.plainName("a.jpg.cfenc"))
        assertEquals("a.jpg", CfEnc.plainName("a.jpg.CFENC"))
        assertEquals("x.dec", CfEnc.plainName("x"))
        assertTrue(CfEnc.isEncryptedName("b.cfenc"))
    }
}
