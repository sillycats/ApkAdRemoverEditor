package com.shinegirls.apkadremovereditor

import android.os.Bundle
import android.util.Log
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shinegirls.apkadremovereditor.core.AdPatternConfig
import com.shinegirls.apkadremovereditor.core.AdPatternConfig.Category

import com.shinegirls.apkadremovereditor.core.SubscriptionManager
import com.shinegirls.apkadremovereditor.core.SubscriptionManager.Subscription
import com.shinegirls.apkadremovereditor.core.ThemeManager
import com.shinegirls.apkadremovereditor.core.LanguageManager
import com.shinegirls.apkadremovereditor.utils.PathPreferences
import com.shinegirls.apkadremovereditor.utils.UiUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 广告特征配置设置界面。
 *
 * 功能：
 * - 读取并显示当前配置文件中的广告特征
 * - 按分类显示各特征条目数量
 * - 点击"管理"进入特征列表，可查看、编辑、删除、添加单条特征
 * - 保存配置到 JSON 文件
 * - 重置为默认配置
 */
class SettingsActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.wrapContext(newBase))
    }

    private lateinit var tvConfigPath: TextView
    private lateinit var tvConfigStats: TextView
    private lateinit var tvThemeMode: TextView
    private lateinit var tvLanguageMode: TextView
    private lateinit var btnSave: MaterialButton
    private lateinit var tvSubscriptionStats: TextView
    private lateinit var btnManageSubscriptions: MaterialButton
    private lateinit var tvOutputDirSetting: TextView

    private var config: AdPatternConfig.AdPatterns = AdPatternConfig.AdPatterns()

    // 分类卡片视图引用
    private val categoryCards = mutableMapOf<Category, View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        // 应用持久化的主题模式（必须在 setContentView 之前），
        // 与 MainActivity 一致，避免主题模式切换后进入设置页时资源错配导致闪退。
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_settings)
        } catch (e: Exception) {
            Log.e("SettingsActivity", getString(R.string.h_06b81d3d), e)
            UiUtils.error(this, getString(R.string.h_23348314, e.message))
            finish()
            return
        }

        try {
            // 自定义头部：返回按钮 + 可换行的标题/副标题
            findViewById<ImageButton>(R.id.btnSettingsBack)?.setOnClickListener { finish() }

            tvConfigPath = findViewById(R.id.tvConfigPath)
            tvConfigStats = findViewById(R.id.tvConfigStats)
            btnSave = findViewById(R.id.btnSave)
            tvSubscriptionStats = findViewById(R.id.tvSubscriptionStats)
            btnManageSubscriptions = findViewById(R.id.btnManageSubscriptions)
            tvThemeMode = findViewById(R.id.tvThemeMode)

            // 加载配置
            loadAndDisplayConfig()

            // 主题切换（外观设置）
            updateThemeDisplay()
            findViewById<MaterialButton>(R.id.btnChangeTheme).setOnClickListener {
                showThemeDialog()
            }

            // 语言切换（多语言设置）
            tvLanguageMode = findViewById(R.id.tvLanguageMode)
            updateLanguageDisplay()
            findViewById<MaterialButton>(R.id.btnChangeLanguage).setOnClickListener {
                showLanguageDialog()
            }

            // 订阅源管理按钮
            btnManageSubscriptions.setOnClickListener {
                showSubscriptionListDialog()
            }
            updateSubscriptionStats()

            // 路径设置
            tvOutputDirSetting = findViewById(R.id.tvOutputDirSetting)
            updatePathDisplay()

            findViewById<MaterialButton>(R.id.btnChangeConfigPath).setOnClickListener {
                showChangePathDialog(isConfigPath = true)
            }
            findViewById<MaterialButton>(R.id.btnResetConfigPath).setOnClickListener {
                PathPreferences.resetConfigPath(this)
                updatePathDisplay()
                loadAndDisplayConfig()
                UiUtils.success(this, getString(R.string.h_d8a58722))
            }
            findViewById<MaterialButton>(R.id.btnChangeOutputDir).setOnClickListener {
                showChangePathDialog(isConfigPath = false)
            }
            findViewById<MaterialButton>(R.id.btnResetOutputDir).setOnClickListener {
                PathPreferences.resetOutputDir(this)
                updatePathDisplay()
                UiUtils.success(this, getString(R.string.h_c6af077e))
            }

            // 保存按钮：单击保存，长按重置默认
            btnSave.setOnClickListener {
                val success = AdPatternConfig.saveConfig(config, this)
                if (success) {
                    UiUtils.success(this, getString(R.string.h_e9472910))
                    updateStats()
                } else {
                    UiUtils.error(this, getString(R.string.h_01d3d9f4))
                }
            }
            btnSave.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.h_300739f4))
                    .setMessage(getString(R.string.h_0e13f623))
                    .setPositiveButton(getString(R.string.h_4b9c3271)) { _, _ ->
                        config = AdPatternConfig.resetToDefault(this)
                        displayConfig()
                        UiUtils.success(this, getString(R.string.h_0fd47041))
                    }
                    .setNegativeButton(getString(R.string.s_625fb26b), null)
                    .show()
                true
            }
        } catch (e: Exception) {
            Log.e("SettingsActivity", getString(R.string.h_ab94e2c3), e)
            UiUtils.error(this, getString(R.string.h_c20905d1, e.message))
            finish()
        }
    }

    /**
     * 更新外观卡片中当前主题模式的显示。
     */
    private fun updateThemeDisplay() {
        tvThemeMode.text = getString(ThemeManager.modeDisplayNameRes(ThemeManager.getMode(this)))
    }

    /**
     * 主题切换对话框：跟随系统 / 白天 / 夜间。
     * 使用自定义卡片式布局，三种模式配图标与说明，选中项高亮。
     * 选中后持久化并重启 Activity 以应用新主题。
     */
    private fun showThemeDialog() {
        val current = ThemeManager.getMode(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_theme_choice, null)

        val optionSystem = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.optionSystem)
        val optionLight = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.optionLight)
        val optionDark = dialogView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.optionDark)

        val checkSystem = dialogView.findViewById<View>(R.id.ivSystemCheck)
        val checkLight = dialogView.findViewById<View>(R.id.ivLightCheck)
        val checkDark = dialogView.findViewById<View>(R.id.ivDarkCheck)

        // 高亮当前选中项
        fun resetSelection() {
            fun unselect(card: com.google.android.material.card.MaterialCardView, check: View) {
                card.strokeWidth = 1
                card.strokeColor = ContextCompat.getColor(this, R.color.primary_light)
                check.visibility = View.INVISIBLE
            }
            unselect(optionSystem, checkSystem)
            unselect(optionLight, checkLight)
            unselect(optionDark, checkDark)
        }

        fun select(card: com.google.android.material.card.MaterialCardView, check: View) {
            card.strokeWidth = 2
            card.strokeColor = ContextCompat.getColor(this, R.color.accent)
            check.visibility = View.VISIBLE
        }

        fun applySelection(mode: Int) {
            resetSelection()
            when (mode) {
                ThemeManager.MODE_LIGHT -> select(optionLight, checkLight)
                ThemeManager.MODE_DARK -> select(optionDark, checkDark)
                else -> select(optionSystem, checkSystem)
            }
        }

        applySelection(current)

        fun choose(mode: Int) {
            if (mode != current) {
                ThemeManager.setMode(this, mode)
                recreate()
            } else {
                applySelection(mode)
            }
        }

        optionSystem.setOnClickListener { choose(ThemeManager.MODE_SYSTEM) }
        optionLight.setOnClickListener { choose(ThemeManager.MODE_LIGHT) }
        optionDark.setOnClickListener { choose(ThemeManager.MODE_DARK) }

        val themeDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton(getString(R.string.s_625fb26b), null)
            .create()
        themeDialog.show()
        // 自适应屏幕：内容过长时限制高度并滚动，避免溢出屏幕
        UiUtils.fitDialogToScreen(themeDialog)
    }

    /**
     * 更新语言卡片中当前语言的显示名称。
     */
    private fun updateLanguageDisplay() {
        val tag = LanguageManager.getTag(this)
        tvLanguageMode.text = LanguageManager.displayName(tag)
    }

    /**
     * 语言切换对话框：跟随系统 / 简体 / 繁體 / 英文 / 日文 / 韩文 / 西班牙文。
     * 选项基于 [LanguageManager.supportedTags] 动态构建，选中项高亮，
     * 选中后持久化并重启当前页面以立即应用新语言。
     */
    private fun showLanguageDialog() {
        val current = LanguageManager.getTag(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_language_choice, null)
        val container = dialogView.findViewById<LinearLayout>(R.id.llLanguageOptions)
        container.removeAllViews()

        // 每个选项占位（用于高亮/取消高亮）
        val cards = mutableListOf<com.google.android.material.card.MaterialCardView>()
        val checks = mutableListOf<View>()

        val langDialogHolder = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton(getString(R.string.s_625fb26b), null)
            .create()

        fun resetSelection() {
            for (i in cards.indices) {
                cards[i].strokeWidth = 1
                cards[i].strokeColor = ContextCompat.getColor(this, R.color.primary_light)
                checks[i].visibility = View.INVISIBLE
            }
        }

        fun select(tag: String) {
            resetSelection()
            for (i in cards.indices) {
                if (container.getChildAt(i)?.tag == tag) {
                    cards[i].strokeWidth = 2
                    cards[i].strokeColor = ContextCompat.getColor(this, R.color.accent)
                    checks[i].visibility = View.VISIBLE
                    break
                }
            }
        }

        fun choose(tag: String) {
            if (tag == current) {
                select(tag)
                return
            }
            LanguageManager.setTag(this, tag)
            // 同步刷新全局 appContext，使核心引擎/Toast/对话框立即使用新语言
            LanguageManager.refreshAppContext(this)
            langDialogHolder.dismiss()
            // 语言影响全局所有页面，重启主界面并清空返回栈，让整体界面立即以新语言呈现。
            restartApp()
        }

        for (tag in LanguageManager.supportedTags()) {
            val row = layoutInflater.inflate(R.layout.item_language_choice, container, false)
            val card = row.findViewById<com.google.android.material.card.MaterialCardView>(R.id.optionLang)
            val check = row.findViewById<View>(R.id.ivLangCheck)
            row.findViewById<TextView>(R.id.tvLangName).text = LanguageManager.displayName(tag)
            cards.add(card)
            checks.add(check)
            row.tag = tag

            card.setOnClickListener { v ->
                choose(v.tag as String)
            }
            container.addView(row)
        }

        // 标注当前选中项
        select(current)

        langDialogHolder.show()
        UiUtils.fitDialogToScreen(langDialogHolder)
    }

    /**
     * 重启应用主界面并清空 Activity 返回栈，用于语言切换后让全部页面统一应用新语言。
     */
    private fun restartApp() {
        try {
            val launcher = packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(this, MainActivity::class.java)
            launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(launcher)
        } catch (_: Exception) {
            recreate()
        }
    }

    /**
     * 加载配置并显示。
     */
    private fun loadAndDisplayConfig() {
        try {
            config = AdPatternConfig.loadConfig(this)
        } catch (e: Exception) {
            Log.e("SettingsActivity", getString(R.string.h_327638c1), e)
            config = AdPatternConfig.AdPatterns()
        }
        displayConfig()
    }

    /**
     * 显示配置内容到 UI。
     */
    private fun displayConfig() {
        tvConfigPath.text = AdPatternConfig.getConfigFile(this).absolutePath
        updateStats()

        // 设置分组标题
        setSectionTitle(R.id.sectionDex, getString(R.string.h_42f52a63), getString(R.string.h_9f4a2d63))
        setSectionTitle(R.id.sectionRes, getString(R.string.h_db0dc430), getString(R.string.h_ee7d1dad))
        setSectionTitle(R.id.sectionManifest, getString(R.string.h_91337ff2), getString(R.string.h_92fab849))

        // 绑定各分类卡片
        bindCategoryCard(R.id.cardSdkPackages, Category.SDK_PACKAGES)
        bindCategoryCard(R.id.cardClassKeywords, Category.CLASS_KEYWORDS)
        bindCategoryCard(R.id.cardMethodPatterns, Category.METHOD_PATTERNS)
        bindCategoryCard(R.id.cardUrlPatterns, Category.URL_PATTERNS)
        bindCategoryCard(R.id.cardAdViewNames, Category.AD_VIEW_NAMES)
        bindCategoryCard(R.id.cardAdActivities, Category.AD_ACTIVITIES)
        bindCategoryCard(R.id.cardAdServices, Category.AD_SERVICES)
        bindCategoryCard(R.id.cardAdReceivers, Category.AD_RECEIVERS)
        bindCategoryCard(R.id.cardForceTrueMethods, Category.FORCE_TRUE_METHODS)
        bindCategoryCard(R.id.cardForceFalseMethods, Category.FORCE_FALSE_METHODS)
        bindCategoryCard(R.id.cardAdAssetPaths, Category.AD_ASSET_PATHS)
        bindCategoryCard(R.id.cardLibFileKeywords, Category.LIB_FILE_KEYWORDS)
        bindCategoryCard(R.id.cardAssetKeywords, Category.ASSET_KEYWORDS)
        bindCategoryCard(R.id.cardMethodNeutralizeKeywords, Category.METHOD_NEUTRALIZE_KEYWORDS)
        bindCategoryCard(R.id.cardStringPatterns, Category.STRING_PATTERNS)
        bindCategoryCard(R.id.cardAdPermissions, Category.AD_PERMISSIONS)
        bindCategoryCard(R.id.cardRootFileKeywords, Category.ROOT_FILE_KEYWORDS)
        bindCategoryCard(R.id.cardResLayoutKeywords, Category.RES_LAYOUT_KEYWORDS)

        // Flutter 广告特征管理（独立于 DEX 特征）
        findViewById<MaterialButton>(R.id.btnManageFlutterPatterns)?.setOnClickListener {
            showPatternListDialog(Category.FLUTTER_PATTERNS)
        }

        // Flutter 广告特征说明
        findViewById<ImageButton>(R.id.btnFlutterHelp)?.setOnClickListener {
            showCategoryHelpDialog(Category.FLUTTER_PATTERNS)
        }

        // Flutter 广告特征启停开关：绑定到 Flutter libapp.so 处理开关，
        // 关闭后处理 APK 时跳过 Flutter 去广告步骤。
        findViewById<SwitchCompat>(R.id.swFlutterEnabled)?.apply {
            isChecked = PathPreferences.isFlutterLibappEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, isChecked ->
                PathPreferences.setFlutterLibappEnabled(this@SettingsActivity, isChecked)
                UiUtils.info(this@SettingsActivity,
                    if (isChecked) getString(R.string.h_02317889) else getString(R.string.h_844bbd8d))
            }
        }

        // DEX 体积优化开关：移除调试信息（行号/局部变量表/参数名）减小 APK 体积
        findViewById<SwitchCompat>(R.id.swDexOptimizeEnabled)?.apply {
            isChecked = PathPreferences.isDexOptimizeEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, isChecked ->
                PathPreferences.setDexOptimizeEnabled(this@SettingsActivity, isChecked)
                UiUtils.info(this@SettingsActivity,
                    if (isChecked) getString(R.string.h_ed310a00) else getString(R.string.h_31e413e3))
            }
        }

        // 签名效验去除开关：开启后处理 APK 时自动去签名效验
        findViewById<SwitchCompat>(R.id.swSignRemovalEnabled)?.apply {
            isChecked = PathPreferences.isSignRemovalEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    // 开启：若当前模式为关闭，则默认使用"普通去除"
                    val cur = PathPreferences.getSignRemovalMode(this@SettingsActivity)
                    if (cur == 0) {
                        PathPreferences.setSignRemovalMode(this@SettingsActivity,
                            com.shinegirls.apkadremovereditor.core.SignatureVerificationRemover.MODE_NORMAL)
                    }
                    UiUtils.info(this@SettingsActivity, getString(R.string.h_3bd446d8))
                } else {
                    val cur = PathPreferences.getSignRemovalMode(this@SettingsActivity)
                    if (cur != 0) {
                        PathPreferences.setSignRemovalMode(this@SettingsActivity, 0)
                    }
                    UiUtils.info(this@SettingsActivity, getString(R.string.h_9d60567b))
                }
                refreshSignModeUi()
            }
        }

        // 修改签名效验模式：普通去除 / 原包去除
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSignMode)?.setOnClickListener {
            showSignModeDialog()
        }

        // 自定义注入参数：原包路径 / 解压路径 / So库名
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSignParams)?.setOnClickListener {
            showSignParamsDialog()
        }

        // 打包时是否重签名：开启后打包自动重签名，关闭后输出未签名 APK
        findViewById<SwitchCompat>(R.id.swSkipSigning)?.apply {
            // 开关当前状态：ON=重签名（即偏好中的未跳过签名）
            isChecked = !PathPreferences.isSigningSkipped(this@SettingsActivity)
            setOnCheckedChangeListener { _, isChecked ->
                // isChecked=true 表示重签名，即不跳过签名
                PathPreferences.setSigningSkipped(this@SettingsActivity, !isChecked)
                UiUtils.info(this@SettingsActivity,
                    if (isChecked) getString(R.string.h_53d2a9b1) else getString(R.string.h_8c9f1e7b))
            }
        }
        refreshSignModeUi()
        refreshSignParamsUi()
    }

    /**
     * 刷新签名效验去除的 UI 显示（开关状态 + 模式文案）。
     */
    private fun refreshSignModeUi() {
        val mode = PathPreferences.getSignRemovalMode(this)
        findViewById<SwitchCompat>(R.id.swSignRemovalEnabled)?.isChecked = mode != 0
        findViewById<TextView>(R.id.tvSignMode)?.text = when (mode) {
            com.shinegirls.apkadremovereditor.core.SignatureVerificationRemover.MODE_ORIGINAL -> getString(R.string.h_15286e90)
            com.shinegirls.apkadremovereditor.core.SignatureVerificationRemover.MODE_NORMAL -> getString(R.string.s_636c1a42)
            else -> getString(R.string.h_0b1609fb)
        }
    }

    /**
     * 弹出签名效验模式选择对话框。
     */
    private fun showSignModeDialog() {
        val options = arrayOf(getString(R.string.s_636c1a42), getString(R.string.h_15286e90))
        val startMode = PathPreferences.getSignRemovalMode(this)
        val checked = when {
            startMode == com.shinegirls.apkadremovereditor.core.SignatureVerificationRemover.MODE_ORIGINAL -> 1
            else -> 0
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.h_158dee5e))
            .setSingleChoiceItems(options, checked) { _, which ->
                when (which) {
                    0 -> PathPreferences.setSignRemovalMode(this,
                        com.shinegirls.apkadremovereditor.core.SignatureVerificationRemover.MODE_NORMAL)
                    else -> PathPreferences.setSignRemovalMode(this,
                        com.shinegirls.apkadremovereditor.core.SignatureVerificationRemover.MODE_ORIGINAL)
                }
                refreshSignModeUi()
                UiUtils.info(this, getString(R.string.h_05360de7, options[which]))
            }
            .setPositiveButton(getString(R.string.s_38cf16f2), null)
            .show()
    }

    /**
     * 刷新注入参数显示（原包路径 / 解压路径 / So库名 / 钩子类名 / 签名信息 / 入口名称）。
     */
    private fun refreshSignParamsUi() {
        val origin = PathPreferences.getSignOriginPath(this)
        val extract = PathPreferences.getSignExtractPath(this)
        val so = PathPreferences.getSignSoName(this)
        val hook = PathPreferences.getSignHookClass(this)
        val info = PathPreferences.getSignInfo(this)
        val entry = PathPreferences.getSignEntry(this)
        val isCustom = origin != com.shinegirls.apkadremovereditor.core.SignatureVerificationRemover.DEFAULT_ORIGIN_ASSET_PATH ||
            extract != com.shinegirls.apkadremovereditor.core.SignatureVerificationRemover.DEFAULT_EXTRACT_PATH ||
            so != com.shinegirls.apkadremovereditor.core.SignatureVerificationRemover.DEFAULT_SO_NAME ||
            hook != com.shinegirls.apkadremovereditor.core.SignatureVerificationRemover.DEFAULT_HOOK_CLASS ||
            info != com.shinegirls.apkadremovereditor.core.SignatureVerificationRemover.DEFAULT_SIGN_INFO ||
            entry != com.shinegirls.apkadremovereditor.core.SignatureVerificationRemover.DEFAULT_ENTRY_NAME
        findViewById<TextView>(R.id.tvSignParams)?.text =
            if (isCustom) getString(R.string.h_720380b4, so, hook) else getString(R.string.s_4d96525d)
    }

    /**
     * 弹出自定义注入参数对话框：原包路径 / 解压路径 / So库名 / 钩子类名 / 签名信息 / 入口名称。
     * 前三个值会写入钩子 <clinit> 的 const-string（对应 MT 的"注入原包路径/原包解压路径/注入So库名"），
     * 钩子类名会重命名注入的 KillerApplication 类（含 12 个内部类）；
     * 签名信息（Base64 证书）与入口名称（包名）留空则运行时自动从原包 / manifest 读取。
     */
    private fun showSignParamsDialog() {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.dialog_sign_params, null)
        val etOrigin = view.findViewById<EditText>(R.id.etSignOrigin)
        val etExtract = view.findViewById<EditText>(R.id.etSignExtract)
        val etSo = view.findViewById<EditText>(R.id.etSignSo)
        val etHook = view.findViewById<EditText>(R.id.etSignHookClass)
        val etInfo = view.findViewById<EditText>(R.id.etSignInfo)
        val etEntry = view.findViewById<EditText>(R.id.etSignEntry)
        etOrigin.setText(PathPreferences.getSignOriginPath(this))
        etExtract.setText(PathPreferences.getSignExtractPath(this))
        etSo.setText(PathPreferences.getSignSoName(this))
        etHook.setText(PathPreferences.getSignHookClass(this))
        etInfo.setText(PathPreferences.getSignInfo(this))
        etEntry.setText(PathPreferences.getSignEntry(this))

        val signDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(view)
            .create()
        signDialog.show()
        // 自适应屏幕：内容过长时限制高度并滚动，避免溢出屏幕
        UiUtils.fitDialogToScreen(signDialog)

        // 布局内自定义按钮：保存 / 取消（避免 AlertDialog 底部按钮被长内容挤出屏幕）
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSignSave).setOnClickListener {
            val origin = etOrigin.text?.toString()?.trim().orEmpty()
            val extract = etExtract.text?.toString()?.trim().orEmpty()
            val so = etSo.text?.toString()?.trim().orEmpty()
            val hook = etHook.text?.toString()?.trim().orEmpty()
            val info = etInfo.text?.toString()?.trim().orEmpty()
            val entry = etEntry.text?.toString()?.trim().orEmpty()
            if (origin.isEmpty() || extract.isEmpty() || so.isEmpty() || hook.isEmpty()) {
                UiUtils.info(this, getString(R.string.h_b8fa7cb1))
                return@setOnClickListener
            }
            PathPreferences.setSignOriginPath(this, origin)
            PathPreferences.setSignExtractPath(this, extract)
            PathPreferences.setSignSoName(this, so)
            PathPreferences.setSignHookClass(this, hook)
            PathPreferences.setSignInfo(this, info)
            PathPreferences.setSignEntry(this, entry)
            refreshSignParamsUi()
            signDialog.dismiss()
            UiUtils.info(this, getString(R.string.h_582b60a3, so, hook))
        }
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSignCancel).setOnClickListener {
            signDialog.dismiss()
        }
    }

    /**
     * 更新统计信息。
     */
    private fun updateStats() {
        tvConfigStats.text = getString(R.string.h_fc69844a2, config.totalCount())
        findViewById<TextView>(R.id.tvFlutterStats)?.text = getString(R.string.h_fc69844a, config.flutterPatterns.size)
    }

    /**
     * 更新订阅源数量统计。
     */
    private fun updateSubscriptionStats() {
        val all = SubscriptionManager.loadSubscriptions(this)
        val enabled = all.count { it.enabled }
        tvSubscriptionStats.text = getString(R.string.h_54d484f4, all.size, enabled)
    }

    /**
     * 更新路径显示。
     */
    private fun updatePathDisplay() {
        tvConfigPath.text = PathPreferences.getConfigFilePath(this)
        tvOutputDirSetting.text = PathPreferences.getOutputDir(this)
    }

    /**
     * 弹出路径修改对话框。
     * @param isConfigPath true=修改配置文件路径，false=修改 APK 输出目录
     */
    private fun showChangePathDialog(isConfigPath: Boolean) {
        val currentPath = if (isConfigPath) {
            PathPreferences.getConfigFilePath(this)
        } else {
            PathPreferences.getOutputDir(this)
        }

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_change_path, null)
        val etPath = dialogView.findViewById<TextInputEditText>(R.id.etPath)
        etPath.setText(currentPath)

        val title = if (isConfigPath) getString(R.string.h_1e52f7a3) else getString(R.string.h_351683da)
        val hint = if (isConfigPath) {
            getString(R.string.h_d8328f57)
        } else {
            getString(R.string.h_7258ddc9)
        }
        dialogView.findViewById<TextView>(R.id.tvPathHint).text = hint

        val pathDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton(getString(R.string.s_38cf16f2)) { _, _ ->
                val newPath = etPath.text.toString().trim()
                if (newPath.isBlank()) {
                    UiUtils.warning(this, getString(R.string.h_60aa379b))
                    return@setPositiveButton
                }

                val success = if (isConfigPath) {
                    // 配置文件路径必须以 .json 结尾
                    if (!newPath.endsWith(".json")) {
                        UiUtils.warning(this, getString(R.string.h_599c01d1))
                        return@setPositiveButton
                    }
                    // 如果旧配置文件存在，迁移到新路径
                    val oldFile = java.io.File(currentPath)
                    val newFile = java.io.File(newPath)
                    if (oldFile.exists() && oldFile.absolutePath != newFile.absolutePath) {
                        newFile.parentFile?.mkdirs()
                        oldFile.copyTo(newFile, overwrite = true)
                    }
                    PathPreferences.setConfigFilePath(this, newPath)
                } else {
                    PathPreferences.setOutputDir(this, newPath)
                }

                if (success) {
                    updatePathDisplay()
                    if (isConfigPath) {
                        loadAndDisplayConfig()
                    }
                    UiUtils.success(this, getString(R.string.h_73468857))
                } else {
                    UiUtils.error(this, getString(R.string.h_2fe6efbe))
                }
            }
            .setNegativeButton(getString(R.string.s_625fb26b), null)
            .create()
        pathDialog.show()
        // 自适应屏幕：内容过长时限制高度并滚动，避免溢出屏幕
        UiUtils.fitDialogToScreen(pathDialog)
    }

    /**
     * 设置分组标题文本。
     */
    private fun setSectionTitle(sectionId: Int, title: String, hint: String) {
        try {
            val section = findViewById<View>(sectionId) ?: return
            section.findViewById<TextView>(R.id.tvSectionTitle)?.text = title
            section.findViewById<TextView>(R.id.tvSectionHint)?.text = hint
        } catch (_: Exception) {
        }
    }

    /**
     * 绑定分类卡片视图，设置名称、数量和管理按钮。
     */
    private fun bindCategoryCard(cardId: Int, category: Category) {
        try {
            val card = findViewById<View>(cardId) ?: run {
                Log.w("SettingsActivity", getString(R.string.h_b8884134, cardId))
                return
            }
            categoryCards[category] = card

            val tvName = card.findViewById<TextView>(R.id.tvCategoryName)
            val tvCount = card.findViewById<TextView>(R.id.tvCategoryCount)
            val btnManage = card.findViewById<MaterialButton>(R.id.btnManage)
            val btnHelp = card.findViewById<ImageButton>(R.id.btnCategoryHelp)
            val ivIcon = card.findViewById<ImageView>(R.id.ivCategoryIcon)
            val swEnabled = card.findViewById<SwitchCompat>(R.id.swCategoryEnabled)

            if (tvName == null || tvCount == null || btnManage == null) {
                Log.w("SettingsActivity", getString(R.string.h_e03c4e1e, category))
                return
            }

            tvName.text = getString(category.titleRes)
            val list = AdPatternConfig.getCategoryList(config, category)
            tvCount.text = getString(R.string.h_c71a303c, list.size)

            // 设置分类图标（不同分类不同图标与配色）
            ivIcon?.setImageResource(categoryIcon(category))
            ivIcon?.setColorFilter(ContextCompat.getColor(this, categoryAccent(category)))

            btnManage.setOnClickListener {
                showPatternListDialog(category)
            }

            btnHelp?.setOnClickListener {
                showCategoryHelpDialog(category)
            }

            // 绑定分类启停开关：状态持久化到 SharedPreferences，
            // 处理 APK 时 AdRemover 会依据开关决定是否执行该分类去广告。
            if (swEnabled != null) {
                swEnabled.isChecked = PathPreferences.isCategoryEnabled(this, category.name)
                swEnabled.setOnCheckedChangeListener { _, isChecked ->
                    PathPreferences.setCategoryEnabled(this, category.name, isChecked)
                    UiUtils.info(this, if (isChecked) getString(R.string.h_8ae1f6e1, getString(category.titleRes)) else getString(R.string.h_66c23c1c, getString(category.titleRes)))
                }
            }
        } catch (e: Exception) {
            Log.e("SettingsActivity", getString(R.string.h_0d2d462b, category), e)
        }
    }

    /**
     * 根据分类返回对应的图标资源。
     */
    private fun categoryIcon(category: Category): Int = when (category) {
        Category.SDK_PACKAGES -> R.drawable.ic_category_sdk
        Category.CLASS_KEYWORDS -> R.drawable.ic_category_class
        Category.METHOD_PATTERNS -> R.drawable.ic_category_method
        Category.URL_PATTERNS -> R.drawable.ic_category_url
        Category.AD_VIEW_NAMES -> R.drawable.ic_category_view
        Category.AD_ACTIVITIES -> R.drawable.ic_category_activity
        Category.AD_SERVICES -> R.drawable.ic_category_service
        Category.AD_RECEIVERS -> R.drawable.ic_category_receiver
        Category.FORCE_TRUE_METHODS -> R.drawable.ic_category_vip
        Category.FORCE_FALSE_METHODS -> R.drawable.ic_category_vip
        Category.AD_ASSET_PATHS -> R.drawable.ic_category_asset
        Category.LIB_FILE_KEYWORDS -> R.drawable.ic_category_lib
        Category.ASSET_KEYWORDS -> R.drawable.ic_category_layer
        Category.METHOD_NEUTRALIZE_KEYWORDS -> R.drawable.ic_category_neutralize
        Category.STRING_PATTERNS -> R.drawable.ic_category_method
        Category.AD_PERMISSIONS -> R.drawable.ic_category_permission
        Category.ROOT_FILE_KEYWORDS -> R.drawable.ic_category_rootfile
        Category.RES_LAYOUT_KEYWORDS -> R.drawable.ic_category_layout
        Category.FLUTTER_PATTERNS -> R.drawable.ic_category_layer
    }

    /**
     * 根据分类返回对应的强调色资源。
     */
    private fun categoryAccent(category: Category): Int = when (category) {
        Category.SDK_PACKAGES -> R.color.primary
        Category.CLASS_KEYWORDS -> R.color.primary_dark
        Category.METHOD_PATTERNS -> R.color.accent
        Category.URL_PATTERNS -> R.color.accent_dark
        Category.AD_VIEW_NAMES -> R.color.primary
        Category.AD_ACTIVITIES -> R.color.primary_dark
        Category.AD_SERVICES -> R.color.accent
        Category.AD_RECEIVERS -> R.color.accent_dark
        Category.FORCE_TRUE_METHODS -> R.color.primary
        Category.FORCE_FALSE_METHODS -> R.color.accent
        Category.AD_ASSET_PATHS -> R.color.primary_dark
        Category.LIB_FILE_KEYWORDS -> R.color.accent
        Category.ASSET_KEYWORDS -> R.color.accent_dark
        Category.METHOD_NEUTRALIZE_KEYWORDS -> R.color.teal_700
        Category.STRING_PATTERNS -> R.color.teal_700
        Category.AD_PERMISSIONS -> R.color.primary
        Category.ROOT_FILE_KEYWORDS -> R.color.accent_dark
        Category.RES_LAYOUT_KEYWORDS -> R.color.primary_dark
        Category.FLUTTER_PATTERNS -> R.color.accent_dark
    }

    /**
     * 显示指定分类的特征列表对话框。
     * 支持：查看列表、添加、编辑、删除单条特征。
     */
    private fun showPatternListDialog(category: Category) {
        val list = AdPatternConfig.getCategoryList(config, category)

        val dialogView = layoutInflater.inflate(R.layout.dialog_pattern_list, null)
        val rvPatterns = dialogView.findViewById<RecyclerView>(R.id.rvPatterns)
        val etNewPattern = dialogView.findViewById<TextInputEditText>(R.id.etNewPattern)
        val btnAddPattern = dialogView.findViewById<MaterialButton>(R.id.btnAddPattern)
        val tvEmptyHint = dialogView.findViewById<TextView>(R.id.tvEmptyHint)

        val adapter = PatternAdapter(list, object : PatternAdapter.Callback {
            override fun onEdit(position: Int, oldValue: String) {
                showEditDialog(oldValue) { newValue ->
                    if (newValue.isNotBlank() && newValue != oldValue) {
                        // 检查是否已存在
                        if (list.any { it.equals(newValue, ignoreCase = true) }) {
                            UiUtils.warning(this@SettingsActivity, getString(R.string.h_f2765b03))
                            return@showEditDialog
                        }
                        list[position] = newValue.trim()
                        rvPatterns.adapter?.notifyItemChanged(position)
                        updateEmptyHint(list, tvEmptyHint)
                        // 实时保存
                        AdPatternConfig.saveConfig(config, this@SettingsActivity)
                        updateCategoryCount(category, list.size)
                    }
                }
            }

            override fun onDelete(position: Int) {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(getString(R.string.h_8f7f9a61))
                    .setMessage("确定删除 \"${list[position].take(50)}\" ？")
                    .setPositiveButton(getString(R.string.s_2f4aaddd)) { _, _ ->
                        list.removeAt(position)
                        rvPatterns.adapter?.notifyItemRemoved(position)
                        rvPatterns.adapter?.notifyItemRangeChanged(position, list.size)
                        updateEmptyHint(list, tvEmptyHint)
                        // 实时保存
                        AdPatternConfig.saveConfig(config, this@SettingsActivity)
                        updateCategoryCount(category, list.size)
                        updateStats()
                    }
                    .setNegativeButton(getString(R.string.s_625fb26b), null)
                    .show()
            }
        })

        rvPatterns.layoutManager = LinearLayoutManager(this)
        rvPatterns.adapter = adapter

        // 添加按钮
        btnAddPattern.setOnClickListener {
            val text = etNewPattern.text.toString().trim()
            if (text.isEmpty()) {
                UiUtils.warning(this, getString(R.string.h_f57b7d67))
                return@setOnClickListener
            }
            if (list.any { it.equals(text, ignoreCase = true) }) {
                UiUtils.warning(this, getString(R.string.h_f2765b03))
                return@setOnClickListener
            }
            list.add(text)
            rvPatterns.adapter?.notifyItemInserted(list.size - 1)
            rvPatterns.scrollToPosition(list.size - 1)
            etNewPattern.text?.clear()
            updateEmptyHint(list, tvEmptyHint)
            // 实时保存
            AdPatternConfig.saveConfig(config, this)
            updateCategoryCount(category, list.size)
            updateStats()
            UiUtils.success(this, getString(R.string.h_b189550a))
        }

        updateEmptyHint(list, tvEmptyHint)

        val patternDialog = AlertDialog.Builder(this)
            .setTitle(getString(category.titleRes) + getString(R.string.h_1b5de080, list.size))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.s_b15d9127), null)
            .setOnDismissListener {
                updateCategoryCount(category, list.size)
                updateStats()
            }
            .create()
        patternDialog.show()
        // 自适应屏幕：内容过长时限制高度并滚动，避免溢出屏幕
        UiUtils.fitDialogToScreen(patternDialog)
    }

    /**
     * 显示编辑对话框。
     */
    private fun showEditDialog(oldValue: String, onSave: (String) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(oldValue)
            setSelection(oldValue.length)
            setSingleLine(true)
        }

        val editDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.h_d0f51307))
            .setView(input)
            .setPositiveButton(getString(R.string.s_be5fbbe3)) { _, _ ->
                onSave(input.text.toString().trim())
            }
            .setNegativeButton(getString(R.string.s_625fb26b), null)
            .create()
        editDialog.show()
        // 自适应屏幕：内容过长时限制高度并滚动，避免溢出屏幕
        UiUtils.fitDialogToScreen(editDialog)
    }

    /**
     * 更新空列表提示。
     */
    private fun updateEmptyHint(list: List<*>, tvEmptyHint: TextView) {
        tvEmptyHint.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * 显示指定分类的帮助说明对话框。
     *
     * 帮助内容包含：功能说明、添加方式、示例、修改的文件/代码、配合使用的功能。
     * 内容来源见 [CATEGORY_HELP]。
     */
    private fun showCategoryHelpDialog(category: Category) {
        val help = CATEGORY_HELP[category] ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_category_help, null)

        dialogView.findViewById<TextView>(R.id.tvHelpTitle).text = getString(help.titleRes)
        dialogView.findViewById<TextView>(R.id.tvHelpSubtitle).text = getString(help.subtitleRes)

        val body = dialogView.findViewById<LinearLayout>(R.id.llHelpBody)
        body.removeAllViews()

        // 功能说明
        addHelpSection(body, getString(R.string.s_10445608), getString(help.descriptionRes), R.color.primary)
        // 添加方式
        addHelpSection(body, getString(R.string.h_1897b7f5), getString(help.addHowRes), R.color.accent)
        // 示例
        addHelpSection(body, getString(R.string.h_1a63ac23), getString(help.examplesRes), R.color.accent_dark)
        // 修改内容
        addHelpSection(body, getString(R.string.h_710378a3), getString(help.modifiedWhatRes), R.color.primary_dark)
        // 配合使用
        if (help.relatedWithRes != 0) {
            addHelpSection(body, getString(R.string.h_fea9a093), getString(help.relatedWithRes), R.color.teal_700)
        }
        // 提示
        addHelpSection(body, getString(R.string.h_b5f40c44), getString(help.tipRes), R.color.text_secondary)

        val helpDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton(getString(R.string.s_ce26955a), null)
            .create()
        helpDialog.show()
        // 自适应屏幕：内容过长时限制高度并滚动，避免溢出屏幕
        UiUtils.fitDialogToScreen(helpDialog)
    }

    /**
     * 向帮助对话框容器中添加一个带标题的区块。
     */
    private fun addHelpSection(
        container: LinearLayout,
        title: String,
        content: String,
        accentColorRes: Int
    ) {
        if (content.isBlank()) return
        val context: Context = this

        // 区块标题行
        val titleRow = TextView(context).apply {
            text = title
            setTextColor(ContextCompat.getColor(context, accentColorRes))
            textSize = 14f
            // 使用全局优雅字体（霞鹜文楷）加粗
            setTypeface(
                Typeface.create(
                    androidx.core.content.res.ResourcesCompat.getFont(context, R.font.lxgw_wenkai),
                    Typeface.BOLD,
                    false
                )
            )
            letterSpacing = 0.02f
        }
        container.addView(titleRow)

        // 内容
        val contentTv = TextView(context).apply {
            text = content
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 13.5f
            setLineSpacing(2.dp.toFloat(), 1f)
            // 等高排版：等宽字体用于示例类内容更清晰
            setTypeface(Typeface.MONOSPACE)
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        val margin = (8 * resources.displayMetrics.density).toInt()
        lp.topMargin = margin
        container.addView(contentTv, lp)

        // 区块之间留白
        val spacer = View(context)
        val spacerH = (14 * resources.displayMetrics.density).toInt()
        container.addView(spacer, ViewGroup.LayoutParams.MATCH_PARENT, spacerH)
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    /**
     * 更新分类卡片上的数量显示。
     */
    private fun updateCategoryCount(category: Category, count: Int) {
        val card = categoryCards[category] ?: return
        val tvCount = card.findViewById<TextView>(R.id.tvCategoryCount)
        tvCount.text = getString(R.string.h_62d0f9f8, count)
    }

    // ==================== 订阅源功能 ====================

    /**
     * 显示订阅源管理对话框（添加 / 编辑 / 删除 / 分享 / 应用）。
     */
    private fun showSubscriptionListDialog() {
        try {
        val dialogView = layoutInflater.inflate(R.layout.dialog_subscription_list, null)
        val rvSubscriptions = dialogView.findViewById<RecyclerView>(R.id.rvSubscriptions)
        val tvEmptyHint = dialogView.findViewById<View>(R.id.tvSubEmptyHint)
        val btnAdd = dialogView.findViewById<MaterialButton>(R.id.btnAddSubscription)
        val btnShare = dialogView.findViewById<MaterialButton>(R.id.btnShareConfig)

        val subscriptions = SubscriptionManager.loadSubscriptions(this).toMutableList()

        val adapter = SubscriptionAdapter(subscriptions, object : SubscriptionAdapter.Callback {
            override fun onToggle(sub: Subscription, enabled: Boolean) {
                // 先更新内存列表和持久化
                val idx = subscriptions.indexOfFirst { it.id == sub.id }
                if (idx >= 0) {
                    subscriptions[idx] = subscriptions[idx].copy(enabled = enabled)
                }
                SubscriptionManager.setSubscriptionEnabled(sub.id, enabled, this@SettingsActivity)
                rvSubscriptions.adapter?.notifyDataSetChanged()
                // 合并应用所有已开启订阅
                applyEnabledSubscriptions(subscriptions)
            }

            override fun onShare(sub: Subscription) {
                shareSubscription(sub)
            }

            override fun onEdit(sub: Subscription) {
                showEditSubscriptionDialog(sub) { updated ->
                    SubscriptionManager.updateSubscription(updated, this@SettingsActivity)
                    val editIdx = subscriptions.indexOfFirst { it.id == updated.id }
                    if (editIdx >= 0) {
                        subscriptions[editIdx] = updated
                    }
                    refreshSubscriptionList(subscriptions, rvSubscriptions, tvEmptyHint)
                    updateSubscriptionStats()
                }
            }

            override fun onDelete(sub: Subscription) {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(getString(R.string.h_7c64bdce))
                    .setMessage("确定删除订阅源 \"${sub.name}\" ？")
                    .setPositiveButton(getString(R.string.s_2f4aaddd)) { _, _ ->
                        val wasEnabled = sub.enabled
                        SubscriptionManager.deleteSubscription(sub.id, this@SettingsActivity)
                        subscriptions.removeAll { it.id == sub.id }
                        refreshSubscriptionList(subscriptions, rvSubscriptions, tvEmptyHint)
                        updateSubscriptionStats()
                        // 如果删除的是已开启的订阅，需要重新合并应用
                        if (wasEnabled) {
                            applyEnabledSubscriptions(subscriptions)
                        }
                    }
                    .setNegativeButton(getString(R.string.s_625fb26b), null)
                    .show()
            }
        })

        rvSubscriptions.layoutManager = LinearLayoutManager(this)
        rvSubscriptions.adapter = adapter

        // 添加订阅
        btnAdd.setOnClickListener {
            showAddSubscriptionDialog { newSub ->
                SubscriptionManager.addSubscription(newSub, this@SettingsActivity)
                subscriptions.add(newSub)
                refreshSubscriptionList(subscriptions, rvSubscriptions, tvEmptyHint)
                updateSubscriptionStats()
            }
        }

        // 分享当前配置
        btnShare.setOnClickListener {
            showShareConfigDialog()
        }

        refreshSubscriptionList(subscriptions, rvSubscriptions, tvEmptyHint)

        val subListDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton(getString(R.string.s_b15d9127), null)
            .create()
        subListDialog.show()
        // 自适应屏幕：内容过长时限制高度并滚动，避免溢出屏幕
        UiUtils.fitDialogToScreen(subListDialog)
        } catch (e: Exception) {
            Log.e("SettingsActivity", getString(R.string.h_956dd267), e)
            UiUtils.error(this, getString(R.string.h_db72862a, e.message))
        }
    }

    /**
     * 刷新订阅列表显示。
     */
    private fun refreshSubscriptionList(
        list: MutableList<Subscription>,
        rv: RecyclerView,
        tvEmptyHint: View
    ) {
        rv.adapter?.notifyDataSetChanged()
        tvEmptyHint.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * 显示"添加订阅"对话框：输入口令、直链链接或直接粘贴广告特征配置内容，解析并添加订阅源。
     *
     * 支持三种输入：
     * - 订阅源口令：以 ADSUB: 开头，解码后直接添加
     * - 直链链接：http(s):// 开头的配置 JSON 地址，异步拉取并校验后添加为 URL 型订阅
     * - 配置内容：直接粘贴广告特征配置 JSON 文本，校验合法后添加为 CONTENT 型订阅
     */
    private fun showAddSubscriptionDialog(onAdded: (Subscription) -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_subscription, null)
        val etToken = dialogView.findViewById<TextInputEditText>(R.id.etSubscriptionToken)

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.s_d94cc5ea))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.s_b58c7549), null)
            .setNegativeButton(getString(R.string.s_625fb26b), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val input = etToken.text.toString().trim()
                if (input.isEmpty()) {
                    UiUtils.warning(this, getString(R.string.h_1b92ede8))
                    return@setOnClickListener
                }

                // 直链链接：http:// 或 https:// 开头
                if (input.startsWith("http://") || input.startsWith("https://")) {
                    addSubscriptionByUrl(input, dialog, onAdded)
                    return@setOnClickListener
                }

                // 直接粘贴的广告特征配置 JSON：以 { 开头且校验为合法配置
                if (input.startsWith("{")) {
                    if (!SubscriptionManager.isValidConfigJson(input)) {
                        UiUtils.warning(this, getString(R.string.h_9fce216a))
                        return@setOnClickListener
                    }
                    val newSub = Subscription(
                        id = java.util.UUID.randomUUID().toString(),
                        name = getString(R.string.h_299bd8c9),
                        type = SubscriptionManager.Type.CONTENT,
                        contentJson = input
                    )
                    onAdded(newSub)
                    dialog.dismiss()
                    UiUtils.success(this, getString(R.string.h_ee4e2a27))
                    return@setOnClickListener
                }

                // 订阅源口令
                val parsed = SubscriptionManager.decodeToken(input)
                if (parsed == null) {
                    UiUtils.warning(this, getString(R.string.h_5a8cb987))
                    return@setOnClickListener
                }
                val newSub = Subscription(
                    id = java.util.UUID.randomUUID().toString(),
                    name = parsed.name,
                    type = parsed.type,
                    url = parsed.url,
                    contentJson = parsed.contentJson
                )
                onAdded(newSub)
                dialog.dismiss()
                UiUtils.success(this, getString(R.string.h_975cc249, parsed.name))
            }
        }

        dialog.show()
        // 自适应屏幕：内容过长时限制高度并滚动，避免溢出屏幕
        UiUtils.fitDialogToScreen(dialog)
    }

    /**
     * 通过直链链接添加订阅源：后台拉取配置 JSON，校验合法后添加为 URL 型订阅。
     */
    private fun addSubscriptionByUrl(
        url: String,
        dialog: AlertDialog,
        onAdded: (Subscription) -> Unit
    ) {
        // 拉取期间禁用添加按钮，防止重复提交
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        UiUtils.info(this, getString(R.string.h_f5f084be))

        lifecycleScope.launch {
            val jsonStr = withContext(Dispatchers.IO) {
                SubscriptionManager.fetchRemoteConfig(url)
            }
            if (jsonStr == null) {
                withContext(Dispatchers.Main) {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    UiUtils.error(this@SettingsActivity, getString(R.string.h_2fcb517d))
                }
                return@launch
            }
            if (!SubscriptionManager.isValidConfigJson(jsonStr)) {
                withContext(Dispatchers.Main) {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    UiUtils.error(this@SettingsActivity, getString(R.string.h_c6439a1a))
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                val name = url.substringAfterLast('/').substringBefore('.').ifBlank { getString(R.string.h_7b695da8) }
                val newSub = Subscription(
                    id = java.util.UUID.randomUUID().toString(),
                    name = name,
                    type = SubscriptionManager.Type.URL,
                    url = url
                )
                onAdded(newSub)
                dialog.dismiss()
                UiUtils.success(this@SettingsActivity, getString(R.string.h_92d2d9c6, name))
            }
        }
    }

    /**
     * 显示"编辑订阅"对话框。
     */
    private fun showEditSubscriptionDialog(
        sub: Subscription,
        onSaved: (Subscription) -> Unit
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_subscription, null)
        val etName = dialogView.findViewById<TextInputEditText>(R.id.etEditName)
        val etUrl = dialogView.findViewById<TextInputEditText>(R.id.etEditUrl)
        val tilUrl = dialogView.findViewById<TextInputLayout>(R.id.tilEditUrl)
        val tvUrlLabel = dialogView.findViewById<TextView>(R.id.tvEditUrlLabel)

        etName.setText(sub.name)
        if (sub.type == SubscriptionManager.Type.URL) {
            tilUrl.visibility = View.VISIBLE
            tvUrlLabel.visibility = View.VISIBLE
            etUrl.setText(sub.url)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.s_07ee07e4))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.s_be5fbbe3), null)
            .setNegativeButton(getString(R.string.s_625fb26b), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = etName.text.toString().trim()
                if (name.isEmpty()) {
                    UiUtils.warning(this, getString(R.string.h_0b34e814))
                    return@setOnClickListener
                }
                val updated = if (sub.type == SubscriptionManager.Type.URL) {
                    sub.copy(name = name, url = etUrl.text.toString().trim())
                } else {
                    sub.copy(name = name)
                }
                onSaved(updated)
                dialog.dismiss()
                UiUtils.success(this, getString(R.string.h_432ecd96))
            }
        }

        dialog.show()
        // 自适应屏幕：内容过长时限制高度并滚动，避免溢出屏幕
        UiUtils.fitDialogToScreen(dialog)
    }

    /**
     * 显示"分享配置"对话框：把当前配置编码成订阅源口令。
     */
    private fun showShareConfigDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_share_subscription, null)
        val etName = dialogView.findViewById<TextInputEditText>(R.id.etShareName)
        val rgType = dialogView.findViewById<RadioGroup>(R.id.rgShareType)
        val tilUrl = dialogView.findViewById<TextInputLayout>(R.id.tilShareUrl)
        val etUrl = dialogView.findViewById<TextInputEditText>(R.id.etShareUrl)
        val tvPreview = dialogView.findViewById<TextView>(R.id.tvSharePreview)
        val tvUrlLabel = dialogView.findViewById<TextView>(R.id.tvShareUrlLabel)

        rgType.setOnCheckedChangeListener { _, checkedId ->
            val isUrl = checkedId == R.id.rbShareUrl
            tilUrl.visibility = if (isUrl) View.VISIBLE else View.GONE
            tvUrlLabel.visibility = if (isUrl) View.VISIBLE else View.GONE
            tvPreview.visibility = View.GONE
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.s_5f535b47))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.h_cdb8ce5c), null)
            .setNegativeButton(getString(R.string.s_625fb26b), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = etName.text.toString().trim()
                if (name.isEmpty()) {
                    UiUtils.warning(this, getString(R.string.h_0b34e814))
                    return@setOnClickListener
                }
                val isUrl = rgType.checkedRadioButtonId == R.id.rbShareUrl
                val token = if (isUrl) {
                    val url = etUrl.text.toString().trim()
                    if (url.isEmpty()) {
                        UiUtils.warning(this, getString(R.string.h_4b30694f))
                        return@setOnClickListener
                    }
                    SubscriptionManager.encodeToken(
                        name,
                        SubscriptionManager.Type.URL,
                        url = url
                    )
                } else {
                    val contentJson = AdPatternConfig.toJson(config).toString()
                    SubscriptionManager.encodeToken(
                        name,
                        SubscriptionManager.Type.CONTENT,
                        contentJson = contentJson
                    )
                }
                showTokenResultDialog(token)
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    /**
     * 显示生成的口令结果，支持复制到剪贴板或系统分享。
     */
    private fun showTokenResultDialog(token: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_subscription, null)
        val etToken = dialogView.findViewById<TextInputEditText>(R.id.etSubscriptionToken)
        val tvPreview = dialogView.findViewById<TextView>(R.id.tvTokenPreview)

        etToken.setText(token)
        tvPreview.text = getString(R.string.h_05ade75d)
        tvPreview.visibility = View.VISIBLE

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.h_63d55e8f))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.h_79d3abe9), null)
            .setNeutralButton(getString(R.string.h_8f6495fd), null)
            .setNegativeButton(getString(R.string.s_b15d9127), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                copyToClipboard(token)
                UiUtils.success(this, getString(R.string.h_ee0a3ba0))
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                shareToken(token)
            }
        }

        dialog.show()
        // 自适应屏幕：内容过长时限制高度并滚动，避免溢出屏幕
        UiUtils.fitDialogToScreen(dialog)
    }

    /**
     * 复制文本到剪贴板。
     */
    private fun copyToClipboard(text: String) {
        val clip = ClipData.newPlainText(getString(R.string.h_63d55e8f), text)
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
    }

    /**
     * 通过系统分享面板分享口令。
     */
    private fun shareToken(token: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, token)
        }
        startActivity(Intent.createChooser(sendIntent, getString(R.string.h_c2501d73)))
    }

    /**
     * 分享单个订阅源（重新编码为口令并分享）。
     */
    private fun shareSubscription(sub: Subscription) {
        val token = when (sub.type) {
            SubscriptionManager.Type.URL ->
                SubscriptionManager.encodeToken(sub.name, SubscriptionManager.Type.URL, url = sub.url)
            SubscriptionManager.Type.CONTENT ->
                SubscriptionManager.encodeToken(sub.name, SubscriptionManager.Type.CONTENT, contentJson = sub.contentJson)
        }
        showTokenResultDialog(token)
    }

    /**
     * 合并应用所有已开启的订阅配置。
     *
     * - 收集所有 enabled 的订阅
     * - URL 型订阅异步拉取远程配置，内嵌型直接解析
     * - 将所有配置合并（并集去重）后应用并保存
     * - 若无已开启订阅，恢复默认配置
     */
    private fun applyEnabledSubscriptions(allSubs: List<Subscription>) {
        val enabledSubs = allSubs.filter { it.enabled }

        if (enabledSubs.isEmpty()) {
            // 无已开启订阅，恢复默认配置
            config = AdPatternConfig.getDefaultConfig(this)
            AdPatternConfig.saveConfig(config, this)
            displayConfig()
            UiUtils.info(this, getString(R.string.h_c7fdb835))
            return
        }

        val contentSubs = enabledSubs.filter { it.type == SubscriptionManager.Type.CONTENT }
        val urlSubs = enabledSubs.filter { it.type == SubscriptionManager.Type.URL }

        // 先处理内嵌型（同步解析）
        val configs = mutableListOf<AdPatternConfig.AdPatterns>()
        for (sub in contentSubs) {
            if (sub.contentJson.isNotBlank()) {
                try {
                    configs.add(AdPatternConfig.fromJson(JSONObject(sub.contentJson), this))
                } catch (e: Exception) {
                    Log.e("SettingsActivity", getString(R.string.h_be5de496, sub.name), e)
                }
            }
        }

        if (urlSubs.isEmpty()) {
            // 无需网络请求，直接合并应用
            applyMergedConfigs(configs, enabledSubs.size)
            return
        }

        // 有 URL 型订阅，需要异步拉取
        val fetchedConfigs = mutableListOf<AdPatternConfig.AdPatterns>()
        val errors = mutableListOf<String>()

        UiUtils.info(this, getString(R.string.h_583bde12))

        lifecycleScope.launch {
            for (sub in urlSubs) {
                val jsonStr = withContext(Dispatchers.IO) {
                    SubscriptionManager.fetchRemoteConfig(sub.url)
                }
                if (jsonStr != null && SubscriptionManager.isValidConfigJson(jsonStr)) {
                    try {
                        fetchedConfigs.add(AdPatternConfig.fromJson(JSONObject(jsonStr), this@SettingsActivity))
                    } catch (e: Exception) {
                        errors.add(sub.name)
                    }
                } else {
                    errors.add(sub.name)
                }
            }

            // 合并内嵌 + 远程拉取的配置
            configs.addAll(fetchedConfigs)
            applyMergedConfigs(configs, enabledSubs.size)

            if (errors.isNotEmpty()) {
                UiUtils.error(this@SettingsActivity, getString(R.string.h_50542d9e, errors.joinToString(", ")))
            }
        }
    }

    /**
     * 合并多个配置并应用保存。
     */
    private fun applyMergedConfigs(configs: List<AdPatternConfig.AdPatterns>, totalEnabled: Int) {
        if (configs.isEmpty()) {
            UiUtils.warning(this, getString(R.string.h_de0412d9))
            return
        }
        config = AdPatternConfig.merge(configs)
        val success = AdPatternConfig.saveConfig(config, this)
        if (success) {
            displayConfig()
            UiUtils.success(this, getString(R.string.h_f0b2a7b1, totalEnabled, config.totalCount()))
        } else {
            UiUtils.error(this, getString(R.string.h_f744e671))
        }
    }
}

/**
 * 特征列表 RecyclerView 适配器。
 */
class PatternAdapter(
    private val items: MutableList<String>,
    private val callback: Callback
) : RecyclerView.Adapter<PatternAdapter.ViewHolder>() {

    interface Callback {
        fun onEdit(position: Int, oldValue: String)
        fun onDelete(position: Int)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvPatternText: TextView = view.findViewById(R.id.tvPatternText)
        val btnEdit: ImageButton = view.findViewById(R.id.btnEditItem)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteItem)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pattern, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvPatternText.text = item

        holder.btnEdit.setOnClickListener {
            val pos = holder.adapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                callback.onEdit(pos, items[pos])
            }
        }

        holder.btnDelete.setOnClickListener {
            val pos = holder.adapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                callback.onDelete(pos)
            }
        }
    }

    override fun getItemCount(): Int = items.size
}

/**
 * 订阅源列表 RecyclerView 适配器。
 */
class SubscriptionAdapter(
    private val items: MutableList<Subscription>,
    private val callback: Callback
) : RecyclerView.Adapter<SubscriptionAdapter.ViewHolder>() {

    interface Callback {
        fun onToggle(sub: Subscription, enabled: Boolean)
        fun onShare(sub: Subscription)
        fun onEdit(sub: Subscription)
        fun onDelete(sub: Subscription)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvSubName)
        val tvType: TextView = view.findViewById(R.id.tvSubType)
        val switchEnabled: androidx.appcompat.widget.SwitchCompat =
            view.findViewById(R.id.switchSubEnabled)
        val btnShare: MaterialButton = view.findViewById(R.id.btnSubShare)
        val btnEdit: MaterialButton = view.findViewById(R.id.btnSubEdit)
        val btnDelete: MaterialButton = view.findViewById(R.id.btnSubDelete)
        val card: com.google.android.material.card.MaterialCardView =
            view.findViewById(R.id.cardSubscription)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_subscription, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvName.text = item.name

        // 类型标签：不同类型使用不同颜色
        if (item.type == SubscriptionManager.Type.URL) {
            holder.tvType.text = holder.itemView.context.getString(R.string.s_2c3f3245)
            holder.tvType.setTextColor(
                androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.primary_dark)
            )
            holder.tvType.setBackgroundResource(R.drawable.bg_type_badge_accent)
        } else {
            holder.tvType.text = holder.itemView.context.getString(R.string.h_9140cf96)
            holder.tvType.setTextColor(
                androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.accent_dark)
            )
            holder.tvType.setBackgroundResource(R.drawable.bg_type_badge_lavender)
        }

        // 开启状态视觉反馈：开启时卡片边框高亮
        if (item.enabled) {
            holder.card.strokeColor = androidx.core.content.ContextCompat.getColor(
                holder.itemView.context, R.color.primary
            )
            holder.card.strokeWidth = 2
        } else {
            holder.card.strokeColor = androidx.core.content.ContextCompat.getColor(
                holder.itemView.context, R.color.blush_light
            )
            holder.card.strokeWidth = 1
        }

        // 设置开关状态（先移除监听器避免触发回调）
        holder.switchEnabled.setOnCheckedChangeListener(null)
        holder.switchEnabled.isChecked = item.enabled
        holder.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            callback.onToggle(item, isChecked)
        }

        holder.btnShare.setOnClickListener { callback.onShare(item) }
        holder.btnEdit.setOnClickListener { callback.onEdit(item) }
        holder.btnDelete.setOnClickListener { callback.onDelete(item) }
    }

    override fun getItemCount(): Int = items.size
}

/**
 * 分类帮助信息。
 *
 * @param title       帮助对话框标题
 * @param subtitle    副标题
 * @param description 功能说明（该列表是做什么的）
 * @param addHow      添加方式（怎么添加特征）
 * @param examples    示例值
 * @param modifiedWhat 修改的文件 / 代码（处理时作用于哪个文件、哪段逻辑）
 * @param relatedWith 配合使用的功能（与哪些列表搭配）
 * @param tip         小贴士
 */
data class HelpInfo(
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    @StringRes val descriptionRes: Int,
    @StringRes val addHowRes: Int,
    @StringRes val examplesRes: Int,
    @StringRes val modifiedWhatRes: Int,
    @StringRes val relatedWithRes: Int = 0,
    @StringRes val tipRes: Int = 0
)

/** 配置目录（用于帮助文本展示，与 AdPatternConfig 保持一致） */
private const val HELP_CONFIG_NAME = "ad_patterns.json"

/**
 * 各分类的帮助内容映射。
 *
 * 说明（对应工程内实际代码）：
 * - 配置文件：外部存储 /storage/emulated/0/APKEditor/ad_patterns.json
 * - DEX 修补：core/DexPatcher.kt（置空广告类方法 / 置空广告链接 / 强制返回 true）
 * - AXML 清单：core/AxmlAdRemover.kt（移除 AndroidManifest 中的广告组件）
 * - 原生库清理：core/AdRemover.kt 的 cleanAdSdkLibs()（删除 lib 目录下的 .so 原生库文件）
 * - assets 清理：core/AdRemover.kt 的 cleanAdSdkAssets()（删除 assets 广告文件）
 */
private val CATEGORY_HELP: Map<AdPatternConfig.Category, HelpInfo> = mapOf(
    AdPatternConfig.Category.SDK_PACKAGES to HelpInfo(
        titleRes = R.string.h_7e235a0c,
        subtitleRes = R.string.h_6e8ddbbd,
        descriptionRes = R.string.h_70bbbe57,
        addHowRes = R.string.h_6a3c653f,
        examplesRes = R.string.h_00a68483,
        modifiedWhatRes = R.string.h_6c1094f9,
        relatedWithRes = R.string.h_2d8e8ed2,
        tipRes = R.string.h_36427794
    ),
    AdPatternConfig.Category.CLASS_KEYWORDS to HelpInfo(
        titleRes = R.string.h_32b1989c,
        subtitleRes = R.string.h_599cc493,
        descriptionRes = R.string.h_6ab5a0e9,
        addHowRes = R.string.h_6303de15,
        examplesRes = R.string.h_04cd3cff,
        modifiedWhatRes = R.string.h_af7dfb03,
        relatedWithRes = R.string.h_7f86257a,
        tipRes = R.string.h_1e8d2df8
    ),
    AdPatternConfig.Category.METHOD_PATTERNS to HelpInfo(
        titleRes = R.string.h_d0997ec0,
        subtitleRes = R.string.h_6ac37232,
        descriptionRes = R.string.h_90af539a,
        addHowRes = R.string.h_21806150,
        examplesRes = R.string.h_16753bc3,
        modifiedWhatRes = R.string.h_b82698e4,
        relatedWithRes = R.string.h_6d79cd1c,
        tipRes = R.string.h_9fd2d8c2
    ),
    AdPatternConfig.Category.URL_PATTERNS to HelpInfo(
        titleRes = R.string.h_bc77dbf8,
        subtitleRes = R.string.h_b4ea6ec8,
        descriptionRes = R.string.h_fe97b23b,
        addHowRes = R.string.h_9fa3cca0,
        examplesRes = R.string.h_87ae9335,
        modifiedWhatRes = R.string.h_fa8a0296,
        relatedWithRes = R.string.h_0b1c5479,
        tipRes = R.string.h_166c2b0d
    ),
    AdPatternConfig.Category.AD_VIEW_NAMES to HelpInfo(
        titleRes = R.string.h_02be3cd1,
        subtitleRes = R.string.h_0094af95,
        descriptionRes = R.string.h_e77ef5d2,
        addHowRes = R.string.h_3c1e4f2e,
        examplesRes = R.string.h_98d11213,
        modifiedWhatRes = R.string.h_bffae154,
        relatedWithRes = R.string.h_14e50afa,
        tipRes = R.string.h_ddc38ea1
    ),
    AdPatternConfig.Category.AD_ACTIVITIES to HelpInfo(
        titleRes = R.string.h_f08c72bc,
        subtitleRes = R.string.h_53cb60e6,
        descriptionRes = R.string.h_284f10db,
        addHowRes = R.string.h_a6775ccc,
        examplesRes = R.string.h_17ac7253,
        modifiedWhatRes = R.string.h_21d3c920,
        relatedWithRes = R.string.h_4222e5fa,
        tipRes = R.string.h_52279c25
    ),
    AdPatternConfig.Category.AD_SERVICES to HelpInfo(
        titleRes = R.string.h_a092d8f0,
        subtitleRes = R.string.h_324f699a,
        descriptionRes = R.string.h_cab966b6,
        addHowRes = R.string.h_a17816fd,
        examplesRes = R.string.h_3141af8c,
        modifiedWhatRes = R.string.h_80943b1f,
        relatedWithRes = R.string.h_ffdeee21,
        tipRes = R.string.h_430a8d97
    ),
    AdPatternConfig.Category.AD_RECEIVERS to HelpInfo(
        titleRes = R.string.h_840399bf,
        subtitleRes = R.string.h_bc9facfc,
        descriptionRes = R.string.h_0a855e59,
        addHowRes = R.string.h_cfd21cdc,
        examplesRes = R.string.h_bf82a4bf,
        modifiedWhatRes = R.string.h_b7a517ca,
        relatedWithRes = R.string.h_ffdeee21,
        tipRes = R.string.h_1c397bbb
    ),
    AdPatternConfig.Category.FORCE_TRUE_METHODS to HelpInfo(
        titleRes = R.string.h_3c1862f0,
        subtitleRes = R.string.h_6bfa92c8,
        descriptionRes = R.string.h_8d7ba09c,
        addHowRes = R.string.h_0d37a3a3,
        examplesRes = R.string.h_03277167,
        modifiedWhatRes = R.string.h_2be69e45,
        relatedWithRes = R.string.h_130a118a,
        tipRes = R.string.h_69f3d080
    ),
    AdPatternConfig.Category.FORCE_FALSE_METHODS to HelpInfo(
        titleRes = R.string.h_c84eabb9,
        subtitleRes = R.string.h_e17b0b80,
        descriptionRes = R.string.h_481170fc,
        addHowRes = R.string.h_46137025,
        examplesRes = R.string.h_556c8e96,
        modifiedWhatRes = R.string.h_dd6c571b,
        relatedWithRes = R.string.h_67fac552,
        tipRes = R.string.h_69f3d080
    ),
    AdPatternConfig.Category.AD_ASSET_PATHS to HelpInfo(
        titleRes = R.string.h_69000422,
        subtitleRes = R.string.h_5d4aa133,
        descriptionRes = R.string.h_f7d28e75,
        addHowRes = R.string.h_52aa344f,
        examplesRes = R.string.h_7dda6d57,
        modifiedWhatRes = R.string.h_e785557e,
        relatedWithRes = R.string.h_8c6fb706,
        tipRes = R.string.h_8b98d772
    ),
    AdPatternConfig.Category.LIB_FILE_KEYWORDS to HelpInfo(
        titleRes = R.string.h_c4ed42f4,
        subtitleRes = R.string.h_85a0ed61,
        descriptionRes = R.string.h_122f1ca8,
        addHowRes = R.string.h_a3e07f53,
        examplesRes = R.string.h_1516029a,
        modifiedWhatRes = R.string.h_fc2459c8,
        relatedWithRes = R.string.h_e0aa11ae,
        tipRes = R.string.h_b9ca883a
    ),
    AdPatternConfig.Category.ASSET_KEYWORDS to HelpInfo(
        titleRes = R.string.h_757b3361,
        subtitleRes = R.string.h_1394b002,
        descriptionRes = R.string.h_5c5b99c0,
        addHowRes = R.string.h_0456832d,
        examplesRes = R.string.h_3db4cd4d,
        modifiedWhatRes = R.string.h_48574fbe,
        relatedWithRes = R.string.h_f66c3cc8,
        tipRes = R.string.h_fa4706ec
    ),
    AdPatternConfig.Category.METHOD_NEUTRALIZE_KEYWORDS to HelpInfo(
        titleRes = R.string.h_7c6a17aa,
        subtitleRes = R.string.h_7ba901d8,
        descriptionRes = R.string.h_430b3928,
        addHowRes = R.string.h_b3c9c976,
        examplesRes = R.string.h_e75ba18c,
        modifiedWhatRes = R.string.h_521147e7,
        relatedWithRes = R.string.h_68c89932,
        tipRes = R.string.h_91d57778
    ),
    AdPatternConfig.Category.AD_PERMISSIONS to HelpInfo(
        titleRes = R.string.h_7de4534e,
        subtitleRes = R.string.h_ceefa2cd,
        descriptionRes = R.string.h_bde178a7,
        addHowRes = R.string.h_5c244e39,
        examplesRes = R.string.h_e870ac19,
        modifiedWhatRes = R.string.h_7b58f794,
        relatedWithRes = R.string.h_01be0fd6,
        tipRes = R.string.h_67914606
    ),
    AdPatternConfig.Category.ROOT_FILE_KEYWORDS to HelpInfo(
        titleRes = R.string.h_e58abe16,
        subtitleRes = R.string.h_41994826,
        descriptionRes = R.string.h_9fe0ce82,
        addHowRes = R.string.h_e25ab6b8,
        examplesRes = R.string.h_18374fbf,
        modifiedWhatRes = R.string.h_ec07479c,
        relatedWithRes = R.string.h_7d2579cf,
        tipRes = R.string.h_6591496b
    ),
    AdPatternConfig.Category.RES_LAYOUT_KEYWORDS to HelpInfo(
        titleRes = R.string.h_e93cb7ad,
        subtitleRes = R.string.h_827cfb79,
        descriptionRes = R.string.h_04cc78d8,
        addHowRes = R.string.h_4f5a392d,
        examplesRes = R.string.h_73a8f02e,
        modifiedWhatRes = R.string.h_0d3ea6cb,
        relatedWithRes = R.string.h_cd984ab2,
        tipRes = R.string.h_37f14a6c
    ),
    AdPatternConfig.Category.STRING_PATTERNS to HelpInfo(
        titleRes = R.string.h_369a31f0,
        subtitleRes = R.string.h_aa223402,
        descriptionRes = R.string.h_dd0a7c93,
        addHowRes = R.string.h_0f8a3b32,
        examplesRes = R.string.h_f3a25b4b,
        modifiedWhatRes = R.string.h_cc8469a8,
        relatedWithRes = R.string.h_271e5d10,
        tipRes = R.string.h_2664f5c6
    ),
    AdPatternConfig.Category.FLUTTER_PATTERNS to HelpInfo(
        titleRes = R.string.h_566859e2,
        subtitleRes = R.string.h_9a5a28fd,
        descriptionRes = R.string.h_0dd640d5,
        addHowRes = R.string.h_c1ca70de,
        examplesRes = R.string.h_c393daa8,
        modifiedWhatRes = R.string.h_857a16c4,
        relatedWithRes = R.string.h_ad3146c4,
        tipRes = R.string.h_94d2d961
    )
)
