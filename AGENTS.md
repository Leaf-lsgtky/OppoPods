# Repository Guidelines

## 项目结构与模块组织

这是一个单模块的 Android/Xposed 项目（仅 `:app`），用于在 Xiaomi HyperOS 上为 OPPO 耳机提供控制、连接弹窗和系统面板适配。根构建配置位于 `settings.gradle.kts`、`build.gradle.kts`、`gradle.properties` 和 `gradle/libs.versions.toml`。

主要源码在 `app/src/main/java/moe/chenxy/oppopods/`：

- 根包：`MainActivity`、`PopupActivity` 与 `ConnectionPopupActivity`，分别承载主界面、耳机详情弹窗和连接弹窗。
- `hook/`：Xposed 入口与面向 `com.android.bluetooth`、`com.milink.service`、`com.xiaomi.bluetooth`、`com.android.settings` 的 Hook 适配。
- `pods/`：RFCOMM 连接、协议包解析、设备配置档、资源 Provider 和蓝牙日志。
- `ui/`：Compose/Miuix 页面、导航、主题、通用组件，以及 `effect/` 中的背景模糊效果。
- `utils/`：Focus Island、媒体控制、系统 API、偏好保存及 `miuiStrongToast/` 的跨进程通知数据与工具。

资源在 `app/src/main/res/`；Xposed 元数据在 `app/src/main/resources/META-INF/xposed/`。`scope.list` 当前包含四个 Hook 目标进程，新增 Hook 目标时必须同步更新它。协议与逆向记录置于 `docs/`。

## 构建、测试与开发命令

- Windows PowerShell：`.\gradlew.bat :app:assembleDebug`
- 发布构建：`.\gradlew.bat :app:assembleRelease`
- 静态检查：`.\gradlew.bat :app:lintDebug`
- 清理：`.\gradlew.bat clean`

项目使用 Java 22、AGP 9.1、Kotlin 2.3、Compose、Navigation 3、Miuix 和 LibXposed API 102。Release 启用 R8 和资源压缩；Debug 不执行 ProGuard/R8。GitHub Actions 在 `master`、`main`、`dev`、`v*` 标签及对应 PR 上用 Java 22 构建 release APK，并在提供签名密钥时签名和发布。

## 编码风格与约定

使用 Kotlin 与 Gradle Kotlin DSL，采用 4 空格缩进。类型、页面和 Composable 使用 `PascalCase`；函数、变量、preference key 使用 `camelCase`。仓库未配置 ktlint 或 detekt，至少应运行 debug 构建；改动资源、Manifest 或 API 用法时再运行 Lint。

Miuix 是默认 UI 工具包。Composable 放在 `ui/`，以 `AppTheme`/`MiuixTheme` 包裹；需要弹层时确保处于 Miuix `Scaffold` 环境。协议命令、字节解析和设备能力判断集中在 `pods/`，UI 与 Hook 层不得重复硬编码协议包。跨进程传递电池状态时复用 `utils/miuiStrongToast/data/BatteryStatusIntent.kt` 的兼容读写 helper，避免各进程维护不同的 extra 格式。

LibXposed 入口只保留 `HookEntry` 一个 Java entry，以支持 API 102 热重载。所有 Hook 必须通过 `HookContext` 注册，确保拥有稳定的 hook ID；不要直接调用 `module.hook()`。热重载前必须在 `onHotReloading()` 中停止线程/轮询并注销广播或其他外部回调，避免旧 module classloader 被目标进程保留。`module.prop` 的 `minApiVersion`、`targetApiVersion` 与 `autoHotReload` 必须保持为 `102`、`102`、`true`。

## 测试与设备验证

当前没有 `app/src/test/`、`app/src/androidTest/` 或测试框架依赖。提交前至少执行 `.\gradlew.bat :app:assembleDebug`。

修改 Hook、蓝牙 RFCOMM、跨进程广播、通知、连接弹窗、系统设置页或 Focus Island 时，还需在 HyperOS Android 15+ 的 LSPosed 设备上手动回归，并覆盖 `scope.list` 中所有受影响进程。不要把仅在目标系统上存在的反射失败当作普通应用逻辑错误；Hook 代码应保持兼容性保护与可诊断日志。

## 提交、PR 与安全

PR 需说明变更目的、验证命令和受影响的系统进程；UI/弹窗改动附截图，协议改动引用或更新 `docs/`。不要提交签名密钥、设备日志中的蓝牙地址，或私有抓包数据。本地签名读取 `KEYSTORE_FILE`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`；CI 使用 GitHub Secrets。新增权限、导出组件或 Hook 目标时，同时审查 `AndroidManifest.xml`、`scope.list` 和 README。
