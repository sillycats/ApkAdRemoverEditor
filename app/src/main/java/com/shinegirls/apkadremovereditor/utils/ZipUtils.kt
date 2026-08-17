package com.shinegirls.apkadremovereditor.utils

import java.io.*
import java.util.zip.*

/** 单次解压最大字节数（500MB），防止 zip bomb。 */
private const val MAX_UNZIP_BYTES = 500L * 1024 * 1024

object ZipUtils {

    /**
     * 安全解压 [zipFile] 到 [destDir]。
     *
     * 校验 Zip Slip 路径穿越：所有条目的目标路径必须在 [destDir] 内。
     * 限制总解压字节数防止 zip bomb。
     */
    fun unzip(zipFile: File, destDir: File) {
        if (!destDir.exists()) destDir.mkdirs()
        val destCanonical = destDir.canonicalPath

        ZipFile(zipFile).use { zip ->
            val entries = zip.entries()
            var totalBytes = 0L

            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()

                // 安全校验：防止 Zip Slip 路径穿越
                val outFile = File(destDir, entry.name).canonicalFile
                if (!outFile.path.startsWith(destCanonical + File.separator)) {
                    throw SecurityException("Zip Slip detected: ${entry.name} escapes target directory")
                }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                    continue
                }

                if (totalBytes >= MAX_UNZIP_BYTES) {
                    throw IOException("Unzip size exceeds maximum allowed ($MAX_UNZIP_BYTES bytes)")
                }

                outFile.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    outFile.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                        totalBytes += entry.compressedSize.coerceAtLeast(1)
                    }
                }
            }
        }
    }

    fun zip(sourceDir: File, outputZip: File, filter: ((File) -> Boolean)? = null) {
        if (outputZip.exists()) outputZip.delete()

        ZipOutputStream(FileOutputStream(outputZip)).use { zos ->
            sourceDir.walkTopDown().forEach { file ->
                if (file.isDirectory) return@forEach
                if (filter != null && !filter(file)) return@forEach

                val relativePath = file.path.removePrefix(sourceDir.path).trimStart(File.separatorChar)
                val entry = ZipEntry(relativePath)
                zos.putNextEntry(entry)
                file.inputStream().use { input ->
                    input.copyTo(zos, bufferSize = 64 * 1024)
                }
                zos.closeEntry()
            }
        }
    }
}