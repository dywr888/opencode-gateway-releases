package com.ocgw.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * 主页：极简三步
 *  1) 选项目目录（可选）
 *  2) 启动
 *  3) 自动进入聊天界面
 * 「Web UI」和「网关地址」收进高级区，默认不打扰用户。
 */
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
        Toast.makeText(this, "已选择目录，下次启动生效", Toast.LENGTH_SHORT).show()
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
                        status.text = "已就绪"
                        uiUrl = intent.getStringExtra(GatewayService.EXTRA_URL)
                        uiPassword = intent.getStringExtra(GatewayService.EXTRA_PASSWORD)
                        gatewayUrl = intent.getStringExtra(GatewayService.EXTRA_GATEWAY)
                        setRunning(true)
                        gatewayInfo.text = gatewayUrl ?: ""
                        // 自动进入聊天；Web UI 保留为高级入口
                        launchChat()
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
        startBtn.text = if (running) "运行中" else "启动"
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
        openBtn.setOnClickListener {
            val gw = gatewayUrl ?: return@setOnClickListener
            launchChat()
        }
        pickBtn.setOnClickListener { pickDir.launch(null) }
    }

    private fun launchChat() {
        val gw = gatewayUrl
        if (gw == null) {
            Toast.makeText(this, "还没就绪", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, ChatActivity::class.java).apply {
            putExtra(ChatActivity.EXTRA_GATEWAY, gw)
            putExtra(ChatActivity.EXTRA_MODEL, GatewayService.DEFAULT_MODEL)
        })
    }

    private fun refreshProjectLabel() {
        val uri = ProjectStore.load(this)
        if (uri == null) {
            projectPath.text = getString(R.string.no_project)
            return
        }
        val path = ProjectStore.resolvePath(this, uri)
        projectPath.text = path ?: "已选择（无法解析路径）"
    }

    private fun startServiceCompat(action: String) {
        val intent = Intent(this, GatewayService::class.java).apply { this.action = action }
        ContextCompat.startForegroundService(this, intent)
    }
}
