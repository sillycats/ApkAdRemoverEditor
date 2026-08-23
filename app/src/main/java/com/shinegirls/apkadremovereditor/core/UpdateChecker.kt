package com.shinegirls.apkadremovereditor.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.shinegirls.apkadremovereditor.R
import com.shinegirls.apkadremovereditor.ui.WebViewActivity
import com.shinegirls.apkadremovereditor.utils.Format
import com.shinegirls.apkadremovereditor.utils.UiUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.HashSet

/**
 * 应用更新检测器（内置专业更新弹窗 + 远程公告弹窗）。
 *
 * 从配置的版本清单地址拉取最新版本信息，与当前安装版本比对后：
 * - 无更新：提示已是最新版本
 * - 有更新：弹出内置美化弹窗，展示新旧版本号、更新包大小与更新说明
 * - 点击"立即更新"：应用内直接下载 APK，实时显示进度条、百分比与已下载/总大小
 * - 下载完成后通过 FileProvider 拉起系统安装器安装
 * - 强制更新：弹窗不可取消，仅提供"立即更新"，必须更新后才能继续使用
 *
 * 远程公告：与更新清单同源，启动时静默检查，展示模式完全由远程配置控制：
 * - force   ：强制公告，不能返回 / 不能取消 / 不能关闭，仅可点击"知道了"确认
 * - noMore  ：可关闭公告，额外提供"不再提示"按钮，点击后永久不再弹出
 * - once    ：只显示一次，展示后自动记录，之后启动不再弹出
 * - closable：普通可关闭公告，每次启动都会弹出
 *
 * 远程版本清单为 JSON 格式，字段如下：
 * {
 *   "versionCode": 2,              // 最新版本号（整数，必须大于当前版本才提示）
 *   "versionName": "1.1",          // 最新版本名称（展示用）
 *   "force": false,                // 是否强制更新
 *   "description": "更新内容...",  // 更新说明
 *   "url": "https://.../app.apk",  // 新版 APK 下载地址
 *   "size": 27800000,              // 可选：更新包大小（字节），不提供则下载时从响应头获取
 *   "announcement": {              // 可选：远程公告配置
 *     "enabled": true,             // 公告总开关，false 时一律不显示（默认 true）
 *     "id": "ann_001",             // 公告唯一 ID（用于"不再提示 / 只显示一次"持久化）
 *     "title": "重要公告",          // 公告标题
 *     "content": "公告内容...",     // 公告正文
 *     "type": "force",             // 展示模式：force / noMore / once / closable
 *     "buttonText": "知道了",       // 可选：确认按钮文字
 *     "minVersion": 1,             // 可选：仅当前版本 >= minVersion 时显示
 *     "maxVersion": 99999          // 可选：仅当前版本 <= maxVersion 时显示
 *   }
 * }
 */
object UpdateChecker {

    /** 主版本清单地址（如无自建服务器，可替换为自己的地址）。 */
    const val DEFAULT_CHECK_URL =
        "https://raw.githubusercontent.com/sillycats/XiaoNaiPing/main/apkadremovereditor/update.json"

    /** 备用版本清单地址：主地址无法访问或返回异常时自动切换。 */
    const val FALLBACK_CHECK_URL =
        "https://apkadremovereditor.pages.dev/update.json"

    /** 蓝奏云网盘链接：当线上更新清单都不可达时，引导用户用手机浏览器自行下载。 */
    const val LANZOU_DOWNLOAD_URL =
        "https://www.lanzoux.com/b062p46la"

    private const val PREFS_NAME = "update_checker"

    /** 网络超时（毫秒）。 */
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    /** 分块下载缓冲大小（字节）。 */
    private const val BUFFER_SIZE = 64 * 1024

    /** 最新版本信息。 */
    data class UpdateInfo(
        val versionCode: Long,
        val versionName: String,
        val forceUpdate: Boolean,
        val description: String,
        val downloadUrl: String,
        val fileSize: Long = 0L,
        val announcement: AnnouncementInfo? = null
    )

    /**
     * 远程公告信息。
     *
     * @param enabled 公告总开关，false 时一律不显示（默认 true）
     * @param id 公告唯一 ID，用于"不再提示 / 只显示一次"的持久化去重
     * @param title 公告标题
     * @param content 公告正文
     * @param type 展示模式：force（强制不可关闭）/ noMore（可关闭+不再提示）/ once（只显示一次）/ closable（普通可关闭）
     * @param buttonText 确认按钮文字，默认"知道了"
     * @param minVersion 仅当当前版本号 >= minVersion 时显示
     * @param maxVersion 仅当当前版本号 <= maxVersion 时显示
     */
    data class AnnouncementInfo(
        val enabled: Boolean = true,
        val id: String,
        val title: String,
        val content: String,
        val type: String,
        val buttonText: String = LanguageManager.str(R.string.s_ce26955a),
        val minVersion: Long = 0L,
        val maxVersion: Long = Long.MAX_VALUE
    ) {
        /** 是否为强制公告（不能返回 / 不能取消 / 不能关闭）。 */
        val isForce: Boolean get() = type == "force"

        /**
         * 判断当前安装版本下该公告是否应展示。
         * - 总开关 enabled=false：一律不展示
         * - 版本号不在 [minVersion, maxVersion] 区间内：不展示
         * - once 类型：已展示过则不再展示
         * - noMore 类型：用户已选择"不再提示"则不再展示
         */
        fun shouldShow(context: Context): Boolean {
            if (!enabled) return false
            val current = getCurrentVersionCode(context)
            if (current < minVersion || current > maxVersion) return false
            return when (type) {
                "once" -> !hasShownOnce(context, id)
                "noMore" -> !isNoMore(context, id)
                else -> true
            }
        }
    }

    /**
     * 获取当前自己管理的检查地址（兼容单地址读取）。
     */
    fun getCheckUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("check_url", DEFAULT_CHECK_URL) ?: DEFAULT_CHECK_URL
    }

    /**
     * 获取按优先级排列的检测地址列表，用于自动故障切换。
     *
     * 顺序优先级：自定义地址（prefs 中保存） > 主地址 > 备用地址。
     * 使用 LinkedHashSet 自动去除重复项并保持顺序，保证每个地址只尝试一次。
     */
    fun getCheckUrls(context: Context): List<String> {
        val urls = LinkedHashSet<String>()
        val custom = getCheckUrl(context).trim()
        if (custom.isNotBlank()) urls.add(custom)
        urls.add(DEFAULT_CHECK_URL)
        urls.add(FALLBACK_CHECK_URL)
        return urls.toList()
    }

    /**
     * 保存检查地址。
     */
    fun setCheckUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("check_url", url.trim())
            .apply()
    }

    /**
     * 获取当前安装版本号（versionCode）。
     */
    fun getCurrentVersionCode(context: Context): Long {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
        } catch (_: PackageManager.NameNotFoundException) {
            0L
        }
    }

    /**
     * 获取当前安装版本名称（versionName）。
     */
    fun getCurrentVersionName(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        } catch (_: PackageManager.NameNotFoundException) {
            "1.0"
        }
    }

    /**
     * 一键检测更新：后台拉取版本信息，UI 线程展示结果。
     * 若有强制更新会弹出不可取消的对话框。
     *
     * @param activity 用于展示结果弹窗的 Activity（需持有 lifecycleScope）。
     */
    fun checkForUpdate(activity: androidx.appcompat.app.AppCompatActivity) {
        UiUtils.info(activity, activity.getString(R.string.h_037647ef))
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val info = fetchLatestUpdate(getCheckUrls(activity))
            withContext(Dispatchers.Main) {
                showResult(activity, info)
            }
        }
    }

    /**
     * 启动时自动检测强制更新 + 远程公告（静默检查）。
     *
     * 优先级：强制更新优先于公告。仅当满足以下条件时弹出不可关闭、不可取消的强制更新弹窗：
     * - 远程清单声明 force=true（强制更新）
     * - 当前安装版本号低于最新版本号
     *
     * 无强制更新时，若远程配置了公告（announcement），按公告自身的展示模式弹出：
     * - force：强制公告，不可关闭
     * - noMore：可关闭 + "不再提示"
     * - once：只显示一次
     * - closable：普通可关闭
     *
     * 其余情况（无更新 / 非强制更新 / 已是最新版本 / 无公告）均静默处理，不打扰用户。
     *
     * @param activity 用于展示弹窗的 Activity（需持有 lifecycleScope）。
     */
    fun checkForUpdateOnLaunch(activity: androidx.appcompat.app.AppCompatActivity) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val info = fetchLatestUpdate(getCheckUrls(activity))
            withContext(Dispatchers.Main) {
                if (info == null) return@withContext
                val currentCode = getCurrentVersionCode(activity)
                // 仅当"强制更新 且 当前版本低于最新版本"时弹出不可关闭弹窗
                if (info.forceUpdate && info.versionCode > currentCode) {
                    showUpdateDialog(activity, info)
                    return@withContext
                }
                // 无强制更新时，按远程配置展示公告
                info.announcement?.let { ann ->
                    if (ann.shouldShow(activity)) {
                        showAnnouncementDialog(activity, ann)
                    }
                }
            }
        }
    }

    /**
     * 依次尝试多个检测地址拉取版本清单（同步调用，需在子线程执行）。
     *
     * 自动故障切换：按传入顺序逐个尝试，若某个地址无法访问（网络失败、连接超时、
     * HTTP 非 2xx）或返回内容解析失败（非合法 JSON / 缺少关键字段），
     * 自动切换到下一个地址，直到有地址成功返回为止。
     *
     * @param checkUrls 按优先级排列的检测地址列表
     * @return 首个成功解析的 UpdateInfo；所有地址均失败时返回 null
     */
    fun fetchLatestUpdate(checkUrls: List<String>): UpdateInfo? {
        for (url in checkUrls) {
            val info = fetchFromSingleUrl(url)
            if (info != null) return info
        }
        return null
    }

    /**
     * 从单个远程地址拉取版本清单并解析（同步调用，需在子线程执行）。
     *
     * @return 解析后的 UpdateInfo；网络失败、HTTP 非 2xx、JSON 非法或字段缺失返回 null。
     */
    private fun fetchFromSingleUrl(checkUrl: String): UpdateInfo? {
        return try {
            val url = URL(checkUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "APKAdRemoverEditor/1.0")
                instanceFollowRedirects = true
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) return null
                val sb = StringBuilder()
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        sb.append(line).append('\n')
                    }
                }
                parseUpdateInfo(sb.toString().trim())
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseUpdateInfo(jsonStr: String): UpdateInfo? {
        return try {
            val json = JSONObject(jsonStr)
            if (!json.has("versionCode") || !json.has("url")) return null
            UpdateInfo(
                versionCode = json.getLong("versionCode"),
                versionName = json.optString("versionName", ""),
                forceUpdate = json.optBoolean("force", false),
                description = json.optString("description", ""),
                downloadUrl = json.getString("url"),
                fileSize = json.optLong("size", json.optLong("fileSize", 0L)),
                announcement = parseAnnouncement(json)
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 从版本清单 JSON 中解析远程公告配置。
     * 未配置 announcement 或正文为空时返回 null。
     */
    private fun parseAnnouncement(json: JSONObject): AnnouncementInfo? {
        return try {
            if (!json.has("announcement")) return null
            val ann = json.getJSONObject("announcement")
            val content = ann.optString("content", "")
            if (content.isBlank()) return null
            AnnouncementInfo(
                enabled = ann.optBoolean("enabled", true),
                id = ann.optString("id", ""),
                title = ann.optString("title", LanguageManager.str(R.string.s_a1b863fa)),
                content = content,
                type = ann.optString("type", "closable"),
                buttonText = ann.optString("buttonText", LanguageManager.str(R.string.s_ce26955a)),
                minVersion = ann.optLong("minVersion", 0L),
                maxVersion = ann.optLong("maxVersion", Long.MAX_VALUE)
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 在 UI 线程显示内置更新结果弹窗。
     *
     * @param activity 宿主 Activity
     * @param info 已拉取到的最新版本信息，null 表示网络/解析失败
     */
    fun showResult(activity: Activity, info: UpdateInfo?) {
        // Activity 已销毁则不再展示，避免崩溃或内存泄漏
        if (activity.isFinishing || activity.isDestroyed) return
        if (info == null) {
            // 更新清单全部地址都不可达时，引导用户用手机浏览器自行下载
            showCheckFailedDialog(activity)
            return
        }

        val currentCode = getCurrentVersionCode(activity)
        if (info.versionCode <= currentCode) {
            UiUtils.success(activity, activity.getString(R.string.h_08430702, getCurrentVersionName(activity)))
            return
        }

        showUpdateDialog(activity, info)
    }

    /**
     * 展示"检测更新失败"对话框，提供通过手机浏览器前往蓝奏云自行下载的入口。
     *
     * 当所有线上更新清单地址都不可达（网络异常 / 服务不可用）时调用，
     * 保证用户始终有一条可以下载最新版本的路径。
     */
    private fun showCheckFailedDialog(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity, R.style.RoundedAlertDialog)
            .setTitle(activity.getString(R.string.h_9e1acb3b))
            .setMessage(
                activity.getString(R.string.h_7822f13f) +
                    activity.getString(R.string.h_6c59c767) +
                    LANZOU_DOWNLOAD_URL
            )
            .setPositiveButton(activity.getString(R.string.h_21888ee3)) { _, _ -> openLanzouInBuiltInBrowser(activity) }
            .setNegativeButton(activity.getString(R.string.s_625fb26b), null)
            .show()
    }

    /**
     * 调用手机浏览器打开指定链接。
     */
    private fun openBrowser(activity: Activity, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
        } catch (_: Exception) {
            UiUtils.error(activity, activity.getString(R.string.h_8afaf028))
        }
    }

    /**
     * 使用内置浏览器（WebView）打开蓝奏云下载页。
     *
     * 内置浏览器会拦截页面内的 APK 下载地址，并自动调用应用内进度下载
     * （[downloadApkFromUrl]），让用户无需切换系统浏览器即可完成新版下载安装。
     */
    fun openLanzouInBuiltInBrowser(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        try {
            val intent = Intent(activity, WebViewActivity::class.java).apply {
                putExtra(WebViewActivity.EXTRA_URL, LANZOU_DOWNLOAD_URL)
                putExtra(WebViewActivity.EXTRA_TITLE, activity.getString(R.string.app_title_download))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (_: Exception) {
            UiUtils.error(activity, activity.getString(R.string.h_eca9a205))
        }
    }

    /**
     * 展示专业美化的内置更新弹窗。
     */
    private fun showUpdateDialog(activity: Activity, info: UpdateInfo) {
        if (activity.isFinishing || activity.isDestroyed) return
        val view = activity.layoutInflater.inflate(R.layout.dialog_update, null)

        val tvCurrentVersion = view.findViewById<TextView>(R.id.tvCurrentVersion)
        val tvNewVersion = view.findViewById<TextView>(R.id.tvNewVersion)
        val tvUpdateDesc = view.findViewById<TextView>(R.id.tvUpdateDesc)
        val tvPercent = view.findViewById<TextView>(R.id.tvPercent)
        val tvProgressDetail = view.findViewById<TextView>(R.id.tvProgressDetail)
        val progressBar = view.findViewById<LinearProgressIndicator>(R.id.progressBar)
        val progressSection = view.findViewById<View>(R.id.progressSection)
        val btnUpdate = view.findViewById<MaterialButton>(R.id.btnUpdate)
        val btnLater = view.findViewById<MaterialButton>(R.id.btnLater)
        val btnClose = view.findViewById<ImageButton>(R.id.btnClose)
        val btnBrowserDownload = view.findViewById<MaterialButton>(R.id.btnBrowserDownload)

        // 版本号
        tvCurrentVersion.text = "v${getCurrentVersionName(activity)}"
        tvNewVersion.text = if (info.versionName.isBlank()) "v${info.versionCode}" else "v${info.versionName}"

        // 更新说明
        tvUpdateDesc.text = if (info.description.isBlank()) activity.getString(R.string.h_ec3b529d) else info.description

        // 构建弹窗
        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .create()
        // 强制更新不可取消，否则可点击外部或返回键关闭
        dialog.setCancelable(!info.forceUpdate)
        dialog.setCanceledOnTouchOutside(!info.forceUpdate)
        // 强制更新时拦截返回键，彻底阻止关闭
        if (info.forceUpdate) {
            dialog.setOnKeyListener { _, keyCode, _ ->
                keyCode == android.view.KeyEvent.KEYCODE_BACK
            }
        }
        // 透明背景 + 圆角卡片
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        // 自适应屏幕：内容过长时限制高度并滚动，避免溢出屏幕
        UiUtils.fitDialogToScreen(dialog)

        // 强制更新：隐藏"稍后再说"与右上角关闭按钮
        if (info.forceUpdate) {
            btnLater.visibility = View.GONE
            btnClose.visibility = View.INVISIBLE
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        btnLater.setOnClickListener { dialog.dismiss() }

        // 点击后用内置浏览器前往蓝奏云自行下载
        btnBrowserDownload.setOnClickListener { openLanzouInBuiltInBrowser(activity) }

        btnUpdate.setOnClickListener {
            // 若内置下载链接不是 APK 直链（如网盘页面 / 下载站中转页），
            // 应用内无法直接下载，自动改用手机浏览器跳转蓝奏云供用户下载
            if (!isApkDirectLink(info.downloadUrl)) {
                UiUtils.info(activity, activity.getString(R.string.h_f26b60e4))
                dialog.dismiss()
                openLanzouInBuiltInBrowser(activity)
                return@setOnClickListener
            }
            btnUpdate.isEnabled = false
            btnUpdate.text = activity.getString(R.string.h_e4090eb7)
            btnLater.isEnabled = false
            progressSection.visibility = View.VISIBLE
            startDownload(
                activity = activity,
                info = info,
                progressBar = progressBar,
                tvPercent = tvPercent,
                tvProgressDetail = tvProgressDetail,
                btnUpdate = btnUpdate,
                onSuccess = { dialog.dismiss() }
            )
        }
    }

    /**
     * 判断下载链接是否为 APK 直链（可直接使用 HttpURLConnection 下载的文件地址）。
     *
     * 判定规则：
     * - 链接以 .apk 结尾（忽略大小写与查询参数）视为直链；
     * - 否则（如蓝奏云等网盘分享页、下载站页面等）视为非直链。
     */
    private fun isApkDirectLink(url: String): Boolean {
        return try {
            // 去掉查询参数（? 之后的部分）再判断路径后缀
            val withoutQuery = url.substringBefore('?').trim().lowercase()
            withoutQuery.endsWith(".apk")
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 展示远程公告弹窗。
     *
     * 展示模式由远程配置的 type 字段控制：
     * - force：强制公告，不能返回 / 不能取消 / 不能关闭，确认按钮被禁用，弹窗一直显示
     * - noMore：可关闭，额外提供"不再提示"按钮，点击后永久不再弹出
     * - once：只显示一次，展示后自动记录，之后启动不再弹出
     * - closable：普通可关闭公告，每次启动都会弹出
     */
    private fun showAnnouncementDialog(activity: Activity, ann: AnnouncementInfo) {
        if (activity.isFinishing || activity.isDestroyed) return
        val view = activity.layoutInflater.inflate(R.layout.dialog_announcement, null)

        val tvTitle = view.findViewById<TextView>(R.id.tvAnnTitle)
        val tvContent = view.findViewById<TextView>(R.id.tvAnnContent)
        val btnClose = view.findViewById<ImageButton>(R.id.btnAnnClose)
        val btnNoMore = view.findViewById<MaterialButton>(R.id.btnAnnNoMore)
        val btnOk = view.findViewById<MaterialButton>(R.id.btnAnnOk)

        tvTitle.text = ann.title
        tvContent.text = ann.content
        btnOk.text = ann.buttonText

        // 构建弹窗
        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .create()
        // 强制公告不可取消，否则可点击外部或返回键关闭
        dialog.setCancelable(!ann.isForce)
        dialog.setCanceledOnTouchOutside(!ann.isForce)
        // 强制公告时拦截返回键，彻底阻止关闭
        if (ann.isForce) {
            dialog.setOnKeyListener { _, keyCode, _ ->
                keyCode == android.view.KeyEvent.KEYCODE_BACK
            }
        }
        // 透明背景 + 圆角卡片
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        // 自适应屏幕：内容过长时限制高度并滚动，避免溢出屏幕
        UiUtils.fitDialogToScreen(dialog)

        // 强制公告：隐藏关闭按钮与"不再提示"，禁用确认按钮，弹窗无法以任何方式关闭
        if (ann.isForce) {
            btnClose.visibility = View.GONE
            btnNoMore.visibility = View.GONE
            btnOk.isEnabled = false
            btnOk.alpha = 0.6f
        } else {
            btnClose.setOnClickListener { dialog.dismiss() }
            // 仅 noMore 类型显示"不再提示"按钮
            if (ann.type == "noMore") {
                btnNoMore.visibility = View.VISIBLE
                btnNoMore.setOnClickListener {
                    markNoMore(activity, ann.id)
                    dialog.dismiss()
                }
            } else {
                btnNoMore.visibility = View.GONE
            }
        }

        // once 类型：展示即记录，保证"只显示一次"
        if (ann.type == "once") {
            markShownOnce(activity, ann.id)
        }

        // 仅非强制公告可点击确认关闭
        if (!ann.isForce) {
            btnOk.setOnClickListener { dialog.dismiss() }
        }
    }

    // ==================== 公告持久化 ====================

    /**
     * once 类型：是否已展示过该公告。
     */
    private fun hasShownOnce(context: Context, id: String): Boolean {
        if (id.isBlank()) return false
        val set = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet("ann_shown_once", emptySet()) ?: emptySet()
        return id in set
    }

    /**
     * once 类型：记录该公告已展示。
     */
    private fun markShownOnce(context: Context, id: String) {
        if (id.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = HashSet(prefs.getStringSet("ann_shown_once", emptySet()) ?: emptySet())
        set.add(id)
        prefs.edit().putStringSet("ann_shown_once", set).apply()
    }

    /**
     * noMore 类型：用户是否已选择"不再提示"。
     */
    private fun isNoMore(context: Context, id: String): Boolean {
        if (id.isBlank()) return false
        val set = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet("ann_no_more", emptySet()) ?: emptySet()
        return id in set
    }

    /**
     * noMore 类型：记录用户选择"不再提示"。
     */
    private fun markNoMore(context: Context, id: String) {
        if (id.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = HashSet(prefs.getStringSet("ann_no_more", emptySet()) ?: emptySet())
        set.add(id)
        prefs.edit().putStringSet("ann_no_more", set).apply()
    }

    /**
     * 应用内下载更新 APK 到应用缓存目录，实时更新进度，完成后拉起安装器。
     *
     * 进度显示策略：下载前先通过 HEAD 请求获取文件真实大小，确保总大小可用，
     * 从而始终以确定进度条显示"已下载/总大小 + 百分比"，
     * 进度条与下载量严格成正比（下载 10% 进度条即 1/10）。
     */
    private fun startDownload(
        activity: Activity,
        info: UpdateInfo,
        progressBar: LinearProgressIndicator,
        tvPercent: TextView,
        tvProgressDetail: TextView,
        btnUpdate: MaterialButton,
        onSuccess: () -> Unit
    ) {
        val handler = Handler(Looper.getMainLooper())

        Thread {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(info.downloadUrl)

                // ========== 预检：优先用 HEAD 获取文件真实大小 ==========
                var total = 0L
                try {
                    val headConn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "HEAD"
                        connectTimeout = CONNECT_TIMEOUT_MS
                        readTimeout = CONNECT_TIMEOUT_MS
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "APKAdRemoverEditor/1.0")
                    }
                    try {
                        if (headConn.responseCode in 200..299) {
                            total = headConn.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L
                        }
                    } finally {
                        headConn.disconnect()
                    }
                } catch (_: Exception) {
                }

                // HEAD 拿不到时，回退到版本清单声明的 size
                if (total <= 0) total = info.fileSize

                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    // 下载大文件时放宽读超时，避免中途被误判为超时
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "application/vnd.android.package-archive,*/*")
                    setRequestProperty("User-Agent", "APKAdRemoverEditor/1.0")
                }

                val code = conn.responseCode
                if (code !in 200..299) {
                    throw IOException(activity.getString(R.string.h_8669676f))
                }

                // 若预检仍未拿到大小，再尝试从 GET 响应头获取
                if (total <= 0) {
                    total = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L
                }

                // 下载到应用内部缓存（无需存储权限，FileProvider cache-path 已配置）
                val safeVersion = info.versionName.ifBlank { info.versionCode.toString() }
                val target = File(activity.cacheDir, "app_update_v${safeVersion}.apk")

                // 进度条复位并设为确定模式
                handler.post {
                    progressBar.isIndeterminate = false
                    progressBar.setProgressCompat(0, true)
                    tvProgressDetail.text = if (total > 0) {
                        activity.getString(R.string.h_ce5f4cc6, formatSize(total))
                    } else {
                        activity.getString(R.string.h_98601d36)
                    }
                }

                conn.inputStream.use { input ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var downloaded = 0L
                        var read: Int
                        // 节流：每 300ms 至多向 UI 线程投递一次进度，避免大文件高频 post 卡顿
                        var lastUpdate = 0L
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate < 300) continue
                            lastUpdate = now
                            if (total > 0) {
                                // 严格按下载量计算百分比：下载多少，进度条就显示多少
                                val percent = (downloaded * 100 / total).toInt()
                                handler.post {
                                    progressBar.setProgressCompat(percent, true)
                                    tvPercent.text = "$percent%"
                                    tvProgressDetail.text = activity.getString(R.string.h_afb873ee, formatSize(downloaded), formatSize(total))
                                }
                            } else {
                                // 极端兜底：确实拿不到总大小时，仅显示已下载量
                                handler.post {
                                    tvPercent.text = activity.getString(R.string.h_2d455ce5)
                                    tvProgressDetail.text = activity.getString(R.string.h_ba5f8360, formatSize(downloaded))
                                }
                            }
                        }
                        output.flush()
                    }
                }

                // 下载完成后进度强制设为 100%
                handler.post {
                    progressBar.isIndeterminate = false
                    progressBar.setProgressCompat(100, true)
                    tvPercent.text = "100%"
                    tvProgressDetail.text = activity.getString(R.string.h_b1b762e6)
                    UiUtils.success(activity, activity.getString(R.string.h_7a8af100))
                    installApk(activity, target)
                    onSuccess()
                }
            } catch (e: Exception) {
                handler.post {
                    btnUpdate.isEnabled = true
                    btnUpdate.text = activity.getString(R.string.h_4357855b)
                    UiUtils.error(activity, activity.getString(R.string.h_1d9e668b, e.message))
                }
            } finally {
                conn?.disconnect()
            }
        }.start()
    }

    /**
     * 调用系统安装应用安装下载完成的 APK。
     *
     * 将下载到应用缓存目录的 APK 通过 FileProvider 以 content:// URI 共享给
     * 系统安装器（PackageInstaller activity），由系统完成安装。
     * - Android 8.0+ 需先检查"安装未知来源应用"权限，未开启则引导前往设置
     */
    private fun installApk(activity: Activity, file: File) {
        // 校验下载的 APK 文件是否存在且非空
        if (!file.exists() || file.length() == 0L) {
            UiUtils.error(activity, activity.getString(R.string.h_839ae19b))
            return
        }

        // Android 8.0+ 需要"安装未知来源应用"权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !canRequestPackageInstalls(activity)) {
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.h_4d002649))
                .setMessage(activity.getString(R.string.h_424670d9))
                .setPositiveButton(activity.getString(R.string.h_5e213ddb)) { _, _ ->
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")
                        )
                        activity.startActivity(intent)
                    } catch (_: Exception) {
                        UiUtils.warning(activity, activity.getString(R.string.h_bbe82bbc))
                    }
                }
                .setNegativeButton(activity.getString(R.string.s_87e4d9ef), null)
                .show()
            return
        }

        try {
            // 通过 FileProvider 生成可共享的 content:// URI
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (_: Exception) {
            try {
                // 兜底：直接使用 file:// URI 尝试（部分旧设备或 FileProvider 异常时）
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)
            } catch (_: Exception) {
                UiUtils.error(activity, activity.getString(R.string.h_6dfefe33))
            }
        }
    }

    /**
     * 检查是否允许安装未知来源应用（Android 8.0+）。
     */
    private fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * 公开下载入口：从捕获到的 APK 直链下载新版本并弹出应用内进度对话框。
     *
     * 供内置浏览器（WebView）拦截到蓝奏云等网盘的真实下载地址时调用，
     * 让用户在应用内看到进度条并以应用内方式下载完成安装。
     *
     * @param downloadUrl 捕获到的 APK 直链（以 .apk 结尾）
     * @param versionName 展示用版本名，可为空
     * @param fileSize    已知文件大小（字节），可为 0 表示下载时从响应头获取
     */
    fun downloadApkFromUrl(
        activity: Activity,
        downloadUrl: String,
        versionName: String = "",
        fileSize: Long = 0L
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (downloadUrl.isBlank()) {
            UiUtils.error(activity, activity.getString(R.string.h_790b1641))
            return
        }
        if (!isApkDirectLink(downloadUrl)) {
            // 非直链时给用户提示，防止把网页/中转页误当文件下载
            UiUtils.warning(activity, activity.getString(R.string.h_b9f0ee4f))
            return
        }

        val view = activity.layoutInflater.inflate(R.layout.dialog_download, null)
        val progressBar = view.findViewById<LinearProgressIndicator>(R.id.dlProgressBar)
        val tvPercent = view.findViewById<TextView>(R.id.dlPercent)
        val tvProgressDetail = view.findViewById<TextView>(R.id.dlProgressDetail)
        val btnClose = view.findViewById<ImageButton>(R.id.btnDlClose)

        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .create()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        // 自适应屏幕：内容过长时限制高度并滚动，避免溢出屏幕
        UiUtils.fitDialogToScreen(dialog)

        btnClose.setOnClickListener { }
        // 下载期间关闭按钮置灰不可用，避免误关导致下载中断
        btnClose.isEnabled = false

        val handler = Handler(Looper.getMainLooper())

        Thread {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(downloadUrl)

                // 预检：优先用 HEAD 获取文件真实大小
                var total = fileSize
                try {
                    val headConn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "HEAD"
                        connectTimeout = CONNECT_TIMEOUT_MS
                        readTimeout = CONNECT_TIMEOUT_MS
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "APKAdRemoverEditor/1.0")
                    }
                    try {
                        if (headConn.responseCode in 200..299) {
                            total = headConn.getHeaderField("Content-Length")?.toLongOrNull() ?: fileSize
                        }
                    } finally {
                        headConn.disconnect()
                    }
                } catch (_: Exception) {
                }

                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "application/vnd.android.package-archive,*/*")
                    setRequestProperty("User-Agent", "APKAdRemoverEditor/1.0")
                }

                val code = conn.responseCode
                if (code !in 200..299) {
                    throw IOException(activity.getString(R.string.h_8669676f))
                }

                if (total <= 0) {
                    total = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L
                }

                val safeVersion = versionName.ifBlank { "download" }.replace("v", "").replace(".", "_").ifBlank { "download" }
                val target = File(activity.cacheDir, "app_update_$safeVersion.apk")

                handler.post {
                    progressBar.isIndeterminate = false
                    progressBar.setProgressCompat(0, true)
                    tvProgressDetail.text = if (total > 0) {
                        activity.getString(R.string.h_ce5f4cc6, formatSize(total))
                    } else {
                        activity.getString(R.string.h_98601d36)
                    }
                }

                conn.inputStream.use { input ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var downloaded = 0L
                        var read: Int
                        var lastUpdate = 0L
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate < 300) continue
                            lastUpdate = now
                            if (total > 0) {
                                val percent = (downloaded * 100 / total).toInt()
                                handler.post {
                                    progressBar.setProgressCompat(percent, true)
                                    tvPercent.text = "$percent%"
                                    tvProgressDetail.text = activity.getString(R.string.h_afb873ee, formatSize(downloaded), formatSize(total))
                                }
                            } else {
                                handler.post {
                                    tvPercent.text = activity.getString(R.string.h_2d455ce5)
                                    tvProgressDetail.text = activity.getString(R.string.h_ba5f8360, formatSize(downloaded))
                                }
                            }
                        }
                        output.flush()
                    }
                }

                handler.post {
                    progressBar.isIndeterminate = false
                    progressBar.setProgressCompat(100, true)
                    tvPercent.text = "100%"
                    tvProgressDetail.text = activity.getString(R.string.h_b1b762e6)
                    UiUtils.success(activity, activity.getString(R.string.h_7a8af100))
                    dialog.dismiss()
                    installApk(activity, target)
                }
            } catch (e: Exception) {
                handler.post {
                    UiUtils.error(activity, activity.getString(R.string.h_1d9e668b, e.message))
                    if (dialog.isShowing) dialog.dismiss()
                }
            } finally {
                conn?.disconnect()
            }
        }.start()
    }

    /**
     * 格式化文件大小。
     */
    private fun formatSize(bytes: Long): String = Format.formatSize(bytes)
}