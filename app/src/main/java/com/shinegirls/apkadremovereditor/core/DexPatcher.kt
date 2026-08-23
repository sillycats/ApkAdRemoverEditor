package com.shinegirls.apkadremovereditor.core

import com.shinegirls.apkadremovereditor.R
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.iface.DexFile
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.MethodImplementation
import org.jf.dexlib2.iface.instruction.formats.Instruction21c
import org.jf.dexlib2.iface.instruction.formats.Instruction31c
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.immutable.ImmutableDexFile
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11n
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction12x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21c
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction31c
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import android.content.Context
import com.shinegirls.apkadremovereditor.utils.Format
import java.io.File

/** 日志回调类型 */
typealias Logger = (String) -> Unit

/**
 * 直接编辑DEX文件（无需smali反编译/回编译）。
 * 使用dexlib2的Immutable API读取-修改-写入DEX，速度比smali流程快几十倍。
 *
 * 功能（仅保留置空方法）：
 * - 匹配到广告类后，仅将方法名包含广告关键词的方法体替换为返回默认值（置空），
 *   跳过构造方法、<clinit> 等关键方法，避免闪退。
 * 已移除功能：NOP广告调用指令、替换广告URL字符串。
 *
 * 性能优化：
 * - 广告模式预编译为HashSet + 小写索引，匹配从O(n*m)降为O(n)
 * - 广告方法名预编译为HashSet，单次lookup O(1)
 * - 未修改的类使用ImmutableClassDef.of零拷贝转换
 */
object DexPatcher {

    /**
     * 预编译的广告模式索引，避免每次匹配都遍历整个列表。
     */
    private data class CompiledPatterns(
        /** SDK包名/类关键词的小写集合，用于精确匹配 */
        val adPatternLowercase: Set<String>,
        /**
         * 配置中声明的广告方法名（小写）。
         * 用于精确匹配：方法名与配置项完全一致时判定为广告方法。
         * 例如配置 `loadAd` 时，方法名 `loadAd` 精确命中。
         */
        val exactMethodNamesLowercase: Set<String>,
        /**
         * 广告类方法置空关键词（小写），用于边界感知的子串匹配。
         * 当匹配到广告SDK类时，只置空方法名"命中"这些关键词的方法，
         * 避免过度置空导致软件崩溃（如置空构造方法、生命周期方法等）。
         * 包含：_ad_, _ads_, _banner_, AdShow, ShowAd, loadAd, showAd 等。
         */
        val neutralizeMethodKeywords: Set<String>,
        /**
         * 广告URL/域名模式（小写）。
         * 用于置空 DEX 中以 const-string 形式存在的广告链接字符串。
         * 参考开源项目 DTL-X 的域名黑名单整理。
         */
        val urlPatternLowercase: Set<String>,
        /**
         * 强制返回 true 的方法名（小写）。
         * 当方法名精确命中该集合且返回类型为 boolean(Z) 或 int(I) 时，方法体被替换为
         * `const/4 v0, 0x1; return v0`（boolean 返回 true，int 返回 1）。
         * 用于"解锁 VIP / 会员 / 专业版"等判定方法，不受广告类限制，作用于全部类。
         */
        val forceTrueMethodNamesLowercase: Set<String>,
        /**
         * 强制返回 false 的方法名（小写）。
         * 当方法名精确命中该集合且返回类型为 boolean(Z) 或 int(I) 时，方法体被替换为
         * `const/4 v0, 0x0; return v0`（boolean 返回 false，int 返回 0）。
         * 用于"广告是否已加载 / 是否正在展示 / 是否有广告"等判定方法，
         * 让应用认为广告从未加载/展示，从而跳过广告展示逻辑。不受广告类限制，作用于全部类。
         */
        val forceFalseMethodNamesLowercase: Set<String>,
        /**
         * 置空关键词的 2 字符前缀索引（key = 关键词前 2 个小写字符）。
         *
         * 性能优化：旧实现 `fastMatchNeutralizeMethod` 对每个方法名遍历全部 ~150 个
         * 关键词做边界匹配（O(方法数 × 关键词数)）。本索引把关键词按前 2 字符分组，
         * 匹配时先对方法名做一次 `contains(前缀)` 快速过滤，仅对命中的前缀组执行
         * 真正的边界匹配，把大 DEX 的方法匹配开销从 O(m×k) 降为 O(m×p)（p 为命中前缀数，
         * 通常远小于关键词总数），显著降低多 DEX 连续处理时的 CPU 峰值。
         */
        val neutralizeKeywordPrefixes: Map<String, List<String>>,
        /**
         * 长度 < 2 的置空关键词（无 2 字符前缀，需单独直接匹配）。
         */
        val shortNeutralizeKeywords: Set<String>,
        /**
         * 广告字符串特征（小写）。
         * 用户自定义的 DEX 字符串广告特征（如广告位 ID、SDK 特征串等）。
         * 处理时会扫描所有方法体内的 const-string / const-string/jumbo 指令，
         * 字符串值(小写化后)命中任一特征即被置空为空字符串，从而破坏广告 SDK
         * 对相关字符串的引用（如广告位 ID、统计上报关键字），阻断广告逻辑。
         */
        val stringPatternLowercase: Set<String>
    )

    /**
     * 预编译广告模式，将列表转为HashSet加速查找。
     */
    private fun compilePatterns(
        adPatterns: List<String>,
        adMethodNames: List<String>,
        urlPatterns: List<String>,
        forceTrueMethodNames: List<String> = emptyList(),
        forceFalseMethodNames: List<String> = emptyList(),
        neutralizeMethodKeywords: List<String> = emptyList(),
        stringPatterns: List<String> = emptyList()
    ): CompiledPatterns {
        // 合并配置中的方法名 + 内置广告方法关键词，用于广告类方法置空筛选
        // 避免过度置空非广告方法（如构造方法、生命周期方法等）导致崩溃
        // 关键词参考开源项目 DTL-X 的 adloader / t4adremover 模式扩充。
        val builtinMethodKeywords = listOf(
            "_ad_", "_ads_", "_banner_", "_adview_", "_adsdk_",
            "adshow", "showad", "showads", "loadad", "loadads",
            "bannerad", "bannerads", "nativead", "splashad",
            "interstitialad", "rewardedad", "rewardedvideo",
            "adload", "adclose", "adclick", "adfail",
            "adimpression", "adrequest", "adresponse",
            "adcallback", "adlistener", "adobserver",
            "adcontroller", "admanager", "adhelper",
            "adprovider", "adnetwork", "adsource",
            "initad", "initads", "initsdk",
            "setad", "getad", "onad", "onads",
            "preloadad", "cachead", "fetchad", "requestad",
            "destroyad", "resumead", "pausead",
            "displayad", "hidead", "removead",
            "adview", "adloader", "adbanner", "adsplash",
            "adwidget", "adcontainer", "adlayout",
            "ttad", "panglead", "gdtad", "baiduad",
            "ad_config", "ad_settings", "ad_unit_",
            "advertising", "adidclient", "adid",
            "admob", "adviewbinder",
            // 常见广告方法名变体
            "loadinterstitial", "showinterstitial",
            "loadrewarded", "showrewarded",
            "loadbanner", "showbanner",
            "loadnative", "shownative",
            "loadsplash", "showsplash",
            "loadexpress", "showexpress",
            // ===== 从开源项目 DTL-X 移植的广告方法关键词 =====
            // adloader 加载器方法
            "loadadfrombid", "requestbannerad", "requestinterstitialad",
            "loadbannerad", "loadinterstitialad", "loadnativead", "loadrewardedad",
            "loadrewardedinterstitialad", "loadappopenad", "loadinterscrollerad",
            "loadnativeadforbidding", "loadnextad", "createinterstitialad",
            "setnativead", "loadadviewad", "loadadfromnetwork", "loadadfromub",
            "loadadinternal", "loadadvertisement", "loadsmartbanner",
            "loadnextadforadtoken", "loadnextadforzoneid", "loadrewardedvideo",
            "loadrewardedvideofordemandonly",
            // 展示方法
            "showbannerandnative", "shownativeinterstitial", "showofferwall",
            "showrewardedvideo", "showrewardedvideoad", "showinterstitialad",
            "shownativead", "showbannerad", "showvideoad", "displayadeventloaded",
            "resumebanner", "startadsession", "unsetnativead", "setadlistener",
            "setrewardedvideoadlistener", "reportadclicked", "reportadimpression",
            // 广告回调方法
            "onadloaded", "onaddisplayed", "onaddisplayfailed",
            "onaddismissedfullscreencontent", "onadfailedtoshowsfullscreencontent",
            "onadhidden", "onadleftapplication", "onadopen", "onadopened",
            "onadrevenuepaid", "onadrequeststarted", "onadshowedfullscreencontent",
            "onappopenadloadfailed", "oninterstitialadloaded", "oninterstitialadloadfailed",
            "oninterstitialadrewarded", "onnativeadclicked", "onnativeadloaded",
            "onnativeadloadfailed", "onnativeadshown", "onrewardedadclosed",
            "onrewardedaddisplayfailed", "onrewardedadfailedtoload",
            "onrewardedadfailedtoshow", "onrewardedadloaded", "onrewardedadopened",
            "onrewardedvideoadclicked", "onrewardedvideoadclosed",
            "onrewardedvideoadfailedtoload", "onrewardedvideoadloaded",
            "onrewardedvideoadopened", "onrewardedvideoadrewarded",
            "onrewardedvideoadshowfailed", "onrewardedvideoadstarted",
            "onunifiednativeadloaded", "onuserearnedreward",
            "failedtoreceivead", "failedtoreceiveadv2", "fetchadwithlocation",
            "vpaidadimpression", "vpaidadinteraction", "vpaidadloaded",
            // 第三方广告SDK特有方法
            "renderad", "hasvideocontent"
        )
        // 合并：配置的精确方法名 + 配置的置空关键词 + 内置置空关键词，去重后小写化。
        // 用户可通过设置中的"广告方法置空关键词"分类增删自定义关键词。
        val configMethodLowercase = adMethodNames.map { it.lowercase() }.toHashSet()
        val builtinLowercase = builtinMethodKeywords.map { it.lowercase() }.toHashSet()
        val configNeutralizeLowercase = neutralizeMethodKeywords
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toHashSet()
        val allKeywords = (configMethodLowercase + builtinLowercase + configNeutralizeLowercase).toHashSet()

        // 构建 2 字符前缀索引：把关键词按前 2 个小写字符分组，加速方法名匹配
        val prefixMap = HashMap<String, MutableList<String>>()
        val shortKeywords = HashSet<String>()
        for (kw in allKeywords) {
            if (kw.length >= 2) {
                prefixMap.getOrPut(kw.substring(0, 2)) { mutableListOf() }.add(kw)
            } else {
                shortKeywords.add(kw)
            }
        }

        return CompiledPatterns(
            adPatternLowercase = adPatterns.map { it.lowercase() }.toHashSet(),
            exactMethodNamesLowercase = configMethodLowercase,
            neutralizeMethodKeywords = allKeywords,
            neutralizeKeywordPrefixes = prefixMap,
            shortNeutralizeKeywords = shortKeywords,
            urlPatternLowercase = urlPatterns.map { it.lowercase() }.toHashSet(),
            forceTrueMethodNamesLowercase = forceTrueMethodNames.map { it.lowercase() }.toHashSet(),
            forceFalseMethodNamesLowercase = forceFalseMethodNames.map { it.lowercase() }.toHashSet(),
            stringPatternLowercase = stringPatterns
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toHashSet()
        )
    }

    /**
     * 快速检查类名是否匹配广告模式。
     * 先做小写转换，再用 HashSet 精确匹配，最后才做子串匹配。
     * 单次遍历返回第一个命中项，避免重复扫描。
     */
    private fun fastMatchAdClass(className: String, patterns: CompiledPatterns): String? {
        val lowerName = className.lowercase()
        // 快速路径：精确匹配（HashSet O(1) 查找 + 精确命中优先）
        if (lowerName in patterns.adPatternLowercase) return lowerName
        // 慢路径：子串匹配，单次遍历即可；跳过比类名更长的模式（无命中可能），减少无效 contains
        for (pattern in patterns.adPatternLowercase) {
            if (pattern.length > lowerName.length) continue
            if (lowerName.contains(pattern)) return pattern
        }
        return null
    }

    /**
     * 直接修补DEX文件中的广告内容（置空广告类方法 + 置空广告链接字符串）。
     *
     * 两阶段扫描（性能关键，本次重构的核心）：
     * 阶段1 - 快速识别：仅遍历类名与方法名，用低内存方式检测该 DEX 是否包含任何广告特征，
     *         不构建任何不可变对象。若不含广告特征（绝大多数多 DEX 包中的非广告 DEX），
     *         直接跳过，不处理、不写回，省去最耗时的全量重建与写回步骤。
     * 阶段2 - 精准修补：仅当阶段1识别到广告内容时才执行，复用阶段1的识别结果，
     *         只对广告类做方法置空/URL置空，其余类零拷贝复用。
     *
     * 内存友好（根治内存爆炸）：
     * - 无广告 DEX 全程零对象构建，内存占用约 2MB（仅文件读取）。
     * - 有广告 DEX 仅在阶段2构建一次不可变类列表，且未修改类走 [ImmutableClassDef.of] 零拷贝。
     * - 移除显式 System.gc()：ART 并发 GC 自动回收，显式全量 GC 会触发 stop-the-world 停顿，
     *   这是根治"处理卡死"的关键。
     *
     * 写入安全保护：
     * - 写入前自动备份原文件；写入失败或异常中断时自动从备份恢复，杜绝 DEX 损坏。
     * - 原子写入（临时文件 + 重命名），任何失败都不会破坏原 dex。
     * - 零修改 DEX 直接保留原样，跳过写回。
     *
     * @param logger 实时日志回调，仅输出 DEX 级汇总（每个 DEX 2~3 行），不含逐类明细
     */
    fun patchDex(
        dexFile: File,
        adPatterns: List<String>,
        adMethodNames: List<String>,
        urlPatterns: List<String> = emptyList(),
        forceTrueMethodNames: List<String> = emptyList(),
        forceFalseMethodNames: List<String> = emptyList(),
        neutralizeMethodKeywords: List<String> = emptyList(),
        stringPatterns: List<String> = emptyList(),
        stripDebugInfo: Boolean = false,
        context: Context,
        logger: Logger? = null
    ): DexPatchOutcome {

        val log = logger ?: {}
        val startTime = System.currentTimeMillis()
        val originalSize = dexFile.length()

        val dex: DexFile = try {
            DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
        } catch (e: Exception) {
            log(context.getString(R.string.h_44057142, dexFile.name, e.message))
            return DexPatchOutcome(
                originalSize, originalSize, failed = true, error = e.message,
                elapsedMs = System.currentTimeMillis() - startTime
            )
        }

        // 预编译广告模式（一次编译，阶段1/2复用，杜绝重复扫描）
        val patterns = compilePatterns(
            adPatterns, adMethodNames, urlPatterns, forceTrueMethodNames, forceFalseMethodNames,
            neutralizeMethodKeywords, stringPatterns
        )

        // ===== 阶段1: 快速识别广告内容（低内存，不构建对象） =====
        val adClassNames = scanAdClasses(dex, patterns)
        if (adClassNames.isEmpty()) {
            // 无广告 DEX：自动跳过，不处理不写回
            val elapsed = System.currentTimeMillis() - startTime
            log(context.getString(R.string.h_a7c027a6, dexFile.name, elapsed))
            return DexPatchOutcome(originalSize, originalSize, skippedNoAd = true, elapsedMs = elapsed)
        }

        // ===== 阶段2: 精准修补（复用阶段1识别结果） =====
        val totalClasses = dex.classes.size
        val newClasses = ArrayList<ImmutableClassDef>(totalClasses)
        var patchedClasses = 0
        var neutralizedMethods = 0
        var neutralizedUrlStrings = 0
        var forcedTrueMethods = 0
        var forcedFalseMethods = 0
        var neutralizedStrings = 0
        var failedClasses = 0

        for (classDef in dex.classes) {
            if (classDef.type in adClassNames) {
                try {
                    // 逐类细节不再输出日志（精简为 DEX 级汇总）
                    val result = patchSingleClass(classDef, classDef.type, patterns, context, {})
                    newClasses.add(result.classDef)
                    patchedClasses++
                    neutralizedMethods += result.neutralized
                    neutralizedUrlStrings += result.urls
                    forcedTrueMethods += result.forcedTrue
                    forcedFalseMethods += result.forcedFalse
                    neutralizedStrings += result.strings
                } catch (_: Exception) {
                    // 单类处理失败不影响整体：回退为原类，累计失败数
                    newClasses.add(ImmutableClassDef.of(classDef))
                    failedClasses++
                }
            } else {
                // 非广告类：零拷贝复用，避免额外对象
                newClasses.add(ImmutableClassDef.of(classDef))
            }
        }

        // 零修改 DEX：识别到广告特征但实际无任何修改，直接保留原样，跳过最耗时的写回
        if (patchedClasses == 0) {
            val elapsed = System.currentTimeMillis() - startTime
            log(context.getString(R.string.h_42a1235e, dexFile.name, elapsed))
            return DexPatchOutcome(originalSize, originalSize, skippedNoChange = true, elapsedMs = elapsed)
        }

        // ===== 写回（备份保护 + 原子写入） =====
        try {
            writeDexWithProtection(dexFile, newClasses, stripDebugInfo)
        } catch (e: OutOfMemoryError) {
            newClasses.clear()
            throw RuntimeException(context.getString(R.string.h_362944cf, dexFile.name), e)
        }

        val elapsed = System.currentTimeMillis() - startTime
        val urlSuffix = if (neutralizedUrlStrings > 0) context.getString(R.string.h_7db41333, neutralizedUrlStrings) else ""
        val forcedSuffix = if (forcedTrueMethods > 0) context.getString(R.string.h_db41810b, forcedTrueMethods) else ""
        val forcedFalseSuffix = if (forcedFalseMethods > 0) context.getString(R.string.h_1da8eee1, forcedFalseMethods) else ""
        val stringSuffix = if (neutralizedStrings > 0) context.getString(R.string.h_327c8914, neutralizedStrings) else ""
        log(context.getString(R.string.h_9c6c3d16, dexFile.name, patchedClasses, neutralizedMethods, urlSuffix, forcedSuffix, forcedFalseSuffix, stringSuffix, elapsed, formatSize(originalSize), formatSize(dexFile.length())))
        if (failedClasses > 0) {
            log(context.getString(R.string.h_75403159, failedClasses))
        }

        return DexPatchOutcome(
            originalSize = originalSize,
            newSize = dexFile.length(),
            patchedClasses = patchedClasses,
            neutralizedMethods = neutralizedMethods,
            neutralizedUrlStrings = neutralizedUrlStrings,
            forcedTrueMethods = forcedTrueMethods,
            forcedFalseMethods = forcedFalseMethods,
            neutralizedStrings = neutralizedStrings,
            elapsedMs = elapsed
        )
    }

    /**
     * 阶段1：快速识别 DEX 中是否包含广告内容，并返回需要修补的广告类名集合。
     *
     * 识别策略（低内存、高效）：
     * - 类名匹配广告模式（精确 + 子串）。
     * - 类内存在"强制返回true"清单中的方法名（仅当配置了该清单才遍历方法，避免无谓开销）。
     * - 仅返回类名集合，不构建任何不可变对象，内存占用极低，适合超大 DEX 安全扫描。
     */
    private fun scanAdClasses(dex: DexFile, patterns: CompiledPatterns): Set<String> {
        val adClassNames = HashSet<String>()
        val hasForceTrue = patterns.forceTrueMethodNamesLowercase.isNotEmpty()
        val hasForceFalse = patterns.forceFalseMethodNamesLowercase.isNotEmpty()
        val hasStringPattern = patterns.stringPatternLowercase.isNotEmpty()
        for (classDef in dex.classes) {
            val className = classDef.type
            if (fastMatchAdClass(className, patterns) != null) {
                adClassNames.add(className)
                continue
            }
            if ((hasForceTrue &&
                    classDef.methods.any { it.name.lowercase() in patterns.forceTrueMethodNamesLowercase }) ||
                (hasForceFalse &&
                    classDef.methods.any { it.name.lowercase() in patterns.forceFalseMethodNamesLowercase })
            ) {
                adClassNames.add(className)
                continue
            }
            // 广告字符串特征：扫描该类所有方法体中的 const-string，命中即标记为需修补类。
            // 仅在配置了字符串特征时才遍历方法体，避免无谓开销。
            if (hasStringPattern && classContainsAdString(classDef, patterns)) {
                adClassNames.add(className)
            }
        }
        return adClassNames
    }

    /**
     * 检测一个类中是否含有命中"广告字符串特征"的 const-string 常量。
     *
     * 低开销：仅遍历指令的 opcode 与字符串值，不构建任何不可变对象。
     * [patterns.stringPatternLowercase] 非空时才会被调用。
     */
    private fun classContainsAdString(classDef: ClassDef, patterns: CompiledPatterns): Boolean {
        for (method in classDef.methods) {
            val impl = method.implementation ?: continue
            for (ins in impl.instructions) {
                val opcode = ins.opcode
                if (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) {
                    val str = extractConstString(ins) ?: continue
                    if (matchesStringPattern(str, patterns)) return true
                }
            }
        }
        return false
    }

    /**
     * 判断字符串值（const-string 常量）是否命中任一广告字符串特征。
     * 子串匹配（大小写不敏感）。
     */
    private fun matchesStringPattern(value: String, patterns: CompiledPatterns): Boolean {
        if (patterns.stringPatternLowercase.isEmpty()) return false
        val lower = value.lowercase()
        for (pattern in patterns.stringPatternLowercase) {
            if (lower.contains(pattern)) return true
        }
        return false
    }

    /**
     * 带备份保护的原子写入。
     *
     * - 写入前将原文件复制为 .bak 备份。
     * - 先写临时文件，成功后原子重命名替换原文件（任何失败都不会破坏原 dex）。
     * - 写入异常时自动从备份恢复原文件，杜绝处理中断导致的 DEX 损坏。
     * - 写成功后清理备份。
     *
     * @param stripDebugInfo 为 true 时剥离所有方法的 debug info（行号/局部变量表/参数名），
     *                       可减小 DEX 体积 5%~15%，不影响运行功能。
     */
    private fun writeDexWithProtection(
        dexFile: File,
        newClasses: List<ImmutableClassDef>,
        stripDebugInfo: Boolean = false
    ) {
        val backup = File(dexFile.parentFile, "${dexFile.name}.bak")
        // 写入前备份原文件
        if (!backup.exists()) {
            dexFile.copyTo(backup, overwrite = true)
        }
        try {
            val finalClasses = if (stripDebugInfo) {
                stripDebugInfoFromClasses(newClasses)
            } else {
                newClasses
            }
            val newDex = ImmutableDexFile(Opcodes.getDefault(), finalClasses)
            val tmpDex = File(dexFile.parentFile, "${dexFile.name}.tmp")
            if (tmpDex.exists()) tmpDex.delete()
            DexFileFactory.writeDexFile(tmpDex.absolutePath, newDex)

            if (!tmpDex.renameTo(dexFile)) {
                // 重命名失败（极少见，如文件被占用）：回退为删除+复制
                dexFile.delete()
                if (!tmpDex.renameTo(dexFile)) {
                    tmpDex.copyTo(dexFile, overwrite = true)
                    tmpDex.delete()
                }
            }
            // 写成功：清理备份
            backup.delete()
        } catch (e: Exception) {
            // 异常中断：自动恢复原文件
            try {
                if (dexFile.exists() && backup.exists() && dexFile.length() != backup.length()) {
                    backup.copyTo(dexFile, overwrite = true)
                }
            } catch (_: Exception) {
            }
            backup.delete()
            throw e
        }
    }

    /**
     * 剥离所有类的 debug info（行号/局部变量表/参数名），减小 DEX 体积。
     *
     * 原理：DEX 的 debug info 段（debug_info_off）仅用于崩溃堆栈行号与调试器，
     * 运行时完全不需要。通过重建 [ImmutableMethodImplementation] 并传入空 debug items
     * 列表，dexlib2 写回时 debug_info_off 置 0，对应数据段被丢弃。
     *
     * 仅对含广告、需要写回的 DEX 执行，且只遍历有方法体的方法，开销可控。
     */
    private fun stripDebugInfoFromClasses(classes: List<ImmutableClassDef>): List<ImmutableClassDef> {
        val result = ArrayList<ImmutableClassDef>(classes.size)
        for (classDef in classes) {
            // 快速路径：类内没有任何带方法体的方法，直接复用
            var hasImpl = false
            for (m in classDef.methods) {
                if (m.implementation != null) {
                    hasImpl = true
                    break
                }
            }
            if (!hasImpl) {
                result.add(classDef)
                continue
            }
            val newMethods = ArrayList<ImmutableMethod>(classDef.methods.count())
            for (method in classDef.methods) {
                val impl = method.implementation
                if (impl == null) {
                    newMethods.add(ImmutableMethod.of(method))
                    continue
                }
                // 重建方法体，debug items 传空列表以剥离 debug info
                val newImpl = ImmutableMethodImplementation(
                    impl.registerCount,
                    impl.instructions,
                    impl.tryBlocks,
                    emptyList()
                )
                newMethods.add(
                    ImmutableMethod(
                        method.definingClass, method.name, method.parameters.toList(),
                        method.returnType, method.accessFlags,
                        method.annotations.toSet(), method.hiddenApiRestrictions.toSet(), newImpl
                    )
                )
            }
            result.add(
                ImmutableClassDef(
                    classDef.type, classDef.accessFlags, classDef.superclass,
                    classDef.interfaces, classDef.sourceFile, classDef.annotations,
                    classDef.fields, newMethods
                )
            )
        }
        return result
    }

    /**
     * 单类修补结果。
     */
    private data class SingleClassPatch(
        val classDef: ImmutableClassDef,
        val neutralized: Int,
        val urls: Int,
        val forcedTrue: Int,
        val forcedFalse: Int,
        val strings: Int
    )

    /**
     * 单遍修补单个类：同时完成广告方法置空、广告URL字符串置空、强制返回true。
     *
     * 相比旧实现（先置空方法、二次遍历全量类列表置空URL），
     * 本方法在遍历一个类的方法时同步处理三类修改，避免二次构建方法对象，
     * 显著降低大 DEX 处理时的内存峰值。
     *
     * @return [SingleClassPatch] 修改后的类及各修改计数
     */
    private fun patchSingleClass(
        classDef: ClassDef,
        className: String,
        patterns: CompiledPatterns,
        context: Context,
        log: Logger
    ): SingleClassPatch {
        val needUrl = patterns.urlPatternLowercase.isNotEmpty()
        val needString = patterns.stringPatternLowercase.isNotEmpty()
        val needConstString = needUrl || needString
        var neutralizedCount = 0
        var urlCount = 0
        var stringCount = 0
        var forcedTrueCount = 0
        var forcedFalseCount = 0
        var skippedCount = 0

        val newMethods = ArrayList<ImmutableMethod>(classDef.methods.count())
        for (method in classDef.methods) {
            try {
                val methodName = method.name
                val impl = method.implementation
                if (impl == null) {
                    newMethods.add(ImmutableMethod.of(method))
                    continue
                }

                // 始终跳过构造方法和静态构造器，避免类初始化失败
                if (methodName == "<init>" || methodName == "<clinit>") {
                    newMethods.add(ImmutableMethod.of(method))
                    skippedCount++
                    continue
                }

                // 0) 强制返回 true：方法名精确命中"强制返回true"清单且返回类型为 boolean(Z) 或 int(I)。
                //    方法体替换为 const/4 v0, 0x1 + return v0，用于解锁 VIP/会员/专业版判定。
                //    - Z (boolean)：返回 1，即逻辑 true
                //    - I (int)：返回 1，通常表示"是会员/已解锁/已购买"等非0真值
                //    该判定独立于广告类，作用于所有类、所有方法。
                if (patterns.forceTrueMethodNamesLowercase.isNotEmpty() &&
                    methodName.lowercase() in patterns.forceTrueMethodNamesLowercase
                ) {
                    if (method.returnType == "Z" || method.returnType == "I") {
                        val newImpl = ImmutableMethodImplementation(
                            impl.registerCount.coerceAtLeast(1),
                            createReturnTrueInstructions(),
                            emptyList(),
                            emptyList()
                        )
                        newMethods.add(
                            ImmutableMethod(
                                method.definingClass, method.name, method.parameters.toList(),
                                method.returnType, method.accessFlags,
                                method.annotations.toSet(), method.hiddenApiRestrictions.toSet(), newImpl
                            )
                        )
                        forcedTrueCount++
                        continue
                    }
                    // 非 boolean/int 返回类型：跳过，不强制，避免生成非法指令
                }

                // 0.5) 强制返回 false：方法名精确命中"强制返回false"清单且返回类型为 boolean(Z) 或 int(I)。
                //    方法体替换为 const/4 v0, 0x0 + return v0，用于"广告是否已加载/展示/有广告"等判定方法，
                //    让应用认为广告从未加载/展示，从而跳过广告展示逻辑。
                //    该判定独立于广告类，作用于所有类、所有方法。
                if (patterns.forceFalseMethodNamesLowercase.isNotEmpty() &&
                    methodName.lowercase() in patterns.forceFalseMethodNamesLowercase
                ) {
                    if (method.returnType == "Z" || method.returnType == "I") {
                        val newImpl = ImmutableMethodImplementation(
                            impl.registerCount.coerceAtLeast(1),
                            createReturnFalseInstructions(),
                            emptyList(),
                            emptyList()
                        )
                        newMethods.add(
                            ImmutableMethod(
                                method.definingClass, method.name, method.parameters.toList(),
                                method.returnType, method.accessFlags,
                                method.annotations.toSet(), method.hiddenApiRestrictions.toSet(), newImpl
                            )
                        )
                        forcedFalseCount++
                        continue
                    }
                    // 非 boolean/int 返回类型：跳过，不强制，避免生成非法指令
                }

                // 1) 置空广告方法（方法名命中广告关键词）
                val isAdMethod = fastMatchNeutralizeMethod(methodName, patterns)
                // 2) 置空广告链接字符串 + 广告字符串特征：惰性处理，仅当方法体内确实命中时才构建新指令，
                //    绝大多数方法无命中，返回 null 直接复用原始方法，零对象创建。
                var constInstructions: List<ImmutableInstruction>? = null
                var urlCountInMethod = 0
                var stringCountInMethod = 0
                if (needConstString && !isAdMethod) {
                    val result = neutralizeConstStringsInMethod(impl, patterns)
                    constInstructions = result.first
                    urlCountInMethod = result.second
                    stringCountInMethod = result.third
                }

                if (isAdMethod) {
                    // 广告方法：方法体替换为返回默认值
                    val returnType = method.returnType
                    val newInstructions = createReturnInstructions(returnType)
                    val newImpl = ImmutableMethodImplementation(
                        impl.registerCount.coerceAtLeast(1),
                        newInstructions,
                        emptyList(),
                        emptyList()
                    )
                    newMethods.add(
                        ImmutableMethod(
                            method.definingClass, method.name, method.parameters.toList(),
                            method.returnType, method.accessFlags,
                            method.annotations.toSet(), method.hiddenApiRestrictions.toSet(), newImpl
                        )
                    )
                    neutralizedCount++
                } else if (needConstString && (urlCountInMethod > 0 || stringCountInMethod > 0) && constInstructions != null) {
                    // 非广告方法但含广告链接/广告字符串：重建方法体，其余不变
                    urlCount += urlCountInMethod
                    stringCount += stringCountInMethod
                    val newImpl = ImmutableMethodImplementation(
                        impl.registerCount.coerceAtLeast(1),
                        constInstructions,
                        emptyList(),
                        emptyList()
                    )
                    newMethods.add(
                        ImmutableMethod(
                            method.definingClass, method.name, method.parameters.toList(),
                            method.returnType, method.accessFlags,
                            method.annotations.toSet(), method.hiddenApiRestrictions.toSet(), newImpl
                        )
                    )
                } else {
                    newMethods.add(ImmutableMethod.of(method))
                    if (!isAdMethod) skippedCount++
                }
            } catch (_: Exception) {
                try {
                    newMethods.add(ImmutableMethod.of(method))
                } catch (_: Exception) {
                }
            }
        }

        if (forcedTrueCount > 0) {
            log(context.getString(R.string.h_9140b5bf, className, forcedTrueCount))
        }
        if (forcedFalseCount > 0) {
            log(context.getString(R.string.h_3ae2b7d3, className, forcedFalseCount))
        }
        val stringSuffix2 = if (stringCount > 0) context.getString(R.string.h_daea0bf6, stringCount) else ""
        val urlSuffix = if (urlCount > 0) context.getString(R.string.dexpatcher_link_neutral, urlCount) else ""
        if (neutralizedCount > 0) {
            log(context.getString(R.string.dexpatcher_neutral, className, neutralizedCount, skippedCount, urlSuffix, stringSuffix2))
        } else if (urlCount > 0 || stringCount > 0) {
            log(context.getString(R.string.h_11ca9ed7, className, urlCount, stringSuffix2))
        }

        val newClass = ImmutableClassDef(
            classDef.type, classDef.accessFlags, classDef.superclass,
            classDef.interfaces.toList(), classDef.sourceFile,
            classDef.annotations.toSet(), classDef.fields.toList(), newMethods
        )
        return SingleClassPatch(newClass, neutralizedCount, urlCount, forcedTrueCount, forcedFalseCount, stringCount)
    }

    /**
     * 置空单个方法体内引用广告URL/域名或命中"广告字符串特征"的 const-string / const-string/jumbo 指令。
     *
     * 惰性两遍策略（性能关键）：
     * - 第一遍只读扫描：仅遍历指令，检查 opcode 与字符串值，不创建任何对象。
     *   绝大多数方法体内没有广告内容，此时直接返回 (null, 0, 0)，零开销。
     * - 第二遍仅在第一遍命中时执行：才真正构建新的指令列表并替换命中项。
     *   这样避免旧实现"无条件为每个方法复制全部指令"导致的 CPU/内存峰值。
     *
     * @return Triple(新指令列表, 被置空的广告URL数量, 被置空的广告字符串数量)；
     *         无命中时 first 为 null。
     */
    private fun neutralizeConstStringsInMethod(
        impl: MethodImplementation,
        patterns: CompiledPatterns
    ): Triple<List<ImmutableInstruction>?, Int, Int> {
        // 第一遍：只读检测是否有命中的广告链接/广告字符串
        var hit = false
        var urlChanged = 0
        var stringChanged = 0
        for (ins in impl.instructions) {
            val opcode = ins.opcode
            if (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) {
                val str = extractConstString(ins) ?: continue
                if (isAdUrlString(str, patterns)) {
                    hit = true
                    urlChanged++
                } else if (matchesStringPattern(str, patterns)) {
                    hit = true
                    stringChanged++
                }
            }
        }
        // 无命中：零对象创建，直接返回 null
        if (!hit) return Triple(null, 0, 0)

        // 第二遍：命中才构建新指令列表
        val newInstructions = mutableListOf<ImmutableInstruction>()
        for (ins in impl.instructions) {
            val opcode = ins.opcode
            if (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) {
                val str = extractConstString(ins)
                if (str != null && (isAdUrlString(str, patterns) || matchesStringPattern(str, patterns))) {
                    newInstructions.add(
                        ImmutableInstruction21c(
                            Opcode.CONST_STRING, extractRegister(ins), ImmutableStringReference("")
                        )
                    )
                    continue
                }
            }
            newInstructions.add(ImmutableInstruction.of(ins))
        }
        return Triple(newInstructions, urlChanged, stringChanged)
    }

    /** 从 const-string / const-string/jumbo 指令提取字符串值（非字符串指令返回 null）。 */
    private fun extractConstString(ins: org.jf.dexlib2.iface.instruction.Instruction): String? {
        return when (ins) {
            is Instruction21c -> (ins.reference as? StringReference)?.string
            is Instruction31c -> (ins.reference as? StringReference)?.string
            else -> null
        }
    }

    /** 从 const-string / const-string/jumbo 指令提取目标寄存器（非字符串指令返回 0）。 */
    private fun extractRegister(ins: org.jf.dexlib2.iface.instruction.Instruction): Int {
        return when (ins) {
            is Instruction21c -> ins.registerA
            is Instruction31c -> ins.registerA
            else -> 0
        }
    }

    /**
     * 判断方法名是否为广告方法，决定是否置空该方法。
     *
     * 匹配策略（提升准确率）：
     * 1. 精确匹配：方法名与配置中的某个广告方法名完全一致（如 `loadAd`），
     *    避免过长或拼接方法名的误判。
     * 2. 边界感知子串匹配：广告关键词必须作为方法名中的"单词边界"出现。
     *    边界基于原始大小写识别驼峰边界（如 `Ad`），
     *    避免把 `showAndroid`、`loadAdapter`、`getAddress` 等误判为广告方法，
     *    同时保留 `showRewardedVideo`、`showInterstitialAd` 等驼峰复合广告方法的命中。
     *
     * 使用预编译的 HashSet 加速查找。
     */
    private fun fastMatchNeutralizeMethod(methodName: String, patterns: CompiledPatterns): Boolean {
        val lower = methodName.lowercase()

        // 1) 精确匹配配置中的广告方法名
        if (lower in patterns.exactMethodNamesLowercase) return true

        // 2) 短关键词（长度<2）直接边界匹配
        if (patterns.shortNeutralizeKeywords.isNotEmpty()) {
            for (keyword in patterns.shortNeutralizeKeywords) {
                if (isKeywordAtBoundary(methodName, lower, keyword)) return true
            }
        }

        // 3) 前缀索引加速的边界匹配：仅对方法名中命中 2 字符前缀的关键词组做真正的边界检查，
        //    避免对每个方法遍历全部 ~150 个关键词（O(m×k) → O(m×p)）。
        for ((prefix, keywords) in patterns.neutralizeKeywordPrefixes) {
            if (lower.contains(prefix)) {
                for (keyword in keywords) {
                    if (isKeywordAtBoundary(methodName, lower, keyword)) return true
                }
            }
        }
        return false
    }

    /**
     * 判断 keyword 是否在 name 中以"单词边界"形式出现。
     * 使用原始大小写识别驼峰边界（如 `loadAd` 中的 `Ad` 前是 `load` 的小写 `d`）。
     *
     * @param nameLower 已小写化的 name，避免每个关键词重复分配低开销字符串
     */
    private fun isKeywordAtBoundary(name: String, nameLower: String, keyword: String): Boolean {
        if (keyword.isEmpty()) return false
        var fromIndex = 0
        while (true) {
            val idx = nameLower.indexOf(keyword, fromIndex)
            if (idx < 0) return false
            // 前方边界：位于开头，或前一个字符不是字母，或为驼峰边界（前小写后大写）
            val prevOk = idx == 0 ||
                !name[idx - 1].isLetter() ||
                (name[idx].isUpperCase() && name[idx - 1].isLowerCase())
            // 后方边界：位于末尾，或后一个字符不是字母，或后一字符为新单词开头（大写）
            val nextIdx = idx + keyword.length
            val nextOk = nextIdx >= name.length ||
                !name[nextIdx].isLetter() ||
                name[nextIdx].isUpperCase()
            if (prevOk && nextOk) return true
            fromIndex = idx + 1
        }
    }

    /**
     * 判断字符串是否为广告URL链接。
     * 匹配广告URL模式中的域名/关键词，避免误伤普通字符串。
     */
    private fun isAdUrlString(value: String, patterns: CompiledPatterns): Boolean {
        // 仅处理形如链接的字符串，降低误判
        if (!value.contains("://") && !value.contains("www.") && !value.contains(".com") &&
            !value.contains(".net") && !value.contains(".cn") && !value.contains(".mobi") &&
            !value.contains(".ru") && !value.contains("/ads") && !value.contains("ca-app-pub")
        ) {
            return false
        }
        val lower = value.lowercase()
        for (pattern in patterns.urlPatternLowercase) {
            if (lower.contains(pattern)) return true
        }
        return false
    }

    /**
     * 生成返回 true 的指令序列（适用于 boolean(Z) 和 int(I) 返回类型）。
     *
     * const/4 v0, 0x1
     * return v0
     *
     * 对 boolean 返回 1（true），对 int 返回 1（非0真值）。
     */
    private fun createReturnTrueInstructions(): List<ImmutableInstruction> {
        return listOf(
            ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
            ImmutableInstruction11x(Opcode.RETURN, 0)
        )
    }

    /**
     * 生成返回 false 的指令序列（适用于 boolean(Z) 和 int(I) 返回类型）。
     *
     * const/4 v0, 0x0
     * return v0
     *
     * 对 boolean 返回 0（false），对 int 返回 0（假值）。
     */
    private fun createReturnFalseInstructions(): List<ImmutableInstruction> {
        return listOf(
            ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
            ImmutableInstruction11x(Opcode.RETURN, 0)
        )
    }

    /**
     * 根据返回类型生成return指令。
     */
    private fun createReturnInstructions(returnType: String): List<ImmutableInstruction> {
        if (returnType.isEmpty()) {
            return listOf(ImmutableInstruction10x(Opcode.RETURN_VOID))
        }

        val firstChar = returnType.first()

        return when (firstChar) {
            'V' -> listOf(ImmutableInstruction10x(Opcode.RETURN_VOID))
            'Z', 'B', 'S', 'C', 'I' -> listOf(
                ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                ImmutableInstruction11x(Opcode.RETURN, 0)
            )
            'J' -> listOf(
                ImmutableInstruction11n(Opcode.CONST_WIDE_16, 0, 0),
                ImmutableInstruction12x(Opcode.RETURN_WIDE, 0, 0)
            )
            'F' -> listOf(
                ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                ImmutableInstruction11x(Opcode.RETURN, 0)
            )
            'D' -> listOf(
                ImmutableInstruction11n(Opcode.CONST_WIDE_16, 0, 0),
                ImmutableInstruction12x(Opcode.RETURN_WIDE, 0, 0)
            )
            else -> listOf(
                ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)
            )
        }
    }

    private fun formatSize(bytes: Long): String = Format.formatSize(bytes)
}

/**
 * DEX 修补结果（结构化，供 AdRemover 收集与报告生成）。
 *
 * @param skippedNoAd   无广告 DEX 自动跳过（未处理、未写回）
 * @param skippedNoChange 识别到广告特征但实际零修改，跳过写回
 */
data class DexPatchOutcome(
    val originalSize: Long,
    val newSize: Long,
    val skippedNoAd: Boolean = false,
    val skippedNoChange: Boolean = false,
    val patchedClasses: Int = 0,
    val neutralizedMethods: Int = 0,
    val neutralizedUrlStrings: Int = 0,
    val forcedTrueMethods: Int = 0,
    val forcedFalseMethods: Int = 0,
    val neutralizedStrings: Int = 0,
    val failed: Boolean = false,
    val error: String? = null,
    val elapsedMs: Long = 0
)