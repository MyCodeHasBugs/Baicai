package com.browns.cloudbuild

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HistoryItem(
    val buildId: String,
    val time: Long,
    val repo: String,
    val variant: String,
    val apkTag: String,
    val apkName: String,
    val size: Long,
    val runUrl: String,
    val success: Boolean
)

class HistoryStore private constructor(ctx: Context) {

    private val sp = ctx.getSharedPreferences("cloudbuild_history", Context.MODE_PRIVATE)

    fun list(): List<HistoryItem> {
        val raw = sp.getString("items", "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HistoryItem(
                    buildId = o.optString("buildId"),
                    time = o.optLong("time"),
                    repo = o.optString("repo"),
                    variant = o.optString("variant"),
                    apkTag = o.optString("apkTag"),
                    apkName = o.optString("apkName"),
                    size = o.optLong("size"),
                    runUrl = o.optString("runUrl"),
                    success = o.optBoolean("success")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun add(item: HistoryItem) {
        val now = list().filterNot { it.buildId == item.buildId }
        val arr = JSONArray()
        (listOf(item) + now).take(30).forEach { h ->
            arr.put(JSONObject().apply {
                put("buildId", h.buildId)
                put("time", h.time)
                put("repo", h.repo)
                put("variant", h.variant)
                put("apkTag", h.apkTag)
                put("apkName", h.apkName)
                put("size", h.size)
                put("runUrl", h.runUrl)
                put("success", h.success)
            })
        }
        sp.edit().putString("items", arr.toString()).apply()
    }

    fun clear() = sp.edit().remove("items").apply()

    companion object {
        @Volatile private var inst: HistoryStore? = null
        fun get(ctx: Context): HistoryStore =
            inst ?: synchronized(this) {
                inst ?: HistoryStore(ctx.applicationContext).also { inst = it }
            }
    }
}