package com.choidev.choifile.remote

import com.choidev.choifile.storage.FileItem
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * SMB2/3 share through smbj. Paths inside the share use "/" in the app and are converted to the backslash form
 * SMB expects. SMB1 is not supported. Not exercised against a real server by the automated tests.
 */
class SmbStore private constructor(
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
    private val share: DiskShare,
) : RemoteStore {

    private fun p(path: String) = RemotePaths.normalize(path).trim('/').replace('/', '\\')

    override fun list(path: String): List<FileItem> =
        share.list(p(path)).filter { it.fileName != "." && it.fileName != ".." }.map {
            val dir = (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
            FileItem(RemotePaths.join(path, it.fileName), it.fileName, dir, if (dir) 0L else it.endOfFile, it.lastWriteTime.toEpochMillis(), it.fileName.startsWith("."))
        }

    override fun open(path: String): InputStream {
        val f = share.openFile(p(path), EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
        val s = try { f.inputStream } catch (t: Throwable) { f.close(); throw t }
        return object : FilterInputStream(s) { override fun close() { try { super.close() } finally { f.close() } } }
    }

    override fun create(path: String, sizeHint: Long): OutputStream {
        val f = share.openFile(
            p(path), EnumSet.of(AccessMask.GENERIC_WRITE), EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null,
        )
        val s = try { f.outputStream } catch (t: Throwable) { f.close(); throw t }
        return object : FilterOutputStream(s) {
            override fun write(b: ByteArray, off: Int, len: Int) = s.write(b, off, len)
            override fun close() { try { super.close() } finally { f.close() } }
        }
    }

    override fun mkdir(path: String) {
        var cur = ""
        for (part in p(path).split('\\').filter { it.isNotEmpty() }) {
            cur = if (cur.isEmpty()) part else "$cur\\$part"
            if (!share.folderExists(cur)) share.mkdir(cur)
        }
    }

    override fun delete(path: String) {
        val sp = p(path)
        if (share.folderExists(sp)) share.rmdir(sp, true) else share.rm(sp)
    }

    override fun rename(from: String, to: String) {
        val entry = share.open(
            p(from), EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
        )
        entry.use { it.rename(p(to)) }
    }

    override fun close() {
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }

    companion object {
        fun connect(c: ConnectionConfig, timeoutSeconds: Long = 15): SmbStore {
            require(c.share.isNotBlank()) { "SMB share name is empty" }
            val client = SMBClient(SmbConfig.builder().withTimeout(timeoutSeconds, TimeUnit.SECONDS).withSoTimeout(timeoutSeconds * 2, TimeUnit.SECONDS).build())
            try {
                val conn = client.connect(c.host, c.port)
                // "DOMAIN\user" and "user@domain" are both accepted by the SMB stack; keep the user text as typed.
                val domain = if ('\\' in c.username) c.username.substringBefore('\\') else null
                val user = c.username.substringAfter('\\')
                val auth = if (c.username.isEmpty()) AuthenticationContext.guest()
                else AuthenticationContext(user, c.password.toCharArray(), domain)
                val session = conn.authenticate(auth)
                val share = session.connectShare(c.share) as? DiskShare ?: throw IOException("${c.share} is not a file share")
                return SmbStore(client, conn, session, share)
            } catch (t: Throwable) {
                runCatching { client.close() }
                throw if (t is IOException) t else IOException(t.message ?: t.javaClass.simpleName, t)
            }
        }
    }
}
