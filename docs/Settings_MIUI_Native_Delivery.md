# 小米原生耳机插件页：OPPO 适配交付记录

版本：OppoPods 1.6.0 / versionCode 19。首版记录于 1.5.2 / versionCode 18（2026-11-22），随 1.6.0 发布。

## 目标与范围

接入设置动态加载的小米耳机专属页，复用图二的首页和原生降噪控件。标准 `BluetoothDeviceDetailsFragment` 不再作为 Settings 的模块入口。

当前工作包括：原生插件加载、OPPO 设备身份与控制地址桥接、三路真实电量、关闭/通透/降噪模式、支持的智能降噪与手动档位、原生新版设置导航，以及热重载恢复。小米固件升级、Dolby 标识、通勤沉浸等无法直接映射到 OPPO 协议的功能不伪装为已支持。

## 运行样本与实际版本

| 样本 | 来源 | 信息 |
|---|---|---|
| 设置主 APK | 用户指定 Settings 17 | `com.android.settings`，SDK 37 |
| 暂存插件 | `files/bluetooth/plugins` 中的现存文件 | 1.87，14,047,506 bytes，仅作为前期研究样本 |
| **实际加载插件** | 系统 Qigsaw `1.5/java/1.85@1` | **1.85，15,619,774 bytes，compileSdk 35** |

实际加载样本 SHA-256：`e6c8e1da5d93b89235b1eca1071571170f2352aee083e96ae5f1cc725f7820e0`。

前期暂存文件不能替代实际加载版本。两个版本的语义方法基本一致，但内部资源数字 ID 已发生移动，因此接入层按目标插件的 `R$id` 字段名称查找，不固定使用某一个版本的数值。

## 实现位置

- [SettingsMiuiPluginHook.kt](../app/src/main/java/moe/chenxy/oppopods/hook/SettingsMiuiPluginHook.kt)：新版原生页路由、Qigsaw loader 观察、服务桥、状态接收、原生控件及生命周期。
- [MiuiHeadsetCodec.kt](../app/src/main/java/moe/chenxy/oppopods/pods/MiuiHeadsetCodec.kt)：插件电量编码、模式和实际支持档位的纯 Kotlin 转换。
- [MiuiHeadsetCodecTest.kt](../app/src/test/java/moe/chenxy/oppopods/pods/MiuiHeadsetCodecTest.kt)：新增 20 项协议转换测试。
- [HookEntry.kt](../app/src/main/java/moe/chenxy/oppopods/hook/HookEntry.kt)：Settings 使用新入口，热重载优先使用当前应用 ClassLoader。
- [HeadsetStateDispatcher.kt](../app/src/main/java/moe/chenxy/oppopods/hook/HeadsetStateDispatcher.kt)：热重载后主动恢复已连接耳机控制桥，直接从实际 ProfileService 查询设备。
- [RfcommController.kt](../app/src/main/java/moe/chenxy/oppopods/pods/RfcommController.kt)：沿用既有 OPPO 发包，验证目标地址并外发配置档和断连状态。

## 关键行为

### 真正的插件入口

```text
系统蓝牙设备设置按钮
  → OPPO 设备判定
  → MiuiHeadsetActivityPlugin（原生加载壳）
  → Qigsaw java split
  → plugin.settings.java.JavaActivity
  → 插件 MiuiHeadsetFragment
  → MiuiHeadsetForPluto
```

不复制系统插件资源，不将插件重签后作为普通 App 安装。模块只在实际插件 ClassLoader 可用后注册 Hook。

### 新版首页和更多设置

仅为模块自己的虚拟配置 ID 关联 Pluto 控件组合，不改真实小米机型。新版首页还必须启用两个语义标记：

- `FEATURE_SETTINGS_VERSION_V2`：让原生判断隐藏旧重命名/升级卡，并使用现代设置布局；另对虚拟 ID 的 `isSupportV2Version` 做针对性适配，避免原方法的 Context 强转问题。
- `FEATURE_MORE_SETTINGS`：让原生更多设置进入 `MiuiHeadsetMoreSettings`，否则系统会分流到小爱。

`FEATURE_MAIN_HOME_PAGE_RECONSTRUCTION` 单独开启不足以替代这两个条件。这是首轮装机中“页面仍旧、更多跳小爱”的修复依据。

### 电量与命令

- 电量使用实际左右耳/盒状态。插件编码为低 7 位百分数、高位充电，255 表示未知。
- 模式映射：插件 0/1/2（关闭/降噪/通透）对应 OPPO 1/2/3。独立自适应回流为降噪＋智能状态。
- 原生 20 档滑杆按设备配置中真实存在的 LIGHT/MEDIUM/DEEP 命令就近映射，不创造 OPPO 不存在的协议档位。
- 智能开优先使用实际 SMART 命令，没有时按能力使用独立 ADAPTIVE；无对应能力则隐藏。
- 档位包本身已是 SET_ANC，不再同时异步发送另一主模式包与它竞争。
- 原生 UI 使用真实状态广播更新，不向设置自己发送虚构的成功回报。

### 热重载和设备地址

API 102 热重载不会重跑已存在的蓝牙服务 `onCreate`。旧逻辑会停止 RFCOMM 后再也收不到连接事件，因此本次主动扫描实际 A2DP/HFP/LE Audio 服务恢复会话。

目标 ClassLoader 也不能只取旧 hook handle 的首项：该项可能属于系统状态栏或 Instrumentation。优先使用当前应用 ClassLoader，才可重新解析蓝牙业务类。

LE 页面地址与经典 RFCOMM 地址可能不同。设置侧仅在唯一同名 LE 会话时建立受限映射，命令使用实际控制地址；不通过放宽所有设备地址检查解决串控。

## 本地检查

执行：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline --console=plain -I .workbuddy/settings17/lint-offline.gradle
```

临时 init 脚本仅跳过需要在线 Maven 元数据的 `NewerVersionAvailable`、`GradleDependency` 版本升级建议，保留代码、资源、权限和 API 检查；没有在项目中关闭 Lint。

本地构建与测试的阶段性结果：86 项测试通过，其中插件转换新增 20 项。最终构建和装机结果以交付前记录为准。

## 手机验证记录

| 项目 | 状态 |
|---|---|
| 保留数据覆盖安装 1.5.2 | 首版已成功，迭代修正版继续覆盖 |
| 实际 Activity 为 `plugin.settings.java.JavaActivity` | 已确认 |
| Qigsaw java 模块安装和装载 | 已确认，实际 1.85 插件 |
| 原生新版首页重组及正确更多设置导航 | 依据实机反馈修复后验证中 |
| 真实电量和连接状态自动恢复 | 验证中 |
| 降噪、通透、关闭及智能/手动档位往返 | 验证中 |
| 最终构建、测试及 Lint | 交付前更新 |

验证中发现的问题继续修复，不能把只打开页面或只显示控件记为功能完成。
