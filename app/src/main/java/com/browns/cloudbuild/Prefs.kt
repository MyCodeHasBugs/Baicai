package com.browns.cloudbuild

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** 加密保存 token / 仓库配置 / 上次选择的工程。 */
class Prefs private constructor(ctx: Context) {

    private val sp: SharedPreferences = try {
        val key = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            ctx, "cloudbuild_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (t: Throwable) {
        // 个别机型 keystore 异常，退回普通存储，保证 App 可用
        ctx.getSharedPreferences("cloudbuild_plain", Context.MODE_PRIVATE)
    }

    var owner: String
        get() = sp.getString("owner", "MyCodeHasBugs") ?: "MyCodeHasBugs"
        set(v) = sp.edit().putString("owner", v.trim()).apply()

    var repo: String
        get() = sp.getString("repo", "Baicai") ?: "Baicai"
        set(v) = sp.edit().putString("repo", v.trim()).apply()

    var branch: String
        get() = sp.getString("branch", "main") ?: "main"
        set(v) = sp.edit().putString("branch", v.trim()).apply()

    var workflow: String
        get() = sp.getString("workflow", "cloud-build.yml") ?: "cloud-build.yml"
        set(v) = sp.edit().putString("workflow", v.trim()).apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v.trim()).apply()

    var variant: String
        get() = sp.getString("variant", "debug") ?: "debug"
        set(v) = sp.edit().putString("variant", v).apply()

    var accelerate: Boolean
        get() = sp.getBoolean("accelerate", true)
        set(v) = sp.edit().putBoolean("accelerate", v).apply()

    /** 每行一个镜像前缀，空行表示直连。 */
    var mirrors: String
        get() = sp.getString("mirrors", DEFAULT_MIRRORS) ?: DEFAULT_MIRRORS
        set(v) = sp.edit().putString("mirrors", v).apply()

    var uploadConcurrency: Int
        get() = sp.getInt("uploadConcurrency", 4).coerceIn(1, 8)
        set(v) = sp.edit().putInt("uploadConcurrency", v.coerceIn(1, 8)).apply()

    var downloadThreads: Int
        get() = sp.getInt("downloadThreads", 6).coerceIn(1, 16)
        set(v) = sp.edit().putInt("downloadThreads", v.coerceIn(1, 16)).apply()

    var shardMb: Int
        get() = sp.getInt("shardMb", 24).coerceIn(4, 64)
        set(v) = sp.edit().putInt("shardMb", v.coerceIn(4, 64)).apply()

    var lastUri: String?
        get() = sp.getString("lastUri", null)
        set(v) = sp.edit().putString("lastUri", v).apply()

    var lastIsDir: Boolean
        get() = sp.getBoolean("lastIsDir", false)
        set(v) = sp.edit().putBoolean("lastIsDir", v).apply()

    var lastName: String?
        get() = sp.getString("lastName", null)
        set(v) = sp.edit().putString("lastName", v).apply()

    fun repoFull(): String = "$owner/$repo"

    companion object {
        const val DEFAULT_MIRRORS = "https://ghfast.top/\nhttps://gh-proxy.com/\nhttps://ghproxy.net/\n"

        @Volatile private var inst: Prefs? = null

        fun get(ctx: Context): Prefs =
            inst ?: synchronized(this) {
                inst ?: Prefs(ctx.applicationContext).also { inst = it }
            }
    }
}