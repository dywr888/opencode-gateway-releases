package com.ocgw.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** 记住用户选择的项目目录，并解析成可 bind 的真实路径 */
object ProjectStore {
    private const val PREF = "ocgw"
    private const val KEY_URI = "project_uri"

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

    /**
     * 把 SAF 目录 URI 解析成文件系统路径。
     * 优先用 primary 存储的 documentId（形如 primary:Android/data/... 或 primary:DCIM）。
     * 非 primary 卷（SD 卡）返回 null。
     */
    fun resolvePath(context: Context, uri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val parts = docId.split(":", limit = 2)
            if (parts.size != 2) return null
            val volume = parts[0]
            val rel = parts[1]
            if (volume != "primary") {
                // SD 卡等：尝试 /storage/<volume>
                val base = "/storage/$volume"
                if (java.io.File(base).exists()) {
                    return if (rel.isEmpty()) base else "$base/$rel"
                }
                return null
            }
            val base = "/storage/emulated/0"
            if (rel.isEmpty()) base else "$base/$rel"
        } catch (e: Exception) {
            null
        }
    }
}
