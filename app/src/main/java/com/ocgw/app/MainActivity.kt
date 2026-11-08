package com.ocgw.app

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 主页：状态条 + 接入信息 + 启动/停止/管理/日志/选目录。
 * 进度用横向进度条 + 百分比。
 */
class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var statusDot: View
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var manageBtn: Button
    private lateinit var logBtn: Button
    private lateinit var pickBtn: Button
    private lateinit var baseUrl: TextView
    private lateinit var apiKey: TextView
    private lateinit var modelInfo: TextView
    private lateinit var projectPath: TextView

    private var uiUrl: String? = null
    private var uiPassword: String? = null
    private var gatewayUrl: String? = null
    private var webView: WebView? = null
    private var showingWeb = false
    private val io = Executors.newSingleThreadExecutor()

    private val pickDir = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {}
        ProjectStore.save(this, uri)
        refreshProjectLabel()
        Toast.makeText(this, "已选目录，下次启动会同步文件", Toast.LENGTH_SHORT).show()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != GatewayService.ACTION_STATE) return
            val phase = intent.getStringExtra(GatewayService.EXTRA_PHASE) ?: return
            val message = intent.getStringExtra(GatewayService.EXTRA_MESSAGE) ?: ""
            val pct = intent.getIntExtra(GatewayService.EXTRA_PROGRESS, -1)
            runOnUiThread {
                when (phase) {
                    GatewayService.PHASE_DOWNLOAD,
                    GatewayService.PHASE_EXTRACT,
                    GatewayService.PHASE_STARTING -> {
                        progress.visibility = View.VISIBLE
                        progressText.visibility = View.VISIBLE
                        if (pct >= 0) {
                            progress.isIndeterminate = false
                            progress.progress = pct
                            progressText.text = "$pct%  $message"
                        } else {
                            progress.isIndeterminate = true
                            progressText.text = message
                        }
                        setRunning(false)
                        setStatus("准备中 · $message", false)
                    }
                    GatewayService.PHASE_RUNNING -> {
                        progress.visibility = View.GONE
                        progressText.visibility = View.GONE
                        uiUrl = intent.getStringExtra(GatewayService.EXTRA_URL)
                        uiPassword = intent.getStringExtra(GatewayService.EXTRA_PASSWORD)
                        gatewayUrl = intent.getStringExtra(GatewayService.EXTRA_GATEWAY)
                        setRunning(true)
                        setStatus("运行中 · 端口 4318", true)
                        baseUrl.text = gatewayUrl ?: "http://127.0.0.1:4318/v1"
                        loadModelInfo()
                    }
                    GatewayService.PHASE_STOPPED -> {
                        progress.visibility = View.GONE
                        progressText.visibility = View.GONE
                        setRunning(false)
                        setStatus("未启动 · 端口 4318", false)
                        uiUrl = null; uiPassword = null; gatewayUrl = null
                        modelInfo.text = "启动后显示可用模型"
                    }
                    GatewayService.PHASE_FAILED -> {
                        progress.visibility = View.GONE
                        progressText.visibility = View.GONE
                        setRunning(false)
                        setStatus("启动失败", false)
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun setStatus(text: String, running: Boolean) {
        status.text = text
        statusDot.setBackgroundResource(
            if (running) R.drawable.dot_green else R.drawable.dot_gray
        )
        status.setTextColor(if (running) 0xFF16A34A.toInt() else 0xFF666666.toInt())
    }

    private fun setRunning(running: Boolean) {
        startBtn.isEnabled = !running
        stopBtn.isEnabled = running
        manageBtn.isEnabled = running
        pickBtn.isEnabled = !running
        startBtn.alpha = if (running) 0.5f else 1f
    }

    /** 从网关拉模型数量 */
    private fun loadModelInfo() {
        val gw = gatewayUrl ?: return
        io.execute {
            var text = "免费模型已就绪"
            try {
                val conn = (URL("$gw/models").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val arr = JSONObject(body).optJSONArray("data")
                if (arr != null && arr.length() > 0) {
                    val names = mutableListOf<String>()
                    for (i in 0 until minOf(arr.length(), 3)) {
                        names.add(arr.getJSONObject(i).optString("id").substringAfter('/'))
                    }
                    text = "${arr.length()} 个 · ${names.joinToString("、")} 等"
                }
            } catch (_: Exception) {}
            val finalText = text
            runOnUiThread { modelInfo.text = finalText }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        statusDot = findViewById(R.id.statusDot)
        progress = findViewById(R.id.progress)
        progressText = findViewById(R.id.progressText)
        startBtn = findViewById(R.id.startBtn)
        stopBtn = findViewById(R.id.stopBtn)
        manageBtn = findViewById(R.id.manageBtn)
        logBtn = findViewById(R.id.logBtn)
        pickBtn = findViewById(R.id.pickBtn)
        baseUrl = findViewById(R.id.baseUrl)
        apiKey = findViewById(R.id.apiKey)
        modelInfo = findViewById(R.id.modelInfo)
        projectPath = findViewById(R.id.projectPath)

        setStatus("未启动 · 端口 4318", false)
        setRunning(false)
        refreshProjectLabel()

        startBtn.setOnClickListener { startServiceCompat(GatewayService.ACTION_START) }
        stopBtn.setOnClickListener { startServiceCompat(GatewayService.ACTION_STOP) }
        manageBtn.setOnClickListener { openWebUI() }
        logBtn.setOnClickListener { showLog() }
        pickBtn.setOnClickListener { pickDir.launch(null) }
    }

    private fun showLog() {
        val f = java.io.File(cacheDir, "opencode.log")
        val text = if (f.exists()) {
            val lines = f.readLines()
            lines.takeLast(60).joinToString("\n").ifBlank { "（日志为空）" }
        } else "（还没有日志，先启动一次）"
        android.app.AlertDialog.Builder(this)
            .setTitle("运行日志（最后 60 行）")
            .setMessage(text)
            .setPositiveButton("关闭", null)
            .show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openWebUI() {
        val url = uiUrl ?: run {
            Toast.makeText(this, "还没就绪", Toast.LENGTH_SHORT).show()
            return
        }
        val password = uiPassword ?: return

        val wv = webView ?: WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(true)
                loadWithOverviewMode = true
                useWideViewPort = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
        }.also { webView = it }

        val token = android.util.Base64.encodeToString(
            "opencode:$password".toByteArray(), android.util.Base64.NO_WRAP
        )
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(wv, true)
        cm.setCookie(url, "auth_token=$token")
        cm.flush()

        wv.loadUrl("$url/?auth_token=$token")
        setContentView(wv)
        showingWeb = true
    }

    private fun refreshProjectLabel() {
        val uri = ProjectStore.load(this)
        projectPath.text = if (uri == null) {
            "未选择项目目录"
        } else {
            "项目：" + ProjectStore.displayName(this, uri)
        }
    }

    private fun startServiceCompat(action: String) {
        val intent = Intent(this, GatewayService::class.java).apply { this.action = action }
        ContextCompat.startForegroundService(this, intent)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(GatewayService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (showingWeb) {
            showingWeb = false
            setContentView(R.layout.activity_main)
            rebindViews()
            setRunning(uiUrl != null)
            setStatus(if (uiUrl != null) "运行中 · 端口 4318" else "未启动 · 端口 4318", uiUrl != null)
            refreshProjectLabel()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun rebindViews() {
        status = findViewById(R.id.status)
        statusDot = findViewById(R.id.statusDot)
        progress = findViewById(R.id.progress)
        progressText = findViewById(R.id.progressText)
        startBtn = findViewById(R.id.startBtn)
        stopBtn = findViewById(R.id.stopBtn)
        manageBtn = findViewById(R.id.manageBtn)
        logBtn = findViewById(R.id.logBtn)
        pickBtn = findViewById(R.id.pickBtn)
        baseUrl = findViewById(R.id.baseUrl)
        apiKey = findViewById(R.id.apiKey)
        modelInfo = findViewById(R.id.modelInfo)
        projectPath = findViewById(R.id.projectPath)

        startBtn.setOnClickListener { startServiceCompat(GatewayService.ACTION_START) }
        stopBtn.setOnClickListener { startServiceCompat(GatewayService.ACTION_STOP) }
        manageBtn.setOnClickListener { openWebUI() }
        logBtn.setOnClickListener { showLog() }
        pickBtn.setOnClickListener { pickDir.launch(null) }
        if (gatewayUrl != null) loadModelInfo()
    }

    override fun onDestroy() {
        io.shutdownNow()
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
