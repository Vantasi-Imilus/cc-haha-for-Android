package com.cchaha.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面：WebView 容器，加载本机 cc-haha server 提供的 H5 工作台。
 */
class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var preparingOverlay: android.widget.LinearLayout
    private lateinit var preparingText: android.widget.TextView
    private var probeJob: Job? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = result.data?.clipData?.let { clip ->
                (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            }?.toTypedArray()
                ?: result.data?.data?.let { arrayOf(it) }
            filePathCallback?.onReceiveValue(uris)
            filePathCallback = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 请求通知权限（Android 13+ 才需要，targetSdk 28 上华为仍可能提示）
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100,
            )
        }
        // 外部存储权限：工作区默认在 /storage/emulated/0/cc-haha
        // （targetSdk 28 + legacy 存储，运行时权限有效）
        requestStoragePermission()

        buildLayout()
        configureWebView()
        PermissionHelper.ensureBatteryOptimization(this)
        // Shizuku：请求 shell 级权限（用户已安装 Shizuku 时生效），
        // 用于放开受限目录访问与以 shell 身份执行命令
        ShizukuHelper.registerListener()
        ShizukuHelper.ensurePermission(this)
        ServerService.start(this)
    }

    override fun onResume() {
        super.onResume()
        probeAndLoad()
    }

    override fun onPause() {
        super.onPause()
        probeJob?.cancel()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildLayout() {
        webView = WebView(this)
        progressBar = ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal,
        )
        progressBar.max = 100
        progressBar.visibility = View.GONE
        val container = android.widget.FrameLayout(this)
        container.addView(
            webView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        val barParams = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 6,
        )
        barParams.topMargin = 0
        container.addView(progressBar, barParams)

        // 准备期覆盖层：首次启动要解压运行时与工具链（慢设备 1-3 分钟），
        // 显示当前阶段与已写入体积，避免用户以为卡死。
        preparingText = android.widget.TextView(this).apply {
            setTextColor(android.graphics.Color.parseColor("#dddddd"))
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            setLineSpacing(8f, 1f)
            setPadding(64, 40, 64, 0)
        }
        preparingOverlay = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(android.graphics.Color.parseColor("#121212"))
            visibility = View.GONE
            addView(ProgressBar(this@MainActivity).apply { isIndeterminate = true })
            addView(preparingText)
        }
        container.addView(
            preparingOverlay,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(container)
    }

    /** 读取 RuntimeInstaller 上报的安装进度，刷新准备期覆盖层 */
    private fun updatePreparing() {
        val sp = getSharedPreferences(Env.PREFS_SETTINGS, MODE_PRIVATE)
        val stage = sp.getString(Env.KEY_INSTALL_STAGE, "").orEmpty()
        val detail = sp.getString(Env.KEY_INSTALL_DETAIL, "").orEmpty()
        if (stage.isEmpty()) {
            preparingOverlay.visibility = View.GONE
            return
        }
        preparingOverlay.visibility = View.VISIBLE
        preparingText.text = buildString {
            append(stage)
            if (detail.isNotEmpty()) append("\n$detail")
            append("\n\n首次启动需要 1-3 分钟，完成后自动进入工作台")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true // H5 用 localStorage 保存 token/配对
        settings.databaseEnabled = true
        // 安全收紧：H5 不依赖 file:// 或 content://（已核查 desktop/src），
        // 关闭后可避免"页面被注入时读取应用私有文件"的风险。
        // 若未来需要本地文件预览，改回 true 即可。
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        // 默认即为 false，显式声明以防被别处改动
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.textZoom = 100
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        CookieManager.getInstance().setAcceptCookie(true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                Log.i(TAG, "页面加载完成: $url")
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                Log.e(TAG, "页面加载错误: ${request?.url} ${error?.description}")
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
            ): Boolean {
                val uri = request?.url ?: return false
                val url = uri.toString()
                // 内部页面与回环地址留在 WebView；外部链接交系统浏览器。
                // 用 host 精确匹配而非 startsWith —— 后者会被
                // "http://127.0.0.1.attacker.com" 这类域名绕过。
                val isLoopback = uri.scheme == "http" &&
                    (uri.host == "127.0.0.1" || uri.host == "localhost")
                if (isLoopback ||
                    url.startsWith("blob:") ||
                    url.startsWith("data:")
                ) {
                    return false
                }
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {
                }
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress >= 100) {
                    progressBar.visibility = View.GONE
                } else {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                try {
                    val intent = fileChooserParams?.createIntent()
                        ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                    this@MainActivity.fileChooser.launch(intent)
                } catch (_: Exception) {
                    filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = null
                }
                return true
            }
        }
    }

    /** 轮询 server 健康状态，就绪后加载 H5（运行时首次解压需要时间） */
    private fun probeAndLoad() {
        probeJob?.cancel()
        probeJob = CoroutineScope(Dispatchers.Main).launch {
            // 内核前置检查：APK 不再内置内核产物，缺失/损坏时强制进入引导页。
            // 运行时尚未安装完成时不跳转（首次解压 1-3 分钟，属正常等待）。
            val needSetup = withContext(Dispatchers.IO) {
                RuntimeInstaller.isInstalled(this@MainActivity) &&
                    when (KernelManager.ensureHealthy(this@MainActivity)) {
                        KernelManager.Status.MISSING, KernelManager.Status.BROKEN -> true
                        else -> false
                    }
            }
            if (needSetup) {
                startActivity(Intent(this@MainActivity, KernelSetupActivity::class.java))
                return@launch
            }

            val port = getSharedPreferences("settings", MODE_PRIVATE)
                .getInt("port", Env.DEFAULT_PORT)
            val url = "http://127.0.0.1:$port/"
            var attempt = 0
            // 首次安装/构建可能持续数分钟：安装进行中不计入超时，另设 10 分钟总上限
            val deadline = System.currentTimeMillis() + 10 * 60 * 1000
            while (isActive && attempt < 60 && System.currentTimeMillis() < deadline) {
                updatePreparing()
                val installingNow = preparingOverlay.visibility == View.VISIBLE
                val ok = withContext(Dispatchers.IO) {
                    try {
                        val conn = java.net.URL("http://127.0.0.1:$port/health")
                            .openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
                        conn.connectTimeout = 1500
                        conn.readTimeout = 1500
                        conn.responseCode == 200
                    } catch (e: Exception) {
                        if (attempt == 0 || attempt == 59) {
                            Log.w(TAG, "probe 失败: ${e.javaClass.simpleName}: ${e.message}")
                        }
                        false
                    }
                }
                if (ok) {
                    preparingOverlay.visibility = View.GONE
                    if (webView.url?.startsWith(url) != true) {
                        webView.loadUrl(url)
                    }
                    return@launch
                }
                if (!installingNow) attempt++
                delay(1000)
            }
            // 超时：显示重试提示页
            preparingOverlay.visibility = View.GONE
            webView.loadDataWithBaseURL(
                null,
                "<html><body style='background:#121212;color:#ddd;font-family:sans-serif;" +
                    "display:flex;align-items:center;justify-content:center;height:100%'>" +
                    "<div style='text-align:center'><h2>cc-haha 服务未就绪</h2>" +
                    "<p>运行时可能仍在解压安装（首次启动约需 1-2 分钟）</p>" +
                    "<p>请稍候返回重试，或到「设置」查看服务状态</p></div></body></html>",
                "text/html",
                "utf-8",
                null,
            )
        }
    }

    /** 外部存储读写权限（工作区在 /storage/emulated/0/cc-haha） */
    private fun requestStoragePermission() {
        val needed = arrayListOf<String>()
        if (ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.READ_EXTERNAL_STORAGE,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (needed.isNotEmpty()) {
            try {
                requestPermissions(needed.toTypedArray(), 101)
            } catch (e: Exception) {
                Log.w(TAG, "存储权限申请失败（工作区将回退到私有目录）", e)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Shizuku 的授权结果也走此回调
        ShizukuHelper.onRequestPermissionsResult(requestCode, grantResults)
        if (requestCode == 101) {
            val granted = grantResults.isNotEmpty() &&
                grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
            Log.i(TAG, "存储权限结果: granted=$granted（工作区=" +
                Env.workspaceDir(this).absolutePath + "）")
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            // 保持服务运行，仅退到后台
            moveTaskToBack(true)
        }
    }

    fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }
}
