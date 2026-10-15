package com.choidev.choifile.storage

import com.choidev.choifile.util.VideoMath
import java.io.File

/**
 * Where each video was left, so it reopens there. Positions in the first seconds or near the end are not kept
 * ([VideoMath.resumable]); a finished video starts over. Newest kept, oldest dropped beyond [max] entries.
 */
class VideoResume(file: File, max: Int = 300) {
    private val store = KeyedLongStore(file, max)

    fun get(path: String): Long? = store.get(path)

    /** Remembers [positionMs] of [durationMs] for [path], or forgets the video when the position is not worth resuming. */
    fun put(path: String, positionMs: Long, durationMs: Long) {
        if (VideoMath.resumable(positionMs, durationMs)) store.put(path, positionMs) else store.remove(path)
    }

    fun clear() = store.clear()
}
