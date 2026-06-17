# OppoPods

[English](#english) | [中文](#中文)

---

## English

Xposed module that brings system-level OPPO earphone control to Xiaomi HyperOS devices.

Based on [HyperPods](https://github.com/Art-Chen/HyperPods) by Art_Chen.

### Features

- **ANC Control** — Switch between Off / Noise Cancellation / Adaptive / Transparency
- **Spatial Audio** — Switch between Off / Fixed / Head Tracking from the home detail page
- **Game Mode** — Low-latency audio toggle with optional auto-enable on connect
- **Battery Display** — Real-time battery level for left ear, right ear, and charging case
- **Quick Popup** — Tap the persistent notification to open a compact floating dialog with battery, ANC, and game mode controls; tap "More" to enter the full app
- **HyperOS Integration** — Focus Island battery popup on connection, optional Super Island-style persistent notification, status bar headset icon
- **Dark Mode** — Full dark theme support including popup dialog and battery icons
- **Standalone Mode** — Direct RFCOMM connection when Xposed hooks are unavailable

### Requirements

- Xiaomi device running **HyperOS** (Android 15+)
- **LSPosed** or compatible Xposed framework
- Module scope: `com.android.bluetooth`, `com.milink.service`, `com.xiaomi.bluetooth`, `com.android.settings`

### How It Works

OppoPods hooks into four packages:

| Process | Purpose |
|---------|---------|
| `com.android.bluetooth` | Detect OPPO earphone via A2DP, establish RFCOMM via the selected UUID or channel mode, send/receive protocol packets |
| `com.milink.service` | Mirror headset ANC, battery, game mode, and spatial audio state into HyperOS headset runtime |
| `com.xiaomi.bluetooth` | Show Focus Island battery popup, create persistent notification |
| `com.android.settings` | Sync headset settings page state and ANC commands |

### Protocol

Communication uses Bluetooth Classic **RFCOMM**. The connection method can be selected in settings: `UUID` tries the HeyMelody SPP UUIDs `00001107-D102-11E1-9B23-00025B00A5A5` and `0000079A-D102-11E1-9B23-00025B00A5A5`; `Channel 15` uses the fixed RFCOMM channel directly. Packet format:

```
AA [TotalLen] 00 00 [Cmd 2B LE] [Seq] [PayLen 2B LE] [Payload...]
```

| Function | Cmd | Payload |
|----------|-----|---------|
| ANC Control | `0x0404` | `01 01 <mode>` — `01`=Off, `02`=NC, `04`=Transparency, `00 08`=Adaptive |
| Game Mode Set | `0x0403` | `28 01`=On, `28 00`=Off |
| Spatial Audio Set | `0x0422` | `<mode>` — `00`=Off, `01`=Fixed, `02`=Head Tracking |
| Battery Query | `0x0106` | (empty) |
| Battery Response | `0x8106` | Pairs of `[Index, RawValue]` — battery=`val & 0x7F`, charging=`(val & 0x80) != 0` |
| Active Battery Report | `0x0204` | `01 <count> [Index, StatusValue]...` — unsolicited, same value encoding as above |
| Spatial Audio Notify | `0x0510` | `<mode>` — unsolicited spatial audio state |
| Batch Status Query | `0x010D` | Fixed blob (see below), wakes earbuds, no prerequisite |
| Batch Status Response | `0x810D` | Key-value stream; find byte `0x28`, next byte = game mode (`01`=On, `00`=Off) |

**Batch Status Query (fixed hex):**
```
AA 13 00 00 0D 01 00 0C 00 0B 05 04 0B 11 13 18 06 1B 1C 27 28
```

**Spatial Audio packets:**
```
AA 08 00 00 22 04 F0 01 00 00  # Off
AA 08 00 00 22 04 F0 01 00 01  # Fixed
AA 08 00 00 22 04 F0 01 00 02  # Head Tracking
```

### MiLink Spatial Audio Option

The advanced setting `Add spatial audio option to MiLink card` controls only the HyperOS/MiLink card entry. The home page dropdown always remains available.

Implementation notes:

- Preference key: `milink_spatial_audio_option_enabled`, default `true`.
- App broadcasts `ACTION_MILINK_SPATIAL_AUDIO_OPTION_CHANGED` to `com.milink.service` and `com.android.settings` after changes.
- When enabled, MiLink spatial modes map to OPPO modes as follows: `0 -> 0`, `1 -> 1`, `9/11 -> 2`.
- When disabled, MiLink getters return unsupported values and MiLink-originated spatial audio commands are swallowed, so no OPPO `0x0422` packet is sent.

### Build

```bash
./gradlew assembleDebug
```

### Install

1. Install the APK
2. Enable the module in LSPosed with scope: `com.android.bluetooth`, `com.milink.service`, `com.xiaomi.bluetooth`, `com.android.settings`
3. Reboot
4. Connect your OPPO earphones via Bluetooth

### Credits

- [HyperPods](https://github.com/Art-Chen/HyperPods) by Art_Chen — original project
- [libxposed](https://github.com/libxposed/api) — Xposed module API
- [Miuix](https://github.com/YuKongA/miuix) — HyperOS-style Compose UI components

### License

GPL-3.0

---

## 中文

为小米 HyperOS 设备提供系统级 OPPO 耳机控制的 Xposed 模块。

基于 Art_Chen 的 [HyperPods](https://github.com/Art-Chen/HyperPods)。

### 功能

- **降噪控制** — 在关闭 / 降噪 / 自适应 / 通透模式之间切换
- **空间音频** — 首页详情页支持关闭 / 固定 / 头部跟踪三档切换
- **游戏模式** — 低延迟音频开关，支持连接时自动开启
- **电量显示** — 实时显示左耳、右耳、充电盒电量
- **快捷弹窗** — 点击常驻通知，弹出浮窗显示电量、降噪、游戏模式控制；点击「更多」进入完整页面
- **HyperOS 集成** — 连接时焦点岛电量弹窗、可选超级岛样式常驻通知、状态栏耳机图标
- **深色模式** — 完整深色主题支持，包括弹窗对话框与电池图标
- **独立模式** — 在 Xposed 钩子不可用时通过 RFCOMM 直连耳机

### 系统要求

- 小米设备，运行 **HyperOS**（Android 15+）
- **LSPosed** 或兼容的 Xposed 框架
- 模块作用域：`com.android.bluetooth`、`com.milink.service`、`com.xiaomi.bluetooth`、`com.android.settings`

### 工作原理

OppoPods 挂钩四个包：

| 进程 | 用途 |
|------|------|
| `com.android.bluetooth` | 通过 A2DP 检测 OPPO 耳机，按设置选择 UUID 或通道模式建立 RFCOMM，收发协议包 |
| `com.milink.service` | 将耳机电量、降噪、游戏模式和空间音频状态同步到 HyperOS 耳机运行时 |
| `com.xiaomi.bluetooth` | 焦点岛电量弹窗、创建常驻通知 |
| `com.android.settings` | 同步系统耳机设置页状态和降噪命令 |

### 协议

通信使用经典蓝牙 **RFCOMM**。连接方式可在设置中选择：`UUID` 会尝试欢律 SPP UUID `00001107-D102-11E1-9B23-00025B00A5A5` 和 `0000079A-D102-11E1-9B23-00025B00A5A5`；`通道 15` 会直接使用固定 RFCOMM 通道。数据包格式：

```
AA [总长度] 00 00 [命令 2字节小端] [序列号] [载荷长度 2字节小端] [载荷...]
```

| 功能 | 命令 | 载荷 |
|------|------|------|
| 降噪控制 | `0x0404` | `01 01 <模式>` — `01`=关闭, `02`=降噪, `04`=通透, `00 08`=自适应 |
| 游戏模式设置 | `0x0403` | `28 01`=开, `28 00`=关 |
| 空间音频设置 | `0x0422` | `<模式>` — `00`=关闭, `01`=固定, `02`=头部跟踪 |
| 电量查询 | `0x0106` | （空） |
| 电量响应 | `0x8106` | `[索引, 原始值]` 对 — 电量=`val & 0x7F`，充电中=`(val & 0x80) != 0` |
| 电量主动上报 | `0x0204` | `01 <数量> [索引, 状态值]...` — 耳机主动推送，编码同上 |
| 空间音频主动上报 | `0x0510` | `<模式>` — 耳机主动推送空间音频状态 |
| 批量状态查询 | `0x010D` | 固定数据包（见下），自带唤醒权重，无需前置指令 |
| 批量状态响应 | `0x810D` | 键值流；查找字节 `0x28`，下一字节为游戏模式状态（`01`=开, `00`=关） |

**批量状态查询（固定数据）：**
```
AA 13 00 00 0D 01 00 0C 00 0B 05 04 0B 11 13 18 06 1B 1C 27 28
```

**空间音频设置包：**
```
AA 08 00 00 22 04 F0 01 00 00  # 关闭
AA 08 00 00 22 04 F0 01 00 01  # 固定
AA 08 00 00 22 04 F0 01 00 02  # 头部跟踪
```

### MiLink 空间音频选项

高级设置里的 `在 MiLink 卡片添加空间音频选项` 只控制 HyperOS/MiLink 卡片入口；首页空间音频下拉菜单始终可用。

实现方法：

- 偏好键：`milink_spatial_audio_option_enabled`，默认 `true`。
- App 修改后广播 `ACTION_MILINK_SPATIAL_AUDIO_OPTION_CHANGED` 给 `com.milink.service` 和 `com.android.settings`。
- 开启时，MiLink 空间音频模式映射到 OPPO 模式：`0 -> 0`，`1 -> 1`，`9/11 -> 2`。
- 关闭时，MiLink getter 返回不支持，MiLink 发起的空间音频命令会被拦截，不发送 OPPO `0x0422` 包。

### 构建

```bash
./gradlew assembleDebug
```

### 安装

1. 安装 APK
2. 在 LSPosed 中启用模块，作用域选择：`com.android.bluetooth`、`com.milink.service`、`com.xiaomi.bluetooth`、`com.android.settings`
3. 重启设备
4. 通过蓝牙连接你的 OPPO 耳机

### 致谢

- [HyperPods](https://github.com/Art-Chen/HyperPods) by Art_Chen — 原始项目
- [libxposed](https://github.com/libxposed/api) — Xposed 模块 API
- [Miuix](https://github.com/YuKongA/miuix) — HyperOS 风格 Compose UI 组件

### 许可证

GPL-3.0
