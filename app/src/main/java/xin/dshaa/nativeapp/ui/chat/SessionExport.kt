package xin.dshaa.nativeapp.ui.chat

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 会话导出落盘（M22）。
 *
 * Android 10+（API 29）走 MediaStore 的 Downloads 集合：不申请存储权限，文件出现在
 * 「下载/DSH/」里，其它 App 能直接看到。API 24–28 退化成 App 专属外部目录
 * （`Android/data/<pkg>/files/Download/DSH/`），不申请 `WRITE_EXTERNAL_STORAGE`
 * ——旧设备上弹权限对「导出一份 Markdown」太重，且在 Android 10 之后又没用。
 */
data class ExportedSession(
    /** MediaStore 返回的 content:// uri；null = 退化成 App 专属目录（没有可分享的 uri）。 */
    val uri: Uri?,
    /** 给用户看的路径（`下载/DSH/xxx.md` 或绝对路径）。 */
    val displayPath: String,
    val error: String? = null,
) {
    val ok: Boolean get() = error == null
}

/** 把 Markdown 写进下载目录；失败返回 [ExportedSession.error]，不抛异常给 UI。 */
fun writeSessionMarkdown(context: Context, fileName: String, content: String): ExportedSession {
    return try {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/markdown")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DSH")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return ExportedSession(null, "", "无法在下载目录创建文件")
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: return ExportedSession(uri, "", "无法写入下载文件")
            } finally {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
            ExportedSession(uri, "下载/DSH/$fileName")
        } else {
            val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            val dir = File(base, "DSH").apply { mkdirs() }
            val file = File(dir, fileName)
            file.writeBytes(bytes)
            ExportedSession(null, file.absolutePath)
        }
    } catch (t: Throwable) {
        ExportedSession(null, "", t.message ?: "导出失败")
    }
}
