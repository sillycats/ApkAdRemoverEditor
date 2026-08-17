package com.shinegirls.apkadremovereditor.core

import java.nio.charset.StandardCharsets

/**
 * Dart AOT 快照解析器（Flutter libapp.so 解包的基础）。
 *
 * Flutter 生产包（AOT）的 `libapp.so` 内嵌有一个或多个 Dart 快照，其中包含应用堆对象
 * （含广告 SDK 包名、广告 URL/域名、类名关键词等字符串，以原始 ASCII 字节保存）。
 * 每个"数据快照"的头部格式如下（参考 Dart VM `snapshot.h` 与社区逆向资料）：
 *
 *   offset 0  : 4 字节魔数 0xF5F5DCDC（磁盘字节序为 F5 F5 DC DC）
 *   offset 4  : 8 字节小端大小（单位字节，不含前 4 字节魔数）
 *   offset 12 : 8 字节 kind（AOT=2，JIT=1）
 *   offset 20 : 32 字节 ASCII 版本串
 *   offset 52 : 以 '\0' 结尾的 features 字符串
 *
 * 重要：上述字段中的 size 与 kind 为 Dart VM 快照头声明的【固定 8 字节小端整数】，
 * 与当前 libapp.so 是 32 位（armeabi-v7a）还是 64 位（arm64-v8a）无关。
 * 之前曾误认为 32 位快照头会缩小为 4 字节，导致 32 位快照边界解析错乱、
 * 广告字符串无法命中。Dart VM 只为 32 位架构生成 AOT 快照时把对象引用压窄，
 * 但快照头本身的 magic/size/kind 始终是固定宽度。
 *
 * 之后为按"类簇(cluster)"序列化的堆对象。本类负责在 ELF 字节流中定位快照头、
 * 提取可读字符串，供去广告与回编译环节使用。
 */
object DartSnapshot {

    /** 数据快照魔数：0xF5F5DCDC，磁盘字节序 F5 F5 DC DC。 */
    private val MAGIC = byteArrayOf(0xF5.toByte(), 0xF5.toByte(), 0xDC.toByte(), 0xDC.toByte())

    /** 快照头最小长度（魔数4 + 大小8 + kind8 + 版本32 = 52）。 */
    private const val HEADER_MIN = 52

    /** 64 位指针宽度（字节），仅用于信息展示。 */
    private const val PTR_64 = 8
    /** 32 位指针宽度（字节），仅用于信息展示。 */
    private const val PTR_32 = 4

    /**
     * libapp.so 中定位到的一个 Dart 数据快照。
     *
     * @param offset 快照头在 ELF 字节流中的起始偏移
     * @param size   快照总字节数 = 4(魔数) + 头部声明的存储大小
     * @param kind   快照类型（AOT=2 / JIT=1）
     * @param version 编译版本串（32 字符）
     * @param features 特性串（以 '\0' 结尾）
     * @param pointerWidth 该快照所在 SO 的指针宽度（4=32位 / 8=64位），仅用于信息展示
     */
    data class SnapshotBlob(
        val offset: Int,
        val size: Int,
        val kind: Int,
        val version: String,
        val features: String,
        val pointerWidth: Int = PTR_64
    ) {
        /** 快照结束偏移（不含）。 */
        val end: Int get() = offset + size
    }

    /**
     * 在 libapp.so 字节流中定位全部 Dart 数据快照。
     *
     * 无论 32 位还是 64 位，Dart 快照头的 size/kind 均为固定 8 字节，
     * 因此使用统一布局解析：扫描魔数 F5 F5 DC DC 找到快照头，读取头部声明的
     * 存储大小确定快照范围，并用"大小合法 + 范围在文件内"做健全性校验，
     * 避免误命中（如代码段中的巧合字节）。
     */
    fun findBlobs(data: ByteArray): List<SnapshotBlob> {
        val p = detectPointerWidth(data)
        val blobs = mutableListOf<SnapshotBlob>()
        var idx = 0
        while (true) {
            val pos = indexOf(data, MAGIC, idx, data.size)
            if (pos < 0) break
            if (pos + HEADER_MIN <= data.size) {
                val storedSize = readLongLE(data, pos + 4)
                val kind = readLongLE(data, pos + 12)
                // 健全性校验：大小为正、且快照范围不越界
                if (storedSize > 0 && pos + 8 + storedSize <= data.size) {
                    val size = 4 + storedSize.toInt()
                    val version = String(data, pos + 20, 32, StandardCharsets.US_ASCII)
                        .substringBefore('\u0000')
                    val featStart = pos + HEADER_MIN
                    var featEnd = featStart
                    while (featEnd < data.size && data[featEnd].toInt() != 0) featEnd++
                    val features = String(data, featStart, featEnd - featStart, StandardCharsets.US_ASCII)
                    blobs.add(SnapshotBlob(pos, size, kind.toInt(), version, features, p))
                    idx = pos + size
                    continue
                }
            }
            idx = pos + 4
        }
        return blobs
    }

    /**
     * 通过 ELF 头判定 libapp.so 的指针宽度。
     *
     * ELF 头部前 16 字节为 e_ident：
     *   [0]=0x7F [1]='E' [2]='L' [3]='F' [4]=EI_CLASS
     * EI_CLASS=1 => ELFCLASS32（32 位），EI_CLASS=2 => ELFCLASS64（64 位）。
     * 非 ELF 文件（理论不会出现）时按 64 位兜底。
     */
    private fun detectPointerWidth(data: ByteArray): Int {
        if (data.size >= 6 &&
            data[0] == 0x7F.toByte() && data[1] == 0x45.toByte() &&
            data[2] == 0x4C.toByte() && data[3] == 0x46.toByte()
        ) {
            val eiClass = data[4].toInt() and 0xFF
            return if (eiClass == 1) PTR_32 else PTR_64
        }
        return PTR_64
    }

    /**
     * 从指定快照区域内提取全部可读 ASCII 字符串（长度 >= [minLen]）。
     * 用于"解包"后的字符串导出与人工查看广告特征。
     */
    fun extractStrings(data: ByteArray, blob: SnapshotBlob, minLen: Int = 6): List<String> {
        val result = mutableListOf<String>()
        val start = blob.offset
        val end = blob.offset + blob.size
        var i = start
        while (i < end) {
            val b = data[i].toInt() and 0xFF
            if (b in 33..126) {
                var j = i
                while (j < end) {
                    val c = data[j].toInt() and 0xFF
                    if (c in 33..126) j++ else break
                }
                if (j - i >= minLen) {
                    result.add(String(data, i, j - i, StandardCharsets.US_ASCII))
                }
                i = j
            } else {
                i++
            }
        }
        return result
    }

    /**
     * 读取任意字节数（[bytes] 可为 4 或 8）的小端无符号整数。
     * 若 [bytes] 不为 8，则只取低 [bytes] 字节。
     */
    fun readUIntLE(data: ByteArray, offset: Int, bytes: Int): Long {
        var value = 0L
        for (i in 0 until bytes) {
            value = value or ((data[offset + i].toLong() and 0xFFL) shl (i * 8))
        }
        return value
    }

    /** 读取 8 字节小端无符号整数（保留旧调用，等价于 [readUIntLE] bytes=8）。 */
    fun readLongLE(data: ByteArray, offset: Int): Long =
        readUIntLE(data, offset, PTR_64)

    /**
     * 在 [data] 的 [from, toExclusive) 区间内查找 [pattern]，返回首个命中偏移，否则 -1。
     */
    fun indexOf(data: ByteArray, pattern: ByteArray, from: Int, toExclusive: Int): Int {
        if (pattern.isEmpty() || from < 0 || pattern.size > toExclusive - from) return -1
        val last = toExclusive - pattern.size
        var i = from
        while (i <= last) {
            var j = 0
            while (j < pattern.size && data[i + j] == pattern[j]) j++
            if (j == pattern.size) return i
            i++
        }
        return -1
    }
}