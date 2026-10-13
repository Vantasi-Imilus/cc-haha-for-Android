<!--
提交前请确认：
  1. 每个 commit 都有 DCO 签名（git commit -s）
  2. 已运行 node tools/verify.mjs --fast 并贴出结果
  3. 若改动 compat 层 → 必须附真机验证证据
-->

## 变更类型

- [ ] `fix` 缺陷修复
- [ ] `feat` 新功能
- [ ] `perf` 性能优化
- [ ] `refactor` 重构（不改变行为）
- [ ] `docs` 文档
- [ ] `build` / `ci` 构建与流水线
- [ ] `deps` 依赖升级

## 变更说明

<!-- 一句话说明「为什么」要改，而不是「改了什么」（后者看 diff 即可） -->

关联 Issue：#

## 影响面

| 项 | 内容 |
|---|---|
| 受影响模块 | <!-- compat / runtime / android / tools / docs --> |
| 是否修改 `runtime/` | 是 / 否 |
| **是否影响 `RUNTIME_VERSION`** | **是（已 +1 → 新值：N）/ 否** |
| 是否新增依赖 | 是（包名 + 许可证：）/ 否 |
| 是否有破坏性变更 | 是（说明：）/ 否 |

> ⚠️ 凡改动 `runtime/` 内容，**必须**将 `RUNTIME_VERSION` +1，否则老用户不会重新解压运行时。

## 验证证据（必填，无证据不 review）

**1. 本地校验**

```
$ node tools/verify.mjs --fast
<!-- 粘贴完整输出 -->
```

**2. 真机验证**（改动 compat / runtime / android 时**必填**）

| 项 | 内容 |
|---|---|
| 机型 / ROM | |
| Android 版本 / ABI | |
| 应用版本 / RUNTIME_VERSION | |
| 验证步骤 | |
| 结果 | <!-- 截图或日志；说明「完整对话跑通」等关键路径是否验证 --> |

**3. 其他证据**（如适用）

<!-- 性能对比数据、解包检查、命令输出等 -->

## 未验证项（必填）

<!--
如实列出本次改动中未能验证的部分，无则写"无"。
例：未在 armeabi-v7a 设备上验证；未测试 Android 15 上的兼容性。
禁止写"应该没问题"。
-->

无

## 自查清单

- [ ] 未修改 `runtime/upstream/` 中任何文件
- [ ] 新增的 compat stub 已实现上游调用的**全部**方法（已 `grep` 上游调用点确认）
- [ ] 未提交任何二进制产物（`*.pkg` / `*.apk` / `node_modules`）
- [ ] 未提交任何凭证（API Key / Token / keystore / `.env` / 内网地址）
- [ ] 所有 commit 均有 DCO 签名（`git commit -s`）
- [ ] 提交信息符合 Conventional Commits 规范
- [ ] 若新增依赖：已确认许可证，且非 AGPL / SSPL / BUSL
- [ ] 若涉及文档：已同步更新相关章节与索引
- [ ] 已阅读 `CONTRIBUTING.md` 的八条硬规则
