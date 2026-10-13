package com.cchaha.android

import android.app.Activity
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Shizuku 集成：向 Shizuku 申请 shell 级权限。
 *
 * 能力边界（诚实说明）：
 *  - 拿到 Shizuku 权限后可调用需要 shell 身份的 API，以及通过
 *    `Shizuku.newProcess()` 以 shell uid 执行命令（例如访问
 *    /data/local/tmp、部分系统目录，或执行 pm/settings 等命令）。
 *  - 它**不能**绕过 SELinux 的 W^X 策略（wine 的 mmap PROT_EXEC 限制
 *    依然存在，那需要 proot）。
 *  - 未安装 Shizuku 或用户拒绝时，App 完全按普通权限运行（本类所有
 *    方法安全降级，不影响主流程）。
 *
 * 使用前提：用户需自行安装 Shizuku App 并通过 adb/root 启动其服务。
 */
object ShizukuHelper {
    private const val TAG = "ShizukuHelper"
    const val REQUEST_CODE = 2001

    @Volatile
    var permissionGranted: Boolean = false
        private set

    /** Shizuku 服务是否可用（已安装且服务在运行） */
    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            Log.w(TAG, "Shizuku 不可用: ${e.message}")
            false
        }
    }

    private fun alreadyGranted(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 应用启动时调用：Shizuku 可用且未授权时弹出授权请求。
     * 返回 true 表示当前已具备权限。
     */
    fun ensurePermission(activity: Activity): Boolean {
        return try {
            if (!isAvailable()) {
                Log.i(TAG, "未检测到 Shizuku（未安装或服务未启动），跳过授权")
                return false
            }
            if (alreadyGranted()) {
                permissionGranted = true
                Log.i(TAG, "Shizuku 权限已具备")
                // 首次确认权限后做一次能力实测（后台线程，不阻塞启动）
                Thread {
                    try {
                        probeCapability()
                    } catch (_: Throwable) {
                    }
                }.start()
                return true
            }
            // 用户要求「强制申请」：每次启动都主动请求（Shizuku 侧
            // 有自己的拒后冷却策略，重复请求是安全的）
            Log.i(TAG, "请求 Shizuku 权限 ...")
            Shizuku.requestPermission(REQUEST_CODE)
            false
        } catch (e: Throwable) {
            Log.w(TAG, "Shizuku 授权流程异常: ${e.message}")
            false
        }
    }

    /** 在 Activity 的 onRequestPermissionsResult 中转发（Shizuku 走权限回调） */
    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray): Boolean {
        if (requestCode != REQUEST_CODE) return false
        permissionGranted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "Shizuku 授权结果: granted=$permissionGranted")
        return true
    }

    /** 注册权限变化与 Binder 可用性监听 */
    fun registerListener() {
        try {
            Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
                if (requestCode == REQUEST_CODE) {
                    permissionGranted = grantResult == PackageManager.PERMISSION_GRANTED
                    Log.i(TAG, "Shizuku 权限变化: $permissionGranted")
                    if (permissionGranted) {
                        Thread { runCatching { probeCapability() } }.start()
                    }
                }
            }
            // Binder 到达监听：Shizuku 服务在 App 启动后才拉起时（常见于
            // 用户手动启动服务），自动完成权限确认与能力探测，无需重启 App
            Shizuku.addBinderReceivedListenerSticky {
                try {
                    Log.i(TAG, "Shizuku Binder 就绪")
                    if (alreadyGranted()) {
                        permissionGranted = true
                        Thread { runCatching { probeCapability() } }.start()
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Binder 就绪回调异常: ${e.message}")
                }
            }
            Shizuku.addBinderDeadListener {
                permissionGranted = false
                Log.w(TAG, "Shizuku Binder 断开，特权能力暂不可用")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "注册 Shizuku 监听失败: ${e.message}")
        }
    }

    /**
     * 以 shell 身份执行命令（需 Shizuku 权限）；返回 "exit=N\n<stdout+stderr>"。
     *
     * Shizuku 13.x 的 `newProcess(String[], String[], String)` 是私有静态方法
     * 但签名稳定（社区长期依赖），这里用反射调用；失败时安全返回 null。
     */
    fun runAsShell(command: Array<String>): String? {
        if (!permissionGranted) {
            Log.w(TAG, "无 Shizuku 权限，拒绝执行 shell 命令")
            return null
        }
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            method.isAccessible = true
            val proc = method.invoke(null, command, null, null) as java.lang.Process
            val out = proc.inputStream.bufferedReader().readText()
            val err = proc.errorStream.bufferedReader().readText()
            val code = proc.waitFor()
            "exit=$code\n$out$err"
        } catch (e: Throwable) {
            Log.w(TAG, "shell 命令执行失败: ${e.message}")
            null
        }
    }

    /** 当前状态摘要（供设置页与诊断日志使用） */
    fun statusSummary(): String = when {
        !isAvailable() -> "unavailable"
        permissionGranted -> "granted"
        else -> "available-unauthorized"
    }

    /**
     * 能力实测：以 shell 身份执行诊断命令，验证特权通道真实可用。
     * 结果缓存供设置页展示。
     */
    @Volatile
    var lastProbeResult: String = "（未测试）"
        private set

    fun probeCapability(): String {
        val out = runAsShell(
            arrayOf(
                "sh", "-c",
                "echo uid=\$(id -u) name=\$(id -un); " +
                    "echo model=\$(getprop ro.product.model); " +
                    "echo localTmp=\$(ls /data/local/tmp 2>&1 | head -2 | tr '\\n' ' ')",
            ),
        )
        lastProbeResult = out ?: "❌ 无权限或执行失败"
        Log.i(TAG, "能力实测: $lastProbeResult")
        return lastProbeResult
    }

    /** 以 shell 身份读取任意路径内容（用于访问受限目录） */
    fun readFileAsShell(path: String): String? {
        return runAsShell(arrayOf("sh", "-c", "cat '$path' 2>&1 | head -c 65536"))
    }

    /** 以 shell 身份列出目录 */
    fun listDirAsShell(path: String): String? {
        return runAsShell(arrayOf("sh", "-c", "ls -la '$path' 2>&1 | head -100"))
    }
}
