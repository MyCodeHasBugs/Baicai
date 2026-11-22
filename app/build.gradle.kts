import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Token 不写进仓库：构建时从环境变量 / gradle 属性读取，Base64 后注入 BuildConfig。
// GitHub Actions 里由 secrets.CLOUD_TOKEN 提供。
val cloudToken: String = System.getenv("CLOUD_TOKEN")
    ?: (project.findProperty("CLOUD_TOKEN") as String?)
    ?: ""
val tokenB64: String = if (cloudToken.isBlank()) "" else
    Base64.getEncoder().encodeToString(cloudToken.toByteArray())

android {
    namespace = "com.browns.cloudbuild"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.browns.cloudbuild"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "TOKEN_B64", "\"$tokenB64\"")
        buildConfigField("String", "DEFAULT_OWNER", "\"MyCodeHasBugs\"")
        buildConfigField("String", "DEFAULT_REPO", "\"Baicai\"")
        buildConfigField("String", "DEFAULT_BRANCH", "\"main\"")
        buildConfigField("String", "DEFAULT_WORKFLOW", "\"cloud-build.yml\"")
    }

    buildTypes {
        getByName("release") {
            // 混淆 + 收缩，提高逆向后直接抠字符串的门槛
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}