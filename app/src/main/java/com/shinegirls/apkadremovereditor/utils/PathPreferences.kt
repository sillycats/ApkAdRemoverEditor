package com.shinegirls.apkadremovereditor.utils

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * 路径偏好管理器。
 *
 * 管理两个可自定义路径：
 * - 广告特征配置文件路径（ad_patterns.json 的完整路径）
 * - 去广告后 APK 输出目录路径
 *
 * 使用 SharedPreferences 持久化，默认值与 [Format.EXPORT_DIR] 一致。
 */
object PathPreferences {

    private const val PREFS_NAME = "path_preferences"
    private const val KEY_CONFIG_PATH = "config_file_path"
    private const val KEY_OUTPUT_DIR = "output_apk_dir"
    private const val KEY_ENABLE_FLUTTER = "enable_flutter_libapp"
    private const val KEY_ENABLE_DEX_OPTIMIZE = "enable_dex_optimize"
    /** 广告特征分类开关的前缀，完整 key = 前缀 + 分类名。 */
    private const val KEY_ENABLE_CATEGORY_PREFIX = "enable_category_"

    /** 默认配置文件完整路径。 */
    val DEFAULT_CONFIG_PATH: String = "${Format.EXPORT_DIR}/ad_patterns.json"

    /** 默认 APK 输出目录。 */
    val DEFAULT_OUTPUT_DIR: String = Format.EXPORT_DIR

    /** 缓存实例，避免重复调用 getSharedPreferences。 */
    private val prefsCache = HashMap<Context, SharedPreferences>()

    private fun getPrefs(context: Context): SharedPreferences =
        synchronized(prefsCache) {
            prefsCache.getOrPut(context.applicationContext) {
                context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
        }

    /**
     * 获取当前广告特征配置文件完整路径。
     * 若用户未自定义，返回默认路径。
     */
    fun getConfigFilePath(context: Context): String {
        return getPrefs(context).getString(KEY_CONFIG_PATH, DEFAULT_CONFIG_PATH) ?: DEFAULT_CONFIG_PATH
    }

    /**
     * 设置广告特征配置文件完整路径。
     * 设置后会自动确保目录存在。
     */
    fun setConfigFilePath(context: Context, path: String): Boolean {
        // 确保父目录存在
        val dir = File(path).parentFile
        if (dir != null) {
            try {
                if (!dir.exists()) dir.mkdirs()
            } catch (_: Exception) {
                return false
            }
        }
        return try {
            getPrefs(context).edit().putString(KEY_CONFIG_PATH, path).apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 获取当前 APK 输出目录路径。
     * 若用户未自定义，返回默认目录。
     */
    fun getOutputDir(context: Context): String {
        return getPrefs(context).getString(KEY_OUTPUT_DIR, DEFAULT_OUTPUT_DIR) ?: DEFAULT_OUTPUT_DIR
    }

    /**
     * 设置 APK 输出目录路径。
     * 设置后会自动确保目录存在。
     */
    fun setOutputDir(context: Context, path: String): Boolean {
        try {
            val dir = File(path)
            if (!dir.exists()) dir.mkdirs()
        } catch (_: Exception) {
            return false
        }
        return try {
            getPrefs(context).edit().putString(KEY_OUTPUT_DIR, path).apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 配置文件路径是否为自定义（与默认值不同）。
     */
    fun isConfigPathCustom(context: Context): Boolean {
        return getConfigFilePath(context) != DEFAULT_CONFIG_PATH
    }

    /**
     * 输出目录是否为自定义（与默认值不同）。
     */
    fun isOutputDirCustom(context: Context): Boolean {
        return getOutputDir(context) != DEFAULT_OUTPUT_DIR
    }

    /**
     * 重置配置文件路径为默认值。
     */
    fun resetConfigPath(context: Context) {
        getPrefs(context).edit().remove(KEY_CONFIG_PATH).apply()
    }

    /**
     * 重置输出目录为默认值。
     */
    fun resetOutputDir(context: Context) {
        getPrefs(context).edit().remove(KEY_OUTPUT_DIR).apply()
    }

    /**
     * 是否启用 Flutter libapp.so 处理（解包 / 去广告 / 回编译）。
     * 默认开启。
     */
    fun isFlutterLibappEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ENABLE_FLUTTER, true)
    }

    /**
     * 设置 Flutter libapp.so 处理开关。
     */
    fun setFlutterLibappEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ENABLE_FLUTTER, enabled).apply()
    }

    /**
     * 是否启用 DEX 体积优化（移除调试信息：行号/局部变量表/参数名）。
     *
     * 移除 debug info 可减小 DEX 体积 5%~15%（商业 App 含大量广告 SDK 时收益更高），
     * 不影响运行功能，仅丢失崩溃堆栈中的行号信息。默认开启。
     */
    fun isDexOptimizeEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ENABLE_DEX_OPTIMIZE, true)
    }

    /**
     * 设置 DEX 体积优化开关。
     */
    fun setDexOptimizeEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ENABLE_DEX_OPTIMIZE, enabled).apply()
    }

    /**
     * 广告特征分类是否启用。
     *
     * @param categoryName 分类的 enum 名称（AdPatternConfig.Category 的 name()），
     *                     例如 "SDK_PACKAGES"、"RES_LAYOUT_KEYWORDS"。
     * @return true 表示启用该分类（默认启用），false 表示用户已关闭该分类。
     */
    fun isCategoryEnabled(context: Context, categoryName: String): Boolean {
        return getPrefs(context).getBoolean(KEY_ENABLE_CATEGORY_PREFIX + categoryName, true)
    }

    /**
     * 设置广告特征分类是否启用。
     */
    fun setCategoryEnabled(context: Context, categoryName: String, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ENABLE_CATEGORY_PREFIX + categoryName, enabled).apply()
    }
}