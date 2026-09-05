# 年份智能播放列表（Years）功能方案

> 版本：v1.1（2026-09-05，决策已锁定，待最终确认后开工）
> 目标：在音乐库新增「年份」入口，对标 Poweramp 的 Years：年份列表 → 某年的歌曲列表（智能播放列表），年内支持多属性排序与一键反向。
> 范围：仅 `:app` 模块手机端；不含 `:wear`、Android Auto 浏览树。
> 参照：Poweramp 反编译资源 `D:\3rd-party-projects\Poweramp-decompiled`（`res/values/strings.xml` 中 `years / by_year / all_year_songs` 确认其为与 Genres/Artists 同级的库分类）。Poweramp 业务代码混淆严重，只对标交互，不抄实现。

---

## 0. 结论先行

| 问题 | 结论 |
|---|---|
| 入口放哪？ | 音乐库新增 Tab「年份（YEARS）」，**默认位置：专辑（ALBUMS）之后**，即默认顺序 SONGS → ALBUMS → **YEARS** → ARTIST → PLAYLISTS → FOLDERS → LIKED |
| 两级结构？ | L1 年份列表是库 Tab 内页面；L2 走**独立导航页** `YearDetailScreen`（仿 GenreDetail/AlbumDetail），不走 FOLDERS 式 Tab 内下钻 |
| 年份列表取数？ | Room 聚合 `GROUP BY year` 的响应式 `Flow<List<YearBucket>>`（仿 `getUniqueGenres`），不分页 |
| 年内歌曲分页吗？ | **不分页**，响应式 `Flow<List<Song>>` + SQL 层 `ORDER BY`（与 GenreDetail/AlbumDetail 一致，播放全部直接拿全量） |
| 排序在哪做？ | 全部下推 SQL；评分/播放次数/最后播放需 `LEFT JOIN favorites / song_engagements` |
| 一键反向？ | 复用 `SortOption`（method+direction 成对枚举）+ `flipDirection()` + `LibrarySortBottomSheet` 翻转卡，零新组件 |
| 排序持久化？ | 新增两个 DataStore key：年份列表排序、年份详情排序（仿 `song_sort_option`） |
| 老用户 Tab 兼容？ | 把 `PlayerViewModel.libraryTabsFlow` 改为「存储顺序 + 缺失默认项按默认位置补齐」，老用户升级自动出现 YEARS，以后新 Tab 同样自动生效 |
| Room 迁移？ | **不需要**。只读新查询 + LEFT JOIN，不新增/不改表与列 |
| 它是真正的歌单吗？ | **不是落库歌单**。不向 `playlists/playlist_songs` 写任何行，是查询时动态计算的智能分类（详见 §1.5） |
| 排序能导出备份吗？ | **能，且零额外开发**：应用内备份泛化导出整个 DataStore，新排序 key 与 Tab 顺序自动随「全局设置」模块导出/恢复；系统 Google 云备份按项目既定安全策略排除整个 DataStore（详见 §3.8） |

### 已锁定决策（review 结论）

1. **未知年份**：`year=0` 的歌显示为「未知年份」桶，**无论升降序都固定钉在列表末尾**（实现：已知年份走 SQL，未知桶由 Repository 追加，仿 Unknown Genre）。
2. **Tab 位置**：专辑之后；Tab 顺序支持拖拽——现有 `ReorderTabsSheet` 已用拖拽手柄（`sh.calvin.reorderable`，持久化到 DataStore），本次**额外加「整行长按拖动」**（`longPressDraggableHandle`，对全部 Tab 统一生效），保留手柄作为视觉 affordance。
3. **年份行封面**：年份数字/日历图标 tile，不做封面拼贴、不发额外查询。
4. **年内排序项**：补齐 9 个方法，全部支持正/反序；**默认排序 = 播放次数（多→少）**，即 `song_engagements.play_count DESC`。
5. **年份列表排序**：仅保留年份新→旧（默认倒序）/旧→新（正序）两种，用同一个排序面板的翻转卡切换；不做按歌曲数排序。

---

## 1. 现状（代码实测）

### 1.1 音乐库 Tab 机制

- 存在**两个** `LibraryTabId` 枚举，新增 Tab 必须同步改：
  - `data/model/LibraryTabId.kt`：Pager/数据层用，带 `storageKey / titleRes / defaultSort`，`String.toLibraryTabIdOrNull()`。
  - `presentation/library/LibraryTabId.kt`：排序面板与「编辑标签」用，带 `sortOptions`；其 `decodeLibraryTabOrder()` 已实现「存储顺序优先 + 默认项补齐」。
- Tab 顺序持久化：`UserPreferencesRepository.LIBRARY_TABS_ORDER`（JSON 字符串数组）。`PlayerViewModel.libraryTabsFlow`（约 `:1063`）解码后直接使用、不合并缺失项，默认/异常回退列表在该函数硬编码 3 次——本次抽成常量并改为合并逻辑。
- Tab 渲染：`LibraryScreen.kt` HorizontalPager（约 `:1500` `renderTabContent` 按 `toLibraryTabIdOrNull()` 分发）；排序面板分发在 `:1225`、`:1258` 两处 `when(tab)`。枚举穷举 `when` 会在编译期报出所有漏改点。
- Tab 懒加载：`LibraryTabsStateHolder.onLibraryTabSelected()` 只对 SONGS/ALBUMS/ARTISTS/FOLDERS 有旧式一次性 `loadXxx()`；YEARS 走 Flow 收集（同 PLAYLISTS/LIKED），落 `else -> Unit`。
- 拖拽排序：`ReorderTabsSheet.kt` 用 `ReorderableItem + Modifier.draggableHandle()`（手柄），`onReorder` 落 `saveLibraryTabsOrder`；改长按整行拖动只需将 handle 修饰换/补为 `longPressDraggableHandle()` 并挂到 Row 上。

### 1.2 详情页范式（L2 直接照抄的模板）

- 导航：`Screen.kt` 路由对象 + `AppNavigation.kt:336` 注册（navArgument），ScreenWrapper 包裹后挂详情屏。
- `GenreDetailViewModel`：`loadGenre(genreId)` → `musicRepository.getMusicByGenre(name).first()` 取全量；`GenreDetailScreen.kt`（1016 行）含 Expressive 顶栏、播放/随机头、`EnhancedSongListItem`、长按多选（`MultiSelectionStateHolder`）、`SongInfoBottomSheet`。
- 播放队列：`PlayerViewModel.playSongs(songsToPlay, startSong, queueName)` / `shuffleSongs(...)`；`AlbumDetailScreen` 用 `showAndPlaySong(song, songs)` 把全量列表作为队列。

### 1.3 排序机制（直接复用）

- `data/model/SortOption.kt`：sealed class，每项 = `methodKey` + `direction`（Asc/Desc）成对出现；`flipDirection()` 一键反向；新枚举必须进 companion 的 `ALL`，否则 `optionByMethodAndDirection` 找不到对侧方向。
- `LibrarySortBottomSheet.kt`：上半张方向翻转卡（`canFlipDirection` 时显示，点击即反向），下半张方法列表。
- 持久化范式：`UserPreferencesRepository` 的 `songSortOptionFlow / saveSongSortOption`（存 storageKey 字符串）；`LibraryStateHolder` 用 `combine(排序Flow, effectiveStorageFilter).flatMapLatest { ... }` 驱动数据。

### 1.4 年份 / 评分 / 播放数据基础（已全部存在，无需建表）

| 需求属性 | 数据来源（实测列名） | 备注 |
|---|---|---|
| 发布年份 | `songs.year`（INTEGER NOT NULL DEFAULT 0） | MediaStore YEAR + 标签解析双路写入；`AudioMetadataReader` 两处 `take(4)`，只到年 |
| 播放次数（默认排序） | `song_engagements.play_count`（INTEGER） | 每次播放由 `recordPlay` 原子 +1 |
| 最后播放 | `song_engagements.last_played_timestamp`（Long） | 未播放为 0/无行 |
| 最后加入 | `songs.date_added`（Long 毫秒） | 扫描入库时间 |
| 评分 | `favorites.rating`（INTEGER，0=未评，1..5 星） | 主键列名驼峰 **`favorites.songId`**；允许 `isFavorite=0 且 rating>0` 的纯评分行 |
| 本地/云过滤 | `songs.file_path IS [NOT] NULL` | 与 `getSongsSongsPaginated` 同口径（StorageFilter） |

- 注意两表主键列名不一致：`favorites.songId`（驼峰）vs `song_engagements.song_id`（蛇形）。
- 现有 MusicDao 只有 Liked 的 `INNER JOIN favorites ... AND isFavorite=1`；LEFT JOIN 两表做排序是新 SQL，但两表均以 song 为主键一对一，**无行数扇出**。
- 列表查询统一用 `SONG_LIST_PROJECTION`（不含 lyrics，规避 CursorWindow 2MB 上限），行→Song 有现成 mapper。
- 流派聚合范式：`MusicRepositoryImpl.getGenres()`（`:892`）= DAO `getUniqueGenres()` Flow + `hasUnknownGenre()` 补「未知」桶 + allowed/blocked 目录过滤。

### 1.5 关键概念：年份是「智能分类」，不是落库歌单

| 对比项 | 普通自建/AI/云歌单 | 年份（本功能，同专辑/流派/文件夹） |
|---|---|---|
| 持久化 | `playlists` 一行 + `playlist_songs` 多行成员，显式存储 `sort_order` | **零写入**，查询时 `WHERE year=:year` 动态计算 |
| 成员变化 | 需用户显式加/删 | 改年份标签/扫描/删歌后自动迁移、自动出现/消失 |
| 顺序 | 可手动排（持久化） | 只能按属性排序（本功能的排序面板），不持久化手动曲序 |
| 点播放 | 取成员表 | 取查询结果快照塞进 Media3 内存队列，队列名=年份，退出即释放 |
| 想固化 | — | 用现有「多选 → 加入播放列表」，那时才真正写 `playlist_songs` |

结论：通用歌单是「存出来的」，年份是「查出来的」，二者共用歌曲表与播放队列，只是新界面组织。

---

## 2. 交互设计

### L1「年份」Tab（YearsTabContent）

- 每行 = 一个年份智能播放列表：
  - 左侧 tile：圆角方块，内显年份数字（未知年份显日历图标 + 文案）；
  - 主标题：`2019`（未知年份显「未知年份」），副标题 `N 首歌曲`（复用 `utils.formatSongCount`）；
  - 右尾 ChevronRight。
- 排序：仅年份倒序（新→旧，默认）/正序（旧→新），翻转卡一键切换；**未知年份桶永远在最后**。
- 点击：`navController.navigate(Screen.YearDetail(year))`；长按不进多选（桶不可编辑，同流派列表）。
- 空态：曲库为空时显示空态组件；全部歌曲无年份时只剩「未知年份」桶。
- 快速滚动 glyph 取年份字符串。

### L2 年份详情页（YearDetailScreen）

- 顶栏：返回 + 年份标题（未知年份显文案）+ 排序按钮；头部：歌曲总数/总时长、「播放全部」「随机播放」（仿 GenreDetail 头部）。
- 列表：`EnhancedSongListItem`；单击 `showAndPlaySong(song, allSongs)`（队列名=年份字符串），长按进多选（收藏/加歌单/入队等动作零新增）。
- 排序面板（`LibrarySortBottomSheet`，方法组 `SortOption.YEAR_SONGS`，9 个方法，默认播放次数多→少）：
  1. 播放次数（多→少 / 少→多，**默认**）
  2. 发布时间（专辑+碟号+轨号 tie-break，正/反）
  3. 标题 A-Z / Z-A
  4. 艺术家 A-Z / Z-A
  5. 专辑 A-Z / Z-A
  6. 最后加入（新→旧 / 旧→新）
  7. 最后播放（近→远 / 远→近）
  8. 评分（高→低 / 低→高）
  9. 时长（长→短 / 短→长）
- 响应式：播放/评分/收藏变化时 Room Flow 自动重发，列表与排序即时更新。

### 与 Poweramp 的差异

不做 `by_year_album`（年→专辑→歌三级），v1 只做「年→歌」两级。

---

## 3. 技术设计

### 3.1 数据层

**新增 model**：`data/model/YearBucket.kt`

```kotlin
@Immutable
data class YearBucket(
    val year: Int,          // 0 = 未知年份桶（固定排末尾）
    val songCount: Int
)
```

**MusicDao 新增查询**（SQL 草案；实现时对齐 StorageFilter / 目录过滤占位；Room 不支持参数化 ASC/DESC，沿用本仓库「成对 sortOrder 字符串 / 两份 CASE」既有做法）：

```sql
-- (a) 已知年份聚合桶（响应式；不含 year=0）
SELECT year, COUNT(*) AS song_count
FROM songs
WHERE year > 0
  AND (:localOnly = 0 OR file_path IS NOT NULL)
  AND (:cloudOnly = 0 OR file_path IS NULL)
  /* allowedParentDirs 目录过滤同 getUniqueGenres */
GROUP BY year
ORDER BY year [DESC|ASC]

-- (b) 未知年份计数（仿 hasUnknownGenre；Repository 把结果追加为末尾桶）
SELECT COUNT(*) FROM songs WHERE year = 0 [AND 同样过滤]

-- (c) 年内歌曲（响应式全量，SONG_LIST_PROJECTION + 两个 LEFT JOIN）
SELECT <SONG_LIST_PROJECTION>,
       COALESCE(eng.play_count, 0)          AS _play_count,
       COALESCE(eng.last_played_timestamp,0) AS _last_played,
       COALESCE(fav.rating, 0)              AS _rating
FROM songs
LEFT JOIN favorites fav        ON songs.id = fav.songId     -- 驼峰列名
LEFT JOIN song_engagements eng ON songs.id = eng.song_id    -- 蛇形列名
WHERE songs.year = :year
  AND (:localOnly = 0 OR songs.file_path IS NOT NULL)
  AND (:cloudOnly = 0 OR songs.file_path IS NULL)
ORDER BY CASE :sortOrder
    WHEN 'year_song_play_count'  THEN _play_count
    WHEN 'year_song_release'     THEN songs.album_name, songs.disc_number, songs.track_number
    WHEN 'year_song_title'       THEN songs.title COLLATE NOCASE
    WHEN 'year_song_artist'      THEN songs.artist_name COLLATE NOCASE
    WHEN 'year_song_album'       THEN songs.album_name COLLATE NOCASE
    WHEN 'year_song_date_added'  THEN songs.date_added
    WHEN 'year_song_last_played' THEN _last_played
    WHEN 'year_song_rating'      THEN _rating
    WHEN 'year_song_duration'    THEN songs.duration
  END [ASC|DESC],
  songs.title COLLATE NOCASE   -- 稳定 tie-break
```

`_play_count/_last_played/_rating` 仅用于排序，不进 `Song` model；未知年份详情查询条件为 `songs.year = 0`。

**Repository**（`MusicRepository` 接口 + `MusicRepositoryImpl`）：
- `fun getYearBuckets(sortOption: SortOption, storageFilter: StorageFilter): Flow<List<YearBucket>>`：已知桶 SQL + (b) 非零时把 `YearBucket(0, count)` 追加到**列表末尾**（正逆序都在末尾）。
- `fun getSongsByYear(year: Int, sortOption: SortOption, storageFilter: StorageFilter): Flow<List<Song>>`：行 mapper 复用现有 SongListRow→Song。

### 3.2 SortOption 扩展（`data/model/SortOption.kt`）

- **年份桶组 `YEAR_BUCKETS`（2 项）**：`YearBucketNewest`（year DESC，默认）/ `YearBucketOldest`（year ASC），methodKey `year_bucket_year`。
- **年内歌曲组 `YEAR_SONGS`（18 个对象 = 9 方法 × 2 方向）**：
  - `YearSongPlayCount(/Asc)`（methodKey `year_song_play_count`，**Desc 为全局默认**）
  - `YearSongRelease(/Asc)`、`YearSongTitleAZ/ZA`、`YearSongArtist(/Desc)`、`YearSongAlbum(/Desc)`、`YearSongDateAdded(/Asc)`、`YearSongLastPlayed(/Asc)`、`YearSongRatingHigh/Low`、`YearSongDuration(/Asc)`
- 两组都并入 companion `ALL`（保证翻转映射完整）。

### 3.3 ViewModel / StateHolder / Preferences

- `LibraryStateHolder`：`yearBucketsFlow = combine(_currentYearBucketSort, effectiveStorageFilter).flatMapLatest { repo.getYearBuckets(...) }`；`LibraryViewModel` 暴露。
- 新增 `presentation/viewmodel/YearDetailViewModel.kt`（`@HiltViewModel`，仿 GenreDetailViewModel）：
  - `loadYear(year)`；`uiState`（year、songs、isLoading、totalDuration）；
  - 排序来自 `yearDetailSortOptionFlow`，`setSortOption()` 同步落 DataStore；
  - `combine(sortFlow, storageFilter).flatMapLatest { repo.getSongsByYear(...) }`。
- `UserPreferencesRepository`：新增 `YEAR_BUCKET_SORT_OPTION`（默认 YearBucketNewest）、`YEAR_DETAIL_SORT_OPTION`（默认 YearSongPlayCount）两个 key 及 flow/save，照抄 `songs_sort_option`（`:107/:775/:793`）三小件范式，两套合计约 15 行；不碰 Room、无迁移，key 缺失即走默认值。
- **持久化语义**：v1 为「全局一份」——年份列表排序全局共享，年份详情排序对所有年份共享（进任意年份都沿用同一偏好，与专辑/流派详情一致）。排序 Flow 本就是驱动 SQL 的管道，持久化只替换初值来源 + 选择时调一次 setter。未来若要「每个年份各自记住排序」，把 year 编入 key 即可，v1 不做。

### 3.4 UI 层

- 新增 `presentation/screens/YearDetailScreen.kt`：GenreDetailScreen 骨架裁剪为扁平歌曲列表；排序面板用 `LibrarySortBottomSheet(options = SortOption.YEAR_SONGS)`。
- 新增 `presentation/library/YearsTabContent.kt`（或并入 LibraryScreen）：年份桶列表 + 数字 tile。
- `LibraryScreen.kt`：`renderTabContent` 加 YEARS 分支；`:1225/:1258` 两处排序 `when` 加 YEARS 分支（2 项 + 翻转卡）；其余枚举穷举点由编译器提示补齐。
- 两个 `LibraryTabId` 枚举同步加项（storageKey/stableKey 均为 `"YEARS"`，永不更改）：data 侧 `defaultSort = YearBucketNewest`；presentation 侧 `sortOptions = SortOption.YEAR_BUCKETS`。
- `ReorderTabsSheet.kt`：在现有拖拽手柄之外，给整行 `Row` 增加 `Modifier.longPressDraggableHandle()`（长按拖动，全部 Tab 统一生效），手柄保留。

### 3.5 导航

```kotlin
// Screen.kt
data object YearDetail : Screen("year_detail/{year}") {
    const val ARG_YEAR = "year"
    operator fun invoke(year: Int) = "year_detail/$year"
}
// AppNavigation.kt：composable(YearDetail.route, listOf(navArgument("year"){ type = NavType.IntType }))
//   -> ScreenWrapper { YearDetailScreen(navController, year, playerViewModel) }
```

### 3.6 Tab 顺序兼容

`PlayerViewModel.libraryTabsFlow` 改为合并语义（同 `decodeLibraryTabOrder`），默认常量：

```kotlin
val DEFAULT_TAB_ORDER = listOf("SONGS","ALBUMS","YEARS","ARTIST","PLAYLISTS","FOLDERS","LIKED")
// 存储顺序在前；缺失项（如 YEARS）按默认相对位置插入，而非简单追加到末尾
```

> 注：因 YEARS 默认位于 ALBUMS 之后而非末尾，合并时按 DEFAULT_TAB_ORDER 的相对位置插入，保证老用户升级后 YEARS 出现在专辑后面；`migrateTabOrder()` 保留不动。

### 3.7 字符串与图标

- `res/values/strings_library.xml` 与 `res/values-zh-rCN/strings_library.xml` 至少新增（其余语种自动回退英文）：
  - `library_tab_years` = Years / 年份；`unknown_year` = Unknown Year / 未知年份
  - `years_empty_title / years_empty_sub`
  - 年份桶排序：`sort_method_year`、`sort_display_year_newest/oldest`
  - 年内排序方法/展示：`sort_method_play_count`（播放次数，新）、`sort_display_play_count_most/fewest`；`sort_method_last_played`、`sort_display_last_played_recent/oldest`；`sort_method_rating`、`sort_display_rating_high/low`；release/title/artist/album/date_added/duration 复用现有文案
- 图标：复用 `drawable/rounded_calendar_view_week_24.xml`（不引依赖）。
- 无 R8 keep 变更（无新反射/序列化类，排序只存 storageKey 字符串）。

### 3.8 备份与恢复（零额外开发，已实测确认）

- **应用内「备份与恢复」自动覆盖**：`GlobalSettingsModuleHandler.export()` 调 `UserPreferencesRepository.exportPreferencesForBackup()`（`:1312`），该方法**泛化遍历整个 DataStore** 按类型序列化（String/Int/Long/Boolean/Float/Double/Set），黑名单 `backupExcludedKeyNames` 仅含 `initial_setup_done` 与 AI 偏好（另有独立 AI 模块）。新增的两个排序 key（String）与 `library_tabs_order`（Tab 顺序，决定 YEARS 位置）**无需注册任何 BackupSection/Handler/Schema，自动随「全局设置」模块导出并在恢复时写回**。
- **系统云备份/换机不走此通道**：`res/xml/backup_rules.xml` 与 `data_extraction_rules.xml` 刻意 `exclude datastore/settings.preferences_pb`（防止 API key 明文中转 Google 云），这是项目既定安全策略，排序偏好同样不进系统云备份——迁移以应用内备份文件为准，本次不改该策略。
- **配套数据模块**：排序偏好归「全局设置」；但默认排序「播放次数」依赖的播放统计在 EngagementStats 模块、评分依赖 Favorites 模块，要在新机复现完全一致的排序结果，备份时需一并勾选；年份桶本身无需备份（恢复后从歌曲表自动重新聚合）。
- **跨版本兼容**：新备份被旧版本恢复时，未知 key 写入后被忽略，无害；排序 storageKey 一经发布不可改名（与现有全部排序 key 同约束），否则备份还原后回退默认值。

---

## 4. 改动文件清单

### 新增（5）

| 文件 | 作用 |
|---|---|
| `data/model/YearBucket.kt` | 年份桶 UI model |
| `presentation/viewmodel/YearDetailViewModel.kt` | 年份详情状态 + 排序 + 取数 |
| `presentation/screens/YearDetailScreen.kt` | L2 页面（GenreDetail 骨架裁剪） |
| `presentation/library/YearsTabContent.kt`（或并入 LibraryScreen） | L1 年份列表 |
| `app/src/test/.../YearSortOptionTest.kt` | 排序翻转/持久化回退/默认值单测 |

### 修改（11）

| 文件 | 改动 |
|---|---|
| `data/model/SortOption.kt` | YEAR_BUCKETS(2) + YEAR_SONGS(18) 枚举并入 ALL |
| `data/model/LibraryTabId.kt` | 加 YEARS（defaultSort=YearBucketNewest） |
| `presentation/library/LibraryTabId.kt` | 加 Years（sortOptions=YEAR_BUCKETS） |
| `data/database/MusicDao.kt` | 3 条新查询 + YearBucketRow |
| `data/repository/MusicRepository.kt`（+`Impl`） | 2 个 Flow 方法、未知桶末尾拼装 |
| `data/preferences/UserPreferencesRepository.kt` | 2 个排序 pref key + flow/save |
| `presentation/viewmodel/LibraryStateHolder.kt`（+`LibraryViewModel.kt`） | yearBucketsFlow |
| `presentation/viewmodel/PlayerViewModel.kt` | Tab 合并逻辑 + DEFAULT_TAB_ORDER（YEARS 在 ALBUMS 后） |
| `presentation/components/ReorderTabsSheet.kt` | 整行长按拖动（保留手柄） |
| `presentation/screens/LibraryScreen.kt` | Pager 分支、排序 when ×2 及编译期穷举点 |
| `presentation/navigation/Screen.kt` + `AppNavigation.kt` | YearDetail 路由与注册 |
| `res/values(-zh-rCN)/strings_library.xml`、（可选）drawable、`CHANGELOG.md` | 文案/图标/变更日志 |

---

## 5. 边界与取舍

1. **发布时间粒度**：`songs.year` 只有年，年内「发布时间」= 专辑/碟号/轨号顺序；月/日精度需改标签解析 + 新列 + Room migration，单独立项。
2. **未知年份**：`year=0`（含缺年份的云音源）单列桶且固定末尾；其详情页就是 `WHERE year=0` 的歌曲。
3. **性能**：L1 `GROUP BY year` 万首级曲库毫秒级；L2 单年全量与流派详情同量级，`SONG_LIST_PROJECTION` 已规避 CursorWindow 风险；两个 LEFT JOIN 一对一无扇出。不引入分页（分页还需为播放全部补全量查询，净增复杂度）。
4. **智能分类不可手工改成员**：年份桶不支持加/删歌与持久化手动曲序；要固化走「多选 → 加入播放列表」（写 playlist_songs）。
5. **云音源**：Telegram 歌在同一张 songs 表，缺年份落未知桶；StorageFilter 口径与歌曲 Tab 一致。
6. **Wear / Android Auto**：不动；以后 Auto 要 Years 节点可直接复用同组 Repository 方法。

---

## 6. 测试与验证

### 6.1 JVM 单测（JUnit5，`.\run-tests.bat`；既有 6 个基线失败类与本次无关）

- `YearSortOptionTest`：9 方法各自 `flipDirection()` 落到对侧方向且 methodKey 不变；默认值 = `YearSongPlayCount`（Desc）；`fromStorageKey` 非法值回退；YEAR 两组已并入 ALL。
- Tab 合并：旧顺序（无 YEARS）合并后 YEARS 位于 ALBUMS 与 ARTIST 之间；自定义顺序不被破坏；含未知 key 的健壮性。
- 未知桶拼装：year=0 计数为 0 时不追加；>0 时追加且永远在末尾（正序/逆序各验一次）。
- **边界（AGENTS.md 已声明）**：Room DAO SQL 无法在 JVM 验证（无 Robolectric），(a)(b)(c) 靠仪器/手工，不假装覆盖。

### 6.2 构建与静态检查

```powershell
.\gradlew.bat :app:assembleDebug "-Ppixelplay.enableAbiSplits=true"
.\gradlew.bat :app:lintDebug
.\run-tests.bat
```

### 6.3 真机手工验收清单

1. 全新安装 / 老用户升级两路径：YEARS 都出现在专辑之后；编辑标签里手柄拖动 + 整行长按拖动都可排序并持久化，可显隐。
2. 桶数量/每桶歌曲数核对；未知年份桶存在且永远在最后，点入是 year=0 的歌。
3. 年详情：9 个排序逐一正确（重点验默认播放次数、最后播放、评分），翻转卡一键反向；杀进程重进排序保留（默认播放次数）。
4. 播放一首歌后播放次数/最后播放序即时重发重排（验证 LEFT JOIN 响应式）；改评分后评分序即时变化。
5. 播放全部/随机/单曲点击：队列只含该年、顺序=当前排序；多选动作正常。
6. 旋转/深色/中英文、空曲库空态。

---

## 7. 明确不做（v1 out of scope）

- 年→专辑→歌曲三级层级（Poweramp by_year/album）。
- 完整发布日期（月/日）解析、schema 变更与 Room 迁移。
- 年份桶手工成员编辑/手工曲序持久化（智能分类的固有边界）。
- Wear OS、Android Auto、桌面小部件、AI 歌单联动。
