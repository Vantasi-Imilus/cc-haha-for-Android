# cc-haha Android 工作区

这是 cc-haha 安卓版的默认工作区（位于 `/storage/emulated/0/cc-haha`，
你可以用任意文件管理器访问这里的文件）。

## 特权命令通道（shell 身份）

本应用的宿主进程已通过 **Shizuku / 群星之眼** 获得 shell 级权限。
当任务**确实需要**特权时（访问受限目录如 `/data/local/tmp`、读取其他
应用数据、执行需要 shell 身份的系统命令），走本地特权通道：

```bash
curl -s -X POST \
  -H "X-CC-Token: $CC_HAHA_PRIV_TOKEN" \
  -d '{"cmd":"id; ls /data/local/tmp"}' \
  "http://127.0.0.1:$CC_HAHA_PRIV_PORT/exec"
```

返回 JSON：`{"ok":true,"output":"exit=0\nuid=2000(shell)..."}`

环境变量 `CC_HAHA_PRIV_PORT` 与 `CC_HAHA_PRIV_TOKEN` 已在你的 shell 环境中
自动注入（每次应用启动轮换）。

**使用原则**：
- 能用普通 bash 完成的，不要走特权通道 —— 它更慢且影响面更大
- 特权通道以 `shell` 用户（uid 2000）执行，不是 root
- 通道仅监听 `127.0.0.1`，并校验一次性 token

## 环境说明

- 工作区（当前目录）：`/storage/emulated/0/cc-haha`（外部存储，用户可见）
- 注意：外部存储是 fuse 挂载，**不支持可执行权限与符号链接**；
  需要编译产物可执行时，请输出到应用私有目录或 `/data/local/tmp`
- 工具链：`node` / `python` / `clang` / `git` / `make` / `cmake` / `ninja` /
  `curl` / `ssh` 等已在 PATH 中（App 内置）
- 编译产物路径建议：`/data/local/tmp/<项目名>/`（可执行、可用特权通道访问）
