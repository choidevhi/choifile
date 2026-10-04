package com.choidev.choifile

import com.choidev.choifile.security.PathGuard
import com.choidev.choifile.server.AuthThrottle
import com.choidev.choifile.server.FtpFileServer
import com.choidev.choifile.server.HttpFileServer
import com.choidev.choifile.server.SafePath
import com.choidev.choifile.server.ServerToken
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.util.Base64

class ServerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val token = "s3cretTokenValue"
    private lateinit var shared: File
    private lateinit var outside: File
    private var http: HttpFileServer? = null
    private var ftp: FtpFileServer? = null
    private val local = InetAddress.getByName("127.0.0.1")

    @Before fun setUp() {
        val top = tmp.newFolder("top")
        shared = File(top, "share").also { it.mkdirs() }
        outside = File(top, "outside-secret.txt").also { it.writeText("TOP SECRET") }
        File(shared, "hello.txt").writeText("hello world")
        File(shared, "sub").mkdirs()
        File(shared, "sub/inner.txt").writeText("inner")
        File(shared, "Locked").mkdirs()
        File(shared, "Locked/p.txt").writeText("protected")
        File(shared, ".choifile_vault").mkdirs()
        File(shared, ".choifile_vault/v.txt").writeText("vault")
        File(shared, ".hidden").writeText("dotfile")
    }

    @After fun tearDown() { http?.stop(); ftp?.stop() }

    private fun guard() = PathGuard(setOf(File(shared, "Locked").absolutePath), isLocked = { false }, excludeAllProtected = true)

    private fun startHttp(allowUpload: Boolean = true, throttle: AuthThrottle = AuthThrottle()): Int =
        HttpFileServer(shared, token, guard(), allowUpload, local, 0, throttle).also { http = it }.start()

    private fun get(port: Int, path: String, auth: String? = null, method: String = "GET", headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.instanceFollowRedirects = false
        auth?.let { c.setRequestProperty("Authorization", it) }
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.readBytes()?.toString(Charsets.UTF_8) ?: ""
        return code to body
    }

    private fun basic(pw: String) = "Basic " + Base64.getEncoder().encodeToString("user:$pw".toByteArray())

    /** Sends the request line exactly as given (HttpURLConnection would normalise paths). */
    private fun raw(port: Int, requestLine: String, extra: String = ""): String {
        Socket("127.0.0.1", port).use { s ->
            s.getOutputStream().write("$requestLine\r\nHost: x\r\nAuthorization: ${basic(token)}\r\n$extra\r\n".toByteArray())
            return s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
        }
    }

    // ---- HTTP: authentication ----

    @Test fun noTokenIs401() {
        val port = startHttp()
        val (code, _) = get(port, "/")
        assertEquals(401, code)
        val c = URL("http://127.0.0.1:$port/hello.txt").openConnection() as HttpURLConnection
        assertEquals(401, c.responseCode)
        assertTrue(c.getHeaderField("WWW-Authenticate").startsWith("Basic"))
        assertEquals(401, get(port, "/hello.txt", method = "HEAD").first)
        assertEquals(401, get(port, "/sub/").first)
    }

    @Test fun wrongTokenIs401() {
        val port = startHttp(throttle = AuthThrottle(1000))
        assertEquals(401, get(port, "/", basic("wrong")).first)
        assertEquals(401, get(port, "/", basic(token + "x")).first)
        assertEquals(401, get(port, "/", basic("")).first)
        assertEquals(401, get(port, "/", "Bearer nope").first)
        assertEquals(401, get(port, "/?token=nope").first)
        assertEquals(401, get(port, "/", "Basic !!!notbase64").first)
    }

    @Test fun uploadWithoutTokenIs401AndWritesNothing() {
        val port = startHttp()
        val c = URL("http://127.0.0.1:$port/evil.txt").openConnection() as HttpURLConnection
        c.requestMethod = "PUT"; c.doOutput = true
        c.setFixedLengthStreamingMode(4)
        try { c.outputStream.write("data".toByteArray()) } catch (_: Exception) {}
        assertEquals(401, c.responseCode)
        assertFalse(File(shared, "evil.txt").exists())
    }

    @Test fun validTokenWorksByBasicBearerAndQuery() {
        val port = startHttp()
        val (code, body) = get(port, "/", basic(token))
        assertEquals(200, code)
        assertTrue(body.contains("hello.txt") && body.contains("sub/"))
        assertEquals(200, get(port, "/", "Bearer $token").first)
        assertEquals(200, get(port, "/?token=$token").first)
        assertEquals("hello world", get(port, "/hello.txt", basic(token)).second)
        assertEquals(200, get(port, "/sub/", basic(token)).first)
        assertEquals("inner", get(port, "/sub/inner.txt", basic(token)).second)
    }

    @Test fun repeatedWrongTokensGetThrottledEvenForTheRightOne() {
        val clock = longArrayOf(0)
        val port = startHttp(throttle = AuthThrottle(3, 60_000) { clock[0] })
        repeat(3) { assertEquals(401, get(port, "/", basic("bad$it")).first) }
        assertEquals(429, get(port, "/", basic(token)).first)
        clock[0] = 61_000
        assertEquals(200, get(port, "/", basic(token)).first)
    }

    @Test fun missingCredentialsDoNotCountTowardsTheLockout() {
        val port = startHttp(throttle = AuthThrottle(3, 60_000))
        repeat(10) { assertEquals(401, get(port, "/").first) }
        assertEquals(200, get(port, "/", basic(token)).first)
    }

    // ---- HTTP: path traversal ----

    @Test fun traversalIsRejected() {
        val port = startHttp()
        for (line in listOf(
            "GET /../outside-secret.txt HTTP/1.1",
            "GET /sub/../../outside-secret.txt HTTP/1.1",
            "GET /%2e%2e/outside-secret.txt HTTP/1.1",
            "GET /%2E%2E%2Foutside-secret.txt HTTP/1.1",
            "GET /sub/%2e%2e/%2e%2e/outside-secret.txt HTTP/1.1",
            "GET /..%5coutside-secret.txt HTTP/1.1",
            "GET /sub\\..\\..\\outside-secret.txt HTTP/1.1",
            "GET /hello.txt%00.png HTTP/1.1",
            "GET /../ HTTP/1.1",
        )) {
            val resp = raw(port, line)
            assertFalse("leaked for: $line", resp.contains("TOP SECRET"))
            assertTrue("expected 403/404 for: $line, got ${resp.lineSequence().first()}", resp.startsWith("HTTP/1.1 403") || resp.startsWith("HTTP/1.1 404") || resp.startsWith("HTTP/1.1 400"))
        }
        // Writing outside is rejected too, and nothing is created.
        val put = raw(port, "PUT /../planted.txt HTTP/1.1", "Content-Length: 3\r\n")
        assertTrue(put.startsWith("HTTP/1.1 403"))
        assertFalse(File(shared.parentFile, "planted.txt").exists())
    }

    @Test fun safePathUnit() {
        assertEquals(File(shared, "sub/inner.txt"), SafePath.resolve(shared, "/sub/inner.txt"))
        assertEquals(shared, SafePath.resolve(shared, "/"))
        assertEquals(null, SafePath.resolve(shared, "/../x"))
        assertEquals(null, SafePath.resolve(shared, "a/../../x"))
        assertEquals(null, SafePath.resolve(shared, "a\\b"))
        assertEquals(null, SafePath.resolve(shared, "a\u0000b"))
        assertEquals("/", SafePath.normalizeVirtual("/", "../../.."))
        assertEquals("/a/c", SafePath.normalizeVirtual("/a/b", "../c"))
        assertEquals("/x", SafePath.normalizeVirtual("/a", "/x"))
        // a sibling directory sharing the prefix of the share folder must not count as inside
        val sibling = File(shared.parentFile, "share-evil").also { it.mkdirs() }
        assertEquals(null, SafePath.resolve(shared, "/../share-evil/x"))
        assertTrue(sibling.exists())
    }

    @Test fun protectedVaultAndDotFilesAreNotServedOrListed() {
        val port = startHttp()
        assertEquals(403, get(port, "/Locked/p.txt", basic(token)).first)
        assertEquals(403, get(port, "/Locked/", basic(token)).first)
        assertEquals(403, get(port, "/.choifile_vault/v.txt", basic(token)).first)
        val listing = get(port, "/", basic(token)).second
        assertFalse(listing.contains("Locked")); assertFalse(listing.contains("vault")); assertFalse(listing.contains(".hidden"))
    }

    // ---- HTTP: downloads and uploads ----

    @Test fun rangeAndHeadAndMissing() {
        val port = startHttp()
        val (code, body) = get(port, "/hello.txt", basic(token), headers = mapOf("Range" to "bytes=6-10"))
        assertEquals(206, code); assertEquals("world", body)
        assertEquals(416, get(port, "/hello.txt", basic(token), headers = mapOf("Range" to "bytes=100-")).first)
        assertEquals(200, get(port, "/hello.txt", basic(token), method = "HEAD").first)
        assertEquals(404, get(port, "/nope.txt", basic(token)).first)
        assertEquals(405, get(port, "/hello.txt", basic(token), method = "DELETE").first)
        assertEquals(301, get(port, "/sub", basic(token)).first)
    }

    private fun put(port: Int, path: String, data: ByteArray, auth: String? = basic(token)): Int {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = "PUT"; c.doOutput = true; c.setFixedLengthStreamingMode(data.size)
        auth?.let { c.setRequestProperty("Authorization", it) }
        c.outputStream.use { it.write(data) }
        return c.responseCode
    }

    @Test fun uploadCreatesFilesAndNeverOverwrites() {
        val port = startHttp()
        assertEquals(201, put(port, "/sub/new%20file.txt", "abc".toByteArray()))
        assertEquals("abc", File(shared, "sub/new file.txt").readText())
        assertEquals(201, put(port, "/hello.txt", "replacement".toByteArray()))
        assertEquals("hello world", File(shared, "hello.txt").readText())          // original kept
        assertEquals("replacement", File(shared, "hello (1).txt").readText())      // new file got a free name
        assertEquals(201, put(port, "/newdir/", ByteArray(0)))
        assertTrue(File(shared, "newdir").isDirectory)
        assertEquals(403, put(port, "/.sneaky", "x".toByteArray()))
        assertEquals(403, put(port, "/Locked/x.txt", "x".toByteArray()))
        assertFalse(shared.walkTopDown().any { it.name.endsWith(".part") })
    }

    @Test fun uploadCanBeDisabled() {
        val port = startHttp(allowUpload = false)
        assertEquals(403, put(port, "/x.txt", "x".toByteArray()))
        assertFalse(File(shared, "x.txt").exists())
        assertEquals(200, get(port, "/hello.txt", basic(token)).first)
    }

    @Test fun truncatedUploadLeavesNoFile() {
        val port = startHttp()
        Socket("127.0.0.1", port).use { s ->
            s.getOutputStream().write("PUT /big.bin HTTP/1.1\r\nHost: x\r\nAuthorization: ${basic(token)}\r\nContent-Length: 1000\r\n\r\nonly a few bytes".toByteArray())
            s.shutdownOutput()
            s.getInputStream().readBytes()
        }
        Thread.sleep(100)
        assertFalse(File(shared, "big.bin").exists())
        assertFalse(shared.walkTopDown().any { it.name.endsWith(".part") })
    }

    @Test fun serverStopsAndPortIsReported() {
        val port = startHttp()
        assertTrue(port > 0)
        http!!.stop()
        assertTrue(runCatching { Socket("127.0.0.1", port).close() }.isFailure)
    }

    // ---- token ----

    @Test fun generatedTokensAreLongAndDifferent() {
        val a = ServerToken.generate(); val b = ServerToken.generate()
        assertEquals(24, a.length)
        assertNotEquals(a, b)
        assertTrue(ServerToken.matches(a, a)); assertFalse(ServerToken.matches(a, b)); assertFalse(ServerToken.matches(a, null))
    }

    // ---- FTP ----

    private fun startFtp(allowUpload: Boolean = true): Int =
        FtpFileServer(shared, token, guard(), allowUpload, local, 0).also { ftp = it }.start()

    private fun client(port: Int): FTPClient = FTPClient().apply {
        connectTimeout = 5000; defaultTimeout = 10000
        connect("127.0.0.1", port)
        enterLocalPassiveMode()
        setFileType(FTP.BINARY_FILE_TYPE)
    }

    @Test fun ftpRejectsMissingAndWrongPassword() {
        val port = startFtp()
        val c = client(port)
        assertFalse(c.login("anyone", "wrong"))
        assertEquals(530, c.replyCode)
        c.sendCommand("LIST"); assertEquals(530, c.replyCode) // not logged in
        c.sendCommand("RETR hello.txt"); assertEquals(530, c.replyCode)
        c.disconnect()
        // too many bad passwords end the session
        val d = client(port)
        repeat(3) { d.login("u", "bad$it") }
        assertTrue(runCatching { d.noop(); d.noop() }.isFailure)
        runCatching { d.disconnect() }
    }

    @Test fun ftpBrowsesDownloadsAndUploads() {
        val port = startFtp()
        val c = client(port)
        assertTrue(c.login("choifile", token))
        val names = c.listNames().toSet()
        assertEquals(setOf("hello.txt", "sub"), names)           // no dotfiles, vault, protected
        assertTrue(c.changeWorkingDirectory("sub"))
        assertEquals("/sub", c.printWorkingDirectory())
        assertEquals(setOf("inner.txt"), c.listNames().toSet())
        val out = java.io.ByteArrayOutputStream()
        assertTrue(c.retrieveFile("inner.txt", out)); assertEquals("inner", out.toString())
        assertEquals(1, c.listFiles().size)
        assertTrue(c.storeFile("up.txt", ByteArrayInputStream("uploaded".toByteArray())))
        assertEquals("uploaded", File(shared, "sub/up.txt").readText())
        assertFalse(c.storeFile("up.txt", ByteArrayInputStream("again".toByteArray())))   // no overwrite
        assertEquals("uploaded", File(shared, "sub/up.txt").readText())
        assertTrue(c.makeDirectory("made"))
        assertTrue(File(shared, "sub/made").isDirectory)
        assertFalse(c.deleteFile("up.txt")); assertEquals(502, c.replyCode)
        assertTrue(File(shared, "sub/up.txt").exists())
        assertFalse(c.removeDirectory("made")); assertEquals(502, c.replyCode)
        assertFalse(c.rename("up.txt", "z.txt"))
        assertFalse(c.storeFile(".hidden-up", ByteArrayInputStream(ByteArray(1))))
        assertFalse(shared.walkTopDown().any { it.name.endsWith(".part") })
        c.logout(); c.disconnect()
    }

    @Test fun ftpCannotEscapeTheSharedFolder() {
        val port = startFtp()
        val c = client(port)
        assertTrue(c.login("u", token))
        // ".." at the top stays at the top
        assertTrue(c.changeWorkingDirectory("../.."))
        assertEquals("/", c.printWorkingDirectory())
        assertFalse(c.retrieveFile("../outside-secret.txt", java.io.ByteArrayOutputStream()))
        assertFalse(c.retrieveFile("/../../outside-secret.txt", java.io.ByteArrayOutputStream()))
        assertFalse(c.retrieveFile("sub/../../outside-secret.txt", java.io.ByteArrayOutputStream()))
        assertFalse(c.changeWorkingDirectory("../share-evil"))
        assertFalse(c.storeFile("../planted.txt", ByteArrayInputStream("x".toByteArray())) && File(shared.parentFile, "planted.txt").exists())
        assertFalse(File(shared.parentFile, "planted.txt").exists())
        // protected and vault folders are invisible
        assertFalse(c.changeWorkingDirectory("Locked"))
        assertFalse(c.changeWorkingDirectory(".choifile_vault"))
        assertFalse(c.retrieveFile("Locked/p.txt", java.io.ByteArrayOutputStream()))
        c.disconnect()
    }

    @Test fun ftpUploadCanBeDisabled() {
        val port = startFtp(allowUpload = false)
        val c = client(port)
        assertTrue(c.login("u", token))
        assertFalse(c.storeFile("x.txt", ByteArrayInputStream("x".toByteArray())))
        assertFalse(c.makeDirectory("d"))
        assertFalse(File(shared, "x.txt").exists())
        c.disconnect()
    }
}
