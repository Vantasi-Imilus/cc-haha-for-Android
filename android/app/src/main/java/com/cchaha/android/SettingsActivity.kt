package com.cchaha.android

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 设置页：服务端口、重启服务、保活引导、工具链管理（阶段 3 扩展）。
 */
class SettingsActivity : AppCompatActivity() {
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        scroll.addView(root)

        fun label(text: String): TextView = TextView(this).apply {
            this.text = text
            textSize = 14f
            setPadding(0, 24, 0, 8)
        }

        root.addView(label("服务端口（重启后生效）"))
        val portInput = EditText(this).apply {
            setText((prefs.getInt("port", Env.DEFAULT_PORT)).toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        root.addView(portInput)

        root.addView(Button(this).apply {
            text = "保存并重启服务"
            setOnClickListener {
                val port = portInput.text.toString().toIntOrNull() ?: Env.DEFAULT_PORT
                if (port !in 1024..65535) {
                    Toast.makeText(this@SettingsActivity, "端口需在 1024-65535", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.edit().putInt("port", port).apply()
                // 重启服务
                ServerService.stop(this@SettingsActivity)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    ServerService.start(this@SettingsActivity)
                }, 800)
                Toast.makeText(this@SettingsActivity, "已重启（端口 $port）", Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(label("保活设置（华为 EMUI 必看）"))
        root.addView(CheckBox(this).apply {
            text = "忽略电池优化（防止锁屏后被杀）"
            isChecked = PermissionHelper.isIgnoringBatteryOptimizations(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                if (checked) PermissionHelper.ensureBatteryOptimization(this@SettingsActivity)
            }
        })
        root.addView(Button(this).apply {
            text = "打开应用系统设置（华为用户请关闭「应用启动管理-自动管理」）"
            setOnClickListener { PermissionHelper.openAppSettings(this@SettingsActivity) }
        })

        root.addView(label("运行时"))
        val runtimeStatus = TextView(this).apply {
            text = if (RuntimeInstaller.isInstalled(this@SettingsActivity)) {
                "状态：已安装 v${Env.RUNTIME_VERSION}"
            } else {
                "状态：未安装（首次启动服务时自动解压）"
            }
        }
        root.addView(runtimeStatus)
        root.addView(Button(this).apply {
            text = "重新安装运行时"
            setOnClickListener {
                scope.launch(Dispatchers.IO) {
                    Toast.makeText(this@SettingsActivity, "解压中...", Toast.LENGTH_SHORT).show()
                    java.io.File(filesDir, "version.txt").delete()
                    RuntimeInstaller.installIfNeeded(this@SettingsActivity)
                    scope.launch(Dispatchers.Main) {
                        runtimeStatus.text = "状态：已安装 v${Env.RUNTIME_VERSION}"
                        ServerService.stop(this@SettingsActivity)
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            ServerService.start(this@SettingsActivity)
                        }, 800)
                    }
                }
            }
        })

        root.addView(label("工具链管理（预置 Python/Clang/Git 等已随 App 安装；以下按需下载）"))
        for ((pkgName, desc) in ToolchainManager.CATALOG) {
            val preinstalled = ToolchainManager.isInstalled(this, pkgName)
            root.addView(Button(this).apply {
                text = if (preinstalled) "✅ $pkgName（已安装）" else "⬇ $pkgName — $desc"
                isEnabled = !preinstalled
                setOnClickListener {
                    scope.launch {
                        isEnabled = false
                        text = "⏳ $pkgName 安装中..."
                        val ok = ToolchainManager.install(
                            this@SettingsActivity, pkgName,
                        ) { msg -> runOnUiThread { text = "⏳ $pkgName：$msg" } }
                        text = if (ok) "✅ $pkgName（已安装）"
                        else "❌ $pkgName 安装失败（详见 logcat）"
                    }
                }
            })
        }

        root.addView(label("Shizuku（shell 级权限）"))
        val shizukuStatus = TextView(this).apply {
            text = shizukuStatusText()
        }
        root.addView(shizukuStatus)
        root.addView(Button(this).apply {
            text = "重新请求 Shizuku 权限"
            setOnClickListener {
                val ok = ShizukuHelper.ensurePermission(this@SettingsActivity)
                shizukuStatus.text = shizukuStatusText()
                Toast.makeText(
                    this@SettingsActivity,
                    if (ok) "已具备权限" else "见通知栏/Shizuku 应用内确认",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        })
        val probeLabel = TextView(this).apply {
            text = "能力实测：${ShizukuHelper.lastProbeResult}"
            textSize = 12f
        }
        root.addView(probeLabel)
        root.addView(Button(this).apply {
            text = "测试特权能力（shell 身份执行命令）"
            setOnClickListener {
                probeLabel.text = "测试中..."
                scope.launch(Dispatchers.IO) {
                    val r = ShizukuHelper.probeCapability()
                    scope.launch(Dispatchers.Main) {
                        probeLabel.text = "能力实测：$r"
                    }
                }
            }
        })

        root.addView(label("关于与支持"))
        root.addView(Button(this).apply {
            text = "关于 cc-haha（上游项目）"
            setOnClickListener {
                startActivity(AboutActivity.intent(this@SettingsActivity, AboutActivity.MODE_UPSTREAM))
            }
        })
        root.addView(Button(this).apply {
            text = "关于安卓端适配（本项目）"
            setOnClickListener {
                startActivity(AboutActivity.intent(this@SettingsActivity, AboutActivity.MODE_PORT))
            }
        })
        root.addView(Button(this).apply {
            text = "❤ 支持开发者"
            setOnClickListener {
                startActivity(AboutActivity.intent(this@SettingsActivity, AboutActivity.MODE_SPONSOR))
            }
        })

        setContentView(scroll)
    }

    private fun shizukuStatusText(): String = when {
        !ShizukuHelper.isAvailable() ->
            "状态：未检测到 Shizuku\n（需自行安装 Shizuku 应用并启动其服务）"
        ShizukuHelper.permissionGranted -> "状态：已授权 ✅（shell 级能力可用）"
        else -> "状态：Shizuku 可用，等待授权（点下方按钮）"
    }
}
