package com.theveloper.pixelplay.data.importer

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.stats.PlaybackStatsRepository
import com.theveloper.pixelplay.data.stats.StatsTimeRange
import com.theveloper.pixelplay.presentation.model.collectRecentlyPlayedSongIds
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * 真机现象复现：导入 Poweramp 备份后「最近播放」只看得到新装机这 2 天的记录，
 * 导入的老历史（2023 ~ 2026-09）一条都看不到。
 *
 * 本测试覆盖**筛选端**（`collectRecentlyPlayedSongIds`），验证结论：
 * 事件在库里，是「最近播放」默认 `StatsTimeRange.WEEK` 把老历史挡住了。
 *
 * ⚠️ 范围边界（为什么不做端到端）：`PlaybackStatsRepository` 的文件 I/O 依赖
 * `android.util.AtomicFile`，纯 JVM 单测（本工程未引入 Robolectric，且开了
 * `unitTests.isReturnDefaultValues = true`）下 `startWrite()` 返回 null，
 * `writePayloadLocked()` 的安全调用全部跳过却仍返回 true —— 假成功。
 * 写入端只能在真机验证（已由本次新增的导入日志 + 回读校验覆盖）。
 */
class ImportedHistoryVisibilityTest {

    @Test
    fun `ALL range keeps imported old history - nothing is filtered out`() = runTest {
        val history = mixedHistory(now = NOW)
        val visibleIds = collectRecentlyPlayedSongIds(
            playbackHistory = history,
            range = StatsTimeRange.ALL,
            nowMillis = NOW
        )

        // ALL 无时间下界：新装的本地记录和导入的老历史都该出现
        assertThat(visibleIds).hasSize(LOCAL_EVENT_COUNT + IMPORTED_EVENT_COUNT)
        assertThat(visibleIds).containsAtLeast(
            "local-0",
            "imported-0",      // 最老一条（约 900 天前）
            "imported-1675"    // 最新一条导入（约 481 天前）
        )
    }

    @Test
    fun `WEEK range hides imported history - the two-day symptom`() = runTest {
        val history = mixedHistory(now = NOW)
        val weekIds = collectRecentlyPlayedSongIds(
            playbackHistory = history,
            range = StatsTimeRange.WEEK,
            nowMillis = NOW
        )

        // 导入事件最早也在 481 天前，全部落在 WEEK 之外 → 只剩本地 2 天的记录。
        // 这正是「导入后只看到 2 天」的直接原因：切到 ALL 即可见。
        assertThat(weekIds).hasSize(LOCAL_EVENT_COUNT)
        assertThat(weekIds).containsNoneOf("imported-0", "imported-1675")
    }

    @Test
    fun `history cap alone cannot wipe out imported history`() = runTest {
        val history = mixedHistory(now = NOW)

        // 修复前 ListeningStatsTracker 只取 500 条（按时间倒序）。
        // 即便那样也仍会保留 200 条最新老历史 ⇒ 单靠上限无法解释
        //「一条老历史都看不到」，共犯是 WEEK 默认范围。
        val capped = history
            .sortedWith(
                compareByDescending<PlaybackStatsRepository.PlaybackHistoryEntry> { it.timestamp }
                    .thenBy { it.songId }
            )
            .take(500)

        assertThat(capped).hasSize(500)
        assertThat(capped.count { it.songId.startsWith("imported-") }).isEqualTo(200)
    }

    /**
     * 模拟真机数据：本地 300 条（2 天内）+ 导入 1676 条（900 ~ 481 天前）。
     * 与 PowerampBackupImporter 一致——每首歌只合成 1 条事件，挂在最后收听时间上。
     */
    private fun mixedHistory(now: Long): List<PlaybackStatsRepository.PlaybackHistoryEntry> {
        val local = (0 until LOCAL_EVENT_COUNT).map { i ->
            PlaybackStatsRepository.PlaybackHistoryEntry(
                songId = "local-$i",
                timestamp = now - (i % 2) * DAY_MS
            )
        }
        val imported = (0 until IMPORTED_EVENT_COUNT).map { i ->
            PlaybackStatsRepository.PlaybackHistoryEntry(
                songId = "imported-$i",
                timestamp = now - 900 * DAY_MS + i * 6 * HOUR_MS
            )
        }
        return local + imported
    }

    private companion object {
        const val LOCAL_EVENT_COUNT = 300
        const val IMPORTED_EVENT_COUNT = 1_676
        val DAY_MS = TimeUnit.DAYS.toMillis(1)
        val HOUR_MS = TimeUnit.HOURS.toMillis(1)
        val NOW = System.currentTimeMillis()
    }
}
