# 欢律蓝牙协议反编译笔记

本文记录通过 JADX 查看欢律/HeyMelody 官方 App 后，对 OPPO 耳机蓝牙连接与 SPP 协议的整理。类名多为混淆名，建议把这里当成定位线索和实现依据，而不是完整协议规范。

## 结论

- 欢律经典蓝牙数据连接使用 `BluetoothDevice.createRfcommSocketToServiceRecord(UUID)`，不是直接写死公开 channel。
- 已确认两个 OPPO/HeyMelody SPP UUID：
  - `00001107-D102-11E1-9B23-00025B00A5A5`
  - `0000079A-D102-11E1-9B23-00025B00A5A5`
- `UUID` 本身不是 channel；Android 会通过 SDP 解析到实际 RFCOMM server channel，再建立 socket。公共 API 不直接暴露这个 channel。
- 欢律代码里有一层内层 packet：`Cmd(2 LE) + Seq/Type(1) + Len(2 LE) + Payload`。OppoPods 当前发送的是耳机 socket 上的完整外层帧：`AA + TotalLen + 00 00 + 内层 packet`。
- 命令响应一般用 `cmd | 0x8000` 表示，例如请求 `0x0106` 的响应是 `0x8106`。

## JADX 证据

### RFCOMM UUID 连接

`p553p6.AbstractC6302a`，注释名为 `BaseBRConnection.java`。连接创建点：

```java
bluetoothDevice.createRfcommSocketToServiceRecord(c6188a.f23639d);
```

同一类的 `p540o6.C6188a`，注释名为 `BRClientConnection.java`，负责 socket connect、读写线程、超时关闭等：

```java
bluetoothSocket.connect();
bluetoothSocket.getOutputStream().write(...);
```

`p553p6.AbstractC6303b`，注释名为 `BaseBRDevice.java`，静态字段里确认经典蓝牙默认 UUID：

```java
f23666w = UUID.fromString("00001107-D102-11E1-9B23-00025B00A5A5");
```

`p623v7.C6745e`，注释名为 `WhitelistRepositoryServerImpl.kt`，白名单默认 UUID 列表：

```java
["0000079A-D102-11E1-9B23-00025B00A5A5",
 "00001107-D102-11E1-9B23-00025B00A5A5"]
```

`p529n6.C6125f`，注释名为 `GattDevice.java`，BLE/GATT 侧也使用 `0000079A...` 作为 service UUID，并派生出：

- `0100079A-D102-11E1-9B23-00025B00A5A5`
- `0200079A-D102-11E1-9B23-00025B00A5A5`

这说明 `0000079A...` 不只是随便的字符串，而是欢律蓝牙协议族的一部分。

### Packet 结构

`p362b6.C2740a`，注释名为 `Packet.java`，从 byte array 解析出：

```text
offset 0..1: cmd, little endian
offset 2:    seq/type
offset 3..4: payload length, little endian
offset 5..:  payload
```

解析出的 packet key：

```java
((seqOrType & 0xff) << 16) | (cmd & 0x7fff)
```

这和 OppoPods 当前外层格式对应：

```text
AA [TotalLen] 00 00 [Cmd 2B LE] [Seq] [PayLen 2B LE] [Payload...]
```

也就是说，欢律 `Packet.java` 看到的是去掉 `AA TotalLen 00 00` 后的内层 packet。

`p362b6.C2741b`，注释名为 `PacketFactory.java`：

- `m5621a(address, cmd, payload)` 会给每个地址维护递增 seq。
- `m5620b(packet, payload)` 会生成响应包，命令位设置为 `cmd | 0x8000`。

### 命令线索

`com.oplus.melody.btsdk.protocol.commands.C4048c`，注释名为 `PollCommandManager.java`，包含大量查询命令：

| 十进制 | 十六进制 | 反编译语义 |
|---:|---:|---|
| 256 | `0x0100` | getRemoteCapability |
| 257 | `0x0101` | getRemoteMTU |
| 258 | `0x0102` | getRemoteVID |
| 259 | `0x0103` | getRemotePID |
| 261 | `0x0105` | getRemoteVersion |
| 262 | `0x0106` | getBatteryLevel |
| 268 | `0x010C` | noise reduction mode queries |
| 269 | `0x010D` | getFeatureSwitchStatus |
| 271 | `0x010F` | getCurrentEqualizerMode |
| 276 | `0x0114` | getCurrentCodecType |
| 280 | `0x0118` | getEarRestoreData |
| 284 | `0x011C` | getTriangleInfo |
| 286 | `0x011E` | getEarScanData |
| 289 | `0x0121` | getEarToneData |
| 290 | `0x0122` | getAllEqInfo |
| 293 | `0x0125` | getAccountKey |
| 298 | `0x012A` | getHeadsetSpatialType |
| 299 | `0x012B` | getGameSoundInfo |
| 302 | `0x012E` | getAISummaryType |
| 303 | `0x012F` | multi SPP command info |

OppoPods 当前已经使用或解析的命令：

| 功能 | Cmd | 说明 |
|---|---:|---|
| 电量查询 | `0x0106` | 空 payload |
| 电量响应 | `0x8106` | `[Index, RawValue]` pairs |
| 降噪查询 | `0x010C` | payload `01 01` |
| 降噪响应 | `0x810C` | payload 内扫描 `01 01 <val1> <val2>` |
| 主动上报 | `0x0204` | battery/ANC/button 等事件复用 |
| 批量状态查询 | `0x010D` | 查询 feature switch，payload 为数量 + feature id 列表 |
| 批量状态响应 | `0x810D` | `0x28` 是游戏模式主开关，`0x06` 是低延迟游戏模式 |
| 设置游戏模式 | `0x0403` | 标准模式只发 `28 01/00`；兼容模式开启发 `28 01` + `06 01`，关闭发 `06 00` + `28 00` |
| 设置佩戴检测 | `0x0403` | featureId `04 01/00`，对应官方 `wearDetection` |
| 设置双设备连接 | `0x0403` | featureId `11 01/00`，对应官方 `multiDevicesConnect` |
| 设置 ANC | `0x0404` | `01 01 <mode>`；降噪深度用 `01 01 <level>`（level: `80`=智能 `40`=轻度 `20`=中度 `10`=深度） |
| 查询空间音频类型 | `0x012A` | 官方 `getHeadsetSpatialType`，空 payload |
| 设置空间音频类型 | `0x0422` | payload 为单字节 `<type>`：`00`=关闭，`01`=固定，`02`=头部跟踪 |
| 空间音频主动上报 | `0x0510` | payload 首字节为当前 `<type>` |
| 查询通知能力 | `0x0200` | `CMD_GET_NOTIFICATION_CAPABILITY`，空 payload |
| 通知能力响应 | `0x8200` | `[status] [count] [eventCode1] [eventCode2] ...` |
| 订阅通知 | `0x0205` | `CMD_REGISTER_NOTIFICATION_MULTI`，payload `[count] [code1] [code2] ...` |
| 订阅通知响应 | `0x8205` | 订阅成功确认 |

### 空间音频

当前 OppoPods 按官方新空间音频协议实现三档控制，只使用 `0x0422 setHeadsetSpatialType`，暂不把 `0x0403 featureId=0x1B` 当作三档菜单的控制路径。

完整外层包：

```text
关闭:     AA 08 00 00 22 04 F0 01 00 00
固定:     AA 08 00 00 22 04 F0 01 00 01
头部跟踪: AA 08 00 00 22 04 F0 01 00 02
```

实现位置：

- `Packets.kt`
  - `SpatialAudioMode.OFF = 0`
  - `SpatialAudioMode.FIXED = 1`
  - `SpatialAudioMode.HEAD_TRACKING = 2`
  - `Enums.spatialAudioPacket(mode)` 生成 `0x0422` 包
  - `SpatialAudioParser` 解析 `0x0510` 主动上报和 `0x8422` 设置响应
- `RfcommController.kt` / `AppRfcommController.kt`
  - `setSpatialAudioMode(mode)` 乐观更新 UI，再发送 `Enums.spatialAudioPacket(mode)`
  - 收到 `0x0510` 后广播 `ACTION_PODS_SPATIAL_AUDIO_CHANGED`

### 降噪深度

降噪深度（Noise Level）仅在降噪模式（NC）下有效。通过 `0x0404` 命令设置，复用 ANC 设置通道。

欢律官方使用 `type=2` 格式 `01 02 <level>`，zerOBuds 使用 `01 01 <level>`。两种方案均通过 `Cmd.SET_ANC (0x0404)` 发送。OppoPods 采用与 zerOBuds 一致的 `01 01 <level>` 格式。

等级值：

| 值 | 含义 |
|---:|---|
| `0x80` | 智能 |
| `0x40` | 轻度 |
| `0x20` | 中度 |
| `0x10` | 深度 |

外层包示例（以深度为例）：

```text
AA 08 00 00 04 04 F0 03 00 01 01 10
```

实现位置：

- `Packets.kt`
  - `NoiseLevel` 对象定义四个常量 + `ALL` 列表
  - `AncModeParser.AncResult` 扩展了 `noiseLevel` 字段，解析 `01 01 [level] 00` 中的降噪等级值
- `DeviceProfile.kt`
  - `noiseLevelVisible` 控制降噪深度 UI 显隐
  - `noiseLevelPacket(level)` 按 ProfileKey 发包
  - ProfileKeys: `SET_NOISE_LEVEL_SMART/LIGHT/MEDIUM/DEEP`
- `RfcommController.kt` / `AppRfcommController.kt`
  - `setNoiseLevel(level)` 乐观更新 UI + 发送降噪深度包
  - 收到 ANC 响应/通知时同时提取 noiseLevel

### 佩戴检测（自动播放暂停）

佩戴检测（Wear Detection / Auto Play Pause）通过 `0x0403` feature switch 命令控制，feature ID 为 `0x04`。

官方方法名：`BtOperate.m2704Q(address, featureId=0x04, enabled, needGetState)`

设置包：

```text
开启: AA 09 00 00 03 04 F0 02 00 04 01
关闭: AA 09 00 00 03 04 F0 02 00 04 00
```

批量查询 `0x010D` 响应中 feature ID `0x04` 的值表示当前开关状态。

`0x0204` 主动上报中暂未确认佩戴检测独立 eventCode，当前通过 `0x810D` 批量响应解析状态。

实现位置：

- `Packets.kt`
  - `BatchParamId.AUTO_PLAY_PAUSE = 0x04`
  - `GameModeParser.Status` 扩展 `autoPlayPause` 字段，从 `0x810D` 响应解析
- `DeviceProfile.kt`
  - `autoPlayPauseVisible` 控制 UI 显隐
  - `autoPlayPausePacket(enabled)` 发送 `0x0403` 包
  - ProfileKeys: `SET_AUTO_PLAY_PAUSE_ON/OFF`
- `RfcommController.kt` / `AppRfcommController.kt`
  - `setAutoPlayPause(enabled)` 乐观更新 + 发包
  - `changeUIAutoPlayPauseStatus()` 广播状态变更

### 双设备连接

双设备连接（Dual Device / Multi Devices Connect）通过 `0x0403` feature switch 命令控制，feature ID 为 `0x11`。

官方方法名：`BtOperate.m2704Q(address, featureId=0x11, enabled, needGetState)`

设置包：

```text
开启: AA 09 00 00 03 04 F0 02 00 11 01
关闭: AA 09 00 00 03 04 F0 02 00 11 00
```

批量查询 `0x010D` 响应中 feature ID `0x11` 的值表示当前开关状态。

`0x0204` 主动上报 eventCode `0x06` 对应 `MultiConnectInformations`，可承载已连接设备列表信息。当前 OppoPods 通过 `0x810D` 批量响应解析开关状态，设备名称信息待后续 `0x0204` eventCode `0x06` 解析支持。

实现位置：

- `Packets.kt`
  - `BatchParamId.DUAL_DEVICE = 0x11`
  - `GameModeParser.Status` 扩展 `dualDevice` 字段，从 `0x810D` 响应解析
- `DeviceProfile.kt`
  - `dualDeviceVisible` 控制 UI 显隐
  - `dualDevicePacket(enabled)` 发送 `0x0403` 包
  - ProfileKeys: `SET_DUAL_DEVICE_ON/OFF`
- `RfcommController.kt` / `AppRfcommController.kt`
  - `setDualDevice(enabled)` 乐观更新 + 发包
  - `changeUIDualDeviceStatus()` 广播状态变更

### 通知能力查询与订阅

OppoPods 连接后遵循欢律官方初始化流程，先查询耳机支持哪些主动上报事件，再批量注册订阅。

流程：

```text
1. App → 耳机: 0x0200 (QUERY_BROADCAST_CODES, 空 payload)
2. 耳机 → App: 0x8200 响应 [status] [count] [eventCode1] [eventCode2] ...]
3. App → 耳机: 0x0205 (SUBSCRIBE_BROADCAST, payload [count] [code1] [code2] ...])
4. 耳机 → App: 0x8205 确认
5. App → 耳机: 0x010D + 0x0106 + 0x010C (批量状态查询)
```

已知 eventCode（来自欢律反编译）：

| eventCode | 含义 |
|---:|---|
| `0x01` | 电量变化 |
| `0x02` | 佩戴状态 |
| `0x03` | 降噪模式变化 |
| `0x05` | 游戏模式变化 |
| `0x06` | 多设备连接信息（已连接设备名称） |
| `0xF2` | 连接设备列表 |

不注册时耳机默认可能推送部分通知（如 ANC、电量），但 `0x06` 多设备连接信息需要注册后才会推送。

实现位置：

- `Packets.kt`
  - `OppoPackets.buildQueryBroadcastCodes()` 构建 `0x0200` 包
  - `OppoPackets.buildSubscribeBroadcast(codes)` 构建 `0x0205` 包
  - `BroadcastCodesParser.parse()` 解析 `0x8200` 响应
  - `ConnectedDevicesParser.parse()` 解析 `0x0204` eventCode `0x06`
- `AppRfcommController.kt`
  - `connect()` 中连接成功后发 `0x0200` → 收到 `0x8200` 自动发 `0x0205` → 再发 `queryStatus()`
  - `handlePacket` 中处理 `0x8200`、`0x8205`、`0x0204` eventCode `0x06`
- `RfcommController.kt`
  - 同样的注册流程和响应处理

### MiLink 卡片空间音频入口

高级设置项 `在 MiLink 卡片添加空间音频选项` 对应偏好键：

```text
milink_spatial_audio_option_enabled
```

默认值为 `true`。App 保存后广播：

```text
ACTION_MILINK_SPATIAL_AUDIO_OPTION_CHANGED
```

目标进程：

```text
com.milink.service
com.android.settings
```

MiLink 的模式值和 OPPO 空间音频类型映射：

| MiLink 值 | 含义 | OPPO `0x0422` type |
|---:|---|---:|
| `0` | 关闭 | `0` |
| `1` | 固定空间音频 | `1` |
| `9` | 手机头部跟踪 | `2` |
| `11` | 耳机头部跟踪 | `2` |

Hook 方法：

- 开关开启时，`MiLinkServiceHook` 让 `getSpatialMode` / `getMiAudioEffect` / `getAudioSpatialEffectState` 返回当前空间音频状态，并 hook `setSpatialMode` / `setMiAudioEffect` / `setHeadTracking` / `setAudioEffectState` 转发为 OPPO `0x0422`。
- 开关关闭时，MiLink 空间音频相关 getter 返回 unsupported，`isSupportAudioSwitch` / `getSwitchState` 返回不支持，MiLink 发起的空间音频命令被拦截，不发送 OPPO 包。
- UI 同步时写入 `AncBatteryModel.spatialState` 和 `deviceSpatialType`，并通知 `headsetPropertyChangeListener` 的 updateType `9` 和 `4`。

## OppoPods 当前落地

当前实现位于：

- `app/src/main/java/moe/chenxy/oppopods/pods/OppoRfcommSocketFactory.kt`
- `app/src/main/java/moe/chenxy/oppopods/pods/RfcommConnectionMethod.kt`
- `app/src/main/java/moe/chenxy/oppopods/pods/Packets.kt`

设置页现在有两种连接方式：

- `UUID`：尝试 `00001107...` 和 `0000079A...`。
- `Channel 15`：直接反射调用 `createRfcommSocket(15)`，用于和旧实现对照测试。

日志会显示当前实际走的路径：

```text
RFCOMM connection method: uuid
RFCOMM connected via UUID 00001107-D102-11E1-9B23-00025B00A5A5

RFCOMM connection method: channel
RFCOMM connected via channel 15
```

查看方式：

```powershell
adb logcat -s OppoPods-RfcommController OppoPods-AppRfcomm
```

## 仍需实机验证

- UUID 经 SDP 最终解析出的实际 RFCOMM channel 需要 HCI snoop 或反射读取 `BluetoothSocket` 内部字段确认。
- 欢律 packet 内层和 OppoPods 当前 `AA` 外层之间的拆包/封包位置还可以继续深挖，尤其是 read loop 中对原始流的切包逻辑。
- `0x0204` 主动上报按钮事件（eventCode `0xF1`）还可以继续解析。
- `0x010D/0x810D` 的 key-value payload 还有更多 feature key；游戏模式相关至少包括主开关 `0x28` 和低延迟 `0x06`。
- 降噪深度：官方 `type=2` 格式 `01 02 <level>` 与 zerOBuds `01 01 <level>` 哪种在 Enco X3/Free4 上生效需实机确认。
- 佩戴检测：`0x0403 featureId=0x04` 设置和 `0x810D` 响应解析需实机确认。
- 双设备连接：`0x0403 featureId=0x11` 设置和 `0x0204` eventCode `0x06` 设备列表解析需实机确认。
- 通知注册流程：`0x0200` → `0x8200` → `0x0205` → `0x8205` 是否在 Enco X3/Free4 上正常工作需实机确认。
- `0x0204` eventCode `0x06` 的 payload 格式（MAC、连接状态、设备名）需抓包确认。
- `AncModeParser` 现在只处理 eventCode `0x03`/`0x04`，跳过 `0x01`/`0x02`/`0x05`/`0x06` 等，需实机确认 ANC 通知不被误跳过。
