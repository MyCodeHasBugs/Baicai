package com.browns.cloudbuild

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvProject: TextView
    private lateinit var tvProjectMeta: TextView
    private lateinit var tvStep: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvApkName: TextView
    private lateinit var tvApkMeta: TextView
    private lateinit var tvRunLink: TextView
    private lateinit var cardProgress: View
    private lateinit var cardResult: View
    private lateinit var progress: LinearProgressIndicator
    private lateinit var btnBuild: MaterialButton
    private lateinit var btnCancel: MaterialButton
    private lateinit var historyContainer: LinearLayout

    private var selUri: Uri? = null
    private var selIsDir = false
    private var selName = ""
    private var lastApk: File? = null

    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        selUri = uri
        selIsDir = false
        selName = queryName(uri) ?: "project.zip"
        saveSelection()
    }

    private val pickDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        selUri = uri
        selIsDir = true
        selName = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri)?.name ?: "工程目录"
        saveSelection()
    }

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        restoreSelection()
        bindActions()
        observeState()
        renderHistory()
    }

    override fun onResume() {
        super.onResume()
        renderHistory()
    }

    private fun bindViews() {
        tvProject = findViewById(R.id.tvProject)
        tvProjectMeta = findViewById(R.id.tvProjectMeta)
        tvStep = findViewById(R.id.tvStep)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvLog = findViewById(R.id.tvLog)
        tvApkName = findViewById(R.id.tvApkName)
        tvApkMeta = findViewById(R.id.tvApkMeta)
        tvRunLink = findViewById(R.id.tvRunLink)
        cardProgress = findViewById(R.id.cardProgress)
        cardResult = findViewById(R.id.cardResult)
        progress = findViewById(R.id.progress)
        btnBuild = findViewById(R.id.btnBuild)
        btnCancel = findViewById(R.id.btnCancel)
        historyContainer = findViewById(R.id.historyContainer)

        tvLog.movementMethod = ScrollingMovementMethod()
    }

    private fun bindActions() {
        findViewById<MaterialButton>(R.id.btnPickZip).setOnClickListener {
            pickZip.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed"))
        }
        findViewById<MaterialButton>(R.id.btnPickDir).setOnClickListener {
            pickDir.launch(null)
        }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { showSettings() }
        findViewById<MaterialButton>(R.id.btnClearHistory).setOnClickListener { confirmClearHistory() }

        findViewById<MaterialSwitch>(R.id.swAccelerate).apply {
            isChecked = App.prefs.accelerate
            setOnCheckedChangeListener { _, checked -> App.prefs.accelerate = checked }
        }

        val toggle = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleVariant)
        if (App.prefs.variant == "release") {
            toggle.check(R.id.btnRelease)
        } else {
            toggle.check(R.id.btnDebug)
        }
        toggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                App.prefs.variant = if (checkedId == R.id.btnRelease) "release" else "debug"
            }
        }

        btnBuild.setOnClickListener { startBuild() }
        btnCancel.setOnClickListener {
            BuildService.cancel(this)
            btnCancel.isEnabled = false
        }

        findViewById<MaterialButton>(R.id.btnInstall).setOnClickListener {
            lastApk?.let { ApkInstaller.install(this, it) }
                ?: Toast.makeText(this, "还没有可安装的 APK", Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.btnShare).setOnClickListener {
            lastApk?.let { ApkInstaller.share(this, it) }
                ?: Toast.makeText(this, "还没有可导出的 APK", Toast.LENGTH_SHORT).show()
        }
        tvRunLink.setOnClickListener {
            val u = tvRunLink.tag as? String ?: return@setOnClickListener
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
        }
    }

    private fun restoreSelection() {
        val p = App.prefs
        val raw = p.lastUri ?: return
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return
        selUri = uri
        selIsDir = p.lastIsDir
        selName = p.lastName ?: if (selIsDir) "工程目录" else "project.zip"
        updateProjectViews()
    }

    private fun saveSelection() {
        val p = App.prefs
        p.lastUri = selUri?.toString()
        p.lastIsDir = selIsDir
        p.lastName = selName
        updateProjectViews()
    }

    private fun updateProjectViews() {
        tvProject.text = selName.ifBlank { getString(R.string.no_project) }
        tvProjectMeta.text = when {
            selUri == null -> "支持标准 Gradle 工程，ZIP 或目录都可以"
            selIsDir -> "目录工程 · 上传时自动排除 build/.gradle/.git"
            else -> "ZIP 工程 · 上传前会校验文件头"
        }
    }

    private fun startBuild() {
        val uri = selUri
        if (uri == null) {
            Toast.makeText(this, "先选择一个工程（ZIP 或目录）", Toast.LENGTH_SHORT).show()
            return
        }
        if (App.prefs.token.isBlank()) {
            Toast.makeText(this, "此安装包未内置云端密钥，请联系提供者重新构建", Toast.LENGTH_LONG).show()
            return
        }
        if (BuildState.state.value.stage !in setOf(
                Stage.IDLE, Stage.DONE, Stage.FAILED, Stage.CANCELLED
            )
        ) {
            Toast.makeText(this, "已有构建在进行中", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        cardResult.visibility = View.GONE
        btnBuild.isEnabled = false
        btnCancel.isEnabled = true
        BuildService.start(this, uri, selIsDir)
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                BuildState.state.collect { render(it) }
            }
        }
    }

    private fun render(s: BuildUiState) {
        when (s.stage) {
            Stage.IDLE -> {
                cardProgress.visibility = View.GONE
                cardResult.visibility = View.GONE
                btnBuild.isEnabled = true
                btnCancel.visibility = View.GONE
            }
            Stage.CANCELLED, Stage.FAILED -> {
                cardProgress.visibility = View.VISIBLE
                cardResult.visibility = View.GONE
                btnBuild.isEnabled = true
                btnCancel.visibility = View.GONE
                progress.isIndeterminate = false
                if (s.stage == Stage.FAILED) progress.progress = 0
                tvStep.text = if (s.stage == Stage.FAILED) "构建失败" else "已取消"
                tvSpeed.text = s.error ?: s.message
                renderLog(s.log)
            }
            Stage.DONE -> {
                cardProgress.visibility = View.VISIBLE
                cardResult.visibility = View.VISIBLE
                btnBuild.isEnabled = true
                btnCancel.visibility = View.GONE
                progress.isIndeterminate = false
                progress.progress = 100
                tvStep.text = "构建成功"
                tvSpeed.text = "APK 已就绪，可直接安装"
                renderLog(s.log)
                tvApkName.text = s.apkName ?: "-"
                tvApkMeta.text = "${Fmt.bytes(s.apkSize)} · 下载通道 ${s.mirror.ifBlank { "直连" }}"
                lastApk = s.apkPath?.let { File(it) }
                tvRunLink.visibility = View.GONE
            }
            else -> {
                cardProgress.visibility = View.VISIBLE
                cardResult.visibility = View.GONE
                btnBuild.isEnabled = false
                btnCancel.visibility = View.VISIBLE
                btnCancel.isEnabled = true
                progress.isIndeterminate = s.indeterminate
                progress.progress = s.percent
                tvStep.text = s.message.ifBlank { "处理中…" }
                tvSpeed.text = when (s.stage) {
                    Stage.UPLOADING, Stage.DOWNLOADING ->
                        "${Fmt.bytes(s.bytesDone)} / ${Fmt.bytes(s.bytesTotal)}  ·  ${Fmt.speed(s.speedBps)}"
                    else -> "云端任务：${s.buildId}"
                }
                renderLog(s.log)
            }
        }
    }

    private fun renderLog(lines: List<String>) {
        tvLog.text = lines.takeLast(60).joinToString("\n")
        tvLog.post { tvLog.scrollTo(0, tvLog.layout?.height ?: 0) }
    }

    private fun showSettings() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val p = App.prefs
        val etMirrors = view.findViewById<EditText>(R.id.etMirrors)
        val etUpload = view.findViewById<EditText>(R.id.etUploadConc)
        val etThreads = view.findViewById<EditText>(R.id.etDownloadThreads)
        val etShard = view.findViewById<EditText>(R.id.etShardMb)

        etMirrors.setText(p.mirrors)
        etUpload.setText(p.uploadConcurrency.toString())
        etThreads.setText(p.downloadThreads.toString())
        etShard.setText(p.shardMb.toString())

        AlertDialog.Builder(this)
            .setTitle("高级设置")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                p.mirrors = etMirrors.text.toString()
                p.uploadConcurrency = etUpload.text.toString().toIntOrNull() ?: p.uploadConcurrency
                p.downloadThreads = etThreads.text.toString().toIntOrNull() ?: p.downloadThreads
                p.shardMb = etShard.text.toString().toIntOrNull() ?: p.shardMb
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renderHistory() {
        historyContainer.removeAllViews()
        val items = App.history.list()
        if (items.isEmpty()) {
            val tv = TextView(this).apply {
                text = "还没有构建记录"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 13f
                setPadding(8, 12, 8, 12)
            }
            historyContainer.addView(tv)
            return
        }
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        for (h in items) {
            val v = layoutInflater.inflate(R.layout.item_history, historyContainer, false)
            v.findViewById<TextView>(R.id.tvHistoryTitle).text = "云端构建 · ${h.variant}"
            val status = v.findViewById<TextView>(R.id.tvHistoryStatus)
            status.text = if (h.success) "成功" else "失败"
            status.setTextColor(
                ContextCompat.getColor(this, if (h.success) R.color.success else R.color.danger)
            )
            v.findViewById<TextView>(R.id.tvHistoryMeta).text =
                "${fmt.format(Date(h.time))}  ·  ${Fmt.bytes(h.size)}${if (h.success) "  ·  点击安装" else ""}"
            if (h.success) {
                v.setOnClickListener {
                    val f = File(
                        File(getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "CloudBuild"),
                        h.apkName
                    )
                    if (f.exists()) ApkInstaller.install(this, f)
                    else Toast.makeText(this, "本地 APK 已清理，请重新构建", Toast.LENGTH_SHORT).show()
                }
            }
            historyContainer.addView(v)
        }
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle("清空构建历史")
            .setMessage("只清记录，不删除已下载的 APK。")
            .setPositiveButton("清空") { _, _ ->
                App.history.clear()
                renderHistory()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun queryName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}