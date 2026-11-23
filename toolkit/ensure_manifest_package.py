#!/usr/bin/env python3
"""给裸源码工程的 AndroidManifest.xml 补 package 属性。

用法: ensure_manifest_package.py <AndroidManifest.xml> <java目录>
"""
import os
import re
import sys


def find_package(java_dir):
    if not os.path.isdir(java_dir):
        return ""
    for base, _, files in os.walk(java_dir):
        for f in files:
            if not f.endswith(".java"):
                continue
            try:
                with open(os.path.join(base, f), encoding="utf-8", errors="ignore") as fh:
                    s = fh.read()
            except OSError:
                continue
            m = re.search(r"^\s*package\s+([A-Za-z_][\w.]*)\s*;", s, re.M)
            if m:
                return m.group(1)
    return ""


def main() -> int:
    if len(sys.argv) < 2:
        return 0
    manifest = sys.argv[1]
    java_dir = sys.argv[2] if len(sys.argv) > 2 else ""

    try:
        with open(manifest, encoding="utf-8", errors="ignore") as f:
            s = f.read()
    except OSError:
        return 1

    if re.search(r"<manifest[^>]*\spackage\s*=", s):
        return 0

    pkg = find_package(java_dir) or "com.example.app"
    s = re.sub(r"<manifest", f'<manifest package="{pkg}"', s, count=1)
    with open(manifest, "w", encoding="utf-8") as f:
        f.write(s)
    print("已补 manifest package:", pkg)
    return 0


if __name__ == "__main__":
    sys.exit(main())