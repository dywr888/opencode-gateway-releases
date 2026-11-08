package com.ocgw.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 前台服务：拉起 opencode serve（proot + rootfs 内）+ 本地 OpenAI 兼容网关。
 *
 * 实测要点：
 * - proot 用 Termux 版（bionic/NDK）：LD_LIBRARY_PATH=proot/lib，PROOT_LOADER=proot/libexec/loader。
 * - targetSdk <= 28，否则禁止 exec app 数据目录里的可执行文件。
 * - resolv.conf 用系统实际 DNS：写死 8.8.8.8 在纯 IPv6 蜂窝网下解析不了。
 * - 用户选了项目目录就用 proot -b <真实路径>:/workspace 挂进来。
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
        const val EXTRA_GATEWAY = "gateway"

        const val CHANNEL_ID = "gateway"
        const val NOTIF_ID = 1

        const val ORIGINAL =
            "https://github.com/dormantxxy/opencode-gateway-releases/releases/download/rootfs/rootfs-arm64.tar.gz"
        val MIRRORS = listOf("https://gh-proxy.com/", "https://ghproxy.net/", "")

        private val FALLBACK_DNS = listOf("223.5.5.5", "114.114.114.114", "8.8.8.8")

        const val PHASE_DOWNLOAD = "download"
        const val PHASE_EXTRACT = "extract"
        const val PHASE_STARTING = "starting"
        const val PHASE_RUNNING = "running"
        const val PHASE_STOPPED = "stopped"
        const val PHASE_FAILED = "failed"

        /** 本地网关端口（给本 app / 其他 app 调 OpenAI 兼容接口） */
        const val GATEWAY_PORT = 4318
        const val OPENCODE_PORT = 4317
        const val DEFAULT_MODEL = "opencode/big-pickle"
    }

    private var proc: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var gateway: OpenAiGateway? = null

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
                val alreadyReady = ready(File(filesDir, "rootfs"))
                val initial = if (alreadyReady) getString(R.string.starting) else getString(R.string.downloading)
                startForeground(NOTIF_ID, buildNotification(initial, true))

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

    private fun publish(
        phase: String,
        message: String,
        running: Boolean,
        url: String? = null,
        password: String? = null,
        gatewayUrl: String? = null,
    ) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(message, running))
        sendBroadcast(Intent(ACTION_STATE).apply {
            putExtra(EXTRA_PHASE, phase)
            putExtra(EXTRA_MESSAGE, message)
            if (url != null) putExtra(EXTRA_URL, url)
            if (password != null) putExtra(EXTRA_PASSWORD, password)
            if (gatewayUrl != null) putExtra(EXTRA_GATEWAY, gatewayUrl)
        })
    }

    private fun startGatewayAsync() {
        stopping = false
        Thread {
            try {
                val root = File(filesDir, "rootfs")
                if (!ready(root)) {
                    downloadAndExtract(root)
                } else {
                    Log.i("GatewayService", "rootfs already present; skip download")
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

    private fun writeResolvConf(root: File) {
        val servers = LinkedHashSet<String>()
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            cm?.allNetworks?.forEach { net ->
                try {
                    cm.getLinkProperties(net)?.dnsServers?.forEach { addr ->
                        addr.hostAddress?.let { servers.add(it.substringBefore('%')) }
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w("GatewayService", "read system dns failed: ${e.message}")
        }
        FALLBACK_DNS.forEach { servers.add(it) }
        val content = buildString { servers.take(6).forEach { append("nameserver ").append(it).append('\n') } }
        try {
            File(root, "etc").mkdirs()
            File(root, "etc/resolv.conf").writeText(content)
            Log.i("GatewayService", "resolv.conf:\n$content")
        } catch (e: Exception) {
            Log.w("GatewayService", "write resolv.conf failed: ${e.message}")
        }
    }

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

    private fun remoteSize(url: String): Long = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "HEAD"
        }
        val len = conn.contentLengthLong
        conn.disconnect()
        len
    } catch (_: Exception) { -1L }

    private fun startServer(root: File) {
        publish(PHASE_STARTING, getString(R.string.starting), true)
        writeResolvConf(root)

        val password = (0..15).map { "0123456789abcdef"[(Math.random() * 16).toInt()] }.joinToString("")
        val prootBin = File(root, "proot/bin/proot").absolutePath
        val prootLib = File(root, "proot/lib").absolutePath
        val prootLoader = File(root, "proot/libexec/loader").absolutePath

        // 用户选的项目目录 → 挂到容器内 /workspace
        val projectPath = ProjectStore.load(this)?.let { ProjectStore.resolvePath(this, it) }
        val workspaceInRootfs = File(root, "workspace")
        val projectExists = projectPath != null && File(projectPath).exists()
        if (!projectExists) workspaceInRootfs.mkdirs()
        Log.i("GatewayService", "project=$projectPath mounted=$projectExists")

        val cmd = buildList {
            add(prootBin)
            add("-r"); add(root.absolutePath)
            add("-b"); add("/dev")
            add("-b"); add("/proc")
            add("-b"); add("/sys")
            add("-b"); add("/dev/pts")
            if (projectExists) {
                // 真实项目目录覆盖容器内 /workspace
                add("-b"); add("$projectPath:/workspace")
            }
            add("--kill-on-exit")
            add("-w"); add("/workspace")
            add("/opencode/bin/opencode"); add("serve")
            add("--hostname"); add("127.0.0.1")
            add("--port"); add(OPENCODE_PORT.toString())
        }

        val builder = ProcessBuilder(cmd)
            .directory(root)
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

        val url = "http://127.0.0.1:$OPENCODE_PORT"
        if (!waitForReady(url, password)) {
            publish(PHASE_FAILED, getString(R.string.failed, "server not ready (check opencode.log)"), false)
            stopSelf()
            return
        }

        // 起本地 OpenAI 兼容网关
        val token = android.util.Base64.encodeToString(
            "opencode:$password".toByteArray(), android.util.Base64.NO_WRAP
        )
        val gw = OpenAiGateway(
            listenPort = GATEWAY_PORT,
            opencodeBase = url,
            opencodeAuthToken = token,
            defaultModel = DEFAULT_MODEL,
            onStatus = { Log.i("GatewayService", "gateway: $it") },
        )
        gw.start()
        gateway = gw

        val gatewayUrl = "http://127.0.0.1:$GATEWAY_PORT/v1"
        val mountInfo = if (projectExists) projectPath else "容器内 /workspace（未选项目）"
        publish(
            PHASE_RUNNING,
            getString(R.string.running),
            true,
            url,
            password,
            gatewayUrl,
        )
        Log.i("GatewayService", "READY ui=$url gateway=$gatewayUrl workspace=$mountInfo")
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

        val expect = remoteSize(url)
        if (tmp.exists() && expect > 0 && tmp.length() != expect) {
            Log.i("GatewayService", "cached size ${tmp.length()} != remote $expect, restart download")
            tmp.delete()
        }

        var attempt = 0
        while (true) {
            attempt++
            val existing = if (tmp.exists()) tmp.length() else 0L
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
        if (!File(staging, "opencode/bin/opencode").exists()) {
            staging.deleteRecursively()
            throw RuntimeException("extract ok but opencode missing")
        }
        dest.deleteRecursively()
        if (!staging.renameTo(dest)) {
            staging.copyRecursively(dest, overwrite = true)
            staging.deleteRecursively()
        }
        Log.i("GatewayService", "rootfs ready at ${dest.absolutePath}")
    }

    private fun stopGateway() {
        stopping = true
        synchronized(lock) { busy = false }
        try { gateway?.stop() } catch (_: Exception) {}
        gateway = null
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
        try { gateway?.stop() } catch (_: Exception) {}
        try { proc?.destroy() } catch (_: Exception) {}
        try { wakeLock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
