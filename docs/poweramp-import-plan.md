# Poweramp 播放列表导入方案

> 目标：把用户在 Poweramp 里创建的播放列表，导入 PixelPlayer。
> 调研日期：2026-09-02。路线 A 已落地，路线 B 未实现（待决策）。

## 一、核心事实（先看清数据在哪）

Poweramp 的播放列表**不是独立文件**，而是存在 SQLite 数据库里。搞清楚这一点，方案才不跑偏。

| 存储位置 | 内容 |
|---|---|
| `data/data/com.maxmpz.audioplayer/databases/folders.db` | 内部播放列表（表 `playlists` + `folder_playlist_entries`） |
| 备份文件 `Android/data/com.maxmpz.audioplayer/last.poweramp-settings` | **zip 归档**，内含 `shared_prefs/`（XML 设置）+ `databases/folders.db` |
| `.m3u8` 物理文件 | 仅当用户在 Poweramp 里手动「导出播放列表」才存在，绝对路径文本 |

**歌曲路径不是单一字段**，而是 `folders.path` + `folder_files.name` 拼接。

`folder_files` 表字段（从 Poweramp 论坛 API 讨论核实）：

| 字段 | 含义 |
|---|---|
| `_id` | 歌曲 ID |
| `name` | 文件名（不含目录） |
| `title_tag` | 标题标签 |
| `artist_id` / `album_id` | 艺术家/专辑外键 |
| `duration` / `rating` | 时长 / 评分 |
| `folder_id` | 所属目录外键（→ `folders._id`） |

## 二、三条路线对比

| 路线 | 原理 | 开发量 | 用户体验 | 可靠性 | 结论 |
|---|---|---|---|---|---|
| **A：导出 m3u8** | 手动导出 → 现有 `M3uManager` 导入 | **几乎为零** | 需手动操作一次 | 高 | ✅ 已落地 |
| B：解析备份文件 | 解压 zip → 读 SQLite → 提取 | 中（新增 importer） | 全自动 | 中（表结构随版本变） | ⏳ 未做 |
| C：ContentProvider | 经 Poweramp API 读 | 中 | 需 Poweramp 授权 | 低（第三方受限） | ❌ 弃 |

## 三、路线 A — 导出 m3u8（已落地）

### 原理

PixelPlayer 已有 `M3uManager.parseM3u()`，做「精确路径匹配 → 文件名 fallback」两级匹配。Poweramp 导出的 m3u8 是绝对路径（SD 卡卷名，如 `/storage/XXXX-XXXX/Music/...`），文件名 fallback 正好兜住跨设备路径差异。

### 已落地改动（`LibraryScreen.kt`，2 处）

1. Picker 契约：`ActivityResultContracts.GetContent()` → `OpenDocument()`
   - 原因：`GetContent` 只接受单个 MIME，`OpenDocument` 支持多 MIME 数组。
2. MIME 类型：单值 `audio/x-mpegurl` → 数组
   - `arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/x-mpegURL", "application/vnd.apple.mpegurl")`
   - 原因：`.m3u` 的 MIME 是 `audio/x-mpegurl`，而 `.m3u8` 是 `application/vnd.apple.mpegurl`（Android MimeTypeMap 映射），单值会过滤掉 `.m3u8`。

`M3uManager.parseM3u()` 本身已含 `.m3u8` 去后缀取歌单名，无需改动。

### 用户操作路径

1. Poweramp：`设置 → 库 → 播放列表 → 导出 Poweramp 播放列表`（得到 `.m3u8` 文件到 `/Playlists/`）
2. PixelPlayer：`库 → 播放列表 → 导入 M3U`，选中该 `.m3u8`

## 四、路线 B — 解析备份文件（未实现，供后续参考）

### 流程

1. 让用户选 `.poweramp-settings` 文件 → `ZipInputStream` 读入 `databases/folders.db`
2. 落盘临时副本 → 用 Room 的 `SupportSQLiteDatabase` 或 `sqlite3` 直接打开
3. 查 4 张表 join 出播放列表（字段名随版本漂移，**实现时先 `PRAGMA table_info` dump schema 再适配**）：

```sql
SELECT p._id, p.playlist, d.path, f.name
FROM playlists p
JOIN folder_playlist_entries e ON e.playlist_id = p._id   -- 字段名需核实
JOIN folder_files f          ON f._id = e.folder_file_id
JOIN folders d               ON d._id = f.folder_id
ORDER BY p._id, e.sort_order;
```

4. 每条 `d.path + "/" + f.name` 得到绝对路径，喂给现有 `M3uManager` 的匹配逻辑（复用 `songsByFileName` fallback）
5. 生成 `Playlist(name, songIds, source = "LOCAL")` 写入本地库

### 风险与注意

- **表/字段名漂移**：`folder_playlist_entries` 的外键字段名（`playlist_id` vs `folder_playlist_id`、`folder_file_id`）不同版本可能不同，必须动态 dump schema。
- **zip 内文件路径**：旧版备份直接是 `folders.db`，新版可能带目录前缀，需遍历 zip entry。
- **SQLite 兼容**：`folders.db` 由 Poweramp 用系统 sqlite 创建，WAL 模式可能残留 `-wal`/`-shm` 文件，读备份里的主 db 文件即可。

## 五、决策与现状

- 路线 A 已实现并编译通过（`:app:compileDebugKotlin`，10s），**未 commit**。
- 路线 B 待用户决策是否投入；价值在「用户无需手动导出、一次导入全部歌单」。
