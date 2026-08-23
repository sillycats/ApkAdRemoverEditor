package com.shinegirls.apkadremovereditor.core

import android.content.Context
import com.shinegirls.apkadremovereditor.R
import com.shinegirls.apkadremovereditor.utils.Format
import java.util.Locale

/**
 * 处理报告生成器：将 [ProcessingReport] 渲染为 Markdown 格式文本。
 *
 * 报告内容（与处理后 APK 同目录保存）：
 * - 处理前后体积与耗时对比
 * - 每个 DEX 的修改统计（含跳过标记）
 * - 断点续传检查点（阶段耗时）
 *
 * 注意：报告不包含广告特征识别清单（SDK包名 / 类名关键词 / 方法名 / URL 等），
 * 避免泄露去广告特征配置内容。
 */
object ReportGenerator {

    private fun formatSize(bytes: Long): String = Format.formatSize(bytes)

    private fun formatMs(context: Context, ms: Long): String {
        return if (ms >= 1000) String.format(Locale.US, context.getString(R.string.h_ae4e6627), ms / 1000.0) else "${ms}ms"
    }

    /**
     * 渲染完整 Markdown 报告。
     */
    fun generate(context: Context, report: ProcessingReport): String {
        val sb = StringBuilder()

        sb.appendLine(context.getString(R.string.h_d9f1a2ee))
        sb.appendLine()
        sb.appendLine(context.getString(R.string.h_c11c4e64, report.sourceApkName))
        sb.appendLine(context.getString(R.string.h_bb543276, report.startedAt))
        report.configFile.let { if (it.isNotBlank()) sb.appendLine(context.getString(R.string.h_8fa638b6, it)) }
        sb.appendLine()

        // ===== 体积与耗时对比 =====
        sb.appendLine(context.getString(R.string.h_c6e12265))
        sb.appendLine()
        sb.appendLine(context.getString(R.string.h_74f74cf4))
        sb.appendLine("| --- | --- | --- | --- |")
        val sizeDiff = report.finalApkSize - report.originalApkSize
        val sizeDiffText = when {
            sizeDiff > 0 -> "+${formatSize(sizeDiff)}"
            sizeDiff < 0 -> "-${formatSize(-sizeDiff)}"
            else -> context.getString(R.string.h_33b16185)
        }
        sb.appendLine(
            context.getString(
                R.string.h_0078e971,
                formatSize(report.originalApkSize),
                formatSize(report.finalApkSize),
                sizeDiffText
            )
        )
        sb.appendLine(context.getString(R.string.h_0148e9c3, formatMs(context, report.totalTimeMs)))
        sb.appendLine()

        // ===== 汇总统计 =====
        sb.appendLine(context.getString(R.string.h_72444798))
        sb.appendLine()
        sb.appendLine(context.getString(R.string.h_6af466b2))
        sb.appendLine("| --- | --- |")
        sb.appendLine(context.getString(R.string.h_6dedd2e5, report.totalPatchedClasses))
        sb.appendLine(context.getString(R.string.h_0e655b36, report.totalNeutralizedMethods))
        sb.appendLine(context.getString(R.string.h_4a804b6d, report.totalNeutralizedUrls))
        sb.appendLine(context.getString(R.string.h_41d17685, report.totalNeutralizedStrings))
        sb.appendLine(context.getString(R.string.h_6d2ef53d, report.totalForcedTrueMethods))
        sb.appendLine(context.getString(R.string.h_1d9b17d9, report.totalForcedFalseMethods))
        sb.appendLine(context.getString(R.string.h_0c374516, report.axmlRemovedComponents))
        sb.appendLine(context.getString(R.string.h_9a0ee6cf, report.axmlRemovedPermissions))
        sb.appendLine(context.getString(R.string.h_7336558f, report.cleanedSdkLibs))
        sb.appendLine(context.getString(R.string.h_7c417117, report.cleanedSdkAssets))
        sb.appendLine(context.getString(R.string.h_941cd4b5, report.cleanedRootFiles))
        sb.appendLine(context.getString(R.string.h_0c258295, report.hiddenLayoutViews))
        sb.appendLine(
            context.getString(
                R.string.h_34e480ea,
                report.totalFlutterLibapps,
                report.totalFlutterReplaced
            )
        )
        if (report.signRemovalEnabled) {
            val modeText = if (report.signRemovalMode == SignatureVerificationRemover.MODE_ORIGINAL) context.getString(R.string.h_8e8a7f64) else context.getString(R.string.h_ad4097b5)
            sb.appendLine(
                context.getString(
                    R.string.h_6e1073d7,
                    modeText,
                    report.totalSignPatchedMethods,
                    report.totalSignPatchedDex
                )
            )
            if (report.originalSignerFingerprint.isNotBlank()) {
                sb.appendLine(
                    context.getString(
                        R.string.h_99f18050,
                        report.originalSignerFingerprint.take(16)
                    )
                )
            }
        }
        sb.appendLine(context.getString(R.string.h_01ee9186, report.totalSkippedDex))
        sb.appendLine(context.getString(R.string.h_16a1939f, report.totalFailedDex))
        sb.appendLine()

        // ===== 每个 DEX 的修改统计 =====
        sb.appendLine(context.getString(R.string.h_7d0b10f4))
        sb.appendLine()
        if (report.dexStats.isEmpty()) {
            sb.appendLine(context.getString(R.string.h_364d2bba))
            sb.appendLine()
        } else {
            sb.appendLine(context.getString(R.string.h_78e42143))
            sb.appendLine("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
            for (stat in report.dexStats) {
                val status = when {
                    stat.failed -> context.getString(R.string.h_f9bb4128)
                    stat.skippedNoAd -> context.getString(R.string.h_ee4d78ba)
                    stat.skippedNoChange -> context.getString(R.string.h_542664ea)
                    stat.changed -> context.getString(R.string.h_21be04bc)
                    else -> "—"
                }
                sb.appendLine(
                    "| `${stat.name}` | ${formatSize(stat.originalSize)} | ${formatSize(stat.newSize)} | " +
                        "${stat.patchedClasses} | ${stat.neutralizedMethods} | ${stat.neutralizedUrls} | " +
                        "${stat.forcedTrueMethods} | ${stat.forcedFalseMethods} | ${formatMs(context, stat.elapsedMs)} | $status |"
                )
            }
            sb.appendLine()
        }

        // ===== Flutter libapp.so 处理明细 =====
        sb.appendLine(context.getString(R.string.h_e5029582))
        sb.appendLine()
        if (!report.flutterDetected) {
            sb.appendLine(context.getString(R.string.h_02b610da))
            sb.appendLine()
        } else if (report.flutterStats.isEmpty()) {
            sb.appendLine(context.getString(R.string.h_80928937))
            sb.appendLine()
        } else {
            sb.appendLine(context.getString(R.string.h_cd369de4))
            sb.appendLine("| --- | --- | --- | --- | --- | --- | --- | --- | --- |")
            for (s in report.flutterStats) {
                val status = when {
                    s.failed -> context.getString(R.string.h_f9bb4128)
                    s.changed -> context.getString(R.string.h_21be04bc)
                    else -> context.getString(R.string.h_1716f0ff)
                }
                val topPatterns = s.matchedPatterns.entries
                    .sortedByDescending { it.value }.take(3)
                    .joinToString(", ") { "${it.key}×${it.value}" }
                sb.appendLine(
                    "| `${s.abi}` | ${formatSize(s.originalSize)} | ${formatSize(s.newSize)} | " +
                        "${s.snapshotCount} | ${s.stringsFound} | ${s.replacedCount} | " +
                        "${topPatterns.ifEmpty { "—" }} | ${formatMs(context, s.elapsedMs)} | $status |"
                )
            }
            sb.appendLine()
        }

        // ===== 断点续传检查点 =====
        sb.appendLine(context.getString(R.string.h_0b849763))
        sb.appendLine()
        if (report.checkpoints.isEmpty()) {
            sb.appendLine(context.getString(R.string.h_bfd50952))
        } else {
            sb.appendLine(context.getString(R.string.h_fe1aa884))
            sb.appendLine("| --- | --- | --- |")
            for (cp in report.checkpoints) {
                sb.appendLine("| ${cp.phase} | ${cp.detail} | ${formatMs(context, cp.elapsedMs)} |")
            }
        }
        sb.appendLine()

        return sb.toString()
    }
}