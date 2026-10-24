# 通透人声、贴合度检测与黄金听感

本轮为模块新增三项能力，协议与流程参考欢律 17.6.5、无线耳机 17.6.3 及
`heymelody-windows` 的公开研究记录；界面文案与耳机素材取自欢律 APK。

## 1. 通透人声增强

白名单 `function.noiseReductionMode` 里的子档决定入口：

| 型号 | 通透父档 | 子档 | 本模块展示 |
| --- | --- | --- | --- |
| Enco X2（063C10）、Enco X3（067410） | `modeType=2, index=2` | `modeType=6, index=9` | 「强化人声」下拉项 |
| Free2 / Free2i / Air2 Pro / R Pro | `modeType=2, index=2` | 顶层 `modeType=6, index=3` | 「聆听」下拉项 |

- 子档不再被误映射成自适应；`modeType=6` 在顶层是「聆听」，在通透子级才是「强化人声」。
- 选择变体直接发送 `0x0404` 位图，例如 idx9 → `01 01 00 02`；标准通透仍发父位 idx2。
- 位图回报通过 `AncModeParser` 的 `bitmapIndex` 定位当前变体，UI 再映射回下拉项。
- 文案：强化人声「弱化环境声的同时突出人声」；聆听「减弱周围噪音基础上保留人声/广播声」。

代码：`DeviceModelRegistry.buildAncOptions()`、`DeviceCapabilities.transparencyVariants`、
`DeviceProfile.transparencyVariants`、`AncModeParser.AncResult.bitmapIndex`。

## 2. 耳塞贴合度检测

| 方向 | 命令 | payload |
| --- | --- | --- |
| 查询佩戴 | `0x0109` | 空 |
| 佩戴回报 | `0x8109` | `[status][count]([component][bits])*`，bit1=入耳 |
| 开始/停止 | `0x0405` | `[01]` / `[00]` |
| 结果事件 | `0x0204` event 4 | `[04][01][left][02][right]`，结果 0=一般 1=良好 6=较差 |

流程：佩戴查询 → 双耳入耳 → `0405 [01]` → 等待事件 4（16 秒超时）→ `0405 [00]` →
左右结果卡片。启动 ACK 非 0 时映射官方状态码（9 通话、10 TWS 未连、11 佩戴、15 忙、16 低电量）。
事件 2 的主动佩戴上报也可作为查询回退。

代码：`CalibrationProtocol.parseFit/parseWear/parseWearEvent`、`CalibrationEngine.startFit()`、
`ui/FitTestPage.kt`。

## 3. 黄金听感

### 协议

| 方向 | 命令 | payload / reply |
| --- | --- | --- |
| 测听开始/停止 | `0x040D` | `[02,01]` / `[02,00]` |
| 耳扫开始/停止 | `0x040D` | `[04,01,UID:BE32]` / `[04,00,UID:BE32]` |
| 单频点 | `0x040E` | `[03,01,side,type,db:i8]`，side 1=左 2=右，type 1..6 |
| 暂停 / 停试听 | `0x040E` | `[04]` / `[02]` |
| 试听 / 保存 | `0x040E` | `[action,count,(side,type,db)*count,UID:BE32,name:UTF8]`，action 1/3 |
| 当前方案 | `0x0115` | `[status,03,12,(side,type,db)*12,UID:BE32,name]` |
| 滤波 | `0x0116` | 请求 `[count,items,UID]`；回报 `0x8116` |
| 描述 | `0x0411` | `[01,01,01,00,desc]` |
| 保存耳扫 | `0x0415` | `[03,len:LE16,blob,UID:BE32]` |
| 耳扫事件 | `0x0204` event 14 | `[action,len:LE16,blob,UID:BE32]` |
| 状态事件 | `0x0204` event 8 | `[type,status,...]`，type 2=测听 4=耳扫 |
| 开关 | `0x0403` | `[0B,01/00]`，回报 `0x810D` feature `0B` |

UID 是 Java signed int 的大端 4 字节，启动时随机生成正整数；保存后用 `0x0115`
校验 UID 与名称。名称限制 1–39 个 UTF-8 字节。

### 流程

- 支持 `earScan` 的机型：佩戴确认 → 耳扫（20 秒）→ 停止耳扫并等待 600 ms →
  `040D [02,01]` → 12 步滑块测听（V2，12 档 `[-55..17]`，初始 -19）。
- 不支持耳扫的机型：佩戴确认 → `040D [02,01]` → 12 步「听到 / 没听到」二分（V1，
  区间 -60..20，负区间阈值 4、非负区间阈值 2）。
- 每一步先 `040E [04]` 暂停，再发下一频点；最后请求 `0116` 生成滤波。
- 保存：`0411` 描述 → `040E action3` →（有耳扫时）`0415 action3` → `0403 [0B,01]` →
  `0115` 回读校验；失败保留结果页允许重试。

### 界面

- 详情页「听感与佩戴」卡片：贴合度检测、黄金听感（显示当前方案名）。
- 黄金听感页：当前方案、启用开关、12 步向导、结果页名称输入与试听增强。
- 页面退出时自动取消正在进行的检测。

## 4. 双进程接入

`CalibrationEngine` 只依赖「发包回调」与「能力回调」，因此：

- 应用内直连：`AppRfcommController.calibration`，UI 直接读 `StateFlow`。
- Hook 连接：`RfcommController.calibration`，状态经
  `ACTION_PODS_CALIBRATION_STATE`（JSON）广播给应用；应用经
  `ACTION_CALIBRATION_COMMAND` 下发命令。
- 热重载前 `RfcommController.shutdownForHotReload()` 会 `dispose()` 状态机，避免旧
  classloader 残留协程。

## 5. 素材来源

以下文件从欢律 17.6.5 APK（`com.heytap.headset`）资源中提取，放入
`app/src/main/res/drawable-nodpi/`：

| 模块文件 | 欢律资源 |
| --- | --- |
| `melody_ear_left.webp` | `melody_ui_image_ear_left_default`（xxxhdpi） |
| `melody_ear_right.webp` | `melody_ui_image_ear_right_default`（xxxhdpi） |
| `melody_hearing_analysis.webp` | `melody_ui_hearing_enhancement_analysis_default`（xxxhdpi） |

## 6. 验证边界

- `.\gradlew.bat :app:assembleDebug` 通过；`testDebugUnitTest` 通过（含
  `CalibrationProtocolTest` 与更新后的 ANC 白名单回归）。
- 未在 HyperOS + LSPosed 真机上执行贴合度/黄金听感；频点 Hz 映射与 V2 档位仅对
  Enco X4 v116 有公开机器码证据，其他固件以实际听感为准。
- 本模块只保存耳机当前方案，不维护手机端历史列表；结果页试听使用用户自己的音乐。
