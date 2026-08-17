package com.shinegirls.apkadremovereditor.core

import android.app.Activity
import android.os.Looper
import android.view.WindowManager

/**
 * 屏幕常亮控制器。
 *
 * 用于在处理 APK 过程中保持屏幕常亮，防止处理到一半时突然黑屏锁屏导致处理失败；
 * 处理完毕后调用 [setKeepScreenOn] 关闭常亮，恢复正常锁屏。
 *
 * 使用 FLAG_KEEP_SCREEN_ON（无需任何权限）：
 * 该 flag 在 Activity 可见期间持续生效，且不会阻止系统睡眠，
 * 仅阻止屏幕自动关闭，是最轻量、最安全的保屏方案。
 */
object ScreenKeeper {

    /**
     * 设置/关闭屏幕常亮。
     *
     * @param activity 当前 Activity
     * @param keepOn true 开启常亮，false 关闭
     */
    fun setKeepScreenOn(activity: Activity, keepOn: Boolean) {
        val run = {
            try {
                val window = activity.window
                if (keepOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            } catch (_: Exception) {
                // 忽略异常，不影响处理流程
            }
        }
        // 仅在非 UI 线程时切换，避免在主线程多一次排队
        if (Looper.myLooper() == Looper.getMainLooper()) {
            run()
        } else {
            activity.runOnUiThread(run)
        }
    }
}