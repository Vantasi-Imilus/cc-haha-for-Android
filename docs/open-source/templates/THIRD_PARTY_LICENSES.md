# 第三方组件与许可

> 复制到仓库根目录：`cp docs/open-source/templates/THIRD_PARTY_LICENSES.md THIRD_PARTY_LICENSES.md`
>
> ⚠️ **本文件当前为框架，所有条目必须由 `tools/gen-third-party.mjs` 自动生成或人工核实后填写。**
> 未核实的条目保留 `⚠️ 待确认` 标记，**不得臆造许可证**。
> 维护节奏：每次新增依赖或升级工具链后重新生成（见 `docs/open-source/01-法律与许可合规.md` §7）。

本项目以源码与二进制两种形式分发第三方作品，义务不同，故分列如下。

---

## A. 构建期上游项目（源码输入）

| 项目 | 版本/Commit | 许可证 | 用途 | 源码 |
|---|---|---|---|---|
| [cc-haha](https://github.com/NanmiCoder/cc-haha) | `<commit>`（由 `tools/fetch-upstream.mjs` 固定） | MIT (c) 2026 cc-haha | 上游源码输入，经 esbuild 打包为运行时 | 见链接 |

**MIT 许可证全文**：

```
MIT License

Copyright (c) 2026 cc-haha

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

> **授权范围说明**：本项目对上游代码的使用范围与授权状态由其作者负责，本项目不作任何陈述或担保。使用者应自行评估。

---

## B. npm 依赖（打包进 `runtime.pkg`）

共 `<N>` 个直接依赖，传递依赖共 `<M>` 个。完整清单由以下命令生成：

```bash
cd runtime
npx license-checker-rseidelsohn --production --json > /tmp/npm-licenses.json
# 或
npx @cyclonedx/cyclonedx-npm --output-file sbom-npm.json
```

### 摘要（⚠️ 待核实，核实后替换）

| 许可证 | 数量 | 义务 |
|---|---|---|
| MIT | `<n>` | 保留版权与许可声明 |
| ISC | `<n>` | 同上 |
| Apache-2.0 | `<n>` | 保留 LICENSE + **NOTICE** |
| BSD-2/3-Clause | `<n>` | 保留版权与许可声明 |
| 其他 | `<n>` | 逐项核实 |
| **AGPL / SSPL / BUSL** | **必须为 0** | 若不为 0，须移除该依赖 |

### 明细

<!-- 由 tools/gen-third-party.mjs 生成，格式：
| 包名 | 版本 | 许可证 | 仓库 |
|---|---|---|---|
-->

---

## C. Termux 工具链二进制（打包进 `toolchain-*.pkg`）

来源：[Termux 官方仓库](https://packages.termux.dev/)（`apt/termux-main`，aarch64）。
由 `tools/fetch-toolchain.mjs` 解析依赖闭包后下载 `.deb` 并重排布局，**未修改任何二进制内容**。

### 义务摘要（⚠️ 逐包核实后填写）

| 许可证族 | 包（示例，待核实） | 义务 |
|---|---|---|
| GPL-2.0 / GPL-3.0 | `<bash, coreutils, git, grep, sed, gawk, findutils, tar, patch, ...>` | **提供对应源码**（链接或书面要约）；保留许可证与版权声明 |
| LGPL-2.1 / LGPL-3.0 | `<hangover-wine, wowbox64, ...>` | 同 GPL，且须保证用户可**替换**该库 |
| Apache-2.0 | `<clang, lld, aapt2, d8, apksigner, gradle, kotlin, ...>` | 保留 LICENSE + NOTICE |
| Apache-2.0 WITH LLVM-exception | `<clang, lld 中的 LLVM 组件>` | 同上 |
| GPL-2.0 WITH Classpath-exception | `<openjdk-17>` | 保留声明 |
| PSF-2.0 | `<python>` | 保留许可证与版权声明 |
| MIT / ISC / BSD | `<nodejs, ripgrep, openssh, ...>` | 保留版权与许可声明 |

### 源码获取方式

对于 GPL / LGPL 组件，本项目提供以下源码获取途径（满足 GPL 的源码提供义务）：

1. **首选**：各包对应的上游项目源码仓库（见下表），版本与 Termux 仓库中的构建版本对应；
2. **补充**：Termux 构建脚本与补丁位于 <https://github.com/termux/termux-packages>；
3. **兜底**：如需特定版本的源码副本，可联系 `<contact@your-domain>`，我们承诺在合理期限内提供。

### 明细

<!-- 由 tools/gen-third-party.mjs 生成，格式：
| 包名 | 版本 | 许可证 | 上游项目 | 源码地址 |
|---|---|---|---|---|
-->

---

## D. 其他随附组件

| 组件 | 许可证 | 说明 |
|---|---|---|
| ripgrep | MIT / Unlicense（双许可） | 搜索工具，见上游 `THIRD_PARTY_LICENSES.md` |
| AndroidX / Kotlin 标准库 | Apache-2.0 | Gradle 依赖 |

---

## E. 生成与维护

```bash
# 生成 npm 部分
node tools/gen-third-party.mjs --npm

# 生成 Termux 部分（需先有 build/toolchain-cache/）
node tools/gen-third-party.mjs --toolchain

# 全量
node tools/gen-third-party.mjs --all
```

**维护要求**：

- 每次新增/升级依赖 → 重跑生成脚本 → 提交本文件变更
- 每次 Release → 在 Release Notes 中确认本文件已同步
- `CONTRIBUTING.md` 已规定：新增依赖需在 PR 中说明许可证，禁止引入 AGPL/SSPL/BUSL

---

## F. 免责

本清单按"尽力核实"原则维护，可能存在遗漏或标注错误。若你是权利人并认为本项目的使用方式不当，
请联系 `<legal@your-domain>`，我们承诺 **72 小时内响应**并配合整改。
