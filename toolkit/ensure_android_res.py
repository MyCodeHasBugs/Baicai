#!/usr/bin/env python3
"""扫描源码里的 R.type.name 引用，缺失的资源自动生成占位文件。

缺 res 时 Kotlin/Java 会报 Unresolved reference；这里补齐。
对 layout，会按 findViewById<Type>(R.id.xxx) 生成带 id 的控件，避免运行时空指针。
"""
import os
import re
import sys

KNOWN = {
    "layout", "id", "string", "drawable", "mipmap", "color",
    "style", "dimen", "integer", "bool", "array",
}

VIEW_TAGS = {
    "Button": "Button",
    "TextView": "TextView",
    "EditText": "EditText",
    "ImageView": "ImageView",
    "CheckBox": "CheckBox",
    "RadioButton": "RadioButton",
    "Switch": "Switch",
    "ProgressBar": "ProgressBar",
    "LinearLayout": "LinearLayout",
    "FrameLayout": "FrameLayout",
    "ScrollView": "ScrollView",
    "View": "View",
}


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


def has_resource(res, rtype, name):
    if rtype in ("id", "string", "color", "style", "dimen", "integer", "bool", "array"):
        for base, _, files in os.walk(res):
            folder = os.path.basename(base)
            if folder == "values" or folder.startswith("values-"):
                for f in files:
                    if f.endswith(".xml") and re.search(
                        r'<[^>]+\bname\s*=\s*["\']' + re.escape(name) + r'["\']',
                        read(os.path.join(base, f)),
                    ):
                        return True
        return False
    for base, _, files in os.walk(res):
        folder = os.path.basename(base)
        if folder == rtype or folder.startswith(rtype + "-"):
            for f in files:
                if os.path.splitext(f)[0] == name:
                    return True
    return False


def append_value(res, filename, tag, name, value, attrs=""):
    values = os.path.join(res, "values")
    os.makedirs(values, exist_ok=True)
    path = os.path.join(values, filename)
    s = read(path)
    if not s:
        s = "<resources>\n</resources>\n"
    entry = f'    <{tag} name="{name}"{attrs}>{value}</{tag}>\n'
    if "</resources>" in s:
        s = s.replace("</resources>", entry + "</resources>")
    else:
        s += entry
    write(path, s)


def main() -> int:
    if len(sys.argv) < 2:
        return 0
    src_main = sys.argv[1]
    res = os.path.join(src_main, "res")

    refs = set()
    id_types = {}
    for base, _, files in os.walk(src_main):
        for f in files:
            if not f.endswith((".kt", ".java")):
                continue
            s = read(os.path.join(base, f))
            for m in re.finditer(r"\bR\.([A-Za-z_]\w*)\.([A-Za-z_]\w*)", s):
                if m.group(1) in KNOWN:
                    refs.add((m.group(1), m.group(2)))
            for m in re.finditer(
                r"findViewById\s*<\s*([\w.]+)\s*>\s*\(\s*R\.id\.(\w+)", s
            ):
                id_types[m.group(2)] = m.group(1).split(".")[-1]
            for m in re.finditer(r"findViewById\s*\(\s*R\.id\.(\w+)", s):
                id_types.setdefault(m.group(1), "View")

    controls = ""
    for name, typ in id_types.items():
        tag = VIEW_TAGS.get(typ, "View")
        controls += (
            f'    <{tag} android:id="@+id/{name}"\n'
            '        android:layout_width="wrap_content"\n'
            '        android:layout_height="wrap_content" />\n'
        )

    created = []
    for rtype, name in sorted(refs):
        if has_resource(res, rtype, name):
            continue
        if rtype == "layout":
            write(
                os.path.join(res, "layout", name + ".xml"),
                '<?xml version="1.0" encoding="utf-8"?>\n'
                '<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"\n'
                '    android:layout_width="match_parent"\n'
                '    android:layout_height="match_parent"\n'
                '    android:orientation="vertical">\n'
                + controls
                + '</LinearLayout>\n',
            )
        elif rtype == "id":
            append_value(res, "ids.xml", "item", name, "", ' type="id"')
        elif rtype == "string":
            append_value(res, "strings.xml", "string", name, name)
        elif rtype == "color":
            append_value(res, "colors.xml", "color", name, "#888888")
        elif rtype == "style":
            append_value(
                res, "styles.xml", "style", name,
                "", ' parent="android:Theme.Material.Light"',
            )
        elif rtype == "dimen":
            append_value(res, "dimens.xml", "dimen", name, "16dp")
        elif rtype == "integer":
            append_value(res, "integers.xml", "integer", name, "0")
        elif rtype == "bool":
            append_value(res, "bools.xml", "bool", name, "false")
        elif rtype == "array":
            append_value(res, "arrays.xml", "string-array", name, "")
        elif rtype in ("drawable", "mipmap"):
            write(
                os.path.join(res, rtype, name + ".xml"),
                '<?xml version="1.0" encoding="utf-8"?>\n'
                '<shape xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <solid android:color="#888888" />\n'
                '</shape>\n',
            )
        created.append(f"{rtype}/{name}")

    if created:
        print("generated placeholder resources:", ", ".join(created))
    return 0


if __name__ == "__main__":
    sys.exit(main())