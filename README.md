# OppoPods

Xposed module that brings system-level OPPO earphone control to Xiaomi HyperOS devices.

Based on [HyperPods](https://github.com/Art-Chen/HyperPods) by Art_Chen.

## Features

- **ANC Control** - Switch between Off / Noise Cancellation / Transparency directly from the device UI
- **Battery Display** - Real-time battery level for left ear, right ear, and charging case
- **HyperOS Integration** - Native Strong Toast popup on connection, persistent notification with battery info, status bar headset icon
- **Control Center** - Tap the earphone card in Control Center to open OppoPods settings
- **System Battery Sync** - Earphone battery level reported to Android bluetooth framework

## Requirements

- Xiaomi device running **HyperOS** (Android 15+)
- **LSPosed** or compatible Xposed framework
- Module scope: `com.android.bluetooth`, `com.xiaomi.bluetooth`, `com.android.systemui`

## How It Works

OppoPods hooks into three system processes:

| Process | Purpose |
|---------|---------|
| `com.android.bluetooth` | Detect OPPO earphone connection via A2DP state, establish RFCOMM channel 15, send/receive protocol packets |
| `com.xiaomi.bluetooth` | Show native HyperOS Strong Toast with battery animation, create persistent notification |
| `com.android.systemui` | Intercept Control Center device card tap to open OppoPods UI |

### Protocol

Communication uses Bluetooth Classic **RFCOMM** on channel 15. Packet format:

```
AA [TotalLen] 00 00 [Cmd 2B LE] [Seq] [PayLen 2B LE] [Payload...]
```

- **ANC control**: Cmd `0x0404`, Payload `01 01 <mode>` where mode is `01`=Off, `02`=NC, `04`=Transparency
- **Battery query**: Send `AA 07 00 00 06 01 00 00 00`, parse response with Cmd `0x8106`
- **Battery response**: Payload pairs of `[Index, RawValue]` — battery = `RawValue & 0x7F`, charging = `(RawValue & 0x80) != 0`

### Device Detection

OPPO earphones are identified by checking if the Bluetooth device name contains "oppo" (case insensitive).

## Build

```bash
./gradlew assembleDebug
```

Or push to GitHub — the CI workflow builds automatically on every push to `main`.

## Install

1. Install the APK
2. Enable the module in LSPosed with scope: `com.android.bluetooth`, `com.xiaomi.bluetooth`, `com.android.systemui`
3. Reboot
4. Connect your OPPO earphones via Bluetooth

## Credits

- [HyperPods](https://github.com/Art-Chen/HyperPods) by Art_Chen — original project this is based on
- [YukiHookAPI](https://github.com/HighCapable/YukiHookAPI) — Xposed hook framework
- [Miuix](https://github.com/YuKongA/miuix) — MIUI-style Compose UI components

## License

GPL-3.0
