package com.ocgw.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 前台服务：拉起 opencode serve（proot + rootfs）+ 本地 OpenAI 兼容网关。
 *
 * 实测要点：
 * - proot 用 Termux 版（bionic/NDK）：LD_LIBRARY_PATH=proot/lib，PROOT_LOADER=proot/libexec/loader。
 * - targetSdk <= 28，否则禁止 exec app 数据目录里的可执行文件。
 * - resolv.conf 用系统实际 DNS（纯 IPv6 蜂窝网下 8.8.8.8 不可用）。
 * - 项目目录：app 无法按硬路径访问 /sdcard（scoped storage），
 *   故用 SAF 把文件拷进 filesDir/workspace，再挂载。
 * - waitForReady 不能只认 200：/api/config 可能返回 500（如目录不可读），需尽快失败而不是空转。
 * - **下载/解压/启动进度通过广播 EXTRA_PROGRESS 传回 UI，0-100，-1 表示不定长**。
 */
class GatewayService : Service() {
    private fun fetchModelSummary() {
        try {
            val gw = Companion.gatewayUrl ?: return
            val conn = java.net.URL("$gw/models").openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val arr = org.json.JSONObject(body).optJSONArray("data") ?: return
            if (arr.length() == 0) return
            val names = (0 until minOf(arr.length(), 2)).map {
                arr.getJSONObject(it).optString("id").substringAfter('/')
            }
            Companion.modelSummary = "${arr.length()} 个 · ${names.joinToString("、")} 等"
        } catch (_: Exception) {}
    }

    companion object {
        const val ACTION_START = "com.ocgw.app.START"
        const val ACTION_STOP = "com.ocgw.app.STOP"
        const val ACTION_STATE = "com.ocgw.app.STATE"
        const val EXTRA_URL = "url"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_PHASE = "phase"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_GATEWAY = "gateway"

        const val CHANNEL_ID = "gateway"
        const val NOTIF_ID = 1

        const val ORIGINAL =
            "https://github.com/dormantxxy/opencode-gateway-releases/releases/download/rootfs/rootfs-arm64.tar.gz"
        val MIRRORS = listOf(
            "https://gh-proxy.com/",
            "https://ghproxy.net/",
            "https://gh-proxy.top/",
            "https://gh.ddlc.top/",
            "https://gh.llkk.cc/",
            "https://ghfast.top/",
            "https://gh.ddlc.top/",
            "https://git.yiuman.com/",
            "https://git.886.be/",
            "https://gh.nyc3.sosnw.com/",
            "",
        )

        private val FALLBACK_DNS = listOf("223.5.5.5", "114.114.114.114", "8.8.8.8")

        const val PHASE_DOWNLOAD = "download"
        const val PHASE_EXTRACT = "extract"
        const val PHASE_STARTING = "starting"
        const val PHASE_RUNNING = "running"
        const val PHASE_STOPPED = "stopped"
        const val PHASE_FAILED = "failed"

        const val GATEWAY_PORT = 4318
        const val OPENCODE_PORT = 4317
        const val DEFAULT_MODEL = "opencode/big-pickle"

        // ===== 供 UI 轮询的静态状态（照 OurFreeModel 的做法）=====
        @Volatile var running = false
        @Volatile var actualPort = GATEWAY_PORT
        @Volatile var lastError: String? = null
        @Volatile var progressPct = -1
        @Volatile var progressMsg = ""
        @Volatile var uiUrl: String? = null
        @Volatile var uiPassword: String? = null
        @Volatile var gatewayUrl: String? = null
        @Volatile var apiKey: String = ""
        @Volatile var modelSummary: String = ""
    }

    private var proc: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var gateway: OpenAiGateway? = null
    private var serverPassword: String? = null

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
                val initial = if (alreadyReady) "正在启动…" else "正在准备运行环境…"
                startForeground(NOTIF_ID, buildNotification(initial, true, 0))

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

    private fun buildNotification(message: String, running: Boolean, progress: Int): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { action = Intent.ACTION_MAIN },
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenCode")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java).apply { action = Intent.ACTION_MAIN },
                PendingIntent.FLAG_IMMUTABLE
            ))
            .setOngoing(running)
        if (progress >= 0) builder.setProgress(100, progress, false)
        return builder.build()
    }

    private fun publish(
        phase: String, message: String, running: Boolean,
        url: String? = null, password: String? = null, gatewayUrl: String? = null,
        progress: Int = -1,
    ) {
        Companion.progressPct = progress
        Companion.progressMsg = message
        when (phase) {
            PHASE_RUNNING -> {
                Companion.running = true
                Companion.lastError = null
                Thread { fetchModelSummary() }.start()
                if (url != null) Companion.uiUrl = url
                if (password != null) Companion.uiPassword = password
                if (gatewayUrl != null) Companion.gatewayUrl = gatewayUrl
            }
            PHASE_STOPPED -> {
                Companion.running = false
            }
            PHASE_FAILED -> {
                Companion.running = false
                Companion.lastError = message
            }
        }
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(message, running, progress))
        sendBroadcast(Intent(ACTION_STATE).apply {
            putExtra(EXTRA_PHASE, phase)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_PROGRESS, progress)
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
                publish(PHASE_FAILED, "失败：${e.message ?: e.javaClass.simpleName}", false, progress = -1)
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
            Log.w("GatewayService", "read dns failed: ${e.message}")
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
        // 并发测速，取最快
        val results = mutableListOf<Pair<String, Long>>()
        val tasks = MIRRORS.map { prefix ->
            Thread {
                val url = prefix + ORIGINAL
                try {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 5000
                        readTimeout = 8000
                        setRequestProperty("Range", "bytes=0-1023")
                    }
                    val t0 = System.currentTimeMillis()
                    val code = conn.responseCode
                    val ms = System.currentTimeMillis() - t0
                    conn.disconnect()
                    if (code == 200 || code == 206) {
                        synchronized(results) { results.add(prefix to ms.toLong()) }
                        Log.i("GatewayService", "mirror '$prefix' -> $code in ${ms}ms")
                    }
                } catch (e: Exception) {
                    Log.w("GatewayService", "mirror '$prefix' failed: ${e.message}")
                }
            }.also { it.start() }
        }.also { threads ->
            threads.forEach { it.join(8000) } // 最多等 8 秒
        }
        // 选最快
        if (results.isNotEmpty()) {
            val (bestUrl, bestTime) = results.minByOrNull { it.second } ?: return ORIGINAL
            Log.i("GatewayService", "selected mirror: $bestUrl (${bestTime}ms)")
            return bestUrl + ORIGINAL
        }
        return ORIGINAL
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
        publish(PHASE_STARTING, "正在启动…", true, progress = 0)
        writeResolvConf(root)

        // 1) 同步项目文件到私有 workspace（app 无法直接访问 /sdcard）
        val ws = ProjectStore.workspaceDir(this)
        val projectUri = ProjectStore.load(this)
        if (projectUri != null) {
            publish(PHASE_STARTING, "正在同步项目文件…", true, progress = 0)
            val n = ProjectStore.syncToWorkspace(this, projectUri)
            Log.i("GatewayService", "project sync: $n files")
            if (n < 0) Log.w("GatewayService", "project sync failed, using existing workspace")
        }
        // 容器内 /workspace 指向私有目录
        val containerWs = File(root, "workspace")
        if (containerWs.exists() && containerWs.absolutePath != ws.absolutePath) {
            containerWs.deleteRecursively()
        }

        // VCS 兜底：OpenCode 打开项目时会调 /api/vcs/init，
        // 若容器内缺 git 或目录不是仓库，就会返回 503（UI 报 "VCS initialization failed"）。
        // 这里在容器里先跑一次 git init（缺 git 时静默跳过，不阻塞启动）。
        ensureGitRepo(root, ws)

        val password = (0..15).map { "0123456789abcdef"[(Math.random() * 16).toInt()] }.joinToString("")
        serverPassword = password
        Companion.apiKey = ""

        val prootBin = File(root, "proot/bin/proot").absolutePath
        val prootLib = File(root, "proot/lib").absolutePath
        val prootLoader = File(root, "proot/libexec/loader").absolutePath

        val cmd = buildList {
            add(prootBin)
            add("-r"); add(root.absolutePath)
            add("-b"); add("/dev")
            add("-b"); add("/proc")
            add("-b"); add("/sys")
            add("-b"); add("/dev/pts")
            add("-b"); add("${ws.absolutePath}:/workspace")
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
            publish(PHASE_FAILED, "服务启动失败，请查看日志", false, progress = -1)
            stopSelf()
            return
        }

        // 2) 起本地 OpenAI 兼容网关
        val token = android.util.Base64.encodeToString(
            "opencode:$password".toByteArray(), android.util.Base64.NO_WRAP
        )
        val gw = OpenAiGateway(
            listenPort = GATEWAY_PORT,
            opencodeBase = url,
            opencodeAuthToken = token,
            defaultModel = DEFAULT_MODEL,
            toolRunner = ToolRunner(this, ws),
            onStatus = { Log.i("GatewayService", "gateway: $it") },
        )
        gw.start()
        gateway = gw

        val gatewayUrl = "http://127.0.0.1:$GATEWAY_PORT/v1"
        publish(PHASE_RUNNING, "已就绪", true, url, password, gatewayUrl, progress = 100)
        Log.i("GatewayService", "READY ui=$url gateway=$gatewayUrl workspace=${ws.absolutePath}")

        // 3) 启动双向同步：监听 workspace 变化并写回 SAF 目录
        if (projectUri != null) {
            Thread { syncBackLoop(ws, projectUri) }.start()
        }
    }

    /**
     * 在容器内确保 /workspace 是一个 git 仓库。
     * 用 proot 执行，不依赖宿主；git 不存在时直接放弃（不影响启动）。
     * 同时写入一份 .gitignore，避免把缓存/构建产物纳入版本库。
     */
    private fun ensureGitRepo(root: File, ws: File) {
        try {
            // 宿主侧先放 .gitignore（走文件系统，不需要容器）
            val gi = File(ws, ".gitignore")
            if (!gi.exists()) {
                gi.writeText(
                    """
                    node_modules/
                    .cache/
                    dist/
                    build/
                    *.log
                    .DS_Store
                    """.trimIndent() + "\n"
                )
            }
            val dotGit = File(ws, ".git")
            if (dotGit.exists()) return

            val prootBin = File(root, "proot/bin/proot").absolutePath
            val prootLib = File(root, "proot/lib").absolutePath
            val prootLoader = File(root, "proot/libexec/loader").absolutePath
            if (!File(prootBin).exists()) return

            val cmd = listOf(
                prootBin, "-r", root.absolutePath,
                "-b", "/dev", "-b", "/proc", "-b", "/sys",
                "-b", "${ws.absolutePath}:/workspace",
                "-w", "/workspace",
                "/bin/sh", "-c",
                "command -v git >/dev/null 2>&1 || exit 127; " +
                    "git rev-parse --git-dir >/dev/null 2>&1 && exit 0; " +
                    "git init -q . && git config user.email ocgw@localhost && " +
                    "git config user.name OpenCode && git add -A >/dev/null 2>&1; " +
                    "git -c user.email=ocgw@localhost -c user.name=OpenCode commit -q -m 'init' >/dev/null 2>&1; " +
                    "exit 0"
            )
            val pb = ProcessBuilder(cmd).directory(root).redirectErrorStream(true)
            pb.environment().clear()
            pb.environment()["HOME"] = "/root"
            pb.environment()["PATH"] = "/usr/local/bin:/usr/bin:/bin"
            pb.environment()["TMPDIR"] = "/tmp"
            pb.environment()["LD_LIBRARY_PATH"] = prootLib
            pb.environment()["PROOT_LOADER"] = prootLoader
            pb.environment()["PROOT_TMP_DIR"] = cacheDir.absolutePath
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            val rc = p.waitFor()
            Log.i("GatewayService", "ensureGitRepo rc=$rc out=${out.take(200)}")
        } catch (e: Exception) {
            Log.w("GatewayService", "ensureGitRepo skipped: ${e.message}")
        }
    }

    /** 就绪判定：200 即可；401/403/500 也说明 HTTP 服务已起来，容错接受 */
    private fun waitForReady(url: String, password: String): Boolean {
        val encoded = android.util.Base64.encodeToString("opencode:$password".toByteArray(), android.util.Base64.NO_WRAP)
        for (i in 0 until 60) {
            if (stopping) return false
            try {
                val conn = (URL("$url/api/config?auth_token=$encoded").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 1500
                    readTimeout = 3000
                }
                val code = conn.responseCode
                conn.disconnect()
                Log.i("GatewayService", "ready probe -> $code")
                if (code == 200) return true
                // 401/403/500 说明 HTTP 服务已起来；容错
                if (code in intArrayOf(401, 403, 500) && i >= 2) {
                    Log.w("GatewayService", "server up but /api/config=$code, continue anyway")
                    return true
                }
            } catch (_: Exception) {}
            Thread.sleep(1000)
        }
        Log.w("GatewayService", "waitForReady timeout")
        return false
    }

    private fun downloadAndExtract(dest: File) {
        publish(PHASE_DOWNLOAD, "选择最快镜像…", true, progress = 0)
        val url = pickFastestMirror()
        Log.i("GatewayService", "download from $url")
        val tmp = File(cacheDir, "rootfs-arm64.tar.gz")

        val expect = remoteSize(url)
        if (tmp.exists() && expect > 0 && tmp.length() != expect) {
            Log.i("GatewayService", "cached size ${tmp.length()} != remote $expect, restart")
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
                                val pct = (done * 100 / total).toInt()
                                publish(PHASE_DOWNLOAD, "下载中 ${pct}%", true, progress = pct)
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
        publish(PHASE_EXTRACT, "正在解压…", true, progress = 0)

        val staging = File(filesDir, "rootfs.staging")
        staging.deleteRecursively()
        if (!staging.mkdirs() && !staging.exists()) throw RuntimeException("cannot create staging")

        val p = ProcessBuilder("tar", "-xzf", tmp.absolutePath, "-C", staging.absolutePath).start()
        val rc = p.waitFor()
        if (rc != 0) {
            val err = p.errorStream.bufferedReader().readText()
            staging.deleteRecursively()
            throw RuntimeException("解压失败($rc): $err")
        }
        if (!File(staging, "opencode/bin/opencode").exists()) {
            staging.deleteRecursively()
            throw RuntimeException("解压后缺少 opencode")
        }
        dest.deleteRecursively()
        if (!staging.renameTo(dest)) {
            staging.copyRecursively(dest, overwrite = true)
            staging.deleteRecursively()
        }
        Log.i("GatewayService", "rootfs ready at ${dest.absolutePath}")
    }

    /** 双向同步：每 3 秒检查 workspace mtime，有变化写回 SAF */
    private fun syncBackLoop(ws: File, uri: Uri) {
        var lastCheck = 0L
        while (!stopping) {
            try {
                Thread.sleep(3000)
                if (stopping) break
                // 只扫 mtime 变化的文件（简单粗暴：记录每个文件 mtime）
                val changed = mutableListOf<File>()
                fun walk(f: File) {
                    if (f.isDirectory) {
                        f.listFiles()?.forEach { walk(it) }
                    } else if (f.lastModified() > lastCheck) {
                        changed.add(f)
                    }
                }
                walk(ws)
                if (changed.isNotEmpty()) {
                    Log.i("GatewayService", "detected ${changed.size} changed files, syncing back...")
                    ProjectStore.syncFromWorkspace(this, uri, changed)
                }
                lastCheck = System.currentTimeMillis()
            } catch (_: Exception) {}
        }
    }

    private fun stopGateway() {
        stopping = true
        synchronized(lock) { busy = false }
        try { gateway?.stop() } catch (_: Exception) {}
        gateway = null
        // 先尝试正常 destroy
        try { proc?.destroy() } catch (_: Exception) {}
        proc = null
        // 再强制 kill 所有 proot/opencode 相关进程（防止残留）
        try {
            Runtime.getRuntime().exec("pkill -9 -f proot").waitFor()
            Runtime.getRuntime().exec("pkill -9 -f opencode").waitFor()
        } catch (_: Exception) {}
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        Companion.running = false
        Companion.uiUrl = null
        Companion.uiPassword = null
        Companion.gatewayUrl = null
        publish(PHASE_STOPPED, "已停止", false, progress = -1)
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