package com.ocgw.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 前台服务：负责拉起 opencode serve（在 proot + rootfs 内），并对外广播
 * 监听地址与 Basic Auth 密码。MainActivity 拿到后加载 WebView 并可用于原生调用网关。
 */
class GatewayService : Service() {
    companion object {
        const val ACTION_START = "com.ocgw.app.START"
        const val ACTION_STOP = "com.ocgw.app.STOP"
        const val ACTION_STATE = "com.ocgw.app.STATE"
        const val EXTRA_URL = "url"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_PHASE = "phase"
        const val EXTRA_MESSAGE = "message"

        const val CHANNEL_ID = "gateway"
        const val NOTIF_ID = 1

        // 运行环境包（proot + rootfs + opencode），首次启动下载。
        // 固定使用 tag=rootfs 的发布（build-rootfs.yml 产出 rootfs-arm64.tar.gz）。
        const val RELEASE_BASE = "https://github.com/dormantxxy/opencode-gateway-releases/releases/download/rootfs"
        const val ROOTFS_NAME = "rootfs-arm64.tar.gz"

        const val PHASE_DOWNLOAD = "download"
        const val PHASE_EXTRACT = "extract"
        const val PHASE_STARTING = "starting"
        const val PHASE_RUNNING = "running"
        const val PHASE_STOPPED = "stopped"
        const val PHASE_FAILED = "failed"
    }

    private var proc: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startGateway()
            ACTION_STOP -> stopGateway()
        }
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CHANNEL_ID, "Gateway", NotificationManager.IMPORTANCE_LOW)
            mgr.createNotificationChannel(ch)
        }
    }

    private fun notify(phase: String, message: String, running: Boolean) {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
        }
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenCode Gateway")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pi)
            .setOngoing(running)
            .build()
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIF_ID, notif)

        val broadcast = Intent(ACTION_STATE).apply {
            putExtra(EXTRA_PHASE, phase)
            putExtra(EXTRA_MESSAGE, message)
        }
        sendBroadcast(broadcast)
    }

    private fun startGateway() {
        val root = File(filesDir, "rootfs")
        // 1) 确保运行环境就绪
        if (!File(root, "opencode/bin/opencode").exists()) {
            downloadAndExtract(root)
            if (!File(root, "opencode/bin/opencode").exists()) return
        }
        // 2) 起服务
        notify(PHASE_STARTING, getString(R.string.starting), true)
        try {
            val password = (0..15).map { "0123456789abcdef"[(Math.random() * 16).toInt()] }.joinToString("")
            val port = 4317
            val cmd = buildList {
                add(File(root, "proot/bin/proot").absolutePath)
                add("-r")
                add(root.absolutePath)
                add("-b")
                add("/dev")
                add("-b")
                add("/proc")
                add("-b")
                add("/sys")
                add("-b")
                add("/dev/pts")
                add("--kill-on-exit")
                add("-w")
                add("/workspace")
                add("/opencode/bin/opencode")
                add("serve")
                add("--hostname")
                add("127.0.0.1")
                add("--port")
                add(port.toString())
            }
            val envp = arrayOf(
                "OPENCODE_PASSWORD=$password",
                "HOME=/root",
                "PATH=/usr/local/bin:/usr/bin:/bin",
                "TMPDIR=/tmp"
            )
            val builder = ProcessBuilder(cmd)
                .directory(File(root, "workspace").also { it.mkdirs() })
                .redirectErrorStream(true)
            builder.environment().clear()
            envp.forEach { e ->
                val i = e.indexOf('=')
                builder.environment()[e.substring(0, i)] = e.substring(i + 1)
            }
            proc = builder.start()
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).run {
                newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ocgw::gw").apply { acquire(6 * 3600 * 1000L) }
            }
            Thread {
                val url = "http://127.0.0.1:$port"
                waitForReady(url, password)
                notify(PHASE_RUNNING, getString(R.string.running), true)
                val b = Intent(ACTION_STATE).apply {
                    putExtra(EXTRA_PHASE, PHASE_RUNNING)
                    putExtra(EXTRA_URL, url)
                    putExtra(EXTRA_PASSWORD, password)
                }
                sendBroadcast(b)
            }.start()
        } catch (e: Exception) {
            Log.e("GatewayService", "start failed", e)
            notify(PHASE_FAILED, getString(R.string.failed, e.message ?: ""), false)
            stopSelf()
        }
    }

    private fun waitForReady(url: String, password: String) {
        val auth = "opencode:$password"
        val encoded = android.util.Base64.encodeToString(auth.toByteArray(), android.util.Base64.NO_WRAP)
        for (i in 0 until 60) {
            try {
                val conn = URL("$url/api/config").openConnection() as HttpURLConnection
                conn.setRequestProperty("Authorization", "Basic $encoded")
                conn.connectTimeout = 1000
                conn.readTimeout = 1000
                if (conn.responseCode == 200) {
                    conn.disconnect()
                    return
                }
                conn.disconnect()
            } catch (_: Exception) {
            }
            Thread.sleep(1000)
        }
    }

    private fun downloadAndExtract(dest: File) {
        notify(PHASE_DOWNLOAD, getString(R.string.downloading), true)
        try {
            val url = "$RELEASE_BASE/$ROOTFS_NAME"
            val tmp = File(cacheDir, ROOTFS_NAME)
            URL(url).openStream().use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            }
            notify(PHASE_EXTRACT, getString(R.string.extracting), true)
            // 解压 tar.gz 到 dest
            dest.mkdirs()
            val p = ProcessBuilder(
                "sh", "-c",
                "cd '${dest.absolutePath}' && tar -xzf '${tmp.absolutePath}'"
            ).start()
            val code = p.waitFor()
            if (code != 0) throw RuntimeException("extract failed: ${p.errorStream.bufferedReader().readText()}")
            tmp.delete()
        } catch (e: Exception) {
            Log.e("GatewayService", "prepare failed", e)
            notify(PHASE_FAILED, getString(R.string.failed, e.message ?: ""), false)
            stopSelf()
        }
    }

    private fun stopGateway() {
        try { proc?.destroy() } catch (_: Exception) {}
        proc = null
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        notify(PHASE_STOPPED, getString(R.string.stopped), false)
        stopSelf()
    }

    override fun onDestroy() {
        stopGateway()
        super.onDestroy()
    }
}
