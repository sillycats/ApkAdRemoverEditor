package com.shinegirls.apkadremovereditor.core

import bin.zip.ZipFile
import bin.zip.ZipMaker
import com.shinegirls.apkadremovereditor.utils.Format
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.Locale

/**
 * 数据复用优化（DataMultiplexing）的 Kotlin 封装。
 *
 * 原理（移植自开源项目 ApkDataMultiplexing，https://github.com/L-JINBIN/ApkDataMultiplexing）：
 * 过签包（如 LSPatch 产物）在 assets/base.apk 内置原包，过签包中与原包完全相同的文件
 * （res 目录、lib 目录等）会重复存储一份。优化时让过签包中央目录的数据偏移指向原包内的
 * 数据段，删除重复数据段，从而显著减小体积（最多约 50%）。
 *
 * 前提：原包（host entry）必须以 STORED 方式打包（ApkProcessor 已保证）。
 * 签名：优化后必须使用 V2V3SchemeSigner 签名，apksig 会破坏复用优化。
 */
object DataMultiplexingHelper {

    /**
     * 从候选嵌套子包路径中找出"最佳 host"（即与原包相同文件最多的子包）。
     * 返回 null 表示没有可复用的子包（非过签包，无需优化）。
     */
    fun findBestHost(apkFile: File, candidates: List<String>): String? {
        if (candidates.isEmpty()) return null
        var bestHost: String? = null
        var bestCount = 0
        for (candidate in candidates) {
            try {
                val count = countChildren(apkFile, candidate)
                if (count > bestCount) {
                    bestCount = count
                    bestHost = candidate
                }
            } catch (_: Exception) {
                // 该子包无法作为 host（非 STORED 或损坏），跳过
            }
        }
        return if (bestCount > 0) bestHost else null
    }

    /**
     * 统计过签包中与指定 host 子包完全相同的条目数。
     * 逻辑移植自 DataMultiplexing.collectChildren。
     */
    fun countChildren(apkFile: File, hostPath: String): Int {
        var count = 0
        try {
            ZipFile(apkFile).use { outer ->
                val hostEntry = outer.getEntry(hostPath)
                if (hostEntry != null && hostEntry.method == ZipMaker.METHOD_STORED) {
                    outer.openEntryAsZipFile(hostEntry).use { inner ->
                        val outerEntries = outer.getEntries()
                        for (outerEntry in outerEntries) {
                            if (outerEntry === hostEntry || outerEntry.isDirectory) continue
                            val innerEntry = inner.getEntry(outerEntry.name) ?: continue
                            if (outerEntry.method != innerEntry.method) continue
                            if (outerEntry.crc != innerEntry.crc) continue
                            if (outerEntry.size != innerEntry.size) continue
                            // 注意：commentData 在无注释条目时为 null，必须用 Arrays.equals（兼容 null）
                            if (!java.util.Arrays.equals(outerEntry.commentData, innerEntry.commentData)) continue
                            // STORED 条目必须满足对齐要求，否则复用后安装会失败
                            if (innerEntry.method == ZipMaker.METHOD_STORED) {
                                val name = innerEntry.name
                                if (name == "resources.arsc" && innerEntry.getDataOffset() % 4 != 0L) continue
                                if (name.endsWith(".so") && innerEntry.getDataOffset() % 4096 != 0L) continue
                            }
                            val equals = (outerEntry.getCompressedSize() == innerEntry.getCompressedSize() &&
                                    streamEquals(inner.getRawInputStream(innerEntry), outer.getRawInputStream(outerEntry))) ||
                                    streamEquals(inner.getInputStream(innerEntry), outer.getInputStream(outerEntry))
                            if (equals) count++
                        }
                    }
                }
            }
        } catch (_: Exception) {
            return 0
        }
        return count
    }

    /**
     * 执行数据复用优化：input -> output，host 为原包路径。
     * 返回优化后文件大小；失败返回 null。
     */
    fun optimize(
        input: File,
        output: File,
        hostPath: String,
        logger: (String) -> Unit
    ): Long? {
        return try {
            val before = input.length()
            bin.zip.DataMultiplexing.optimize(input, output, hostPath, false)
            val after = output.length()
            val saved = before - after
            logger("  ℹ 数据复用优化完成: ${Format.formatSize(before)} -> ${Format.formatSize(after)}")
            if (before > 0) {
                logger("  · 复用原包($hostPath)数据段，节省 ${Format.formatSize(saved)} " +
                        "(${String.format(Locale.US, "%.1f", saved * 100.0 / before)}%)")
            }
            after
        } catch (e: Exception) {
            logger("  ✗ 数据复用优化失败: ${e.message}")
            null
        }
    }

    private fun streamEquals(a: InputStream, b: InputStream): Boolean {
        val ba = if (a is BufferedInputStream) a else BufferedInputStream(a)
        val bb = if (b is BufferedInputStream) b else BufferedInputStream(b)
        var ch = ba.read()
        while (ch != -1) {
            if (ch != bb.read()) return false
            ch = ba.read()
        }
        return bb.read() == -1
    }
}
