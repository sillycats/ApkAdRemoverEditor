package com.shinegirls.apkadremovereditor.core

import android.content.Context
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.builder.MethodImplementationBuilder
import org.jf.dexlib2.builder.instruction.BuilderInstruction10x
import org.jf.dexlib2.builder.instruction.BuilderInstruction21c
import org.jf.dexlib2.builder.instruction.BuilderInstruction35c
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableDexFile
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import org.jf.dexlib2.rewriter.DexRewriter
import org.jf.dexlib2.rewriter.Rewriter
import org.jf.dexlib2.rewriter.RewriterModule
import org.jf.dexlib2.rewriter.Rewriters
import com.shinegirls.apkadremovereditor.utils.Format
import android.util.Base64
import com.android.apksig.ApkVerifier
import java.io.File
import java.security.MessageDigest

/**
 * 去签名效验（去除 APK 签名校验）引擎 —— MT 管理器式【KillerApplication 注入过签】。
 *
 * 本实现直接使用 MT 管理器（ApkSignatureKillerEx）样本中的
 * bin.mt.signature.KillerApplication 及其 12 个内部类（a~l）与
 * libSignatureKiIIer.so 原生库，而非"置空校验方法"：
 *
 * 原理：
 * 1. 把样本中的 KillerApplication 钩子类（含 12 个内部类）以独立 classesN.dex 注入目标 APK；
 * 2. 改写目标应用 Application 的【父类】为 bin.mt.signature.KillerApplication（注入目标按优先级选择）：
 *    - 优先改写 androidx.multidex.MultiDexApplication 自身的父类（Application -> KillerApplication）；
 *    - 若该类不存在，改写自定义 Application（直接继承 Application）的父类；
 *    - 否则改写其他含有 .super Landroid/app/Application; 的类；
 *    - 若所有类都不含 .super Application，则把清单 application.android:name 指向 KillerApplication
 *      （清单无 name 时新建一个 name 写入）。
 *    - 注意：bin.mt.signature.KillerApplication 自身的父类（android.app.Application）绝不改写。
 * 3. 应用启动时框架先加载 KillerApplication（作为自定义 Application 的父类），其 <clinit> 立即执行：
 *    - killPM(pkg, signB64)：用反射替换 PackageInfo.CREATOR 为自定义 Parcelable.Creator，
 *      使所有经 Parcel 反序列化的 PackageInfo 在返回给应用前被回填【原包签名】；
 *      同时清空 PackageManager.sPackageInfoCache / Parcel.mCreators / sPairedCreators 缓存；
 *      Android 9+ 通过 KillerApplication$j（Unsafe + HiddenApiBypass）解除隐藏 API 限制。
 *    - killOpen(pkg, soName, originPath, extractPath)（仅原包模式）：从安装包 assets 中
 *      提取 origin.apk 到 files/SignatureKiIIer/base.apk，并加载 libSignatureKiIIer.so，
 *      使"从磁盘 APK 重新读取签名"（getPackageArchiveInfo / 原生校验）同样被覆盖。
 *
 * 两种模式（对应 UI 中"普通去除签名效验"与"原包去除签名效验"）：
 * - 普通模式：仅注入 KillerApplication 钩子 + 改写 Application 父类，覆盖绝大多数 Java 层签名校验。
 * - 原包模式（增强过签强度）：在前者基础上，额外嵌入 assets/SignatureKiIIer/origin.apk
 *   与 lib 目录下的 libSignatureKiIIer.so，并让 <clinit> 调用 killOpen 完成原生层过签。
 *
 * 相比"置空校验方法"，本方案不依赖脆弱的关键词/指纹臆测，不触碰被测应用任何业务方法，
 * 不产生字节码置空导致的启动卡死问题，过签强度与 MT 管理器一致。
 *
 * 崩溃防护：钩子 DEX 注入与 Application 父类改写均采用独立 try-catch + 备份原子写回。
 */
object SignatureVerificationRemover {

    /** 签名效验模式：关闭 */
    const val MODE_OFF = 0
    /** 签名效验模式：普通去除（仅注入 KillerApplication 钩子覆盖原包签名） */
    const val MODE_NORMAL = 1
    /** 签名效验模式：原包去除（增强，额外嵌入 origin.apk + 原生库，过签强度更高） */
    const val MODE_ORIGINAL = 2

    /** MT 钩子 Application 类（样本 bin.mt.signature.KillerApplication）。 */
    const val HOOK_TYPE = "Lbin/mt/signature/KillerApplication;"
    const val HOOK_CLASS_DOTTED = "bin.mt.signature.KillerApplication"

    /** 钩子类名（点号形式，用户可自定义，默认 android.app.AppIication）。 */
    const val DEFAULT_HOOK_CLASS = "android.app.AppIication"

    /** 签名信息（Base64 证书，用户可自定义；留空则运行时从原包读取真实签名）。 */
    const val DEFAULT_SIGN_INFO = ""

    /** 入口名称（包名，用户可自定义；留空则运行时从 manifest 读取真实包名）。 */
    const val DEFAULT_ENTRY_NAME = ""

    /** 用户可自定义的三个注入参数（默认 SignatureKiIIer 系列）。 */
    const val DEFAULT_ORIGIN_ASSET_PATH = "assets/SignatureKiIIer/origin.apk"
    const val DEFAULT_EXTRACT_PATH = "files/SignatureKiIIer/base.apk"
    const val DEFAULT_SO_NAME = "SignatureKiIIer"

    /** 原包模式：origin.apk 在安装包内的 assets 路径 / 解压目标路径 / 原生库名（默认值别名）。 */
    const val ORIGIN_ASSET_PATH = DEFAULT_ORIGIN_ASSET_PATH
    const val ORIGIN_EXTRACT_PATH = DEFAULT_EXTRACT_PATH
    const val SO_NAME = DEFAULT_SO_NAME

    /** App 内置资源：预编译钩子 DEX（含占位 <clinit>）与原生库。 */
    private const val ASSET_HOOK_DEX = "SignatureKiIIer/hook_base.dex"
    private const val ASSET_SO_DIR = "SignatureKiIIer/lib"

    /** 钩子 KillerApplication 的父类（样本权威模板：android.app.Application，绝不改写）。 */
    const val HOOK_SUPER_APPLICATION = "Landroid/app/Application;"
    /** 目标应用可能含有的 MultiDexApplication（其父类将被改写为 KillerApplication）。 */
    const val HOOK_SUPER_MULTIDEX = "Landroidx/multidex/MultiDexApplication;"

    /** <clinit> 访问标志：static */
    private const val ACC_STATIC = 0x8
    /** <clinit> 访问标志：constructor */
    private const val ACC_CONSTRUCTOR = 0x10000

    /**
     * 主入口：MT 式注入去签名效验。
     *
     * @param context 用于读取 App 内置的钩子 DEX / 原生库资源
     * @param extractDir 解包后的 APK 根目录（含 classes*.dex 与 AndroidManifest.xml）
     * @param originalApk 未处理的原始 APK 文件（用于读取其真实签名证书）
     * @param mode [MODE_OFF]/[MODE_NORMAL]/[MODE_ORIGINAL]
     * @param logger 日志回调
     * @param originAssetPath 原包在安装包内的 assets 路径（用户可自定义）
     * @param extractPath 原包解压目标路径（用户可自定义）
     * @param soName 原生库名（用户可自定义，对应 lib/<abi>/lib<soName>.so）
     * @param hookClass 钩子类名（点号形式，用户可自定义，默认 bin.mt.signature.KillerApplication）
     * @param signInfo 签名信息（Base64 证书，用户可自定义；留空则从原包读取真实签名）
     * @param entryName 入口名称（包名，用户可自定义；留空则从 manifest 读取真实包名）
     * @return 结构化报告 [SignRemovalReport]
     */
    fun removeSignatures(
        context: Context,
        extractDir: File,
        originalApk: File?,
        mode: Int,
        logger: Logger? = null,
        originAssetPath: String = DEFAULT_ORIGIN_ASSET_PATH,
        extractPath: String = DEFAULT_EXTRACT_PATH,
        soName: String = DEFAULT_SO_NAME,
        hookClass: String = DEFAULT_HOOK_CLASS,
        signInfo: String = DEFAULT_SIGN_INFO,
        entryName: String = DEFAULT_ENTRY_NAME
    ): SignRemovalReport {
        val log = logger ?: {}
        val startTime = System.currentTimeMillis()
        if (mode == MODE_OFF) {
            log("  · 签名效验去除已关闭，跳过")
            return SignRemovalReport()
        }
        val enhance = mode == MODE_ORIGINAL
        val modeLabel = if (enhance) "原包去除(增强)" else "普通去除"
        // 规范化钩子类名 → descriptor 形式（兼容用户输入点号 / 斜杠 / L...; 三种写法）
        val hookType = normalizeHookType(hookClass)
        val hookClassDotted = hookType.removePrefix("L").removeSuffix(";").replace('/', '.')
        log("▶ 签名效验去除 · $modeLabel")
        log("  · 原理: 注入 $hookClassDotted 钩子, 改写 Application 父类, 运行时覆盖原包签名")

        // 1. 签名信息（Base64）：优先用用户自定义值，否则从原包读取真实签名证书
        val signB64 = if (signInfo.isNotBlank()) {
            log("  · 使用自定义签名信息（${signInfo.length} 字符）")
            signInfo
        } else if (originalApk != null && originalApk.exists()) {
            readOriginalSignatureBase64(originalApk)
        } else null
        if (signB64 == null) {
            log("  ✗ 无法读取原包签名（原包缺失或未签名），无法注入钩子")
            return SignRemovalReport(
                mode = mode,
                elapsedMs = System.currentTimeMillis() - startTime
            )
        }
        val fingerprint = sha256Hex(Base64.decode(signB64, Base64.DEFAULT))

        // 2. manifest 信息：包名与已有自定义 Application（入口名称优先用用户自定义值）
        val manifestFile = File(extractDir, "AndroidManifest.xml")
        val manifestInfo = AxmlAdRemover.readManifestInfo(manifestFile)
        val packageName = entryName.takeIf { it.isNotBlank() } ?: manifestInfo?.packageName
        if (entryName.isNotBlank()) {
            log("  · 使用自定义入口名称: $entryName")
        }
        val customApp = manifestInfo?.applicationName
        val customAppType = resolveClassType(customApp, packageName)
        if (customAppType == null) {
            log("  · 原包未声明自定义 Application，将按策略选择注入目标")
        } else {
            log("  · 原包自定义 Application: $customApp")
        }

        // 3. 校验解包目录存在 DEX 文件
        val dexFiles = extractDir.listFiles { f -> f.isFile && f.name.endsWith(".dex") }
            ?: emptyArray()
        if (dexFiles.isEmpty()) {
            log("  · 未找到 DEX 文件，跳过")
            return SignRemovalReport(
                mode = mode,
                originalSignerFingerprint = fingerprint,
                elapsedMs = System.currentTimeMillis() - startTime
            )
        }
        log("  · 已读取原包签名: ${fingerprint.take(16)}…")

        // 4. 注入 KillerApplication 钩子 DEX（独立 classesN.dex）
        val hookStats = injectHookDex(
            context, extractDir, packageName, signB64, enhance,
            originAssetPath, extractPath, soName, hookType, log
        )

        // 5. 改写 Application 父类 / 清单指向（MT 式注入目标选择）
        var appStats: SignRemovalStats? = null
        if (!hookStats.failed) {
            appStats = patchApplicationSuperclass(extractDir, manifestFile, customAppType, hookType, hookClassDotted, log)
        }

        // 6. 原包模式：嵌入 origin.apk 与原生库
        var originStats: SignRemovalStats? = null
        if (enhance && !hookStats.failed) {
            originStats = embedOriginalPackage(
                context, extractDir, originalApk, originAssetPath, soName, log
            )
        }

        val stats = listOfNotNull(hookStats, appStats, originStats)
        val report = SignRemovalReport(
            mode = mode,
            originalSignerFingerprint = fingerprint,
            dexStats = stats,
            elapsedMs = System.currentTimeMillis() - startTime
        )
        if (!hookStats.failed) {
            log("  ✓ 签名效验去除完成: 已注入 KillerApplication 钩子($modeLabel), 涉及 ${report.totalPatchedDex} 个 DEX (${report.elapsedMs}ms)")
        }
        return report
    }

    // ==================== 钩子 DEX 注入 ====================

    /**
     * 从 App 内置资源读取预编译钩子 DEX，重建 <clinit>（注入包名/签名/路径），
     * 作为新的 classesN.dex 写入解包根目录。
     *
     * 钩子 DEX 由样本的 13 个类（KillerApplication + 12 个内部类）预编译而来，
     * 已含完整过签逻辑（CREATOR 替换 / HiddenApiBypass / 原生库加载），
     * 运行时仅需替换 <clinit> 中的占位字符串为真实值。
     *
     * 钩子类父类固定为 android.app.Application（样本权威模板），
     * 目标应用的 MultiDexApplication / 自定义 Application 父类改写后不会产生循环继承。
     *
     * @param originAssetPath 原包在安装包内的 assets 路径（用户可自定义）
     * @param extractPath 原包解压目标路径（用户可自定义）
     * @param soName 原生库名（用户可自定义）
     * @param hookType 钩子类 descriptor（如 Lbin/mt/signature/KillerApplication;，用户可自定义）
     */
    private fun injectHookDex(
        context: Context,
        extractDir: File,
        packageName: String?,
        signB64: String,
        enhance: Boolean,
        originAssetPath: String,
        extractPath: String,
        soName: String,
        hookType: String,
        log: Logger
    ): SignRemovalStats {
        val dexStart = System.currentTimeMillis()
        val workDir = createTempDir("SignatureKiIIer")
        return try {
            log("  ▶ 注入 MT 式签名钩子 (${hookType.removePrefix("L").removeSuffix(";")})…")

            // 1. 从 assets 读取预编译钩子 DEX（KillerApplication 父类固定为 android.app.Application）
            val baseDex = File(workDir, "hook_base.dex")
            if (!loadAssetToFile(context, ASSET_HOOK_DEX, baseDex)) {
                throw RuntimeException("内置钩子 DEX 资源缺失: $ASSET_HOOK_DEX")
            }

            // 2. 若用户自定义了钩子类名，用 DexRewriter 重命名钩子 DEX 中的 13 个类（主类 + 内部类 a~l）
            val renamed = if (hookType == HOOK_TYPE) {
                baseDex
            } else {
                log("  · 自定义钩子类名: ${hookType.removePrefix("L").removeSuffix(";")}")
                renameHookClasses(baseDex, hookType, log)
            }

            // 3. 重建 <clinit>：注入真实包名 / 签名 / 路径（三个路径用户可自定义）
            val pkg = packageName ?: ""
            val originPath = if (enhance) originAssetPath else ""
            val extract = if (enhance) extractPath else ""
            val so = if (enhance) soName else ""
            val patched = patchHookClinit(renamed, pkg, signB64, originPath, extract, so, hookType)

            // 4. 自检：确认产物确实含钩子类，且其父类恒为 android.app.Application
            //    （与 MT 管理器去签产物一致；若父类被误改为 MultiDexApplication 会导致循环继承/运行异常）
            val checkDex = DexFileFactory.loadDexFile(patched, Opcodes.getDefault())
            val hookClass = checkDex.classes.firstOrNull { it.type == hookType }
            if (hookClass == null) {
                throw RuntimeException("钩子 DEX 构建产物缺少 ${hookType} 类，已中止")
            }
            if (hookClass.superclass != HOOK_SUPER_APPLICATION) {
                throw RuntimeException(
                    "钩子 DEX 构建产物中 ${hookType} 父类异常: ${hookClass.superclass}，" +
                        "应为 $HOOK_SUPER_APPLICATION，已中止"
                )
            }

            // 5. 写入解包根目录（classesN.dex）
            val nextIndex = nextDexIndex(extractDir)
            val outDex = File(extractDir, if (nextIndex <= 1) "classes.dex" else "classes$nextIndex.dex")
            if (outDex.exists()) {
                val reserved = File(extractDir, "classes${nextIndex + 100}.dex")
                patched.copyTo(reserved, overwrite = true)
                val elapsed = System.currentTimeMillis() - dexStart
                log("  ✓ 签名钩子已写入独立 DEX: ${reserved.name} (${formatSize(reserved.length())}, ${elapsed}ms)")
                return SignRemovalStats(
                    name = reserved.name,
                    originalSize = reserved.length(),
                    newSize = reserved.length(),
                    patchedMethods = 1,
                    elapsedMs = elapsed
                )
            }
            patched.copyTo(outDex, overwrite = true)

            val elapsed = System.currentTimeMillis() - dexStart
            log("  ✓ 签名钩子已写入独立 DEX: ${outDex.name} (${formatSize(outDex.length())}, ${elapsed}ms)")
            SignRemovalStats(
                name = outDex.name,
                originalSize = outDex.length(),
                newSize = outDex.length(),
                patchedMethods = 1,
                elapsedMs = elapsed
            )
        } catch (e: Exception) {
            val s = SignWriteName(extractDir) ?: "hook.dex"
            log("  ✗ 签名钩子注入失败: ${e.message}")
            SignRemovalStats(name = s, failed = true, error = e.message,
                elapsedMs = System.currentTimeMillis() - dexStart)
        } finally {
            workDir.takeIf { it.exists() }?.deleteRecursively()
        }
    }

    /**
     * 重建 KillerApplication.<clinit>，把占位字符串替换为真实值。
     *
     * 生成的 <clinit> 语义（与样本 bin.mt.signature.KillerApplication 权威模板一致）：
     *   const-string v0, signB64
     *   const-string v1, pkg
     *   invoke-static {v1, v0}, KillerApplication.killPM(String, String)V
     *   const-string v0, originPath
     *   const-string v2, extractPath
     *   const-string v3, soName
     *   invoke-static {v1, v3, v0, v2}, KillerApplication.killOpen(String, String, String, String)V
     *   return-void
     */
    private fun patchHookClinit(
        baseDex: File,
        pkg: String,
        signB64: String,
        originPath: String,
        extractPath: String,
        soName: String,
        hookType: String
    ): File {
        val dexFile = DexFileFactory.loadDexFile(baseDex, Opcodes.getDefault())
        val newClasses = ArrayList<ClassDef>()
        for (cd in dexFile.classes) {
            if (cd.type == hookType) {
                // 保留原 <clinit> 的访问标志（static | constructor），仅替换方法体
                val origClinit = cd.directMethods.firstOrNull { it.name == "<clinit>" }
                val clinitAccess = origClinit?.accessFlags ?: (ACC_STATIC or ACC_CONSTRUCTOR)

                val b = MethodImplementationBuilder(4)
                b.addInstruction(BuilderInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(signB64)))
                b.addInstruction(BuilderInstruction21c(Opcode.CONST_STRING, 1, ImmutableStringReference(pkg)))
                b.addInstruction(BuilderInstruction35c(
                    Opcode.INVOKE_STATIC, 2, 1, 0, 0, 0, 0,
                    methodRef(hookType, "killPM",
                        listOf("Ljava/lang/String;", "Ljava/lang/String;"), "V")))
                b.addInstruction(BuilderInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(originPath)))
                b.addInstruction(BuilderInstruction21c(Opcode.CONST_STRING, 2, ImmutableStringReference(extractPath)))
                b.addInstruction(BuilderInstruction21c(Opcode.CONST_STRING, 3, ImmutableStringReference(soName)))
                b.addInstruction(BuilderInstruction35c(
                    Opcode.INVOKE_STATIC, 4, 1, 3, 0, 2, 0,
                    methodRef(hookType, "killOpen",
                        listOf("Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;"), "V")))
                b.addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))

                val newClinit = ImmutableMethod(
                    hookType, "<clinit>", emptyList(), "V",
                    clinitAccess, emptySet(), emptySet(), b.methodImplementation)

                val newDirect = ArrayList<Method>()
                for (m in cd.directMethods) {
                    newDirect.add(if (m.name == "<clinit>") newClinit else m)
                }
                newClasses.add(ImmutableClassDef(
                    cd.type, cd.accessFlags, cd.superclass, cd.interfaces,
                    cd.sourceFile, cd.annotations,
                    cd.staticFields, cd.instanceFields,
                    newDirect, cd.virtualMethods))
            } else {
                newClasses.add(ImmutableClassDef.of(cd))
            }
        }
        val newDex = ImmutableDexFile(Opcodes.getDefault(), newClasses)
        val out = File(baseDex.parentFile, "hook_patched.dex")
        DexFileFactory.writeDexFile(out.absolutePath, newDex)
        return out
    }

    // ==================== Application 父类改写 ====================

    /**
     * 把目标应用自定义 Application 类的父类改写为 bin.mt.signature.KillerApplication。
     *
     * 在包含该类的 DEX 中定位类定义，重建其 superclass 后整包写回（备份 + 原子重命名）。
     * 若该类不在任何 DEX 中（极罕见），返回失败，由调用方决定清单兜底。
     */
    private fun modifyApplicationSuperclass(
        extractDir: File,
        customAppType: String,
        hookType: String,
        log: Logger
    ): SignRemovalStats {
        val start = System.currentTimeMillis()
        val dexFiles = extractDir.listFiles { f -> f.isFile && f.name.endsWith(".dex") }
            ?: emptyArray()
        for (dexFile in dexFiles) {
            try {
                val dex = DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
                val target = dex.classes.firstOrNull { it.type == customAppType } ?: continue
                if (target.superclass == hookType) {
                    log("  · ${dexFile.name} 中 $customAppType 已是钩子类父类")
                    return SignRemovalStats(name = dexFile.name, patchedMethods = 1,
                        elapsedMs = System.currentTimeMillis() - start)
                }
                val newCd = ImmutableClassDef(
                    target.type, target.accessFlags, hookType, target.interfaces,
                    target.sourceFile, target.annotations,
                    target.staticFields, target.instanceFields,
                    target.directMethods, target.virtualMethods)
                val newClasses = ArrayList<ImmutableClassDef>(dex.classes.size)
                for (cd in dex.classes) {
                    newClasses.add(if (cd.type == customAppType) newCd else ImmutableClassDef.of(cd))
                }
                writeDexWithProtection(dexFile, newClasses)
                log("  ✓ 已将 $customAppType 的父类改为钩子类 (${dexFile.name})")
                return SignRemovalStats(name = dexFile.name, patchedMethods = 1,
                    elapsedMs = System.currentTimeMillis() - start)
            } catch (e: Exception) {
                log("  ⚠ 处理 ${dexFile.name} 失败: ${e.message}")
            }
        }
        log("  ✗ 未在 DEX 中找到自定义 Application 类 $customAppType，父类改写失败")
        return SignRemovalStats(name = "classes.dex", failed = true,
            error = "未找到 $customAppType", elapsedMs = System.currentTimeMillis() - start)
    }

    // ==================== 注入目标选择（MT 式） ====================

    /**
     * 选择并改写 Application 父类为 bin.mt.signature.KillerApplication（MT 式注入目标选择）。
     *
     * 策略（按优先级）：
     * 1. 优先改写 androidx.multidex.MultiDexApplication 自身的父类（Application -> KillerApplication）；
     *    注意：bin.mt.signature.KillerApplication 自身的父类（android.app.Application）绝不改写。
     * 2. 若 MultiDexApplication 不存在 → 改写自定义 Application（若其直接继承 android.app.Application）的父类；
     * 3. 否则改写其他含有 .super Landroid/app/Application; 的类（排除 KillerApplication 与 MultiDexApplication）；
     * 4. 若所有类都不含 .super Landroid/app/Application; → 改写 axml 的 application name
     *    （清单无 name 时新建一个 name 写入）。
     */
    private fun patchApplicationSuperclass(
        extractDir: File,
        manifestFile: File,
        customAppType: String?,
        hookType: String,
        hookClassDotted: String,
        log: Logger
    ): SignRemovalStats {
        // 1. 优先：改写 androidx.multidex.MultiDexApplication 的父类（Application -> KillerApplication）
        val multidexStats = modifyClassSuperclass(extractDir, HOOK_SUPER_MULTIDEX, hookType, log)
        if (multidexStats != null) return multidexStats

        // 2. 其次：自定义 Application（若其直接继承 android.app.Application，且非钩子类本身）
        if (customAppType != null && customAppType != hookType &&
            findClassSuperclass(extractDir, customAppType) == HOOK_SUPER_APPLICATION
        ) {
            val stats = modifyApplicationSuperclass(extractDir, customAppType, hookType, log)
            if (!stats.failed) return stats
        }

        // 3. 再次：任意继承 android.app.Application 的类（排除钩子类与 MultiDexApplication）
        val fallback = findAndModifyApplicationSubclass(extractDir, customAppType, hookType, log)
        if (fallback != null) return fallback

        // 4. 兜底：改写 axml 的 application name（无 name 则新建）
        return setManifestApplicationName(manifestFile, hookClassDotted, log)
    }

    /**
     * 在 DEX 中查找指定类（如 androidx.multidex.MultiDexApplication），
     * 若其父类为 android.app.Application 则改写为 bin.mt.signature.KillerApplication。
     * 返回 null 表示未找到该类（或无需改写）。
     */
    private fun modifyClassSuperclass(
        extractDir: File,
        classType: String,
        hookType: String,
        log: Logger
    ): SignRemovalStats? {
        val start = System.currentTimeMillis()
        val dexFiles = extractDir.listFiles { f -> f.isFile && f.name.endsWith(".dex") }
            ?: return null
        for (dexFile in dexFiles) {
            try {
                val dex = DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
                val target = dex.classes.firstOrNull { it.type == classType } ?: continue
                if (target.superclass == hookType) {
                    log("  · ${dexFile.name} 中 $classType 已是钩子类父类")
                    return SignRemovalStats(name = dexFile.name, patchedMethods = 1,
                        elapsedMs = System.currentTimeMillis() - start)
                }
                if (target.superclass != HOOK_SUPER_APPLICATION) {
                    log("  · ${dexFile.name} 中 $classType 父类为 ${target.superclass}，跳过改写")
                    return null
                }
                log("  · 找到 $classType (${dexFile.name})，改写其父类")
                val newCd = ImmutableClassDef(
                    target.type, target.accessFlags, hookType, target.interfaces,
                    target.sourceFile, target.annotations,
                    target.staticFields, target.instanceFields,
                    target.directMethods, target.virtualMethods)
                val newClasses = ArrayList<ImmutableClassDef>(dex.classes.size)
                for (cd in dex.classes) {
                    newClasses.add(if (cd.type == classType) newCd else ImmutableClassDef.of(cd))
                }
                writeDexWithProtection(dexFile, newClasses)
                log("  ✓ 已将 $classType 的父类改为钩子类 (${dexFile.name})")
                return SignRemovalStats(name = dexFile.name, patchedMethods = 1,
                    elapsedMs = System.currentTimeMillis() - start)
            } catch (e: Exception) {
                log("  ⚠ 处理 ${dexFile.name} 失败: ${e.message}")
            }
        }
        return null
    }

    /**
     * 改写 axml 的 application.android:name 指向钩子类。
     */
    private fun setManifestApplicationName(manifestFile: File, hookClassDotted: String, log: Logger): SignRemovalStats {
        val ok = AxmlAdRemover.setApplicationName(manifestFile, hookClassDotted)
        if (ok) {
            log("  ✓ 已改写 application.android:name 指向 $hookClassDotted")
            return SignRemovalStats(name = "AndroidManifest.xml", patchedMethods = 1)
        }
        log("  ⚠ 改写清单失败（钩子类可能不生效）")
        return SignRemovalStats(name = "AndroidManifest.xml", failed = true, error = "改写清单失败")
    }

    /**
     * 在 DEX 中查找含有 .super Landroid/app/Application; 的类（排除指定类、KillerApplication 与
     * MultiDexApplication），改写其父类为 bin.mt.signature.KillerApplication。
     * 返回 null 表示未找到任何可改写的类。
     */
    private fun findAndModifyApplicationSubclass(
        extractDir: File,
        excludeType: String?,
        hookType: String,
        log: Logger
    ): SignRemovalStats? {
        val start = System.currentTimeMillis()
        val dexFiles = extractDir.listFiles { f -> f.isFile && f.name.endsWith(".dex") }
            ?: return null
        for (dexFile in dexFiles) {
            try {
                val dex = DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
                val target = dex.classes.firstOrNull {
                    it.superclass == HOOK_SUPER_APPLICATION &&
                        it.type != excludeType &&
                        it.type != hookType &&
                        it.type != HOOK_SUPER_MULTIDEX
                } ?: continue
                log("  · 找到继承 android.app.Application 的类: ${target.type} (${dexFile.name})")
                val newCd = ImmutableClassDef(
                    target.type, target.accessFlags, hookType, target.interfaces,
                    target.sourceFile, target.annotations,
                    target.staticFields, target.instanceFields,
                    target.directMethods, target.virtualMethods)
                val newClasses = ArrayList<ImmutableClassDef>(dex.classes.size)
                for (cd in dex.classes) {
                    newClasses.add(if (cd.type == target.type) newCd else ImmutableClassDef.of(cd))
                }
                writeDexWithProtection(dexFile, newClasses)
                log("  ✓ 已将 ${target.type} 的父类改为钩子类 (${dexFile.name})")
                return SignRemovalStats(name = dexFile.name, patchedMethods = 1,
                    elapsedMs = System.currentTimeMillis() - start)
            } catch (e: Exception) {
                log("  ⚠ 处理 ${dexFile.name} 失败: ${e.message}")
            }
        }
        return null
    }

    // ==================== 原包模式：嵌入 origin.apk + 原生库 ====================

    /**
     * 原包模式：把原始 APK 嵌入 assets 指定路径（用户可自定义），
     * 并把内置的 libSignatureKiIIer.so 复制到 lib/<abi>/lib<soName>.so。
     *
     * SO 库写入策略：
     * - 若目标 APK 解包目录存在 lib/ 目录，则仅写入其中已存在的 ABI
     *   （存在 arm64-v8a 就写 arm64-v8a，存在 armeabi-v7a 就写 armeabi-v7a，哪个存在写哪里）；
     * - 若目标 APK 不存在 lib/ 目录，则全部 ABI 都写入（含 x86 与 x86_64）。
     */
    private fun embedOriginalPackage(
        context: Context,
        extractDir: File,
        originalApk: File?,
        originAssetPath: String,
        soName: String,
        log: Logger
    ): SignRemovalStats {
        val start = System.currentTimeMillis()
        var embedded = 0
        try {
            // 1. origin.apk（路径用户可自定义）
            if (originalApk != null && originalApk.exists()) {
                val originFile = File(extractDir, originAssetPath)
                originFile.parentFile?.mkdirs()
                originalApk.copyTo(originFile, overwrite = true)
                embedded++
                log("  ✓ 已嵌入原包: $originAssetPath (${formatSize(originFile.length())})")
            } else {
                log("  ⚠ 原包缺失，无法嵌入 origin.apk")
            }

            // 2. lib<soName>.so：按目标 APK 已存在的 ABI 写入；无 lib 目录则全部写入
            val libDir = File(extractDir, "lib")
            val existingAbis = if (libDir.exists() && libDir.isDirectory) {
                libDir.listFiles { f -> f.isDirectory }?.map { it.name }?.toSet() ?: emptySet()
            } else emptySet()

            val soAssets = context.assets.list(ASSET_SO_DIR) ?: emptyArray()
            val abisToWrite = if (existingAbis.isEmpty()) {
                log("  · 目标 APK 无 lib 目录，写入全部 ABI（含 x86 / x86_64）")
                soAssets.toList()
            } else {
                log("  · 检测到目标 APK lib 目录 ABI: ${existingAbis.sorted().joinToString()}")
                soAssets.filter { it in existingAbis }
            }
            if (abisToWrite.isEmpty()) {
                log("  ⚠ 目标 APK 已存在的 ABI 均无对应原生库资源，未嵌入 so")
            }
            for (abi in abisToWrite) {
                val srcName = "$abi/libSignatureKiIIer.so"
                val target = File(extractDir, "lib/$abi/lib$soName.so")
                target.parentFile?.mkdirs()
                if (loadAssetToFile(context, "$ASSET_SO_DIR/$srcName", target)) {
                    embedded++
                    log("  ✓ 已嵌入原生库: lib/$abi/lib$soName.so (${formatSize(target.length())})")
                } else {
                    log("  ⚠ 原生库资源缺失: $srcName")
                }
            }
            return SignRemovalStats(name = "$originAssetPath + lib", patchedMethods = embedded,
                elapsedMs = System.currentTimeMillis() - start)
        } catch (e: Exception) {
            log("  ✗ 原包模式资源嵌入失败: ${e.message}")
            return SignRemovalStats(name = "$originAssetPath + lib", failed = true, error = e.message,
                elapsedMs = System.currentTimeMillis() - start)
        }
    }

    /**
     * 用 dexlib2 的 DexRewriter 重命名钩子 DEX 中的 13 个类（主类 + 内部类 a~l）。
     *
     * 仅重写类型引用（类定义 / 方法引用 / 字段引用 / 注解 / 父类等），
     * 钩子类内部无点号类名字符串字面量，因此无需处理字符串池。
     * 主类父类恒为 android.app.Application，不受影响。
     */
    private fun renameHookClasses(baseDex: File, hookType: String, log: Logger): File {
        val dexFile = DexFileFactory.loadDexFile(baseDex, Opcodes.getDefault())
        val oldPrefix = HOOK_TYPE.removeSuffix(";")   // Lbin/mt/signature/KillerApplication
        val newPrefix = hookType.removeSuffix(";")    // Lcom/custom/MyHook
        val module = object : RewriterModule() {
            override fun getTypeRewriter(rewriters: Rewriters): Rewriter<String> {
                return object : Rewriter<String> {
                    override fun rewrite(value: String): String {
                        return if (value.startsWith(oldPrefix)) {
                            newPrefix + value.substring(oldPrefix.length)
                        } else value
                    }
                }
            }
        }
        val rewritten = DexRewriter(module).getDexFileRewriter().rewrite(dexFile)
        val out = File(baseDex.parentFile, "hook_renamed.dex")
        DexFileFactory.writeDexFile(out.absolutePath, rewritten)
        log("  ✓ 钩子类已重命名为 ${hookType.removePrefix("L").removeSuffix(";")} (含 12 个内部类)")
        return out
    }

    /**
     * 规范化用户输入的钩子类名 → descriptor 形式（L...;）。
     * 兼容三种写法：点号（bin.mt.signature.KillerApplication）、
     * 斜杠（bin/mt/signature/KillerApplication）、完整 descriptor（Lbin/mt/signature/KillerApplication;）。
     */
    private fun normalizeHookType(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return HOOK_TYPE
        if (s.startsWith("L") && s.endsWith(";")) {
            s = s.substring(1, s.length - 1)
        }
        s = s.replace('.', '/')
        return "L$s;"
    }

    // ==================== 原包签名读取 ====================

    /**
     * 读取 APK 首个签名证书的 DER 字节，Base64(NO_WRAP) 编码。
     * 与 MT 钩子 <clinit> 中 `Base64.decode(signB64, 0)` + `new Signature(bytes)` 严格对应。
     */
    private fun readOriginalSignatureBase64(apkFile: File): String? {
        return try {
            val result = ApkVerifier.Builder(apkFile).build().verify()
            val certs = result.signerCertificates
            if (certs.isEmpty()) return null
            Base64.encodeToString(certs[0].encoded, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    private fun sha256Hex(data: ByteArray): String {
        return try {
            MessageDigest.getInstance("SHA-256")
                .digest(data).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            ""
        }
    }

    // ==================== 辅助 ====================

    /** 解析自定义 Application 类名（支持 .X 相对前缀）为 dex 类型名。 */
    private fun resolveClassType(customApp: String?, packageName: String?): String? {
        val custom = customApp?.trim()
        if (custom.isNullOrEmpty()) return null
        var name = custom
        if (name.startsWith(".") && !packageName.isNullOrBlank()) {
            name = packageName + name
        }
        val t = name.replace('.', '/')
        return if (t.startsWith("L") && t.endsWith(";")) t else "L$t;"
    }

    /**
     * 在 DEX 中查找指定类的父类类型。
     */
    private fun findClassSuperclass(extractDir: File, classType: String?): String? {
        if (classType == null) return null
        val dexFiles = extractDir.listFiles { f -> f.isFile && f.name.endsWith(".dex") }
            ?: return null
        for (dexFile in dexFiles) {
            try {
                val dex = DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
                val target = dex.classes.firstOrNull { it.type == classType } ?: continue
                return target.superclass
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun methodRef(cls: String, name: String, params: List<String>, ret: String) =
        ImmutableMethodReference(cls, name, params, ret)

    /** 从 assets 读取资源到目标文件。 */
    private fun loadAssetToFile(context: Context, assetPath: String, target: File): Boolean {
        return try {
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.length() > 0
        } catch (_: Exception) {
            false
        }
    }

    /** 仅供错误回显定位：返回目录中下一个可用 dex 名，不执行写入。 */
    private fun SignWriteName(extractDir: File): String? {
        val n = nextDexIndex(extractDir)
        return if (n <= 1) "classes.dex" else "classes$n.dex"
    }

    /** 计算目录中现有 classesN.dex 的最大序号 + 1（classes.dex 计为 1）。 */
    private fun nextDexIndex(extractDir: File): Int {
        val files = extractDir.listFiles { f -> f.isFile && f.name.endsWith(".dex") }
            ?: return -999
        var max = 0
        for (f in files) {
            val n = dexIndex(f.name)
            if (n != -1 && n > max) max = n
        }
        return max + 1
    }

    private fun dexIndex(name: String): Int {
        val lower = name.lowercase()
        if (!lower.endsWith(".dex")) return -1
        val base = lower.removeSuffix(".dex")
        return when {
            base == "classes" -> 1
            base.startsWith("classes") -> base.removePrefix("classes").toIntOrNull() ?: -1
            else -> -1
        }
    }

    private fun createTempDir(prefix: String): File {
        var n = 0
        while (true) {
            val f = File(System.getProperty("java.io.tmpdir"), "${prefix}_${System.nanoTime()}_$n")
            if (f.mkdirs()) return f
            n++
        }
    }

    /**
     * 备份 + 临时文件 + 原子重命名写回 DEX，异常时自动恢复原文件。
     * 与 DexPatcher 走同一套已被真机验证的写入链路（ImmutableDexFile + DexFileFactory.writeDexFile）。
     */
    private fun writeDexWithProtection(dexFile: File, newClasses: List<ImmutableClassDef>) {
        val backup = File(dexFile.parentFile, "${dexFile.name}.bak")
        if (!backup.exists()) {
            dexFile.copyTo(backup, overwrite = true)
        }
        try {
            val newDex = ImmutableDexFile(Opcodes.getDefault(), newClasses)
            val tmpDex = File(dexFile.parentFile, "${dexFile.name}.tmp")
            if (tmpDex.exists()) tmpDex.delete()
            DexFileFactory.writeDexFile(tmpDex.absolutePath, newDex)
            if (!tmpDex.renameTo(dexFile)) {
                dexFile.delete()
                if (!tmpDex.renameTo(dexFile)) {
                    tmpDex.copyTo(dexFile, overwrite = true)
                    tmpDex.delete()
                }
            }
            backup.delete()
        } catch (e: Exception) {
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

    private fun formatSize(bytes: Long): String = Format.formatSize(bytes)
}

/**
 * 签名效验去除报告（结构化，供 UI/报告生成）。
 */
data class SignRemovalReport(
    val mode: Int = SignatureVerificationRemover.MODE_OFF,
    val originalSignerFingerprint: String = "",
    val dexStats: List<SignRemovalStats> = emptyList(),
    val elapsedMs: Long = 0
) {
    val totalPatchedMethods: Int get() = dexStats.sumOf { it.patchedMethods }
    val totalPatchedDex: Int get() = dexStats.count { it.patchedMethods > 0 }
    val totalFailedDex: Int get() = dexStats.count { it.failed }
}

/**
 * 单个 DEX 的签名效验注入统计。
 * patchedMethods：1 表示成功注入一个签名钩子类 / 改写一个 Application 父类。
 */
data class SignRemovalStats(
    val name: String = "",
    val originalSize: Long = 0,
    val newSize: Long = 0,
    val patchedMethods: Int = 0,
    val failed: Boolean = false,
    val error: String? = null,
    val elapsedMs: Long = 0
) {
    val changed: Boolean get() = patchedMethods > 0
}
