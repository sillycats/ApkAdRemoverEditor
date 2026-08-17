package com.shinegirls.apkadremovereditor.core

import com.shinegirls.apkadremovereditor.utils.Format
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * APK 解包/打包处理器（基于 java.util.zip，不依赖 Android Framework）。
 *
 * 优化功能：
 * - META-INF 签名文件自动清理（避免签名冲突）
 * - 智能压缩策略：已压缩文件（.so/.png/.jpg 等）使用 STORE 存储，其余使用 DEFLATE
 * - DEX 文件使用 DEFLATE 压缩（targetSdk>=24 的 ART 原生支持压缩 DEX，可再减 30%~40% 体积）
 * - 4字节/页对齐由 apksig 在签名阶段自动完成（见 Signer）
 * - 防 zip slip 路径穿越攻击
 * - 大文件流式处理，避免 OOM
 */
class ApkProcessor {

    /**
     * 最近一次打包识别出的嵌套 APK/ZIP 子包路径（如 assets/base.apk、assets/base 等）。
     * 供数据复用优化（DataMultiplexingHelper）在打包后定位"原包 host"使用。
     */
    var lastEmbeddedApkPaths: List<String> = emptyList()
        private set

    companion object {
        /**
         * Android ZIP Alignment Extra Field 的 Header ID（0xd935，小端写入）。
         *
         * 布局（共 6+ 字节，field 内所有整数均为小端）：
         *   2 字节: Header ID = 0xd935
         *   2 字节: Data Size = 2 + padding（即对齐值 2 字节 + 填充长度）
         *   2 字节: 对齐值（4 或 4096）
         *   N 字节: 零填充，使 STORED 条目的数据起始偏移对齐
         *
         * 该字段必须同时写入【本地文件头】与【中央目录】的 extra 区，
         * apksig 签名时会读取中央目录中的对齐值并在插入 v2 签名块后重新对齐，
         * 从而保证签名后的 APK 依然满足 Android 11+（targetSdk>=30）的对齐要求。
         */
        private const val ALIGNMENT_FIELD_ID = 0xd935

        /** STORED 条目的最小对齐值（resources.arsc / classes.dex 等 4 字节对齐） */
        private const val ALIGN_4 = 4

        /** 未压缩 .so 原生库的页对齐值（4096 字节，供 mmap 直接映射） */
        private const val ALIGN_PAGE = 4096

        /** 使用 STORE（不压缩）存储的文件扩展名 */
        private val STORED_EXTENSIONS = setOf(
            "so",      // Native库，需 mmap 对齐，压缩后影响启动速度
            "png",     // 图片，已压缩
            "jpg",     // 图片，已压缩
            "jpeg",    // 图片，已压缩
            "gif",     // 图片，已压缩
            "webp",    // 图片，已压缩
            "arsc",    // 资源表，需 4 字节对齐供 mmap 直读
            "mp3",     // 音频，已高度压缩
            "mp4"      // 视频，已高度压缩
            // 说明：字体(ttf/otf)、wav、ogg 等此前误判为"已压缩"而走 STORE，
            // 实际它们可被 DEFLATE 进一步压缩，现改为默认压缩以减小导出 APK 体积。
        )

        /** 流的缓冲区大小（64KB），用于大文件流式读写 */
        private const val DEFAULT_BUFFER_SIZE = 64 * 1024
    }

    /**
     * 将 APK（ZIP）解包到指定目录。
     * 自动跳过 META-INF 中的签名文件（仅保留非签名文件）。
     */
    fun extractApk(apkFile: File, outputDir: File) {
        if (!outputDir.exists()) outputDir.mkdirs()

        ZipFile(apkFile).use { zipFile ->
            val entries = zipFile.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val entryName = entry.name

                // 跳过 META-INF 签名文件（.SF/.RSA/.DSA/.EC，签名时会重新生成）。
                // 注意：保留 META-INF/MANIFEST.MF（.MF 结尾），apksig 在 v1 签名时会读取并
                // 完整复制其主属性（如原包的 Created-By / Built-By 等），使处理后的
                // MANIFEST.MF 与原包保持一致，避免暴露"重建/重签"痕迹。
                if (entryName.startsWith("META-INF/") && (
                        entryName.endsWith(".SF") ||
                        entryName.endsWith(".RSA") ||
                        entryName.endsWith(".DSA") ||
                        entryName.endsWith(".EC")
                    )) {
                    continue
                }

                val outFile = File(outputDir, entryName)

                // 防止 zip slip 路径穿越
                if (!outFile.canonicalPath.startsWith(outputDir.canonicalPath + File.separator) &&
                    outFile.canonicalPath != outputDir.canonicalPath) {
                    continue
                }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                    continue
                }

                outFile.parentFile?.mkdirs()
                zipFile.getInputStream(entry).use { input ->
                    outFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    /**
     * 将源目录重新打包为 APK（ZIP）。
     *
     * 优化功能：
     * - META-INF 签名文件不打包（签名时由 apksig 重新生成）
     * - 智能压缩：已压缩文件用 STORE 存储，其余（含 DEX）用 DEFLATE 压缩
     * - 自动排除 smali 源码目录和临时文件
     *
     * 说明：打包时直接写入 0xd935 对齐字段（见下文），对齐由本方法完成，
     * 签名阶段 apksig 会读取该对齐值并在插入 v2 签名块后保持对齐。
     *
     * @param sourceDir          源目录
     * @param outputApk          输出 APK 文件
     * @param logger             日志回调
     * @param dataReuseOverride  数据复用优化开关；null=自动检测
     *                           （检测到 assets/其它目录含内置 .apk 子包即启用），
     *                           true=强制启用，false=强制关闭
     */
    fun buildApk(
        sourceDir: File,
        outputApk: File,
        logger: Logger? = null,
        dataReuseOverride: Boolean? = null
    ) {
        val log = logger ?: {}
        if (outputApk.exists()) outputApk.delete()
        outputApk.parentFile?.mkdirs()

        // 【数据复用优化】检测：被修改 APK 是否在 assets 或其它子目录内置了 .apk 子包。
        // 这类嵌套 APK（如 assets/base.apk，去签名效验/动态加载/拆包资源）本身是已高度压缩
        // 的 ZIP 容器，若仍对整包按 DEFLATE 最高压缩级别重压，会因"对高熵数据再压缩"而
        // 产生明显的体积膨胀 + 耗时增加，正是个别去签名效验大包重打后 apk 过大的主因。
        // 命中时改为对该子包按原字节直接复用（STORED），既省体积又保留内部对齐。
        //
        // 识别不依赖后缀名：去签名效验类子包常被重命名为无后缀或任意后缀（如 assets/base、
        // assets/base.dat 等）以规避检测。这里对每个嵌套子目录文件读取其魔数头，
        // 凡是 ZIP 容器（PK\x03\x04 / PK\x05\x06 / PK\x07\x08）一律视为可复用的 APK 子包。
        val embeddedApkPaths = mutableSetOf<String>()
        var embeddedApkCount = 0
        sourceDir.walkTopDown().forEach { f ->
            if (!f.isFile) return@forEach
            val rel = sourceDir.toURI().relativize(f.toURI()).path
            // 仅认嵌套（非根级）文件；根级才是本工具自产临时产物，不参与复用
            if (!rel.contains('/')) return@forEach
            if (isZipContainer(f)) {
                embeddedApkPaths += rel
                embeddedApkCount++
            }
        }
        lastEmbeddedApkPaths = embeddedApkPaths.sorted()
        val dataReuse = dataReuseOverride ?: (embeddedApkCount > 0)
        if (dataReuse) {
            log("  ℹ 数据复用优化已启用：识别出 $embeddedApkCount 个内置 APK 子包")
            log("  · 识别依据为文件真实格式(ZIP 容器)，不依赖后缀名，重命名的子包(base/base.dat 等)也能被识别")
            log("  · 已对这些子包按原字节直接复用(STORED)，避免对已压缩嵌套 APK 重复压缩导致的体积膨胀")
        }

        var entryCount = 0
        var totalUncompressed = 0L
        var totalCompressed = 0L

        // 使用 java.util.zip 标准 ZipOutputStream 打包，并显式指定 UTF-8 编码，
        // 保证条目文件名按 UTF-8 字节写入（与本地文件头/中央目录长度一致）。
        //
        // 【ZIP 对齐（zipalign）】：这是本工具能否安装成功的关键。
        // 旧实现曾：先手写 0xd935 extra 但字节布局错误导致安装失败；后又一度完全不做对齐、
        // 并错误地认为"apksig 签名阶段会自动对齐"。实际上 apksig【不会】自行决定对齐值，
        // 它只是读取输入 APK 中每个 STORED 条目的 0xd935 对齐字段并在重写时保持/重新应用。
        //
        // 若打包时不写入对齐字段：
        //  - 未压缩的 resources.arsc 未做 4 字节对齐；
        //  - 未压缩的 .so 未做 4096 字节页对齐。
        // 现代 app（targetSdk>=30 且 extractNativeLibs=false）安装时，系统会校验这两类对齐，
        // 不满足即返回 INSTALL_FAILED_INVALID_APK，即用户看到的"该安装包无效或不完整"/灰包。
        //
        // 注意：DEX 已改为 DEFLATE 压缩存储（见 STORED_EXTENSIONS），压缩条目无需对齐，
        // 因此这里只对仍为 STORED 的 resources.arsc / .so 写入对齐字段。
        //
        // 因此这里在写入每个 STORED 条目时，根据"数据起始偏移"计算填充量，
        // 直接把 0xd935 对齐字段 + 零填充写入 local header 的 extra；
        // ZipOutputStream 会把同一 extra 也写入中央目录，供 apksig 读取对齐值。
        FileOutputStream(outputApk).use { fos ->
            ZipOutputStream(fos, StandardCharsets.UTF_8).use { zos ->
                // 使用最高压缩级别：DEX/XML/文本等 DEFLATE 条目可显著减小体积。
                // 已压缩格式（图片/音频/视频/.so）走 STORE 分支，不受此级别影响，
                // 因此高压缩级别只作用于真正可压缩的内容，换取更小的导出 APK。
                zos.setLevel(Deflater.BEST_COMPRESSION)

                sourceDir.walkTopDown().forEach { file ->
                    if (file.isDirectory) return@forEach

                    val relativePath = sourceDir.toURI().relativize(file.toURI()).path

                    // 跳过反编译产生的 smali 源码目录
                    if (relativePath.startsWith("smali_")) return@forEach
                    // 跳过解包根目录下的临时 APK 文件。
                    // 注意：仅跳过"与 classes.dex 平级"(即 sourceDir 根)的 apk 产物；
                    // 不能按扩展名一刀切跳过所有 .apk，否则会误删受处理应用 assets 里
                    // 嵌套的 APK 子包（如 assets/base.apk，动态加载/拆包资源），
                    // 导致重新打包后该真实资产丢失。
                    if (!relativePath.contains('/') && file.extension.equals("apk", ignoreCase = true)) {
                        return@forEach
                    }
                    // 跳过临时文件
                    if (file.name.endsWith(".tmp")) return@forEach
                    // 跳过 META-INF 签名文件（.SF/.RSA/.DSA/.EC，签名时重新生成）。
                    // 保留 META-INF/MANIFEST.MF（.MF 结尾），供 apksig 复制其主属性，
                    // 使签名后的 MANIFEST.MF 与原包保持一致。
                    if (relativePath.startsWith("META-INF/") && (
                            relativePath.endsWith(".SF") ||
                            relativePath.endsWith(".RSA") ||
                            relativePath.endsWith(".DSA") ||
                            relativePath.endsWith(".EC")
                        )) {
                        return@forEach
                    }

                    val ext = file.extension.lowercase()
                    val isSo = ext == "so"
                    // 命中数据复用优化时，识别到的嵌套 APK/ZIP 子包按原字节 STORE 复用，
                    // 避免重复压缩膨胀。识别不依赖后缀名（见上方 embeddedApkPaths 集合）。
                    val isEmbeddedSubApk = relativePath in embeddedApkPaths
                    val shouldStore = ext in STORED_EXTENSIONS || (dataReuse && isEmbeddedSubApk)

                    val entry = ZipEntry(relativePath)
                    // 需对齐的 STORED 条目：.so 按 4096 页对齐，其余 STORED 按 4 字节对齐
                    val align = if (!shouldStore) 0 else if (isSo) ALIGN_PAGE else ALIGN_4

                    if (shouldStore) {
                        // STORE 模式：不压缩。
                        // 优化：原先"算 CRC"与"写入 zip"会把这类大文件（.so/.png 等）读取两次。
                        // 改为先流式读入临时文件并同时计算 CRC，再流式写入 zip，源文件只读一次，
                        // 同时保持"边读边算"的内存安全（不整读进内存，避免大文件 OOM）。
                        val tmp = File.createTempFile("stored_", ".tmp", outputApk.parentFile)
                        try {
                            val crc = java.util.zip.CRC32()
                            val size = tmp.outputStream().use { out ->
                                file.inputStream().use { input ->
                                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                    var total = 0L
                                    var read = input.read(buffer)
                                    while (read != -1) {
                                        crc.update(buffer, 0, read)
                                        out.write(buffer, 0, read)
                                        total += read
                                        read = input.read(buffer)
                                    }
                                    total
                                }
                            }
                            entry.method = ZipEntry.STORED
                            entry.size = size
                            entry.compressedSize = size
                            entry.crc = crc.value

                            // 计算并写入 0xd935 对齐 extra，使数据起始偏移对齐到 align 字节
                            if (align > 0) {
                                val nameBytes = relativePath.toByteArray(StandardCharsets.UTF_8)
                                // 当前文件写入位置即本地文件头起始偏移
                                val localHeaderStart = fos.channel.position()
                                // 数据起始偏移 = localHeaderStart + 30(头) + 文件名长 + 6(0xd935字段) + pad
                                val fieldSize = 6
                                val base = localHeaderStart + 30 + nameBytes.size
                                val pad = ((align - (base + fieldSize) % align) % align).toInt()
                                entry.extra = buildAlignExtra(pad, align)
                            }

                            zos.putNextEntry(entry)
                            tmp.inputStream().use { input ->
                                input.copyTo(zos, bufferSize = DEFAULT_BUFFER_SIZE)
                            }
                            zos.closeEntry()
                        } finally {
                            tmp.delete()
                        }
                        return@forEach
                    } else {
                        // DEFLATE 模式：压缩
                        entry.method = ZipEntry.DEFLATED
                    }

                    // 设置修改时间
                    entry.time = file.lastModified()

                    zos.putNextEntry(entry)
                    file.inputStream().use { input ->
                        input.copyTo(zos)
                    }
                    zos.closeEntry()

                    entryCount++
                    totalUncompressed += file.length()
                }
            }
        }

        totalCompressed = outputApk.length()

        log("  ✓ 打包完成: $entryCount 个条目")
        log("  · 未压缩大小: ${formatSize(totalUncompressed)}")
        log("  · 打包后大小: ${formatSize(totalCompressed)}")
        if (totalUncompressed > 0) {
            val ratio = (1.0 - totalCompressed.toDouble() / totalUncompressed) * 100
            log("  · 压缩率: ${String.format(Locale.US, "%.1f", ratio)}%")
        }
    }

    /**
     * 获取 APK 的基本信息。
     */
    fun getApkInfo(apkFile: File): Map<String, String> {
        val info = mutableMapOf<String, String>()
        ZipFile(apkFile).use { zip ->
            val manifestEntry = zip.getEntry("AndroidManifest.xml")
            if (manifestEntry != null) {
                info["has_manifest"] = "true"
            }

            val entries = zip.entries()
            var dexCount = 0
            var resCount = 0
            var libCount = 0
            var assetsCount = 0
            var totalSize = 0L

            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name.endsWith(".dex")) dexCount++
                if (entry.name.startsWith("res/")) resCount++
                if (entry.name.startsWith("lib/")) libCount++
                if (entry.name.startsWith("assets/")) assetsCount++
                totalSize += entry.size
            }
            info["dex_count"] = dexCount.toString()
            info["res_count"] = resCount.toString()
            info["lib_count"] = libCount.toString()
            info["assets_count"] = assetsCount.toString()
            info["total_size"] = totalSize.toString()
            info["file_size"] = apkFile.length().toString()
        }
        return info
    }

    /**
     * 判断文件是否为 ZIP 容器（APK 即 ZIP 格式）。
     *
     * 通过读取文件前 4 字节的魔数判断，不依赖扩展名。这样即使去签名效验的子 APK
     * 被重命名为无后缀或任意后缀（assets/base、assets/base.dat 等），也能被准确识别
     * 为"可数据复用的嵌套 APK 子包"。
     *
     * ZIP 魔数分类：
     *   PK\x03\x04 —— 本地文件头（最常见，含 ≥1 文件的正常 ZIP/APK）
     *   PK\x05\x06 —— 空 ZIP 尾部记录（0 个条目）
     *   PK\x07\x08 —— 分卷 ZIP
     */
    private fun isZipContainer(file: File): Boolean {
        return try {
            file.inputStream().use { ins ->
                val magic = ByteArray(4)
                val read = ins.read(magic)
                read == 4 &&
                    magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() && (
                    magic[2] == 0x03.toByte() ||
                        magic[2] == 0x05.toByte() ||
                        magic[2] == 0x07.toByte()
                    )
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 计算 CRC32 校验值（STORE 模式必须设置）。
     */
    /**
     * 构建 Android ZIP Alignment Extra Field（0xd935）。
     *
     * 布局（全部小端）：
     *   [0..1] Header ID = 0xd935
     *   [2..3] Data Size = 2 + pad
     *   [4..5] 对齐值 align（4 或 4096）
     *   [6..]  pad 个零字节，使数据起始偏移对齐
     *
     * @param pad   填充字节数（0..align-1）
     * @param align 对齐值（必须为 4 或 4096）
     */
    private fun buildAlignExtra(pad: Int, align: Int): ByteArray {
        val dataSize = 2 + pad
        val xtr = ByteArray(6 + pad)
        var i = 0
        // Header ID = 0xd935，小端
        xtr[i++] = (ALIGNMENT_FIELD_ID and 0xff).toByte()
        xtr[i++] = ((ALIGNMENT_FIELD_ID shr 8) and 0xff).toByte()
        // Data Size = 2 + pad，小端
        xtr[i++] = (dataSize and 0xff).toByte()
        xtr[i++] = ((dataSize shr 8) and 0xff).toByte()
        // 对齐值，小端
        xtr[i++] = (align and 0xff).toByte()
        xtr[i] = ((align shr 8) and 0xff).toByte()
        // 剩余 i+1.. 默认为 0，即 pad 个零填充
        return xtr
    }

    private fun formatSize(bytes: Long): String = Format.formatSize(bytes)
}
