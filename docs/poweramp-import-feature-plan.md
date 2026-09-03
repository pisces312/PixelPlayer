# 方案 A 详细计划：第三方导入（Poweramp 备份一键导入）

> 版本：v1.4（2026-09-03，已按本轮四项决策修订：730 天裁剪改为条数上限、澄清 affinityScore 与时长的关系、双 0 歌曲维持不过滤、评分改由原生五星存储承接）
> 目标：在 PixelPlayer 内新增「第三方导入」唯一入口，选择 Poweramp 备份（`.poweramp-backup`），一键迁移 播放列表 / 播放历史 / 参与度统计 / 收藏 + **评分**，支持与已有数据**合并**。
> 范围外：原生 5 星评分的 **UI 层**（本期只用其数据层；评分直接落 `favorites.rating`，不再走旁路文件）。
>
> **依赖关系（v1.4）**：本方案依赖 `docs/five-star-rating-feature-plan.md` 的 **M1 数据层**（`favorites.rating` 列 + Migration 42→43）。两个功能的排期关系见 §9 **D14**。
>
> **v1.2 修订要点**（详见 §2.1、§3.1、§4.1、§9）：
> ① 修正表名 `song_entities` → **`songs`**（原表述在库中不存在）；
> ② 实测确认 Poweramp **无播放时长数据**、`played_at` 为**毫秒**且**只有最后播放时间（无事件流）**；
> ③ 补 Poweramp → PixelPlayer 逐字段映射表与 5 条归一化规则（N1–N5）；
> ④ 补充 `MusicDao` 批量投影方法（路径匹配的前置条件，原清单遗漏）；
> ⑤ 明确各存储为「幂等分步 + 可重跑」而非跨存储事务。
>
> **v1.3 修订要点**（目标备份到位后的校正，详见 §2.1、§3.0、§3.2、§9）：
> ⑥ 全部计数改用**目标备份 `Sep-2-2026` 本体实测值**（v1.2 是用 `Dec-2025` 推算的近似值）；
> ⑦ **修正文件名解析假设**：实测 `readable_name` **只含标题不含歌手**，且文件名中 artist/title **顺序以「歌名 - 歌手」为主、不固定**，须双向试探（推翻 v1.1「常含歌手 - 歌名」）；
> ⑧ 新增「目录名含 artist/album」这一匹配信号（实测 112 首在带 artist/album 的子目录下）；
> ⑨ 补 PixelPlayer 侧 `.pxpl` 实测：`songId` 类型分裂（`String` vs `Long`）得到数据侧确认，并给出导入规模冲击预估。
>
> **v1.4 修订要点**（详见 §2.1、§4.4、§9）：
> ⑩ **评分落地改为原生五星存储**：不再写旁路文件 `poweramp_ratings.json`，直接写入 `favorites.rating`；未达收藏阈值的评分也保留（v1.3 会丢失 3 星 98 首 / 2 星 2 首）。详见 `docs/five-star-rating-feature-plan.md`。
> ⑪ **播放历史的 730 天裁剪**：确认为**与推荐排序无关**（推荐读 Room 的 `song_engagements`，不读 `playback_history.json`）。按用户决策去除时间裁剪，改为**条数上限 20,000 条**。详见 `docs/recommendation-algorithm-design.md` §6。
> ⑫ **澄清 `affinityScore` 与歌曲长度无关**：`affinityScore` 用的是 `song_engagements.total_play_duration_ms`（**累计播放时长**），不是 `Song.duration`。所以「不知道歌曲长度」不妨碍计算，规则 N2 的理由随之修正。
> ⑬ **双 0 歌曲维持不过滤**（用户决策）：`playCount = 0 且 playedAt = 0` 的 54 首照常写入。
>
> **v1.3 实测依据（三份真实文件）**：
> - `E:\downloads\Sep-2--2026-12-14-32-PM.poweramp-backup`（**目标备份**，169,610 B，2720 行 / 29 歌单）
> - `E:\downloads\PixelPlayer_Backup_1788322314872.pxpl`（PixelPlayer 侧现状，84,334 B，schemaVersion 3）
> - v1.2 已用的 `Dec-19--2025` / `Apr-15--2025`（用于 schema 版本差异对照）

---

## 1. 功能定位与入口

### 1.1 入口（唯一）
- **唯一入口**：`设置 → Backup & Restore → 底部「第三方导入」卡片`（与备份恢复同区，语义一致）。不在库页重复放菜单。
- 进入后是**来源选择页**（`ThirdPartyImportScreen`），列出可导入来源：
  - **Poweramp**（本期实现）
  - 后续可扩展：其他播放器（架构预留 `ImportSource` 接口）
- 选择 Poweramp → 文件选择器（`OpenDocument`，MIME `application/octet-stream` + `*/*`，可带 `.poweramp-backup` 过滤）→ 进入解析与配置向导。

### 1.2 技术架构（预留扩展）
```kotlin
interface ImportSource {
    val id: String                       // "poweramp"
    val displayName: String
    val supportedFileExtensions: List<String>   // [".poweramp-backup"]
    suspend fun parse(uri: Uri): ImportSourceData   // 解出统一中间数据
}

// 统一中间数据（跨来源通用）
data class ImportSourceData(
    val playlists: List<ImportPlaylist>,            // 播放列表
    val songRecords: Map<String, ImportSongRecord>  // key = Poweramp 的 path（唯一去重键，见 2.1 规则 N4）
)
data class ImportPlaylist(val name: String, val songKeys: List<String>) // songKey=path，顺序即 Poweramp 内顺序
data class ImportSongRecord(
    val path: String,          // Poweramp 相对路径 primary/...（去重键）
    val titleHint: String?,    // 首选取 readable_name（实测 96%+ 为纯标题，F11），并保留文件名解析结果作为并列候选（D10）
    val artistHint: String?,   // **唯一来源是文件名解析**（readable_name 不含歌手，F11）
    val albumHint: String?,    // 来自目录名，仅 112/1676 首可获得；作消歧加分项（F14）
    val rating: Int,           // 0–5（原始值，保留；Poweramp 可为 NULL → 归 0，见 N1）
    val playCount: Int,        // 来自 played_times（经 N3 归一化）
    val lastPlayedAt: Long?,   // 来自 played_at，单位 = 毫秒（实测 F1/F16，无需换算）
    val playedFullyAt: Long?,  // 来自 played_fully_at，单位 = 毫秒；0 = 从未完整播放
    val totalPlayDurationMs: Long?  // **Poweramp 无此数据，恒为 null**（实测 F2，见 N2）
)
```

---

## 2. Poweramp 备份解析（`PowerampBackupParser`）

### 2.1 输入解析

1. `ZipInputStream` 读取 `.poweramp-backup`，找出 `lists-export` 条目（跳过 `settings-export`）。
   - 实测：部分备份还含 `selected_aa/*.jpg`、`selected_playlist/*.jpg`（歌单封面图）。**本期不导入**，仅作扩展留位。
2. 用 `android.database.sqlite.SQLiteDatabase.openDatabase(file, null, OPEN_READONLY)` 打开解出的 `lists-export`（**只读**，不修改）。
3. 读取两张表（**实测 schema，取自 `Dec-19--2025` 备份**）：

```sql
CREATE TABLE playlists (
  _id INTEGER PRIMARY KEY, name TEXT NOT NULL,
  keep_list_pos INTEGER NOT NULL, keep_track_pos INTEGER NOT NULL)

CREATE TABLE tracks (
  _id INTEGER PRIMARY KEY, playlist_id INTEGER, path TEXT NOT NULL,
  readable_name TEXT, file_type INTEGER, cue_offset_ms INTEGER,
  rating INTEGER, played_at INTEGER, played_fully_at INTEGER,
  played_times INTEGER, last_pos INTEGER, export_type INTEGER NOT NULL,
  preset_id INTEGER, total_played_times INTEGER)
```

> ⚠️ **schema 随 Poweramp 版本变化**（实测 F4）：`total_played_times` **仅存在于新版**（`Dec-19-2025` 有，`Apr-15-2025` 无）。
> 因此解析器**必须**用 `PRAGMA table_info(tracks)` 动态探测列是否存在，**禁止**硬编码列名直接 `SELECT`。

4. **字段映射表**（Poweramp 列 → `ImportSongRecord` → PixelPlayer 落地）

| Poweramp 列 | 全版本都有？ | → `ImportSongRecord` | → PixelPlayer 落地 | 备注 |
|---|---|---|---|---|
| `path` | ✅ | `path`（去重键） | 匹配键（→ `songs.file_path`） | 卷前缀可能为 `primary/`、`9C33-6BBD/`(SD)、或**无前缀裸文件名** |
| `readable_name` | ✅ | `titleHint` | 元数据匹配候选 | 实测非空率 100% |
| `rating` | ✅ | `rating` | **`favorites.rating`（新增列）**；≥ 阈值 → `isFavorite = 1` | **可为 NULL**（目标备份实测 2 行）→ 规则 N1。<br>⚠️ **v1.4 变更**：不再写旁路文件；未达阈值的评分以 `isFavorite = 0, rating = N` 形式**保留**，见 §4.4 |
| `played_at` | ✅ | `lastPlayedAt` | `song_engagements.last_played_timestamp` + 播放历史合成事件 | **单位 = 毫秒**（实测 13 位，如 1766075055093） |
| `played_fully_at` | ✅ | `playedFullyAt` | 暂不落地（预留） | `0` = 从未完整播放 |
| `played_times` | ✅ | `playCount` | `song_engagements.play_count` | 规则 N3 归一化 |
| `total_played_times` | ❌ 仅新版 | — | **不导入** | 语义为「完整播放次数」，且与 `played_times` 无稳定大小关系（实测 13 行 `played_times < total_played_times`） |
| — | — | `totalPlayDurationMs` | **无源，不导入** | 规则 N2 |
| `cue_offset_ms`, `file_type`, `last_pos`, `export_type`, `preset_id` | ✅ | — | 不导入 | CUE / 文件类型 / 播放位置 / 音效预设，本期无关 |

5. **归一化规则**（实测驱动，必须实现）

| 规则 | 内容 | 实测依据 |
|---|---|---|
| **N1** | `rating IS NULL` → 归 `0` | 目标备份实测 **2 行**为 NULL，而 `rating: Int` 不可空 |
| **N2** | `totalPlayDurationMs` **恒为 null，不捏造** | `tracks` 表**无任何时长列**（duration-like 列仅 `played_times` / `total_played_times`，二者皆计数非时长）。写入时 `durationMs = 0`，即 `total_play_duration_ms += 0`，**不污染本地已有收听时长** |
| **N3** | `played_at > 0 && played_times == 0` → `playCount = 1` | 目标备份实测 **424 行**存在此矛盾（有最后播放时间却记 0 次）。两者会不同步，取「至少播过 1 次」更贴近事实 |
| **N4** | 去重键用 `path`；重复行**优先取曲库行**（`playlist_id IS NULL`，等价 `export_type = 3`） | 目标备份实测 **627 组**重复 `path`，但**评分冲突 0 组**（Poweramp 内部保持同步），故仅需确定性规则即可 |
| **N5** | `cue_offset_ms > 0` 时去重键应为 `path + "#" + cue_offset_ms` | 目标备份实测 `cue_offset_ms` 全为 0（无 CUE 音轨），但 CUE 分割场景下同一 `path` 会对应多首曲子，需防患 |
| **N6** | `export_type` 可直接区分曲库（`3`）与列表引用（`1`），比 `playlist_id IS NULL` 更语义化 | 目标备份实测：`export_type=3` 1674 行、`=1` 1046 行，与 `playlist_id` 判定**完全等价**；建议优先用 `export_type`，但保留 `playlist_id` 作回退（旧版本可能无此列） |

6. **组织（v1.3：目标备份已到位，全部为本体实测值）**

| 口径 | 目标备份 `Sep-2-2026`（**v1.3 实测**） | 对照 `Dec-19-2025` | 对照 `Apr-15-2025` |
|---|---|---|---|
| `tracks` 总行数 | **2720** | 2637 | 2540 |
| 曲库行（`playlist_id IS NULL`） | **1674** | 1625 | 1590 |
| 列表引用行（`playlist_id NOT NULL`） | **1046** | 1012 | 950 |
| 去重后歌曲数 | **1676** | 1628 | — |
| 歌单数 | **29** | 29 | 28 |
| **空歌单** | **0** | — | — |
| **列表独有**（不在曲库中） | **2** | 3 | — |
| 重复 `path` 组数 | **627**（评分冲突 0 组） | 612 | — |
| `played_at > 0` 的歌曲 | **2164** / 2720 行 | — | — |

> ✅ **v1.1 原文的 2720 / 1674 / 1046 / 1676 / 29 / 2 全部实测吻合**，无需修正。

- **列表独有歌曲的路径无卷前缀**（目标备份实测 2 首：`01 - 彩云追月.mp3`、`10 - 同桌的你 [风行版].mp3`）。
  这些是**裸文件名，既无目录也无卷前缀**，绝对路径匹配必然失败，**只能依赖 §3.1 第 2/3 级（文件名 / 元数据）匹配**。
- **卷前缀分布（目标备份）**：`primary` 1674 首、裸文件名 2 首，**无 SD 卡卷**。`Apr-15-2025` 的 `9C33-6BBD/` 属历史情况，说明卷前缀处理**仍不可省略**（换机 / 换卡后会再次出现）。
- **目录分布**：`primary/Music4Phone/` 下 **1564 首（93.3%）无子目录**；**112 首**在带 artist/album 的子目录下（如 `primary/Music4Phone/周杰伦/十一月的萧邦/`）。后者可作为第 3 级匹配的补充信号（见 §3.2）。
- **扩展名分布**：`.mp3` 1286 / `.flac` 356 / `.wav` 23 / `.ape` 9 / `.m4a` 1 / `.aac` 1。
  ⚠️ **`.ape`（9 首）需预期为未匹配**：Android MediaStore 对 APE 格式索引支持不完整，这 9 首可能**根本不在 PixelPlayer 曲库中**，属预期内的 `unresolved`（非缺陷）。
- **范围决策**：播放历史 / 参与度统计 / 评分按**全部去重歌曲**导入；**歌单**按每个列表的引用顺序导入（引用次数，PixelPlayer 端自动去重）。

7. 异常处理：损坏 zip / 非 SQLite / 缺 `tracks` 或 `playlists` 表 / 必需列全缺失 → 返回可读错误（"不是有效的 Poweramp 备份"）。

### 2.2 路径规范化（`PowerampPathNormalizer`）

- 卷前缀映射表：`primary → /storage/emulated/0/`；其余卷（SD 卡）保留卷名并尝试匹配。
  - **实测确认 SD 卡卷真实存在，非假设**（F7）：`Apr-15--2025` 备份的路径为 `9C33-6BBD/Music4Phone/...`（FAT32 卷序列号），对应外置 SD 卡；`Dec-19--2025` 备份则全部为 `primary/...`。
  - 因此卷前缀**不能只处理 `primary`**，需枚举本机可用存储卷并与 `songs.file_path` 的实际根做前缀匹配。
- **无卷前缀的裸文件名**（实测 3 首列表独有歌曲，见 §2.1.6）：规范化后无法构成绝对路径，**直接降级**到 §3.1 第 2/3 级匹配，不参与第 1 级路径匹配。
- **本机不存在该卷时**（如换机后 SD 卡已移除）：该卷下的全部路径跳过第 1 级，直接走文件名 / 元数据兜底。
- 规则：去卷前缀 → 拼对应存储根；统一路径分隔符、去除尾部斜杠；不做大小写折叠（Android 路径大小写敏感），但提供文件名兜底匹配。

---

## 3. 歌曲匹配（`SongMatcher`）—— 关键模块

### 3.0 关于"路径匹配"的澄清（决策依据）
- **路径匹配不是 PixelPlayer 的原生备份策略**。原生（`PlaylistsModuleHandler`）是 songId + 元数据（title/artist/album/duration）匹配，因为备份里只存 songId、不存路径。
- **但本地曲库表 `songs` 有 `file_path` 列**（= MediaStore DATA 绝对路径）。⚠️ **表名是 `songs`，不是 `song_entities`**（`SongEntity.tableName = "songs"`，全库无 `song_entities`）。`Song.path` 也映射它（`SongEntity.toSong()` 中 `path = this.filePath`）。因此路径匹配可在**同设备**下做"数据库内 join"（把 Poweramp 的 `primary/…` 规范化为 `/storage/emulated/0/…` 后比对 `file_path`），最快最准、零额外 IO。
- **不需要"按路径真实读音乐文件"来构造字段**：MediaStore 扫描时已把歌曲的 title/artist/album/duration 全部索引进 `songs`（来源即文件 ID3 标签），元数据匹配直接用库内字段即可，省 IO、更快。真正的兜底是**文件名解析**。
  ⚠️ **v1.3 实测修正**：v1.1 称「Poweramp 文件名常含 `歌手 - 歌名`」——**这个假设是反的**。目标备份实测中文件名以「**歌名 - 歌手**」为主，且顺序**不固定**，必须双向试探。详见 §3.2。
- **路径匹配必须限定 `source_type = LOCAL`**：`songs.file_path` 对云端歌曲（Telegram / Netease / GDrive / QQMusic / Navidrome / Jellyfin）通常为空或非 MediaStore 路径，不应参与路径比对。
- **跨设备换机**时路径失效：① 路径匹配失效，由 ②③ 兜住。

### 3.1 匹配优先级（从快到准、从准到兜底）

> **前置条件（v1.2 补充）**：`MusicDao` 需新增一条批量投影查询，一次性载入内存建索引：
> ```kotlin
> @Query("SELECT id, file_path, title, artist_name, album_name, duration FROM songs WHERE source_type = 0")
> suspend fun getAllLocalSongsForImport(): List<ImportSongProjection>
> ```
> 现有方法均不适用：`getAllLocalSongSummaries()` 返回的 `SongSummary` **不含 `file_path`**；`getSongByPath(path)` 是 `LIMIT 1` 单点查询，逐首调用在 1600+ 规模下不可接受。

1. **绝对路径精确匹配**：规范化后的路径 == `songs.file_path`（MediaStore DATA，绝对路径），且 `source_type = LOCAL`。→ 最可靠，优先。
2. **文件名匹配**：Poweramp path 尾段文件名（含扩展名）== 本地 `file_path` 尾段文件名。兜住存储根差异与 SD 卡卷变化。
   - **实测必要性**：列表独有的 **2 首**是裸文件名（无卷前缀），只能靠这一级。
   - **预期命中率（同设备）**：第 1 级可达 1674/1676 = **99.9%**，第 2/3 级实际只需处理 2 首裸文件名 + 少量改名/移动过的曲目。
3. **元数据匹配**：解析出 `artist` + `title` 后，复用现有消歧逻辑（参考 `PlaylistsModuleHandler.resolveSongId`，其 `DURATION_TOLERANCE_MS = 2000L`）：
   - 归一化 `title|artist`（trim + lowercase）索引；
   - 唯一候选 → 命中；
   - 多候选 → 用 `album` 消歧，再按 `duration ± 2000ms` 消歧；
   - 仍歧义 → 视为未匹配（宁缺勿错）。
   - 元数据来源：直接查 `songs`（`title` / `artist_name` / `album_name` / `duration`），不真实读文件。
   - ⚠️ **v1.3 关键修正——`title` 与 `artist` 的取值来源不同**：

     | 字段 | 来源 | 实测依据 |
     |---|---|---|
     | **`title`（首选）** | `readable_name` | 目标备份中 **96%+ 的 `readable_name` 是纯标题**，已剥离歌手与序号（如文件名 `02 - 煎熬.mp3` → `readable_name = "煎熬"`） |
     | **`artist`（唯一来源）** | **文件名主干解析** | `readable_name` **不含歌手**（如 `宫保鸡丁 - 陶喆.flac` 的 `readable_name` 只有 `宫保鸡丁`）。实测仅 **2.6%** 的 `readable_name` 含 `" - "`，且均为古典曲目名（`Ludwig van Beethoven: Bagatelle…`） |

   - ⚠️ **补充信号：目录名**。实测 112 首位于 `…/周杰伦/十一月的萧邦/` 这类带 artist/album 的子目录下，可提取 `artistHint` / `albumHint` 参与消歧。但 **93.3%（1564 首）直接在 `Music4Phone/` 根目录、无子目录**，故目录信号只能作为**加分项**，不能作为主要依据。
4. **无法匹配**：计入 `unresolved`，在结果报告中列出数量与示例（不静默丢弃）。

### 3.2 文件名解析歌手/歌名（v1.3 按目标备份实测重写）

#### 3.2.1 实测模式分布（目标备份 1676 首去重歌曲）

| 模式 | 占比 | 实例 |
|---|---|---|
| 序号前缀 + ` - ` | **38.2%** | `02 - 煎熬`、`03 - A Thousand Miles`、`07 - Donna Donna` |
| 下划线 `_` | **20.7%** | `Life for Rent_Dido`、`Letter_iris`、`See You Again_Various Artists`、`英雄的黎明_原声带` |
| ` - `（无序号） | **18.1%** | `宫保鸡丁 - 陶喆`、`GReeeeN - キセキ`、`向云端 - 小霞&海洋Bo` |
| 连字符无空格 | **17.5%** | `下沙-游鸿明`、`繁花-董真`、`对面的女孩看过来-任贤齐` |
| 序号前缀 + 连字符 | 1.3% | `11-Ave Maria`、`15-Love Story`、`2-Pour Else` |
| 无分隔符 | 3.4% | `Frontier`、`08你的微笑`、`爱就一个字 (电影《宝莲灯(1999)》片尾曲` |
| 序号前缀 + 点号 | 0.7% | `01.Victory Remix`、`03.Exodus`、`05.Flight of The Bumblebee` |

> **带序号前缀合计 40.2%**（674 首）——序号必须剥离，否则 title 归一化失败。

#### 3.2.2 ⚠️ artist / title 顺序不固定（推翻 v1.1 假设）

v1.1 写「Poweramp 命名常见形态：`歌手 - 歌名.ext`」——**实测相反，以「歌名 - 歌手」为主**：

| 文件名 | 实际顺序 |
|---|---|
| `宫保鸡丁 - 陶喆` | 歌名 - 歌手 |
| `The Last Waltz - Engelbert Hamperdink` | 歌名 - 歌手 |
| `向云端 - 小霞&海洋Bo` | 歌名 - 歌手 |
| `下沙-游鸿明` | 歌名 - 歌手 |
| `GReeeeN - キセキ` | **歌手 - 歌名** |
| `おどるポンポコリン-《樱桃小丸子》TV动画片尾曲 - B.B.Queens` | 歌名 - 歌手（且含两段分隔） |

**因此解析必须双向试探，不能假定顺序**：

```kotlin
// 伪代码
val (left, right) = splitByDelimiter(stem)          // 取最后一个分隔符切分
val candidates = listOf(
    ParsedTitle(title = left,  artist = right),     // 试探 A：歌名 - 歌手
    ParsedTitle(title = right, artist = left)       // 试探 B：歌手 - 歌名
)
// 两个候选都去元数据索引里查，任一唯一命中即采纳；两者都命中且不同 → 用 album/duration 消歧，仍歧义则放弃
```

> 判断顺序的辅助启发式（可选，仅作打分）：`readable_name` **通常等于 title**——若 `left == readable_name` 则倾向「歌名 - 歌手」，若 `right == readable_name` 则倾向「歌手 - 歌名」。实测该启发式在多数样本上成立（`宫保鸡丁` ↔ left、`キセキ` ↔ right）。

#### 3.2.3 解析流程（按序执行）

1. **取文件名主干**：去扩展名（`os.path.splitext` 等价逻辑）。
2. **剥离序号前缀**：正则 `^\s*\d{1,3}\s*[-_.、]\s*`（覆盖 `02 - `、`11-`、`01.` 三种形态，实测 40.2%）。
3. **剥离杂质词**：`（抖音版）`、`[高品质]`、`(电影《…》片尾曲`、`_原声带`、`Remix` 等（规则表可后续扩展）。
   - 实测 `readable_name` 已自动剥离了部分杂质：`Wrap Me In Plastic（抖音版） - 沈小3.mp3` → `readable_name = "Wrap Me in Plastic (TIK TOK)"`。
   - ⚠️ 注意 `readable_name` 与文件名**可能完全不同**（上例中一个是英文名、一个是中文名），因此**两者都要作为 title 候选**去查索引。
4. **切分 artist/title**：按 ` - `、`-`、`_`、`–`、`—` 切分；**同时保留完整主干**作为一个候选（应对 `08你的微笑` 这类无分隔符）。
5. **双向试探**两个顺序，见 3.2.2。
6. **回退**：`readable_name` 直接作为 title 候选（它本身就是纯标题）。

#### 3.2.4 复用现有工具

- 复用 `utils.extractArtistsFromTitle` / `splitArtistsByDelimiters`（`ArtistParsingUtils` 已在用）——注意它们是为**从 title 中拆多个歌手**设计的（处理 `&`、`feat.`、`,` 等），与本节的「切分 artist/title」是**不同层次**的问题，需组合使用：先切分出 artist 段，再用 `splitArtistsByDelimiters` 拆多歌手。
- 实测中存在多歌手：`向云端 - 小霞&海洋Bo`。

---

## 4. 数据写入与合并策略（核心）

> 所有写入统一在 `PowerampBackupImporter.run()` 内、以**批量**方式执行；全程可中断、可上报进度。
>
> ⚠️ **用词修正（v1.2 / v1.4）**：四类落地目标分属不同存储——收藏 / 评分 / 统计走 Room，播放历史走 `PlaybackStatsRepository` 的原子文件。它们**不共享事务**，无法整体原子回滚。这里保证的是**幂等分步 + 可重跑**：
>
> | 步骤 | 幂等性来源 |
> |---|---|
> | 歌单 | `addSongsToPlaylist` 内部 `(existing.songIds + songIdsToAdd).distinct()`，重跑不产生重复 |
> | 播放历史 | `importEventsFromBackup` 按 `songId:startMillis:endMillis:durationMs` 去重 |
> | 收藏 / 评分 | `FavoritesDao.insertAll` 为 REPLACE 语义（v1.4 起评分与收藏同表，幂等性一致） |
> | **参与度** | ❌ **唯一非幂等**：`mergeEngagement` 是累加，重跑会翻倍。规避方案见 §9 待确认决策 **D6** |
>
> **v1.4 变更**：原「原始评分走 `poweramp_ratings.json` 文件」改为走 Room 的 `favorites.rating` 列，落地目标由 4 类存储减为 3 类。

### 4.1 合并模式（默认，推荐）
用户可在向导选择 **合并（推荐）/ 替换**。默认合并。

| 数据 | 合并逻辑 | 落地接口 |
|---|---|---|
| **播放列表** | 对每个 Poweramp 歌单：<br>• 若 PixelPlayer **无同名** → `createPlaylist(name, songIds)`，songIds **按 Poweramp 内顺序**；<br>• 若**已有同名** → 追加导入歌曲到现有歌单末尾（`addSongsToPlaylist`），**保持导入段内顺序 = Poweramp 顺序**，已有歌曲顺序不动；内部去重（已存在的歌不重复追加）；<br>• 不提供"创建副本"选项（默认合并） | `PlaylistPreferencesRepository.createPlaylist` / `addSongsToPlaylist` / `getPlaylistsOnce` |
| **播放历史** | 每首歌**最多合成 1 条事件**（`timestamp = played_at`，`durationMs = 0`）后追加导入；去重键 `songId:startMillis:endMillis:durationMs`，按时间排序。<br>⚠️ **语义澄清（实测 F3）**：Poweramp 只记录「最后播放时间」，**没有逐条播放事件流**，因此这不是「完整历史迁移」而是「最后播放时间快照」。UI 文案处理见 §9 决策 **D7** | `PlaybackStatsRepository.importEventsFromBackup(events, clearExisting=false)`（现成；注意该方法**默认 `clearExisting = true`**，合并必须显式传 `false`） |
| **参与度统计** | `songId` 冲突时**累加**：`playCount +=`、**`totalPlayDurationMs += 0`（不捏造时长，见 N2）**、`lastPlayedTimestamp = max(现有, 导入)` | 新增 `EngagementDao.mergeEngagement(...)`（见 4.3） |
| **收藏 + 评分（v1.4 合并为一类）** | 一次性 upsert 到 `favorites` 表：`rating = Poweramp rating`（NULL→0），`isFavorite = (rating ≥ 阈值)`，默认阈值 **4 星**。<br>⚠️ `FavoritesEntity.songId` 是 **`Long`**，而匹配结果（`Song.id` / `resolveSongId` 返回值）是 `String`，落库前需 `toLong()`<br>⚠️ 未达阈值的歌曲**也写入**（`isFavorite = 0, rating = N`），以保住 3 星 / 2 星数据 | `FavoritesDao.insertAll`（现成，REPLACE 语义）<br>+ 新增 `setRating`（见 §4.4） |

> 顺序保证：Poweramp `tracks` 行按列表内自然顺序读取（`_id` 递增即列表内顺序），构造 `songIds` 时保持该顺序；`addSongsToPlaylist` 追加到末尾。

#### 4.1.1 合成播放事件的构造（v1.3 实测校准）

`PlaybackStatsRepository.PlaybackEvent` 定义为：

```kotlin
data class PlaybackEvent(
    val songId: String,
    val timestamp: Long,
    val durationMs: Long,
    val startTimestamp: Long? = null,
    val endTimestamp: Long? = null
)
```

**导入侧的构造方式**（按 N2，`durationMs` 恒为 0）：

```kotlin
PlaybackEvent(
    songId = matchedSongId,      // String
    timestamp = playedAt,        // 毫秒，来自 Poweramp played_at
    durationMs = 0L,             // N2：Poweramp 无时长数据，不捏造
    startTimestamp = null,       // 交给 sanitizeEvent 自动补全
    endTimestamp = null
)
```

**为什么可以留空 start/end**——`sanitizeEvent()` 的行为已实测确认（`PlaybackStatsRepository.kt:553`）：

| 输入 | `sanitizeEvent` 归一化结果 |
|---|---|
| `durationMs = 0`，start/end 均为 `null` | `startTimestamp = endTimestamp = timestamp`，`durationMs = 0` |

即最终落库为 `start = end = played_at, durationMs = 0`，**自洽**，无需手工计算。

**去重键**（`PlaybackStatsRepository.kt:494`）随之确定为：

```
"${songId}:${played_at}:${played_at}:0"
```

**⚠️ 数据一致性约束（实测 F19）**：PixelPlayer 的 `engagement_stats` 是**从 `playback_history` 聚合而来**——实测 `.pxpl` 中 6 条 engagement 与 8 条 history 的 `playCount` / `totalPlayDurationMs` **逐条精确吻合**（差值 ≤ 20ms）。

因此：
- 播放历史与参与度统计**必须成对写入**，不可只导其一；
- 若参与度按 N2 累加 `playCount` 而历史写入 `durationMs = 0` 的事件，二者仍然自洽（因为 duration 累加量也是 0）；
- 但**若 D8 决定启用「按播放次数 × 本地时长估算」**，则必须**同步**合成 `durationMs = 估算值` 的事件，否则会出现「参与度有时长、历史无时长」的不一致。**这是不建议启用 D8 的技术理由之一。**

### 4.2 替换模式（可选）
- 播放历史 / 参与度统计：先清空再写入（复用 `importEventsFromBackup(clearExisting=true)`、`engagementDao.replaceAll`）。
- 歌单 / 收藏：替换模式对这两类**不建议**（破坏性大），界面禁用或明确警告。
- 替换模式默认关闭，仅在向导显式选择。

### 4.3 新增 DAO 方法（唯一必须改数据层的地方）
```kotlin
@Dao
interface EngagementDao {
    // 新增：合并式 upsert（幂等，供导入使用）
    @Query("""
        INSERT INTO song_engagements (song_id, play_count, total_play_duration_ms, last_played_timestamp)
        VALUES (:songId, :playCount, :durationMs, :lastPlayedAt)
        ON CONFLICT(song_id) DO UPDATE SET
            play_count = play_count + :playCount,
            total_play_duration_ms = total_play_duration_ms + :durationMs,
            last_played_timestamp = MAX(last_played_timestamp, :lastPlayedAt)
    """)
    suspend fun mergeEngagement(songId: String, playCount: Int, durationMs: Long, lastPlayedAt: Long)
}
```
- 收藏：`FavoritesDao.insertAll` 已具备 REPLACE 合并语义，无需改动。
- 播放历史：`PlaybackStatsRepository.importEventsFromBackup` 已具备合并，无需改动（但**默认 `clearExisting = true`**，合并时须显式传 `false`）。
- 歌单：复用 `PlaylistPreferencesRepository` 现有方法（`createPlaylist` / `addSongsToPlaylist` / `getPlaylistsOnce`），无需改动。
  - 已验证：`addSongsToPlaylist` 实现为 `(existing.songIds + songIdsToAdd).distinct()`，**天然满足**「已有顺序不动 + 追加段保序 + 去重」。
- **关于 `durationMs`**：按规则 N2，导入时恒传 `0`，即 `total_play_duration_ms += 0` —— **只累加播放次数与最后播放时间，不污染本地已有的收听时长**。若未来需要「估算收听时长」，可用 `played_times × 本地歌曲 duration`，但会与本地已有统计重复计算，**本期不做**（§9 D8）。

### 4.4 评分落地（v1.4 重写：改为写入原生五星字段）

- 用户确认：能存本地的都是喜欢的歌，二值收藏会丢信息，**具体评分更准确**。
- **v1.3 做法**（已废弃）：把原始 rating 落盘到 `filesDir/poweramp_ratings.json`，等原生评分上线后再回填。
- **v1.4 做法**：原生五星评分先行（见 `docs/five-star-rating-feature-plan.md` 的 M1–M2），导入时**直接写入 `favorites.rating`**，不再需要旁路文件。

**写入逻辑**：

```
对每首匹配成功的歌曲：
    rating     = Poweramp rating（NULL → 0，规则 N1）
    isFavorite = (rating >= 阈值)          // 默认 4
    若 rating > 0 或 isFavorite：
        favorites 表 upsert(songId.toLong(), isFavorite, timestamp = 导入时刻, rating)
```

**三态定义**（与 `five-star-rating-feature-plan.md` §2.3 一致）：

| 条件 | `isFavorite` | `rating` | 说明 |
|---|---|---|---|
| `rating >= 阈值` | `1` | `1..5` | 已收藏且已评分 |
| `0 < rating < 阈值` | `0` | `1..5` | **仅评分，未收藏**（v1.3 会丢失这部分） |
| `rating == 0` | `1` | `0` | 已收藏未评分（Poweramp 中评 0 星但被收藏的场景） |
| `rating == 0` 且未收藏 | 不写 | — | 跳过，不产生无意义行 |

**收益（相对 v1.3）**：

1. 删除 `poweramp_ratings.json` 旁路文件与 `RatingPreserver` 组件，落地存储由 4 类减为 3 类；
2. **3 星 98 首、2 星 2 首的评分不再丢失**（实测分布见 §9 F24）；
3. 评分立即可用于「按评分排序 / 筛选」，无需等待回填步骤；
4. 取消收藏时评分保留（决策点 **R1**）。

**依赖**：本方案依赖 `five-star-rating-feature-plan.md` 的 **M1 数据层**（`FavoritesEntity` 加 `rating` 列 + Migration 42→43 + DAO 软删除改造）。若 M1 未完成，导入功能应**暂不支持评分导入**（仅按阈值写收藏），而非退回 JSON 旁路方案——避免两套存储并存。

---

## 5. UI 流程（`PowerampImportFlow`）

### 5.1 步骤
1. **来源选择**（`ThirdPartyImportScreen`）：卡片列表 → Poweramp。
2. **选文件**：`OpenDocument`（`.poweramp-backup`）。
3. **解析预览**（**必看步骤**，用户已确认）：检测到 歌单数 / 歌曲数 / 评分分布 / 播放记录数；**匹配预览**显示预估可匹配歌曲数与匹配率。
   - ⚠️ **v1.3 新增（D11）**：预览页必须展示**导入规模冲击对比表**（现状 → 导入后，见 §9 F23 表）。目标机器实测为参与度 6 → 1682（**×280**）、收藏 1 → 710、歌单 0 → 29。数量级跳变必须让用户在执行前看到。
4. **选项配置**（`ImportOptionsDialog`）：
   - 合并模式开关（合并 / 替换）；
   - 评分阈值（3 / 4 / 5 星，默认 **4**）「≥阈值 → 收藏」；**切换阈值时实时显示将新增的收藏数**（目标备份实测 F10：4 星阈值在 **1676** 首去重歌曲中命中 **709** 首 = **42.3%**；5 星命中 538 / 32.1%，3 星命中 807 / 48.2%。数量偏大，须让用户在执行前有预期）；
   - 是否导入：播放列表 ☑ / 播放历史 ☑ / 参与度统计 ☑ / 收藏（评分映射）☑；
   - 提示：歌单默认合并（同名追加、保序）。
5. **执行**：进度条（分步骤：解析 → 匹配 → 写歌单 → 写历史 → 写统计 → 写收藏 → 写评分保留文件），可取消。
6. **结果报告**（`ImportResultDialog`）：成功歌单数、导入歌曲数、播放历史事件数、统计条目数、收藏数、未匹配歌曲数（可展开示例）、错误提示。

### 5.2 结果模型
```kotlin
data class ImportResult(
    val matchedSongs: Int, val unresolvedSongs: Int,
    val unresolvedExamples: List<String>,
    val playlistsCreated: Int, val playlistsMerged: Int,
    val historyEventsImported: Int, val engagementImported: Int,
    val favoritesImported: Int, val ratingsSaved: Int,
    val skippedEmptyPlaylists: Int
)
```

---

## 6. 代码文件清单

**新增**
| 文件 | 职责 |
|---|---|
| `data/importer/ImportSource.kt` | 来源接口 + 中间数据模型 |
| `data/importer/poweramp/PowerampBackupParser.kt` | zip/sqlite 解析 → `ImportSourceData` |
| `data/importer/poweramp/PowerampPathNormalizer.kt` | 路径卷映射/规范化 |
| `data/importer/SongMatcher.kt` | 路径/文件名/元数据三级匹配（复用 PlaylistsModuleHandler 的归一化与消歧） |
| `data/importer/PowerampBackupImporter.kt` | 编排：解析→匹配→写库（含评分 upsert）→进度/结果 |
| `presentation/screens/ThirdPartyImportScreen.kt` | 来源选择页 |
| `presentation/screens/import/PowerampImportFlow.kt`（含 Preview/Options/Result 对话框） | 导入向导 UI |
| `presentation/viewmodel/ImportViewModel.kt` | 状态机 + 调用 importer |
| `res/values/strings_import.xml` | 文案 |

**修改**
| 文件 | 改动 |
|---|---|
| `data/database/EngagementDao.kt` | 新增 `mergeEngagement`（见 4.3） |
| `data/database/MusicDao.kt` | 新增批量投影方法 `getAllLocalSongsForImport()`（路径匹配前置条件，见 §3.1） |
| `data/database/ImportSongProjection.kt`（新） | 上述查询的投影数据类（`id` / `filePath` / `title` / `artistName` / `albumName` / `duration`） |
| `presentation/screens/SettingsCategoryScreen.kt` | Backup & Restore 区域加「第三方导入」入口 |
| `data/stats/PlaybackStatsRepository.kt` | **v1.4**：去除 730 天时间裁剪，改为条数上限（见 §9 D13） |
| `presentation/navigation/…` | 注册新路由 |
| DI module（`data/…/AppModule.kt`） | 注入 importer/parser |
| `docs/AGENTS.md`（或 docs） | 更新新模块说明 |

---

## 7. 测试与验证

### 7.1 单元测试（`app/src/test`）
- **解析器**：用**真实样本**（`lists-export` 脱敏 fixture 或用户备份转换的只读样本）+ 构造样本，断言表读取、空列表跳过、损坏文件报错。
  - **schema 版本差异**：构造「无 `total_played_times` 列」的旧版 DB（等价 `Apr-15--2025`），断言不崩溃且 `playCount` 仍取自 `played_times`（F4）。
  - **NULL 评分**：`rating IS NULL` → 归 `0`（N1）。
  - **数据矛盾**：`played_at > 0 && played_times == 0` → `playCount = 1`（N3，目标备份实测 **424 行**）。
  - **去重确定性**：同一 `path` 多行时取 `playlist_id IS NULL` 的曲库行（N4，实测 **627 组**，冲突 0 组）。
  - **`export_type` 判定**：`export_type=3` 判曲库 / `=1` 判引用（N6，实测与 `playlist_id` 完全等价）；`export_type` 列缺失时回退到 `playlist_id`。
- **路径规范化**：`primary/…` → `/storage/emulated/0/…`；SD 卡卷（`9C33-6BBD/…`）处理；**本机无此卷时降级**；**无卷前缀裸文件名**（目标备份实测：`01 - 彩云追月.mp3`、`10 - 同桌的你 [风行版].mp3`）降级到第 2/3 级匹配。
- **文件名解析**（v1.3 按实测重写，覆盖 F11–F13 全部 7 类模式）：
  - **序号前缀剥离**：`02 - 煎熬` → `煎熬`；`11-Ave Maria` → `Ave Maria`；`01.Victory Remix` → `Victory Remix`（实测占 40.2%）。
  - **双向顺序试探**（F12）：`宫保鸡丁 - 陶喆` 应解析出 title=`宫保鸡丁`、artist=`陶喆`；`GReeeeN - キセキ` 应解析出 title=`キセキ`、artist=`GReeeeN`。**断言两者都能命中**，而非只支持一种顺序。
  - **分隔符覆盖**：` - `（含空格）、`-`（无空格）、`_`、`01.` 点号；以及无分隔符（`08你的微笑`）时以完整主干作候选。
  - **`readable_name` 与文件名并列为候选**（D10）：`Wrap Me In Plastic（抖音版） - 沈小3.mp3` + `readable_name="Wrap Me in Plastic (TIK TOK)"`，断言两个 title 候选都被尝试。
  - **杂质剥离**：`（抖音版）`、`[高品质]`、`(电影《宝莲灯(1999)》片尾曲`、`_原声带` 等。
  - **目录名加分项**（F14）：`primary/Music4Phone/周杰伦/十一月的萧邦/xxx.mp3` 应提取出 artistHint=`周杰伦`、albumHint=`十一月的萧邦` 参与消歧。
- **匹配策略**：路径精确命中 / 文件名兜底 / 元数据消歧（多候选 + duration 容差）/ 无法匹配；**云端歌曲（`source_type != 0`）不参与路径匹配**。
  - **预期命中率回归**：同一设备上，1676 首中第 1 级应命中 1674 首（99.9%），仅 2 首裸文件名走第 2/3 级。
- **合并逻辑**：`mergeEngagement` 累加与 `MAX(last_played)`；播放历史 `clearExisting=false` 去重；**同名歌单合并后顺序正确**（已有顺序不变、导入段保持 Poweramp 顺序、去重）。
- **时长不被污染**：导入后断言既有 `total_play_duration_ms` **保持不变**（N2）。
- **重跑幂等**：同一备份连续导入两次，断言歌单 / 历史 / 收藏不翻倍，且参与度按 D6 方案不翻倍。
- **合成事件契约**（v1.3，F20/F21）：
  - 断言合成事件经 `sanitizeEvent` 后满足 `startTimestamp == endTimestamp == played_at` 且 `durationMs == 0`；
  - 断言去重键为 `"${songId}:${played_at}:${played_at}:0"`；
  - 断言导入后 `engagement_stats` 的 `playCount` 与 `playback_history` 的聚合结果**保持一致**（F21：本地既有数据中二者逐条精确吻合，导入后不得破坏该不变量）。
- **`songId` 类型**（F19）：断言收藏落库时 `String → Long` 转换正确，且不丢失高位（实测 id 如 `962604`、`922458`）。

### 7.2 集成/手动验证
- 用用户提供的真实 `Sep-2--2026-12-14-32-PM.poweramp-backup`（`E:\downloads\`）在 Debug 包实际导入，核对（**v1.3 全部为实测值**）：
  - 解析层：2720 行 = 1674 曲库 + 1046 引用、去重 **1676**、**29 歌单（0 个空歌单）**、列表独有 **2** 首；
  - 匹配层：同设备下第 1 级路径匹配应达 **1674 / 1676 ≈ 99.9%**；
  - 写入层：历史 **+1628** 条合成事件、参与度 **6 → 1682**、收藏 **1 → 710**（4 星阈值）、歌单 **0 → 29**、`favorites.rating` 落 **809** 条（`rating > 0`）；
    - 其中 `isFavorite = 1` 的 709 条（≥4 星），`isFavorite = 0` 的 **100** 条（3 星 98 + 2 星 2）——**断言这 100 条未丢失**；
  - 格式边界：9 首 `.ape` 预期为 unresolved（D12）。
- 验证「已有数据 + 合并」：先人为制造本地历史/统计/歌单，再导入，确认累加而非覆盖、歌单顺序正确。
- 验证「替换」模式行为符合预期。
- lint：`gradlew.bat :app:lintDebug`；单测：`gradlew.bat :app:testDebugUnitTest`。

---

## 8. 实施顺序（里程碑）

| 里程碑 | 内容 | 验收 |
|---|---|---|
| **M0 前置依赖** | `five-star-rating-feature-plan.md` 的 **M1 数据层**（`favorites.rating` + Migration 42→43 + DAO 软删除） | 评分可写入且取消收藏时评分不丢（见 D14） |
| **M1 解析与匹配** | Parser + PathNormalizer + SongMatcher + 真实样本单测 | 能正确解析用户备份并给出匹配率 |
| **M2 写库与合并** | `mergeEngagement` + Importer 编排（歌单保序/历史/统计/**收藏+评分 upsert**）+ 合并单测 | 合并/替换两种模式落库正确、顺序正确、`favorites.rating` 809 条且 100 条未达阈值记录完整 |
| **M3 UI 向导** | 来源选择页 + 导入向导 + 必看预览 + 进度 + 结果报告 | 端到端可导入，体验完整 |
| **M4 集成收尾** | 导航/DI/文案、lint+单测、真实备份端到端验证、docs 更新 | 全部通过，可提交 |

---

## 9. 决策记录（已确认）

| # | 决策 | 结论 |
|---|---|---|
| 1 | 入口位置 | **唯一入口**：设置 → Backup & Restore 底部「第三方导入」 |
| 2 | 同名歌单策略 | **默认合并**（追加到现有歌单，保持 Poweramp 顺序，去重） |
| 3 | 评分阈值 | 默认 **4 星** → 收藏；同时**保留原始 rating** 落盘，为原生评分铺路 |
| 4 | 导入范围 | **全部去重歌曲**：曲库 + 列表独有；歌单按列表引用顺序导入（引用次数而非独立歌曲数）。目标备份 `Sep-2-2026`：2720 行 = 曲库 1674 + 引用 1046，去重 ≈1676 首，29 歌单
| 5 | 匹配率预览 | **导入前必看步骤** |
| — | 路径匹配性质 | 非原生策略；同设备走 **`songs`.`file_path`**（表名是 `songs`）库内 join，跨设备由文件名/元数据兜底；元数据直接从库读，不真实读文件 |

### v1.2 实测结论（2026-09-02，基于 `Dec-19--2025` 与 `Apr-15--2025` 两份真实备份）

| # | 结论 | 证据 |
|---|---|---|
| **F1** | `played_at` / `played_fully_at` **单位是毫秒**，与 PixelPlayer 的 `timestamp` 一致，**无需换算** | 实测 13 位：min 1680793671678 / max 1766075055093（→ 2025-12-18）；按秒解析直接溢出报错 |
| **F2** | `tracks` 表**没有任何播放时长列**；`played_times` / `total_played_times` 都是**计数** | 实测 schema 无 duration 类列 → `totalPlayDurationMs` 无源，规则 N2 |
| **F3** | Poweramp **只有「最后播放时间」，没有播放事件流**，历史导入只能是「每首最多 1 条合成事件」 | 实测 `tracks` 每 `path` 仅一行，`played_at` 为单值 |
| **F4** | **schema 随版本变化**：`total_played_times` 仅存在于新版（`Dec-2025` 有，`Apr-2025` 无） | 两份备份 schema 对比 → 解析器须 `PRAGMA table_info` 动态探测 |
| **F5** | 同一 `path` 的重复行**评分冲突为 0 组**（Poweramp 内部同步），去重只需确定性规则 | 实测 612 组重复 `path`，冲突 0 组 |
| **F6** | 列表独有歌曲的路径**无卷前缀（裸文件名）**，绝对路径匹配必然失败，只能走文件名 / 元数据 | 实测 3 首：`01 - 彩云追月.mp3` 等 |
| **F7** | SD 卡卷真实存在（非假设） | `Apr-15-2025` 路径前缀为 `9C33-6BBD/`（FAT32 卷序列号） |
| **F8** | 评分阈值 4 星在实测库中命中约 **42%**（686 / 1628 去重歌曲），预览页需显著提示该数量 | 实测 `rating >= 4` 的去重 path = 686 |

### v1.3 实测结论（2026-09-02，目标备份本体 + PixelPlayer 侧 .pxpl）

**Poweramp 侧（`Sep-2--2026-12-14-32-PM.poweramp-backup`）**

| # | 结论 | 证据 |
|---|---|---|
| **F9** | v1.1 全文计数**全部实测吻合**：2720 行 = 1674 曲库 + 1046 引用，去重 **1676**，29 歌单，**空歌单 0 个**，列表独有 **2** 首 | 目标备份 SQLite 直接查询 |
| **F10** | 计数校准（替换 v1.2 用 `Dec-2025` 推算的近似值）：`played_at>0 && played_times=0` = **424**（原 454）；`rating IS NULL` = **2**（原 3）；重复 `path` 组 = **627**（原 612），评分冲突仍为 **0** 组；`rating>=4` = **709 / 1676（42.3%）** | 目标备份实测 |
| **F11** | ⚠️ **`readable_name` 只含标题、不含歌手** | 仅 **2.6%**（44/1676）含 `" - "`，且均为古典曲目名；`readable_name` == 文件名主干的仅 **1.5%**（25/1676）。例：文件名 `宫保鸡丁 - 陶喆.flac` → `readable_name = "宫保鸡丁"` |
| **F12** | ⚠️ **文件名 artist/title 顺序以「歌名 - 歌手」为主且不固定** —— 推翻 v1.1「常含 `歌手 - 歌名`」的假设 | `宫保鸡丁 - 陶喆`、`The Last Waltz - Engelbert Hamperdink`、`下沙-游鸿明` 均为「歌名-歌手」；但 `GReeeeN - キセキ` 为「歌手-歌名」。**必须双向试探**（§3.2.2） |
| **F13** | 文件名分隔符多样：`` ` - ` `` 56.3%、`_` 20.7%、连字符无空格 18.8%、无分隔符 4.1%；**序号前缀占 40.2%**（674 首） | 7 类模式量化，见 §3.2.1 |
| **F14** | **目录名含 artist/album**，可作匹配加分项；但 **93.3%（1564 首）无子目录** | 112 首位于 `…/周杰伦/十一月的萧邦/` 等子目录 |
| **F15** | `export_type` 与 `playlist_id` 判定**完全等价**（`3`=曲库 1674 / `1`=引用 1046），是更语义化的判定列 | 实测分布对比 → 规则 N6 |
| **F16** | **`played_at` 毫秒单位铁证**：max = `1788322482259` → `2026-09-02 12:14:42.259`，**恰为备份导出时刻**（zip 内时间戳同为 12:14:42） | 目标备份实测；按秒解析会溢出 |
| **F17** | 目标备份**无 SD 卡卷**（100% `primary`），但卷前缀处理仍不可省略 | `Apr-15-2025` 的 `9C33-6BBD/` 证明换机/换卡后会复现 |
| **F18** | 扩展名 `.mp3` 1286 / `.flac` 356 / `.wav` 23 / **`.ape` 9** / `.m4a` 1 / `.aac` 1；`.ape` 预期为 unresolved | MediaStore 对 APE 索引支持不完整 |

**PixelPlayer 侧（`PixelPlayer_Backup_1788322314872.pxpl`）**

| # | 结论 | 证据 |
|---|---|---|
| **F19** | ⚠️ **`songId` 类型分裂得到数据侧确认**：`engagement_stats.json` / `playback_history.json` 的 `songId` 是 **`String`**（`"962604"`），而 `favorites.json` 的 `songId` 是 **`Int`**（`3421`） | 三份 JSON 直接解析；与代码一致（`Song.id: String` vs `SongEntity.id: Long`）→ 收藏落库必须 `String.toLong()` |
| **F20** | `PlaybackEvent` 契约：`endTimestamp - startTimestamp == durationMs` 且 `timestamp == endTimestamp` | 实测 8 条历史数据 **8/8 全部自洽**；`timestamp == endTimestamp` 为 `True` |
| **F21** | **`engagement_stats` 完全由 `playback_history` 聚合而来** | 6 条 engagement 与 history 聚合结果的 `playCount`、`totalPlayDurationMs` **逐条精确吻合**（差值 ≤ 20ms）→ 两者必须成对导入（§4.1.1） |
| **F22** | `.pxpl` = **4 字节 magic `PXPL` + ZIP**；代码已正确处理（非缺陷） | `BackupFormatDetector.PXPL_MAGIC` / `PXPL_MAGIC_SIZE = 4`，`BackupReader.skipFully(raw, 4)`。备份 `schemaVersion = 3` == `CURRENT_SCHEMA_VERSION` |
| **F23** | `SourceType.LOCAL = 0`，§3.1 的 `source_type = 0` 过滤条件正确 | `SongEntity.kt:18` `const val LOCAL = 0` |

#### 导入规模冲击预估（当前 PixelPlayer 近乎全新，实测对比）

| 数据 | 现状（.pxpl 实测） | 导入后（预估） | 倍数 |
|---|---|---|---|
| 参与度统计 | **6** 条 | ≤ **1682** 条 | **×280** |
| 播放历史 | **8** 条 | **+1628** 条（仅 `played_at>0` 的 2164 行去重后） | ×204 |
| 收藏 | **1** 条 | **+709** 条（4 星阈值） | ×710 |
| 歌单 | **0** 个 | **+29** 个 | — |

> ⚠️ **这是本次实测最需要正视的一点**：目标机器上 PixelPlayer 几乎是全新状态（`playlists: []`、收藏仅 1 条），而 Poweramp 侧有 1676 首歌的沉淀。导入后统计页、收藏页、歌单页会发生**数量级跳变**。
> 因此 §5 的「匹配率预览 + 影响预估」步骤**不可省略**，且必须在预览页以数字明确列出上表，让用户确认后再执行。

### v1.2 新增待确认决策

| # | 决策点 | 建议 | 不处理的后果 |
|---|---|---|---|
| **D6** | 参与度累加的**重跑幂等** | 建议：在 `filesDir` 维护 `poweramp_import_state.json`，记录备份指纹（文件名 + 大小 + 行数）+ 各 `songId` 的本次累加量；重跑同一备份时先按记录回滚增量再重导（或指纹一致则跳过参与度写入） | 「导入中途取消 → 重跑」会导致播放次数翻倍 |
| **D7** | 播放历史的**语义呈现** | 建议：UI 文案写「导入最后播放时间」而非「导入播放历史」，结果报告中说明「N 首歌曲的最后播放时间已导入（Poweramp 不记录完整播放历史）」 | 用户误以为迁移了完整播放历史 |
| **D8** | 是否估算收听时长 | 建议：**不做**（N2，恒 0）。若确需，可加开关「按 播放次数 × 本地时长 估算」，但须提示会与本地已有统计重复计算。<br>⚠️ **v1.3 补充技术理由（F21）**：`engagement_stats` 由 `playback_history` 聚合而来，启用估算后必须**同步**改写历史事件的 `durationMs`，否则两处数据会不一致 | 默认 0 时统计页「总收听时长」不会因导入而跳变，且与历史数据保持自洽 |

### v1.3 新增待确认决策

| # | 决策点 | 建议 | 不处理的后果 |
|---|---|---|---|
| **D9** | 文件名 artist/title **顺序不固定**（F12） | 建议：**双向试探**（§3.2.2 伪代码），两个候选都查索引；任一唯一命中即采纳，两者命中不同则用 album/duration 消歧，仍歧义则放弃 | 沿用 v1.1「歌手 - 歌名」假设会错配**大多数**曲目（实测以「歌名 - 歌手」为主） |
| **D10** | `readable_name` 与文件名**可能完全不同**（F11） | 建议：两者**都**作为 title 候选查询（如 `Wrap Me In Plastic（抖音版） - 沈小3.mp3` 对应 `readable_name = "Wrap Me in Plastic (TIK TOK)"`） | 只用其一会漏掉部分曲目的匹配机会 |
| **D11** | **导入规模冲击**（参与度 ×280）是否需要在 UI 上二次确认 | 建议：预览页**强制展示** §F23 的规模对比表，且「开始导入」按钮需用户显式点击；若参与度增幅 > 10× 则增加一句醒目提示 | 用户在不知情的情况下执行，会对统计页的剧变感到困惑甚至误判为 bug |
| **D12** | `.ape`（9 首）等 MediaStore 未索引格式 | 建议：**预期内 unresolved**，在结果报告中单独归类为「格式不受支持」而非「匹配失败」，避免误导用户去查找根本不存在的匹配问题 | 用户误以为是匹配算法缺陷，反复排查 |

### v1.4 实测结论（2026-09-03，评分分布与算法关联性）

| # | 结论 | 证据 |
|---|---|---|
| **F24** | ⚠️ **Poweramp 评分是「准二值」分布**：5 星 538（32.1%）、4 星 171（10.2%）、3 星 98（5.9%）、2 星 2（0.1%）、**1 星 0 首**、0 星 865（51.7%） | 目标备份 1674 首去重曲库。中间档（1–4 星）合计仅 16.2%，用户实际行为是「喜欢=5 星，否则不评」 |
| **F25** | ⚠️ **五星评分不会提升推荐区分度**（推翻原假设）：`ratingScore(rating/5)` 标准差 0.4643 **低于** 二值 `favoriteScore(≥4星)` 的 0.4941，比值 **×0.94** | 1674 首逐条计算。根因即 F24 的准二值分布 |
| **F26** | **评分是独立于播放行为的信号**（值得保留）：有评分歌曲平均播放 **5.00** 次，无评分 **2.00** 次；`Pearson(rating, playCount) = 0.2694`（弱相关） | 同上。弱相关说明评分携带了播放行为之外的信息 |
| **F27** | **`affinityScore` 与歌曲长度无关**：它用 `song_engagements.total_play_duration_ms`（**累计播放时长**）÷ `maxDuration`，全程不读 `Song.duration` | `DailyMixManager.kt:318-320`。`Song.duration` 在本文件中零引用 |
| **F28** | **730 天裁剪与推荐排序无关**：`DailyMixManager` 只读 `engagementDao.getAllEngagements()`（Room），不读 `playback_history.json`；Room 的 `song_engagements` 表**无任何时间裁剪** | 全文件检索确认；裁剪仅在 `PlaybackStatsRepository.recordPlayback()` 中 |
| **F29** | 收藏存在**两套存储**，权威源是 `favorites` 表；`songs.is_favorite` 列为**无业务调用方的死列** | 所有业务调用点（`MusicService:1122/2618`、`PlayerViewModel:1547/2262`、`MultiSelectionStateHolder:357`）均走 `musicRepository` → `favoritesDao` |

### v1.4 新增待确认决策

| # | 决策点 | 建议 | 不处理的后果 |
|---|---|---|---|
| **D13** | 播放历史 **730 天裁剪改为条数上限**（用户已决定去除时间限制） | 建议：`MAX_HISTORY_AGE_MS` 的时间裁剪替换为 `MAX_HISTORY_EVENTS = 20,000` 的**条数裁剪**（按 `endMillis` 降序保留最近 N 条），并把 `MAX_PLAYBACK_HISTORY_LIMIT` 从 5,000 提到 20,000 对齐。<br>⚠️ **注意（F28）**：此项**不影响推荐排序**，只影响统计页与备份导出的历史完整性 | 直接删除裁剪会导致 `playback_history.json` 无界增长（重度使用 5 年约 17.5 MB，每次 `recordPlay` 都要全量读写 `AtomicFile`） |
| **D14** | **五星评分与导入功能的排期关系** | 建议：**先做五星 M1 数据层**（`favorites.rating` + Migration 42→43 + DAO 软删除），再做导入。两者约 1–2 天工作量，但顺序颠倒会导致：导入先落地时只能用 JSON 旁路方案，后续再迁移会产生两套存储 | 若导入先行且采用 JSON 旁路，后续需额外一次「回填 + 清理」迁移，且期间评分数据不一致 |

### v1.4 已关闭的建议（用户决策）

| 原建议 | 用户决策 | 落地情况 |
|---|---|---|
| 建议 1：导入时跳过 `played_at < now − 730d` 的事件 | ❌ **不采纳**。改为**去除 730 天限制本身** | 已落为 **D13**（改为条数上限 20,000） |
| 建议 2：维持 `durationMs` 恒 0（N2） | ✅ 采纳，但**理由需修正**：原理由「与本地统计重复计算」不准确，正确理由是「`engagement_stats` 由 `playback_history` 聚合（F21），启用估算须同步改写历史事件，会膨胀到约 5,700 条且每次播放全量读写 JSON」。<br>另澄清：**不需要知道歌曲长度**（F27） | 规则 N2 保留；`affinityScore` 与歌曲长度的关系已写入 `recommendation-algorithm-design.md` §3.2 |
| 建议 3：过滤「双 0」歌曲（`playCount=0 且 playedAt=0`，54 首） | ❌ **不采纳，维持不过滤** | 规则未变。影响：这 54 首仅让 `baselineScore` 从 0.1 降为 0（净损失 0.1），不影响其他歌曲的相对排序 |

> 备注（用户观点）：能保存在本地的都是喜欢听的歌，二值收藏不如具体评分准确 → 原生 5 星评分留待本功能完成后独立评估（已通过 4.4 保留评分数据，届时可无损迁移）。
