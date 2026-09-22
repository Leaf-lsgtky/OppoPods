# Settings 蓝牙耳机详情页"更多内容"渲染机制逆向分析

> 分析对象：`jadx` MCP 加载的 `com.android.settings`（Xiaomi HyperOS），关键反编译源码见 `.workbuddy/dump/`。
> 关联：[`hook/SettingsHeadsetHook.kt`](../app/src/main/java/moe/chenxy/oppopods/hook/SettingsHeadsetHook.kt) 现有实现；`docs/` 协议笔记。
> 本文聚焦"设置里点开蓝牙耳机后，耳机图 / 电量 / 降噪控制等更多内容是如何被渲染出来的"。

## 结论

Settings 里蓝牙耳机详情页存在 **两条并存路径**，机制完全不同：

| 路径 | 入口页面 | "更多内容"来源 | 是否被现有 hook 使用 |
|---|---|---|---|
| A. 旧版 MiuiHeadset 页 | `MiuiHeadsetActivity` + `MiuiHeadsetFragment` + `MiuiHeadsetBattery` | 页面自带 UI，靠**伪造 MIUI TWS 耳机身份**让小米原生页面接管 | ✅ 现有 `SettingsHeadsetHook` 完全基于此 |
| B. 新版数据驱动页 | `BluetoothDeviceDetailsFragment` → `BluetoothDetailsConfigurableFragment` | 蓝牙 metadata（图/电量）+ `IDeviceSettingsConfigProviderService` 动态下发的控件 | ❌ 现有 hook 未触及；本文 jadx 分析对象 |

**关键认知**：

- 现有 hook 的"显示更多内容"本质是——给 OPPO 设备**伪造 `MIUI_HEADSET_SUPPORT` / `DEVICE_ID`**，把它骗进小米自己的 `MiuiHeadset` 页面；耳机图、电量、降噪全部由这个旧页面原本就支持的控件承载，模块只是往里**注入假数据**（电池值、ANC 模式、状态 payload）。
- 而 **新版本的标准详情页**走的是另一套数据驱动架构：耳机图/电量取自蓝牙设备的 **metadata**，降噪等控件由设备对应的 **`IDeviceSettingsConfigProviderService`（AIDL provider）下发 `DeviceSettingsConfig`**，再用**内嵌 Compose** 渲染。Settings 本身不硬编码任何耳机的 ANC 逻辑。
- 两条路径是**互斥入口**：旧版 `MiuiHeadsetActivity` 仅在 intent 带了 `MIUI_HEADSET_SUPPORT` 时被路由到（`BluetoothDeviceDetailsFragment` 是通用兜底页）。现代 HyperOS 上两者可能并存，也可能新版逐步弃用旧页——这直接关系到现有 hook 的长期可用性（见第 5 节）。

---

## 1. 路径 A：旧版 MiuiHeadset 页（现有 hook 的根基）

详见第 4 节对 `SettingsHeadsetHook.kt` 的逐函数拆解。要点：

- 入口 `MiuiHeadsetActivity` / `MiuiHeadsetActivityPlugin`（均 `onCreate(Bundle)`）。
- 设备身份判定集中在 `com.android.settings.bluetooth.HeadsetIDConstants`（`checkSupport` / `isTWS01Headset` / `isBleMmaConnect`）。
- 真实数据来自 `com.android.bluetooth.ble.app.IMiuiHeadsetService$Stub$Proxy`。
- 页面 UI 由 `MiuiHeadsetFragment` 承载，电池控件 `com.android.settings.bluetooth.tws.MiuiHeadsetBattery`，状态通过 `updateAtUiInfo` / `updateAncUi` / `refreshStatus` 注入。

这套页面是小米为自家/合作 TWS 耳机做的"专属详情页"，字段布局写死在页面里，模块通过伪造 MIUI 耳机身份 + 拦截 proxy 返回假值来复用它。

---

## 2. 路径 B：新版数据驱动页（jadx 分析对象）

### 2.1 页面组装

`Settings.BluetoothDeviceDetailActivity` → **`BluetoothDeviceDetailsFragment`**（继承 `BluetoothDetailsConfigurableFragment`），偏好屏 `R.xml.bluetooth_device_details_fragment`。

页面内容由两部分拼成：

1. **固定控制器列表** —— `createPreferenceControllers()` 里 `new` 一批 `BluetoothDetails*Controller`（按钮、空间音频、配置文件、MAC、关联应用、数据共享……），这些是通用条目。
2. **数据驱动的"更多内容"** —— 耳机图、电量、降噪控件，来自下文两条独立链路。

### 2.2 耳机图（header image）

由 `AdvancedBluetoothDetailsHeaderController`（另有 `GeneralBluetoothDetailsHeaderController` / `LeAudioBluetoothDetailsHeaderController` 作备选，按设备类型互斥选择）负责：

- 图片来自蓝牙设备 metadata 里的 **`iconUri`**（ContentProvider URI），经 `getContentResolver()` 读成 `Bitmap` 缓存进 `mIconCache`，再 `imageView.setImageDrawable(...)`。
- 是否显示由 `BluetoothUtils.isAdvancedDetailsHeader(device)` 决定。

### 2.3 电量

同一头部控制器内：`cachedDevice.getBatteryLevelsInfo()` 取出**左耳 / 右耳 / 充电盒**三路电量，用 `BatteryMeterView.BatteryMeterDrawable` 绘制成电池环/图标，并支持充电状态。

### 2.4 降噪等控件（ANC / 开关 / Banner）

Settings **不硬编码 ANC**，而是向设备对应的 provider service 动态拉取。链路：

```text
BluetoothDeviceDetailsViewModel
  └─ DeviceSettingRepositoryImpl
       └─ DeviceSettingServiceConnection（按设备缓存 EndPoint{packageName, className, intentAction}）
            └─ 绑定 IDeviceSettingsConfigProviderService (AIDL)
                 └─ getDeviceSettingsConfigWithOptions(deviceInfo, callback)
                      └─ 返回 DeviceSettingsConfig
                           ├─ getMainContentItems()        // 主区域：降噪模式多档切换等
                           └─ getMoreSettingsItems()       // 更多设置区
```

- 提供方列表来自系统/厂商内置 `config`（Deferred，每个设备一个 `EndPoint`）。
- `DeviceSettingsConfig` 的项类型映射为：
  - `MultiTogglePreference` —— 降噪模式（关 / 通透 / 降噪 多档切换）
  - `ActionSwitchPreference` —— 开关项
  - `BannerPreference` / `FooterPreference` / `HelpPreference` —— 横幅 / 页脚 / 帮助
- 这些项在 `BluetoothDetailsConfigurableFragment.constructLayout()` 里用**内嵌 Compose**（`ComposableSingletons$BluetoothDetailsConfigurableFragmentKt`）渲染；图标同样支持 `DeviceSettingIcon.BitmapIcon`（ContentProvider 返回的 Bitmap）。
- 用户点击 → `triggerAction()` 通过 `Intent` / `PendingIntent` 回传给提供方执行。

### 2.5 遗留注入点（Slice 路线）

`BlockingPrefWithSliceController` + `BluetoothFeatureProvider.getBluetoothDeviceSettingsUri()` 读蓝牙 **metadata key 16**（Slice URI）——Android 传统的"设备设置 Slice"注入方式，伴侣 App 可通过它塞入自定义设置条目。

---

## 3. 新版页面数据流全景

```text
┌─────────────────────────────────────────────────────────────────────┐
│  蓝牙耳机详情页（点开设备）                                          │
│                                                                     │
│  BluetoothDeviceDetailsFragment                                     │
│   ├─【固定】createPreferenceControllers() → BluetoothDetails*Controller│
│   │                                                                │
│   └─【动态】BluetoothDetailsConfigurableFragment                   │
│        ├─ 头部：Advanced/General/LeAudio HeaderController          │
│        │     ├─ 耳机图  ← 蓝牙 metadata.iconUri (ContentProvider)  │
│        │     └─ 电量    ← cachedDevice.getBatteryLevelsInfo()      │
│        │               (左/右/盒 → BatteryMeterDrawable)           │
│        │                                                           │
│        └─ 主体：ViewModel → DeviceSettingRepositoryImpl            │
│                   └─ DeviceSettingServiceConnection                │
│                        └─ bind IDeviceSettingsConfigProviderService│
│                             └─ DeviceSettingsConfig                │
│                                  ├─ MultiTogglePreference (降噪)    │
│                                  ├─ ActionSwitchPreference         │
│                                  └─ Banner/Footer/Help             │
│                             → Compose 渲染，点击回传 Intent        │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 4. 现有 hook 设置逻辑梳理：`SettingsHeadsetHook.kt`

### 4.1 注册与生命周期

- `HookEntry.loadHookForPackage` 在 **`com.android.settings` 进程**加载时实例化 `SettingsHeadsetHook` 并调用 `onHook()`。
- 所有 hook 通过基类 `HookContext` 注册：`registerHook()` 封装 LibXposed 的 `module.hook()`，并按 `phase:genericSignature` 生成**稳定 hook id**（满足 API 102 热重载要求）。
- `onHotReloading()` 在热重载前停止主线程刷新循环、注销广播接收器——符合 AGENTS.md 的"热重载前必须释放回调"约定。

### 4.2 `onHook()` 的五大子 hook

| 子 hook | 目标 | 作用 |
|---|---|---|
| `hookActivityEntry()` | `MiuiHeadsetActivity` / `MiuiHeadsetActivityPlugin` 的 `onCreate`；`getDeviceID` / `getSupport` | 给 OPPO 设备的 intent **注入 `MIUI_HEADSET_SUPPORT=FAKE_SUPPORT`、`DEVICE_ID=FAKE_DEVICE_ID`**，把它路由进 MiuiHeadset 页面 |
| `hookSupportChecks()` | `HeadsetIDConstants.checkSupport` / `isTWS01Headset` / `isK77sHeadset` / `isBleMmaConnect` | 让设备 ID 命中 FAKE_DEVICE_ID 时返回"支持/是 TWS01"，并强制 `isBleMmaConnect=true` |
| `hookServiceProxy()` | `IMiuiHeadsetService$Stub$Proxy` 十余个方法 | **拦截真实蓝牙服务调用**：`checkSupport/getDeviceInfo` 返回 FAKE_SUPPORT；`connect/getDeviceConfig/getCommonConfig` 直接吞掉（noop）；`isMiTWS/checkIsMiTWS` 返回 true；`changeAncMode/changeAncLevel` 把小米的 ANC 指令**翻译成 OPPO 指令**并发出 |
| `hookBatteryView()` | `MiuiHeadsetBattery` 构造器、`onBatteryChanged(String)` | 构造时登记设备并请求状态；把电池刷新替换成模块下发的三路电量（左/右/盒） |
| `hookFragmentState()` | `MiuiHeadsetFragment.onCreateView` / `onServiceConnected` / `refreshStatus` / `handleConnectMmaFailed` / `updateAncMode` / `updateAncLevel` | 吞掉 MMA 连接失败、注入状态 payload、把页面 ANC 操作转成 OPPO 指令 |

### 4.3 状态桥接（模块 ↔ 设置页）

- `registerStatusReceiver()` 用一个 `BroadcastReceiver` 监听 `ACTION_PODS_CONNECTED / DISCONNECTED / BATTERY_CHANGED / ANC_CHANGED / MILINK_SPATIAL_AUDIO_OPTION_CHANGED`（来自 `com.android.bluetooth` / 自身进程）。
- `requestBluetoothStatus()` 周期性（默认 3s）向 `com.android.bluetooth` 发 `ACTION_PODS_UI_INIT` / `ACTION_REFRESH_STATUS` 主动拉取状态。
- 收到数据后：电量走 `updateBatteryView()` → `MiuiHeadsetBattery.onBatteryChanged(int,int,int)`；状态走 `injectFragmentStatus()` → `updateAtUiInfo(payload)` / `updateAncUi(level,false)` / `refreshStatus(address, payload)`，其中 payload 形如 `"ancMode|0100;0101;0102;0103;0200;0201|battery|00"` 与 16 字段逗号串。
- 状态持久化到 `SharedPreferences("oppopods_milink_state")`，进程重启后可恢复。

### 4.4 FAKE 身份常量

```text
FAKE_DEVICE_ID = "01010901"
FAKE_SUPPORT   = "01010901,000000000000000010000000"
```

`FAKE_SUPPORT` 是 device-id 后跟一串能力 bitmap；`checkSupport` 只要以 `FAKE_DEVICE_ID` 开头就被判定为受支持。

### 4.5 设备识别

- `isOppoPod(device)`：地址在 `knownOppoAddresses` 集合中，或设备名含 `"oppo"`（命中则记录地址）。
- `isOppoFragment(fragment)`：进一步检查 `mDeviceId` / `mSupport` 是否等于/以 `FAKE_DEVICE_ID` 开头——这是 hook 区分"是否要对这个页面动手"的核心判定。

---

## 5. 两条路径的关系与对 OppoPods 的接入建议

**关系**：旧版 `MiuiHeadsetActivity` 是"专属页"，只有 intent 带了 `MIUI_HEADSET_SUPPORT` 才会进入；新版 `BluetoothDeviceDetailsFragment` 是所有蓝牙设备都走的通用详情页。**现有 hook 主动把 OPPO 设备塞进旧版专属页**，从而复用其已经写好的图/电量/降噪 UI；新版的"更多内容"机制则是 provider service 下发，与现有 hook 无交集。

**风险与机会**：

1. **兼容性风险**：若新版 HyperOS 弱化/移除 `MiuiHeadsetActivity` 这套旧页，现有 `SettingsHeadsetHook` 会因找不到目标类而全部 `onFailure` 跳过（每个 hook 都有 `runCatching` 兜底，不会崩，但功能消失）。届时需切换到路径 B。
2. **路径 B 的未来接入点**（让 OPPO 耳机在新版通用页也显示图/电量/降噪）：
   - **图 + 电量**：hook 头部控制器，或让蓝牙栈/伴侣 App 写入设备的 `iconUri` + battery metadata（与路径 A 完全不同，不靠伪造 MIUI 身份）。
   - **降噪控件**：实现一个 `IDeviceSettingsConfigProviderService` 提供方，或 hook `DeviceSettingRepositoryImpl` 注入 OPPO 的 `DeviceSettingsConfig`；也可走 metadata key 16 的 Slice 路线。
   - 三个头部控制器靠 `isAvailable()` 互斥，要强制显示 OPPO 头部可 hook 对应 `isAvailable()` / `BluetoothUtils.isAdvancedDetailsHeader()`。

**结论**：现有 hook 是一条"以假乱真"的稳妥旧路径；jadx 揭示的新版数据驱动路径是更标准、长期更可能存活的替代方案，二者应作为互补的后备/演进路线并存。
