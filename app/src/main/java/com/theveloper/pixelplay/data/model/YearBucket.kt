package com.theveloper.pixelplay.data.model

import androidx.compose.runtime.Immutable

/**
 * 年份智能播放列表桶（Years smart category）。
 *
 * 由 `songs` 表按 `year` 聚合动态计算，不向 playlists/playlist_songs 写入任何行：
 * - [year] > 0：该发布年份的桶；
 * - [year] == 0：标签缺失年份的「未知年份」桶，UI 层固定显示在列表末尾。
 */
@Immutable
data class YearBucket(
    val year: Int,
    val songCount: Int
) {
    val isUnknown: Boolean get() = year <= 0

    companion object {
        const val UNKNOWN_YEAR: Int = 0
    }
}
