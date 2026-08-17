package com.shinegirls.apkadremovereditor.core

import android.content.Context
import com.shinegirls.apkadremovereditor.utils.Format
import com.shinegirls.apkadremovereditor.utils.PathPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 广告特征配置管理器（仅 DEX 相关分类）。
 *
 * 将广告特征以 JSON 配置文件形式存储在外部存储，而非硬编码在 DEX 中。
 * 用户可在设置界面中读取、显示、编辑、删除、添加和保存自定义广告特征。
 *
 * 配置文件路径: /storage/emulated/0/APKEditor/ad_patterns.json
 *
 * 配置结构（仅保留 DEX 修补所需的分类，布局/资源/权限相关分类已移除）:
 * {
 *   "sdk_packages": ["com.google.android.gms.ads", ...],
 *   "class_keywords": ["AdView", "AdActivity", ...],
 *   "method_patterns": ["loadAd", "showAd", ...],
 *   "url_patterns": ["googleads.g.doubleclick.net", ...],
 *   "ad_view_names": ["AdView", "BannerAd", ...],
 *   "ad_activities": ["AdActivity", "InterstitialAdActivity", ...],
 *   "ad_services": ["AdService", "DownloadService", ...],
 *   "ad_receivers": ["AdReceiver", "BootReceiver", ...],
 *   "force_true_methods": ["isVip", "isPro", "isPremium", ...],
 *   "force_false_methods": ["isAdLoaded", "hasAd", "isAdShowing", ...],
 *   "ad_asset_paths": ["assets/gdt_plugin/gdtadv2.jar", "assets/qumeng", ...],
 *   "lib_file_keywords": ["ttad", "gdt", "pangle", "admob", ...],
 *   "asset_keywords": ["gdt", "oneway", "bdxadsdk", "qumeng", ...],
 *   "method_neutralize_keywords": ["showad", "loadad", "onadloaded", ...]
 * }
 */
object AdPatternConfig {

    private const val CONFIG_DIR = Format.EXPORT_DIR
    private const val CONFIG_FILE = "ad_patterns.json"

    // JSON 字段名
    private const val KEY_SDK_PACKAGES = "sdk_packages"
    private const val KEY_CLASS_KEYWORDS = "class_keywords"
    private const val KEY_METHOD_PATTERNS = "method_patterns"
    private const val KEY_URL_PATTERNS = "url_patterns"
    private const val KEY_AD_VIEW_NAMES = "ad_view_names"
    private const val KEY_AD_ACTIVITIES = "ad_activities"
    private const val KEY_AD_SERVICES = "ad_services"
    private const val KEY_AD_RECEIVERS = "ad_receivers"
    private const val KEY_FORCE_TRUE_METHODS = "force_true_methods"
    private const val KEY_FORCE_FALSE_METHODS = "force_false_methods"
    private const val KEY_AD_ASSET_PATHS = "ad_asset_paths"
    private const val KEY_LIB_FILE_KEYWORDS = "lib_file_keywords"
    private const val KEY_ASSET_KEYWORDS = "asset_keywords"
    private const val KEY_METHOD_NEUTRALIZE_KEYWORDS = "method_neutralize_keywords"
    private const val KEY_AD_PERMISSIONS = "ad_permissions"
    private const val KEY_ROOT_FILE_KEYWORDS = "root_file_keywords"
    private const val KEY_RES_LAYOUT_KEYWORDS = "res_layout_keywords"
    private const val KEY_FLUTTER_PATTERNS = "flutter_string_patterns"

    /**
     * 广告特征配置数据类。
     */
    data class AdPatterns(
        val sdkPackages: MutableList<String> = mutableListOf(),
        val classKeywords: MutableList<String> = mutableListOf(),
        val methodPatterns: MutableList<String> = mutableListOf(),
        val urlPatterns: MutableList<String> = mutableListOf(),
        val adViewNames: MutableList<String> = mutableListOf(),
        val adActivities: MutableList<String> = mutableListOf(),
        val adServices: MutableList<String> = mutableListOf(),
        val adReceivers: MutableList<String> = mutableListOf(),
        val forceTrueMethodNames: MutableList<String> = mutableListOf(),
        val forceFalseMethodNames: MutableList<String> = mutableListOf(),
        val adAssetPaths: MutableList<String> = mutableListOf(),
        val libFileKeywords: MutableList<String> = mutableListOf(),
        val assetKeywords: MutableList<String> = mutableListOf(),
        val methodNeutralizeKeywords: MutableList<String> = mutableListOf(),
        val adPermissions: MutableList<String> = mutableListOf(),
        val rootFileKeywords: MutableList<String> = mutableListOf(),
        val resLayoutKeywords: MutableList<String> = mutableListOf(),
        val flutterPatterns: MutableList<String> = mutableListOf()
    ) {
        /**
         * 合并所有广告模式供 DexPatcher 匹配。
         * SDK包名(转换为斜杠格式) + 类名关键词 + Activity/Service/Receiver名称 + View名称
         */
        fun allAdPatterns(): List<String> {
            val result = mutableListOf<String>()
            // SDK包名：点号转换为斜杠（DEX类名格式为 Lcom/google/...;）
            result.addAll(sdkPackages.map { it.replace('.', '/') })
            result.addAll(classKeywords)
            result.addAll(adActivities)
            result.addAll(adServices)
            result.addAll(adReceivers)
            result.addAll(adViewNames)
            return result
        }

        /**
         * 统计总数。
         */
        fun totalCount(): Int =
            sdkPackages.size + classKeywords.size +
            methodPatterns.size + urlPatterns.size + adViewNames.size +
            adActivities.size + adServices.size + adReceivers.size +
            forceTrueMethodNames.size + forceFalseMethodNames.size + adAssetPaths.size +
            libFileKeywords.size + assetKeywords.size +
            methodNeutralizeKeywords.size + adPermissions.size + rootFileKeywords.size +
            resLayoutKeywords.size + flutterPatterns.size
    }

    /**
     * 配置分类信息（用于 UI 显示）。
     */
    enum class Category(val key: String, val displayName: String) {
        SDK_PACKAGES(KEY_SDK_PACKAGES, "广告SDK包名"),
        CLASS_KEYWORDS(KEY_CLASS_KEYWORDS, "广告类名关键词"),
        METHOD_PATTERNS(KEY_METHOD_PATTERNS, "广告方法名"),
        URL_PATTERNS(KEY_URL_PATTERNS, "广告URL/域名"),
        AD_VIEW_NAMES(KEY_AD_VIEW_NAMES, "广告View类名"),
        AD_ACTIVITIES(KEY_AD_ACTIVITIES, "广告Activity"),
        AD_SERVICES(KEY_AD_SERVICES, "广告Service"),
        AD_RECEIVERS(KEY_AD_RECEIVERS, "广告Receiver"),
        FORCE_TRUE_METHODS(KEY_FORCE_TRUE_METHODS, "强制返回true的方法名"),
        FORCE_FALSE_METHODS(KEY_FORCE_FALSE_METHODS, "强制返回false的方法名"),
        AD_ASSET_PATHS(KEY_AD_ASSET_PATHS, "assets广告文件路径"),
        LIB_FILE_KEYWORDS(KEY_LIB_FILE_KEYWORDS, "广告SDK原生库关键词"),
        ASSET_KEYWORDS(KEY_ASSET_KEYWORDS, "assets广告关键词"),
        METHOD_NEUTRALIZE_KEYWORDS(KEY_METHOD_NEUTRALIZE_KEYWORDS, "广告方法置空关键词"),
        AD_PERMISSIONS(KEY_AD_PERMISSIONS, "广告权限特征"),
        ROOT_FILE_KEYWORDS(KEY_ROOT_FILE_KEYWORDS, "APK根目录文件关键词"),
        RES_LAYOUT_KEYWORDS(KEY_RES_LAYOUT_KEYWORDS, "Res布局广告View关键词"),
        FLUTTER_PATTERNS(KEY_FLUTTER_PATTERNS, "Flutter 字符串特征")
    }

    /**
     * 获取配置文件路径。
     * 若提供 context，则使用用户自定义路径（如有）。
     */
    fun getConfigFile(context: Context? = null): File {
        if (context != null) {
            return File(PathPreferences.getConfigFilePath(context))
        }
        return File(CONFIG_DIR, CONFIG_FILE)
    }

    /**
     * 确保配置目录存在。
     * 若提供 context，则使用用户自定义路径。
     */
    private fun ensureConfigDir(context: Context? = null): File {
        val dir = if (context != null) {
            File(PathPreferences.getConfigFilePath(context)).parentFile
        } else {
            File(CONFIG_DIR)
        } ?: File(CONFIG_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * 从 JSON 文件加载广告特征配置。
     * 如果文件不存在，则从 assets 读取默认配置并保存到外部存储。
     */
    fun loadConfig(context: Context): AdPatterns {
        val configFile = getConfigFile(context)
        if (!configFile.exists()) {
            val defaults = getDefaultConfig(context)
            saveConfig(defaults, context)
            return defaults
        }

        return try {
            val jsonStr = configFile.readText(Charsets.UTF_8)
            val json = JSONObject(jsonStr)
            // 默认配置：用于对旧版配置文件中缺失的字段自动回填，保证新增分类能生效
            val defaults = getDefaultConfig(context)

            AdPatterns(
                sdkPackages = jsonToStringList(json, KEY_SDK_PACKAGES),
                classKeywords = jsonToStringList(json, KEY_CLASS_KEYWORDS),
                methodPatterns = jsonToStringList(json, KEY_METHOD_PATTERNS),
                urlPatterns = jsonToStringList(json, KEY_URL_PATTERNS),
                adViewNames = jsonToStringList(json, KEY_AD_VIEW_NAMES),
                adActivities = jsonToStringList(json, KEY_AD_ACTIVITIES),
                adServices = jsonToStringList(json, KEY_AD_SERVICES),
                adReceivers = jsonToStringList(json, KEY_AD_RECEIVERS),
                forceTrueMethodNames = jsonToStringList(json, KEY_FORCE_TRUE_METHODS),
                forceFalseMethodNames = jsonToStringList(json, KEY_FORCE_FALSE_METHODS),
                adAssetPaths = jsonToStringListOrDefault(json, KEY_AD_ASSET_PATHS, defaults.adAssetPaths),
                libFileKeywords = jsonToStringListOrDefault(json, KEY_LIB_FILE_KEYWORDS, defaults.libFileKeywords),
                assetKeywords = jsonToStringListOrDefault(json, KEY_ASSET_KEYWORDS, defaults.assetKeywords),
                methodNeutralizeKeywords = jsonToStringListOrDefault(
                    json,
                    KEY_METHOD_NEUTRALIZE_KEYWORDS,
                    defaults.methodNeutralizeKeywords
                ),
                adPermissions = jsonToStringListOrDefault(
                    json,
                    KEY_AD_PERMISSIONS,
                    defaults.adPermissions
                ),
                rootFileKeywords = jsonToStringListOrDefault(
                    json,
                    KEY_ROOT_FILE_KEYWORDS,
                    defaults.rootFileKeywords
                ),
                resLayoutKeywords = jsonToStringListOrDefault(
                    json,
                    KEY_RES_LAYOUT_KEYWORDS,
                    defaults.resLayoutKeywords
                ),
                flutterPatterns = jsonToStringList(json, KEY_FLUTTER_PATTERNS)
            )
        } catch (_: Exception) {
            // 配置文件损坏，恢复默认
            val defaults = getDefaultConfig(context)
            saveConfig(defaults, context)
            defaults
        }
    }

    /**
     * 保存广告特征配置到 JSON 文件。
     * 若提供 context，则使用用户自定义路径。
     */
    fun saveConfig(config: AdPatterns, context: Context? = null): Boolean {
        return try {
            ensureConfigDir(context)
            val json = JSONObject()

            json.put(KEY_SDK_PACKAGES, listToJsonArray(config.sdkPackages))
            json.put(KEY_CLASS_KEYWORDS, listToJsonArray(config.classKeywords))
            json.put(KEY_METHOD_PATTERNS, listToJsonArray(config.methodPatterns))
            json.put(KEY_URL_PATTERNS, listToJsonArray(config.urlPatterns))
            json.put(KEY_AD_VIEW_NAMES, listToJsonArray(config.adViewNames))
            json.put(KEY_AD_ACTIVITIES, listToJsonArray(config.adActivities))
            json.put(KEY_AD_SERVICES, listToJsonArray(config.adServices))
            json.put(KEY_AD_RECEIVERS, listToJsonArray(config.adReceivers))
            json.put(KEY_FORCE_TRUE_METHODS, listToJsonArray(config.forceTrueMethodNames))
            json.put(KEY_FORCE_FALSE_METHODS, listToJsonArray(config.forceFalseMethodNames))
            json.put(KEY_AD_ASSET_PATHS, listToJsonArray(config.adAssetPaths))
            json.put(KEY_LIB_FILE_KEYWORDS, listToJsonArray(config.libFileKeywords))
            json.put(KEY_ASSET_KEYWORDS, listToJsonArray(config.assetKeywords))
            json.put(KEY_METHOD_NEUTRALIZE_KEYWORDS, listToJsonArray(config.methodNeutralizeKeywords))
            json.put(KEY_AD_PERMISSIONS, listToJsonArray(config.adPermissions))
            json.put(KEY_ROOT_FILE_KEYWORDS, listToJsonArray(config.rootFileKeywords))
            json.put(KEY_RES_LAYOUT_KEYWORDS, listToJsonArray(config.resLayoutKeywords))
            json.put(KEY_FLUTTER_PATTERNS, listToJsonArray(config.flutterPatterns))

            getConfigFile(context).writeText(json.toString(2), Charsets.UTF_8)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 重置为默认配置。
     */
    fun resetToDefault(context: Context): AdPatterns {
        val defaults = getDefaultConfig(context)
        saveConfig(defaults, context)
        return defaults
    }

    /**
     * 从配置中获取指定分类的列表。
     */
    fun getCategoryList(config: AdPatterns, category: Category): MutableList<String> {
        return when (category) {
            Category.SDK_PACKAGES -> config.sdkPackages
            Category.CLASS_KEYWORDS -> config.classKeywords
            Category.METHOD_PATTERNS -> config.methodPatterns
            Category.URL_PATTERNS -> config.urlPatterns
            Category.AD_VIEW_NAMES -> config.adViewNames
            Category.AD_ACTIVITIES -> config.adActivities
            Category.AD_SERVICES -> config.adServices
            Category.AD_RECEIVERS -> config.adReceivers
            Category.FORCE_TRUE_METHODS -> config.forceTrueMethodNames
            Category.FORCE_FALSE_METHODS -> config.forceFalseMethodNames
            Category.AD_ASSET_PATHS -> config.adAssetPaths
            Category.LIB_FILE_KEYWORDS -> config.libFileKeywords
            Category.ASSET_KEYWORDS -> config.assetKeywords
            Category.METHOD_NEUTRALIZE_KEYWORDS -> config.methodNeutralizeKeywords
            Category.AD_PERMISSIONS -> config.adPermissions
            Category.ROOT_FILE_KEYWORDS -> config.rootFileKeywords
            Category.RES_LAYOUT_KEYWORDS -> config.resLayoutKeywords
            Category.FLUTTER_PATTERNS -> config.flutterPatterns
        }
    }

    /**
     * 将配置序列化为 JSONObject（用于订阅口令内嵌完整配置）。
     */
    fun toJson(config: AdPatterns): JSONObject {
        val json = JSONObject()
        json.put(KEY_SDK_PACKAGES, listToJsonArray(config.sdkPackages))
        json.put(KEY_CLASS_KEYWORDS, listToJsonArray(config.classKeywords))
        json.put(KEY_METHOD_PATTERNS, listToJsonArray(config.methodPatterns))
        json.put(KEY_URL_PATTERNS, listToJsonArray(config.urlPatterns))
        json.put(KEY_AD_VIEW_NAMES, listToJsonArray(config.adViewNames))
        json.put(KEY_AD_ACTIVITIES, listToJsonArray(config.adActivities))
        json.put(KEY_AD_SERVICES, listToJsonArray(config.adServices))
        json.put(KEY_AD_RECEIVERS, listToJsonArray(config.adReceivers))
        json.put(KEY_FORCE_TRUE_METHODS, listToJsonArray(config.forceTrueMethodNames))
        json.put(KEY_FORCE_FALSE_METHODS, listToJsonArray(config.forceFalseMethodNames))
        json.put(KEY_AD_ASSET_PATHS, listToJsonArray(config.adAssetPaths))
        json.put(KEY_LIB_FILE_KEYWORDS, listToJsonArray(config.libFileKeywords))
        json.put(KEY_ASSET_KEYWORDS, listToJsonArray(config.assetKeywords))
        json.put(KEY_METHOD_NEUTRALIZE_KEYWORDS, listToJsonArray(config.methodNeutralizeKeywords))
        json.put(KEY_AD_PERMISSIONS, listToJsonArray(config.adPermissions))
        json.put(KEY_ROOT_FILE_KEYWORDS, listToJsonArray(config.rootFileKeywords))
        json.put(KEY_RES_LAYOUT_KEYWORDS, listToJsonArray(config.resLayoutKeywords))
        json.put(KEY_FLUTTER_PATTERNS, listToJsonArray(config.flutterPatterns))
        return json
    }

    /**
     * 从 JSONObject 反序列化为配置（用于订阅口令内嵌完整配置的解析）。
     * 缺失字段自动回填默认值。
     */
    fun fromJson(json: JSONObject, context: Context): AdPatterns {
        val defaults = getDefaultConfig(context)
        return AdPatterns(
            sdkPackages = jsonToStringList(json, KEY_SDK_PACKAGES),
            classKeywords = jsonToStringList(json, KEY_CLASS_KEYWORDS),
            methodPatterns = jsonToStringList(json, KEY_METHOD_PATTERNS),
            urlPatterns = jsonToStringList(json, KEY_URL_PATTERNS),
            adViewNames = jsonToStringList(json, KEY_AD_VIEW_NAMES),
            adActivities = jsonToStringList(json, KEY_AD_ACTIVITIES),
            adServices = jsonToStringList(json, KEY_AD_SERVICES),
            adReceivers = jsonToStringList(json, KEY_AD_RECEIVERS),
            forceTrueMethodNames = jsonToStringList(json, KEY_FORCE_TRUE_METHODS),
            forceFalseMethodNames = jsonToStringList(json, KEY_FORCE_FALSE_METHODS),
            adAssetPaths = jsonToStringListOrDefault(json, KEY_AD_ASSET_PATHS, defaults.adAssetPaths),
            libFileKeywords = jsonToStringListOrDefault(json, KEY_LIB_FILE_KEYWORDS, defaults.libFileKeywords),
            assetKeywords = jsonToStringListOrDefault(json, KEY_ASSET_KEYWORDS, defaults.assetKeywords),
            methodNeutralizeKeywords = jsonToStringListOrDefault(
                json, KEY_METHOD_NEUTRALIZE_KEYWORDS, defaults.methodNeutralizeKeywords
            ),
            adPermissions = jsonToStringListOrDefault(json, KEY_AD_PERMISSIONS, defaults.adPermissions),
            rootFileKeywords = jsonToStringListOrDefault(json, KEY_ROOT_FILE_KEYWORDS, defaults.rootFileKeywords),
            resLayoutKeywords = jsonToStringListOrDefault(json, KEY_RES_LAYOUT_KEYWORDS, defaults.resLayoutKeywords),
            flutterPatterns = jsonToStringList(json, KEY_FLUTTER_PATTERNS)
        )
    }

    /**
     * 将多个配置合并为一个（并集去重）。
     *
     * 用于多订阅同时开启时，将各订阅的配置合并为一份统一应用。
     */
    fun merge(configs: List<AdPatterns>): AdPatterns {
        if (configs.isEmpty()) return AdPatterns()
        if (configs.size == 1) return configs[0]
        return AdPatterns(
            sdkPackages = configs.flatMap { it.sdkPackages }.distinct().toMutableList(),
            classKeywords = configs.flatMap { it.classKeywords }.distinct().toMutableList(),
            methodPatterns = configs.flatMap { it.methodPatterns }.distinct().toMutableList(),
            urlPatterns = configs.flatMap { it.urlPatterns }.distinct().toMutableList(),
            adViewNames = configs.flatMap { it.adViewNames }.distinct().toMutableList(),
            adActivities = configs.flatMap { it.adActivities }.distinct().toMutableList(),
            adServices = configs.flatMap { it.adServices }.distinct().toMutableList(),
            adReceivers = configs.flatMap { it.adReceivers }.distinct().toMutableList(),
            forceTrueMethodNames = configs.flatMap { it.forceTrueMethodNames }.distinct().toMutableList(),
            forceFalseMethodNames = configs.flatMap { it.forceFalseMethodNames }.distinct().toMutableList(),
            adAssetPaths = configs.flatMap { it.adAssetPaths }.distinct().toMutableList(),
            libFileKeywords = configs.flatMap { it.libFileKeywords }.distinct().toMutableList(),
            assetKeywords = configs.flatMap { it.assetKeywords }.distinct().toMutableList(),
            methodNeutralizeKeywords = configs.flatMap { it.methodNeutralizeKeywords }.distinct().toMutableList(),
            adPermissions = configs.flatMap { it.adPermissions }.distinct().toMutableList(),
            rootFileKeywords = configs.flatMap { it.rootFileKeywords }.distinct().toMutableList(),
            resLayoutKeywords = configs.flatMap { it.resLayoutKeywords }.distinct().toMutableList(),
            flutterPatterns = configs.flatMap { it.flutterPatterns }.distinct().toMutableList()
        )
    }

    // ========== JSON 辅助方法 ==========

    private fun jsonToStringList(json: JSONObject, key: String): MutableList<String> {
        val result = mutableListOf<String>()
        if (!json.has(key)) return result
        val arr = json.getJSONArray(key)
        for (i in 0 until arr.length()) {
            result.add(arr.getString(i))
        }
        return result
    }

    /**
     * 读取 JSON 数组；若该字段缺失，则回填默认列表（用于旧版配置的平滑升级）。
     */
    private fun jsonToStringListOrDefault(
        json: JSONObject,
        key: String,
        defaults: List<String>
    ): MutableList<String> {
        if (!json.has(key)) {
            return defaults.toMutableList()
        }
        return jsonToStringList(json, key)
    }

    private fun listToJsonArray(list: List<String>): JSONArray {
        val arr = JSONArray()
        for (item in list) {
            arr.put(item)
        }
        return arr
    }

    // ========== 默认配置 ==========

    /**
     * 从 assets 内置 JSON 文件读取默认广告特征配置。
     * 文件路径: assets/ad_patterns_default.json
     * SDK包名以点号格式存储（com.google.android.gms.ads），
     * 在 DEX 匹配时会自动转换为斜杠格式（com/google/android/gms/ads）。
     */
    fun getDefaultConfig(context: Context): AdPatterns {
        return try {
            context.assets.open("ad_patterns_default.json").use { inputStream ->
                val jsonStr = inputStream.bufferedReader().readText()
                val json = JSONObject(jsonStr)
                AdPatterns(
                    sdkPackages = jsonToStringList(json, KEY_SDK_PACKAGES),
                    classKeywords = jsonToStringList(json, KEY_CLASS_KEYWORDS),
                    methodPatterns = jsonToStringList(json, KEY_METHOD_PATTERNS),
                    urlPatterns = jsonToStringList(json, KEY_URL_PATTERNS),
                    adViewNames = jsonToStringList(json, KEY_AD_VIEW_NAMES),
                    adActivities = jsonToStringList(json, KEY_AD_ACTIVITIES),
                    adServices = jsonToStringList(json, KEY_AD_SERVICES),
                    adReceivers = jsonToStringList(json, KEY_AD_RECEIVERS),
                    forceTrueMethodNames = jsonToStringList(json, KEY_FORCE_TRUE_METHODS),
                    forceFalseMethodNames = jsonToStringList(json, KEY_FORCE_FALSE_METHODS),
                    adAssetPaths = jsonToStringList(json, KEY_AD_ASSET_PATHS),
                    libFileKeywords = jsonToStringList(json, KEY_LIB_FILE_KEYWORDS),
                    assetKeywords = jsonToStringList(json, KEY_ASSET_KEYWORDS),
                    methodNeutralizeKeywords = jsonToStringList(json, KEY_METHOD_NEUTRALIZE_KEYWORDS),
                    adPermissions = jsonToStringList(json, KEY_AD_PERMISSIONS),
                    rootFileKeywords = jsonToStringList(json, KEY_ROOT_FILE_KEYWORDS),
                    resLayoutKeywords = jsonToStringList(json, KEY_RES_LAYOUT_KEYWORDS),
                    flutterPatterns = jsonToStringList(json, KEY_FLUTTER_PATTERNS)
                )
            }
        } catch (e: Exception) {
            // assets 文件缺失或损坏时的最小化兜底
            AdPatterns(
                sdkPackages = mutableListOf("com.google.android.gms.ads"),
                classKeywords = mutableListOf("AdView", "AdActivity")
            )
        }
    }
}