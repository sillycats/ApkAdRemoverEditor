# 第三方许可声明 (Third-Party Notices)

本文件列出了 **APK去广告编辑器（ApkAdRemoverEditor）** 所使用、调用或参考的第三方开源项目及其许可信息。

本项目遵循 MIT License 开源，但**不改变**以下第三方项目的原始许可条款。使用本项目时，请同时遵守本项目及其依赖项目的许可要求。

---

## 一、直接依赖（编译期引入）

### 1. dexlib2 / smali / baksmali

| 项目 | 说明 |
|------|------|
| 主页 | https://github.com/JesusFreke/smali |
| 作者 | JesusFreke |
| 用途 | DEX 文件读写与 smali 反汇编/汇编工具链，本项目去广告核心引擎 |
| 协议 | BSD 3-Clause |

**BSD 3-Clause License 摘要**：允许自由使用、修改、分发（含商业用途），需保留版权声明、条件列表与免责声明；禁止使用作者名义进行推广。

### 2. apksig

| 项目 | 说明 |
|------|------|
| 主页 | https://android.googlesource.com/platform/tools/apksig |
| 作者 | AOSP (Android Open Source Project) |
| 用途 | APK v1/v2/v3 签名实现，保证处理结果可安装 |
| 协议 | Apache License 2.0 |

### 3. BouncyCastle (bcprov / bcpkix)

| 项目 | 说明 |
|------|------|
| 主页 | https://www.bouncycastle.org/ |
| 作者 | The Legion of the Bouncy Castle Inc. |
| 用途 | Java 加解密与证书生成库，用于生成签名证书 |
| 协议 | MIT License |

### 4. Guava

| 项目 | 说明 |
|------|------|
| 主页 | https://github.com/google/guava |
| 作者 | Google |
| 用途 | Java 集合与工具库，提供 dexlib2 所需的不可变集合 |
| 协议 | Apache License 2.0 |

### 5. AndroidX

| 项目 | 说明 |
|------|------|
| 主页 | https://developer.android.com/jetpack |
| 作者 | AOSP |
| 用途 | core-ktx / appcompat / constraintlayout / recyclerview / lifecycle / coordinatorlayout |
| 协议 | Apache License 2.0 |

### 6. Material Components for Android

| 项目 | 说明 |
|------|------|
| 主页 | https://github.com/material-components/material-components-android |
| 作者 | Google |
| 用途 | Material Design 组件库，提供卡片、按钮、对话框等 UI 组件 |
| 协议 | Apache License 2.0 |

### 7. Kotlin 标准库

| 项目 | 说明 |
|------|------|
| 主页 | https://kotlinlang.org/ |
| 作者 | JetBrains |
| 用途 | Kotlin 编程语言与标准库 |
| 协议 | Apache License 2.0 |

### 8. JUnit

| 项目 | 说明 |
|------|------|
| 主页 | https://junit.org/ |
| 作者 | JUnit Team |
| 用途 | 单元测试框架（testImplementation） |
| 协议 | Eclipse Public License 2.0 |

---

## 二、移植与借鉴（源码移植）

### 9. ApkDataMultiplexing

| 项目 | 说明 |
|------|------|
| 主页 | https://github.com/L-JINBIN/ApkDataMultiplexing |
| 作者 | L-JINBIN |
| 用途 | APK 数据复用优化核心算法，本项目移植了其 DataMultiplexing / ZipMaker / ZipFile / V2V3SchemeSigner 实现 |
| 协议 | 原项目未标注许可证，仅供学习参考 |

> 说明：原项目未附带明确的开源许可证。本项目仅将其作为技术学习参考并移植实现思路，未直接复制其二进制产物。如原作者认为存在侵权，请联系我们处理。

### 10. AAPT2

| 项目 | 说明 |
|------|------|
| 主页 | https://developer.android.com/tools/aapt2 |
| 作者 | AOSP |
| 用途 | Android 资源编译与打包工具，用于 AXML / 资源处理 |
| 协议 | Apache License 2.0 |

---

## 三、参考来源（仅作特征与思路参考）

### 11. DTL-X

| 项目 | 说明 |
|------|------|
| 主页 | https://github.com/Gameye98/DTL-X |
| 作者 | Gameye98 |
| 用途 | 广告类名 / 方法名 / URL 特征规则参考来源，仅供特征参考与学习，未修改其二进制 |
| 协议 | 原项目未标注许可证，仅供学习参考 |

### 12. ApkSignatureKillerEx

| 项目 | 说明 |
|------|------|
| 主页 | https://github.com/L-JINBIN/ApkSignatureKillerEx |
| 作者 | L-JINBIN（林锦斌） |
| 用途 | 过签核心参考：`bin.mt.signature.KillerApplication` 钩子类（含 12 个内部类）与 `libSignatureKiIIer.so` 原生库的来源项目，演示 MT 去除签名校验原理及其对抗方式 |
| 协议 | 原项目未标注许可证，仅供学习参考 |

> 说明：本项目过签能力直接参考其 KillerApplication 注入过签实现（反射替换 `PackageInfo.CREATOR` 回填原包签名），最终实现均为本项目基于 dexlib2 自研，未直接复制其二进制产物。如原作者认为存在侵权，请联系我们处理。

### 13. LSPatch / LSP 技术团队

| 项目 | 说明 |
|------|------|
| 主页 | https://github.com/LSPosed/LSPatch |
| 作者 | LSPosed 团队 |
| 用途 | 过签包结构（assets/base.apk 内置原包 + IO 重定向）思路参考 |
| 协议 | 仅供技术思路参考 |

---

## 四、许可证全文

### Apache License 2.0

> 允许自由使用、修改、分发（含商业用途），需保留版权声明与许可文本；对修改后的文件需显著标注变更；如涉及专利声明需在 NOTICE 中说明。
>
> 完整文本：https://www.apache.org/licenses/LICENSE-2.0

### BSD 3-Clause License

> 允许自由使用、修改、分发（含商业用途），需保留版权声明、条件列表与免责声明；禁止使用作者名义进行推广。
>
> 完整文本：https://opensource.org/licenses/BSD-3-Clause

### MIT License

> 允许自由使用、修改、分发（含商业用途），需保留版权声明与许可文本；按"现状"提供，不附带任何担保。
>
> 完整文本：https://opensource.org/licenses/MIT

### Eclipse Public License 2.0

> 允许自由使用、修改、分发，修改后的代码需在相同协议下开源；提供专利授权。
>
> 完整文本：https://www.eclipse.org/legal/epl-2.0/

---

## 五、致谢

衷心感谢以上所有开源项目及其作者，正是他们的卓越工作让本项目成为可能。本项目对上述项目的使用均遵循其原始许可条款，如对使用方式有任何疑问，欢迎通过 Issue 与我们联系。

---

© 2026 sillycat · 本文件随项目以 MIT License 分发
