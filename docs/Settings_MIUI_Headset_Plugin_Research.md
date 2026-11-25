# 小米耳机专属页定位：图二所在的 java 动态插件

日期：2026-11-22。本文接续对 Settings 17 的研究，纠正“新版标准蓝牙详情页等于小米新耳机专属页”的判断。

## 1. 定位结论

**已经找到与用户图二特征吻合的小米插件页面实现。** 它属于设置动态加载的耳机插件，入口为 `plugin.settings.java.JavaActivity`，主 Fragment 为 `plugin.settings.java.MiuiHeadsetFragment`；图二最有辨识度的“智能降噪开关＋轻度/深度连续滑杆＋通勤沉浸”由 `plugin.settings.java.tws.anc.MiuiHeadsetForPluto` 实现。

上一轮实际接入的 `com.android.settings.bluetooth.BluetoothDeviceDetailsFragment` 是 AOSP 风格的标准蓝牙详情页。用户第一张截图中的三路圆环电量与分段按钮正是这条路径；它不等同于第二张截图中的小米专属耳机页。

本轮定位得到的结论是“目标实现在哪里、怎样进入、如何接数据”。**这不代表当前装机版本已经切换到该插件，或插件的全部功能已经完成 OPPO 适配。** 目前装机版本仍使用上一轮标准详情页路径；本轮没有把未接通控制链的插件预览作为完成版本再次安装。

## 2. 实际存在三条页面路径

| 路径 | 入口及实现 | 界面特征 | 本轮判断 |
|---|---|---|---|
| 标准详情页 | `BluetoothDeviceDetailsFragment` → `BluetoothDetailsConfigurableFragment` | 通用头部、三路电量环、原生分段按钮、通用蓝牙条目 | 上一轮实际接入，接近图一 |
| 设置内置耳机专属页 | `com.android.settings.bluetooth.MiuiHeadsetActivity` → 同包 `MiuiHeadsetFragment` | 大图、三路电量、圆形 ANC、旧分档控件 | 与图二部分相似，但不是完整目标 |
| 动态插件耳机专属页 | `MiuiHeadsetActivityPlugin` 加载壳 → `plugin.settings.java.JavaActivity` → 插件同包 `MiuiHeadsetFragment` | 按机型配置重组首页、电量和 ANC；包含智能降噪连续滑杆与通勤沉浸 | 图二对应的实现路径 |

内置专属页和插件专属页都存在 `MiuiHeadsetFragment` 等同名类。包名、ClassLoader 和资源归属不同，不能把二者的方法或资源 ID 混用。

## 3. 插件样本来自哪里

本次从手机现有设置数据目录只读取得耳机插件，没有下载或替换系统 APK。

```text
/data/user_de/0/com.android.settings/files/bluetooth/plugins/java-master.apk
```

分析副本保存在工作区忽略目录中的 [headset-java-master.apk](../.workbuddy/settings17/headset-java-master.apk)。它是研究样本，不是需要单独安装的普通 App。

| 项目 | 结果 |
|---|---|
| 文件大小 | 14,047,506 bytes |
| SHA-256 | `a28aaf3766927d6dc044b67e612dd3f61b5efaa7116e68ed4f657e50aee1c652` |
| Manifest package | `com.xiaomi.settings.headsetplugin` |
| versionName / versionCode | `1.87` / `1` |
| split | `java` |
| isFeatureSplit | `true` |
| minSdk / targetSdk / compileSdk | `24` / `33` / `33` |
| 业务入口 | `plugin.settings.java.JavaActivity` |

主设置 APK 预先声明了 `JavaActivity`，但主设置 APK 的 DEX 中没有 `plugin.settings.java.*` 的实际类定义。完整 assets 中也没有内嵌的插件 APK。仅分析用户最初提供的设置 APK，能够找到加载器，却看不到图二页面的全部实现。

设备上找到暂存插件，不等于已经验证当前进程成功加载了这个 split。本轮没有通过设备页面启动验证，把静态定位与运行结果分开记录。

## 4. 图二的对应证据

插件 `MiuiHeadsetFragment.onCreateView()`（13/7853）加载自己的 `res/layout/headsetlayout.xml`。其中直接包含耳机大图、左/右/盒电量区域、横排“通透/降噪/关闭”图标按钮，以及 Pluto 子布局的 include（`0x7f800071`）。`showLogoForAudio()`（13/7906）在 `FEATURE_DOLBY` 可用时显示 Dolby logo View（`0x7f80031e`）。这补齐了目标截图上半部到 ANC 区域的对应链；但 REDMI Buds 8 Pro 商品图究竟由哪个资源/云配置加载，本轮没有独立证明。

### 4.1 智能降噪开关、连续滑杆、轻度/深度

插件布局 `res/layout/anc_open_sub_level_pluto.xml`，资源 ID `0x800d0032`，在同一个布局中包含：

| 元素 | 插件资源或控件 |
|---|---|
| “智能降噪” | `string/anc_intelligent`，`0x80110030` |
| 智能开关 | `miuix.slidingwidget.widget.SlidingButton`，`0x7f800081` |
| 连续降噪滑杆 | `plugin.settings.java.ui.AncAdjustSeekBar`，`0x7f800078` |
| 左侧“轻度” | `string/anc_mild`，`0x80110031` |
| 右侧“深度” | `string/miheadset_ancDepth`，`0x801101d8` |

这组结构直接对应图二降噪卡的下半部分。相比“也存在降噪文案”，同一个布局中的控件组合提供了更明确的归属证据。

### 4.2 通勤沉浸

`MiuiHeadsetForPluto.addImmersionPreference()` 创建 `ImmersionDropDownPreference`，key 为 `immersion_preference`，放到 `immersion_category` 中，标题使用 `string/miheadset_immersion`（`0x8011024a`），中文为“通勤沉浸”。

其 `onCreatView()` 的初始化顺序为：

```text
updateTransparentView()
  → setAncOnClick()
  → initIntelligentAnc()
  → initAncSeekBar()
  → addImmersionPreference()
```

因此，“智能降噪开关、滑杆、通勤沉浸”确实来自同一套 ANC 实现，而不是来自几个互不关联页面的资源。

### 4.3 机型配置分流

插件通过 `InterfaceForDeviceFuncConfig.getInstance(deviceId)` 选择配置器，Pluto 分支返回 `PlutoFeatureConfigurator`。它的 `getHeadsetAnc(...)` 明确构造 `MiuiHeadsetForPluto`。

该配置器主页面能力包括：

```text
FEATURE_ANC
FEATURE_DOLBY
FEATURE_BATTERY_OPTIMIZATION
```

自定义功能包括：

```text
FEATURE_INTELLIGENCE_ANC
FEATURE_COMMUTER_IMMERSION
SUB_NOISE_CONTROL
FEATURE_MAIN_HOME_PAGE_RECONSTRUCTION
```

配置器的 `getHeadsetBattery()`（13/9975）直接创建 `MiuiHeadsetBatteryOptimization`，对应优化后的电量控件。结合上面的 ANC 初始化、首页重组和 Dolby 能力，可以把图二的控件组合定位到这一插件页面族。

插件的 `P76SFeatureConfigurator` 也会复用 `MiuiHeadsetForPluto`，因此该类不是仅凭名称就能断言与某个商品名一一对应。REDMI Buds 8 Pro 的标题本身也不是类名；截图商品名与内部代号的映射不能仅凭界面推断。

已核验插件 ID 集合：

| 内部配置 | 第一代 device ID | V2 device ID |
|---|---|---|
| Pluto | `01013700`–`01013706` | `0201010041`–`0201010047` |
| P76S | `01013E00` | `0201010078` |

这些是小米插件内部识别号，不是 OPPO productId。旧适配使用的 `01010901` 不会自动命中 Pluto/P76S 的新页面配置。

## 5. 从蓝牙列表进入插件的真实链路

主设置的 `MiuiBluetoothSettings.checkHeadsetSurpportMiuiFragment()` 向蓝牙服务查询：

```text
checkSupport(device)                           → mConfig
setCommonCommand(120, splitInfoVersion, device) → mPlugin
setCommonCommand(133, "SettingsOriginal", device) → requestedConfig
```

随后 `checkStartMiuiHeadset(cachedDevice)` 分流：

```text
mPlugin == "true" 或 airPodsType 非空
  且 mStatus == HEADSETPLUGIN_INITED
  且 mConfig 非空
    ├─ java 已在 getInstalledModules() 中
    │    → 显式启动 com.android.settings 包下的 plugin.settings.java.JavaActivity
    └─ 否则
         → action miui.bluetooth.action.HEADSET_SETTINGS_PLUGIN
         → MiuiHeadsetActivityPlugin
         → Qigsaw 安装/加载模块 java
         → JavaActivity
```

`MiuiHeadsetActivityPlugin` 是显示 loading 的加载壳，它不是图二的内容页。它解析 `MIUI_HEADSET_SUPPORT` 的逗号两段，取第一段为 device ID，并向真正的 Activity 转传：

```text
android.bluetooth.device.extra.DEVICE
MIUI_HEADSET_SUPPORT
COME_FROM
DEVICE_ID
bluetoothaddress
VIRTUAL_DEVICE_ADDRESS / VIRTUAL_DEVICE_NAME（相应入口时）
```

`JavaActivity.loadFragment()` 对普通耳机创建插件包中的 `MiuiHeadsetFragment`，参数为 `BT_Device` 与 `BT_Device_Support`。它还会按来路切换到查找、虚拟设备、空间音频、AirPods 或散热背夹等其他页，因此只拿到 Activity 名称还不够。

插件 Activity 实际从 `MIUI_HEADSET_SUPPORT` 第一段取得 device ID，不能假设额外传一个 `DEVICE_ID` 就能覆盖机型。`COME_FROM` 为空会结束任务；设备参数或蓝牙地址还须匹配可取得的配对设备，否则准备页面时也会结束。`RUNINPLUGIN` 在此样本中不是硬性启动门槛。

### 插件更新来源

主设置的 `MiuiUpdatePluginsReceiver` 接收 `SETTINGS_URLS`，从以下 Provider 读取内容并复制到 `getFilesDir()/bluetooth/plugins/`：

```text
content://com.android.bluetooth.ble.app.headset.provider/<源路径>
```

它选择 Qigsaw 配置后调用 `Qigsaw.updateSplits(...)`。主 APK 的 `SampleDownloader` 从上述暂存目录移动 APK 到 Qigsaw 目标目录；该方法本身不是远端 HTTP 下载器。本轮得到的本机 `java-master.apk` 路径与此接收链一致。

### 插件 ClassLoader 与资源装载

主设置 `SettingsApplication.attachBaseContext()` 仅在 `com.android.settings` 主进程初始化 Qigsaw，配置 `splitLoadMode(1)`、`verifySignature(true)`，随后安装 Qigsaw 并设置初始化标志。

后续 Hook 应在真实 split loader 可用后查找插件类。已核验的观察点为：

- `SplitDelegateClassloader.inject(ClassLoader, Context)`：创建代理 loader 并替换 Context 的实际 classloader。
- `SplitApplicationLoaders.addClassLoader(SplitDexClassLoader)`：记录实际加载器。
- `SplitApplicationLoaders.getValidClassLoader("java")`：取得模块名匹配且有效的 loader。

`JavaActivity.getResources()` 也调用 `SplitInstallHelper.loadResources(...)`。因此，直接在 Xposed 包加载初期查找 `plugin.settings.java.MiuiHeadsetFragment` 可能失败；直接启动一个尚未加载资源的插件 Activity 同样不是完整接入方案。基础类和插件内有重复的 Binder descriptor，必须以实际加载器解析出的 Class 对象为 Hook 目标。

## 6. 插件控制契约与 OPPO 接入差异

### 6.1 三种模式

小米接口的模式编码为：

| 小米原生模式 | 语义 | OppoPods 控制器模式 |
|---|---|---|
| 0 | 关闭 | 1 |
| 1 | 降噪 | 2 |
| 2 | 通透 | 3 |

必须依据语义翻译，不能沿用上一轮 AOSP 控件的按钮索引直接传给小米方法。

### 6.2 Pluto 智能降噪与滑杆

| 交互或状态 | 插件契约 |
|---|---|
| 智能降噪开关 | `setCommonCommand(132, "F2\|102\|01/00", device)` |
| 智能状态回报 | `refreshStatus` 的 type 以 `getcommonconfig102` 开头，data=`01` 表示开 |
| 连续降噪滑杆 | 索引 0–19 经 `getAncValueForLevel20()` 映射到 `0100`–`0113`，再调用 `changeAncLevel(String, device)` |
| 通勤沉浸切换 | `setCommonCommand(132, "F2\|103\|" + value, device)` |
| 通勤沉浸状态 | type 以 `getcommonconfig103` 开头，更新相应下拉值 |

这些是小米插件和小米蓝牙服务之间的控制协议，不是 OPPO 耳机的 RFCOMM 指令。OPPO 的“智能/自适应”与实际支持的降噪档位需要根据 [DeviceProfile.kt](../app/src/main/java/moe/chenxy/oppopods/pods/DeviceProfile.kt) 和 [CapabilityProfileFactory.kt](../app/src/main/java/moe/chenxy/oppopods/pods/CapabilityProfileFactory.kt) 做映射。尤其不能把 20 档滑杆无条件映射成 OPPO 的 20 个协议档位。

通勤沉浸属于小米耳机专有功能；找到并显示这个条目，不等于 OPPO 自动具备对应能力。实际接入时应只启用有协议映射的功能。

### 6.3 电量与状态回流

插件的通用 `MiuiHeadsetFragment.refreshStatus(address, data)` 对普通状态要求 `data.split(",", -1)` 恰好为 16 项。索引 0、1、2 是左右盒电量，索引 7 是四字符 ANC 状态，随后分别转交电量控件和 `mMiuiHeadsetAnc.refreshAnc()`。`getcommonconfig102/103` 等前缀状态走专门分支。

电量编码不是裸百分数：`255` 表示无效/未知，低 7 位（`value & 127`）表示数值，`value & 128` 表示充电。OPPO 的百分数、连接和充电布尔值必须先转换成这个格式。

`JavaActivity$MiuiHeadsetCallback.refreshStatus()`（13/6781）会切到 UI 线程分发给当前 Fragment，是可考虑复用的注入端。插件还检查 HFP/BleMma 连接状态及佩戴状态；仅向 View 写数值、却让原生判断仍认为连接失败，会再次把电量置为未知或把模式 UI 重置。模式操作的佩戴查询为 `setCommonCommand(102, "", device)`：返回 `1/2/3` 可通过普通佩戴检查，双耳检查只接受 `1`。

智能降噪启用时，插件会禁用手动滑杆。通勤沉浸的值为 `01=飞机、02=公共交通、03=高铁、00=关闭`。它们应与模式及设备能力一起同步，而不是孤立更新一项控件。

## 7. 正确的适配方向

如果继续让 OPPO 复用图二这条专属页，需要接入四层：

1. **入口和插件加载。** 对目标 OPPO 设备选择耳机插件入口，并让现有 Qigsaw 安装、资源和 ClassLoader 流程完成。不能只把 AOSP Fragment 名改成尚未加载的插件类。
2. **配置器和真实设备身份。** 让页面使用所需的首页/电量/ANC 控件组合，同时保持命令目标仍是实际 OPPO 地址。不能把整机全局伪装成 Redmi 后放行所有小米功能。
3. **数据桥。** 将 OPPO 电量、模式、佩戴和连接状态转换为插件的电量控件与 `refreshStatus`/ANC 回报格式；上一轮只适用于 `BatteryLevelsInfo` 和 Flow 的桥不能原样复用。
4. **命令桥和能力过滤。** 把插件模式、智能开关与支持的降噪档位翻译回现有 OPPO 命令，拦截不能支持的厂商功能，补齐断连及热重载释放。

本轮的核心改正是：**目标应研究动态耳机插件，而不是继续把 AOSP 标准详情页当作“小米新版耳机页”。** 旧内置专属页可以提供相似骨架，但仍缺少图二的完整组合。

## 8. 证据索引

联合分析输入顺序为主设置 APK、耳机插件；`file_id=4` 是主设置的 `classes2.dex`，`12/13` 为插件 `classes.dex/classes2.dex`。索引只对该输入组合有效。

| file_id/method_idx | 方法 | 结论 |
|---|---|---|
| 4/45269 | `MiuiBluetoothSettings.checkHeadsetSurpportMiuiFragment` | 远端 120/133 查询决定插件路由 |
| 4/45272 | `MiuiBluetoothSettings.checkStartMiuiHeadset` | 插件/旧页实际分流 |
| 4/45570 | `MiuiHeadsetActivityPlugin.onCreate` | loading 壳，安装 java |
| 4/45574 | `MiuiHeadsetActivityPlugin.onSuccessfullyLoad` | 转发参数到 JavaActivity |
| 4/47186 | `MiuiUpdatePluginsReceiver.copyFromRemote` | 通过蓝牙 Provider 读取插件文件 |
| 4/47187 | `MiuiUpdatePluginsReceiver.handleUpdatePluginsBroadcast` | SETTINGS_URLS 暂存及 Qigsaw 更新 |
| 13/6881 | `JavaActivity.onCreate` | 真实插件业务 Activity |
| 13/6891 | `JavaActivity.parseFeature` | 按来路选择业务分支 |
| 13/6877 | `JavaActivity.loadFragment` | 创建插件 MiuiHeadsetFragment |
| 13/7853 | `MiuiHeadsetFragment.onCreateView` | 插件首页大图、电量区与 ANC 布局 |
| 13/7906 | `MiuiHeadsetFragment.showLogoForAudio` | FEATURE_DOLBY 与 logo |
| 13/6781 | `JavaActivity$MiuiHeadsetCallback.refreshStatus` | 主线程分发状态的注入候选 |
| 13/7895 | `MiuiHeadsetFragment.refreshStatus` | 16 字段普通状态与特殊前缀 |
| 13/10924 | `MiuiHeadsetBattery.onBatteryChanged(int,int,int)` | 三路 encoded 电量 |
| 13/6739 | `InterfaceForDeviceFuncConfig.getInstance` | 选择 Pluto 等配置器 |
| 13/9969 | `PlutoFeatureConfigurator.<clinit>` | 主页面与自定义能力集合 |
| 13/9974 | `PlutoFeatureConfigurator.getHeadsetAnc` | 构造 MiuiHeadsetForPluto |
| 13/11779 | `MiuiHeadsetForPluto.onCreatView` | 同时初始化开关、滑杆和通勤沉浸 |
| 13/11771 | `MiuiHeadsetForPluto.addImmersionPreference` | 图二通勤沉浸下拉 |
| 13/11782 | `MiuiHeadsetForPluto.refreshStatus` | 102/103 回报契约 |
| 13/11800 | `MiuiHeadsetForPluto.updatedSeekbarAncValue` | 20 档滑杆更新 |
| 13/11838 | `MiuiHeadsetIntelligentAncController.setIntelligentAncState` | F2\|102 智能开关 |

## 9. 当前交付与验证状态

- 已确认图二相关的插件 Activity、Fragment、布局、能力配置器与主要 ANC 方法。
- 已只读提取本机现有插件，未改写、重签或单独安装系统插件。
- 现有模块装机版本仍为上一轮的标准详情页接入；本轮没有宣称已经切换到图二。
- 上一轮 debug 构建和 66 项单元测试通过。Lint 曾报告 5 处听感/配置页面的配置读取错误，已做最小修复；修复后的复查由用户中止，因此不能记为完整 Lint 通过。
- 本文是本轮原生页面定位的主记录。[上一轮标准详情页记录](Settings_17_Hook_Repair.md) 保留为已尝试路径的实现说明，不能作为图二接入成功的证据。
