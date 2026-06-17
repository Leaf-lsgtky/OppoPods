# HeyMelody 官方 App 协议线索补充

本文是独立补充文档，不依赖 README 或其它旧协议笔记。这里尽量把能从 OPPO/HeyMelody 官方 App、`C:\com.heytap.headset` 私有数据目录、以及当前抓包结论互相印证的内容写全。

旧文档和项目代码没有在本文中修改。

## 私有数据目录起到的作用

目录：

```text
C:\com.heytap.headset
```

它不是一个直接的“蓝牙抓包目录”，里面没有现成的 SPP/GATT 原始包流。但它非常有用，作用主要是把官方 App 的运行状态、机型资源和 UI 配置补出来：

| 作用 | 能回答的问题 | 本次实际发现 |
| --- | --- | --- |
| 确认当前耳机身份 | 当前官方 App 识别的是哪款耳机、哪个颜色、哪个 product id | `067410 / OPPO Enco X3 / colorId=3 / brand=oppo / type=T1` |
| 确认连接路径 | 这台耳机在官方 App 里走 classic SPP 还是 SPP over GATT | 反馈日志显示 `BRClientDevice` 且 `isSppOverGatt=false` |
| 关联 UI 和协议能力 | UI 里出现哪些功能，哪些功能可能触发对应设置包 | 日志出现 `SpatialAudio`、`GameSoundMutex`、`WearCheck`、`FeatureSwitch` |
| 映射触控 UI 值 | 触控动作页面里的 function/action/earType 是什么 | `control_067410_3/config.json` 给出暂停、下一首、音量、降噪控制等功能码 |
| 查本机缓存状态 | 电量、版本、提示音主题、听感增强数据等 | `data_collect` 记录 `battery=60|60|30`、`version=143.143.039`，`hearing_enhancement` 保存听感增强/耳扫曲线 |
| 判断哪些文件暂时不能直接读 | 白名单、shared_prefs 是否可直接解析 | 白名单是 AES-GCM + gzip；多数 prefs 是加密 XML |

所以这个目录的价值是“把耳机型号、UI 功能、官方 App 状态和协议代码对上”。它不能替代抓包，但能告诉我们应该抓哪些动作、哪些字段可能代表哪个 UI 设置。

本次最有用的文件：

```text
C:\com.heytap.headset\databases\melody-model.db
C:\com.heytap.headset\files\Documents\Oplus\Feedback\FbLog\24042601\fblog2026-06-06.txt
C:\com.heytap.headset\files\melody-model-download\control_067410_3\config.json
C:\com.heytap.headset\files\melody-model-download\fetch14_067410_3\config.json
C:\com.heytap.headset\files\melody-model-download\popup_067410_3_normal\config.json
C:\com.heytap.headset\files\melody-model-whitelist\encrypted.gz
```

可以把这个目录按用途拆成四块看：

| 区域 | 主要内容 | 协议分析价值 |
| --- | --- | --- |
| `databases` | 连接过的设备、机型、颜色、提示音/弹窗主题、听感增强历史 | 确认当前样本耳机身份；确认 App 已经缓存过哪些 UI 状态和曲线数据 |
| `files\melody-model-download` | `control_067410_3`、`fetch14_067410_3`、`popup_067410_3_normal` 等机型资源 | 把 UI 页面、触控动作、3D 模型、弹窗资源和 product/color 对上 |
| `files\Documents\Oplus\Feedback\FbLog` | 官方 App 运行日志 | 确认连接路径、通知注册、主动事件、固件埋点 JSON 和部分 UI 初始化状态 |
| `shared_prefs` / `melody-model-whitelist` | 加密 prefs、加密白名单 | 证明白名单是 App 本地能力来源之一；但脱离 App context 不能直接当明文协议表读 |

当前数据库确认：

```text
product_id   = 067410
product_name = OPPO Enco X3
brand        = oppo
type         = T1
colorId      = 3
version      = 143.143.039
battery      = 60|60|30
```

当前反馈日志确认：

```text
BRClientDevice ... isSppOverGatt = false
MSG_RECEIVE_REMOTE_VERSION left version = 143, right version =143
GameSoundMutexHelper ... gameSoundMutexes: [1]
SpatialAudioItem ... mSupportNewHeadsetSpatial: true
SpatialAudioHelper ... spatialType: 0
EarphoneRepository:updateFeatureSwitchToStatus IGNORE 5
WearCheckManager ... switchOpened=false
NotificationCommandManager: CMD_GET_NOTIFICATION_CAPABILITY_RSP
NotificationCommandManager: CMD_REGISTER_NOTIFICATION_MULTI_RSP ok
MSG_RECEIVE_HEARING_ENHANCEMENT_DATA
EarphoneRepository:EVENT_ID_BT_DEVICES_BURIED_POINT
EarphoneRepository:EVENT_ID_BT_CONNECT_DEVICES_INFO
```

## 结论摘要

- OPPO 耳机控制协议至少有两层：外层 `OPOv1` link frame，内层 `Packet`。
- 外层 `TotalLen / LinkLen` 不是固定 1 字节，而是 7-bit little-endian varint。
- 内层 packet 的 cmd 和 payload length 都是小端序。
- 普通请求/响应的 `Seq` 不使用 `0xFF`；`Seq=0xFF` 应作为耳机主动广播/主动上报处理。
- `0x0100` 段主要是请求/查询类，`0x0200` 段主要是主动上报/广播类，`0x0400` 段主要是设置/控制类。
- 请求的响应 cmd 通常是 `cmd | 0x8000`，例如 `0x010D -> 0x810D`。
- `BatchQuery / getFeatureSwitchStatus` 不是固定 payload。首字节是查询 feature 数量，例如 `0B` 表示后面一共 11 个 feature id。
- 不同耳机抓到的 BatchQuery 不同是正常现象，因为官方 App 会根据白名单能力动态拼 feature id 列表。
- 官方代码只明确暴露两个 OPPO UUID 和 GATT 派生 UUID；Java 层没有直接出现 `uihChannel5/15` 名称。按当前实测记录，两个 OPPO UUID 应对应 `uihChannel5/15`。

## 外层 OPOv1 Frame

证据类：

- `p633w6.C6789b`
- compiled from `OPOv1Wrapper.java`

简单非分片 frame：

```text
AA <LinkLen varint> <Control/FSN> 00 <inner packet>
```

字段说明：

| 字段 | 长度 | 说明 |
| --- | --- | --- |
| `AA` | 1 | SOF |
| `LinkLen` | 1..N | 7-bit little-endian varint |
| `Control/FSN` | 1 | 非分片常见 `00`；低 2 bit 可表示分片状态 |
| `00` | 1 | 非分片简单包中固定为 `00` |
| `inner packet` | `LinkLen - 2` | 内层 Packet |

非分片简单包里：

```text
LinkLen = innerPacket.length + 2
```

也就是说 `LinkLen` 包含 link-layer 的 `Control/FSN` 和后面的 `00`，但不包含最前面的 `AA` 和 `LinkLen` 自身。

### TotalLen / LinkLen Varint

官方 `OPOv1Wrapper.m12254k(byte[])` 从 `AA` 后面开始读长度：

```text
value = byte0低7位
如果 byte0 bit7=1，则继续读 byte1:
value += byte1低7位 << 7
如果 byte1 bit7=1，则继续读 byte2:
value += byte2低7位 << 14
...
```

例子：

| 字节 | 长度值 |
| --- | --- |
| `00` | 0 |
| `13` | 19 |
| `7F` | 127 |
| `80 01` | 128 |
| `81 01` | 129 |
| `AC 02` | 300 |

所以抓包/实现里不能把 `TotalLen` 写死为单字节。长度超过 `0x7F` 时，必须按 bit7 继续读取下一字节。

### BatchQuery 外层示例

抓到的例子：

```text
AA 13 00 00 0D 01 00 0C 00 0B 05 04 0B 11 13 18 06 1B 1C 27 28
```

拆分：

```text
AA
13
00 00
0D 01 00 0C 00 0B 05 04 0B 11 13 18 06 1B 1C 27 28
```

解释：

| 字节 | 含义 |
| --- | --- |
| `AA` | SOF |
| `13` | `LinkLen=19` |
| `00 00` | 非分片 link header |
| `0D 01` | 内层 cmd `0x010D`，小端 |
| `00` | seq |
| `0C 00` | payload length = 12，小端 |
| `0B ... 28` | payload |

校验：

```text
inner packet length = 5 byte header + 12 byte payload = 17
LinkLen = 2 byte link header + 17 byte inner packet = 19 = 0x13
```

## 内层 Packet

证据类：

- `p362b6.C2740a`
- compiled from `Packet.java`
- `p362b6.C2741b`
- compiled from `PacketFactory.java`

格式：

```text
offset 0..1  cmd，小端
offset 2     seq
offset 3..4  payload length，小端
offset 5..   payload
```

字段表：

| Offset | 长度 | 名称 | 说明 |
| --- | --- | --- | --- |
| 0 | 2 | `cmd` | 小端，例如 `0D 01` 是 `0x010D` |
| 2 | 1 | `seq` | 请求/响应匹配用；`FF` 特殊 |
| 3 | 2 | `payloadLen` | 小端 |
| 5 | N | `payload` | 长度为 `payloadLen` |

### Seq 规则

实现时按这个规则处理：

| Seq | 含义 |
| --- | --- |
| `00..FE` | 普通请求/响应 seq |
| `FF` | 耳机主动广播/主动上报，不应拿去匹配 App 发出的 pending request |

也就是说：

- App 主动发请求时，seq 不要分配 `0xFF`。
- 收到 `seq=0xFF` 的包时，应优先按 `0x0200` 段主动广播/主动上报处理。
- `seq=0xFF` 不是“某个请求刚好排到了 255”。

官方 `Packet` 的 key 计算方式：

```text
key = ((seq & 0xff) << 16) | (cmd & 0x7fff)
```

普通响应 cmd：

```text
rspCmd = (reqCmd & 0x7FFF) | 0x8000
```

例子：

| 请求 | 响应 |
| --- | --- |
| `0x010D` | `0x810D` |
| `0x0403` | `0x8403` |
| `0x0422` | `0x8422` |

主动广播则不是这个“请求 -> 响应”的关系。它通常表现为：

```text
<0x0200 段 cmd 小端> FF <payloadLenLE> <payload>
```

例如如果是 `0x0204` 主动状态类广播，内层开头形态会是：

```text
04 02 FF <lenLE> <payload>
```

具体 payload 仍要按对应广播 cmd 拆。

## 命令分组

结合抓包和官方代码，可按 cmd 高字节粗分：

| 范围 | 用途 | 例子 |
| --- | --- | --- |
| `0x0100` | 查询/请求 | 能力、电量、版本、功能开关、EQ、空间音频、游戏声音 |
| `0x0200` | 通知/上报机制 | `0x0200` 查询通知能力，`0x0201/0x0205` 注册通知，`0x0204` 承载耳机主动事件 |
| `0x0400` | 设置/控制 | 查找耳机、功能开关、ANC、按键、EQ、空间音频、游戏声音 |
| `0x0F00` | debug / 诊断 | debug switch |

请求类常见形态：

```text
cmd in 0x0100, seq = 00..FE
```

设置类常见形态：

```text
cmd in 0x0400, seq = 00..FE
```

广播类常见形态：

```text
cmd in 0x0200, seq = FF
```

但要注意：`0x0200` 这个具体 cmd 不是“所有广播”的意思。官方 App 里 `0x0200` 是 `getNotificationCapabilities`，真正的主动事件入口是 `0x0204 CMD_NOTIFICATION_EVENT`。

## `0x0200` 通知能力与 `0x0204` 主动事件

证据类：

- `com.oplus.melody.btsdk.protocol.commands.C4047b`
- compiled from `NotificationCommandManager.java`
- `C:\com.heytap.headset\files\Documents\Oplus\Feedback\FbLog\24042601\fblog2026-06-06.txt`

### 初始化链路

官方 App 连接后会先查耳机支持哪些通知事件：

```text
0x0200 CMD_GET_NOTIFICATION_CAPABILITY
payload = empty
```

响应：

```text
0x8200 CMD_GET_NOTIFICATION_CAPABILITY_RSP
payload = <status:1> <count:1> <eventCode:1>...
```

官方代码逻辑：

1. `status != 0` 时认为查询失败。
2. `count = payload[1]`。
3. `payload[2..]` 是耳机支持的事件码集合。
4. 如果耳机支持 `0x0205`，官方 App 发批量注册；否则逐个发 `0x0201` 单事件注册。
5. 注册后如果耳机支持 `0x0109`，还会再发 `getEarBudsStatus`。

单事件注册：

```text
0x0201 CMD_REGISTER_NOTIFICATION
payload = <eventCode:1>

0x8201 CMD_REGISTER_NOTIFICATION_RSP
payload = <status:1> <eventCode:1>
```

批量注册：

```text
0x0205 CMD_REGISTER_NOTIFICATION_MULTI
payload = <count:1> <eventCode:1>...

0x8205 CMD_REGISTER_NOTIFICATION_MULTI_RSP
```

本机日志能直接印证这条初始化链路：

```text
NotificationCommandManager:onReceivePacket.CMD_GET_NOTIFICATION_CAPABILITY_RSP
NotificationCommandManager:onReceivePacket.CMD_REGISTER_NOTIFICATION_MULTI_RSP ok, init cost time: 394
```

### 主动事件格式

注册完成后，耳机主动上报统一进入：

```text
0x0204 CMD_NOTIFICATION_EVENT
```

结合用户抓包结论，主动上报时内层 packet 应按：

```text
cmdLE = 04 02
seq   = FF
lenLE = payload length
payload = <eventCode:1> <eventData...>
```

也就是说：

```text
04 02 FF <lenLE2> <eventCode> <eventData...>
```

`Seq=FF` 不是普通请求的序号；普通请求序号保留 `00..FE`，`FF` 代表耳机主动广播/主动上报。

### 已反查到的 eventCode

下面表来自 `NotificationCommandManager.m7328b()` 的 dex 指令和 packed-switch 表。表里的 handler msg 是官方 App 内部 Handler 消息号，不是蓝牙协议字段，但能帮助确认业务含义。

| eventCode | 官方解析对象 / 日志 | handler msg | payload 形态 |
| --- | --- | --- | --- |
| `0x01` | Battery / `Receive Battery` | `0x0A` | 电量信息列表 |
| `0x02` | EarBudsStatus / `Receive EarBudsStatus` | `0x0C` | 左右耳佩戴/状态信息 |
| `0x03` | NoiseReduction umbrella | `0x14` / `0x17` / `0x2C` | eventData 首字节是子类型，分到 `NoiseReductionInfo`、`CurrentNoiseModeInfo`、`IntelligentNoiseModeInfo` |
| `0x04` | CompactnessDetectionInfo | `0x15` | 左右耳贴合度/紧密度检测信息，两段 `CompactnessDetectionInfo` |
| `0x05` | `GAME_MODE_CHANGED` | `0x33` | 游戏模式状态，部分机型按 product id 决定偏移 |
| `0x06` | MultiConnectInformations | `0x22` | 多设备连接状态 |
| `0x08` | `HEARING_ENHANCE_DETECTION_STATUS_CHANGED` | `0x19` / `0x2D` | 听感增强检测状态，payload 至少含类型/状态 |
| `0x09` | FreqPacket / `FreqDataCache` | `0x1A` | 听感增强/听力保护频率数据分片 |
| `0x0A` | Zen mode switch status | `0x23` | `[status]` |
| `0x0B` | PersonalizedNoiseReductionResult | `0x1D` | 个性化降噪结果 |
| `0x0D` | TriangleInfo / `TRIANGLE_INFO_CHANGED` | `0x3D` | Triangle 信息变化 |
| `0x0E` | EarScanResult | `0x2E` | 耳扫结果 |
| `0x0F` | PublicEvent bitmask | `0x4E` / `0x4F` / `0x56` | `<maskLE2><data...>`，bit `0x01` 简单状态，bit `0x04` HandheldDeviceInfo，bit `0x10` TapLevelSettingInfo |
| `0x10` | OneshotStateInfo / `ONESHOT_EVENT` | `0x51` | Oneshot 状态 |
| `0xF1` | UserInteractionEventInfo | `0x13` | 用户交互事件，字段见下 |
| `0xF2` | ConnectDevicesInfo | `0x27` | 连接过的设备列表 |
| `0xF4` | JsonDataInfo / `DEVICES_BURIED_POINT_EVENT` | `0x31` | UTF-8 JSON 诊断/埋点 |

`0xF1 UserInteractionEventInfo` 的 payload 去掉 eventCode 后是：

```text
<deviceType:1> <button:1> <buttonAction:1> <function:1> <scene:1> [optionLE2]...
```

这和 `0x0401 setKeyFunctions` 的字段有交集，但多了 `scene` 和可变数量的 `optionLE2`。它更像“耳机主动报告用户做了什么操作”，不是设置触控的请求包。

`0xF2 ConnectDevicesInfo` 的 payload 去掉 eventCode 后是：

```text
<count:1>
[
  <mac:6>
  <connectTimesLE:2>
  <nameLen:1>
  <nameUtf8:nameLen>
] * count
```

本机日志里已经出现：

```text
EarphoneRepository:EVENT_ID_BT_CONNECT_DEVICES_INFO
```

`0xF4 JsonDataInfo` 的 payload 去掉 eventCode 后是 UTF-8 JSON。日志中实际看到的 `cmd` 包括：

```text
bt_acl
tws_acl
btad
twsad
tnc
overload_business
hardware_crash
adaptive_general_info
lea_link_Info
```

这说明 `0x0204` 不只是 UI 状态变化，也承载固件诊断、连接统计、异常和 LE Audio 链路信息。官方 App 收到 `0xF4` 后还会调用 `onResponseCommandEvent` 给耳机回包，payload 形态是：

```text
<status:1> <eventCode:1> [rspData...]
```

## UUID / Channel

证据类：

- `p553p6.AbstractC6303b`
- `p623v7.C6745e`
- `p529n6.C6125f`

官方代码中出现的 UUID：

| 用途 | UUID |
| --- | --- |
| BR/SPP 默认 UUID | `00001107-D102-11E1-9B23-00025B00A5A5` |
| 白名单默认 UUID 之一 / GATT service | `0000079A-D102-11E1-9B23-00025B00A5A5` |
| GATT write characteristic | `0100079A-D102-11E1-9B23-00025B00A5A5` |
| GATT notify/read characteristic | `0200079A-D102-11E1-9B23-00025B00A5A5` |

当前结论：

- 官方 Java 层未搜到 `uihChannel`、`channel5`、`channel15` 字样。
- 按当前实测记录，两个 OPPO UUID 应对应 `uihChannel5` 和 `uihChannel15`。
- 本文把 UUID 和官方 App 连接方式作为代码证据；`uihChannel5/15` 是实测映射，不是官方 Java 符号名。

建议在实现里把两套名字同时写清楚：

| 官方 UUID | 实测 channel |
| --- | --- |
| `00001107-D102-11E1-9B23-00025B00A5A5` | `uihChannel5` / classic SPP |
| `0000079A-D102-11E1-9B23-00025B00A5A5` | `uihChannel15` / GATT service 族 |

本机私有日志里的 `067410 / OPPO Enco X3`：

```text
BRClientDevice ... isSppOverGatt = false
```

说明这台设备在这次日志里走 classic SPP，不是 SPP over GATT。

## BatchQuery / Feature Switch

证据类：

- `com.oplus.melody.btsdk.protocol.commands.C4048c.m7351l`
- `com.oplus.melody.btsdk.ota.FeatureSwitchInfo`
- `com.google.gson.internal.C3676a.m7042G`

官方方法名：

```text
getFeatureSwitchStatus
```

cmd：

```text
0x010D
```

请求 payload：

```text
<count:1> <featureId:1>...
```

### 当前抓包示例

Payload：

```text
0B 05 04 0B 11 13 18 06 1B 1C 27 28
```

这里的 `0B` 不是 feature id，而是 Query 功能数量：

```text
0B = 11
```

所以后面总共 11 个 feature id：

```text
05 04 0B 11 13 18 06 1B 1C 27 28
```

完整 inner packet：

```text
0D 01 00 0C 00 0B 05 04 0B 11 13 18 06 1B 1C 27 28
```

逐字段拆：

| 字节 | 含义 |
| --- | --- |
| `0D 01` | cmd `0x010D` |
| `00` | seq |
| `0C 00` | payloadLen = 12 |
| `0B` | count = 11 |
| `05 04 0B 11 13 18 06 1B 1C 27 28` | 11 个 feature id |

完整外层：

```text
AA 13 00 00 0D 01 00 0C 00 0B 05 04 0B 11 13 18 06 1B 1C 27 28
```

### 为什么不同耳机 BatchQuery 不同

官方 App 不是写死一串固定 feature id。`PollCommandManager.m7351l` 的逻辑是：

1. 创建一个 `IntBuffer`。
2. 先固定加入 `5`。
3. 读取当前耳机的 `WhitelistConfigDTO.Function`。
4. 哪个功能在白名单里支持，就追加对应 feature id。
5. 最后生成 payload：`count + featureId...`。

因此同一个 App 连接不同耳机时，`0x010D` 的 payload 会变。抓多个耳机发现 BatchQuery 不是固定数据，这和官方实现一致。

### 官方可见 feature id 列表

| Dec | Hex | 官方条件 / 含义 |
| --- | --- | --- |
| 5 | `0x05` | 固定加入；日志里出现 `updateFeatureSwitchToStatus IGNORE 5` |
| 4 | `0x04` | `wearDetection`，佩戴检测 |
| 11 | `0x0B` | `hearingEnhancement` / `hearingEnhancementNew` |
| 12 | `0x0C` | `personalNoise` |
| 13 | `0x0D` | `clickTakePic` / `clickTakePicNew` |
| 15 | `0x0F` | `zenMode` |
| 17 | `0x11` | `multiDevicesConnect` |
| 9 | `0x09` | `vocalEnhance` |
| 19 | `0x13` | `headSetSoundRecord` |
| 24 | `0x18` | `highToneQuality` |
| 23 | `0x17` | `longPowerMode` |
| 21 | `0x15` | `smartCall` |
| 22 | `0x16` | `deviceLostRemind` |
| 20 | `0x14` | `voiceWake` |
| 25 | `0x19` | `voiceCommand` |
| 6 | `0x06` | `gameMode` |
| 27 | `0x1B` | `spatialTypes` |
| 29 | `0x1D` | `bassEngineSupport` |
| 28 | `0x1C` | `controlAutoVolumeSupport` |
| 30 | `0x1E` | `collectLogs` |
| 33 | `0x21` | `gameEqPkgList` |
| 31 | `0x1F` | 平台条件 `C0282d.m617e()`，官方代码未给出直观字段名 |
| 34 | `0x22` | `spineHealth` 相关 |
| 35 | `0x23` | `spineHealth` 相关 |
| 36 | `0x24` | `spineHealth` 相关 |
| 39 | `0x27` | `gameSoundList` 或支持 `0x0423` |
| 40 | `0x28` | `gameSoundList` 或支持 `0x0423` |
| 48 | `0x30` | `adaptiveVolume` |
| 49 | `0x31` | `adaptiveEar` |
| 50 | `0x32` | `speechPerception` |
| 52 | `0x34` | `meetingAssistant` |
| 53 | `0x35` | `longPressVolume` |
| 55 | `0x37` | `swiftPair` |
| 56 | `0x38` | `hearingOptimize` |
| 57 | `0x39` | `incomingCallControl` |

注意：

- `28(dec) = 0x1C`，官方代码条件是 `controlAutoVolumeSupport`。
- `40(dec) = 0x28`，官方代码条件和 `gameSoundList / 0x0423` 相关。
- 项目里看到的 `featureId=0x28` 是十六进制 `0x28`，不是十进制 `28`。

### FeatureSwitch 响应

响应 payload 结构由 `FeatureSwitchInfo` 描述：

```text
<count:1> <featureType:1> <status:1> ...
```

每个 item 固定 2 字节：

```text
featureType, status
```

如果响应 cmd 是 `0x810D`，inner packet 形态是：

```text
0D 81 <seq> <payloadLenLE> <payload>
```

如果耳机主动推送 feature 状态，并且 seq 是 `FF`，就不能按 request/response 匹配，应按主动广播路径处理。

## 设置项与蓝牙命令

主要证据类：

- `p232R5.C1290a`
- compiled from `BtOperate.java`
- `com.oplus.melody.model.repository.earphone.C4365J`
- `com.oplus.melody.model.repository.earphone.C4392q`
- `com.oplus.melody.model.repository.earphone.C4395t`
- `com.oplus.melody.btsdk.protocol.commands.*Info`
- `p362b6.C2742c`
- compiled from `Protocol.java`

### 查询类命令

| 设置项 / 查询项 | Cmd | 官方方法 / 类 | Payload |
| --- | --- | --- | --- |
| 查远端能力 | `0x0100` | `PollCommandManager.getRemoteCapability` | 空 |
| 查 MTU | `0x0101` | `getRemoteMTU` | `00 02`，即 512 小端 |
| 查 VID | `0x0102` | `getRemoteVID` | 本机 VID，小端 |
| 查 PID | `0x0103` | `getRemotePID` | 空 |
| 查版本 | `0x0105` | `getRemoteVersion` | 空 |
| 查电量 | `0x0106` | `getBatteryLevel` | 空 |
| 查触控按键 | `0x0108` | `getKeyFunction` | `<count> <deviceType...>`，官方常请求 `02/03/01` |
| 查当前降噪 | `0x010C` | `getCurrentNoiseReductionMode` | `01 01` |
| 查支持降噪模式 | `0x010C` | `getSupportNoiseReductionMode` | `03 01` |
| 查降噪切换项 | `0x010C` | `getNoiseReductionSwitchMode` | `02 01` 或 `02 03`、`02 04` |
| 查智能降噪 | `0x010C` | `getIntelligentNoiseReductionMode` | `04 01` |
| 查功能开关 | `0x010D` | `getFeatureSwitchStatus` | 动态 `<count><featureIds...>` |
| 查 EQ 模式 | `0x010F` | `getCurrentEqualizerMode` | 空 |
| 查编码类型 | `0x0114` | `getCurrentCodecType` | 空 |
| 查耳机恢复数据 | `0x0118` | `getEarRestoreData` | `<count><dataIds...>` |
| 查 Triangle 信息 | `0x011C` | `getTriangleInfo` | 空 |
| 查耳扫数据 | `0x011E` | `getEarScanData` | 空 |
| 查耳扫过滤数据 | `0x011F` | `getEarScanFilterData` | `<lenLE2><data><intBE4>` |
| 查耳机提示音数据 | `0x0121` | `getEarToneData` | 空 |
| 查所有 EQ | `0x0122` | `getAllEqInfo` | 空 |
| 查账号 key | `0x0125` | `getAccountKey` | 空 |
| 查空间音频类型 | `0x012A` | `getHeadsetSpatialType` | 空 |
| 查游戏声音 | `0x012B` | `getGameSoundInfo` | 空 |
| 查 AI 摘要类型 | `0x012E` | `getAISummaryType` | 空 |
| 查 Multi SPP command info | `0x012F` | `getMultiSppCommandInfo` | 空 |
| 查提示音音量 / VolumeValueInfo | `0x0130` | service command `304`，`handleReceiveVolumeValueInfo` | 空；响应见下文 |
| 查点击强度 / TapLevelSetting | `0x0133` | service command `307`，`TapLevelSettingInfo` | 空；响应见下文 |

### 设置类命令

| 设置项 | Cmd | 官方方法 / 类 | Payload |
| --- | --- | --- | --- |
| 查找耳机 / find mode | `0x0400` | `BtOperate.m2696I` | `[01]` 开启，`[00]` 关闭 |
| 设置触控按键 | `0x0401` | `BtOperate.m2699L` | `<count> [deviceType, button, buttonAction, function]...` |
| 功能开关 | `0x0403` | `BtOperate.m2704Q` | `[featureId, status]`，`status=01/00` |
| 设置当前降噪 | `0x0404` | `BtOperate.m2692E` / `CurrentNoiseModeInfo` | `type=1` 时 `01 01 <modeBitmask...>`；`type=2` 时 `01 02 <level>` |
| 设置支持降噪信息 | `0x0404` | `BtOperate.m2703P` / `NoiseReductionInfo` | `[action, type, valueLE(1..4 bytes)]` |
| 贴合度/紧密度检测开关 | `0x0405` | `C1291b.m2741b` | `[status]` |
| 设置 EQ 模式 | `0x0406` | `BtOperate.m2695H` | `[eqModeType]` |
| 关联设备 / 多设备相关 | `0x0408` | `BtOperate.m2701N` | `[hostType][hostMac6][count][type][mac6][state]...` |
| 听感增强检测流程 | `0x040D` / `0x040E` | `BtOperate.m2739z` / `m2691D` / `m2712Y` | 按 action/type 组合，需按场景抓包 |
| 系统相机状态 | `0x040F` | `BtOperate.m2690C` | `[status]` |
| Zen mode 校验信息 | `0x0410` | `BtOperate.m2705R` | `ZenModeFileVertifyInformation.getData()` |
| 耳机恢复数据 | `0x0411` | `BtOperate.m2693F` | `<count> <EarRestoreDataInfo...>` |
| 个性化降噪 | `0x0412` | `BtOperate.m2700M` | `[value]` |
| 自由对话恢复时间 | `0x0414` | `BtOperate.m2697J` | `[type]` |
| 自定义 EQ 信息 | `0x0418` | `BtOperate.m2694G` | `[action,min,max,eqId,nameLen,name...,bandCount,(freqLE2,db)...]` |
| 游戏 EQ 状态 | `0x0420` | `C4365J.mo7870r0` / service param `4170` | service 传 `game_type, game_status`；实际 payload 需抓包确认 |
| 脊椎范围检测 | `0x0421` | `BtOperate.m2702O` | `[status, step]` |
| 设置耳机空间音频类型 | `0x0422` | `BtOperate.m2698K` | `[type]` |
| 游戏声音类型启用 | `0x0423` | `C5615d.mo9858i` / `C5614c` / action `4173` | service 传 `game_sound_type, game_sound_type_enable`；底层 payload 仍需抓包确认 |
| LE Audio action | `0x0424` | `C4365J.mo7880w0` | service 传 `type,value`；payload 需抓包确认 |
| 设置提示音音量 / VolumeValueInfo | `0x0427` | `C4365J.mo7819J0` / action `4181` | `[value]` |
| 设置点击强度 / TapLevelSetting | `0x042D` | `C4365J.mo7815H0` / action `4189` | `[value]` |
| Debug switch | `0x0F00` | `BtOperate.m2711X` | `[i3, i4, i10]` |

### `0x0403 setSwitchFeature`

官方方法：

```text
BtOperate.m2704Q(address, featureId, enabled, needGetState)
```

Payload：

```text
<featureId:1> <status:1>
```

其中：

```text
status = 01 开
status = 00 关
```

Inner packet 模板：

```text
03 04 <seq> 02 00 <featureId> <status>
```

如果 `featureId=0x28` 且开启：

```text
03 04 <seq> 02 00 28 01
```

对应简单外层：

```text
AA 09 00 00 03 04 <seq> 02 00 28 01
```

校验：

```text
inner length = 5 + 2 = 7
LinkLen = 2 + 7 = 9
```

注意：`0x0403 featureId=0x28` 和 BatchQuery 表里的 `28(dec)=0x1C` 不是同一个写法。文档和代码里建议同时写十进制和十六进制。

还有一个官方特例：`BtOperate.m2704Q` 里如果 `featureId == 31(dec) / 0x1F`，即使 capability 检查不通过也会继续发 `0x0403`。官方日志把它标成 `CMD_SWITCH_FEATURE_LEA`。所以“能力表没声明但仍然发设置包”的情况，至少对 LEA 开关是官方行为，不一定是抓包或解析错。

### `0x0404 setCurrentNoiseReduction`

官方方法：

```text
BtOperate.m2692E(address, CurrentNoiseModeInfo)
```

`CurrentNoiseModeInfo.getData()`：

```text
type = 1:
01 01 <modeBitmask...>

type = 2:
01 02 <level>
```

`type=1` 时，`modeBitmask` 是 bitset：

```text
bit0 => mode index 0
bit1 => mode index 1
bit2 => mode index 2
...
```

如果只打开 mode index 2，则 bitmask 第一字节是：

```text
04
```

对应 payload：

```text
01 01 04
```

Inner packet：

```text
04 04 <seq> 03 00 01 01 04
```

同一个 cmd `0x0404` 还会承载 `NoiseReductionInfo`：

```text
<action:1> <type:1> <valueLE:1..4>
```

这个结构用于“支持降噪信息/降噪选项”类设置，不要和当前 ANC mode 的 `01 01 <bitmask>` 混掉。

### `0x0401 setKeyFunctions`

官方方法：

```text
BtOperate.m2699L(address, protocol, keyFunctionInfoList)
```

默认 protocol 常见是：

```text
0x0401
```

Payload：

```text
<count:1> [deviceType, button, buttonAction, function] * count
```

每个 `KeyFunctionInfo` 固定 4 字节：

```text
deviceType
button
buttonAction
function
```

官方设置完按键后会再发 `getKeyFunction` 查询：

```text
cmd = 0x0108
```

所以如果抓“修改触控动作”的完整流程，通常会看到：

```text
0x0401 setKeyFunctions
0x8401 response
0x0108 getKeyFunction
0x8108 response
```

### `0x0422 setHeadsetSpatialType`

官方方法：

```text
BtOperate.m2698K(type, address)
```

Payload：

```text
<type:1>
```

已按当前实机和 fork 交叉验证采用三档：

| type | OppoPods 文案 | MiLink 对应 |
| ---: | --- | --- |
| `0x00` | 关闭 | `SpatialMode.CLOSE = 0` |
| `0x01` | 固定 | `SpatialMode.OPEN = 1` |
| `0x02` | 头部跟踪 | `SpatialMode.PHONE_HEAD_TRACKING = 9` 或 `EARPHONE_HEAD_TRACKING = 11` |

Inner packet：

```text
22 04 <seq> 01 00 <type>
```

OppoPods 使用固定 seq `F0` 时，完整外层包为：

```text
AA 08 00 00 22 04 F0 01 00 00  # 关闭
AA 08 00 00 22 04 F0 01 00 01  # 固定
AA 08 00 00 22 04 F0 01 00 02  # 头部跟踪
```

相关响应 / 上报：

| Cmd | 方向 | 说明 |
| --- | --- | --- |
| `0x8422` | 耳机响应设置结果 | payload 首字节为 status |
| `0x0510` | 耳机主动上报 | payload 首字节为当前 type |

本机日志里：

```text
SpatialAudioItem ... mSupportNewHeadsetSpatial: true
SpatialAudioHelper ... spatialType: 0
```

说明 `067410 / OPPO Enco X3` 当前官方 App UI 支持新空间音频逻辑，当前类型为 `0`。

### OppoPods MiLink 空间音频接入

目标是在 HyperOS MiLink 卡片里显示/隐藏空间音频入口，同时保持 OppoPods 首页下拉菜单始终可用。

设置项：

```text
在 MiLink 卡片添加空间音频选项
```

偏好键：

```text
milink_spatial_audio_option_enabled
```

广播：

```text
ACTION_MILINK_SPATIAL_AUDIO_OPTION_CHANGED
```

实现要点：

- 开启时：MiLink 侧 `getSpatialMode`、`getMiAudioEffect`、`getAudioSpatialEffectState` 等 getter 返回当前状态；`setSpatialMode`、`setMiAudioEffect`、`setHeadTracking`、`setAudioEffectState` 映射为 OPPO `0x0422`。
- 关闭时：MiLink 空间音频相关 getter 返回 unsupported，`isSupportAudioSwitch` / `getSwitchState` 返回不支持；MiLink 发起的设置命令只更新/吞掉结果，不发送 OPPO 包。
- MiLink 值到 OPPO 值映射：`0 -> 0`，`1 -> 1`，`9/11 -> 2`。
- UI 刷新时同步 `AncBatteryModel.spatialState`、`deviceSpatialType`，并通知 updateType `9` 和 `4`。

### `0x012B / 0x0423 game sound`

官方能力表 `Protocol` 里有：

```text
0x012B getGameSoundInfo
0x0423 setGameSound
```

数据类：

```text
GameSoundInfo:
  selectType
  supportTypes[]
```

`0x012B` 查询响应在 dexdump 里能看到解析逻辑：

```text
payload = <status> <selectType> <count> <supportType...>
```

官方处理流程：

1. 先读 `status`，`0` 表示成功。
2. `payload[1]` 作为 `selectType`。
3. `payload[2]` 作为 `supportTypes` 数量。
4. 从 `payload[3]` 开始逐字节读 `supportTypes[]`。

也就是说，`0x012B` 不是单纯读一个开关，而是读“当前选中的游戏声音类型 + 支持的类型列表”。

`0x0423` 设置侧的 repository/service 层传参是：

```text
param_game_sound_type
param_game_sound_type_enable
```

证据：

```text
C5615d.mo9858i(type, enable, address) -> command key 1059
C5614c.get() -> action 4173
Intent extras:
  param_address
  param_game_sound_type
  param_game_sound_type_enable
```

目前能确认：

- `0x012B` 用于查询游戏声音信息。
- `0x0423` 用于设置某个游戏声音类型是否启用。
- 本机日志出现 `GameSoundMutexHelper ... gameSoundMutexes: [1]`。
- `Protocol` 表中 `1059(dec)=0x0423`，配对查询是 `299(dec)=0x012B`。

注意不要把三件事混成一个：

| 功能 | 命令 | 证据 | 说明 |
| --- | --- | --- | --- |
| 游戏模式/低延迟主开关 | `0x0403 featureId=0x28` | BatchQuery 可出现 `40(dec)=0x28`，App 条件和 `gameSoundList / 0x0423` 相关 | 这是 feature switch，不是 `0x0423` |
| 游戏 EQ 状态 | `0x0420` | `C4365J.mo7870r0`，action `4170`，extras `param_game_type / param_game_status` | 和 EQ package / game type 相关 |
| 游戏声音类型启用 | `0x0423` | `C5615d/C5614c`，action `4173`，extras `param_game_sound_type / param_game_sound_type_enable` | 按声音类型开关 |

还不能仅凭当前反编译文本完全确认 `0x0423` 底层 payload 顺序，因此暂记为：

```text
疑似 payload = <game_sound_type> <enable>
```

需要抓“游戏声音”开关或选项变化确认。

### `0x0427 VolumeValueInfo`

官方路径：

```text
C4365J.mo7819J0(value, address)
  -> command key 1063(dec) = 0x0427
  -> C4392q case 6
  -> action 4181
  -> param_volume_value_info
```

dexdump 里 service 接收端能看到最终发包：

```text
cmd = 1063(dec) = 0x0427
payload = [value]
```

所以设置包模板是：

```text
27 04 <seq> 01 00 <value>
```

对应查询是：

```text
0x0130 / 304(dec) getVolumeValueInfo
```

查询响应 payload：

```text
<status:1> <value:1>
```

官方收到后写入 `DeviceInfo.setVolumeValueInfo(value)`，UI 侧对应 `PromptVolumeItem` / `VolumeValueInfoVO`。

### `0x042D TapLevelSetting`

官方路径：

```text
C4365J.mo7815H0(value, address)
  -> command key 1069(dec) = 0x042D
  -> C4392q case 7
  -> action 4189
  -> param_tap_level_setting_value
```

dexdump 里 service 接收端同样能看到最终发包：

```text
cmd = 1069(dec) = 0x042D
payload = [tapLevelSettingValue]
```

所以设置包模板是：

```text
2D 04 <seq> 01 00 <value>
```

对应查询是：

```text
0x0133 / 307(dec) getTapLevelSettingValue
```

普通查询响应由 `TapLevelSettingInfo` 解析：

```text
<status:1> <tapLevelSettingValue:1> <tapLevelDefaultValue:1>
```

如果它来自主动通知事件，`TapLevelSettingInfo(offset, data, true)` 只读当前值，不再读 default value。也就是说同一个数据类会按“查询响应”和“通知事件”两种场景解释 payload。

## OPPO Enco X3 触控配置

关键文件：

```text
C:\com.heytap.headset\files\melody-model-download\control_067410_3\config.json
```

这个文件是官方 App 下载的机型 UI 模型。它不是蓝牙包，但它把触控页面里的动作和功能码列出来了。

`controlPages`：

| UI 文案 | function | earType | action | 动画 |
| --- | --- | --- | --- | --- |
| 暂停音乐 | `0x15` | `0x04` | `0x01` | single pinch |
| 继续播放 | `0x15` | `0x04` | `0x01` | single pinch |
| 下一首 | `0x15` | `0x04` | `0x02` | double pinch |
| 音量加 | `0x0B` | `0x04` | `0x07` | slide up |
| 音量减 | `0x0C` | `0x04` | `0x08` | slide down |
| 降噪控制 | `0x08` | `0x04` | `0x04` | long pinch |
| 降噪模式 | `0x08` | `0x04` | `0x04` | long pinch |

Lottie type：

| type | 动作 |
| --- | --- |
| `16` | single pinch |
| `17` | double pinch |
| `18` | triple pinch |
| `5` | slide up |
| `20` | long pinch |

与 `KeyFunctionInfo` 的关系：

```text
KeyFunctionInfo item = [deviceType, button, buttonAction, function]
```

`control_067410_3/config.json` 能直接对应：

```text
deviceType   ≈ earType
buttonAction = action
function     = function
```

但 `button` 字段还没有被这个 JSON 直接给出。它可能来自：

- 左右耳槽位
- 动作类型
- lottie type
- 官方 UI 内部 control type

所以触控设置还需要抓 `0x0401 setKeyFunctions` 包确认。

## 私有数据目录其它内容

### 数据库表

数据库：

```text
C:\com.heytap.headset\databases\melody-model.db
```

有用表：

| 表 | 发现 |
| --- | --- |
| `connected_device` | 当前连接过 `067410 / OPPO Enco X3` |
| `melody_equipment` | `colorId=3`，`channelSwitch=-1`，`multiConversationSwitch=-1` |
| `data_collect` | 记录设备版本、电量、手机型号、系统版本等 |
| `hearing_enhancement` | 有听感增强/耳道扫描数据 JSON |
| `persnoal_dress` | 个性化弹窗/提示音主题资源，含 `.bin` 提示音下载 URL |
| `persnoal_dress_series` | 个性化主题分组 |
| `provisional_whitelist` | 当前为空 |
| `zenmode_resource_info` | 当前为空 |

本次实际读取到的关键行：

```text
connected_device:
  product_id   = 067410
  product_name = OPPO Enco X3
  brand        = oppo
  type         = T1
  mac_address  = 加密后的本机 MAC

melody_equipment:
  productId                 = 067410
  colorId                   = 3
  name                      = OPPO Enco X3
  autoOTASwitch             = -1
  channelSwitch             = -1
  multiConversationSwitch   = -1
  reconnectPopupSwitch      = -1

data_collect:
  locale      = zh_CN
  phoneModel  = 25113PN0EC
  phoneBrand  = Xiaomi
  appVersion  = 16.6.3
  android     = 16
  battery     = 60|60|30
  version     = 143.143.039
  productId   = 067410
  colorId     = 3
```

`hearing_enhancement` 不是简单开关表，而是保存了一整份听感增强/耳扫 JSON，里面包括：

```text
mEarScanData
mEarScanFrequencyLeftCurveData
mEarScanFrequencyRightCurveData
mHearingEnhancementList
mEnhanceType
mType
```

这类数据能帮助判断 App 已经执行过耳扫/听感增强流程，但不能直接当成某一个设置包的 payload；真正下发时还要看 `0x040D/0x040E` 相关流程和分包。

`persnoal_dress` / `persnoal_dress_series` 存的是主题、弹窗和提示音资源。里面的 `.bin` 提示音 URL 很有价值，因为日志里可以看到官方 App 会把这些 `.bin` 当作 `deviceType=4` 的升级/传输任务发给耳机：

```text
startUpgrade ... upgradeType=0 deviceType=4 file=...tone_067410\1011.bin
startUpgrade ... upgradeType=0 deviceType=4 file=...tone_067410\1009.bin
```

这说明“提示音主题”不是 `0x0403` 这种普通开关，而更像固件资源传输/升级通道，应该和普通设置包分开抓。

### 模型资源

```text
C:\com.heytap.headset\files\melody-model-download\fetch14_067410_3\config.json
```

内容主要是 3D 模型：

```json
{
  "model3D": {
    "path": "res/raw/detail_model.vfxms"
  }
}
```

```text
C:\com.heytap.headset\files\melody-model-download\popup_067410_3_normal\config.json
```

内容主要是弹窗动画、连接/断开状态 UI，不直接给蓝牙包。

### 白名单文件

```text
C:\com.heytap.headset\files\melody-model-whitelist\encrypted.gz
```

它不是普通 gzip。官方 `WhitelistRepository` 代码显示格式是：

```text
12-byte GCM IV + AES/GCM/NoPadding + gzip content
```

密钥来自 `melody-model-settings` 的：

```text
whitelistSecretKey
```

但本机 shared prefs 多数是 AndroidX Security 加密 XML。脱离 App context 暂时不能直接还原白名单明文。

## 实现注意事项

1. 外层长度必须按 7-bit varint 解析和编码。
2. 内层 cmd、payload length 都按小端处理。
3. 普通 request seq 保留 `00..FE`，不要分配 `FF`。
4. 收到 `seq=FF` 时，按耳机主动广播/主动上报分发。
5. `0x010D BatchQuery` payload 首字节是数量，例如 `0B` 表示后面 11 个 feature id。
6. `0x010D` 的 feature 列表按耳机白名单动态变化，不能写死一套全机型共用。
7. `0x0403` 的 `featureId=0x28` 和 `BatchQuery` 表里的 `28(dec)=0x1C` 必须区分。
8. `0x0403 featureId=0x28`、`0x0420 game EQ`、`0x0423 game sound type enable` 应分成三个概念记录。
9. 十进制命令号不要心算错：`1063(dec)=0x0427`，`1069(dec)=0x042D`，不是 `0x042F/0x0435`。
10. `0x0427` 和 `0x042D` 的设置 payload 已能从 dexdump 看到是单字节 `[value]`；实际 UI 值域仍建议通过操作 App 抓一次确认。

## 仍需抓包确认

优先抓这些动作：

1. 切一次佩戴检测，确认 `featureId=0x04` 的 `0x0403` 设置和响应。
2. 切一次项目里的游戏主开关，确认 `featureId=0x28` 是否固定对应该 UI。
3. 切一次游戏声音，确认 `0x0423` payload 顺序。
4. 切一次游戏 EQ，确认 `0x0420` payload。
5. 切一次提示音音量和点击强度，回归确认 `0x0427/0x042D` 的 UI 值域。
6. 改一次触控动作，抓 `0x0401 setKeyFunctions`，确认 `button` 字段如何从 `control_067410_3/config.json` 映射。
7. 抓一包长度超过 `0x7F` 的设置或查询，验证外层 `LinkLen` varint 写法。
8. 抓 `0x0200` 段主动上报，重点看 `seq=FF` 时的 cmd、payload 和 UI 状态变化关系。
9. 如果要从系统层进一步坐实 `uihChannel5/15`，需要抓蓝牙 socket/channel 建连日志；官方 App Java 层目前只给出 UUID。
