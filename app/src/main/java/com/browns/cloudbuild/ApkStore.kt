package com.browns.cloudbuild

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 把编译好的 APK 额外存一份到公共 Download/CloudBuild。
 * Android 10+ 走 MediaStore（不需要存储权限，卸载 App 后文件仍在）；
 * Android 8/9 走公共目录（需要 WRITE_EXTERNAL_STORAGE，失败就跳过，不影响安装）。
 */
object ApkStore {

    /** 返回公共位置描述（路径或 content Uri），失败返回 null。 */
    fun publishToDownloads(ctx: Context, src: File): String? {
        if (!src.exists()) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val resolver = ctx.contentResolver
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, src.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CloudBuild")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(collection, values) ?: return null
                resolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                } ?: return null
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri.toString()
            }.getOrNull()
        } else {
            runCatching {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "CloudBuild"
                ).apply { mkdirs() }
                val out = File(dir, src.name)
                src.copyTo(out, overwrite = true)
                out.absolutePath
            }.getOrNull()
        }
    }
}