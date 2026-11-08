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
 * 对外：POST http://127.0.0.1:<port>/v1/chat/completions  以及 GET /v1/models
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
    private val pool = Executors.newFixedThreadPool(8)

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
            sock.soTimeout = 120_000
            val input = BufferedInputStream(sock.getInputStream())
            val out = sock.getOutputStream()

            val (method, path, headers, body) = readRequest(input)
            Log.i(TAG, "$method $path")

            when {
                method == "OPTIONS" -> writeResponse(out, 204, "{}")
                method == "GET" && path.startsWith("/v1/models") -> writeResponse(out, 200, modelsJson())
                method == "GET" && (path == "/" || path.startsWith("/health")) ->
                    writeResponse(out, 200, """{"ok":true,"service":"ocgw","opencode":"$opencodeBase"}""")
                method == "POST" && path.startsWith("/v1/chat/completions") ->
                    writeResponse(out, 200, chatCompletions(body))
                else -> writeResponse(out, 404, errorJson("not found: $method $path"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "handle error", e)
            try { writeResponse(sock.getOutputStream(), 500, errorJson(e.message ?: "error")) } catch (_: Exception) {}
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

    private fun writeResponse(out: OutputStream, code: Int, json: String) {
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

    /** 从 opencode 拿可用模型，失败则返回内置列表 */
    private fun listModels(): List<String> {
        val fallback = listOf(
            "$defaultModel",
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

    // ---------------- 转发到 opencode session ----------------

    private fun chatCompletions(body: String): String {
        val req = JSONObject(body)
        val modelStr = req.optString("model", defaultModel)
        val (providerID, modelID) = splitModel(modelStr)
        val prompt = buildPrompt(req.optJSONArray("messages"))
        if (prompt.isBlank()) return errorJson("messages is empty")

        return try {
            val sessionID = createSession(providerID, modelID)
            sendPrompt(sessionID, providerID, modelID, prompt)
            val text = awaitAssistantText(sessionID)
            completionJson(modelStr, text)
        } catch (e: Exception) {
            Log.e(TAG, "chatCompletions failed", e)
            errorJson(e.message ?: "upstream error")
        }
    }

    private fun splitModel(model: String): Pair<String, String> {
        val idx = model.indexOf('/')
        return if (idx > 0) model.substring(0, idx) to model.substring(idx + 1)
        else "opencode" to model
    }

    /** 把 OpenAI messages 拼成单个 prompt（opencode prompt 接口只收 text） */
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
        val data = JSONObject(text).optJSONObject("data") ?: throw RuntimeException("create session failed: $text")
        val id = data.optString("id")
        if (id.isEmpty()) throw RuntimeException("no session id: $text")
        return id
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

    /** 轮询会话消息，直到出现已完成的 assistant 文本 */
    private fun awaitAssistantText(sessionID: String): String {
        val deadline = System.currentTimeMillis() + 180_000
        var lastError: String? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                val conn = (URL("$opencodeBase/api/session/$sessionID/message?auth_token=$opencodeAuthToken").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 15000
                }
                val text = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val data = JSONObject(text).optJSONArray("data") ?: continue
                for (i in 0 until data.length()) {
                    val m = data.getJSONObject(i)
                    if (m.optString("type") != "assistant") continue
                    // 有 retry.error 说明上游失败
                    val retry = m.optJSONObject("retry")
                    if (retry != null) {
                        val err = retry.optJSONObject("error")
                        if (err != null) lastError = err.optString("message")
                    }
                    val content = m.optJSONArray("content") ?: continue
                    val sb = StringBuilder()
                    for (j in 0 until content.length()) {
                        val part = content.optJSONObject(j) ?: continue
                        if (part.optString("type") == "text") sb.append(part.optString("text"))
                    }
                    val finished = m.has("finish") && !m.isNull("finish")
                    if (finished && sb.isNotEmpty()) return sb.toString()
                    if (finished && sb.isEmpty() && lastError != null) {
                        throw RuntimeException(lastError ?: "upstream error")
                    }
                }
            } catch (e: RuntimeException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "poll failed: ${e.message}")
            }
            Thread.sleep(1200)
        }
        throw RuntimeException(lastError ?: "timeout waiting for assistant reply")
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
