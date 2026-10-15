package com.choidev.choifile.ebook.data

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.choidev.choifile.R

enum class TurnMode(@StringRes val label: Int) {
    CURL(R.string.eb_turn_curl),
    SLIDE(R.string.eb_turn_slide),
    NONE(R.string.eb_turn_none),
}

data class ReaderTheme(
    val id: String,
    @StringRes val name: Int,
    val background: Long,
    val text: Long,
    val dark: Boolean,
)

val ReaderThemes = listOf(
    ReaderTheme("white", R.string.eb_theme_white, 0xFFFFFFFF, 0xFF1C1C1E, false),
    ReaderTheme("paper", R.string.eb_theme_paper, 0xFFF6EFE2, 0xFF3A2F25, false),
    ReaderTheme("calm", R.string.eb_theme_calm, 0xFFE9E2D0, 0xFF2E2A22, false),
    ReaderTheme("green", R.string.eb_theme_green, 0xFFDDE8D6, 0xFF26301F, false),
    ReaderTheme("gray", R.string.eb_theme_gray, 0xFF3A3A3C, 0xFFE5E5EA, true),
    ReaderTheme("night", R.string.eb_theme_night, 0xFF000000, 0xFFAEAEB2, true),
)

data class ReaderSettings(
    val fontSizeSp: Float = 18f,
    val lineSpacing: Float = 1.7f,
    val marginDp: Int = 24,
    val fontId: String = Fonts.SYSTEM,
    val themeId: String = "white",
    val turnMode: TurnMode = TurnMode.CURL,
    val justify: Boolean = true,
    val volumeKeys: Boolean = true,
    val keepScreenOn: Boolean = true,
    val pageSound: Boolean = true,
    /** 0~1. 음수면 시스템 밝기를 그대로 쓴다. */
    val brightness: Float = -1f,
) {
    val theme: ReaderTheme get() = ReaderThemes.firstOrNull { it.id == themeId } ?: ReaderThemes.first()
}

/** 보기 설정은 SharedPreferences에 두고, 화면은 Compose 상태로 바로 따라간다. */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("reader_settings", Context.MODE_PRIVATE)

    var value by mutableStateOf(load())
        private set

    fun update(change: (ReaderSettings) -> ReaderSettings) {
        val next = change(value)
        value = next
        prefs.edit()
            .putFloat("fontSizeSp", next.fontSizeSp)
            .putFloat("lineSpacing", next.lineSpacing)
            .putInt("marginDp", next.marginDp)
            .putString("fontId", next.fontId)
            .putString("themeId", next.themeId)
            .putString("turnMode", next.turnMode.name)
            .putBoolean("justify", next.justify)
            .putBoolean("volumeKeys", next.volumeKeys)
            .putBoolean("keepScreenOn", next.keepScreenOn)
            .putBoolean("pageSound", next.pageSound)
            .putFloat("brightness", next.brightness)
            .apply()
    }

    private fun load(): ReaderSettings {
        val d = ReaderSettings()

        return ReaderSettings(
            fontSizeSp = prefs.getFloat("fontSizeSp", d.fontSizeSp),
            lineSpacing = prefs.getFloat("lineSpacing", d.lineSpacing),
            marginDp = prefs.getInt("marginDp", d.marginDp),
            fontId = prefs.getString("fontId", d.fontId) ?: d.fontId,
            themeId = prefs.getString("themeId", d.themeId) ?: d.themeId,
            turnMode = runCatching { TurnMode.valueOf(prefs.getString("turnMode", null) ?: "") }.getOrDefault(d.turnMode),
            justify = prefs.getBoolean("justify", d.justify),
            volumeKeys = prefs.getBoolean("volumeKeys", d.volumeKeys),
            keepScreenOn = prefs.getBoolean("keepScreenOn", d.keepScreenOn),
            pageSound = prefs.getBoolean("pageSound", d.pageSound),
            brightness = prefs.getFloat("brightness", d.brightness),
        )
    }
}
