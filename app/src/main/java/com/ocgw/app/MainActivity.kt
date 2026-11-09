package com.ocgw.app

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File

/**
 * 单 Activity 双页面：主页（启动/停止/管理） + OpenCode Web UI。
 *
 * 为什么合并成一个 Activity：
 *   原来主页和 WebUI 是两个 Activity，点桌面图标会落到任务栈顶那个、另一个被清掉，
 *   表现为「有时进 WebUI、有时进主页」，而且 WebView 每次都要重新加载。
 *   现在两个页面常驻同一 Activity，只切 visibility，进程不死就不重载，
 *   并且记住你上次看的是哪一页，点图标回到那一页。
 *
 * WebUI 里的「网关」按钮：不是悬浮窗，而是注入到 WebUI 自己的导航栏里
 *   （header[data-slot=titlebar-v2]），复用它的 class，所以样式/尺寸/位置完全跟随，
 *   WebUI 设置里把导航栏切到顶部或底部，按钮都跟着走。
 *
 * 沉浸式：窗口 edge-to-edge，状态栏/导航栏透明，颜色取自页面自身背景，
 *   图标明暗自动跟随，内容不会被系统栏挡住。
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

    private var rootLayout: FrameLayout? = null
    private var homeView: View? = null
    private var webHost: FrameLayout? = null
    private var webView: WebView? = null
    private var webBar: ProgressBar? = null
    private var webToken: String? = null
    private var webLoaded = false
    private var showingWeb = false

    private var sbh = 0
    private var nbh = 0
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
        applyPalette()
        sbh = systemBarHeight("status_bar_height", 24)
        nbh = systemBarHeight("navigation_bar_height", 24)

        val root = FrameLayout(this).apply { setBackgroundColor(bg) }
        rootLayout = root
        homeView = buildPage()
        root.addView(homeView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)
        setupImmersive()

        ensureStoragePerm()
        refreshLoop()

        // 回到上次所在的页面：点桌面图标不会重载
        if (prefs().getBoolean(KEY_SHOWING_WEB, false)) showWeb(checkReady = false)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // 点桌面图标：什么都不做，保持当前页面和 WebView 状态
    }

    override fun onResume() {
        super.onResume()
        refreshProject()
        if (showingWeb && webView == null) showWeb(checkReady = false)
    }

    private fun prefs() = getSharedPreferences("ocgw", Context.MODE_PRIVATE)

    private fun ensureStoragePerm() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val need = ArrayList<String>()
        for (p in arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p)
        }
        if (need.isEmpty()) return
        try {
            requestPermissions(need.toTypedArray(), 1001)
        } catch (e: Exception) {
            Toast.makeText(this, "申请存储权限失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
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

    // ---------------- 沉浸式 ----------------

    private fun setupImmersive() {
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        var vis = window.decorView.systemUiVisibility
        vis = vis or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        window.decorView.systemUiVisibility = vis
        setBarIcons(light = !isNight())
    }

    /** light=true：栏下是浅色 → 图标用深色 */
    private fun setBarIcons(light: Boolean) {
        var vis = window.decorView.systemUiVisibility
        vis = if (light) vis or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        else vis and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vis = if (light) vis or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            else vis and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        }
        window.decorView.systemUiVisibility = vis
    }

    private fun systemBarHeight(name: String, fallbackDp: Int): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(fallbackDp)
    }

    // ---------------- 主页 UI ----------------

    private fun buildPage(): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = true; setBackgroundColor(bg)
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), sbh + dp(26), dp(24), nbh + dp(40))
        }

        val title = TextView(this).apply {
            text = "OpenCode Gateway"; textSize = 30f; setTextColor(ink)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val subtitle = TextView(this).apply {
            text = "本地网关 · 免费模型"; textSize = 14f; setTextColor(dim)
            setPadding(0, dp(6), 0, dp(18))
        }
        statusPill = TextView(this).apply {
            text = "●  正在启动…"; textSize = 15f; setTextColor(amber)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = pill(if (isNight()) Color.parseColor("#2A2E34") else Color.parseColor("#EEF0F3"))
        }

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

        val infoCard = card()
        infoCard.addView(sectionTitle("接入信息"))
        baseVal = addRow(infoCard, "Base URL", "http://127.0.0.1:4318/v1", true)
        keyVal = addRow(infoCard, "API Key", "无需填写", true)
        modelsVal = addRow(infoCard, "模型", "—", false)

        val actionCard = card()
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startBtn = pillBtn("启动", green) { startGateway() }
        stopBtn = pillBtn("停止", red) {
            startService(Intent(this@MainActivity, GatewayService::class.java).apply {
                action = GatewayService.ACTION_STOP
            })
            statusPill?.text = "●  已停止"; statusPill?.setTextColor(dim)
        }
        stopBtn?.alpha = 0.45f
        r1.addView(startBtn); r1.addView(space(dp(12))); r1.addView(stopBtn)
        actionCard.addView(r1)

        val r2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0)
        }
        r2.addView(pillBtn("管理", accent) { showWeb(true) })
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
    }

    private fun addRow(parent: LinearLayout, label: String, value: String, mono: Boolean): TextView {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, dp(10))
        }
        val l = TextView(this).apply { text = label; textSize = 13.5f; setTextColor(dim) }
        val v = TextView(this).apply {
            text = value; textSize = 13.5f; setTextColor(ink); gravity = Gravity.END
            isClickable = true
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
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

    private fun pillBtn(label: String, fill: Int, onClick: () -> Unit): TextView =
        pillBtn(label, fill, Color.WHITE, onClick)

    private fun space(px: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(px, 1) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun isNight() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    // ---------------- 页面切换 ----------------

    fun showHome() {
        showingWeb = false
        prefs().edit().putBoolean(KEY_SHOWING_WEB, false).apply()
        webHost?.visibility = View.GONE
        homeView?.visibility = View.VISIBLE
        setBarIcons(light = !isNight())
    }

    fun showWeb(checkReady: Boolean) {
        if (checkReady) {
            if (GatewayService.uiUrl == null || GatewayService.uiPassword == null) {
                Toast.makeText(this, "请先启动网关", Toast.LENGTH_SHORT).show()
                return
            }
        }
        showingWeb = true
        prefs().edit().putBoolean(KEY_SHOWING_WEB, true).apply()
        homeView?.visibility = View.GONE
        ensureWebHost()
        webHost?.visibility = View.VISIBLE
        setBarIcons(light = webLight)
        maybeLoad()
    }

    private var webLight = true

    private fun ensureWebHost() {
        if (webHost != null) return
        val root = rootLayout ?: return
        val host = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(if (isNight()) Color.parseColor("#202327") else Color.WHITE)
            // 顶部让出状态栏、底部让出导航栏；系统栏下面是 host 的背景色，
            // 而 host 背景色会被 JS 改成页面自身背景色 → 视觉上无缝，肉眼就是沉浸。
            setPadding(0, sbh, 0, nbh)
        }
        root.addView(host, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        webHost = host
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun maybeLoad() {
        if (webLoaded) return
        val url = GatewayService.uiUrl ?: return
        val pw = GatewayService.uiPassword ?: return
        val host = webHost ?: return

        val wv = WebView(this)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            progressDrawable = buildProgressDrawable()
        }
        host.addView(wv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        host.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(3)
        ))

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            // 禁止缩放：双指 / 双击 / 缩放控件 全关
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            mediaPlaybackRequiresUserGesture = false
        }
        wv.isScrollbarFadingEnabled = true
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.setBackgroundColor(if (isNight()) Color.parseColor("#202327") else Color.WHITE)
        wv.addJavascriptInterface(Bridge(), "OCGW")

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, u: String?) {
                bar.visibility = View.GONE
                inject(view)
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                bar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                bar.progress = newProgress
            }
        }

        val token = Base64.encodeToString("opencode:$pw".toByteArray(), Base64.NO_WRAP)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
            setCookie(url, "auth_token=$token")
            flush()
        }
        wv.loadUrl("$url/?auth_token=$token")
        webView = wv
        webBar = bar
        webToken = pw
        webLoaded = true
    }

    /** 给网页装 JS 桥：网页里的「网关」按钮回调这里 */
    inner class Bridge {
        @JavascriptInterface
        fun showHome() {
            runOnUiThread { this@MainActivity.showHome() }
        }

        @JavascriptInterface
        fun onBackground(css: String) {
            runOnUiThread { applyPageBackground(css) }
        }
    }

    /** 用页面真实背景色填充系统栏区域 + 决定状态栏图标明暗 */
    private fun applyPageBackground(css: String) {
        val c = parseColorSafe(css)
        webLight = if (c != null) Color.luminance(c) > 0.5f else !isNight()
        if (c != null) webHost?.setBackgroundColor(c)
        if (showingWeb) setBarIcons(webLight)
    }

    /**
     * 注入脚本：
     *  1) 禁止缩放（meta viewport + 手势拦截 + 双击拦截）
     *  2) 在 WebUI 自己的导航栏里插入「网关」按钮，复用它的 class，
     *     所以样式/尺寸一致，且导航栏切到顶部或底部它都跟着走。
     *  3) 把页面背景色回报给原生，用来给状态栏/导航栏上色。
     */
    private fun inject(view: WebView?) {
        val js = """
(function(){
  if (window.__ocgwInstalled) { window.__ocgwEnsure && window.__ocgwEnsure(); return; }
  window.__ocgwInstalled = true;

  // ---- 1) 禁止缩放 ----
  var vp='width=device-width, initial-scale=1.0, maximum-scale=1.0, minimum-scale=1.0, user-scalable=no, viewport-fit=cover';
  var m=document.querySelector('meta[name=viewport]');
  if(m){m.setAttribute('content',vp);}else{var x=document.createElement('meta');x.name='viewport';x.content=vp;document.head.appendChild(x);}
  var st=document.createElement('style');
  st.textContent='html{-webkit-text-size-adjust:100%!important;}';
  document.head.appendChild(st);
  document.addEventListener('gesturestart',function(e){e.preventDefault();},{passive:false});
  document.addEventListener('gesturechange',function(e){e.preventDefault();},{passive:false});
  document.addEventListener('gestureend',function(e){e.preventDefault();},{passive:false});
  document.addEventListener('touchmove',function(e){if(e.touches.length>1)e.preventDefault();},{passive:false});
  var _dbl=0;
  document.addEventListener('touchend',function(e){
    var t=Date.now();
    if(t-_dbl<320){e.preventDefault();}
    _dbl=t;
  },{passive:false});
  window.addEventListener('wheel',function(e){if(e.ctrlKey)e.preventDefault();},{passive:false});
  window.addEventListener('keydown',function(e){
    if((e.ctrlKey||e.metaKey)&&(e.key==='+'||e.key==='-'||e.key==='='||e.key==='0'))e.preventDefault();
  },true);

  // ---- 2) 导航栏里插入「网关」按钮 ----
  var SVG='<svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" style="flex-shrink:0"><rect x="2.25" y="2.25" width="11.5" height="11.5" rx="3.2"></rect><circle cx="8" cy="8" r="2.1" fill="currentColor" stroke="none"></circle></svg>';
  // 与 WebUI 导航栏按钮完全相同的 class
  var BTN_CLASS='group flex h-7 shrink-0 items-center gap-2 rounded-[6px] px-2 text-[13px] leading-4 text-v2-text-text-faint hover:text-v2-text-text-base';

  function ensure(){
    var hdr=document.querySelector('header[data-slot=titlebar-v2]');
    if(!hdr) return;
    if(hdr.querySelector('[data-ocgw-home]')) return;
    var b=document.createElement('button');
    b.type='button';
    b.setAttribute('data-ocgw-home','1');
    b.className=BTN_CLASS;
    b.innerHTML=SVG+'<span>网关</span>';
    b.addEventListener('click',function(e){
      e.preventDefault(); e.stopPropagation();
      try{ window.OCGW && window.OCGW.showHome(); }catch(_){}
    },true);
    hdr.appendChild(b);
  }
  window.__ocgwEnsure=ensure;
  ensure();

  // 页面重渲染后把按钮补回去
  var pending=false;
  var mo=new MutationObserver(function(){
    if(pending) return;
    pending=true;
    requestAnimationFrame(function(){ pending=false; ensure(); });
  });
  mo.observe(document.body,{childList:true,subtree:true});

  // ---- 3) 背景色回报 ----
  function report(){
    try{
      var a=getComputedStyle(document.body).backgroundColor;
      var b=getComputedStyle(document.documentElement).backgroundColor;
      var pick=function(s){ return s && s!=='rgba(0, 0, 0, 0)' && s!=='transparent'; };
      var c = pick(a)?a : (pick(b)?b : '');
      if(c && window.OCGW) window.OCGW.onBackground(c);
    }catch(e){}
  }
  setTimeout(report,60);
  setTimeout(report,600);
  var lastBg='';
  setInterval(function(){
    try{
      var a=getComputedStyle(document.body).backgroundColor;
      if(a!==lastBg){ lastBg=a; report(); }
    }catch(e){}
  },1200);
})();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    private fun parseColorSafe(s: String): Int? = try {
        if (s.startsWith("rgb")) {
            val nums = s.substringAfter('(').substringBefore(')').split(',')
                .map { it.trim().toFloatOrNull() ?: 0f }
            if (nums.size >= 3) Color.rgb(nums[0].toInt(), nums[1].toInt(), nums[2].toInt()) else null
        } else null
    } catch (_: Exception) { null }

    // ---------------- 行为 ----------------

    private fun startGateway() {
        val i = Intent(this, GatewayService::class.java).apply { action = GatewayService.ACTION_START }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        statusPill?.text = "●  启动中…"; statusPill?.setTextColor(amber)
    }

    private fun refreshLoop() {
        handler.postDelayed({
            val running = GatewayService.running
            val port = GatewayService.actualPort
            val err = GatewayService.lastError
            val pct = GatewayService.progressPct
            val msg = GatewayService.progressMsg

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
            keyVal?.text = "无需填写"
            modelsVal?.text = if (running) GatewayService.modelSummary.ifEmpty { "加载中…" } else "—"
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
            setOnClickListener { copyText(body) }
        }
        val sv = ScrollView(this).apply { addView(tv); isVerticalScrollBarEnabled = false }
        AlertDialog.Builder(this)
            .setTitle("运行日志（点文字复制）")
            .setView(sv)
            .setPositiveButton("关闭", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (showingWeb) { showHome(); return }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val KEY_SHOWING_WEB = "showing_web"
    }
}
