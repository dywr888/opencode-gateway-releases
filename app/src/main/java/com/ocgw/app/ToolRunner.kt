package com.ocgw.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 原生工具执行器 —— 跑在 **Android App 进程** 里，不是 proot。
 *
 * 因此这些工具拥有 App 自己的权限：
 *  - READ/WRITE_EXTERNAL_STORAGE（targetSdk 28 + legacy storage）→ /sdcard 全盘可读写
 *  - /system/bin/sh 直接可用（git、ls、find、sed、python 若有的话）
 *  - App 私有目录 filesDir / cacheDir
 *
 * OpenCode 只负责「想调用哪个工具、参数是什么」，真正执行在这里。
 */
class ToolRunner(private val ctx: Context, private val root: File) {

    data class Result(val ok: Boolean, val content: String)

    companion object {
        private const val TAG = "ToolRunner"
        private const val MAX_OUT = 40_000
        private const val MAX_FILES = 20_000
        private val SKIP_DIRS = setOf(
            ".git", "node_modules", ".gradle", ".idea", "__pycache__",
            ".venv", "venv", ".cxx", ".dart_tool", "build", "dist", ".next"
        )

        /** 内置工具名（模型可以直接调，网关自己执行） */
        val NAMES = listOf("bash", "read", "write", "edit", "ls", "glob", "grep")
    }

    fun rootPath(): String = root.absolutePath

    // ------------------------------------------------------------------ schema

    fun schemas(): JSONArray {
        val arr = JSONArray()

        fun p(type: String, desc: String, extra: JSONObject? = null): JSONObject {
            val o = JSONObject().apply { put("type", type); put("description", desc) }
            extra?.let { for (k in it.keys()) o.put(k, it.get(k)) }
            return o
        }

        fun add(name: String, desc: String, props: JSONObject, required: List<String> = emptyList()) {
            arr.put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", name)
                    put("description", desc)
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", props)
                        put("required", JSONArray().apply { required.forEach { put(it) } })
                    })
                })
            })
        }

        add(
            "bash",
            "在手机（Android App 进程）上执行 shell 命令，返回退出码和 stdout/stderr 合并输出。" +
                "可以跑 git / ls / find / sed / cp / mv / python 等。默认工作目录是项目根目录。" +
                "注意：执行身份是本 App，不是 root，也不是 proot 容器。",
            JSONObject().apply {
                put("command", p("string", "完整 shell 命令"))
                put("cwd", p("string", "工作目录，绝对路径或相对项目根，默认项目根"))
                put("timeout_ms", p("integer", "超时毫秒，默认 120000，最大 600000"))
            },
            listOf("command")
        )

        add(
            "read",
            "读取文本文件，返回带行号的 content。大文件用 offset/limit 分段读。",
            JSONObject().apply {
                put("path", p("string", "文件路径，相对项目根或绝对路径，例如 /sdcard/Download/a.txt"))
                put("offset", p("integer", "起始行号（0 起），默认 0"))
                put("limit", p("integer", "最多读取行数，默认 2000"))
            },
            listOf("path")
        )

        add(
            "write",
            "把 content 完整写入文件（覆盖写），自动创建父目录。用于新建文件或整文件重写。",
            JSONObject().apply {
                put("path", p("string", "文件路径"))
                put("content", p("string", "要写入的完整文本内容"))
            },
            listOf("path", "content")
        )

        add(
            "edit",
            "精确字符串替换：把文件里的 old_string 换成 new_string。" +
                "old_string 必须唯一出现（含缩进与换行），否则请补足上下文，或设置 replace_all=true。",
            JSONObject().apply {
                put("path", p("string", "文件路径"))
                put("old_string", p("string", "要被替换的原文，必须与文件内容完全一致"))
                put("new_string", p("string", "替换后的新文本"))
                put("replace_all", JSONObject().apply {
                    put("type", "boolean")
                    put("description", "替换全部出现处，默认 false")
                })
            },
            listOf("path", "old_string", "new_string")
        )

        add(
            "ls",
            "列出目录内容（名称、类型、大小）。参数若是文件则返回该文件信息。",
            JSONObject().apply {
                put("path", p("string", "目录或文件，默认项目根"))
            }
        )

        add(
            "glob",
            "按 glob 模式查找文件，支持 ** 递归，例如 **/*.kt、app/src/**/*.xml。",
            JSONObject().apply {
                put("pattern", p("string", "glob 模式"))
                put("path", p("string", "搜索起点，默认项目根"))
            },
            listOf("pattern")
        )

        add(
            "grep",
            "在文件内容里用正则搜索，返回 路径:行号: 内容。支持 include 按文件名过滤。",
            JSONObject().apply {
                put("pattern", p("string", "正则表达式"))
                put("path", p("string", "搜索起点，默认项目根"))
                put("include", p("string", "文件名 glob，例如 *.kt，默认所有文本文件"))
                put("case_sensitive", JSONObject().apply {
                    put("type", "boolean")
                    put("description", "区分大小写，默认 false")
                })
            },
            listOf("pattern")
        )

        return arr
    }

    /** 给 /health 用的简短描述 */
    fun describe(): String {
        val n = try {
            var c = 0
            val t = Thread {
                c = walkCount(root, 0, MAX_FILES)
            }
            t.start(); t.join(1500)
            c
        } catch (_: Exception) { -1 }
        return "root=${root.absolutePath}, files=$n, canRead=${root.canRead()}, canWrite=${root.canWrite()}"
    }

    private fun walkCount(d: File, depth: Int, cap: Int): Int {
        if (depth > 3) return 0
        val k = d.listFiles() ?: return 0
        var c = 0
        for (f in k) {
            c++
            if (c > cap) return c
            if (f.isDirectory && !f.name.startsWith(".") && f.name !in SKIP_DIRS) c += walkCount(f, depth + 1, cap)
        }
        return c
    }

    // ------------------------------------------------------------------ dispatch

    fun run(name: String, args: JSONObject): Result = try {
        when (name) {
            "bash" -> bash(args)
            "read" -> read(args)
            "write" -> write(args)
            "edit" -> edit(args)
            "ls" -> ls(args)
            "glob" -> glob(args)
            "grep" -> grep(args)
            else -> Result(false, "未知工具: $name（可用：${NAMES.joinToString("、")}）")
        }
    } catch (e: Exception) {
        Log.e(TAG, "tool $name failed", e)
        Result(false, "${e.javaClass.simpleName}: ${e.message}")
    }

    private fun resolve(p: String?): File {
        val s = (p ?: "").trim()
        if (s.isEmpty() || s == "." || s == "~") return root
        val n = if (s == "~/" || s.startsWith("~/")) s.removePrefix("~/") else s
        val f = File(n)
        return if (f.isAbsolute) f else File(root, n)
    }

    // ------------------------------------------------------------------ bash

    private fun bash(a: JSONObject): Result {
        val cmd = a.optString("command").trim()
        if (cmd.isEmpty()) return Result(false, "command 不能为空")

        val cwd = resolve(a.optString("cwd")).let {
            if (it.exists() || it.mkdirs()) it else root
        }
        val timeout = a.optLong("timeout_ms", 120_000L).coerceIn(2_000L, 600_000L)

        val pb = ProcessBuilder("/system/bin/sh", "-c", cmd)
        pb.directory(cwd)
        pb.redirectErrorStream(true)
        val env = pb.environment()
        env["HOME"] = ctx.filesDir.absolutePath
        env["TMPDIR"] = ctx.cacheDir.absolutePath
        env["PWD"] = cwd.absolutePath
        env["LANG"] = "C.UTF-8"
        env["PATH"] = "/system/bin:/system/xbin:/vendor/bin:/product/bin:" +
            "/apex/com.android.runtime/bin:/sbin:/usr/bin:/bin"
        env["OCGW_ROOT"] = root.absolutePath

        val p = try {
            pb.start()
        } catch (e: Exception) {
            return Result(false, "无法启动 sh: ${e.message}")
        }
        try { p.outputStream.close() } catch (_: Exception) {}

        val sb = StringBuilder()
        val truncated = AtomicBoolean(false)
        val reader = Thread {
            try {
                p.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(8192)
                    while (true) {
                        val n = r.read(buf)
                        if (n <= 0) break
                        synchronized(sb) {
                            val room = MAX_OUT - sb.length
                            if (room <= 0) { truncated.set(true) } else sb.append(buf, 0, minOf(n, room))
                        }
                        if (truncated.get()) break
                    }
                }
            } catch (_: Exception) {}
        }
        reader.isDaemon = true
        reader.start()

        val done = p.waitFor(timeout, TimeUnit.MILLISECONDS)
        if (!done) {
            try { p.destroyForcibly() } catch (_: Exception) {}
            try { p.waitFor(2, TimeUnit.SECONDS) } catch (_: Exception) {}
        }
        try { reader.join(1500) } catch (_: Exception) {}

        val code = try { p.exitValue() } catch (_: Exception) { if (done) 0 else -1 }
        val out = synchronized(sb) { sb.toString() }

        val body = buildString {
            append("[exit] ").append(code)
            if (!done) append("  (超时 ").append(timeout).append("ms 已终止)")
            append('\n')
            append("[cwd] ").append(cwd.absolutePath).append('\n')
            if (truncated.get()) append("[输出超过 ").append(MAX_OUT).append(" 字符，已截断]\n")
            append(out.trimEnd())
        }
        return Result(done && code == 0, body.ifBlank { "(无输出)" })
    }

    // ------------------------------------------------------------------ read

    private fun read(a: JSONObject): Result {
        val f = resolve(a.optString("path"))
        if (!f.exists()) return Result(false, "文件不存在: ${f.absolutePath}")
        if (f.isDirectory) return Result(false, "${f.absolutePath} 是目录，请用 ls 或 glob")
        if (!f.canRead()) return Result(false, "无权读取: ${f.absolutePath}")
        if (f.length() > 16L * 1024 * 1024) return Result(false, "文件过大 (${f.length()} bytes)，用 bash 的 head/sed 分段读")

        val bytes = f.readBytes()
        if (looksBinary(bytes)) return Result(false, "二进制文件 (${bytes.size} bytes): ${f.absolutePath}")
        val text = String(bytes, Charsets.UTF_8)

        val offset = a.optInt("offset", 0).coerceAtLeast(0)
        val limit = a.optInt("limit", 2000).coerceIn(1, 20000)
        val lines = text.split("\n")

        val sb = StringBuilder()
        sb.append("[file] ").append(f.absolutePath)
        sb.append("  [lines] ").append(lines.size)
        sb.append("  [bytes] ").append(bytes.size).append('\n')

        var i = offset
        while (i < lines.size && i < offset + limit) {
            sb.append((i + 1).toString().padStart(6)).append("| ").append(lines[i]).append('\n')
            i++
        }
        if (i < lines.size) sb.append("...(还有 ").append(lines.size - i).append(" 行，用 offset=").append(i).append(" 继续)\n")
        return Result(true, sb.toString())
    }

    private fun looksBinary(b: ByteArray): Boolean {
        val n = minOf(b.size, 8000)
        for (i in 0 until n) if (b[i] == 0.toByte()) return true
        return false
    }

    // ------------------------------------------------------------------ write / edit

    private fun write(a: JSONObject): Result {
        val path = a.optString("path").trim()
        if (path.isEmpty()) return Result(false, "path 不能为空")
        val content = if (a.has("content")) a.optString("content") else ""
        val f = resolve(path)
        if (f.isDirectory) return Result(false, "${f.absolutePath} 是目录")
        try {
            f.parentFile?.mkdirs()
            f.writeText(content)
        } catch (e: Exception) {
            return Result(false, "写入失败: ${e.message}（${f.absolutePath}）")
        }
        return Result(
            true,
            "已写入 ${content.toByteArray(Charsets.UTF_8).size} 字节 -> ${f.absolutePath}\n共 ${content.split('\n').size} 行"
        )
    }

    private fun edit(a: JSONObject): Result {
        val f = resolve(a.optString("path"))
        if (!f.isFile) return Result(false, "文件不存在: ${f.absolutePath}")
        val oldS = a.optString("old_string")
        val newS = a.optString("new_string")
        if (oldS.isEmpty()) return Result(false, "old_string 不能为空")
        if (oldS == newS) return Result(false, "old_string 与 new_string 相同，无需修改")

        var text = try { f.readText() } catch (e: Exception) {
            return Result(false, "读取失败: ${e.message}")
        }

        var count = 0
        var idx = text.indexOf(oldS)
        while (idx >= 0) {
            count++
            idx = text.indexOf(oldS, idx + oldS.length)
        }
        if (count == 0) return Result(false, "未找到 old_string（${f.absolutePath}）。请先 read 确认原文，注意缩进与换行必须完全一致")
        if (count > 1 && !a.optBoolean("replace_all", false)) {
            return Result(false, "old_string 在文件中出现 $count 次，不唯一。请补充更多上下文，或设置 replace_all=true")
        }

        text = text.replace(oldS, newS)
        return try {
            f.writeText(text)
            Result(true, "已替换 $count 处 -> ${f.absolutePath}")
        } catch (e: Exception) {
            Result(false, "写入失败: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ ls

    private fun ls(a: JSONObject): Result {
        val f = resolve(a.optString("path"))
        if (!f.exists()) return Result(false, "路径不存在: ${f.absolutePath}")
        if (f.isFile) return Result(true, "f  ${f.length()}\t${f.name}\n(${f.absolutePath})")

        val kids = f.listFiles() ?: return Result(false, "无法列出目录（权限）: ${f.absolutePath}")
        val sorted = kids.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        val sb = StringBuilder()
        sb.append("dir ").append(f.absolutePath).append("  共 ").append(kids.size).append(" 项\n")
        for (c in sorted.take(2000)) {
            if (c.isDirectory) sb.append("d  -\t").append(c.name).append("/\n")
            else sb.append("f  ").append(c.length()).append('\t').append(c.name).append('\n')
        }
        if (sorted.size > 2000) sb.append("...(仅显示 2000 项，共 ").append(sorted.size).append(")\n")
        return Result(true, sb.toString())
    }

    // ------------------------------------------------------------------ glob

    private fun glob(a: JSONObject): Result {
        val pat = a.optString("pattern").trim()
        if (pat.isEmpty()) return Result(false, "pattern 不能为空")
        val base = resolve(a.optString("path"))
        if (!base.exists()) return Result(false, "路径不存在: ${base.absolutePath}")

        val rx = globToRegex(pat)
        val byPath = pat.contains('/')
        val hits = mutableListOf<String>()
        val start = if (base.isFile) base.parentFile ?: base else base

        walkFiles(start, MAX_FILES) { f ->
            val rel = relPath(start, f)
            val target = if (byPath) rel else f.name
            if (rx.matches(target)) hits.add(rel)
        }

        return if (hits.isEmpty()) Result(true, "没有匹配 $pat 的文件（根：${start.absolutePath}）")
        else Result(true, "匹配 ${hits.size} 个文件（根：${start.absolutePath}）\n" + hits.take(2000).joinToString("\n"))
    }

    private fun relPath(base: File, f: File): String {
        val b = base.absolutePath.trimEnd('/')
        val p = f.absolutePath
        return if (p.startsWith("$b/")) p.substring(b.length + 1) else p
    }

    // ------------------------------------------------------------------ grep

    private fun grep(a: JSONObject): Result {
        val pat = a.optString("pattern").trim()
        if (pat.isEmpty()) return Result(false, "pattern 不能为空")
        val base = resolve(a.optString("path"))
        if (!base.exists()) return Result(false, "路径不存在: ${base.absolutePath}")

        val ci = !a.optBoolean("case_sensitive", false)
        val re = try {
            if (ci) Regex(pat, RegexOption.IGNORE_CASE) else Regex(pat)
        } catch (e: Exception) {
            return Result(false, "正则非法: ${e.message}")
        }
        val includeRaw = a.optString("include").trim()
        val incRx = if (includeRaw.isEmpty() || includeRaw == "*") null else globToRegex(includeRaw)
        val start = if (base.isFile) base.parentFile ?: base else base
        val scopeRoot = start.absolutePath

        val sb = StringBuilder()
        var hits = 0
        var scanned = 0

        if (base.isFile) {
            scanGrepFile(base, re, sb, hits)
        } else {
            walkFiles(start, MAX_FILES) { f ->
                if (hits >= 200 || sb.length > 30_000) return@walkFiles
                if (incRx != null && !incRx.matches(f.name)) return@walkFiles
                if (f.length() > 2L * 1024 * 1024) return@walkFiles
                scanned++
                try {
                    f.bufferedReader().useLines { seq ->
                        var ln = 0
                        for (t in seq) {
                            ln++
                            if (re.containsMatchIn(t)) {
                                val rel = f.absolutePath.removePrefix(scopeRoot).removePrefix("/")
                                sb.append(rel).append(':').append(ln).append(": ")
                                    .append(t.trimEnd().take(400)).append('\n')
                                hits++
                                if (hits >= 200) break
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        if (hits == 0) return Result(true, "没有匹配「$pat」的行（扫描 $scanned 个文件）")
        val tail = if (hits >= 200) "\n...(结果已达上限 200 条，请缩小范围)\n" else ""
        return Result(true, "匹配 $hits 行：\n$sb$tail")
    }

    private fun scanGrepFile(f: File, re: Regex, sb: StringBuilder, startHits: Int) {
        var hits = startHits
        try {
            f.bufferedReader().useLines { seq ->
                var ln = 0
                for (t in seq) {
                    ln++
                    if (re.containsMatchIn(t)) {
                        sb.append(f.absolutePath).append(':').append(ln).append(": ")
                            .append(t.trimEnd().take(400)).append('\n')
                        hits++
                        if (hits >= 200) break
                    }
                }
            }
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ helpers

    private fun walkFiles(base: File, max: Int, onFile: (File) -> Unit) {
        var count = 0
        fun go(dir: File, depth: Int) {
            if (depth > 24 || count >= max) return
            val kids = dir.listFiles() ?: return
            for (c in kids) {
                if (count >= max) return
                if (c.name.startsWith(".")) continue
                if (c.isDirectory) {
                    if (c.name in SKIP_DIRS) continue
                    go(c, depth + 1)
                } else {
                    count++
                    onFile(c)
                }
            }
        }
        go(base, 0)
    }

    /** 简易 glob -> Regex：** 跨目录，* 不跨，? 单字符 */
    private fun globToRegex(glob: String): Regex {
        val g = glob.trim().ifEmpty { "*" }
        val sb = StringBuilder()
        var i = 0
        while (i < g.length) {
            val c = g[i]
            when (c) {
                '*' -> {
                    if (i + 1 < g.length && g[i + 1] == '*') {
                        i++
                        if (i + 1 < g.length && g[i + 1] == '/') {
                            i++
                            sb.append("(?:.*/)?")
                        } else {
                            sb.append(".*")
                        }
                    } else {
                        sb.append("[^/]*")
                    }
                }
                '?' -> sb.append("[^/]")
                '.', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|', '\\' ->
                    sb.append('\\').append(c)
                else -> sb.append(c)
            }
            i++
        }
        return try { Regex("^$sb$") } catch (_: Exception) { Regex(".*") }
    }
}
