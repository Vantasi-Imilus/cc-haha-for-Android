# 第三方组件与许可声明

本项目（cc-haha Android 版）分发了若干第三方二进制与代码。按各自许可证要求声明如下。

---

## 一、Termux 运行时与工具链 ⚠️ 含 GPL 组件

本 App 打包并分发了来自 **Termux 官方仓库**的 aarch64 二进制。
这些二进制在 App 首次启动时解压到应用私有目录，供本机编译/运行使用。

### 来源与对应源码（GPL-3.0 §6 要求的 Corresponding Source）

| 项 | 位置 |
|---|---|
| 二进制来源 | `https://packages.termux.dev/apt/termux-main` |
| 源码与构建脚本 | `https://github.com/termux/termux-packages` |
| 各包源码 | `https://github.com/termux/termux-packages/tree/master/packages/<包名>` |
| 构建补丁与配置 | 同上仓库（termux-packages 包含完整的构建配方） |

> 本项目**未修改**这些二进制，按原样分发（仅做解包与权限设置）。
> 需要任一组件源码时，请从上述仓库按包名获取；如需本项目协助，请提 Issue。

### GPL / LGPL 组件（copyleft，需履行开源义务）

| 组件 | 许可证（以 Termux 仓库为准） |
|---|---|
| `bash` | GPL-3.0-or-later |
| `coreutils` | GPL-3.0-or-later |
| `findutils` | GPL-3.0-or-later |
| `gawk` | GPL-3.0-or-later |
| `grep` | GPL-3.0-or-later |
| `gzip` | GPL-3.0-or-later |
| `make` | GPL-3.0-or-later |
| `sed` | GPL-3.0-or-later |
| `tar` | GPL-3.0-or-later |
| `diffutils` | GPL-3.0-or-later |
| `patch` | GPL-3.0-or-later |
| `xz-utils` | GPL-3.0-or-later（`liblzma` 为公有领域） |
| `pkg-config` | GPL-2.0-or-later |
| `git` | GPL-2.0 |
| `psmisc` | GPL-2.0-or-later |
| `procps` | GPL-2.0-or-later |
| `util-linux` | GPL-2.0-or-later（部分组件为其他宽松许可） |
| `less` | GPL-3.0-or-later（或 BSD，以 Termux 仓库为准） |
| `dialog` | LGPL-2.1 |
| `libgmp` | LGPL-3.0-or-later / GPL-2.0-or-later（双许可） |
| `libmpfr` | LGPL-3.0-or-later |
| `pulseaudio` | LGPL-2.1-or-later |
| `alsa-lib` | LGPL-2.1-or-later |
| `libdbus` | AFL-2.1 / GPL-2.0-or-later（双许可） |

### 宽松许可组件（节选）

| 组件 | 许可证 |
|---|---|
| `nodejs` | MIT |
| `python` | PSF-2.0 |
| `clang` / `llvm` / `lld` / `libllvm` | Apache-2.0 WITH LLVM-exception |
| `llvm-mingw-w64*` | Apache-2.0 WITH LLVM-exception（含 MIT 组件） |
| `cmake` | BSD-3-Clause |
| `ninja` | Apache-2.0 |
| `kotlin` / `aapt` / `aapt2` / `d8` / `apksigner` | Apache-2.0（Android SDK 组件） |
| `openjdk-17` / `openjdk-21` | GPL-2.0 WITH Classpath-exception |
| `ripgrep` | MIT / Unlicense（双许可） |
| `openssh` | BSD 系（OpenSSH License） |
| `curl` / `libcurl` | curl（MIT 系） |
| `libuv` / `jsoncpp` / `libc++` | MIT / Apache-2.0 WITH LLVM-exception |
| `zlib` | zlib License |
| `zstd` | BSD-3-Clause / GPL-2.0（双许可） |
| `sqlite` / `libsqlite` | 公有领域 |
| `openssl` | Apache-2.0 |
| `libicu` | Unicode License |
| `unzip` / `zip` | Info-ZIP License |
| `termux-*`（am/auth/core/tools 等） | 见 termux-packages 仓库 |

> 完整组件清单（135 个包）见 `tools/fetch-toolchain.mjs` 与 `tools/fetch-node.mjs`
> 中的依赖闭包解析逻辑；实际打包内容以 `android/app/src/main/assets/*.pkg` 为准。

---

## 二、上游工作台与运行时代码

| 项 | 说明 |
|---|---|
| 上游工作台 | [NanmiCoder/cc-haha](https://github.com/NanmiCoder/cc-haha)（MIT） |
| 运行时代码 | 源自社区流传的 Claude Code 公开版本 |
| Android 移植层 | 本项目原创：`android/`（Kotlin 壳）+ `runtime/compat/`（Bun→Node 兼容层）+ `tools/`（构建脚本） |

上游 `THIRD_PARTY_LICENSES.md` 中的声明（ripgrep、claude-tap 等）同样适用于本项目。

---

## 三、npm 依赖

`runtime/`、`runtime/upstream/desktop/`、`runtime/upstream/adapters/` 下的 Node 依赖
各自保留其原始许可证（多为 MIT / Apache-2.0 / ISC）。
完整清单见各目录的 `package.json` 与安装后的 `node_modules/*/LICENSE`。

---

## 四、如何自行复现本文档所述的二进制

```bash
# 1. 下载 Node 运行时（10 个包）
node tools/fetch-node.mjs

# 2. 下载预置工具链（88 个包）
node tools/fetch-toolchain.mjs --preset core

# 3. 组装进 Android assets
node tools/assemble-runtime.mjs
```

脚本会从 `packages.termux.dev` 拉取 `.deb` 并解包——**来源可完全核查**。
