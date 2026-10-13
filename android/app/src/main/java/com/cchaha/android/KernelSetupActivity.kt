package com.cchaha.android

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 内核引导页：内核缺失或损坏时强制进入。
 *
 * 说明：APK 不再内置内核产物（cc-haha / Claude Code 的 esbuild bundle），
 * 首次使用需在此选择来源、在设备上完成获取与构建。构建产物落 runtime/dist，
 * 随后自动生成 manifest 并备份，供每次启动的完整性校验与损坏自愈使用。
 */
class KernelSetupActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var startButton: Button
    private lateinit var sourceGroup: RadioGroup
    private lateinit var localButton: Button
    private lateinit var localLabel: TextView

    private var selectedSource = KernelInstaller.Source.GITCODE
    private var localArchive: File? = null
    private var running = false

    private val pickArchive = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        scope.launch {
            val f = withContext(Dispatchers.IO) { copyToCache(uri) }
            if (f != null) {
                localArchive = f
                localLabel.text = "已选择：${f.name}（${f.length() / 1024 / 1024} MB）"
                startButton.isEnabled = true
            } else {
                localLabel.text = "读取文件失败，请重试"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()
        refreshStatus()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI

    private fun buildLayout() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(pad, pad, pad, pad)
        }

        root.addView(title("准备内核"))
        root.addView(hint(
            "本应用不再内置 Claude Code / cc-haha 的内核产物。\n" +
                "首次使用请选择来源，在设备上获取源码并完成构建（约 1-3 分钟）。\n" +
                "构建成功后会自动备份；此后每次启动都会校验，损坏时自动用备份恢复。",
        ))

        statusView = TextView(this).apply {
            setTextColor(Color.parseColor("#8ab4f8"))
            textSize = 13f
            setPadding(0, pad / 2, 0, pad)
        }
        root.addView(statusView)

        // ---- 来源选择
        root.addView(sectionLabel("选择来源"))
        sourceGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        KernelInstaller.Source.entries.forEach { src ->
            val rb = RadioButton(this).apply {
                id = src.ordinal + 1000
                text = "${src.label}\n${src.note}"
                setTextColor(Color.parseColor("#dddddd"))
                textSize = 14f
                isChecked = src == KernelInstaller.Source.GITCODE
            }
            sourceGroup.addView(rb)
        }
        sourceGroup.setOnCheckedChangeListener { _, id ->
            selectedSource = KernelInstaller.Source.entries[id - 1000]
            localButton.visibility =
                if (selectedSource == KernelInstaller.Source.LOCAL) LinearLayout.VISIBLE
                else LinearLayout.GONE
            localLabel.visibility = localButton.visibility
            startButton.isEnabled = !running &&
                (selectedSource != KernelInstaller.Source.LOCAL || localArchive != null)
        }
        root.addView(sourceGroup)

        localButton = Button(this).apply {
            text = "选择本地压缩包（.tar.gz / .zip）"
            visibility = LinearLayout.GONE
            setOnClickListener {
                pickArchive.launch(arrayOf("application/gzip", "application/x-gzip",
                    "application/zip", "application/octet-stream", "*/*"))
            }
        }
        root.addView(localButton)

        localLabel = TextView(this).apply {
            setTextColor(Color.parseColor("#aaaaaa"))
            textSize = 12f
            visibility = LinearLayout.GONE
        }
        root.addView(localLabel)

        // ---- 开始按钮
        startButton = Button(this).apply {
            text = "开始获取并构建"
            setOnClickListener { startInstall() }
        }
        root.addView(startButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = pad / 2 })

        val skip = Button(this).apply {
            text = "已有可用内核？返回"
            setOnClickListener { finish() }
        }
        root.addView(skip)

        // ---- 日志
        root.addView(sectionLabel("进度"))
        logView = TextView(this).apply {
            setTextColor(Color.parseColor("#9e9e9e"))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            movementMethod = ScrollingMovementMethod()
            setBackgroundColor(Color.parseColor("#1e1e1e"))
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
        }
        val logScroll = ScrollView(this).apply { addView(logView) }
        root.addView(logScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        setContentView(root)
    }

    private fun title(t: String) = TextView(this).apply {
        text = t
        setTextColor(Color.WHITE)
        textSize = 22f
        setPadding(0, 0, 0, 8)
    }

    private fun hint(t: String) = TextView(this).apply {
        text = t
        setTextColor(Color.parseColor("#bbbbbb"))
        textSize = 13f
        setLineSpacing(4f, 1f)
    }

    private fun sectionLabel(t: String) = TextView(this).apply {
        text = t
        setTextColor(Color.parseColor("#8ab4f8"))
        textSize = 13f
        setPadding(0, 12, 0, 4)
    }

    private fun appendLog(line: String) {
        logView.append(line + "\n")
    }

    private fun refreshStatus() {
        scope.launch {
            val status = withContext(Dispatchers.IO) { KernelManager.ensureHealthy(this@KernelSetupActivity) }
            val summary = withContext(Dispatchers.IO) {
                KernelManager.readManifestSummary(this@KernelSetupActivity)
            }
            val backup = withContext(Dispatchers.IO) { KernelManager.hasBackup(this@KernelSetupActivity) }
            statusView.text = buildString {
                append("当前状态：")
                append(
                    when (status) {
                        KernelManager.Status.MISSING -> "内核未安装"
                        KernelManager.Status.READY -> "就绪"
                        KernelManager.Status.REPAIRED -> "已从备份自动修复"
                        KernelManager.Status.BROKEN -> "内核损坏且无可用备份"
                    },
                )
                if (summary != null) append("\n内核信息：$summary")
                append("\n本地备份：${if (backup) "有（可自动恢复）" else "无"}")
            }
        }
    }

    // ------------------------------------------------------------------ 构建

    private fun startInstall() {
        if (running) return
        running = true
        startButton.isEnabled = false
        sourceGroup.isEnabled = false
        logView.text = ""
        statusView.text = "进行中 ..."

        val source = selectedSource
        val archive = localArchive

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    KernelInstaller.install(this@KernelSetupActivity, source, archive) { p ->
                        runOnUiThread { renderProgress(p) }
                    }
                }
                withContext(Dispatchers.IO) { RuntimeInstaller.installIfNeeded(this@KernelSetupActivity) }
                appendLog("\n✅ 完成，正在启动服务 ...")
                ServerService.start(this@KernelSetupActivity)
                refreshStatus()
                startActivity(Intent(this@KernelSetupActivity, MainActivity::class.java))
                finish()
            } catch (e: Exception) {
                Log.e(TAG, "安装失败", e)
                appendLog("\n❌ 失败：${e.message}")
                statusView.text = "失败：${e.message}\n可换一个来源重试，或检查网络后重试。"
            } finally {
                running = false
                startButton.isEnabled = true
                sourceGroup.isEnabled = true
            }
        }
    }

    private fun renderProgress(p: KernelInstaller.Progress) {
        when (p) {
            is KernelInstaller.Progress.Stage -> {
                appendLog("\n── ${p.name} ──")
                statusView.text = "进行中：${p.name}"
            }
            is KernelInstaller.Progress.Percent ->
                statusView.text = "进行中：${p.detail} ${p.value}%"
            is KernelInstaller.Progress.Log -> appendLog(p.line)
            is KernelInstaller.Progress.Done -> statusView.text = "完成：${p.summary}"
        }
    }

    /** 把用户选中的文件复制到 cache（tar/unzip 需要真实文件路径） */
    private fun copyToCache(uri: Uri): File? = try {
        val name = queryName(uri) ?: "import-${System.currentTimeMillis()}.tgz"
        val f = File(cacheDir, name)
        contentResolver.openInputStream(uri)?.use { ins ->
            f.outputStream().use { outs -> ins.copyTo(outs) }
        }
        f
    } catch (e: Exception) {
        Log.e(TAG, "复制导入文件失败", e)
        null
    }

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val TAG = "KernelSetup"
    }
}
