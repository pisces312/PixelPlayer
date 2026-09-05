# 发行「月/日」提取与年内按发行时间排序 — 实施方案（v1.0，已 review，**暂缓实现**）

> **状态（2026-09-05）**：方案已 review 并存档，**本期不实现，以后确有需要再启动**。
> 暂缓原因：MediaStore 系统 API 只暴露年份列，月/日只能逐文件读标签（且仅全量/深度扫描可回填存量曲库），收益与成本不匹配；当前年内「发行顺序」维持专辑名+碟号+音轨号的代理排序。
> 重启时直接按本文 §2/§3 执行，§5 的 4 个决策点默认采用各条「推荐」项（A 打包整数列、B 手动全量扫描回填、缺日期钉尾、远程源/UI 展示不做），实现前再与用户最终确认一次即可。

> 目标：年份智能歌单（L2）内的「发行顺序」排序从当前的「专辑名+碟号+音轨号」代理排序，升级为按标签中的真实发行年月日排序。
> 现状结论：**标签里的月、日信息在入库时被丢弃了**——`AudioMetadataReader` 对 `DATE` 标签只做了 `.take(4).toIntOrNull()`，数据库 `songs` 表也只有 `year INTEGER`。

---

## 1. 现状调研（已逐文件核实）

### 1.1 标签提取链路
- `data/media/AudioMetadataReader.kt`
  - TagLib 主路径（L102）：`propertyMap["DATE"]?.firstOrNull()?.take(4)?.toIntOrNull()`，`DATE` 原始串可能是 `2019-05-21T12:00:00`（ID3v2.4 TDRC / MP4 ©day）、`2019-05-21`（Vorbis/FLAC）、`2019`（只有年）——**月日被 take(4) 砍掉**。
  - JAudioTagger 兜底路径（L196）：`FieldKey.YEAR` 同样只取前 4 位。
- `data/worker/SyncWorker.kt`
  - MediaStore 游标（L890/939）只有 `MediaStore.Audio.Media.YEAR`（Int），**Android 系统本身不提供月/日列**，月日只能从文件标签读。
  - `buildSongEntity`（L1093-1169）：只有 `deepScan`（全量扫描）/ wav·opus·ogg·oga·aiff / 艺术家或专辑缺失时才调 `AudioMetadataReader` 补标签；普通增量扫描的常见 MP3/FLAC 不会重读标签。
  - 远程源（Telegram/网易云/QQ/Navidrome/Jellyfin/GDrive）当前一律 `year = 0` 或只给年份。

### 1.2 存储链路
- `SongEntity`（`data/database/SongEntity.kt` L90）：`@ColumnInfo("year") val year: Int = 0`。
- Room 当前版本 **43**（`PixelPlayDatabase.kt` L39），最新迁移 `MIGRATION_42_43`（favorites.rating）；迁移在 `di/AppModule.kt` L169 注册。
- 全新安装的表结构由 Room 按 `@Entity` 自动生成；`recreateSongsTable`（L772）只服务于 15→18 等**老迁移路径**，新迁移不需要动它。
- `MusicDao.SONG_LIST_PROJECTION`（L75-81）是所有歌曲列表查询的统一投影，新增列必须加进去。
- `Song` 领域模型（`data/model/Song.kt` L32）只有 `year`；`toSongInternal / Song.toEntity / toEntityWithoutPaths` 三处映射。
- Room schema 导出目录 `app/schemas/`（KSP 自动生成，需随迁移提交 44.json）。
- 仪器迁移测试 `androidTest/.../PixelPlayDatabaseMigrationTest.kt`：**目前滞后**（`LATEST = 42`、ALL_MIGRATIONS 只到 41_42，连现存 42_43 都没补），本次一并补齐到 44。

### 1.3 当前「发行顺序」排序
- `MusicDao.getSongsByYear`（L1705-1710）：`year_song_release` 目前按 `album_name → disc_number → track_number` 排，是没有真实日期时的代理方案。
- 排序枚举 `SortOption.YearSongRelease / YearSongReleaseDesc` 与中英文案（`sort_display_release_year`、`sort_method_release_year`）**已存在，本次无需新增字符串**。

---

## 2. 设计方案

### 2.1 存储形式（推荐方案 A，待你拍板）

**方案 A（推荐）：单列打包整数 `release_date INTEGER`，值 = `yyyy*10000 + MM*100 + dd`**

| 标签情况 | 存储值 |
|---|---|
| 无日期 | `0` |
| 只有年 `2019` | `20190000` |
| 年+月 `2019-05` | `20190500` |
| 完整 `2019-05-21` | `20190521` |

- 整数自然序即时间序，SQL 一层 `ORDER BY release_date` 即可，无需日期函数、无字符串解析；只加一列、一个默认值，迁移最轻。
- 年列 `year` 原样保留（年份桶分组、其他几十个查询都依赖它，零波及）。

备选：B. `release_month / release_day` 两列（排序要拼算术，两列迁移）；C. 原始日期字符串（排序需字符串解析、无法走整型比较，不推荐）。

### 2.2 标签解析：新增容错解析器

新增 `data/media/ReleaseDateParser.kt`（纯 Kotlin，可 JVM 单测）：
- 正则 `^\D*(\d{4})(?:[-/.](\d{1,2}))?(?:[-/.](\d{1,2}))?`，兼容 `2019`、`2019-05`、`2019-05-21`、`2019/5/21`、`2019.05.21`、`20190521`、`2019-05-21T12:00:00`、`T21 May 2019` 前缀等形态。
- 月 1–12、日 1–31 范围校验，非法段降级为 0（如 `2019-13` → `20190000`）。
- 输出打包整数；解析失败返回 null。
- `AudioMetadata` 增加 `releaseDate: Int? = null`（默认 null，其余 5 个构造点零改动）；TagLib 路径喂 `DATE`（缺失再试 `YEAR`、`ORIGINALDATE`/`ORIGINALYEAR`），JAudioTagger 路径喂 `FieldKey.YEAR` 原始串。

### 2.3 数据库迁移（43 → 44）

```sql
ALTER TABLE songs ADD COLUMN release_date INTEGER NOT NULL DEFAULT 0;
```
- 瞬时完成、不搬数据；老用户升级后已有行先为 0，标签回读后才有值（见 2.5）。
- `version = 44`；新增 `MIGRATION_43_44` 并在 `AppModule` 注册；KSP 生成并提交 `app/schemas/.../44.json`。
- debug 才允许破坏性迁移、release 迁移失败直接崩溃的既有策略不变。

### 2.4 年内「发行顺序」SQL 改造（getSongsByYear）

- 正序（最早发行优先）：
  ```sql
  CASE WHEN release_date = 0 THEN 1 ELSE 0 END ASC,  -- 日期缺失的歌排到该年最后
  release_date ASC,
  albums 名/disc/track 现有 tie-break（同日多张专辑时保持稳定次序）
  ```
- 反序：`release_date DESC`（0 自然落末尾）+ 反向 tie-break。
- L1 年份桶（GROUP BY year）**不变**；其他列表排序**不碰**。

### 2.5 已有曲库的回填策略（待你拍板，推荐 B）

- A. 升级后自动触发一次深度扫描（forceMetadata）回填：用户无感，但全量读标签耗电耗时（大曲库可能数十分钟）。
- **B（推荐）. 不自动扫描**：迁移秒完成；新入库/改动的歌曲立即带上月日；存量歌曲在用户下次主动「全量重新扫描」（现有功能）后回填。排序在回填前优雅降级为当前的专辑/音轨 tie-break，不会错序。
- 折中 C. DataStore 记一次性 flag，升级后仅在充电+WiFi 时提示用户「可全量扫描以启用精确发行排序」，不自动跑。

### 2.6 明确不做（v1 边界）
- 远程源（Navidrome/Jellyfin 等）的发行日期解析：DTO 层以后可扩展，本期默认 0。
- UI 不展示完整发行日期（本期只服务排序；歌曲信息页以后要展示再加）。
- 元数据编辑页目前**没有年份编辑项**，不存在标签写回路径，无需改 tag writer。
- Wear 模块独立 Room，不涉及。
- R8 keep 规则无需改（无反射/序列化新模型）。

---

## 3. 改动文件清单（约 10 处，无 UI 布局改动）

| # | 文件 | 改动 |
|---|---|---|
| 1 | `data/media/ReleaseDateParser.kt` | 【新建】容错日期解析 |
| 2 | `data/media/AudioMetadataReader.kt` | AudioMetadata 加 releaseDate；两条读取路径解析 DATE |
| 3 | `data/model/Song.kt` | 加 `releaseDate: Int = 0` |
| 4 | `data/database/SongEntity.kt` | 加 `release_date` 列 + 三处映射 |
| 5 | `data/database/MusicDao.kt` | SONG_LIST_PROJECTION 加列；getSongsByYear 发行排序改造 |
| 6 | `data/database/PixelPlayDatabase.kt` | version 44 + MIGRATION_43_44 |
| 7 | `di/AppModule.kt` | 注册迁移 |
| 8 | `data/worker/SyncWorker.kt` | buildSongEntity 透传 releaseDate |
| 9 | `androidTest/.../PixelPlayDatabaseMigrationTest.kt` | LATEST→44、补 42_43/43_44、循环范围 25..43 |
| 10 | `app/schemas/.../44.json` | KSP 生成并提交 |
| 11 | 单测 `ReleaseDateParserTest.kt` | 日期形态矩阵（纯 JVM） |
| 12 | `CHANGELOG.md` | Added 条目 |

## 4. 验证计划
- `ReleaseDateParserTest`：10+ 种标签日期形态/非法值 JVM 全覆盖。
- `:app:assembleDebug`：KSP 校验新 SQL 与 schema 导出。
- 全量 `run-tests.bat` 无新增回归。
- 仪器迁移测试（需真机/模拟器，JVM 跑不了）：43→44 列存在、默认值 0、老数据不丢。
- 真机验收：造一批同一年但月日不同的歌曲（含只有年、无年、`yyyy-MM-dd`、`yyyyMMdd` 等标签），验证正/反序与未知日期钉尾；全量扫描后回填生效。

## 5. 待你确认的决策点
1. **存储形式**：A 打包整数（推荐）/ B 两列 / C 字符串？
2. **回填策略**：B 手动全量扫描（推荐）/ A 升级后自动深扫 / C 一次性提示？
3. **缺月日的歌**在发行顺序中钉在该年有日期歌曲之后（推荐），确认？
4. 远程源日期解析、UI 展示完整日期：本期都不做，确认？
