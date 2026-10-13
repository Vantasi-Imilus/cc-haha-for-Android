package com.cchaha.android

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 首次启动（或运行时版本升级）时从 assets 解压运行时：
 *   - assets/runtime.tar.gz 解压到 filesDir/runtime/（bin/node、lib 下的 .so、dist 下的 .js、h5/）
 *   - 之后 chmod +x 全部可执行文件（targetSdk 28 下 filesDir 可执行）
 */
object RuntimeInstaller {
    private const val TAG = "RuntimeInstaller"
    private const val ASSET_TAR = "runtime.pkg"
    private const val ASSET_TOOLCHAIN = "toolchain-core.pkg"

    /** 工具链可执行文件清单（tools/gen-exec-manifest.py 从原始 deb 的 tar 头提取） */
    private const val ASSET_EXEC_MANIFEST = "toolchain-exec-manifest.txt"

    fun isInstalled(context: Context): Boolean {
        val versionFile = File(context.filesDir, "version.txt")
        if (!versionFile.exists()) return false
        val installed = versionFile.readText().trim()
        if (installed != Env.RUNTIME_VERSION) return false
        // 只校验运行时本体（node）；内核产物 dist/ 不再随 APK 分发，
        // 其存在性与完整性由 KernelManager 单独负责（含备份与自愈）。
        return Env.nodeBinary(context).canExecute()
    }

    /** @return 是否实际执行了解压（同步阻塞，调用方需在 IO 线程执行） */
    fun installIfNeeded(context: Context): Boolean {
        if (isInstalled(context)) return false
        Log.i(TAG, "开始安装运行时 v${Env.RUNTIME_VERSION} ...")
        Env.ensureDirs(context)

        try {
            publish(context, "准备安装", "首次启动约需 1-3 分钟")
            // 1. runtime.tar.gz → filesDir/runtime
            watchSize(context, "解压运行时", Env.runtimeDir(context)) {
                extractAssetTar(context, ASSET_TAR, Env.runtimeDir(context))
            }
            // 2. toolchain-core.tar.gz → filesDir/toolchain（预置工具链，可选）
            if (hasAsset(context, ASSET_TOOLCHAIN)) {
                Log.i(TAG, "解压预置工具链 ...")
                watchSize(context, "解压预置工具链", Env.toolchainDir(context)) {
                    extractAssetTar(context, ASSET_TOOLCHAIN, context.filesDir)
                }
                publish(context, "修复工具链", "调整硬编码路径与可执行权限")
                Log.i(TAG, "修复工具链硬编码路径 ...")
                ToolchainManager.fixTermuxPrefix(
                    Env.toolchainDir(context),
                    Env.toolchainDir(context).absolutePath,
                )
                Log.i(TAG, "按清单恢复工具链可执行权限（Windows 打包会丢失 exec 位）...")
                restoreToolchainExecBits(context)
            }
            // 3. 权限：bin 下全部 +x；lib 下 .so 全部可读
            chmodExecRecursive(Env.binDir(context))
            chmodReadRecursive(Env.libDir(context))
            chmodExecRecursive(Env.toolchainBin(context))
            chmodReadRecursive(Env.toolchainLib(context))
            // 4. 生成 cli-launcher 脚本
            publish(context, "写入启动脚本", "")
            Env.writeCliLauncher(context)
            // 4.5 工作区说明文档（让 Agent 了解工作区与特权通道用法）
            deployWorkspaceDoc(context)
            // 5. 写版本标记
            File(context.filesDir, "version.txt").writeText(Env.RUNTIME_VERSION)
            publish(context, "", "")
            Log.i(TAG, "运行时安装完成")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "安装失败", e)
            publish(context, "安装失败", e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    /** 把工作区说明文档（CLAUDE.md）放到默认工作区，供 Agent 阅读 */
    private fun deployWorkspaceDoc(context: Context) {
        try {
            val doc = File(Env.workspaceDir(context), "CLAUDE.md")
            if (doc.exists()) return
            context.assets.open("workspace-CLAUDE.md").use { input ->
                FileOutputStream(doc).use { output -> input.copyTo(output) }
            }
            Log.i(TAG, "工作区说明已部署: ${doc.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "工作区说明部署失败（不影响主流程）", e)
        }
    }

    private fun hasAsset(context: Context, name: String): Boolean = try {
        context.assets.open(name).close()
        true
    } catch (_: Exception) {
        false
    }

    private fun extractAssetTar(context: Context, assetName: String, destDir: File) {
        val tarFile = File(context.cacheDir, assetName)
        try {
            context.assets.open(assetName).use { input ->
                FileOutputStream(tarFile).use { output -> input.copyTo(output) }
            }
            destDir.mkdirs()
            val proc = ProcessBuilder("tar", "-xzf", tarFile.absolutePath, "-C", destDir.absolutePath)
                .redirectErrorStream(true)
                .start()
            val log = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            if (code != 0) {
                throw IllegalStateException("tar 解压失败($assetName, code=$code): ${log.take(500)}")
            }
        } finally {
            tarFile.delete()
        }
    }

    private fun chmodExecRecursive(dir: File) {
        if (!dir.exists()) return
        dir.walkTopDown().forEach { f ->
            if (f.isFile) f.setExecutable(true, false)
        }
    }

    private fun chmodReadRecursive(dir: File) {
        if (!dir.exists()) return
        dir.walkTopDown().forEach { f ->
            if (f.isFile) f.setReadable(true, false)
        }
    }

    /**
     * 按清单恢复工具链可执行权限。
     *
     * 为什么需要：Windows 文件系统没有 Unix 权限位。构建机上解包 deb、再重新打包时，
     * tar 只能按文件名猜测权限，无扩展名的 ELF（如 libexec/git-core/git-remote-http）
     * 会被打成 644 而无法执行，表现为 git clone 报 "unable to find remote helper"。
     * 原实现只对 usr/bin 递归 +x，libexec/ 完全没覆盖，故改为按清单精确恢复。
     * 清单来自原始 deb 的 tar 头（mode & 0o111），是权威来源。
     */
    private fun restoreToolchainExecBits(context: Context) {
        // 注意：Env.toolchainDir 已经是 <files>/toolchain/usr，清单里的路径相对它
        val usr = Env.toolchainDir(context)
        if (!usr.exists()) {
            Log.w(TAG, "工具链目录不存在，跳过权限恢复：${usr.absolutePath}")
            return
        }
        var fixed = 0
        try {
            context.assets.open(ASSET_EXEC_MANIFEST).bufferedReader().useLines { lines ->
                for (rel in lines) {
                    val trimmed = rel.trim()
                    if (trimmed.isEmpty()) continue
                    val f = File(usr, trimmed)
                    if (f.isFile && !f.canExecute() && f.setExecutable(true, false)) {
                        fixed++
                    }
                }
            }
            Log.i(TAG, "工具链权限恢复：修复 $fixed 个文件")
        } catch (e: Exception) {
            Log.w(TAG, "可执行清单处理失败（回退到 bin 目录兜底）", e)
        }
        // 兜底：bin 目录整体 +x（清单缺失时仍可工作）
        chmodExecRecursive(Env.toolchainBin(context))
    }

    // ------------------------------------------------------------ 进度上报

    /** 写安装进度（MainActivity 通过 SharedPreferences 读取，用于准备期提示） */
    private fun publish(context: Context, stage: String, detail: String) {
        context.getSharedPreferences(Env.PREFS_SETTINGS, Context.MODE_PRIVATE)
            .edit()
            .putString(Env.KEY_INSTALL_STAGE, stage)
            .putString(Env.KEY_INSTALL_DETAIL, detail)
            .apply()
    }

    /**
     * 解压期间轮询目标目录体积，实时反馈"已写入 XX MB"。
     * tar 是子进程、不报告进度，用目录体积做近似 —— 数字在涨，用户就知道没卡死。
     */
    private fun watchSize(context: Context, stage: String, dir: File, block: () -> Unit) {
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val watcher = Thread {
            while (!stop.get()) {
                publish(context, stage, "已写入 ${dirSizeText(dir)}")
                try {
                    Thread.sleep(1500)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        watcher.isDaemon = true
        watcher.start()
        try {
            block()
        } finally {
            stop.set(true)
        }
    }

    private fun dirSizeText(dir: File): String {
        var total = 0L
        try {
            dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        } catch (_: Exception) {
            // 解压过程中目录内容在变，瞬时异常忽略即可
        }
        val mb = total / 1024.0 / 1024.0
        return if (mb >= 1024) String.format(java.util.Locale.US, "%.1f GB", mb / 1024)
        else "${mb.toLong()} MB"
    }
}
