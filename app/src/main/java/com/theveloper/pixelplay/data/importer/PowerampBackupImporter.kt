package com.theveloper.pixelplay.data.importer

import android.net.Uri
import com.theveloper.pixelplay.data.database.EngagementDao
import com.theveloper.pixelplay.data.database.FavoritesDao
import com.theveloper.pixelplay.data.database.FavoritesEntity
import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.database.SongEngagementEntity
import com.theveloper.pixelplay.data.importer.poweramp.PowerampBackupParser
import com.theveloper.pixelplay.data.importer.poweramp.PowerampPathNormalizer
import com.theveloper.pixelplay.data.preferences.PlaylistPreferencesRepository
import com.theveloper.pixelplay.data.stats.PlaybackStatsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Poweramp 导入编排器（poweramp-import-feature-plan §4）。
 *
 * 两阶段用法：
 * 1. [prepare]：解析 + 匹配 → 预览数据（用户确认前不落任何数据）；
 * 2. [execute]：按 [ImportOptions] 写库（歌单 / 历史 / 统计 / 收藏+评分），可上报进度。
 *
 * 幂等性：歌单（distinct 追加）、历史（事件去重键）、收藏/评分（REPLACE）均幂等；
 * ⚠️ 参与度 mergeEngagement 为累加，重跑会翻倍（见方案 §9 D6）。
 */
@Singleton
class PowerampBackupImporter @Inject constructor(
    private val parser: PowerampBackupParser,
    private val normalizer: PowerampPathNormalizer,
    private val musicDao: MusicDao,
    private val engagementDao: EngagementDao,
    private val favoritesDao: FavoritesDao,
    private val playbackStatsRepository: PlaybackStatsRepository,
    private val playlistPreferencesRepository: PlaylistPreferencesRepository
) {
    /** prepare 的产物：中间数据 + 匹配结果 + 预览。 */
    class PreparedImport(
        val data: ImportSourceData,
        val preview: ImportPreview,
        /** 去重键 → 本地 songId（String 形态，与 Song.id 一致） */
        internal val matchMap: Map<String, String>,
        internal val unresolvedKeys: List<String>
    )

    /** 解析 + 匹配，生成预览（不写库）。 */
    suspend fun prepare(uri: Uri): PreparedImport = withContext(Dispatchers.IO) {
        val data = parser.parse(uri)
        val matcher = SongMatcher(normalizer, musicDao.getAllLocalSongsForImport())

        val matchMap = mutableMapOf<String, String>()
        val unresolved = mutableListOf<String>()
        for ((key, record) in data.songRecords) {
            coroutineContext.ensureActive()
            val hit = matcher.match(record)
            if (hit != null) matchMap[key] = hit.id.toString() else unresolved += key
        }

        val records = data.songRecords.values
        val preview = ImportPreview(
            sourceId = parser.id,
            playlistCount = data.playlists.size,
            songCount = records.size,
            ratedCount = records.count { it.rating > 0 },
            ratingDistribution = records.groupingBy { it.rating }.eachCount(),
            playedCount = records.count { (it.lastPlayedAt ?: 0) > 0 },
            matchedEstimate = matchMap.size,
            matchRate = if (records.isEmpty()) 0f else matchMap.size.toFloat() / records.size
        )
        PreparedImport(data, preview, matchMap, unresolved)
    }

    /** 执行导入（按选项分步写库）。 */
    suspend fun execute(
        prepared: PreparedImport,
        options: ImportOptions,
        onProgress: suspend (ImportProgress) -> Unit = {}
    ): ImportResult = withContext(Dispatchers.IO) {
        val data = prepared.data
        val matchMap = prepared.matchMap

        var playlistsCreated = 0
        var playlistsMerged = 0
        var skippedEmptyPlaylists = 0
        var historyEventsImported = 0
        var engagementImported = 0
        var favoritesImported = 0
        var ratingsSaved = 0

        // ---- 1. 播放列表（合并：同名追加保序去重；无同名 → 新建）----
        if (options.importPlaylists) {
            val existingByName = playlistPreferencesRepository.getPlaylistsOnce()
                .associateBy { it.name }
            data.playlists.forEachIndexed { index, playlist ->
                coroutineContext.ensureActive()
                onProgress(ImportProgress(ImportProgress.Step.PLAYLISTS, index + 1, data.playlists.size))
                // 保持 Poweramp 内顺序，去重
                val songIds = playlist.songKeys.mapNotNull { matchMap[it] }.distinct()
                if (songIds.isEmpty()) {
                    skippedEmptyPlaylists++
                    return@forEachIndexed
                }
                val existing = existingByName[playlist.name]
                if (existing != null) {
                    playlistPreferencesRepository.addSongsToPlaylist(existing.id, songIds)
                    playlistsMerged++
                } else {
                    playlistPreferencesRepository.createPlaylist(playlist.name, songIds = songIds)
                    playlistsCreated++
                }
            }
        }

        // 匹配成功的记录（后续三类写入共用）
        val matchedRecords = data.songRecords.entries
            .mapNotNull { (key, record) -> matchMap[key]?.let { songId -> songId to record } }

        // ---- 2. 播放历史（每首最多 1 条合成事件；N2：durationMs 恒 0）----
        if (options.importHistory) {
            onProgress(ImportProgress(ImportProgress.Step.HISTORY, 0, 1))
            val events = matchedRecords.mapNotNull { (songId, record) ->
                record.lastPlayedAt?.let { playedAt ->
                    PlaybackStatsRepository.PlaybackEvent(
                        songId = songId,
                        timestamp = playedAt,
                        durationMs = 0L
                        // start/end 留空：sanitizeEvent 自动补为 start = end = timestamp
                    )
                }
            }
            if (events.isNotEmpty()) {
                playbackStatsRepository.importEventsFromBackup(
                    events = events,
                    clearExisting = options.replaceMode // 合并模式必须显式传 false
                )
            }
            historyEventsImported = events.size
        }

        // ---- 3. 参与度统计（N2：时长累加 0，不污染本地收听时长）----
        if (options.importEngagement) {
            val statsRecords = matchedRecords.filter { (_, r) ->
                r.playCount > 0 || (r.lastPlayedAt ?: 0) > 0
            }
            if (options.replaceMode) {
                onProgress(ImportProgress(ImportProgress.Step.ENGAGEMENT, 0, 1))
                engagementDao.replaceAll(statsRecords.map { (songId, r) ->
                    SongEngagementEntity(
                        songId = songId,
                        playCount = r.playCount,
                        totalPlayDurationMs = 0L,
                        lastPlayedTimestamp = r.lastPlayedAt ?: 0L
                    )
                })
            } else {
                statsRecords.forEachIndexed { index, (songId, r) ->
                    coroutineContext.ensureActive()
                    engagementDao.mergeEngagement(
                        songId = songId,
                        playCount = r.playCount,
                        durationMs = 0L,
                        lastPlayedAt = r.lastPlayedAt ?: 0L
                    )
                    if (index % 50 == 0) {
                        onProgress(ImportProgress(ImportProgress.Step.ENGAGEMENT, index + 1, statsRecords.size))
                    }
                }
            }
            engagementImported = statsRecords.size
        }

        // ---- 4. 收藏 + 评分（v1.4：一次性 upsert 到 favorites 表）----
        if (options.importFavorites) {
            onProgress(ImportProgress(ImportProgress.Step.FAVORITES, 0, 1))
            val now = System.currentTimeMillis()
            val threshold = options.favoriteRatingThreshold.coerceIn(1, 5)
            // 三态规则（§4.4）：rating == 0 且未收藏 → 不写；合并模式下
            // isFavorite 取「本地已有 OR 达阈值」，避免导入降级本地已有收藏。
            val existingFavorites = favoritesDao.getAllFavoritesOnce()
                .associateBy { it.songId }
            val entities = matchedRecords.mapNotNull { (songId, r) ->
                val id = songId.toLongOrNull() ?: return@mapNotNull null
                if (r.rating <= 0) return@mapNotNull null // Poweramp 未评分 → 不产生无意义行
                val imported = r.rating >= threshold
                val mergedFavorite = imported || (existingFavorites[id]?.isFavorite == true)
                FavoritesEntity(
                    songId = id,
                    isFavorite = mergedFavorite,
                    timestamp = existingFavorites[id]?.timestamp ?: now,
                    rating = r.rating
                )
            }
            if (entities.isNotEmpty()) favoritesDao.insertAll(entities)
            favoritesImported = entities.count { it.isFavorite }
            ratingsSaved = entities.count { it.rating > 0 }
        }

        ImportResult(
            matchedSongs = matchMap.size,
            unresolvedSongs = prepared.unresolvedKeys.size,
            unresolvedExamples = prepared.unresolvedKeys.take(UNRESOLVED_EXAMPLE_LIMIT)
                .map { normalizer.fileName(it) },
            playlistsCreated = playlistsCreated,
            playlistsMerged = playlistsMerged,
            historyEventsImported = historyEventsImported,
            engagementImported = engagementImported,
            favoritesImported = favoritesImported,
            ratingsSaved = ratingsSaved,
            skippedEmptyPlaylists = skippedEmptyPlaylists
        )
    }

    companion object {
        private const val UNRESOLVED_EXAMPLE_LIMIT = 5
    }
}
