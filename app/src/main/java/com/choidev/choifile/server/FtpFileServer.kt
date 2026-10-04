package com.choidev.choifile.server

import com.choidev.choifile.security.PathGuard
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A deliberately small FTP server: passive mode only, the token as password (any user name), browse and download,
 * upload of new files and new folders. It never overwrites, deletes or renames anything, and ".." can never climb
 * above the shared folder. Plain FTP is not encrypted; the screen says so.
 */
class FtpFileServer(
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
    private val sockets = java.util.Collections.synchronizedSet(HashSet<Socket>())

    val port: Int get() = server?.localPort ?: -1

    @Synchronized fun start(): Int {
        check(!running) { "already running" }
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(bindAddress, requestedPort))
        server = ss
        running = true
        val ex = Executors.newFixedThreadPool(16) { r -> Thread(r, "choifile-ftp").also { it.isDaemon = true } }
        pool = ex
        Thread({
            while (running) {
                val s = try { ss.accept() } catch (_: IOException) { break }
                try { ex.execute { Session(s).run() } } catch (_: Exception) { runCatching { s.close() } }
            }
        }, "choifile-ftp-accept").also { it.isDaemon = true }.start()
        return ss.localPort
    }

    @Synchronized fun stop() {
        running = false
        runCatching { server?.close() }
        synchronized(sockets) { sockets.toList() }.forEach { runCatching { it.close() } }
        pool?.shutdownNow()
        server = null
    }

    private inner class Session(val ctl: Socket) {
        var cwd = "/"
        var user: String? = null
        var authed = false
        var failures = 0
        var pasv: ServerSocket? = null
        var restart = 0L
        private lateinit var out: OutputStream

        fun reply(code: Int, text: String) {
            out.write("$code $text\r\n".toByteArray(Charsets.UTF_8))
            out.flush()
        }

        fun run() {
            sockets += ctl
            try {
                ctl.use {
                    ctl.soTimeout = 300_000
                    out = ctl.getOutputStream()
                    val remote = ctl.inetAddress.hostAddress ?: "?"
                    if (throttle.isBlocked(remote)) { reply(421, "Too many failed attempts"); return }
                    val reader = BufferedReader(InputStreamReader(ctl.getInputStream(), Charsets.UTF_8))
                    reply(220, "choifile FTP ready")
                    while (running) {
                        val line = reader.readLine() ?: break
                        if (line.length > 4096) { reply(500, "Line too long"); continue }
                        val cmd = line.substringBefore(' ').uppercase()
                        val arg = if (' ' in line) line.substringAfter(' ').trim() else ""
                        if (!command(cmd, arg, remote)) break
                    }
                }
            } catch (_: IOException) {
            } finally {
                closePasv()
                sockets -= ctl
            }
        }

        /** Returns false to end the session. */
        fun command(cmd: String, arg: String, remote: String): Boolean {
            when (cmd) {
                "USER" -> { user = arg; authed = false; reply(331, "Password required") }
                "PASS" -> {
                    if (user == null) { reply(503, "Send USER first"); return true }
                    if (throttle.isBlocked(remote)) { reply(421, "Too many failed attempts"); return false }
                    if (ServerToken.matches(token, arg)) { authed = true; throttle.succeeded(remote); reply(230, "Logged in") }
                    else {
                        throttle.failed(remote)
                        reply(530, "Login incorrect")
                        if (++failures >= 3) return false
                    }
                }
                "QUIT" -> { reply(221, "Bye"); return false }
                "NOOP" -> reply(200, "OK")
                "FEAT" -> { out.write("211-Features:\r\n UTF8\r\n EPSV\r\n SIZE\r\n REST STREAM\r\n211 End\r\n".toByteArray()); out.flush() }
                "OPTS" -> reply(200, "OK")
                else -> {
                    if (!authed) { reply(530, "Please log in"); return true }
                    authedCommand(cmd, arg)
                }
            }
            return true
        }

        private fun target(arg: String): File? {
            val v = SafePath.normalizeVirtual(cwd, arg)
            val f = SafePath.resolve(root, v) ?: return null
            return if (guard.isExcluded(f.absolutePath)) null else f
        }

        private fun authedCommand(cmd: String, arg: String) {
            when (cmd) {
                "SYST" -> reply(215, "UNIX Type: L8")
                "TYPE", "MODE", "STRU" -> reply(200, "OK")
                "PWD", "XPWD" -> reply(257, "\"${cwd.replace("\"", "\"\"")}\" is the current directory")
                "CWD", "XCWD", "CDUP", "XCUP" -> {
                    val path = if (cmd.endsWith("UP")) ".." else arg
                    val f = target(path)
                    if (f != null && f.isDirectory) { cwd = SafePath.normalizeVirtual(cwd, path); reply(250, "OK") } else reply(550, "No such directory")
                }
                "PASV" -> {
                    val local = ctl.localAddress
                    if (local !is Inet4Address) { reply(522, "Use EPSV"); return }
                    val ss = openPasv() ?: run { reply(425, "Cannot open data port"); return }
                    val p = ss.localPort
                    val ip = local.address.joinToString(",") { (it.toInt() and 0xff).toString() }
                    reply(227, "Entering Passive Mode ($ip,${p shr 8},${p and 0xff})")
                }
                "EPSV" -> {
                    val ss = openPasv() ?: run { reply(425, "Cannot open data port"); return }
                    reply(229, "Entering Extended Passive Mode (|||${ss.localPort}|)")
                }
                "PORT", "EPRT" -> reply(502, "Active mode is not supported, use passive")
                "REST" -> { restart = arg.toLongOrNull()?.coerceAtLeast(0) ?: 0; reply(350, "Restarting at $restart") }
                "LIST", "NLST" -> list(cmd, arg)
                "RETR" -> retrieve(arg)
                "STOR" -> store(arg)
                "SIZE" -> {
                    val f = target(arg)
                    if (f != null && f.isFile) reply(213, f.length().toString()) else reply(550, "Not a file")
                }
                "MKD", "XMKD" -> {
                    val f = target(arg)
                    when {
                        !allowUpload -> reply(550, "Uploads are disabled")
                        f == null || f.name.startsWith(".") -> reply(550, "Not allowed")
                        f.exists() -> reply(550, "Already exists")
                        f.parentFile?.isDirectory != true -> reply(550, "Parent folder missing")
                        f.mkdir() -> reply(257, "\"${SafePath.normalizeVirtual(cwd, arg)}\" created")
                        else -> reply(550, "Could not create")
                    }
                }
                "DELE", "RMD", "XRMD", "RNFR", "RNTO", "APPE", "STOU" -> reply(502, "Not supported: this server only browses, downloads and uploads new files")
                else -> reply(500, "Unknown command")
            }
        }

        private fun openPasv(): ServerSocket? {
            closePasv()
            return try {
                ServerSocket(0, 1, ctl.localAddress).also { pasv = it }
            } catch (_: IOException) { null }
        }

        private fun closePasv() { runCatching { pasv?.close() }; pasv = null }

        /** Accepts the client's data connection, only from the same address as the control connection. */
        private fun data(): Socket? {
            val ss = pasv ?: run { reply(425, "Use PASV first"); return null }
            return try {
                ss.soTimeout = 15_000
                val s = ss.accept()
                if (s.inetAddress != ctl.inetAddress) { s.close(); reply(425, "Data connection refused"); null } else s
            } catch (_: IOException) {
                reply(425, "Cannot open data connection"); null
            } finally {
                closePasv()
            }
        }

        private fun list(cmd: String, rawArg: String) {
            val arg = rawArg.split(' ').filter { !it.startsWith("-") }.joinToString(" ")
            val f = if (arg.isEmpty()) target(".") else target(arg)
            if (f == null || !f.exists()) { reply(550, "No such file or directory"); return }
            val entries = if (f.isDirectory) (f.listFiles() ?: emptyArray()).filter { !it.name.startsWith(".") && !guard.isExcluded(it.absolutePath) }.sortedBy { it.name.lowercase() } else listOf(f)
            reply(150, "Opening data connection")
            val s = data() ?: return
            try {
                s.use { sock ->
                    val w = sock.getOutputStream().bufferedWriter(Charsets.UTF_8)
                    for (e in entries) {
                        if (cmd == "NLST") w.write(e.name + "\r\n") else w.write(unixLine(e) + "\r\n")
                    }
                    w.flush()
                }
                reply(226, "Done")
            } catch (_: IOException) { reply(426, "Transfer aborted") }
        }

        private fun unixLine(f: File): String {
            val now = System.currentTimeMillis()
            val old = now - f.lastModified() > 180L * 86_400_000L
            val date = SimpleDateFormat(if (old) "MMM dd  yyyy" else "MMM dd HH:mm", Locale.US).format(Date(f.lastModified()))
            val perm = if (f.isDirectory) "dr-xr-xr-x" else "-r--r--r--"
            return "$perm 1 choifile choifile ${if (f.isDirectory) 4096 else f.length()} $date ${f.name}"
        }

        private fun retrieve(arg: String) {
            val f = target(arg)
            if (f == null || !f.isFile) { reply(550, "No such file"); return }
            val start = restart.also { restart = 0 }
            reply(150, "Opening data connection")
            val s = data() ?: return
            try {
                s.use { sock ->
                    f.inputStream().use { i ->
                        var skip = start
                        while (skip > 0) { val n = i.skip(skip); if (n <= 0) break; skip -= n }
                        i.copyTo(sock.getOutputStream(), 64 * 1024)
                    }
                    sock.getOutputStream().flush()
                }
                reply(226, "Done")
            } catch (_: IOException) { reply(426, "Transfer aborted") }
        }

        private fun store(arg: String) {
            val f = target(arg)
            when {
                !allowUpload -> { reply(550, "Uploads are disabled"); return }
                f == null || f.name.startsWith(".") -> { reply(550, "Not allowed"); return }
                f.exists() -> { reply(550, "File exists; this server never overwrites"); return }
                f.parentFile?.isDirectory != true -> { reply(550, "Target folder missing"); return }
            }
            f!!
            restart = 0
            reply(150, "Opening data connection")
            val s = data() ?: return
            val part = File(f.parentFile, ".cf-upload-" + System.nanoTime() + ".part")
            var ok = false
            try {
                s.use { sock -> part.outputStream().use { o -> sock.getInputStream().copyTo(o, 64 * 1024) } }
                ok = part.renameTo(f)
            } catch (_: IOException) {
            } finally {
                if (!ok) part.delete()
            }
            if (ok) reply(226, "Stored") else reply(426, "Transfer aborted")
        }
    }
}
