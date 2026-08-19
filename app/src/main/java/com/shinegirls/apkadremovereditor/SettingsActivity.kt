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

    private lateinit var tvConfigPath: TextView
    private lateinit var tvConfigStats: TextView
    private lateinit var tvThemeMode: TextView
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
            Log.e("SettingsActivity", "布局加载失败", e)
            UiUtils.error(this, "设置界面加载失败: ${e.message}")
            finish()
            return
        }

        try {
            val toolbar = findViewById<Toolbar>(R.id.toolbar)
            setSupportActionBar(toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            toolbar.setNavigationOnClickListener { finish() }

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
                UiUtils.success(this, "已重置为默认配置路径")
            }
            findViewById<MaterialButton>(R.id.btnChangeOutputDir).setOnClickListener {
                showChangePathDialog(isConfigPath = false)
            }
            findViewById<MaterialButton>(R.id.btnResetOutputDir).setOnClickListener {
                PathPreferences.resetOutputDir(this)
                updatePathDisplay()
                UiUtils.success(this, "已重置为默认输出目录")
            }

            // 保存按钮：单击保存，长按重置默认
            btnSave.setOnClickListener {
                val success = AdPatternConfig.saveConfig(config, this)
                if (success) {
                    UiUtils.success(this, "配置已保存")
                    updateStats()
                } else {
                    UiUtils.error(this, "保存失败，请检查存储权限")
                }
            }
            btnSave.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setTitle("重置默认配置")
                    .setMessage("确定要恢复所有广告特征为内置默认值？\n当前自定义修改将丢失。")
                    .setPositiveButton("重置") { _, _ ->
                        config = AdPatternConfig.resetToDefault(this)
                        displayConfig()
                        UiUtils.success(this, "已重置为默认配置")
                    }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
        } catch (e: Exception) {
            Log.e("SettingsActivity", "初始化失败", e)
            UiUtils.error(this, "设置初始化失败: ${e.message}")
            finish()
        }
    }

    /**
     * 更新外观卡片中当前主题模式的显示。
     */
    private fun updateThemeDisplay() {
        tvThemeMode.text = ThemeManager.modeDisplayName(ThemeManager.getMode(this))
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
            .setPositiveButton("取消", null)
            .create()
        themeDialog.show()
        // 自适应屏幕：内容过长时限制高度并滚动，避免溢出屏幕
        UiUtils.fitDialogToScreen(themeDialog)
    }

    /**
     * 加载配置并显示。
     */
    private fun loadAndDisplayConfig() {
        try {
            config = AdPatternConfig.loadConfig(this)
        } catch (e: Exception) {
            Log.e("SettingsActivity", "加载配置失败，使用默认配置", e)
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
        setSectionTitle(R.id.sectionDex, "DEX 代码处理", "置空广告方法 / 解锁 VIP")
        setSectionTitle(R.id.sectionRes, "资源文件清理", "删除广告 SDK 文件")
        setSectionTitle(R.id.sectionManifest, "清单权限", "移除广告权限声明")

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
                    if (isChecked) "Flutter 广告特征已启用" else "Flutter 广告特征已关闭")
            }
        }

        // DEX 体积优化开关：移除调试信息（行号/局部变量表/参数名）减小 APK 体积
        findViewById<SwitchCompat>(R.id.swDexOptimizeEnabled)?.apply {
            isChecked = PathPreferences.isDexOptimizeEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, isChecked ->
                PathPreferences.setDexOptimizeEnabled(this@SettingsActivity, isChecked)
                UiUtils.info(this@SettingsActivity,
                    if (isChecked) "DEX 体积优化已启用" else "DEX 体积优化已关闭")
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
                    UiUtils.info(this@SettingsActivity, "签名效验去除已启用")
                } else {
                    val cur = PathPreferences.getSignRemovalMode(this@SettingsActivity)
                    if (cur != 0) {
                        PathPreferences.setSignRemovalMode(this@SettingsActivity, 0)
                    }
                    UiUtils.info(this@SettingsActivity, "签名效验去除已关闭")
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
            com.shinegirls.apkadremovereditor.core.SignatureVerificationRemover.MODE_ORIGINAL -> "原包去除签名效验"
            com.shinegirls.apkadremovereditor.core.SignatureVerificationRemover.MODE_NORMAL -> "普通去除签名效验"
            else -> "未启用（点击修改选择模式）"
        }
    }

    /**
     * 弹出签名效验模式选择对话框。
     */
    private fun showSignModeDialog() {
        val options = arrayOf("普通去除签名效验", "原包去除签名效验")
        val startMode = PathPreferences.getSignRemovalMode(this)
        val checked = when {
            startMode == com.shinegirls.apkadremovereditor.core.SignatureVerificationRemover.MODE_ORIGINAL -> 1
            else -> 0
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选择签名效验去除模式")
            .setSingleChoiceItems(options, checked) { _, which ->
                when (which) {
                    0 -> PathPreferences.setSignRemovalMode(this,
                        com.shinegirls.apkadremovereditor.core.SignatureVerificationRemover.MODE_NORMAL)
                    else -> PathPreferences.setSignRemovalMode(this,
                        com.shinegirls.apkadremovereditor.core.SignatureVerificationRemover.MODE_ORIGINAL)
                }
                refreshSignModeUi()
                UiUtils.info(this, "签名效验模式已设为：${options[which]}")
            }
            .setPositiveButton("确定", null)
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
            if (isCustom) "已自定义（$so / $hook）" else "默认（SignatureKiIIer）"
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
                UiUtils.info(this, "原包路径 / 解压路径 / So库名 / 钩子类名均不能为空")
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
            UiUtils.info(this, "注入参数已保存：$so / $hook")
        }
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSignCancel).setOnClickListener {
            signDialog.dismiss()
        }
    }

    /**
     * 更新统计信息。
     */
    private fun updateStats() {
        tvConfigStats.text = "共 ${config.totalCount()} 条特征"
        findViewById<TextView>(R.id.tvFlutterStats)?.text = "共 ${config.flutterPatterns.size} 条特征"
    }

    /**
     * 更新订阅源数量统计。
     */
    private fun updateSubscriptionStats() {
        val all = SubscriptionManager.loadSubscriptions(this)
        val enabled = all.count { it.enabled }
        tvSubscriptionStats.text = "共 ${all.size} 个订阅源，已开启 $enabled 个"
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

        val title = if (isConfigPath) "修改配置文件路径" else "修改 APK 输出目录"
        val hint = if (isConfigPath) {
            "完整文件路径，例如：\n/storage/emulated/0/APKEditor/ad_patterns.json"
        } else {
            "目录路径，例如：\n/storage/emulated/0/APKEditor"
        }
        dialogView.findViewById<TextView>(R.id.tvPathHint).text = hint

        val pathDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton("确定") { _, _ ->
                val newPath = etPath.text.toString().trim()
                if (newPath.isBlank()) {
                    UiUtils.warning(this, "路径不能为空")
                    return@setPositiveButton
                }

                val success = if (isConfigPath) {
                    // 配置文件路径必须以 .json 结尾
                    if (!newPath.endsWith(".json")) {
                        UiUtils.warning(this, "配置文件路径需以 .json 结尾")
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
                    UiUtils.success(this, "路径已更新")
                } else {
                    UiUtils.error(this, "路径设置失败，请检查权限")
                }
            }
            .setNegativeButton("取消", null)
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
                Log.w("SettingsActivity", "卡片视图未找到: cardId=$cardId")
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
                Log.w("SettingsActivity", "卡片子视图未找到: $category")
                return
            }

            tvName.text = category.displayName
            val list = AdPatternConfig.getCategoryList(config, category)
            tvCount.text = "${list.size} 条"

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
                    UiUtils.info(this, if (isChecked) "${category.displayName}已启用" else "${category.displayName}已关闭")
                }
            }
        } catch (e: Exception) {
            Log.e("SettingsActivity", "绑定分类卡片失败: $category", e)
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
                            UiUtils.warning(this@SettingsActivity, "该特征已存在")
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
                    .setTitle("删除特征")
                    .setMessage("确定删除 \"${list[position].take(50)}\" ？")
                    .setPositiveButton("删除") { _, _ ->
                        list.removeAt(position)
                        rvPatterns.adapter?.notifyItemRemoved(position)
                        rvPatterns.adapter?.notifyItemRangeChanged(position, list.size)
                        updateEmptyHint(list, tvEmptyHint)
                        // 实时保存
                        AdPatternConfig.saveConfig(config, this@SettingsActivity)
                        updateCategoryCount(category, list.size)
                        updateStats()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        })

        rvPatterns.layoutManager = LinearLayoutManager(this)
        rvPatterns.adapter = adapter

        // 添加按钮
        btnAddPattern.setOnClickListener {
            val text = etNewPattern.text.toString().trim()
            if (text.isEmpty()) {
                UiUtils.warning(this, "请输入特征内容")
                return@setOnClickListener
            }
            if (list.any { it.equals(text, ignoreCase = true) }) {
                UiUtils.warning(this, "该特征已存在")
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
            UiUtils.success(this, "已添加")
        }

        updateEmptyHint(list, tvEmptyHint)

        val patternDialog = AlertDialog.Builder(this)
            .setTitle(category.displayName + " (${list.size} 条)")
            .setView(dialogView)
            .setPositiveButton("关闭", null)
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
            .setTitle("编辑特征")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                onSave(input.text.toString().trim())
            }
            .setNegativeButton("取消", null)
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

        dialogView.findViewById<TextView>(R.id.tvHelpTitle).text = help.title
        dialogView.findViewById<TextView>(R.id.tvHelpSubtitle).text = help.subtitle

        val body = dialogView.findViewById<LinearLayout>(R.id.llHelpBody)
        body.removeAllViews()

        // 功能说明
        addHelpSection(body, "功能说明", help.description, R.color.primary)
        // 添加方式
        addHelpSection(body, "添加方式", help.addHow, R.color.accent)
        // 示例
        addHelpSection(body, "示例", help.examples, R.color.accent_dark)
        // 修改内容
        addHelpSection(body, "修改的文件 / 代码", help.modifiedWhat, R.color.primary_dark)
        // 配合使用
        if (help.relatedWith.isNotBlank()) {
            addHelpSection(body, "配合使用", help.relatedWith, R.color.teal_700)
        }
        // 提示
        addHelpSection(body, "小贴士", help.tip, R.color.text_secondary)

        val helpDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("知道了", null)
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
        tvCount.text = "$count 条"
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
                    .setTitle("删除订阅源")
                    .setMessage("确定删除订阅源 \"${sub.name}\" ？")
                    .setPositiveButton("删除") { _, _ ->
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
                    .setNegativeButton("取消", null)
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
            .setPositiveButton("关闭", null)
            .create()
        subListDialog.show()
        // 自适应屏幕：内容过长时限制高度并滚动，避免溢出屏幕
        UiUtils.fitDialogToScreen(subListDialog)
        } catch (e: Exception) {
            Log.e("SettingsActivity", "订阅管理对话框打开失败", e)
            UiUtils.error(this, "打开失败: ${e.message}")
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
     * 显示"添加订阅"对话框：输入口令或订阅源直链链接，解析并添加订阅源。
     *
     * 支持两种输入：
     * - 订阅源口令：以 ADSUB: 开头，解码后直接添加
     * - 直链链接：http(s):// 开头的配置 JSON 地址，异步拉取并校验后添加为 URL 型订阅
     */
    private fun showAddSubscriptionDialog(onAdded: (Subscription) -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_subscription, null)
        val etToken = dialogView.findViewById<TextInputEditText>(R.id.etSubscriptionToken)

        val dialog = AlertDialog.Builder(this)
            .setTitle("添加订阅")
            .setView(dialogView)
            .setPositiveButton("添加", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val input = etToken.text.toString().trim()
                if (input.isEmpty()) {
                    UiUtils.warning(this, "请输入订阅源口令或直链链接")
                    return@setOnClickListener
                }

                // 直链链接：http:// 或 https:// 开头
                if (input.startsWith("http://") || input.startsWith("https://")) {
                    addSubscriptionByUrl(input, dialog, onAdded)
                    return@setOnClickListener
                }

                // 订阅源口令
                val parsed = SubscriptionManager.decodeToken(input)
                if (parsed == null) {
                    UiUtils.warning(this, "口令无效，请检查后重试")
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
                UiUtils.success(this, "已添加订阅源：${parsed.name}")
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
        UiUtils.info(this, "正在拉取订阅源配置...")

        lifecycleScope.launch {
            val jsonStr = withContext(Dispatchers.IO) {
                SubscriptionManager.fetchRemoteConfig(url)
            }
            if (jsonStr == null) {
                withContext(Dispatchers.Main) {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    UiUtils.error(this@SettingsActivity, "拉取失败，请检查链接是否有效")
                }
                return@launch
            }
            if (!SubscriptionManager.isValidConfigJson(jsonStr)) {
                withContext(Dispatchers.Main) {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    UiUtils.error(this@SettingsActivity, "链接内容不是有效的广告特征配置")
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                val name = url.substringAfterLast('/').substringBefore('.').ifBlank { "直链订阅源" }
                val newSub = Subscription(
                    id = java.util.UUID.randomUUID().toString(),
                    name = name,
                    type = SubscriptionManager.Type.URL,
                    url = url
                )
                onAdded(newSub)
                dialog.dismiss()
                UiUtils.success(this@SettingsActivity, "已添加订阅源：$name")
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

        etName.setText(sub.name)
        if (sub.type == SubscriptionManager.Type.URL) {
            tilUrl.visibility = View.VISIBLE
            etUrl.setText(sub.url)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("编辑订阅源")
            .setView(dialogView)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = etName.text.toString().trim()
                if (name.isEmpty()) {
                    UiUtils.warning(this, "请输入订阅源名称")
                    return@setOnClickListener
                }
                val updated = if (sub.type == SubscriptionManager.Type.URL) {
                    sub.copy(name = name, url = etUrl.text.toString().trim())
                } else {
                    sub.copy(name = name)
                }
                onSaved(updated)
                dialog.dismiss()
                UiUtils.success(this, "已保存订阅源")
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

        rgType.setOnCheckedChangeListener { _, checkedId ->
            val isUrl = checkedId == R.id.rbShareUrl
            tilUrl.visibility = if (isUrl) View.VISIBLE else View.GONE
            tvPreview.visibility = View.GONE
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("分享配置为订阅源口令")
            .setView(dialogView)
            .setPositiveButton("生成口令", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = etName.text.toString().trim()
                if (name.isEmpty()) {
                    UiUtils.warning(this, "请输入订阅源名称")
                    return@setOnClickListener
                }
                val isUrl = rgType.checkedRadioButtonId == R.id.rbShareUrl
                val token = if (isUrl) {
                    val url = etUrl.text.toString().trim()
                    if (url.isEmpty()) {
                        UiUtils.warning(this, "请输入远程配置 URL")
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
        tvPreview.text = "口令已生成，可复制或分享给他人。"
        tvPreview.visibility = View.VISIBLE

        val dialog = AlertDialog.Builder(this)
            .setTitle("订阅源口令")
            .setView(dialogView)
            .setPositiveButton("复制", null)
            .setNeutralButton("系统分享", null)
            .setNegativeButton("关闭", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                copyToClipboard(token)
                UiUtils.success(this, "口令已复制到剪贴板")
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
        val clip = ClipData.newPlainText("订阅源口令", text)
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
        startActivity(Intent.createChooser(sendIntent, "分享订阅源口令"))
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
            UiUtils.info(this, "已关闭所有订阅，恢复默认配置")
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
                    Log.e("SettingsActivity", "解析订阅配置失败: ${sub.name}", e)
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

        UiUtils.info(this, "正在拉取远程配置...")

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
                UiUtils.error(this@SettingsActivity, "部分订阅拉取失败：${errors.joinToString(", ")}")
            }
        }
    }

    /**
     * 合并多个配置并应用保存。
     */
    private fun applyMergedConfigs(configs: List<AdPatternConfig.AdPatterns>, totalEnabled: Int) {
        if (configs.isEmpty()) {
            UiUtils.warning(this, "订阅配置为空，未做更改")
            return
        }
        config = AdPatternConfig.merge(configs)
        val success = AdPatternConfig.saveConfig(config, this)
        if (success) {
            displayConfig()
            UiUtils.success(this, "已应用 $totalEnabled 个订阅源（共 ${config.totalCount()} 条特征）")
        } else {
            UiUtils.error(this, "应用订阅失败，请检查存储权限")
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
            holder.tvType.text = "URL 订阅源"
            holder.tvType.setTextColor(
                androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.primary_dark)
            )
            holder.tvType.setBackgroundResource(R.drawable.bg_type_badge_accent)
        } else {
            holder.tvType.text = "内嵌配置"
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
    val title: String,
    val subtitle: String,
    val description: String,
    val addHow: String,
    val examples: String,
    val modifiedWhat: String,
    val relatedWith: String = "",
    val tip: String = ""
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
        title = "广告 SDK 包名",
        subtitle = "识别并移除广告 SDK 的根包名",
        description = "声明要识别/移除的广告 SDK 的 Java 包名。这是最核心的分类：处理时用它识别广告类、广告清单组件，并自动推导 lib 目录里的 .so 原生库名。",
        addHow = "点击右侧\"管理\"→ 在输入框输入广告 SDK 的完整包名 → 点\"添加\"。也可用手机文件管理器直接编辑配置文件。",
        examples = "com.google.android.gms.ads\n" +
            "com.bytedance.sdk.openadsdk\n" +
            "com.qq.e.ads\n" +
            "com.mbridge.msdk",
        modifiedWhat = "写入外部配置文件 $HELP_CONFIG_NAME 的 \"sdk_packages\" 字段。\n" +
            "运行时由 core/DexPatcher.kt（DEX 类识别）、core/AxmlAdRemover.kt（清单组件移除）、core/AdRemover.kt（lib 原生库清理）读取。",
        relatedWith = "常与\"广告类名关键词\"\"广告 SDK 原生库关键词\"配合；包名会自动推导 lib 目录 .so 库名关键词。",
        tip = "包名用点号格式（如 com.x.y），处理时自动转为斜杠格式匹配 DEX 类名。"
    ),
    AdPatternConfig.Category.CLASS_KEYWORDS to HelpInfo(
        title = "广告类名关键词",
        subtitle = "按关键词识别广告类",
        description = "广告类名的关键词片段。类名命中即判定为广告类，将其广告方法体替换为返回默认值（置空），从而屏蔽广告展示逻辑。",
        addHow = "点击\"管理\"→ 输入广告类名关键词（可只写片段）→ 点\"添加\"。",
        examples = "AdView\nAdActivity\nBannerAd\nInterstitialAd",
        modifiedWhat = "写入 $HELP_CONFIG_NAME 的 \"class_keywords\" 字段。\n" +
            "运行时由 core/DexPatcher.kt 做广告类匹配，也由 core/AxmlAdRemover.kt 匹配清单组件。",
        relatedWith = "与\"广告 SDK 包名\"配合效果最佳，也常与\"广告 View 类名\"\"广告 Activity\"等用途重叠。",
        tip = "建议写通用片段而非完整路径，命中率更高；关键词越多误伤可能性越大，请谨慎。"
    ),
    AdPatternConfig.Category.METHOD_PATTERNS to HelpInfo(
        title = "广告方法名",
        subtitle = "精确置空指定方法",
        description = "要精确置空的广告方法名。方法名与该列表项完全一致时，方法体被替换为返回默认值，从而取消广告加载/展示调用。",
        addHow = "点击\"管理\"→ 输入与代码中完全一致的方法名 → 点\"添加\"。",
        examples = "loadAd\nshowAd\nloadInterstitialAd\nshowRewardedVideo",
        modifiedWhat = "写入 $HELP_CONFIG_NAME 的 \"method_patterns\" 字段。\n" +
            "运行时由 core/DexPatcher.kt 做方法名精确匹配并置空方法体。",
        relatedWith = "与\"广告方法置空关键词\"配合使用：本分类为精确匹配，后者为模糊关键词匹配。",
        tip = "方法名需与反编译后的代码完全一致（含大小写），否则无法命中。"
    ),
    AdPatternConfig.Category.URL_PATTERNS to HelpInfo(
        title = "广告 URL / 域名",
        subtitle = "置空广告请求链接",
        description = "广告请求 URL 或域名。处理时会把 DEX 中以 const-string 形式存在的广告链接字符串置空为空字符串，阻断广告请求。",
        addHow = "点击\"管理\"→ 输入广告域名或 URL 片段 → 点\"添加\"。",
        examples = "googleads.g.doubleclick.net\nadmob.com\nadview.cn\nca-app-pub-",
        modifiedWhat = "写入 $HELP_CONFIG_NAME 的 \"url_patterns\" 字段。\n" +
            "运行时由 core/DexPatcher.kt 扫描 const-string 指令并置空匹配的广告链接。",
        relatedWith = "通常独立使用，也可与\"广告 SDK 包名\"配合增强屏蔽效果。",
        tip = "只影响形如网址的字符串（含 ://、www、.com 等），避免误伤普通文本。"
    ),
    AdPatternConfig.Category.AD_VIEW_NAMES to HelpInfo(
        title = "广告 View 类名",
        subtitle = "识别广告视图组件类",
        description = "广告 View 组件类名关键词，用于识别广告视图类（如横幅、插屏、激励视频的 View）。类名命中即按广告类处理并置空其广告方法。",
        addHow = "点击\"管理\"→ 输入广告 View 类名或关键词 → 点\"添加\"。",
        examples = "AdView\nBannerAd\nNativeAdView\nSplashView",
        modifiedWhat = "写入 $HELP_CONFIG_NAME 的 \"ad_view_names\" 字段。\n" +
            "运行时由 core/DexPatcher.kt 做广告类匹配。",
        relatedWith = "与\"广告 SDK 包名\"\"广告类名关键词\"配合，同属类名识别体系。",
        tip = "与\"广告类名关键词\"的作用有重叠，可任选其一维护。"
    ),
    AdPatternConfig.Category.AD_ACTIVITIES to HelpInfo(
        title = "广告 Activity",
        subtitle = "识别广告页面类",
        description = "广告 Activity（页面）类名关键词，用于识别广告页面类，处理时置空其广告方法，并在清单中移除对应组件。",
        addHow = "点击\"管理\"→ 输入广告 Activity 类名或关键词 → 点\"添加\"。",
        examples = "AdActivity\nInterstitialAdActivity\nSplashActivity",
        modifiedWhat = "写入 $HELP_CONFIG_NAME 的 \"ad_activities\" 字段。\n" +
            "由 core/DexPatcher.kt（类识别）与 core/AxmlAdRemover.kt（AndroidManifest 组件移除）读取。",
        relatedWith = "与\"广告 SDK 包名\"配合，可同时清理清单中的相关 Activity。",
        tip = "清单中匹配的 <activity> 会被整体移除，删除前请确认该 Activity 确为广告用途。"
    ),
    AdPatternConfig.Category.AD_SERVICES to HelpInfo(
        title = "广告 Service",
        subtitle = "识别广告后台服务类",
        description = "广告 Service（后台服务）类名关键词，用于识别广告后台服务类，处理时置空其广告方法，并在清单中移除对应组件。",
        addHow = "点击\"管理\"→ 输入广告 Service 类名或关键词 → 点\"添加\"。",
        examples = "AdService\nDownloadService\nUpdateService",
        modifiedWhat = "写入 $HELP_CONFIG_NAME 的 \"ad_services\" 字段。\n" +
            "由 core/DexPatcher.kt（类识别）与 core/AxmlAdRemover.kt（清单组件移除）读取。",
        relatedWith = "与\"广告 SDK 包名\"配合使用。",
        tip = "清单中匹配的 <service> 会被整体移除。"
    ),
    AdPatternConfig.Category.AD_RECEIVERS to HelpInfo(
        title = "广告 Receiver",
        subtitle = "识别广告广播接收类",
        description = "广告广播接收器（Receiver）类名关键词，用于识别广告广播组件，处理时置空其广告方法，并在清单中移除对应组件。",
        addHow = "点击\"管理\"→ 输入广告 Receiver 类名或关键词 → 点\"添加\"。",
        examples = "AdReceiver\nBootReceiver\nInstallReceiver",
        modifiedWhat = "写入 $HELP_CONFIG_NAME 的 \"ad_receivers\" 字段。\n" +
            "由 core/DexPatcher.kt（类识别）与 core/AxmlAdRemover.kt（清单组件移除）读取。",
        relatedWith = "与\"广告 SDK 包名\"配合使用。",
        tip = "清单中匹配的 <receiver> 会被整体移除。"
    ),
    AdPatternConfig.Category.FORCE_TRUE_METHODS to HelpInfo(
        title = "强制返回 true 的方法名",
        subtitle = "解锁 VIP / 会员 / 专业版判定",
        description = "用于解锁 VIP/会员/专业版等付费判定方法。方法名精确命中且返回类型为 boolean 或 int 时，方法体被替换为\"返回 true / 1\"，直接绕过付费校验。",
        addHow = "点击\"管理\"→ 输入要解锁的判定方法名 → 点\"添加\"。",
        examples = "isVip\nisPro\nisPremium\nisMember\nisPaid",
        modifiedWhat = "写入 $HELP_CONFIG_NAME 的 \"force_true_methods\" 字段。\n" +
            "运行时由 core/DexPatcher.kt 的强制返回 true 逻辑处理（仅 boolean/int 返回类型）。",
        relatedWith = "独立功能，作用于所有类、所有方法，不依赖其他分类。",
        tip = "仅影响返回类型为 boolean(Z) 或 int(I) 的方法；其他类型自动跳过，避免生成非法指令。"
    ),
    AdPatternConfig.Category.FORCE_FALSE_METHODS to HelpInfo(
        title = "强制返回 false 的方法名",
        subtitle = "让广告判定方法返回 false",
        description = "用于\"广告是否已加载 / 是否正在展示 / 是否有广告\"等判定方法。方法名精确命中且返回类型为 boolean 或 int 时，方法体被替换为\"返回 false / 0\"，让应用认为广告从未加载或展示，从而跳过广告展示逻辑。",
        addHow = "点击\"管理\"→ 输入要强制返回 false 的判定方法名 → 点\"添加\"。",
        examples = "isAdLoaded\nhasAd\nisAdShowing\nisAdReady\nisInterstitialLoaded",
        modifiedWhat = "写入 $HELP_CONFIG_NAME 的 \"force_false_methods\" 字段。\n" +
            "运行时由 core/DexPatcher.kt 的强制返回 false 逻辑处理（仅 boolean/int 返回类型）。",
        relatedWith = "与\"强制返回 true\"互补：true 用于解锁 VIP，false 用于屏蔽广告展示判定。",
        tip = "仅影响返回类型为 boolean(Z) 或 int(I) 的方法；其他类型自动跳过，避免生成非法指令。"
    ),
    AdPatternConfig.Category.AD_ASSET_PATHS to HelpInfo(
        title = "assets 广告文件路径",
        subtitle = "删除指定广告资源路径",
        description = "要删除的 assets 目录下的广告 SDK 文件/目录路径。精确匹配，命中即整条（文件或目录）删除，用于移除打进 assets 的广告插件、胶水层等。",
        addHow = "点击\"管理\"→ 输入相对路径（可写 assets/ 前缀，也可省略）→ 点\"添加\"。",
        examples = "assets/gdt_plugin/gdtadv2.jar\nassets/qumeng\nassets/bdxadsdk",
        modifiedWhat = "写入 $HELP_CONFIG_NAME 的 \"ad_asset_paths\" 字段。\n" +
            "运行时由 core/AdRemover.kt 的 cleanAdSdkAssets() 删除对应路径。",
        relatedWith = "与\"assets 广告关键词\"配合：本分类为精确路径，后者为模糊关键词。",
        tip = "只删除 assets 目录内的内容，不影响 lib 与 res 目录。"
    ),
    AdPatternConfig.Category.LIB_FILE_KEYWORDS to HelpInfo(
        title = "广告 SDK 原生库关键词",
        subtitle = "删除广告 so 原生库",
        description = "lib 目录下要删除的 .so 原生库名关键词。用于删除广告 SDK 的动态库文件（如确保 lib 目录里的广告 so 被移除）。",
        addHow = "点击\"管理\"→ 输入 .so 库名关键词 → 点\"添加\"。",
        examples = "ttad\ngdt\npangle\nadmob\nbaiduad",
        modifiedWhat = "写入 $HELP_CONFIG_NAME 的 \"lib_file_keywords\" 字段。\n" +
            "运行时由 core/AdRemover.kt 的 cleanAdSdkLibs() 遍历 lib/*/ 并删除匹配的 .so 文件。",
        relatedWith = "与\"广告 SDK 包名\"配合使用（包名会自动推导部分库名关键词）。",
        tip = "关键词为子串匹配（如 ttad 可命中 libttad.so），请勿写完整路径。"
    ),
    AdPatternConfig.Category.ASSET_KEYWORDS to HelpInfo(
        title = "assets 广告关键词",
        subtitle = "按关键词删除广告资源",
        description = "assets 广告文件关键词。assets 内路径或文件名包含该关键词即删除，用于覆盖已知路径之外的同类广告资产。",
        addHow = "点击\"管理\"→ 输入关键词 → 点\"添加\"。",
        examples = "gdt\noneway\nbdxadsdk\nqumeng",
        modifiedWhat = "写入 $HELP_CONFIG_NAME 的 \"asset_keywords\" 字段。\n" +
            "运行时由 core/AdRemover.kt 的 cleanAdSdkAssets() 按关键词匹配删除。",
        relatedWith = "与\"assets 广告文件路径\"配合使用。",
        tip = "关键词为子串匹配，覆盖面广，请避免使用过于通用的词以防误删。"
    ),
    AdPatternConfig.Category.METHOD_NEUTRALIZE_KEYWORDS to HelpInfo(
        title = "广告方法置空关键词",
        subtitle = "模糊匹配置空广告方法",
        description = "广告方法置空关键词（模糊匹配）。命中广告类后，只置空方法名包含这些关键词的方法，避免过度置空导致程序崩溃（如保留构造方法、生命周期方法等）。",
        addHow = "点击\"管理\"→ 输入方法名关键词 → 点\"添加\"。",
        examples = "showad\nloadad\nonadloaded\nadclick",
        modifiedWhat = "写入 $HELP_CONFIG_NAME 的 \"method_neutralize_keywords\" 字段。\n" +
            "运行时由 core/DexPatcher.kt 的方法置空筛选逻辑（带单词边界感知）读取。",
        relatedWith = "与\"广告方法名\"配合：一个模糊关键词、一个精确方法名；常与\"广告 SDK 包名\"\"广告类名关键词\"搭配。",
        tip = "内置了大量默认关键词（_ad_、showad、loadad 等），此分类主要用于补充自定义关键词。"
    ),
    AdPatternConfig.Category.AD_PERMISSIONS to HelpInfo(
        title = "广告权限特征",
        subtitle = "移除清单中的广告 SDK 权限声明",
        description = "广告 SDK 常在 AndroidManifest.xml 中声明自定义权限（如 com.lineone.connecter.openadsdk.permission.TT_PANGOLIN、com.lineone.connecter.permission.KW_SDK_BROADCAST 等），用于保护其广告组件。命中这些权限的 <uses-permission> 声明会被从清单中移除。",
        addHow = "点击\"管理\"→ 输入权限名或权限关键词 → 点\"添加\"。运行时会按子串匹配（不区分大小写），可用广告 SDK 包名前缀或权限特征词。",
        examples = "TT_PANGOLIN\nKW_SDK\ncom.bytedance\ncom.qq.e\ncom.mbridge.msdk\ncom.kwad.sdk",
        modifiedWhat = "写入 $HELP_CONFIG_NAME 的 \"ad_permissions\" 字段。\n" +
            "运行时由 core/AxmlAdRemover.kt 的 removeAdPermissions() 匹配并移除 AndroidManifest.xml 中的 <uses-permission> 声明。",
        relatedWith = "与\"广告 SDK 包名\"\"广告 Activity / Service / Receiver\"配合使用，用于清理广告 SDK 在清单中的权限声明。",
        tip = "仅移除命中特征的权限声明，不影响 INTERNET / CAMERA 等正常功能权限；请勿把正常功能权限加入特征以免影响应用运行。"
    ),
    AdPatternConfig.Category.ROOT_FILE_KEYWORDS to HelpInfo(
        title = "APK 根目录文件关键词",
        subtitle = "删除包文件根目录的广告残留文件",
        description = "APK 解包后根目录（与 classes.dex 同目录）中可能残留的广告 SDK 配置文件、标识文件等。文件名包含该关键词即被删除，用于清理不在 lib / assets 目录里的广告残留。",
        addHow = "点击\"管理\"→ 输入文件名词关键词 → 点\"添加\"。运行时会按子串匹配（不区分大小写）。",
        examples = "tt_version\nstartup_config\noaid\nuuid\ndevice_id\noa_sdk",
        modifiedWhat = "写入 $HELP_CONFIG_NAME 的 \"root_file_keywords\" 字段。\n" +
            "运行时由 core/AdRemover.kt 的 cleanRootFiles() 遍历 APK 根目录（仅一层，不递归）并删除匹配文件。",
        relatedWith = "与\"assets 广告关键词\"\"广告 SDK 原生库关键词\"配合，分别覆盖 assets、lib、以及根目录三处广告残留。",
        tip = "只扫描 APK 根目录一层文件，不含子目录；classes.dex 等核心文件不会被删除。"
    ),
    AdPatternConfig.Category.RES_LAYOUT_KEYWORDS to HelpInfo(
        title = "Res 布局广告 View 关键词",
        subtitle = "隐藏布局文件中的广告视图区域",
        description = "APK 的 res/layout 布局文件中，广告 SDK 常以自定义 View 形式嵌入（如 com.meishu.sdk.meishu_ad.view.MeishuVideoCahceTextureView、AdView 等）。元素类名包含该关键词时，处理后会把该元素的 layout_width / layout_height 改为 0dp，将广告区域压缩为 0 尺寸隐藏，而不移除其在布局中的位置与引用，避免破坏布局结构。",
        addHow = "点击\"管理\"→ 输入广告 View 类名或关键词片段 → 点\"添加\"。运行时会按子串匹配（不区分大小写）。",
        examples = ".ad.\nmeishu_ad\nadview\nadcontainer\nbannerad\nsplashad\nadmob",
        modifiedWhat = "写入 $HELP_CONFIG_NAME 的 \"res_layout_keywords\" 字段。\n" +
            "运行时由 core/AxmlAdRemover.kt 的 hideAdLayoutViews() 改写 res/layout 及 res/layout-* 下 AXML 布局中命中元素宽高为 0dp。",
        relatedWith = "常与\"广告 SDK 包名\"\"广告 View 类名\"\"广告类名关键词\"配合：这些分类的关键词也会被一起用于布局元素匹配，命中即隐藏。",
        tip = "此关键词用于匹配布局元素类名，覆盖面广，请避免使用过于通用的词（如 ad）以防隐藏正常控件；建议使用 .ad.、具体 SDK 名或完整类名。"
    ),
    AdPatternConfig.Category.FLUTTER_PATTERNS to HelpInfo(
        title = "Flutter 字符串特征",
        subtitle = "自定义 libapp.so 中去广告的字符串",
        description = "Flutter 应用（AOT）的 libapp.so 内嵌 Dart 快照，以原始 ASCII 字节保存了广告 SDK 包名、广告 URL/域名、广告类名等字符串。此处维护在快照中要抹除的字符串特征：命中后会在 libapp.so 解包/去广告/回编译时做等长 NUL 覆盖，使广告字符串内容失效。",
        addHow = "点击\"管理\"→ 输入要在 libapp.so 中抹除的广告字符串（长度≥2 的可打印 ASCII）→ 点\"添加\"。",
        examples = "com.google.android.gms.ads\ngoogleads.g.doubleclick.net\npangle\nadview\nadmob\ncom.bytedance.sdk",
        modifiedWhat = "写入 $HELP_CONFIG_NAME 的 \"flutter_string_patterns\" 字段。\n" +
            "运行时由 core/LibappSoPatcher.kt 在 Dart 快照内做等长 NUL 覆盖；core/FlutterAdRemover.kt 负责解包/回编译与自动保存。",
        relatedWith = "此特征独立于 DEX 特征。设置后优先用于 Flutter 处理；留空则自动沿用下方\"广告特征分类\"中的 URL/包名/类名等特征。",
        tip = "仅支持可打印 ASCII 字符串（长度≥2）。请填写和广告 SDK 强相关的唯一字符串，避免过于通用导致误伤正常功能。"
    )
)
