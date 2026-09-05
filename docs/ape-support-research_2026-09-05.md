# APE（Monkey's Audio）支持调研记录

> 调研日期：2026-09-05
> 实测设备：HONOR Magic8（BKQ-AN80），Android 16 / SDK 36
> 关联文档：`docs/ape-support-plan.md`（实施方案）

---

## 1. 问题陈述

用户从 Poweramp 备份导入数据时，`.ape` 格式的歌曲提示导入失败。

初步假设：APE 作为小众无损格式，可能未被 Android MediaScanner 收录，导致从未进入曲库。

**调研目标**：定位根因，给出可行方案。

---

## 2. 调研方法

采用两条独立证据链交叉验证：

1. **静态代码分析** — 追踪「扫描入库 → 元数据 → 格式识别 → 播放」全链路，定位代码层面的阻断点。
2. **真机 adb 诊断** — 直接查询 MediaStore 与平台编解码器能力，验证/证伪静态分析的推断。

> 事后证明第二条至关重要：**静态分析得出的核心假设被实测推翻**，方案工作量因此从 1~2 天降至半天。

---

## 3. 静态代码分析（改动前）

### 3.1 导入链路：候选集来自曲库

`PowerampBackupImporter` 的匹配由 `data/importer/SongMatcher.kt` 完成，其候选集来自
`MusicDao.getAllLocalSongsForImport()`（`data/database/MusicDao.kt:242`），
即 `SELECT ... FROM songs WHERE source_type = 0`（本地已入库歌曲）。

**结论**：导入逻辑本身没问题。若 APE 不在 `songs` 表里，必然匹配不上。

### 3.2 曲库来源：MediaStore.Audio

`data/worker/SyncWorker.kt:849`、`data/repository/MediaStoreSongRepository.kt:119`
查询 `MediaStore.Audio.Media`，条件由 `utils/MediaStoreSelectionUtils.kt:26` 生成：

```kotlin
// 简化后
(DURATION >= ?) OR (mime_type = 'audio/midi' ...)   // MIDI 特例
```

关键点：**存在 MIDI 特例**，说明项目已处理过「因元数据缺失导致时长不合规」的情形。

### 3.3 解码器能力面

- `DualPlayerEngine.kt:1091` 已设置 `EXTENSION_RENDERER_MODE_ON`
- 依赖 `androidx.media3:media3-ffmpeg-decoder`（`gradle/libs.versions.toml:153`）
- 上游 `jellyfin/jellyfin-androidx-media` 的 `build.sh` 写死：

```bash
export ENABLED_DECODERS=(flac alac pcm_mulaw pcm_alaw mp3 aac ac3 eac3 dca mlp truehd)
```

**不含 `ape`**。

### 3.4 静态分析得出的（部分错误的）结论

> APE 未被 MediaScanner 收录 → 需新增 `MediaStore.Files` 扫描通道 → 预估 1~2 天。

---

## 4. 真机诊断（实测数据）

### 4.1 曲库与文件规模

| 项 | 值 |
|---|---|
| `MediaStore.Files` 总行数 | 186,882 |
| `MediaStore.Audio.Media` 总行数 | 1,875 |
| `.ape` 文件数 | **9**（三方交叉验证一致） |
| `.ape` 总大小 | 221,924,439 B（约 211.6 MiB） |

三方交叉验证：

```
find /storage -iname '*.ape'                          → 9   （文件系统）
MediaStore.Audio.Media WHERE _data LIKE '%.ape'       → 9   （媒体库）
ls /storage/emulated/0/Music4Phone/*.ape              → 9   （目录）
```

> **用户最初记忆为 12 首**，实测为 9 首。设备无外置 SD 卡（`/storage/` 下仅 `emulated`、`sdcard0`、`self`），
> 全盘递归搜索无遗漏。差异可能源于其他 App 的虚拟歌单或记忆偏差。

### 4.2 决定性发现：APE 已在音频表内，但 duration 为 NULL

```
Row: 394 _id=3357, _data=/storage/emulated/0/Music4Phone/又见炊烟_王菲.ape,
         duration=NULL, title=又见炊烟_王菲, mime_type=audio/ffmpeg
```

9 首 APE **全部已在 `MediaStore.Audio.Media` 表中**，`_id`、`_data`、`title` 齐全。

**阻断点不是「扫不到文件」，而是 `duration=NULL` 被 `DURATION >= 10000` 过滤**
（SQL 三值逻辑：`NULL >= 10000` 求值为 UNKNOWN，不被选中）。

这与项目为 MIDI 开特例的情形**完全同类**，只是 APE 没人给它开洞。

### 4.3 MIME 标记：`audio/ffmpeg`（ROM 私有，不可用）

MediaScanner 把 APE 标记为 `audio/ffmpeg`（不是 `application/octet-stream`），
全库恰好 9 条 `audio/ffmpeg`，与 9 首 `.ape` 一一对应。

> **该 MIME 是荣耀 ROM 的私有标记，不可作为判定依据。**
> Pixel / 小米 / 三星 ROM 上 APE 可能被标记为 `application/octet-stream` 或直接不收录。
> 跨 ROM 一致的判据只有文件扩展名。

### 4.4 duration=NULL 的完整构成（13 条）

| MIME | 数量 | 文件 | 处理 |
|---|---|---|---|
| `audio/ffmpeg` | 9 | `.ape` | **本次目标，应放行** |
| `audio/x-pn-realaudio` | 4 | `.rm`（位于 `DCIM/_我和亲朋好友/`） | **误收录的视频，应继续过滤** |
| `audio/midi` | 2 | `.mid` | 已有特例 |

> 关键约束：放行条件必须精确到 `%.ape` 扩展名。
> 若放宽成「duration IS NULL 就放行」，4 个 `.rm` 视频会污染曲库。

### 4.5 平台播放能力：NDK 仍必需

```
$ adb shell dumpsys media.codec | grep -iE "ape|monkey|ffmpeg"     → 无输出
$ adb shell dumpsys media.extractor
  Available extractors: AAC / AMR / AVI / FLAC / MIDI / MP3 / MP4 / MP4-DTS
                        MPEG2-PS-TS / Matroska / Ogg / WAV          → 无 APE
```

**平台既不能解封装也不能解码 APE。**

注意区分：`audio/ffmpeg` 只是 MediaScanner 的**标记**，
不代表 Stagefright 具备解码能力。二者不要混淆。

---

## 5. 调研结论

### 5.1 被证伪的假设

| 原假设 | 实测结果 |
|---|---|
| APE 未被 MediaScanner 收录，需新增扫描通道 | **证伪**。APE 已在 `MediaStore.Audio.Media` 内，仅因 `duration=NULL` 被过滤 |
| 需改用 `MediaStore.Files` 表（1~2 天） | **不需要**。在现有 Audio 表查询上加扩展名特例即可（半天） |
| `mime_type` 可用于格式判定 | **不成立**。MIME 为 ROM 私有标记，跨 ROM 不可靠 |

### 5.2 修正后的根因链

| 层 | 位置 | 现状 |
|---|---|---|
| ① 入库 | `utils/MediaStoreSelectionUtils.kt:26` | APE 在表内，被 `DURATION >= 10000` 挡掉 |
| ② 元数据 | `SyncWorker.kt:1105` 补扫白名单 | 不含 `.ape`，不走 TagLib，duration 保持 NULL |
| ③ 格式显示 | `utils/AudioMetaUtils.kt:87` | 无 `audio/ffmpeg` 分支，会显示 `ffmpeg` 或 `<unknown>` |
| ④ 播放 | Media3 + ffmpeg 扩展 + 平台 | 三者均无 APE 能力，需自建 |

### 5.3 工作量重估

| 阶段 | 原估 | 修正后 |
|---|---|---|
| Stage 1（入库） | 1~2 d | **0.5 d**（纯 Kotlin） |
| Stage 2b（NDK 解码器） | 2~4 d | **1~2 d**（WSL + NDK 环境已具备） |

---

## 6. 工具踩坑记录（可复用经验）

### 6.1 `adb shell content query` 的 `--where` 不支持 `%`

`content` 命令对参数做严格 token 校验，`_data LIKE '%.ape'` 会被拒绝，
且 `%` 在 shell→adb 参数传递中被吞掉。

**绕过方案**：不带 `--where` 全量拉取，本地 grep 过滤。

```bash
adb exec-out content query --uri content://media/external/file \
    --projection "_id:_data:mime_type" > files_dump.txt
grep -i "\.ape" files_dump.txt
```

### 6.2 `adb pull` 中文文件名被 ANSI 转换破坏

```bash
adb pull "/storage/emulated/0/Music4Phone/闷_王菲.ape" .
# → adb: error: cannot create '.\闂穇鐜嬭': Illegal byte sequence
```

`闂穇鐜嬭` 即 UTF-8 字节被当作 GBK/CP936 解释的结果。
`adb pull` 在本地解析目标路径参数时未做 UTF-8 处理。

**绕过方案**：改用 `adb exec-out` + 远程 `cat`，并把路径 **base64 编码**，
使命令行仅含 ASCII，彻底规避编码问题：

```bash
P64=$(printf '%s' "$REMOTE" | base64 -w0)
adb exec-out "cat \"\$(echo '$P64' | base64 -d)\"" > "$LOCAL"
```

实测 `adb exec-out` 直接传中文参数也可用，但 base64 方案对含引号的路径更健壮。
校验：`md5sum` 设备端与本地一致（实测 `8b2fb8f0...` 匹配）。

> 同理，设备端批量操作用 `cd <dir> && md5sum *.ape` 的 glob 形式，
> 可完全避免向 adb 传递中文参数。

### 6.3 存在多个 adb 连接时需 `-s` 指定设备

无线调试出现两个条目（同一设备的重复 mDNS 记录）：

```
adb-AE3GVB5A14002404-GHAxsW (2)._adb-tls-connect._tcp   device
adb-AE3GVB5A14002404-GHAxsW._adb-tls-connect._tcp       device
```

不指定 `-s` 会报 `more than one device/emulator`。取序号：

```bash
DEV=$(adb devices | sed -n '2p' | cut -f1)   # 注意用 cut，awk 会被空格截断
```

### 6.4 原生 ffmpeg（Windows）无法解析 MSYS 路径

ffmpeg 是原生 Windows 程序，Git Bash 的 `/tmp/foo.ape`、`/d/tmp/foo.ape`
对它不可解析。**用相对路径或 Windows 风格绝对路径**。

### 6.5 FFmpeg 无法探测「ID3v2.3 头 + syncsafe size」的 APE 文件

9 首 APE 均带 ID3v2 标签，头部声明版本 `0x03`（v2.3），
但 size 字段用了 v2.4 的 **syncsafe** 编码：

```
offset 0:  49 44 33 03 00 00 00 00 0a 01
                     └── size bytes ──┘
非 syncsafe 解析（v2.3 规范）= 0x00000A01 = 2561
syncsafe    解析（v2.4 规范）= 0<<21|0<<14|10<<7|1 = 1281   ← 实际值
```

FFmpeg 按版本号 v2.3 用非 syncsafe 解析（2561），跳过 2571 字节后找不到 MAC 头：

```
$ ffmpeg -i "Valder Fields_Tamas Wells.ape"
[in#0] Error opening input: Invalid data found when processing input
```

实际 MAC 头位于 offset 1291（`4d 41 43 20` + version `96 0f` = 3990，即 Monkey's Audio 3.99）：

```
0001285 00 00 00 00 00 00 4d 41 43 20 96 0f 00 00 34 00  >......MAC ....4.<
0001301 00 00 18 00 00 00 c8 71 00 00 00 00 00 00 ec 15  >.......q........<
                            ↑ descriptor_bytes=52, header_bytes=24
```

**解决方案**：用 `-skip_initial_bytes N` 跳过 ID3 标签（N 需按文件动态计算，
推荐启发式：依次试探 `0` / syncsafe 值 / 非 syncsafe 值，取首个后随 `MAC ` 的偏移）。

验证成功：

```
Stream #0:0: Audio: ape (APE / 0x20455041), 44100 Hz, stereo, s16p
Stream #0:1: Video: mjpeg, 1400x1400 (attached pic)   ← 内嵌封面
Duration: 00:02:38.69, bitrate: 846 kb/s
```

> 该缺陷属编码器写入不规范（v2.3 头 + v2.4 size），在中文音乐站分发的
> APE 文件中较常见。**App 内自建解码时同样需处理此 ID3 偏移问题**，
> 不能依赖标准 ID3 解析库按版本号硬算。

---

## 7. 附录：9 首 APE 清单

路径均为 `/storage/emulated/0/Music4Phone/`：

| # | 文件名 | 大小 (B) |
|---|---|---|
| 1 | The Mo Run Air A Ghille (I Lov_Various Artists.ape | 36,141,061 |
| 2 | Far Away from Home_Groove Coverage.ape | 31,052,414 |
| 3 | 闷_王菲.ape | 28,861,434 |
| 4 | My Oh My_Aqua.ape | 27,811,168 |
| 5 | 又见炊烟_王菲.ape | 23,902,417 |
| 6 | 水百景_川井郁子.ape | 20,190,833 |
| 7 | Radetzky March, Op. 228.ape | 20,884,852 |
| 8 | Imagine_John Lennon.ape | 16,288,521 |
| 9 | Valder Fields_Tamas Wells.ape | 16,791,739 |
| | **合计** | **221,924,439** |

MediaStore `_id`：2988 / 3357 / 3580 / 3597 / 3683 / 3811 / 3858 / 4167 / 4256

---

## 8. 后续

实施方案见 `docs/ape-support-plan.md`。

---

## 9. 临时方案实操记录（APE → FLAC，已完成）

在 App 支持落地前，已用 PC 侧无损转码解决当前痛点。工作目录 `D:\tmp\ape_convert`：

```
origin/     从手机拉取的 9 个 .ape（md5 与设备端逐一对齐）
flac/       转出的 9 个 .flac
convert.py  转换脚本（ID3 偏移修复 + 无损性校验）
```

### 9.1 转换命令

```bash
ffmpeg -y -skip_initial_bytes <N> -i src.ape \
    -map 0:a -map 0:v? \
    -c:a flac -compression_level 8 \
    -c:v copy -disposition:v attached_pic \
    dst.flac
```

`<N>` = ID3 标签真实长度，需按文件探测（见 6.5 节）。`-map 0:v?` 保留内嵌封面
（APE 内嵌 mjpeg `attached_pic`，实测 1400×1400）。

### 9.2 无损性验证

每个文件分别把源（带 `-skip_initial_bytes`）与目标解码到 `pcm_s32le` 后比对 md5，
**9/9 完全一致**，确认无损：

| 文件 | APE (B) | FLAC (B) | 体积比 | 时长 (ms) |
|---|---:|---:|---:|---:|
| Far Away from Home_Groove Coverage | 31,052,414 | 31,735,168 | 102.2% | 261,867 |
| Imagine_John Lennon | 16,288,521 | 16,582,128 | 101.8% | 184,227 |
| My Oh My_Aqua | 27,811,168 | 29,114,099 | 104.7% | 203,933 |
| Radetzky March, Op. 228 | 20,884,852 | 21,231,343 | 101.7% | 187,014 |
| The Mo Run Air A Ghille (I Lov_Various Artists | 36,141,061 | 36,727,101 | 101.6% | 329,827 |
| Valder Fields_Tamas Wells | 16,791,739 | 17,258,067 | 102.8% | 158,694 |
| 又见炊烟_王菲 | 23,902,417 | 24,572,536 | 102.8% | 218,333 |
| 水百景_川井郁子 | 20,190,833 | 20,647,658 | 102.3% | 214,667 |
| 闷_王菲 | 28,861,434 | 29,794,154 | 103.2% | 252,707 |
| **合计** | **221,924,439** | **227,662,254** | **102.6%** | |

> FLAC 比原 APE 大约 2.6%（APE 压缩率略高于 FLAC level 8），属正常现象，非质量损失。

### 9.3 回传与入库

1. `adb exec-in "cat > <path>" < local.flac` 写回 `/storage/emulated/0/Music4Phone/`，
   文件名保持不变（Poweramp 导入的文件名匹配可命中）。9/9 md5 校验一致。
2. **回传后 MediaStore 会自动收录，但 `duration` 为 NULL**（与 APE 同一症状），
   PixelPlayer 仍扫不到。必须主动触发扫描：

```bash
# 路径含空格/中文，务必先 URL 编码
URI="file:///storage/emulated/0/Music4Phone/$(python -c \
    "import urllib.parse;print(urllib.parse.quote('又见炊烟_王菲.flac',safe=''))")"
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "$URI"
```

3. 扫描后 9 首的 `duration` 全部正确写入（`_id` 964568~964576），均 > 10000 ms，
   满足 `buildLocalAudioSelection()` 的门槛，**PixelPlayer 与 Poweramp 导入均可命中**。

### 9.4 原 APE 的处理（已完成）

- 9 个 `.ape` 已从手机 `Music4Phone/` 删除，删除前 md5 与备份逐一对齐。
- **备份位置**：`E:\resources\music\`（9 个 .ape，md5 一致，作为无损源留存）。
- MediaStore 中的 ape 残留记录已通过 `MEDIA_SCANNER_SCAN_FILE` 广播清理（0 残留）。
- 若日后 App 侧完成 Stage 1/2 需要源 APE，从 E 盘取回即可。

---

## 10. 工具：`ape2flac.py` 使用说明

脚本已提交到仓库根目录 `ape2flac.py`，是 9.1 节「转换命令 + ID3 偏移探测」的封装。

### 10.1 用法

```bash
# 单文件（输出默认到 <文件所在目录>/flac/）
python ape2flac.py "E:\resources\music\闷_王菲.ape"

# 单文件 + 指定输出目录
python ape2flac.py "E:\resources\music\闷_王菲.ape" "D:\out"

# 整个目录（批量处理所有 .ape）
python ape2flac.py "E:\resources\music" "D:\out"

# 常用选项
python ape2flac.py <输入> --compression 8   # 压缩等级 0~8，默认 8
python ape2flac.py <输入> --no-verify       # 跳过无损性校验（默认开启）
python ape2flac.py <输入> --ffmpeg D:\tools\ffmpeg.exe   # 指定 ffmpeg 路径
```

### 10.2 注意事项

1. **路径必须用 Windows 风格**（`E:\...` 或 `E:/...`），**不能**用 Git Bash 的
   MSYS 路径（`/e/...`）。Python 在 Windows 下会把 `/e/foo` 解析成 `E:\e\foo`
   （当前盘根下多一层 `e\`），与 aria2 的 `/d/...` 坑同源。实测用 `/d/tmp/x`
   传入时文件被错误写到了 `D:\d\tmp\x`。
2. **依赖 FFmpeg**（PATH 中可调用，或 `--ffmpeg` 指定），需含 ape 解码器与
   flac 编码器（绝大多数发行版自带）。
3. **只支持 Monkey's Audio 3.97+**（FFmpeg `apedec.c` 下限），更老版本会明确报错
   而非静默产出坏文件。
4. **ID3 偏移逐文件动态探测**，不假设全库一致——批量时每个文件单独计算。
5. **内嵌封面自动保留**（mjpeg `attached_pic` → FLAC PICTURE 块）。
6. **默认开启无损性校验**：源与产物各解码到 `pcm_s32le` 后比对 md5，不一致标记
   `LOSS!` 并计入失败；生产环境不建议 `--no-verify`。
7. **输出重跑会覆盖**同名 `.flac`（仅扩展名不同），属预期行为。

### 10.3 完整闭环（手机 → PC → 手机）

脚本只负责「本地 APE→FLAC 转换」；拉取与回传仍需 adb，中文文件名处理见第 6.2 节：

```bash
# 拉取（中文名必须 base64 编码路径）
P64=$(printf '%s' "$REMOTE" | base64 -w0)
adb exec-out "cat \"\$(echo '$P64' | base64 -d)\"" > "$LOCAL"

# 转换
python ape2flac.py "$LOCAL" "$OUTDIR"

# 回传（exec-in 反向写）
adb exec-in "cat > \"\$(echo '$P64' | base64 -d)\"" < "$OUTDIR/xxx.flac"

# 回传后必须触发扫描，否则 duration=NULL 仍被曲库过滤（见 9.3）
```
