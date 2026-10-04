package com.choidev.choifile.server

import com.choidev.choifile.security.PathGuard
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A small HTTP/1.1 file server: browse folders, download (with Range), and upload with PUT. Every request needs the
 * token (Basic auth password, Bearer header, or the cf_token cookie / ?token= that sets it). It never overwrites an
 * existing file and never leaves the shared folder. One response per connection (Connection: close).
 *
 * Auth and path checks happen before any file is touched; the unit tests cover both.
 */
class HttpFileServer(
    private val root: File,
    private val token: String,
    private val guard: PathGuard,
    private val allowUpload: Boolean = true,
    private val bindAddress: InetAddress? = null,
    private val requestedPort: Int = 0,
    private val throttle: AuthThrottle = AuthThrottle(),
) {
    private var server: ServerSocket? = null
    private var pool: ExecutorService? = null
    @Volatile private var running = false

    val port: Int get() = server?.localPort ?: -1

    @Synchronized fun start(): Int {
        check(!running) { "already running" }
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(bindAddress, requestedPort))
        server = ss
        running = true
        val ex = Executors.newFixedThreadPool(8) { r -> Thread(r, "choifile-http").also { it.isDaemon = true } }
        pool = ex
        Thread({
            while (running) {
                val s = try { ss.accept() } catch (_: IOException) { break }
                try { ex.execute { handle(s) } } catch (_: Exception) { runCatching { s.close() } }
            }
        }, "choifile-http-accept").also { it.isDaemon = true }.start()
        return ss.localPort
    }

    @Synchronized fun stop() {
        running = false
        runCatching { server?.close() }
        pool?.shutdownNow()
        server = null
    }

    // ---- request handling ----

    private class Request(val method: String, val target: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: InputStream)

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 30_000
                val input = BufferedInputStream(s.getInputStream())
                val out = s.getOutputStream()
                val req = parse(input, out) ?: return
                route(req, out, s.inetAddress.hostAddress ?: "?")
            } catch (_: SocketTimeoutException) {
            } catch (_: IOException) {
            } catch (_: Exception) {
                runCatching { respond(s.getOutputStream(), 500, "Server Error", text("Server error")) }
            }
        }
    }

    private fun parse(input: BufferedInputStream, out: OutputStream): Request? {
        val line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size != 3) { respond(out, 400, "Bad Request", text("Bad request")); return null }
        val headers = HashMap<String, String>()
        var total = 0
        while (true) {
            val h = readLine(input) ?: return null
            if (h.isEmpty()) break
            total += h.length
            if (total > 32 * 1024 || headers.size > 100) { respond(out, 431, "Request Header Fields Too Large", text("Headers too large")); return null }
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        val target = parts[1]
        val rawPath = target.substringBefore('?')
        val rawQuery = if ('?' in target) target.substringAfter('?') else ""
        val query = rawQuery.split('&').filter { it.isNotEmpty() }.associate {
            val k = it.substringBefore('=')
            val v = if ('=' in it) it.substringAfter('=') else ""
            decode(k) to decode(v)
        }
        return Request(parts[0].uppercase(), target, decode(rawPath), query, headers, input)
    }

    private fun readLine(input: InputStream): String? {
        val sb = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.size() == 0) null else sb.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.write(b)
            if (sb.size() > 8192) throw IOException("line too long")
        }
        return sb.toString("ISO-8859-1")
    }

    private fun decode(s: String) = try { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { s }

    private fun route(req: Request, out: OutputStream, remote: String) {
        if (throttle.isBlocked(remote)) { respond(out, 429, "Too Many Requests", text("Too many failed attempts. Try again later.")); return }
        val authed = authenticate(req)
        if (!authed) {
            // Only wrong credentials count; a browser's first request has none and must not lock the user out.
            if (req.headers["authorization"] != null || req.query["token"] != null || req.headers["cookie"]?.contains("cf_token=") == true) throttle.failed(remote)
            respond(out, 401, "Unauthorized", text("Authentication required"), mapOf("WWW-Authenticate" to "Basic realm=\"choifile\", charset=\"UTF-8\""))
            return
        }
        throttle.succeeded(remote)
        val cookie = if (req.query["token"] != null) mapOf("Set-Cookie" to "cf_token=$token; Path=/; HttpOnly; SameSite=Strict") else emptyMap()

        val file = SafePath.resolve(root, req.path)
        if (file == null || guard.isExcluded(file.absolutePath)) { respond(out, 403, "Forbidden", text("Forbidden"), cookie); return }
        when (req.method) {
            "GET", "HEAD" -> serve(req, file, out, cookie, head = req.method == "HEAD")
            "PUT" -> upload(req, file, out)
            else -> respond(out, 405, "Method Not Allowed", text("Method not allowed"), mapOf("Allow" to "GET, HEAD, PUT"))
        }
    }

    private fun authenticate(req: Request): Boolean {
        val header = req.headers["authorization"]
        if (header != null) {
            if (header.startsWith("Bearer ", ignoreCase = true) && ServerToken.matches(token, header.substring(7).trim())) return true
            if (header.startsWith("Basic ", ignoreCase = true)) {
                val decoded = try { String(Base64.getDecoder().decode(header.substring(6).trim()), Charsets.UTF_8) } catch (_: Exception) { null }
                if (decoded != null && ServerToken.matches(token, decoded.substringAfter(':', ""))) return true
            }
        }
        if (ServerToken.matches(token, req.query["token"])) return true
        val cookie = req.headers["cookie"]
        if (cookie != null) {
            val value = cookie.split(';').map { it.trim() }.firstOrNull { it.startsWith("cf_token=") }?.substringAfter('=')
            if (ServerToken.matches(token, value)) return true
        }
        return false
    }

    private fun serve(req: Request, file: File, out: OutputStream, cookie: Map<String, String>, head: Boolean) {
        if (!file.exists()) { respond(out, 404, "Not Found", text("Not found"), cookie); return }
        if (file.isDirectory) {
            if (!req.path.endsWith("/")) {
                respond(out, 301, "Moved Permanently", Body(ByteArray(0), "text/plain"), cookie + ("Location" to (encodePath(req.path) + "/")))
                return
            }
            respond(out, 200, "OK", Body(listing(req.path, file).toByteArray(Charsets.UTF_8), "text/html; charset=utf-8"), cookie, head)
            return
        }
        val length = file.length()
        var start = 0L
        var end = length - 1
        var status = 200
        val range = req.headers["range"]
        if (range != null && range.startsWith("bytes=") && ',' !in range) {
            val spec = range.substring(6)
            val a = spec.substringBefore('-')
            val b = spec.substringAfter('-', "")
            try {
                if (a.isEmpty()) { start = maxOf(0, length - b.toLong()) }
                else { start = a.toLong(); if (b.isNotEmpty()) end = minOf(end, b.toLong()) }
                if (start > end || start >= length) {
                    respond(out, 416, "Range Not Satisfiable", Body(ByteArray(0), "text/plain"), mapOf("Content-Range" to "bytes */$length"))
                    return
                }
                status = 206
            } catch (_: NumberFormatException) { /* ignore a malformed range and send everything */ }
        }
        val len = end - start + 1
        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = "application/octet-stream"
        headers["Content-Length"] = len.toString()
        headers["Accept-Ranges"] = "bytes"
        headers["Content-Disposition"] = "attachment; filename*=UTF-8''" + URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
        headers["X-Content-Type-Options"] = "nosniff"
        if (status == 206) headers["Content-Range"] = "bytes $start-$end/$length"
        headers.putAll(cookie)
        writeHead(out, status, if (status == 206) "Partial Content" else "OK", headers)
        if (head) return
        file.inputStream().use { i ->
            var skip = start
            while (skip > 0) { val n = i.skip(skip); if (n <= 0) break; skip -= n }
            val buf = ByteArray(64 * 1024)
            var left = len
            while (left > 0) {
                val n = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
        out.flush()
    }

    private fun upload(req: Request, target: File, out: OutputStream) {
        if (!allowUpload) { respond(out, 403, "Forbidden", text("Uploads are disabled")); return }
        // A trailing slash creates a folder; otherwise the body becomes a new file (never replacing an existing one).
        if (req.path.endsWith("/")) {
            if (target.exists()) { respond(out, 409, "Conflict", text("Already exists")); return }
            if (!target.mkdirs()) { respond(out, 500, "Server Error", text("Could not create folder")); return }
            respond(out, 201, "Created", text("Created")); return
        }
        val dir = target.parentFile
        if (dir == null || !dir.isDirectory) { respond(out, 409, "Conflict", text("Target folder does not exist")); return }
        if (target.name.startsWith(".")) { respond(out, 403, "Forbidden", text("Hidden names are not allowed")); return }
        if (req.headers["transfer-encoding"] != null) { respond(out, 411, "Length Required", text("Content-Length required")); return }
        val length = req.headers["content-length"]?.toLongOrNull()
        if (length == null || length < 0) { respond(out, 411, "Length Required", text("Content-Length required")); return }
        if (dir.usableSpace < length) { respond(out, 507, "Insufficient Storage", text("Not enough space")); return }

        val final = uniqueIn(dir, target.name)
        val part = File(dir, ".cf-upload-" + System.nanoTime() + ".part")
        var ok = false
        try {
            part.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var left = length
                while (left > 0) {
                    val n = req.body.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw IOException("body ended early")
                    o.write(buf, 0, n)
                    left -= n
                }
            }
            ok = part.renameTo(final)
        } finally {
            if (!ok) part.delete()
        }
        if (!ok) { respond(out, 500, "Server Error", text("Could not save")); return }
        respond(out, 201, "Created", text("Created"), mapOf("Location" to encodePath(req.path.substringBeforeLast('/') + "/" + final.name)))
    }

    private fun uniqueIn(dir: File, name: String): File {
        var c = File(dir, name)
        if (!c.exists()) return c
        val base = name.substringBeforeLast('.', name).ifEmpty { name }
        val ext = if (base.length < name.length) name.substring(base.length) else ""
        var i = 1
        while (c.exists()) c = File(dir, "$base ($i)$ext").also { i++ }
        return c
    }

    // ---- responses ----

    private class Body(val bytes: ByteArray, val type: String)

    private fun text(s: String) = Body(s.toByteArray(Charsets.UTF_8), "text/plain; charset=utf-8")

    private fun respond(out: OutputStream, code: Int, reason: String, body: Body, extra: Map<String, String> = emptyMap(), head: Boolean = false) {
        val h = LinkedHashMap<String, String>()
        h["Content-Type"] = body.type
        h["Content-Length"] = body.bytes.size.toString()
        h["Cache-Control"] = "no-store"
        h["X-Content-Type-Options"] = "nosniff"
        h["Content-Security-Policy"] = "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'"
        h.putAll(extra)
        writeHead(out, code, reason, h)
        if (!head) out.write(body.bytes)
        out.flush()
    }

    private fun writeHead(out: OutputStream, code: Int, reason: String, headers: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $code $reason\r\n")
        sb.append("Date: ").append(httpDate(System.currentTimeMillis())).append("\r\n")
        sb.append("Connection: close\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v.replace("\r", "").replace("\n", "")).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun httpDate(ms: Long): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date(ms))

    private fun encodePath(p: String) = p.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun listing(path: String, dir: File): String {
        val shown = if (path.endsWith("/")) path else "$path/"
        val df: DateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val children = (dir.listFiles() ?: emptyArray())
            .filter { !it.name.startsWith(".") && !guard.isExcluded(it.absolutePath) }
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        val sb = StringBuilder()
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<title>choifile ").append(esc(shown)).append("</title><style>")
        sb.append("body{font:15px system-ui,sans-serif;margin:1.5rem auto;max-width:56rem;padding:0 1rem}table{width:100%;border-collapse:collapse}")
        sb.append("td,th{padding:.35rem .5rem;border-bottom:1px solid #8884;text-align:left}td.n,th.n{text-align:right;white-space:nowrap}a{text-decoration:none}")
        sb.append("#drop{border:2px dashed #8886;border-radius:8px;padding:1rem;margin:1rem 0}</style></head><body>")
        sb.append("<h2>choifile</h2><p>").append(esc(shown)).append("</p><table><tr><th>Name</th><th class=\"n\">Size</th><th class=\"n\">Modified</th></tr>")
        if (shown != "/") sb.append("<tr><td><a href=\"../\">../</a></td><td></td><td></td></tr>")
        for (f in children) {
            val name = f.name + if (f.isDirectory) "/" else ""
            sb.append("<tr><td><a href=\"").append(esc(encodePath(f.name))).append(if (f.isDirectory) "/" else "").append("\">").append(esc(name)).append("</a></td>")
            sb.append("<td class=\"n\">").append(if (f.isDirectory) "-" else humanSize(f.length())).append("</td>")
            sb.append("<td class=\"n\">").append(df.format(Date(f.lastModified()))).append("</td></tr>")
        }
        sb.append("</table>")
        if (allowUpload) {
            sb.append("<div id=\"drop\"><b>Upload</b> <input type=\"file\" id=\"f\" multiple> <button id=\"go\">Upload</button> <span id=\"st\"></span></div>")
            sb.append("<script>document.getElementById('go').onclick=async()=>{const fs=document.getElementById('f').files,st=document.getElementById('st');")
            sb.append("for(let i=0;i<fs.length;i++){st.textContent='Uploading '+fs[i].name+' ('+(i+1)+'/'+fs.length+')';")
            sb.append("const r=await fetch(encodeURIComponent(fs[i].name),{method:'PUT',body:fs[i],credentials:'same-origin'});")
            sb.append("if(!r.ok){st.textContent='Failed: '+fs[i].name+' ('+r.status+')';return}}st.textContent='Done';location.reload()}</script>")
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun humanSize(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.1f KB".format(Locale.US, b / 1024.0)
        b < 1024L * 1024 * 1024 -> "%.1f MB".format(Locale.US, b / 1048576.0)
        else -> "%.2f GB".format(Locale.US, b / 1073741824.0)
    }
}
