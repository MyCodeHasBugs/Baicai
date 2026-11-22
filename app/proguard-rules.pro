# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**

# EncryptedSharedPreferences 依赖反射
-keep class androidx.security.crypto.** { *; }

# 保留 BuildConfig（内置服务配置）
-keep class com.browns.cloudbuild.BuildConfig { *; }