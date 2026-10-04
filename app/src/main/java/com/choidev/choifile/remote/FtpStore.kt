package com.choidev.choifile.remote

import com.choidev.choifile.storage.FileItem
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * FTP has one control connection per client, so listing while a transfer runs would corrupt it.
 * Metadata calls share one locked client; every file stream gets its own short-lived connection.
 * Plain FTP sends the password unencrypted; the connection form warns about that and offers FTPS.
 */
class FtpStore(private val config: ConnectionConfig, private val timeoutMs: Int = 15_000) : RemoteStore {
    private val lock = Any()
    private var control: FTPClient? = null

    private fun newClient(): FTPClient {
        val c = if (config.useTls) FTPSClient("TLS", false) else FTPClient()
        c.connectTimeout = timeoutMs
        c.defaultTimeout = timeoutMs * 2
        c.controlEncoding = "UTF-8"
        try {
            c.connect(config.host, config.port)
            if (!FTPReply.isPositiveCompletion(c.replyCode)) throw IOException("FTP server refused the connection")
            if (c is FTPSClient) { c.execPBSZ(0); c.execPROT("P") }
            if (!c.login(config.username.ifEmpty { "anonymous" }, config.password)) throw IOException("FTP login failed")
            c.enterLocalPassiveMode()
            c.setFileType(FTP.BINARY_FILE_TYPE)
            c.soTimeout = timeoutMs * 2
        } catch (t: Throwable) {
            runCatching { if (c.isConnected) c.disconnect() }
            throw t
        }
        return c
    }

    /** Fails fast with a readable error when the server is unreachable or credentials are wrong. */
    fun connect(): FtpStore { synchronized(lock) { control = newClient() }; return this }

    private fun <T> withControl(block: (FTPClient) -> T): T = synchronized(lock) {
        var c = control
        if (c == null || !c.isConnected || !runCatching { c!!.sendNoOp() }.getOrDefault(false)) {
            runCatching { c?.disconnect() }
            c = newClient().also { control = it }
        }
        block(c)
    }

    override fun list(path: String): List<FileItem> = withControl { c ->
        val files: Array<FTPFile> = if (c.hasFeature("MLST")) c.mlistDir(path) else c.listFiles(path)
        files.filter { it.name != "." && it.name != ".." && (it.isDirectory || it.isFile) }.map {
            val name = it.name.substringAfterLast('/')
            FileItem(RemotePaths.join(path, name), name, it.isDirectory, if (it.isDirectory) 0L else it.size, it.timestamp?.timeInMillis ?: 0L, name.startsWith("."))
        }
    }

    override fun open(path: String): InputStream {
        val c = newClient()
        try {
            val s = c.retrieveFileStream(path) ?: throw IOException("FTP: cannot read $path (${c.replyString.trim()})")
            return object : FilterInputStream(s) {
                override fun close() {
                    try { super.close(); c.completePendingCommand() } finally { runCatching { c.disconnect() } }
                }
            }
        } catch (t: Throwable) {
            runCatching { c.disconnect() }
            throw t
        }
    }

    override fun create(path: String, sizeHint: Long): OutputStream {
        val c = newClient()
        try {
            val s = c.storeFileStream(path) ?: throw IOException("FTP: cannot write $path (${c.replyString.trim()})")
            return object : FilterOutputStream(s) {
                override fun write(b: ByteArray, off: Int, len: Int) = s.write(b, off, len)
                override fun close() {
                    try {
                        super.close()
                        if (!c.completePendingCommand()) throw IOException("FTP: upload rejected (${c.replyString.trim()})")
                    } finally { runCatching { c.disconnect() } }
                }
            }
        } catch (t: Throwable) {
            runCatching { c.disconnect() }
            throw t
        }
    }

    override fun mkdir(path: String) {
        withControl { c ->
            // Create each missing level; ignore "already exists" by checking the working directory afterwards.
            var cur = ""
            for (part in path.split('/').filter { it.isNotEmpty() }) {
                cur += "/$part"
                if (!c.changeWorkingDirectory(cur)) {
                    if (!c.makeDirectory(cur)) throw IOException("FTP: cannot create $cur (${c.replyString.trim()})")
                }
            }
            c.changeWorkingDirectory("/")
        }
    }

    override fun delete(path: String) {
        withControl { c ->
            if (c.changeWorkingDirectory(path)) {
                c.changeWorkingDirectory("/")
                for (child in list(path)) delete(child.path)
                if (!c.removeDirectory(path)) throw IOException("FTP: cannot remove $path (${c.replyString.trim()})")
            } else if (!c.deleteFile(path)) throw IOException("FTP: cannot delete $path (${c.replyString.trim()})")
        }
    }

    override fun rename(from: String, to: String) {
        withControl { c -> if (!c.rename(from, to)) throw IOException("FTP: rename failed (${c.replyString.trim()})") }
    }

    override fun close() {
        synchronized(lock) {
            runCatching { control?.logout() }
            runCatching { control?.disconnect() }
            control = null
        }
    }
}
