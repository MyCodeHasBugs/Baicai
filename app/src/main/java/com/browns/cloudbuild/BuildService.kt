package com.browns.cloudbuild

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** 前台服务承载整条构建链，退出界面也不会中断。 */
class BuildService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var notifyJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                BuildState.cancelRequested = true
                job?.cancel()
                BuildState.log("正在取消…")
                if (job == null) stopSelf()
            }
            ACTION_START -> {
                val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
                val isDir = intent.getBooleanExtra(EXTRA_IS_DIR, false)
                if (uri == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                begin(uri, isDir)
            }
        }
        return START_NOT_STICKY
    }

    private fun begin(uri: Uri, isDir: Boolean) {
        BuildState.cancelRequested = false
        startInForeground(buildNotification("准备中…", 0, true))

        val p = App.prefs
        val cfg = BuildOrchestrator.Snapshot(
            owner = p.owner,
            repo = p.repo,
            branch = p.branch,
            workflow = p.workflow,
            token = p.token,
            variant = p.variant,
            accelerate = p.accelerate,
            uploadConcurrency = p.uploadConcurrency,
            downloadThreads = p.downloadThreads,
            shardMb = p.shardMb,
            mirrors = p.mirrors
        )

        job?.cancel()
        job = scope.launch {
            try {
                BuildOrchestrator(this@BuildService, cfg).run(uri, isDir)
            } catch (c: CancellationException) {
                BuildState.cancel()
                BuildState.log("已取消")
            } catch (t: Throwable) {
                BuildState.fail(t.message ?: t.javaClass.simpleName)
                BuildState.log("失败: ${t.message}")
            } finally {
                notifyJob?.cancel()
                delayNotifyFinal()
                stopForegroundCompat()
                stopSelf()
            }
        }

        notifyJob?.cancel()
        notifyJob = scope.launch {
            BuildState.state.collectLatest { s ->
                val text = when (s.stage) {
                    Stage.UPLOADING -> "${s.message}  ${Fmt.speed(s.speedBps)}"
                    Stage.DOWNLOADING -> "${s.message}  ${Fmt.speed(s.speedBps)}"
                    Stage.COMPILING, Stage.QUEUED -> s.message
                    Stage.DONE -> "构建成功，APK 已下载"
                    Stage.FAILED -> "构建失败"
                    Stage.CANCELLED -> "已取消"
                    else -> s.message.ifBlank { "处理中…" }
                }
                val indeterminate = s.indeterminate || s.stage in setOf(Stage.PACKING, Stage.DISPATCHING, Stage.QUEUED, Stage.COMPILING)
                val notif = buildNotification(text, s.percent, indeterminate, s.stage == Stage.DONE || s.stage == Stage.FAILED || s.stage == Stage.CANCELLED)
                notificationManager().notify(NOTIF_ID, notif)
            }
        }
    }

    private suspend fun delayNotifyFinal() {
        kotlinx.coroutines.delay(600)
    }

    private fun startInForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
    }

    private fun buildNotification(
        text: String,
        percent: Int,
        indeterminate: Boolean,
        finished: Boolean = false
    ): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, BuildService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_build)
            .setContentTitle("云端编译")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(!finished)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (!finished) {
            b.setProgress(100, percent.coerceIn(0, 100), indeterminate)
            b.addAction(0, "取消", cancel)
        }
        return b.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL, "云端构建", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "上传、云端编译、下载 APK 的进度" }
            notificationManager().createNotificationChannel(ch)
        }
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "cloudbuild"
        private const val NOTIF_ID = 1001
        const val ACTION_START = "com.browns.cloudbuild.START"
        const val ACTION_CANCEL = "com.browns.cloudbuild.CANCEL"
        const val EXTRA_URI = "uri"
        const val EXTRA_IS_DIR = "is_dir"

        fun start(ctx: Context, uri: Uri, isDir: Boolean) {
            val i = Intent(ctx, BuildService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URI, uri.toString())
                putExtra(EXTRA_IS_DIR, isDir)
            }
            ContextCompat.startForegroundService(ctx, i)
        }

        fun cancel(ctx: Context) {
            val i = Intent(ctx, BuildService::class.java).setAction(ACTION_CANCEL)
            ctx.startService(i)
        }
    }
}