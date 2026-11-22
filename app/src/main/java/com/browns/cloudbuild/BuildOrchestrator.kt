package com.browns.cloudbuild

import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * 全流程编排：
 * 打包 → 分片并发上传 → 触发 workflow → 轮询状态 → 镜像选优多线程下载 → 落盘。
 */
class BuildOrchestrator(
    private val ctx: Context,
    private val cfg: Snapshot
) {

    data class Snapshot(
        val owner: String,
        val repo: String,
        val branch: String,
        val workflow: String,
        val token: String,
        val variant: String,
        val accelerate: Boolean,
        val uploadConcurrency: Int,
        val downloadThreads: Int,
        val shardMb: Int,
        val mirrors: String
    )

    private val client = GithubClient(cfg.owner, cfg.repo, cfg.token)

    suspend fun run(uri: Uri, isDir: Boolean) {
        val buildId = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val inputTag = "in-$buildId"
        val apkTag = "apk-$buildId"
        BuildState.reset(buildId)
        BuildState.log("构建: ${cfg.variant}  分片: ${cfg.shardMb}MB  上传并发: ${cfg.uploadConcurrency}  下载线程: ${cfg.downloadThreads}")

        var packed: ProjectPacker.Packed? = null
        var shards: List<File> = emptyList()
        var releaseId: Long? = null
        var runId: Long? = null

        try {
            checkCancel()

            // ---------- 1. 打包 ----------
            BuildState.stage(Stage.PACKING, "整理工程文件…", indeterminate = true)
            packed = if (isDir) ProjectPacker.fromTree(ctx, uri) { BuildState.log(it) }
            else ProjectPacker.fromZip(ctx, uri) { BuildState.log(it) }
            val zip = packed.file
            BuildState.log("待上传: ${zip.name}  ${Fmt.bytes(zip.length())}")

            // ---------- 2. 分片并发上传 ----------
            BuildState.stage(Stage.UPLOADING, "创建上传通道…", 0)
            val tag = inputTag
            val release = client.createRelease(tag, "输入 $buildId", "CloudBuild 上传的工程快照，构建完成后可删除。")
            releaseId = release.id
            BuildState.log("Release 已创建: $tag")

            shards = splitFile(zip)
            BuildState.log("已切成 ${shards.size} 个分片，开始并发上传")

            val total = shards.sumOf { it.length() }
            val doneAll = AtomicLong(0)
            val sem = Semaphore(cfg.uploadConcurrency)
            val sw = System.currentTimeMillis()
            var lastEmit = 0L

            coroutineScope {
                shards.map { shard ->
                    async(Dispatchers.IO) {
                        sem.withPermit {
                            val shardPrev = AtomicLong(0)
                            client.uploadAsset(release.uploadUrl, shard.name, shard) { sent, _ ->
                                val delta = sent - shardPrev.getAndSet(sent)
                                val d = doneAll.addAndGet(delta)
                                val now = System.currentTimeMillis()
                                if (now - lastEmit > 200 || d >= total) {
                                    synchronized(doneAll) {
                                        if (now - lastEmit > 200) {
                                            lastEmit = now
                                            val bps = if (now > sw) d * 1000 / (now - sw) else 0
                                            val p = ((d * 100) / total).toInt()
                                            BuildState.progress(p, d, total, bps, "上传工程 ${p}%")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
            BuildState.progress(100, total, total, 0, "上传完成")
            BuildState.log("上传完成，共 ${Fmt.bytes(total)}")

            // ---------- 3. 触发云端构建 ----------
            checkCancel()
            BuildState.stage(Stage.DISPATCHING, "触发 GitHub Actions…", indeterminate = true)
            val inputs = mapOf(
                "tag" to inputTag,
                "apk_tag" to apkTag,
                "variant" to cfg.variant
            )
            runId = client.dispatchWorkflow(cfg.workflow, cfg.branch, inputs)
            if (runId == null) {
                BuildState.log("未直接拿到 run id，回退查询最新 run…")
                var found: GithubClient.RunInfo? = null
                var tries = 0
                while (found == null && tries < 10) {
                    delay(2000)
                    found = client.latestDispatchRun()
                    tries++
                }
                runId = found?.id
            }
            if (runId == null) throw IllegalStateException("触发成功但找不到构建记录，请到 Actions 页面查看")
            BuildState.log("Run #$runId 已排队")

            // ---------- 4. 轮询 ----------
            BuildState.stage(Stage.QUEUED, "云端排队中…", indeterminate = true)
            val started = System.currentTimeMillis()
            var run = client.run(runId)
            var lastStatus = ""
            while (true) {
                checkCancel()
                val elapsed = System.currentTimeMillis() - started
                when {
                    run.status == "queued" -> BuildState.stage(
                        Stage.QUEUED, "云端排队中… 已等待 ${Fmt.duration(elapsed)}", indeterminate = true
                    )
                    run.status == "in_progress" || run.status == "waiting" -> BuildState.stage(
                        Stage.COMPILING, "云端编译中… 已用 ${Fmt.duration(elapsed)}", indeterminate = true
                    )
                }
                if (run.status != lastStatus) {
                    BuildState.log("状态: ${run.status}${if (run.conclusion != null) " / ${run.conclusion}" else ""}")
                    lastStatus = run.status
                }
                if (run.status == "completed") {
                    if (run.conclusion != "success") {
                        throw IllegalStateException("云端构建失败（${run.conclusion}），请重试或联系提供者")
                    }
                    break
                }
                if (elapsed > 90L * 60 * 1000) {
                    throw IllegalStateException("等待超时（90 分钟），请到 Actions 页面查看：${run.htmlUrl}")
                }
                delay(2000)
                run = client.run(runId)
            }
            BuildState.log("云端构建成功")

            // ---------- 5. 下载 APK ----------
            checkCancel()
            BuildState.stage(Stage.DOWNLOADING, "查找 APK 产物…", indeterminate = true)
            val assets = client.assetsOf(apkTag)
            val apkAsset = assets.filter { it.name.endsWith(".apk", true) }
                .maxByOrNull { it.size }
                ?: throw IllegalStateException("Release $apkTag 里没有 APK 产物")
            BuildState.log("找到 ${apkAsset.name} (${Fmt.bytes(apkAsset.size)})")

            val outDir = File(
                ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir,
                "CloudBuild"
            ).apply { mkdirs() }
            val outFile = File(outDir, "app-${cfg.variant}-$buildId.apk")

            val result = ApkDownloader.download(
                ctx = ctx,
                originalUrl = apkAsset.browserUrl,
                outFile = outFile,
                threads = cfg.downloadThreads,
                accelerate = cfg.accelerate,
                mirrors = cfg.mirrors
            ) { done, total, bps ->
                val p = if (total > 0) ((done * 100) / total).toInt() else 0
                BuildState.progress(p, done, total, bps, "下载 APK $p%")
            }

            BuildState.done(result.file, "", result.mirror)
            BuildState.log("下载完成: ${result.file.name}")
            val publicRef = ApkStore.publishToDownloads(ctx, result.file)
            if (publicRef != null) {
                BuildState.log("已另存到 Download/CloudBuild/${result.file.name}")
            } else {
                BuildState.log("另存到公共 Download 失败，私有目录里的 APK 仍可安装")
            }
            App.history.add(
                HistoryItem(
                    buildId = buildId,
                    time = System.currentTimeMillis(),
                    repo = "${cfg.owner}/${cfg.repo}",
                    variant = cfg.variant,
                    apkTag = apkTag,
                    apkName = result.file.name,
                    size = result.file.length(),
                    runUrl = "",
                    success = true
                )
            )
        } catch (c: CancellationException) {
            BuildState.cancel()
            BuildState.log("已取消")
            throw c
        } catch (t: Throwable) {
            val msg = t.message ?: t.javaClass.simpleName
            BuildState.fail(msg)
            BuildState.log("失败: $msg")
            App.history.add(
                HistoryItem(
                    buildId = buildId,
                    time = System.currentTimeMillis(),
                    repo = "${cfg.owner}/${cfg.repo}",
                    variant = cfg.variant,
                    apkTag = apkTag,
                    apkName = "",
                    size = 0,
                    runUrl = "",
                    success = false
                )
            )
        } finally {
            // 清掉上传分片和临时 zip，节省手机空间（取消时也要执行）
            withContext(NonCancellable + Dispatchers.IO) {
                shards.forEach { runCatching { it.delete() } }
                shards.firstOrNull()?.parentFile?.delete()
                runCatching { packed?.file?.delete() }
            }
            if (releaseId != null && runId == null) {
                runCatching { client.deleteRelease(releaseId) }
            }
        }
    }

    private fun checkCancel() {
        if (BuildState.cancelRequested) throw CancellationException("用户取消")
    }

    /** 把 zip 按 shardMb 切成 project.zip.part000 / part001 … */
    private fun splitFile(zip: File): List<File> {
        val shardBytes = cfg.shardMb.toLong() * 1024 * 1024
        val dir = File(ctx.cacheDir, "shards-${zip.nameWithoutExtension}").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val parts = mutableListOf<File>()
        zip.inputStream().buffered().use { input ->
            var index = 0
            while (true) {
                val part = File(dir, "project.zip.part%03d".format(index))
                var written = 0L
                part.outputStream().buffered().use { o ->
                    val buf = ByteArray(256 * 1024)
                    while (written < shardBytes) {
                        val want = minOf(buf.size.toLong(), shardBytes - written).toInt()
                        val r = input.read(buf, 0, want)
                        if (r <= 0) break
                        o.write(buf, 0, r)
                        written += r
                    }
                }
                if (written == 0L) {
                    part.delete()
                    break
                }
                parts += part
                index++
                if (index > 200) break
            }
        }
        if (parts.isEmpty()) throw IllegalStateException("工程为空")
        return parts
    }
}