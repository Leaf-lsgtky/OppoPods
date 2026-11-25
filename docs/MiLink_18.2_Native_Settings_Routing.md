# 设备互联 18.2：让“更多设置”原生进入耳机插件页

日期：2026-11-22。关联：[小米原生耳机页交付记录](Settings_MIUI_Native_Delivery.md)。

## 结论

设备互联“更多设置”的新旧页面选择，直接取决于蓝牙服务命令 **120** 的插件兼容能力返回值，并不由面板侧 `getDeviceId()` 的 `01010901` 直接决定。

本次保留既有面板兼容 ID，避免电量、类型判断、自定义按钮或空间音频布局回归。仅在 MiLink 的蓝牙服务数据边界，为目标 OPPO 设备补齐插件能力查询结果，使 **MiLink 原生代码自己选择 `HEADSET_SETTINGS_PLUGIN`**。

没有 Hook 更多设置点击回调，没有 Hook `switchToHeadsetActivity()`，没有改写它创建的 Intent 或启动 Activity。

## 分析对象

用户提供的设备互联 APK：

| 项目 | 值 |
|---|---|
| 包名 | `com.milink.service` |
| 版本 | `18.2.0.12.2608172246` |
| versionCode | `180020012` |
| compileSdk / targetSdk | `36` / `36` |
| APK 大小 | 102,102,479 bytes |
| SHA-256 | `043885891ae4759634e12e670a00bab5f18e9c1fff2e491233b9ceab873baa2d` |

手机实际安装版本已核对相同，系统 `Settings.Global["0#bt_plugin_settings_qigsaw"]` 返回 `1.5_1.85`，不是空值。

## 真实调用链

```text
HeadSetsDetail 原生“更多设置”点击
  → HeadsetServiceController / Headset API
  → ProfileContext / AncBatteryController
  → MxBluetoothManager.switchToHeadsetActivity(device)
  → MxBluetoothService.switchToHeadsetActivity(device)
       1. 读取 Settings.Global["<userId>#bt_plugin_settings_qigsaw"]
       2. mBluetoothHeadsetService.checkSupport(device) → support extra
       3. mBluetoothHeadsetService.setCommonCommand(120, version, device) → ret
       4. 默认创建 action HEADSET_SETTINGS
       5. ret 精确等于 "true" 或设备是 AirPods
            → 将 action 改成 HEADSET_SETTINGS_PLUGIN
       6. 原生 context.startActivity(intent)
```

在联合输入“设置17、实际插件1.85、设备互联18.2”的 DexSQL 数据集中，最终方法为 file 17 / method 20427：

```text
Lcom/xiaomi/mxbluetoothsdk/service/MxBluetoothService;
  ->switchToHeadsetActivity(Landroid/bluetooth/BluetoothDevice;)V
```

关键 smali：

- `:label_0050`：调用 `setCommonCommand(0x78, version, device)`，`0x78=120`。
- 随后用字符串 `"true".equals(ret)`，不是布尔返回类型，也不接受 `"1"`。
- `:label_00dd`：若能力为真或 AirPods 判定为真，进入 `:label_00e9` 设置插件 action。
- `:label_00ee`：保留原生 `Context.startActivity`。

`checkSupport(device)` 的结果只写入 `MIUI_HEADSET_SUPPORT` extra。`setCommonCommand(133, "SettingsOriginal", device)` 是设置列表另一个分支的配置请求，不参与这个方法的页面选择。

## 为什么数据 Hook 能覆盖真实请求

MiLink 使用真实跨进程服务：

```text
package = com.xiaomi.bluetooth
action  = miui.bluetooth.mible.BluetoothHeadsetService
```

`MxBluetoothService$2.onServiceConnected` 调用 `IMiuiHeadsetService$Stub.asInterface(binder)`，将结果存到 `mBluetoothHeadsetService`。服务位于另一个进程，本地 `queryLocalInterface` 无法取得同进程实现，因而会创建 `IMiuiHeadsetService$Stub$Proxy`。

实际查询方法：

```text
IMiuiHeadsetService$Stub$Proxy
  .setCommonCommand(int command, String value, BluetoothDevice device): String
```

Binder transaction ID 为 `0x0e`。这就是本次适配的数据边界，不需要触碰按钮和导航入口。

## 修复条件与放行行为

实现位于 [MiLinkServiceHook.kt](../app/src/main/java/moe/chenxy/oppopods/hook/MiLinkServiceHook.kt) 的 `hookSettingsPluginCapability()`。

在原调用返回后，只有以下三项全部成立才把结果改为 `"true"`：

1. `command == 120`。
2. 原生传入的 Qigsaw 插件版本非空。
3. 当前 `BluetoothDevice` 被既有 OPPO 判定识别。

其他设备、其他命令和空插件版本保留原值。先执行真实远端查询，远端调用发生异常时不凭空报告服务可用。

原面板兼容 ID `01010901` 保持不变。本次证据说明“面板身份”和“设置插件兼容能力”是两条不同数据链，不能仅因旧页存在就更换所有 ID getter。

## 设置接收端的衔接

MiLink 原生 Intent 携带真实 `BluetoothDevice`、`MIUI_HEADSET_SUPPORT` 和 `COME_FROM=MIUI_BLUETOOTH_SETTINGS`。设置的插件加载壳允许 support 为空时继续加载 java 模块；真正的 `JavaActivity` 进入模块适配后，会按真实 OPPO 设备补齐本模块的插件配置，并保持实际蓝牙地址。

因此本次不用让 MiLink 的所有面板数据都伪装成 Pluto，也不需要让更多设置按钮知道模块新页面的实现类。

## 验证边界

- 已对用户指定的 APK 与手机实际版本完成方法、Binder 来源、字符串返回值和原生 action 分支核验。
- 已确认系统中存在有效插件版本标记 `1.5_1.85`。
- 成品需在更新后的 MiLink 进程中加载该数据 Hook；API 102 热重载和目标应用 ClassLoader 恢复在本次模块中一并修复。
- 设备互联更多设置的完整触控回归结果，以及最终 APK 信息，记录在 [交付记录](Settings_MIUI_Native_Delivery.md)。未执行的设备步骤不会标记为已通过。
