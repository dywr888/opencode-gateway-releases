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
 * 反向代理网关（真正的反代）
 *
 * 对外：标准 OpenAI 接口，完全无状态
 *   GET  /health
 *   GET  /v1/models
 *   GET  /v1/tools            内置工具清单
 *   POST /v1/chat/completions 支持 tools / tool_choice / stream
 *   POST /v1/completions      兼容旧接口
 *
 * 对内：
 *   1) 把 messages 摊平成一段文本，喂给 OpenCode 的免费模型（只当 LLM 大脑）
 *   2) 模型想调工具 → 网关在 **Android App 进程** 里真执行（ToolRunner）
 *   3) 结果回灌 → 继续循环，直到模型给出最终答复
 *   4) 每次请求一个一次性 session，用完即删，零状态残留
 */
class OpenAiGateway(
    private val listenPort: Int,
    private val opencodeBase: String,
    private val opencodeAuthToken: String,
    private val defaultModel: String,
    private val toolRunner: ToolRunner,
    private val onStatus: (String) -> Unit,
) {
    private var server: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool = Executors.newFixedThreadPool(8)
    private val writeLock = Any()

    companion object {
        private const val TAG = "OpenAiGateway"
        private const val TURN_TIMEOUT_MS = 300_000L
        private const val POLL_MS = 400L
        private const val HEARTBEAT_MS = 8_000L
        private const val MAX_STEPS_DEFAULT = 12
    }

    // ================================================================== server

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
                    try { pool.execute { handle(sock) } } catch (_: Exception) { try { sock.close() } catch (_: Exception) {} }
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

    // ================================================================== http

    private data class Req(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 600_000
            val input = BufferedInputStream(sock.getInputStream())
            val out = sock.getOutputStream()
            val req = readRequest(input)
            val path = req.path.substringBefore('?')
            Log.i(TAG, "${req.method} $path")

            when {
                req.method == "OPTIONS" -> writeJson(out, 204, "{}")
                req.method == "GET" && path == "/health" -> {
                    val arr = JSONArray()
                    toolRunner.schemas().let { s -> for (i in 0 until s.length()) arr.put(s.getJSONObject(i).getJSONObject("function").optString("name")) }
                    writeJson(out, 200, JSONObject().apply {
                        put("ok", true)
                        put("service", "ocgw")
                        put("upstream", opencodeBase)
                        put("default_model", defaultModel)
                        put("workspace", toolRunner.rootPath())
                        put("native_tools", arr)
                    }.toString())
                }
                req.method == "GET" && (path == "/" || path == "/v1") -> writeJson(out, 200, modelsJson())
                req.method == "GET" && path == "/v1/models" -> writeJson(out, 200, modelsJson())
                req.method == "GET" && path == "/v1/tools" -> writeJson(out, 200, toolRunner.schemas().toString())
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
            if (head.length > 100_000) break
        }
        val lines = head.toString().split("\r\n")
        val parts = (lines.firstOrNull() ?: "").split(" ")
        val method = parts.getOrElse(0) { "" }
        val path = parts.getOrElse(1) { "/" }

        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val l = lines[i]
            if (l.isEmpty()) break
            val idx = l.indexOf(':')
            if (idx > 0) headers[l.substring(0, idx).trim().lowercase()] = l.substring(idx + 1).trim()
        }

        if (headers["transfer-encoding"]?.contains("chunked") == true) {
            return Req(method, path, headers, readChunked(input))
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
            input.read(); input.read() // CRLF
        }
        return sb.toString()
    }

    private fun writeRaw(out: OutputStream, s: String) {
        synchronized(writeLock) {
            out.write(s.toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }

    private fun head(code: Int, contentType: String, extra: String = ""): String = buildString {
        append("HTTP/1.1 ").append(code).append(' ').append(statusText(code)).append("\r\n")
        append("Content-Type: ").append(contentType).append("\r\n")
        append("Access-Control-Allow-Origin: *\r\n")
        append("Access-Control-Allow-Headers: *\r\n")
        append("Access-Control-Allow-Methods: GET, POST, OPTIONS, DELETE\r\n")
        append("Cache-Control: no-cache\r\n")
        append(extra)
        append("Connection: close\r\n")
        append("\r\n")
    }

    private fun writeJson(out: OutputStream, code: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        writeRaw(out, head(code, "application/json; charset=utf-8", "Content-Length: ${bytes.size}\r\n"))
        synchronized(writeLock) { out.write(bytes); out.flush() }
    }

    private fun statusText(c: Int) = when (c) {
        200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"
        401 -> "Unauthorized"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
        500 -> "Internal Server Error"; 504 -> "Gateway Timeout"; else -> "OK"
    }

    private fun errJson(msg: String) =
        JSONObject().put("error", JSONObject().apply {
            put("message", msg); put("type", "gateway_error")
        }).toString()

    // ================================================================== models

    private fun modelsJson(): String {
        val arr = JSONArray()
        for (m in listModels()) {
            arr.put(JSONObject().apply {
                put("id", m); put("object", "model"); put("owned_by", "opencode")
            })
        }
        return JSONObject().put("object", "list").put("data", arr).toString()
    }

    private fun listModels(): List<String> {
        val fallback = listOf(defaultModel, "opencode/big-pickle").distinct()
        return try {
            val (code, text) = get("$opencodeBase/api/model?auth_token=$opencodeAuthToken", 6000)
            if (code !in 200..299) return fallback
            val data = JSONObject(text).optJSONArray("data") ?: return fallback
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

    // ================================================================== chat

    private fun serveChat(out: OutputStream, body: String) {
        val req = try { JSONObject(body) } catch (e: Exception) {
            writeJson(out, 400, errJson("invalid json: ${e.message}")); return
        }
        val modelStr = req.optString("model").ifBlank { defaultModel }
        val stream = req.optBoolean("stream", false)
        val nativeOn = req.optBoolean("native_tools", true)
        val maxSteps = req.optInt("max_steps", MAX_STEPS_DEFAULT).coerceIn(1, 40)
        val messages = req.optJSONArray("messages") ?: JSONArray()

        val nativeSchemas = if (nativeOn) toolRunner.schemas() else JSONArray()
        val nativeNames = if (nativeOn) ToolRunner.NAMES.toHashSet() else HashSet<String>()
        val clientTools = collectClientTools(req)
        val clientNames = clientTools.map { it.first }.toHashSet()
        // 客户端同名工具优先（交给客户端执行）
        nativeNames.removeAll(clientNames)

        val catalog = JSONArray()
        for (i in 0 until nativeSchemas.length()) {
            val f = nativeSchemas.optJSONObject(i) ?: continue
            val n = f.optJSONObject("function")?.optString("name") ?: ""
            if (n.isNotEmpty() && n !in clientNames) catalog.put(f)
        }
        for ((n, schema) in clientTools) catalog.put(schema)

        val (providerID, modelID) = splitModel(modelStr)

        val id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)
        val created = System.currentTimeMillis() / 1000

        var hbActive = false

        if (stream) {
            writeRaw(out, head(200, "text/event-stream; charset=utf-8",
                "X-Accel-Buffering: no\r\n"))
            // 立刻回一个 role chunk，避免客户端认为连接死了
            writeRaw(out, sse(chunkJson(id, modelStr, created,
                JSONObject().put("role", "assistant").put("content", ""), null)))
            hbActive = true
            Thread {
                while (hbActive) {
                    try { Thread.sleep(HEARTBEAT_MS) } catch (_: Exception) { break }
                    if (!hbActive) break
                    try { writeRaw(out, ": keep-alive\n\n") } catch (_: Exception) { break }
                }
            }.apply { isDaemon = true; start() }
        }

        var sessionId: String? = null
        try {
            sessionId = createSession()
            val prompt = buildInitialPrompt(messages, catalog)

            var reply = ask(sessionId, providerID, modelID, prompt)
            reply = ensureUseful(sessionId, providerID, modelID, reply)
            var steps = 0
            var executed = 0
            var finals = reply.trim()

            while (steps < maxSteps) {
                val parsed = parseToolCalls(reply)
                if (parsed.first.isEmpty()) { finals = parsed.second; break }

                val handoff = parsed.first.filter { it.name !in nativeNames }
                if (handoff.isNotEmpty()) {
                    hbActive = false
                    if (stream) emitStreamToolCalls(out, id, modelStr, created, handoff)
                    else writeJson(out, 200, jsonToolCalls(id, modelStr, created, handoff))
                    return
                }

                val fb = StringBuilder()
                for (c in parsed.first) {
                    val started = System.currentTimeMillis()
                    val r = toolRunner.run(c.name, c.args)
                    executed++
                    Log.i(TAG, "tool ${c.name} ok=${r.ok} ${System.currentTimeMillis() - started}ms")
                    fb.append("<tool_result name=\"").append(c.name).append("\" ok=\"").append(r.ok).append("\">\n")
                    if (!r.ok) fb.append("ERROR: ")
                    fb.append(r.content).append("\n</tool_result>\n\n")
                }

                steps++
                val text = fb.toString() + "继续。若已得到最终答案，直接用纯文本回答用户（不要再输出 tool_call 代码块）。"
                reply = ask(sessionId, providerID, modelID, text)
                reply = ensureUseful(sessionId, providerID, modelID, reply)
            }

            finals = cleanFinal(finals)
            if (finals.isBlank()) finals = reply.trim()

            hbActive = false
            if (stream) {
                emitStreamText(out, id, modelStr, created, finals)
            } else {
                writeJson(out, 200, jsonCompletion(id, modelStr, created, finals, prompt.length, executed))
            }
        } catch (e: Exception) {
            Log.e(TAG, "chat failed", e)
            hbActive = false
            if (stream) {
                try {
                    writeRaw(out, sse(JSONObject().put("error", JSONObject().apply {
                        put("message", e.message ?: "upstream error")
                        put("type", "gateway_error")
                    }).toString()))
                    writeRaw(out, "data: [DONE]\n\n")
                } catch (_: Exception) {}
            } else {
                try { writeJson(out, 500, errJson(e.message ?: "upstream error")) } catch (_: Exception) {}
            }
        } finally {
            hbActive = false
            sessionId?.let { deleteSession(it) }
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
            put("stream", false)
        }
        serveChat(out, fake.toString())
    }

    // ================================================================== upstream

    private fun createSession(): String {
        val url = "$opencodeBase/api/session?auth_token=$opencodeAuthToken"
        val payloads = listOf(
            JSONObject().put("title", "ocgw-" + System.currentTimeMillis()).toString(),
            "{}"
        )
        var last = ""
        for (body in payloads) {
            val (code, text) = post(url, body, 20_000)
            last = "HTTP $code $text"
            if (code in 200..299) {
                val id = try {
                    val o = JSONObject(text)
                    (o.optJSONObject("data") ?: o).optString("id")
                } catch (_: Exception) { "" }
                if (id.isNotEmpty()) return id
                // 有的版本返回 {"data":{"id":...}}，有的直接 {"id":...}
            }
        }
        throw RuntimeException("创建上游 session 失败: ${last.take(300)}")
    }

    private fun deleteSession(id: String) {
        try {
            val (code, _) = request("DELETE", "$opencodeBase/api/session/$id?auth_token=$opencodeAuthToken", null, 8000)
            Log.i(TAG, "delete session $id -> $code")
        } catch (_: Exception) {}
    }

    private data class Snap(val idx: Int, val text: String, val done: Boolean, val error: String?)

    private fun snapshot(sid: String): Snap {
        val arr = fetchMessages(sid)
        var idx = -1
        var text = ""
        var done = false
        var error: String? = null
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val t = m.optString("type", m.optString("role", ""))
            if (!t.equals("assistant", ignoreCase = true)) continue
            idx = i
            text = extractText(m)
            done = isDone(m)
            error = extractError(m)
        }
        return Snap(idx, text, done, error)
    }

    private fun extractText(m: JSONObject): String {
        val sb = StringBuilder()
        val parts = m.optJSONArray("content") ?: m.optJSONArray("parts")
        if (parts != null) {
            for (j in 0 until parts.length()) {
                val p = parts.optJSONObject(j) ?: continue
                val t = p.optString("type")
                if (t == "text" || t == "textPart" || (t.isEmpty() && p.has("text"))) {
                    sb.append(p.optString("text"))
                }
            }
        }
        if (sb.isEmpty()) {
            val c = m.opt("content")
            if (c is String) sb.append(c)
            else if (c != null && c !== JSONObject.NULL) sb.append(c.toString())
        }
        if (sb.isEmpty() && m.has("text")) sb.append(m.optString("text"))
        return sb.toString().trim()
    }

    private fun extractError(m: JSONObject): String? {
        val e = m.optJSONObject("error") ?: return null
        val d = e.optJSONObject("data")
        return d?.optString("message")?.ifBlank { null }
            ?: e.optString("name").ifBlank { e.optString("message") }.ifBlank { null }
    }

    private fun isDone(m: JSONObject): Boolean {
        if (m.has("finish") && !m.isNull("finish") && m.optString("finish").isNotEmpty()) return true
        if (m.optBoolean("completed", false)) return true
        val time = m.optJSONObject("time")
        if (time != null && time.has("completed") && !time.isNull("completed")) return true
        if (m.has("completionTime") && !m.isNull("completionTime")) return true
        if (m.optJSONObject("error") != null) return true
        return false
    }

    private fun fetchMessages(sid: String): JSONArray {
        return try {
            val (code, text) = get("$opencodeBase/api/session/$sid/message?auth_token=$opencodeAuthToken", 20_000)
            if (code !in 200..299) return JSONArray()
            val o = JSONObject(text)
            o.optJSONArray("data") ?: JSONArray()
        } catch (_: Exception) {
            try {
                val (_, text) = get("$opencodeBase/api/session/$sid/message?auth_token=$opencodeAuthToken", 20_000)
                JSONArray(text)
            } catch (_: Exception) { JSONArray() }
        }
    }

    /**
     * 发一条消息并等它答完，返回 assistant 的完整文本。
     */
    private fun ask(sid: String, providerID: String, modelID: String, text: String): String {
        val before = snapshot(sid)
        sendPrompt(sid, providerID, modelID, text)

        val deadline = System.currentTimeMillis() + TURN_TIMEOUT_MS
        var lastText = ""
        var lastChange = System.currentTimeMillis()
        var lastErr: String? = null

        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
            val s = snapshot(sid)
            if (lastErr == null && s.error != null) lastErr = s.error
            if (s.idx <= before.idx) continue
            if (s.text != lastText) {
                lastText = s.text
                lastChange = System.currentTimeMillis()
            }
            if (s.done) {
                if (lastText.isNotEmpty()) Log.d(TAG, "assistant raw: " + lastText.take(300))
                if (lastText.isBlank()) {
                    if (s.error != null) throw RuntimeException(upperErr(s.error))
                    Thread.sleep(600)
                    val again = snapshot(sid)
                    if (again.text.isNotBlank()) return again.text
                    throw RuntimeException("上游返回空回复")
                }
                return lastText
            }
            // 没有 finish 字段的版本：文本稳定 8 秒即认为结束
            if (lastText.isNotEmpty() && System.currentTimeMillis() - lastChange > 8_000) return lastText
        }
        if (lastText.isNotEmpty()) return lastText
        throw RuntimeException("上游超时（${TURN_TIMEOUT_MS / 1000}s）")
    }

    /** 空、[]、{}、空代码块 都算无效回复，需要追问一次 */
    private fun isUselessReply(t: String): Boolean {
        var s = t.trim()
        if (s.isEmpty()) return true
        s = s.replace(Regex("(?is)```(?:tool_call|tool_calls|tool|tools|json|ocgw)?\\s*```"), "").trim()
        if (s.isEmpty()) return true
        return Regex("^\\[\\s*\\]$|^\\{\\s*\\}$|^null$|^None$|^\"\"$").matches(s)
    }

    /** 追问模型：你上一次回复无效，请用自然语言直接回答 */
    private fun ensureUseful(sid: String, providerID: String, modelID: String, reply: String): String {
        var cur = reply
        var tries = 0
        while (isUselessReply(cur) && tries < 2) {
            tries++
            Log.w(TAG, "useless reply #$tries: '${cur.take(120)}'")
            cur = ask(
                sid, providerID, modelID,
                "你上一次的回复是无效的（" + (if (cur.isBlank()) "完全为空" else "内容：" + cur.take(60)) + "）。" +
                    "请直接用自然语言回答用户刚才的问题。不要输出 tool_call 代码块，不要输出 JSON，" +
                    "不要输出 [] 或 {}，不要输出空内容。如果你需要先调用工具，就输出 tool_call 代码块（内容是非空 JSON 数组）。"
            )
        }
        return cur
    }

    /** 输出兜底：剥掉残留的空代码块 / 空数组 / 空对象 */
    private fun cleanFinal(t: String): String {
        var s = t
        s = s.replace(Regex("(?is)```(?:tool_call|tool_calls|tool|tools|json|ocgw)?\\s*(\\[\\s*\\]|\\{\\s*\\})\\s*```"), "")
        s = s.replace(Regex("(?is)^\\s*(\\[\\s*\\]|\\{\\s*\\})\\s*$"), "")
        s = s.replace(Regex("(?is)```\\s*```"), "")
        return s.trim()
    }

    private fun upperErr(e: String): String = when {
        e.contains("FreeUsageLimit", true) -> "上游免费额度已限流：$e"
        e.contains("limit", true) -> "上游限流：$e"
        else -> "上游错误：$e"
    }

    private fun sendPrompt(sid: String, providerID: String, modelID: String, text: String) {
        val url = "$opencodeBase/api/session/$sid/prompt?auth_token=$opencodeAuthToken"
        val model = JSONObject().apply {
            put("providerID", providerID)
            put("modelID", modelID)
        }
        val minimal = JSONObject().apply {
            put("model", model)
            put("text", text)
        }
        val withParts = JSONObject(minimal.toString()).apply {
            put("parts", JSONArray().put(JSONObject().apply { put("type", "text"); put("text", text) }))
        }
        val withTools = JSONObject(withParts.toString()).apply {
            val t = JSONObject()
            for (n in listOf("bash", "read", "write", "edit", "grep", "glob", "list", "patch", "webfetch", "task", "todowrite", "todoread", "multiedit", "agent")) {
                t.put(n, false)
            }
            put("tools", t)
        }
        var last = ""
        for (b in listOf(withTools.toString(), withParts.toString(), minimal.toString())) {
            val (code, resp) = post(url, b, 60_000)
            if (code in 200..299) return
            last = "HTTP $code ${resp.take(300)}"
            if (code != 400) break
        }
        throw RuntimeException("prompt 失败: $last")
    }

    private fun splitModel(model: String): Pair<String, String> {
        val i = model.indexOf('/')
        return if (i > 0) model.substring(0, i) to model.substring(i + 1) else "opencode" to model
    }

    // ================================================================== prompt 构造

    private fun buildInitialPrompt(messages: JSONArray, catalog: JSONArray): String {
        val sys = toolPreamble(catalog)
        val conv = renderMessages(messages)
        return if (sys.isBlank()) conv else "$sys\n\n=== 以下是用户对话 ===\n\n$conv"
    }

    private fun toolPreamble(catalog: JSONArray): String {
        if (catalog.length() == 0) return ""
        return buildString {
            appendLine("## 你的运行环境")
            appendLine("你是运行在 Android 手机上的 AI 助手。工具由 App 原生进程执行，拥有 App 的真实文件权限：")
            appendLine("- 能读写 /sdcard、/storage/emulated/0 下的真实文件（不是容器里的虚拟路径）")
            appendLine("- 能执行 /system/bin/sh 里的命令（ls、find、sed、cp、git、python 等，取决于设备）")
            appendLine("- 相对路径以项目根为基准，项目根 = ${toolRunner.rootPath()}")
            appendLine("- 写进项目根的文件会自动同步回用户手机上选的原始目录")
            appendLine()
            appendLine("## 关键：你没有内置工具")
            appendLine("你不能自己调用 read/bash 之类的工具。唯一能操作文件的方式，就是在回复里输出下面的 tool_call 代码块，交给 App 执行。")
            appendLine("不要假装已经读过文件：先发 tool_call，拿到 <tool_result> 再说话。")
            appendLine()
            appendLine("## 调用协议")
            appendLine("需要执行动作时，输出一个语言标记为 tool_call 的代码块，内容是 JSON 数组：")
            appendLine()
            appendLine("```tool_call")
            appendLine("[{\"name\":\"read\",\"arguments\":{\"path\":\"README.md\"}}]")
            appendLine("```")
            appendLine()
            appendLine("- 可以一次放多个调用")
            appendLine("- 输出代码块后就停下，等 App 把 <tool_result name=\"...\">…</tool_result> 发回来")
            appendLine("- **不需要任何工具时，直接用自然语言回答用户**，绝对不要输出 tool_call 代码块")
            appendLine("- 绝对不要输出空的 [] 或 {} 或空代码块，那不是合法回复")
            appendLine("- 回答用户时用中文（或用户提问的语言），不要用 JSON")
            appendLine("- 收到结果后继续；全部完成时直接输出给用户的最终答复，**纯文本**，不要再带 tool_call 块")
            appendLine("- 不要输出「我将要读取…」这类空话，直接调用")
            appendLine()
            appendLine("## 可用工具（JSON Schema）")
            append(catalog.toString(2))
        }
    }

    private fun renderMessages(messages: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val role = m.optString("role", "user")
            val content = contentText(m)
            when (role) {
                "system", "developer", "instructions" ->
                    sb.append("<system>\n").append(content).append("\n</system>\n\n")
                "tool" -> {
                    val name = m.optString("name").ifBlank { m.optString("tool_call_id").ifBlank { "tool" } }
                    sb.append("<tool_result name=\"").append(name).append("\">\n")
                        .append(content).append("\n</tool_result>\n\n")
                }
                "assistant" -> {
                    sb.append("<assistant>\n").append(content)
                    val calls = m.optJSONArray("tool_calls")
                    if (calls != null) {
                        for (j in 0 until calls.length()) {
                            val c = calls.optJSONObject(j) ?: continue
                            val fn = c.optJSONObject("function") ?: continue
                            sb.append("\n<previous_tool_call name=\"").append(fn.optString("name"))
                                .append("\" arguments=\"").append(fn.optString("arguments")).append("\"/>")
                        }
                    }
                    sb.append("\n</assistant>\n\n")
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
                        "image_url", "input_image" -> append("[图片已忽略]")
                        else -> if (p.has("text")) append(p.optString("text"))
                    }
                }
            }
            else -> c.toString()
        }
    }

    /** OpenAI tools[] / functions[] -> (name, schema)；schema 为 OpenAI 的 function 格式 */
    private fun collectClientTools(req: JSONObject): List<Pair<String, JSONObject>> {
        val out = mutableListOf<Pair<String, JSONObject>>()
        val seen = HashSet<String>()

        fun take(obj: JSONObject?) {
            if (obj == null) return
            val n = obj.optString("name").trim()
            if (n.isEmpty() || !seen.add(n)) return
            val fn = JSONObject().apply {
                put("name", n)
                put("description", obj.optString("description", ""))
                put("parameters", obj.optJSONObject("parameters") ?: JSONObject().apply {
                    put("type", "object"); put("properties", JSONObject())
                })
            }
            out.add(n to JSONObject().apply { put("type", "function"); put("function", fn) })
        }

        req.optJSONArray("tools")?.let { arr ->
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                if (t.optString("type") != "function") continue
                take(t.optJSONObject("function") ?: t)
            }
        }
        req.optJSONArray("functions")?.let { arr ->
            for (i in 0 until arr.length()) take(arr.optJSONObject(i))
        }
        return out
    }

    // ================================================================== tool_calls 解析

    private class Call(val name: String, val args: JSONObject)

    private fun parseToolCalls(raw: String): Pair<List<Call>, String> {
        val calls = mutableListOf<Call>()
        var text = raw

        // 1) ```tool_call {...} ```
        val fence = Regex("(?is)```(?:tool_calls?|tools?|ocgw|function_call)\\s*\\n?(.*?)\\n?\\s*```")
        text = fence.replace(text) { m ->
            if (payloadToCalls(m.groupValues[1], calls)) "" else m.value
        }

        // 2) <tool_call>...</tool_call>
        val tag = Regex("(?is)<tool_calls?>(.*?)</tool_calls?>")
        text = tag.replace(text) { m ->
            if (payloadToCalls(m.groupValues[1], calls)) "" else m.value
        }

        // 3) 整个回复就是一个 JSON
        if (calls.isEmpty()) {
            val t = text.trim()
            if (t.startsWith("{") || t.startsWith("[")) {
                if (payloadToCalls(t, calls)) text = ""
            }
        }

        return calls to text.trim()
    }

    private fun payloadToCalls(payload: String, out: MutableList<Call>): Boolean {
        var p = payload.trim()
        if (p.isEmpty()) return false
        if (p.startsWith("```")) p = p.trim('`').trim()
        if (p.endsWith(",")) p = p.dropLast(1)
        if (p.isEmpty()) return false

        val tmp = mutableListOf<Call>()
        try {
            if (p.startsWith("[")) {
                val a = JSONArray(p)
                for (i in 0 until a.length()) addCall(a.optJSONObject(i), tmp)
            } else if (p.startsWith("{")) {
                val o = JSONObject(p)
                val arr = o.optJSONArray("tool_calls") ?: o.optJSONArray("calls") ?: o.optJSONArray("tools")
                if (arr != null) {
                    for (i in 0 until arr.length()) addCall(arr.optJSONObject(i), tmp)
                } else if (o.has("name") || o.has("function") || o.has("tool")) {
                    addCall(o, tmp)
                } else {
                    // {"read": {"path": "..."}} 简写
                    val it = o.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        if (k in ToolRunner.NAMES) {
                            val v = o.opt(k)
                            tmp.add(Call(k, if (v is JSONObject) v else JSONObject()))
                        }
                    }
                }
            }
        } catch (_: Exception) {
            return false
        }

        if (tmp.isEmpty()) return false
        out.addAll(tmp)
        return true
    }

    private fun addCall(o: JSONObject?, out: MutableList<Call>) {
        if (o == null) return
        var name = o.optString("name").trim()
        var raw: Any? = when {
            o.has("arguments") -> o.opt("arguments")
            o.has("parameters") -> o.opt("parameters")
            o.has("args") -> o.opt("args")
            o.has("input") -> o.opt("input")
            else -> null
        }
        if (name.isEmpty()) {
            val fn = o.optJSONObject("function")
            if (fn != null) {
                name = fn.optString("name").trim()
                if (raw == null) raw = fn.opt("arguments") ?: fn.opt("parameters")
            }
        }
        if (name.isEmpty()) name = o.optString("tool").trim().ifEmpty { o.optString("tool_name").trim() }
        if (name.isEmpty()) return

        val args = when (raw) {
            is JSONObject -> raw
            is String -> try {
                if (raw.trim().isEmpty()) JSONObject() else JSONObject(raw)
            } catch (_: Exception) { JSONObject() }
            else -> JSONObject()
        }
        out.add(Call(name, args))
    }

    // ================================================================== 输出

    private fun sse(json: String) = "data: $json\n\n"

    private fun chunkJson(
        id: String, model: String, created: Long,
        delta: JSONObject, finish: String?
    ): String = JSONObject().apply {
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
        val body = text.ifBlank { "" }
        var i = 0
        while (i < body.length) {
            val end = minOf(i + 48, body.length)
            writeRaw(out, sse(chunkJson(id, model, created,
                JSONObject().put("content", body.substring(i, end)), null)))
            i = end
            if (i < body.length) try { Thread.sleep(10) } catch (_: Exception) {}
        }
        writeRaw(out, sse(chunkJson(id, model, created, JSONObject(), "stop")))
        writeRaw(out, "data: [DONE]\n\n")
    }

    private fun emitStreamToolCalls(out: OutputStream, id: String, model: String, created: Long, calls: List<Call>) {
        writeRaw(out, sse(chunkJson(id, model, created,
            JSONObject().put("role", "assistant").put("content", JSONObject.NULL), null)))

        val arr = JSONArray()
        for ((i, c) in calls.withIndex()) {
            arr.put(JSONObject().apply {
                put("index", i)
                put("id", "call_" + UUID.randomUUID().toString().replace("-", "").take(16))
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", c.name)
                    put("arguments", c.args.toString())
                })
            })
        }
        writeRaw(out, sse(chunkJson(id, model, created,
            JSONObject().put("tool_calls", arr), null)))
        writeRaw(out, sse(chunkJson(id, model, created, JSONObject(), "tool_calls")))
        writeRaw(out, "data: [DONE]\n\n")
    }

    private fun jsonCompletion(id: String, model: String, created: Long, text: String, promptLen: Int, steps: Int): String {
        val pt = (promptLen / 4).coerceAtLeast(1)
        val ct = (text.length / 4).coerceAtLeast(1)
        return JSONObject().apply {
            put("id", id)
            put("object", "chat.completion")
            put("created", created)
            put("model", model)
            put("choices", JSONArray().put(JSONObject().apply {
                put("index", 0)
                put("message", JSONObject().apply {
                    put("role", "assistant")
                    put("content", text)
                })
                put("finish_reason", "stop")
            }))
            put("usage", JSONObject().apply {
                put("prompt_tokens", pt)
                put("completion_tokens", ct)
                put("total_tokens", pt + ct)
            })
            put("ocgw_steps", steps)
        }.toString()
    }

    private fun jsonToolCalls(id: String, model: String, created: Long, calls: List<Call>): String {
        val arr = JSONArray()
        for (c in calls) {
            arr.put(JSONObject().apply {
                put("id", "call_" + UUID.randomUUID().toString().replace("-", "").take(16))
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", c.name)
                    put("arguments", c.args.toString())
                })
            })
        }
        return JSONObject().apply {
            put("id", id)
            put("object", "chat.completion")
            put("created", created)
            put("model", model)
            put("choices", JSONArray().put(JSONObject().apply {
                put("index", 0)
                put("message", JSONObject().apply {
                    put("role", "assistant")
                    put("content", JSONObject.NULL)
                    put("tool_calls", arr)
                })
                put("finish_reason", "tool_calls")
            }))
        }.toString()
    }

    // ================================================================== http helpers

    private fun get(url: String, timeoutMs: Int = 15000): Pair<Int, String> =
        request("GET", url, null, timeoutMs)

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
