package com.choidev.choifile.storage

import org.json.JSONArray
import java.io.File

/**
 * Small persistent map "key -> number", newest kept, oldest dropped beyond [max] entries. Stored as an array of
 * [key, value] pairs, oldest first, so the drop order survives a restart. Thread safe; an unreadable file simply
 * starts empty, and a failed write never throws.
 */
class KeyedLongStore(private val file: File, private val max: Int = 300) {
    private val map = LinkedHashMap<String, Long>()

    init {
        runCatching {
            if (file.isFile) {
                val root = JSONArray(file.readText())
                for (i in 0 until root.length()) root.getJSONArray(i).let { map[it.getString(0)] = it.getLong(1) }
            }
        }
    }

    @Synchronized fun get(key: String): Long? = map[key]

    @Synchronized fun put(key: String, value: Long) {
        map.remove(key)
        map[key] = value
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

    private fun save() {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(JSONArray(map.map { (k, v) -> JSONArray().put(k).put(v) }).toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (_: Exception) {
            // These numbers are a convenience; failing to write them must never break playback.
        }
    }
}
