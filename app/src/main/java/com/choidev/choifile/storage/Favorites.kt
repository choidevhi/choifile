package com.choidev.choifile.storage

import com.choidev.choifile.archive.ArchiveLocation
import com.choidev.choifile.remote.RemoteLocation
import com.choidev.choifile.security.APP_PRIVATE_FOLDERS
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A starred folder shown in the drawer. [label] is what the user sees; it defaults to the folder name. */
data class Favorite(val path: String, val label: String)

enum class FavoriteAdd { ADDED, DUPLICATE, FORBIDDEN }

/**
 * The user's favorite folders, in the order the user arranged them, persisted as a small JSON file.
 * Only local folders can be starred, and never the app's own vault or trash (or anything inside them).
 * Thread safe; an unreadable file starts empty and a failed write never throws.
 */
class FavoritesStore(private val file: File) {
    private val items = ArrayList<Favorite>()

    init {
        runCatching { if (file.isFile) items += decode(file.readText()) }
    }

    @Synchronized fun all(): List<Favorite> = items.toList()

    @Synchronized fun contains(path: String): Boolean = indexOf(path) >= 0

    @Synchronized fun add(path: String, label: String? = null): FavoriteAdd {
        if (!isAllowed(path)) return FavoriteAdd.FORBIDDEN
        if (indexOf(path) >= 0) return FavoriteAdd.DUPLICATE
        val p = normalize(path)
        items += Favorite(p, label?.trim()?.takeIf { it.isNotEmpty() } ?: defaultLabel(p))
        save()
        return FavoriteAdd.ADDED
    }

    @Synchronized fun remove(path: String): Boolean {
        val i = indexOf(path)
        if (i < 0) return false
        items.removeAt(i)
        save()
        return true
    }

    /** A blank label goes back to the folder name. */
    @Synchronized fun rename(path: String, label: String): Boolean {
        val i = indexOf(path)
        if (i < 0) return false
        items[i] = items[i].copy(label = label.trim().ifEmpty { defaultLabel(items[i].path) })
        save()
        return true
    }

    /** Moves the item at [from] to position [to] (both clamped); false when nothing changed. */
    @Synchronized fun move(from: Int, to: Int): Boolean {
        if (from !in items.indices) return false
        val target = to.coerceIn(0, items.size - 1)
        if (target == from) return false
        items.add(target, items.removeAt(from))
        save()
        return true
    }

    /**
     * A folder was renamed or moved inside the app: favorites pointing at it (or inside it) follow it.
     * Labels the user did not change follow the new folder name.
     */
    @Synchronized fun relocate(old: String, new: String) {
        val o = normalize(old)
        val n = normalize(new)
        var changed = false
        for (i in items.indices) {
            val f = items[i]
            val moved = when {
                f.path == o -> n
                f.path.startsWith("$o/") -> n + f.path.removePrefix(o)
                else -> continue
            }
            val label = if (f.label == defaultLabel(f.path)) defaultLabel(moved) else f.label
            items[i] = Favorite(moved, label)
            changed = true
        }
        if (changed) save()
    }

    /** Favorites whose folder is gone (deleted, moved outside the app, card removed). */
    @Synchronized fun missing(exists: (String) -> Boolean = { File(it).isDirectory }): Set<String> =
        items.map { it.path }.filterNot(exists).toSet()

    private fun indexOf(path: String): Int {
        val p = normalize(path)
        return items.indexOfFirst { it.path == p }
    }

    private fun save() {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(encode(items))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (_: Exception) {
            // Losing a favorite change is better than crashing the browser.
        }
    }

    companion object {
        /** Local folders only, and nothing inside the app's vault or trash. */
        fun isAllowed(path: String): Boolean {
            if (path.isBlank() || RemoteLocation.isRemote(path) || ArchiveLocation.isArchive(path)) return false
            return normalize(path).split('/').none { it in APP_PRIVATE_FOLDERS }
        }

        fun normalize(path: String): String {
            val p = path.replace('\\', '/')
            return if (p.length > 1) p.trimEnd('/').ifEmpty { "/" } else p
        }

        fun defaultLabel(path: String): String = normalize(path).substringAfterLast('/').ifEmpty { "/" }

        fun encode(list: List<Favorite>): String =
            JSONArray().apply { list.forEach { put(JSONObject().put("p", it.path).put("l", it.label)) } }.toString()

        fun decode(text: String): List<Favorite> {
            val arr = JSONArray(text)
            val out = ArrayList<Favorite>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val p = o.optString("p").takeIf { it.isNotEmpty() && isAllowed(it) }?.let(::normalize) ?: continue
                if (out.any { it.path == p }) continue
                out += Favorite(p, o.optString("l").ifEmpty { defaultLabel(p) })
            }
            return out
        }
    }
}
