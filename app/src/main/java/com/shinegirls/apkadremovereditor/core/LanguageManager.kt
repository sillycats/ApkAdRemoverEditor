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
 * - fr       法文
 * - de       德文
 * - it       意大利文
 * - pt       葡萄牙文
 * - ru       俄文
 * - hi       印地文
 * - vi       越南文
 * - th       泰文
 * - id       印尼文
 * - ar       阿拉伯文
 * - tr       土耳其文
 */
object LanguageManager {

    private const val PREFS_NAME = "language_prefs"
    private const val KEY_LANG = "language_tag"

    /** 全局应用上下文，由 App 在 attachBaseContext 中注入，供无 Context 的核心引擎取字符串资源。 */
    lateinit var appContext: Context
        private set

    /** 应用第一次启动时的原始系统 base context（未做语言包装）。 */
    private var baseContext: Context? = null

    /** 由 [App] 注入全局应用上下文（已按当前语言包装）。 */
    fun init(context: Context) {
        // 首次注入时记录未包装的系统 base，供后续切换语言时重建 appContext
        if (baseContext == null) {
            @Suppress("DEPRECATION")
            baseContext = context.createConfigurationContext(context.resources.configuration)
        }
        appContext = context
    }

    /**
     * 语言切换后重建全局 appContext，使无 Context 的核心引擎 / Toast /
     * 对话框等通过 [str] 立即使用新语言，而不必等待进程重启。
     * @param trigger 触发者上下文（任意 Activity/Application，用于读系统配置）
     */
    fun refreshAppContext(trigger: Context) {
        val base = baseContext ?: trigger.getApplicationContext()
        val tag = getTag(base)
        val locale = if (tag == SYSTEM) systemLocale(base) else resolveLocale(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        }
        try {
            appContext = base.createConfigurationContext(config)
        } catch (_: Exception) {
            // 极少数厂商 ROM 对该 API 支持不佳，保持旧上下文
        }
    }

    /** 跟随系统 */
    const val SYSTEM = "system"

    /** 支持的取语言标签（含系统）。与 res/values-* 目录一一对应。 */
    private val SUPPORTED = arrayOf(
        SYSTEM, "zh", "zh-TW", "en", "ja", "ko", "es",
        "fr", "de", "it", "pt", "ru", "hi", "vi", "th", "id", "ar", "tr"
    )

    /** 依赖全局默认语言：简体中文，避免跟随系统时出现韩文等非预期界面。 */
    const val DEFAULT_TAG = "zh"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 读取当前语言标签，默认简体中文（zh）。
     */
    fun getTag(context: Context): String =
        prefs(context).getString(KEY_LANG, DEFAULT_TAG) ?: DEFAULT_TAG

    /** 当前语言是否为"跟随系统"。 */
    fun isFollowSystem(context: Context): Boolean =
        getTag(context) == SYSTEM

    /**
     * 解析语言标签为 Locale。找不到时回退为默认 Locale。
     * 使用 Locale.forLanguageTag 以正确解析各大洲语言代码（id/in/zh-CN 等），
     * 并保证返回稳定的 Locale 以命中 res/values-* 目录。
     */
    fun resolveLocale(tag: String): Locale {
        if (tag.isBlank()) return LanguageLocale
        return try {
            val locale = Locale.forLanguageTag(tag.replace('_', '-'))
            if (locale.language.isEmpty()) LanguageLocale else locale
        } catch (_: Exception) {
            LanguageLocale
        }
    }

    /**
     * 全局语言默认 Locale：简体中文，避免跟随系统时出现非预期界面。
     */
    val LanguageLocale: Locale
        get() = Locale.forLanguageTag(DEFAULT_TAG)

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
        val tag = prefs(context).getString(KEY_LANG, DEFAULT_TAG) ?: DEFAULT_TAG
        // 跟随系统时从系统配置读取真实系统语言，避免被 Locale.setDefault 污染；
        // 自定义选择时使用所选语言。
        val locale = if (tag == SYSTEM) systemLocale(context) else resolveLocale(tag)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        }
        return context.createConfigurationContext(config)
    }

    /**
     * 获取系统当前语言。优先从系统配置读取（真实系统语言），
     * 仅在极端情况下回退到默认 Locale。
     */
    private fun systemLocale(context: Context): Locale {
        val config = context.resources.configuration
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            config.locales[0] ?: Locale.getDefault()
        } else {
            config.locale ?: Locale.getDefault()
        }
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

    /**
     * 直接用全局应用上下文获取本地化字符串，供核心引擎（无 Context 处）使用。
     * @see appContext
     */
    fun str(resId: Int, vararg args: Any): String =
        appContext.getString(resId, *args)

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
            "fr" -> "Français"
            "de" -> "Deutsch"
            "it" -> "Italiano"
            "pt" -> "Português"
            "ru" -> "Русский"
            "hi" -> "हिन्दी"
            "vi" -> "Tiếng Việt"
            "th" -> "ไทย"
            "id" -> "Bahasa Indonesia"
            "ar" -> "العربية"
            "tr" -> "Türkçe"
            else -> tag
        }
}