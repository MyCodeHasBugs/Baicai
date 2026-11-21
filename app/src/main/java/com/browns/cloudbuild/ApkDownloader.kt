package com.browns.cloudbuild

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * APK 下载器：
 * 1) 镜像并发测速，选最快的；
 * 2) 支持 Range 就分多段并发下载，否则单流；
 * 3) 任一镜像失败自动换下一个。
 */
object ApkDownloader {

    data class Result(val file: File, val bytes: Long, val mirror: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private data class Probe(val total: Long, val ranged: Boolean)

    suspend fun download(
        ctx: android.content.Context,
        originalUrl: String,
        outFile: File,
        threads: Int,
        accelerate: Boolean,
        mirrors: String,
        onProgress: (done: Long, total: Long, bps: Long) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        val candidates = LinkedHashSet<String>()
        if (accelerate) {
            val ranked = MirrorSelector(mirrors).rank(originalUrl)
            ranked.filter { it.ok }.forEach { candidates += it.prefix }
        }
        candidates += "" // 直连兜底

        val list = candidates.take(6)
        var lastError: Throwable? = null
        for (prefix in list) {
            val url = if (prefix.isEmpty()) originalUrl else prefix + originalUrl
            val label = if (prefix.isEmpty()) "直连" else prefix
            try {
                val probe = probe(url)
                if (probe.total <= 0) throw IllegalStateException("文件大小未知")
                outFile.parentFile?.mkdirs()
                if (probe.ranged && threads > 1 && probe.total > 4L * 1024 * 1024) {
                    parallelDownload(url, outFile, probe.total, threads, onProgress)
                } else {
                    singleDownload(url, outFile, probe.total, onProgress)
                }
                if (outFile.length() != probe.total) {
                    throw IllegalStateException("大小不符: ${outFile.length()} != ${probe.total}")
                }
                if (!looksLikeApk(outFile)) {
                    throw IllegalStateException("下载内容不是 APK（可能是镜像错误页）")
                }
                return@withContext Result(outFile, outFile.length(), label)
            } catch (t: Throwable) {
                lastError = t
                outFile.delete()
            }
        }
        throw lastError ?: IllegalStateException("下载失败")
    }

    private fun probe(url: String): Probe {
        val req = Request.Builder().url(url)
            .header("Range", "bytes=0-0")
            .header("User-Agent", "CloudBuild-Android")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiException(resp.code, "HTTP ${resp.code}")
            if (resp.code == 206) {
                val cr = resp.header("Content-Range") ?: ""
                val total = cr.substringAfterLast('/', "0").toLongOrNull() ?: 0
                return Probe(total, true)
            }
            return Probe(resp.body?.contentLength() ?: 0, false)
        }
    }

    private fun singleDownload(
        url: String,
        out: File,
        total: Long,
        onProgress: (Long, Long, Long) -> Unit
    ) {
        val req = Request.Builder().url(url)
            .header("User-Agent", "CloudBuild-Android")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiException(resp.code, "HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("空响应")
            var done = 0L
            val sw = System.currentTimeMillis()
            var last = 0L
            out.outputStream().use { o ->
                body.byteStream().use { input ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val r = input.read(buf)
                        if (r <= 0) break
                        o.write(buf, 0, r)
                        done += r
                        val now = System.currentTimeMillis()
                        if (now - last > 200 || done == total) {
                            last = now
                            val bps = if (now > sw) done * 1000 / (now - sw) else 0
                            onProgress(done, total, bps)
                        }
                    }
                }
            }
        }
    }

    private suspend fun parallelDownload(
        url: String,
        out: File,
        total: Long,
        threads: Int,
        onProgress: (Long, Long, Long) -> Unit
    ) {
        RandomAccessFile(out, "rw").use { it.setLength(total) }
        val done = AtomicLong(0)
        val sw = System.currentTimeMillis()
        val lastEmit = AtomicLong(0)
        val chunk = (total + threads - 1) / threads

        coroutineScope {
            (0 until threads).map { i ->
                val start = i * chunk
                val end = minOf(total - 1, start + chunk - 1)
                async(Dispatchers.IO) {
                    if (start > end) return@async
                    val req = Request.Builder().url(url)
                        .header("Range", "bytes=$start-$end")
                        .header("User-Agent", "CloudBuild-Android")
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.code != 206 && resp.code != 200) {
                            throw ApiException(resp.code, "分片 HTTP ${resp.code}")
                        }
                        if (resp.code == 200 && threads > 1) {
                            throw IllegalStateException("镜像不支持分段")
                        }
                        RandomAccessFile(out, "rw").use { raf ->
                            raf.seek(start)
                            resp.body!!.byteStream().use { input ->
                                val buf = ByteArray(128 * 1024)
                                var pos = start
                                while (pos <= end) {
                                    val want = minOf(buf.size.toLong(), end - pos + 1).toInt()
                                    val r = input.read(buf, 0, want)
                                    if (r <= 0) break
                                    raf.write(buf, 0, r)
                                    pos += r
                                    val d = done.addAndGet(r.toLong())
                                    val now = System.currentTimeMillis()
                                    if (now - lastEmit.get() > 200 || d >= total) {
                                        if (lastEmit.compareAndSet(lastEmit.get(), now)) {
                                            val bps = if (now > sw) d * 1000 / (now - sw) else 0
                                            onProgress(d, total, bps)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        onProgress(total, total, if (System.currentTimeMillis() > sw) total * 1000 / (System.currentTimeMillis() - sw) else 0)
    }

    private fun looksLikeApk(f: File): Boolean {
        if (f.length() < 4) return false
        return f.inputStream().use { i ->
            val b = ByteArray(4); i.read(b)
            b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()
        }
    }
}