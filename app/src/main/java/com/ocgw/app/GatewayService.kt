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
 * 前台服务：拉起 opencode serve（proot + rootfs 内），广播监听地址与密码给 UI。
 *
 * 关键点（均已实测验证）：
 * - proot 用 Termux 版（bionic/NDK），需 LD_LIBRARY_PATH 指向 proot/lib，PROOT_LOADER 指向 libexec/loader。
 * - targetSdk 必须 <= 28，否则 Android 10+ 禁止 exec app 数据目录里的可执行文件（W^X）。
 * - Android 系统 tar 是 toybox，只用 -xzf。
 * - 解压到 staging 目录后原子替换，避免并发/残留导致目录消失。
 * - 单 worker 守卫：同一时刻只允许一个准备/启动任务。
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

        const val ORIGINAL =
            "https://github.com/dormantxxy/opencode-gateway-releases/releases/download/rootfs/rootfs-arm64.tar.gz"
        val MIRRORS = listOf("https://gh-proxy.com/", "https://ghproxy.net/", "")

        const val PHASE_DOWNLOAD = "download"
        const val PHASE_EXTRACT = "extract"
        const val PHASE_STARTING = "starting"
        const val PHASE_RUNNING = "running"
        const val PHASE_STOPPED = "stopped"
        const val PHASE_FAILED = "failed"
    }

    private var proc: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // 单 worker 守卫：busy 为真时忽略重复 START
    private val lock = Any()
    @Volatile private var busy = false
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
                synchronized(lock) {
                    if (busy) {
                        Log.i("GatewayService", "busy; ignore duplicate START")
                        return START_STICKY
                    }
                    busy = true
                }
                startGatewayAsync()
            }
            ACTION_STOP -> stopGateway()
        }
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Gateway", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(message: String, running: Boolean): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { action = Intent.ACTION_MAIN },
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenCode Gateway")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pi)
            .setOngoing(running)
            .build()
    }

    private fun publish(phase: String, message: String, running: Boolean, url: String? = null, password: String? = null) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(message, running))
        sendBroadcast(Intent(ACTION_STATE).apply {
            putExtra(EXTRA_PHASE, phase)
            putExtra(EXTRA_MESSAGE, message)
            if (url != null) putExtra(EXTRA_URL, url)
            if (password != null) putExtra(EXTRA_PASSWORD, password)
        })
    }

    private fun startGatewayAsync() {
        stopping = false
        Thread {
            try {
                val root = File(filesDir, "rootfs")
                if (!ready(root)) {
                    downloadAndExtract(root)
                }
                if (stopping) return@Thread
                if (!ready(root)) throw RuntimeException("rootfs incomplete after extract")
                startServer(root)
            } catch (e: Exception) {
                Log.e("GatewayService", "prepare/start failed", e)
                publish(PHASE_FAILED, getString(R.string.failed, e.message ?: e.javaClass.simpleName), false)
                stopSelf()
            } finally {
                synchronized(lock) { busy = false }
            }
        }.start()
    }

    private fun ready(root: File): Boolean =
        File(root, "opencode/bin/opencode").exists() &&
            File(root, "proot/bin/proot").exists() &&
            File(root, "proot/libexec/loader").exists() &&
            File(root, "proot/lib/libtalloc.so.2").exists()

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
                Log.i("GatewayService", "mirror '$prefix' -> $code in ${ms}ms")
                if ((code == 200 || code == 206) && ms < bestMs) { bestMs = ms; best = url }
            } catch (e: Exception) {
                Log.w("GatewayService", "mirror '$prefix' failed: ${e.message}")
            }
        }
        return best
    }

    /** 查询远端大小（用于判断本地缓存是否完整） */
    private fun remoteSize(url: String): Long {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "HEAD"
            }
            val len = conn.contentLengthLong
            conn.disconnect()
            len
        } catch (_: Exception) { -1L }
    }

    private fun startServer(root: File) {
        publish(PHASE_STARTING, getString(R.string.starting), true)
        val password = (0..15).map { "0123456789abcdef"[(Math.random() * 16).toInt()] }.joinToString("")
        val port = 4317

        val prootBin = File(root, "proot/bin/proot").absolutePath
        val prootLib = File(root, "proot/lib").absolutePath
        val prootLoader = File(root, "proot/libexec/loader").absolutePath

        val cmd = listOf(
            prootBin,
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
            "TMPDIR" to "/tmp",
            "LD_LIBRARY_PATH" to prootLib,
            "PROOT_LOADER" to prootLoader,
            "PROOT_TMP_DIR" to cacheDir.absolutePath
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
            } catch (_: Exception) {}
        }.start()

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ocgw::gw").apply { acquire(6 * 3600 * 1000L) }
        }

        val url = "http://127.0.0.1:$port"
        if (waitForReady(url, password)) {
            publish(PHASE_RUNNING, getString(R.string.running), true, url, password)
        } else {
            publish(PHASE_FAILED, getString(R.string.failed, "server not ready (check opencode.log)"), false)
            stopSelf()
        }
    }

    private fun waitForReady(url: String, password: String): Boolean {
        val encoded = android.util.Base64.encodeToString("opencode:$password".toByteArray(), android.util.Base64.NO_WRAP)
        for (i in 0 until 90) {
            if (stopping) return false
            try {
                val conn = (URL("$url/api/config").openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Authorization", "Basic $encoded")
                    connectTimeout = 1000
                    readTimeout = 1000
                }
                val code = conn.responseCode
                conn.disconnect()
                if (code == 200) return true
            } catch (_: Exception) {}
            Thread.sleep(1000)
        }
        return false
    }

    private fun downloadAndExtract(dest: File) {
        publish(PHASE_DOWNLOAD, "选择最快镜像…", true)
        val url = pickFastestMirror()
        Log.i("GatewayService", "download from $url")
        val tmp = File(cacheDir, "rootfs-arm64.tar.gz")

        // 本地缓存若不完整（大小与远端不符），删掉重下，避免续传污染
        val expect = remoteSize(url)
        if (tmp.exists() && expect > 0 && tmp.length() != expect) {
            Log.i("GatewayService", "cached size ${tmp.length()} != remote $expect, restart download")
            tmp.delete()
        }

        var attempt = 0
        while (true) {
            attempt++
            var existing = if (tmp.exists()) tmp.length() else 0L
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 60000
                    if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
                }
                val code = conn.responseCode
                if (code == 416) {
                    conn.disconnect()
                    tmp.delete()
                    throw RuntimeException("http 416 (reset)")
                }
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
                if (attempt >= 6) throw e
                Thread.sleep(2000)
            }
        }

        if (stopping) return
        publish(PHASE_EXTRACT, getString(R.string.extracting), true)

        // 解压到 staging，成功后再原子替换，避免并发/残留
        val staging = File(filesDir, "rootfs.staging")
        staging.deleteRecursively()
        if (!staging.mkdirs() && !staging.exists()) throw RuntimeException("cannot create staging dir")

        val p = ProcessBuilder("tar", "-xzf", tmp.absolutePath, "-C", staging.absolutePath).start()
        val rc = p.waitFor()
        if (rc != 0) {
            val err = p.errorStream.bufferedReader().readText()
            staging.deleteRecursively()
            throw RuntimeException("extract failed($rc): $err")
        }
        // 校验关键文件
        if (!File(staging, "opencode/bin/opencode").exists()) {
            staging.deleteRecursively()
            throw RuntimeException("extract ok but opencode missing")
        }
        dest.deleteRecursively()
        if (!staging.renameTo(dest)) {
            // rename 失败则退化为拷贝目录（同分区一般能成功）
            staging.copyRecursively(dest, overwrite = true)
            staging.deleteRecursively()
        }
        Log.i("GatewayService", "rootfs ready at ${dest.absolutePath}")
    }

    private fun stopGateway() {
        stopping = true
        synchronized(lock) { busy = false }
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
