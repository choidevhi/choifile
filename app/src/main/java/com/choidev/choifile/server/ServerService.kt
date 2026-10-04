package com.choidev.choifile.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.choidev.choifile.MainActivity
import com.choidev.choifile.R
import com.choidev.choifile.security.AppPrefs
import com.choidev.choifile.security.PathGuard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

enum class ServerKind { HTTP, FTP }

data class ServerStatus(
    val running: Boolean = false,
    val port: Int = 0,
    /** Password / token for this run. Generated fresh at every start and shown only on the server screen. */
    val token: String = "",
    val root: String = "",
    val allowUpload: Boolean = true,
    /** Message resource id of the last start failure, shown on the screen. */
    val errorRes: Int = 0,
    val errorDetail: String = "",
)

/** Observable state of both servers, written by [ServerService]. */
object ServerRuntime {
    val http = MutableStateFlow(ServerStatus())
    val ftp = MutableStateFlow(ServerStatus())

    fun flow(kind: ServerKind) = if (kind == ServerKind.HTTP) http else ftp

    /** Non-loopback IPv4 addresses of this phone with a short label ("Tailscale" for 100.64.0.0/10). */
    fun localAddresses(): List<Pair<String, Boolean>> = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { it.hostAddress.orEmpty() } }
            .filter { it.isNotEmpty() }
            .map { it to isTailscale(it) }
    } catch (_: Exception) {
        emptyList()
    }

    fun isTailscale(ip: String): Boolean {
        val p = ip.split('.').mapNotNull { it.toIntOrNull() }
        return p.size == 4 && p[0] == 100 && p[1] in 64..127
    }
}

/**
 * Keeps the file servers alive while they run (foreground service with a notification and a stop button).
 * Nothing starts on its own: only an explicit start from the server screen creates a socket.
 */
class ServerService : Service() {
    private var httpServer: HttpFileServer? = null
    private var ftpServer: FtpFileServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val kind = runCatching { ServerKind.valueOf(intent.getStringExtra(EXTRA_KIND).orEmpty()) }.getOrNull()
                if (kind != null) {
                    showNotification()
                    start(kind, intent.getStringExtra(EXTRA_ROOT).orEmpty(), intent.getIntExtra(EXTRA_PORT, 0), intent.getBooleanExtra(EXTRA_UPLOAD, true))
                }
            }
            ACTION_STOP -> stop(runCatching { ServerKind.valueOf(intent.getStringExtra(EXTRA_KIND).orEmpty()) }.getOrNull())
            else -> showNotification()
        }
        refreshOrStop()
        return START_NOT_STICKY
    }

    private fun start(kind: ServerKind, rootPath: String, port: Int, upload: Boolean) {
        val flow = ServerRuntime.flow(kind)
        if (flow.value.running) return
        val root = File(rootPath)
        val guard = PathGuard(AppPrefs(this).protectedFolders, excludeAllProtected = true)
        if (!root.isDirectory || guard.isExcluded(root.absolutePath)) {
            flow.value = ServerStatus(errorRes = R.string.srv_err_folder)
            return
        }
        val token = ServerToken.generate()
        try {
            val actual = if (kind == ServerKind.HTTP) {
                HttpFileServer(root, token, guard, upload, null, port).also { httpServer = it }.start()
            } else {
                FtpFileServer(root, token, guard, upload, null, port).also { ftpServer = it }.start()
            }
            flow.value = ServerStatus(running = true, port = actual, token = token, root = rootPath, allowUpload = upload)
        } catch (e: Exception) {
            if (kind == ServerKind.HTTP) httpServer = null else ftpServer = null
            flow.value = ServerStatus(errorRes = R.string.srv_err_bind, errorDetail = e.message.orEmpty())
        }
    }

    private fun stop(kind: ServerKind?) {
        if (kind == null || kind == ServerKind.HTTP) { httpServer?.stop(); httpServer = null; ServerRuntime.http.value = ServerStatus() }
        if (kind == null || kind == ServerKind.FTP) { ftpServer?.stop(); ftpServer = null; ServerRuntime.ftp.value = ServerStatus() }
    }

    private fun refreshOrStop() {
        if (httpServer == null && ftpServer == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else showNotification()
    }

    override fun onDestroy() {
        stop(null)
        super.onDestroy()
    }

    // Android 15 limits some foreground service types to a few hours; "specialUse" has no such timeout.
    private fun showNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.srv_channel), NotificationManager.IMPORTANCE_LOW))
        val parts = ArrayList<String>()
        if (httpServer != null) parts += "HTTP :" + ServerRuntime.http.value.port
        if (ftpServer != null) parts += "FTP :" + ServerRuntime.ftp.value.port
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.srv_notif_title))
            .setContentText(parts.joinToString(" · ").ifEmpty { getString(R.string.srv_notif_starting) })
            .setContentIntent(open)
            .addAction(0, getString(R.string.srv_stop_all), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(NOTIF_ID, n)
    }

    companion object {
        private const val CHANNEL = "choifile_server"
        private const val NOTIF_ID = 4101
        const val ACTION_START = "com.choidev.choifile.server.START"
        const val ACTION_STOP = "com.choidev.choifile.server.STOP"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_ROOT = "root"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_UPLOAD = "upload"

        fun start(context: Context, kind: ServerKind, root: String, port: Int, allowUpload: Boolean) {
            ServerRuntime.flow(kind).update { ServerStatus() } // clear an old error
            val i = Intent(context, ServerService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_KIND, kind.name).putExtra(EXTRA_ROOT, root).putExtra(EXTRA_PORT, port).putExtra(EXTRA_UPLOAD, allowUpload)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context, kind: ServerKind) {
            context.startService(Intent(context, ServerService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_KIND, kind.name))
        }
    }
}
