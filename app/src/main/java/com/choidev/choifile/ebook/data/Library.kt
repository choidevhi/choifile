package com.choidev.choifile.ebook.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.choidev.choifile.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Bookmark(val offset: Int, val createdAt: Long, val snippet: String)

data class Book(
    val id: String,
    val title: String,
    val charCount: Int,
    val addedAt: Long,
    val lastReadAt: Long,
    /** 마지막으로 읽던 쪽의 첫 글자 위치. 글꼴·크기가 바뀌어도 같은 곳을 찾을 수 있게 쪽 번호 대신 글자 위치로 둔다. */
    val offset: Int,
    val encoding: String,
    /** 문장 중간의 강제 줄바꿈을 이어 붙여 보여 줄지. 글자 수는 그대로라 위치·책갈피가 어긋나지 않는다. */
    val reflow: Boolean = false,
    val bookmarks: List<Bookmark> = emptyList(),
) {
    val progress: Float get() = if (charCount <= 0) 0f else (offset.toFloat() / charCount).coerceIn(0f, 1f)
}

/**
 * 가져온 텍본을 앱 내부 저장소에 UTF-8로 복사해 두고 서재를 관리한다.
 * 책 목록(books.json)은 책을 넣고 빼거나 이름을 바꿀 때만 쓰고, 쪽마다 바뀌는 읽던 위치와 책갈피는
 * 책마다 작은 파일(<id>.state.json)에 따로 써서 책이 많아도 저장이 가볍다.
 */
class Library(private val context: Context) {

    private val dir = File(context.filesDir, "books").apply { mkdirs() }
    private val indexFile = File(dir, "books.json")
    private val _books = MutableStateFlow(load())
    val books: StateFlow<List<Book>> = _books.asStateFlow()

    init {
        // 1.0.x에서 옮겨 온 책은 위치가 books.json에만 있다. 목록이 새 형식으로 다시 쓰이기 전에 책별 파일로 옮겨 둔다.
        _books.value.filterNot { stateFile(it.id).exists() }.forEach { writeAtomic(stateFile(it.id), stateJson(it)) }
    }

    fun textFile(id: String) = File(dir, "$id.txt")

    private fun stateFile(id: String) = File(dir, "$id.state.json")

    fun find(id: String): Book? = _books.value.firstOrNull { it.id == id }

    /** 화면에 보여 줄 본문. 줄바꿈 정리를 켠 책은 정리된 본문을 돌려준다. */
    suspend fun readText(id: String, reflow: Boolean): String = withContext(Dispatchers.Default) {
        val raw = withContext(Dispatchers.IO) { textFile(id).readText(Charsets.UTF_8) }
        if (reflow) Reflow.apply(raw) else raw
    }

    suspend fun import(uri: Uri): Book = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: context.getString(R.string.eb_untitled)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error(context.getString(R.string.eb_error_file_open))
        val decoded = TextDecoder.decode(bytes)
        val title = name.substringBeforeLast('.').ifBlank { name }

        // 같은 파일을 두 번 넣으면 새로 만들지 않고 기존 책을 돌려준다(읽던 위치 유지).
        val existing = _books.value.firstOrNull { it.title == title && it.charCount == decoded.text.length }
        if (existing != null) return@withContext existing

        val book = Book(
            id = UUID.randomUUID().toString(),
            title = title,
            charCount = decoded.text.length,
            addedAt = System.currentTimeMillis(),
            lastReadAt = 0L,
            offset = 0,
            encoding = decoded.encoding,
            reflow = Reflow.looksHardWrapped(decoded.text),
        )
        textFile(book.id).writeText(decoded.text, Charsets.UTF_8)
        updateIndex { it + book }
        book
    }

    fun savePosition(id: String, offset: Int) {
        updateState(id) { it.copy(offset = offset, lastReadAt = System.currentTimeMillis()) }
    }

    fun addBookmark(id: String, bookmark: Bookmark) {
        updateState(id) { b -> b.copy(bookmarks = (b.bookmarks + bookmark).sortedBy { it.offset }) }
    }

    fun removeBookmark(id: String, offset: Int) {
        updateState(id) { b -> b.copy(bookmarks = b.bookmarks.filterNot { it.offset == offset }) }
    }

    fun rename(id: String, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return

        updateIndex { list -> list.map { if (it.id == id) it.copy(title = clean) else it } }
    }

    fun setReflow(id: String, reflow: Boolean) {
        updateIndex { list -> list.map { if (it.id == id) it.copy(reflow = reflow) else it } }
    }

    fun delete(ids: Collection<String>) {
        ids.forEach { id ->
            textFile(id).delete()
            stateFile(id).delete()
            File(context.cacheDir, "pages").listFiles { f -> f.name.startsWith(id) }?.forEach { it.delete() }
        }
        updateIndex { list -> list.filterNot { it.id in ids } }
    }

    @Synchronized
    private fun updateIndex(change: (List<Book>) -> List<Book>) {
        val next = change(_books.value)
        _books.value = next
        writeAtomic(indexFile, indexJson(next))
    }

    @Synchronized
    private fun updateState(id: String, change: (Book) -> Book) {
        val current = find(id) ?: return
        val next = change(current)
        _books.value = _books.value.map { if (it.id == id) next else it }
        writeAtomic(stateFile(id), stateJson(next))
    }

    private fun indexJson(list: List<Book>): String {
        val array = JSONArray()
        list.forEach { b ->
            array.put(
                JSONObject()
                    .put("id", b.id)
                    .put("title", b.title)
                    .put("charCount", b.charCount)
                    .put("addedAt", b.addedAt)
                    .put("encoding", b.encoding)
                    .put("reflow", b.reflow),
            )
        }

        return array.toString()
    }

    private fun stateJson(b: Book): String {
        val marks = JSONArray()
        b.bookmarks.forEach { m ->
            marks.put(JSONObject().put("offset", m.offset).put("createdAt", m.createdAt).put("snippet", m.snippet))
        }

        return JSONObject()
            .put("offset", b.offset)
            .put("lastReadAt", b.lastReadAt)
            .put("bookmarks", marks)
            .toString()
    }

    // 임시 파일에 쓴 뒤 바꿔 끼워서, 쓰는 도중 앱이 꺼져도 파일이 깨지지 않게 한다.
    private fun writeAtomic(target: File, content: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private fun load(): List<Book> {
        if (!indexFile.exists()) return emptyList()

        return runCatching {
            val array = JSONArray(indexFile.readText(Charsets.UTF_8))
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val id = o.getString("id")
                // 1.0.x는 위치를 books.json에 함께 적었다. 책별 상태 파일이 없으면 그 값을 쓴다.
                val state = runCatching { JSONObject(stateFile(id).readText(Charsets.UTF_8)) }.getOrNull()
                Book(
                    id = id,
                    title = o.getString("title"),
                    charCount = o.getInt("charCount"),
                    addedAt = o.getLong("addedAt"),
                    lastReadAt = state?.optLong("lastReadAt") ?: o.optLong("lastReadAt", 0L),
                    offset = state?.optInt("offset") ?: o.optInt("offset", 0),
                    encoding = o.optString("encoding", "UTF-8"),
                    reflow = o.optBoolean("reflow", false),
                    bookmarks = state?.optJSONArray("bookmarks")?.let { parseBookmarks(it) }.orEmpty(),
                )
            }.filter { textFile(it.id).exists() }
        }.getOrDefault(emptyList())
    }

    private fun parseBookmarks(array: JSONArray): List<Bookmark> = (0 until array.length()).map { i ->
        val m = array.getJSONObject(i)
        Bookmark(m.getInt("offset"), m.optLong("createdAt", 0L), m.optString("snippet", ""))
    }

    private fun displayName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment

        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
    }
}
