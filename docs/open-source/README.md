# cc-haha Android 开源治理文档包

> 本目录回答一个问题：**把 `cc-haha-android` 放到 GitHub 上开源，需要满足什么条件、避开哪些雷、按什么顺序做。**
> 主体是任何项目可复用的开源规范，每个章节末尾都落到本项目的具体动作。

---

## 0. 三句话结论（没时间就看这段）

1. **技术上你已经可以开源**——仓库内无密钥泄露（全仓扫描 0 命中），代码结构清晰（上游零修改 + compat 层隔离）。
2. **法务上是红灯**——你的 APK 通过 `runtime.pkg` 分发了从上游 `cc-haha`（MIT）打包而来的代码，而该上游的 `src/`（2224 个 TS 文件）与 Anthropic 专有的 Claude Code CLI 内部实现高度一致，且上游**未作任何来源声明与授权说明**。MIT 只能授权"上游有权授权的部分"。**这是开源前必须做的第一个决策**，见 `01-法律与许可合规.md` §2。
3. **工程上有一次必踩的坑**——本工程工作区体积约 **7GB**（`build-env` 1.4G / `build` 2.8G / `android` 3.2G / `runtime` 1.3G），且当前**没有 `.gitignore`**。直接 `git add .` 会吞进构建产物、346MB 的 `assets/*.pkg`、`runtime/node_modules` 与 `runtime/upstream` 全量副本。**第一步永远是先落 `.gitignore`**，见 `06-开源操作手册.md` Phase 1。

---

## 1. 文档地图

| 文件 | 内容 | 谁必读 | 优先级 |
|---|---|---|---|
| [`01-法律与许可合规.md`](./01-法律与许可合规.md) | 权利分层、**Anthropic 派生代码风险与 3 种处置方案**、商标命名规范、Termux 二进制 GPL/LGPL 义务、许可证选型、DCO/CLA、第三方清单维护 | 项目所有者 | ★★★ 阻断 |
| [`02-安全与密钥管理.md`](./02-安全与密钥管理.md) | 密钥生命周期与四道防线、泄露应急、供应链门禁、Release 产物签名、**本项目特有攻击面**（WebView/特权 exec 通道/Shizuku/targetSdk 28） | 维护者 | ★★★ |
| [`03-仓库工程与发布规范.md`](./03-仓库工程与发布规范.md) | 仓库边界（什么进什么不进）、大文件策略、首次建仓、提交/分支规范、**墙内 CI 现实方案**、版本与发布、可复现构建 | 维护者 | ★★ |
| [`04-社区治理与协作规范.md`](./04-社区治理与协作规范.md) | 治理模型、Issue/PR 规范（含安卓特有复现信息要求）、行为准则、沟通渠道与语言策略、法务/安全升级路径、bus factor | 维护者 | ★★ |
| [`05-落地检查表.md`](./05-落地检查表.md) | **P0 阻断项 / P1 发布前 / P2 持续改进** 三段式体检表，逐项含"现状 → 要求 → 整改动作 → 验收证据" | 执行者 | ★★★ |
| [`06-开源操作手册.md`](./06-开源操作手册.md) | Phase 0-6 可复制粘贴的命令序列，从当前状态到 GitHub 上有一个干净仓库 + 首个 Release | 执行者 | ★★★ |
| [`templates/`](./templates) | 可直接复制到仓库根目录使用的文件 | 执行者 | ★★★ |

---

## 2. 待决策台账（阻塞项）

> 这 4 项必须由**项目所有者**拍板，未拍板前 P0 检查表无法闭合。

| # | 决策项 | 选项 | 推荐 | 影响面 | 状态 |
|---|---|---|---|---|---|
| D1 | **开源范围** | A 全量开源（含 `runtime/upstream/` 与打包产物）<br>B 仅开源 Android 壳 + compat 层 + 工具链脚本，上游由使用者自行获取<br>C 全量代码开源但**不分发预编译 APK** | **B + C 组合** | 决定 Anthropic 派生代码风险等级（见 01 §2.3） | ⬜ 待决策 |
| D2 | **许可证** | MIT / Apache-2.0 / GPL-3.0 / AGPL-3.0 | **Apache-2.0**（含显式专利授权与商标条款，适合系统集成类工程；若追求与上游一致则选 MIT） | 决定他人能否闭源二次分发、专利与商标条款 | ⬜ 待决策 |
| D3 | **贡献者协议** | DCO（轻量，`-s` 签名） / CLA（重，需签署系统） | **DCO** | 决定 PR 流程复杂度与版权集中度 | ⬜ 待决策 |
| D4 | **托管与镜像策略** | 仅 GitHub / GitHub + Gitee 镜像 / 仅 Gitee | **GitHub + Gitee 镜像** | 本机 GitHub 不通（见记忆 `reference_build_env.md`），且国内用户下载 300MB+ APK 需要加速 | ⬜ 待决策 |

---

## 3. 红线（不可谈判项）

以下 8 条任何一条不满足，**不得开源**：

```
☐ R1  仓库历史中不存在任何真实密钥 / Token / 签名证书（含历史提交，不只是当前 HEAD）
☐ R2  .gitignore 已生效，构建产物、node_modules、upstream 副本、*.pkg、*.apk 未入库
☐ R3  已明确 Anthropic 派生代码的处置方式（D1 决策落地），README 顶部有非官方声明与免责声明
☐ R4  已选定额外许可证并生成 LICENSE 文件，第三方许可证清单（THIRD_PARTY_LICENSES.md）随仓库发布
☐ R5  README 中不出现"官方""Claude Code 安卓版"等暗示与 Anthropic 存在关联的表述
☐ R6  已声明 targetSdk 28 的安全代价与仅限侧载的分发方式（用户知情）
☐ R7  漏洞报告渠道（SECURITY.md）已就位，且不引导用户用公开 Issue 报安全问题
☐ R8  账号已开启 2FA；公共仓库已开启 Secret Scanning + Push Protection + Dependabot
```

---

## 4. 现状体检摘要（2026-09-15 实测）

| 维度 | 现状 | 判定 |
|---|---|---|
| 密钥泄露 | 全仓扫描（排除 `node_modules`/`build-env`/`build`/`upstream`/`dist`）**0 命中**；仅上游 `.env.example` 为示例文件 | ✅ 通过 |
| 许可证文件 | 无 `LICENSE` | ❌ P0 |
| `.gitignore` | **不存在**（工程内 7GB 构建产物） | ❌ P0 |
| `.git` | 未初始化 | ⬜ 待办 |
| 社区文件 | 无 `CONTRIBUTING` / `SECURITY` / `CODE_OF_CONDUCT` / `.github/` | ❌ P1 |
| README | 已存在（7969 字节，架构与决策描述完整，质量高） | ✅ 通过（需补免责声明） |
| 依赖锁文件 | `runtime/package-lock.json` 存在 | ✅ 通过 |
| Release 签名 | `android/app/build.gradle.kts:29` 使用 **debug 签名** | ⚠️ 分发前须换正式 keystore（见 02 §6） |
| 上游许可证 | `cc-haha-main/LICENSE` = MIT (c) 2026 cc-haha | ⚠️ 见 D1 |
| 派生代码风险 | 上游 `src/` 与 Anthropic Claude Code 内部实现高度一致，无来源声明 | 🔴 **最高危** |

---

## 5. 使用方式

```bash
# 1) 先读三份：本文件 → 01（法务）→ 05（检查表）
# 2) 拍板 D1-D4（第 2 节）
# 3) 照 06 操作手册逐 Phase 执行，每 Phase 有验收证据
# 4) 把 templates/ 下的文件复制到仓库根目录（06 Phase 3 有命令）
```

---

## 6. 免责

本目录内容为**工程实践建议，不构成法律意见**。涉及许可证解释、商标使用、二次分发的判断，请咨询执业律师；文档中所有未经核实的结论均标注 `⚠️ 待确认` 并附核实方法。

## 资料来源

- `cc-haha-main/LICENSE`（MIT，Copyright (c) 2026 cc-haha）
- `cc-haha-main/package.json`（`name: claude-code-local`, `private: true`, 无 repository 字段）
- `cc-haha-main/.gitignore`、`.github/`、`THIRD_PARTY_LICENSES.md`、`.env.example`、`CONTRIBUTING.md`
- `cc-haha-android/runtime/build/build.mjs:4,214,271`（从 `upstream/` 打包入口）
- `cc-haha-android/runtime/package.json`（88 个运行期依赖 + esbuild）
- `cc-haha-android/tools/fetch-toolchain.mjs`（Termux 源拉取与依赖闭包）
- `cc-haha-android/android/app/build.gradle.kts:29`（debug 签名）
- 体积数据：`du -sh` 实测于 2026-09-15
