# 欢律官方耳机素材与 3D 模型

本文记录从欢律（HeyMelody）服务器获取耳机图片、充电盒图和 3D 模型的实现，以及
`.vfxms` 的完整解析方式。协议与接口依据桌面版 `heymelody-windows` 的
`VendorResourceClient` 和欢律 17.6.5 APK 中的 `com.oplusos.vfxmodelviewer`。

## 接口

服务根地址：`https://iot-earbuds-cn.heytapmobi.com/`

| 用途 | 路径 | 请求体 |
| --- | --- | --- |
| 控制包（图片/动画） | `v1/earphone/resource/control` | `platform/channel/versionCode/productId/color` |
| 3D 包（模块 14） | `v1/earphone/resource/fetch` | 同上，另加 `moduleId=14` |

请求头：

```text
appid: earphone
sv: v1
ts: <毫秒时间戳>
nonce: <UUID>
sign: HMAC-SHA1(key, body) 的小写 hex
Content-Type: application/json
```

`body` 必须与实际发送的字节完全一致；顺序固定为
`platform, channel, versionCode[, productId, color][, moduleId]`。
桌面版内置密钥为 `&*%earphone-OP7u3423**%$`，本模块沿用。

返回 `data.cartoonUrl`（控制包）或 `data.resourceUrl`（3D 包）以及 `sha256`。
下载后必须校验 SHA-256，ZIP 解压限制成员数、单项大小和总展开大小，并拒绝路径穿越。
下载域名只允许 `iot-earbuds-cn.heytapimage.com` 和
`iot-heythings-cn.heytapimage.com`，且必须是 HTTPS。

颜色是协议配色编号，不是任意猜测值。X3（`067410`）只有颜色 2、3 有控制包；
X4（`06EC10`）颜色 1、2、3 有。因此下载时先尝试设备 `0x010B` 返回的颜色，再按
0..7 依次探测，取第一个有效包。`0x010B` 响应格式为 `[status][color]`。

控制包中与本模块相关的字段：

```json
{
  "detailImageRes": {"path": "res/image/img_detail.png"},
  "leftImageRes": {"path": "res/image/img_left.png"},
  "rightImageRes": {"path": "res/image/img_right.png"},
  "boxImageRes": {"path": "res/image/img_box.png"},
  "capsuleImageRes": {"path": "res/image/img_capsule.png"},
  "hearingEnhancementRes": {"path": "res/image/img_hearing_enhancement.png"},
  "capsuleVideoRes": {"path": "res/video/capsule.webp"}
}
```

## `.vfxms` 容器

3D 包里的 `res/raw/detail_model.vfxms` 不是裸 GLB。开头是 40 字节、10 个大端 int32：

| 偏移 | 字段 | X3 示例 |
| --- | --- | --- |
| 0 | configStart | 40 |
| 4 | configLength | 1096 |
| 8 | glbStart | 1136 |
| 12 | glbLength | 10,835,448 |
| 16 | iblStart | 10,836,584 |
| 20 | iblLength | 2,095,480 |
| 24 | skyboxStart | 0 |
| 28 | skyboxLength | 0 |
| 32 | animationStart | 0 |
| 36 | animationLength | 0 |

- `config` 是 `ModelSceneConfig` JSON，包含 `modelScale`、`materials`、`light`
  （`intensity`、`direction`、`iblIntensity`、`iblAngle`）和背景/后处理开关。
- `glb` 是自包含 GLB（内嵌 PNG 纹理）。
- `ibl` 是 KTX1 立方体贴图，X3 的 `iblIntensity=30000`、`iblAngle=[0,90,0]`。
- `skybox`、`animation` 当前为 0；有值时同样按偏移/长度读取。

### 不是所有「3D 包」都是 `.vfxms`

X4（`06EC10`）的模块 14 返回的是 `modelWebp`：一个 780×780、180 帧的
RIFF WebP（6.5 MB），不是 GLB。资源解析会同时接受 `model3D` 和 `modelWebp`，
Android 端用 `ImageDecoder` + `AnimatedImageDrawable` 循环播放；只有 `.vfxms`
才进入 Filament 渲染。不要把 X4 的动画误判为损坏的 3D 包。

### 为什么 Windows 版看起来“怪怪的”

桌面版 `ArtworkPackageExtractor.AddFile` 只读了偏移 8/12 的 GLB：

```csharp
int start = ReadInt32BigEndian(container.AsSpan(8, 4));
int length = ReadInt32BigEndian(container.AsSpan(12, 4));
```

配置、IBL 和 `modelScale` 全部被丢弃。没有 KTX1 IBL 时，GLB 的 PBR 金属/粗糙度
材质只能靠默认直射光，模型会明显偏暗、反射缺失；`modelScale` 丢失还会让部分机型
比例不对。这不是 GLB 本身的问题，而是容器被截断了。

## Android 渲染

本模块用 Filament（与欢律 `com.oplusos.vfxmodelviewer` 同引擎）渲染：

- `ModelViewer` + `TextureView`，手势轨道旋转/缩放；
- `Filament.init()`、`Gltfio.init()`、`Utils.init()` 分别装载
  `filament-jni`、`gltfio-jni`、`filament-utils-jni`；漏掉任何一步会在
  `Engine.nCreateBuilder` 报 `No implementation found`；
- `KTX1Loader.createIndirectLight` 读取容器内 IBL，设置 `iblIntensity` 和
  `iblAngle` 的旋转；
- 按 `config.materials` 覆盖 `FLOAT`/`FLOAT4` 材质参数（X3 的 `baseColorFactor`
  是白色 FLOAT4，`metallicFactor`/`roughnessFactor` 为 1）；
- 按 `modelScale` 做单位立方体归一化，中心 `(0, 0, -4)`，与欢律的
  `DEFAULT_MODEL_POS` 一致；
- 背景使用页面自身的 `MiuixTheme.colorScheme.background`，不额外画色块；3D 按钮只在模型已缓存时出现；
- 相机初始 `yaw=180°`（正面开盖）、`distance=4.2`、FOV 45°/near 0.5/far 100；自转 20°/s；
- 手动拖拽水平环绕 360°、垂直俯仰 ±85°；松手先停 1.2 秒，再用 10 秒把俯仰缓回 0° 并恢复自转，对应欢律 `ControlOver -> ControlOverToRotate`；
- 触摸层只覆盖模型区域（82%×90%），竖向拖动在模型上旋转，在区域外仍交给 `LazyColumn` 滚动。

### `alphaMode=BLEND` 导致图层穿插

官方 GLB 的基色/法线贴图都是 RGB，没有 alpha，但材质声明为 `"alphaMode": "BLEND"`。Filament 对 BLEND 关闭深度写入，31 个子网格按 renderable 顺序**互相穿插**，看起来像耳机跑到盒外、耳柄穿过盒子。加载前把 JSON 里的 `BLEND` 改成 `OPAQUE` 并重建 GLB 的 JSON/BIN chunk 长度，图层即恢复正常。

## 缓存

```text
files/official_artwork/current.json
files/official_artwork/images/<productId>_<color>/{detail,left,right,box}.png
files/official_artwork/models/<productId>_<color>/model.bin
```

`model.bin` 可能是 `.vfxms`，也可能是 X4 的动态 WebP；UI 按文件魔数选择渲染器。

用户自定义槽位（`PodImageStore`）优先；没有自定义时才使用官方素材。跨进程的
`PodImageProvider` 同样按这个顺序解析，因此超级岛左/右耳和充电盒会自动用上官方图。

## 限制

- 图片与 3D 模型来自欢律服务器，需联网；下载失败会保留旧缓存。
- 3D 模型约 13 MB，首次解析/上传纹理需要几秒；模型下载按钮在图片缓存完成后才可用。
- 3D 材质覆盖只处理配置里出现的 `FLOAT`/`FLOAT4`；未知类型忽略。
- 后处理（SSAO/Bloom）暂未逐项复刻，当前优先保证 IBL、比例和材质与欢律一致。
