package com.cchaha.android

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 内核获取与设备端构建。
 *
 * 设计前提：APK 不内置内核（cc-haha / Claude Code 产物），由用户自行获取源码、
 * 在设备上用内置的构建套件（assets/buildkit.pkg）编译。三条来源通道：
 *
 *   1. GITCODE  —— 从 GitCode 的 cc-haha 镜像拉取源码后 esbuild 构建。
 *                  镜像由 AtomGit 平台自动同步，不是本应用分发的内容。
 *                  产出完整功能（server + cli + IM 适配器）。
 *   2. OFFICIAL —— 从 npmmirror 下载 Anthropic 官方 npm 包（Node 可直接运行，
 *                  无需构建）。注意：官方包只含 CLI，**不含 cc-haha 的 server
 *                  层**，因此不能驱动 H5 工作台，仅作命令行通道使用。
 *   3. LOCAL    —— 用户用系统文件选择器指定的自备压缩包（tar.gz / zip）。
 *
 * 构建成功后调用 KernelManager.writeManifest() + backup() 落盘快照。
 */
object KernelInstaller {
    private const val TAG = "KernelInstaller"

    /** cc-haha 的 GitCode 镜像（AtomGit 平台自动同步目录） */
    const val GITCODE_REPO = "https://gitcode.com/gh_mirrors/cl/cc-haha.git"

    private const val OFFICIAL_PKG = "@anthropic-ai/claude-code"
    private const val NPM_MIRROR = "https://registry.npmmirror.com"

    /** Alpine 镜像：musl 的 libc 与动态链接器是同一个文件，取来即可在 Android 上跑 musl 程序 */
    private const val ALPINE_MIRROR =
        "https://mirrors.tuna.tsinghua.edu.cn/alpine/latest-stable"

    enum class Source(val id: String, val label: String, val note: String) {
        GITCODE("gitcode", "cc-haha 镜像（GitCode）", "完整功能：H5 工作台 + CLI + IM 适配器"),
        OFFICIAL("official", "Claude Code 官方包", "仅命令行通道：官方包不含 H5 工作台后端"),
        LOCAL("local", "本地压缩包", "使用你自备的 cc-haha 源码包"),
    }

    sealed class Progress {
        data class Stage(val name: String) : Progress()
        data class Percent(val value: Int, val detail: String = "") : Progress()
        data class Log(val line: String) : Progress()
        data class Done(val summary: String) : Progress()
    }

    class BuildException(message: String, val log: String = "") : Exception(message)

    /**
     * 完整流程：准备构建套件 → 获取源码 → 构建 → 安装 → manifest → 备份。
     * 阻塞调用，请在后台线程执行。
     */
    fun install(
        context: Context,
        source: Source,
        localArchive: File? = null,
        onProgress: (Progress) -> Unit,
    ) {
        val work = KernelManager.buildWorkDir(context)
        deleteTree(work)
        work.mkdirs()
        val kit = File(work, "buildkit")
        var succeeded = false

        try {
            onProgress(Progress.Stage("准备构建套件"))
            unpackAsset(context, "buildkit.pkg", work)
            if (!File(kit, "build/build.mjs").isFile) {
                throw BuildException("构建套件解压异常：缺少 build/build.mjs")
            }
            fixBuildkitExecBits(kit)

            when (source) {
                Source.GITCODE -> {
                    onProgress(Progress.Stage("获取源码（GitCode 镜像）"))
                    val repo = File(kit, "upstream")
                    cloneGitCode(context, repo, onProgress)
                    onProgress(Progress.Stage("构建内核（设备端 esbuild）"))
                    runBuild(context, kit, onProgress)
                }
                Source.LOCAL -> {
                    onProgress(Progress.Stage("导入本地源码包"))
                    val repo = File(kit, "upstream")
                    unpackLocal(context, localArchive ?: throw BuildException("未选择文件"), repo, onProgress)
                    if (!File(repo, "src").isDirectory) {
                        throw BuildException("压缩包结构不符：未找到 src/ 目录")
                    }
                    onProgress(Progress.Stage("构建内核（设备端 esbuild）"))
                    runBuild(context, kit, onProgress)
                }
                Source.OFFICIAL -> {
                    onProgress(Progress.Stage("下载官方 CLI 包"))
                    fetchOfficial(context, kit, onProgress)
                }
            }

            val dist = File(kit, "dist")

            // 官方包单独存放：它只含 CLI，没有 cc-haha 的 server 层，
            // 若放进 runtime/dist 会让完整性校验永远判定 BROKEN（缺 server-split），
            // 用户会卡在引导页死循环。如实标注为"仅命令行通道"。
            if (source == Source.OFFICIAL) {
                if (!dist.isDirectory) throw BuildException("官方包解压异常")
                val dest = File(context.filesDir, "official-cli")
                deleteTree(dest)
                copyTree(dist, dest)
                succeeded = true
                onProgress(Progress.Stage("完成"))
                onProgress(
                    Progress.Done(
                        "官方 CLI 已就绪：official-cli/run-claude（仅命令行通道）。\n" +
                            "H5 工作台需要 cc-haha 内核，请改用「cc-haha 镜像」或「本地压缩包」。",
                    ),
                )
                return
            }

            onProgress(Progress.Stage("安装内核"))
            if (!dist.isDirectory) throw BuildException("构建未产出 dist/ 目录")
            installDist(context, dist)

            val n = KernelManager.writeManifest(context, source.id, null)
            if (n < 0) throw BuildException("写入 manifest 失败")
            if (!KernelManager.backup(context)) Log.w(TAG, "备份失败（不影响使用）")
            onProgress(Progress.Stage("完成"))
            onProgress(Progress.Done("内核构建完成：$n 个文件，已备份"))
            succeeded = true
        } catch (e: Exception) {
            Log.e(TAG, "内核安装失败", e)
            throw e
        } finally {
            // 成功才清理；失败保留现场（下次 install 会先清理），便于排查
            if (succeeded) deleteTree(work)
            else Log.w(TAG, "构建失败，保留工作目录以便排查：${work.absolutePath}")
        }
    }

    // ------------------------------------------------------------ 源码获取

    /** git clone --depth 1（toolchain 的 git 需要一组路径修正，见 KernelManager 注释） */
    private fun cloneGitCode(context: Context, dest: File, onProgress: (Progress) -> Unit) {
        val usr = Env.toolchainDir(context)
        val git = File(usr, "bin/git")
        if (!git.canExecute()) {
            throw BuildException("工具链 git 不可用（权限修复未生效？），无法拉取源码")
        }
        val env = HashMap(Env.nodeEnv(context))
        // Termux git 的硬编码路径修正（编译期嵌入了 /data/data/com.termux/files/usr）
        env["GIT_CONFIG_NOSYSTEM"] = "1"
        env["GIT_ATTR_NOSYSTEM"] = "1"
        env["GIT_EXEC_PATH"] = File(usr, "libexec/git-core").absolutePath
        env["GIT_TEMPLATE_DIR"] = File(usr, "share/git-core/templates").absolutePath
        // 证书已由 Env.nodeEnv() 通过 GIT_SSL_CAINFO 统一注入，无需跳过校验

        val code = run(
            listOf(
                git.absolutePath, "clone", "--depth", "1", "--progress",
                GITCODE_REPO, dest.absolutePath,
            ),
            workDir = dest.parentFile ?: dest, env = env, onLine = { line ->
                onProgress(Progress.Log(line))
                parseGitProgress(line)?.let { onProgress(Progress.Percent(it, "拉取源码")) }
            },
        )
        if (code != 0 || !File(dest, "src").isDirectory) {
            throw BuildException("git clone 失败（退出码 $code）")
        }
    }

    /**
     * 下载 Anthropic 官方 CLI（免构建）。
     *
     * 官方包的实情（实测）：
     *   - `@anthropic-ai/claude-code` 本体只有 27KB，是个安装器 wrapper；
     *     真正的 CLI 在 optionalDependencies 的平台包里。
     *   - 平台包选 `linux-arm64-musl`（95MB → 解压 210MB 单文件二进制）。
     *   - 它是 **musl 动态链接**，需要 `/lib/ld-musl-aarch64.so.1`，Android 没有；
     *     musl 把 libc 与动态链接器合并成单个文件，从 Alpine 取来即可。
     *     实测 `./ld-musl-aarch64.so.1 ./claude --version` → `2.1.272 (Claude Code)`。
     */
    private fun fetchOfficial(context: Context, kit: File, onProgress: (Progress) -> Unit) {
        val out = File(kit, "dist")
        out.mkdirs()

        // 1. 版本号
        onProgress(Progress.Log("查询官方版本 ..."))
        val meta = httpGet("$NPM_MIRROR/${OFFICIAL_PKG.replace("/", "%2f")}", "application/json")
            .decodeToString()
        val latest = Regex("\"latest\"\\s*:\\s*\"([^\"]+)\"").find(meta)?.groupValues?.get(1)
            ?: throw BuildException("无法解析官方包版本号")
        onProgress(Progress.Log("最新版本：$latest"))

        // 2. 平台二进制
        val plat = "linux-arm64-musl"
        onProgress(Progress.Log("下载平台二进制（约 95MB）..."))
        val platTgz = File(kit, "official-platform.tgz")
        download(
            "$NPM_MIRROR/@anthropic-ai/claude-code-$plat/-/claude-code-$plat-$latest.tgz",
            platTgz,
        ) { got, total ->
            if (total > 0) onProgress(Progress.Percent((got * 100 / total).toInt(), "下载官方 CLI"))
        }
        extractTgz(context, platTgz, out, onProgress)
        platTgz.delete()

        // 3. musl 动态链接器（无它则报 "No such file or directory"）
        onProgress(Progress.Log("获取 musl 运行库 ..."))
        val index = httpGet("$ALPINE_MIRROR/main/aarch64/").decodeToString()
        val muslName = Regex("(musl-[0-9][^\"]*\\.apk)").find(index)?.groupValues?.get(1)
            ?: throw BuildException("无法从 Alpine 镜像解析 musl 包名")
        val muslApk = File(kit, "musl.apk")
        download("$ALPINE_MIRROR/main/aarch64/$muslName", muslApk) { _, _ -> }
        val muslTmp = File(kit, "_musl")
        extractTgz(context, muslApk, muslTmp, onProgress)
        val loader = File(muslTmp, "lib/ld-musl-aarch64.so.1")
        if (!loader.isFile) throw BuildException("musl 包中未找到 ld-musl-aarch64.so.1")
        val loaderDest = File(out, "ld-musl-aarch64.so.1")
        if (loaderDest.exists()) loaderDest.delete()
        loader.renameTo(loaderDest)
        loaderDest.setExecutable(true, false)
        deleteTree(muslTmp)
        muslApk.delete()

        // 4. 启动器
        val launcher = File(out, "run-claude")
        launcher.writeText(
            "#!/system/bin/sh\n" +
                "# 官方 Claude Code CLI 启动器（musl 动态链接，需显式指定 loader）\n" +
                "DIR=\$(cd \$(dirname \"\$0\") && pwd)\n" +
                "exec \"\$DIR/ld-musl-aarch64.so.1\" \"\$DIR/claude\" \"\$@\"\n",
        )
        launcher.setExecutable(true, false)
        onProgress(Progress.Log("已就绪：${out.list()?.joinToString(", ").orEmpty()}"))
    }

    /** 解压 .tgz 并剥掉顶层目录（Android 的 toybox tar 不支持 --strip-components） */
    private fun extractTgz(
        context: Context,
        tgz: File,
        dest: File,
        onProgress: (Progress) -> Unit,
    ) {
        val tmp = File(tgz.parentFile ?: dest, "_extract_tmp")
        deleteTree(tmp)
        tmp.mkdirs()
        val code = run(
            listOf("tar", "-xzf", tgz.absolutePath, "-C", tmp.absolutePath),
            workDir = tmp,
            env = Env.nodeEnv(context),
            onLine = { onProgress(Progress.Log(it)) },
        )
        if (code != 0) throw BuildException("解压失败（退出码 $code）：${tgz.name}")
        val pkg = File(tmp, "package")
        val src = if (pkg.isDirectory) pkg else tmp
        dest.mkdirs()
        src.listFiles()?.forEach { f ->
            val target = File(dest, f.name)
            if (target.exists()) deleteTree(target)
            if (!f.renameTo(target)) copyTree(f, target)
        }
        deleteTree(tmp)
    }

    /** 解压用户自备的源码包（tar.gz / tgz / zip） */
    private fun unpackLocal(
        context: Context,
        archive: File,
        dest: File,
        onProgress: (Progress) -> Unit,
    ) {
        if (!archive.isFile) throw BuildException("文件不存在：${archive.absolutePath}")
        dest.mkdirs()
        val name = archive.name.lowercase()
        val env = Env.nodeEnv(context)
        val code = when {
            name.endsWith(".zip") -> run(
                listOf("unzip", "-q", "-o", archive.absolutePath, "-d", dest.absolutePath),
                workDir = dest, env = env, onLine = { onProgress(Progress.Log(it)) },
            )
            else -> run(
                listOf("tar", "-xzf", archive.absolutePath, "-C", dest.absolutePath),
                workDir = dest, env = env, onLine = { onProgress(Progress.Log(it)) },
            )
        }
        if (code != 0) throw BuildException("解压失败（退出码 $code）：$name")

        // 压缩包常见多一层顶层目录（cc-haha-main/），把它拍平
        flattenSingleRoot(dest)
    }

    // ------------------------------------------------------------ 构建

    private fun runBuild(context: Context, kit: File, onProgress: (Progress) -> Unit) {
        // 1. 适配器依赖：预置在构建套件里，构建时挂到 upstream/adapters/node_modules
        val adaptersDeps = File(kit, "adapters-node_modules")
        val adaptersTarget = File(kit, "upstream/adapters/node_modules")
        if (adaptersDeps.isDirectory && !adaptersTarget.exists()) {
            // 用复制而非符号链接：实测 esbuild 的模块解析在 Android 上对符号链接
            // 目录不可靠（@wecom/aibot-node-sdk 依赖的 eventemitter3 解析不到）
            onProgress(Progress.Log("挂载适配器依赖（复制约 94MB）..."))
            copyTree(adaptersDeps, adaptersTarget)
            onProgress(Progress.Log("适配器依赖已就绪"))
        }

        val node = Env.nodeBinary(context)
        if (!node.canExecute()) throw BuildException("node 不可用")

        val env = HashMap(Env.nodeEnv(context))
        env["CC_HAHA_SPLIT"] = "1"
        env["NODE_OPTIONS"] = "--max-old-space-size=4096"

        val code = run(
            listOf(
                node.absolutePath, "--import", "./build/android-preload.mjs", "build/build.mjs",
            ),
            workDir = kit, env = env, onLine = { line ->
                onProgress(Progress.Log(line))
                Regex("Done in (\\d+)ms").find(line)?.let {
                    onProgress(Progress.Percent(0, "编译中（${it.groupValues[1]}ms/入口）"))
                }
            },
        )
        if (code != 0) throw BuildException("构建失败（退出码 $code）")
    }

    private fun installDist(context: Context, dist: File) {
        val target = KernelManager.kernelDir(context)
        deleteTree(target)
        copyTree(dist, target)
        target.mkdirs()
    }

    /**
     * 修复构建套件里的可执行位。
     *
     * 与工具链同一个坑：buildkit.pkg 在 Windows 上打包，NTFS 无 Unix 权限位，
     * tar 只能按文件名猜测，esbuild 的 arm64 二进制（无扩展名）被打成 644，
     * 运行时报 `spawn .../bin/esbuild EACCES`。
     */
    private fun fixBuildkitExecBits(kit: File) {
        var n = 0
        // node_modules/.bin 与 adapters-node_modules/.bin 下的命令包装
        for (rel in listOf("node_modules/.bin", "adapters-node_modules/.bin")) {
            File(kit, rel).listFiles()?.forEach { f ->
                if (f.isFile && !f.canExecute() && f.setExecutable(true, false)) n++
            }
        }
        // 平台专属二进制：@esbuild/<platform>/bin/*
        File(kit, "node_modules/@esbuild").listFiles()?.forEach { pkg ->
            File(pkg, "bin").listFiles()?.forEach { f ->
                if (f.isFile && !f.canExecute() && f.setExecutable(true, false)) n++
            }
        }
        if (n > 0) Log.i(TAG, "构建套件权限修复：$n 个文件")
    }

    // ------------------------------------------------------------ 进程与 IO

    /** 运行外部命令，逐行回调输出；返回退出码 */
    private fun run(
        cmd: List<String>,
        workDir: File,
        env: Map<String, String>,
        onLine: (String) -> Unit,
    ): Int {
        val pb = ProcessBuilder(cmd)
        pb.directory(workDir)
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        proc.inputStream.bufferedReader().use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                onLine(line)
            }
        }
        return proc.waitFor()
    }

    private fun parseGitProgress(line: String): Int? =
        Regex("(\\d+)%").find(line)?.groupValues?.get(1)?.toIntOrNull()

    private fun httpGet(url: String, accept: String? = null): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        accept?.let { conn.setRequestProperty("Accept", it) }
        conn.inputStream.use { return it.readBytes() }
    }

    private fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 120000
        conn.inputStream.use { ins ->
            val total = conn.contentLengthLong
            var got = 0L
            FileOutputStream(dest).use { outs ->
                val buf = ByteArray(1 shl 16)
                var lastReport = 0L
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    outs.write(buf, 0, n)
                    got += n
                    if (got - lastReport > 512 * 1024) {
                        onProgress(got, total)
                        lastReport = got
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ 文件工具

    /** 压缩包顶层只有一个目录时拍平（cc-haha-main/ → 内容上移） */
    private fun flattenSingleRoot(dest: File) {
        val kids = dest.listFiles() ?: return
        if (kids.size != 1 || !kids[0].isDirectory) return
        val only = kids[0]
        if (File(only, "src").isDirectory) {
            only.listFiles()?.forEach { f ->
                if (!f.renameTo(File(dest, f.name))) {
                    copyTree(f, File(dest, f.name))
                    deleteTree(f)
                }
            }
            only.delete()
        }
    }

    private fun unpackAsset(context: Context, assetName: String, destDir: File) {
        val tmp = File(context.cacheDir, assetName)
        try {
            context.assets.open(assetName).use { ins ->
                FileOutputStream(tmp).use { outs -> ins.copyTo(outs) }
            }
            destDir.mkdirs()
            val code = run(
                listOf("tar", "-xzf", tmp.absolutePath, "-C", destDir.absolutePath),
                workDir = destDir, env = Env.nodeEnv(context), onLine = {},
            )
            if (code != 0) throw BuildException("解压 $assetName 失败（退出码 $code）")
        } finally {
            tmp.delete()
        }
    }

    private fun copyTree(src: File, dst: File) {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { copyTree(it, File(dst, it.name)) }
        } else if (src.isFile) {
            dst.parentFile?.mkdirs()
            src.inputStream().use { ins -> dst.outputStream().use { outs -> ins.copyTo(outs) } }
            // 流复制不保留执行位（默认 600），启动脚本/二进制会因此无法执行
            if (src.canExecute()) dst.setExecutable(true, false)
        }
    }

    private fun deleteTree(f: File) {
        if (f.isDirectory) f.listFiles()?.forEach { deleteTree(it) }
        f.delete()
    }
}
