# cc-haha 安卓全量版移植 — 实施计划

## 一、已实证的技术底座（华为设备 / Android 12 真机验证）

| 验证项 | 结果 |
|---|---|
| Node v26.4.0（Termux arm64 二进制）在真机运行 | ✅ |
| `node:sqlite`（替代 `bun:sqlite`） | ✅ |
| HTTP server / WebSocket client / `spawn` / `zstd` / `fs.glob` | ✅ |
| Termux 官方源（工具链）可达 | ✅ |
| 关键环境变量：`LD_LIBRARY_PATH=<lib目录>` + `OPENSSL_CONF=/dev/null` | 实测必需 |
| 设备已装 Termux（targetSdk=28）证明该方案在 Android 12 可用 | ✅ |

**核心决策**：Android 10+ 对 targetSdk≥29 的应用禁止私有目录 exec（W^X）。选 **targetSdk 28**（与 Termux 相同），工具链才能按需下载安装。Google Play 不收（我们也不需要，直接本地安装 APK）。

## 二、总体架构（用户已确认：Node 单进程移植 + 预置工具链 + Windows 编译运行都要）

```
Android App (Kotlin, targetSdk 28)
 ├─ WebView ──http://127.0.0.1:3456──> Node 版 cc-haha server（compat 层移植，代码零侵入）
 ├─ ServerService（前台服务）：守护 node 进程、崩溃重启
 ├─ RuntimeInstaller：assets 解压 node + H5 到 filesDir（可 exec）
 ├─ ToolchainManager：预置 + 按需下载 Termux deb 包
 └─ H5 UI：cc-haha desktop/dist（移动端 UI 现成，无需重写）
```

**功能边界**：cc-haha 全量功能（多会话、工具执行、MCP、SubAgent、Workflow、技能市场、IM、模型自选等）；桌面专属能力（Computer Use 控桌面、Electron PTY 终端、桌面宠物、内置浏览器）在安卓上自然降级。

## 三、五个阶段

### 阶段 0：构建环境（Windows 本机）
- JDK 17（清华 Adoptium 镜像）、Android cmdline-tools（dl.google.com ✅）、Gradle 8.7（腾讯镜像 ✅）
- sdkmanager 装 `platforms;android-31` + `build-tools;34` + `platform-tools`
- Gradle 全部仓库走阿里云 Maven 镜像

### 阶段 1：Node 兼容层移植（最高风险，最先做，真机门禁）
- 复制 `cc-haha-main` → `cc-haha-android/runtime/`（原版保持不动作参照）
- 新增 `compat/` 包（接口规格已调研到调用点级）：
  - `bun-serve`：`node:http` + `ws`（项目已依赖），实现 `{port, stop, upgrade, requestIP}` + websocket 回调
  - `bun-sqlite`：包装 `node:sqlite`（位置绑定 `?`，`exec/prepare/query/run/get/all/transaction/close`）
  - `bun-bundle`：`feature()` 同步解析 `--feature=` argv（197 处调用均为同步三元门控）
  - `bun-shim`：`Bun.spawn`（child→web ReadableStream）、`sleep`、`hash`（JS 实现 wyhash）、`file`、`gzip/zstd`（node:zlib，设备已验证）、`YAML/JSONL/stringWidth/wrapAnsi/semver/which/version/gc` 等
  - `import.meta.main`：esbuild `--define` 按入口注入；`MACRO` 全局：banner 注入
- esbuild 打包入口：`server`、`cli`、5 个 IM adapters（npmmirror 装依赖）
- **真机门禁**：adb 推送 → 起 server → 设备浏览器打开 H5 → 通过才进入阶段 2

### 阶段 2：Android 壳工程
- 包结构：`MainActivity`(WebView) / `ServerService`(前台服务+看门狗) / `RuntimeInstaller` / `ToolchainManager` / `PermissionHelper` / `SettingsActivity`
- WebView：`network_security_config` 放行 127.0.0.1 cleartext、DOM storage（H5 用 localStorage 存 token）、文件选择器、下载到公共目录、返回键
- 华为保活：前台服务 + 电池优化白名单引导 + 华为自启动/后台运行提示

### 阶段 3：工具链集成
- **预置进 APK**：node 运行时、python 3.14、clang 21 + lld（C/C++）、git、make、cmake、pkg-config（压缩后约 250-350MB）
- **按需下载器**（Termux 官方源）：依赖解析 → deb 解包 → 安装到 filesDir：
  - 语言：go、rust、openjdk-17、kotlin、php、ruby、mono、dart、zig、perl
  - **Android 构建链**：aapt2、d8、apksigner、gradle（在安卓上编译安卓软件）
  - **Windows 编译**：llvm-mingw-w64（产出 .exe/.dll）
  - **Windows 运行**：hangover-wine + hangover-wowbox64（本机运行 Windows CLI 程序）
  - Linux 编译：clang 原生即 Linux；交叉见 mingw
- 每语言在真机上跑冒烟测试

### 阶段 4：APK 构建与真机验收
- 命令行 `gradle assembleRelease`（无 Android Studio）+ 签名 + `adb install`
- 验收：安装 → 首次解压 → WebView 直连 → 会话跑通 → python/clang 冒烟 → 按需装 go 冒烟

### 阶段 5：交付
- 完整工程（`cc-haha-android/`）+ APK + 一键构建/安装脚本 + README（镜像源、华为设置、工具链说明）

## 四、主要风险与对策

| 风险 | 对策 |
|---|---|
| `Bun.serve` shim 与业务 WS 语义差异 | 接口规格已精确到调用点；阶段 1 真机门禁尽早暴露 |
| 197 处 `feature()` 同步语义 | esbuild alias + argv 解析，编译期保证 |
| 华为 EMUI 激进杀后台 | 前台服务 + 电池白名单 + 用户引导（EMUI 已知问题，文档写明） |
| APK 体积超预期 | 预置清单可裁剪；按需下载器兜底 |
| Android 12 上 targetSdk 28 兼容面 | 同设备 Termux 已验证；scoped storage 用 app 私有目录 + SAF |
| GitHub 不通 | 全部走 Termux 官方源/阿里云/清华/npmmirror（均已实测可达） |
