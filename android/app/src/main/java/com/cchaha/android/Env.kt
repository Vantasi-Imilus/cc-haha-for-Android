package com.cchaha.android

import android.content.Context
import java.io.File

/**
 * 目录布局（全部位于应用私有目录，targetSdk 28 下可执行）：
 *
 *   filesDir/
 *   ├── runtime/            # 运行时（assets/runtime.tar.gz 解压产物）
 *   │   ├── bin/            # node 等可执行文件
 *   │   ├── lib/            # 动态库（LD_LIBRARY_PATH）
 *   │   ├── dist/           # cc-haha Node bundle（server.js/cli.js/...）
 *   │   └── h5/             # H5 前端（desktop/dist 构建产物）
 *   ├── toolchain/usr/      # 工具链 prefix（bin/lib/share，Termux 布局）
 *   ├── home/               # HOME（~/.claude 配置与会话数据）
 *   ├── workspace/          # 默认工作目录（CALLER_DIR）
 *   ├── tmp/                # TMPDIR
 *   └── version.txt         # 运行时版本标记（变更后触发重装）
 */
object Env {
    const val CHANNEL_ID = "cchaha-server"
    const val NOTIFICATION_ID = 1

    const val DEFAULT_PORT = 3456

    /** 改动此值会触发下次启动时重装运行时（保留 App 数据与配置） */
    const val RUNTIME_VERSION = "5"

    /** 安装进度上报（RuntimeInstaller 写，MainActivity 读，用于准备期进度提示） */
    const val KEY_INSTALL_STAGE = "install_stage"
    const val KEY_INSTALL_DETAIL = "install_detail"
    const val PREFS_SETTINGS = "settings"

    fun filesDir(context: Context): File = context.filesDir

    fun runtimeDir(context: Context) = File(filesDir(context), "runtime")
    fun binDir(context: Context) = File(runtimeDir(context), "bin")
    fun libDir(context: Context) = File(runtimeDir(context), "lib")
    fun distDir(context: Context) = File(runtimeDir(context), "dist")
    fun h5Dir(context: Context) = File(runtimeDir(context), "h5")
    fun toolchainDir(context: Context) = File(filesDir(context), "toolchain/usr")
    fun toolchainBin(context: Context) = File(toolchainDir(context), "bin")
    fun toolchainLib(context: Context) = File(toolchainDir(context), "lib")
    fun homeDir(context: Context) = File(filesDir(context), "home")
    fun tmpDir(context: Context) = File(filesDir(context), "tmp")

    /** 私有回退工作区（外部存储不可用时使用） */
    fun privateWorkspaceDir(context: Context) = File(filesDir(context), "workspace")

    /**
     * 默认工作区：外部存储公共目录（用户可用文件管理器直接访问项目文件）。
     *
     * 背景：应用私有目录（/data/user/0/...）在 Android 上对非 root 完全
     * 隔离，用户无法用任何文件管理器访问，且部分工具链操作受限。因此把
     * 「用户代码」放在 /storage/emulated/0/cc-haha/。
     *
     * 注意：运行时（node + 动态库）、工具链、HOME 配置仍留在私有目录 ——
     * 外部存储是 fuse 挂载（noexec、无 Unix 权限位、无符号链接），
     * 二进制无法执行、npm/git 等依赖符号链接的工具会失败。
     */
    fun workspaceDir(context: Context): File {
        val external = File("/storage/emulated/0/cc-haha")
        return if (isExternalUsable(context, external)) external
        else privateWorkspaceDir(context)
    }

    /** 外部存储可用性：目录可创建/可写 */
    private fun isExternalUsable(context: Context, dir: File): Boolean {
        return try {
            if (!dir.exists()) dir.mkdirs()
            dir.exists() && dir.canWrite()
        } catch (_: Exception) {
            false
        }
    }

    fun nodeBinary(context: Context) = File(binDir(context), "node")
    fun cliLauncher(context: Context) = File(binDir(context), "cli-launcher")
    // split 构建（esbuild code splitting + V8 compile cache）：手机上
    // 单文件 89MB bundle 冷启动 5 分钟+，split 版 20-40 秒且热缓存后更快
    fun serverBundle(context: Context) = File(distDir(context), "server-split/server.js")
    fun cliBundle(context: Context) = File(distDir(context), "cli-split/cli.js")

    /** 保证基础目录存在 */
    fun ensureDirs(context: Context) {
        for (d in listOf(
            homeDir(context), workspaceDir(context), tmpDir(context),
            binDir(context), libDir(context), distDir(context), h5Dir(context),
            toolchainBin(context), toolchainLib(context),
        )) {
            d.mkdirs()
        }
        // node 需要可写的 home/.claude
        File(homeDir(context), ".claude").mkdirs()
    }

    /**
     * 构建 node 进程环境变量（真机实测的必需项已固化）：
     * - LD_LIBRARY_PATH：runtime/lib + toolchain/lib（Termux 系二进制依赖）
     * - OPENSSL_CONF=/dev/null：绕过 Termux 硬编码 openssl.cnf 路径
     * - PATH：工具链 + 运行时 + 系统
     */
    fun nodeEnv(context: Context, port: Int = DEFAULT_PORT): Map<String, String> {
        val base = System.getenv().toMutableMap()
        val files = filesDir(context).absolutePath
        val runtime = runtimeDir(context).absolutePath
        val toolchain = toolchainDir(context).absolutePath
        val libPath = listOf(
            libDir(context).absolutePath,
            toolchainLib(context).absolutePath,
        ).joinToString(":")
        val pathParts = listOf(
            toolchainBin(context).absolutePath,
            // openjdk 的 java 在 lib/jvm/java-17-openjdk/bin（d8/apksigner/gradle 脚本依赖）
            File(toolchain, "lib/jvm/java-17-openjdk/bin").absolutePath,
            binDir(context).absolutePath,
            base["PATH"] ?: "/system/bin",
        ).joinToString(":")

        base["HOME"] = homeDir(context).absolutePath
        base["TMPDIR"] = tmpDir(context).absolutePath
        // CLI 的 Bash 工具（upstream/src/utils/Shell.ts）读的是 CLAUDE_CODE_TMPDIR
        // 而非 TMPDIR，未设置时会回退到 '/tmp' 并在 Android 上 mkdir 失败（根目录只读），
        // 表现为 Bash 工具完全不可用（ENOENT: mkdir '/tmp'）—— 真机实测定位。
        base["CLAUDE_CODE_TMPDIR"] = tmpDir(context).absolutePath
        base["PATH"] = pathParts
        base["LD_LIBRARY_PATH"] = libPath
        base["OPENSSL_CONF"] = "/dev/null"

        // ── 工具链的编译期路径重定位（Termux 前缀 → 本应用私有目录）──────
        // Termux 系二进制把 /data/data/com.termux/files/usr 编译进程序，
        // 重定位后这些路径全部失效，且各工具读取的变量名不同，需逐个补齐。
        // （真机自检定位，见项目记忆「Bash 工具 TMPDIR 问题」一节）
        val tcCert = File(toolchainDir(context), "etc/tls/cert.pem")
        if (tcCert.isFile) {
            val cert = tcCert.absolutePath
            base["CURL_CA_BUNDLE"] = cert // curl
            base["SSL_CERT_FILE"] = cert // openssl / python ssl
            base["REQUESTS_CA_BUNDLE"] = cert // python requests
            base["GIT_SSL_CAINFO"] = cert // git（注意 git 不认 CURL_CA_BUNDLE）
            base["NODE_EXTRA_CA_CERTS"] = cert // node
        }
        base["GIT_CONFIG_NOSYSTEM"] = "1" // 编死的 /etc/gitconfig 不存在
        base["GIT_ATTR_NOSYSTEM"] = "1" // 同上，gitattributes

        // 文件浏览 API 白名单默认只有 home + /tmp + 会话 workDir，导致目录选择器的
        // 「内部存储」按钮（目标为 workDir 的父目录 /storage/emulated/0）被 403 拒绝。
        // 读取逻辑由 build.mjs 的 androidAccessRoots 插件在构建期注入。
        base["CC_HAHA_EXTRA_ROOTS"] = "/storage/emulated/0"
        base["GIT_EXEC_PATH"] = File(toolchainDir(context), "libexec/git-core").absolutePath
        base["GIT_TEMPLATE_DIR"] = File(toolchainDir(context), "share/git-core/templates").absolutePath
        // cc-haha 专用
        base["CLAUDE_CONFIG_DIR"] = File(homeDir(context), ".claude").absolutePath
        base["CLAUDE_H5_DIST_DIR"] = h5Dir(context).absolutePath
        base["CLAUDE_CLI_PATH"] = cliLauncher(context).absolutePath
        base["CC_HAHA_DIST_DIR"] = distDir(context).absolutePath
        base["CALLER_DIR"] = workspaceDir(context).absolutePath
        base["CC_HAHA_SKIP_DOTENV"] = "1"
        base["NODE_BIN"] = nodeBinary(context).absolutePath
        base["CLI_BUNDLE"] = cliBundle(context).absolutePath
        base["CC_HAHA_COMPILE_CACHE_DIR"] =
            java.io.File(filesDir(context), "compile-cache").absolutePath
        // Android 上禁用桌面专属能力
        base["CC_HAHA_ANDROID"] = "1"
        // 非必要远程流量全部关闭：telemetry/statsig/policy/autoupdate。
        // 国内网络 api.claude.ai 等域名 DNS 不可达，相关初始化会挂起
        // 或长时重试，导致 CLI 启动数分钟无响应（真机实测）。
        base["CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC"] = "1"
        base["DISABLE_TELEMETRY"] = "1"
        base["DISABLE_ERROR_REPORTING"] = "1"
        base["DISABLE_AUTOUPDATER"] = "1"
        base["DISABLE_NETWORK_LOGGING"] = "1"
        base["DISABLE_COST_WARNINGS"] = "1"
        // 调试标记：files/debug-spawn-log 存在时，子进程 stderr 落盘（诊断用）
        if (java.io.File(filesDir(context), "debug-spawn-log").exists()) {
            base["CC_HAHA_SPAWN_LOG_DIR"] =
                java.io.File(filesDir(context), "logs/spawn").absolutePath
        }
        // 启动追踪标记：files/debug-startup-trace 存在时，CLI 每 20 秒
        // dump 一次启动阶段 marks（定位初始化卡点）
        if (java.io.File(filesDir(context), "debug-startup-trace").exists()) {
            base["CC_HAHA_STARTUP_TRACE"] = "1"
            base["CLAUDE_CODE_PROFILE_STARTUP"] = "1"
        }
        return base
    }

    /** 写入 cli-launcher 脚本（server 以子进程方式拉起 CLI 时使用） */
    fun writeCliLauncher(context: Context) {
        val script = cliLauncher(context)
        script.parentFile?.mkdirs()
        script.writeText(
            "#!/system/bin/sh\n" +
                "# 由 cc-haha-android 生成：server 通过 CLAUDE_CLI_PATH 拉起 CLI 子进程\n" +
                "exec \"\$NODE_BIN\" \"\$CLI_BUNDLE\" \"\$@\"\n",
        )
        script.setExecutable(true, false)
    }
}
