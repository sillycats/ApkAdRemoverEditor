package com.shinegirls.apkadremovereditor.core

import android.content.Context
import com.android.apksig.ApkSigner
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * APK 签名器，使用 apksig (com.android.apksig) 进行 v1/v2 签名。
 *
 * 【签名密钥】
 * 优先使用内置的 AOSP platform 签名密钥（assets/platform.pk8 + assets/platform.x509.pem），
 * 该密钥可通过 openssl 从 PKCS#8 DER 私钥加载，签名产物具有 Android 平台签名权限。
 * 若内置密钥缺失或解析失败，则回退到应用自动生成的自签名证书。
 *
 * 为何只启用 v1 + v2（不启用 v3）：
 * - v1（JAR 签名）: 兼容 Android 4.4 ~ 7.0 的所有设备。
 * - v2（APK Signature Scheme v2）: 兼容 Android 7.0+，且满足
 *   "targetSdkVersion >= 30 的 APK 必须使用 v2 或更高签名方案" 的强制要求。
 * - v3 签名为 Android 9.0+ 的签名轮换设计，在部分定制 ROM 与旧工具链上
 *   存在验证失败导致无法安装的已知风险；v1+v2 已覆盖全部 Android 版本，
 *   因此移除 v3 以最大化安装兼容性。
 *
 * 证书生成（回退用）使用 BouncyCastle 1.70 的新 API（非已废弃的 X509V3CertificateGenerator）。
 */
object Signer {

    /** assets 中内置的 platform 签名密钥文件 */
    private const val ASSET_PRIVATE_KEY = "platform.pk8"
    private const val ASSET_CERTIFICATE = "platform.x509.pem"

    private const val KEYSTORE_NAME = "apk_editor_keystore.p12"
    private const val KEY_ALIAS = "apkeditor"
    private const val KEYSTORE_PASSWORD = "apkeditor123"
    private const val KEY_SIZE = 2048

    /**
     * 使用自管理的 BouncyCastle Provider 实例，避免与系统裁剪版 BC 名称冲突。
     */
    private val bcProvider: BouncyCastleProvider = BouncyCastleProvider()

    init {
        if (Security.getProvider(bcProvider.name) == null) {
            Security.addProvider(bcProvider)
        }
    }

    /**
     * 对 APK 进行签名。
     *
     * @param inputApk  未签名的 APK 文件
     * @param outputApk 签名后的输出 APK 文件
     */
    fun signApk(context: Context, inputApk: File, outputApk: File) {
        // 优先用内置的 AOSP platform 密钥，缺失/解析失败才回退到自动生成的自签名证书
        val (privateKey, certificate) = loadKeyAndCert(context)

        if (outputApk.exists()) outputApk.delete()
        outputApk.parentFile?.mkdirs()

        val signerConfig = ApkSigner.SignerConfig.Builder(
            KEY_ALIAS,
            privateKey,
            listOf(certificate)
        ).build()

        val apkSigner = ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(inputApk)
            .setOutputApk(outputApk)
            .setMinSdkVersion(21)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(false)
            .build()

        apkSigner.sign()
    }

    /**
     * 仅做 V1（JAR）签名。
     *
     * 用于"过签包"（含 assets/base.apk 等嵌套原包）的数据复用优化流程：
     * V1 签名在优化前完成（优化不改变文件内容，V1 签名保持有效），
     * 优化后由 [signV2V3] 补 V2 签名。若直接用 apksig 做 V2 签名，
     * 会重写中央目录导致数据复用优化失效。
     */
    fun signApkV1(context: Context, inputApk: File, outputApk: File) {
        val (privateKey, certificate) = loadKeyAndCert(context)

        if (outputApk.exists()) outputApk.delete()
        outputApk.parentFile?.mkdirs()

        val signerConfig = ApkSigner.SignerConfig.Builder(
            KEY_ALIAS,
            privateKey,
            listOf(certificate)
        ).build()

        val apkSigner = ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(inputApk)
            .setOutputApk(outputApk)
            .setMinSdkVersion(21)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(false)
            .setV3SigningEnabled(false)
            .build()

        apkSigner.sign()
    }

    /**
     * 使用 V2V3SchemeSigner 对已优化的 APK 进行 V2 签名（原地修改文件）。
     *
     * 数据复用优化会破坏 apksig 生成的 V2/V3 签名块（中央目录偏移被改写），
     * 因此优化后必须用本方法重新签名。V2V3SchemeSigner 直接操作 ZIP 结构，
     * 在中央目录前插入签名块，不改变各条目的数据偏移，从而保持复用优化有效。
     */
    fun signV2V3(context: Context, apkFile: File) {
        val (privateKey, certificate) = loadKeyAndCert(context)
        val signatureKey = object : bin.mt.apksign.key.SignatureKey {
            override fun getCertificate(): X509Certificate = certificate
            override fun getPrivateKey(): PrivateKey = privateKey
        }
        bin.mt.apksign.V2V3SchemeSigner.sign(apkFile, signatureKey, true, false)
    }

    /**
     * 加载签名密钥与证书。
     *
     * 优先级：
     * 1. 内置 AOSP platform 密钥（assets/platform.pk8 + platform.x509.pem），
     *    从 PKCS#8 DER 私钥 + X509 PEM 证书加载。
     * 2. 回退：应用私有目录中的 PKCS12 keystore（自动生成的自签名证书）。
     */
    private fun loadKeyAndCert(context: Context): Pair<PrivateKey, X509Certificate> {
        try {
            val pk8 = context.assets.open(ASSET_PRIVATE_KEY).use { it.readBytes() }
            val certPem = context.assets.open(ASSET_CERTIFICATE).use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            val privateKey = loadPkcs8PrivateKey(pk8)
            val certificate = loadX509Certificate(certPem)
            if (privateKey != null && certificate != null) {
                return Pair(privateKey, certificate)
            }
        } catch (_: Exception) {
            // 内置密钥缺失或解析失败，回退到自签名
        }
        return loadOrCreateKeyAndCert(context)
    }

    /** 从 PKCS#8 DER 编码的私钥字节加载 RSA 私钥。 */
    private fun loadPkcs8PrivateKey(pkcs8Der: ByteArray): PrivateKey? = try {
        val spec = PKCS8EncodedKeySpec(pkcs8Der)
        KeyFactory.getInstance("RSA").generatePrivate(spec)
    } catch (_: Exception) {
        null
    }

    /** 从 PEM 文本加载 X509 证书。 */
    private fun loadX509Certificate(pem: String): X509Certificate? = try {
        val certFactory = CertificateFactory.getInstance("X.509")
        ByteArrayInputStream(pem.toByteArray(Charsets.UTF_8)).use { fis ->
            certFactory.generateCertificate(fis) as? X509Certificate
        }
    } catch (_: Exception) {
        null
    }

    private fun loadOrCreateKeyAndCert(context: Context): Pair<PrivateKey, X509Certificate> {
        val keystoreFile = File(context.filesDir, KEYSTORE_NAME)
        val keyStore = KeyStore.getInstance("PKCS12")

        if (!keystoreFile.exists()) {
            createKeystore(keystoreFile, keyStore)
        } else {
            keystoreFile.inputStream().use { fis ->
                keyStore.load(fis, KEYSTORE_PASSWORD.toCharArray())
            }
            // 若别名不存在则重建
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                createKeystore(keystoreFile, keyStore)
            }
        }

        val privateKey = keyStore.getKey(KEY_ALIAS, KEYSTORE_PASSWORD.toCharArray()) as PrivateKey
        val certificate = keyStore.getCertificate(KEY_ALIAS) as X509Certificate
        return Pair(privateKey, certificate)
    }

    private fun createKeystore(keystoreFile: File, keyStore: KeyStore) {
        keyStore.load(null, null)

        val keyPair = generateKeyPair()
        val certificate = generateSelfSignedCertificate(keyPair)

        keyStore.setKeyEntry(
            KEY_ALIAS,
            keyPair.private,
            KEYSTORE_PASSWORD.toCharArray(),
            arrayOf(certificate)
        )

        keystoreFile.parentFile?.mkdirs()
        keystoreFile.outputStream().use { fos ->
            keyStore.store(fos, KEYSTORE_PASSWORD.toCharArray())
        }
    }

    private fun generateKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(KEY_SIZE)
        return keyPairGenerator.generateKeyPair()
    }

    /**
     * 使用 BouncyCastle 新 API 生成自签名 X.509 v3 证书。
     */
    private fun generateSelfSignedCertificate(keyPair: KeyPair): X509Certificate {
        val subject = X500Name("CN=APKEditor, O=APKEditor, C=CN")
        val notBefore = Date(System.currentTimeMillis() - 24L * 60 * 60 * 1000)
        // 有效期 25 年
        val notAfter = Date(System.currentTimeMillis() + 3650L * 24 * 60 * 60 * 1000)
        val serial = BigInteger.valueOf(System.currentTimeMillis())

        val certBuilder = JcaX509v3CertificateBuilder(
            subject,            // issuer（自签名，颁发者=主体）
            serial,
            notBefore,
            notAfter,
            subject,            // subject
            keyPair.public
        )

        val contentSigner = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(bcProvider)
            .build(keyPair.private)

        val certHolder: X509CertificateHolder = certBuilder.build(contentSigner)

        return JcaX509CertificateConverter()
            .setProvider(bcProvider)
            .getCertificate(certHolder)
    }
}
