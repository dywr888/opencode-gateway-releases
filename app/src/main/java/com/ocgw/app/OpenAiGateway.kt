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
 * OpenAI 兼容网关（走 OpenCode 二进制的正规 agent 流水线）
 *
 * 实测结论：
 *   OpenCode 的 prompt 接口 body = { id, text, files, agents, skills, metadata, delivery }
 *   **没有顶层 model 字段**。只有 metadata.agent = "build" 且 metadata.model = {providerID, modelID}
 *   且 delivery = "queue" 时，请求才走正规 agent 流水线，免费额度才生效；
 *   否则走「裸调用」遗留路径，被 opencode.ai 判为非 OpenCode 客户端使用 -> 429 限流
 *   （表现就是模型回一个空的 []）。
 */
class OpenAiGateway(
    private val listenPort: Int,
    private val opencodeBase: String,
    private val opencodeAuthToken: String,
    private val defaultModel: String,
    private val toolRunner: ToolRunner? = null,
    private val onStatus: (String) -> Unit = {},
) {
    private var server: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool = Executors.newFixedThreadPool(6)
    private val writeLock = Any()

    companion object {
        private const val TAG = "OpenAiGateway"
        private const val AGENT = "build"
        private const val POLL_MS = 500L
        private const val TURN_TIMEOUT_MS = 300_000L
        private const val RATE_RETRY_MS = 20_000L
        private const val RATE_TRIES = 4
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread {
            try {
                val ss = ServerSocket(listenPort, 32, InetAddress.getByName("127.0.0.1"))
                server = ss
                Log.i(TAG, "listening 127.0.0.1:$listenPort")
                onStatus("网关已启动 · 127.0.0.1:$listenPort")
                while (running.get()) {
                    val sock = try { ss.accept() } catch (_: Exception) { break }
                    try { pool.execute { handle(sock) } } catch (_: Exception) {
                        try { sock.close() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "server error", e)
                onStatus("网关启动失败: ${e.message}")
            } finally {
                running.set(false)
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() {
        running.set(false)
        try { server?.close() } catch (_: Exception) {}
        server = null
        try { pool.shutdownNow() } catch (_: Exception) {}
    }

    private data class Req(val method: String, val path: String, val body: String)

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 900_000
            val out = sock.getOutputStream()
            val req = readRequest(BufferedInputStream(sock.getInputStream()))
            val path = req.path.substringBefore('?')
            Log.i(TAG, "${req.method} $path")

            when {
                req.method == "OPTIONS" -> writeJson(out, 204, "{}")
                req.method == "GET" && path == "/health" -> writeJson(out, 200, JSONObject().apply {
                    put("ok", true)
                    put("service", "ocgw")
                    put("upstream", opencodeBase)
                    put("default_model", defaultModel)
                    put("agent", AGENT)
                }.toString())
                req.method == "GET" && (path == "/" || path == "/v1" || path == "/v1/models") ->
                    writeJson(out, 200, modelsJson())
                req.method == "POST" && path == "/v1/chat/completions" -> serveChat(out, req.body)
                req.method == "POST" && path == "/v1/completions" -> serveLegacy(out, req.body)
                else -> writeJson(out, 404, errJson("not found: ${req.method} $path"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "handle error", e)
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private fun readRequest(input: BufferedInputStream): Req {
        val head = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) break
            head.append(c.toChar())
            if (head.endsWith("\r\n\r\n")) break
            if (head.length > 200_000) break
        }
        val lines = head.toString().split("\r\n")
        val parts = (lines.firstOrNull() ?: "").split(" ")
        val method = parts.getOrElse(0) { "" }
        val path = parts.getOrElse(1) { "/" }

        var contentLength = 0L
        var chunked = false
        for (i in 1 until lines.size) {
            val l = lines[i]
            if (l.isEmpty()) break
            val idx = l.indexOf(':')
            if (idx <= 0) continue
            val k = l.substring(0, idx).trim().lowercase()
            val v = l.substring(idx + 1).trim()
            if (k == "content-length") contentLength = v.toLongOrNull() ?: 0L
            if (k == "transfer-encoding" && v.contains("chunked", true)) chunked = true
        }

        val body = when {
            chunked -> readChunked(input)
            contentLength > 0 -> {
                val bytes = ByteArray(contentLength.toInt())
                var off = 0
                while (off < bytes.size) {
                    val r = input.read(bytes, off, bytes.size - off)
                    if (r <= 0) break
                    off += r
                }
                String(bytes, 0, off, Charsets.UTF_8)
            }
            else -> ""
        }
        return Req(method, path, body)
    }

    private fun readChunked(input: BufferedInputStream): String {
        val sb = StringBuilder()
        while (true) {
            val line = StringBuilder()
            while (true) {
                val c = input.read()
                if (c == -1) return sb.toString()
                if (c == '\n'.code) break
                if (c != '\r'.code) line.append(c.toChar())
            }
            val size = line.toString().trim().substringBefore(';').toIntOrNull(16) ?: return sb.toString()
            if (size == 0) break
            val buf = ByteArray(size)
            var off = 0
            while (off < size) {
                val r = input.read(buf, off, size - off)
                if (r <= 0) return sb.toString()
                off += r
            }
            sb.append(String(buf, Charsets.UTF_8))
            input.read(); input.read()
        }
        return sb.toString()
    }

    private fun writeRaw(out: OutputStream, s: String) {
        synchronized(writeLock) {
            out.write(s.toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }

    private fun statusText(c: Int) = when (c) {
        200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"
        404 -> "Not Found"; 500 -> "Internal Server Error"; 504 -> "Gateway Timeout"; else -> "OK"
    }

    private fun writeJson(out: OutputStream, code: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        writeRaw(
            out,
            buildString {
                append("HTTP/1.1 ").append(code).append(' ').append(statusText(code)).append("\r\n")
                append("Content-Type: application/json; charset=utf-8\r\n")
                append("Content-Length: ").append(bytes.size).append("\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Access-Control-Allow-Headers: *\r\n")
                append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
                append("Connection: close\r\n\r\n")
            }
        )
        synchronized(writeLock) { out.write(bytes); out.flush() }
    }

    private fun errJson(msg: String) = JSONObject().put("error", JSONObject().apply {
        put("message", msg); put("type", "gateway_error")
    }).toString()

    private fun modelsJson(): String {
        val arr = JSONArray()
        for (m in listModels()) {
            arr.put(JSONObject().apply { put("id", m); put("object", "model"); put("owned_by", "opencode") })
        }
        return JSONObject().put("object", "list").put("data", arr).toString()
    }

    private fun listModels(): List<String> {
        val fallback = listOf(defaultModel, "opencode/big-pickle", "opencode/flint-40b-free").distinct()
        return try {
            val (code, text) = get("$opencodeBase/api/model?auth_token=$opencodeAuthToken", 8000)
            if (code !in 200..299) return fallback
            val data = parseObj(text).optJSONArray("data") ?: return fallback
            val list = mutableListOf<String>()
            for (i in 0 until data.length()) {
                val m = data.optJSONObject(i) ?: continue
                val pid = m.optString("providerID")
                val mid = m.optString("modelID")
                if (pid.isNotEmpty() && mid.isNotEmpty()) {
                    val id = "$pid/$mid"
                    if (id !in list) list.add(id)
                }
            }
            if (list.isEmpty()) fallback else list
        } catch (e: Exception) {
            Log.w(TAG, "listModels: ${e.message}")
            fallback
        }
    }

    private fun serveChat(out: OutputStream, body: String) {
        val req = try { JSONObject(body) } catch (e: Exception) {
            writeJson(out, 400, errJson("invalid json: ${e.message}")); return
        }
        val modelStr = req.optString("model").ifBlank { defaultModel }
        val (providerID, modelID) = splitModel(modelStr)
        val stream = req.optBoolean("stream", false)
        val messages = req.optJSONArray("messages") ?: JSONArray()
        val prompt = flatten(messages)
        if (prompt.isBlank()) {
            writeJson(out, 400, errJson("messages is empty")); return
        }

        val id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)
        val created = System.currentTimeMillis() / 1000

        var hbActive = false
        if (stream) {
            writeRaw(
                out,
                buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: text/event-stream; charset=utf-8\r\n")
                    append("Cache-Control: no-cache\r\n")
                    append("Access-Control-Allow-Origin: *\r\n")
                    append("X-Accel-Buffering: no\r\n")
                    append("Connection: close\r\n\r\n")
                }
            )
            writeRaw(out, sse(chunkJson(id, modelStr, created,
                JSONObject().put("role", "assistant").put("content", ""), null)))
            hbActive = true
            Thread {
                while (hbActive) {
                    try { Thread.sleep(8000) } catch (_: Exception) { break }
                    if (!hbActive) break
                    try { writeRaw(out, ": keep-alive\n\n") } catch (_: Exception) { break }
                }
            }.apply { isDaemon = true }.start()
        }

        var sid: String? = null
        try {
            sid = createSession()
            var tries = 0
            var answer: String? = null
            while (answer == null) {
                sendPrompt(sid, prompt, providerID, modelID)
                when (val r = awaitReply(sid)) {
                    is Reply.Ok -> answer = r.text
                    is Reply.Rate -> {
                        tries++
                        Log.w(TAG, "rate limited, retry $tries: ${r.msg}")
                        if (tries >= RATE_TRIES) throw RuntimeException(r.msg)
                        try { Thread.sleep(RATE_RETRY_MS) } catch (_: Exception) {}
                    }
                    is Reply.Fail -> throw RuntimeException(r.msg)
                }
            }
            hbActive = false
            val text = answer!!
            Log.i(TAG, "reply ${text.length} chars")
            if (stream) emitStreamText(out, id, modelStr, created, text)
            else writeJson(out, 200, jsonCompletion(id, modelStr, created, text, prompt.length))
        } catch (e: Exception) {
            hbActive = false
            Log.e(TAG, "chat failed", e)
            val msg = e.message ?: "upstream error"
            if (stream) {
                try {
                    writeRaw(out, sse(JSONObject().put("error", JSONObject().apply {
                        put("message", msg); put("type", "gateway_error")
                    }).toString()))
                    writeRaw(out, "data: [DONE]\n\n")
                } catch (_: Exception) {}
            } else {
                try { writeJson(out, 500, errJson(msg)) } catch (_: Exception) {}
            }
        } finally {
            hbActive = false
            sid?.let { deleteSession(it) }
        }
    }

    private fun serveLegacy(out: OutputStream, body: String) {
        val req = try { JSONObject(body) } catch (e: Exception) {
            writeJson(out, 400, errJson("invalid json")); return
        }
        val prompt = req.optString("prompt")
        if (prompt.isBlank()) { writeJson(out, 400, errJson("prompt is empty")); return }
        val fake = JSONObject().apply {
            put("model", req.optString("model").ifBlank { defaultModel })
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user"); put("content", prompt)
            }))
        }
        serveChat(out, fake.toString())
    }

    private sealed class Reply {
        class Ok(val text: String) : Reply()
        class Rate(val msg: String) : Reply()
        class Fail(val msg: String) : Reply()
    }

    private fun createSession(): String {
        val (code, text) = post("$opencodeBase/api/session?auth_token=$opencodeAuthToken", "{}", 20_000)
        if (code !in 200..299) throw RuntimeException("创建会话失败 HTTP $code: ${text.take(200)}")
        val root = parseObj(text)
        val id = (root.optJSONObject("data") ?: root).optString("id")
        if (id.isEmpty()) throw RuntimeException("创建会话失败: ${text.take(200)}")
        return id
    }

    private fun deleteSession(sid: String) {
        try {
            val (code, _) = request("DELETE", "$opencodeBase/api/session/$sid?auth_token=$opencodeAuthToken", null, 8000)
            Log.i(TAG, "delete session $sid -> $code")
        } catch (_: Exception) {}
    }

    private fun sendPrompt(sid: String, text: String, providerID: String, modelID: String) {
        val body = JSONObject().apply {
            put("id", "msg_" + UUID.randomUUID().toString().replace("-", "").take(16))
            put("text", text)
            put("files", JSONArray())
            put("agents", JSONArray())
            put("skills", JSONArray())
            put("delivery", "queue")
            put("metadata", JSONObject().apply {
                put("displayText", text.replace("\n", " ").take(120))
                put("comments", JSONArray())
                put("attachments", JSONArray())
                put("agent", AGENT)
                put("model", JSONObject().apply {
                    put("providerID", providerID)
                    put("modelID", modelID)
                })
            })
        }
        val (code, resp) = post("$opencodeBase/api/session/$sid/prompt?auth_token=$opencodeAuthToken", body.toString(), 60_000)
        if (code !in 200..299) throw RuntimeException("prompt 失败 HTTP $code: ${resp.take(300)}")
    }

    private fun awaitReply(sid: String, timeoutMs: Long = TURN_TIMEOUT_MS): Reply {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
            val asst = newestAssistant(fetchMessages(sid)) ?: continue

            val err = asst.optJSONObject("error")
            if (err != null) {
                val type = err.optString("type").ifBlank { err.optString("name") }
                val msg = err.optString("message").ifBlank { type }
                val status = err.optInt("status", 0)
                val rate = type.contains("limit", true) || type.contains("rate", true) ||
                    type.contains("quota", true) || status == 429
                return if (rate) Reply.Rate(msg) else Reply.Fail(msg)
            }

            val finish = if (asst.has("finish") && !asst.isNull("finish")) asst.optString("finish") else ""
            if (finish.isNotEmpty() && !finish.startsWith("tool")) {
                return Reply.Ok(assistantText(asst))
            }
        }
        return Reply.Fail("上游超时（${timeoutMs / 1000}s）")
    }

    private fun fetchMessages(sid: String): JSONArray {
        return try {
            val (code, text) = get("$opencodeBase/api/session/$sid/message?auth_token=$opencodeAuthToken", 20_000)
            if (code !in 200..299) JSONArray() else (parseObj(text).optJSONArray("data") ?: JSONArray())
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun newestAssistant(arr: JSONArray): JSONObject? {
        var best: JSONObject? = null
        var bestT = -1L
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val t = m.optString("type", m.optString("role", ""))
            if (!t.equals("assistant", ignoreCase = true)) continue
            val ct = m.optJSONObject("time")?.optLong("created", 0L) ?: 0L
            if (ct >= bestT) { bestT = ct; best = m }
        }
        return best
    }

    private fun assistantText(m: JSONObject): String {
        val sb = StringBuilder()
        val parts = m.optJSONArray("content") ?: m.optJSONArray("parts")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                if (p.optString("type") == "text") sb.append(p.optString("text"))
            }
        }
        if (sb.isEmpty()) {
            val c = m.opt("content")
            if (c is String) sb.append(c)
        }
        return sb.toString().trim()
    }

    private fun splitModel(model: String): Pair<String, String> {
        val i = model.indexOf('/')
        return if (i > 0) model.substring(0, i) to model.substring(i + 1) else "opencode" to model
    }

    private fun flatten(messages: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val role = m.optString("role", "user")
            val content = contentText(m)
            if (content.isBlank() && role != "assistant") continue
            when (role) {
                "system", "developer" -> sb.append(content).append("\n\n")
                "tool" -> sb.append("[tool result] ").append(content).append("\n\n")
                "assistant" -> {
                    if (content.isNotBlank()) sb.append("<assistant>\n").append(content).append("\n</assistant>\n\n")
                }
                else -> sb.append("<user>\n").append(content).append("\n</user>\n\n")
            }
        }
        return sb.toString().trim()
    }

    private fun contentText(m: JSONObject): String {
        val c = m.opt("content") ?: return ""
        if (c === JSONObject.NULL) return ""
        return when (c) {
            is String -> c
            is JSONArray -> buildString {
                for (i in 0 until c.length()) {
                    val p = c.optJSONObject(i) ?: continue
                    when (p.optString("type")) {
                        "text", "input_text" -> append(p.optString("text"))
                        else -> if (p.has("text")) append(p.optString("text"))
                    }
                }
            }
            else -> c.toString()
        }
    }

    private fun sse(json: String) = "data: $json\n\n"

    private fun chunkJson(id: String, model: String, created: Long, delta: JSONObject, finish: String?): String =
        JSONObject().apply {
            put("id", id)
            put("object", "chat.completion.chunk")
            put("created", created)
            put("model", model)
            put("choices", JSONArray().put(JSONObject().apply {
                put("index", 0)
                put("delta", delta)
                if (finish != null) put("finish_reason", finish) else put("finish_reason", JSONObject.NULL)
            }))
        }.toString()

    private fun emitStreamText(out: OutputStream, id: String, model: String, created: Long, text: String) {
        var i = 0
        while (i < text.length) {
            val end = minOf(i + 48, text.length)
            writeRaw(out, sse(chunkJson(id, model, created,
                JSONObject().put("content", text.substring(i, end)), null)))
            i = end
            if (i < text.length) try { Thread.sleep(12) } catch (_: Exception) {}
        }
        writeRaw(out, sse(chunkJson(id, model, created, JSONObject(), "stop")))
        writeRaw(out, "data: [DONE]\n\n")
    }

    private fun jsonCompletion(id: String, model: String, created: Long, text: String, promptLen: Int): String {
        val pt = (promptLen / 4).coerceAtLeast(1)
        val ct = (text.length / 4).coerceAtLeast(1)
        return JSONObject().apply {
            put("id", id)
            put("object", "chat.completion")
            put("created", created)
            put("model", model)
            put("choices", JSONArray().put(JSONObject().apply {
                put("index", 0)
                put("message", JSONObject().apply { put("role", "assistant"); put("content", text) })
                put("finish_reason", "stop")
            }))
            put("usage", JSONObject().apply {
                put("prompt_tokens", pt); put("completion_tokens", ct); put("total_tokens", pt + ct)
            })
        }.toString()
    }

    private fun sanitizeJson(s: String): String {
        val sb = StringBuilder(s.length + 32)
        var inStr = false
        var esc = false
        for (c in s) {
            if (inStr) {
                when {
                    esc -> { sb.append(c); esc = false }
                    c == '\\' -> { sb.append(c); esc = true }
                    c == '"' -> { sb.append(c); inStr = false }
                    c.code < 0x20 -> sb.append("\\u").append(Integer.toHexString(c.code).padStart(4, '0'))
                    else -> sb.append(c)
                }
            } else {
                if (c == '"') { inStr = true }
                sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun parseObj(s: String): JSONObject = JSONObject(sanitizeJson(s))

    private fun get(url: String, timeoutMs: Int = 15000): Pair<Int, String> = request("GET", url, null, timeoutMs)

    private fun post(url: String, body: String, timeoutMs: Int = 15000): Pair<Int, String> =
        request("POST", url, body, timeoutMs)

    private fun request(method: String, url: String, body: String?, timeoutMs: Int): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = minOf(timeoutMs, 10_000)
            readTimeout = timeoutMs
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }
}
