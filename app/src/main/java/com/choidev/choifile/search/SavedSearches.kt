package com.choidev.choifile.search

import org.json.JSONArray
import org.json.JSONObject

data class SavedSearch(val name: String, val query: SearchQuery)

object SavedSearchCodec {
    fun encode(list: List<SavedSearch>): String {
        val arr = JSONArray()
        for (s in list) {
            val q = s.query
            arr.put(
                JSONObject()
                    .put("name", s.name)
                    .put("text", q.text)
                    .put("ext", JSONArray(q.extensions.toList()))
                    .put("kinds", JSONArray(q.kinds.map { it.name }))
                    .put("minSize", q.minSize ?: JSONObject.NULL)
                    .put("maxSize", q.maxSize ?: JSONObject.NULL)
                    .put("after", q.modifiedAfter ?: JSONObject.NULL)
                    .put("before", q.modifiedBefore ?: JSONObject.NULL)
                    .put("content", q.content)
                    .put("within", q.withinDays ?: JSONObject.NULL),
            )
        }
        return arr.toString()
    }

    fun decode(json: String?): List<SavedSearch> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                fun strings(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                fun optLong(key: String) = if (o.isNull(key)) null else o.getLong(key)
                SavedSearch(
                    o.getString("name"),
                    SearchQuery(
                        text = o.optString("text"),
                        extensions = strings("ext").toSet(),
                        kinds = strings("kinds").mapNotNull { k -> KindFilter.entries.firstOrNull { it.name == k } }.toSet(),
                        minSize = optLong("minSize"),
                        maxSize = optLong("maxSize"),
                        modifiedAfter = optLong("after"),
                        modifiedBefore = optLong("before"),
                        content = o.optBoolean("content"),
                        withinDays = if (o.isNull("within")) null else o.getInt("within"),
                    ),
                )
            }
        }.getOrDefault(emptyList())
    }
}
