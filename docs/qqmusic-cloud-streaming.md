# QQ 音乐云端串流行为说明

> 记录「登录 QQ 音乐并同步后，曲库 / 播放列表是否混合线上曲目」的实测代码行为。
> 更新：2026-09-07 ｜ 代码基线：本仓库 master（MusicDao / SongEntity / QqMusicRepository）

## TL;DR（结论）

登录并同步 QQ 音乐后：

1. **曲库会混合**。线上歌曲被镜像进统一曲库 `songs` 表（`source_type = 4`），与本地歌曲同表共存，UI 默认「全部」模式（本地 + 云端一起显示）。
2. 曲库视图提供 **全部 / 离线 / 在线** 三档过滤（`StorageFilter`），可随时切回「只看本地」。
3. **播放列表默认不自动混合**：QQ 歌单以独立 App 播放列表形式同步（id 前缀 `qqmusic_playlist:`），内容只含线上歌曲。本地歌单不会自动混入 QQ 曲目；反过来，QQ 歌单内容由同步全量替换。只有用户**手动**把另一来源歌曲加进某个歌单时，该歌单才会混合。
4. 登出后 QQ 音乐相关数据（原始表 + 主库镜像 + 同步歌单）会被**全部清除**。

---

## 1. 数据模型：两套存储

### 1.1 原始层（独立表，QQ 音乐专用）

| 表 | 实体 | 作用 |
|---|---|---|
| `qqmusic_playlists` | `QqMusicPlaylistEntity` | 同步下来的用户歌单元数据（封面、歌曲数、最后同步时间） |
| `qqmusic_songs` | `QqMusicSongEntity` | 按歌单组织的歌曲明细（`playlist_id` + `song_mid` 复合主键） |

访问入口 `data/database/QqMusicDao.kt`，歌曲以 `id = "{playlistId}_{mid}"` 存储。

### 1.2 统一层（主曲库）

`QqMusicRepository.syncUnifiedLibrarySongsFromQqMusic()`（`QqMusicRepository.kt:626`）把 `qqmusic_songs` **全量**转换为 `SongEntity` 写入统一 `songs` 表（连同 `albums` / `artists` / 关联表），关键字段：

- `source_type = SourceType.QQMUSIC`（= 4，`SongEntity.kt:22`）
- `id` = **负数**：`-(6_000_000_000_000 + mid.hashCode)`，与本地 MediaStore 正 `_id` 永不冲突（`QqMusicRepository.kt:53,734`）
- `content_uri_string = "qqmusic://{songMid}"`
- `parent_directory_path = "/Cloud/QQMusic"`（虚拟目录，`QqMusicRepository.kt:56`）
- `genre = "QQ Music"`（`QqMusicRepository.kt:57`）
- 专辑 / 艺人 id 同理取负偏移（`7e12` / `8e12`），不存在于本地则新建

增量策略：与已镜像 id 集合比对，只删除「远端已消失」的歌曲，其余 REPLACE/去重（`incrementalSyncMusicData`）。

---

## 2. 登录后发生什么

**登录本身只保存凭证（加密 cookie），不会自动拉取任何内容。**

内容同步需在 QQ 音乐面板（dashboard）里手动触发：

- `syncAll(ALL / CREATED / COLLECTED)` — 批量同步
- `syncPlaylist(id)` — 同步单个歌单

调用链（`QqMusicRepository.kt:529`）：

```
syncAllPlaylistsAndSongs(syncType)
├─ syncUserPlaylists()              拉歌单列表（100/页翻页），全量替换，删除已失效歌单
│    └─ 若删除过歌单 → syncUnifiedLibrarySongsFromQqMusic()（清理主库镜像）
├─ 对每个歌单 → syncPlaylistSongs() 拉歌曲明细（1000/页翻页，分页上限 200 页）
│    ├─ dao.deleteSongsByPlaylist() 全量重写该歌单原始歌曲
│    ├─ updateAppPlaylistForQqMusicPlaylist() 更新 App 侧播放列表
│    └─ syncUnifiedLibrarySongsFromQqMusic()  同步后重建主库镜像
└─ syncUnifiedLibrarySongsFromQqMusic()（兜底一次）
```

API 字段名兼容新旧两代（`songmid|mid`、`songname|title`、Base64 编码兜底解码），时长 `interval` 秒转毫秒。

---

## 3. 曲库混合与过滤开关

所有主要列表查询（歌曲 / 专辑 / 艺人 / 收藏 / 文件夹 / 年份桶等）都以统一 `songs` 表为源，并支持 `filterMode`：

| filterMode | 含义 | SQL 条件（`MusicDao.kt:1642` 起） |
|---|---|---|
| 0 | 全部（混合） | 不过滤 `source_type` |
| 1 | 仅本地 | `source_type = 0` |
| 2 | 仅云端 | `source_type != 0` |

UI 层映射为 `StorageFilter`（`data/model/StorageFilter.kt`），**默认 `ALL`**（`LibraryScreen.kt:393`、`LibraryMediaTabs.kt:95`）：

- `ALL(0)` → filterMode 0
- `OFFLINE(1)` → filterMode 1（只看本地）
- `ONLINE(2)` → filterMode 2（只看云端）

> 注意：`ONLINE / source_type != 0` 的语义覆盖**所有**云端源（Telegram/网易云/Google Drive/QQ 音乐/Navidrome/Jellyfin），不限于 QQ 音乐。各云端账号登录越多，混合面越大。

因此「混合」不是 QQ 音乐登录独有的行为，而是统一曲库 + 云端源的通用设计；QQ 音乐只是其中一个 source。

---

## 4. 播放列表行为

- QQ 歌单同步为 App 播放列表，id = `"qqmusic_playlist:{qqPlaylistId}"`（`QqMusicRepository.kt:58,751`），内容条目映射到主库负 id 歌曲。
- **同步即全量替换**该歌单内容，不会与本地歌曲合并。
- App 播放列表存储本身是统一的：若用户手动往任一播放列表（含 QQ 同步歌单）添加本地或其他来源歌曲，该歌单会出现混合条目——这是用户主动操作，非登录副作用。
- 删除 QQ 歌单 / 登出时，同步歌单连同其镜像一并删除（`deleteAppPlaylistForQqMusicPlaylist`）。

---

## 5. 播放机制

线上歌曲不落盘（`filePath` 为空），播放时实时换流：

1. `resolveSongUrl(songMid)` 先取默认 M4A `C400{media_mid}.m4a`，解析出 `media_mid`
2. 尝试升级 MP3 320k `M500{media_mid}.mp3`，失败回落 M4A（`QqMusicRepository.kt:193-222`）
3. purl 带 `vkey` 签名，最终 URL 形如 `https://ws.stream.qqmusic.qq.com/...`，交给播放引擎流式播放

请求经**全局限速**（1.1s/请求互斥锁）+ 同 songMid 并发去重（1.5s 冷却），避免触发风控。

**前提**：需要登录态与网络。掉线、cookie 过期后云端歌曲无法播放（本地歌曲不受影响）。

---

## 6. 登出清理

`logout()`（`QqMusicRepository.kt:253`）：

```
1. api.logout() + 清空加密 prefs
2. 遍历 qqmusic_playlists：删该歌单的 qqmusic_songs → 删歌单 → 删 App 侧同步播放列表
3. musicDao.clearAllQqMusicSongs()   // 清空主库 source_type=4 的镜像歌曲
```

登出后曲库恢复为纯本地视图，无残留。

---

## 7. 排查 / 维护备忘

- 想看当前混合了多少云端歌曲：`MusicDao` 的云端计数查询（`source_type != 0` 计数，`MusicDao.kt:541,568`）。
- 想确认某首歌来自云端哪个源：看 `content_uri_string` scheme（`qqmusic://` 等，`SongEntity.kt:27`）。
- 改动实体 / 加新云端源时注意：`SourceType` 常量、`SourceType.fromContentUri()`、以及各处 `source_type = 0` / `!= 0` 过滤 SQL 需同步评估。
- QQ 音乐 ID 偏移量在 `QqMusicRepository` companion 中集中定义（`6e12/7e12/8e12`），修改需保证与历史数据无碰撞。
