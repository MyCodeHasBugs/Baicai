#!/usr/bin/env python3
"""把「没有 Gradle 构建文件、但源码依赖 AppCompat/Kotlin」的纯源码工程
自动包装成一个最小 Gradle 工程，交给 Gradle 构建。

处理：
- 检测 .kt，或 .java 里其实是 Kotlin 语法（fun/val/var/lateinit/class X :）
- 把内容为 Kotlin 的 .java 重命名为 .kt
- 生成 settings.gradle.kts / build.gradle.kts / gradle.properties / wrapper 配置
- 依赖给 appcompat + core-ktx（覆盖常见的 AppCompatActivity/ContextCompat）

退出码：0 = 已包装；1 = 不需要包装（普通 Java 裸源码）。
"""
import os
import re
import sys


def read(path):
    try:
        with open(path, encoding="utf-8", errors="ignore") as f:
            return f.read()
    except OSError:
        return ""


def write(path, s):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(s)


def kotlin_like(s):
    return bool(
        re.search(r"\b(fun\s+\w+|val\s+\w+|var\s+\w+|lateinit\s+var|data\s+class)\b", s)
        or re.search(r"\bclass\s+\w+\s*:\s*\w+", s)
        or "import kotlin." in s
    )


def find_src_main(root):
    for d in (os.path.join(root, "src", "main"), os.path.join(root, "app", "src", "main")):
        if os.path.isdir(d):
            return d
    return None


def ensure_src_main_layout(root, src_main):
    """老式裸工程常把 AndroidManifest.xml / res 放在根目录，搬进 src/main。"""
    man = os.path.join(root, "AndroidManifest.xml")
    dst_man = os.path.join(src_main, "AndroidManifest.xml")
    if os.path.isfile(man) and not os.path.isfile(dst_man):
        os.replace(man, dst_man)
        print("moved AndroidManifest.xml ->", dst_man)
    res = os.path.join(root, "res")
    dst_res = os.path.join(src_main, "res")
    if os.path.isdir(res) and not os.path.isdir(dst_res):
        os.replace(res, dst_res)
        print("moved res ->", dst_res)


def find_package(root, src_main):
    manifest = os.path.join(src_main, "AndroidManifest.xml")
    m = re.search(r'package\s*=\s*["\']([\w.]+)["\']', read(manifest))
    if m:
        return m.group(1)
    for base, _, files in os.walk(src_main):
        for f in files:
            if f.endswith((".kt", ".java")):
                m = re.search(r"^\s*package\s+([\w.]+)", read(os.path.join(base, f)), re.M)
                if m:
                    return m.group(1)
    return "com.example.app"


def main() -> int:
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    src_main = find_src_main(root)
    if not src_main:
        return 1
    ensure_src_main_layout(root, src_main)

    has_kotlin = False
    for base, _, files in os.walk(src_main):
        for f in files:
            path = os.path.join(base, f)
            if f.endswith(".kt"):
                has_kotlin = True
            elif f.endswith(".java") and kotlin_like(read(path)):
                has_kotlin = True
                kt = path[:-5] + ".kt"
                os.replace(path, kt)
                print("renamed kotlin source", path, "->", kt)

    if not has_kotlin:
        return 1

    pkg = find_package(root, src_main)

    for name in ("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"):
        p = os.path.join(root, name)
        if os.path.exists(p):
            os.replace(p, p + ".bak")

    write(
        os.path.join(root, "settings.gradle.kts"),
        """pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "autowrap"
""",
    )

    write(
        os.path.join(root, "build.gradle.kts"),
        f"""plugins {{
    id("com.android.application") version "8.2.0"
    id("org.jetbrains.kotlin.android") version "1.9.22"
}}

android {{
    namespace = "{pkg}"
    compileSdk = 34
    defaultConfig {{
        applicationId = "{pkg}"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }}
    compileOptions {{
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }}
    kotlinOptions {{ jvmTarget = "17" }}
}}

dependencies {{
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.12.0")
}}
""",
    )

    write(
        os.path.join(root, "gradle.properties"),
        "android.useAndroidX=true\norg.gradle.jvmargs=-Xmx2048m\n",
    )

    write(
        os.path.join(root, "gradle", "wrapper", "gradle-wrapper.properties"),
        "distributionBase=GRADLE_USER_HOME\n"
        "distributionPath=wrapper/dists\n"
        "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.9-bin.zip\n"
        "zipStoreBase=GRADLE_USER_HOME\n"
        "zipStorePath=wrapper/dists\n",
    )

    print("wrapped gradle project, package", pkg)
    return 0


if __name__ == "__main__":
    sys.exit(main())