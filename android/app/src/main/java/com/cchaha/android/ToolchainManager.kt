package com.cchaha.android

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 工具链管理器（阶段 3 实现）。
 *
 * 数据源：Termux 官方仓库（https://packages.termux.dev/apt/termux-main）
 * 机制：下载 aarch64 .deb → 解析（ar 归档）→ 提取 data.tar.xz → 解包到
 * filesDir/toolchain/usr/（Termux prefix 布局，与 Env.nodeEnv 的 PATH/
 * LD_LIBRARY_PATH 对接）。
 */
object ToolchainManager {
    private const val TAG = "ToolchainManager"

    const val REPO_BASE = "https://packages.termux.dev/apt/termux-main"

    /** Termux 打包时硬编码的 prefix（shebang 与脚本内路径均含此值） */
    const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"

    /**
     * 修复 Termux 硬编码路径：把文本文件中的 TERMUX_PREFIX 替换为实际
     * prefix，shebang 中的 sh/env 指向 /system/bin/sh。必须只处理文本文件
     * （含 NUL 字节的二进制跳过），否则会破坏 ELF 可执行文件。
     */
    fun fixTermuxPrefix(dir: File, prefix: String) {
        val shebangRe = Regex("""#!\Q$TERMUX_PREFIX\E/bin/(\S+)""")
        dir.walkTopDown().forEach { f ->
            if (!f.isFile || f.length() > 2_000_000L) return@forEach
            val bytes = try {
                f.readBytes()
            } catch (_: Exception) {
                return@forEach
            }
            if (bytes.any { it == 0.toByte() }) return@forEach
            val text = String(bytes, Charsets.UTF_8)
            if (!text.contains(TERMUX_PREFIX)) return@forEach
            var out = shebangRe.replace(text) { m ->
                val cmd = m.groupValues[1]
                when (cmd) {
                    "sh", "env" -> "#!/system/bin/sh"
                    else -> "#!$prefix/bin/$cmd"
                }
            }
            out = out.replace(TERMUX_PREFIX, prefix)
            try {
                f.writeText(out)
                f.setExecutable(true, false)
            } catch (_: Exception) {
                /* 只读文件跳过 */
            }
        }
    }
    private const val PACKAGES_GZ = "https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages.gz"

    /** 工具链清单：name → 一句话说明（阶段 3 按需下载的全部条目） */
    val CATALOG: List<Pair<String, String>> = listOf(
        "python" to "Python 3 解释器与标准库",
        "clang" to "C/C++ 编译器（LLVM/Clang）",
        "lld" to "LLVM 链接器",
        "binutils" to "汇编器与二进制工具",
        "git" to "版本控制",
        "make" to "构建工具",
        "cmake" to "跨平台构建系统",
        "ninja" to "Ninja 构建",
        "pkg-config" to "编译期依赖查询",
        "golang" to "Go 工具链",
        "rust" to "Rust 工具链（rustc/cargo）",
        "openjdk-17" to "Java 17（Android 构建基础）",
        "kotlin" to "Kotlin 编译器",
        "gradle" to "Gradle 构建（编译 Android 应用）",
        "aapt2" to "Android 资源编译",
        "aapt" to "Android 资源工具",
        "d8" to "Dex 转换器",
        "apksigner" to "APK 签名",
        "llvm-mingw-w64" to "Windows 交叉编译（产出 .exe/.dll）",
        "php" to "PHP",
        "ruby" to "Ruby",
        "perl" to "Perl",
        "mono" to ".NET 兼容运行时",
        "dart" to "Dart SDK",
        "zig" to "Zig 工具链",
        "nodejs" to "Node.js 开发环境（运行时自带）",
        "ripgrep" to "快速文本搜索（cc-haha 依赖）",
        "bash" to "Bash shell",
        "coreutils" to "GNU 核心工具",
        "openssh" to "SSH 客户端",
    )

    /** 已安装检测：prefix 下存在同名可执行文件或已知标记文件 */
    fun isInstalled(context: Context, name: String): Boolean {
        return markerFor(context, name).any { File(it).exists() }
    }

    private fun markerFor(context: Context, name: String): List<String> = when (name) {
        "python" -> listOf(File(Env.toolchainBin(context), "python").absolutePath)
        "clang" -> listOf(File(Env.toolchainBin(context), "clang").absolutePath)
        "golang" -> listOf(File(Env.toolchainBin(context), "go").absolutePath)
        "rust" -> listOf(File(Env.toolchainBin(context), "cargo").absolutePath)
        "openjdk-17" -> listOf(
            File(Env.toolchainDir(context), "lib/jvm/java-17-openjdk/bin/java").absolutePath,
        )
        "llvm-mingw-w64" -> listOf(
            File(Env.toolchainBin(context), "x86_64-w64-mingw32-clang").absolutePath,
        )
        else -> listOf(File(Env.toolchainBin(context), name).absolutePath)
    }

    /** 下载并安装单个包（含依赖解析，阶段 3 补全实现） */
    suspend fun install(context: Context, name: String, onProgress: (String) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            onProgress("解析包索引...")
            val index = fetchPackageIndex()
            val toInstall = resolveWithDeps(index, name)
            onProgress("共 ${toInstall.size} 个包")
            for ((pkg, filename) in toInstall) {
                onProgress("下载 $pkg ...")
                val debFile = File(context.cacheDir, "toolchain-$pkg.deb")
                downloadFile("$REPO_BASE/$filename", debFile)
                onProgress("解包 $pkg ...")
                extractDeb(debFile, Env.toolchainDir(context))
                debFile.delete()
            }
            onProgress("修复硬编码路径 ...")
            fixTermuxPrefix(
                Env.toolchainDir(context),
                Env.toolchainDir(context).absolutePath,
            )
            onProgress("完成")
            true
        }

    // ---- 包索引与依赖闭包（Termux Packages 格式） ----

    private class PkgEntry(
        val name: String,
        var filename: String = "",
        val depends: MutableList<List<String>> = mutableListOf(),
    )

    private fun fetchPackageIndex(): Map<String, PkgEntry> {
        val conn = URL(PACKAGES_GZ).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        val gz = conn.inputStream.readBytes()
        val raw = java.util.zip.GZIPInputStream(gz.inputStream()).readBytes()
        val text = String(raw, Charsets.UTF_8)
        val map = mutableMapOf<String, PkgEntry>()
        var cur: PkgEntry? = null
        for (line in text.lines()) {
            when {
                line.startsWith("Package: ") -> {
                    cur = PkgEntry(line.removePrefix("Package: ").trim())
                    map[cur.name] = cur
                }
                line.startsWith("Filename: ") && cur != null ->
                    cur.filename = line.removePrefix("Filename: ").trim()
                line.startsWith("Depends: ") && cur != null ->
                    cur.depends += parseDepends(line.removePrefix("Depends: ").trim())
            }
        }
        return map
    }

    /** "a (>= 1.0), b | c" → [[a], [b, c]]（OR 组） */
    private fun parseDepends(s: String): List<List<String>> {
        if (s.isBlank()) return emptyList()
        return s.split(",").map { part ->
            part.trim()
                .split("|")
                .map { alt ->
                    alt.trim().replace(Regex("""\s*\([^)]*\)\s*"""), "").trim()
                }
                .filter { it.isNotEmpty() }
        }.filter { it.isNotEmpty() }
    }

    /** 依赖闭包（OR 组取第一个存在的候选） */
    private fun resolveWithDeps(
        index: Map<String, PkgEntry>,
        name: String,
    ): List<Pair<String, String>> {
        val resolved = LinkedHashMap<String, PkgEntry>()
        val queue = ArrayDeque<String>()
        queue.add(name)
        while (queue.isNotEmpty()) {
            val pkgName = queue.removeFirst()
            if (resolved.containsKey(pkgName)) continue
            val entry = index[pkgName]
            if (entry == null) {
                Log.w(TAG, "包不存在（跳过）: $pkgName")
                continue
            }
            resolved[pkgName] = entry
            for (group in entry.depends) {
                val pick = group.firstOrNull { index.containsKey(it) } ?: group.firstOrNull()
                if (pick != null && !resolved.containsKey(pick)) queue.add(pick)
            }
        }
        return resolved.values.map { it.name to it.filename }
    }

    private fun downloadFile(url: String, dest: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 300_000
        conn.inputStream.use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        }
    }

    private fun extractDeb(debFile: File, destPrefix: File) {
        // ar 归档：跳过全局头 "!<arch>\n"，逐成员读；取 data.tar.* 解包
        val bytes = debFile.readBytes()
        var pos = 8
        while (pos + 60 <= bytes.size) {
            val name = String(bytes, pos, 16, Charsets.US_ASCII).trim()
            val sizeStr = String(bytes, pos + 48, 10, Charsets.US_ASCII).trim()
            val size = sizeStr.toIntOrNull() ?: break
            val dataStart = pos + 60
            if (name.startsWith("data.tar")) {
                extractDataTar(bytes.copyOfRange(dataStart, dataStart + size), destPrefix)
                return
            }
            pos = dataStart + size + (size % 2)
        }
        throw IllegalStateException("deb 解析失败: ${debFile.name}")
    }

    private fun extractDataTar(tarBytes: ByteArray, destUsr: File) {
        // data.tar.xz 内部布局为 ./data/data/com.termux/files/usr/...
        // 剥掉 5 层前缀后解到 toolchain/usr（toybox tar 支持 -J 与 --strip-components）
        val tmpTar = File(destUsr, ".tmp-data.tar.xz")
        tmpTar.writeBytes(tarBytes)
        try {
            destUsr.mkdirs()
            val proc = ProcessBuilder(
                "tar", "-xJf", tmpTar.absolutePath,
                "--strip-components=5",
                "-C", destUsr.absolutePath,
            ).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            if (code != 0) {
                throw IllegalStateException("tar 解包失败: ${out.take(300)}")
            }
            // 恢复可执行权限（tar 保留位但保险起见）
            destUsr.walkTopDown().forEach { f ->
                if (f.isFile) f.setExecutable(true, false)
            }
        } finally {
            tmpTar.delete()
        }
    }
}
