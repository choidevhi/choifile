package com.choidev.choifile.ui

import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import androidx.annotation.OptIn
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.ssa.SsaParser
import androidx.media3.extractor.text.subrip.SubripParser
import androidx.media3.extractor.text.webvtt.WebvttParser
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.choidev.choifile.util.CueTimeline
import com.choidev.choifile.util.SidecarSubtitles
import com.choidev.choifile.util.SmiParser
import com.choidev.choifile.util.SubtitleText
import java.io.File

/**
 * A subtitle file next to the video, parsed whole and shown by the player itself (not by Media3), so it can be
 * shifted earlier or later, read in any encoding, and picked from any file in the folder.
 */
class ExternalSubtitle(val file: File, val charset: String, val timeline: CueTimeline<Cue>)

/** Files above this size are not subtitles worth parsing (and would only cost memory). */
private const val MAX_SUBTITLE_BYTES = 20L * 1024 * 1024

/** Subtitle files in [dir], same-name matches for [video] first, then the rest by name. */
fun subtitleFilesFor(video: File, names: List<String>): List<File> {
    val dir = video.parentFile ?: return emptyList()
    val matched = SidecarSubtitles.match(video.name, names).map { it.fileName }
    val others = names.filter { SidecarSubtitles.isSubtitle(it) && it !in matched }.sortedBy { it.lowercase() }
    return (matched + others).map { File(dir, it) }
}

/**
 * Reads [file] in [charset] (detected when null) into a timeline of cues: SRT, ASS/SSA and WebVTT through Media3's
 * parsers, SAMI through [SmiParser]. Null when the file cannot be read or holds no lines. Runs on the caller's
 * thread; call it off the main thread.
 */
@OptIn(UnstableApi::class)
fun loadExternalSubtitle(file: File, charset: String? = null): ExternalSubtitle? = runCatching {
    if (!file.isFile || file.length() > MAX_SUBTITLE_BYTES) return null
    val raw = file.readBytes()
    val cs = charset ?: SubtitleText.detect(raw)
    val utf8 = SubtitleText.toUtf8(raw, cs)
    val entries = ArrayList<CueTimeline.Entry<Cue>>()
    when (file.extension.lowercase()) {
        "smi", "sami" -> SmiParser.parse(String(utf8, Charsets.UTF_8)).forEach { line ->
            entries.add(CueTimeline.Entry(line.startMs * 1000, line.endMs * 1000, Cue.Builder().setText(line.text).build()))
        }
        else -> {
            val parser: SubtitleParser = when (file.extension.lowercase()) {
                "srt" -> SubripParser()
                "ass", "ssa" -> SsaParser()
                "vtt" -> WebvttParser()
                else -> return null
            }
            parser.parse(utf8, SubtitleParser.OutputOptions.allCues()) { c ->
                val end = if (c.durationUs > 0) c.startTimeUs + c.durationUs else c.endTimeUs
                c.cues.forEach { cue -> entries.add(CueTimeline.Entry(c.startTimeUs, end, cue)) }
            }
        }
    }
    if (entries.isEmpty()) null else ExternalSubtitle(file, cs, CueTimeline(entries))
}.getOrNull()

/** Text colors offered for subtitles. */
val SUBTITLE_COLORS = listOf(AndroidColor.WHITE, 0xFFFFE14D.toInt(), 0xFF7FE3FF.toInt())

/** Applies the user's subtitle look: size, color, and either an outline or a dark box behind the text. */
fun SubtitleView.applyLook(scale: Float, colorIndex: Int, box: Boolean) {
    val fg = SUBTITLE_COLORS.getOrElse(colorIndex) { AndroidColor.WHITE }
    setStyle(
        CaptionStyleCompat(
            fg,
            if (box) 0xB3000000.toInt() else AndroidColor.TRANSPARENT,
            AndroidColor.TRANSPARENT,
            if (box) CaptionStyleCompat.EDGE_TYPE_NONE else CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            AndroidColor.BLACK,
            Typeface.create(Typeface.DEFAULT, Typeface.BOLD),
        ),
    )
    setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale)
}
