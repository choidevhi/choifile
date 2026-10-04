package com.choidev.choifile.remote

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/** The user's answer to an SFTP host-key prompt. */
enum class HostKeyDecision { TRUST, REJECT }

/**
 * Asked when a host key is new or has changed. [previous] is the stored fingerprint on a mismatch and null
 * on first contact. Implementations block until the user decides (the caller runs on a worker thread).
 */
fun interface HostKeyDecider {
    fun decide(host: String, port: Int, fingerprint: String, previous: String?): HostKeyDecision
}

/** Fingerprints we trusted before, keyed by "host:port". Plain file: fingerprints are public information. */
class HostKeyStore(private val file: File) {
    @Synchronized fun get(host: String, port: Int): String? = read().optString(key(host, port)).ifEmpty { null }

    @Synchronized fun put(host: String, port: Int, fingerprint: String) {
        val o = read().put(key(host, port), fingerprint)
        file.writeText(o.toString())
    }

    @Synchronized fun remove(host: String, port: Int) {
        file.writeText(read().also { it.remove(key(host, port)) }.toString())
    }

    private fun read(): JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    private fun key(host: String, port: Int) = "${host.lowercase()}:$port"
}

/** Trust on first use: unknown keys need a visible approval, changed keys need an explicit, warned override. */
class HostKeyPolicy(private val store: HostKeyStore, private val decider: HostKeyDecider) {
    fun verify(host: String, port: Int, fingerprint: String): Boolean {
        val known = store.get(host, port)
        if (known == fingerprint) return true
        val decision = decider.decide(host, port, fingerprint, known)
        if (decision != HostKeyDecision.TRUST) return false
        store.put(host, port, fingerprint)
        return true
    }
}

/** OpenSSH style "SHA256:base64" fingerprint of an SSH public key blob. */
fun sshFingerprint(publicKeyBlob: ByteArray): String =
    "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(publicKeyBlob))
