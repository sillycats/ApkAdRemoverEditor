package com.shinegirls.apkadremovereditor.utils

import java.util.Locale

/**
 * 通用格式化工具。
 *
 * 集中管理文件大小格式化、导出目录常量等，避免在多个类中重复实现。
 */
object Format {

    /** APK 处理产物与配置文件的默认导出目录。 */
    const val EXPORT_DIR = "/storage/emulated/0/APKEditor"

    private const val KB = 1024L
    private const val MB = 1024L * 1024
    private const val GB = 1024L * 1024 * 1024

    /**
     * 将字节数格式化为人类可读的文件大小。
     *
     * 依次按 B / KB / MB / GB 分级，统一使用 [Locale.US] 避免地区小数点差异。
     */
    fun formatSize(bytes: Long): String {
        val kb = KB.toDouble()
        return when {
            bytes < KB -> "${bytes}B"
            bytes < MB -> String.format(Locale.US, "%.1fKB", bytes / kb)
            bytes < GB -> String.format(Locale.US, "%.1fMB", bytes / (kb * 1024))
            else -> String.format(Locale.US, "%.2fGB", bytes / (kb * 1024 * 1024))
        }
    }
}
