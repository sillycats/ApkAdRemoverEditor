---
layout: default
title: ApkAdRemoverEditor
---

<div class="content-body" data-lang-body="zh">

<h2 id="download-install">📥 下载安装</h2>
<p>前往 <a href="https://github.com/sillycats/ApkAdRemoverEditor/releases">Releases</a> 页面下载最新 APK，或从下方任一渠道获取：</p>
<ul>
<li><strong>项目主页</strong>：https://sillycats.github.io/ApkAdRemoverEditor/</li>
<li><strong>GitHub Releases</strong>：https://github.com/sillycats/ApkAdRemoverEditor/releases</li>
<li><strong>自动构建</strong>：每次代码提交由 GitHub Actions 自动编译，产物可在 CI 构建记录中下载</li>
<li><strong>社区讨论</strong>：https://github.com/sillycats/ApkAdRemoverEditor/discussions</li>
</ul>
<blockquote>
<p>安装时如提示"未知来源"，请在系统设置中允许安装来自此来源的应用。</p>
</blockquote>
<hr />
<h2 id="about">📖 项目简介</h2>
<p>一键完成 <strong>解包 → 去广告 → 过签 → 体积优化 → 打包 → 签名</strong> 全流程，全程本地离线处理，无需网络。</p>
<p>摒弃传统 smali 反汇编-回汇编流程，采用<strong>字节码直接修补</strong>方案，在保证处理精度的同时将处理速度提升数倍。</p>
<h2 id="features">✨ 功能特性</h2>
<h3 id="signature-bypass-new-in-v30">🛡 过签能力（v3.0 新增）</h3>
<ul>
<li><strong>MT 管理器式 KillerApplication 注入过签</strong>：注入含 12 个内部类的钩子类为独立 DEX，改写目标应用 Application 父类，运行时反射替换 <code>PackageInfo.CREATOR</code> 回填原包签名，覆盖绝大多数 Java 层签名校验</li>
<li><strong>双模式过签</strong>：普通去除（仅注入钩子覆盖 Java 层校验）/ 原包去除（增强，额外嵌入 <code>origin.apk</code> + 原生库，覆盖磁盘 APK 重新读取签名的原生层校验）</li>
<li><strong>SO 智能注入</strong>：自动识别目标 APK 的 ABI 架构，仅写入已存在的目录（arm64-v8a / armeabi-v7a 等）；无 lib 目录则全架构写入（含 x86 / x86_64）</li>
<li><strong>钩子类名可自定义</strong>：KillerApplication 名称自由修改，内置 12 个内部类基于 dexlib2 自动同步重命名，避免特征被检测</li>
<li><strong>注入参数全面可自定义</strong>：原包路径 / 解压路径 / So库名 / 钩子类名 / 签名信息 / 入口名称，留空自动读取真实值</li>
<li><strong>不触碰业务方法</strong>：相比"置空校验方法"方案，不依赖脆弱的关键词/指纹臆测，不产生字节码置空导致的启动卡死，过签强度与 MT 管理器一致</li>
<li><strong>崩溃防护</strong>：钩子 DEX 注入与 Application 父类改写均采用备份 + 原子写回 + 异常自动恢复</li>
</ul>
<h3 id="ad-removal-core">🛡 去广告核心</h3>
<ul>
<li><strong>18 类广告特征全覆盖</strong>：SDK 库 / 权限 / 类 / 方法 / 资源 / URL / View / Activity / Service / Receiver，可自定义增删改查与一键重置</li>
<li><strong>AXML 深度处理</strong>：移除广告组件、广告权限声明、隐藏 Res 布局广告 View</li>
<li><strong>方法级精准处理</strong>：广告类方法置空、广告链接置空、View 几何置空</li>
<li><strong>会员功能解锁</strong>：强制返回 true/false，解密会员功能并屏蔽广告判定</li>
<li><strong>广告资源清理</strong>：自动清理广告 SDK 原生库 (.so)、assets 广告资源与根目录广告文件</li>
<li><strong>Flutter 应用适配</strong>：解析 Dart AOT 快照，抹除 libapp.so 中的广告字符串特征</li>
</ul>
<h3 id="size-optimization">📦 体积优化</h3>
<ul>
<li><strong>数据复用优化</strong>：过签包（如 LSPatch 产物）复用原包数据段，最多减小约 50% 体积</li>
<li><strong>重命名识别</strong>：原包被重命名为无后缀或任意后缀也能准确识别并优化</li>
<li><strong>DEX 体积优化</strong>：移除调试信息（行号/局部变量表），再减小 5%~15% 体积</li>
<li><strong>智能压缩策略 + ZIP 对齐</strong>：保证打包后 APK 正常安装启动</li>
</ul>
<h3 id="stability-security">🔒 稳定性与安全</h3>
<ul>
<li><strong>v1 + v2 双签名</strong>，兼容低版本设备，处理结果可直接安装</li>
<li><strong>DEX 崩溃防护</strong>：备份保护 + 原子写入 + 异常自动恢复</li>
<li><strong>路径穿越防护</strong>：修复 Zip Slip 漏洞，杜绝恶意 APK 越界写文件</li>
<li><strong>低内存扫描</strong>：超大 DEX 也能稳定处理不卡死</li>
</ul>
<h3 id="experience">🎨 使用体验</h3>
<ul>
<li><strong>内置 17 种语言切换</strong>（v3.4 新增）：简体中文 / 繁體中文 / English / 日本語 / 한국어 / Español / Français / Deutsch / Italiano / Português / Русский / हिन्दी / Tiếng Việt / ไทย / Bahasa Indonesia / العربية / Türkçe，可"跟随系统"或在应用内一键切换并即时生效</li>
<li>实时彩色处理日志，支持一键复制 / 清空</li>
<li>自动生成 Markdown 处理报告</li>
<li>广告特征订阅导入与分享</li>
<li>明暗双主题（跟随系统 / 白天 / 夜间）</li>
<li>长文本自动换行完整显示，杜绝省略号截断（涵盖俄语等长单词语言）</li>
</ul>
<h2 id="preview">📸 界面预览</h2>
<table class="preview-table">
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

<h2 id="quick-start">🚀 快速上手</h2>
<h3 id="build">编译</h3>
<pre><code class="language-bash"># 环境要求：Android Studio / JDK 17+ / SDK 34
./gradlew assembleRelease
</code></pre>
<p>产物位于 <code>app/build/outputs/apk/release/</code>，可直接安装使用。</p>
<blockquote>
<p>本项目已配置 <a href=".github/workflows/build.yml">GitHub Actions</a> 自动构建：每次 push / PR 自动编译 Debug 与 Release 包；打 <code>v*</code> 标签时自动发布 Release 并附带 APK 产物。</p>
<p>发布说明由 <a href=".github/release-drafter.yml">release-drafter</a> 自动生成：会根据 PR 标题 / 分支 / 改动文件自动打标签（<code>feature</code> / <code>bug</code> / <code>docs</code> / <code>ci</code> 等），发布时自动按分类汇总为结构化更新日志。</p>
</blockquote>
<h3 id="usage">使用</h3>
<ol>
<li>选择需要处理的 APK 文件（支持重命名后的 APK）</li>
<li>点击"开始处理"，自动完成解包、去广告、体积优化、打包、签名</li>
<li>处理完成自动导出到原包目录，并生成 Markdown 处理报告</li>
</ol>
<h2 id="whats-new-v34">🆕 版本更新（v3.4）</h2>
<ul>
<li><strong>多国语言切换</strong>：支持 17 种语言，可"跟随系统"或应用内一键切换即时生效</li>
<li><strong>界面全量外生化</strong>：所有布局、菜单、弹窗、日志文案统一为字符串资源，各语言资源严格对齐，长文本自动换行完整显示</li>
<li><strong>修复导出路径显示</strong>：导出完成日志不再显示 <code>已导出: %1$s</code> 占位符，改为真实路径</li>
<li><strong>修复多语言去广告</strong>：补齐字符串占位符实参，杜绝切换语言后无法去广告处理 APK 的 <code>MissingFormatArgumentException</code></li>
</ul>
<p>完整的版本记录请参见 <a href="CHANGELOG.md">CHANGELOG</a>。</p>
<h2 id="architecture">🏗 技术架构</h2>
<table>
<thead>
<tr>
<th>模块</th>
<th>技术方案</th>
<th>核心说明</th>
</tr>
</thead>
<tbody>
<tr>
<td>DEX 修补引擎</td>
<td>dexlib2 2.5.2</td>
<td>直接修改字节码，无需反汇编/回汇编</td>
</tr>
<tr>
<td>签名效验去除</td>
<td>KillerApplication 注入</td>
<td>MT 式钩子注入 + Application 父类改写 + 反射回填原包签名</td>
</tr>
<tr>
<td>AXML 处理器</td>
<td>自研解析器</td>
<td>解析 AXML chunk 结构，移除广告组件与权限</td>
</tr>
<tr>
<td>数据复用优化</td>
<td>ApkDataMultiplexing</td>
<td>中央目录偏移复用原包数据段</td>
</tr>
<tr>
<td>签名模块</td>
<td>apksig + V2V3SchemeSigner</td>
<td>v1/v2 双签名，优化后专用签名</td>
</tr>
<tr>
<td>Flutter 适配</td>
<td>Dart AOT 快照解析</td>
<td>抹除 libapp.so 广告字符串特征</td>
</tr>
<tr>
<td>打包模块</td>
<td>自研 ZIP 引擎</td>
<td>智能压缩 + 对齐 + 崩溃防护</td>
</tr>
</tbody>
</table>
<h2 id="open-source-credits">⚖️ 开源致谢</h2>
<p>本应用基于以下开源项目构建，在此向作者表示诚挚感谢：</p>
<table>
<thead>
<tr>
<th>项目</th>
<th>作者/组织</th>
<th>用途</th>
<th>协议</th>
</tr>
</thead>
<tbody>
<tr>
<td><a href="https://github.com/L-JINBIN/ApkDataMultiplexing">ApkDataMultiplexing</a></td>
<td>L-JINBIN</td>
<td>数据复用优化核心算法</td>
<td>未标注</td>
</tr>
<tr>
<td><a href="https://github.com/JesusFreke/smali">dexlib2 / smali</a></td>
<td>JesusFreke</td>
<td>DEX 文件读写与 smali 工具链</td>
<td>BSD 3-Clause</td>
</tr>
<tr>
<td><a href="https://android.googlesource.com/platform/tools/apksig">apksig</a></td>
<td>AOSP</td>
<td>APK 签名实现</td>
<td>Apache 2.0</td>
</tr>
<tr>
<td><a href="https://www.bouncycastle.org/">BouncyCastle</a></td>
<td>bcprov/bcpkix</td>
<td>加解密与证书生成</td>
<td>MIT</td>
</tr>
<tr>
<td><a href="https://github.com/google/guava">Guava</a></td>
<td>Google</td>
<td>集合与工具库</td>
<td>Apache 2.0</td>
</tr>
<tr>
<td><a href="https://developer.android.com/jetpack">AndroidX</a></td>
<td>AOSP</td>
<td>Jetpack 支持库</td>
<td>Apache 2.0</td>
</tr>
<tr>
<td><a href="https://github.com/material-components/material-components-android">Material Components</a></td>
<td>Google</td>
<td>Material 组件</td>
<td>Apache 2.0</td>
</tr>
<tr>
<td><a href="https://github.com/Gameye98/DTL-X">DTL-X</a></td>
<td>Gameye98</td>
<td>广告特征规则参考</td>
<td>仅供学习</td>
</tr>
<tr>
<td><a href="https://github.com/L-JINBIN/ApkSignatureKillerEx">ApkSignatureKillerEx</a></td>
<td>L-JINBIN</td>
<td>过签核心参考</td>
<td>仅供学习</td>
</tr>
<tr>
<td><a href="https://github.com/LSPosed/LSPatch">LSPatch</a></td>
<td>LSPosed</td>
<td>过签包结构思路参考</td>
<td>仅供学习</td>
</tr>
</tbody>
</table>
<p>详细的开源项目与参考代码出处，请参阅 <a href="开源声明.md">开源声明.md</a>；完整的第三方许可信息，请参阅 <a href="THIRD_PARTY_NOTICES.md">THIRD_PARTY_NOTICES.md</a>。</p>
<h2 id="contributing">🤝 参与贡献</h2>
<p>欢迎任何形式的贡献！无论是报告 Bug、提出建议还是提交代码：</p>
<ul>
<li><strong>报告问题 / 提建议</strong>：使用 <a href=".github/ISSUE_TEMPLATE/bug_report.md">Issue 模板</a> 提交</li>
<li><strong>提交代码</strong>：Fork 后提交 <a href=".github/PULL_REQUEST_TEMPLATE.md">Pull Request</a>，请先阅读 <a href="CONTRIBUTING.md">贡献指南</a></li>
<li><strong>社区交流</strong>：前往 <a href="https://github.com/sillycats/ApkAdRemoverEditor/discussions">Discussions</a> 讨论</li>
<li><strong>安全漏洞</strong>：请通过 <a href="SECURITY.md">安全政策</a> 描述的私有渠道报告</li>
</ul>
<p>参与本项目即表示你同意遵守 <a href="CODE_OF_CONDUCT.md">行为准则</a>。</p>
<h2 id="copyright-license">📄 版权与许可证</h2>
<ul>
<li><strong>版权声明</strong>：本软件及相关文档版权归 <strong>© 2026 sillycats</strong> 所有，保留所有权利</li>
<li><strong>开源协议</strong>：本项目基于 <a href="LICENSE">MIT License</a> 开源，您可自由使用、修改、分发本项目，但需保留原始版权声明与许可文本</li>
<li><strong>使用限制</strong>：不得使用本项目作者名义进行推广；不得对项目进行歪曲、误导性描述</li>
<li><strong>无担保</strong>：本项目按"现状"提供，不附带任何形式的明示或默示担保</li>
</ul>
<blockquote>
<p>⚠️ 本工具仅供学习、研究与个人合法用途使用。请勿对您不拥有版权、未获授权或受法律保护的应用进行修改与分发，由此产生的法律责任由使用者自担。</p>
</blockquote>
<hr />
<div align="center">

**© 2026 sillycats · 本项目基于 [MIT License](LICENSE) 开源**  
Powered by dexlib2 · apksig · Kotlin · Material Components

</div>

</div>