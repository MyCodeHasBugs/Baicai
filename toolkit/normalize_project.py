#!/usr/bin/env python3
"""在解压后统一规范化工程，兼容各种非常规结构。

用法: normalize_project.py <工程根>
输出（供 workflow 读取）:
  /tmp/compile_sdk.txt  需要安装的 compileSdk（若有）
  /tmp/app_module.txt   application 模块名（如 :app）
  /tmp/normalized.txt   是否做过改动
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
    d = os.path.dirname(path)
    if d:
        os.makedirs(d, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(s)


def find_build_files(root):
    out = []
    for base, _, files in os.walk(root):
        if os.sep + "build" + os.sep in base + os.sep:
            continue
        for f in files:
            if f in ("build.gradle", "build.gradle.kts",
                     "settings.gradle", "settings.gradle.kts"):
                out.append(os.path.join(base, f))
    return out


def fix_gradlew(root):
    p = os.path.join(root, "gradlew")
    if os.path.isfile(p):
        s = read(p)
        if "\r\n" in s:
            write(p, s.replace("\r\n", "\n"))
            print("fixed gradlew CRLF")


def ensure_settings(root, build_files):
    has_settings = any(os.path.basename(p).startswith("settings.gradle") for p in build_files)
    if has_settings:
        return False
    root_build = ""
    kotlin = False
    for p in build_files:
        if os.path.dirname(p) == root and os.path.basename(p) in ("build.gradle", "build.gradle.kts"):
            root_build = read(p)
            kotlin = p.endswith(".kts")
    if "com.android.application" not in root_build and "com.android.library" not in root_build:
        return False
    name = "settings.gradle.kts" if kotlin else "settings.gradle"
    jit = 'maven { url = uri("https://jitpack.io") }' if kotlin else "maven { url 'https://jitpack.io' }"
    content = f"""pluginManagement {{
    repositories {{
        google()
        mavenCentral()
        gradlePluginPortal()
    }}
}}
dependencyResolutionManagement {{
    repositories {{
        google()
        mavenCentral()
        {jit}
    }}
}}
rootProject.name = "autowrap"
"""
    write(os.path.join(root, name), content)
    wrap = os.path.join(root, "gradle", "wrapper", "gradle-wrapper.properties")
    if not os.path.isfile(wrap):
        write(
            wrap,
            "distributionBase=GRADLE_USER_HOME\n"
            "distributionPath=wrapper/dists\n"
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.9-bin.zip\n"
            "zipStoreBase=GRADLE_USER_HOME\n"
            "zipStorePath=wrapper/dists\n",
        )
    print("generated", name)
    return True


def fix_repositories(root, build_files):
    changed = False
    for p in build_files:
        s = read(p)
        if not s:
            continue
        new = s
        if "jcenter()" in new:
            new = new.replace("jcenter()", "mavenCentral()")
        if "repositories" in new and "google()" not in new:
            jit = 'maven { url = uri("https://jitpack.io") }' if p.endswith(".kts") else "maven { url 'https://jitpack.io' }"
            inserted = "\\1\n        google()\n        mavenCentral()\n        " + jit
            new = re.sub(r"(repositories\s*\{)", inserted, new, count=1)
        if new != s:
            write(p, new)
            changed = True
            print("patched repositories", p)
    return changed


def ensure_manifest_package(root, build_files):
    manifest = None
    for p in build_files:
        if os.path.basename(p).startswith("build.gradle"):
            d = os.path.dirname(p)
            for cand in (
                os.path.join(d, "src", "main", "AndroidManifest.xml"),
                os.path.join(d, "AndroidManifest.xml"),
            ):
                if os.path.isfile(cand):
                    manifest = cand
                    break
        if manifest:
            break
    if not manifest:
        return False
    s = read(manifest)
    if re.search(r"<manifest[^>]*\spackage\s*=", s):
        return False

    pkg = ""
    for p in build_files:
        m = re.search(r"\bnamespace\s*=?\s*[\"']([\w.]+)[\"']", read(p))
        if m:
            pkg = m.group(1)
            break
    if not pkg:
        for p in build_files:
            if p.endswith((".gradle", ".gradle.kts")):
                continue
        for base, _, files in os.walk(os.path.join(os.path.dirname(manifest))):
            for f in files:
                if f.endswith((".kt", ".java")):
                    m = re.search(r"^\s*package\s+([\w.]+)", read(os.path.join(base, f)), re.M)
                    if m:
                        pkg = m.group(1)
                        break
            if pkg:
                break
    pkg = pkg or "com.example.app"
    write(manifest, re.sub(r"<manifest", f'<manifest package="{pkg}"', s, count=1))
    print("added manifest package", pkg)
    return True


def find_compile_sdk(build_files):
    for p in build_files:
        if not os.path.basename(p).startswith("build.gradle"):
            continue
        s = read(p)
        m = re.search(r"compileSdk(?:Version)?\s*=?\s*(\d+)", s)
        if m:
            return m.group(1)
    return ""


def find_app_module(root, build_files):
    modules = []
    for p in build_files:
        if os.path.basename(p).startswith("settings.gradle"):
            s = read(p)
            for m in re.finditer(r"include\s*[\(\s]\s*[\"']([^\"']+)[\"']", s):
                modules.append(m.group(1))
    for mod in modules:
        d = os.path.join(root, mod.lstrip(":").replace(":", os.sep))
        for name in ("build.gradle", "build.gradle.kts"):
            if os.path.isfile(os.path.join(d, name)):
                s = read(os.path.join(d, name))
                if "com.android.application" in s:
                    return ":" + mod.lstrip(":")
    return ""



def kotlin_like(s):
    return bool(
        re.search(r"\b(fun\s+\w+|val\s+\w+|var\s+\w+|lateinit\s+var|data\s+class)\b", s)
        or re.search(r"\bclass\s+\w+\s*:\s*\w+", s)
        or "import kotlin." in s
    )


def rename_kotlin_sources(root):
    changed = False
    for base, _, files in os.walk(root):
        if os.sep + "build" + os.sep in base + os.sep:
            continue
        for f in files:
            if not f.endswith(".java"):
                continue
            p = os.path.join(base, f)
            if kotlin_like(read(p)):
                os.replace(p, p[:-5] + ".kt")
                print("renamed kotlin source", p)
                changed = True
    return changed

def main() -> int:
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    changed = False
    build_files = find_build_files(root)

    changed |= rename_kotlin_sources(root)
    fix_gradlew(root)
    changed |= ensure_settings(root, build_files)
    build_files = find_build_files(root)
    changed |= fix_repositories(root, build_files)
    changed |= ensure_manifest_package(root, build_files)

    sdk = find_compile_sdk(build_files)
    if sdk:
        write("/tmp/compile_sdk.txt", sdk)
    mod = find_app_module(root, build_files)
    if mod:
        write("/tmp/app_module.txt", mod)

    if changed:
        write("/tmp/normalized.txt", "1")
    print("normalize done, compileSdk", sdk or "-", "module", mod or "-")
    return 0


if __name__ == "__main__":
    sys.exit(main())