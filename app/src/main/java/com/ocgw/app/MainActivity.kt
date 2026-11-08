package com.ocgw.app

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
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

/**
 * 主页：选项目 → 启动 → 自动进入 Web UI（完整功能）。
 * WebView 优化：加载进度条、错误重试页、硬件加速、缓存、自动鉴权。
 */
class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var startBtn: Button
    private lateinit var openBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var pickBtn: Button
    private lateinit var projectPath: TextView
    private lateinit var gatewayInfo: TextView

    private var uiUrl: String? = null
    private var uiPassword: String? = null
    private var gatewayUrl: String? = null
    private var webView: WebView? = null
    private var showingWeb = false

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
                            progressText.text = "$pct%"
                        } else {
                            progress.isIndeterminate = true
                            progressText.text = message
                        }
                        status.text = message
                        setRunning(false)
                    }
                    GatewayService.PHASE_RUNNING -> {
                        progress.visibility = View.GONE
                        progressText.visibility = View.GONE
                        status.text = "已就绪"
                        uiUrl = intent.getStringExtra(GatewayService.EXTRA_URL)
                        uiPassword = intent.getStringExtra(GatewayService.EXTRA_PASSWORD)
                        gatewayUrl = intent.getStringExtra(GatewayService.EXTRA_GATEWAY)
                        setRunning(true)
                        gatewayInfo.text = gatewayUrl ?: ""
                        openWebUI()
                    }
                    GatewayService.PHASE_STOPPED -> {
                        progress.visibility = View.GONE
                        progressText.visibility = View.GONE
                        status.text = "已停止"
                        setRunning(false)
                        uiUrl = null; uiPassword = null; gatewayUrl = null
                        gatewayInfo.text = ""
                    }
                    GatewayService.PHASE_FAILED -> {
                        progress.visibility = View.GONE
                        progressText.visibility = View.GONE
                        status.text = message
                        setRunning(false)
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun setRunning(running: Boolean) {
        startBtn.isEnabled = !running
        openBtn.isEnabled = running
        stopBtn.isEnabled = running
        pickBtn.isEnabled = !running
        startBtn.text = if (running) "运行中" else "启动"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 锁定竖屏
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        progressText = findViewById(R.id.progressText)
        startBtn = findViewById(R.id.startBtn)
        openBtn = findViewById(R.id.openBtn)
        stopBtn = findViewById(R.id.stopBtn)
        pickBtn = findViewById(R.id.pickBtn)
        projectPath = findViewById(R.id.projectPath)
        gatewayInfo = findViewById(R.id.gatewayInfo)

        status.text = "未启动"
        progressText.text = ""
        progress.progress = 0
        progress.visibility = View.GONE
        progressText.visibility = View.GONE
        setRunning(false)
        refreshProjectLabel()

        startBtn.setOnClickListener { startServiceCompat(GatewayService.ACTION_START) }
        stopBtn.setOnClickListener { startServiceCompat(GatewayService.ACTION_STOP) }
        openBtn.setOnClickListener { openWebUI() }
        pickBtn.setOnClickListener { pickDir.launch(null) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openWebUI() {
        val url = uiUrl ?: return
        val password = uiPassword ?: return

        val wv = webView ?: WebView(this).apply {
            // 硬件加速
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = true
                supportMultipleWindows = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = false
                displayZoomControls = false
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                textZoom = 100
                // 允许 file 协议 / 本地资源（opencode 有内部接口）
                setGeolocationEnabled(false)
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    // 页面开始加载
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    // 页面加载完成
                }
                @SuppressLint("WebViewClientOnReceivedError")
                override fun onReceivedError(
                    view: WebView?, request: WebResourceRequest?, error: WebResourceError?
                ) {
                    // 只处理主框架错误
                    if (request?.isForMainFrame == true && error != null) {
                        showWebError(error.description?.toString() ?: "加载失败")
                    }
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val u = request?.url?.toString() ?: return false
                    if (u.startsWith("http")) {
                        view?.loadUrl(u)
                        return true
                    }
                    return false
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    // 用系统返回键提示不做了，静默
                }
            }

            // 全屏沉浸
            systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }.also { webView = it }

        // Cookie 自动鉴权
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

    private fun showWebError(detail: String) {
        // 简单弹提示，让用户返回重试
        runOnUiThread {
            Toast.makeText(this, "页面加载失败：$detail\n可点「打开 Web UI」重试", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshProjectLabel() {
        val uri = ProjectStore.load(this)
        projectPath.text = if (uri == null) {
            getString(R.string.no_project)
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
        if (showingWeb && webView?.canGoBack() == true) {
            webView?.goBack()
        } else if (showingWeb) {
            showingWeb = false
            setContentView(R.layout.activity_main)
            status = findViewById(R.id.status)
            progress = findViewById(R.id.progress)
            progressText = findViewById(R.id.progressText)
            startBtn = findViewById(R.id.startBtn)
            openBtn = findViewById(R.id.openBtn)
            stopBtn = findViewById(R.id.stopBtn)
            pickBtn = findViewById(R.id.pickBtn)
            projectPath = findViewById(R.id.projectPath)
            gatewayInfo = findViewById(R.id.gatewayInfo)
            status.text = "已就绪"
            setRunning(true)
            refreshProjectLabel()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
