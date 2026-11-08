package com.ocgw.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
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
        Toast.makeText(this, "已选择目录，重启网关后生效", Toast.LENGTH_LONG).show()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != GatewayService.ACTION_STATE) return
            val phase = intent.getStringExtra(GatewayService.EXTRA_PHASE) ?: return
            val message = intent.getStringExtra(GatewayService.EXTRA_MESSAGE) ?: ""
            runOnUiThread {
                when (phase) {
                    GatewayService.PHASE_DOWNLOAD, GatewayService.PHASE_EXTRACT, GatewayService.PHASE_STARTING -> {
                        progress.visibility = ProgressBar.VISIBLE
                        status.text = message
                        setRunning(false)
                    }
                    GatewayService.PHASE_RUNNING -> {
                        progress.visibility = ProgressBar.GONE
                        status.text = getString(R.string.running)
                        uiUrl = intent.getStringExtra(GatewayService.EXTRA_URL)
                        uiPassword = intent.getStringExtra(GatewayService.EXTRA_PASSWORD)
                        gatewayUrl = intent.getStringExtra(GatewayService.EXTRA_GATEWAY)
                        setRunning(true)
                        gatewayInfo.text = buildString {
                            append("OpenAI 兼容网关（可直接给其他 app 用）\n")
                            append(gatewayUrl ?: "")
                            append("\n模型示例：").append(GatewayService.DEFAULT_MODEL)
                            append("\n\nPOST ").append(gatewayUrl ?: "").append("/chat/completions")
                        }
                        // 每次进入 RUNNING 都自动打开 Web UI（修复之前只开一次的问题）
                        openWebView()
                    }
                    GatewayService.PHASE_STOPPED -> {
                        progress.visibility = ProgressBar.GONE
                        status.text = getString(R.string.stopped)
                        setRunning(false)
                        uiUrl = null
                        uiPassword = null
                        gatewayUrl = null
                        gatewayInfo.text = ""
                    }
                    GatewayService.PHASE_FAILED -> {
                        progress.visibility = ProgressBar.GONE
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        startBtn = findViewById(R.id.startBtn)
        openBtn = findViewById(R.id.openBtn)
        stopBtn = findViewById(R.id.stopBtn)
        pickBtn = findViewById(R.id.pickBtn)
        projectPath = findViewById(R.id.projectPath)
        gatewayInfo = findViewById(R.id.gatewayInfo)

        status.text = getString(R.string.stopped)
        setRunning(false)
        refreshProjectLabel()

        startBtn.setOnClickListener { startServiceCompat(GatewayService.ACTION_START) }
        stopBtn.setOnClickListener { startServiceCompat(GatewayService.ACTION_STOP) }
        openBtn.setOnClickListener { openWebView() }
        pickBtn.setOnClickListener { pickDir.launch(null) }
    }

    private fun refreshProjectLabel() {
        val uri = ProjectStore.load(this)
        if (uri == null) {
            projectPath.text = getString(R.string.no_project)
            return
        }
        val path = ProjectStore.resolvePath(this, uri)
        projectPath.text = path ?: "已选择（无法解析为文件路径，可能不支持）"
    }

    private fun startServiceCompat(action: String) {
        val intent = Intent(this, GatewayService::class.java).apply { this.action = action }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun openWebView() {
        val url = uiUrl ?: return
        val password = uiPassword ?: return
        val wv = webView ?: WebView(this).apply {
            settings.domStorageEnabled = true
            settings.javaScriptEnabled = true
            settings.databaseEnabled = true
            webViewClient = WebViewClient()
        }.also { webView = it }

        val token = android.util.Base64.encodeToString("opencode:$password".toByteArray(), android.util.Base64.NO_WRAP)
        wv.loadUrl("$url/?auth_token=$token")
        if (!showingWeb) {
            setContentView(wv)
            showingWeb = true
        } else {
            setContentView(wv)
        }
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
            // 从 WebView 退回控制页
            showingWeb = false
            setContentView(R.layout.activity_main)
            status = findViewById(R.id.status)
            progress = findViewById(R.id.progress)
            startBtn = findViewById(R.id.startBtn)
            openBtn = findViewById(R.id.openBtn)
            stopBtn = findViewById(R.id.stopBtn)
            pickBtn = findViewById(R.id.pickBtn)
            projectPath = findViewById(R.id.projectPath)
            gatewayInfo = findViewById(R.id.gatewayInfo)
            status.text = getString(R.string.running)
            setRunning(true)
            refreshProjectLabel()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
