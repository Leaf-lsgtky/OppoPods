# Repository Guidelines

## 项目结构与模块组织

本仓库是单模块 Android/Xposed 项目，是控制OPPO耳机的第三方应用，只包含 `:app` 模块。根 Gradle 配置位于 `settings.gradle.kts`、`build.gradle.kts`、`gradle.properties` 和 `gradle/libs.versions.toml`。主要源码在 `app/src/main/java/moe/chenxy/oppopods/`：

- `hook/`：Xposed 入口、Hook 工具和四个目标进程适配。
- `pods/`：OPPO 耳机 RFCOMM socket、协议包、连接和控制逻辑。
- `ui/`：Compose/Miuix 页面、主题与组件。
- `utils/`：Focus Island、媒体控制、系统 API 和跨进程广播数据类。

资源位于 `app/src/main/res/`；Xposed 元数据位于 `app/src/main/resources/META-INF/xposed/`，`scope.list` 固定包含 `com.android.bluetooth`、`com.milink.service`、`com.xiaomi.bluetooth`、`com.android.settings`。协议说明与逆向记录放在 `docs/`。

## 构建、测试与开发命令

- `./gradlew :app:assembleDebug`：构建调试 APK，适合本地安装验证。
- `./gradlew :app:assembleRelease`：构建启用 R8、资源压缩和 ProGuard 规则的发布 APK。
- `./gradlew :app:lintDebug`：运行 Android Lint，检查资源、Manifest 和 API 使用问题。
- `./gradlew clean`：清理根项目构建产物。

Windows PowerShell 下使用 `.\gradlew.bat :app:assembleDebug`。GitHub Actions 工作流 `.github/workflows/build.yml` 在 `master`、`dev` 和 `v*` 标签上运行 `./gradlew :app:assembleRelease`，并使用 Java 22。

## 编码风格与命名约定

使用 Kotlin 2.3、Gradle Kotlin DSL、Jetpack Compose、Navigation3 和 Miuix。保持 4 空格缩进，类型、页面和 Composable 使用 `PascalCase`，函数、变量和 preference key 使用 `camelCase`。仓库未配置 ktlint 或 detekt；提交前依赖 Gradle 编译与 Android Lint 兜底。Composable 放在 `ui/`，协议常量、命令字和字节解析集中维护在 `pods/`，不要在 UI 层硬编码协议包。

## 测试与验证指南

仓库没有 `app/src/test/` 或 `app/src/androidTest/`，也没有测试框架依赖。提交前运行 `./gradlew :app:assembleDebug`。涉及 Hook、蓝牙 RFCOMM、通知、连接弹窗或小米超级岛（一种Xiaomi HyperOS独有的，在安卓原生通知基础上添加额外参数构建的通知）的修改，需要在 HyperOS Android 15+ 设备上启用 LSPosed，并按 `scope.list` 的四个包完成手动回归。

## 提交与 Pull Request 规范

仓库没有 PR 模板；PR 描述需写明变更目的、验证命令、受影响的系统进程。涉及 UI 或弹窗时附截图，涉及协议时引用 `docs/` 或新增抓包说明。

## 安全与配置提示

不要提交签名密钥、设备日志中的蓝牙地址或私有抓包数据。本地发布签名使用 `KEYSTORE_FILE`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`；CI 发布签名使用 GitHub secrets。新增权限、导出组件或 Hook 目标时，同步检查 `AndroidManifest.xml`、`scope.list` 和 README。
