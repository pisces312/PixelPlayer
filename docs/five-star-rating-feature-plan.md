# 五星评分功能方案（含收藏入口 UI 重设计）

> 版本：v1.0（2026-09-03）
> 目标：让 PixelPlayer 原生支持 1–5 星评分，并借此完整承接 Poweramp 备份中的评分数据。
> 范围：数据层（Room + 备份）、UI 层（收藏入口重设计）、推荐算法接入、与 Poweramp 导入的衔接。
> 相关文档：
> - `docs/poweramp-import-feature-plan.md`（评分数据来源与导入口径）
> - `docs/recommendation-algorithm-design.md`（评分如何进入排序）

---

## 0. 结论先行

| 问题 | 结论 |
|---|---|
| 评分加在哪张表？ | **扩展 `favorites` 表**，允许 `isFavorite = 0` 的纯评分行存在。**不要**加在 `songs` 上 |
| 为什么要现在做？ | Poweramp 备份中 **809 首（48.3%）有评分**，当前只能降级为二值收藏，会丢失 3/4/5 星之间的差异 |
| 五星能改善推荐吗？ | **不能**（实测：区分度 ×0.94，反而略降）。价值在**数据保真**，不在排序质量 |
| 收藏入口怎么改？ | 播放器第三格改为**双态分段格**：单击切收藏（保持肌肉记忆），长按原地展开五星 |

### ⚠️ 一个必须先知道的实测事实：你的评分其实是准二值的

目标备份 1674 首去重曲库的评分分布：

| rating | 数量 | 占比 |
|---|---|---|
| 5 | 538 | 32.14% |
| 4 | 171 | 10.22% |
| 3 | 98 | 5.85% |
| 2 | 2 | 0.12% |
| 1 | 0 | **0.00%** |
| 0（未评分） | 865 | 51.67% |

**1 星 0 首、2 星 2 首**——中间档位几乎不用。你在 Poweramp 里的实际行为是「喜欢 = 5 星，否则不评」。

因此：
- 五星的**数据结构**要做完整（1–5 星都能存），因为未来行为可能变化；
- 但**不要指望它立刻带来推荐质量的跃升**，理由见 §5；
- UI 上也不必为 1–2 星做过多视觉设计，按「5 星为主 + 中间档可用」处理即可。

**评分仍是独立信号**（值得保留）：有评分的歌平均播放 **5.00 次**，无评分的 **2.00 次**，`Pearson(rating, playCount) = 0.2694`（弱相关）——说明评分携带了播放行为之外信息。

---

## 1. 现状（代码实测）

### 1.1 收藏有两套存储，权威源是 `favorites` 表

| 存储 | 写入方 | 状态 |
|---|---|---|
| `favorites` 表（`FavoritesEntity`） | `MusicRepositoryImpl` → `favoritesDao` | ✅ **权威**。收藏分页用 `INNER JOIN favorites ON ... AND favorites.isFavorite = 1` |
| `songs.is_favorite` 列 | `MusicDao.setFavoriteStatus` | ❌ **死列**，无任何业务调用方 |

所有业务调用点（`MusicService:1122/2618`、`PlayerViewModel:1547/2262`、`MultiSelectionStateHolder:357`）都走 `musicRepository` → `favoritesDao`。

`Song.isFavorite` 之所以显示正确，是因为 `PlayerViewModel:1391` 用 `song.copy(isFavorite = favorites.contains(songId))` 覆盖了原本从 `songs.is_favorite` 投影出的值。

> **决策依据**：评分加在 `favorites` 表。`songs.is_favorite` 建议**后续单独清理**（本次不动，避免扩大改动面）。

### 1.2 现有结构

```kotlin
@Entity(tableName = "favorites", indices = [Index(value = ["timestamp"], unique = false)])
data class FavoritesEntity(
    @PrimaryKey val songId: Long,
    val isFavorite: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)
```

```kotlin
@Dao interface FavoritesDao {
    @Insert(onConflict = REPLACE) suspend fun setFavorite(favorite: FavoritesEntity)
    @Insert(onConflict = REPLACE) suspend fun insertAll(favorites: List<FavoritesEntity>)
    @Query("DELETE FROM favorites WHERE songId = :songId") suspend fun removeFavorite(songId: Long)
    @Query("SELECT isFavorite FROM favorites WHERE songId = :songId") suspend fun isFavorite(songId: Long): Boolean?
    @Query("SELECT songId FROM favorites WHERE isFavorite = 1 ORDER BY songId") fun getFavoriteSongIdsRaw(): Flow<List<Long>>
    ...
    @Transaction suspend fun replaceAll(favorites: List<FavoritesEntity>) { clearAll(); insertAll(...) }
}
```

⚠️ **两个需要改造的语义点**：

1. `removeFavorite` 是 `DELETE` —— 加了评分后，取消收藏会**连带删掉评分**。必须改为软删除。
2. `replaceAll` 是 `clearAll + insertAll` —— 备份恢复时会清空重写，需确保 `isFavorite = 0` 的纯评分行也能被恢复。

### 1.3 收藏入口的两处 UI

| 位置 | 形态 | 文件 |
|---|---|---|
| **播放器控制区下方** | `BottomToggleRow`：`Shuffle` / `Repeat` / `Favorite` **三等分分段按钮** | `BottomToggleRow.kt:105-117` |
| **歌曲信息底部弹窗** | `Row1Actions`：`Favorite` / `Share` / … **等分 FilledTonalIconButton** | `SongInfoBottomSheet.kt:1290-1305` |
| 歌词页 | 单个收藏图标按钮 | `LyricsSheet.kt:1085` |
| 多选底部弹窗 | 菜单项 | `MultiSelectionBottomSheet.kt:307` |

`BottomToggleRow` 的第三格（`BottomToggleRow.kt:105-117`）就是你说的「当前点击收藏的位置」——三连分段按钮的最后一格，点击即切换收藏。

---

## 2. 数据层设计

### 2.1 方案对比

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| A. `favorites` 加 `rating` 列，**仅收藏歌曲可有评分** | 记录不存在 = 未收藏 | 改动最小 | ❌ **导入时会丢失未达收藏阈值歌曲的评分**（如 3 星共 98 首） |
| **B. `favorites` 加 `rating` 列，允许 `isFavorite = 0` 的纯评分行** ✅ | 记录存在 + `isFavorite=0` = 仅评分 | 一处存储；现有收藏查询 `WHERE isFavorite = 1` **不用改**；导入可保留全部评分 | 需改造 `removeFavorite` 语义 |
| C. 新建 `song_ratings` 表 | 评分与收藏完全解耦 | 语义最清晰 | 多一张表 + 一个备份 section；DailyMix 需额外查询；`favorites` 与 `ratings` 可能不一致 |

**推荐方案 B**。关键理由：Poweramp 导入场景下，方案 A 会让 **98 首 3 星歌 + 2 首 2 星歌的评分直接丢失**——而这正是本次做五星评分想保住的数据。

### 2.2 实体与迁移

```kotlin
@Entity(
    tableName = "favorites",
    indices = [
        Index(value = ["timestamp"], unique = false),
        Index(value = ["rating"], unique = false)      // 新增：支持按评分排序/筛选
    ]
)
data class FavoritesEntity(
    @PrimaryKey
    @SerializedName(value = "songId", alternate = ["song_id"])
    val songId: Long,
    @SerializedName(value = "isFavorite", alternate = ["is_favorite"])
    val isFavorite: Boolean = true,
    @SerializedName(value = "timestamp", alternate = ["addedAt", "added_at"])
    val timestamp: Long = System.currentTimeMillis(),
    /** 0 = 未评分；1..5 = 评分。与 isFavorite 相互独立。 */
    @SerializedName(value = "rating", alternate = ["rating_stars"])
    val rating: Int = 0
)
```

**Room 迁移 42 → 43**：

```kotlin
val MIGRATION_42_43 = object : Migration(42, 43) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE favorites ADD COLUMN rating INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_favorites_rating ON favorites (rating)")
    }
}
```

> `rating` 用 `NOT NULL DEFAULT 0`，既有行自动为 0，无需数据回填。

### 2.3 语义定义（三态）

| `favorites` 行 | `isFavorite` | `rating` | 含义 |
|---|---|---|---|
| 不存在 | — | — | 未收藏、未评分 |
| 存在 | `true` | `0` | 已收藏，未评分 |
| 存在 | `true` | `1..5` | 已收藏且已评分 |
| 存在 | `false` | `1..5` | **仅评分，未收藏**（Poweramp 导入产生） |
| 存在 | `false` | `0` | 无意义，应被删除 |

### 2.4 DAO 改造

```kotlin
// 1. 软删除：取消收藏时保留评分
@Query("UPDATE favorites SET isFavorite = 0 WHERE songId = :songId")
suspend fun clearFavoriteFlag(songId: Long)

// 2. 清除无意义行（isFavorite=0 且 rating=0）
@Query("DELETE FROM favorites WHERE songId = :songId AND isFavorite = 0 AND rating = 0")
suspend fun purgeIfEmpty(songId: Long)

// 3. 设置评分（保留 isFavorite 原值）
@Query("""
    INSERT INTO favorites (songId, isFavorite, timestamp, rating)
    VALUES (:songId, COALESCE((SELECT isFavorite FROM favorites WHERE songId = :songId), 0),
            :timestamp, :rating)
    ON CONFLICT(songId) DO UPDATE SET rating = :rating
""")
suspend fun setRating(songId: Long, rating: Int, timestamp: Long)

// 4. 新增查询
@Query("SELECT songId, rating FROM favorites WHERE rating > 0")
suspend fun getAllRatingsOnce(): List<SongRatingProjection>

@Query("SELECT rating FROM favorites WHERE songId = :songId")
suspend fun getRating(songId: Long): Int?

@Query("""
    SELECT f.songId FROM favorites f
    INNER JOIN songs s ON s.id = f.songId
    WHERE f.isFavorite = 1
    ORDER BY f.rating DESC, f.timestamp DESC
""")
fun getFavoriteSongIdsSortedByRating(): Flow<List<Long>>
```

> ⚠️ **破坏性变更**：现有 `removeFavorite()`（`DELETE`）必须停止在业务逻辑中直接调用，改为 `clearFavoriteFlag() + purgeIfEmpty()`。调用点：`MusicRepositoryImpl.setFavoriteStatus`（第 823 行附近）。

### 2.5 Repository 层

```kotlin
// MusicRepository 新增
suspend fun setSongRating(songId: String, rating: Int)
suspend fun getSongRating(songId: String): Int
fun getRatingsFlow(): Flow<Map<String, Int>>
```

`PlayerViewModel` 增加 `currentSongRating: StateFlow<Int>`，与现有 `isCurrentSongFavorite` 并列（参考 `PlayerViewModel:1206`）。

---

## 3. UI 重设计

### 3.1 播放器：第三格改为「双态分段格」

**现状**（`BottomToggleRow.kt`）：`Shuffle` / `Repeat` / `Favorite` 三格等分（`Modifier.weight(1f)`）。

**改造后**：

| 交互 | 行为 |
|---|---|
| **单击** | 切换收藏（`isFavorite` 翻转）——**保持现有肌肉记忆，不变** |
| **长按**（≥400ms） | 该格**原地展开**为五星条 |
| 展开态点击星星 | 设置评分并收起 |
| 展开态点击当前星级 | 清除评分（归 0）并收起 |
| 展开态点击格内空白 / 其他格 | 收起，不改动 |

**布局动画**：展开时第三格 `weight` 从 `1f` → `2.6f`，`Shuffle` / `Repeat` 各从 `1f` → `0.7f`。用项目已有的 `MotionScheme.expressive()`（`FullPlayerContent.kt:1136` 已用）与 `AbsoluteSmoothCornerShape`（`BottomToggleRow.kt:38` 已用）保持设计语言一致。

**图标状态**：

| 状态 | 图标 | 容器色 |
|---|---|---|
| 未收藏、未评分 | `Icons.Rounded.FavoriteBorder` | `surfaceContainerHighest` |
| 已收藏、未评分 | `Icons.Rounded.Favorite` 实心 | `tertiary`（现状） |
| 已收藏且已评分 | `Icons.Rounded.Favorite` 实心 **+ 右下角星级数字徽标** | `tertiary` |
| 仅评分、未收藏 | `Icons.Rounded.Star` | `tertiaryContainer` |

> 徽标用于在不展开的情况下也能看出「这首歌评了几星」，避免每次都要长按。

**无障碍**：长按需提供替代路径（无障碍服务无法长按）。建议在格子的 `contentDescription` 中提示，并在 `SongInfoBottomSheet`（§3.2）提供**无需长按**的常驻五星条作为等价入口。

### 3.2 歌曲信息弹窗：常驻五星条

`SongInfoBottomSheet` 的 `Row1Actions` 是「Favorite / Share / …」等分按钮组。此处**空间充足且属于详情场景**，建议：

- Row1 的 Favorite 按钮**保持单击切收藏**（不变）
- **在 Row1 下方新增一行「评分」**：5 个星形图标，等宽排列，点击即设分

```
┌─────────────────────────────────┐
│  [♡收藏]  [↗分享]  [⋮更多]       │  ← Row1Actions（不变）
├─────────────────────────────────┤
│  评分                            │
│  ★ ★ ★ ★ ☆        3 星 · 清除   │  ← 新增
└─────────────────────────────────┘
```

这一行是**无障碍等价入口**，也解决了「长按不好发现」的问题。

### 3.3 其余入口

| 位置 | 处理 |
|---|---|
| `LyricsSheet:1085` | 与播放器一致：单击切收藏，长按展开五星 |
| `MultiSelectionBottomSheet:307` | 菜单项「收藏」旁新增「评分…」，点开为五星选择行 |
| 列表项 | 沿用长按多选入口，同上 |

### 3.4 交互细节

| 项 | 规格 |
|---|---|
| 半星 | **不支持**（与 Poweramp 一致） |
| 星级粒度 | 整数 1–5 |
| 触觉反馈 | `HapticFeedbackType.LongPress`（展开时）、`ToggleOn`/`ToggleOff`（选星时） |
| 无障碍 | 每星 `contentDescription = "评 N 星"`；整组 `Role.RadioGroup` 语义 |
| 视觉 | 已选中 `Icons.Rounded.Star`（实心，`tertiary`），未选中 `Icons.Rounded.StarBorder`（`onSurfaceVariant`） |
| 取消评分 | 点击当前星级 → 归 0；或五星条右侧「清除」按钮 |

---

## 4. 与 Poweramp 导入的衔接（重要简化）

`poweramp-import-feature-plan.md` v1.3 的 §4.4 计划把原始评分写入旁路文件 `poweramp_ratings.json`，**这是当时没有原生评分存储的权宜之计**。有了五星评分后：

| v1.3 做法 | 改为 |
|---|---|
| `rating ≥ 阈值` → `favorites`（二值收藏） | 不变（默认阈值 4 星） |
| 全部评分 → `poweramp_ratings.json` 旁路文件 | ❌ **删除该旁路文件**，改为**直接写 `favorites.rating`** |
| 未达阈值的评分 | ✅ **保留**（`isFavorite = 0, rating = N` 的行） |

**收益**：

1. 无需维护第二个存储与后续的「原生评分上线后再迁移」步骤；
2. 3 星（98 首）、2 星（2 首）的评分不再丢失；
3. 评分立即可用于「按评分排序」「按评分筛选」。

**导入写入逻辑**：

```
对每首匹配成功的歌曲：
    rating = Poweramp rating（NULL → 0，规则 N1）
    isFavorite = (rating >= 阈值)
    若 rating > 0 或 isFavorite：
        favorites 表 upsert(songId, isFavorite, timestamp=导入时刻, rating)
```

---

## 5. 推荐算法接入（预期要放低）

### 5.1 建议公式

`DailyMixManager.computeRankedSongs` 的 `favoriteScore`（权重 0.10，二值）改为 `ratingScore`：

```kotlin
val ratingScore = when {
    rating >= 1  -> 0.4 + (rating / 5.0) * 0.6      // 1星=0.52, 2星=0.64, ..., 5星=1.0
    isFavorite   -> 0.4                              // 收藏但未评分：保留基础价值
    else         -> 0.0
}
```

保留 0.4 的收藏底分，是为了不让既有收藏行为被评分稀释。

### 5.2 ⚠️ 但实测显示区分度**不会**改善

用目标备份 1674 首实测对比（详见 `recommendation-algorithm-design.md` §7.3）：

| 方案 | 分布标准差 | 有效贡献（权重 0.10 × 标准差） |
|---|---|---|
| `favoriteScore`（二值，≥4 星） | **0.4941** | 0.04941 |
| `ratingScore`（`rating / 5`） | **0.4643** | 0.04643 |

**×0.94，反而略降。** 原因是评分分布高度集中在 5 星与 0 星（§0）。

> **结论**：接入 `ratingScore` 的收益是**语义更合理**（收藏≠满分），**不是**排序区分度提升。若期望靠评分解决 `recencyScore` 87.3% 满分的问题，会落空——那个问题需要改 `recencyScore` 本身（见 `recommendation-algorithm-design.md` §7.4）。

### 5.3 建议的接入顺序

先只做**数据层 + UI + 导入衔接**，算法接入放到最后单独评估。这样即使最终决定不接入排序，评分数据也已完整落地。

---

## 6. 备份兼容

| 项 | 处理 |
|---|---|
| `CURRENT_SCHEMA_VERSION` | `3` → **`4`** |
| 导入 v3 备份 | ✅ 兼容。`rating` 有 Gson 缺省值 `0`，`ManifestValidator` 仅拒绝 `> CURRENT` |
| 导出 v4 备份 | 老版本 App 会因 `4 > 3` 拒绝导入——这是版本升级的正常语义，需在 Release Note 说明 |
| `FavoritesModuleHandler` | 无需改代码（`export()` 直接序列化 `FavoritesEntity`，自动带上 `rating`） |
| `restore()` | ⚠️ 需确认 `replaceAll` 会写入 `isFavorite = 0` 的纯评分行——现有实现是 `clearAll + insertAll`，**已满足** |

---

## 7. 测试要点

- **数据层**
  - Migration 42→43 后既有收藏行 `rating = 0`，收藏状态不变
  - 取消收藏时评分保留（`isFavorite → 0`，`rating` 不变）
  - `isFavorite = 0 且 rating = 0` 的行被 purge
  - 评分 upsert 不覆盖 `isFavorite` 原值
  - 按评分排序 / 筛选正确
- **备份**
  - v3 备份导入后 `rating` 全为 0，收藏不受影响
  - v4 备份导出再导入，评分与收藏状态完整还原
  - `isFavorite = 0` 的纯评分行在备份往返后不丢失
- **UI**
  - 播放器单击切收藏（回归测试，不得破坏）
  - 长按展开五星，选星后收起且状态即时反映
  - 点击当前星级清除评分
  - 无障碍：TalkBack 下可完成评分（经 `SongInfoBottomSheet` 常驻五星条）
- **导入衔接**
  - 导入后 1676 首的评分全部落库（`rating > 0` 的应有 809 首）
  - 4 星阈值下收藏 709 首，且 3 星 / 2 星歌曲仍保留评分记录

---

## 8. 里程碑

| 阶段 | 内容 | 依赖 |
|---|---|---|
| **M1** 数据层 | `FavoritesEntity` 加 `rating`、Migration 42→43、DAO 改造（含软删除）、Repository 方法 | — |
| **M2** 播放器 UI | `BottomToggleRow` 第三格双态化、`PlayerViewModel.currentSongRating` | M1 |
| **M3** 其余 UI | `SongInfoBottomSheet` 常驻五星条、歌词页长按、多选菜单 | M1, M2 |
| **M4** 备份 | `CURRENT_SCHEMA_VERSION` → 4、往返测试 | M1 |
| **M5** 导入衔接 | 改造 Poweramp 导入写入 `favorites.rating`，**移除 `poweramp_ratings.json` 旁路方案** | M1, M4 |
| **M6** 算法接入 | `favoriteScore` → `ratingScore`（**收益有限，建议最后单独评估**） | M1 |

> M1–M5 是本功能的完整闭环。M6 按 §5.2 的实测结论，可以不做。

---

## 9. 待确认决策

| # | 决策点 | 建议 | 不处理的后果 |
|---|---|---|---|
| **R1** | 取消收藏时是否保留评分？ | 建议**保留**（`isFavorite → 0`，`rating` 不动） | 用户误点取消收藏会连带丢失评分，不可逆 |
| **R2** | 未达收藏阈值的评分是否导入？ | 建议**导入**（`isFavorite = 0, rating = N`） | 3 星 98 首、2 星 2 首的评分丢失 |
| **R3** | 播放器是否用「长按」展开五星？ | 建议**是**，但必须同时提供 `SongInfoBottomSheet` 常驻五星条作为无障碍等价入口 | 长按不可发现 + 无障碍服务无法触发长按时功能不可用 |
| **R4** | `songs.is_favorite` 死列是否清理？ | 建议**本次不动**，后续单独提交清理 | 无功能影响，仅代码冗余 |
| **R5** | 是否立即接入推荐排序（M6）？ | 建议**延后**，先落地数据 | 预期收益为负（区分度 ×0.94），且增加回归面 |

---

## 10. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-09-03 | 初稿。实测确认收藏权威源为 `favorites` 表；量化 Poweramp 评分分布（准二值）；给出方案 B 数据层设计、三处 UI 重设计、与导入的衔接简化方案；实测推翻「五星提升推荐区分度」假设。 |
