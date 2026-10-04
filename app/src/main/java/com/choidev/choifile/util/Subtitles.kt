package com.choidev.choifile.util

import java.util.Locale

/**
 * Subtitle files that belong to a video: same base name, one of the text formats the player reads, optionally
 * with a language tag in between ("movie.srt", "Movie.ko.srt", "movie.en.forced.vtt"). Pure so it can be tested.
 */
object SidecarSubtitles {
    /** MIME types as Media3 names them (MimeTypes.APPLICATION_SUBRIP, TEXT_SSA, TEXT_VTT). */
    private val mimeByExt = mapOf(
        "srt" to "application/x-subrip",
        "ass" to "text/x-ssa",
        "ssa" to "text/x-ssa",
        "vtt" to "text/vtt",
    )

    data class Match(val fileName: String, val mimeType: String, val language: String?, val label: String)

    fun isSubtitle(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in mimeByExt

    /** Subtitles among [siblings] (file names in the video's folder) for [videoName], exact base name first. */
    fun match(videoName: String, siblings: Collection<String>): List<Match> {
        val base = videoName.substringBeforeLast('.').lowercase()
        if (base.isEmpty()) return emptyList()
        return siblings.mapNotNull { name ->
            val ext = name.substringAfterLast('.', "").lowercase()
            val mime = mimeByExt[ext] ?: return@mapNotNull null
            val stem = name.dropLast(ext.length + 1)
            val middle = when {
                stem.lowercase() == base -> ""
                stem.lowercase().startsWith("$base.") -> stem.substring(base.length + 1)
                else -> return@mapNotNull null
            }
            val language = middle.split('.').firstOrNull { isLanguageTag(it) }?.lowercase()
            middle to Match(name, mime, language, label(middle, ext))
        }.sortedWith(compareBy({ it.first.isNotEmpty() }, { it.second.fileName.lowercase() })).map { it.second }
    }

    private fun label(middle: String, ext: String): String =
        if (middle.isEmpty()) ext.uppercase() else "$middle (${ext.uppercase()})"

    private val iso2 by lazy { Locale.getISOLanguages().toSet() }
    private val iso3 by lazy { iso2.mapNotNull { runCatching { Locale(it).isO3Language }.getOrNull() }.toSet() }

    /** "ko", "en", "kor", "pt-BR" style tags; anything else ("forced", "sdh", "1080p") is not a language. */
    fun isLanguageTag(s: String): Boolean {
        val lang = s.substringBefore('-').substringBefore('_').lowercase()
        if (!lang.all { it in 'a'..'z' }) return false
        return when (lang.length) {
            2 -> lang in iso2
            3 -> lang in iso3
            else -> false
        }
    }
}

/** Human-readable name for an audio or subtitle track in the track picker. */
object TrackLabels {
    fun label(label: String?, language: String?, mimeType: String?, channels: Int, index: Int): String {
        val parts = mutableListOf<String>()
        label?.takeIf { it.isNotBlank() }?.let(parts::add)
        language?.takeIf { it.isNotBlank() && it != "und" }?.let { tag ->
            val name = Locale.forLanguageTag(tag).getDisplayLanguage(Locale.getDefault()).takeIf { it.isNotBlank() } ?: tag
            if (parts.none { it.equals(name, ignoreCase = true) }) parts.add(name)
        }
        codecName(mimeType)?.let(parts::add)
        when {
            channels == 1 -> parts.add("mono")
            channels == 2 -> parts.add("stereo")
            channels > 2 -> parts.add("${channels - 1}.1")
        }
        return if (parts.isEmpty()) "#${index + 1}" else "#${index + 1}  " + parts.joinToString(" · ")
    }

    fun codecName(mimeType: String?): String? = when (mimeType?.lowercase()) {
        null -> null
        "audio/mp4a-latm" -> "AAC"
        "audio/ac3" -> "AC3"
        "audio/eac3", "audio/eac3-joc" -> "E-AC3"
        "audio/vnd.dts", "audio/vnd.dts.hd", "audio/vnd.dts.hd;profile=lbr", "audio/vnd.dts.uhd;profile=p2" -> "DTS"
        "audio/true-hd" -> "TrueHD"
        "audio/flac" -> "FLAC"
        "audio/alac" -> "ALAC"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/mpeg" -> "MP3"
        "audio/raw" -> "PCM"
        "application/x-subrip" -> "SRT"
        "text/x-ssa" -> "ASS"
        "text/vtt" -> "WebVTT"
        "application/pgs" -> "PGS"
        "application/x-quicktime-tx3g" -> "tx3g"
        "application/dvbsubs" -> "DVB"
        else -> mimeType.substringAfter('/').uppercase()
    }
}
