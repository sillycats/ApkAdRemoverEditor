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
   /** 签名效验去除模式：0=关闭 1=普通去除 2=原包去除 */
    private const val KEY_SIGN_REMOVAL_MODE = "sign_removal_mode"
    /** 签名效验去除：原包在安装包内的 assets 路径（用户可自定义） */
    private const val KEY_SIGN_ORIGIN_PATH = "sign_origin_path"
    /** 签名效验去除：原包解压目标路径（用户可自定义） */
    private const val KEY_SIGN_EXTRACT_PATH = "sign_extract_path"
    /** 签名效验去除：原生库名（用户可自定义） */
    private const val KEY_SIGN_SO_NAME = "sign_so_name"
    /** 签名效验去除：钩子类名（用户可自定义，默认 android.app.AppIication） */
    private const val KEY_SIGN_HOOK_CLASS = "sign_hook_class"
    /** 签名效验去除：签名信息（Base64 证书，用户可自定义；留空则从原包读取） */
    private const val KEY_SIGN_INFO = "sign_info"
    /** 签名效验去除：入口名称（包名，用户可自定义；留空则从 manifest 读取） */
    private const val KEY_SIGN_ENTRY = "sign_entry"
    /** 上次记录的版本号（用于升级后重置签名效验为默认关闭） */
    private const val KEY_LAST_VERSION_CODE = "last_version_code"
    /** 打包 APK 时是否跳过重签名（true=不签名，直接输出未签名 APK） */
    private const val KEY_SKIP_SIGNING = "skip_apk_signing"

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

    /**
     * 获取签名效验去除模式。
     * 0=关闭；1=普通去除；2=原包去除。默认关闭。
     */
    fun getSignRemovalMode(context: Context): Int {
        return getPrefs(context).getInt(KEY_SIGN_REMOVAL_MODE, 0)
    }

    /**
     * 设置签名效验去除模式。
     */
    fun setSignRemovalMode(context: Context, mode: Int) {
        getPrefs(context).edit().putInt(KEY_SIGN_REMOVAL_MODE, mode).apply()
    }

    /**
     * 签名效验去除是否开启。
     */
    fun isSignRemovalEnabled(context: Context): Boolean {
        return getSignRemovalMode(context) != 0
    }

    /**
     * 版本升级后重置签名效验为默认关闭。
     *
     * 签名效验去除默认应为关闭状态。若用户从旧版本升级安装（SharedPreferences 数据保留），
     * 之前开启过的签名效验会残留为开启，此处检测到版本号升级时自动重置为关闭，
     * 保证新版本首次运行即处于默认关闭状态。
     *
     * 应在应用启动时（MainActivity.onCreate）调用。
     */
    fun resetSignRemovalOnUpgrade(context: Context) {
        try {
            val prefs = getPrefs(context)
            val lastCode = prefs.getLong(KEY_LAST_VERSION_CODE, 0L)
            val currentCode = context.packageManager
                .getPackageInfo(context.packageName, 0).versionCode.toLong()
            // 首次安装（lastCode==0）或版本升级（currentCode > lastCode）时重置为关闭
            if (currentCode > lastCode) {
                prefs.edit().putInt(KEY_SIGN_REMOVAL_MODE, 0).apply()
                prefs.edit().putLong(KEY_LAST_VERSION_CODE, currentCode).apply()
            }
        } catch (_: Exception) {
            // 极端情况下忽略，不影响启动
        }
    }

    /**
     * 获取原包在安装包内的 assets 路径（用户可自定义）。
     * 默认：assets/SignatureKiIIer/origin.apk
     */
    fun getSignOriginPath(context: Context): String {
        return getPrefs(context).getString(KEY_SIGN_ORIGIN_PATH,
            com.shinegirls.apkadremovereditor.core.SignatureVerificationRemover.DEFAULT_ORIGIN_ASSET_PATH)
            ?: com.shinegirls.apkadremovereditor.core.SignatureVerificationRemover.DEFAULT_ORIGIN_ASSET_PATH
    }

    /**
     * 设置原包在安装包内的 assets 路径。
     */
    fun setSignOriginPath(context: Context, path: String) {
        getPrefs(context).edit().putString(KEY_SIGN_ORIGIN_PATH, path).apply()
    }

    /**
     * 获取原包解压目标路径（用户可自定义）。
     * 默认：files/SignatureKiIIer/base.apk
     */
    fun getSignExtractPath(context: Context): String {
        return getPrefs(context).getString(KEY_SIGN_EXTRACT_PATH,
            com.shinegirls.apkadremovereditor.core.SignatureVerificationRemover.DEFAULT_EXTRACT_PATH)
            ?: com.shinegirls.apkadremovereditor.core.SignatureVerificationRemover.DEFAULT_EXTRACT_PATH
    }

    /**
     * 设置原包解压目标路径。
     */
    fun setSignExtractPath(context: Context, path: String) {
        getPrefs(context).edit().putString(KEY_SIGN_EXTRACT_PATH, path).apply()
    }

    /**
     * 获取原生库名（用户可自定义）。
     * 默认：SignatureKiIIer（对应 lib/<abi>/libSignatureKiIIer.so）
     */
    fun getSignSoName(context: Context): String {
        return getPrefs(context).getString(KEY_SIGN_SO_NAME,
            com.shinegirls.apkadremovereditor.core.SignatureVerificationRemover.DEFAULT_SO_NAME)
            ?: com.shinegirls.apkadremovereditor.core.SignatureVerificationRemover.DEFAULT_SO_NAME
    }

    /**
     * 设置原生库名。
     */
    fun setSignSoName(context: Context, name: String) {
        getPrefs(context).edit().putString(KEY_SIGN_SO_NAME, name).apply()
    }

    /**
     * 获取钩子类名（点号形式，用户可自定义）。
     * 默认：android.app.AppIication
     */
    fun getSignHookClass(context: Context): String {
        return getPrefs(context).getString(KEY_SIGN_HOOK_CLASS,
            com.shinegirls.apkadremovereditor.core.SignatureVerificationRemover.DEFAULT_HOOK_CLASS)
            ?: com.shinegirls.apkadremovereditor.core.SignatureVerificationRemover.DEFAULT_HOOK_CLASS
    }

    /**
     * 设置钩子类名。
     */
    fun setSignHookClass(context: Context, name: String) {
        getPrefs(context).edit().putString(KEY_SIGN_HOOK_CLASS, name).apply()
    }

    /**
     * 获取签名信息（Base64 证书，用户可自定义）。
     * 默认留空，运行时从原包读取真实签名。
     */
    fun getSignInfo(context: Context): String {
        return getPrefs(context).getString(KEY_SIGN_INFO,
            com.shinegirls.apkadremovereditor.core.SignatureVerificationRemover.DEFAULT_SIGN_INFO)
            ?: com.shinegirls.apkadremovereditor.core.SignatureVerificationRemover.DEFAULT_SIGN_INFO
    }

    /**
     * 设置签名信息（Base64 证书）。
     */
    fun setSignInfo(context: Context, info: String) {
        getPrefs(context).edit().putString(KEY_SIGN_INFO, info).apply()
    }

    /**
     * 获取入口名称（包名，用户可自定义）。
     * 默认留空，运行时从 manifest 读取真实包名。
     */
    fun getSignEntry(context: Context): String {
        return getPrefs(context).getString(KEY_SIGN_ENTRY,
            com.shinegirls.apkadremovereditor.core.SignatureVerificationRemover.DEFAULT_ENTRY_NAME)
            ?: com.shinegirls.apkadremovereditor.core.SignatureVerificationRemover.DEFAULT_ENTRY_NAME
    }

    /**
     * 设置入口名称（包名）。
     */
    fun setSignEntry(context: Context, entry: String) {
        getPrefs(context).edit().putString(KEY_SIGN_ENTRY, entry).apply()
    }

    /**
     * 打包 APK 时是否跳过重签名。
     *
     * true = 打包时不重签名，直接输出未签名 APK（适合需要二次签名/加固等场景）；
     * false = 打包后自动进行 v1+v2 重签名（默认）。
     */
    fun isSigningSkipped(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SKIP_SIGNING, false)
    }

    /**
     * 设置打包 APK 时是否跳过重签名。
     */
    fun setSigningSkipped(context: Context, skip: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SKIP_SIGNING, skip).apply()
    }
}