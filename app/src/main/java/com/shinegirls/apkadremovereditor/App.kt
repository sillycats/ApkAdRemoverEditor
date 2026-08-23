package com.shinegirls.apkadremovereditor

import android.app.Application
import android.content.Context
import com.shinegirls.apkadremovereditor.core.LanguageManager

/**
 * 应用入口。
 *
 * 在 attachBaseContext 时按用户设置的语言包装全局资源，
 * 供核心引擎（无 Context 处）通过 [LanguageManager.appContext] /
 * [LanguageManager.str] 获取本地化字符串，保证所有日志、弹窗、
 * 对话框等文字均随设置的语言自动切换。
 */
class App : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LanguageManager.wrapContext(base))
        LanguageManager.init(this)
    }
}