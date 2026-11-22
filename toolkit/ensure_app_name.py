#!/usr/bin/env python3
"""确保 app/src/main/res/values/strings.xml 里有 app_name。
有些 ZIP 打包工具会漏掉空目录或单个 strings.xml，导致 aapt 报
resource string/app_name not found。这里补一个默认值，保证能出包。
"""
import os
import sys


def main() -> int:
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    app = os.path.join(root, "app")
    if not os.path.isdir(app):
        return 0

    values = os.path.join(app, "src", "main", "res", "values")
    os.makedirs(values, exist_ok=True)
    path = os.path.join(values, "strings.xml")

    if os.path.isfile(path):
        with open(path, encoding="utf-8") as f:
            s = f.read()
        if 'name="app_name"' in s:
            return 0
        if "</resources>" in s:
            s = s.replace(
                "</resources>",
                '    <string name="app_name">MyApp</string>\n</resources>',
            )
        else:
            s += '\n<string name="app_name">MyApp</string>\n'
        with open(path, "w", encoding="utf-8") as f:
            f.write(s)
    else:
        with open(path, "w", encoding="utf-8") as f:
            f.write(
                "<resources>\n"
                '    <string name="app_name">MyApp</string>\n'
                "</resources>\n"
            )

    print("ensured app_name:", path)
    return 0


if __name__ == "__main__":
    sys.exit(main())