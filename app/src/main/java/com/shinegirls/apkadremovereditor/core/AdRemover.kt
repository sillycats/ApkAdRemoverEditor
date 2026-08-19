package com.shinegirls.apkadremovereditor.core

import android.content.Context
import com.shinegirls.apkadremovereditor.utils.Format
import com.shinegirls.apkadremovereditor.utils.PathPreferences
import java.io.File

/**
 * 一键去广告引擎（仅 DEX 模式）。
 *
 * 通过直接修补 DEX 字节码移除广告调用，并清理 lib 目录下的广告 SDK 原生库
 * 与 assets 目录下的广告 SDK 文件。不再扫描或修改布局文件与
 * AndroidManifest（AXML）之外的资源。
 *
 * 崩溃防护：
 * - 每个 DEX 文件独立 try-catch
 * - OOM 时自动 GC 并跳过当前文件
 *
 * 广告特征从外部 JSON 配置文件加载（AdPatternConfig），不再硬编码在 DEX 中。
 * DEX编辑模式：直接使用 DexPatcher 操作 DEX 字节码，无需 smali 反编译/回编译。
 */
object AdRemover {

    /**
     * 大 DEX 预检阈值（单位：字节）。超过该阈值时提示用户，避免误以为卡死。
     */
    private const val LARGE_DEX_THRESHOLD: Long = 10L * 1024 * 1024

    /**
     * 主入口：通过 DEX 修补去广告，返回结构化处理报告。
     *
     * 性能优化（本次重构）：
     * - 无广告 DEX 自动跳过：阶段1快速识别后，不含广告特征的 DEX 直接跳过，不处理不写回。
     * - 零修改 DEX 跳过写回：识别到广告特征但实际无修改时，保留原样，省略最耗时写回。
     * - 识别结果复用：DexPatcher 内部阶段1识别结果直接复用于阶段2修补判定，杜绝重复扫描。
     * - 移除 finally 中的 System.gc()：显式全量 GC 触发 stop-the-world 停顿导致界面冻结，
     *   交由 ART 并发 GC 自动回收，根治多 DEX 连续处理时的卡死。
     * - DEX 按自然顺序处理（classes → classes2 → ... → classes10），修复报告顺序混乱。
     *
     * @param extractDir 解包后的APK目录
     * @param context    用于加载 assets 内置默认配置
     * @param logger     实时日志回调，用于UI实时显示处理进度（已精简为 DEX 级汇总）
     * @return 结构化处理报告 [ProcessingReport]
     */
    fun removeAds(extractDir: File, context: Context, logger: Logger? = null): ProcessingReport {
        val log = logger ?: {}
        val report = ProcessingReport()
        val totalStartTime = System.currentTimeMillis()
        report.startedAt = formatTimestamp(totalStartTime)

        // ========== 从配置文件加载广告特征 ==========
        val config = AdPatternConfig.loadConfig(context)
        val configFile = AdPatternConfig.getConfigFile(context)

        // 依据用户在设置中的分类开关，将已关闭的分类特征清空，
        // 使后续各步骤的 "列表为空则跳过" 守卫自动生效，实现按分类启停去广告。
        val disabledCategories = AdPatternConfig.Category.values()
            .filter { !PathPreferences.isCategoryEnabled(context, it.name) }
        if (disabledCategories.isNotEmpty()) {
            for (category in disabledCategories) {
                AdPatternConfig.getCategoryList(config, category).clear()
            }
            log("  · 已关闭 ${disabledCategories.size} 个分类: " +
                disabledCategories.joinToString("、") { it.displayName })
        }

        report.config = config
        report.configFile = configFile.absolutePath

        log("  · 特征配置: SDK=${config.sdkPackages.size} 类=${config.classKeywords.size} 方法=${config.methodPatterns.size} 总计=${config.totalCount()} 条")

        if (config.totalCount() == 0) {
            log("  ⚠ 广告特征配置为空，跳过去广告处理")
            report.totalTimeMs = System.currentTimeMillis() - totalStartTime
            return report
        }

        val allAdPatterns = config.allAdPatterns()
        val adMethodPatterns = config.methodPatterns
        val adUrlPatterns = config.urlPatterns
        // 强制返回 true 的方法名（解锁 VIP/会员/专业版判定方法）
        val forceTrueMethods = config.forceTrueMethodNames
        // 强制返回 false 的方法名（广告是否已加载/展示/有广告等判定方法）
        val forceFalseMethods = config.forceFalseMethodNames
        // DEX 字符串广告特征：扫描 const-string 并置空命中字符串
        val adStringPatterns = config.stringPatterns

        // ---------- 阶段1: 直接修补DEX文件 ----------
        val phase1Start = System.currentTimeMillis()
        log("▶ 阶段 1 DEX 修补")

        val dexOptimizeEnabled = PathPreferences.isDexOptimizeEnabled(context)
        if (dexOptimizeEnabled) {
            log("  ℹ DEX 体积优化已开启：移除调试信息，进一步减小 APK 体积")
        }

        val dexFiles = extractDir.listFiles { f ->
            f.isFile && f.name.endsWith(".dex")
        } ?: emptyArray()

        if (dexFiles.isNotEmpty()) {
            log("  · 找到 ${dexFiles.size} 个 DEX 文件")
            // 自然顺序排序：classes → classes2 → ... → classes10（修复 classes10 排在 classes2 前的问题）
            val sortedDex = dexFiles.sortedWith(dexNaturalComparator)

            // 串行处理：保证日志顺序为"正在处理 → 处理完成"交替输出，
            // 即 classes.dex 正在处理 → classes.dex 完成 → classes2.dex 正在处理 → classes2.dex 完成 ...
            for (dexFile in sortedDex) {
                val stat = processSingleDex(
                    dexFile,
                    allAdPatterns,
                    adMethodPatterns,
                    adUrlPatterns,
                    forceTrueMethods,
                    forceFalseMethods,
                    config.methodNeutralizeKeywords,
                    adStringPatterns,
                    context,
                    log
                )
                report.dexStats.add(stat)
                // 断点续传基础设施：记录每个 DEX 的处理检查点
                report.checkpoints.add(CheckpointRecord("DEX", dexFile.name, stat.elapsedMs))
            }
        } else {
            log("  · 未找到 DEX 文件")
        }

        logPhaseTime("DEX修补", phase1Start, log)

        // ---------- 阶段2: AXML 广告清单移除 ----------
        val axmlStart = System.currentTimeMillis()
        log("▶ 阶段 2 AXML 清单移除")

        val manifestFile = File(extractDir, "AndroidManifest.xml")
        val axmlResult = try {
            // 合并所有清单组件类名特征：SDK包名 + 类名关键词 + Activity/Service/Receiver 类名
            val axmlKeywords = buildList {
                addAll(config.sdkPackages)
                addAll(config.classKeywords)
                addAll(config.adActivities)
                addAll(config.adServices)
                addAll(config.adReceivers)
            }
            AxmlAdRemover.removeAdComponents(
                manifestFile,
                config.sdkPackages,
                axmlKeywords
            )
        } catch (e: Exception) {
            log("  ✗ AXML 解析失败: ${e.message}")
            AxmlAdRemover.AxmlResult(false, 0, emptyList())
        }
        report.axmlRemovedComponents = axmlResult.removedCount

        if (axmlResult.modified) {
            log("  ✓ 已移除 ${axmlResult.removedCount} 个广告组件")
        } else {
            log("  · 未发现需移除的广告组件")
        }
        report.checkpoints.add(CheckpointRecord("AXML组件", "移除 ${axmlResult.removedCount} 个", System.currentTimeMillis() - axmlStart))
        logPhaseTime("AXML清单移除", axmlStart, log)

        // ---------- AXML 广告权限移除 ----------
        val permStart = System.currentTimeMillis()
        log("▶ 阶段 2.5 AXML 权限移除")

        val permResult = try {
            AxmlAdRemover.removeAdPermissions(manifestFile, config.adPermissions)
        } catch (e: Exception) {
            log("  ✗ AXML 权限解析失败: ${e.message}")
            AxmlAdRemover.AxmlResult(false, 0, emptyList())
        }
        report.axmlRemovedPermissions = permResult.removedCount

        if (permResult.modified) {
            log("  ✓ 已移除 ${permResult.removedCount} 个广告权限声明")
        } else {
            log("  · 未发现需移除的广告权限")
        }
        report.checkpoints.add(CheckpointRecord("AXML权限", "移除 ${permResult.removedCount} 个", System.currentTimeMillis() - permStart))
        logPhaseTime("AXML权限移除", permStart, log)

        // ---------- 阶段3~6: 资源/库/文件清理（返回计数） ----------
        report.cleanedSdkLibs = cleanAdSdkLibs(extractDir, config.sdkPackages, config.libFileKeywords, log)
        report.cleanedSdkAssets = cleanAdSdkAssets(extractDir, config.adAssetPaths, config.assetKeywords, log)
        report.cleanedRootFiles = cleanRootFiles(extractDir, config.rootFileKeywords, log)

        // 隐藏 Res 布局中的广告 View。
        // 该步骤与 RES_LAYOUT_KEYWORDS 分类开关绑定：关闭该分类后整个布局隐藏步骤不再执行，
        // 确保"归类于 Res 布局"的隐藏动作完全停用，不被其他已启用分类的合并关键词触发。
        val resLayoutEnabled = PathPreferences.isCategoryEnabled(
            context, AdPatternConfig.Category.RES_LAYOUT_KEYWORDS.name)
        if (resLayoutEnabled) {
            val layoutKeywords = buildList {
                addAll(config.resLayoutKeywords)
                addAll(config.sdkPackages)
                addAll(config.classKeywords)
                addAll(config.adViewNames)
                addAll(config.adActivities)
                addAll(config.adServices)
                addAll(config.adReceivers)
            }
            report.hiddenLayoutViews = hideAdLayoutViews(extractDir, layoutKeywords, log)
        } else {
            log("  · 已关闭 Res 布局广告 View 分类，跳过布局隐藏")
            report.hiddenLayoutViews = 0
        }

        // ---------- 汇总报告 ----------
        report.totalTimeMs = System.currentTimeMillis() - totalStartTime

        val parts = buildList {
            if (report.totalPatchedClasses > 0) add("类置空 ${report.totalPatchedClasses}")
            if (report.totalNeutralizedMethods > 0) add("方法置空 ${report.totalNeutralizedMethods}")
            if (report.totalNeutralizedUrls > 0) add("链接置空 ${report.totalNeutralizedUrls}")
            if (report.totalNeutralizedStrings > 0) add("字符串置空 ${report.totalNeutralizedStrings}")
            if (report.totalForcedTrueMethods > 0) add("强制true ${report.totalForcedTrueMethods}")
            if (report.totalForcedFalseMethods > 0) add("强制false ${report.totalForcedFalseMethods}")
            if (report.axmlRemovedComponents > 0) add("AXML组件 ${report.axmlRemovedComponents}")
            if (report.axmlRemovedPermissions > 0) add("AXML权限 ${report.axmlRemovedPermissions}")
            if (report.cleanedSdkLibs > 0) add("SDK库 ${report.cleanedSdkLibs}")
            if (report.cleanedSdkAssets > 0) add("assets ${report.cleanedSdkAssets}")
            if (report.cleanedRootFiles > 0) add("根文件 ${report.cleanedRootFiles}")
            if (report.hiddenLayoutViews > 0) add("布局隐藏 ${report.hiddenLayoutViews}")
        }
        if (parts.isEmpty()) {
            log("  · 未命中广告特征，无需修改")
        } else {
            log("  ✓ ${parts.joinToString(" | ")} | ${report.totalTimeMs}ms")
        }

        return report
    }

    /**
     * 处理单个 DEX 文件（供并行线程池调用）。
     * 独立 try-catch 崩溃防护，返回结构化统计。
     */
    private fun processSingleDex(
        dexFile: File,
        allAdPatterns: List<String>,
        adMethodPatterns: List<String>,
        adUrlPatterns: List<String>,
        forceTrueMethods: List<String>,
        forceFalseMethods: List<String>,
        methodNeutralizeKeywords: List<String>,
        adStringPatterns: List<String>,
        context: Context,
        log: Logger
    ): DexProcessingStats {
        val dexStart = System.currentTimeMillis()
        log("  ▶ 正在处理: ${dexFile.name} (${formatSize(dexFile.length())})")

        // 大 DEX 预检：体积超过阈值时提示，避免用户误以为卡死
        if (dexFile.length() > LARGE_DEX_THRESHOLD) {
            log("  ℹ 该 DEX 较大，已启用低内存安全扫描，请耐心等待...")
        }

        return try {
            val result = DexPatcher.patchDex(
                dexFile,
                allAdPatterns,
                adMethodPatterns,
                urlPatterns = adUrlPatterns,
                forceTrueMethodNames = forceTrueMethods,
                forceFalseMethodNames = forceFalseMethods,
                neutralizeMethodKeywords = methodNeutralizeKeywords,
                stringPatterns = adStringPatterns,
                stripDebugInfo = PathPreferences.isDexOptimizeEnabled(context),
                logger = { msg -> log(msg) }
            )
            DexProcessingStats(
                name = dexFile.name,
                originalSize = result.originalSize,
                newSize = result.newSize,
                skippedNoAd = result.skippedNoAd,
                skippedNoChange = result.skippedNoChange,
                patchedClasses = result.patchedClasses,
                neutralizedMethods = result.neutralizedMethods,
                neutralizedUrls = result.neutralizedUrlStrings,
                forcedTrueMethods = result.forcedTrueMethods,
                forcedFalseMethods = result.forcedFalseMethods,
                neutralizedStrings = result.neutralizedStrings,
                failed = result.failed,
                error = result.error,
                elapsedMs = result.elapsedMs
            )
        } catch (e: OutOfMemoryError) {
            log("  ✗ ${dexFile.name} 内存不足: ${e.message}")
            DexProcessingStats(
                name = dexFile.name, failed = true, error = e.message,
                elapsedMs = System.currentTimeMillis() - dexStart
            )
        } catch (e: Exception) {
            log("  ✗ ${dexFile.name} 修补失败: ${e.message}")
            DexProcessingStats(
                name = dexFile.name, failed = true, error = e.message,
                elapsedMs = System.currentTimeMillis() - dexStart
            )
        }
        // 不再 finally { System.gc() }：交由 ART 并发 GC，根治多 DEX 处理卡死
    }

    /**
     * DEX 文件自然顺序比较器：classes → classes2 → classes3 → ... → classes10。
     * 修复旧实现按字典序排序导致 classes10 错误排在 classes2 之前的问题。
     */
    private val dexNaturalComparator = Comparator<File> { a, b ->
        val ia = dexIndex(a.name)
        val ib = dexIndex(b.name)
        if (ia == ib) a.name.compareTo(b.name) else ia.compareTo(ib)
    }

    /** 解析 DEX 序号：classes.dex→0，classesN.dex→N；无法解析时返回 -1。 */
    private fun dexIndex(name: String): Int {
        val base = name.removeSuffix(".dex").removePrefix("classes")
        return base.toIntOrNull() ?: -1
    }

    private fun formatTimestamp(ms: Long): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(ms))
    }

    // ========== 阶段耗时日志 ==========
    private fun logPhaseTime(phaseName: String, startTime: Long, log: Logger) {
        val elapsed = System.currentTimeMillis() - startTime
        log("  ⏱ $phaseName 耗时: ${elapsed}ms")
    }

    private fun formatSize(bytes: Long): String = Format.formatSize(bytes)

    /**
     * 清理广告SDK对应的原生库文件（lib 目录下的 libXXX.so）。
     *
     * 通过广告SDK包名推导常见的 .so 库名关键词（如 adsdk、ttad、gdt、pangle、admob 等），
     * 遍历 APK 解压根目录下的 lib 目录，删除匹配的 .so 文件，从而"移除广告SDK文件"。
     *
     * @return 清理的文件数量
     */
    private fun cleanAdSdkLibs(
        extractDir: File,
        sdkPackages: List<String>,
        libFileKeywords: List<String>,
        log: Logger
    ): Int {
        log("▶ 阶段 3 SDK 库清理")

        val libDir = File(extractDir, "lib")
        if (!libDir.exists() || !libDir.isDirectory) {
            log("  · 未找到 lib 目录，跳过原生库清理")
            return 0
        }

        // 合并：配置的自定义关键词 + 从广告SDK包名自动推导的关键词
        val libKeywords = mutableSetOf<String>().apply {
            addAll(libFileKeywords.map { it.trim().lowercase() }.filter { it.isNotEmpty() })
            addAll(buildSdkLibKeywords(sdkPackages))
        }
        if (libKeywords.isEmpty()) {
            log("  · 无广告SDK库名关键词，跳过")
            return 0
        }
        log("  · 关键词 ${libKeywords.size} 个")

        val startTime = System.currentTimeMillis()
        var cleaned = 0
        val abiDirs = libDir.listFiles { f -> f.isDirectory } ?: emptyArray()
        for (abiDir in abiDirs) {
            val soFiles = abiDir.listFiles { f -> f.isFile && f.name.endsWith(".so") } ?: emptyArray()
            for (soFile in soFiles) {
                val libName = soFile.name.lowercase()
                if (libKeywords.any { libName.contains(it) }) {
                    if (soFile.delete()) {
                        cleaned++
                    } else {
                        log("  ⚠ 删除失败: ${abiDir.name}/${soFile.name}")
                    }
                }
            }
        }
        val elapsed = System.currentTimeMillis() - startTime

        log("  ✓ 清理 $cleaned 个 SDK 库 | ${elapsed}ms")
        return cleaned
    }

    /**
     * 从广告SDK包名集合推导原生库 .so 文件名关键词。
     * 例如 com.bytedance.sdk.openadsdk -> pangle/ttad, com.qq.e.ads -> gdt 等。
     */
    private fun buildSdkLibKeywords(sdkPackages: List<String>): Set<String> {
        val keywords = mutableSetOf<String>()
        val joined = sdkPackages.joinToString(" ").lowercase()

        // 常见广告SDK的原生库命名关键词
        val knownMappings = mapOf(
            "bytedance" to listOf("ttad", "pangle", "openadsdk", "bytedance"),
            "pangle" to listOf("pangle", "ttad"),
            "qq.e" to listOf("gdt", "qqad", "gdtad"),
            "gdt" to listOf("gdt"),
            "baidu" to listOf("baidu", "mobads", "mobad"),
            "kuaishou" to listOf("kuaishou", "gdfp"),
            "unity3d" to listOf("unityads", "unity_ad"),
            "mintegral" to listOf("mintegral", "mbridge", "mtg"),
            "mobvista" to listOf("mobvista", "mtg"),
            "vungle" to listOf("vungle"),
            "chartboost" to listOf("chartboost"),
            "appnext" to listOf("appnext"),
            "inmobi" to listOf("inmobi"),
            "flurry" to listOf("flurry"),
            "adcolony" to listOf("adcolony"),
            "applovin" to listOf("applovin", "applvn"),
            "ironsource" to listOf("ironsource", "is_adapt"),
            "startapp" to listOf("startapp"),
            "smaato" to listOf("smaato"),
            "pubmatic" to listOf("pubmatic"),
            "amazon" to listOf("amazon", "amoad"),
            "yandex" to listOf("yandex"),
            "mytarget" to listOf("mytarget"),
            "huawei" to listOf("huawei_hms", "hms_ads"),
            "sigmob" to listOf("sigmob"),
            "anythink" to listOf("anythink", "topon"),
            "topon" to listOf("topon"),
            "facebook" to listOf("facebook", "fb_ads", "audience"),
            "admob" to listOf("admob", "gms"),
            "googleadb" to listOf("gms"),
            "appodeal" to listOf("appodeal"),
            "pollfish" to listOf("pollfish"),
            "tapjoy" to listOf("tapjoy"),
            "mopub" to listOf("mopub"),
            "pubnative" to listOf("pubnative"),
            "fyber" to listOf("fyber", "inneractive"),
            "oneway" to listOf("oneway")
        )

        for ((pkgFragment, libNames) in knownMappings) {
            if (joined.contains(pkgFragment)) {
                keywords.addAll(libNames)
            }
        }

        // 通用穷举：任何包含 "ad" 的 .so 库名关键词（保守，需与包名关联）
        // 仅当包名里明确含 adsdk/ad 时加入
        if (joined.contains("adsdk") || joined.contains("_ads")) {
            keywords.add("adsdk")
        }
        return keywords
    }

    /**
     * 清理 assets 目录下的广告 SDK 文件/目录。
     *
     * 部分广告 SDK 会把插件、胶水层或运行库打包进 assets（.jar、子目录等），
     * 例如广点通 gdt_plugin、趣盟 qumeng、Oneway、百度 bdxadsdk 等。
     * 本方法通过两类方式删除：
     * 1. 已知广告 SDK 资产路径：精确匹配（目录或文件），命中即整条删除
     * 2. 广告关键词：路径/文件名包含关键词时删除，用于覆盖已知路径外的同类广告资产
     *
     * 先收集再删除、按路径深度降序处理，避免遍历过程中删除导致遗漏。
     *
     * @return 清理的条目数量（文件或目录）
     */
    private fun cleanAdSdkAssets(
        extractDir: File,
        adAssetPaths: List<String>,
        assetKeywords: List<String>,
        log: Logger
    ): Int {
        log("▶ 阶段 4 assets 清理")

        val assetsDir = File(extractDir, "assets")
        if (!assetsDir.exists() || !assetsDir.isDirectory) {
            log("  · 未找到 assets 目录，跳过广告文件清理")
            return 0
        }

        // 用户自定义的广告 SDK 资产路径（相对 assets/，目录或文件均可）
        val knownAdAssetPaths = adAssetPaths
            .map { it.trim().removePrefix("assets/").removeSuffix("/") }
            .filter { it.isNotEmpty() }

        if (knownAdAssetPaths.isEmpty()) {
            log("  · 未配置 assets 广告文件路径，跳过")
            return 0
        }
        log("  · 路径 ${knownAdAssetPaths.size} 个")

        // 广告关键词：覆盖自定义列表之外的同类广告资产（可在设置中自定义）
        val adKeywords = assetKeywords
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

        // 先收集目标，避免遍历中删除导致迭代行为异常
        val targets = mutableListOf<File>()
        var protectedCount = 0
        assetsDir.walkTopDown().forEach { file ->
            if (file == assetsDir) return@forEach
            val relative = assetsDir.toURI().relativize(file.toURI()).path.lowercase()
            val isKnown = knownAdAssetPaths.any { relPath ->
                val p = relPath.lowercase()
                relative == p || relative.startsWith("$p/")
            }

            // 保护 assets 下的 APK 子包文件（如 base.apk）。
            // 这类文件是被处理应用运行所需的子 APK / 动态加载资源，绝不应因文件名
            // 命中广告关键词而被误删；仅当用户通过"assets 广告文件路径"精确指定时才删除。
            val isEmbeddedApk = file.isFile && file.name.endsWith(".apk", ignoreCase = true)
            if ((!isEmbeddedApk || isKnown) && (isKnown || adKeywords.any { relative.contains(it) })) {
                targets.add(file)
            } else if (isEmbeddedApk && !isKnown) {
                protectedCount++
            }
        }

        var cleaned = 0
        // 按路径深度降序删除，保证先删文件、再删其父目录，避免重复计数
        val startTime = System.currentTimeMillis()
        targets.sortedByDescending { it.absolutePath.length }.forEach { file ->
            if (!file.exists()) return@forEach
            file.deleteRecursively()
            cleaned++
        }
        val elapsed = System.currentTimeMillis() - startTime

        if (protectedCount > 0) {
            log("  ℹ 已跳过 $protectedCount 个 assets 内置 APK 文件（如 base.apk），如需删除请在\"assets 广告文件路径\"中精确指定")
        }
        log("  ✓ 清理 $cleaned 个 assets 条目 | ${elapsed}ms")
        return cleaned
    }

    /**
     * 删除 APK 根目录下的广告相关文件。
     *
     * 有些广告 SDK 会在 APK 根目录（即与 classes.dex 同目录）放置配置文件、
     * 版本信息、OAID/设备标识等，例如：
     * - tt_version.json / tt_xxx.json（穿山甲）
     * - startup_config.json / jd_xxx.json（京东/联盟）
     * - oaid / 设备标识相关文件
     * 这些文件虽非 .so 或 assets，但同样属于广告 SDK 的运行时数据。
     *
     * 匹配规则：根目录下文件名包含任一关键词即删除（子串匹配，大小写不敏感）。
     * 仅处理根目录一层文件，不递归子目录，避免误删 res/lib/assets 等资源。
     *
     * @return 删除的文件数量
     */
    private fun cleanRootFiles(
        extractDir: File,
        rootFileKeywords: List<String>,
        log: Logger
    ): Int {
        log("▶ 阶段 5 根目录清理")

        val keywords = rootFileKeywords
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (keywords.isEmpty()) {
            log("  · 未配置根目录文件关键词，跳过")
            return 0
        }
        log("  · 关键词 ${keywords.size} 个")

        // 仅遍历 APK 根目录一层（与 classes.dex 同目录），不递归
        val rootFiles = extractDir.listFiles { f ->
            f.isFile && f.name != "classes.dex"
        } ?: emptyArray()

        var cleaned = 0
        val startTime = System.currentTimeMillis()
        for (file in rootFiles) {
            val name = file.name.lowercase()
            if (keywords.any { name.contains(it) }) {
                if (file.delete()) {
                    cleaned++
                } else {
                    log("  ⚠ 删除失败: ${file.name}")
                }
            }
        }
        val elapsed = System.currentTimeMillis() - startTime

        log("  ✓ 清理 $cleaned 个根目录文件 | ${elapsed}ms")
        return cleaned
    }

    /**
     * 隐藏 Res 布局中的广告 View。
     *
     * 遍历 APK 解压根目录下 res/layout、res/layout-* 目录以及 res 根目录下的所有 AXML
     * 布局/资源文件，对每个 XML 文件调用 [AxmlAdRemover.hideAdLayoutViews]，将命中
     * 广告特征的元素宽高改为 0dp，从而隐藏广告区域而不破坏布局结构。
     *
     * 兼容两种资源布局形式：
     * - 标准形式：xml 位于 res/layout、res/layout-land 等子目录
     * - 混淆形式：xml 文件名被混淆（如 -1.xml、-2.xml）且直接平铺在 res 根目录，
     *   此时没有 layout 子目录，需直接扫描 res 根目录下的 .xml 文件
     *
     * @return 被隐藏的广告元素总数
     */
    private fun hideAdLayoutViews(
        extractDir: File,
        layoutKeywords: List<String>,
        log: Logger
    ): Int {
        log("▶ 阶段 6 布局隐藏")

        val keywords = layoutKeywords
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (keywords.isEmpty()) {
            log("  · 未配置 Res 布局关键词，跳过")
            return 0
        }
        log("  · 关键词 ${keywords.size} 个")

        val resDir = File(extractDir, "res")
        if (!resDir.exists() || !resDir.isDirectory) {
            log("  · 未找到 res 目录，跳过")
            return 0
        }

        // 待扫描的 xml 文件集合（布局目录下的 + res 根目录下的混淆文件）
        val xmlFiles = mutableListOf<Pair<String, File>>()

        // 1. 收集所有 layout 目录（layout、layout-land、layout-night 等）下的 xml
        val layoutDirs = resDir.listFiles { f ->
            f.isDirectory && f.name.startsWith("layout")
        } ?: emptyArray()
        for (layoutDir in layoutDirs.sortedBy { it.name }) {
            val files = layoutDir.listFiles { f ->
                f.isFile && f.name.endsWith(".xml")
            } ?: emptyArray()
            for (f in files) {
                xmlFiles.add((layoutDir.name) to f)
            }
        }

        // 2. 收集 res 根目录下的 xml（兼容文件名被混淆、直接平铺在 res 根目录的 APK）
        val resRootXml = resDir.listFiles { f ->
            f.isFile && f.name.endsWith(".xml")
        } ?: emptyArray()
        for (f in resRootXml) {
            xmlFiles.add("res" to f)
        }

        if (xmlFiles.isEmpty()) {
            log("  · 未找到任何 res 布局 xml 文件，跳过")
            return 0
        }
        log("  · 共发现 ${xmlFiles.size} 个 res 布局 xml 文件（含 ${resRootXml.size} 个 res 根目录混淆 xml）")

        val startTime = System.currentTimeMillis()
        var totalHidden = 0
        var totalFiles = 0
        for ((dirName, xmlFile) in xmlFiles) {
            try {
                val hidden = AxmlAdRemover.hideAdLayoutViews(xmlFile, keywords)
                if (hidden > 0) {
                    totalHidden += hidden
                    totalFiles++
                }
            } catch (e: Exception) {
                log("  ⚠ $dirName/${xmlFile.name} 处理失败: ${e.message}")
            }
        }
        val elapsed = System.currentTimeMillis() - startTime

        log("  ✓ Res 广告布局隐藏完成: $totalFiles 个布局文件, $totalHidden 个元素, 耗时 ${elapsed}ms")
        return totalHidden
    }
}