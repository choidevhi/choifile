package com.choidev.choifile.oplog

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class OpType { COPY, MOVE, RENAME, TRASH, DELETE, EXTRACT, ARCHIVE }

/** Where an operation came from: the user, or an automation rule (then [OpRecord.origin] is "rule:NAME"). */
const val ORIGIN_MANUAL = "manual"
fun ruleOrigin(name: String) = "rule:$name"

enum class UndoState { AVAILABLE, DONE, PARTIAL, UNAVAILABLE }

/** Why an operation cannot be undone; shown to the user. */
object NoUndo {
    const val PERMANENT = "permanent"
    const val SECURE = "secure"
    const val REMOTE = "remote"
    const val DRY_RUN = "dry"
}

data class CreatedFile(val path: String, val size: Long, val modified: Long)

/**
 * One thing an operation did. [src] and [dst] are the old and new path for move / rename / trash.
 * [created] and [createdDirs] list what a copy, extraction or archive brought into existence; undo may only remove those.
 */
data class OpItem(
    val src: String? = null,
    val dst: String? = null,
    val trashId: String? = null,
    val trashName: String? = null,
    val created: List<CreatedFile> = emptyList(),
    val createdDirs: List<String> = emptyList(),
)

data class OpRecord(
    val id: String = UUID.randomUUID().toString(),
    val time: Long,
    val type: OpType,
    val origin: String = ORIGIN_MANUAL,
    /** Items that went through. */
    val count: Int,
    val failed: Int = 0,
    val firstName: String = "",
    val items: List<OpItem> = emptyList(),
    val undoState: UndoState,
    /** One of [NoUndo] when [undoState] is UNAVAILABLE. */
    val reason: String? = null,
) {
    val isRule: Boolean get() = origin.startsWith("rule:")
    val ruleName: String get() = origin.removePrefix("rule:")
}

object OpCodec {
    fun encode(list: List<OpRecord>): String = JSONArray().also { arr ->
        list.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id); put("time", r.time); put("type", r.type.name); put("origin", r.origin)
                put("count", r.count); put("failed", r.failed); put("first", r.firstName)
                put("undo", r.undoState.name); r.reason?.let { put("reason", it) }
                put("items", JSONArray().also { a -> r.items.forEach { a.put(item(it)) } })
            })
        }
    }.toString()

    private fun item(i: OpItem) = JSONObject().apply {
        i.src?.let { put("s", it) }; i.dst?.let { put("d", it) }
        i.trashId?.let { put("ti", it) }; i.trashName?.let { put("tn", it) }
        if (i.created.isNotEmpty()) put("c", JSONArray().also { a ->
            i.created.forEach { a.put(JSONObject().put("p", it.path).put("s", it.size).put("m", it.modified)) }
        })
        if (i.createdDirs.isNotEmpty()) put("cd", JSONArray(i.createdDirs))
    }

    fun decode(raw: String?): List<OpRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { idx ->
                runCatching {
                    val o = arr.getJSONObject(idx)
                    val items = o.optJSONArray("items") ?: JSONArray()
                    OpRecord(
                        id = o.getString("id"), time = o.getLong("time"), type = OpType.valueOf(o.getString("type")),
                        origin = o.optString("origin", ORIGIN_MANUAL), count = o.getInt("count"), failed = o.optInt("failed"),
                        firstName = o.optString("first"), undoState = UndoState.valueOf(o.getString("undo")),
                        reason = if (o.has("reason")) o.getString("reason") else null,
                        items = (0 until items.length()).map { decodeItem(items.getJSONObject(it)) },
                    )
                }.getOrNull()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun decodeItem(o: JSONObject): OpItem {
        val c = o.optJSONArray("c")
        val cd = o.optJSONArray("cd")
        return OpItem(
            src = if (o.has("s")) o.getString("s") else null,
            dst = if (o.has("d")) o.getString("d") else null,
            trashId = if (o.has("ti")) o.getString("ti") else null,
            trashName = if (o.has("tn")) o.getString("tn") else null,
            created = if (c == null) emptyList() else (0 until c.length()).map {
                val f = c.getJSONObject(it); CreatedFile(f.getString("p"), f.getLong("s"), f.getLong("m"))
            },
            createdDirs = if (cd == null) emptyList() else (0 until cd.length()).map { cd.getString(it) },
        )
    }
}

/** Persisted list of file operations, newest first. Thread safe; an unreadable file starts a fresh log. */
class OperationLog(private val file: File, private val max: Int = 500) {
    private var records: List<OpRecord> = OpCodec.decode(runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull())
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(List<OpRecord>) -> Unit>()

    @Synchronized fun all(): List<OpRecord> = records

    @Synchronized fun get(id: String): OpRecord? = records.firstOrNull { it.id == id }

    @Synchronized fun add(record: OpRecord) {
        records = (listOf(record) + records).take(max)
        save()
    }

    @Synchronized fun update(record: OpRecord) {
        records = records.map { if (it.id == record.id) record else it }
        save()
    }

    @Synchronized fun clear() {
        records = emptyList()
        save()
    }

    fun addListener(l: (List<OpRecord>) -> Unit) { listeners += l }

    fun removeListener(l: (List<OpRecord>) -> Unit) { listeners -= l }

    private fun save() {
        val snapshot = records
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(OpCodec.encode(snapshot))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (_: Exception) {
            // The log is a convenience; a failed write must never fail the file operation itself.
        }
        listeners.forEach { it(snapshot) }
    }
}
