package com.choidev.choifile.ebook.data

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import com.choidev.choifile.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class FontOption(val id: String, val name: String)

/** 기본 글꼴 몇 개와 사용자가 직접 넣은 TTF/OTF를 함께 다룬다. */
class Fonts(private val context: Context) {

    companion object {
        const val SYSTEM = "system"
        const val SERIF = "serif"
        const val GOWUN = "gowun_batang"
        const val NANUM = "nanum_myeongjo"
        private const val FILE_PREFIX = "file:"
    }

    private val dir = File(context.filesDir, "fonts").apply { mkdirs() }
    private val cache = HashMap<String, Typeface>()

    fun options(): List<FontOption> {
        val builtIn = listOf(
            FontOption(SYSTEM, context.getString(R.string.eb_font_system_sans)),
            FontOption(SERIF, context.getString(R.string.eb_font_system_serif)),
        )
        val custom = dir.listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name }
            ?.map { FontOption(FILE_PREFIX + it.name, it.nameWithoutExtension) }
            .orEmpty()

        return builtIn + custom
    }

    fun isCustom(id: String) = id.startsWith(FILE_PREFIX)

    fun typeface(id: String): Typeface = cache.getOrPut(id) {
        runCatching {
            when {
                id == SYSTEM -> Typeface.DEFAULT
                id == SERIF -> Typeface.SERIF
                // choifile does not bundle these two fonts (11 MB); books set to them read in the system serif.
                id == GOWUN || id == NANUM -> Typeface.SERIF
                isCustom(id) -> Typeface.createFromFile(File(dir, id.removePrefix(FILE_PREFIX)))
                else -> null
            }
        }.getOrNull() ?: Typeface.DEFAULT
    }

    suspend fun import(uri: Uri): FontOption = withContext(Dispatchers.IO) {
        val raw = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: "font.ttf"
        val name = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val target = File(dir, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { input.copyTo(it) }
        } ?: error(context.getString(R.string.eb_error_font_open))

        // 글꼴로 읽히지 않는 파일은 바로 지운다.
        val loaded = runCatching { Typeface.createFromFile(target) }.getOrNull()
        if (loaded == null) {
            target.delete()
            error(context.getString(R.string.eb_error_not_font))
        }

        cache.remove(FILE_PREFIX + name)
        FontOption(FILE_PREFIX + name, target.nameWithoutExtension)
    }

    fun delete(id: String) {
        if (!isCustom(id)) return

        File(dir, id.removePrefix(FILE_PREFIX)).delete()
        cache.remove(id)
    }
}
