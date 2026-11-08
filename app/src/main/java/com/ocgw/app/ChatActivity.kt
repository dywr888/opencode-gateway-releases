package com.ocgw.app

import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 极简聊天界面：不碰 opencode 的 Web UI，直接调本地网关。
 * 用户只需要「输入 → 发送 → 看回复」。
 */
class ChatActivity : ComponentActivity() {

    private lateinit var messages: LinearLayout
    private lateinit var input: EditText
    private lateinit var sendBtn: Button
    private lateinit var scroll: ScrollView

    private val executor = Executors.newSingleThreadExecutor()
    private var gatewayBase: String? = null
    private var model: String = GatewayService.DEFAULT_MODEL

    companion object {
        const val EXTRA_GATEWAY = "gateway"
        const val EXTRA_MODEL = "model"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gatewayBase = intent.getStringExtra(EXTRA_GATEWAY)
        intent.getStringExtra(EXTRA_MODEL)?.let { model = it }

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
        }

        val title = TextView(this).apply {
            text = "OpenCode 助手"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(12))
            setTextColor(0xFF111111.toInt())
        }
        rootLayout.addView(title, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        messages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        scroll.addView(messages)
        rootLayout.addView(scroll)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(8), dp(10), dp(12))
        }
        input = EditText(this).apply {
            hint = "说点什么…"
            minLines = 1
            maxLines = 4
            setTextColor(0xFF111111.toInt())
            setHintTextColor(0xFF999999.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        sendBtn = Button(this).apply { text = "发送" }
        sendBtn.setOnClickListener { doSend() }
        bottom.addView(input)
        bottom.addView(sendBtn)
        rootLayout.addView(bottom)

        setContentView(rootLayout)

        if (gatewayBase == null) {
            addBubble("网关未就绪，请先回主页点「启动」。", true)
        } else {
            addBubble("你好，我可以帮你写代码、解释问题。直接把需求发给我就行。", true)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun bubbleLayout(fromAssistant: Boolean): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(6)
            gravity = if (fromAssistant) Gravity.START else Gravity.END
        }

    private fun makeBubble(text: String, fromAssistant: Boolean): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(if (fromAssistant) 0xFF222222.toInt() else 0xFFFFFFFF.toInt())
            setBackgroundColor(if (fromAssistant) 0xFFF1F3F5.toInt() else 0xFF4A7DFF.toInt())
            setPadding(dp(12), dp(9), dp(12), dp(9))
            maxWidth = (resources.displayMetrics.widthPixels * 0.82).toInt()
            textIsSelectable = true
        }

    private fun addBubble(text: String, fromAssistant: Boolean) {
        messages.addView(makeBubble(text, fromAssistant), bubbleLayout(fromAssistant))
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun doSend() {
        val text = input.text?.toString()?.trim().orEmpty()
        if (TextUtils.isEmpty(text)) return
        val base = gatewayBase ?: run {
            Toast.makeText(this, "网关未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        input.setText("")
        addBubble(text, false)

        val thinking = makeBubble("正在思考…", true).apply {
            setTextColor(0xFF888888.toInt())
        }
        val thinkingParams = bubbleLayout(true)
        messages.addView(thinking, thinkingParams)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

        sendBtn.isEnabled = false
        executor.execute {
            var reply: String
            try {
                reply = callGateway(base, text)
            } catch (e: Exception) {
                reply = "出错了：${e.message}"
            }
            runOnUiThread {
                messages.removeView(thinking)
                addBubble(reply, true)
                sendBtn.isEnabled = true
            }
        }
    }

    /** 调本地 OpenAI 兼容网关 */
    private fun callGateway(base: String, text: String): String {
        val payload = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", text)
            }))
        }
        val conn = (URL("$base/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 15000
            readTimeout = 200000
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray()) }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText() ?: ""
        conn.disconnect()

        if (code !in 200..299) return "出错($code)：${body.take(300)}"

        val json = JSONObject(body)
        val err = json.optJSONObject("error")
        if (err != null) return "出错：${err.optString("message")}"
        val choices = json.optJSONArray("choices") ?: return "无回复内容"
        if (choices.length() == 0) return "无回复内容"
        val msg = choices.getJSONObject(0).optJSONObject("message")
        return msg?.optString("content").orEmpty().ifBlank { "（空回复）" }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
