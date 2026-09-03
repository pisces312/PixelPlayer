# PixelPlayer 推荐与排序算法设计

> 版本：v1.0（2026-09-03）
> 范围：`DailyMixManager` 的排序模型、`PlaybackStatsRepository` 的历史存储、以及二者的边界。
> 目标：把散落在实现里的参数集中成一份可查阅、可调优、可回归的规格说明。
> 相关文档：`docs/poweramp-import-feature-plan.md`（第三方导入如何冲击本算法）

---

## 1. 全局数据流

推荐排序**不读播放历史**。这是最容易误解的一点，先明确：

```
                    ┌─────────────────────────────────────────┐
   播放结束 ────────▶│  DailyMixManager.recordPlay()           │
                    │    ↓ EngagementDao.recordPlay()         │
                    │  【Room 表 song_engagements】★推荐数据源 │
                    └──────────────┬──────────────────────────┘
                                   │  getAllEngagements()
                                   ▼
                        computeRankedSongs()
                                   │
                    ┌──────────────┼──────────────┐
                    ▼              ▼              ▼
            generateDailyMix  generateYourMix  getTopCandidatesForAi
              (30 首)           (60 首)          (100 首候选)


                    ┌─────────────────────────────────────────┐
   播放结束 ────────▶│  PlaybackStatsRepository.recordPlayback│
                    │    ↓ AtomicFile JSON                    │
                    │  【playback_history.json】              │
                    └──────────────┬──────────────────────────┘
                                   │
                    ┌──────────────┼──────────────┐
                    ▼              ▼              ▼
              统计页 Summary    备份导出       ⚠️ 730 天裁剪
              loadSummary()   exportEvents()   （见 §6）
```

两条链路**只在「播放结束」这一刻同源**，之后完全独立：

| | `song_engagements`（Room） | `playback_history.json`（文件） |
|---|---|---|
| 粒度 | **每首歌一行**（累计聚合） | **每次播放一条**（事件流） |
| 字段 | `play_count` / `total_play_duration_ms` / `last_played_timestamp` | `songId` / `timestamp` / `durationMs` / `start` / `end` |
| 时间裁剪 | **无**（永久保留） | **730 天**（见 §6） |
| 消费方 | DailyMix / YourMix / AI 候选 / Android Auto 最近播放 / 备份 | 统计页 / 备份 |
| 上限 | 无 | 读取 `MAX_PLAYBACK_HISTORY_LIMIT = 5000` 条 |

> ⚠️ **结论**：`MAX_HISTORY_AGE_MS`（730 天）**对推荐排序没有任何影响**。删除或保留它都不会改变 DailyMix 的结果。它的影响面只有统计页与备份导出（详见 §6）。

---

## 2. 输入

`computeRankedSongs(allSongs, favoriteSongIds, random)` 接收三样东西：

| 输入 | 来源 | 说明 |
|---|---|---|
| `allSongs: List<Song>` | 曲库（本地 + 已接入的云端源） | 参与打分的全集 |
| `favoriteSongIds: Set<String>` | `FavoritesDao`（`favorites` 表） | 权威收藏源，见 §5.1 |
| `random: Random` | 按**日期**生成种子 | 保证同一天结果稳定（§4.3） |

以及从 Room 读出的 `Map<String, SongEngagementStats>`。

`SongEngagementStats` 三个字段，缺省全 0：

```kotlin
data class SongEngagementStats(
    val playCount: Int = 0,
    val totalPlayDurationMs: Long = 0L,
    val lastPlayedTimestamp: Long = 0L
)
```

---

## 3. 特征工程

### 3.1 全局归一化基准（先算，后用于每首歌）

```kotlin
maxPlayCount      = max(所有 playCount)              ?: 1     // 除零保护
maxDuration       = max(所有 totalPlayDurationMs)    ?: 1L
maxArtistAffinity = max(所有 artistAffinity)         ?: 1.0
maxGenreAffinity  = max(所有 genreAffinity)          ?: 1.0
maxFavoriteArtist = max(所有 favoriteArtistWeights)  ?: 1
```

> **性质**：这些是**全库相对基准**，不是绝对值。因此它是一个**零和排序系统**——新增数据会稀释已有歌曲的分数。这是 §7.2「导入冲击」的根因。

**歌手/流派亲和度**（先按播放强度给每首歌赋权，再按 artist / genre 聚合）：

```kotlin
weight = playCount.toDouble() + (totalPlayDurationMs / 60000.0)   // 次 + 分钟
if (weight > 0) {
    artistAffinity[song.artistId]      += weight
    genreAffinity[normalizeGenre(song.genre)] += weight
}
```

注意 `weight` 的量纲混合：**1 次播放 ≡ 1 分钟播放时长**。一首 4 分钟的歌播完一次，`weight = 1 + 4 = 5`，其中时长占 80%。

`normalizeGenreKey()`：`trim` → `lowercase` → 空串返回 `null` → **含 `"unknown"` 也返回 `null`**（归为「未知流派」独立计数）。

### 3.2 五个打分维度

#### ① `preferenceScore`（权重 **0.45**）—— 口味偏好，权重最高

```kotlin
artistPreference        = artistAffinity[artistId] / maxArtistAffinity
genrePreference         = genreAffinity[genreKey]  / maxGenreAffinity
favoriteArtistPreference = favoriteArtistWeights[artistId] / maxFavoriteArtist

preferenceScore =
    if (genreKey == null)
        artistPreference * 0.60 + favoriteArtistPreference * 0.40
    else
        artistPreference * 0.45 +
        genrePreference  * 0.35 +
        favoriteArtistPreference * 0.20
```

`favoriteArtistWeights` = 收藏歌曲按 artistId 计数（每首收藏歌 +1，与播放强度无关）。

> 设计意图：流派缺失时把权重让给歌手（0.60 vs 0.45）+ 收藏歌手（0.40 vs 0.20）。

#### ② `affinityScore`（权重 **0.25**）—— 这首歌本身有多常听

```kotlin
playCountScore = playCount / maxPlayCount
durationScore  = totalPlayDurationMs / maxDuration
affinityScore  = (playCountScore * 0.7 + durationScore * 0.3).coerceIn(0.0, 1.0)
```

> ⚠️ **常见误解澄清**：这里的 `totalPlayDurationMs` 是 `song_engagements` 里的**累计播放时长**，**不是歌曲本身的长度**。
> `Song.duration` 在 `DailyMixManager` 中**完全未被使用**——它只出现在 `PlaylistsModuleHandler.resolveSongId` 的元数据消歧（±2000ms 容差）里，与推荐无关。
> 因此「不知道歌曲长度」**不会**妨碍 `affinityScore` 计算。

#### ③ `recencyScore`（权重 **0.15**）—— 越久没听，越该推

```kotlin
lastPlayedTimestamp == null || <= 0  →  0.6      // 从未听过：给中间值
daysSinceLastPlay < 1   → 0.2        // 刚听过：压低
daysSinceLastPlay < 3   → 0.5
daysSinceLastPlay < 7   → 0.7
daysSinceLastPlay < 14  → 0.85
else                    → 1.0        // 超过 14 天：满分
```

> 这是**反向指标**（久未播放得分高），用于避免刚听过的歌反复出现。
> ⚠️ 副作用：**任何超过 14 天未听的歌都拿满分 1.0**，该维度在这部分歌曲间完全失去区分度（§7.3 实测量化）。

#### ④ `favoriteScore`（权重 **0.10**）—— 二值

```kotlin
favoriteScore = if (favoriteSongIds.contains(song.id)) 1.0 else 0.0
```

#### ⑤ `noveltyScore`（权重 **0.05**）—— 新歌加成

```kotlin
if (dateAdded <= 0) → 0.0
dateAddedMillis = if (dateAdded < 10_000_000_000L) dateAdded * 1000 else dateAdded  // 秒/毫秒自适应
noveltyScore = (1.0 - daysSinceAdded / 60.0).coerceIn(0.0, 1.0)   // 60 天线性衰减到 0
```

### 3.3 合成

```kotlin
baselineScore = if (stats == null) 0.1 else 0.0     // 冷启动：没听过的歌给 0.1 保底
noise         = random.nextDouble() * 0.005         // 极小扰动，仅用于打破并列

finalScore = preferenceScore * 0.45
           + affinityScore   * 0.25
           + recencyScore    * 0.15
           + favoriteScore   * 0.10
           + noveltyScore    * 0.05
           + baselineScore
           + noise
```

`finalScore` 的理论上界 ≈ `0.45 + 0.25 + 0.15 + 0.10 + 0.05 + 0.1 + 0.005 = 1.105`。

**排序**：`compareByDescending { finalScore }.thenBy { song.id }`（同分时按 id 升序，保证确定性）。

**另算一个 `discoveryScore`**（用于 YourMix 的「探索」段，不参与 `finalScore`）：

```kotlin
discoveryScore = (1.0 - affinityScore).coerceIn(0.0, 1.0) * 0.6
               + noveltyScore    * 0.25
               + preferenceScore * 0.15
```

语义：听得少（affinity 低）× 0.6 + 新歌 × 0.25 + 仍符合口味 × 0.15。

### 3.4 参数总表

| 参数 | 值 | 位置 | 作用 |
|---|---|---|---|
| `PREFERENCE_WEIGHT` | 0.45 | `computeRankedSongs` | 口味偏好权重 |
| `AFFINITY_WEIGHT` | 0.25 | 同上 | 播放强度权重 |
| `RECENCY_WEIGHT` | 0.15 | 同上 | 新鲜度权重 |
| `FAVORITE_WEIGHT` | 0.10 | 同上 | 收藏权重 |
| `NOVELTY_WEIGHT` | 0.05 | 同上 | 新歌权重 |
| `BASELINE_SCORE` | 0.1 | 同上 | 无参与度歌曲的冷启动加成 |
| `NOISE_MAX` | 0.005 | 同上 | 打破并列的随机扰动 |
| `AFFINITY_PLAY_RATIO` | 0.7 | 同上 | affinity 中播放次数占比 |
| `AFFINITY_DURATION_RATIO` | 0.3 | 同上 | affinity 中播放时长占比 |
| `MINUTES_PER_PLAY_EQUIV` | 1 分钟 ≡ 1 次播放 | `weight` 计算 | 歌手/流派亲和度的量纲换算 |
| `RECENCY_*` 分档 | 0.2 / 0.5 / 0.7 / 0.85 / 1.0 | `computeRecencyScore` | 1/3/7/14 天分档 |
| `RECENCY_NEVER_PLAYED` | 0.6 | 同上 | 从未播放 |
| `NOVELTY_DECAY_DAYS` | 60 | `computeNoveltyScore` | 新歌加成衰减周期 |
| `MILLIS_THRESHOLD` | 10_000_000_000L | 同上 | 秒/毫秒时间戳判定 |
| `DISCOVERY_*` | 0.6 / 0.25 / 0.15 | `discoveryScore` | 探索分构成 |

---

## 4. 三个入口

### 4.1 `generateDailyMix` —— 每日 30 首

```
seed  = YEAR * 1000 + DAY_OF_YEAR
limit = 30
流程：按 finalScore 降序 → pickWithDiversity(30) → 不足则 quotaFill → 仍不足则随机补齐
```

### 4.2 `generateYourMix` —— 三段式 60 首

```
seed = YEAR * 1000 + DAY_OF_YEAR + 17
limit = 60

favoriteSectionSize = (60 * 0.30) = 18   （coerceAtLeast(5)）
coreSectionSize     = (60 * 0.45) = 27   （coerceAtLeast(10)）
discoverySectionSize= 60 - 18 - 27 = 15

favoriteSection  = 候选池{favoriteSongIds 内的歌}   按 finalScore
coreSection      = 候选池{剩余}                     按 finalScore
discoverySection = 候选池{剩余}                     按 discoveryScore  ← 换排序键
输出 = favoriteSection + coreSection + discoverySection（LinkedHashSet 保序去重）
```

三段共享同一个 `DiversityState`（歌手/流派配额跨段累计）。

### 4.3 `getTopCandidatesForAi` —— AI 歌单候选 100 首

```
seed = YEAR * 1000 + DAY_OF_YEAR + 42
按 finalScore 降序直接 take(100)，不做多样性约束
```

> 种子含 `DAY_OF_YEAR` 的意义：**同一天内多次调用结果一致**（利于 LLM prompt 缓存），跨天自然变化。

### 4.4 多样性约束 `pickWithDiversity`

对每个候选，按 `finalScore` 降序贪心选取，受两重配额限制：

| 约束 | 规则 |
|---|---|
| 每歌手上限 | 收藏歌曲 **3** 首，非收藏 **2** 首 |
| 已知流派上限 | `limit ≤ 12 → 2`；`≤ 30 → 3`；`else → 4`；收藏歌曲 **+1** |
| 未知流派上限 | `limit ≤ 12 → 1`；`≤ 30 → 2`；`else → 3`；收藏歌曲 **+1** |

若走完一轮仍未凑够 `limit`，**第二轮放开所有多样性约束**直接补齐——保证数量优先。

---

## 5. 数据现状（实测）

### 5.1 ⚠️ 收藏存在两套存储，权威源是 `favorites` 表

| 存储 | 写入方 | 读取方 | 状态 |
|---|---|---|---|
| `favorites` 表（`FavoritesEntity`） | `MusicRepositoryImpl.setFavoriteStatus` → `favoritesDao` | 收藏分页（`INNER JOIN favorites`）、`getFavoriteSongIdsFlow` | ✅ **权威** |
| `songs.is_favorite` 列 | `MusicDao.setFavoriteStatus` | 仅 `SongEntity` 投影 | ❌ **无业务调用方，遗留死列** |

全库检索确认：所有业务调用点（`MusicService`、`PlayerViewModel`、`MultiSelectionStateHolder`）都走 `musicRepository` → `favoritesDao`。`MusicDao` 的 `setFavoriteStatus` / `getFavoriteStatus` / `toggleFavoriteStatus` **没有任何业务调用者**。

`Song.isFavorite` 之所以不会显示错，是因为 `PlayerViewModel:1391` 用 `song.copy(isFavorite = favorites.contains(songId))` 覆盖了它。

> **对五星评分的直接影响**：评分字段应加在 **`favorites` 表**，不要加在 `songs` 上。详见 `docs/five-star-rating-feature-plan.md`。

### 5.2 数据库与备份版本

- Room `version = 42`（`PixelPlayDatabase.kt:39`）
- 备份 `CURRENT_SCHEMA_VERSION = 3`（`BackupManifest.kt:12`）
- `ManifestValidator` 仅拒绝 `schemaVersion > CURRENT`，**低版本备份可正常导入**（Gson 缺省值兜底）

---

## 6. 播放历史存储与 730 天裁剪

### 6.1 现状

`PlaybackStatsRepository.kt:1100`：

```kotlin
private val MAX_HISTORY_AGE_MS = TimeUnit.DAYS.toMillis(730) // Keep roughly two years of history
```

裁剪发生在 **`recordPlayback()`**，不在导入路径：

```kotlin
val writeSucceeded = updateEventsAtomically { events ->
    val cutoff = sanitizedEvent.endMillis() - MAX_HISTORY_AGE_MS
    if (cutoff > 0) events.removeAll { it.endMillis() < cutoff }
    events += sanitizedEvent
    events
}
```

配套上限：

| 常量 | 值 | 作用 |
|---|---|---|
| `MAX_HISTORY_AGE_MS` | 730 天 | **写入时**按时间裁剪 |
| `DEFAULT_PLAYBACK_HISTORY_LIMIT` | 500 | `loadPlaybackHistory()` 默认读取条数 |
| `MAX_PLAYBACK_HISTORY_LIMIT` | 5_000 | 读取条数硬上限（`coerceAtMost`） |
| `MAX_SONG_STATS_COUNT` | 100 | 统计页 Top 歌曲上限 |

### 6.2 影响面（澄清）

**730 天裁剪不影响推荐**（§1 已证）。它的实际影响：

| 影响对象 | 后果 |
|---|---|
| 统计页 `loadSummary` | 时间线/活跃天数等只覆盖最近 2 年 |
| `exportEventsForBackup` | 备份里不含 2 年前的事件 |
| **推荐排序** | ❌ **完全不受影响** |

### 6.3 决策：去除时间裁剪，改为条数上限

**用户诉求**：去掉 730 天限制。

**评估**：直接删除 `removeAll` 会导致 `playback_history.json` **无界增长**。该文件由 `AtomicFile` 整体读-改-写，每次 `recordPlayback()` 都要全量反序列化再序列化。按每条约 120 字节估算：

| 使用强度 | 年增事件 | 5 年后体积 | 单次写入成本 |
|---|---|---|---|
| 轻度（10 首/天） | 3,650 | ~2.2 MB | ~30–50 ms |
| 中度（30 首/天） | 10,950 | ~6.6 MB | ~100–150 ms |
| 重度（80 首/天） | 29,200 | ~17.5 MB | ~300–500 ms |

（成本在 `Dispatchers.IO`，不阻塞主线程，但会拖慢播放结束后的写入并增加备份体积。）

**建议方案：时间裁剪 → 条数裁剪**

```kotlin
// 替换 MAX_HISTORY_AGE_MS
private const val MAX_HISTORY_EVENTS = 20_000        // 约 2 年中度使用

val writeSucceeded = updateEventsAtomically { events ->
    events += sanitizedEvent
    if (events.size > MAX_HISTORY_EVENTS) {
        // 按 endMillis 降序保留最近的 N 条
        events.sortedByDescending { it.endMillis() }.take(MAX_HISTORY_EVENTS)
    } else events
}
```

| | 现状（730 天） | 建议（20,000 条） |
|---|---|---|
| 老歌被裁条件 | 播放时间 > 2 年前 | **总量超过 20,000 条**（与时间无关） |
| 文件大小 | 有界（约 2 年） | 有界（约 2.4 MB） |
| 写入成本 | O(n) 全量重写 | O(n log n)（仅超限时排序） |
| 数据保真 | 2 年内的事件 | 最近 2 万条事件 |

> 排序只在超限时触发一次，且此后每次超限都会再排一次。可优化为「超限 10% 时才裁剪」（滞回），把排序次数降到 1/2000。

**配套建议**：
- `MAX_PLAYBACK_HISTORY_LIMIT` 从 5,000 提到 **20,000**，与存储上限对齐（否则存着的事件统计页看不到）。
- `DEFAULT_PLAYBACK_HISTORY_LIMIT`（500）保持不变，避免统计页首屏加载过重。

---

## 7. 已知问题与调优记录

### 7.1 归一化基准是零和的

`maxPlayCount` / `maxDuration` 是全库最大值。导入 Poweramp 数据后：

- `maxPlayCount` 由 **2** 跳到 **76**
- 本地原有 6 首歌的 `playCountScore` 从 ~0.5 稀释到 ~0.026（**降幅 97.4%**）

**这是正确行为**（它们确实只播过 1–2 次），但用户会直观感觉「我以前的歌都不见了」。建议在导入预览页说明。

### 7.2 导入后的维度退化（目标备份实测，1674 首去重曲库）

| 维度 | 权重 | 退化情况 |
|---|---|---|
| `recencyScore` | 0.15 | **87.3%（1414 首）拿到满分 1.0**（>14 天未听即满分） |
| `affinityScore` | 0.25 | 82.9% 的歌 < 0.05；99.3% < 0.30；仅 11 首 ≥ 0.30 |
| `preferenceScore` | 0.45 | 受 `maxArtistAffinity` 抬升影响，未评估 |

两个维度合计 **0.40 权重**在导入后近似退化为常数，排序实际上由 `preferenceScore`（0.45）与 `favoriteScore`（0.10）主导。

**根因是数据分布，不是算法缺陷**：Poweramp 的 `played_times` 长尾极重（中位数 1、最大 76），且 71% 的歌半年以上没听。

### 7.3 ⚠️ 五星评分**不能**解决区分度退化（实测推翻原假设）

原假设：把二值 `favoriteScore` 升级为连续的 `rating / 5` 能提升区分度。

**实测（目标备份 1674 首）**：

| rating | 数量 | 占比 |
|---|---|---|
| 5 | 538 | 32.14% |
| 4 | 171 | 10.22% |
| 3 | 98 | 5.85% |
| 2 | 2 | 0.12% |
| 1 | 0 | 0.00% |
| 0（未评分） | 865 | 51.67% |

```
favoriteScore(二值, ≥4星)   标准差 = 0.4941
ratingScore  (rating / 5)   标准差 = 0.4643   →  ×0.94（反而更低）
```

**评分高度集中在 5 星与 0 星，1/2/3/4 星合计仅 16.19%**。用户在 Poweramp 里实际把评分当作**准二值**使用（喜欢 = 5 星，否则不评）。因此：

> **结论**：五星评分的价值在于**数据保真与未来可用性**，**不在于立即改善推荐区分度**。若以提升排序质量为目标引入评分，预期会落空。

**但评分确实是独立信号**：

```
有评分（rating > 0）的歌：平均播放 5.00 次
无评分（rating = 0）的歌：平均播放 2.00 次
Pearson(rating, playCount) = 0.2694   ← 弱相关
```

弱相关意味着评分携带了**播放行为之外**的信息，值得作为独立维度保留。

### 7.4 建议的参数调整（待验证，未实施）

若未来希望提升区分度，优先级排序：

1. **`recencyScore` 改为连续函数**，消除 87.3% 满分问题。建议：`1.0 - exp(-days / 30)`，或把 >14 天档位细分为 30/90/365 天。
2. **`affinityScore` 改为对数缩放**：`affinity = log1p(playCount) / log1p(maxPlayCount)`。长尾分布下对数缩放能显著拉开低分区。
3. **`preferenceScore` 的 `weight` 量纲**：当前「1 次 ≡ 1 分钟」让时长占主导（4 分钟歌播完一次占 80%）。可考虑 `playCount + sqrt(durationMinutes)` 降低时长影响。

---

## 8. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-09-03 | 初稿。梳理 `DailyMixManager` 全部参数、三入口、多样性约束；澄清「推荐不读播放历史」；量化 730 天裁剪影响面并给出条数裁剪替代方案；实测推翻「五星评分提升区分度」假设。 |
