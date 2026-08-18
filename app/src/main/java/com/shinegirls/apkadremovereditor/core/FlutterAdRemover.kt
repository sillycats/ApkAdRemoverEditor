package com.shinegirls.apkadremovereditor.core

import com.shinegirls.apkadremovereditor.utils.Format
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Flutter 应用 libapp.so 一键处理编排器。
 *
 * 完整流程（与 DEX 模式并行，自动检测 Flutter 应用）：
 *   1. 解包：从 lib/<abi>/libapp.so 中定位 Dart 数据快照，并导出快照原始文件与字符串清单；
 *   2. 去广告：对快照内的广告特征字符串做等长 NUL 覆盖（见 [LibappSoPatcher]）；
 *   3. 回编译：把修补后的快照整体写回，生成新的 libapp.so（大小与 ELF 布局不变）；
 *   4. 自动保存：新 libapp.so 写回 APK 解包目录（随 APK 打包）；
 *   5. 清理缓存：删除导出的 .snapshot 快照与回编译副本，仅保留 strings.txt 字符串清单。
 *
 * 使用到的广告特征来自 [AdPatternConfig]（用户可在设置中修改"广告特征"），
 * 主要取 URL/域名、广告 SDK 包名、类名关键词等 ASCII 字符串分类。
 */
object FlutterAdRemover {

    /** Flutter 处理汇总结果。 */
    data class FlutterResult(
        val stats: MutableList<FlutterLibappStats> = mutableListOf(),
        var detected: Boolean = false
    )

    /**
     * 扫描 APK 解包目录，处理所有 libapp.so。
     *
     * @param extractDir APK 解包目录（libapp.so 处理结果会写回此处）
     * @param config     广告特征配置
     * @param exportDir  Flutter 解包/回编译产物的导出根目录
     */
    fun process(
        extractDir: File,
        config: AdPatternConfig.AdPatterns,
        exportDir: File,
        logger: Logger? = null
    ): FlutterResult {
        val log = logger ?: {}
        val result = FlutterResult()

        val libDir = File(extractDir, "lib")
        if (!libDir.isDirectory) {
            log("  · 未找到 lib 目录，Flutter 处理跳过")
            return result
        }
        val libappFiles = libDir.walkTopDown()
            .filter { it.isFile && it.name.equals("libapp.so", ignoreCase = true) }
            .toList()
        if (libappFiles.isEmpty()) {
            log("  · 未检测到 libapp.so，该应用可能不是 Flutter（或为 AAB 拆分包），Flutter 处理跳过")
            return result
        }

        result.detected = true
        log("  ✓ 检测到 ${libappFiles.size} 个 libapp.so: " +
            libappFiles.joinToString { it.parentFile?.name ?: "?" })

        val patterns = LibappSoPatcher.buildPatterns(buildPatternList(config))
        if (patterns.isEmpty()) {
            log("  ⚠ 无可用于 Flutter 的广告特征（需为 length>=2 的 ASCII 字符串），仅执行解包，不做修改")
        } else {
            log("  · Flutter 特征 ${patterns.size} 条（URL/包名/类名等 ASCII 字符串）")
        }

        val flutterExportDir = File(exportDir, "flutter")
        for (libapp in libappFiles) {
            val abi = libapp.parentFile?.name ?: "lib"
            val relative = libDir.toURI().relativize(libapp.toURI()).path
            val bitness = detectBitness(libapp)
            log("  ▶ 处理 ${relative} (${Format.formatSize(libapp.length())}, ${bitness})")

            // 1. 解包：导出快照原始文件与字符串清单（含去广告前的特征视图）
            val abiExport = File(flutterExportDir, abi)
            extractForInspection(libapp, abiExport, log)

            // 2+3. 去广告 + 回编译：就地等长打补丁并写回临时文件
            val tmp = File(libapp.parentFile, "libapp_patched.so")
            val r = LibappSoPatcher.processLibapp(libapp, tmp, patterns, log)

            // 4. 自动保存：写回 APK 解包目录（随 APK 重打包）
            if (!r.failed && r.changed) {
                tmp.copyTo(libapp, overwrite = true)
                log("  ✓ ${relative} 回编译完成: 快照=${r.snapshotCount}, " +
                    "字符串=${r.stringsFound}, 广告特征替换=${r.replacedCount} 处")
                if (r.matchedPatterns.isNotEmpty()) {
                    val top = r.matchedPatterns.entries.sortedByDescending { it.value }.take(5)
                    log("  · 命中特征: " + top.joinToString(" | ") { "${it.key}×${it.value}" })
                }
            } else if (r.failed) {
                log("  ✗ ${relative} 处理失败: ${r.error}")
            } else {
                tmp.delete()
                log("  ℹ ${relative} 未命中任何广告特征，保留原样")
            }

            result.stats.add(r.toReportStats(abi, relative))
        }

        // 5. 清理 Flutter 快照缓存（导出的 .snapshot 原始快照文件）
        cleanupSnapshotCache(flutterExportDir, log)
        return result
    }

    /** 处理完成后清理 Flutter 产物缓存，仅保留 strings.txt（字符串清单），删除快照与回编译副本。 */
    private fun cleanupSnapshotCache(flutterExportDir: File, log: Logger) {
        if (!flutterExportDir.isDirectory) return
        var removed = 0
        try {
            flutterExportDir.walkBottomUp().forEach { f ->
                if (f.isFile) {
                    val keep = f.name.equals("strings.txt", ignoreCase = true)
                    if (!keep && f.delete()) removed++
                }
            }
            // 清理后删除已空的 ABI 子目录（仅剩 strings.txt 的目录保留）
            flutterExportDir.listFiles()?.forEach { sub ->
                if (sub.isDirectory && sub.listFiles()?.isEmpty() == true) {
                    sub.delete()
                }
            }
            if (removed > 0) {
                log("  ✓ 已清理 Flutter 缓存: 删除 $removed 个文件，仅保留 strings.txt")
            }
        } catch (e: Exception) {
            log("  ⚠ 清理 Flutter 缓存失败: ${e.message}")
        }
    }

    /** 汇总可用于 Flutter 快照字符串匹配的广告特征。 */
    private fun buildPatternList(config: AdPatternConfig.AdPatterns): List<String> {
        // 用户自定义的 Flutter 字符串特征优先；为空时回退到 DEX 广告特征（向后兼容）
        if (config.flutterPatterns.isNotEmpty()) {
            return config.flutterPatterns.distinct()
        }
        return buildList {
            addAll(config.urlPatterns)          // 广告 URL/域名
            addAll(config.sdkPackages)          // 广告 SDK 包名（Dart 字符串保留点号形式）
            addAll(config.classKeywords)        // 广告类名关键词
            addAll(config.adViewNames)
            addAll(config.adActivities)
            addAll(config.adServices)
            addAll(config.adReceivers)
            addAll(config.assetKeywords)
            addAll(config.libFileKeywords)
            addAll(config.rootFileKeywords)
            addAll(config.resLayoutKeywords)
        }.distinct()
    }

    /** 通过 ELF 头判断 libapp.so 的位数，用于日志展示。 */
    private fun detectBitness(libapp: File): String {
        return try {
            val data = libapp.readBytes()
            val p = if (data.size >= 6 &&
                data[0] == 0x7F.toByte() && data[1] == 0x45.toByte() &&
                data[2] == 0x4C.toByte() && data[3] == 0x46.toByte()
            ) {
                val eiClass = data[4].toInt() and 0xFF
                if (eiClass == 1) 4 else 8
            } else 8
            "${p * 8}-bit"
        } catch (_: Exception) {
            "?"
        }
    }

    /** 解包：导出快照原始文件（.snapshot）与字符串清单（strings.txt）到导出目录。 */
    private fun extractForInspection(libapp: File, outDir: File, log: Logger) {
        try {
            val data = libapp.readBytes()
            val blobs = DartSnapshot.findBlobs(data)
            if (blobs.isEmpty()) {
                log("  · 解包：未找到 Dart 快照")
                return
            }
            outDir.mkdirs()
            for ((i, blob) in blobs.withIndex()) {
                val snapshotOut = File(outDir, "snapshot_${i}_${blob.version}.snapshot")
                data.copyOfRange(blob.offset, blob.offset + blob.size).let { snapshotOut.writeBytes(it) }
            }
            val strings = blobs.flatMap { DartSnapshot.extractStrings(data, it) }.distinct()
            val sb = StringBuilder()
            sb.appendLine("# libapp.so Dart 快照字符串导出")
            sb.appendLine("# 工具: ApkAdRemoverEditor · Flutter 解包")
            sb.appendLine("# 快照数: ${blobs.size}")
            sb.appendLine("# 字符串总数(去重): ${strings.size}")
            sb.appendLine()
            strings.forEach { sb.appendLine(it) }
            val stringsOut = File(outDir, "strings.txt")
            stringsOut.writeText(sb.toString(), StandardCharsets.UTF_8)
            log("  · 解包完成: ${blobs.size} 个快照, ${strings.size} 条字符串 → ${outDir.absolutePath}")
        } catch (e: Exception) {
            log("  ⚠ 解包 ${libapp.name} 失败: ${e.message}")
        }
    }
}