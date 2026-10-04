package com.choidev.choifile.util

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Seek-preview thumbnails. One MediaMetadataRetriever per video (it is not thread safe, hence the mutex),
 * frames decoded off the main thread and kept in a small LRU cache keyed by time bucket and precision.
 */
class VideoFrames(private val file: File, private val maxEdge: Int = 320) {
    private val lock = Mutex()
    private var retriever: MediaMetadataRetriever? = null
    private var failed = false
    private var released = false
    private val cache = LruCache<String, Bitmap>(48)

    /**
     * Frame near [ms]. [precise] decodes the exact frame (slower: from the previous key frame on);
     * otherwise the nearest key frame is used, which is fast enough to follow a quick drag.
     */
    suspend fun frameAt(ms: Long, precise: Boolean): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$ms:$precise"
        cache.get(key)?.let { return@withContext it }
        lock.withLock {
            if (released || failed) return@withLock null
            cache.get(key)?.let { return@withLock it }
            val r = retriever ?: runCatching { MediaMetadataRetriever().apply { setDataSource(file.path) } }
                .onFailure { failed = true }.getOrNull()?.also { retriever = it } ?: return@withLock null
            val option = if (precise) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            val frame = runCatching {
                if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(ms * 1000, option, maxEdge, maxEdge)
                else r.getFrameAtTime(ms * 1000, option)?.let(::scaleDown)
            }.getOrNull()
            frame?.also { cache.put(key, it) }
        }
    }

    private fun scaleDown(b: Bitmap): Bitmap {
        val scale = maxEdge.toFloat() / maxOf(b.width, b.height)
        if (scale >= 1f) return b
        return Bitmap.createScaledBitmap(b, (b.width * scale).toInt().coerceAtLeast(1), (b.height * scale).toInt().coerceAtLeast(1), true)
            .also { if (it !== b) b.recycle() }
    }

    suspend fun release() = withContext(Dispatchers.IO) {
        lock.withLock {
            released = true
            runCatching { retriever?.release() }
            retriever = null
            cache.evictAll()
        }
    }
}
