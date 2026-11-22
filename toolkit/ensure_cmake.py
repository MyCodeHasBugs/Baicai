#!/usr/bin/env python3
"""工程声明了 CMake 但 CMakeLists.txt 缺失时，自动生成一个。

- 找 app/src/main/cpp 或 app/src/main/jni 下的 .c/.cpp/.cc
- 从 Java/Kotlin 的 System.loadLibrary("xxx") 推断库名
- 生成 CMakeLists.txt，保留 native 构建，确保 .so 打进 APK

退出码：0 = 已生成或已存在；1 = 没有 native 源码（调用方应回退到禁用 externalNativeBuild）。
"""
import os
import re
import sys


def find_source_dir(root):
    for d in (
        os.path.join(root, "app", "src", "main", "cpp"),
        os.path.join(root, "app", "src", "main", "jni"),
    ):
        if os.path.isdir(d):
            files = [
                f
                for f in os.listdir(d)
                if f.endswith((".c", ".cpp", ".cc"))
            ]
            if files:
                return d
    return None


def find_lib_name(root):
    pat = re.compile(r"System\s*\.\s*loadLibrary\s*\(\s*[\"']([^\"']+)[\"']")
    base_dir = os.path.join(root, "app", "src", "main")
    for base, _, files in os.walk(base_dir):
        for f in files:
            if f.endswith((".java", ".kt")):
                try:
                    with open(os.path.join(base, f), encoding="utf-8", errors="ignore") as fh:
                        s = fh.read()
                except OSError:
                    continue
                m = pat.search(s)
                if m:
                    return m.group(1)
    return "native-lib"


def main() -> int:
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    d = find_source_dir(root)
    if not d:
        return 1

    cmake = os.path.join(d, "CMakeLists.txt")
    if os.path.exists(cmake):
        return 0

    name = find_lib_name(root)
    if not re.match(r"^[A-Za-z_][A-Za-z0-9_]*$", name):
        name = "native-lib"

    content = f"""cmake_minimum_required(VERSION 3.22)
project({name} C CXX)

set(CMAKE_CXX_STANDARD 17)
file(GLOB SOURCES CONFIGURE_DEPENDS "*.c" "*.cpp" "*.cc")
add_library({name} SHARED ${{SOURCES}})
target_link_libraries({name} log android)
"""
    with open(cmake, "w", encoding="utf-8") as f:
        f.write(content)
    print("generated", cmake, "library", name)
    return 0


if __name__ == "__main__":
    sys.exit(main())