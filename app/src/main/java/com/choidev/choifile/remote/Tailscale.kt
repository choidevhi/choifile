package com.choidev.choifile.remote

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Tailscale support without any Tailscale SDK: the phone's Tailscale app provides a VPN, and tailnet devices are then
 * reachable by their 100.x address or MagicDNS name like any other host. Nothing here works unless that app is running.
 */
object Tailscale {

    enum class HostKind { TAILNET_IP, MAGIC_DNS, OTHER }

    /** 100.64.0.0/10 is the CGNAT range Tailscale hands out. */
    fun isTailnetIpv4(host: String): Boolean {
        val p = host.trim().split('.')
        if (p.size != 4) return false
        val n = p.map { it.toIntOrNull() ?: return false }
        return n.all { it in 0..255 } && n[0] == 100 && n[1] in 64..127
    }

    /**
     * ".ts.net" names are always MagicDNS. A bare name without dots is probably a MagicDNS short name, but that
     * cannot be proven offline, so it is only classified as such when the caller opted into the Tailscale preset.
     */
    fun classify(host: String, presetShortNames: Boolean = false): HostKind {
        val h = host.trim().trimEnd('.').lowercase()
        return when {
            isTailnetIpv4(h) -> HostKind.TAILNET_IP
            h.endsWith(".ts.net") -> HostKind.MAGIC_DNS
            presetShortNames && h.isNotEmpty() && '.' !in h && h.all { it.isLetterOrDigit() || it == '-' } -> HostKind.MAGIC_DNS
            else -> HostKind.OTHER
        }
    }

    sealed interface Reach {
        data class Ok(val millis: Long) : Reach
        data class Failed(val reason: String) : Reach
    }

    /** TCP connect test. A failure usually means the Tailscale app is off, the device is offline, or the port is closed. */
    fun checkReachable(host: String, port: Int, timeoutMs: Int = 5_000): Reach {
        val start = System.nanoTime()
        return try {
            Socket().use { it.connect(InetSocketAddress(host.trim(), port), timeoutMs) }
            Reach.Ok((System.nanoTime() - start) / 1_000_000)
        } catch (e: java.net.UnknownHostException) {
            Reach.Failed("name not found")
        } catch (e: java.net.SocketTimeoutException) {
            Reach.Failed("timeout")
        } catch (e: IOException) {
            Reach.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    data class Device(val name: String, val hostname: String, val addresses: List<String>, val os: String) {
        val ipv4: String? get() = addresses.firstOrNull { '.' in it }
        /** Preferred address to connect to: MagicDNS FQDN if present, else the 100.x address. */
        val target: String get() = name.trimEnd('.').ifEmpty { ipv4 ?: hostname }
    }

    fun parseDevices(json: String): List<Device> {
        val arr = JSONObject(json).optJSONArray("devices") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val addrs = o.optJSONArray("addresses")
            Device(
                o.optString("name"), o.optString("hostname"),
                if (addrs == null) emptyList() else (0 until addrs.length()).map { addrs.getString(it) },
                o.optString("os"),
            )
        }
    }

    /**
     * Lists devices through the Tailscale control API with a user-provided API key. This is the only call that
     * goes to the internet instead of the tailnet, and it is only made when the user pressed the button.
     */
    fun listDevices(apiKey: String, baseUrl: String = "https://api.tailscale.com", client: OkHttpClient = OkHttpClient()): List<Device> {
        val req = Request.Builder().url("$baseUrl/api/v2/tailnet/-/devices")
            .header("Authorization", Credentials.basic(apiKey, ""))
            .build()
        client.newBuilder().callTimeout(15, TimeUnit.SECONDS).followRedirects(false).build().newCall(req).execute().use { r ->
            if (r.code == 401 || r.code == 403) throw IOException("Tailscale API: key rejected (HTTP ${r.code})")
            if (!r.isSuccessful) throw IOException("Tailscale API: HTTP ${r.code}")
            return parseDevices(r.body!!.string())
        }
    }
}
