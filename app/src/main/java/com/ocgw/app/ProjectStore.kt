package com.ocgw.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File

/**
 * 项目管理。
 *
 * 重要：Android 11+ scoped storage 下，app 无法按硬路径访问 /sdcard（Linux 权限拦），
 * 所以不能直接 proot -b /storage/emulated/0/xxx。做法是：
 *   选中 SAF 目录 → 用 DocumentFile 把文件**拷贝进 app 私有目录** → 挂载私有目录。
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
     * 把 SAF 目录内容拷贝进私有 workspace 目录。
     * 返回拷贝的文件数；失败返回 -1。
     */
    fun syncToWorkspace(context: Context, uri: Uri): Int {
        val dest = workspaceDir(context)
        return try {
            val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
                ?: return -1
            var count = 0
            var totalBytes = 0L
            val maxBytes = 200L * 1024 * 1024 // 最多拷 200MB，避免塞爆
            fun walk(dir: androidx.documentfile.provider.DocumentFile, out: File) {
                out.mkdirs()
                for (child in dir.listFiles()) {
                    if (totalBytes > maxBytes) {
                        Log.w(TAG, "workspace copy exceeds ${maxBytes} bytes, stop")
                        return
                    }
                    val name = child.name ?: continue
                    if (name.startsWith(".")) continue
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
}
