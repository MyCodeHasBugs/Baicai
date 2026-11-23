#!/usr/bin/env python3
"""裸源码模式：清理 AndroidManifest.xml 里指向不存在资源的引用。

裸源码流水线只有 aapt2 + android.jar，不带 androidx/appcompat 资源。
manifest 里常见的 @mipmap/ic_launcher、@style/Theme.AppCompat.*、@string/xxx
如果不清理，aapt2 会直接报 resource not found。

用法: sanitize_manifest.py <AndroidManifest.xml> <res目录>
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
    with open(path, "w", encoding="utf-8") as f:
        f.write(s)


def resource_exists(res, rtype, name):
    if not os.path.isdir(res):
        return False
    for base, _, files in os.walk(res):
        folder = os.path.basename(base)
        if folder == "values" or folder.startswith("values-"):
            for f in files:
                if not f.endswith(".xml"):
                    continue
                s = read(os.path.join(base, f))
                if re.search(
                    r'<[^>]+\bname\s*=\s*["\']' + re.escape(name) + r'["\']', s
                ):
                    return True
        elif folder == rtype or folder.startswith(rtype + "-"):
            for f in files:
                if os.path.splitext(f)[0] == name:
                    return True
    return False


def replace_ref(s, attr, rtype, fallback):
    m = re.search(r'\sandroid:' + attr + r'="(@[^"]+)"', s)
    if not m:
        return s
    ref = m.group(1)[1:]
    if "/" not in ref:
        return s
    t, n = ref.split("/", 1)
    if resource_exists(RES, t, n):
        return s
    if fallback is None:
        return s.replace(m.group(0), "")
    return s.replace(m.group(0), f' android:{attr}="{fallback}"')


def main() -> int:
    global RES
    if len(sys.argv) < 2:
        return 0
    manifest = sys.argv[1]
    RES = sys.argv[2] if len(sys.argv) > 2 else ""

    s = read(manifest)
    if not s:
        return 1

    s = replace_ref(s, "icon", "mipmap", None)
    s = replace_ref(s, "roundIcon", "mipmap", None)
    s = replace_ref(s, "theme", "style", "@android:style/Theme.Material.Light")
    s = replace_ref(s, "label", "string", "App")

    write(manifest, s)
    print("sanitized manifest:", manifest)
    return 0


if __name__ == "__main__":
    sys.exit(main())