package com.shinegirls.apkadremovereditor.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AXML（Android Binary XML）广告清单移除器。
 *
 * APK 内的 AndroidManifest.xml 是二进制 XML（AXML）格式，无法直接用文本解析。
 * 本类直接解析 AXML 二进制结构：
 * - 解析字符串池（String Pool），将字符串引用解析为可读文本
 * - 遍历底层 XML chunk，识别各类广告相关元素（元素类型补全）：
 *   <activity> / <activity-alias> / <service> / <receiver> / <provider> 与
 *   <meta-data> 等，读取其 android:name（类名/标识）等属性
 * - 若属性值命中广告 SDK 包名或广告类名关键词，则移除该元素及其内部子元素
 *
 * 不同元素类型的广告标识所在属性不同（元素类型补全）：
 * - activity / service / receiver / provider：android:name（组件类名）
 * - activity-alias：android:name（别名类名）与 android:targetActivity（目标类名）
 * - meta-data：android:name（SDK 配置键，常为广告包名/标识）与 android:value
 *
 * 只做"移除元素"操作，不修改字符串池与资源映射，因此重写时无需重建字符串表，
 * 保留其余 chunk 原样，安全性高。
 */
object AxmlAdRemover {

    // ========== AXML chunk 类型 ==========
    private const val CHUNK_STRING_POOL = 0x0001
    private const val CHUNK_START_NS = 0x0100
    private const val CHUNK_END_NS = 0x0101
    private const val CHUNK_START_ELEMENT = 0x0102
    private const val CHUNK_END_ELEMENT = 0x0103
    private const val CHUNK_CDATA = 0x0104
    private const val CHUNK_LAST = 0x0105

    /** String Pool 标志位：UTF-8 编码（否则为 UTF-16LE） */
    private const val FLAG_UTF8 = 0x100

    /** Android 命名空间 URI：属性若带 android: 前缀，其 ns 字段必须指向该字符串 */
    private const val ANDROID_NS_URI = "http://schemas.android.com/apk/res/android"

    /**
     * 需要过滤的清单元素类型（小写，元素类型补全）。
     * 覆盖所有可能承载广告类名/包名/标识的声明元素。
     */
    private val COMPONENT_TAGS = setOf(
        "activity", "activity-alias", "service", "receiver",
        "provider", "meta-data"
    )

    /**
     * 需要匹配读取的 android 属性名（小写）。
     * 不同元素类型的广告标识位于不同属性：
     * - name：组件类名（activity/service/receiver/provider）、别名类名（activity-alias）、
     *   meta-data 的 SDK 配置键
     * - targetactivity：activity-alias 的目标类名
     * - value：meta-data 的字面量值（资源引用时不可读，天然跳过）
     */
    private val AD_ATTR_NAMES = setOf("name", "targetactivity", "value")

    /** 处理结果。 */
    data class AxmlResult(
        val modified: Boolean,
        val removedCount: Int,
        val removedComponents: List<String>
    )

    /**
     * AXML 字符串池解析器。
     */
    private class StringPool(private val data: ByteArray, private val chunkStart: Int) {

        private val stringCount: Int = readU32(data, chunkStart + 8).toInt()
        private val flags: Int = readU32(data, chunkStart + 16).toInt()
        private val stringsStart: Int = readU32(data, chunkStart + 20).toInt()
        private val isUtf8: Boolean = (flags and FLAG_UTF8) != 0
        private val offsets: IntArray = IntArray(stringCount)

        init {
            // 字符串偏移表位于 chunk 头部之后（chunkStart + 28）
            for (i in 0 until stringCount) {
                offsets[i] = readU32(data, chunkStart + 28 + i * 4).toInt()
            }
        }

        operator fun get(index: Int): String? {
            if (index < 0 || index >= stringCount) return null
            val pos = chunkStart + stringsStart + offsets[index]
            return if (isUtf8) decodeUtf8(pos) else decodeUtf16(pos)
        }

        /** 解码 UTF-16LE 字符串（Android 默认）。 */
        private fun decodeUtf16(pos: Int): String? {
            var p = pos
            if (p + 2 > data.size) return null
            var len = readU16(data, p)
            p += 2
            if (len and 0x8000 != 0) {
                // 高位置位表示长度需要扩展到 32 位
                if (p + 2 > data.size) return null
                len = ((len and 0x7fff) shl 16) or readU16(data, p)
                p += 2
            }
            val byteLen = len * 2
            if (p + byteLen > data.size) return null
            val bytes = ByteArray(byteLen)
            System.arraycopy(data, p, bytes, 0, byteLen)
            return String(bytes, Charsets.UTF_16LE)
        }

        /** 解码 UTF-8 字符串。 */
        private fun decodeUtf8(pos: Int): String? {
            var p = pos
            if (p >= data.size) return null
            // 第一个变长整数：UTF-16 字符数（解码时不需要）
            p = skipVarint(p)
            if (p >= data.size) return null
            // 第二个变长整数：字节长度
            var byteLen = data[p].toInt() and 0xff
            p++
            if (p >= data.size) return null
            if (byteLen and 0x80 != 0) {
                byteLen = ((byteLen and 0x7f) shl 8) or (data[p].toInt() and 0xff)
                p++
            }
            if (p + byteLen > data.size) return null
            val bytes = ByteArray(byteLen)
            System.arraycopy(data, p, bytes, 0, byteLen)
            return String(bytes, Charsets.UTF_8)
        }

        /** 跳过 1 或 2 字节的变长整数。 */
        private fun skipVarint(p: Int): Int {
            val b = data[p].toInt() and 0xff
            return if (b and 0x80 != 0) p + 2 else p + 1
        }
    }

    /**
     * 从 AndroidManifest.xml 中移除广告组件。
     * 同步调用，应在工作线程执行。
     *
     * @param manifestFile 解包后的 AndroidManifest.xml 文件
     * @param sdkPackages  广告 SDK 包名列表
     * @param classKeywords 广告类名关键词列表
     * @return 处理结果
     */
    fun removeAdComponents(
        manifestFile: File,
        sdkPackages: List<String>,
        classKeywords: List<String>
    ): AxmlResult {
        if (!manifestFile.exists()) return AxmlResult(false, 0, emptyList())

        val data = try {
            manifestFile.readBytes()
        } catch (_: Exception) {
            return AxmlResult(false, 0, emptyList())
        }
        if (data.size < 8) return AxmlResult(false, 0, emptyList())

        // 校验 AXML 魔数：文件头 type 应为 0x0003
        val fileType = readU16(data, 0)
        if (fileType != 0x0003) return AxmlResult(false, 0, emptyList())

        val fileSize = readU32(data, 4).toInt().coerceAtMost(data.size)

        // 解析字符串池（起始于 offset 8，紧跟文件头）
        val stringPool = StringPool(data, 8)

        val retainedChunks = mutableListOf<ByteArray>()
        val removedComponents = mutableListOf<String>()

        var offset = 8
        // skipDepth：-1 表示未在移除状态；>=0 表示正在跳过某个被移除组件的子树
        var skipDepth = -1

        while (offset + 8 <= fileSize) {
            val type = readU16(data, offset)
            val chunkSize = readU32(data, offset + 4).toInt()
            if (chunkSize < 8 || offset + chunkSize > fileSize) break

            if (skipDepth >= 0) {
                // 处于跳过状态：不复制任何 chunk，仅跟踪嵌套深度
                when (type) {
                    CHUNK_START_ELEMENT -> skipDepth++
                    CHUNK_END_ELEMENT -> {
                        if (skipDepth == 0) skipDepth = -1 // 已消费被移除组件的结束标签
                        else skipDepth--
                    }
                }
                offset += chunkSize
                continue
            }

            if (type == CHUNK_START_ELEMENT) {
                val elementName = stringPool[readU32(data, offset + 20)]?.lowercase()
                if (elementName in COMPONENT_TAGS) {
                    // 读取该元素类型可能承载广告标识的各 android 属性
                    val attrs = readAndroidAttrs(data, offset, stringPool, AD_ATTR_NAMES)
                    val isAd = attrs.values.any { v -> isAdComponentClass(v, sdkPackages, classKeywords) }
                    if (isAd) {
                        // 记录时优先展示类名/别名，其次为目标类名或配置键
                        val display = attrs["name"] ?: attrs["targetactivity"] ?: attrs["value"] ?: "?"
                        removedComponents.add("$elementName: $display")
                        skipDepth = 0
                        offset += chunkSize
                        continue
                    }
                }
                retainedChunks.add(data.copyOfRange(offset, offset + chunkSize))
            } else {
                retainedChunks.add(data.copyOfRange(offset, offset + chunkSize))
            }
            offset += chunkSize
        }

        if (removedComponents.isEmpty()) {
            return AxmlResult(false, 0, emptyList())
        }

        // 重建 AXML：文件头 + 保留的 chunk
        var newSize = 8
        for (chunk in retainedChunks) newSize += chunk.size

        val out = ByteBuffer.allocate(newSize).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(0x0003.toShort()) // type
        out.putShort(0x0008.toShort()) // headerSize
        out.putInt(newSize)            // 新的文件大小
        for (chunk in retainedChunks) out.put(chunk)

        try {
            manifestFile.writeBytes(out.array())
        } catch (_: Exception) {
            return AxmlResult(false, 0, emptyList())
        }

        return AxmlResult(true, removedComponents.size, removedComponents)
    }

    /**
     * 从 AndroidManifest.xml 中移除命中的广告权限声明（<uses-permission>）。
     *
     * 广告 SDK 常会声明自定义权限（如 com.lineone.connecter.permission.KW_SDK_BROADCAST、
     * com.lineone.connecter.openadsdk.permission.TT_PANGOLIN 等），用于保护其内部组件。
     * 这些权限特征可参考 /storage/emulated/0/APKEditor/ad_patterns.json 的 "ad_permissions" 字段自定义。
     *
     * 匹配规则：权限名（android:name）包含任一权限特征关键词（子串匹配，大小写不敏感）。
     * 仅移除 <uses-permission> 声明，不影响 INTERNET / CAMERA 等正常功能权限（除非用户主动加入特征）。
     *
     * @param manifestFile 解包后的 AndroidManifest.xml 文件
     * @param permissionKeywords 广告权限特征关键词列表
     * @return 处理结果
     */
    fun removeAdPermissions(
        manifestFile: File,
        permissionKeywords: List<String>
    ): AxmlResult {
        if (!manifestFile.exists()) return AxmlResult(false, 0, emptyList())
        val cleanKw = permissionKeywords.filter { it.isNotBlank() }
        if (cleanKw.isEmpty()) return AxmlResult(false, 0, emptyList())

        val data = try {
            manifestFile.readBytes()
        } catch (_: Exception) {
            return AxmlResult(false, 0, emptyList())
        }
        if (data.size < 8) return AxmlResult(false, 0, emptyList())

        // 校验 AXML 魔数：文件头 type 应为 0x0003
        if (readU16(data, 0) != 0x0003) return AxmlResult(false, 0, emptyList())
        val fileSize = readU32(data, 4).toInt().coerceAtMost(data.size)

        val stringPool = StringPool(data, 8)

        val retainedChunks = mutableListOf<ByteArray>()
        val removedPermissions = mutableListOf<String>()

        var offset = 8
        var skipDepth = -1

        while (offset + 8 <= fileSize) {
            val type = readU16(data, offset)
            val chunkSize = readU32(data, offset + 4).toInt()
            if (chunkSize < 8 || offset + chunkSize > fileSize) break

            if (skipDepth >= 0) {
                when (type) {
                    CHUNK_START_ELEMENT -> skipDepth++
                    CHUNK_END_ELEMENT -> {
                        if (skipDepth == 0) skipDepth = -1 else skipDepth--
                    }
                }
                offset += chunkSize
                continue
            }

            if (type == CHUNK_START_ELEMENT) {
                val elementName = stringPool[readU32(data, offset + 20)]?.lowercase()
                // 同时处理 <uses-permission>（权限请求）与 <permission>（自定义权限声明），
                // 两者命中同一特征名时应一并移除，避免广告 SDK 自定义权限残留。
                if (elementName == "uses-permission" || elementName == "permission") {
                    // 读取 android:name 属性（权限名）
                    val attrs = readAndroidAttrs(data, offset, stringPool, setOf("name"))
                    val permName = attrs["name"]
                    if (permName != null && matchesPermissions(permName, cleanKw)) {
                        removedPermissions.add(permName)
                        skipDepth = 0
                        offset += chunkSize
                        continue
                    }
                }
                retainedChunks.add(data.copyOfRange(offset, offset + chunkSize))
            } else {
                retainedChunks.add(data.copyOfRange(offset, offset + chunkSize))
            }
            offset += chunkSize
        }

        if (removedPermissions.isEmpty()) {
            return AxmlResult(false, 0, emptyList())
        }

        // 重建 AXML：文件头 + 保留的 chunk
        var newSize = 8
        for (chunk in retainedChunks) newSize += chunk.size

        val out = ByteBuffer.allocate(newSize).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(0x0003.toShort())
        out.putShort(0x0008.toShort())
        out.putInt(newSize)
        for (chunk in retainedChunks) out.put(chunk)

        try {
            manifestFile.writeBytes(out.array())
        } catch (_: Exception) {
            return AxmlResult(false, 0, emptyList())
        }

        return AxmlResult(true, removedPermissions.size, removedPermissions)
    }

    /**
     * 判断权限名是否命中广告权限特征（子串匹配，大小写不敏感）。
     */
    private fun matchesPermissions(permName: String, keywords: List<String>): Boolean {
        val lower = permName.lowercase()
        for (kw in keywords) {
            val k = kw.lowercase()
            if (k.isNotEmpty() && lower.contains(k)) return true
        }
        return false
    }

    /**
     * 读取 start element 中指定的 android 属性值（字符串引用）。
     *
     * 支持按元素类型读取不同属性（元素类型补全）：name / targetActivity / value 等。
     * 仅读取属于 android 命名空间的属性，取其 rawValue 字符串引用。
     *
     * @param wantNames 需要读取的属性名集合（小写）
     * @return 命中的属性名 -> 属性值 映射；未命中或解析失败返回空映射
     */
    private fun readAndroidAttrs(
        data: ByteArray,
        chunkStart: Int,
        pool: StringPool,
        wantNames: Set<String>
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val attributeCount = readU16(data, chunkStart + 28)
        val attributeSize = readU16(data, chunkStart + 26)
        if (attributeCount <= 0 || attributeSize < 20) return result

        // attributes 起始偏移固定为 chunkStart + 36（node 头 16 + attrExt 头 20）
        var attrOff = chunkStart + 36
        for (i in 0 until attributeCount) {
            if (attrOff + 20 > data.size) break
            val nsRef = readU32(data, attrOff)
            val nameRef = readU32(data, attrOff + 4)
            val rawValueRef = readU32(data, attrOff + 8)

            val attrName = pool[nameRef.toInt()]?.lowercase()
            if (attrName != null && attrName in wantNames) {
                // 校验为 android 命名空间（http://schemas.android.com/apk/res/android）
                val ns = pool[nsRef.toInt()] ?: ""
                if (ns.isEmpty() || ns.contains("schemas.android.com")) {
                    val value = pool[rawValueRef.toInt()]
                    if (value != null && value.isNotBlank()) {
                        result[attrName] = value
                    }
                }
            }
            attrOff += attributeSize
        }
        return result
    }

    /**
     * 判断类名是否属于广告组件。
     * 命中条件：类名包含某个广告 SDK 包名，或包含某个广告类名关键词。
     */
    private fun isAdComponentClass(
        className: String,
        sdkPackages: List<String>,
        classKeywords: List<String>
    ): Boolean {
        val lower = className.lowercase()
        for (pkg in sdkPackages) {
            val p = pkg.lowercase()
            if (p.isNotEmpty() && lower.contains(p)) return true
        }
        for (kw in classKeywords) {
            val k = kw.lowercase()
            if (k.isNotEmpty() && lower.contains(k)) return true
        }
        return false
    }

    // ========== 字节读取辅助 ==========

    private fun readU16(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun readU32(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xff) or
            ((data[offset + 1].toInt() and 0xff) shl 8) or
            ((data[offset + 2].toInt() and 0xff) shl 16) or
            ((data[offset + 3].toInt() and 0xff) shl 24)
    }

    private fun writeU16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xff).toByte()
        data[offset + 1] = ((value shr 8) and 0xff).toByte()
    }

    private fun writeU32(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xff).toByte()
        data[offset + 1] = ((value shr 8) and 0xff).toByte()
        data[offset + 2] = ((value shr 16) and 0xff).toByte()
        data[offset + 3] = ((value shr 24) and 0xff).toByte()
    }

    /**
     * 隐藏 Res 布局中的广告 View：将命中广告特征的元素宽高改为 0dp，并插入
     * android:visibility="gone" 属性，彻底隐藏广告区域以防留下空白。
     *
     * APK 内的 res/layout 布局文件同样是二进制 AXML 格式。本方法遍历其中所有
     * 元素，若元素类名命中广告关键词（如 .ad.、meishu_ad、adview 等），则：
     * 1. 把该元素的 android:layout_width 与 android:layout_height 的值改写为 0dp 尺寸；
     * 2. 插入 android:visibility="gone" 属性（若尚未存在），把广告视图置为 gone，
     *    使其不占布局空间、不留空白。
     *
     * 由于第 2 步需要新增属性，会扩展字符串池（追加 "visibility"）并扩大元素 chunk、
     * 后移后续字节。为保证正确性，所有命中元素按"从后往前"顺序逐个处理，
     * 这样后处理的元素（更靠前）位置不受已处理元素（更靠后）插入的影响。
     *
     * 若布局文件含 styles（styleCount>0），为避免破坏 styles 区，仅做 0dp 改写，
     * 跳过 visibility 属性插入。
     *
     * @param layoutFile 解包后的 res/layout 下的某个 .xml 布局文件
     * @param adKeywords 广告布局关键词（子串匹配，大小写不敏感）
     * @return 被隐藏的广告元素数量；实际写入文件返回 0（未命中或解析失败）
     */
    fun hideAdLayoutViews(
        layoutFile: File,
        adKeywords: List<String>
    ): Int {
        if (!layoutFile.exists()) return 0
        val cleanKw = adKeywords.filter { it.isNotBlank() }
        if (cleanKw.isEmpty()) return 0

        var data = try {
            layoutFile.readBytes()
        } catch (_: Exception) {
            return 0
        }
        if (data.size < 8) return 0
        // AXML 魔数校验
        if (readU16(data, 0) != 0x0003) return 0

        // ---------- 1. 确保字符串池包含 "visibility" 属性名 与 android 命名空间 URI ----------
        // 插入 android:visibility="gone" 需要两个字符串：
        //   - "visibility"（属性名）
        //   - ANDROID_NS_URI（android 命名空间，用于生成 android: 前缀）
        val styleCount = readU32(data, 8 + 12).toInt()
        var visibilityIdx = -1
        var androidNsIdx = -1
        if (styleCount > 0) {
            // 含 styles，避免破坏 styles 区，仅尝试复用已有字符串
            val sp0 = StringPool(data, 8)
            val cnt = readU32(data, 8 + 8).toInt()
            for (i in 0 until cnt) {
                val s = sp0[i]
                if (visibilityIdx < 0 && s == "visibility") visibilityIdx = i
                if (androidNsIdx < 0 && s == ANDROID_NS_URI) androidNsIdx = i
            }
        } else {
            val result = rebuildPoolAppendStrings(data, 8, listOf("visibility", ANDROID_NS_URI))
            data = result.first
            visibilityIdx = result.second["visibility"] ?: -1
            androidNsIdx = result.second[ANDROID_NS_URI] ?: -1
        }

        // 字符串池可能已扩容，同步更新文件头总大小，确保后续元素扫描覆盖全文件
        writeU32(data, 4, data.size)

        val stringPool = StringPool(data, 8)
        val fileSize = readU32(data, 4).toInt().coerceAtMost(data.size)

        // ---------- 2. 收集命中元素偏移 ----------
        val hitOffsets = mutableListOf<Int>()
        var offset = 8
        while (offset + 8 <= fileSize) {
            val type = readU16(data, offset)
            val chunkSize = readU32(data, offset + 4).toInt()
            if (chunkSize < 8 || offset + chunkSize > fileSize) break

            if (type == CHUNK_START_ELEMENT) {
                val elementName = stringPool[readU32(data, offset + 20)]?.lowercase()
                if (elementName != null && matchesElement(elementName, cleanKw)) {
                    hitOffsets.add(offset)
                }
            }
            offset += chunkSize
        }
        if (hitOffsets.isEmpty()) return 0

        // ---------- 3. 从后往前逐个处理（0dp + visibility=gone）----------
        for (elementOffset in hitOffsets.reversed()) {
            forceZeroSize(data, elementOffset, stringPool)
            if (visibilityIdx >= 0 && androidNsIdx >= 0) {
                data = insertVisibilityDueGone(data, elementOffset, visibilityIdx, androidNsIdx)
            }
        }
        // 更新文件头总大小
        writeU32(data, 4, data.size)

        try {
            layoutFile.writeBytes(data)
        } catch (_: Exception) {
            return 0
        }
        return hitOffsets.size
    }

    /**
     * 重建字符串池并追加多个字符串（若已存在则直接复用原索引）。
     *
     * 仅适用于 styleCount==0 的字符串池（res/layout 布局文件绝大多数无 styles）。
     * 重建后原有字符串索引不变，新字符串紧随其后。
     *
     * @return (新文件字节数组, 目标字符串 -> 索引 映射)
     */
    private fun rebuildPoolAppendStrings(
        data: ByteArray,
        poolStart: Int,
        targets: List<String>
    ): Pair<ByteArray, Map<String, Int>> {
        val oldSize = readU32(data, poolStart + 4).toInt()
        val stringCount = readU32(data, poolStart + 8).toInt()
        val flags = readU32(data, poolStart + 16).toInt()
        val isUtf8 = (flags and FLAG_UTF8) != 0

        val sp = StringPool(data, poolStart)
        val strings = mutableListOf<String>()
        for (i in 0 until stringCount) {
            strings.add(sp[i] ?: "")
        }
        // 记录已存在的目标字符串索引
        val indexMap = mutableMapOf<String, Int>()
        for (i in strings.indices) {
            if (indexMap.size < targets.size && targets.contains(strings[i])) {
                indexMap[strings[i]] = i
            }
        }
        // 追加缺失的目标字符串
        for (t in targets) {
            if (!indexMap.containsKey(t)) {
                indexMap[t] = strings.size
                strings.add(t)
            }
        }

        // 重新编码所有字符串，记录每个字符串相对数据区起点的偏移
        val newStringsStart = 28 + strings.size * 4
        val dataBuf = java.io.ByteArrayOutputStream()
        val offsets = IntArray(strings.size)
        for (i in strings.indices) {
            offsets[i] = dataBuf.size()
            val encoded = if (isUtf8) encodeUtf8String(strings[i]) else encodeUtf16String(strings[i])
            dataBuf.write(encoded)
            // AXML 字符串池中每个字符串都必须以 null 终止：UTF-8 为 1 字节 0x00，
            // UTF-16 为 2 字节 0x0000。否则 aapt2 等解析器报
            // "Bad string block: last string is not 0-terminated"。
            if (isUtf8) {
                dataBuf.write(0)
            } else {
                dataBuf.write(0); dataBuf.write(0)
            }
            while (dataBuf.size() % 4 != 0) dataBuf.write(0) // 4 字节对齐
        }
        val dataArea = dataBuf.toByteArray()
        val newPoolSize = newStringsStart + dataArea.size
        val delta = newPoolSize - oldSize

        val newData = ByteArray(data.size + delta)
        // 拷贝 poolStart 之前
        System.arraycopy(data, 0, newData, 0, poolStart)
        // 重写字符串池头
        writeU16(newData, poolStart, 0x0001)
        writeU16(newData, poolStart + 2, 0x001C)
        writeU32(newData, poolStart + 4, newPoolSize)
        writeU32(newData, poolStart + 8, strings.size)
        writeU32(newData, poolStart + 12, 0) // styleCount=0
        writeU32(newData, poolStart + 16, flags)
        writeU32(newData, poolStart + 20, newStringsStart)
        writeU32(newData, poolStart + 24, 0) // stylesStart=0
        for (i in offsets.indices) {
            writeU32(newData, poolStart + 28 + i * 4, offsets[i])
        }
        System.arraycopy(dataArea, 0, newData, poolStart + newStringsStart, dataArea.size)
        // 拷贝字符串池之后的内容
        System.arraycopy(data, poolStart + oldSize, newData, poolStart + newPoolSize, data.size - (poolStart + oldSize))

        return newData to indexMap
    }

    /**
     * 编码 UTF-16LE 字符串（与 Android AXML 字符串池格式一致）。
     */
    private fun encodeUtf16String(s: String): ByteArray {
        val bytes = s.toByteArray(Charsets.UTF_16LE)
        val len = s.length
        val out = java.io.ByteArrayOutputStream()
        if (len < 0x8000) {
            out.write(len and 0xff)
            out.write((len shr 8) and 0xff)
        } else {
            val hi = 0x8000 or (len ushr 16)
            out.write(hi and 0xff)
            out.write((hi shr 8) and 0xff)
            out.write(len and 0xff)
            out.write((len shr 8) and 0xff)
        }
        out.write(bytes)
        return out.toByteArray()
    }

    /**
     * 编码 UTF-8 字符串（与 Android AXML 字符串池格式一致）。
     */
    private fun encodeUtf8String(s: String): ByteArray {
        val utf8 = s.toByteArray(Charsets.UTF_8)
        val utf16Len = s.length
        val out = java.io.ByteArrayOutputStream()
        writeVarint(out, utf16Len)
        writeVarint(out, utf8.size)
        out.write(utf8)
        return out.toByteArray()
    }

    private fun writeVarint(out: java.io.ByteArrayOutputStream, value: Int) {
        if (value < 0x80) {
            out.write(value)
        } else {
            // 双字节变长整数：首字节高位置 1 表示双字节，低 7 位存高字节；
            // 次字节存低字节。与 decodeUtf8 的解析逻辑严格对应。
            out.write(((value shr 8) and 0x7f) or 0x80)
            out.write(value and 0xff)
        }
    }

    /**
     * 判断元素类名是否命中广告布局关键词（子串匹配，大小写不敏感）。
     */
    private fun matchesElement(elementName: String, keywords: List<String>): Boolean {
        for (kw in keywords) {
            val k = kw.lowercase()
            if (k.isNotEmpty() && elementName.contains(k)) return true
        }
        return false
    }

    /**
     * 将指定 start element 的 layout_width / layout_height 属性值改写为 0dp 尺寸。
     * 只改写属性 typed value，不改变 chunk 尺寸与字符串池偏移。
     *
     * 属性数组起始位置由 attributeStart 字段决定（相对 attrExt 起始 chunkStart+16 的偏移），
     * 不能硬编码为 chunkStart+36——带 style 的元素 attributeStart 会更大，否则会错位。
     */
    private fun forceZeroSize(data: ByteArray, chunkStart: Int, pool: StringPool) {
        val attributeStart = readU16(data, chunkStart + 24)
        val attributeCount = readU16(data, chunkStart + 28)
        val attributeSize = readU16(data, chunkStart + 26)
        if (attributeCount <= 0 || attributeSize < 20) return

        // 属性数组起始 = attrExt 起始(chunkStart+16) + attributeStart
        val attrBase = chunkStart + 16 + attributeStart
        var attrOff = attrBase
        for (i in 0 until attributeCount) {
            if (attrOff + 20 > data.size) break
            val nameRef = readU32(data, attrOff + 4)
            val attrName = pool[nameRef.toInt()]?.lowercase()
            if (attrName == "layout_width" || attrName == "layout_height") {
                // rawValue 字符串引用清零
                writeU32(data, attrOff + 8, 0)
                // typedValue：size=8, res0=0, dataType=0x05(TYPE_DIMENSION), data=0(0dp)
                writeU16(data, attrOff + 12, 8)
                data[attrOff + 14] = 0
                data[attrOff + 15] = 0x05
                writeU32(data, attrOff + 16, 0)
            }
            attrOff += attributeSize
        }
    }

    /**
     * 向指定 start element 的属性数组末尾插入 android:visibility="gone" 属性。
     * 属性值：ns=androidNsIdx(android 命名空间), name=visibility 索引, rawValue=0,
     * dataType=TYPE_INT_DECIMAL(0x10), data=0x00000008(GONE)。
     *
     * ns 字段必须指向字符串池中 android 命名空间 URI 的索引，否则属性会渲染为
     * visibility="8" 而非 android:visibility="8"。
     *
     * 属性数组起始由 attributeStart 字段决定（相对 attrExt 起始 chunkStart+16 的偏移），
     * 插入位置 = attrBase + attributeCount*attributeSize，避免带 style 的元素错位破坏结构。
     *
     * 从后往前调用时，该元素之后的内容已被处理，插入只影响其自身 chunk 及之后字节，
     * 不影响更靠前的元素。
     *
     * @return 插入后的新字节数组
     */
    private fun insertVisibilityDueGone(
        data: ByteArray,
        chunkStart: Int,
        visibilityIdx: Int,
        androidNsIdx: Int
    ): ByteArray {
        val attributeStart = readU16(data, chunkStart + 24)
        val attributeCount = readU16(data, chunkStart + 28)
        val attributeSize = readU16(data, chunkStart + 26)
        val chunkSize = readU32(data, chunkStart + 4).toInt()
        if (attributeSize < 20) return data

        // 属性数组起始 = attrExt 起始(chunkStart+16) + attributeStart
        val attrBase = chunkStart + 16 + attributeStart
        // 属性数组末尾位置
        val insertPos = attrBase + attributeCount * attributeSize
        val delta = 20
        val newData = ByteArray(data.size + delta)

        System.arraycopy(data, 0, newData, 0, insertPos)
        // 新属性：android:visibility="gone"
        writeU32(newData, insertPos, androidNsIdx)      // ns=android 命名空间
        writeU32(newData, insertPos + 4, visibilityIdx) // name
        writeU32(newData, insertPos + 8, 0)             // rawValue
        writeU16(newData, insertPos + 12, 8)            // typedValue.size
        newData[insertPos + 14] = 0                     // res0
        newData[insertPos + 15] = 0x10                  // dataType TYPE_INT_DECIMAL
        writeU32(newData, insertPos + 16, 0x00000008)   // data GONE
        // 拷贝插入点之后
        System.arraycopy(data, insertPos, newData, insertPos + delta, data.size - insertPos)

        // 更新 attributeCount 与 chunk size
        writeU16(newData, chunkStart + 28, attributeCount + 1)
        writeU32(newData, chunkStart + 4, chunkSize + delta)
        return newData
    }

    // ========== MT 式去签名效验：清单 application 注入支持 ==========

    /** 清单基础信息：包名与已有 application 名（可能为 null）。 */
    data class ManifestInfo(val packageName: String?, val applicationName: String?)

    /**
     * 读取解包后 AndroidManifest.xml 的包名与已有 application 类名。
     * 供去签名效验引擎解析"原包是否自定义 Application"及解析相对类名（.X 前缀）使用。
     */
    fun readManifestInfo(manifestFile: File): ManifestInfo? {
        if (!manifestFile.exists()) return null
        val data = try { manifestFile.readBytes() } catch (_: Exception) { return null }
        if (data.size < 8 || readU16(data, 0) != 0x0003) return null
        val fileSize = readU32(data, 4).toInt().coerceAtMost(data.size)
        val pool = StringPool(data, 8)
        var packageName: String? = null
        var appName: String? = null
        var offset = 8
        while (offset + 8 <= fileSize) {
            val type = readU16(data, offset)
            val chunkSize = readU32(data, offset + 4).toInt()
            if (chunkSize < 8 || offset + chunkSize > fileSize) break
            if (type == CHUNK_START_ELEMENT) {
                val elem = pool[readU32(data, offset + 20)]?.lowercase()
                when (elem) {
                    "manifest" -> if (packageName == null) {
                        readAllAttrs(data, offset, pool)["package"]?.let { packageName = it }
                    }
                    "application" -> if (appName == null) {
                        readAllAttrs(data, offset, pool)["name"]?.let { appName = it }
                    }
                }
            }
            offset += chunkSize
        }
        return ManifestInfo(packageName, appName)
    }

    /**
     * MT 式去签名效验：把 <application> 元素的 android:name 改写为注入的钩子 Application 类名。
     *
     * 步骤：
     * 1. 复用字符串池重建逻辑追加目标类名字符串（与必要的 "name" / android 命名空间 URI）；
     * 2. 定位 <application> start element；
     * 3. 若已存在 android:name 属性则改写其字符串索引，否则在属性数组末尾插入该属性；
     * 4. 原子写回清单文件。
     *
     * @param manifestFile 解包后的 AndroidManifest.xml
     * @param className 钩子 Application 的完整类名（点分，如 com.shinegirls.pmshook.PmsHookApplication）
     * @return 是否改写成功
     */
    fun setApplicationName(manifestFile: File, className: String): Boolean {
        if (!manifestFile.exists()) return false
        var data = try { manifestFile.readBytes() } catch (_: Exception) { return false }
        if (data.size < 8 || readU16(data, 0) != 0x0003) return false
        // rebuildPoolAppendStrings 仅适用于 styleCount==0 的字符串池。
        // AndroidManifest.xml 实际几乎恒为 0；命中非 0（极罕见）时安全跳过。
        if (readU32(data, 8 + 12).toInt() > 0) return false

        val (poolData, idxMap) =
            rebuildPoolAppendStrings(data, 8, listOf(ANDROID_NS_URI, "name", className))
        data = poolData
        val appIdx = idxMap[className] ?: return false
        val nameIdx = idxMap["name"] ?: return false
        val nsIdx = idxMap[ANDROID_NS_URI] ?: return false
        writeU32(data, 4, data.size)

        val pool = StringPool(data, 8)
        val fileSize = readU32(data, 4).toInt().coerceAtMost(data.size)
        var appOffset = -1
        var offset = 8
        while (offset + 8 <= fileSize) {
            val type = readU16(data, offset)
            val chunkSize = readU32(data, offset + 4).toInt()
            if (chunkSize < 8 || offset + chunkSize > fileSize) break
            if (type == CHUNK_START_ELEMENT &&
                pool[readU32(data, offset + 20)]?.lowercase() == "application"
            ) {
                appOffset = offset
                break
            }
            offset += chunkSize
        }
        if (appOffset < 0) return false

        data = patchApplicationNameAttribute(data, appOffset, appIdx, nameIdx, nsIdx, pool)
            ?: return false
        try {
            manifestFile.writeBytes(data)
        } catch (_: Exception) {
            return false
        }
        return true
    }

    /**
     * 改写 / 插入 application 属性的 android:name。返回布局调整后的新字节数组。
     */
    private fun patchApplicationNameAttribute(
        data: ByteArray,
        chunkStart: Int,
        appIdx: Int,
        nameIdx: Int,
        nsIdx: Int,
        pool: StringPool
    ): ByteArray? {
        val attributeStart = readU16(data, chunkStart + 24)
        val attributeCount = readU16(data, chunkStart + 28)
        val attributeSize = readU16(data, chunkStart + 26)
        if (attributeSize < 20) return null
        val attrBase = chunkStart + 16 + attributeStart

        // 若已存在android:name则改写，否则在末尾插入
        var existing = -1
        for (i in 0 until attributeCount) {
            val p = attrBase + i * attributeSize
            if (pool[readU32(data, p + 4).toInt()]?.lowercase() == "name") {
                existing = p
                break
            }
        }
        if (existing >= 0) {
            writeU32(data, existing + 8, appIdx)      // rawValue
            setStringTypedValue(data, existing + 12, appIdx)
            return data
        }

        val insertPos = attrBase + attributeCount * attributeSize
        val delta = 20
        val newData = ByteArray(data.size + delta)
        System.arraycopy(data, 0, newData, 0, insertPos)
        writeU32(newData, insertPos, nsIdx)             // ns=android 命名空间
        writeU32(newData, insertPos + 4, nameIdx)       // name
        writeU32(newData, insertPos + 8, appIdx)        // rawValue
        setStringTypedValue(newData, insertPos + 12, appIdx)
        System.arraycopy(data, insertPos, newData, insertPos + delta, data.size - insertPos)
        writeU16(newData, chunkStart + 28, attributeCount + 1)
        writeU32(newData, chunkStart + 4, readU32(newData, chunkStart + 4).toInt() + delta)
        return newData
    }

    /**
     * 将 typedValue 写为 TYPE_STRING 且 data 指向字符串池索引。
     * 布局：[size:2][res0:1][dataType:1][data:4]
     */
    private fun setStringTypedValue(data: ByteArray, typedPos: Int, stringIdx: Int) {
        writeU16(data, typedPos, 8)
        data[typedPos + 2] = 0
        data[typedPos + 3] = 0x03          // TYPE_STRING
        writeU32(data, typedPos + 4, stringIdx)
    }

    /**
     * 读取某 start element 的所有字符串属性（用于解析 manifest/application 的信息）。
     * 仅收集 rawValue 以字符串类型存储的属性，资源引用天然跳过。
     */
    private fun readAllAttrs(data: ByteArray, chunkStart: Int, pool: StringPool): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val attributeStart = readU16(data, chunkStart + 24)
        val attributeCount = readU16(data, chunkStart + 28)
        val attributeSize = readU16(data, chunkStart + 26)
        if (attributeCount <= 0 || attributeSize < 20) return result
        var attrOff = chunkStart + 16 + attributeStart
        for (i in 0 until attributeCount) {
            if (attrOff + 20 > data.size) break
            val value = if (readU32(data, attrOff + 8).toInt() >= 0) {
                pool[readU32(data, attrOff + 8).toInt()]
            } else {
                null
            }
            if (value != null && value.isNotBlank()) {
                val name = pool[readU32(data, attrOff + 4).toInt()].orEmpty()
                result[name] = value
            }
            attrOff += attributeSize
        }
        return result
    }
}