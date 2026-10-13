# 贡献指南

感谢你有兴趣贡献。本项目由个人业余维护，**请先读本文件再提 PR**，可以节省双方大量时间。

---

## 一、这个项目是做什么的

把 [cc-haha](https://github.com/NanmiCoder/cc-haha)（Claude Code 桌面工作台）移植为 Android 全量版：
在手机上自带 Node 运行时与工具链，**不依赖电脑**完成编码、编译与对话。

核心架构：**上游零修改**——`runtime/upstream/` 保持原样，所有适配写在 `runtime/compat/`（Bun→Node 兼容层）。

---

## 二、八条硬规则（违反的 PR 会被直接关闭）

1. **不得修改 `runtime/upstream/` 中的任何文件。**
   所有适配必须写在 `runtime/compat/` 或 `runtime/build/build.mjs` 中。
   *理由：这是本项目最重要的架构决策，破坏它会导致上游无法升级。*

2. **新增 compat stub 必须实现上游调用的全部方法。**
   只实现"看起来够用"的几个方法，会在运行期以**静默无响应**的形式爆雷——这是本项目踩过的最大坑。
   提交前请 `grep` 上游对该符号的全部静态调用点并逐一补齐。

3. **禁止提交二进制**：`*.pkg`、`*.apk`、`*.deb`、`node_modules/`、构建产物。
   仓库只保留源码与脚本（见 `.gitignore`）。

4. **禁止提交任何凭证**：API Key、Token、keystore、`.env`、内网地址、真实 IP。
   pre-commit 钩子会扫描并阻止；GitHub Push Protection 亦已开启。

5. **必须通过本地校验**：
   ```bash
   node tools/verify.mjs --fast
   ```
   并在 PR 描述中**贴出执行结果**。CI 可能不可用，本地校验是唯一门禁。

6. **新增依赖需说明许可证**，且**不得引入 AGPL / SSPL / BUSL** 类许可的依赖。
   *理由：本项目分发二进制产物，copyleft 类许可会带来不可控义务。*

7. **所有提交必须包含 DCO 签名**：
   ```bash
   git commit -s -m "fix(compat): ..."
   ```
   签名即表示你同意 <https://developercertificate.org/> 的全部条款。未签名的 PR 不会被合并。

8. **不得提交与上游授权状态相关的猜测性声明。**
   涉及授权、商标、合规的问题请走私密渠道（见 `SECURITY.md`），不要在公开 Issue/PR 中辩论。

---

## 三、提交信息规范

采用 [Conventional Commits](https://www.conventionalcommits.org/)：

```
<type>(<scope>): <subject>
```

| type | 用途 |
|---|---|
| `feat` | 新功能 |
| `fix` | 缺陷修复 |
| `perf` | 性能优化 |
| `docs` | 文档 |
| `refactor` | 重构（不改变行为） |
| `build` / `ci` | 构建与流水线 |
| `chore` | 杂项 |

| scope | 模块 |
|---|---|
| `compat` | Bun→Node 兼容层（`runtime/compat/`） |
| `runtime` | 打包规则、运行时组装 |
| `android` | Kotlin 壳、Gradle |
| `tools` | 构建/抓取脚本 |
| `deps` | 依赖升级 |
| `docs` | 文档 |

示例：

```
feat(compat): 补全 sandbox-runtime stub 的 21 个静态方法
fix(compat): bun-serve upgrade 传递原始 IncomingMessage 而非业务 Request
perf(runtime): 分块打包 + compile cache，冷启动 5min → 20s
```

`type` 与 `scope` 必须用英文；`subject` 中英文均可。

---

## 四、开发流程

```bash
# 1. Fork 并克隆
git clone <your-fork-url> && cd cc-haha-android

# 2. 准备上游源码（不随仓库分发）
node tools/fetch-upstream.mjs

# 3. 安装依赖并构建运行时
cd runtime && npm ci && cd ..
node tools/assemble-runtime.mjs

# 4. 校验
node tools/verify.mjs --fast

# 5. 建分支并提交（记得 -s）
git checkout -b fix/your-topic
git commit -s -m "fix(compat): ..."

# 6. 推送并开 PR
```

### 构建环境

| 组件 | 版本 | 备注 |
|---|---|---|
| JDK | 17 | Java 26 对 AGP 过新，勿用 |
| Android SDK | platform android-31 + build-tools 34 | |
| Gradle | 8.7 | **无 wrapper**，命令行直接调用 |
| Node.js | 24+ | 运行 `tools/*.mjs` |

> 已知坑：Gradle 全局镜像需用 `PREFER_SETTINGS` 模式（`FAIL_ON_PROJECT_REPOS` 会冲突）；
> 资产文件必须用 `.pkg` 后缀（aapt2 会解压 `.gz` 并去掉后缀）。

---

## 五、Pull Request 要求

PR 模板中**必须填写**：

- **变更类型与影响模块**
- **是否影响 `RUNTIME_VERSION`**（凡改动 `runtime/` 内容 → 必须 +1，否则老用户不会更新运行时）
- **验证证据**：命令 + 输出；涉及 compat 层的改动**必须附真机验证**（机型/步骤/结果），不接受"理论上可行"
- **未验证项**：如实列出；无则写"无"

### 不会被合并的 PR

- 大规模格式化/重命名（无功能变更的重写），除非事先在 Issue 中达成一致
- 修改 `runtime/upstream/` 内文件
- 引入重量级依赖（尤其需要联网下载模型的）
- 提交二进制产物或凭证
- 与上游授权状态相关的声明性改动

---

## 六、缺陷报告

请使用 [Bug 报告模板](.github/ISSUE_TEMPLATE/bug_report.yml)，并**务必填写机型、Android 版本、ABI、应用版本、`RUNTIME_VERSION`**。
本项目 90% 的"跑不起来"源于环境差异，缺少这些信息无法定位。

带 `needs-info` 标签的 Issue，若 7 天内未补充信息将被关闭（可随时补充后重开）。

---

## 七、响应时间

本项目由个人业余维护，**不做响应承诺**：
Issue 通常 3-7 天首次回应，PR 通常 1-2 周 review。
安全问题请走 `SECURITY.md` 的私密渠道（48 小时内响应）。

---

## 八、成为维护者

当累计 **3 位贡献者各自合并 ≥10 个 PR** 时，项目所有者将邀请其成为维护者（具备 review 与合并权限）。
在此之前采用 BDFL 模式：项目所有者最终拍板，并公开说明理由。

---

## 九、许可证与贡献者协议

- 本项目采用 **Apache-2.0**（见 `LICENSE`）。
- 所有贡献以 **DCO** 方式提交（`git commit -s`），无需签署额外协议。
- 提交即表示你有权提交该代码，并同意其以本项目许可证分发。
