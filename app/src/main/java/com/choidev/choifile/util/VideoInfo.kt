package com.choidev.choifile.util

import android.media.MediaMetadataRetriever
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/** "0:07", "12:34", "1:02:03". Negative or unknown durations give an empty string. */
fun formatDuration(ms: Long): String {
    if (ms < 0) return ""
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * Video durations for the file list. Read with MediaMetadataRetriever off the main thread, at most two at a time
 * so a fast fling does not queue dozens of decoders, and kept in a small in-memory cache keyed by path and
 * modification time (never written to disk).
 */
object VideoDurations {
    private val cache = LruCache<String, Long>(512)
    private val gate = Semaphore(2)

    fun cached(path: String, modified: Long): Long? = cache.get("$path|$modified")?.takeIf { it >= 0 }

    suspend fun of(path: String, modified: Long): Long? {
        val key = "$path|$modified"
        cache.get(key)?.let { return it.takeIf { d -> d >= 0 } }
        return withContext(Dispatchers.IO) {
            gate.withPermit {
                cache.get(key)?.let { return@withPermit it.takeIf { d -> d >= 0 } }
                val ms = runCatching {
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(File(path).path)
                        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    } finally {
                        r.release()
                    }
                }.getOrNull()
                cache.put(key, ms ?: -1)
                ms
            }
        }
    }

    fun clear() = cache.evictAll()
}
