#!/usr/bin/env python3
"""删除 build.gradle / build.gradle.kts 里的 externalNativeBuild { ... } 块。
只在工程声明了 CMake 但 CMakeLists.txt 缺失时使用，先保证能出 APK。
"""
import os
import re
import sys


def strip(path: str) -> bool:
    if not os.path.isfile(path):
        return False
    with open(path, encoding="utf-8") as f:
        s = f.read()

    out = []
    i = 0
    changed = False
    while True:
        m = re.search(r"externalNativeBuild\s*\{", s[i:])
        if not m:
            out.append(s[i:])
            break
        start = i + m.start()
        out.append(s[i:start])
        j = i + m.end()
        depth = 1
        while j < len(s) and depth > 0:
            if s[j] == "{":
                depth += 1
            elif s[j] == "}":
                depth -= 1
            j += 1
        i = j
        changed = True

    if changed:
        with open(path, "w", encoding="utf-8") as f:
            f.write("".join(out))
    return changed


def main() -> int:
    any_changed = False
    for p in sys.argv[1:]:
        if strip(p):
            print("stripped externalNativeBuild:", p)
            any_changed = True
    return 0 if any_changed else 1


if __name__ == "__main__":
    sys.exit(main())