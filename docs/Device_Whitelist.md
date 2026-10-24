# 机型能力表（device_models.json）的来源与更新

项目内嵌的 `app/src/main/assets/device_models.json` 来自欢律（`com.heytap.headset`）
的云端机型白名单。这份文件决定每个型号「支持哪些功能」，以及 ANC 的
`protocolIndex` 位号——位号错一位，按降噪就会变成通透。

## 官方 App 里的两份白名单

设备上能取到两份，格式相同（`WhitelistContentDO`），只是加密方式不同：

| 位置 | 形态 | 说明 |
| --- | --- | --- |
| `files/melody-model-whitelist/encrypted.gz` | 加密 | `12 字节 IV` + `AES/GCM/NoPadding` + `gzip` + JSON |
| `files/melody-model-whitelist/outside_snapshot.json` | **明文** | 外部快照，只有 `name/pid/type/wear`，没有 `function` |
| `cache/<uuid>` | **明文** | 网络下载后的缓存，含完整 `compatWhiteList` / `whiteList` |

加密那份的密钥是 App 首次启动随机生成、存在
`shared_prefs/melody-model-settings-encrypted.xml` 的 `whitelistSecretKey`
（AndroidX `EncryptedSharedPreferences`，由 Keystore 包裹），
**换机不可复用，也无法离线解开**。所以更新能力表不要走这条路。

真正可用的是明文缓存：App 联网拉取白名单后会把原始 JSON 落到 `cache/` 下。

## 取表步骤

```powershell
# 手机需 root（KernelSU / Magisk）；先触发欢律联网刷新一次白名单
adb shell su -c "grep -rl spatialTypes /data/data/com.heytap.headset 2>/dev/null"
adb shell su -c "cat <上一步命中路径> > /sdcard/wl_plain.json"
adb pull /sdcard/wl_plain.json
```

顶层字段：

```json
{ "versionCode": 0, "compatVersionCode": 117006008,
  "compatWhiteList": [ ... ], "whiteList": [ ... ] }
```

单条结构（与项目资产里的条目一致）：

```json
{ "id": "06EC10", "name": "OPPO Enco X4", "supportSpp": true, "minVersion": 16010000,
  "children": [ { "name": "OPPO Enco Bohr" } ],
  "function": { "noiseReductionMode": [ {"modeType":5,"protocolIndex":1}, ... ], ... } }
```

## 合并规则

项目表是「历史并集」，**不要**直接用官方文件整体替换：

- 官方 `compatWhiteList` 的字段比历史条目少（例如 X3 少了 `spineHealth`、
  `dolbyAtmos` 等），整体替换会让已有型号能力回退。
- 官方 `whiteList` 可能为空数组，能力只体现在 `compatWhiteList`。

因此按 `id` 取并集：已有条目原样保留，缺失的型号追加。当前表 141 条，
其中 4 条来自 2026-10 的 `compatWhiteList`：
`06EC10` OPPO Enco X4、`06F450` OPPO Enco Buds3s、
`067814` OnePlus Buds Pro 4、`06E410` OnePlus Buds Clip。

`children` 里的名称是同一硬件的不同销售名，`DeviceModelRegistry.byDeviceName`
会把它们一起参与匹配，否则「OPPO Enco Bohr」这类型号只能靠 productId 命中。

合并后跑 `:app:testDebugUnitTest`，`CapabilityProfileFactoryTest` 会校验
新型号的存在性、X4 的能力位与 ANC 位号。
