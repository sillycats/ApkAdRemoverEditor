package com.shinegirls.apkadremovereditor.core

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

/**
 * 主题模式管理器。
 *
 * 支持三种主题模式：
 * - MODE_SYSTEM  跟随系统（默认）
 * - MODE_LIGHT   始终白天
 * - MODE_DARK    始终夜间
 *
 * 选择持久化到 SharedPreferences，应用启动时调用 [apply] 生效。
 */
object ThemeManager {

    private const val PREFS_NAME = "theme_prefs"
    private const val KEY_MODE = "theme_mode"

    /** 跟随系统 */
    const val MODE_SYSTEM = 0
    /** 白天 */
    const val MODE_LIGHT = 1
    /** 夜间 */
    const val MODE_DARK = 2

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 读取当前主题模式，默认跟随系统。
     */
    fun getMode(context: Context): Int =
        prefs(context).getInt(KEY_MODE, MODE_SYSTEM)

    /**
     * 应用当前主题模式（应在每个 Activity 的 setContentView 之前调用）。
     */
    fun apply(context: Context) {
        val mode = getMode(context)
        AppCompatDelegate.setDefaultNightMode(when (mode) {
            MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        })
    }

    /**
     * 设置主题模式并持久化。
     * @return 是否成功
     */
    fun setMode(context: Context, mode: Int): Boolean {
        return try {
            prefs(context).edit().putInt(KEY_MODE, mode).apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 主题模式显示名称。
     */
    fun modeDisplayName(mode: Int): String =
        when (mode) {
            MODE_LIGHT -> "白天"
            MODE_DARK -> "夜间"
            else -> "跟随系统"
        }
}