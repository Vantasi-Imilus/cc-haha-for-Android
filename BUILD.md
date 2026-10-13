# 构建说明

## 快速开始（一键脚本）

```bash
# 1. 设置环境变量（按你的实际路径）
export JAVA_HOME=/path/to/jdk-17          # JDK 17（AGP 要求）
export ANDROID_HOME=/path/to/android-sdk  # Android SDK
export GRADLE_BIN=/path/to/gradle/bin/gradle  # 或直接用 PATH 里的 gradle

# 2. 一键构建
bash build-apk.sh
```

产物：`android/app/build/outputs/apk/release/app-release.apk`

脚本是**幂等**的：已存在的中间产物（依赖、H5、assets）会自动跳过；
删除对应目录可强制重建。

## 环境要求

| 组件 | 版本 | 说明 |
|---|---|---|
| Node.js | 20+ | 构建脚本与打包 |
| Python | 3.x | 解包 Termux `.deb` |
| JDK | **17** | AGP 8.4 不支持更高版本 |
| Gradle | 8.7+ | 也可用 `gradle wrapper` |
| Android SDK | platforms;android-34 + build-tools;34+ | `sdkmanager` 安装 |

### 没有 Android SDK？

```bash
# 下载 command line tools（约 150MB）
curl -O https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip
# 解压后
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

## 构建流程详解

```
① 安装依赖        npm install（runtime / desktop / adapters 三处）
② 打包 Node 代码   esbuild：把上游 cc-haha 打成可在 Node 跑的 bundle
                   （compat 层提供 Bun API 兼容，上游零修改）
③ 构建 H5 前端     vite：React 界面（手机端复用的同一套 UI）
④ 准备 Node 运行时  Termux 官方源的 aarch64 node（Android 无官方构建）
⑤ 组装 assets      运行时（node+bundle+H5）+ 预置工具链 → APK assets
⑥ Gradle 构建      assembleRelease
```

### 各步骤可单独执行

```bash
node tools/fetch-node.mjs                    # 只下载 Node 运行时
node tools/fetch-toolchain.mjs --preset core # 只下载预置工具链
(cd runtime && CC_HAHA_SPLIT=1 node build/build.mjs)  # 只打包 Node bundle
node tools/assemble-runtime.mjs              # 只组装 runtime.pkg
```

### 工具链可选集

```bash
node tools/fetch-toolchain.mjs --preset core            # 预置：python/clang/git/make/cmake 等
node tools/fetch-toolchain.mjs --preset android-build   # 额外：openjdk/gradle/aapt2/d8/apksigner
node tools/fetch-toolchain.mjs --preset windows         # 额外：llvm-mingw-w64（编译 Windows exe）
```

后两个 preset 产出的包**不打进 APK**（太大），供设备端按需安装使用。

## 目录结构

```
cc-haha-android/
├── build-apk.sh            # 一键构建
├── android/                # Android 工程（Kotlin）
│   └── app/src/main/
│       ├── java/com/cchaha/android/   # 壳工程源码
│       └── assets/                    # runtime.pkg + toolchain-core.pkg
├── runtime/                # Node 运行时（Bun → Node 移植）
│   ├── compat/             # 兼容层（Bun API / sqlite / WebSocket 等）
│   ├── build/build.mjs     # esbuild 打包配置
│   ├── upstream/           # cc-haha 上游源码（除下方注明外未修改）
│   └── dist/               # 打包产物
└── tools/                  # 构建脚本
    ├── fetch-node.mjs      # 下载 Termux Node 运行时
    ├── fetch-toolchain.mjs # 下载工具链（含依赖闭包解析）
    ├── assemble-runtime.mjs# 组装 assets/runtime.pkg
    └── extract_deb.py      # .deb 解包
```

## 常见问题

**Q: 构建很慢 / 依赖下载失败？**
```bash
export NPM_REGISTRY=https://registry.npmmirror.com  # 默认已是国内镜像
```
GitHub 不可达时不影响构建——全部依赖走国内镜像与 Termux 官方源。

**Q: Gradle 报 JDK 版本错误？**
AGP 8.4 需要 JDK 17。JDK 21+ 会失败。

**Q: 想改 App 包名 / 应用名？**
`android/app/build.gradle.kts` 的 `applicationId` 与 `android/app/src/main/res/values/strings.xml`。

**Q: 正式分发怎么签名？**
当前用 debug 签名（`android/app/build.gradle.kts` 的 release 配置）。
正式分发请生成自己的 keystore 并替换 `signingConfig`。

**Q: assets 里的 pkg 文件是什么？**
- `runtime.pkg`：Node 运行时 + 打包后的 cc-haha 代码 + H5 界面（约 124MB）
- `toolchain-core.pkg`：预置工具链 88 个包（约 206MB）
两者在 App 首次启动时解压到私有目录。

## 交叉编译工具链说明

App 内置（按需下载）三端编译能力，均由 Termux 生态提供：

| 目标 | 工具链 | 说明 |
|---|---|---|
| Linux | clang / gcc | 原生编译 |
| **Windows** | `llvm-mingw-w64` | 产出 `.exe`/`.dll`（PE 文件） |
| **Android** | openjdk-17 + aapt2 + d8 + apksigner | 手机上直接构建 APK |

> Windows 程序**运行**需要 proot 包裹（Android 10+ 的 W^X 策略禁止 App 私有目录
> 的文件映射为可执行内存），详见 README 的边界说明。
