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
 *
 * 关键：必须在 onStartCommand 里立刻 startForeground()，否则 Android 8+ 抛
 * ForegroundServiceDidNotStartInTimeException 崩溃。重活（下载/解压/启动）全在后台线程。
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
    @Volatile private var worker: Thread? = null
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // 立刻进入前台，避免 ForegroundServiceDidNotStartInTimeException
                startForeground(NOTIF_ID, buildNotification(getString(R.string.downloading), true))
                startGatewayAsync()
            }
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

    private fun buildNotification(message: String, running: Boolean): Notification {
        val intent = Intent(this, MainActivity::class.java).apply { action = Intent.ACTION_MAIN }
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenCode Gateway")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pi)
            .setOngoing(running)
            .build()
    }

    /** 更新通知 + 广播状态给 UI */
    private fun publish(phase: String, message: String, running: Boolean, url: String? = null, password: String? = null) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIF_ID, buildNotification(message, running))
        val b = Intent(ACTION_STATE).apply {
            putExtra(EXTRA_PHASE, phase)
            putExtra(EXTRA_MESSAGE, message)
            if (url != null) putExtra(EXTRA_URL, url)
            if (password != null) putExtra(EXTRA_PASSWORD, password)
        }
        sendBroadcast(b)
    }

    private fun startGatewayAsync() {
        stopping = false
        worker = Thread {
            try {
                val root = File(filesDir, "rootfs")
                if (!File(root, "opencode/bin/opencode").exists()) {
                    downloadAndExtract(root)
                    if (!File(root, "opencode/bin/opencode").exists()) return@Thread
                }
                if (stopping) return@Thread
                startServer(root)
            } catch (e: Exception) {
                Log.e("GatewayService", "prepare/start failed", e)
                publish(PHASE_FAILED, getString(R.string.failed, e.message ?: e.javaClass.simpleName), false)
                stopSelf()
            }
        }.also { it.start() }
    }

    private fun startServer(root: File) {
        publish(PHASE_STARTING, getString(R.string.starting), true)
        val password = (0..15).map { "0123456789abcdef"[(Math.random() * 16).toInt()] }.joinToString("")
        val port = 4317
        val cmd = buildList {
            add(File(root, "proot/bin/proot").absolutePath)
            add("-r"); add(root.absolutePath)
            add("-b"); add("/dev")
            add("-b"); add("/proc")
            add("-b"); add("/sys")
            add("-b"); add("/dev/pts")
            add("--kill-on-exit")
            add("-w"); add("/workspace")
            add("/opencode/bin/opencode")
            add("serve")
            add("--hostname"); add("127.0.0.1")
            add("--port"); add(port.toString())
        }
        val builder = ProcessBuilder(cmd)
            .directory(File(root, "workspace").also { it.mkdirs() })
            .redirectErrorStream(true)
        builder.environment().clear()
        mapOf(
            "OPENCODE_PASSWORD" to password,
            "HOME" to "/root",
            "PATH" to "/usr/local/bin:/usr/bin:/bin",
            "TMPDIR" to "/tmp"
        ).forEach { (k, v) -> builder.environment()[k] = v }

        val p = builder.start()
        proc = p
        // 把 opencode 的 stdout/stderr 写进日志文件，便于排查
        val logFile = File(cacheDir, "opencode.log")
        Thread {
            try {
                p.inputStream.bufferedReader().forEachLine { line ->
                    Log.i("opencode", line)
                    logFile.appendText(line + "\n")
                }
            } catch (_: Exception) {
            }
        }.start()

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ocgw::gw").apply { acquire(6 * 3600 * 1000L) }
        }

        val url = "http://127.0.0.1:$port"
        val ok = waitForReady(url, password)
        if (ok) {
            publish(PHASE_RUNNING, getString(R.string.running), true, url, password)
        } else {
            publish(PHASE_FAILED, getString(R.string.failed, "server not ready (see cache/opencode.log)"), false)
            stopSelf()
        }
    }

    private fun waitForReady(url: String, password: String): Boolean {
        val auth = "opencode:$password"
        val encoded = android.util.Base64.encodeToString(auth.toByteArray(), android.util.Base64.NO_WRAP)
        for (i in 0 until 90) {
            if (stopping) return false
            try {
                val conn = URL("$url/api/config").openConnection() as HttpURLConnection
                conn.setRequestProperty("Authorization", "Basic $encoded")
                conn.connectTimeout = 1000
                conn.readTimeout = 1000
                val code = conn.responseCode
                conn.disconnect()
                if (code == 200) return true
            } catch (_: Exception) {
            }
            Thread.sleep(1000)
        }
        return false
    }

    private fun downloadAndExtract(dest: File) {
        publish(PHASE_DOWNLOAD, getString(R.string.downloading), true)
        val url = "$RELEASE_BASE/$ROOTFS_NAME"
        val tmp = File(cacheDir, ROOTFS_NAME)
        // 支持断点续传：已存在部分文件则从断点继续
        var existing = if (tmp.exists()) tmp.length() else 0L
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        val code = conn.responseCode
        val appending = code == 206 && existing > 0
        if (code != 200 && code != 206) throw RuntimeException("download http $code")
        val total = existing + conn.contentLengthLong
        conn.inputStream.use { input ->
            java.io.FileOutputStream(tmp, appending).use { out ->
                val buf = ByteArray(64 * 1024)
                var read: Int
                var done = if (appending) existing else 0L
                var lastTick = 0L
                while (input.read(buf).also { read = it } > 0) {
                    if (stopping) throw InterruptedException("stopped")
                    out.write(buf, 0, read)
                    done += read
                    val now = System.currentTimeMillis()
                    if (now - lastTick > 1000 && total > 0) {
                        lastTick = now
                        val pct = (done * 100 / total).toInt()
                        publish(PHASE_DOWNLOAD, "下载中 $pct%", true)
                    }
                }
            }
        }
        conn.disconnect()

        if (stopping) return
        publish(PHASE_EXTRACT, getString(R.string.extracting), true)
        dest.mkdirs()
        val p = ProcessBuilder("sh", "-c", "cd '${dest.absolutePath}' && tar -xzf '${tmp.absolutePath}'").start()
        val rc = p.waitFor()
        if (rc != 0) throw RuntimeException("extract failed: ${p.errorStream.bufferedReader().readText()}")
        tmp.delete()
    }

    private fun stopGateway() {
        stopping = true
        try { proc?.destroy() } catch (_: Exception) {}
        proc = null
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        publish(PHASE_STOPPED, getString(R.string.stopped), false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopping = true
        try { proc?.destroy() } catch (_: Exception) {}
        try { wakeLock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
