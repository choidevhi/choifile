package com.choidev.choifile.storage

import org.json.JSONArray
import java.io.File

/**
 * Grid or list, remembered per folder. Only folders the user switched are stored; every other folder opens as a
 * list. Newest kept, oldest dropped beyond [max] entries. Thread safe; an unreadable file simply starts empty.
 */
class FolderViewModes(private val file: File, private val max: Int = 1000) {
    private val map = LinkedHashMap<String, Boolean>()

    init {
        runCatching {
            if (file.isFile) {
                // An array of [path, grid] pairs, oldest first, so the drop order survives a restart.
                val root = JSONArray(file.readText())
                for (i in 0 until root.length()) root.getJSONArray(i).let { map[it.getString(0)] = it.getBoolean(1) }
            }
        }
    }

    @Synchronized fun isGrid(path: String): Boolean = map[path] ?: false

    @Synchronized fun set(path: String, grid: Boolean) {
        map.remove(path)
        map[path] = grid
        while (map.size > max) map.remove(map.keys.first())
        save()
    }

    private fun save() {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(JSONArray(map.map { (k, v) -> JSONArray().put(k).put(v) }).toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (_: Exception) {
            // A view preference is a convenience; failing to write it must never break browsing.
        }
    }
}
