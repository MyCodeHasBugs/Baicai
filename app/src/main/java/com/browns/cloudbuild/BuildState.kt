package com.browns.cloudbuild

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Stage { IDLE, PACKING, UPLOADING, DISPATCHING, QUEUED, COMPILING, DOWNLOADING, DONE, FAILED, CANCELLED }

data class BuildUiState(
    val stage: Stage = Stage.IDLE,
    val message: String = "",
    val percent: Int = 0,
    val indeterminate: Boolean = false,
    val speedBps: Long = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val log: List<String> = emptyList(),
    val apkPath: String? = null,
    val apkName: String? = null,
    val apkSize: Long = 0,
    val mirror: String = "",
    val runUrl: String? = null,
    val error: String? = null,
    val buildId: String = ""
)

object BuildState {

    private val _state = MutableStateFlow(BuildUiState())
    val state: StateFlow<BuildUiState> = _state.asStateFlow()

    @Volatile
    var cancelRequested: Boolean = false

    fun reset(buildId: String = "") = _state.update {
        BuildUiState(stage = Stage.PACKING, message = "准备中…", buildId = buildId, indeterminate = true)
    }

    fun set(block: (BuildUiState) -> BuildUiState) = _state.update(block)

    fun stage(stage: Stage, message: String, percent: Int = 0, indeterminate: Boolean = false) =
        _state.update {
            it.copy(stage = stage, message = message, percent = percent, indeterminate = indeterminate)
        }

    fun progress(percent: Int, done: Long, total: Long, speed: Long, message: String? = null) =
        _state.update {
            it.copy(
                percent = percent.coerceIn(0, 100),
                bytesDone = done,
                bytesTotal = total,
                speedBps = speed,
                indeterminate = false,
                message = message ?: it.message
            )
        }

    fun log(line: String) = _state.update {
        it.copy(log = (it.log + line).takeLast(250))
    }

    fun done(apk: java.io.File, runUrl: String, mirror: String) = _state.update {
        it.copy(
            stage = Stage.DONE,
            message = "构建成功",
            percent = 100,
            indeterminate = false,
            apkPath = apk.absolutePath,
            apkName = apk.name,
            apkSize = apk.length(),
            runUrl = runUrl,
            mirror = mirror
        )
    }

    fun fail(message: String) = _state.update {
        it.copy(stage = Stage.FAILED, message = "构建失败", error = message, indeterminate = false)
    }

    fun cancel() = _state.update {
        it.copy(stage = Stage.CANCELLED, message = "已取消", indeterminate = false)
    }
}