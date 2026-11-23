#!/usr/bin/env bash
# build-bare.sh - 不依赖 Gradle，把「裸 Android 源码工程」直接编成可安装 APK
#
# 支持输入：
#   AndroidManifest.xml
#   res/ 或 src/main/res/           -> aapt2 -> resources.arsc / R.java
#   java/ 或 src/ 或 src/main/java/ -> javac -> d8 -> classes.dex
#   cpp/ jni/ src/main/cpp/ 下的 .c/.cpp -> NDK clang -> lib/<abi>/*.so
#
# 用法: bash build-bare.sh <工程目录> <输出目录>
set -euo pipefail

SRC="${1:-/tmp/src}"
OUT="${2:-/tmp/out}"
mkdir -p "$OUT"
WORK=/tmp/bare-work
rm -rf "$WORK"
mkdir -p "$WORK"/{flat,gen,classes,dex,lib}

SDK="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
BT=$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)
JAR="$SDK/platforms/android-34/android.jar"
[ -f "$JAR" ] || JAR=$(ls "$SDK"/platforms/*/android.jar 2>/dev/null | sort -V | tail -1 || true)
[ -n "${JAR:-}" ] && [ -f "$JAR" ] || { echo "找不到 android.jar" >&2; exit 1; }

AAPT2="$BT/aapt2"
ZIPALIGN="$BT/zipalign"
[ -x "$AAPT2" ] || { echo "找不到 aapt2: $AAPT2" >&2; exit 1; }
[ -x "$ZIPALIGN" ] || ZIPALIGN=$(find "$BT" -name zipalign -type f | head -1)

JH="${JAVA_HOME:-}"
JAVAC="$JH/bin/javac"; JAVA="$JH/bin/java"; KEYTOOL="$JH/bin/keytool"
[ -x "$JAVAC" ] || { echo "找不到 javac（JAVA_HOME=$JH）" >&2; exit 1; }

MANIFEST=""
for cand in "$SRC/AndroidManifest.xml" "$SRC/src/main/AndroidManifest.xml"; do
  [ -f "$cand" ] && MANIFEST="$cand" && break
done
[ -n "$MANIFEST" ] || MANIFEST=$(find "$SRC" -maxdepth 8 -name AndroidManifest.xml -type f -not -path '*/build/*' | head -1)
[ -n "$MANIFEST" ] || { echo "工程里没有 AndroidManifest.xml" >&2; exit 1; }
ROOT=$(dirname "$MANIFEST")
echo "Manifest: $MANIFEST"

RES="$ROOT/res"; [ -d "$RES" ] || RES="$SRC/res"
JDIR=""
for cand in "$ROOT/java" "$SRC/src/main/java" "$SRC/java" "$SRC/src"; do
  [ -d "$cand" ] && JDIR="$cand" && break
done
[ -n "$JDIR" ] || JDIR="$ROOT"

# 现代 manifest 常常不写 package（包名在 Gradle 的 namespace 里）。
# 裸源码模式必须补一个，否则 aapt2 报 "must have a 'package' attribute"。
PKG=""
if [ -d "$JDIR" ]; then
  PKG=$(grep -Rh --include='*.java' -E '^[[:space:]]*package[[:space:]]+' "$JDIR" 2>/dev/null \
    | head -1 | sed -E 's/^[[:space:]]*package[[:space:]]+//; s/;.*//' | tr -d '[:space:]')
fi
[ -n "$PKG" ] || PKG="com.example.app"
if ! grep -qE '<manifest[^>]*[[:space:]]package=' "$MANIFEST"; then
  sed -i "0,/<manifest/s//<manifest package=\"$PKG\"/" "$MANIFEST"
  echo "已补 manifest package: $PKG"
fi

CPP_DIRS=()
for d in "$ROOT/cpp" "$SRC/cpp" "$SRC/jni" "$ROOT/jni"; do [ -d "$d" ] && CPP_DIRS+=("$d"); done

# ---------- 1) 资源 ----------
if [ -d "$RES" ]; then
  mapfile -t RESFILES < <(find "$RES" -type f | sort)
  if [ "${#RESFILES[@]}" -gt 0 ]; then
    "$AAPT2" compile -o "$WORK/flat/compiled.zip" "${RESFILES[@]}"
    echo "[1/6] 资源编译完成 (${#RESFILES[@]} 个文件)"
  fi
else
  RESFILES=()
  echo "[1/6] 没有 res 目录，跳过"
fi

# ---------- 2) link ----------
LINK_ARGS=(-o "$WORK/unsigned.apk" -I "$JAR" --manifest "$MANIFEST"
  --java "$WORK/gen" --min-sdk-version 21 --target-sdk-version 34
  --version-code 1 --version-name 1.0)
[ -s "$WORK/flat/compiled.zip" ] && LINK_ARGS+=("$WORK/flat/compiled.zip")
"$AAPT2" link "${LINK_ARGS[@]}"
echo "[2/6] aapt2 link 完成"

# ---------- 3) Java ----------
mapfile -t JSRC < <(find "$JDIR" -name '*.java' -type f | sort)
mapfile -t GENSRC < <(find "$WORK/gen" -name '*.java' -type f | sort)
if [ "${#JSRC[@]}" -eq 0 ]; then
  echo "没有 Java 源码，生成空 dex"
  mkdir -p "$WORK/classes"
else
  "$JAVAC" -source 8 -target 8 -encoding UTF-8 -nowarn -Xlint:-options \
    -bootclasspath "$JAR" -classpath "$JAR" -d "$WORK/classes" \
    "${JSRC[@]}" "${GENSRC[@]}"
  echo "[3/6] javac 完成 (${#JSRC[@]} 个源文件)"
fi

# ---------- 4) d8 ----------
mapfile -t CLASSES < <(find "$WORK/classes" -name '*.class' -type f | sort)
if [ "${#CLASSES[@]}" -gt 0 ]; then
  "$BT/d8" --release --min-api 21 --lib "$JAR" --output "$WORK/dex" "${CLASSES[@]}"
  echo "[4/6] d8 完成"
else
  echo "[4/6] 没有 class，跳过 dex"
fi

# ---------- 5) C/C++ -> so ----------
LIBROOT="$WORK/libroot"
mkdir -p "$LIBROOT/lib/arm64-v8a"
SO_COUNT=0
HAS_CXX=0
if [ "${#CPP_DIRS[@]}" -gt 0 ]; then
  NDK=$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1 || true)
  if [ -n "$NDK" ]; then
    TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
    CLANG="$TOOLCHAIN/bin/clang"
    SYSROOT="$TOOLCHAIN/sysroot"
    for dir in "${CPP_DIRS[@]}"; do
      mapfile -t CSRC < <(find "$dir" -type f \( -name '*.c' -o -name '*.cpp' -o -name '*.cc' \) -not -path '*/build/*' | sort)
      [ "${#CSRC[@]}" -eq 0 ] && continue
      NAME=$(basename "$dir")
      [ "$NAME" = "cpp" ] && NAME="native"
      CXX_FLAGS=()
      case " ${CSRC[*]} " in *.cpp*|*.cc*) CXX_FLAGS=(-std=c++17 -lc++_shared); HAS_CXX=1 ;; esac
      "$CLANG" --target=aarch64-linux-android21 --sysroot="$SYSROOT" \
        -shared -fPIC -O2 -DANDROID -I"$SYSROOT/usr/include" \
        -Wl,-soname,"lib$NAME.so" "${CXX_FLAGS[@]}" \
        "${CSRC[@]}" -o "$LIBROOT/lib/arm64-v8a/lib$NAME.so" -llog -lm
      SO_COUNT=$((SO_COUNT + 1))
      echo "  + lib$NAME.so (${#CSRC[@]} 个源文件)"
    done
    CXXSH=$(find "$SYSROOT/usr/lib/aarch64-linux-android" -name 'libc++_shared.so' 2>/dev/null | head -1 || true)
    if [ "$HAS_CXX" -eq 1 ] && [ -n "$CXXSH" ] && ls "$LIBROOT/lib/arm64-v8a"/*.so >/dev/null 2>&1; then
      cp "$CXXSH" "$LIBROOT/lib/arm64-v8a/libc++_shared.so"
    fi
  else
    echo "[5/6] runner 上没有 NDK，跳过 C/C++"
  fi
else
  echo "[5/6] 没有 C/C++ 源码，跳过"
fi

# ---------- 6) 组装 + 签名 ----------
cp "$WORK/unsigned.apk" "$WORK/full.apk"
[ -f "$WORK/dex/classes.dex" ] && (cd "$WORK/dex" && zip -q "$WORK/full.apk" classes.dex)
[ "$SO_COUNT" -gt 0 ] && (cd "$LIBROOT" && zip -q -0 -r "$WORK/full.apk" lib)

"$ZIPALIGN" -f -p 4 "$WORK/full.apk" "$WORK/aligned.apk"
KS="$WORK/debug.keystore"
"$KEYTOOL" -genkeypair -keystore "$KS" -alias androiddebugkey -keyalg RSA -keysize 2048 \
  -validity 10000 -storepass android -keypass android \
  -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
"$JAVA" -jar "$BT/lib/apksigner.jar" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey --v1-signing-enabled true --v2-signing-enabled true \
  --out "$OUT/app-bare.apk" "$WORK/aligned.apk"
"$JAVA" -jar "$BT/lib/apksigner.jar" verify "$OUT/app-bare.apk" >/dev/null
echo "[6/6] 裸源码 APK 完成: $OUT/app-bare.apk ($(du -h "$OUT/app-bare.apk" | cut -f1))"