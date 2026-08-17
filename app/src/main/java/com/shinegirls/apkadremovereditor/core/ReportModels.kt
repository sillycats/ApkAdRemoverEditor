package com.shinegirls.apkadremovereditor.core

/**
 * 处理报告数据模型。
 *
 * 结构化的处理结果，供 UI 展示、Markdown 报告生成与断点续传基础设施复用。
 */

/**
 * 单个 DEX 文件的处理统计。
 *
 * @param skippedNoAd   无广告 DEX 自动跳过（未处理、未写回）
 * @param skippedNoChange 识别到广告特征但实际零修改，跳过最耗时的写回步骤
 */
data class DexProcessingStats(
    val name: String,
    val originalSize: Long = 0,
    val newSize: Long = 0,
    val skippedNoAd: Boolean = false,
    val skippedNoChange: Boolean = false,
    val patchedClasses: Int = 0,
    val neutralizedMethods: Int = 0,
    val neutralizedUrls: Int = 0,
    val forcedTrueMethods: Int = 0,
    val forcedFalseMethods: Int = 0,
    val failed: Boolean = false,
    val error: String? = null,
    val elapsedMs: Long = 0
) {
    /** 是否发生了任何实际修改（用于统计口径修正） */
    val changed: Boolean
        get() = patchedClasses > 0 || neutralizedMethods > 0 || neutralizedUrls > 0 ||
            forcedTrueMethods > 0 || forcedFalseMethods > 0
}

/**
 * 单个 Flutter libapp.so 的处理统计（解包 / 去广告 / 回编译）。
 *
 * @param replacedCount 快照内广告特征字符串被等长覆盖的总次数
 * @param matchedPatterns 命中的特征 -> 命中次数
 */
data class FlutterLibappStats(
    val abi: String = "",
    val path: String = "",
    val originalSize: Long = 0,
    val newSize: Long = 0,
    val snapshotCount: Int = 0,
    val stringsFound: Int = 0,
    val replacedCount: Int = 0,
    val matchedPatterns: Map<String, Int> = emptyMap(),
    val failed: Boolean = false,
    val error: String? = null,
    val elapsedMs: Long = 0
) {
    /** 是否发生了任何实际修改。 */
    val changed: Boolean get() = replacedCount > 0
}

/**
 * 将底层的 [LibappSoPatcher.PatchOutcome] 转换为报告模型 [FlutterLibappStats]。
 */
fun LibappSoPatcher.PatchOutcome.toReportStats(abi: String, path: String): FlutterLibappStats {
    return FlutterLibappStats(
        abi = abi,
        path = path,
        originalSize = originalSize,
        newSize = newSize,
        snapshotCount = snapshotCount,
        stringsFound = stringsFound,
        replacedCount = replacedCount,
        matchedPatterns = matchedPatterns,
        failed = failed,
        error = error,
        elapsedMs = elapsedMs
    )
}

/**
 * 断点续传基础设施：按阶段记录检查点（phase + 耗时 + 明细）。
 * 目前仅用于进度记录与报告输出，为后续断点恢复功能预留数据基础。
 */
data class CheckpointRecord(
    val phase: String,
    val detail: String = "",
    val elapsedMs: Long = 0
)

/**
 * 一次完整去广告处理的汇总报告。
 */
data class ProcessingReport(
    var sourceApkName: String = "",
    var originalApkSize: Long = 0,
    var finalApkSize: Long = 0,
    var totalTimeMs: Long = 0,
    var startedAt: String = "",
    var config: AdPatternConfig.AdPatterns? = null,
    var configFile: String = "",
    val dexStats: MutableList<DexProcessingStats> = mutableListOf(),
    var axmlRemovedComponents: Int = 0,
    var axmlRemovedPermissions: Int = 0,
    var cleanedSdkLibs: Int = 0,
    var cleanedSdkAssets: Int = 0,
    var cleanedRootFiles: Int = 0,
    var hiddenLayoutViews: Int = 0,
    val checkpoints: MutableList<CheckpointRecord> = mutableListOf(),
    var flutterDetected: Boolean = false,
    var flutterStats: MutableList<FlutterLibappStats> = mutableListOf()
) {
    // ===== 汇总口径（统计修正：仅统计实际发生的修改） =====
    val totalPatchedClasses: Int get() = dexStats.sumOf { it.patchedClasses }
    val totalNeutralizedMethods: Int get() = dexStats.sumOf { it.neutralizedMethods }
    val totalNeutralizedUrls: Int get() = dexStats.sumOf { it.neutralizedUrls }
    val totalForcedTrueMethods: Int get() = dexStats.sumOf { it.forcedTrueMethods }
    val totalForcedFalseMethods: Int get() = dexStats.sumOf { it.forcedFalseMethods }
    val totalSkippedDex: Int get() = dexStats.count { it.skippedNoAd || it.skippedNoChange }
    val totalFailedDex: Int get() = dexStats.count { it.failed }
    // ===== Flutter 汇总口径 =====
    val totalFlutterLibapps: Int get() = flutterStats.size
    val totalFlutterReplaced: Int get() = flutterStats.sumOf { it.replacedCount }
    val totalFlutterFailed: Int get() = flutterStats.count { it.failed }
}