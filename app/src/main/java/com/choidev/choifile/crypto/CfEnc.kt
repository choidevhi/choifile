package com.choidev.choifile.crypto

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * CFENC container, version 1. Only javax.crypto is used; no custom primitives.
 *
 * Layout (all integers big-endian):
 * ```
 * offset size  field
 *   0     5    magic "CFENC"
 *   5     1    format version = 1
 *   6     1    KDF id = 1 (PBKDF2WithHmacSHA256)
 *   7     4    PBKDF2 iteration count
 *  11    16    salt (random per file)
 *  27     4    chunk size = plaintext bytes per full chunk
 *  31     8    nonce prefix (random per file)
 *  39 ...      chunks, back to back
 * ```
 * Each chunk is AES-256-GCM(key, nonce, aad, plaintext) = ciphertext || 16-byte tag.
 *  - nonce (12 bytes) = noncePrefix(8) || chunkIndex(4, big-endian, starts at 0).
 *    The key is derived from a fresh random salt for every file, so (key, nonce) pairs
 *    never repeat across files even if a prefix collided; within a file the counter is unique.
 *  - aad = the 39 header bytes || finalFlag (1 byte, 1 for the last chunk, else 0).
 *    The header is therefore authenticated by every chunk, and a stream truncated at a chunk
 *    boundary, or chunks reordered/duplicated, fail authentication.
 *  - Every full chunk holds exactly chunkSize plaintext bytes; the final chunk holds 0..chunkSize
 *    bytes. An empty file is one final chunk with 0 plaintext bytes (16 bytes of tag).
 *  - Stored size is plaintext + 39 + 16 * chunkCount; memory use is one chunk, not the whole file.
 *
 * Key derivation: PBKDF2WithHmacSHA256, 256-bit key. Argon2/scrypt would be preferable against GPU
 * attackers, but neither is available in the JDK/Android platform without a third-party library,
 * so a high PBKDF2 iteration count is used instead. The password strength is what really matters.
 *
 * Wrong password and corrupted/tampered data are indistinguishable by design (GCM tag failure)
 * and both raise [CfEncAuthException]. Plaintext of a chunk is only released after its tag verified,
 * but earlier chunks of a later-failing stream have already been written; file helpers therefore
 * write to a temp file and only rename it into place on success.
 */
object CfEnc {
    const val EXTENSION = "cfenc"
    const val VERSION = 1
    const val KDF_PBKDF2_SHA256 = 1
    const val DEFAULT_ITERATIONS = 600_000
    const val DEFAULT_CHUNK_SIZE = 64 * 1024
    const val HEADER_SIZE = 39
    const val TAG_BYTES = 16
    private const val SALT_BYTES = 16
    private const val PREFIX_BYTES = 8
    private const val MAX_ITERATIONS = 20_000_000
    private const val MIN_CHUNK = 16
    private const val MAX_CHUNK = 16 * 1024 * 1024
    private val MAGIC = "CFENC".toByteArray(Charsets.US_ASCII)

    fun encrypt(
        input: InputStream,
        output: OutputStream,
        password: CharArray,
        iterations: Int = DEFAULT_ITERATIONS,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        random: SecureRandom = SecureRandom(),
    ) {
        require(iterations in 1..MAX_ITERATIONS) { "iterations" }
        require(chunkSize in MIN_CHUNK..MAX_CHUNK) { "chunkSize" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val prefix = ByteArray(PREFIX_BYTES).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_SIZE)
            .put(MAGIC).put(VERSION.toByte()).put(KDF_PBKDF2_SHA256.toByte())
            .putInt(iterations).put(salt).putInt(chunkSize).put(prefix).array()
        val key = deriveKey(password, salt, iterations)
        output.write(header)

        // buf holds one chunk plus one look-ahead byte so we know which chunk is the last.
        val buf = ByteArray(chunkSize + 1)
        var have = readFully(input, buf, 0, buf.size)
        var index = 0L
        while (true) {
            val final = have <= chunkSize
            val n = if (final) have else chunkSize
            output.write(crypt(Cipher.ENCRYPT_MODE, key, header, prefix, index, final, buf, n))
            if (final) break
            buf[0] = buf[chunkSize]
            have = 1 + readFully(input, buf, 1, chunkSize)
            index++
            if (index > 0xFFFF_FFFFL) throw IOException("too many chunks")
        }
        output.flush()
    }

    /** @throws CfEncAuthException wrong password or modified data; [CfEncFormatException] if not a CFENC v1 stream. */
    fun decrypt(input: InputStream, output: OutputStream, password: CharArray) {
        val header = ByteArray(HEADER_SIZE)
        try {
            DataInputStream(input).readFully(header)
        } catch (e: EOFException) {
            throw CfEncFormatException("file too short")
        }
        val bb = ByteBuffer.wrap(header)
        val magic = ByteArray(MAGIC.size).also(bb::get)
        if (!magic.contentEquals(MAGIC)) throw CfEncFormatException("not a cfenc file")
        if (bb.get().toInt() != VERSION) throw CfEncFormatException("unsupported version")
        if (bb.get().toInt() != KDF_PBKDF2_SHA256) throw CfEncFormatException("unsupported kdf")
        val iterations = bb.getInt()
        val salt = ByteArray(SALT_BYTES).also(bb::get)
        val chunkSize = bb.getInt()
        val prefix = ByteArray(PREFIX_BYTES).also(bb::get)
        if (iterations !in 1..MAX_ITERATIONS || chunkSize !in MIN_CHUNK..MAX_CHUNK) throw CfEncFormatException("bad header")

        val key = deriveKey(password, salt, iterations)
        val sealedMax = chunkSize + TAG_BYTES
        val buf = ByteArray(sealedMax + 1)
        var have = readFully(input, buf, 0, buf.size)
        var index = 0L
        while (true) {
            val final = have <= sealedMax
            val n = if (final) have else sealedMax
            if (n < TAG_BYTES) throw CfEncAuthException() // truncated: no room for even a tag
            output.write(crypt(Cipher.DECRYPT_MODE, key, header, prefix, index, final, buf, n))
            if (final) break
            buf[0] = buf[sealedMax]
            have = 1 + readFully(input, buf, 1, sealedMax)
            index++
        }
        output.flush()
    }

    private fun crypt(mode: Int, key: SecretKeySpec, header: ByteArray, prefix: ByteArray, index: Long, final: Boolean, data: ByteArray, len: Int): ByteArray {
        val nonce = ByteBuffer.allocate(12).put(prefix).putInt(index.toInt()).array()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, key, GCMParameterSpec(TAG_BYTES * 8, nonce))
        c.updateAAD(header)
        c.updateAAD(byteArrayOf(if (final) 1 else 0))
        try {
            return c.doFinal(data, 0, len)
        } catch (e: GeneralSecurityException) {
            // AEADBadTagException (or a provider's BadPaddingException) = tag mismatch.
            if (mode == Cipher.DECRYPT_MODE) throw CfEncAuthException() else throw e
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(raw, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray, off: Int, len: Int): Int {
        var total = 0
        while (total < len) {
            val r = input.read(buf, off + total, len - total)
            if (r < 0) break
            total += r
        }
        return total
    }

    // ---- file helpers ----

    fun isEncryptedName(name: String) = name.endsWith(".$EXTENSION", ignoreCase = true)

    /** Name of the plaintext file for a .cfenc file ("a.jpg.cfenc" -> "a.jpg"). */
    fun plainName(name: String) = if (isEncryptedName(name)) name.dropLast(EXTENSION.length + 1).ifEmpty { "decrypted" } else "$name.dec"

    /** Encrypts [src] to [dst] atomically (temp file + rename); [dst] must not exist. */
    fun encryptFile(src: File, dst: File, password: CharArray, iterations: Int = DEFAULT_ITERATIONS, chunkSize: Int = DEFAULT_CHUNK_SIZE) =
        viaTemp(dst) { out -> src.inputStream().buffered().use { encrypt(it, out, password, iterations, chunkSize) } }

    /** Decrypts [src] to [dst] atomically; on a wrong password nothing is left behind. */
    fun decryptFile(src: File, dst: File, password: CharArray) =
        viaTemp(dst) { out -> src.inputStream().buffered().use { decrypt(it, out, password) } }

    private fun viaTemp(dst: File, block: (OutputStream) -> Unit) {
        check(!dst.exists()) { "target exists" }
        val tmp = File(dst.parentFile, ".${dst.name}.part")
        try {
            tmp.outputStream().buffered().use(block)
            check(tmp.renameTo(dst)) { "rename" }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}

/** Wrong password, or the file was modified/corrupted. The two cannot be told apart. */
class CfEncAuthException : IOException("authentication failed: wrong password or corrupted data")

class CfEncFormatException(message: String) : IOException(message)
