package com.shinegirls.apkadremovereditor.core

import com.shinegirls.apkadremovereditor.R
import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Flutter libapp.so 去广告 + 回编译核心。
 *
 * 原理：`libapp.so` 内的 Dart AOT 数据快照以【原始字节】保存了广告相关字符串
 * （广告 SDK 包名、广告 URL/域名、广告类名关键词等）。本类在数据快照区域内定位这些
 * 字符串，并做【等长覆盖】：把命中的字节替换为等长的 NUL(0x00) 填充。
 *
 * 之所以采用"等长覆盖"而非"截断/改长度"：
 * - Dart 字符串对象头中记录了长度，截短需要改长度字段，且各 Dart 版本对象布局不同，极脆弱；
 * - 等长覆盖后字符串内容被抹除（应用内的 contains/equals/URL 请求将不再命中或失效），
 *   而字符串长度与快照/ELF 布局完全不变，因而【无需修改 ELF 节区布局】，
 *   即可在原偏移处直接写回，得到仍可正常运行的新 libapp.so —— 即"回编译"。
 */
object LibappSoPatcher {

    /**
     * 单份 libapp.so 的处理结果。
     * 与 [FlutterLibappStats] 结构一致，便于上层复用。
     */
    data class PatchOutcome(
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
        val changed: Boolean get() = replacedCount > 0
    }

    /**
     * 将广告特征字符串转为可匹配的 ASCII 字节序列。
     * 仅保留长度 >= 2 且全部为可打印 ASCII 的项（非 ASCII 特征无法在快照中以单字节定位）。
     */
    fun buildPatterns(patterns: List<String>): List<ByteArray> {
        return patterns
            .map { it.trim() }
            .filter { it.length >= 2 && it.all { ch -> ch.code in 33..126 } }
            .map { it.toByteArray(StandardCharsets.US_ASCII) }
    }

    /**
     * 在指定快照 blob 区域内做等长 NUL 覆盖。
     *
     * @return 替换总次数与命中的特征映射（特征 -> 命中次数）
     */
    fun patchBlob(data: ByteArray, blob: DartSnapshot.SnapshotBlob, patterns: List<ByteArray>): PatchOutcome {
        val start = blob.offset
        val end = blob.offset + blob.size
        var replaced = 0
        val matched = LinkedHashMap<String, Int>()
        for (pat in patterns) {
            if (pat.size > blob.size) continue
            var count = 0
            var idx = start
            while (true) {
                val hit = DartSnapshot.indexOf(data, pat, idx, end)
                if (hit < 0) break
                // 等长覆盖为 NUL
                for (i in 0 until pat.size) data[hit + i] = 0
                count++
                replaced++
                idx = hit + pat.size
            }
            if (count > 0) {
                matched[String(pat, StandardCharsets.US_ASCII)] = count
            }
        }
        return PatchOutcome(replacedCount = replaced, matchedPatterns = matched)
    }

    /**
     * 处理单个 libapp.so：定位全部数据快照 → 提取字符串 → 等长打补丁 →
     * 原样写回 [output] 文件（快照大小不变，故 ELF 布局与文件大小均不变）。
     *
     * @param input  解包目录中的原始 libapp.so
     * @param output 回编译输出的新 libapp.so（可能为临时文件）
     */
    fun processLibapp(
        input: File,
        output: File,
        patterns: List<ByteArray>,
        context: Context,
        logger: Logger? = null
    ): PatchOutcome {
        val log = logger ?: {}
        val start = System.currentTimeMillis()
        val originalSize = input.length()
        return try {
            val data = input.readBytes()
            val blobs = DartSnapshot.findBlobs(data)
            if (blobs.isEmpty()) {
                log(context.getString(R.string.h_1cb37f65, input.name))
                return PatchOutcome(
                    originalSize, originalSize, failed = true,
                    error = context.getString(R.string.h_40d44b02), elapsedMs = System.currentTimeMillis() - start
                )
            }

            var totalReplaced = 0
            val totalMatched = LinkedHashMap<String, Int>()
            var totalStrings = 0
            for (blob in blobs) {
                totalStrings += DartSnapshot.extractStrings(data, blob).size
                val res = patchBlob(data, blob, patterns)
                totalReplaced += res.replacedCount
                for ((k, v) in res.matchedPatterns) totalMatched[k] = (totalMatched[k] ?: 0) + v
            }

            output.parentFile?.mkdirs()
            output.writeBytes(data)
            PatchOutcome(
                originalSize = originalSize,
                newSize = output.length(),
                snapshotCount = blobs.size,
                stringsFound = totalStrings,
                replacedCount = totalReplaced,
                matchedPatterns = totalMatched,
                elapsedMs = System.currentTimeMillis() - start
            )
        } catch (e: Exception) {
            log(context.getString(R.string.h_298ae83f, input.name, e.message))
            PatchOutcome(
                originalSize, originalSize, failed = true,
                error = e.message, elapsedMs = System.currentTimeMillis() - start
            )
        }
    }
}