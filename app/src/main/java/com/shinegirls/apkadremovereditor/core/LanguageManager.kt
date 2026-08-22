package com.shinegirls.apkadremovereditor.core

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * 语言选择管理器。
 *
 * 支持用户在应用内选择界面语言，持久化到 SharedPreferences，
 * 并在每个 Activity 的 attachBaseContext 中通过 [wrapContext] 应用，
 * 实现在不依赖系统语言的情况下切换界面语言。
 *
 * 可选语言（value 为存储的语言标签，沿用 IETF BCP-47）：
 * - system   跟随系统指定语言
 * - zh       简体中文（默认）
 * - zh-TW    繁體中文
 * - en       英文
 * - ja       日文
 * - ko       韩文
 * - es       西班牙文
 */
object LanguageManager {

    private const val PREFS_NAME = "language_prefs"
    private const val KEY_LANG = "language_tag"

    /** 跟随系统 */
    const val SYSTEM = "system"

    /** 支持的取语言标签（含系统）。与 res/values-* 目录一一对应。 */
    private val SUPPORTED = arrayOf(SYSTEM, "zh", "zh-TW", "en", "ja", "ko", "es")

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 读取当前语言标签，默认跟随系统（system）。
     */
    fun getTag(context: Context): String =
        prefs(context).getString(KEY_LANG, SYSTEM) ?: SYSTEM

    /** 当前语言是否为"跟随系统"。 */
    fun isFollowSystem(context: Context): Boolean =
        getTag(context) == SYSTEM

    /**
     * 解析语言标签为 Locale。找不到时回退为默认 Locale。
     */
    fun resolveLocale(tag: String): Locale {
        return try {
            val parts = tag.split("-")
            if (parts.size >= 2) {
                Locale(parts[0], parts[1])
            } else {
                Locale(tag)
            }
        } catch (_: Exception) {
            Locale.getDefault()
        }
    }

    /**
     * 将 Context 包装为应用指定语言的上下文。
     * 在 Activity 的 attachBaseContext 中调用，例如：
     * ```
     * override fun attachBaseContext(newBase: Context) {
     *     super.attachBaseContext(LanguageManager.wrapContext(newBase))
     * }
     * ```
     */
    fun wrapContext(context: Context): Context {
        val tag = prefs(context).getString(KEY_LANG, SYSTEM) ?: SYSTEM
        val locale = if (tag == SYSTEM) Locale.getDefault() else resolveLocale(tag)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        }
        return context.createConfigurationContext(config)
    }

    /**
     * 设置语言并持久化。
     * @return 是否成功
     */
    fun setTag(context: Context, tag: String): Boolean {
        if (!isSupported(tag)) return false
        return try {
            prefs(context).edit().putString(KEY_LANG, tag).apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 是否为受支持的语言标签。 */
    fun isSupported(tag: String): Boolean = tag in SUPPORTED

    /** 所有可选项（含 system）。 */
    fun supportedTags(): Array<String> = SUPPORTED.copyOf()

    /**
     * 语言标签在各自语言中的原生显示名（用于语言选择列表）。
     */
    fun displayName(tag: String): String =
        when (tag) {
            SYSTEM -> "跟随系统 / System"
            "zh" -> "简体中文"
            "zh-TW" -> "繁體中文"
            "en" -> "English"
            "ja" -> "日本語"
            "ko" -> "한국어"
            "es" -> "Español"
            else -> tag
        }
}