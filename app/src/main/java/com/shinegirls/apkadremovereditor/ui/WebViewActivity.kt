package com.shinegirls.apkadremovereditor.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.DownloadListener
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.shinegirls.apkadremovereditor.R
import com.shinegirls.apkadremovereditor.core.LanguageManager
import com.shinegirls.apkadremovereditor.core.UpdateChecker
import com.shinegirls.apkadremovereditor.utils.UiUtils

/**
 * 内置下载浏览器。
 *
 * 用应用内 WebView 打开蓝奏云网盘等下载页面，拦截其中的 APK 下载地址：
 * - 常规页面导航中携带 .apk 后缀或 application/vnd.android.package-archive 类型的资源请求，
 *   会被就地拦截并转交 [UpdateChecker.downloadApkFromUrl] 使用应用内进度下载；
 * - 通过系统下载监听（DownloadListener）捕获到 APK 下载时，同样转交应用内下载，
 *   保证用户始终通过内置更新弹窗的进度下载流程完成新版安装。
 */
class WebViewActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.wrapContext(newBase))
    }

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: LinearProgressIndicator

    /** 防止重复弹进度下载对话框。 */
    private var downloadHandled = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        val url = intent.getStringExtra(EXTRA_URL) ?: UpdateChecker.LANZOU_DOWNLOAD_URL
        intent.getStringExtra(EXTRA_TITLE)?.let { toolbar.title = it }

        progressBar = findViewById(R.id.webProgress)
        webView = findViewById(R.id.webView)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.setSupportZoom(false)
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        // 伪装成移动端浏览器 UA，规避部分网盘对 WebView UA 的 UA 检测
        settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"

        // 开启 Cookie，网盘下载依赖会话 Cookie
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {

            /**
             * 拦截页面内的 APK 资源请求（应用了 .apk 后缀的图片/JS/主文档之外请求）。
             * 这里仅用于捕获那些通过 WebView 内部请求加载（非系统下载）的 APK 资源。
             */
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val reqUrl = request.url.toString()
                if (looksLikeApk(reqUrl)) {
                    if (!downloadHandled) {
                        downloadHandled = true
                        UiUtils.info(this@WebViewActivity, "检测到下载地址，正在使用应用内下载…")
                        UpdateChecker.downloadApkFromUrl(this@WebViewActivity, reqUrl)
                        // 已转交应用内下载，拦截原始请求
                    }
                }
                return null
            }

            /**
             * 主框架导航拦截：蓝奏云等网盘点击下载后常以 window.location 跳转到真实 APK 地址，
             * 在此形式下由 DownloadListener 兜底处理，这里做好 Cookie 同步即可。
             */
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest
            ): Boolean {
                val reqUrl = request.url.toString()
                if (looksLikeApk(reqUrl)) {
                    if (!downloadHandled) {
                        downloadHandled = true
                        UiUtils.info(this@WebViewActivity, "检测到下载地址，正在使用应用内下载…")
                        UpdateChecker.downloadApkFromUrl(this@WebViewActivity, reqUrl)
                    }
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 页面加载完成，临时关闭进度条（下载进度用底部对话框展示）
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress >= 100) {
                    progressBar.visibility = View.GONE
                } else {
                    progressBar.visibility = View.VISIBLE
                    progressBar.setProgressCompat(newProgress, false)
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank() && toolbar.title != getString(R.string.app_name)) {
                    toolbar.title = title
                }
            }
        }

        // 系统级下载监听：网盘通常以浏览器下载方式触发，捕获 APK 下载
        webView.setDownloadListener(object : DownloadListener {
            override fun onDownloadStart(
                url: String,
                userAgent: String?,
                contentDisposition: String?,
                mimetype: String?,
                contentLength: Long
            ) {
                if (downloadHandled) return
                val isApk = looksLikeApk(url) ||
                    (mimetype != null && mimetype == "application/vnd.android.package-archive") ||
                    (contentDisposition != null && contentDisposition.contains(".apk", true))
                if (!isApk) {
                    // 非 APK 文件，交由系统浏览器处理
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) {
                        UiUtils.warning(this@WebViewActivity, "无法打开外部链接")
                    }
                    return
                }
                downloadHandled = true
                UiUtils.info(this@WebViewActivity, "检测到下载地址，正在使用应用内下载…")
                UpdateChecker.downloadApkFromUrl(this@WebViewActivity, url, fileSize = contentLength)
            }
        })

        webView.loadUrl(url)
    }

    /**
     * 判断 URL 是否指向 APK 文件（蓝奏云真实下载地址通常以 .apk 结尾）。
     */
    private fun looksLikeApk(url: String): Boolean {
        return url.substringBefore('?').trim().lowercase().endsWith(".apk")
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    /** 返回声明，防止内存泄漏。 */
    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}