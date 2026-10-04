package com.choidev.choifile

import com.choidev.choifile.remote.AesGcmBox
import com.choidev.choifile.remote.CancelToken
import com.choidev.choifile.remote.ConnectionConfig
import com.choidev.choifile.remote.ConnectionRepo
import com.choidev.choifile.remote.HostKeyDecision
import com.choidev.choifile.remote.HostKeyPolicy
import com.choidev.choifile.remote.HostKeyStore
import com.choidev.choifile.remote.LocalRemoteStore
import com.choidev.choifile.remote.Protocol
import com.choidev.choifile.remote.RemoteLocation
import com.choidev.choifile.remote.RemotePaths
import com.choidev.choifile.remote.RemoteSessions
import com.choidev.choifile.remote.Tailscale
import com.choidev.choifile.remote.TransferCancelledException
import com.choidev.choifile.remote.TransferEngine
import com.choidev.choifile.remote.VaultContents
import com.choidev.choifile.remote.WebDavStore
import com.choidev.choifile.remote.sshFingerprint
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class RemoteTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun key(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun cfg(id: String = "c1", password: String = "hunter2-secret") =
        ConnectionConfig(id, "NAS", Protocol.SFTP, "nas.example.com", 22, "me", password, "-----BEGIN KEY-----SECRETKEY", "keypass-secret", basePath = "/home/me")

    // ---- encrypted connection store ----

    @Test fun repoRoundTripAndNoPlaintextOnDisk() {
        val file = File(tmp.root, "connections.enc")
        val repo = ConnectionRepo(file, AesGcmBox(key().let { k -> { k } }))
        repo.upsert(cfg("a"))
        repo.upsert(cfg("b", "other-password"))
        repo.setTailscaleApiKey("tskey-api-SECRETVALUE")
        val loaded = repo.load()
        assertEquals(listOf("a", "b"), loaded.connections.map { it.id })
        assertEquals(cfg("a"), loaded.connections[0])
        assertEquals("tskey-api-SECRETVALUE", loaded.tailscaleApiKey)
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        listOf("hunter2-secret", "other-password", "SECRETKEY", "keypass-secret", "tskey-api", "nas.example.com", "connections").forEach {
            assertFalse("plaintext leaked: $it", raw.contains(it))
        }
    }

    @Test fun repoUpsertReplacesAndDeleteRemoves() {
        val repo = ConnectionRepo(File(tmp.root, "c.enc"), AesGcmBox(key().let { k -> { k } }))
        repo.upsert(cfg("a")); repo.upsert(cfg("a").copy(name = "renamed")); repo.upsert(cfg("b"))
        assertEquals(listOf("renamed", "NAS"), repo.load().connections.map { it.name })
        repo.delete("a")
        assertEquals(listOf("b"), repo.load().connections.map { it.id })
    }

    @Test fun tamperedOrWrongKeyFailsInsteadOfReturningEmpty() {
        val file = File(tmp.root, "c.enc")
        val k = key()
        ConnectionRepo(file, AesGcmBox { k }).upsert(cfg())
        val bytes = file.readBytes()
        bytes[bytes.size - 3] = (bytes[bytes.size - 3].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        try { ConnectionRepo(file, AesGcmBox { k }).load(); fail() } catch (_: GeneralSecurityException) {}
        file.writeBytes(AesGcmBox { k }.seal("{}".toByteArray()))
        val other = key()
        try { ConnectionRepo(file, AesGcmBox { other }).load(); fail() } catch (_: GeneralSecurityException) {}
    }

    @Test fun missingFileIsEmptyAndIvIsFreshEachTime() {
        val box = AesGcmBox(key().let { k -> { k } })
        assertEquals(VaultContents(), ConnectionRepo(File(tmp.root, "none.enc"), box).load())
        val a = box.seal("same".toByteArray()); val b = box.seal("same".toByteArray())
        assertFalse(a.contentEquals(b))
        assertArrayEquals("same".toByteArray(), box.open(a))
    }

    @Test fun toStringNeverContainsSecrets() {
        val s = cfg().toString()
        assertFalse(s.contains("hunter2")); assertFalse(s.contains("SECRETKEY")); assertFalse(s.contains("keypass"))
    }

    // ---- host key policy ----

    @Test fun trustOnFirstUseStoresAndLaterAcceptsSilently() {
        val store = HostKeyStore(File(tmp.root, "hk.json"))
        val asked = ArrayList<Triple<String, String, String?>>()
        val policy = HostKeyPolicy(store) { h, _, fp, prev -> asked += Triple(h, fp, prev); HostKeyDecision.TRUST }
        assertTrue(policy.verify("Host", 22, "SHA256:aaa"))
        assertEquals(listOf(Triple("Host", "SHA256:aaa", null)), asked)
        assertTrue(policy.verify("host", 22, "SHA256:aaa")) // case-insensitive host, no new prompt
        assertEquals(1, asked.size)
        assertEquals("SHA256:aaa", store.get("host", 22))
    }

    @Test fun rejectedFirstContactStoresNothing() {
        val store = HostKeyStore(File(tmp.root, "hk.json"))
        val policy = HostKeyPolicy(store) { _, _, _, _ -> HostKeyDecision.REJECT }
        assertFalse(policy.verify("h", 22, "SHA256:aaa"))
        assertNull(store.get("h", 22))
    }

    @Test fun changedKeyIsFlaggedAndOnlyReplacedOnExplicitTrust() {
        val store = HostKeyStore(File(tmp.root, "hk.json"))
        store.put("h", 22, "SHA256:old")
        var previousSeen: String? = null
        var answer = HostKeyDecision.REJECT
        val policy = HostKeyPolicy(store) { _, _, _, prev -> previousSeen = prev; answer }
        assertFalse(policy.verify("h", 22, "SHA256:new"))
        assertEquals("SHA256:old", previousSeen)
        assertEquals("SHA256:old", store.get("h", 22))
        answer = HostKeyDecision.TRUST
        assertTrue(policy.verify("h", 22, "SHA256:new"))
        assertEquals("SHA256:new", store.get("h", 22))
    }

    @Test fun fingerprintFormatIsOpenSshStyle() {
        val fp = sshFingerprint(ByteArray(10) { it.toByte() })
        assertTrue(fp.startsWith("SHA256:")); assertFalse(fp.endsWith("="))
        assertEquals(7 + 43, fp.length)
    }

    // ---- paths ----

    @Test fun remotePaths() {
        assertEquals("/a/b", RemotePaths.join("/a", "b")); assertEquals("/b", RemotePaths.join("/", "b"))
        assertEquals("/a", RemotePaths.parent("/a/b")); assertEquals("/", RemotePaths.parent("/a")); assertEquals("/", RemotePaths.parent("/"))
        assertEquals("b", RemotePaths.name("/a/b/")); assertEquals("/a/b", RemotePaths.normalize("a//b/./"))
        assertEquals("x (1).txt", RemotePaths.uniqueName("x.txt", setOf("x.txt")))
        assertEquals("x (2).txt", RemotePaths.uniqueName("x.txt", setOf("x.txt", "x (1).txt")))
        assertEquals("noext (1)", RemotePaths.uniqueName("noext", setOf("noext")))
        val loc = RemoteLocation.make("id-1", "/dir/a b")
        assertTrue(RemoteLocation.isRemote(loc)); assertEquals("id-1", RemoteLocation.connectionOf(loc)); assertEquals("/dir/a b", RemoteLocation.pathOf(loc))
        assertEquals("/", RemoteLocation.pathOf("remote:id-1:"))
        assertEquals("/a:b", RemoteLocation.pathOf(RemoteLocation.make("id", "/a:b")))
    }

    // ---- tailscale ----

    @Test fun tailscaleHostClassification() {
        assertTrue(Tailscale.isTailnetIpv4("100.64.0.1")); assertTrue(Tailscale.isTailnetIpv4("100.127.255.254"))
        assertFalse(Tailscale.isTailnetIpv4("100.128.0.1")); assertFalse(Tailscale.isTailnetIpv4("100.63.0.1"))
        assertFalse(Tailscale.isTailnetIpv4("192.168.0.1")); assertFalse(Tailscale.isTailnetIpv4("100.64.0"))
        assertEquals(Tailscale.HostKind.MAGIC_DNS, Tailscale.classify("pc.tail1234.ts.net."))
        assertEquals(Tailscale.HostKind.OTHER, Tailscale.classify("mypc"))
        assertEquals(Tailscale.HostKind.MAGIC_DNS, Tailscale.classify("mypc", presetShortNames = true))
        assertEquals(Tailscale.HostKind.TAILNET_IP, Tailscale.classify("100.100.1.1"))
        assertEquals(Tailscale.HostKind.OTHER, Tailscale.classify("example.com", presetShortNames = true))
    }

    @Test fun tailscaleDeviceListThroughApiUsesKeyAsBasicAuth() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"devices":[{"name":"pc.tail1.ts.net","hostname":"pc","addresses":["100.101.102.103","fd7a::1"],"os":"windows"},{"name":"","hostname":"phone","addresses":["100.99.1.2"],"os":"android"}]}"""))
            server.start()
            val devices = Tailscale.listDevices("tskey-api-XYZ", server.url("/").toString().trimEnd('/'))
            assertEquals(listOf("pc.tail1.ts.net", "100.99.1.2"), devices.map { it.target })
            assertEquals("100.101.102.103", devices[0].ipv4)
            val req = server.takeRequest()
            assertEquals("/api/v2/tailnet/-/devices", req.path)
            val auth = req.getHeader("Authorization")!!
            assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString("tskey-api-XYZ:".toByteArray()), auth)
        }
    }

    @Test fun tailscaleApiRejectedKeyIsReportedWithoutLeakingIt() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401)); server.start()
            try { Tailscale.listDevices("tskey-api-LEAKME", server.url("/").toString().trimEnd('/')); fail() } catch (e: java.io.IOException) {
                assertFalse(e.message!!.contains("LEAKME"))
            }
        }
    }

    @Test fun reachabilityAgainstLocalSocket() {
        java.net.ServerSocket(0).use { s ->
            assertTrue(Tailscale.checkReachable("127.0.0.1", s.localPort, 2000) is Tailscale.Reach.Ok)
        }
        val closed = java.net.ServerSocket(0).use { it.localPort }
        assertTrue(Tailscale.checkReachable("127.0.0.1", closed, 2000) is Tailscale.Reach.Failed)
    }

    // ---- sessions ----

    @Test fun sessionReconnectsOnceAfterIoFailureAndCachesOtherwise() {
        var opened = 0
        val policy = HostKeyPolicy(HostKeyStore(File(tmp.root, "hk.json"))) { _, _, _, _ -> HostKeyDecision.TRUST }
        val sessions = RemoteSessions({ cfg(it) }, policy, { _, _ -> opened++; LocalRemoteStore() })
        val a = sessions.get("x"); assertTrue(a === sessions.get("x")); assertEquals(1, opened)
        var calls = 0
        val r = sessions.use("x") { calls++; if (calls == 1) throw java.io.IOException("stale") else "ok" }
        assertEquals("ok", r); assertEquals(2, opened)
        try { sessions.use("x") { throw TransferCancelledException() }; fail() } catch (_: TransferCancelledException) {}
        assertEquals(2, opened)
        assertNotNull(sessions.get("x"))
    }

    // ---- transfer engine (local on both sides; server round trips are in RemoteServersTest) ----

    private fun p(f: File) = f.absolutePath.replace('\\', '/')

    @Test fun engineCopiesFolderTreeAndAvoidsOverwrite() {
        val src = tmp.newFolder("src"); val dst = tmp.newFolder("dst")
        File(src, "a/b").mkdirs(); File(src, "a/b/x.txt").writeText("xx"); File(src, "a/y.txt").writeText("y")
        val store = LocalRemoteStore()
        val prog = ArrayList<Int>()
        val out = TransferEngine.transfer(store, p(File(src, "a")), store, p(dst), move = false) { prog += it.filesDone }
        assertEquals(p(File(dst, "a")), out)
        assertEquals("xx", File(dst, "a/b/x.txt").readText()); assertEquals("y", File(dst, "a/y.txt").readText())
        assertTrue(File(src, "a/y.txt").exists())
        val again = TransferEngine.transfer(store, p(File(src, "a")), store, p(dst), move = false)
        assertEquals(p(File(dst, "a (1)")), again)
        assertEquals(2, prog.last())
    }

    @Test fun engineMoveDeletesSourceOnlyAfterSuccess() {
        val src = tmp.newFolder("s2"); val dst = tmp.newFolder("d2")
        File(src, "f.txt").writeText("data")
        val store = LocalRemoteStore()
        TransferEngine.transfer(store, p(File(src, "f.txt")), LocalRemoteStore(), p(dst), move = true)
        assertFalse(File(src, "f.txt").exists()); assertEquals("data", File(dst, "f.txt").readText())
    }

    @Test fun engineCancelRemovesPartialFileAndKeepsSource() {
        val src = tmp.newFolder("s3"); val dst = tmp.newFolder("d3")
        File(src, "big.bin").writeBytes(ByteArray(8 * 1024 * 1024))
        val store = LocalRemoteStore()
        val cancel = CancelToken()
        try {
            TransferEngine.transfer(store, p(File(src, "big.bin")), LocalRemoteStore(), p(dst), move = true, cancel = cancel) { cancel.cancel() }
            fail("expected cancel")
        } catch (_: TransferCancelledException) {}
        assertFalse("partial file removed", File(dst, "big.bin").exists())
        assertTrue("source untouched", File(src, "big.bin").exists())
    }

    @Test fun engineRefusesCopyIntoItself() {
        val src = tmp.newFolder("s4"); File(src, "sub").mkdirs()
        val store = LocalRemoteStore()
        try { TransferEngine.transfer(store, p(src), store, p(File(src, "sub")), move = false); fail() } catch (_: IllegalArgumentException) {}
    }

    // ---- webdav against a scripted server ----

    private val multistatus = """<?xml version="1.0"?>
        <d:multistatus xmlns:d="DAV:">
          <d:response><d:href>/dav/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
          <d:response><d:href>/dav/My%20Docs/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype><d:getlastmodified>Tue, 15 Nov 1994 12:45:26 GMT</d:getlastmodified></d:prop></d:propstat></d:response>
          <d:response><d:href>/dav/a%20b+c.txt</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>42</d:getcontentlength><d:getlastmodified>Tue, 15 Nov 1994 12:45:26 GMT</d:getlastmodified></d:prop></d:propstat></d:response>
        </d:multistatus>"""

    @Test fun webDavListGetPutMkcolMoveDelete() {
        MockWebServer().use { server ->
            server.start()
            val store = WebDavStore("http://${server.hostName}:${server.port}/dav".toHttpUrl(), "user", "pw")
            server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus))
            val items = store.list("/")
            val req = server.takeRequest()
            assertEquals("PROPFIND", req.method); assertEquals("/dav/", req.path); assertEquals("1", req.getHeader("Depth"))
            assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString("user:pw".toByteArray()), req.getHeader("Authorization"))
            assertEquals(listOf("My Docs", "a b+c.txt"), items.map { it.name })
            assertTrue(items[0].isDirectory); assertEquals(42L, items[1].size); assertEquals("/a b+c.txt", items[1].path)
            assertTrue(items[1].modified > 0)

            server.enqueue(MockResponse().setBody("file-body"))
            assertEquals("file-body", store.open("/a b+c.txt").use { it.readBytes().toString(Charsets.UTF_8) })
            assertEquals("/dav/a%20b+c.txt", server.takeRequest().path)

            server.enqueue(MockResponse().setResponseCode(201))
            store.create("/up.bin", 5).use { it.write(byteArrayOf(1, 2, 3, 4, 5)) }
            val put = server.takeRequest()
            assertEquals("PUT", put.method); assertEquals("/dav/up.bin", put.path)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), put.body.readByteArray())

            server.enqueue(MockResponse().setResponseCode(201)); server.enqueue(MockResponse().setResponseCode(405))
            store.mkdir("/n/m")
            assertEquals("/dav/n/", server.takeRequest().path.also { }); assertEquals("/dav/n/m/", server.takeRequest().path)

            server.enqueue(MockResponse().setResponseCode(201))
            store.rename("/a.txt", "/b.txt")
            val mv = server.takeRequest()
            assertEquals("MOVE", mv.method)
            assertTrue(mv.getHeader("Destination")!!.endsWith("/dav/b.txt")); assertEquals("F", mv.getHeader("Overwrite"))

            server.enqueue(MockResponse().setResponseCode(204))
            store.delete("/gone")
            assertEquals("DELETE", server.takeRequest().method)
        }
    }

    @Test fun webDavErrorsSurfaceAsIoExceptionAndFailedUploadReports() {
        MockWebServer().use { server ->
            server.start()
            val store = WebDavStore("http://${server.hostName}:${server.port}".toHttpUrl(), "", "")
            server.enqueue(MockResponse().setResponseCode(404))
            try { store.open("/nope"); fail() } catch (e: java.io.IOException) { assertTrue(e.message!!.contains("404")) }
            server.enqueue(MockResponse().setResponseCode(507))
            try { store.create("/big", 3).use { it.write(byteArrayOf(1, 2, 3)) }; fail() } catch (e: java.io.IOException) { assertTrue(e.message!!.contains("507")) }
            assertNull(server.takeRequest().getHeader("Authorization")) // anonymous when no username
        }
    }

    @Test fun webDavRefusesDoctypeInResponse() {
        val evil = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><d:multistatus xmlns:d="DAV:"><d:response><d:href>&e;</d:href></d:response></d:multistatus>"""
        try { WebDavStore.parseMultistatus(evil.byteInputStream(), "", "/"); fail() } catch (_: Exception) {}
    }
}
