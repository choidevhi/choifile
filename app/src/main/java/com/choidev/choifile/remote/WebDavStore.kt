package com.choidev.choifile.remote

import com.choidev.choifile.storage.FileItem
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import org.w3c.dom.Element
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Small WebDAV client: PROPFIND/GET/PUT/MKCOL/DELETE/MOVE over OkHttp. [baseUrl] is the server root
 * including any path prefix, without trailing slash. Bodies are streamed in both directions.
 */
class WebDavStore(
    private val baseUrl: HttpUrl,
    username: String,
    password: String,
    client: OkHttpClient = OkHttpClient(),
) : RemoteStore {
    private val auth = if (username.isEmpty()) null else Credentials.basic(username, password, Charsets.UTF_8)
    private val http = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false) // a redirect to another host would carry our credentials along
        .build()

    private fun url(path: String): HttpUrl {
        val b = baseUrl.newBuilder()
        RemotePaths.normalize(path).split('/').filter { it.isNotEmpty() }.forEach { b.addPathSegment(it) }
        return b.build()
    }

    /** Collections are addressed with a trailing slash; many servers redirect otherwise, and we never follow redirects. */
    private fun dirUrl(path: String): HttpUrl = url(path).newBuilder().apply { if (!url(path).encodedPath.endsWith("/")) addPathSegment("") }.build()

    private fun req(path: String) = Request.Builder().url(url(path)).apply { auth?.let { header("Authorization", it) } }

    private fun Response.ok(vararg extra: Int) {
        if (!isSuccessful && code !in extra) { val c = code; close(); throw IOException("WebDAV: HTTP $c") }
    }

    override fun list(path: String): List<FileItem> {
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
        val r = http.newCall(req(path).url(dirUrl(path)).header("Depth", "1").method("PROPFIND", RequestBody.create("application/xml".toMediaType(), body)).build()).execute()
        r.use {
            it.ok()
            return parseMultistatus(it.body!!.byteStream(), baseUrl.encodedPath.trimEnd('/'), RemotePaths.normalize(path))
        }
    }

    override fun open(path: String): InputStream {
        val r = http.newCall(req(path).get().build()).execute()
        r.ok()
        val s = r.body!!.byteStream()
        return object : FilterInputStream(s) { override fun close() { try { super.close() } finally { r.close() } } }
    }

    override fun create(path: String, sizeHint: Long): OutputStream {
        val pipeIn = PipedInputStream(256 * 1024)
        val pipeOut = PipedOutputStream(pipeIn)
        var failure: Throwable? = null
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = sizeHint
            override fun writeTo(sink: BufferedSink) {
                val buf = ByteArray(64 * 1024)
                while (true) { val n = pipeIn.read(buf); if (n < 0) break; sink.write(buf, 0, n) }
            }
            override fun isOneShot() = true
        }
        val worker = Thread({
            try { http.newCall(req(path).put(body).build()).execute().use { it.ok() } } catch (t: Throwable) {
                failure = t
                runCatching { pipeIn.close() } // unblocks a writer waiting on a full pipe
            }
        }, "webdav-put")
        worker.isDaemon = true
        worker.start()
        return object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                try { pipeOut.write(b, off, len) } catch (e: IOException) { throw failure?.let { IOException(it.message, it) } ?: e }
            }
            override fun close() {
                runCatching { pipeOut.close() }
                worker.join()
                failure?.let { throw if (it is IOException) it else IOException(it.message, it) }
            }
        }
    }

    override fun mkdir(path: String) {
        var cur = ""
        for (part in RemotePaths.normalize(path).split('/').filter { it.isNotEmpty() }) {
            cur += "/$part"
            // 405 = already exists
            http.newCall(req(cur).url(dirUrl(cur)).method("MKCOL", null).build()).execute().use { it.ok(405) }
        }
    }

    override fun delete(path: String) {
        http.newCall(req(path).delete().build()).execute().use { it.ok() }
    }

    override fun rename(from: String, to: String) {
        val dest = url(to).toString()
        http.newCall(req(from).method("MOVE", null).header("Destination", dest).header("Overwrite", "F").build()).execute().use { it.ok() }
    }

    override fun close() { http.dispatcher.executorService.shutdown() }

    companion object {
        /** [basePrefix] is the encoded URL path of the server root; [self] the listed folder, to skip its own entry. */
        internal fun parseMultistatus(xml: InputStream, basePrefix: String, self: String): List<FileItem> {
            val f = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } // no XXE
            }
            val doc = f.newDocumentBuilder().parse(xml)
            val out = ArrayList<FileItem>()
            val responses = doc.getElementsByTagNameNS("DAV:", "response")
            for (i in 0 until responses.length) {
                val r = responses.item(i) as Element
                val href = r.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent ?: continue
                val rawPath = if (href.contains("://")) java.net.URI(href).rawPath else href
                val decoded = URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8")
                var rel = decoded
                val prefix = URLDecoder.decode(basePrefix.replace("+", "%2B"), "UTF-8")
                if (prefix.isNotEmpty() && rel.startsWith(prefix)) rel = rel.substring(prefix.length)
                val path = RemotePaths.normalize(rel)
                if (path == self) continue
                val isDir = r.getElementsByTagNameNS("DAV:", "collection").length > 0
                val size = r.getElementsByTagNameNS("DAV:", "getcontentlength").item(0)?.textContent?.trim()?.toLongOrNull() ?: 0L
                val modified = r.getElementsByTagNameNS("DAV:", "getlastmodified").item(0)?.textContent?.let(::parseHttpDate) ?: 0L
                val name = RemotePaths.name(path)
                out += FileItem(path, name, isDir, if (isDir) 0L else size, modified, name.startsWith("."))
            }
            return out
        }

        private fun parseHttpDate(s: String): Long = runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.parse(s.trim())!!.time
        }.getOrDefault(0L)

        fun fromConfig(c: ConnectionConfig): WebDavStore {
            val scheme = if (c.useTls) "https" else "http"
            val prefix = RemotePaths.normalize(c.basePath).trimEnd('/')
            val url = HttpUrl.Builder().scheme(scheme).host(c.host).port(c.port).apply {
                prefix.split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) }
            }.build()
            return WebDavStore(url, c.username, c.password)
        }
    }
}
