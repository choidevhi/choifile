package com.choidev.choifile.remote

import com.choidev.choifile.storage.FileItem
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.PublicKey
import java.security.Security
import java.util.EnumSet

class SftpStore private constructor(private val ssh: SSHClient, private val sftp: SFTPClient) : RemoteStore {

    override fun list(path: String): List<FileItem> =
        sftp.ls(path).filter { it.name != "." && it.name != ".." }.map {
            FileItem(RemotePaths.join(path, it.name), it.name, it.isDirectory, if (it.isDirectory) 0L else it.attributes.size, it.attributes.mtime * 1000L, it.name.startsWith("."))
        }

    override fun open(path: String): InputStream {
        val rf = sftp.open(path)
        val input = try { rf.ReadAheadRemoteFileInputStream(16) } catch (e: Throwable) { rf.close(); throw e }
        return object : FilterInputStream(input) {
            override fun close() { try { super.close() } finally { rf.close() } }
        }
    }

    override fun create(path: String, sizeHint: Long): OutputStream {
        val rf = sftp.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC))
        val out = try { rf.RemoteFileOutputStream(0, 16) } catch (e: Throwable) { rf.close(); throw e }
        return object : FilterOutputStream(out) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() { try { super.close() } finally { rf.close() } }
        }
    }

    override fun mkdir(path: String) = sftp.mkdirs(path)

    override fun delete(path: String) {
        val attrs = sftp.lstat(path)
        if (attrs.type == net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY) {
            for (c in list(path)) delete(c.path)
            sftp.rmdir(path)
        } else sftp.rm(path)
    }

    override fun rename(from: String, to: String) = sftp.rename(from, to)

    override fun close() {
        runCatching { sftp.close() }
        runCatching { ssh.disconnect() }
    }

    companion object {
        /**
         * Android ships a cut-down "BC" provider that breaks sshj's key exchange; swap in the full library.
         * Harmless on a plain JVM.
         */
        private fun ensureBouncyCastle() {
            val current = Security.getProvider("BC")
            if (current == null || current.javaClass.name != BouncyCastleProvider::class.java.name) {
                Security.removeProvider("BC")
                Security.insertProviderAt(BouncyCastleProvider(), 1)
            }
        }

        fun connect(c: ConnectionConfig, policy: HostKeyPolicy, timeoutMs: Int = 15_000): SftpStore {
            ensureBouncyCastle()
            val ssh = SSHClient(DefaultConfig())
            try {
                ssh.connectTimeout = timeoutMs
                ssh.timeout = timeoutMs * 2
                ssh.addHostKeyVerifier(object : HostKeyVerifier {
                    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                        val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
                        return policy.verify(c.host, c.port, sshFingerprint(blob))
                    }

                    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
                })
                ssh.connect(c.host, c.port)
                if (c.privateKey.isNotBlank()) {
                    val finder = if (c.keyPassphrase.isEmpty()) null else PasswordUtils.createOneOff(c.keyPassphrase.toCharArray())
                    val keys = ssh.loadKeys(c.privateKey, null, finder)
                    ssh.authPublickey(c.username, keys)
                } else {
                    ssh.authPassword(c.username, c.password)
                }
                ssh.connection.keepAlive.keepAliveInterval = 15
                return SftpStore(ssh, ssh.newSFTPClient())
            } catch (t: Throwable) {
                runCatching { ssh.disconnect() }
                throw if (t is IOException) t else IOException(t.message ?: t.javaClass.simpleName, t)
            }
        }
    }
}
