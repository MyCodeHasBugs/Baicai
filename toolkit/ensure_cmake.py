#!/usr/bin/env python3
"""保证工程声明了 CMake 时，它能真的构建出 .so。

处理优先级：
1. 工程里已有 CMakeLists.txt（任意深度）→ 确保它位于 build.gradle 配置的 path；
2. 没有 CMakeLists，但有 .c/.cpp/.cc 源码（任意深度）→ 在配置的 path 生成一个；
3. 两者都没有 → 退出码 1，调用方再回退到禁用 externalNativeBuild。

退出码：0 = native 构建可用；1 = 无 CMakeLists 且无源码。
"""
import os
import re
import shutil
import sys


def read(path):
    try:
        with open(path, encoding="utf-8", errors="ignore") as f:
            return f.read()
    except OSError:
        return ""


def configured_cmake_path(root):
    app = os.path.join(root, "app")
    for name in ("build.gradle", "build.gradle.kts"):
        s = read(os.path.join(app, name))
        if not s:
            continue
        m = re.search(r"path\s*=?\s*(?:file\()?\s*[\"']([^\"']+CMakeLists\.txt)[\"']", s)
        if m:
            return os.path.normpath(os.path.join(app, m.group(1)))
    return os.path.join(app, "src", "main", "cpp", "CMakeLists.txt")


def find_existing_cmake(root):
    for base, _, files in os.walk(root):
        if os.sep + "build" + os.sep in base + os.sep:
            continue
        if "CMakeLists.txt" in files:
            return os.path.join(base, "CMakeLists.txt")
    return None


def find_source_dir(root):
    for base, _, files in os.walk(root):
        if os.sep + "build" + os.sep in base + os.sep:
            continue
        for f in files:
            if f.endswith((".c", ".cpp", ".cc")):
                return base
    return None


def find_lib_name(root):
    pat = re.compile(r"System\s*\.\s*loadLibrary\s*\(\s*[\"']([^\"']+)[\"']")
    for base, _, files in os.walk(os.path.join(root, "app", "src", "main")):
        for f in files:
            if f.endswith((".java", ".kt")):
                m = pat.search(read(os.path.join(base, f)))
                if m:
                    return m.group(1)
    return "native-lib"


def main() -> int:
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    target = configured_cmake_path(root)

    existing = find_existing_cmake(root)
    if existing:
        if os.path.abspath(existing) != os.path.abspath(target):
            os.makedirs(os.path.dirname(target), exist_ok=True)
            shutil.copyfile(existing, target)
            print("copied", existing, "->", target)
        else:
            print("found", existing)
        return 0

    src = find_source_dir(root)
    if not src:
        return 1

    name = find_lib_name(root)
    if not re.match(r"^[A-Za-z_][A-Za-z0-9_]*$", name):
        name = "native-lib"

    content = f"""cmake_minimum_required(VERSION 3.22)
project({name} C CXX)

set(CMAKE_CXX_STANDARD 17)
file(GLOB_RECURSE SOURCES CONFIGURE_DEPENDS "*.c" "*.cpp" "*.cc")
add_library({name} SHARED ${{SOURCES}})
target_link_libraries({name} log android)
"""
    os.makedirs(os.path.dirname(target), exist_ok=True)
    with open(target, "w", encoding="utf-8") as f:
        f.write(content)
    print("generated", target, "library", name)
    return 0


if __name__ == "__main__":
    sys.exit(main())