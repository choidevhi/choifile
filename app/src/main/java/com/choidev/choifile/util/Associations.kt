package com.choidev.choifile.util

import org.json.JSONObject

/** What to do when the user opens a file with a given extension. */
sealed interface OpenChoice {
    /** Use choifile's own viewer (image, video, text, PDF, Markdown, comic for .cbz, folder view for archives). */
    data object BuiltIn : OpenChoice

    /** Always show the "open with" sheet, even for types the built-in viewer handles. */
    data object Ask : OpenChoice

    /** A specific activity of another app. [label] is only for display; the app may have been renamed since. */
    data class App(val packageName: String, val activity: String, val label: String) : OpenChoice
}

/** Lowercase extension without the dot, "" when there is none. "Photo.JPG" -> "jpg", "a.tar.gz" -> "gz". */
fun extensionKey(name: String): String {
    val base = name.substringAfterLast('/').substringAfterLast('\\')
    val dot = base.lastIndexOf('.')
    return if (dot <= 0 || dot == base.length - 1) "" else base.substring(dot + 1).lowercase()
}

/**
 * Remembered default per extension. Pure (no Android) so it can be unit tested; [AppPrefs] stores the JSON.
 * No entry means the default behaviour: built-in viewer when there is one, otherwise ask.
 * Files without an extension cannot get a default (there is nothing reliable to key on).
 */
class Associations(initial: Map<String, OpenChoice> = emptyMap()) {
    private val map = LinkedHashMap(initial)

    @Synchronized fun get(fileName: String): OpenChoice? = extensionKey(fileName).takeIf { it.isNotEmpty() }?.let { map[it] }

    @Synchronized fun set(extension: String, choice: OpenChoice) {
        val key = extension.trim().removePrefix(".").lowercase()
        if (key.isEmpty()) return
        map[key] = choice
    }

    @Synchronized fun clear(extension: String) { map.remove(extension.lowercase()) }

    @Synchronized fun clearAll() = map.clear()

    /** Sorted by extension, for the settings list. */
    @Synchronized fun all(): List<Pair<String, OpenChoice>> = map.entries.sortedBy { it.key }.map { it.key to it.value }

    /** Drops every default that points at [packageName] (the app was uninstalled or no longer accepts the file). */
    @Synchronized fun forgetPackage(packageName: String) {
        map.entries.removeAll { (it.value as? OpenChoice.App)?.packageName == packageName }
    }

    @Synchronized fun encode(): String = AssociationCodec.encode(map)

    companion object {
        fun decode(json: String?) = Associations(AssociationCodec.decode(json))
    }
}

object AssociationCodec {
    fun encode(map: Map<String, OpenChoice>): String {
        val root = JSONObject()
        for ((ext, c) in map) {
            root.put(ext, when (c) {
                OpenChoice.BuiltIn -> JSONObject().put("k", "builtin")
                OpenChoice.Ask -> JSONObject().put("k", "ask")
                is OpenChoice.App -> JSONObject().put("k", "app").put("p", c.packageName).put("a", c.activity).put("l", c.label)
            })
        }
        return root.toString()
    }

    fun decode(json: String?): Map<String, OpenChoice> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val root = JSONObject(json)
            val out = LinkedHashMap<String, OpenChoice>()
            for (ext in root.keys()) {
                val o = root.optJSONObject(ext) ?: continue
                val c = when (o.optString("k")) {
                    "builtin" -> OpenChoice.BuiltIn
                    "ask" -> OpenChoice.Ask
                    "app" -> {
                        val p = o.optString("p")
                        val a = o.optString("a")
                        if (p.isEmpty() || a.isEmpty()) null else OpenChoice.App(p, a, o.optString("l", p))
                    }
                    else -> null
                }
                if (c != null) out[ext] = c
            }
            out
        }.getOrDefault(emptyMap())
    }
}
