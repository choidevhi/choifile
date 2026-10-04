package com.choidev.choifile.search

/** File-type chips. FOLDER matches directories; the others match by extension. */
enum class KindFilter(val extensions: Set<String>) {
    IMAGE(setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg")),
    VIDEO(setOf("mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v")),
    AUDIO(setOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "opus")),
    DOCUMENT(setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "odt", "ods", "hwp", "csv", "rtf")),
    ARCHIVE(setOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "jar")),
    FOLDER(emptySet()),
}

/** Extensions whose contents are searched in content mode. */
val TEXT_EXTENSIONS: Set<String> = setOf(
    "txt", "md", "log", "csv", "ini", "properties", "conf", "cfg",
    "kt", "kts", "java", "js", "ts", "py", "c", "cpp", "h", "hpp", "cs", "go", "rs", "swift",
    "sh", "gradle", "json", "xml", "html", "css", "yml", "yaml", "toml", "sql", "php", "rb",
)

const val CONTENT_SEARCH_MAX_BYTES = 1L * 1024 * 1024

/**
 * All filters are optional and combined with AND. [text] is split on whitespace and every word must appear
 * in the file name (case-insensitive). With [content] set, [text] is instead looked up inside text files.
 */
data class SearchQuery(
    val text: String = "",
    val extensions: Set<String> = emptySet(),
    val kinds: Set<KindFilter> = emptySet(),
    val minSize: Long? = null,
    val maxSize: Long? = null,
    val modifiedAfter: Long? = null,
    val modifiedBefore: Long? = null,
    val content: Boolean = false,
    /** Relative date filter, resolved to [modifiedAfter] each time a search runs so saved searches stay fresh. */
    val withinDays: Int? = null,
) {
    /** True when there is nothing to search for yet. */
    val isEmpty: Boolean
        get() = text.isBlank() && extensions.isEmpty() && kinds.isEmpty() && minSize == null && maxSize == null &&
            modifiedAfter == null && modifiedBefore == null && withinDays == null

    val words: List<String> get() = text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
}

/** Parses "jpg, .PNG  txt" into {jpg, png, txt}. */
fun parseExtensions(raw: String): Set<String> =
    raw.lowercase().split(Regex("[\\s,;]+")).map { it.trim().trimStart('.') }.filter { it.isNotEmpty() }.toSet()
