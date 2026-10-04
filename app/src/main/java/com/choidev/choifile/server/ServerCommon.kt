package com.choidev.choifile.server

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

object ServerToken {
    private const val ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** 24 random characters from an alphabet without look-alikes (about 137 bits). */
    fun generate(random: SecureRandom = SecureRandom()): String =
        buildString { repeat(24) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    /** Constant-time comparison so response timing does not leak how much of a guess was right. */
    fun matches(expected: String, given: String?): Boolean =
        given != null && MessageDigest.isEqual(expected.toByteArray(), given.toByteArray())
}

/** Confines every request to the shared folder. */
object SafePath {
    /**
     * Resolves [relative] (slash separated, as sent by a client) below [root]. Returns null for anything that is not
     * strictly inside it: ".." segments, backslashes, NUL bytes, and symlinks pointing outside (checked on the canonical path).
     */
    fun resolve(root: File, relative: String): File? {
        if (relative.indexOf('\u0000') >= 0 || relative.indexOf('\\') >= 0) return null
        val parts = relative.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        val candidate = parts.fold(root) { f, p -> File(f, p) }
        val rootCanon = root.canonicalPath
        val canon = candidate.canonicalPath
        if (canon != rootCanon && !canon.startsWith(rootCanon.trimEnd(File.separatorChar) + File.separator)) return null
        return candidate
    }

    /** FTP style: ".." is resolved against the virtual path and clamped at the root, never reaching outside. */
    fun normalizeVirtual(cwd: String, arg: String): String {
        val stack = ArrayList<String>()
        val base = if (arg.startsWith("/")) arg else "$cwd/$arg"
        for (seg in base.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                else -> stack += seg
            }
        }
        return "/" + stack.joinToString("/")
    }
}

/** Slows down password guessing: after [maxFailures] bad attempts an address is refused for [lockMs]. */
class AuthThrottle(private val maxFailures: Int = 5, private val lockMs: Long = 60_000, private val clock: () -> Long = System::currentTimeMillis) {
    private class Entry(var failures: Int, var lockedUntil: Long)
    private val map = HashMap<String, Entry>()

    @Synchronized fun isBlocked(addr: String): Boolean {
        val e = map[addr] ?: return false
        if (e.lockedUntil > clock()) return true
        if (e.lockedUntil != 0L) { e.failures = 0; e.lockedUntil = 0 }
        return false
    }

    @Synchronized fun failed(addr: String) {
        val e = map.getOrPut(addr) { Entry(0, 0) }
        e.failures++
        if (e.failures >= maxFailures) e.lockedUntil = clock() + lockMs
    }

    @Synchronized fun succeeded(addr: String) { map.remove(addr) }
}
