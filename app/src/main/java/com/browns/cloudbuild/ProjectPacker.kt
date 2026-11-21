package com.browns.cloudbuild

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 把选中的 ZIP 或目录整理成待上传的 zip。 */
object ProjectPacker {

    data class Packed(val file: File, val name: String, val fileCount: Int, val totalBytes: Long)

    private val SKIP_DIRS = setOf(
        "build", ".gradle", ".git", ".idea", ".cxx", "captures", "node_modules", ".kotlin"
    )
    private val SKIP_FILES = setOf("local.properties", ".DS_Store", "Thumbs.db")

    private const val MAX_BYTES = 1_500L * 1024 * 1024
    private const val MAX_FILES = 6000

    suspend fun fromZip(ctx: Context, uri: Uri, onLog: (String) -> Unit): Packed =
        withContext(Dispatchers.IO) {
            val name = queryName(ctx, uri) ?: "project.zip"
            val dir = File(ctx.cacheDir, "pack").apply { mkdirs() }
            dir.listFiles()?.forEach { if (it.lastModified() < System.currentTimeMillis() - 3600_000) it.delete() }
            val out = File(dir, "project-${System.currentTimeMillis()}.zip")
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { o -> input.copyTo(o) }
            } ?: throw IllegalStateException("无法读取所选文件")
            val head = out.inputStream().use { i ->
                val b = ByteArray(4); i.read(b); b
            }
            if (!(head.size == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte())) {
                out.delete()
                throw IllegalArgumentException("不是有效的 ZIP 文件")
            }
            if (out.length() > MAX_BYTES) {
                out.delete()
                throw IllegalArgumentException("工程超过 ${Fmt.bytes(MAX_BYTES)}，请先移除无用文件")
            }
            onLog("已读取 ZIP: $name (${Fmt.bytes(out.length())})")
            Packed(out, name, 0, out.length())
        }

    suspend fun fromTree(
        ctx: Context,
        treeUri: Uri,
        onLog: (String) -> Unit
    ): Packed = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(ctx, treeUri)
            ?: throw IllegalArgumentException("无法打开所选目录")
        val name = root.name ?: "project"
        val dir = File(ctx.cacheDir, "pack").apply { mkdirs() }
        val out = File(dir, "project-${System.currentTimeMillis()}.zip")

        var files = 0
        var bytes = 0L
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            fun walk(d: DocumentFile, prefix: String) {
                for (child in d.listFiles()) {
                    val n = child.name ?: continue
                    val path = if (prefix.isEmpty()) n else "$prefix/$n"
                    if (child.isDirectory) {
                        if (n in SKIP_DIRS) continue
                        walk(child, path)
                    } else {
                        if (n in SKIP_FILES || n.endsWith(".iml")) continue
                        files++
                        if (files > MAX_FILES) throw IllegalArgumentException("文件数超过 $MAX_FILES")
                        val len = child.length()
                        bytes += len
                        if (bytes > MAX_BYTES) throw IllegalArgumentException("工程超过 ${Fmt.bytes(MAX_BYTES)}")
                        zos.putNextEntry(ZipEntry(path))
                        ctx.contentResolver.openInputStream(child.uri)?.use { it.copyTo(zos) }
                        zos.closeEntry()
                        if (files % 200 == 0) onLog("已打包 $files 个文件 / ${Fmt.bytes(bytes)}")
                    }
                }
            }
            walk(root, "")
        }
        onLog("打包完成: $files 个文件 / ${Fmt.bytes(out.length())}")
        Packed(out, name, files, out.length())
    }

    private fun queryName(ctx: Context, uri: Uri): String? {
        return runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }
}