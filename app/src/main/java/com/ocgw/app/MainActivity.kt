package com.ocgw.app

import androidx.activity.ComponentActivity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject
import java.io.File

/**
 * 主页 —— 风格与 OurFreeModel 网关一致：
 * 标题 / 副标题 / 状态胶囊 / 接入信息卡片 / 按钮组，1.5 秒轮询刷新。
 */
class MainActivity : ComponentActivity() {

    private var bg = 0
    private var cardBg = 0
    private var strokeC = 0
    private var ink = 0
    private var dim = 0
    private val green = Color.parseColor("#34C759")
    private val red = Color.parseColor("#FF453A")
    private val amber = Color.parseColor("#E0A030")
    private val accent = Color.parseColor("#4F7CFF")

    private var statusPill: TextView? = null
    private var baseVal: TextView? = null
    private var keyVal: TextView? = null
    private var modelsVal: TextView? = null
    private var progressBar: ProgressBar? = null
    private var progressLabel: TextView? = null
    private var projectVal: TextView? = null
    private var startBtn: TextView? = null
    private var stopBtn: TextView? = null

    private var sbh = 0
    private val handler = Handler(Looper.getMainLooper())

    private val pickDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {}
        ProjectStore.save(this, uri)
        refreshProject()
        Toast.makeText(this, "已选目录，重启网关后生效", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        applyPalette()
        sbh = statusBarHeight()
        applyBars(bg)
        setContentView(buildPage())
        refreshLoop()
    }

    private fun applyPalette() {
        if (isNight()) {
            bg = Color.parseColor("#202327"); cardBg = Color.parseColor("#2A2E34")
            strokeC = Color.parseColor("#3A3F45"); ink = Color.parseColor("#ECEDEE"); dim = Color.parseColor("#9AA0A6")
        } else {
            bg = Color.WHITE; cardBg = Color.parseColor("#F5F6F8")
            strokeC = Color.parseColor("#E4E6EA"); ink = Color.parseColor("#16181D"); dim = Color.parseColor("#6B7075")
        }
    }

    // ---------------- UI 构建 ----------------

    private fun buildPage(): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = true; setBackgroundColor(bg)
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), sbh + dp(26), dp(24), dp(40))
        }

        val title = TextView(this).apply {
            text = "OpenCode Gateway"; textSize = 30f; setTextColor(ink)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            isLongClickable = false; setTextIsSelectable(false)
        }
        val subtitle = TextView(this).apply {
            text = "本地网关 · 免费模型"; textSize = 14f; setTextColor(dim)
            setPadding(0, dp(6), 0, dp(18)); isLongClickable = false; setTextIsSelectable(false)
        }
        statusPill = TextView(this).apply {
            text = "●  正在启动…"; textSize = 15f; setTextColor(amber)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = pill(if (isNight()) Color.parseColor("#2A2E34") else Color.parseColor("#EEF0F3"))
            isLongClickable = false; setTextIsSelectable(false)
        }

        // 进度条（仅下载/解压/启动时显示）
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0
            progressDrawable = buildProgressDrawable()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(8)
            ).apply { topMargin = dp(14) }
            visibility = View.GONE
        }
        progressLabel = TextView(this).apply {
            textSize = 12f; setTextColor(dim); gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
            visibility = View.GONE
        }

        // 接入信息卡片
        val infoCard = card()
        infoCard.addView(sectionTitle("接入信息"))
        baseVal = addRow(infoCard, "Base URL", "http://127.0.0.1:4318/v1", true)
        keyVal = addRow(infoCard, "API Key", "（启动后生成）", true)
        modelsVal = addRow(infoCard, "模型", "—", false)

        // 按钮卡片
        val actionCard = card()
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startBtn = pillBtn("启动", green) { startGateway() }
        stopBtn = pillBtn("停止", red) {
            stopService(Intent(this@MainActivity, GatewayService::class.java))
            statusPill?.text = "●  已停止"; statusPill?.setTextColor(dim)
        }
        stopBtn?.alpha = 0.45f
        r1.addView(startBtn); r1.addView(space(dp(12))); r1.addView(stopBtn)
        actionCard.addView(r1)

        val r2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0)
        }
        r2.addView(pillBtn("管理", accent) { openWeb() })
        r2.addView(space(dp(10)))
        r2.addView(pillBtn("日志", Color.parseColor(if (isNight()) "#3A3F45" else "#E1E3E7"), ink) { showLog() })
        r2.addView(space(dp(10)))
        r2.addView(pillBtn("选目录", Color.parseColor(if (isNight()) "#3A3F45" else "#E1E3E7"), ink) { pickDir.launch(null) })
        actionCard.addView(r2)

        projectVal = TextView(this).apply {
            textSize = 12f; setTextColor(dim); gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }

        col.addView(title); col.addView(subtitle); col.addView(statusPill)
        col.addView(progressBar); col.addView(progressLabel)
        col.addView(infoCard); col.addView(actionCard); col.addView(projectVal)
        scroll.addView(col)
        return scroll
    }

    private fun buildProgressDrawable() = android.graphics.drawable.LayerDrawable(arrayOf(
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(4).toFloat()
            setColor(Color.parseColor("#E8EAED"))
        },
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(4).toFloat()
            setColor(accent)
        }
    )).apply { setId(0, android.R.id.background); setId(1, android.R.id.progress) }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(dim); setPadding(0, 0, 0, dp(8))
        isLongClickable = false; setTextIsSelectable(false)
    }

    private fun addRow(parent: LinearLayout, label: String, value: String, mono: Boolean): TextView {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, dp(10))
        }
        val l = TextView(this).apply {
            text = label; textSize = 13.5f; setTextColor(dim)
            isLongClickable = false; setTextIsSelectable(false)
        }
        val v = TextView(this).apply {
            text = value; textSize = 13.5f; setTextColor(ink); gravity = Gravity.END
            isLongClickable = false; setTextIsSelectable(false); isClickable = true
            if (mono) typeface = Typeface.MONOSPACE
        }
        l.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        v.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.8f)
        r.addView(l); r.addView(v); parent.addView(r)
        v.setOnClickListener { copyText(v.text?.toString().orEmpty()) }
        return v
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = box(cardBg, dp(20).toFloat(), strokeC)
        setPadding(dp(20), dp(16), dp(20), dp(16))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) }
    }

    private fun pill(fill: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = dp(40).toFloat(); setColor(fill)
    }

    private fun box(fill: Int, radius: Float, stroke: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = radius; setColor(fill)
        setStroke(maxOf(1, dp(1)), stroke)
    }

    private fun pillBtn(label: String, fill: Int, textColor: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label; textSize = 15f; setTextColor(textColor)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER; setPadding(dp(18), dp(12), dp(18), dp(12))
            background = pill(fill); setOnClickListener { onClick() }
            isLongClickable = false; setTextIsSelectable(false)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

    private fun pillBtn(label: String, fill: Int, onClick: () -> Unit): TextView =
        pillBtn(label, fill, Color.WHITE, onClick)

    private fun space(px: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(px, 1) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun isNight() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun applyBars(color: Int) {
        window.statusBarColor = color
        window.navigationBarColor = color
        var vis = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        val light = Color.luminance(color) > 0.5f
        vis = if (light) vis or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        else vis and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vis = if (light) vis or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            else vis and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        }
        window.decorView.systemUiVisibility = vis
    }

    // ---------------- 行为 ----------------

    private fun startGateway() {
        val i = Intent(this, GatewayService::class.java).apply { action = GatewayService.ACTION_START }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        statusPill?.text = "●  启动中…"; statusPill?.setTextColor(amber)
    }

    /** 1.5 秒轮询刷新（照 OurFreeModel） */
    private fun refreshLoop() {
        handler.postDelayed({
            val running = GatewayService.running
            val port = GatewayService.actualPort
            val err = GatewayService.lastError
            val pct = GatewayService.progressPct
            val msg = GatewayService.progressMsg
            val key = GatewayService.apiKey

            if (running) {
                statusPill?.text = "●  运行中 · 端口 $port"
                statusPill?.setTextColor(green)
                progressBar?.visibility = View.GONE
                progressLabel?.visibility = View.GONE
            } else if (err != null) {
                statusPill?.text = "●  启动失败（点日志查看）"
                statusPill?.setTextColor(red)
                progressBar?.visibility = View.GONE
                progressLabel?.visibility = View.GONE
            } else if (pct >= 0 || msg.isNotEmpty()) {
                statusPill?.text = "●  准备中…"
                statusPill?.setTextColor(amber)
                progressBar?.visibility = View.VISIBLE
                progressLabel?.visibility = View.VISIBLE
                if (pct >= 0) {
                    progressBar?.isIndeterminate = false
                    progressBar?.progress = pct
                    progressLabel?.text = "$pct%  $msg"
                } else {
                    progressBar?.isIndeterminate = true
                    progressLabel?.text = msg
                }
            } else {
                statusPill?.text = "●  未启动"
                statusPill?.setTextColor(dim)
                progressBar?.visibility = View.GONE
                progressLabel?.visibility = View.GONE
            }

            baseVal?.text = "http://127.0.0.1:$port/v1"
            keyVal?.text = if (key.isEmpty()) "（启动后生成）" else key
            if (running) {
                modelsVal?.text = GatewayService.modelSummary.ifEmpty { "加载中…" }
            } else {
                modelsVal?.text = "—"
            }
            startBtn?.alpha = if (running) 0.45f else 1f
            stopBtn?.alpha = if (running) 1f else 0.45f

            refreshLoop()
        }, 1500)
    }

    private fun refreshProject() {
        val uri = ProjectStore.load(this)
        projectVal?.text = if (uri == null) "未选择项目目录"
        else "项目：" + ProjectStore.displayName(this, uri)
    }

    private fun openWeb() {
        val url = GatewayService.uiUrl
        val pw = GatewayService.uiPassword
        if (url == null || pw == null) {
            Toast.makeText(this, "请先启动网关", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, WebViewActivity::class.java)
            .putExtra("url", url)
            .putExtra("password", pw))
    }

    private fun copyText(s: String) {
        if (s.isEmpty()) return
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("ocgw", s))
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
    }

    private fun showLog() {
        val f = File(cacheDir, "opencode.log")
        val body = try {
            if (f.exists()) f.readText().lineSequence().toList().takeLast(200).joinToString("\n")
            else "暂无日志"
        } catch (e: Exception) { "读取失败：${e.message}" }

        val tv = TextView(this).apply {
            text = body; textSize = 11.5f
            setTextColor(if (isNight()) Color.parseColor("#D4D6DA") else Color.parseColor("#2A2D33"))
            typeface = Typeface.MONOSPACE
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isLongClickable = false
            setOnClickListener { copyText(body) }
        }
        val sv = ScrollView(this).apply { addView(tv); isVerticalScrollBarEnabled = false }
        AlertDialog.Builder(this)
            .setTitle("运行日志（点文字复制）")
            .setView(sv)
            .setPositiveButton("关闭", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshProject()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
