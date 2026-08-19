<div align="center">

# APK去广告编辑器（ApkAdRemoverEditor）

**一款面向逆向爱好者的专业级 APK 去广告 + 过签工具**

基于 dexlib2 构建 · 字节码直接修补 · MT 式过签 · 数据复用优化 · 全自动流水线

<br>

[![CI](https://github.com/sillycats/ApkAdRemoverEditor/actions/workflows/build.yml/badge.svg)](https://github.com/sillycats/ApkAdRemoverEditor/actions/workflows/build.yml)

**Android 7.0+ · Kotlin + Java · v3.0 · MIT License**

</div>

---

## 📥 下载安装

前往 [Releases](https://github.com/sillycats/ApkAdRemoverEditor/releases) 页面下载最新 APK，或从下方任一渠道获取：

- **项目主页**：https://sillycats.github.io/ApkAdRemoverEditor/
- **GitHub Releases**：https://github.com/sillycats/ApkAdRemoverEditor/releases
- **自动构建**：每次代码提交由 GitHub Actions 自动编译，产物可在 CI 构建记录中下载
- **社区讨论**：https://github.com/sillycats/ApkAdRemoverEditor/discussions

> 安装时如提示"未知来源"，请在系统设置中允许安装来自此来源的应用。

---

## 📖 项目简介

一键完成 **解包 → 去广告 → 过签 → 体积优化 → 打包 → 签名** 全流程，全程本地离线处理，无需网络。

摒弃传统 smali 反汇编-回汇编流程，采用**字节码直接修补**方案，在保证处理精度的同时将处理速度提升数倍。

## ✨ 功能特性

### 🛡 过签能力（v3.0 新增）

- **MT 管理器式 KillerApplication 注入过签**：注入含 12 个内部类的钩子类为独立 DEX，改写目标应用 Application 父类，运行时反射替换 `PackageInfo.CREATOR` 回填原包签名，覆盖绝大多数 Java 层签名校验
- **双模式过签**：普通去除（仅注入钩子覆盖 Java 层校验）/ 原包去除（增强，额外嵌入 `origin.apk` + 原生库，覆盖磁盘 APK 重新读取签名的原生层校验）
- **SO 智能注入**：自动识别目标 APK 的 ABI 架构，仅写入已存在的目录（arm64-v8a / armeabi-v7a 等）；无 lib 目录则全架构写入（含 x86 / x86_64）
- **钩子类名可自定义**：KillerApplication 名称自由修改，内置 12 个内部类基于 dexlib2 自动同步重命名，避免特征被检测
- **注入参数全面可自定义**：原包路径 / 解压路径 / So库名 / 钩子类名 / 签名信息 / 入口名称，留空自动读取真实值
- **不触碰业务方法**：相比"置空校验方法"方案，不依赖脆弱的关键词/指纹臆测，不产生字节码置空导致的启动卡死，过签强度与 MT 管理器一致
- **崩溃防护**：钩子 DEX 注入与 Application 父类改写均采用备份 + 原子写回 + 异常自动恢复

### 🛡 去广告核心

- **18 类广告特征全覆盖**：SDK 库 / 权限 / 类 / 方法 / 资源 / URL / View / Activity / Service / Receiver，可自定义增删改查与一键重置
- **AXML 深度处理**：移除广告组件、广告权限声明、隐藏 Res 布局广告 View
- **方法级精准处理**：广告类方法置空、广告链接置空、View 几何置空
- **会员功能解锁**：强制返回 true/false，解密会员功能并屏蔽广告判定
- **广告资源清理**：自动清理广告 SDK 原生库 (.so)、assets 广告资源与根目录广告文件
- **Flutter 应用适配**：解析 Dart AOT 快照，抹除 libapp.so 中的广告字符串特征

### 📦 体积优化

- **数据复用优化**：过签包（如 LSPatch 产物）复用原包数据段，最多减小约 50% 体积
- **重命名识别**：原包被重命名为无后缀或任意后缀也能准确识别并优化
- **DEX 体积优化**：移除调试信息（行号/局部变量表），再减小 5%~15% 体积
- **智能压缩策略 + ZIP 对齐**：保证打包后 APK 正常安装启动

### 🔒 稳定性与安全

- **v1 + v2 双签名**，兼容低版本设备，处理结果可直接安装
- **DEX 崩溃防护**：备份保护 + 原子写入 + 异常自动恢复
- **路径穿越防护**：修复 Zip Slip 漏洞，杜绝恶意 APK 越界写文件
- **低内存扫描**：超大 DEX 也能稳定处理不卡死

### 🎨 使用体验

- 实时彩色处理日志，支持一键复制 / 清空
- 自动生成 Markdown 处理报告
- 广告特征订阅导入与分享
- 明暗双主题（跟随系统 / 白天 / 夜间）

## 📸 界面预览

<table>
  <tr>
    <td align="center" width="50%">
      <b>主界面 · 一键处理</b><br>
      <img src="screenshots/main.jpg" alt="主界面" width="320">
    </td>
    <td align="center" width="50%">
      <b>关于 · 版本信息</b><br>
      <img src="screenshots/features.jpg" alt="关于页面" width="320">
    </td>
  </tr>
</table>

## 🚀 快速上手

### 编译

```bash
# 环境要求：Android Studio / JDK 17+ / SDK 34
./gradlew assembleRelease
```

产物位于 `app/build/outputs/apk/release/`，可直接安装使用。

> 本项目已配置 [GitHub Actions](.github/workflows/build.yml) 自动构建：每次 push / PR 自动编译 Debug 与 Release 包；打 `v*` 标签时自动发布 Release 并附带 APK 产物。
>
> 发布说明由 [release-drafter](.github/release-drafter.yml) 自动生成：为 PR 打上 `feature` / `bug` / `docs` / `ci` 等标签，发布时会自动按分类汇总为结构化更新日志。

### 使用

1. 选择需要处理的 APK 文件（支持重命名后的 APK）
2. 点击"开始处理"，自动完成解包、去广告、体积优化、打包、签名
3. 处理完成自动导出到原包目录，并生成 Markdown 处理报告

## 🏗 技术架构

| 模块 | 技术方案 | 核心说明 |
|------|----------|----------|
| DEX 修补引擎 | dexlib2 2.5.2 | 直接修改字节码，无需反汇编/回汇编 |
| 签名效验去除 | KillerApplication 注入 | MT 式钩子注入 + Application 父类改写 + 反射回填原包签名 |
| AXML 处理器 | 自研解析器 | 解析 AXML chunk 结构，移除广告组件与权限 |
| 数据复用优化 | ApkDataMultiplexing | 中央目录偏移复用原包数据段 |
| 签名模块 | apksig + V2V3SchemeSigner | v1/v2 双签名，优化后专用签名 |
| Flutter 适配 | Dart AOT 快照解析 | 抹除 libapp.so 广告字符串特征 |
| 打包模块 | 自研 ZIP 引擎 | 智能压缩 + 对齐 + 崩溃防护 |

## ⚖️ 开源致谢

本应用基于以下开源项目构建，在此向作者表示诚挚感谢：

| 项目 | 作者/组织 | 用途 | 协议 |
|------|-----------|------|------|
| [ApkDataMultiplexing](https://github.com/L-JINBIN/ApkDataMultiplexing) | L-JINBIN | 数据复用优化核心算法 | 未标注 |
| [dexlib2 / smali](https://github.com/JesusFreke/smali) | JesusFreke | DEX 文件读写与 smali 工具链 | BSD 3-Clause |
| [apksig](https://android.googlesource.com/platform/tools/apksig) | AOSP | APK 签名实现 | Apache 2.0 |
| [BouncyCastle](https://www.bouncycastle.org/) | bcprov/bcpkix | 加解密与证书生成 | MIT |
| [Guava](https://github.com/google/guava) | Google | 集合与工具库 | Apache 2.0 |
| [AndroidX](https://developer.android.com/jetpack) | AOSP | Jetpack 支持库 | Apache 2.0 |
| [Material Components](https://github.com/material-components/material-components-android) | Google | Material 组件 | Apache 2.0 |
| [DTL-X](https://github.com/Gameye98/DTL-X) | Gameye98 | 广告特征规则参考 | 仅供学习 |
| [ApkSignatureKillerEx](https://github.com/L-JINBIN/ApkSignatureKillerEx) | L-JINBIN | 过签核心参考：KillerApplication 钩子类与 libSignatureKiIIer.so 原生库来源 | 仅供学习 |
| [LSPatch](https://github.com/LSPosed/LSPatch) | LSPosed 团队 | 过签包结构思路参考 | 仅供学习 |

详细的开源项目与参考代码出处，请参阅 [`开源声明.md`](开源声明.md)；完整的第三方许可信息，请参阅 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。

## 🤝 参与贡献

欢迎任何形式的贡献！无论是报告 Bug、提出建议还是提交代码：

- **报告问题 / 提建议**：使用 [Issue 模板](.github/ISSUE_TEMPLATE/bug_report.md) 提交
- **提交代码**：Fork 后提交 [Pull Request](.github/PULL_REQUEST_TEMPLATE.md)，请先阅读 [贡献指南](CONTRIBUTING.md)
- **社区交流**：前往 [Discussions](https://github.com/sillycats/ApkAdRemoverEditor/discussions) 讨论
- **安全漏洞**：请通过 [安全政策](SECURITY.md) 描述的私有渠道报告

参与本项目即表示你同意遵守 [行为准则](CODE_OF_CONDUCT.md)。

## 📄 许可证

本项目基于 [MIT License](LICENSE) 开源，版权归 sillycat 所有。所使用第三方库的许可信息详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

> ⚠️ 本工具仅供学习、研究与个人合法用途使用。请勿对您不拥有版权、未获授权或受法律保护的应用进行修改与分发，由此产生的法律责任由使用者自担。