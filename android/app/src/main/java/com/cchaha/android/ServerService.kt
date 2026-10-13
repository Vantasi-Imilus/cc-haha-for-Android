package com.cchaha.android

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台服务：托管 node 版 cc-haha server 进程。
 *
 * 职责：
 *  1. startForeground 常驻通知（Android 12 + 华为 EMUI 下保活的根本手段）
 *  2. 以正确 env（LD_LIBRARY_PATH/OPENSSL_CONF/PATH/...）启动 node
 *  3. 看门狗：进程意外退出自动重启（退避）
 *  4. 服务销毁时结束进程
 */
class ServerService : Service() {
    companion object {
        private const val TAG = "ServerService"
        private const val ACTION_STOP = "com.cchaha.android.action.STOP"

        fun start(context: android.content.Context) {
            context.startForegroundService(Intent(context, ServerService::class.java))
        }

        fun stop(context: android.content.Context) {
            context.startService(
                Intent(context, ServerService::class.java).setAction(ACTION_STOP),
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var watchdog: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(Env.NOTIFICATION_ID, buildNotification())
        acquireWakeLock()
        stopping = false
        ensureProcess()
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        watchdog?.cancel()
        killProcess()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 华为 EMUI 划掉任务卡片时保持服务运行
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cchaha:server")
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire(6 * 60 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // 设置页入口：应用主题为 NoActionBar 且 H5 铺满全屏，无处安放按钮；
        // 常驻通知是 Android 上最自然的入口（端口/保活/工具链/关于都在这里）
        val settingsIntent = PendingIntent.getActivity(
            this, 2, Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, Env.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.notification_running))
            .setContentText("127.0.0.1:${prefs().getInt("port", Env.DEFAULT_PORT)}")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "设置", settingsIntent)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .build()
    }

    private fun prefs(): android.content.SharedPreferences =
        getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)

    private fun ensureProcess() {
        if (process != null && process!!.isAlive) return
        if (stopping) return
        if (!RuntimeInstaller.isInstalled(this)) {
            // 首次启动：在 IO 线程解压运行时与预置工具链（96MB+206MB，需 1-3 分钟）
            Log.i(TAG, "运行时未就绪，开始安装（首次约 1-3 分钟）")
            scope.launch {
                try {
                    RuntimeInstaller.installIfNeeded(this@ServerService)
                    if (isActive && !stopping) ensureProcess()
                } catch (e: Exception) {
                    Log.e(TAG, "运行时安装失败，60 秒后重试", e)
                    delay(60_000)
                    if (isActive && !stopping) ensureProcess()
                }
            }
            return
        }
        // 内核检查（含备份自愈）：缺失或损坏时不拉起 node——否则会以
        // "启动即失败" 的形式被看门狗反复重启，日志难排查。由 UI 引导用户
        // 在引导页完成获取与构建。可能触发从备份恢复（文件复制），故放在 IO 线程；
        // KernelManager 内部有同步锁，避免与 MainActivity 的并发校验互相踩踏。
        scope.launch {
            val kernel = KernelManager.ensureHealthy(this@ServerService)
            if (kernel == KernelManager.Status.MISSING || kernel == KernelManager.Status.BROKEN) {
                Log.w(TAG, "内核未就绪（$kernel），等待用户在引导页完成获取与构建")
                return@launch
            }
            if (stopping) return@launch
            startProcess()
            startWatchdog()
        }
    }

    private fun startProcess() {
        try {
            val port = prefs().getInt("port", Env.DEFAULT_PORT)
            val env = Env.nodeEnv(this, port).toMutableMap()
            // 特权命令通道：Agent 可通过它执行 shell 身份命令
            // （能力由 Shizuku/群星之眼授权提供）
            try {
                val priv = PrivilegedExecServer.start()
                if (priv != null) {
                    env["CC_HAHA_PRIV_PORT"] = priv.first.toString()
                    env["CC_HAHA_PRIV_TOKEN"] = priv.second
                    Log.i(TAG, "特权通道: 127.0.0.1:${priv.first}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "特权通道启动失败（不影响主流程）", e)
            }
            val logFile = java.io.File(filesDir, "logs/server.log")
            logFile.parentFile?.mkdirs()
            val pb = ProcessBuilder(
                Env.nodeBinary(this).absolutePath,
                Env.serverBundle(this).absolutePath,
                "--port", port.toString(),
                "--host", "127.0.0.1",
            )
            pb.directory(Env.workspaceDir(this))
            pb.environment().clear()
            pb.environment().putAll(env)
            pb.redirectErrorStream(true)
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            process = pb.start()
            Log.i(TAG, "node 进程已启动 port=$port")
            // server 就绪后预热 CLI（编译缓存 + 模块加载），
            // 让用户第一条消息的 CLI spawn 走热缓存
            scope.launch {
                delay(25_000)
                if (!stopping) prewarmCli()
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动失败", e)
            process = null
        }
    }

    /**
     * 预热 CLI：spawn 一个空跑进程，完成模块加载与 compile cache 写入，
     * 90 秒后自动回收。首个会话的 CLI 启动因此从 20-40 秒降到秒级。
     */
    private fun prewarmCli() {
        try {
            val env = Env.nodeEnv(this)
            val pb = ProcessBuilder(
                Env.nodeBinary(this).absolutePath,
                Env.cliBundle(this).absolutePath,
                "--print",
                "--input-format", "stream-json",
                "--output-format", "stream-json",
            )
            pb.directory(Env.workspaceDir(this))
            pb.environment().clear()
            pb.environment().putAll(env)
            pb.redirectErrorStream(true)
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(
                java.io.File(filesDir, "logs/prewarm.log"),
            ))
            val p = pb.start()
            Log.i(TAG, "CLI 预热进程已启动")
            scope.launch {
                delay(90_000)
                if (p.isAlive) {
                    p.destroy()
                    Log.i(TAG, "CLI 预热进程已回收")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "CLI 预热失败（不影响主流程）", e)
        }
    }

    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            var backoff = 2_000L
            while (isActive && !stopping) {
                delay(3_000)
                val p = process
                if (p == null) {
                    backoff = (backoff * 2).coerceAtMost(60_000)
                    if (!stopping) startProcess()
                } else if (!p.isAlive) {
                    Log.w(TAG, "node 进程退出 code=${p.exitValue()}，${backoff}ms 后重启")
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(60_000)
                    if (!stopping) startProcess()
                } else {
                    backoff = 2_000L
                }
            }
        }
    }

    private fun killProcess() {
        try {
            process?.destroy()
            Thread.sleep(300)
            if (process?.isAlive == true) process?.destroyForcibly()
        } catch (_: Exception) {
        }
        process = null
    }
}
