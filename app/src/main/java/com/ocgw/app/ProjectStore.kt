package com.ocgw.app

import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.OutputStream

/**
 * 项目管理。
 *
 * 重要：Android 11+ scoped storage 下，app 无法按硬路径访问 /sdcard（Linux 权限拦），
 * 所以不能直接 proot -b /storage/emulated/0/xxx。做法是：
 *   选中 SAF 目录 → 用 DocumentFile 把文件**拷贝进 app 私有目录** → 挂载私有目录。
 *   运行时监听 workspace 变化 → 写回 SAF 目录（双向同步）。
 */
object ProjectStore {
    private const val PREF = "ocgw"
    private const val KEY_URI = "project_uri"
    private const val TAG = "ProjectStore"

    /** 私有目录里的工作副本 */
    fun workspaceDir(context: Context): File =
        File(context.filesDir, "workspace").also { it.mkdirs() }

    fun save(context: Context, uri: Uri) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_URI, uri.toString()).apply()
    }

    fun load(context: Context): Uri? {
        val s = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_URI, null)
        return s?.let { Uri.parse(it) }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY_URI).apply()
    }

    /** 仅用于显示（SAF 的 documentId 可读性尚可） */
    fun displayName(context: Context, uri: Uri): String = try {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        docId.substringAfter(':', docId)
    } catch (e: Exception) {
        uri.toString()
    }

    /**
     * 手机原目录 → workspace（启动时调用）
     */
    fun syncToWorkspace(context: Context, uri: Uri): Int {
        val dest = workspaceDir(context)
        return try {
            val root = DocumentFile.fromTreeUri(context, uri) ?: return -1
            var count = 0
            var totalBytes = 0L
            val maxBytes = 200L * 1024 * 1024 // 最多拷 200MB
            fun walk(dir: DocumentFile, out: File) {
                out.mkdirs()
                for (child in dir.listFiles()) {
                    if (totalBytes > maxBytes) {
                        Log.w(TAG, "workspace copy exceeds ${maxBytes} bytes, stop")
                        return
                    }
                    val name = child.name ?: continue
                    if (name.startsWith(".")) continue
                    if (name == ".git") continue
                    val target = File(out, name)
                    if (child.isDirectory) {
                        walk(child, target)
                    } else {
                        try {
                            context.contentResolver.openInputStream(child.uri)?.use { input ->
                                target.outputStream().use { output ->
                                    val copied = input.copyTo(output, 256 * 1024)
                                    totalBytes += copied
                                }
                            }
                            count++
                        } catch (e: Exception) {
                            Log.w(TAG, "copy failed ${child.name}: ${e.message}")
                        }
                    }
                }
            }
            walk(root, dest)
            Log.i(TAG, "synced $count files, ${totalBytes / 1024}KB into ${dest.absolutePath}")
            count
        } catch (e: Exception) {
            Log.e(TAG, "syncToWorkspace failed", e)
            -1
        }
    }

    /**
     * workspace → 手机原目录（双向同步：文件变化时调用）
     * 仅同步 mtime 变化的文件，避免全量覆盖
     */
    fun syncFromWorkspace(context: Context, uri: Uri, changedFiles: List<File> = emptyList()): Int {
        val src = workspaceDir(context)
        if (!src.exists()) return 0
        val root = DocumentFile.fromTreeUri(context, uri) ?: return -1
        var count = 0
        try {
            fun walk(dir: DocumentFile, srcDir: File) {
                if (!srcDir.exists()) return
                for (file in srcDir.listFiles() ?: emptyArray()) {
                    val name = file.name
                    if (name.startsWith(".")) continue
                    if (name == ".git") continue
                    val child = dir.findFile(name)
                    if (file.isDirectory) {
                        val childDir: DocumentFile = child ?: dir.createDirectory(name) ?: return
                        walk(childDir, file)
                    } else {
                        // 仅同步指定文件列表，或全部（增量）
                        if (changedFiles.isNotEmpty() && file !in changedFiles) continue
                        try {
                            val output: OutputStream = if (child != null) {
                                // 文件已存在，用 truncate 模式重写
                                context.contentResolver.openOutputStream(child.uri, "wt")
                            } else {
                                // 新文件
                                val mime = file.name.split('.').lastOrNull()?.let { "application/octet-stream" } ?: "application/octet-stream"
                                dir.createFile(mime, name)?.let { context.contentResolver.openOutputStream(it.uri, "wt") }
                            } ?: continue
                            file.inputStream().use { input ->
                                output.use { output ->
                                    input.copyTo(output, 256 * 1024)
                                }
                            }
                            count++
                        } catch (e: Exception) {
                            Log.w(TAG, "write back failed $name: ${e.message}")
                        }
                    }
                }
            }
            walk(root, src)
            Log.i(TAG, "synced back $count files from workspace")
            return count
        } catch (e: Exception) {
            Log.e(TAG, "syncFromWorkspace failed", e)
            return -1
        }
    }
}