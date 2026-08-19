package com.shinegirls.apkadremovereditor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.shinegirls.apkadremovereditor.core.UpdateChecker
import com.shinegirls.apkadremovereditor.utils.UiUtils

/**
 * 关于页面。
 *
 * 展示应用信息、作者信息、开源项目、隐私声明与免责声明。
 */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        // 版本号
        findViewById<TextView>(R.id.tvVersion).text = "版本 ${getVersionName()}"

        // 检查更新
        findViewById<MaterialButton>(R.id.btnCheckUpdate)
            .setOnClickListener { checkForUpdate() }

        // 立即下载：无需检测更新，直接用手机浏览器访问蓝奏云下载最新版
        findViewById<MaterialButton>(R.id.btnDirectDownload)
            .setOnClickListener { openLanZouDownload() }

        // 开源项目信息
        findViewById<TextView>(R.id.tvOpenSource).text = OPEN_SOURCE_TEXT
        findViewById<TextView>(R.id.tvOpenSource).movementMethod = LinkMovementMethod.getInstance()

        // 参考内容与代码出处
        findViewById<TextView>(R.id.tvReference).text = REFERENCE_TEXT

        // 隐私声明
        findViewById<TextView>(R.id.tvPrivacy).text = PRIVACY_TEXT

        // 免责声明
        findViewById<TextView>(R.id.tvDisclaimer).text = DISCLAIMER_TEXT

        // 版权信息
        findViewById<TextView>(R.id.tvCopyright).text = COPYRIGHT_TEXT

        // 功能特性
        bindFeatures()

        // 点击作者信息可复制或发送邮件
        bindAuthorClick()
    }

    /**
     * 动态填充"功能特性"列表。
     */
    private fun bindFeatures() {
        val container = findViewById<LinearLayout>(R.id.llFeatures)
        container.removeAllViews()

        for (feature in FEATURES) {
            val row = layoutInflater.inflate(R.layout.item_about_feature, container, false)
            row.findViewById<ImageView>(R.id.ivFeatureIcon).setImageResource(
                if (feature.first) R.drawable.ic_check else R.drawable.ic_info
            )
            row.findViewById<ImageView>(R.id.ivFeatureIcon)
                .setColorFilter(ContextCompat.getColor(this, R.color.accent))
            row.findViewById<TextView>(R.id.tvFeatureText).text = feature.second
            container.addView(row)
        }
    }

    private fun bindAuthorClick() {
        val tvQq = findViewById<TextView>(R.id.tvAuthorQq)
        val tvEmail = findViewById<TextView>(R.id.tvAuthorEmail)

        // 点击 QQ 复制
        tvQq.setOnClickListener {
            val qq = getString(R.string.author_qq_note)
            val clip = ClipData.newPlainText("作者QQ", qq)
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
            UiUtils.success(this, "QQ已复制到剪贴板")
        }

        // 点击邮箱发邮件
        tvEmail.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:${getString(R.string.author_email)}")
                    putExtra(Intent.EXTRA_SUBJECT, "APK去广告编辑器反馈")
                }
                startActivity(Intent.createChooser(intent, "发送邮件"))
            } catch (_: Exception) {
                UiUtils.warning(this, "未找到邮件客户端")
            }
        }
    }

    private fun getVersionName(): String = UpdateChecker.getCurrentVersionName(this)

    /**
     * 检测更新：后台拉取版本信息，UI 线程展示结果。
     * 若有强制更新，UpdateChecker 会弹出不可取消的对话框。
     */
    private fun checkForUpdate() {
        UpdateChecker.checkForUpdate(this)
    }

    /**
     * 立即下载：无需检测更新，用内置浏览器打开蓝奏云下载最新版。
     * 内置浏览器会拦截 APK 下载地址并自动使用应用内进度下载。
     */
    private fun openLanZouDownload() {
        UpdateChecker.openLanzouInBuiltInBrowser(this)
    }

    companion object {
        /**
         * 功能特性列表。Pair.first 用于选择图标（true=check，false=info）。
         */
        private val FEATURES = listOf(
            true to "一键解包、去广告、打包、签名，全程本地离线处理，无需网络",
            true to "基于 dexlib2 直接修补 DEX 字节码，处理速度远超传统 smali 流程",
            true to "18 类广告特征覆盖 SDK 库 / 权限 / 类 / 方法 / 资源 / URL 等，可自定义增删改查与重置",
            true to "AXML 深度处理：移除广告组件、广告权限声明、隐藏 Res 布局广告 View",
            true to "广告类方法置空、广告链接置空、View 几何置空、强制返回 true/false 解锁会员并屏蔽广告判定",
            true to "自动清理广告 SDK 原生库 (.so)、assets 广告资源与根目录广告文件，精简包体",
            true to "Flutter 应用适配：解析 Dart AOT 快照并抹除 libapp.so 中的广告字符串特征",
            true to "数据复用优化：过签包（如 LSPatch 产物）复用原包数据段，最多减小约 50% 体积",
            true to "识别重命名嵌套 APK：无后缀或任意后缀的原包子包也能准确识别并复用优化",
            true to "DEX 体积优化：移除调试信息（行号/局部变量表），再减小 5%~15% 体积",
            true to "智能压缩策略与 ZIP 对齐，保证打包后 APK 可正常安装启动",
            true to "v1 + v2 双签名，兼容低版本设备，处理结果可直接安装",
            true to "DEX 崩溃防护：备份保护 + 原子写入 + 异常自动恢复，杜绝 DEX 损坏",
            true to "大 DEX 低内存安全扫描，超大 DEX 也能稳定处理不卡死",
            true to "实时彩色处理日志，支持一键复制 / 清空，进度可视化",
            true to "处理完成自动导出到原包目录，并自动清理缓存，不占存储",
            true to "自动生成 Markdown 处理报告，记录各阶段处理明细与体积对比",
            true to "广告特征支持订阅导入与分享，快速同步更多去广告规则",
            true to "内置专业更新弹窗，含进度条、百分比、版本号与文件大小",
            true to "明暗双主题，支持跟随系统、白天、夜间三种模式自由切换",
            true to "签名效验去除：普通模式 + 原包模式，重打包后自动去除签名校验，避免换签后拒绝运行",
            true to "过签 SO 按 ABI 智能注入：仅写入目标 APK 已存在的架构目录，无 lib 目录则全架构写入",
            true to "钩子类名 / 签名信息 / 入口名称 / 注入参数全部可自定义，内置 DEX 类名自动重命名",
            true to "内置签名效验钩子（KillerApplication）基于 dexlib2 DexRewriter 重命名，含 12 个内部类",
            true to "所有弹窗自适应屏幕大小，内容过多时自动滚动，不溢出屏幕"
        )

        private const val OPEN_SOURCE_TEXT = "本应用基于以下开源项目构建并调用，在此向各位作者表示诚挚感谢与敬意：\n\n" +
            "1. ApkDataMultiplexing (L-JINBIN)\n" +
            "   APK 数据复用优化：通过中央目录数据偏移复用原包数据段，删除重复数据段，\n" +
            "   显著减小过签包体积（最多约 50%），并配套 V2V3SchemeSigner 签名方案\n" +
            "   - 主页: https://github.com/L-JINBIN/ApkDataMultiplexing\n" +
            "   - 协议: 未标注许可证，仅供学习参考\n\n" +
            "2. dexlib2 / smali / baksmali (JesusFreke)\n" +
            "   DEX 文件读写与 smali 反汇编 / 汇编工具链，去广告核心引擎\n" +
            "   - 主页: https://github.com/JesusFreke/smali\n" +
            "   - 协议: BSD 3-Clause\n\n" +
            "3. apksig (Android Open Source Project)\n" +
            "   APK v1 / v2 / v3 签名实现，保证处理结果可安装\n" +
            "   - 主页: https://android.googlesource.com/platform/tools/apksig\n" +
            "   - 协议: Apache License 2.0\n\n" +
            "4. BouncyCastle : bcprov / bcpkix\n" +
            "   Java 加解密与证书生成库，用于生成签名证书\n" +
            "   - 主页: https://www.bouncycastle.org/\n" +
            "   - 协议: MIT License\n\n" +
            "5. Guava (Google)\n" +
            "   Java 集合与工具库，提供 dexlib2 所需的不可变集合\n" +
            "   - 主页: https://github.com/google/guava\n" +
            "   - 协议: Apache License 2.0\n\n" +
            "6. AndroidX (Android Open Source Project)\n" +
            "   core-ktx / appcompat / constraintlayout / recyclerview / lifecycle / coordinatorlayout\n" +
            "   Android 官方 Jetpack 支持库\n" +
            "   - 主页: https://developer.android.com/jetpack\n" +
            "   - 协议: Apache License 2.0\n\n" +
            "7. Material Components (Google)\n" +
            "   Material Design 组件库，提供卡片、按钮、对话框等 UI 组件\n" +
            "   - 主页: https://github.com/material-components/material-components-android\n" +
            "   - 协议: Apache License 2.0\n\n" +
            "8. Kotlin 标准库 (JetBrains)\n" +
            "   Kotlin 编程语言与标准库\n" +
            "   - 主页: https://kotlinlang.org/\n" +
            "   - 协议: Apache License 2.0\n\n" +
            "9. DTL-X (Gameye98)\n" +
            "   广告类名 / 方法名 / URL 特征规则参考来源\n" +
            "   - 主页: https://github.com/Gameye98/DTL-X\n" +
            "   - 仅供特征参考与学习，未修改其二进制\n\n" +
            "10. Android Asset Packaging Tool (AAPT2)\n" +
            "    Android 资源编译与打包工具，用于 AXML / 资源处理\n" +
            "    - 主页: https://developer.android.com/tools/aapt2\n" +
            "    - 协议: Apache License 2.0\n\n" +
            "11. MT 管理器 ApkSignatureKillerEx 样本 (Bin.MT)\n" +
            "    签名效验去除钩子类（bin.mt.signature.KillerApplication）与原生库思路来源\n" +
            "    - 主页: https://mt2.cn/\n" +
            "    - 仅供学习参考，钩子类与原生库为样本内置资源，未修改其二进制\n\n" +
            "12. dexlib2 DexRewriter (JesusFreke)\n" +
            "    基于 RewriterModule / TypeRewriter 的 DEX 类重命名方案，用于自定义钩子类名\n" +
            "    - 主页: https://github.com/JesusFreke/smali\n" +
            "    - 协议: BSD 3-Clause\n\n" +
            "以上项目的完整版权与许可文本，请访问对应主页查看。"

        private const val REFERENCE_TEXT = "本应用的实现过程中参考了以下公开的技术文档、开源教程与社区逆向资料，在此一并致谢，并说明出处：\n\n" +
            "1. DEX 直接修补方案 (基于 dexlib2)\n" +
            "   参考 Facebook 曾开源的 android-dexdump 思路，以及 dexlib2 官方示例\n" +
            "   - dexlib2 主页: https://github.com/JesusFreke/smali\n" +
            "   - 借鉴其 API 文档与示例用法，自行实现广告类/方法/字符串的定位与改写\n\n" +
            "2. AXML 二进制格式解析 (Android Asset Packaging)\n" +
            "   参考 AOSP 的 XmlPullParser 与 AXML 二进制格式说明\n" +
            "   - 出处: https://android.googlesource.com/platform/frameworks/base/ (core/res)\n" +
            "   - 依据 AXML chunk 头、资源表 StringPool/ResourceMap 结构与公共逆向资料编写\n\n" +
            "3. Dart AOT 快照解析 (Flutter libapp.so)\n" +
            "   参考 Dart VM 源码 snapshot.h 与社区 Flutter 逆向资料\n" +
            "   - snapshot.h 出处: https://github.com/dart-lang/sdk/blob/main/runtime/vm/snapshot.h\n" +
            "   - 用于定位 libapp.so 内 Dart 快照头并提取/抹除广告字符串\n\n" +
            "4. 广告特征规则整理\n" +
            "   广告 SDK 包名 / 域名 / 类名关键词参考开源项目 DTL-X 的规则整理扩充\n" +
            "   - 主页: https://github.com/Gameye98/DTL-X\n" +
            "   - 参考其 adloader / 域名黑名单 / 方法关键词思路，结合主流广告 SDK 自行整理\n\n" +
            "5. APK v1 / v2 签名与对齐\n" +
            "   参考 apksig 官方实现与 Android 文档中关于 ZIP alignment 的说明\n" +
            "   - apksig: https://android.googlesource.com/platform/tools/apksig\n" +
            "   - ZIP alignment: https://developer.android.com/studio/command-line/zipalign\n\n" +
            "6. 数据复用优化（过签包体积优化）\n" +
            "   参考 L-JINBIN 的 ApkDataMultiplexing 项目与 LSPatch 过签包结构\n" +
            "   - 主页: https://github.com/L-JINBIN/ApkDataMultiplexing\n" +
            "   - 思路来源: LSP 技术团队（过签包 + IO 重定向对抗整包校验）\n" +
            "   - 原理: 通过中央目录数据偏移复用原包数据段，删除重复数据段实现体积优化\n" +
            "   - 已移植其 DataMultiplexing / ZipMaker / ZipFile / V2V3SchemeSigner 实现\n\n" +
            "7. 界面主题与排版\n" +
            "   基于 Material Design 规范与 Material Components 组件库示例编写\n" +
            "   - 规范: https://m3.material.io/\n" +
            "   - 组件: https://github.com/material-components/material-components-android\n\n" +
            "8. 签名效验去除（过签）\n" +
            "   参考 MT 管理器 ApkSignatureKillerEx 的钩子注入思路：\n" +
            "   - 注入 KillerApplication 钩子类并改写 manifest 的 Application 指向\n" +
            "   - 钩子 <clinit> 中通过 PackageManager 读取签名并缓存，覆盖 getPackageInfo / 原生校验\n" +
            "   - 原包模式：嵌入原始 APK 与过签 SO，读取原始签名参与匹配，覆盖更广\n" +
            "   - 出处: https://mt2.cn/ （仅供学习参考）\n\n" +
            "9. DEX 类重命名（自定义钩子类名）\n" +
            "   参考 dexlib2 官方 RewriterModule / TypeRewriter 示例，实现类定义与全部引用的一并改写\n" +
            "   - 出处: https://github.com/JesusFreke/smali\n" +
            "   - 依据类型描述符前缀匹配重写，兼容点号 / 斜杠 / L...; 三种输入写法\n\n" +
            "以上内容仅作技术学习参考，最终实现均为本项目自研；涉及版权归原作者与作者所属机构所有。"

        private const val PRIVACY_TEXT = "本应用遵守最小化收集原则，高度重视并保护您的个人隐私：\n\n" +
            "1. 本地离线处理：所有 APK 的解包、去广告、打包、签名均在您的设备本地完成，应用不会上传任何 APK 文件或内部数据到服务器。\n\n" +
            "2. 联网行为透明：本应用仅在您主动点击\"检查更新\"时联网请求版本信息，其余时间不会在后台联网、收集或上传任何个人信息。\n\n" +
            "3. 权限最小化：本应用不读取、不存储、不访问您的通讯录、相册、定位、短信、通话记录等敏感信息。\n\n" +
            "4. 数据本地存储：应用的配置、处理特征等数据仅保存在您的设备本地，应用卸载后即被清除，不会留存任何云端记录。\n\n" +
            "5. 第三方链接：关于与更新页面可能包含外部链接，点击后由第三方平台处理您的访问行为，建议您查阅相关第三方的隐私政策。\n\n" +
            "6. 若您在使用过程中有任何隐私疑问、建议或顾虑，欢迎通过作者联系方式与我们沟通，我们将尽力解答。"

        private const val DISCLAIMER_TEXT = "请在使用本应用前仔细阅读以下免责声明：\n\n" +
            "1. 合法用途限制：本应用仅供学习、研究与个人合法用途使用。请勿对您不拥有版权、未获授权或受法律保护的应用进行修改与分发。\n\n" +
            "2. 使用风险自担：使用本应用处理 APK 所产生的任何后果（包括但不限于：应用无法安装、闪退、功能异常、账号风险、数据丢失等）均由使用者自行承担。\n\n" +
            "3. 商业使用责任：修改后的 APK 若用于商业用途或对外分发，请确保遵守相关应用的所有权、版权、商标及所在国家或地区的法律法规，由此产生的法律责任由使用者自担。\n\n" +
            "4. 无担保声明：本应用按其现状提供，不附带任何形式的明示或默示担保。作者不对因使用或无法使用本应用而造成的任何直接或间接损失承担责任。\n\n" +
            "5. 第三方内容：本应用引用的开源项目与广告特征规则均来自公开渠道，仅供技术参考，其版权归原作者所有。\n\n" +
            "6. 条款变更：作者保留随时修改本免责声明的权利，更新后的内容将在新版本中生效。\n\n" +
            "7. 使用本应用即视为您已阅读、理解并同意以上全部条款。若不同意，请停止使用本应用。"

        private const val COPYRIGHT_TEXT = "© 2026 小奶瓶 · 保留所有权利\nPowered by dexlib2 / apksig / AndroidX"
    }
}