package com.choidev.choifile

import com.choidev.choifile.remote.CancelToken
import com.choidev.choifile.remote.ConnectionConfig
import com.choidev.choifile.remote.FtpStore
import com.choidev.choifile.remote.HostKeyDecision
import com.choidev.choifile.remote.HostKeyPolicy
import com.choidev.choifile.remote.HostKeyStore
import com.choidev.choifile.remote.LocalRemoteStore
import com.choidev.choifile.remote.Protocol
import com.choidev.choifile.remote.RemoteFactory
import com.choidev.choifile.remote.RemoteStore
import com.choidev.choifile.remote.SftpStore
import com.choidev.choifile.remote.TransferCancelledException
import com.choidev.choifile.remote.TransferEngine
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockftpserver.fake.FakeFtpServer
import org.mockftpserver.fake.UserAccount
import org.mockftpserver.fake.filesystem.DirectoryEntry
import org.mockftpserver.fake.filesystem.FileEntry
import org.mockftpserver.fake.filesystem.UnixFakeFileSystem
import java.io.File
import java.io.IOException

/** Real protocol round trips against in-process servers (Apache MINA SSHD for SFTP, MockFtpServer for FTP). */
class RemoteServersTest {
    @get:Rule val tmp = TemporaryFolder()
    private val closers = ArrayList<() -> Unit>()

    @After fun tearDown() { closers.asReversed().forEach { runCatching { it() } } }

    private fun p(f: File) = f.absolutePath.replace('\\', '/')

    private fun startSftp(root: File): Int {
        val sshd = SshServer.setUpDefaultServer()
        sshd.port = 0
        sshd.keyPairProvider = SimpleGeneratorHostKeyProvider(File(tmp.newFolder(), "hostkey.ser").toPath())
        sshd.setPasswordAuthenticator { u, pw, _ -> u == "tester" && pw == "s3cret" }
        sshd.subsystemFactories = listOf(SftpSubsystemFactory())
        sshd.fileSystemFactory = VirtualFileSystemFactory(root.toPath())
        sshd.start()
        closers += { sshd.stop(true) }
        return sshd.port
    }

    private fun sftpConfig(port: Int, password: String = "s3cret") =
        ConnectionConfig("sftp1", "test", Protocol.SFTP, "127.0.0.1", port, "tester", password)

    private class Prompts {
        val seen = ArrayList<Pair<String, String?>>() // fingerprint, previous
        var answer = HostKeyDecision.TRUST
    }

    private fun policy(prompts: Prompts, hostKeys: File) =
        HostKeyPolicy(HostKeyStore(hostKeys)) { _, _, fp, prev -> prompts.seen += fp to prev; prompts.answer }

    // ---- SFTP ----

    @Test fun sftpFullRoundTripWithProgressMoveAndDelete() {
        val serverRoot = tmp.newFolder("sftp-root")
        File(serverRoot, "existing.txt").writeText("remote text")
        val port = startSftp(serverRoot)
        val prompts = Prompts()
        val store: RemoteStore = SftpStore.connect(sftpConfig(port), policy(prompts, File(tmp.root, "hk.json")))
        closers += { store.close() }
        assertEquals(1, prompts.seen.size)
        assertNull("first contact has no previous fingerprint", prompts.seen[0].second)
        assertTrue(prompts.seen[0].first.startsWith("SHA256:"))

        assertEquals(listOf("existing.txt"), store.list("/").map { it.name })
        assertEquals("remote text", store.open("/existing.txt").use { it.readBytes().toString(Charsets.UTF_8) })

        // upload a folder tree with a multi-MB file, streaming, with progress
        val local = LocalRemoteStore()
        val src = tmp.newFolder("up"); File(src, "tree/sub").mkdirs()
        val big = ByteArray(3 * 1024 * 1024 + 17) { (it * 31).toByte() }
        File(src, "tree/sub/big.bin").writeBytes(big); File(src, "tree/small.txt").writeText("hi")
        var last = 0L
        TransferEngine.transfer(local, p(File(src, "tree")), store, "/", move = false) { last = it.bytesDone }
        assertEquals(big.size + 2L, last)
        assertTrue(File(serverRoot, "tree/sub/big.bin").readBytes().contentEquals(big))

        // download it again, and name collisions get a suffix
        val down = tmp.newFolder("down")
        TransferEngine.transfer(store, "/tree", local, p(down), move = false)
        assertTrue(File(down, "tree/sub/big.bin").readBytes().contentEquals(big))
        TransferEngine.transfer(store, "/tree", store, "/", move = false)
        assertTrue(File(serverRoot, "tree (1)/small.txt").exists())

        // move within the same connection is a rename; folder creation and deletion
        store.mkdir("/a/b/c")
        assertTrue(File(serverRoot, "a/b/c").isDirectory)
        TransferEngine.transfer(store, "/tree (1)", store, "/a", move = true)
        assertTrue(File(serverRoot, "a/tree (1)/small.txt").exists()); assertFalse(File(serverRoot, "tree (1)").exists())
        store.rename("/existing.txt", "/renamed.txt")
        assertTrue(File(serverRoot, "renamed.txt").exists())
        store.delete("/a")
        assertFalse(File(serverRoot, "a").exists())
        store.delete("/renamed.txt")
        assertFalse(File(serverRoot, "renamed.txt").exists())
    }

    @Test fun sftpCancelDuringUploadRemovesPartialFile() {
        val serverRoot = tmp.newFolder("sftp-root2")
        val port = startSftp(serverRoot)
        val store = SftpStore.connect(sftpConfig(port), policy(Prompts(), File(tmp.root, "hk.json")))
        closers += { store.close() }
        val src = tmp.newFolder("cancel-src"); File(src, "big.bin").writeBytes(ByteArray(12 * 1024 * 1024))
        val cancel = CancelToken()
        try {
            TransferEngine.transfer(LocalRemoteStore(), p(File(src, "big.bin")), store, "/", move = true, cancel = cancel) { cancel.cancel() }
            fail()
        } catch (_: TransferCancelledException) {}
        assertFalse(File(serverRoot, "big.bin").exists())
        assertTrue(File(src, "big.bin").exists())
    }

    @Test fun sftpHostKeyIsRememberedAndMismatchWarnsAndBlocks() {
        val serverRoot = tmp.newFolder("sftp-root3")
        val port = startSftp(serverRoot)
        val hostKeys = File(tmp.root, "hk.json")
        val prompts = Prompts()
        SftpStore.connect(sftpConfig(port), policy(prompts, hostKeys)).close()
        assertEquals(1, prompts.seen.size)
        SftpStore.connect(sftpConfig(port), policy(prompts, hostKeys)).close()
        assertEquals("known key: no second prompt", 1, prompts.seen.size)

        // pretend the server's key changed: tamper with what we stored
        HostKeyStore(hostKeys).put("127.0.0.1", port, "SHA256:definitelyNotTheRealKey")
        prompts.answer = HostKeyDecision.REJECT
        try { SftpStore.connect(sftpConfig(port), policy(prompts, hostKeys)); fail() } catch (_: IOException) {}
        assertEquals("SHA256:definitelyNotTheRealKey", prompts.seen.last().second)
        assertEquals("rejecting keeps the old fingerprint", "SHA256:definitelyNotTheRealKey", HostKeyStore(hostKeys).get("127.0.0.1", port))

        prompts.answer = HostKeyDecision.TRUST
        SftpStore.connect(sftpConfig(port), policy(prompts, hostKeys)).close()
        assertTrue(HostKeyStore(hostKeys).get("127.0.0.1", port)!!.startsWith("SHA256:") && HostKeyStore(hostKeys).get("127.0.0.1", port) != "SHA256:definitelyNotTheRealKey")
    }

    @Test fun sftpWrongPasswordFailsWithoutEchoingIt() {
        val port = startSftp(tmp.newFolder("sftp-root4"))
        val err = RemoteFactory.test(sftpConfig(port, password = "WRONG-PASSWORD-123"), policy(Prompts(), File(tmp.root, "hk.json")))
        assertNotNull(err)
        assertFalse(err!!.contains("WRONG-PASSWORD-123"))
    }

    @Test fun connectionTestReportsSuccessAsNull() {
        val port = startSftp(tmp.newFolder("sftp-root5"))
        assertNull(RemoteFactory.test(sftpConfig(port), policy(Prompts(), File(tmp.root, "hk.json"))))
    }

    // ---- FTP ----

    private fun startFtp(): Pair<FakeFtpServer, Int> {
        val fs = UnixFakeFileSystem()
        fs.add(DirectoryEntry("/data"))
        fs.add(FileEntry("/data/hello.txt", "hello ftp"))
        val server = FakeFtpServer()
        server.serverControlPort = 0
        server.addUserAccount(UserAccount("ftpuser", "ftppass", "/data").apply { isPasswordCheckedDuringValidation = true })
        server.fileSystem = fs
        server.start()
        closers += { server.stop() }
        return server to server.serverControlPort
    }

    private fun ftpConfig(port: Int, password: String = "ftppass") =
        ConnectionConfig("ftp1", "ftp", Protocol.FTP, "127.0.0.1", port, "ftpuser", password, useTls = false)

    @Test fun ftpListReadWriteMkdirRenameDelete() {
        val (_, port) = startFtp()
        val store = FtpStore(ftpConfig(port)).connect()
        closers += { store.close() }
        assertEquals(listOf("hello.txt"), store.list("/data").map { it.name })
        assertEquals(9L, store.list("/data")[0].size)
        assertEquals("hello ftp", store.open("/data/hello.txt").use { it.readBytes().toString(Charsets.UTF_8) })

        store.create("/data/new.txt", 3).use { it.write("abc".toByteArray()) }
        assertEquals("abc", store.open("/data/new.txt").use { it.readBytes().toString(Charsets.UTF_8) })
        store.mkdir("/data/x/y")
        assertTrue(store.list("/data").any { it.name == "x" && it.isDirectory })
        store.rename("/data/new.txt", "/data/renamed.txt")
        assertEquals(setOf("hello.txt", "renamed.txt", "x"), store.list("/data").map { it.name }.toSet())
        store.delete("/data/x")
        store.delete("/data/renamed.txt")
        assertEquals(listOf("hello.txt"), store.list("/data").map { it.name })
    }

    @Test fun ftpTransferEngineUploadAndDownloadFolder() {
        val (_, port) = startFtp()
        val store = FtpStore(ftpConfig(port)).connect()
        closers += { store.close() }
        val local = LocalRemoteStore()
        val src = tmp.newFolder("ftp-src"); File(src, "pack/inner").mkdirs()
        val data = ByteArray(1_500_000) { it.toByte() }
        File(src, "pack/inner/data.bin").writeBytes(data); File(src, "pack/t.txt").writeText("t")
        TransferEngine.transfer(local, p(File(src, "pack")), store, "/data", move = false)
        assertEquals(setOf("hello.txt", "pack"), store.list("/data").map { it.name }.toSet())
        val down = tmp.newFolder("ftp-down")
        TransferEngine.transfer(store, "/data/pack", local, p(down), move = true)
        assertTrue(File(down, "pack/inner/data.bin").readBytes().contentEquals(data))
        assertEquals("move removed the remote copy", listOf("hello.txt"), store.list("/data").map { it.name })
    }

    @Test fun ftpWrongPasswordFailsWithoutEchoingIt() {
        val (_, port) = startFtp()
        val err = RemoteFactory.test(ftpConfig(port, "BAD-FTP-PASS-9"), policy(Prompts(), File(tmp.root, "hk.json")))
        assertNotNull(err)
        assertFalse(err!!.contains("BAD-FTP-PASS-9"))
        assertNull(RemoteFactory.test(ftpConfig(port), policy(Prompts(), File(tmp.root, "hk.json"))))
    }
}
