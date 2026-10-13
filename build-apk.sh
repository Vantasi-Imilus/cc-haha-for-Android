#!/usr/bin/env bash
# cc-haha-android 一键构建脚本
#
# 前置要求：
#   - Node 20+（构建脚本用）
#   - Python 3（解包 .deb）
#   - JDK 17（Android Gradle Plugin 要求）
#   - Gradle 8.7+
#   - Android SDK（platform-tools + platforms;android-34 + build-tools;34+）
#
# 环境变量（都有默认值，可按需覆盖）：
#   JAVA_HOME    JDK 17 路径
#   ANDROID_HOME Android SDK 路径
#   GRADLE_BIN   gradle 可执行文件路径
#   NPM_REGISTRY npm 镜像（默认 npmmirror，国内加速）
#
# 产物：android/app/build/outputs/apk/release/app-release.apk
set -e
cd "$(dirname "$0")"
ROOT="$(pwd)"

NPM_REGISTRY="${NPM_REGISTRY:-https://registry.npmmirror.com}"

# ── 环境检查 ───────────────────────────────────────────────
need() { command -v "$1" >/dev/null 2>&1 || { echo "❌ 缺少 $1（$2）"; exit 1; }; }
need node "Node.js 20+"
need python "Python 3"
echo "✅ node $(node -v) / python $(python --version 2>&1 | cut -d' ' -f2)"

if [ -z "$JAVA_HOME" ]; then
  echo "⚠️  未设置 JAVA_HOME（需要 JDK 17）。示例：export JAVA_HOME=/path/to/jdk-17"
  need java "JDK 17"
fi
if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
  echo "⚠️  未设置 ANDROID_HOME（需要 Android SDK）"
  echo "   可从 https://developer.android.com/studio#command-tools 下载 cmdline-tools 后："
  echo "   sdkmanager \"platform-tools\" \"platforms;android-34\" \"build-tools;34.0.0\""
fi
GRADLE_BIN="${GRADLE_BIN:-gradle}"

# ── 1. 安装依赖（缺什么装什么）──────────────────────────────
echo ""
echo "=== 1/6 安装依赖 ==="
if [ ! -d runtime/node_modules ]; then
  echo "→ runtime 依赖 ..."
  (cd runtime && npm install --ignore-scripts --registry="$NPM_REGISTRY" --loglevel=warn)
fi
if [ ! -d runtime/upstream/desktop/node_modules ]; then
  echo "→ desktop（H5 前端）依赖 ..."
  (cd runtime/upstream/desktop && npm install --ignore-scripts --registry="$NPM_REGISTRY" --loglevel=warn)
fi
if [ ! -d runtime/upstream/adapters/node_modules ]; then
  echo "→ adapters（IM 接入）依赖 ..."
  (cd runtime/upstream/adapters && npm install --omit=dev --ignore-scripts --registry="$NPM_REGISTRY" --loglevel=warn)
fi

# ── 2. Node 运行时代码（Bun → Node 兼容层打包）──────────────
# 说明：仓库不含 runtime/upstream/src（上游内核），APK 也不再内置其产物。
# 内核由 App 首次启动时在设备上获取源码并构建（见 KernelInstaller.kt）。
# 本地调试如需预置内核，把上游 src 放回 runtime/upstream/src 后重新执行本步，
# 再用 node tools/assemble-runtime.mjs --with-kernel 组装即可。
echo ""
if [ -d runtime/upstream/src ]; then
  echo "=== 2/6 打包 Node 版 cc-haha（esbuild，含 code splitting）==="
  (cd runtime && CC_HAHA_SPLIT=1 node build/build.mjs)
else
  echo "=== 2/6 跳过内核打包（无 runtime/upstream/src，由设备端构建）==="
fi

# ── 3. H5 前端 ──────────────────────────────────────────────
echo ""
echo "=== 3/6 构建 H5 前端 ==="
if [ ! -f runtime/upstream/desktop/dist/index.html ]; then
  (cd runtime/upstream/desktop && \
    node ../../node_modules/.bin/esbuild ./src/preview-agent/index.ts \
      --outfile=./src-tauri/resources/preview-agent.js --format=iife --minify --bundle && \
    node ./node_modules/typescript/bin/tsc -b && \
    node ./node_modules/vite/bin/vite.js build)
else
  echo "→ 已存在 desktop/dist，跳过（删除该目录可强制重建）"
fi

# ── 4. Node 运行时（Termux 二进制）──────────────────────────
echo ""
echo "=== 4/6 准备 Node 运行时（Termux aarch64）==="
TERMUX_USR="build/termux-node/data/data/com.termux/files/usr"
if [ ! -f "$TERMUX_USR/bin/node" ] && [ -z "$TERMUX_ROOT" ]; then
  echo "→ 下载 Termux Node 运行时（约 100MB）..."
  node tools/fetch-node.mjs
fi

# ── 5. 组装 Android assets ──────────────────────────────────
echo ""
echo "=== 5/6 组装运行时与工具链 assets ==="
if [ ! -f android/app/src/main/assets/runtime.pkg ]; then
  node tools/assemble-runtime.mjs
else
  echo "→ runtime.pkg 已存在，跳过（删除可强制重建）"
fi
if [ ! -f android/app/src/main/assets/toolchain-core.pkg ]; then
  echo "→ 下载预置工具链（约 206MB，88 个包）..."
  node tools/fetch-toolchain.mjs --preset core
else
  echo "→ toolchain-core.pkg 已存在，跳过"
fi
if [ ! -f android/app/src/main/assets/buildkit.pkg ]; then
  echo "→ 组装设备端构建套件（buildkit，约 51MB）..."
  node tools/assemble-buildkit.mjs
else
  echo "→ buildkit.pkg 已存在，跳过"
fi
if [ ! -f android/app/src/main/assets/toolchain-exec-manifest.txt ]; then
  echo "→ 生成工具链可执行权限清单 ..."
  python tools/gen-exec-manifest.py build/toolchain-cache \
    android/app/src/main/assets/toolchain-exec-manifest.txt
else
  echo "→ toolchain-exec-manifest.txt 已存在，跳过"
fi

# ── 6. Gradle 构建 ──────────────────────────────────────────
echo ""
echo "=== 6/6 Gradle 构建 release APK ==="
(cd android && "$GRADLE_BIN" assembleRelease --no-daemon --console=plain)

APK="$ROOT/android/app/build/outputs/apk/release/app-release.apk"
echo ""
echo "✅ 构建完成：$APK"
echo "   大小：$(du -h "$APK" | cut -f1)"
echo ""
echo "安装到设备："
echo "   adb install -r \"$APK\""
echo ""
echo "说明：默认使用 debug 签名（可自行安装调试）。正式分发请配置自己的 keystore，"
echo "      见 android/app/build.gradle.kts 的 signingConfigs。"
