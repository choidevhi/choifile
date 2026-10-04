package com.choidev.choifile.security

import java.net.URLDecoder
import java.net.URLEncoder

data class SavedTab(val path: String, val grid: Boolean)

/** Compact text format for persisting tabs: one "grid|urlencodedPath" per line. */
object TabsCodec {
    fun encode(tabs: List<SavedTab>): String =
        tabs.joinToString("\n") { (if (it.grid) "1" else "0") + "|" + URLEncoder.encode(it.path, "UTF-8") }

    fun decode(text: String?): List<SavedTab> =
        text.orEmpty().lines().mapNotNull { line ->
            if (line.length < 2 || line[1] != '|') return@mapNotNull null
            runCatching { SavedTab(URLDecoder.decode(line.substring(2), "UTF-8"), line[0] == '1') }.getOrNull()
        }
}
