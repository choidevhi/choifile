package com.choidev.choifile.remote

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

object RemoteFactory {
    /** Opens a live store for [c]. SFTP needs [policy]; the others ignore it. Throws IOException on failure. */
    fun open(c: ConnectionConfig, policy: HostKeyPolicy): RemoteStore = when (c.protocol) {
        Protocol.SFTP -> SftpStore.connect(c, policy)
        Protocol.SMB -> SmbStore.connect(c)
        Protocol.FTP -> FtpStore(c).connect()
        Protocol.WEBDAV -> WebDavStore.fromConfig(c)
    }

    /** The folder a connection opens in. SMB and SFTP start at the root of the share / the login directory. */
    fun startPath(c: ConnectionConfig): String = when (c.protocol) {
        Protocol.WEBDAV -> "/"
        else -> RemotePaths.normalize(c.basePath)
    }

    /** Connect and list the start folder; returns an error text without secrets, or null on success. */
    fun test(c: ConnectionConfig, policy: HostKeyPolicy): String? = try {
        open(c, policy).use { it.list(startPath(c)) }
        null
    } catch (e: Exception) {
        describe(e)
    }

    /** Short, secret-free description of a failure. Passwords never appear in library exception messages. */
    fun describe(e: Throwable): String {
        val root = generateSequence(e) { it.cause }.last()
        val msg = (root.message ?: e.message ?: "").take(160)
        return if (msg.isEmpty()) e.javaClass.simpleName else "${root.javaClass.simpleName}: $msg"
    }
}

/**
 * Keeps one live connection per saved connection id and reconnects after failures.
 * [configOf] resolves an id to its (decrypted) config at connect time, so edits take effect on the next reconnect.
 */
class RemoteSessions(
    private val configOf: (String) -> ConnectionConfig?,
    private val policy: HostKeyPolicy,
    private val opener: (ConnectionConfig, HostKeyPolicy) -> RemoteStore = RemoteFactory::open,
) {
    private val stores = ConcurrentHashMap<String, RemoteStore>()

    @Synchronized fun get(id: String): RemoteStore {
        stores[id]?.let { return it }
        val c = configOf(id) ?: throw IOException("connection not found")
        return opener(c, policy).also { stores[id] = it }
    }

    @Synchronized fun invalidate(id: String) {
        stores.remove(id)?.let { runCatching { it.close() } }
    }

    /** Runs [block] and, if it fails with an I/O error, reconnects once and retries (stale idle connections). */
    fun <T> use(id: String, block: (RemoteStore) -> T): T {
        try {
            return block(get(id))
        } catch (e: TransferCancelledException) {
            throw e
        } catch (e: IOException) {
            invalidate(id)
            return block(get(id))
        }
    }

    @Synchronized fun closeAll() {
        stores.values.forEach { runCatching { it.close() } }
        stores.clear()
    }
}
