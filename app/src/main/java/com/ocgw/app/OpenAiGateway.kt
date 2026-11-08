package com.ocgw.app

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地 OpenAI 兼容网关。
 *
 * 对外：
 *   POST http://127.0.0.1:<port>/v1/chat/completions   (支持 stream=true 的 SSE)
 *   GET  http://127.0.0.1:<port>/v1/models
 *   GET  http://127.0.0.1:<port>/health
 *
 * 对内：转发到 opencode serve 的 session 路径
 *   - POST /api/session            建会话
 *   - POST /api/session/:id/prompt 发消息
 *   - GET  /api/session/:id/message 轮询直到 assistant 完成
 *
 * 注意：不能用 /api/experimental/generate/text——它无 OpenCode 客户端身份，
 * 免费模型会直接 429（实测）。
 */
class OpenAiGateway(
    private val listenPort: Int,
    private val opencodeBase: String,
    private val opencodeAuthToken: String,
    private val defaultModel: String,
    private val onStatus: (String) -> Unit,
) {
    private var server: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool = Executors.newFixedThreadPool(16)

    companion object {
        private const val TAG = "OpenAiGateway"
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread {
            try {
                val ss = ServerSocket(listenPort, 16, InetAddress.getByName("127.0.0.1"))
                server = ss
                Log.i(TAG, "gateway listening on 127.0.0.1:$listenPort")
                onStatus("网关已启动 · 127.0.0.1:$listenPort")
                while (running.get()) {
                    val sock = try { ss.accept() } catch (_: Exception) { break }
                    pool.execute { handle(sock) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "gateway server error", e)
                onStatus("网关启动失败: ${e.message}")
            } finally {
                running.set(false)
            }
        }.start()
    }

    fun stop() {
        running.set(false)
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    // ---------------- HTTP ----------------

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 300_000
            val input = BufferedInputStream(sock.getInputStream())
            val out = sock.getOutputStream()

            val req = readRequest(input)
            Log.i(TAG, "${req.method} ${req.path}")

            when {
                req.method == "OPTIONS" -> writeJson(out, 204, "{}")
                req.method == "GET" && req.path.startsWith("/v1/models") ->
                    writeJson(out, 200, modelsJson())
                req.method == "GET" && (req.path == "/" || req.path.startsWith("/health")) ->
                    writeJson(out, 200, """{"ok":true,"service":"ocgw","opencode":"$opencodeBase"}""")
                req.method == "POST" && req.path.startsWith("/v1/chat/completions") ->
                    handleChat(out, req.body)
                else -> writeJson(out, 404, errorJson("not found: ${req.method} ${req.path}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "handle error", e)
            try { writeJson(sock.getOutputStream(), 500, errorJson(e.message ?: "error")) } catch (_: Exception) {}
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private data class Req(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private fun readRequest(input: BufferedInputStream): Req {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) break
            sb.append(c.toChar())
            if (sb.endsWith("\r\n\r\n")) break
        }
        val lines = sb.toString().split("\r\n")
        val first = lines.firstOrNull() ?: ""
        val parts = first.split(" ")
        val method = parts.getOrElse(0) { "" }
        val path = parts.getOrElse(1) { "/" }

        val headers = mutableMapOf<String, String>()
        for (i in 1 until lines.size) {
            val l = lines[i]
            if (l.isEmpty()) break
            val idx = l.indexOf(':')
            if (idx > 0) headers[l.substring(0, idx).trim().lowercase()] = l.substring(idx + 1).trim()
        }

        val len = headers["content-length"]?.toLongOrNull() ?: 0L
        val body = if (len > 0) {
            val bytes = ByteArray(len.toInt())
            var off = 0
            while (off < bytes.size) {
                val r = input.read(bytes, off, bytes.size - off)
                if (r <= 0) break
                off += r
            }
            String(bytes, 0, off, Charsets.UTF_8)
        } else ""
        return Req(method, path, headers, body)
    }

    private fun writeJson(out: OutputStream, code: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(" ").append(statusText(code)).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun statusText(c: Int) = when (c) {
        200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"
        404 -> "Not Found"; 500 -> "Internal Server Error"; else -> "OK"
    }

    private fun errorJson(msg: String) =
        """{"error":{"message":${JSONObject.quote(msg)},"type":"gateway_error"}}"""

    private fun modelsJson(): String {
        val arr = JSONArray()
        for (m in listModels()) {
            arr.put(JSONObject().apply {
                put("id", m)
                put("object", "model")
                put("owned_by", "opencode")
            })
        }
        return JSONObject().put("object", "list").put("data", arr).toString()
    }

    private fun listModels(): List<String> {
        val fallback = listOf(
            defaultModel,
            "opencode/big-pickle",
            "opencode/deepseek-v4.1-flash-free",
            "opencode/glm-5.3-flash-free",
        ).distinct()
        return try {
            val conn = (URL("$opencodeBase/api/model?auth_token=$opencodeAuthToken").openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
            }
            val text = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val data = JSONObject(text).optJSONArray("data") ?: return fallback
            val list = mutableListOf<String>()
            for (i in 0 until data.length()) {
                val m = data.getJSONObject(i)
                val pid = m.optString("providerID")
                val mid = m.optString("modelID")
                if (pid.isNotEmpty() && mid.isNotEmpty()) list.add("$pid/$mid")
            }
            if (list.isEmpty()) fallback else list
        } catch (e: Exception) {
            Log.w(TAG, "listModels failed: ${e.message}")
            fallback
        }
    }

    // ---------------- 聊天处理（支持流式） ----------------

    private fun handleChat(out: OutputStream, body: String) {
        val req = JSONObject(body)
        val modelStr = req.optString("model", defaultModel)
        val (providerID, modelID) = splitModel(modelStr)
        val prompt = buildPrompt(req.optJSONArray("messages"))
        val stream = req.optBoolean("stream", false)

        if (prompt.isBlank()) {
            writeJson(out, 400, errorJson("messages is empty"))
            return
        }

        try {
            val sessionID = createSession(providerID, modelID)
            sendPrompt(sessionID, providerID, modelID, prompt)

            if (stream) {
                streamChat(out, sessionID, modelStr)
            } else {
                val text = awaitAssistantText(sessionID)
                writeJson(out, 200, completionJson(modelStr, text))
            }
        } catch (e: Exception) {
            Log.e(TAG, "chat failed", e)
            writeJson(out, 500, errorJson(e.message ?: "upstream error"))
        }
    }

    /** SSE 流式：边轮询边输出 */
    private fun streamChat(out: OutputStream, sessionID: String, modelStr: String) {
        val id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)
        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/event-stream; charset=utf-8\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Connection: close\r\n")
            append("Access-Control-Allow-Origin: *\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.UTF_8))
        out.flush()

        var sentLen = 0
        var finished = false
        val deadline = System.currentTimeMillis() + 180_000

        while (!finished && System.currentTimeMillis() < deadline) {
            val text = try {
                pollAssistantText(sessionID)
            } catch (e: Exception) {
                Log.w(TAG, "stream poll failed: ${e.message}")
                ""
            }
            if (text != null) {
                if (text.length > sentLen) {
                    val delta = text.substring(sentLen)
                    sentLen = text.length
                    val chunk = JSONObject().apply {
                        put("id", id)
                        put("object", "chat.completion.chunk")
                        put("created", System.currentTimeMillis() / 1000)
                        put("model", modelStr)
                        put("choices", JSONArray().put(JSONObject().apply {
                            put("index", 0)
                            put("delta", JSONObject().apply { put("content", delta) })
                            put("finish_reason", null)
                        }))
                    }
                    out.write(("data: " + chunk.toString() + "\n\n").toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                // 判断完成
                finished = isAssistantFinished(sessionID)
            }
            if (!finished) Thread.sleep(800)
        }

        // 结束标记
        val done = JSONObject().apply {
            put("id", id)
            put("object", "chat.completion.chunk")
            put("created", System.currentTimeMillis() / 1000)
            put("model", modelStr)
            put("choices", JSONArray().put(JSONObject().apply {
                put("index", 0)
                put("delta", JSONObject())
                put("finish_reason", "stop")
            }))
        }
        out.write(("data: " + done.toString() + "\n\n").toByteArray(Charsets.UTF_8))
        out.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun splitModel(model: String): Pair<String, String> {
        val idx = model.indexOf('/')
        return if (idx > 0) model.substring(0, idx) to model.substring(idx + 1)
        else "opencode" to model
    }

    private fun buildPrompt(messages: JSONArray?): String {
        if (messages == null) return ""
        val sb = StringBuilder()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val role = m.optString("role", "user")
            val content = m.opt("content")
            val text = when (content) {
                is String -> content
                is JSONArray -> buildString {
                    for (j in 0 until content.length()) {
                        val part = content.optJSONObject(j) ?: continue
                        if (part.optString("type") == "text") append(part.optString("text"))
                    }
                }
                else -> ""
            }
            when (role) {
                "system" -> sb.append("[system]\n").append(text).append("\n\n")
                "assistant" -> sb.append("[assistant]\n").append(text).append("\n\n")
                else -> sb.append(text).append("\n\n")
            }
        }
        return sb.toString().trim()
    }

    private fun createSession(providerID: String, modelID: String): String {
        val conn = (URL("$opencodeBase/api/session?auth_token=$opencodeAuthToken").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 10000
            readTimeout = 20000
        }
        conn.outputStream.use { it.write("{}".toByteArray()) }
        val text = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        val data = JSONObject(text).optJSONObject("data") ?: throw RuntimeException("create session failed")
        return data.optString("id").ifEmpty { throw RuntimeException("no session id") }
    }

    private fun sendPrompt(sessionID: String, providerID: String, modelID: String, text: String) {
        val payload = JSONObject().apply {
            put("model", JSONObject().apply {
                put("providerID", providerID)
                put("modelID", modelID)
            })
            put("text", text)
        }
        val conn = (URL("$opencodeBase/api/session/$sessionID/prompt?auth_token=$opencodeAuthToken").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 10000
            readTimeout = 30000
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray()) }
        val code = conn.responseCode
        val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
        conn.disconnect()
        if (code !in 200..299) throw RuntimeException("prompt failed($code): $resp")
    }

    /** 返回 assistant 文本（未完成返回 null 表示还在生成） */
    private fun pollAssistantText(sessionID: String): String? {
        return try {
            val data = fetchMessages(sessionID)
            for (i in 0 until data.length()) {
                val m = data.getJSONObject(i)
                if (m.optString("type") != "assistant") continue
                val content = m.optJSONArray("content") ?: continue
                val sb = StringBuilder()
                for (j in 0 until content.length()) {
                    val part = content.optJSONObject(j) ?: continue
                    if (part.optString("type") == "text") sb.append(part.optString("text"))
                }
                if (sb.isNotEmpty()) return sb.toString()
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun isAssistantFinished(sessionID: String): Boolean {
        return try {
            val data = fetchMessages(sessionID)
            for (i in 0 until data.length()) {
                val m = data.getJSONObject(i)
                if (m.optString("type") == "assistant" && m.has("finish") && !m.isNull("finish")) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun fetchMessages(sessionID: String): JSONArray {
        val conn = (URL("$opencodeBase/api/session/$sessionID/message?auth_token=$opencodeAuthToken").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 15000
        }
        val text = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        return JSONObject(text).optJSONArray("data") ?: JSONArray()
    }

    /** 非流式：轮询直到 assistant 完成 */
    private fun awaitAssistantText(sessionID: String): String {
        val deadline = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < deadline) {
            val text = pollAssistantText(sessionID)
            if (text != null && text.isNotEmpty() && isAssistantFinished(sessionID)) {
                return text
            }
            Thread.sleep(1000)
        }
        throw RuntimeException("timeout waiting for assistant reply")
    }

    private fun completionJson(model: String, text: String): String {
        val obj = JSONObject().apply {
            put("id", "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24))
            put("object", "chat.completion")
            put("created", System.currentTimeMillis() / 1000)
            put("model", model)
            put("choices", JSONArray().put(JSONObject().apply {
                put("index", 0)
                put("message", JSONObject().apply {
                    put("role", "assistant")
                    put("content", text)
                })
                put("finish_reason", "stop")
            }))
        }
        return obj.toString()
    }
}
