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
| 设置 ANC | `0x0404` | `01 01 <mode>` |
| 查询空间音频类型 | `0x012A` | 官方 `getHeadsetSpatialType`，空 payload |
| 设置空间音频类型 | `0x0422` | payload 为单字节 `<type>`：`00`=关闭，`01`=固定，`02`=头部跟踪 |
| 空间音频主动上报 | `0x0510` | payload 首字节为当前 `<type>` |

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
- `0x0204` 主动上报复用了多种事件，当前只处理了电量和 ANC，按钮事件还可以继续解析。
- `0x010D/0x810D` 的 key-value payload 还有更多 feature key；游戏模式相关至少包括主开关 `0x28` 和低延迟 `0x06`。
