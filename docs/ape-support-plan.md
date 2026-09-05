# APE（Monkey's Audio）格式支持方案

> 状态：**Stage 0 诊断已完成（方案据此大幅简化）／尚未改动任何业务代码**
> 目标：让 `.ape` 文件进入曲库、能被 Poweramp 导入匹配、并能正常播放。
> 实测设备：HONOR BKQ-AN80（Magic8），Android SDK 36（Android 16）。

---

## 1. 结论

APE 导入失败**不是 Poweramp 导入逻辑的 Bug**。导入匹配器 `SongMatcher` 的候选集来自
`MusicDao.getAllLocalSongsForImport()`（`app/src/main/java/com/theveloper/pixelplay/data/database/MusicDao.kt:242`），
即「本地已入库歌曲」。APE 从未进入 `songs` 表，所以**必然匹配不上**。

**关键修正（Stage 0 实测）**：APE 其实**已经在 `MediaStore.Audio.Media` 表里**，
问题不是"扫不到文件"，而是 **`duration` 为 NULL，被时长过滤条件挡掉**。
这使得 Stage 1 从「新增扫描通道（1~2 天）」降级为「放行 + 补时长（半天，纯 Kotlin）」。

---

## 2. Stage 0 诊断结果（实测）

### 2.1 设备与库容

| 项 | 值 |
|---|---|
| 设备 | HONOR BKQ-AN80（Magic8） |
| Android | SDK 36（Android 16） |
| `MediaStore.Files` 总行数 | 186,882 |
| `MediaStore.Audio` 总行数 | 1,875 |
| `.ape` 文件 | **9 首**（均在 `/storage/emulated/0/Music4Phone/`） |

### 2.2 APE 在 MediaStore 中的真实状态

```
Row: 48  _id=2988, _data=/storage/emulated/0/Music4Phone/The Mo Run Air A Ghille (I Lov_Various Artists.ape,
         duration=NULL, title=The Mo Run Air A Ghille (I Lov_Various Artists, mime_type=audio/ffmpeg
Row: 394 _id=3357, _data=/storage/emulated/0/Music4Phone/又见炊烟_王菲.ape,
         duration=NULL, title=又见炊烟_王菲, mime_type=audio/ffmpeg
...
```

三个要点：

1. **APE 已被收录进音频表**（9/9），`_id`、`_data`、`title` 齐全。
2. **`duration=NULL`** → 被 `buildLocalAudioSelection()` 的 `DURATION >= ?` 过滤（SQL 三值逻辑下 NULL 比较不为真）。
3. **MIME 是 `audio/ffmpeg`**，不是 `application/octet-stream`。
   该 ROM 的 MediaScanner 带 FFmpeg 嗅探能力，把 APE 标记为 `audio/ffmpeg`。
   全库 `audio/ffmpeg` 恰好 9 条，**与 `.ape` 一一对应**。

### 2.3 全库 duration=NULL 的构成（13 条）

| MIME | 数量 | 说明 |
|---|---|---|
| `audio/ffmpeg` | 9 | 即 9 首 `.ape`，**本次要救的目标** |
| `audio/x-pn-realaudio` | 4 | `.rm` 视频文件，位于 `DCIM/_我和亲朋好友/`，**不是音乐，应当继续过滤掉** |
| `audio/midi` | 2 | 已有特例（MIDI 因时长元数据缺失单独开洞） |

> 注意：`.rm` 属于误收录的视频，当前被 `DURATION >= ?` 挡掉是**正确行为**。
> 放行策略必须精确到 `.ape` 扩展名，不能放宽成「duration 为 NULL 就放行」。

### 2.4 平台播放能力（决定 Stage 2 是否仍需 NDK）

```
$ adb shell dumpsys media.codec | grep -iE "ape|monkey|ffmpeg"   → 无任何输出
$ adb shell dumpsys media.extractor
  Available extractors: AAC / AMR / AVI / FLAC / MIDI / MP3 / MP4 / MP4-DTS
                        MPEG2-PS-TS / Matroska / Ogg / WAV        → 无 APE
```

**平台既不能解封装也不能解码 APE。** 因此：

- Stage 1（入库）**纯 Kotlin，无需 NDK**，半天可完成。
- Stage 2（播放）**仍需自建 NDK 产物**（用户已确认接受）。`audio/ffmpeg` 只是 MediaScanner 的
  标记，不代表 Stagefright 有解码能力，二者不要混淆。

---

## 3. 根因链（修正后）

| 层 | 阻断点 | 现状 |
|---|---|---|
| ① 入库 | `utils/MediaStoreSelectionUtils.kt:26` `buildLocalAudioSelection()` | APE 在音频表内，但 `DURATION >= 10000` 把 `duration=NULL` 的它挡掉。MIDI 已有同类特例，APE 没有 |
| ② 元数据 | `data/worker/SyncWorker.kt:1105` `shouldAugmentMetadata` 扩展名白名单 | 含 flac/wav/opus/ogg/oga/aiff，**不含 ape** → 不会走 TagLib 补标签与时长 |
| ② 元数据 | `SyncWorker.kt:1155` `duration = raw.duration` | 直接用 MediaStore 的 NULL→0，**没有用 TagLib 的 `durationMs` 兜底** |
| ③ 格式显示 | `utils/AudioMetaUtils.kt:87` `mimeTypeToFormat()` | 无 `audio/ffmpeg` 分支，会落到 `startsWith("audio/")` 兜底输出 `"ffmpeg"` |
| ④ 播放 | Media3 `DefaultExtractorsFactory` + `media3-ffmpeg-decoder` | 无 APE 容器解析器；上游 `build.sh` 的 `ENABLED_DECODERS` 不含 ape；平台解码器也没有 |

> `AudioMetadataReader` **已具备** APE 所需能力：`AudioMetadataReader.kt:74` 从 TagLib
> `audioProperties.length` 取 `durationMs`，`:75-76` 取 bitrate / sampleRate。
> 且 `SyncWorker.kt:1409`（Telegram 路径）已有「用 `meta.durationMs` 覆盖 duration」的现成写法可参照。

---

## 4. 修订后的实施方案

### Stage 1 — 让 APE 进曲库（纯 Kotlin，约半天）

#### 1.1 放行 `.ape`（`utils/MediaStoreSelectionUtils.kt`）

仿照现有 MIDI 特例，新增 APE 扩展名特例，绕过时长门槛：

```kotlin
private val APE_EXTENSION_SELECTION_ARGS = arrayOf("%.ape")

// selection 中追加：
// OR LOWER(${MediaStore.Audio.Media.DATA}) LIKE ?
```

**设计要点：必须用扩展名判断，不能用 `mime_type = 'audio/ffmpeg'`。**
`audio/ffmpeg` 是本 ROM（荣耀/Magic8）的私有标记，Pixel、小米、三星等 ROM 上 APE 可能标记为
`application/octet-stream` 或直接不收录。扩展名特例在各 ROM 上行为一致。

同时保持精确匹配 `%.ape`，**不要**放宽成「duration IS NULL 就放行」——否则 4 个 `.rm` 视频会被误收进曲库。

#### 1.2 补时长与元数据（`data/worker/SyncWorker.kt`）

- `shouldAugmentMetadata`（`:1105`）白名单增加 `.ape` 分支。
- 在 `AudioMetadataReader.read()` 返回结果的应用处（约 `:1125`），参照 `:1409` 的 Telegram 写法，
  用 `meta.durationMs` / `meta.bitrate` / `meta.sampleRate` 覆盖 `raw.duration`（为 0 时）。
- `SongEntity` 构造处（`:1155`）改用补齐后的 duration。

> 增量同步比较条件 `existing.duration == raw.duration`（`:806`）也要留意：
> duration 从 0 变为真实值会触发一次重扫，属预期行为。

#### 1.3 格式显示（`utils/AudioMetaUtils.kt`）

`mimeTypeToFormat()` 增加 `audio/ffmpeg` 分支。由于该 MIME 宽泛，
**优先按文件扩展名判定具体格式**（ape / wv / tta 等），MIME 仅作兜底。

#### 1.4 无损归类（`data/diagnostics/DebugPerformanceReportCollector.kt:42`）

`losslessFormats` 增加 `"ape"`。

#### 1.5 封面（可选项，建议 Stage 1 一并做）

MediaStore 对 APE 无 album_art 记录，`AlbumArtUtils.getAlbumArtUriForLibraryScan()` 拿不到缩略图。
需在 SyncWorker 对 `.ape` 走 `AudioMetadataReader.read(file, readArtwork = true)` 取内嵌封面，
再经项目已有的 `LocalArtworkUri` / `SharedArtworkContentProvider` 通路落库。

### Stage 2 — 让 APE 能播放（需 NDK，用户已确认接受）

#### 2a. 自建 `ApeExtractor`（Kotlin，建议在 WSL 外先做，可 JVM 单测）

Media3 的 `DefaultExtractorsFactory`（`DualPlayerEngine.kt:1116`）不支持 APE 容器，需实现 `Extractor`。
APE 容器结构简单，约 400~600 行 Kotlin：

- 解析 APE Descriptor（52 字节）→ `descriptorBytes` / `seekTableBytes` / `totalFrames`
- 解析 APE Header → `blocksPerFrame` / `finalFrameBlocks` / `bitsPerSample` / `channels` / `sampleRate`
- 读取 Seek Table（`totalFrames` 个 uint32 偏移）→ 构建 `SeekMap`，**seek 可做到帧级精确**
- 逐帧输出 `Format(sampleMimeType = "audio/x-ape", ...)` + sample data

需处理前置 ID3v2 / APEv2 标签偏移与尾部 ID3v1 / APEv2 footer。
FFmpeg 的 `apedec.c` 仅支持 **version >= 3970（Monkey's Audio 3.97+）**，更老的文件要明确提示不支持，不能静默失败。

#### 2b. 解码器：fork Jellyfin FFmpeg 扩展并编入 ape（WSL + NDK）

上游 `jellyfin/jellyfin-androidx-media` 的 `build.sh` 写死：

```bash
export ENABLED_DECODERS=(flac alac pcm_mulaw pcm_alaw mp3 aac ac3 eac3 dca mlp truehd)
```

Fork 后改两处：

1. `build.sh`：`ENABLED_DECODERS` 追加 `ape`
2. `libraries/decoder_ffmpeg/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegDecoder.java`：
   注册 `audio/x-ape → "ape"` 的 mime → codec name 映射，并加入 `FfmpegLibrary` 的 supported formats。
   **此步不能省**——`FfmpegAudioRenderer` 只采纳自己注册的 mime 集合。

NDK 需与上游一致（r26.1.10909125），在 WSL 中构建，产物经本地 Maven 或 `flatDir` 接入，
替换 `gradle/libs.versions.toml:153` 的坐标。

> 退路（仅在不想 fork 时）：自写 `ApeRenderer extends BaseRenderer`，内部反射构造
> `FfmpegAudioDecoder`。项目已有先例：`MediaFileHttpServerService.kt:2686`（ALAC）、
> `:2712`（AC3）。但需自行处理 AudioSink、变速、音量、tunneling，不如改上游映射省事。

#### 2c. 装配

`DualPlayerEngine.kt:1091` 已设置 `EXTENSION_RENDERER_MODE_ON`，
把 `ApeExtractor` 注册进 `DefaultExtractorsFactory`（`:1116`）即可；
`DeckController.kt:84` 为同一装配点，需同步。

### Stage 3 — 打磨与兜底

| 项 | 改动 |
|---|---|
| HTTP 透传 | `MediaFileHttpServerService` 转码通路（`shouldTryFfmpeg`，`:2088`）增加 ape → AAC，供 Wear / Cast 使用 |
| 失败兜底 | APE 解码失败时给出可读提示（"不支持的 APE 版本"），而非静默跳过 |
| ProGuard | `app/proguard-rules.pro` 已有 `androidx.media3.decoder.ffmpeg.**` keep 规则（`:50`）；新增 Extractor 若被反射创建需补 keep |
| 文档 | `THIRD_PARTY_NOTICES.md` 记录新增解码器与许可证 |

---

## 5. 关于 WavPack（不是 WAV）

**WavPack（`.wv`）与 WAV（`.wav`）是完全不同的两种格式**：

| | WAV（`.wav`） | WavPack（`.wv`） |
|---|---|---|
| 本质 | 未压缩 PCM 的 RIFF 容器 | 独立的有损/无损压缩算法（David Bryant，1998） |
| 压缩 | 无 | 约 30%~70%，与 FLAC / APE 同量级 |
| 系统支持 | Android / ExoPlayer 原生支持 | 与 APE 一样**不被支持** |
| 特色 | — | 独有的 hybrid 混合模式（`.wv` + `.wvc` 修正文件）、支持 DSD、内嵌 MD5 校验 |

**本次诊断中，设备全库（186,882 个文件）没有任何 `.wv` 文件**，唯一的"FFmpeg 类"音频就是 9 首 APE。
因此建议：**本次只做 APE，不顺带 WavPack**。若日后需要，NDK 侧在 `ENABLED_DECODERS` 里加一个
`wavpack` 的边际成本极低（同一次构建即可），主要工作量仍在各自的 Extractor。

---

## 6. 修订后的工作量

| 阶段 | 产出 | 依赖 | 风险 | 量级 |
|---|---|---|---|---|
| ~~Stage 0~~ | 诊断 | — | — | **已完成** |
| Stage 1 | APE 进曲库、Poweramp 导入可匹配 | 无（纯 Kotlin） | 低 | **0.5 d**（原估 1~2 d） |
| Stage 2a | `ApeExtractor`（可 JVM 单测） | 无 | 中（seek/边界/标签偏移） | 2~3 d |
| Stage 2b | FFmpeg 扩展 fork + NDK 构建 | WSL + NDK r26 | 中（构建环境已具备） | 1~2 d（原估 2~4 d） |
| Stage 3 | 打磨兜底 | Stage 1+2 | 低 | 1~2 d |

**建议顺序**：Stage 1（立即，价值最大且零风险）→ Stage 2a → Stage 2b → Stage 3。
Stage 1 单独上线即解决当前痛点：APE 进库、Poweramp 的历史/收藏/评分/歌单都能导入，
只是点击播放时提示需转码。

---

## 7. 风险与合规

1. **放行范围必须精确到 `.ape`**：`.rm` 等误收录视频同样 `duration=NULL`，
   放宽条件会污染曲库（实测有 4 个 `.rm`）。
2. **MIME 不可作为判定依据**：`audio/ffmpeg` 是荣耀 ROM 私有标记，跨 ROM 不可靠。用扩展名。
3. **许可**：`jellyfin/jellyfin-androidx-media` 为 **GPL-3.0**，项目已依赖它，
   本仓库 2026-05-12 后转为专有许可——**该合规问题已存在，非本次新增**，但应记录到
   `THIRD_PARTY_NOTICES.md`。
4. **APK 体积**：追加 ape 解码器预估每 ABI +0.3~1 MB；项目默认仅构建 `arm64-v8a`，影响可控。
5. **APE 版本兼容**：FFmpeg `apedec.c` 不支持 3.97 之前的文件，需明确提示而非静默失败。
6. **TagLib 读取 APE 时长需在真机验证**：理论上支持，但 `com.kyant.taglib` 的实际行为需装包确认。

---

## 8. 验收标准

- [ ] 「歌曲」列表出现 9 首 APE，标题/艺术家/专辑/时长正确（来自 TagLib，非 `<unknown>`）
- [ ] 时长非 0，进度条与总时长正确
- [ ] 4 个 `.rm` 文件**未**被收进曲库
- [ ] Poweramp 备份导入后，9 首 APE 的历史/收藏/评分/歌单归属正确
- [ ] APE 可播放、可 seek（Stage 2 后）
- [ ] 重复扫描不产生重复条目；删除文件后能从曲库移除
- [ ] `run-tests.bat` 全量无新增回归（`ApeExtractor` 需补 JVM 单测）

---

## 9. 待确认

1. 是否立即实施 **Stage 1**？（约半天，纯 Kotlin，零 NDK 依赖，风险低）
2. Stage 1 是否**一并处理封面**（1.5 节）？不处理的话 APE 会显示为默认封面。
3. 确认**只做 APE**、不顺带 WavPack（见第 5 节）。
4. WSL 中的 NDK 版本是否为 r26？若不是，Stage 2b 需先对齐或改用上游支持的其他 NDK 版本。
