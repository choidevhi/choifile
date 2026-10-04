package com.choidev.choifile.remote

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class Protocol(val label: String, val defaultPort: Int) {
    SFTP("SFTP", 22), SMB("SMB", 445), FTP("FTP", 21), WEBDAV("WebDAV", 443);
}

/**
 * One saved connection. [password] and [privateKey]/[keyPassphrase] are secrets: they only ever leave this
 * class through [ConnectionRepo], which encrypts them, and [toString] never prints them.
 */
data class ConnectionConfig(
    val id: String,
    val name: String,
    val protocol: Protocol,
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = "",
    /** SFTP only: OpenSSH/PEM private key text. */
    val privateKey: String = "",
    val keyPassphrase: String = "",
    /** SMB share name. */
    val share: String = "",
    /** Folder to start in (WebDAV: URL path prefix, e.g. /remote.php/dav/files/me). */
    val basePath: String = "/",
    /** WebDAV: https instead of http. FTP: explicit FTPS. */
    val useTls: Boolean = true,
    /** Created from the Tailscale preset (only changes hints in the UI). */
    val tailscale: Boolean = false,
) {
    override fun toString() = "ConnectionConfig($name, $protocol://$host:$port)"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("protocol", protocol.name).put("host", host).put("port", port)
        .put("username", username).put("password", password).put("privateKey", privateKey)
        .put("keyPassphrase", keyPassphrase).put("share", share).put("basePath", basePath)
        .put("useTls", useTls).put("tailscale", tailscale)

    companion object {
        fun fromJson(o: JSONObject) = ConnectionConfig(
            id = o.getString("id"),
            name = o.getString("name"),
            protocol = Protocol.valueOf(o.getString("protocol")),
            host = o.getString("host"),
            port = o.getInt("port"),
            username = o.optString("username"),
            password = o.optString("password"),
            privateKey = o.optString("privateKey"),
            keyPassphrase = o.optString("keyPassphrase"),
            share = o.optString("share"),
            basePath = o.optString("basePath", "/"),
            useTls = o.optBoolean("useTls", true),
            tailscale = o.optBoolean("tailscale", false),
        )
    }
}

/** Seals and opens byte blobs. Implementations must authenticate (tamper = exception). */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/** AES-256-GCM with a fresh random 12-byte IV per message; the key comes from the Android Keystore in the app. */
class AesGcmBox(private val key: () -> SecretKey) : SecretBox {
    private val random = SecureRandom()

    override fun seal(plain: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return byteArrayOf(VERSION) + iv + c.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        if (sealed.size < 1 + IV_BYTES + 16 || sealed[0] != VERSION) throw GeneralSecurityException("bad format")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed.copyOfRange(1, 1 + IV_BYTES)))
        return c.doFinal(sealed, 1 + IV_BYTES, sealed.size - 1 - IV_BYTES)
    }

    private companion object {
        const val IV_BYTES = 12
        const val VERSION: Byte = 1
    }
}

/** What is stored: connections and the optional Tailscale API key, all inside one encrypted file. */
data class VaultContents(val connections: List<ConnectionConfig> = emptyList(), val tailscaleApiKey: String = "")

/**
 * Encrypted store for connections. The file never contains plaintext; if it cannot be decrypted (for example the
 * Keystore key was lost after a restore) [load] throws instead of silently returning an empty list, so a
 * following save cannot wipe data that might still be recoverable.
 */
class ConnectionRepo(private val file: File, private val box: SecretBox) {
    @Synchronized fun load(): VaultContents {
        if (!file.isFile) return VaultContents()
        val o = JSONObject(String(box.open(file.readBytes()), Charsets.UTF_8))
        val arr = o.optJSONArray("connections") ?: JSONArray()
        return VaultContents(
            (0 until arr.length()).map { ConnectionConfig.fromJson(arr.getJSONObject(it)) },
            o.optString("tailscaleApiKey"),
        )
    }

    @Synchronized fun save(contents: VaultContents) {
        val o = JSONObject()
            .put("connections", JSONArray(contents.connections.map { it.toJson() }))
            .put("tailscaleApiKey", contents.tailscaleApiKey)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(box.seal(o.toString().toByteArray(Charsets.UTF_8)))
        check(tmp.renameTo(file) || (file.delete() && tmp.renameTo(file))) { "could not replace vault file" }
    }

    @Synchronized fun upsert(c: ConnectionConfig) {
        val cur = load()
        val list = if (cur.connections.any { it.id == c.id }) cur.connections.map { if (it.id == c.id) c else it } else cur.connections + c
        save(cur.copy(connections = list))
    }

    @Synchronized fun delete(id: String) {
        val cur = load()
        save(cur.copy(connections = cur.connections.filter { it.id != id }))
    }

    @Synchronized fun setTailscaleApiKey(key: String) = save(load().copy(tailscaleApiKey = key))
}
