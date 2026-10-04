package com.choidev.choifile.security

/** Folder names owned by the app itself (trash and hidden vault). They are never scanned, searched or touched by automation. */
val APP_PRIVATE_FOLDERS = setOf(".choifile_vault", ".choifile_trash", ".glass_trash")

/**
 * Decides which paths a background scan (storage analysis, duplicate finder, automation rules) may look at.
 *
 * The vault and trash folders are always off limits. A protected folder is off limits while it is locked
 * ([isLocked]); with [excludeAllProtected] it is off limits even when unlocked, which automation uses because it
 * runs without the user present and must never touch protected folders.
 */
class PathGuard(
    private val protectedPaths: Set<String>,
    private val isLocked: (String) -> Boolean = { true },
    private val excludeAllProtected: Boolean = false,
) {
    fun isExcluded(path: String): Boolean {
        val p = path.replace('\\', '/')
        if (p.split('/').any { it in APP_PRIVATE_FOLDERS }) return true
        for (root in protectedPaths) {
            if (isSameOrInside(p, root) && (excludeAllProtected || isLocked(root))) return true
        }
        return false
    }
}
