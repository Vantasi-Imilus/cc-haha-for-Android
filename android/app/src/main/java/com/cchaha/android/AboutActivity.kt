package com.cchaha.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 关于页（三种模式）：
 *   - UPSTREAM：关于 cc-haha（上游项目）——本移植与上游作者有约定，需在应用内保留其印记
 *   - PORT    ：关于安卓端适配（本项目）
 *   - SPONSOR ：支持开发者（赞赏码）
 */
class AboutActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_MODE = "mode"
        const val MODE_UPSTREAM = "upstream"
        const val MODE_PORT = "port"
        const val MODE_SPONSOR = "sponsor"

        fun intent(context: Context, mode: String): Intent =
            Intent(context, AboutActivity::class.java).putExtra(EXTRA_MODE, mode)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        scroll.addView(root)

        when (intent.getStringExtra(EXTRA_MODE) ?: MODE_PORT) {
            MODE_UPSTREAM -> buildUpstream(root)
            MODE_SPONSOR -> buildSponsor(root)
            else -> buildPort(root)
        }
        setContentView(scroll)
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 20f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, 0, 0, 16)
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setLineSpacing(8f, 1f)
        setPadding(0, 8, 0, 8)
    }

    private fun linkButton(text: String, url: String) = Button(this).apply {
        this.text = text
        setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            } catch (_: Exception) {
                Toast.makeText(this@AboutActivity, url, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 关于 cc-haha（上游项目） */
    private fun buildUpstream(root: LinearLayout) {
        root.addView(heading("cc-haha"))
        root.addView(body("Claude Code 本地工作台\n（AI 编程工作台的 H5 界面与运行时）"))
        root.addView(body("作者：NanmiCoder\n许可：MIT"))
        root.addView(linkButton("打开上游仓库", "https://github.com/NanmiCoder/cc-haha"))
        root.addView(
            body(
                "\n本应用（cc-haha Android 全量版）的工作台部分源自该项目，" +
                    "并保留了其完整功能与署名。感谢上游作者的开放工作。",
            ),
        )
    }

    /** 关于安卓端适配（本项目） */
    private fun buildPort(root: LinearLayout) {
        root.addView(heading("cc-haha Android 全量版"))
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        root.addView(body("版本：$ver\n许可：Apache-2.0"))
        root.addView(
            body(
                "安卓端移植层：Kotlin 壳 + Bun→Node 兼容层 + 本地工具链集成。\n" +
                    "无需电脑，在手机上完成编码、编译与运行。",
            ),
        )
        root.addView(linkButton("打开项目仓库", "https://github.com/Vantasi-Imilus/cc-haha-android"))
        root.addView(
            body(
                "\n内核说明：本应用不内置 Claude Code / cc-haha 的内核产物，由使用者在首次启动时" +
                    "自行获取源码并在设备端构建。内核产物生成校验清单并自动备份，" +
                    "每次启动校验、损坏时自动从备份恢复。",
            ),
        )
        root.addView(
            body(
                "\n运行时目录：${filesDir.absolutePath}/runtime\n" +
                    "工作区目录：${Env.workspaceDir(this).absolutePath}",
            ),
        )
    }

    /** 支持开发者（赞赏码） */
    private fun buildSponsor(root: LinearLayout) {
        root.addView(heading("支持这个项目"))
        root.addView(body("如果这个项目帮到了你，欢迎请我喝杯奶茶："))
        val iv = ImageView(this)
        try {
            assets.open("sponsor/wechat-reward.png").use { ins ->
                iv.setImageBitmap(android.graphics.BitmapFactory.decodeStream(ins))
            }
        } catch (e: Exception) {
            root.addView(body("（赞赏码加载失败：${e.message}）"))
        }
        iv.adjustViewBounds = true
        root.addView(
            iv,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = 16
                bottomMargin = 16
            },
        )
        root.addView(
            body(
                "赞助完全自愿，并且：\n" +
                    "· 不构成购买服务 —— 这是赠与，不是交易\n" +
                    "· 不影响项目许可与功能 —— 本项目以 Apache-2.0 发布，所有人平等获得相同权利\n" +
                    "· 不承诺功能优先级或时间表\n\n" +
                    "也欢迎用别的方式支持：提 Issue、分享使用体验、把它介绍给需要的人。",
            ),
        )
    }
}
