# ProGuard rules for ApkAdRemoverEditor v2.2

# ===== Kotlin =====
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembernames class * {
    native <methods>;
}

# ===== App main classes =====
-keep class com.shinegirls.apkadremovereditor.** { *; }

# ===== DexPatcher / smali / dexlib2 / baksmali (heavy reflection) =====
-keep class org.jf.** { *; }
-keep class org.jf.dexlib2.** { *; }
-keep class org.jf.dexlib2.iface.** { *; }
-keep class org.jf.dexlib2.immutable.** { *; }
-keep class org.jf.dexlib2.dexbacked.** { *; }
-keep class org.jf.baksmali.** { *; }
-keep class org.jf.smali.** { *; }

# ===== BouncyCastle (security provider, registered by name) =====
-keep class org.bouncycastle.** { *; }
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.asn1.** { *; }
-keep class org.bouncycastle.cert.** { *; }
-keep class org.bouncycastle.operator.** { *; }
-keep class org.bouncycastle.crypto.** { *; }
-dontwarn javax.naming.**
-dontwarn org.bouncycastle.cert.dane.**
-dontwarn org.bouncycastle.jce.provider.X509LDAPCertStoreSpi

# ===== Guava (used by dexlib2 internally) =====
-keep class com.google.common.** { *; }
-dontwarn com.google.common.**
-dontwarn com.google.errorprone.**
-dontwarn com.google.j2objc.**
-dontwarn checkers.**
-dontwarn javax.annotation.**

# ===== APK signing (apksig) =====
-keep class com.android.apksig.** { *; }
-keep class com.android.apksig.internal.** { *; }
-keep class com.android.apksig.util.** { *; }

# ===== AndroidX / Material =====
-keep class androidx.** { *; }
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# ===== JSON serialization =====
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# ===== AdPatternConfig / Subscription (JSON serialization) =====
-keep class com.shinegirls.apkadremovereditor.core.AdPatternConfig { *; }
-keep class com.shinegirls.apkadremovereditor.core.AdPatternConfig$** { *; }
-keep class com.shinegirls.apkadremovereditor.core.ReportModels { *; }
-keep class com.shinegirls.apkadremovereditor.core.ReportModels$** { *; }

# ===== R8 full mode =====
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile