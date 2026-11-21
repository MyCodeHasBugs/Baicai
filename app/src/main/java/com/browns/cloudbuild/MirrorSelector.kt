package com.browns.cloudbuild

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 镜像自动选优：对同一个 GitHub 文件地址，并发测每个镜像前缀，
 * 按实测吞吐排序，失败自动剔除。空前缀代表直连。
 */
class MirrorSelector(mirrorLines: String) {

    data class Candidate(val prefix: String, val bps: Long, val ok: Boolean)

    private val prefixes: List<String> = mirrorLines.lineSequence()
        .map { it.trim() }
        .distinct()
        .toList()
        .ifEmpty { listOf("") }

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun urlFor(prefix: String, original: String): String = if (prefix.isEmpty()) original else prefix + original

    suspend fun rank(originalUrl: String, sampleBytes: Int = 256 * 1024): List<Candidate> = coroutineScope {
        prefixes.map { p ->
            async(Dispatchers.IO) {
                val sw = System.currentTimeMillis()
                try {
                    val req = Request.Builder()
                        .url(urlFor(p, originalUrl))
                        .header("Range", "bytes=0-$sampleBytes")
                        .header("User-Agent", "CloudBuild-Android")
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@async Candidate(p, 0, false)
                        val body = resp.body ?: return@async Candidate(p, 0, false)
                        val input = body.byteStream()
                        val buf = ByteArray(32 * 1024)
                        var total = 0
                        while (total < sampleBytes) {
                            val r = input.read(buf)
                            if (r <= 0) break
                            total += r
                        }
                        val ms = (System.currentTimeMillis() - sw).coerceAtLeast(1)
                        val bps = total.toLong() * 1000 / ms
                        Candidate(p, bps, total > 0)
                    }
                } catch (t: Throwable) {
                    Candidate(p, 0, false)
                }
            }
        }.awaitAll().sortedWith(compareByDescending<Candidate> { it.ok }.thenByDescending { it.bps })
    }
}