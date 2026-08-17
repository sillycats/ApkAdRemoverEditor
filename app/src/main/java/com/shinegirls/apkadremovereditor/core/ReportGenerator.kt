package com.shinegirls.apkadremovereditor.core

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

    private fun formatMs(ms: Long): String {
        return if (ms >= 1000) String.format(Locale.US, "%.2f秒", ms / 1000.0) else "${ms}ms"
    }

    /**
     * 渲染完整 Markdown 报告。
     */
    fun generate(report: ProcessingReport): String {
        val sb = StringBuilder()

        sb.appendLine("# APK 广告移除处理报告")
        sb.appendLine()
        sb.appendLine("- **源 APK**: `${report.sourceApkName}`")
        sb.appendLine("- **处理时间**: ${report.startedAt}")
        report.configFile.let { if (it.isNotBlank()) sb.appendLine("- **特征配置文件**: `$it`") }
        sb.appendLine()

        // ===== 体积与耗时对比 =====
        sb.appendLine("## 处理前后对比")
        sb.appendLine()
        sb.appendLine("| 项目 | 原始 | 处理后 | 变化 |")
        sb.appendLine("| --- | --- | --- | --- |")
        val sizeDiff = report.finalApkSize - report.originalApkSize
        val sizeDiffText = when {
            sizeDiff > 0 -> "+${formatSize(sizeDiff)}"
            sizeDiff < 0 -> "-${formatSize(-sizeDiff)}"
            else -> "持平"
        }
        sb.appendLine("| APK 体积 | ${formatSize(report.originalApkSize)} | ${formatSize(report.finalApkSize)} | $sizeDiffText |")
        sb.appendLine("| 总耗时 | - | ${formatMs(report.totalTimeMs)} | - |")
        sb.appendLine()

        // ===== 汇总统计 =====
        sb.appendLine("## 处理汇总")
        sb.appendLine()
        sb.appendLine("| 统计项 | 数量 |")
        sb.appendLine("| --- | --- |")
        sb.appendLine("| 广告 SDK 类置空 | ${report.totalPatchedClasses} |")
        sb.appendLine("| 广告方法置空 | ${report.totalNeutralizedMethods} |")
        sb.appendLine("| 广告链接置空 | ${report.totalNeutralizedUrls} |")
        sb.appendLine("| 强制返回 true | ${report.totalForcedTrueMethods} |")
        sb.appendLine("| 强制返回 false | ${report.totalForcedFalseMethods} |")
        sb.appendLine("| AXML 广告组件移除 | ${report.axmlRemovedComponents} |")
        sb.appendLine("| AXML 广告权限移除 | ${report.axmlRemovedPermissions} |")
        sb.appendLine("| 广告 SDK 库文件清理 | ${report.cleanedSdkLibs} |")
        sb.appendLine("| assets 广告文件清理 | ${report.cleanedSdkAssets} |")
        sb.appendLine("| 根目录广告文件清理 | ${report.cleanedRootFiles} |")
        sb.appendLine("| Res 广告布局隐藏 | ${report.hiddenLayoutViews} |")
        sb.appendLine("| Flutter libapp.so 处理 | ${report.totalFlutterLibapps} 个库, 替换 ${report.totalFlutterReplaced} 处 |")
        sb.appendLine("| 跳过 DEX 数 | ${report.totalSkippedDex} |")
        sb.appendLine("| 失败 DEX 数 | ${report.totalFailedDex} |")
        sb.appendLine()

        // ===== 每个 DEX 的修改统计 =====
        sb.appendLine("## 各 DEX 处理明细")
        sb.appendLine()
        if (report.dexStats.isEmpty()) {
            sb.appendLine("未发现 DEX 文件或未处理。")
            sb.appendLine()
        } else {
            sb.appendLine("| DEX | 处理前 | 处理后 | 广告类 | 方法置空 | 链接置空 | 强制true | 强制false | 耗时 | 状态 |")
            sb.appendLine("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
            for (stat in report.dexStats) {
                val status = when {
                    stat.failed -> "⚠️ 失败"
                    stat.skippedNoAd -> "⏭ 无广告跳过"
                    stat.skippedNoChange -> "⏭ 零修改跳过"
                    stat.changed -> "✅ 已修改"
                    else -> "—"
                }
                sb.appendLine(
                    "| `${stat.name}` | ${formatSize(stat.originalSize)} | ${formatSize(stat.newSize)} | " +
                        "${stat.patchedClasses} | ${stat.neutralizedMethods} | ${stat.neutralizedUrls} | " +
                        "${stat.forcedTrueMethods} | ${stat.forcedFalseMethods} | ${formatMs(stat.elapsedMs)} | $status |"
                )
            }
            sb.appendLine()
        }

        // ===== Flutter libapp.so 处理明细 =====
        sb.appendLine("## Flutter libapp.so 处理（解包 / 去广告 / 回编译）")
        sb.appendLine()
        if (!report.flutterDetected) {
            sb.appendLine("未检测到 Flutter 应用（无 libapp.so），本项不适用。")
            sb.appendLine()
        } else if (report.flutterStats.isEmpty()) {
            sb.appendLine("已检测到 Flutter 应用，但未生成处理明细。")
            sb.appendLine()
        } else {
            sb.appendLine("| ABI | 处理前 | 处理后 | 快照数 | 字符串 | 替换次数 | 命中特征 | 耗时 | 状态 |")
            sb.appendLine("| --- | --- | --- | --- | --- | --- | --- | --- | --- |")
            for (s in report.flutterStats) {
                val status = when {
                    s.failed -> "⚠️ 失败"
                    s.changed -> "✅ 已修改"
                    else -> "⏭ 零修改"
                }
                val topPatterns = s.matchedPatterns.entries
                    .sortedByDescending { it.value }.take(3)
                    .joinToString(", ") { "${it.key}×${it.value}" }
                sb.appendLine(
                    "| `${s.abi}` | ${formatSize(s.originalSize)} | ${formatSize(s.newSize)} | " +
                        "${s.snapshotCount} | ${s.stringsFound} | ${s.replacedCount} | " +
                        "${topPatterns.ifEmpty { "—" }} | ${formatMs(s.elapsedMs)} | $status |"
                )
            }
            sb.appendLine()
        }

        // ===== 断点续传检查点 =====
        sb.appendLine("## 处理检查点（断点续传基础设施）")
        sb.appendLine()
        if (report.checkpoints.isEmpty()) {
            sb.appendLine("无检查点记录。")
        } else {
            sb.appendLine("| 阶段 | 明细 | 耗时 |")
            sb.appendLine("| --- | --- | --- |")
            for (cp in report.checkpoints) {
                sb.appendLine("| ${cp.phase} | ${cp.detail} | ${formatMs(cp.elapsedMs)} |")
            }
        }
        sb.appendLine()

        return sb.toString()
    }
}