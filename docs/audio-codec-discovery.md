# 音频解码器发现与选择机制

> 本文档记录 PixelPlayer 如何发现设备上的音频解码器、如何判断硬件/软件、以及播放时如何选择解码器。最后附荣耀 BKQ-AN80（骁龙 8 Elite）实测清单。

## 1. 数据结构

`DeviceCapabilitiesViewModel.CodecInfo`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `name` | `String` | 解码器名称，如 `c2.android.mp3.decoder` |
| `supportedTypes` | `List<String>` | 支持的 MIME 类型，如 `audio/mpeg`、`audio/flac` |
| `isHardwareAccelerated` | `Boolean` | 是否硬件加速（见 §3 判断逻辑） |
| `maxSupportedInstances` | `Int` | 最大并发实例数，-1 表示未知 |

来源：`MediaCodecList(ALL_CODECS)` 遍历所有解码器，过滤 `isEncoder=false` 且 `supportedTypes` 含 `audio/` 的条目。

## 2. 解码器命名规则

Android 平台音频解码器名称有以下命名空间：

| 前缀 | 厂商 | 典型名称 | 说明 |
|---|---|---|---|
| `c2.android.*` | Google（AOSP） | `c2.android.mp3.decoder` | 通用软件解码器，所有设备都有 |
| `c2.qti.*` | 高通 | `c2.qti.aac.hw.decoder` | 高通 Codec2 解码器，分 `.hw.` 和 `.sw.` |
| `c2.mtk.*` | 联发科 | `c2.mtk.mp3.decoder` | 联发科 Codec2 解码器 |
| `c2.sec.*` | 三星 | `c2.sec.mp3.decoder` | 三星 Codec2 解码器 |
| `c2.exynos.*` | 三星 Exynos | `c2.exynos.*` | Exynos 平台解码器 |
| `OMX.google.*` | Google（旧） | `OMX.google.mp3.decoder` | 旧版 OMX 软件解码器，Android 10+ 逐步被 C2 取代 |
| `OMX.qcom.*` | 高通（旧） | `OMX.qcom.audio.decoder.mp3` | 旧版 OMX 硬件解码器 |
| `OMX.MTK.*` | 联发科（旧） | `OMX.MTK.*` | 旧版 OMX 解码器 |
| `ffmpeg` | Jellyfin FFmpeg | `ffmpeg` | PixelPlayer 内置的 FFmpeg 扩展解码器，格式兜底 |

### 高通 `.hw.` / `.sw.` 后缀

高通新平台（骁龙 8 Gen 2+）的 `c2.qti.*` 解码器名称中带显式后缀：

- `c2.qti.aac.hw.decoder` — **硬件**解码，运行在专用 DSP / 音频加速器上
- `c2.qti.flac.sw.decoder` — **软件**解码，运行在 CPU 上（但可能用了 NEON 优化）

判断硬件时必须优先看后缀，不能仅凭 `c2.qti.` 前缀就认为是硬件。

## 3. 硬件/软件判断逻辑

`AudioDecoderPolicy.isLikelyHardwareDecoder(name)` 按以下优先级判断：

1. **已知软件 token**：名称含 `omx.google.`、`c2.android.`、`ffmpeg`、`midi`、`jsyn`、`libgav1`、`dav1d` → 软件
2. **显式 `.sw.` 后缀**：名称含 `.sw.` → 软件（即使前缀是 `c2.qti.`）
3. **显式 `.hw.` 后缀**：名称含 `.hw.` → 硬件
4. **厂商前缀**：名称以 `omx.` / `c2.` 开头，或含 `.qti.` / `.qcom.` / `.sec.` / `.mtk.` / `.exynos.` / `.dolby.` → 硬件

设备性能页的 `getSupportedAudioCodecs()` 在此基础上增加厂商补标：

- 三星设备：`c2.sec.*` 强制标为硬件（平台常漏标 `isHardwareAccelerated`）
- 高通设备：仅 `c2.qti.*.hw.*` 标为硬件，`c2.qti.*.sw.*` 不标

## 4. 播放时解码器选择策略

### 4.1 渲染器模式

`DualPlayerEngine` 使用 `DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON`：

- **MediaCodec 渲染器优先**（`MediaCodecAudioRenderer`）
- FFmpeg 扩展渲染器作为 fallback（仅在 MediaCodec 不支持格式时使用）

### 4.2 硬件优先排序

`mediaCodecSelector` 对 `MediaCodecSelector.DEFAULT.getDecoderInfos()` 返回的列表按 `isLikelyHardwareDecoder` 降序排列：

```
硬件解码器（c2.qti.*.hw.*）→ 厂商软件（c2.qti.*.sw.*）→ Google 软件（c2.android.*）
```

`MediaCodecAudioRenderer` 初始化时取列表第一个解码器。

### 4.3 失败回退

`setEnableDecoderFallback(true)`：如果第一个解码器初始化失败，自动尝试列表中的下一个。

### 4.4 格式特殊处理

`AudioDecoderPolicy.selectPlatformDecoders()` 对以下格式返回空列表（禁用平台解码器，只用 FFmpeg）：

- ALAC（`audio/alac`）
- MIDI（`audio/midi`、`audio/exoplayer-midi`）

> 注：高通新平台已有 `c2.qti.alac.hw.decoder`，后续可评估放开 ALAC 的平台解码器限制。

### 4.5 硬件卸载 Offload

PixelPlayer **不使用**音频硬件卸载（offload），原因：

- 自定义 `AudioProcessor` 链（`HiResSampleRateCapAudioProcessor` + `SurroundDownmixProcessor`）与 offload 模式不兼容
- 多数设备（包括骁龙 8 Elite）不报告压缩格式 offload 支持

## 5. 真机验证方法

### 5.1 查看当前活动解码器

播放时看详细播放界面的码率胶囊标签，每 3 秒交替显示：

- `320 kbps • MP3 • 44.1 kHz`（码率/格式/采样率）
- `c2.qti.aac.hw.decoder · HW`（当前解码器名称 + 硬件/软件标记）

数据来源：`DualPlayerEngine.activeDecoderInfo`（`onDecoderInitialized` 回调时更新）。

### 5.2 logcat 验证

```bash
adb logcat -c
# 开始播放
adb logcat | grep -iE "onDecoderInitialized|MediaCodecAudioRenderer|c2\.qti|c2\.android|OMX\.qcom|FfmpegAudioRenderer|codec.*init|fallback"
```

### 5.3 枚举设备所有解码器

Android 16 上 `dumpsys media.codec` 服务已移除（`Can't find service: media.codec`）。入口是 vendor 的
XML 配置链，**必须从 `/vendor/etc/media_codecs.xml` 的 `<Include>` 开始顺着读**，只读单个文件会漏：

```bash
# 1) 看 include 链（决定哪些文件真正生效）
adb shell cat /vendor/etc/media_codecs.xml | grep Include

# 2) 按链上的文件名读音频部分（不同平台文件名不同，不存在 media_codecs_canoe_audio.xml 这种）
adb shell grep -h -o 'MediaCodec name="[^"]*"[^>]*type="[^"]*"' /vendor/etc/media_codecs_c2_audio.xml
adb shell grep -h -o 'MediaCodec name="[^"]*"[^>]*type="[^"]*"' /vendor/etc/media_codecs_vendor_audio.xml

# 3) 全量搜索（按格式确认是否存在硬件解码器）
adb shell "cd /vendor/etc && grep -h -o 'MediaCodec name=\"[^\"]*\"[^>]*type=\"[^\"]*\"' media_codecs*.xml | grep -i flac | sort -u"
```

> **注意**：XML 里声明了 ≠ 运行时可用。`MediaCodecList` 只列出 XML 声明 ∩ 实际注册的 C2 组件，
> 没有对应 `.so` 的条目会被 framework 丢弃。见 §6.3。

### 5.4 查看运行时注册的 C2 组件

```bash
adb shell dumpsys media.player | grep -iE "c2\.|OMX\."
# 或
adb shell cmd media.player dump
```

## 6. 实测案例：HONOR BKQ-AN80（骁龙 8 Elite）

- **设备**：HONOR BKQ-AN80，平台 `canoe`，`Build.HARDWARE=qcom`，`ro.soc.manufacturer=QTI`
- **系统**：Android 16（SDK 36）
- **实测日期**：2026-09-06

### 6.1 XML 声明（`/vendor/etc/media_codecs_c2_audio.xml`，已被 include 链引用）

| 解码器 | 格式 | 类别 |
|---|---|---|
| `c2.qti.aac.hw.decoder` | AAC (`audio/mp4a-latm`) | 高通 hw |
| `c2.qti.alac.hw.decoder` / `c2.qti.alac.sw.decoder` | ALAC | 高通 hw + sw |
| `c2.qti.ape.hw.decoder` / `c2.qti.ape.sw.decoder` | APE | 高通 hw + sw |
| `c2.qti.wma.hw.decoder` | WMA | 高通 hw |
| `c2.qti.amrwbplus.hw.decoder` | AMR-WB+ | 高通 hw |
| `c2.qti.flac.sw.decoder` | FLAC | 高通 **sw**（无 hw 版） |
| `c2.qti.dsd.sw.decoder` | DSD | 高通 sw |
| `c2.bd.aivc.decoder` | AIVC（荣耀自有） | 荣耀 |

### 6.2 运行时实际可用（`MediaCodecList(ALL_CODECS)`）

**24 个音频解码器，全部为软件解码**，无任何 `c2.qti.*`、无 `c2.bd.aivc.*`：

- `OMX.google.*`：`aac` `flac` `mp3` `vorbis` `amrnb` `amrwb` `g711.alaw` `g711.mlaw` `raw` `opus` 等
- `c2.android.*`：同上格式的 C2 版本（`mp3` `aac` `flac` `opus` `vorbis` `amrnb` `amrwb` `g711.*` `raw`）

### 6.3 为什么 XML 里有、运行时没有

1. `/vendor/lib64` 下只有高通**视频** C2 组件（`libqcodec2_core.so` / `libqcodec2_base.so` 等）与荣耀
   aivc 软解（`libcodec2_soft_aivc_dec.so`），**没有高通音频 C2 组件库**。
2. 进程列表里没有高通音频 C2 服务（只有 `audiohalservice.qti`，那是 audio HAL，不是 MediaCodec 路径）。
3. framework 的 `MediaCodecList` = XML 声明 ∩ 实际注册的 C2 组件，未实现的条目被丢弃。

→ **结论：这台设备的高通音频硬解是"配置残留"，不可用于播放。**

### 6.4 关键发现

1. **FLAC 在这台设备上没有硬件解码**。全量 grep 所有 `/vendor/etc/media_codecs*.xml`，FLAC 仅
   `OMX.google.flac.decoder` / `c2.android.flac.decoder` / `c2.qti.flac.sw.decoder`，**无 `c2.qti.flac.hw`**。
2. **所有 `c2.qti` 音频解码器运行时都不可用**，因此 `DualPlayerEngine` 的硬件优先排序在本设备上是空转
   （列表里没有硬件解码器可排）。代码保留是合理的——同一份逻辑在三星 / MTK / 真·高通音频 C2 设备上会生效。
3. **APE / ALAC / DSD 只能靠 FFmpeg 扩展渲染器**（`media3-ffmpeg-decoder`）兜底，这也解释了为什么
   该扩展是必需的而非可选。
4. **MP3 无硬件解码**：本设备无 `c2.qti.mp3.*`，只能 `c2.android.mp3.decoder` 软解。
5. **硬件卸载 offload 不支持**：设备未报告任何压缩格式 offload 支持。

### 6.5 功耗说明

- 音频解码本身极轻量，软解 vs 硬解的续航差异通常 < 3%
- 真正省电的是硬件卸载 offload（CPU 完全休眠），但本设备不支持
- MP3/AAC 等有损格式，软解功耗已极低，硬件解码收益可忽略
- 无损格式（FLAC/ALAC/DSD）在有硬件解码的设备上收益相对明显，本设备不适用

## 7. 相关代码位置

| 文件 | 说明 |
|---|---|
| `app/.../player/AudioDecoderPolicy.kt` | 硬件/软件判断、格式特殊处理 |
| `app/.../player/DualPlayerEngine.kt` | `mediaCodecSelector` 硬件优先排序、`activeDecoderInfo`、`EXTENSION_RENDERER_MODE_ON` |
| `app/.../viewmodel/DeviceCapabilitiesViewModel.kt` | `getSupportedAudioCodecs()`、厂商补标 |
| `app/.../screens/DeviceCapabilitiesScreen.kt` | 设备性能页 UI、完整解码器清单卡片 |
| `app/.../components/player/FullPlayerContent.kt` | 详细播放界面码率/解码器交替显示 |

## 8. Media3 1.11.0 是否会过滤硬件解码器

结论：**不会**。核查了 `androidx.media3:media3-exoplayer:1.11.0` 源码（`MediaCodecUtil.java` /
`MediaCodecSelector.java`），排除硬件解码器的地方一个都没有：

| 位置 | 行为 | 是否影响硬件解码器 |
|---|---|---|
| `MediaCodecSelector.DEFAULT` | `= MediaCodecUtil::getDecoderInfos`，直接透传 | 否 |
| `MediaCodecSelector.PREFER_SOFTWARE` | 用 `getDecoderInfosSortedBySoftwareOnly` 把软解排最前 | 是，但 PixelPlayer **未使用** |
| `MediaCodecListCompatV21` | 默认（无 secure/tunneling/special）用 `REGULAR_CODECS`，需要时才 `ALL_CODECS` | 否，REGULAR 仍含硬件解码器 |
| `isCodecUsableDecoder()` | 只排除：encoder、`*.secure` 后缀、三星 S6 的 AAC、MTK AC3（SDK 23） | 否 |
| `getDecoderInfosInternal()` | `isAlias()` 时跳过（避免与 canonical 名重复列出） | 否 |
| `applyWorkarounds()` | SDK < 32 时把 `OMX.qti.audio.decoder.flac` 移到队尾 | 本设备 SDK 36，不触发 |

`DualPlayerEngine` 用的是 `MediaCodecSelector.DEFAULT`（不是 `PREFER_SOFTWARE`），所以播放端不会
主动降级到软解。**实测只看到软解码器的原因是 §6.3 的组件未注册，与 Media3 无关。**

> 推论：设备性能页那张清单用的是原生 `MediaCodecList` API，完全不经过 Media3。它显示什么，
> ExoPlayer 就拿得到什么。两者一致 = 数据可信。
