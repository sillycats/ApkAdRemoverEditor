package com.shinegirls.apkadremovereditor

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.method.ScrollingMovementMethod
import android.content.ClipData
import android.content.ClipboardManager
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.shinegirls.apkadremovereditor.core.AdPatternConfig
import com.shinegirls.apkadremovereditor.core.AdRemover
import com.shinegirls.apkadremovereditor.core.ApkProcessor
import com.shinegirls.apkadremovereditor.core.DataMultiplexingHelper
import com.shinegirls.apkadremovereditor.core.FlutterAdRemover
import com.shinegirls.apkadremovereditor.core.ProcessingReport
import com.shinegirls.apkadremovereditor.core.ReportGenerator
import com.shinegirls.apkadremovereditor.core.ScreenKeeper
import com.shinegirls.apkadremovereditor.core.Signer
import com.shinegirls.apkadremovereditor.core.SignatureVerificationRemover

import com.shinegirls.apkadremovereditor.core.ThemeManager
import com.shinegirls.apkadremovereditor.core.LanguageManager
import com.shinegirls.apkadremovereditor.core.UpdateChecker
import com.shinegirls.apkadremovereditor.utils.Format
import com.shinegirls.apkadremovereditor.utils.PathPreferences
import com.shinegirls.apkadremovereditor.utils.UiUtils
import com.google.android.material.button.MaterialButton

import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.wrapContext(newBase))
    }

    companion object {
        private const val REQUEST_CODE_PICK_APK = 1001
        private const val REQUEST_CODE_PERMISSIONS = 1002
        private const val EXPORT_DIR = Format.EXPORT_DIR
        /** 日志滚动最小间隔（毫秒） */
        private const val SCROLL_INTERVAL_MS = 200L
        /** 日志缓冲最大字符数，超出后丢弃最旧部分，防止 TextView 无限增长导致渲染变慢 */
        private const val MAX_LOG_CHARS = 200_000
    }

    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var btnStart: MaterialButton
    private lateinit var btnClearLog: ImageButton
    private lateinit var btnCopyLog: ImageButton

    private val apkProcessor = ApkProcessor()

    /** 日志缓冲：工作线程先写入，再由 UI 线程批量渲染，避免高频日志刷爆主线程 */
    private val logBuffer = StringBuilder()
    private var logFlushPending = false
    /** 日志滚动节流：SCROLL_INTERVAL_MS 内的多次日志只滚动一次，避免 UI 卡顿 */
    private var lastScrollTime = 0L
    /** 是否正在处理 APK，用于防止开始按钮被重复点击 */
    private var isProcessing = false

    // 日志着色颜色：仅在 Activity 创建时预取一次，避免每次刷新日志重复获取
    private var logColorError = 0
    private var logColorWarning = 0
    private var logColorSuccess = 0
    private var logColorInfo = 0
    private var logColorStep = 0
    private var logColorTime = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        // 应用持久化的主题模式（必须在 setContentView 之前）
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        progressBar = findViewById(R.id.progressBar)
        logView = findViewById(R.id.logView)
        scrollView = findViewById(R.id.scrollView)
        btnStart = findViewById(R.id.btnStart)
        btnClearLog = findViewById(R.id.btnClearLog)
        btnCopyLog = findViewById(R.id.btnCopyLog)

        logView.movementMethod = ScrollingMovementMethod.getInstance()

        // 预取日志着色颜色（避免每次刷新日志重复获取）
        logColorError = ContextCompat.getColor(this, R.color.log_error)
        logColorWarning = ContextCompat.getColor(this, R.color.log_warning)
        logColorSuccess = ContextCompat.getColor(this, R.color.log_success)
        logColorInfo = ContextCompat.getColor(this, R.color.log_info)
        logColorStep = ContextCompat.getColor(this, R.color.log_step)
        logColorTime = ContextCompat.getColor(this, R.color.log_time)

        val pickAction = View.OnClickListener {
            if (isProcessing) return@OnClickListener
            checkPermissionsAndPick()
        }
        btnStart.setOnClickListener(pickAction)

        // 清空日志
        btnClearLog.setOnClickListener {
            logView.text = ""
            log("▶ 日志已清空")
        }

        // 复制日志到剪贴板
        btnCopyLog.setOnClickListener {
            val text = logView.text.toString()
            if (text.isBlank()) {
                UiUtils.warning(this, "日志为空")
                return@setOnClickListener
            }
            val clip = ClipData.newPlainText("处理日志", text)
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(clip)
            UiUtils.success(this, "日志已复制到剪贴板")
        }

        checkPermissions()

        // 版本升级后重置签名效验为默认关闭（避免旧版本开启状态残留）
        PathPreferences.resetSignRemovalOnUpgrade(this)

        // 启动时自动检测强制更新：仅当远程声明强制更新且当前版本低于最新版本时，
        // 弹出不可关闭、不可取消的强制更新弹窗；否则静默处理。
        UpdateChecker.checkForUpdateOnLaunch(this)
    }

    /**
     * 实时日志输出（工作线程安全）。
     * 先写入缓冲，再投递一次 UI 线程渲染，把高频日志批量合并成一次 append。
     * 避免旧实现"每条日志都 runOnUiThread + fullScroll"导致主线程卡顿。
     */
    private fun log(message: String) {
        synchronized(logBuffer) {
            logBuffer.append(message).append('\n')
            // 字符数上限：超过后丢弃最旧的一半（从换行处切断），防止 TextView 无限增长
            if (logBuffer.length > MAX_LOG_CHARS) {
                val cut = logBuffer.indexOf("\n", logBuffer.length / 2)
                if (cut >= 0) logBuffer.delete(0, cut + 1)
            }
            // 同一批日志只投递一次渲染，后续日志合并进同一个缓冲
            if (!logFlushPending) {
                logFlushPending = true
                runOnUiThread { flushLog() }
            }
        }
    }

    /** 在 UI 线程执行一次批量渲染 + 滚动节流。 */
    private fun flushLog() {
        val chunk: String
        synchronized(logBuffer) {
            chunk = logBuffer.toString()
            logBuffer.setLength(0)
            logFlushPending = false
        }
        logView.append(colorizeLog(chunk))
        val now = SystemClock.uptimeMillis()
        if (now - lastScrollTime >= SCROLL_INTERVAL_MS) {
            lastScrollTime = now
            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /**
     * 将原始日志文本按级别着色，提升可读性。
     *
     * - 步骤标题 / 分隔线：品牌色加粗
     * - "✓" 成功：绿色
     * - "✗" / "错误" / "严重" / "堆栈"：红色
     * - "⚠" 警告：橙色
     * - "ℹ" 提示：蓝色
     * - "▶" 处理中：品牌色
     * - "⏱" 耗时：灰色
     */
    private fun colorizeLog(chunk: String): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        val lines = chunk.split('\n')
        val colorError = logColorError
        val colorWarning = logColorWarning
        val colorSuccess = logColorSuccess
        val colorInfo = logColorInfo
        val colorStep = logColorStep
        val colorTime = logColorTime
        for (line in lines) {
            val start = sb.length
            sb.append(line).append('\n')
            val len = sb.length - start
            val trimmed = line.trimStart()

            when {
                trimmed.contains('[', ignoreCase = true) &&
                    (trimmed.contains("错误", true) || trimmed.contains("严重", true) ||
                        trimmed.contains("堆栈", true) || trimmed.contains("失败", true)) ->
                    sb.setSpan(ForegroundColorSpan(colorError), start, start + len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

                trimmed.startsWith("✗") || trimmed.contains("✗") ->
                    sb.setSpan(ForegroundColorSpan(colorError), start, start + len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

                trimmed.startsWith("⚠") || trimmed.contains("⚠") ->
                    sb.setSpan(ForegroundColorSpan(colorWarning), start, start + len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

                trimmed.startsWith("✓") || trimmed.contains("✓") ->
                    sb.setSpan(ForegroundColorSpan(colorSuccess), start, start + len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

                trimmed.startsWith("ℹ") || trimmed.contains("ℹ") ->
                    sb.setSpan(ForegroundColorSpan(colorInfo), start, start + len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

                trimmed.startsWith("步骤") || trimmed.startsWith("▶") ->
                    sb.setSpan(ForegroundColorSpan(colorStep), start, start + len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

                trimmed.startsWith("⏱") ->
                    sb.setSpan(ForegroundColorSpan(colorTime), start, start + len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }

    private fun showProgress(show: Boolean) {
        runOnUiThread {
            progressBar.visibility = if (show) View.VISIBLE else View.GONE
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (_: Exception) {
                }
            }
        } else {
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            if (permissions.any {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }) {
                ActivityCompat.requestPermissions(this, permissions, REQUEST_CODE_PERMISSIONS)
            }
        }
    }

    private fun checkPermissionsAndPick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            UiUtils.warning(this, "请先授予\"所有文件访问\"权限")
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) {
            }
            return
        }
        pickApkFile()
    }

    private fun pickApkFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "application/vnd.android.package-archive"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(intent, "选择APK文件"), REQUEST_CODE_PICK_APK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK && data?.data != null && requestCode == REQUEST_CODE_PICK_APK) {
            processApk(data.data!!)
        }
    }

    /**
     * 处理存储权限申请结果：授权后自动继续选择 APK，避免用户需再次点击。
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS &&
            grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            pickApkFile()
        }
    }

    /**
     * 一键处理流程：解包 -> 直接修补DEX去广告 -> 打包 -> 签名 -> 导出。
     *
     * 优化功能：
     * - 全流程计时，各阶段耗时统计
     * - 原始APK体积与处理后体积对比
     * - 智能文件命名（包含包名和时间戳）
     * - 工作目录自动清理，避免缓存膨胀
     * - 处理完成后自动清理临时文件
     */
    private fun processApk(uri: Uri) {
        isProcessing = true
        logView.text = ""
        showProgress(true)
        log("▶ 开始处理 APK")

        // 处理期间保持屏幕常亮，防止处理中突然黑屏锁屏导致处理失败
        ScreenKeeper.setKeepScreenOn(this, true)

        val totalStartTime = System.currentTimeMillis()

        lifecycleScope.launch(Dispatchers.IO) {
            var workDir: File? = null
            try {
                workDir = File(cacheDir, "apk_work_${System.currentTimeMillis()}")
                workDir.mkdirs()

                // 1. 读取 APK
                val step1Start = System.currentTimeMillis()
                log("▶ 步骤 1/5 读取 APK")
                val sourceApk = File(workDir, "source.apk")
                contentResolver.openInputStream(uri)?.use { input ->
                    sourceApk.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("无法读取所选文件")

                val originalApkSize = sourceApk.length()
                // 获取APK基本信息
                val apkInfo = apkProcessor.getApkInfo(sourceApk)
                log("  ✓ ${sourceApk.name} | ${formatSize(originalApkSize)} | DEX=${apkInfo["dex_count"]} 资源=${apkInfo["res_count"]} 库=${apkInfo["lib_count"]} | ${elapsedMs(step1Start)}")

                // 2. 解包
                val step2Start = System.currentTimeMillis()
                log("▶ 步骤 2/5 解包")
                val extractDir = File(workDir, "extracted")
                extractDir.mkdirs()
                apkProcessor.extractApk(sourceApk, extractDir)

                val dexCount = extractDir.listFiles { f -> f.name.endsWith(".dex") }?.size ?: 0
                val totalFiles = extractDir.walkTopDown().filter { it.isFile }.count()
                log("  ✓ $totalFiles 文件 | $dexCount DEX | ${elapsedMs(step2Start)}")

                // 3. 直接修补DEX去广告
                log("▶ 步骤 3/5 去广告")
                var processingReport: ProcessingReport? = null
                try {
                    processingReport = AdRemover.removeAds(
                        extractDir, this@MainActivity
                    ) { msg ->
                        log(msg)
                    }
                } catch (e: OutOfMemoryError) {
                    log("  ✗ 内存不足: ${e.message}")
                    log("  · 建议: 减少同时处理的DEX大小或关闭其他应用后重试")
                    System.gc()
                } catch (e: Exception) {
                    log("  ✗ 去广告处理异常: ${e.message}")
                    log("  · 堆栈: ${e.stackTraceToString().take(200)}")
                }

                // 3.4 去签名效验（开启后自动跟随去广告流程执行）
                val signStart = System.currentTimeMillis()
                val signMode = PathPreferences.getSignRemovalMode(this@MainActivity)
                if (signMode != SignatureVerificationRemover.MODE_OFF) {
                    log("▶ 步骤 3.4/5 去签名效验")
                    val signModeName = if (signMode == SignatureVerificationRemover.MODE_ORIGINAL) {
                        "原包去除签名效验"
                    } else {
                        "普通去除签名效验"
                    }
                    log("  · 模式: $signModeName")
                    // 读取用户自定义的注入参数（原包路径 / 解压路径 / So库名 / 钩子类名 / 签名信息 / 入口名称）
                    val signOriginPath = PathPreferences.getSignOriginPath(this@MainActivity)
                    val signExtractPath = PathPreferences.getSignExtractPath(this@MainActivity)
                    val signSoName = PathPreferences.getSignSoName(this@MainActivity)
                    val signHookClass = PathPreferences.getSignHookClass(this@MainActivity)
                    val signInfo = PathPreferences.getSignInfo(this@MainActivity)
                    val signEntry = PathPreferences.getSignEntry(this@MainActivity)
                    if (signMode == SignatureVerificationRemover.MODE_ORIGINAL) {
                        log("  · 注入参数: 原包=$signOriginPath, 解压=$signExtractPath, So=$signSoName")
                    }
                    val signReport = SignatureVerificationRemover.removeSignatures(
                        this@MainActivity, extractDir, sourceApk, signMode, ::log,
                        signOriginPath, signExtractPath, signSoName, signHookClass,
                        signInfo, signEntry
                    )
                    processingReport?.signRemovalMode = signMode
                    processingReport?.originalSignerFingerprint = signReport.originalSignerFingerprint
                    processingReport?.signDexStats?.clear()
                    processingReport?.signDexStats?.addAll(signReport.dexStats)
                    log("  ✓ 注入 ${signReport.totalPatchedMethods} 个签名钩子 | ${signReport.totalPatchedDex} DEX | ${elapsedMs(signStart)}")
                } else {
                    log("  · 去签名效验未开启（可在设置中开启）")
                }

                // 3.5 Flutter libapp.so 解包 / 去广告 / 回编译
                val flutterStart = System.currentTimeMillis()
                if (PathPreferences.isFlutterLibappEnabled(this@MainActivity)) {
                    log("▶ 步骤 3.5/5 Flutter 处理")
                    val flutterConfig = AdPatternConfig.loadConfig(this@MainActivity)
                    val flutterResult = FlutterAdRemover.process(
                        extractDir, flutterConfig,
                        File(PathPreferences.getOutputDir(this@MainActivity)),
                        ::log
                    )
                    processingReport?.flutterDetected = flutterResult.detected
                    processingReport?.flutterStats = flutterResult.stats
                    if (flutterResult.detected) {
                        val totalRep = flutterResult.stats.sumOf { it.replacedCount }
                        log("  ✓ ${flutterResult.stats.size} 个 libapp.so | 替换 $totalRep 处 | ${elapsedMs(flutterStart)}")
                    }
                } else {
                    log("  · Flutter 处理已关闭（可在设置中开启）")
                }

                // 4. 打包并签名
                val step4Start = System.currentTimeMillis()
                log("▶ 步骤 4/5 打包签名")
                val unsignedApk = File(workDir, "unsigned.apk")
                apkProcessor.buildApk(extractDir, unsignedApk, logger = { msg ->
                    log(msg)
                })
                val unsignedSize = unsignedApk.length()
                log("  ✓ 打包 | ${formatSize(unsignedSize)}")

                // 检测嵌套 ZIP 子包，尝试数据复用优化（过签包场景，如 LSPatch 产物）
                val embeddedPaths = apkProcessor.lastEmbeddedApkPaths
                val bestHost = DataMultiplexingHelper.findBestHost(unsignedApk, embeddedPaths)
                val tempSigned: File
                if (bestHost != null) {
                    log("  ℹ 过签包结构，启用数据复用优化 (host: $bestHost)")
                    // 1. V1 签名：必须在优化前完成（优化不改变文件内容，V1 保持有效）
                    val v1Signed = File(workDir, "v1_signed.apk")
                    Signer.signApkV1(this@MainActivity, unsignedApk, v1Signed)
                    log("  ✓ V1 签名 | ${formatSize(v1Signed.length())}")
                    // 2. 数据复用优化：让过签包中与原包相同的文件复用原包数据段
                    val optimized = File(workDir, "optimized.apk")
                    val optimizedSize = DataMultiplexingHelper.optimize(
                        v1Signed, optimized, bestHost
                    ) { msg -> log(msg) }
                    if (optimizedSize != null) {
                        // 3. V2 签名：优化后必须用 V2V3SchemeSigner（apksig 会破坏复用优化）
                        Signer.signV2V3(this@MainActivity, optimized)
                        log("  ✓ V2 签名 | ${formatSize(optimized.length())}")
                        tempSigned = optimized
                    } else {
                        // 优化失败回退：直接用 apksig 常规 v1+v2 签名
                        log("  · 优化失败，回退常规 v1+v2 签名")
                        val fallback = File(workDir, "temp_signed.apk")
                        Signer.signApk(this@MainActivity, unsignedApk, fallback)
                        tempSigned = fallback
                    }
                } else {
                    log("  · 常规 v1+v2 签名")
                    val fallback = File(workDir, "temp_signed.apk")
                    Signer.signApk(this@MainActivity, unsignedApk, fallback)
                    tempSigned = fallback
                }
                val signedSize = tempSigned.length()
                log("  ✓ 签名 | ${formatSize(signedSize)} | ${elapsedMs(step4Start)}")

                // 导出：优先保存到所选 APK 所在目录，失败时回退到默认导出目录
                log("▶ 步骤 5/5 导出")

                // 生成输出文件名：原文件名_时间戳_clean.apk（clean 表示去广告处理后的产物）
                val displayName = queryDisplayName(uri) ?: "output"
                val baseName = displayName.substringBeforeLast('.').ifBlank { "output" }
                val exportTime = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(Date())
                val fileName = "${baseName}_${exportTime}_clean.apk"

                var finalSize = 0L
                var exportDesc = ""
                var exportedToSourceDir = false

                // 1. 优先解析原 APK 的真实文件系统路径，直接写回原包所在目录（最可靠）
                val sourcePath = queryRealPath(uri)
                if (sourcePath != null) {
                    val sourceFile = File(sourcePath)
                    val sourceDir = sourceFile.parentFile
                    if (sourceDir != null && sourceDir.exists() && sourceDir.canWrite()) {
                        val exportFile = File(sourceDir, fileName)
                        tempSigned.copyTo(exportFile, overwrite = true)
                        finalSize = exportFile.length()
                        exportDesc = exportFile.absolutePath
                        exportedToSourceDir = true
                        log("  ✓ 已导出: $exportDesc")
                    }
                }

                // 2. 真实路径不可用时，尝试通过 SAF 在所选 APK 的同目录创建输出文件
                if (!exportedToSourceDir) {
                    finalSize = 0L
                    exportDesc = ""
                    val exportedViaSaf = try {
                        val resultUri = createOutputInSelectedDir(uri, fileName)
                        if (resultUri != null) {
                            contentResolver.openOutputStream(resultUri)?.use { out ->
                                tempSigned.inputStream().use { it.copyTo(out) }
                            }
                            true
                        } else {
                            false
                        }
                    } catch (_: Exception) {
                        false
                    }
                    if (exportedViaSaf) {
                        finalSize = tempSigned.length()
                        exportDesc = docUriToReadablePath(uri, fileName)
                        exportedToSourceDir = true
                        log("  ✓ 已导出: $exportDesc")
                    }
                }

                // 3. 以上均不可用时，回退到用户自定义或默认导出目录
                if (!exportedToSourceDir) {
                    val exportDir = File(PathPreferences.getOutputDir(this@MainActivity))
                    if (!exportDir.exists()) exportDir.mkdirs()
                    val exportFile = File(exportDir, fileName)
                    tempSigned.copyTo(exportFile, overwrite = true)
                    finalSize = exportFile.length()
                    exportDesc = exportFile.absolutePath
                    log("  ✓ 已导出: $exportDesc")
                }

                // 生成 Markdown 处理报告（与处理后 APK 同目录保存）
                var reportPath: String? = null
                processingReport?.let { rep ->
                    rep.sourceApkName = displayName
                    rep.originalApkSize = originalApkSize
                    rep.finalApkSize = finalSize
                    try {
                        val reportDir = File(exportDesc).parentFile
                        if (reportDir != null && reportDir.exists()) {
                            val reportFile = File(reportDir, "${baseName}_${exportTime}_report.md")
                            reportFile.writeText(
                                ReportGenerator.generate(rep),
                                Charsets.UTF_8
                            )
                            reportPath = reportFile.absolutePath
                            log("  ✓ 报告: ${reportFile.absolutePath}")
                        }
                    } catch (e: Exception) {
                        log("  ⚠ 处理报告生成失败: ${e.message}")
                    }
                }

                val savedBytes = originalApkSize - finalSize
                val totalTime = System.currentTimeMillis() - totalStartTime

                // 汇总日志：路径在导出/报告生成时已打印过，这里只做精简汇总，避免重复
                log("▶ 处理完成")
                val sizeDesc = if (savedBytes > 0) {
                    "${formatSize(originalApkSize)} → ${formatSize(finalSize)} (节省 ${formatSize(savedBytes)})"
                } else {
                    "${formatSize(originalApkSize)} → ${formatSize(finalSize)}"
                }
                log("  ✓ $sizeDesc | 总耗时 ${String.format(Locale.US, "%.1f", totalTime / 1000.0)}s")

                withContext(Dispatchers.Main) {
                    showProgress(false)
                    showProcessDoneDialog(
                        exportDesc = exportDesc,
                        reportPath = reportPath,
                        originalApkSize = originalApkSize,
                        finalSize = finalSize,
                        savedBytes = savedBytes,
                        totalTime = totalTime
                    )
                }
            } catch (e: OutOfMemoryError) {
                log("▶ 处理失败: 内存不足")
                log("  ✗ ${e.message}")
                log("  · 建议: 该APK可能过大，请尝试关闭其他应用后重试")
                System.gc()
                withContext(Dispatchers.Main) {
                    showProgress(false)
                    UiUtils.error(this@MainActivity, "内存不足，处理失败")
                }
            } catch (e: StackOverflowError) {
                log("▶ 处理失败: 嵌套过深")
                log("  ✗ ${e.message}")
                withContext(Dispatchers.Main) {
                    showProgress(false)
                    UiUtils.error(this@MainActivity, "处理失败: 文件结构异常")
                }
            } catch (e: Exception) {
                log("▶ 处理失败")
                log("  ✗ ${e.message}")
                log("  · 堆栈: ${e.stackTraceToString().take(300)}")
                withContext(Dispatchers.Main) {
                    showProgress(false)
                    UiUtils.error(this@MainActivity, "处理失败: ${e.message}")
                }
            } finally {
                // 处理结束（无论成功或失败），恢复屏幕常亮 flag，允许正常锁屏
                ScreenKeeper.setKeepScreenOn(this@MainActivity, false)
                isProcessing = false

                // 清理工作目录，释放存储空间
                workDir?.let { dir ->
                    try {
                        dir.deleteRecursively()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    /**
     * 查询所选文件的显示名称（含扩展名）。
     */
    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 解析所选 uri 的真实文件系统路径（通过 _data 列）。
     * 仅当系统 ContentProvider 暴露该列（如部分文件管理器、SAF 内部存储）时才有值，
     * 否则返回 null，由调用方回退到 SAF 或默认目录。
     */
    private fun queryRealPath(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex("_data")
                    if (idx >= 0) c.getString(idx) else null
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 通过 SAF 在所选 APK 的父目录创建输出文件，返回写入用的 document uri。
     * 仅当所选 uri 是 document uri 且能解析出父目录时才有意义，否则返回 null。
     */
    private fun createOutputInSelectedDir(uri: Uri, fileName: String): Uri? {
        return try {
            if (!DocumentsContract.isDocumentUri(this, uri)) return null
            val docId = DocumentsContract.getDocumentId(uri)
            val slash = docId.lastIndexOf('/')
            if (slash <= 0) return null
            val parentDocId = docId.substring(0, slash)
            val parentUri = DocumentsContract.buildDocumentUri(uri.authority, parentDocId)
            if (parentUri == null) return null
            DocumentsContract.createDocument(
                contentResolver,
                parentUri,
                "application/vnd.android.package-archive",
                fileName
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 将所选 APK 的 document uri 解析为可读的文件系统路径（仅用于日志展示）。
     * 内部存储（primary）映射为 /storage/emulated/0，其余存储挂载点保守回退为 uri 字符串。
     */
    private fun docUriToReadablePath(uri: Uri, fileName: String): String {
        return try {
            if (DocumentsContract.isDocumentUri(this, uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val slash = docId.lastIndexOf('/')
                if (slash > 0) {
                    val parentDocId = docId.substring(0, slash)
                    if (parentDocId.startsWith("primary:")) {
                        val dir = parentDocId.substringAfter(':')
                        val base = if (dir.isEmpty()) "/storage/emulated/0" else "/storage/emulated/0/$dir"
                        return "$base/$fileName"
                    }
                }
            }
            uri.toString()
        } catch (_: Exception) {
            uri.toString()
        }
    }

    private fun elapsedMs(startTime: Long): String = "${System.currentTimeMillis() - startTime}ms"

    private fun formatSize(bytes: Long): String = Format.formatSize(bytes)

    /**
     * 显示处理完成的美化弹窗。
     *
     * 展示导出位置、处理报告路径与四项统计（原始/处理后/节省/耗时），
     * 使用渐变头部 + 统计卡片布局，与更新弹窗风格统一。
     */
    private fun showProcessDoneDialog(
        exportDesc: String,
        reportPath: String?,
        originalApkSize: Long,
        finalSize: Long,
        savedBytes: Long,
        totalTime: Long
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_process_done, null)

        dialogView.findViewById<TextView>(R.id.tvDoneExportPath).text = exportDesc

        // 处理报告（可选显示）
        val tvReportLabel = dialogView.findViewById<TextView>(R.id.tvDoneReportLabel)
        val tvReportPath = dialogView.findViewById<TextView>(R.id.tvDoneReportPath)
        if (reportPath != null) {
            tvReportLabel.visibility = View.VISIBLE
            tvReportPath.visibility = View.VISIBLE
            tvReportPath.text = reportPath
        }

        dialogView.findViewById<TextView>(R.id.tvDoneOriginalSize).text = formatSize(originalApkSize)
        dialogView.findViewById<TextView>(R.id.tvDoneFinalSize).text = formatSize(finalSize)
        dialogView.findViewById<TextView>(R.id.tvDoneSavedSize).text =
            if (savedBytes > 0) formatSize(savedBytes) else "0 B"
        dialogView.findViewById<TextView>(R.id.tvDoneTotalTime).text =
            String.format(Locale.US, "%.1f 秒", totalTime / 1000.0)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        )
        dialog.show()
        // 自适应屏幕：内容过长时限制高度并滚动，避免溢出屏幕
        UiUtils.fitDialogToScreen(dialog)

        dialogView.findViewById<MaterialButton>(R.id.btnDoneOk).setOnClickListener {
            dialog.dismiss()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_about -> {
                startActivity(Intent(this, AboutActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * 检测更新：在后台线程拉取版本信息，然后在 UI 线程展示结果。
     * 若有强制更新，UpdateChecker 会弹出不可取消的对话框。
     */
    private fun checkForUpdate() {
        UpdateChecker.checkForUpdate(this)
    }
}
