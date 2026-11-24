package com.browns.cloudbuild

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** GitHub REST API 封装。所有阻塞 IO 都切到 IO 线程。 */
class GithubClient(
    private val owner: String,
    private val repo: String,
    private val token: String
) {

    data class ReleaseInfo(val id: Long, val tag: String, val uploadUrl: String)
    data class AssetInfo(val id: Long, val name: String, val size: Long, val browserUrl: String)
    data class RunInfo(
        val id: Long,
        val status: String,
        val conclusion: String?,
        val htmlUrl: String,
        val attempt: Int,
        val title: String
    )

    private val api = "https://api.github.com"
    private val uploads = "https://uploads.github.com"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.MINUTES)
        .callTimeout(20, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()

    private fun request(url: String, method: String = "GET", body: RequestBody? = null): Request {
        val b = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "CloudBuild-Android")
        when (method) {
            "GET" -> b.get()
            "POST" -> b.post(body ?: ByteArray(0).toRequestBody(null))
            "DELETE" -> b.delete()
            "PATCH" -> b.patch(body ?: ByteArray(0).toRequestBody(null))
        }
        return b.build()
    }

    private suspend fun executeJson(req: Request): JSONObject = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(text).optString("message") }.getOrDefault("")
                throw ApiException(
                    resp.code,
                    when {
                        msg.isNotBlank() -> "${resp.code} $msg"
                        resp.code == 401 -> "401 Token 无效或已过期"
                        resp.code == 403 -> "403 权限不足（Token 需要 Contents + Actions 读写权限）"
                        resp.code == 404 -> "404 仓库或资源不存在（检查仓库名和 Token 授权范围）"
                        resp.code == 422 -> "422 参数不合法"
                        else -> "HTTP ${resp.code}"
                    }
                )
            }
            if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private suspend fun executeRaw(req: Request): Pair<Int, ByteArray> = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { resp ->
            resp.code to (resp.body?.bytes() ?: ByteArray(0))
        }
    }

    /** 创建（或复用）上传用的 release。 */
    suspend fun createRelease(tag: String, name: String, body: String): ReleaseInfo {
        val json = JSONObject().apply {
            put("tag_name", tag)
            put("name", name)
            put("body", body)
            put("draft", false)
            put("prerelease", false)
        }
        val resp = executeJson(
            request("$api/repos/$owner/$repo/releases", "POST", json.toString().toRequestBody(JSON))
        )
        val uploadUrl = resp.optString("upload_url").substringBefore("{")
        return ReleaseInfo(resp.getLong("id"), resp.getString("tag_name"), uploadUrl)
    }

    suspend fun releaseByTag(tag: String): ReleaseInfo? {
        return try {
            val resp = executeJson(request("$api/repos/$owner/$repo/releases/tags/${enc(tag)}"))
            ReleaseInfo(resp.getLong("id"), resp.getString("tag_name"), resp.optString("upload_url").substringBefore("{"))
        } catch (e: ApiException) {
            if (e.code == 404) null else throw e
        }
    }

    suspend fun assetsOf(tag: String): List<AssetInfo> {
        val resp = executeJson(request("$api/repos/$owner/$repo/releases/tags/${enc(tag)}"))
        val arr = resp.optJSONArray("assets") ?: JSONArray()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            AssetInfo(
                id = o.getLong("id"),
                name = o.getString("name"),
                size = o.optLong("size"),
                browserUrl = o.optString("browser_download_url")
            )
        }
    }

    /** 读取 Release 的说明（用于失败时回传编译错误）。 */
    suspend fun releaseBody(tag: String): String? {
        return try {
            val resp = executeJson(request("$api/repos/$owner/$repo/releases/tags/${enc(tag)}"))
            resp.optString("body").ifBlank { null }
        } catch (e: ApiException) {
            if (e.code == 404) null else throw e
        }
    }

    suspend fun deleteRelease(id: Long) {
        runCatching { executeJson(request("$api/repos/$owner/$repo/releases/$id", "DELETE")) }
    }

    suspend fun deleteAsset(assetId: Long) {
        runCatching { executeJson(request("$api/repos/$owner/$repo/releases/assets/$assetId", "DELETE")) }
    }

    /** 单请求上传一个分片，带进度回调。 */
    suspend fun uploadAsset(
        uploadUrl: String,
        assetName: String,
        file: File,
        onProgress: (Long, Long) -> Unit
    ): AssetInfo = withContext(Dispatchers.IO) {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = file.length()
            override fun writeTo(sink: BufferedSink) {
                var sent = 0L
                val total = file.length()
                file.source().use { src ->
                    val buf = Buffer()
                    while (true) {
                        val read = src.read(buf, 64 * 1024)
                        if (read == -1L) break
                        sink.write(buf, read)
                        sent += read
                        onProgress(sent, total)
                    }
                }
            }
        }
        val url = "$uploadUrl?name=${enc(assetName)}"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("Content-Type", "application/octet-stream")
            .header("User-Agent", "CloudBuild-Android")
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(text).optString("message") }.getOrDefault("")
                throw ApiException(resp.code, "上传失败 ${resp.code} $msg")
            }
            val o = JSONObject(text)
            AssetInfo(o.getLong("id"), o.getString("name"), o.optLong("size"), o.optString("browser_download_url"))
        }
    }

    /** 触发 workflow_dispatch，返回 run id（GitHub 支持 return_run_details 时）。 */
    suspend fun dispatchWorkflow(workflowFile: String, ref: String, inputs: Map<String, String>): Long? {
        val obj = JSONObject().apply {
            put("ref", ref)
            put("return_run_details", true)
            put("inputs", JSONObject().apply { inputs.forEach { (k, v) -> put(k, v) } })
        }
        val resp = executeJson(
            request(
                "$api/repos/$owner/$repo/actions/workflows/${enc(workflowFile)}/dispatches",
                "POST",
                obj.toString().toRequestBody(JSON)
            )
        )
        val id = resp.optLong("workflow_run_id", 0)
        return if (id > 0) id else null
    }

    suspend fun run(runId: Long): RunInfo {
        val o = executeJson(request("$api/repos/$owner/$repo/actions/runs/$runId"))
        return o.toRun()
    }

    suspend fun latestDispatchRun(): RunInfo? {
        val o = executeJson(request("$api/repos/$owner/$repo/actions/runs?event=workflow_dispatch&per_page=5"))
        val arr = o.optJSONArray("workflow_runs") ?: return null
        if (arr.length() == 0) return null
        return arr.getJSONObject(0).toRun()
    }

    private fun JSONObject.toRun() = RunInfo(
        id = optLong("id"),
        status = optString("status"),
        conclusion = if (isNull("conclusion")) null else optString("conclusion").ifBlank { null },
        htmlUrl = optString("html_url"),
        attempt = optInt("run_attempt", 1),
        title = optString("display_title").ifBlank { optString("name") }
    )

    /** 备用通道：直接下载 run 的 artifact zip（需要 token）。 */
    suspend fun downloadArtifact(runId: Long, dest: File, onProgress: (Long, Long) -> Unit): Boolean {
        val list = executeJson(request("$api/repos/$owner/$repo/actions/runs/$runId/artifacts"))
        val arr = list.optJSONArray("artifacts") ?: return false
        var artifactUrl: String? = null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (!o.optBoolean("expired")) {
                artifactUrl = o.optString("archive_download_url")
                break
            }
        }
        val url = artifactUrl ?: return false
        val (code, bytes) = executeRaw(request(url))
        if (code !in 200..299) return false
        dest.writeBytes(bytes)
        onProgress(bytes.size.toLong(), bytes.size.toLong())
        return true
    }

    suspend fun cancelRun(runId: Long) {
        runCatching {
            executeJson(
                request("$api/repos/$owner/$repo/actions/runs/$runId/cancel", "POST")
            )
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}