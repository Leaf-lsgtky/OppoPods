# OppoPods

[English](#english) | [中文](#中文)

---

## English

Xposed module that brings system-level OPPO earphone control to Xiaomi HyperOS devices.

Based on [HyperPods](https://github.com/Art-Chen/HyperPods) by Art_Chen.

### Features

- **ANC Control** — Switch between Off / Noise Cancellation / Transparency
- **Game Mode** — Low-latency audio toggle with optional auto-enable on connect
- **Battery Display** — Real-time battery level for left ear, right ear, and charging case
- **Quick Popup** — Tap notification or Control Center card to open a compact floating dialog with battery, ANC, and game mode controls; tap "More" to enter the full app
- **HyperOS Integration** — Focus Island battery popup on connection, persistent notification, status bar headset icon
- **Dark Mode** — Full dark theme support including popup dialog and battery icons
- **Standalone Mode** — Direct RFCOMM connection when Xposed hooks are unavailable

### Requirements

- Xiaomi device running **HyperOS** (Android 15+)
- **LSPosed** or compatible Xposed framework
- Module scope: `com.android.bluetooth`, `com.xiaomi.bluetooth`, `com.android.systemui`

### How It Works

OppoPods hooks into three system processes:

| Process | Purpose |
|---------|---------|
| `com.android.bluetooth` | Detect OPPO earphone via A2DP, establish RFCOMM channel 15, send/receive protocol packets |
| `com.xiaomi.bluetooth` | Show Focus Island battery popup, create persistent notification |
| `com.android.systemui` | Intercept Control Center device card tap to open quick popup |

### Protocol

Communication uses Bluetooth Classic **RFCOMM** on channel 15. Packet format:

```
AA [TotalLen] 00 00 [Cmd 2B LE] [Seq] [PayLen 2B LE] [Payload...]
```

| Function | Cmd | Payload |
|----------|-----|---------|
| ANC Control | `0x0404` | `01 01 <mode>` — `01`=Off, `02`=NC, `04`=Transparency |
| Game Mode Set | `0x0403` | `28 01`=On, `28 00`=Off |
| Battery Query | `0x0106` | (empty) |
| Battery Response | `0x8106` | Pairs of `[Index, RawValue]` — battery=`val & 0x7F`, charging=`(val & 0x80) != 0` |
| Active Battery Report | `0x0204` | `01 <count> [Index, StatusValue]...` — unsolicited, same value encoding as above |
| Batch Status Query | `0x010D` | Fixed blob (see below), wakes earbuds, no prerequisite |
| Batch Status Response | `0x810D` | Key-value stream; find byte `0x28`, next byte = game mode (`01`=On, `00`=Off) |

**Batch Status Query (fixed hex):**
```
AA 13 00 00 0D 01 00 0C 00 0B 05 04 0B 11 13 18 06 1B 1C 27 28
```

### Build

```bash
./gradlew assembleDebug
```

### Install

1. Install the APK
2. Enable the module in LSPosed with scope: `com.android.bluetooth`, `com.xiaomi.bluetooth`, `com.android.systemui`
3. Reboot
4. Connect your OPPO earphones via Bluetooth

### Credits

- [HyperPods](https://github.com/Art-Chen/HyperPods) by Art_Chen — original project
- [YukiHookAPI](https://github.com/HighCapable/YukiHookAPI) — Xposed hook framework
- [Miuix](https://github.com/YuKongA/miuix) — HyperOS-style Compose UI components

### License

GPL-3.0

---

## 中文

为小米 HyperOS 设备提供系统级 OPPO 耳机控制的 Xposed 模块。

基于 Art_Chen 的 [HyperPods](https://github.com/Art-Chen/HyperPods)。

### 功能

- **降噪控制** — 在关闭 / 降噪 / 通透模式之间切换
- **游戏模式** — 低延迟音频开关，支持连接时自动开启
- **电量显示** — 实时显示左耳、右耳、充电盒电量
- **快捷弹窗** — 点击通知或控制中心耳机卡片，弹出浮窗显示电量、降噪、游戏模式控制；点击「更多」进入完整页面
- **HyperOS 集成** — 连接时焦点岛电量弹窗、常驻通知、状态栏耳机图标
- **深色模式** — 完整深色主题支持，包括弹窗对话框与电池图标
- **独立模式** — 在 Xposed 钩子不可用时通过 RFCOMM 直连耳机

### 系统要求

- 小米设备，运行 **HyperOS**（Android 15+）
- **LSPosed** 或兼容的 Xposed 框架
- 模块作用域：`com.android.bluetooth`、`com.xiaomi.bluetooth`、`com.android.systemui`

### 工作原理

OppoPods 挂钩三个系统进程：

| 进程 | 用途 |
|------|------|
| `com.android.bluetooth` | 通过 A2DP 检测 OPPO 耳机，建立 RFCOMM 通道 15，收发协议包 |
| `com.xiaomi.bluetooth` | 焦点岛电量弹窗、创建常驻通知 |
| `com.android.systemui` | 拦截控制中心设备卡片点击，打开快捷弹窗 |

### 协议

通信使用经典蓝牙 **RFCOMM**，通道 15。数据包格式：

```
AA [总长度] 00 00 [命令 2字节小端] [序列号] [载荷长度 2字节小端] [载荷...]
```

| 功能 | 命令 | 载荷 |
|------|------|------|
| 降噪控制 | `0x0404` | `01 01 <模式>` — `01`=关闭, `02`=降噪, `04`=通透 |
| 游戏模式设置 | `0x0403` | `28 01`=开, `28 00`=关 |
| 电量查询 | `0x0106` | （空） |
| 电量响应 | `0x8106` | `[索引, 原始值]` 对 — 电量=`val & 0x7F`，充电中=`(val & 0x80) != 0` |
| 电量主动上报 | `0x0204` | `01 <数量> [索引, 状态值]...` — 耳机主动推送，编码同上 |
| 批量状态查询 | `0x010D` | 固定数据包（见下），自带唤醒权重，无需前置指令 |
| 批量状态响应 | `0x810D` | 键值流；查找字节 `0x28`，下一字节为游戏模式状态（`01`=开, `00`=关） |

**批量状态查询（固定数据）：**
```
AA 13 00 00 0D 01 00 0C 00 0B 05 04 0B 11 13 18 06 1B 1C 27 28
```

### 构建

```bash
./gradlew assembleDebug
```

### 安装

1. 安装 APK
2. 在 LSPosed 中启用模块，作用域选择：`com.android.bluetooth`、`com.xiaomi.bluetooth`、`com.android.systemui`
3. 重启设备
4. 通过蓝牙连接你的 OPPO 耳机

### 致谢

- [HyperPods](https://github.com/Art-Chen/HyperPods) by Art_Chen — 原始项目
- [YukiHookAPI](https://github.com/HighCapable/YukiHookAPI) — Xposed 钩子框架
- [Miuix](https://github.com/YuKongA/miuix) — HyperOS 风格 Compose UI 组件

### 许可证

GPL-3.0
