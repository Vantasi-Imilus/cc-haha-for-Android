package com.cchaha.android

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 内核（cc-haha / Claude Code bundle）生命周期管理。
 *
 * 背景：APK 不再内置内核产物。内核由用户在首次启动时自行获取源码、在设备端
 * 构建（见 KernelFetcher / KernelBuilder），产物落在 runtime/dist/。
 *
 * 本对象负责三件事，对应"别把自己玩坏了还得重装"的需求：
 *   1. 完整性检测 —— 依据构建时落盘的 manifest（路径 / 大小 / SHA256）
 *   2. 备份       —— 构建成功后把 dist/ 快照到 kernel-backup/
 *   3. 自愈       —— 检测到异常时删掉损坏产物、从备份恢复
 */
object KernelManager {
    private const val TAG = "KernelManager"

    /** manifest 文件名（同时存在于 dist/ 与备份目录） */
    const val MANIFEST_NAME = ".kernel-manifest.json"

    private const val COPY_BUF = 1 shl 16

    enum class Status {
        /** 从未构建过（全新安装或用户清过数据） */
        MISSING,

        /** 就绪 */
        READY,

        /** 曾损坏，已从备份恢复 */
        REPAIRED,

        /** 损坏且无可用备份 —— 需用户重新获取源码并构建 */
        BROKEN,
    }

    data class Entry(val path: String, val size: Long, val sha256: String)

    // ---------------------------------------------------------------- 路径

    fun kernelDir(context: Context): File = Env.distDir(context)

    fun backupDir(context: Context): File = File(context.filesDir, "kernel-backup")

    /** 构建工作目录（源码 checkout + buildkit 展开 + 构建输出） */
    fun buildWorkDir(context: Context): File = File(context.filesDir, "kernel-build")

    fun manifestFile(context: Context): File = File(kernelDir(context), MANIFEST_NAME)

    // ---------------------------------------------------------------- 健康检查

    /**
     * 启动时调用。快速校验（只比存在性与大小，不算哈希），异常时自动尝试自愈。
     *
     * 必须同步：MainActivity 与 ServerService 会在启动时并发调用；若不加锁，
     * 两者的「删除损坏目录 + 从备份复制」会互相踩踏，产出半截文件（实测踩过）。
     */
    @Synchronized
    fun ensureHealthy(context: Context): Status {
        val manifest = manifestFile(context)
        if (!manifest.isFile) {
            // 没有 manifest：从未构建过，或内核是手工放进去的
            return if (Env.serverBundle(context).isFile) Status.READY else Status.MISSING
        }

        val entries = readManifest(manifest)
        if (entries == null) {
            Log.w(TAG, "manifest 无法解析，尝试自愈")
            return if (restoreFromBackup(context)) Status.REPAIRED else Status.BROKEN
        }

        val problems = verifyQuick(context, entries)
        if (problems.isEmpty()) return Status.READY

        Log.w(TAG, "内核校验异常 ${problems.size} 项，首个：${problems.first()}")
        return if (restoreFromBackup(context)) {
            val after = readManifest(manifestFile(context))
            if (after != null && verifyQuick(context, after).isEmpty()) {
                Log.i(TAG, "已从备份恢复内核")
                Status.REPAIRED
            } else {
                Log.e(TAG, "备份恢复后仍不完整")
                Status.BROKEN
            }
        } else {
            Log.e(TAG, "内核损坏且无可用备份")
            Status.BROKEN
        }
    }

    /** 快速校验：文件存在且大小一致 */
    private fun verifyQuick(context: Context, entries: List<Entry>): List<String> {
        val problems = mutableListOf<String>()
        val dir = kernelDir(context)
        for (e in entries) {
            val f = File(dir, e.path)
            when {
                !f.isFile -> problems.add("缺失 ${e.path}")
                f.length() != e.size -> problems.add("大小不符 ${e.path}")
            }
        }
        if (!Env.serverBundle(context).isFile) problems.add("缺少 server-split/server.js")
        return problems
    }

    /** 深度校验：逐文件 SHA256（构建完成后调用；启动时不用，太慢） */
    fun verifyDeep(context: Context): List<String> {
        val entries = readManifest(manifestFile(context)) ?: return listOf("manifest 缺失或损坏")
        val problems = mutableListOf<String>()
        val dir = kernelDir(context)
        for (e in entries) {
            val f = File(dir, e.path)
            when {
                !f.isFile -> problems.add("缺失 ${e.path}")
                f.length() != e.size -> problems.add("大小不符 ${e.path}")
                sha256(f) != e.sha256 -> problems.add("哈希不符 ${e.path}")
            }
        }
        return problems
    }

    // ---------------------------------------------------------------- manifest

    /**
     * 扫描内核目录生成 manifest（构建成功后调用）。
     * @return 记录的文件数，失败返回 -1
     */
    fun writeManifest(context: Context, source: String, upstreamCommit: String?): Int {
        val dir = kernelDir(context)
        if (!dir.isDirectory) return -1
        return try {
            val files = mutableListOf<File>()
            dir.walkTopDown().forEach { f ->
                if (f.isFile && f.name != MANIFEST_NAME) files.add(f)
            }
            val arr = JSONArray()
            for (f in files) {
                arr.put(
                    JSONObject()
                        .put("path", f.relativeTo(dir).path.replace('\\', '/'))
                        .put("size", f.length())
                        .put("sha256", sha256(f)),
                )
            }
            val obj = JSONObject()
                .put("version", 1)
                .put("createdAt", System.currentTimeMillis())
                .put("source", source)
                .put("upstreamCommit", upstreamCommit ?: "")
                .put("files", arr)
            manifestFile(context).writeText(obj.toString())
            Log.i(TAG, "manifest 已写入：${files.size} 个文件")
            files.size
        } catch (e: Exception) {
            Log.e(TAG, "写 manifest 失败", e)
            -1
        }
    }

    fun readManifestSummary(context: Context): String? = try {
        val f = manifestFile(context)
        if (!f.isFile) null
        else {
            val o = JSONObject(f.readText())
            val src = o.optString("source", "?")
            val commit = o.optString("upstreamCommit", "")
            val n = o.optJSONArray("files")?.length() ?: 0
            val at = o.optLong("createdAt", 0L)
            val time = if (at > 0) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                .format(java.util.Date(at)) else "?"
            "来源 $src${if (commit.isNotEmpty()) " @${commit.take(8)}" else ""} · $n 文件 · $time"
        }
    } catch (_: Exception) {
        null
    }

    private fun readManifest(f: File): List<Entry>? = try {
        if (!f.isFile) null
        else {
            val arr = JSONObject(f.readText()).getJSONArray("files")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(o.getString("path"), o.getLong("size"), o.getString("sha256"))
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "manifest 解析失败", e)
        null
    }

    // ---------------------------------------------------------------- 备份 / 恢复

    /** 把当前内核快照到备份目录（构建成功后调用，覆盖旧备份） */
    fun backup(context: Context): Boolean {
        val src = kernelDir(context)
        if (!Env.serverBundle(context).isFile) {
            Log.w(TAG, "内核不完整，跳过备份")
            return false
        }
        val dst = backupDir(context)
        return try {
            deleteRecursively(dst)
            copyTree(src, dst)
            Log.i(TAG, "内核已备份 → ${dst.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "备份失败", e)
            false
        }
    }

    /** 用备份覆盖当前内核（损坏自愈） */
    fun restoreFromBackup(context: Context): Boolean {
        val bak = backupDir(context)
        if (!File(bak, MANIFEST_NAME).isFile || !File(bak, "server-split/server.js").isFile) {
            Log.w(TAG, "备份不存在或不完整")
            return false
        }
        return try {
            val dst = kernelDir(context)
            deleteRecursively(dst)
            copyTree(bak, dst)
            true
        } catch (e: Exception) {
            Log.e(TAG, "从备份恢复失败", e)
            false
        }
    }

    fun hasBackup(context: Context): Boolean =
        File(backupDir(context), "server-split/server.js").isFile

    // ---------------------------------------------------------------- 工具

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(COPY_BUF)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun copyTree(src: File, dst: File) {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { copyTree(it, File(dst, it.name)) }
        } else if (src.isFile) {
            dst.parentFile?.mkdirs()
            src.inputStream().use { ins ->
                dst.outputStream().use { outs -> ins.copyTo(outs, COPY_BUF) }
            }
            // 流复制不保留执行位，内核里若有可执行文件需显式恢复
            if (src.canExecute()) dst.setExecutable(true, false)
        }
    }

    private fun deleteRecursively(f: File) {
        if (f.isDirectory) f.listFiles()?.forEach { deleteRecursively(it) }
        f.delete()
    }
}
