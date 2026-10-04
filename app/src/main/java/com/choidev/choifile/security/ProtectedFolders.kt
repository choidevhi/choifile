package com.choidev.choifile.security

/** True when [path] is [root] or lies below it. Accepts both / and \ so it is testable on any OS. */
fun isSameOrInside(path: String, root: String): Boolean {
    val p = path.replace('\\', '/').trimEnd('/')
    val r = root.replace('\\', '/').trimEnd('/')
    return p == r || p.startsWith("$r/")
}

/**
 * Pure state machine for protected folders; no Android or filesystem access so it can be unit tested.
 *
 * A folder is "locked" when it (or an ancestor) is protected and the user has not authenticated for it
 * recently. Unlocks last [timeoutMs] after authentication. With [timeoutMs] == 0 ("immediate") an unlock
 * only lasts while the user stays inside the folder (see [onNavigated]) and until the app is backgrounded
 * ([lockAll]).
 */
class ProtectedFolders(
    initial: Set<String> = emptySet(),
    var timeoutMs: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val protectedSet = initial.toMutableSet()
    private val unlockedAt = HashMap<String, Long>()

    @get:Synchronized val paths: Set<String> get() = protectedSet.toSet()

    @Synchronized fun isProtected(path: String) = path in protectedSet

    @Synchronized fun protect(path: String) { protectedSet += path }

    @Synchronized fun unprotect(path: String) { protectedSet -= path; unlockedAt.remove(path) }

    /** The outermost protected folder that currently blocks [path], or null if it may be shown. */
    @Synchronized fun lockedRoot(path: String): String? =
        protectedSet.filter { isSameOrInside(path, it) }.sortedBy { it.length }.firstOrNull(::isLocked)

    private fun isLocked(root: String): Boolean {
        val at = unlockedAt[root] ?: return true
        return timeoutMs > 0 && clock() - at > timeoutMs
    }

    @Synchronized fun unlock(root: String) { unlockedAt[root] = clock() }

    @Synchronized fun lockAll() = unlockedAt.clear()

    /** Call whenever the set of open tab paths changes; implements the "immediate" re-lock. */
    @Synchronized fun onNavigated(openPaths: Collection<String>) {
        if (timeoutMs > 0) return
        unlockedAt.keys.removeAll { root -> openPaths.none { isSameOrInside(it, root) } }
    }

    /** A protected folder was renamed or moved: keep the protection (and unlock state) on the new path. */
    @Synchronized fun relocate(old: String, new: String) {
        val moved = protectedSet.filter { isSameOrInside(it, old) }
        for (p in moved) {
            val np = new + p.substring(old.length)
            protectedSet -= p; protectedSet += np
            unlockedAt.remove(p)?.let { unlockedAt[np] = it }
        }
    }
}
