package com.choidev.choifile.browser

import com.choidev.choifile.archive.ArchiveFormat
import com.choidev.choifile.storage.FileItem
import com.choidev.choifile.util.FileKind
import com.choidev.choifile.util.kindOf
import java.io.File

/** Kinds of items the filter can pick. */
enum class TypeGroup { FOLDER, IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, APK, OTHER }

/** Last-modified windows, in days back from now. */
enum class DateRange(val days: Int?) { ANY(null), DAY(1), WEEK(7), MONTH(30), YEAR(365) }

/** Size bands for files. */
enum class SizeRange(val min: Long, val max: Long) {
    ANY(0, Long.MAX_VALUE),
    SMALL(0, MB),
    MEDIUM(MB, 100 * MB),
    LARGE(100 * MB, GB),
    HUGE(GB, Long.MAX_VALUE),
}

private const val MB = 1024L * 1024
private const val GB = 1024L * MB
private const val DAY_MS = 24L * 60 * 60 * 1000

private val documentExt = setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "hwp", "hwpx", "odt", "ods", "odp", "rtf", "epub", "csv")

fun typeGroupOf(item: FileItem): TypeGroup {
    if (item.isDirectory) return TypeGroup.FOLDER
    val name = item.name
    if (name.endsWith(".apk", ignoreCase = true)) return TypeGroup.APK
    if (ArchiveFormat.detect(name) != null) return TypeGroup.ARCHIVE
    return when (kindOf(File(name))) {
        FileKind.IMAGE -> TypeGroup.IMAGE
        FileKind.VIDEO -> TypeGroup.VIDEO
        FileKind.AUDIO -> TypeGroup.AUDIO
        FileKind.TEXT, FileKind.PDF, FileKind.MARKDOWN -> TypeGroup.DOCUMENT
        FileKind.OTHER -> if (name.substringAfterLast('.', "").lowercase() in documentExt) TypeGroup.DOCUMENT else TypeGroup.OTHER
    }
}

/**
 * What the open folder shows: a name query, a set of kinds (empty = all), a last-modified window and a size band.
 * Size applies to files only, so a size filter hides folders.
 */
data class FileFilter(
    val text: String = "",
    val types: Set<TypeGroup> = emptySet(),
    val date: DateRange = DateRange.ANY,
    val size: SizeRange = SizeRange.ANY,
) {
    /** How many criteria are set (the badge on the filter button). */
    val count: Int get() = (if (text.isNotBlank()) 1 else 0) + (if (types.isNotEmpty()) 1 else 0) +
        (if (date != DateRange.ANY) 1 else 0) + (if (size != SizeRange.ANY) 1 else 0)

    val active: Boolean get() = count > 0

    private val name: NameQuery? = text.trim().takeIf { it.isNotEmpty() }?.let(::NameQuery)

    fun matches(item: FileItem, nowMs: Long): Boolean {
        if (name != null && !name.matches(item.name)) return false
        if (types.isNotEmpty() && typeGroupOf(item) !in types) return false
        date.days?.let { d -> if (item.modified < nowMs - d * DAY_MS) return false }
        if (size != SizeRange.ANY && (item.isDirectory || item.size < size.min || item.size >= size.max)) return false
        return true
    }

    fun apply(items: List<FileItem>, nowMs: Long): List<FileItem> = if (!active) items else items.filter { matches(it, nowMs) }
}

/**
 * A name query: plain text matches anywhere (case ignored); `*` and `?` make it a whole-name wildcard pattern
 * ("*.jpg", "IMG_20??*"); a query of Korean initial consonants only ("ㅎㄱ") matches names whose syllables start
 * with them ("휴가", "한글 문서").
 */
class NameQuery(query: String) {
    private val q = query.lowercase()
    private val wildcard: Regex? = if ('*' in q || '?' in q) {
        Regex(q.split('*').joinToString(".*") { part -> part.split('?').joinToString(".") { Regex.escape(it) } })
    } else null
    private val initials: Boolean = q.all { it in CHOSEONG || it == ' ' } && q.any { it in CHOSEONG }

    fun matches(name: String): Boolean {
        val n = name.lowercase()
        return when {
            wildcard != null -> wildcard.matches(n)
            initials -> initialsOf(n).contains(q.replace(" ", ""))
            else -> n.contains(q)
        }
    }

    companion object {
        /** The 19 initial consonants of Hangul syllables, in Unicode order. */
        private const val CHOSEONG = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"

        /** Each Hangul syllable replaced by its initial consonant; other characters are dropped. */
        fun initialsOf(s: String): String = buildString {
            for (c in s) {
                if (c in '가'..'힣') append(CHOSEONG[(c - '가') / 588])
                else if (c in CHOSEONG) append(c)
            }
        }
    }
}
