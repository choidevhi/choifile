package com.choidev.choifile.storage

import org.json.JSONObject
import java.io.File

/**
 * A remembered scroll position: the first visible item, the pixel offset into it, and optionally the name of
 * that item so a position can be re-anchored when the list changed (files added or removed above it).
 */
data class ScrollPos(val index: Int, val offset: Int, val anchor: String? = null, val time: Long = 0)

/**
 * Small persistent map "key -> position", newest kept, oldest dropped beyond [max] entries.
 * Used for the file list (key = folder path) and the comic viewer (key = folder or archive path).
 * Thread safe; an unreadable file simply starts empty. Never throws from [put] or [clear].
 */
class PositionStore(private val file: File, private val max: Int = 300, private val clock: () -> Long = System::currentTimeMillis) {
    private val map = LinkedHashMap<String, ScrollPos>()

    init {
        runCatching { if (file.isFile) decodeInto(file.readText(), map) }
    }

    @Synchronized fun get(key: String): ScrollPos? = map[key]

    @Synchronized fun put(key: String, pos: ScrollPos) {
        map.remove(key)
        map[key] = pos.copy(time = clock())
        while (map.size > max) map.remove(map.keys.first())
        save()
    }

    @Synchronized fun remove(key: String) {
        if (map.remove(key) != null) save()
    }

    @Synchronized fun clear() {
        map.clear()
        runCatching { file.delete() }
    }

    @get:Synchronized val size: Int get() = map.size

    private fun save() {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(encode(map))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (_: Exception) {
            // Positions are a convenience; failing to write them must never break browsing or reading.
        }
    }

    companion object {
        fun encode(map: Map<String, ScrollPos>): String {
            val root = JSONObject()
            for ((k, v) in map) {
                root.put(k, JSONObject().put("i", v.index).put("o", v.offset).put("t", v.time).apply { v.anchor?.let { put("a", it) } })
            }
            return root.toString()
        }

        fun decodeInto(text: String, into: MutableMap<String, ScrollPos>) {
            val root = JSONObject(text)
            val entries = root.keys().asSequence().mapNotNull { k ->
                runCatching {
                    val o = root.getJSONObject(k)
                    k to ScrollPos(o.getInt("i").coerceAtLeast(0), o.optInt("o").coerceAtLeast(0), if (o.has("a")) o.getString("a") else null, o.optLong("t"))
                }.getOrNull()
            }.sortedBy { it.second.time } // JSON objects are unordered; restore the age order
            entries.forEach { (k, v) -> into[k] = v }
        }
    }
}
