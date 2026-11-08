package com.ocgw.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var startBtn: Button
    private lateinit var openBtn: Button
    private lateinit var stopBtn: Button

    private var uiUrl: String? = null
    private var uiPassword: String? = null
    private var webView: WebView? = null

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
                        setRunning(true)
                        openWebView()
                    }
                    GatewayService.PHASE_STOPPED -> {
                        progress.visibility = ProgressBar.GONE
                        status.text = getString(R.string.stopped)
                        setRunning(false)
                        uiUrl = null
                        uiPassword = null
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        startBtn = findViewById(R.id.startBtn)
        openBtn = findViewById(R.id.openBtn)
        stopBtn = findViewById(R.id.stopBtn)

        status.text = getString(R.string.stopped)
        setRunning(false)

        startBtn.setOnClickListener { startServiceCompat(GatewayService.ACTION_START) }
        stopBtn.setOnClickListener { startServiceCompat(GatewayService.ACTION_STOP) }
        openBtn.setOnClickListener { openWebView() }
    }

    private fun startServiceCompat(action: String) {
        val intent = Intent(this, GatewayService::class.java).apply { this.action = action }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun openWebView() {
        val url = uiUrl ?: return
        val password = uiPassword ?: return
        val wv = WebView(this).apply {
            settings.domStorageEnabled = true
            settings.javaScriptEnabled = true
            webViewClient = WebViewClient()
        }
        webView = wv
        // opencode serve 支持 ?auth_token=base64(user:pass)
        val token = android.util.Base64.encodeToString("opencode:$password".toByteArray(), android.util.Base64.NO_WRAP)
        wv.loadUrl("$url/?auth_token=$token")
        setContentView(wv)
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

    override fun onBackPressed() {
        if (webView?.canGoBack() == true) webView?.goBack()
        else super.onBackPressed()
    }
}
