package com.choidev.choifile.ebook.reader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import com.choidev.choifile.R

/**
 * 책장 넘기는 소리. 지연 없이 바로 나야 해서 MediaPlayer 대신 SoundPool로 미리 올려 둔다.
 * 같은 소리가 반복되면 기계적으로 들려서, 조금씩 다른 소리 세 개를 돌아가며 낸다.
 */
class PageSound(context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val sounds = intArrayOf(
        pool.load(context, R.raw.page_flip_0, 1),
        pool.load(context, R.raw.page_flip_1, 1),
        pool.load(context, R.raw.page_flip_2, 1),
    )
    private var next = 0

    fun play() {
        // 무음·진동 모드에서는 내지 않는다.
        if (audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return

        pool.play(sounds[next], 0.9f, 0.9f, 1, 0, 1f)
        next = (next + 1) % sounds.size
    }

    fun release() = pool.release()
}
