# PixelPlayer vs Poweramp 功能差距分析

日期：2026-09-07
方法：PixelPlayer 源码梳理 + Poweramp jadx 反编译（build-1025-uni / versionCode 1025009）包结构 + 资源字符串反推

---

## 结论

**PixelPlayer 已满足当前使用需求，暂不计划从 Poweramp 引入功能。**
PixelPlayer 在 AI 集成（Daily Mix / AI 歌单生成）、多云音源（网易/QQ/Telegram/Jellyfin/Navidrome）、Wear OS、Compose Material You 现代化 UI 等方面具备 Poweramp 没有的优势；而 Poweramp 的强项（深度 DSP、参数 EQ、DSD 格式、CUE 分轨等）并非刚需。

---

## 差距总览

`✓` = 已有，`✗` = 缺失，`△` = 部分支持。

### DSP / 音效

| 功能点 | PixelPlayer | Poweramp | 备注 |
|---|:---:|:---:|---|
| 图形均衡器（系统 EQ） | ✓ | ✓ | PixelPlayer 走 Android AudioEffect，10 段 |
| 参数均衡器（Parametric EQ，freq/Q/gain） | ✗ | ✓ | Poweramp 核心卖点 |
| AutoEq 预设导入 | ✗ | ✓ | 可从 zip 导入整个 AutoEq 项目 |
| 声道平衡（L/R Balance） | ✗ | ✓ | |
| Preamp / Headroom | ✗ | ✓ | DVC (Direct Volume Control) 体系 |
| Limiter / Compressor | ✗ | ✓ | |
| Reverb 混响 | ✗ | ✓ | |
| 立体声增强 / 加宽 | △（Virtualizer） | ✓ | |
| Mono 单声道混合 | ✗ | ✓ | |
| 重采样质量（SoX 级） | ✗ | ✓ | resampler3 插件 |
| 每首歌 EQ 预设指派 | ✗ | ✓ | per-song / per-output |

### 播放控制

| 功能点 | PixelPlayer | Poweramp | 备注 |
|---|:---:|:---:|---|
| 播放速度 / 变速不变调 | △（仅 Mashup DJ 模式） | ✓ | 主播放器无通用速度控制 |
| 变调（Pitch Shift） | ✗ | ✓ | tempo 插件 |
| AB 重复（A-B Loop） | ✗ | ? | 反编译资源中未找到明确字符串 |
| 歌曲书签（Bookmark） | ✗ | ✓ | |
| 音量键长按切歌 | ✗ | ✓ | |
| 淡出淡入（Fade on seek/pause） | △（仅 crossfade） | ✓ | |
| 智能 Shuffle（最少播放优先） | ✗ | ✓ | "Less Random" |

### 解码器 / 格式

| 功能点 | PixelPlayer | Poweramp | 备注 |
|---|:---:|:---:|---|
| DSD（dff/dsf） | ✗ | ✓ | FFmpeg 扩展默认不含 DSD 解码 |
| WavPack（.wv） | ✗ | ✓ | 独立 WvPluginService |
| Musepack（.mpc） | ✗ | ✓ | 独立 MpcPluginService |
| TTA / TAK | ✗ | ✓ | 走 FFmpeg 插件 |
| Tracker 模块（.it/.mod/.s3m/.xm） | ✗ | ✓ | ModPluginService |
| CUE Sheet 分轨 | ✗ | ✓ | data/cue/ 子包 |
| USB Hi-Res 直出 | ✗ | ✓ | oslhd / athd (Sony) |

### 媒体库 / UI

| 功能点 | PixelPlayer | Poweramp | 备注 |
|---|:---:|:---:|---|
| CUE 分轨浏览 | ✗ | ✓ | |
| SAF 云盘 Provider 挂载 | △（仅本地） | ✓ | Poweramp 支持云盘作为 Provider |
| 频谱 / Milkdrop 可视化 | ✗ | ✓ | milk/ 子包 + 预设 |
| 第三方皮肤框架 | ✗ | ✓ | SKIN_MAIN 意图 |
| 歌词插件生态（多源） | △（仅 LRCLIB） | ✓ | Genius/Musixmatch/QuickLyric 等 |
| 播放列表桌面快捷方式 | ✗ | ✓ | |

### 其他

| 功能点 | PixelPlayer | Poweramp | 备注 |
|---|:---:|:---:|---|
| Last.fm / Libre.fm Scrobbling | △（仅 Navidrome 协议） | ✓ | |

---

## PixelPlayer 独有优势（Poweramp 没有的）

1. **AI 集成**：Daily Mix 个性化推荐、AI 生成播放列表（Gemini/Deepseek/OpenAI 兼容）
2. **多云音源**：网易云、QQ 音乐、Telegram、Jellyfin、Navidrome、Google Drive
3. **Wear OS 端**：独立 wear 模块 + 手表遥控 / 传歌
4. **现代化 UI**：Jetpack Compose + Material 3 + Material You 动态取色
5. **元数据编辑**：TagLib + JAudioTagger 双通道，批量编辑
6. **DJ Mashup**：双 Deck 混音模式
7. **备份 / Poweramp 导入**：已支持从 Poweramp 备份迁移

---

## 如果以后要补，优先级排序（备查）

| 优先级 | 功能 | 工作量 | 理由 |
|---|---|---|---|
| P0 | 软件 DSP 管线（参数 EQ + Preamp + Limiter + Balance） | 1–2 周 | Poweramp 核心竞争力，EQ 党刚需 |
| P1 | 播放速度 / 变速不变调 | 1–2 天 | 成本极低、播客/有声书用户刚需 |
| P1 | Last.fm Scrobbling | 1–2 天 | 社交属性、实现简单 |
| P2 | CUE Sheet 分轨 | 1 周 | 整轨古典 / Live 专辑用户需要 |
| P2 | AB 重复 + 书签 | 2–3 天 | 语言学习 / 练琴场景 |
| P2 | DSD / WavPack 等小众格式 | 2–3 周 + 维护 | 需 fork jellyfin-androidx-media，长期维护成本高 |
| 不推荐 | Milkdrop 可视化、皮肤框架、Tracker 格式、USB Hi-Res | — | ROI 太低或维护成本过高 |

---

## 参考路径

- Poweramp 反编译根目录：`D:\3rd-party-projects\Poweramp-decompiled\`
  - Manifest：`resources/AndroidManifest.xml`
  - 字符串：`resources/res/values/strings.xml`
  - DSP 管线：`sources/com/maxmpz/audioplayer/processing/Pipeline2.java`
  - 解码器插件：`sources/com/maxmpz/audioplayer/decoder/`
- PixelPlayer EQ：`app/src/main/java/com/theveloper/pixelplay/data/equalizer/EqualizerManager.kt`
- PixelPlayer 播放引擎：`app/src/main/java/com/theveloper/pixelplay/data/service/player/DualPlayerEngine.kt`
