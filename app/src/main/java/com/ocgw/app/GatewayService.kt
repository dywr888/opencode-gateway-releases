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
 * 前台服务：拉起 opencode serve（在 proot + rootfs 内），广播监听地址与密码给 UI。
 *
 * - onStartCommand 立刻 startForeground()，避免 ForegroundServiceDidNotStartInTimeException
 * - 下载/解压/启动全在后台线程
 * - 运行环境默认走镜像（先测速选最快的）
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

        // 原始 Release 地址（github.com 在国内常被墙，故默认走镜像）
        const val ORIGINAL =
            "https://github.com/dormantxxy/opencode-gateway-releases/releases/download/rootfs/rootfs-arm64.tar.gz"
        // 镜像前缀（拼接原始完整 URL）。空串 = 直连，作兜底。
        val MIRRORS = listOf(
            "https://gh-proxy.com/",
            "https://ghproxy.net/",
            ""
        )

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
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Gateway", NotificationManager.IMPORTANCE_LOW)
            )
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

    private fun publish(phase: String, message: String, running: Boolean, url: String? = null, password: String? = null) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIF_ID, buildNotification(message, running))
        sendBroadcast(Intent(ACTION_STATE).apply {
            putExtra(EXTRA_PHASE, phase)
            putExtra(EXTRA_MESSAGE, message)
            if (url != null) putExtra(EXTRA_URL, url)
            if (password != null) putExtra(EXTRA_PASSWORD, password)
        })
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

    /** 对每个镜像发小 Range 请求测速，返回最快可用的完整下载 URL */
    private fun pickFastestMirror(): String {
        var best = ORIGINAL
        var bestMs = Long.MAX_VALUE
        for (prefix in MIRRORS) {
            val url = prefix + ORIGINAL
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 8000
                    setRequestProperty("Range", "bytes=0-1023")
                }
                val t0 = System.currentTimeMillis()
                val code = conn.responseCode
                val ms = System.currentTimeMillis() - t0
                conn.disconnect()
                Log.i("GatewayService", "mirror $prefix -> $code in ${ms}ms")
                if ((code == 200 || code == 206) && ms < bestMs) {
                    bestMs = ms
                    best = url
                }
            } catch (e: Exception) {
                Log.w("GatewayService", "mirror $prefix failed: ${e.message}")
            }
        }
        return best
    }

    private fun startServer(root: File) {
        publish(PHASE_STARTING, getString(R.string.starting), true)
        val password = (0..15).map { "0123456789abcdef"[(Math.random() * 16).toInt()] }.joinToString("")
        val port = 4317
        val cmd = listOf(
            File(root, "proot/bin/proot").absolutePath,
            "-r", root.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "/dev/pts",
            "--kill-on-exit",
            "-w", "/workspace",
            "/opencode/bin/opencode", "serve",
            "--hostname", "127.0.0.1", "--port", port.toString()
        )
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
        if (waitForReady(url, password)) {
            publish(PHASE_RUNNING, getString(R.string.running), true, url, password)
        } else {
            publish(PHASE_FAILED, getString(R.string.failed, "server not ready"), false)
            stopSelf()
        }
    }

    private fun waitForReady(url: String, password: String): Boolean {
        val encoded = android.util.Base64.encodeToString("opencode:$password".toByteArray(), android.util.Base64.NO_WRAP)
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
        publish(PHASE_DOWNLOAD, "选择最快镜像…", true)
        val url = pickFastestMirror()
        Log.i("GatewayService", "download from $url")
        val tmp = File(cacheDir, "rootfs-arm64.tar.gz")

        var existing = if (tmp.exists()) tmp.length() else 0L
        var attempt = 0
        while (true) {
            attempt++
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 60000
                    if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
                }
                val code = conn.responseCode
                val appending = code == 206 && existing > 0
                if (code != 200 && code != 206) throw RuntimeException("download http $code")
                val total = existing + conn.contentLengthLong
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(tmp, appending).use { out ->
                        val buf = ByteArray(128 * 1024)
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
                                publish(PHASE_DOWNLOAD, "下载中 ${(done * 100 / total)}%", true)
                            }
                        }
                    }
                }
                conn.disconnect()
                break
            } catch (e: Exception) {
                if (stopping) return
                Log.w("GatewayService", "download attempt $attempt failed: ${e.message}")
                existing = if (tmp.exists()) tmp.length() else 0L
                if (attempt >= 5) throw e
                Thread.sleep(2000)
            }
        }

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
