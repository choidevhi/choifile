package com.choidev.choifile.storage

import com.choidev.choifile.util.VideoMath
import org.json.JSONArray
import java.io.File

/**
 * Where each video was left, so it reopens there. Positions in the first seconds or near the end are not kept
 * ([VideoMath.resumable]); a finished video starts over. Newest kept, oldest dropped beyond [max] entries.
 * Thread safe; an unreadable file simply starts empty.
 */
class VideoResume(private val file: File, private val max: Int = 300) {
    private val map = LinkedHashMap<String, Long>()

    init {
        runCatching {
            if (file.isFile) {
                // An array of [path, positionMs] pairs, oldest first, so the drop order survives a restart.
                val root = JSONArray(file.readText())
                for (i in 0 until root.length()) root.getJSONArray(i).let { map[it.getString(0)] = it.getLong(1) }
            }
        }
    }

    @Synchronized fun get(path: String): Long? = map[path]

    /** Remembers [positionMs] of [durationMs] for [path], or forgets the video when the position is not worth resuming. */
    @Synchronized fun put(path: String, positionMs: Long, durationMs: Long) {
        val old = map.remove(path)
        if (VideoMath.resumable(positionMs, durationMs)) {
            map[path] = positionMs
            while (map.size > max) map.remove(map.keys.first())
        } else if (old == null) {
            return
        }
        save()
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
            // Resume points are a convenience; failing to write them must never break playback.
        }
    }
}
