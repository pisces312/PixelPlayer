# Poweramp 数据迁移调研记录（实测）

> 整理日期：2026-09-02。本文档基于**实际下载解析**的用户备份文件：
> - `Sep-2--2026-12-14-32-PM.poweramp-backup`（Poweramp 导出，165.6 KB）
> - `PixelPlayer_Backup_1788322314872.pxpl`（PixelPlayer 导出，84 KB，app 0.7.5-beta）
>
> 目标：从 Poweramp 迁移 ①播放列表 ②历史播放记录（次数/时间）③评分（5 星）到 PixelPlayer。

---

## 一、Poweramp 备份结构（实测结论：可直接解出播放列表）

### 1.1 文件形态
`.poweramp-backup` 是**标准 ZIP**，含 2 个内部文件：

| 文件 | 大小 | 类型 | 用途 |
|---|---|---|---|
| `settings-export` | 72 KB | **非标准 SQLite（加密/私有格式，头 `kd+9Nj9lhGie1oR`）** | 设置项，与播放列表无关，**忽略** |
| `lists-export` | 303 KB | **标准 SQLite（`SQLite format 3`）** | 播放列表 + 全部歌曲记录，**核心** |

### 1.2 `lists-export` 表结构
```sql
-- 播放列表：29 个
CREATE TABLE playlists (
  _id INTEGER PRIMARY KEY,
  name TEXT NOT NULL,            -- 列表名（__最爱、__抖音、兜风-抒情 …）
  keep_list_pos INTEGER NOT NULL,
  keep_track_pos INTEGER NOT NULL
);

-- 歌曲记录：2720 行（其中列表成员 1046，普通曲库 1674）
CREATE TABLE tracks (
  _id INTEGER PRIMARY KEY,
  playlist_id INTEGER,           -- 关联 playlists._id；NULL = 普通曲库成员，非列表歌曲
  path TEXT NOT NULL,            -- 相对路径：primary/Music4Phone/xxx.mp3
  readable_name TEXT,            -- Poweramp 修正后的可读歌名
  file_type INTEGER,
  cue_offset_ms INTEGER,
  rating INTEGER,                -- ★ 0–5 星评分
  played_at INTEGER,             -- 最近播放时间（epoch ms）
  played_fully_at INTEGER,       -- 完整播放时间
  played_times INTEGER,          -- ★ 播放次数
  last_pos INTEGER,
  export_type INTEGER NOT NULL,
  preset_id INTEGER,
  total_played_times INTEGER     -- ★ 累计播放次数
);
```

### 1.3 实测数据
- **29 个播放列表全部解析成功**，已逐一导出为 m3u8（共 1046 首歌曲引用）。
- 路径格式：`primary/Music4Phone/若月亮没来 -…mp3` → 映射绝对路径 `/storage/emulated/0/Music4Phone/…`（`primary` = 内部存储卷根）。
- `tracks` 自带 **评分（rating 0–5）** 与 **播放统计（played_times / played_at / total_played_times）**——这正是用户想迁移的历史与评分数据。

### 1.4 关键结论
> 播放列表**可以直接解出**，无需解析加密的 settings-export，也无需计划文档中担心的 `folders.db` join（lists-export 自包含）。
> 但 Poweramp 侧**没有 artist/album/duration 元数据**（只有 path 和 readable_name），迁移到 PixelPlayer 时需**从文件名解析 `歌手 - 歌名`**。

---

## 二、PixelPlayer 备份结构（`.pxpl`）

### 2.1 文件形态与导出设置
- 格式：`PXPL` 魔数 + **ZIP**，内含多个独立 JSON 模块 + `manifest.json`（含 app 版本、设备信息、各模块 checksum）。
- 导出入口：`设置 → Backup & Restore`，导出/恢复时**均可勾选要包含的模块**（`BackupModuleSelectionDialog`）。
- 模块清单（11 个可备份 section）：playlists / global_settings / favorites / lyrics / search_history / transitions / engagement_stats / playback_history / quick_fill / artist_images / equalizer / ai_usage_logs。

### 2.2 音乐文件路径怎样保存 —— 重要结论
> **PixelPlayer 备份不保存文件路径。** 歌曲一律以 `songId`（= MediaStore 的数字 `_id`，字符串）引用。

| 模块 | 歌曲引用方式 | 恢复行为 |
|---|---|---|
| playlists | `songIds: List<MediaStore id>` + 可选 `songMetadata{title, artist, album, duration}` | **songId 直配 + 元数据匹配**（title+artist 大小写不敏感，album+duration±2s 消歧）；未解析歌曲写入 pending 文件待下次同步解析 |
| favorites | `FavoritesEntity(songId, isFavorite, timestamp)` | 直接按 songId 写入，**无元数据匹配** |
| playback_history | `{songId, timestamp, durationMs, startTimestamp, endTimestamp}` | 直接按 songId 写入，`clearExisting=true` **整段替换** |
| engagement_stats | `SongEngagementEntity(songId, playCount, totalPlayDurationMs, lastPlayedTimestamp)` | 直接按 songId 写入，**replaceAll 替换**；字段支持别名（play_count/score/plays 等） |

### 2.3 评分字段结论
> **PixelPlayer 目前没有评分（rating/星级）字段。** `Song` 模型仅含 `isFavorite: Boolean`（收藏）。
> 播放次数/时长有：`playback_history.json`（时间线事件）与 `engagement_stats`（每首歌 playCount / totalPlayDurationMs / lastPlayedTimestamp）。

---

## 三、迁移方案

### 3.1 数据流总览
```
Poweramp lists-export (SQLite)
   │  ① 从文件名解析 artist/title（"歌手 - 歌名.ext"）
   │  ② path 卷前缀映射：primary/ → /storage/emulated/0/
   ▼
中间层：歌曲统一为 {artist, title, album, duration, rating, playCount, lastPlayedAt}
   │
   ├─③a 歌单：构造 .pxpl（playlists + songMetadata）→ PixelPlayer 恢复备份（走元数据匹配，免改代码）
   ├─③b 历史/统计/收藏：songId 必须为设备 MediaStore id → 需在设备上匹配后写入
   └─③c 评分：映射为收藏（见 3.4）
```

### 3.2 播放列表导入（✅ 可不改代码）
- 利用 PixelPlayer 备份恢复已有的**元数据匹配**机制：构造一份 `.pxpl`，`playlists.json` 里每个列表歌曲用**虚拟 songId + songMetadata{title, artist, duration}`**，在 PixelPlayer 里「恢复备份」勾选 Playlists 即可自动匹配本地曲库。
- 局限：Poweramp 无 album/duration 元数据，匹配主要靠 title+artist；同名多版本歌曲可能解析歧义（可先按 duration 从本地库回填再构造）。

### 3.3 历史播放记录导入（需 app 支持）
- 现状：`playback_history` / `engagement_stats` 恢复时**直接按 songId 写库**，不做元数据匹配 → 无法靠离线构造 pxpl 完成。
- 方案：
  - **A（推荐，改 app）**：新增「从 Poweramp 备份导入」入口，在 app 内完成 ① 解析 lists-export ② 文件名→artist/title ③ 与本地 MediaStore 匹配（绝对路径 + 元数据）→ 直接写 `PlaybackStatsRepository` / `EngagementDao` / 歌单 / 收藏。可完整还原次数、时间线、时长。
  - B（免改 app，折中）：只导入歌单（3.2 方案）；历史/统计暂不迁（或由用户接受从零累计）。

### 3.4 评分（5 星）方案
> 依据：Poweramp `rating` 0–5 星；PixelPlayer **无评分字段，仅有 isFavorite**。

| 方案 | 做法 | 工作量 | 效果 |
|---|---|---|---|
| **A · 评分→收藏（推荐先做）** | `rating ≥ 4`（可选阈值）→ 写入 `favorites`；`rating 1–3` 忽略或另存 | 极低（构造 favorites.json / 导入脚本） | 用现有「收藏」表达喜欢，零 UI 改动 |
| B · 评分→标签/备注 | 把评分塞进歌名/备注（不推荐，污染元数据） | 低 | 不优雅，不推荐 |
| C · 引入原生 5 星评分 | Song + Room 新增 `rating 0–5` 字段、播放器/列表评分 UI、排序、TagLib 写回 | **大**（实体/迁移/UI/写回） | 像素级还原，长期价值高 |

**建议**：短期用 A（rating≥4 → 收藏 + 把 `played_times` 导入为 playCount 作为"热度"），同时把 Poweramp 评分统计单独保留一份 JSON 备份；若确需原生 5 星体验，再评估方案 C。

---

## 四、待办 / 决策点
- [ ] 是否需要 app 内「Poweramp 备份导入」功能（决定 3.3 的 A/B）
- [ ] 评分策略定档：A（映射收藏）还是 C（原生 5 星）
- [ ] 若走"构造 pxpl"路线：确认歌单虚拟 songId + songMetadata 构造方式与校验（可先用现有 pxpl 反推 playlists.json 合法 schema）
- [ ] 同名多版本歌曲的消歧策略（建议从本地库按 duration 回填）
