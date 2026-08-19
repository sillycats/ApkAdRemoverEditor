# 贡献指南 (Contributing)

感谢你对 **APK去广告编辑器（ApkAdRemoverEditor）** 的关注与支持！本指南将帮助你了解如何参与本项目。

## 目录

- [开发环境](#开发环境)
- [如何报告问题](#如何报告问题)
- [如何提交代码](#如何提交代码)
- [代码规范](#代码规范)
- [提交信息规范](#提交信息规范)
- [行为准则](#行为准则)

## 开发环境

```text
- Android Studio（建议最新稳定版）
- JDK 17+
- compileSdk 34 / minSdk 24 / targetSdk 34
- Gradle 8.5（由 gradle wrapper 自动管理）
```

克隆并构建：

```bash
git clone https://github.com/sillycats/ApkAdRemoverEditor.git
cd ApkAdRemoverEditor
./gradlew assembleDebug   # 编译 Debug 包
./gradlew assembleRelease # 编译 Release 包
```

## 如何报告问题

提交 Issue 前，请先：

1. 搜索是否已有相同或类似的问题，避免重复提交
2. 使用提供的 [Issue 模板](.github/ISSUE_TEMPLATE/) 提交，包含以下关键信息：
   - 设备型号与 Android 系统版本
   - 应用版本号（设置 → 关于 中查看）
   - 复现步骤（越详细越好）
   - 期望行为与实际行为的差异
   - 相关日志或截图

## 如何提交代码

1. **Fork** 本仓库到你的账号
2. 创建功能分支：`git checkout -b feature/你的功能描述`
3. 提交你的修改（遵循下方提交信息规范）
4. 推送到你的分支：`git push origin feature/你的功能描述`
5. 提交 **Pull Request**，使用 [PR 模板](.github/PULL_REQUEST_TEMPLATE.md) 描述改动内容

### 分支命名建议

| 场景 | 分支前缀 | 示例 |
|------|----------|------|
| 新功能 | `feature/` | `feature/ad-filter-optimize` |
| Bug 修复 | `fix/` | `fix/dialog-overflow` |
| 文档 | `docs/` | `docs/update-readme` |
| 重构 | `refactor/` | `refactor/log-system` |
| 性能优化 | `perf/` | `perf/faster-packing` |

## 代码规范

- **语言**：Kotlin 优先，Java 用于移植的底层工具类
- **命名**：遵循 Android 官方 Kotlin 代码风格（类名大驼峰、函数/变量小驼峰、常量全大写）
- **注释**：核心算法与复杂逻辑必须添加中文注释说明原理
- **兼容性**：新增代码需兼容 minSdk 24（Android 7.0），避免使用高版本专属 API（如需使用请做版本判断）
- **依赖**：新增依赖需评估体积与许可协议，并在 `THIRD_PARTY_NOTICES.md` 中登记
- **自测**：提交前请确保 `./gradlew assembleDebug` 编译通过，涉及核心处理逻辑的改动需自测处理一个真实 APK

## 提交信息规范

遵循 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/) 规范：

```text
<type>(<scope>): <subject>

<body>
```

| type | 说明 |
|------|------|
| `feat` | 新功能 |
| `fix` | Bug 修复 |
| `docs` | 文档变更 |
| `style` | 代码风格调整（不影响逻辑） |
| `refactor` | 重构（不影响功能） |
| `perf` | 性能优化 |
| `test` | 测试相关 |
| `chore` | 构建/工具/依赖等杂项 |

示例：

```text
fix(dialog): 修复自定义注入参数弹窗输入框字符重叠问题

- 移除 EditText 重复的 hint 属性，仅保留 TextInputLayout 提示
- 弹窗内容改为 ScrollView 包裹，防止溢出屏幕
```

## 行为准则

参与本项目即表示你同意遵守 [行为准则](CODE_OF_CONDUCT.md)。请保持友善、专业、尊重他人的交流氛围。

---

再次感谢你的贡献！你的每一份努力都会让这个项目变得更好。
