package com.theveloper.pixelplay.data.stats

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import com.theveloper.pixelplay.data.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import timber.log.Timber

@Singleton
class PlaybackStatsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val gson = Gson()
    private val historyFile = File(context.filesDir, "playback_history.json")
    private val atomicHistoryFile = AtomicFile(historyFile)
    private val fileLock = Any()
    private var cachedEvents: List<PlaybackEvent>? = null  // guarded by fileLock
    private val eventsType = object : TypeToken<MutableList<PlaybackEvent>>() {}.type
    private val _refreshVersion = MutableStateFlow(0L)
    val refreshFlow: StateFlow<Long> = _refreshVersion.asStateFlow()

    private val sessionGapThresholdMs = TimeUnit.MINUTES.toMillis(30)

    /**
     * 一次播放记录。
     *
     * [durationMs] 是本次播放的**实际收听时长**（本地播放由 ListeningStatsTracker 累加
     * realtime 得到），区间为 `[timestamp - durationMs, timestamp]`。
     *
     * [playCount] 是本条事件代表的**播放次数权重**：
     * - 本地播放恒为 1（每播一次落一条事件，时长也随之累加）；
     * - 第三方导入（Poweramp）只有 `played_times` 总数 + 最后一次播放时间，没有逐次记录，
     *   因此压缩成 1 条事件并令 [playCount] = N，避免伪造 N 个时间戳污染时间线，
     *   同时让「播放次数」统计反映真实次数。
     *
     * 旧版 JSON 无该字段，反序列化后需经 [sanitizeEvent] 归一化为 1（见该函数的注释）。
     */
    data class PlaybackEvent(
        val songId: String,
        val timestamp: Long,
        val durationMs: Long,
        val startTimestamp: Long? = null,
        val endTimestamp: Long? = null,
        val playCount: Int = 1
    ) {
        /** 归一化后的权重：JSON 缺字段或非法值时退化为 1。 */
        val weight: Int
            get() = if (playCount > 0) playCount else 1
    }

    data class PlaybackHistoryEntry(
        val songId: String,
        val timestamp: Long
    )

    data class SongPlaybackSummary(
        val songId: String,
        val title: String,
        val artist: String,
        val albumArtUri: String?,
        val totalDurationMs: Long,
        val playCount: Int
    )

    data class ArtistPlaybackSummary(
        val artist: String,
        val totalDurationMs: Long,
        val playCount: Int,
        val uniqueSongs: Int
    )

    data class GenrePlaybackSummary(
        val genre: String,
        val totalDurationMs: Long,
        val playCount: Int,
        val uniqueArtists: Int
    )

    data class AlbumPlaybackSummary(
        val album: String,
        val albumArtUri: String?,
        val totalDurationMs: Long,
        val playCount: Int,
        val uniqueSongs: Int
    )

    data class TimelineEntry(
        val label: String,
        val totalDurationMs: Long,
        val playCount: Int
    )

    /**
     * 同一首歌的一个连续播放区间。
     *
     * [playCount] 是该区间内累计的播放次数：本地事件每合并一条 +1；
     * 导入的带权重事件合并时按 [PlaybackEvent.weight] 累加（重叠的多段各自计入次数，
     * 但区间只算一份时长）。
     */
    data class PlaybackSegment(
        val songId: String,
        val startMillis: Long,
        val endMillis: Long,
        val playCount: Int = 1
    ) {
        val durationMs: Long
            get() = (endMillis - startMillis).coerceAtLeast(0L)
    }

    /**
     * 一段与歌曲无关的播放区间（跨歌曲合并后的结果）。
     *
     * [playCount] 为该区间内累计的播放次数，用于让时间线 / 会话等按时间切片的统计
     * 与 [PlaybackStatsSummary.totalPlayCount] 保持一致。
     */
    data class PlaybackSpan(
        val startMillis: Long,
        val endMillis: Long,
        val playCount: Int = 1
    ) {
        val durationMs: Long
            get() = (endMillis - startMillis).coerceAtLeast(0L)
    }

    data class DayListeningDistribution(
        val bucketSizeMinutes: Int,
        val buckets: List<DailyListeningBucket>,
        val maxBucketDurationMs: Long,
        val days: List<DailyListeningDay>
    )

    data class DailyListeningBucket(
        val startMinute: Int,
        val endMinuteExclusive: Int,
        val totalDurationMs: Long
    )

    data class DailyListeningDay(
        val date: LocalDate,
        val buckets: List<DailyListeningBucket>,
        val totalDurationMs: Long
    )

    data class PlaybackStatsSummary(
        val range: StatsTimeRange,
        val startTimestamp: Long?,
        val endTimestamp: Long,
        val totalDurationMs: Long,
        val totalPlayCount: Int,
        val uniqueSongs: Int,
        val averageDailyDurationMs: Long,
        val songs: List<SongPlaybackSummary> = emptyList(),
        val topSongs: List<SongPlaybackSummary> = emptyList(),
        val topGenres: List<GenrePlaybackSummary> = emptyList(),
        val timeline: List<TimelineEntry>,
        val topArtists: List<ArtistPlaybackSummary>,
        val topAlbums: List<AlbumPlaybackSummary>,
        val activeDays: Int,
        val longestStreakDays: Int,
        val totalSessions: Int,
        val averageSessionDurationMs: Long,
        val longestSessionDurationMs: Long,
        val averageSessionsPerDay: Double,
        val dayListeningDistribution: DayListeningDistribution?,
        val peakTimeline: TimelineEntry?,
        val peakDayLabel: String?,
        val peakDayDurationMs: Long
    )

    /**
     * 记录一次播放。
     *
     * @param playCount 本条事件代表的播放次数，本地播放恒为 1；第三方导入可传入聚合次数
     *                  （见 [PlaybackEvent.playCount]）。
     */
    suspend fun recordPlayback(
        songId: String,
        durationMs: Long,
        timestamp: Long = System.currentTimeMillis(),
        playCount: Int = 1
    ) = withContext(Dispatchers.IO) {
        if (songId.isBlank()) return@withContext
        val coercedTimestamp = timestamp.coerceAtLeast(0L)
        val coercedDuration = durationMs.coerceAtLeast(0L)
        val start = (coercedTimestamp - coercedDuration).coerceAtLeast(0L)
        val sanitizedEvent = PlaybackEvent(
            songId = songId,
            timestamp = coercedTimestamp,
            durationMs = coercedDuration,
            startTimestamp = start,
            endTimestamp = coercedTimestamp,
            playCount = playCount.coerceAtLeast(1)
        )
        val writeSucceeded = updateEventsAtomically { events ->
            events += sanitizedEvent
            enforceHistoryCountCap(events)
            events
        }
        if (writeSucceeded) {
            notifyStatsChanged()
        }
    }

    suspend fun loadSummary(
        range: StatsTimeRange,
        songs: List<Song>,
        nowMillis: Long = System.currentTimeMillis()
    ): PlaybackStatsSummary = withContext(Dispatchers.IO) {
        val zoneId = ZoneId.systemDefault()
        val allEvents = readEvents()
        buildSummaryFromEvents(
            range = range,
            songs = songs,
            nowMillis = nowMillis,
            allEvents = allEvents,
            zoneId = zoneId
        )
    }

    /**
     * 把「只有最后播放时间戳」的导入事件展开成真实播放区间。
     *
     * 第三方导入（Poweramp）只提供 played_at，事件退化成 start == end 的零长度点。
     * 真实播放区间应为 `[t - 时长, t]`（向过去回溯，而不是向未来延伸）；
     * 带权重 `[playCount] = N` 的事件按 `N × 歌曲时长` 回溯，与本地「播 N 次累加 N 次时长」口径一致。
     *
     * 本地播放事件（start < end）原样返回。
     */
    private fun expandImportedSpan(
        event: PlaybackEvent,
        songMap: Map<String, Song>
    ): PlaybackEvent {
        val start = event.startMillis()
        val rawEnd = event.endMillis()
        if (rawEnd > start) return event
        val songDuration = songMap[event.songId]?.duration?.takeIf { it > 0L } ?: return event
        val expandedDuration = songDuration * event.weight
        return event.copy(
            durationMs = expandedDuration,
            startTimestamp = (rawEnd - expandedDuration).coerceAtLeast(0L),
            endTimestamp = rawEnd
        )
    }

    internal fun buildSummaryFromEvents(
        range: StatsTimeRange,
        songs: List<Song>,
        nowMillis: Long,
        allEvents: List<PlaybackEvent>,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): PlaybackStatsSummary {
        val songMap = songs.associateBy { it.id }
        // 必须先把导入事件展开成真实区间，再算时间边界：
        // StatsTimeRange.ALL 的起点取「最早事件的 start」，而导入事件的原始 start 就是
        // last_played 时间戳；若先算边界，回溯出的 [t - N×时长, t] 会整条落在边界之前被裁掉。
        val expandedEvents = allEvents.map { event -> expandImportedSpan(event, songMap) }
        val (startBound, endBound) = range.resolveBounds(expandedEvents, nowMillis, zoneId)
        val filteredEvents = expandedEvents.mapNotNull { event ->
            val eventStart = event.startMillis()
            val eventEnd = event.endMillis()
            val lowerBound = startBound ?: Long.MIN_VALUE
            if (eventEnd < lowerBound || eventStart > endBound) {
                return@mapNotNull null
            }

            val clippedStart = max(eventStart, lowerBound)
            val clippedEnd = min(eventEnd, endBound)
            val clippedDuration = (clippedEnd - clippedStart).coerceAtLeast(0L)
            if (clippedDuration <= 0L) {
                return@mapNotNull null
            }

            event.copy(
                timestamp = clippedEnd,
                durationMs = clippedDuration,
                startTimestamp = clippedStart,
                endTimestamp = clippedEnd
            )
        }

        val normalizedEvents = filteredEvents

        val segmentsBySong = normalizedEvents
            .groupBy { it.songId }
            .mapValues { (_, eventsForSong) -> mergeSongEvents(eventsForSong) }

        val overallSpans = mergeSpans(
            segmentsBySong.values.flatten().map { PlaybackSpan(it.startMillis, it.endMillis, it.playCount) }
        )

        val effectiveStart = startBound
            ?: overallSpans.minOfOrNull { it.startMillis }
            ?: normalizedEvents.minOfOrNull { it.startMillis() }
            ?: allEvents.minOfOrNull { it.startMillis() }
        val effectiveEnd = overallSpans.maxOfOrNull { it.endMillis } ?: endBound

        val totalDuration = overallSpans.sumOf { it.durationMs }
        // 段已被合并（时长只算一份），次数必须按段内累计的权重求和。
        val totalPlays = segmentsBySong.values.sumOf { segments -> segments.sumOf { it.playCount } }
        val uniqueSongs = segmentsBySong.keys.size

        val allSongs = segmentsBySong
            .mapNotNull { (songId, segmentsForSong) ->
                val song = songMap[songId] ?: return@mapNotNull null
                val title = song.title.takeIf { it.isNotBlank() }
                    ?: song.path.substringAfterLast('/').ifBlank { return@mapNotNull null }
                val artist = song.displayArtist.takeIf { it.isNotBlank() } ?: "Unknown Artist"
                SongPlaybackSummary(
                    songId = songId,
                    title = title,
                    artist = artist,
                    albumArtUri = song.albumArtUriString,
                    totalDurationMs = segmentsForSong.sumOf { it.durationMs },
                    playCount = segmentsForSong.sumOf { it.playCount }
                )
            }
            .sortedWith(
                compareByDescending<SongPlaybackSummary> { it.totalDurationMs }
                    .thenByDescending { it.playCount }
            )
            .take(MAX_SONG_STATS_COUNT)
        val topSongs = allSongs.take(5)

        val topGenres = segmentsBySong.entries
            .groupBy { (songId, _) ->
                val genre = songMap[songId]?.genre
                if (genre.isNullOrBlank()) "Unknown Genre" else genre
            }
            .map { (genre, groupedSongs) ->
                val flattened = groupedSongs.flatMap { it.value }
                val uniqueArtists = groupedSongs
                    .flatMap { (songId, _) ->
                        statsArtistNames(songMap[songId])
                    }
                    .distinctBy { it.normalizedArtistKey() }
                    .size
                GenrePlaybackSummary(
                    genre = genre,
                    totalDurationMs = flattened.sumOf { it.durationMs },
                    playCount = flattened.sumOf { it.playCount },
                    uniqueArtists = uniqueArtists
                )
            }
            .sortedWith(
                compareByDescending<GenrePlaybackSummary> { it.totalDurationMs }
                    .thenByDescending { it.playCount }
            )
            .take(5)

        var daySpan = 1L
        val averageDailyDuration = if (effectiveStart != null) {
            val startInstant = Instant.ofEpochMilli(effectiveStart)
            val endInstant = Instant.ofEpochMilli(effectiveEnd)
            val startDate = startInstant.atZone(zoneId).toLocalDate()
            val endDate = endInstant.atZone(zoneId).toLocalDate()
            daySpan = max(1L, java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1)
            if (daySpan > 0) totalDuration / daySpan else totalDuration
        } else {
            totalDuration
        }

        val daySlices = overallSpans.flatMap { sliceSpanByDay(it, zoneId) }
        val eventsByDay = daySlices.groupBy { it.date }
        val activeDays = eventsByDay.size
        val sortedDays = eventsByDay.keys.sorted()
        var longestStreak = 0
        var currentStreak = 0
        var lastDay: java.time.LocalDate? = null
        sortedDays.forEach { day ->
            if (lastDay == null || day == lastDay.plusDays(1)) {
                currentStreak += 1
            } else {
                currentStreak = 1
            }
            if (currentStreak > longestStreak) {
                longestStreak = currentStreak
            }
            lastDay = day
        }

        val sessions = computeListeningSessions(overallSpans)
        val totalSessions = sessions.size
        val totalSessionDuration = sessions.sumOf { it.totalDuration }
        val averageSessionDuration = if (totalSessions > 0) totalSessionDuration / totalSessions else 0L
        val longestSessionDuration = sessions.maxOfOrNull { it.totalDuration } ?: 0L
        val averageSessionsPerDay = if (daySpan > 0) totalSessions.toDouble() / daySpan else 0.0

        val timelineBuckets = createTimelineBuckets(
            range = range,
            zoneId = zoneId,
            now = Instant.ofEpochMilli(endBound),
            spans = overallSpans,
            fallbackStart = effectiveStart ?: endBound
        )
        val timelineEntries = accumulateTimelineEntries(timelineBuckets, overallSpans)

        val topArtists = segmentsBySong.entries
            .flatMap { (songId, segmentsForSong) ->
                statsArtistNames(songMap[songId]).map { artist ->
                    ArtistSongPlayback(
                        artist = artist,
                        songId = songId,
                        segments = segmentsForSong
                    )
                }
            }
            .groupBy { it.artist }
            .map { (artist, artistSongs) ->
                val flattened = artistSongs.flatMap { it.segments }
                val uniqueSongCount = artistSongs
                    .map { it.songId }
                    .toSet()
                    .size
                ArtistPlaybackSummary(
                    artist = artist,
                    totalDurationMs = flattened.sumOf { it.durationMs },
                    playCount = flattened.sumOf { it.playCount },
                    uniqueSongs = uniqueSongCount
                )
            }
            .sortedWith(
                compareByDescending<ArtistPlaybackSummary> { it.totalDurationMs }
                    .thenByDescending { it.playCount }
            )
            .take(5)

        val topAlbums = segmentsBySong.entries
            .groupBy { (songId, _) ->
                val song = songMap[songId]
                song?.album?.takeIf { it.isNotBlank() } ?: "Unknown Album"
            }
            .map { (album, groupedSongs) ->
                val flattened = groupedSongs.flatMap { it.value }
                val uniqueSongCount = groupedSongs.size
                val firstSong = groupedSongs
                    .asSequence()
                    .mapNotNull { songMap[it.key] }
                    .firstOrNull()
                AlbumPlaybackSummary(
                    album = album,
                    albumArtUri = firstSong?.albumArtUriString,
                    totalDurationMs = flattened.sumOf { it.durationMs },
                    playCount = flattened.sumOf { it.playCount },
                    uniqueSongs = uniqueSongCount
                )
            }
            .sortedWith(
                compareByDescending<AlbumPlaybackSummary> { it.totalDurationMs }
                    .thenByDescending { it.playCount }
            )
            .take(5)

        val peakTimeline = timelineEntries
            .filter { it.totalDurationMs > 0L }
            .maxByOrNull { it.totalDurationMs }

        val durationsByDayOfWeek = daySlices.groupBy { slice ->
            slice.date.dayOfWeek
        }
        val peakDay = durationsByDayOfWeek.maxByOrNull { entry ->
            entry.value.sumOf { it.durationMs }
        }
        val peakDayLabel = peakDay?.key?.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val peakDayDuration = peakDay?.value?.sumOf { it.durationMs } ?: 0L
        val dayListeningDistribution = if (range == StatsTimeRange.DAY || range == StatsTimeRange.WEEK) {
            computeDayListeningDistribution(
                spans = overallSpans,
                zoneId = zoneId,
                range = range,
                startBound = startBound,
                endBound = endBound
            )
        } else null

        return PlaybackStatsSummary(
            range = range,
            startTimestamp = startBound,
            endTimestamp = endBound,
            totalDurationMs = totalDuration,
            totalPlayCount = totalPlays,
            uniqueSongs = uniqueSongs,
            averageDailyDurationMs = averageDailyDuration,
            songs = allSongs,
            topSongs = topSongs,
            timeline = timelineEntries,
            topGenres = topGenres,
            topArtists = topArtists,
            topAlbums = topAlbums,
            activeDays = activeDays,
            longestStreakDays = longestStreak,
            totalSessions = totalSessions,
            averageSessionDurationMs = averageSessionDuration,
            longestSessionDurationMs = longestSessionDuration,
            averageSessionsPerDay = averageSessionsPerDay,
            dayListeningDistribution = dayListeningDistribution,
            peakTimeline = peakTimeline,
            peakDayLabel = peakDayLabel,
            peakDayDurationMs = peakDayDuration
        )
    }

    suspend fun exportEventsForBackup(): List<PlaybackEvent> = withContext(Dispatchers.IO) {
        readEvents().map { event -> sanitizeEvent(event) }
    }

    suspend fun loadPlaybackHistory(limit: Int = DEFAULT_PLAYBACK_HISTORY_LIMIT): List<PlaybackHistoryEntry> = withContext(Dispatchers.IO) {
        if (limit <= 0) return@withContext emptyList()
        val safeLimit = limit.coerceAtMost(MAX_PLAYBACK_HISTORY_LIMIT)
        readEvents()
            .asSequence()
            .sortedByDescending { event -> event.timestamp }
            .take(safeLimit)
            .map { event ->
                PlaybackHistoryEntry(
                    songId = event.songId,
                    timestamp = event.timestamp.coerceAtLeast(0L)
                )
            }
            .toList()
    }

    /**
     * @return 写入是否成功。返回 false 表示 AtomicFile 写入失败，调用方不应把 events.size
     *         当作「已导入」上报（此前无返回值，导致导入结果页可能显示假成功数）。
     */
    suspend fun importEventsFromBackup(
        events: List<PlaybackEvent>,
        clearExisting: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        val writeSucceeded = updateEventsAtomically { existingEvents ->
            val base = if (clearExisting) {
                emptyList()
            } else {
                existingEvents
            }
            val merged = (base + events)
                .map { event -> sanitizeEvent(event) }
                // 去重键相同（同一首歌、同一区间）时保留权重更大的一条：
                // 重新导入 Poweramp 备份时，旧的无权重事件会被带次数的事件顶掉，
                // 否则 distinctBy 保留先出现的旧事件，重导入将永远不生效。
                .groupBy { event ->
                    "${event.songId}:${event.startMillis()}:${event.endMillis()}:${event.durationMs}"
                }
                .values
                .map { group -> group.maxBy { event -> event.playCount } }
                .sortedBy { event -> event.timestamp }
                .toMutableList()
            enforceHistoryCountCap(merged)
            merged
        }
        if (writeSucceeded) {
            notifyStatsChanged()
        }
        writeSucceeded
    }

    fun requestRefresh() {
        notifyStatsChanged()
    }

    /** D13：条数上限裁剪——超出 [MAX_HISTORY_EVENT_COUNT] 时丢弃最旧事件（替代原 730 天时间裁剪）。 */
    private fun enforceHistoryCountCap(events: MutableList<PlaybackEvent>) {
        if (events.size <= MAX_HISTORY_EVENT_COUNT) return
        val kept = events.sortedBy { it.endMillis() }.takeLast(MAX_HISTORY_EVENT_COUNT)
        events.clear()
        events.addAll(kept)
    }

    private fun readEvents(): List<PlaybackEvent> {
        synchronized(fileLock) { cachedEvents }?.let { return it }
        val raw = synchronized(fileLock) { readRawHistoryLocked() }
        return parseEvents(raw).also { parsed ->
            synchronized(fileLock) { if (cachedEvents == null) cachedEvents = parsed }
        }
    }

    private fun readRawHistoryLocked(): String? {
        return runCatching {
            atomicHistoryFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
            .onFailure { throwable ->
                if (throwable !is FileNotFoundException) {
                    Timber.e(throwable, "Failed reading playback history")
                }
            }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun parseEvents(raw: String?): MutableList<PlaybackEvent> {
        if (raw.isNullOrBlank()) {
            return mutableListOf()
        }

        return runCatching {
            val element = gson.fromJson(raw, JsonElement::class.java)
            parseElement(element)
        }.getOrElse { throwable ->
            Timber.e(throwable, "Failed parsing playback history")
            mutableListOf()
        }
    }

    private fun parseElement(element: JsonElement?): MutableList<PlaybackEvent> {
        if (element == null || element.isJsonNull) return mutableListOf()
        if (element.isJsonArray) {
            val parsed: MutableList<PlaybackEvent> = gson.fromJson(element, eventsType)
            return parsed.mapTo(mutableListOf()) { sanitizeEvent(it) }
        }
        return mutableListOf()
    }

    private fun sanitizeEvent(event: PlaybackEvent): PlaybackEvent {
        val safeDuration = event.durationMs.coerceAtLeast(0L)
        val safeEnd = (event.endTimestamp ?: event.timestamp).coerceAtLeast(0L)
        val safeStart = when {
            event.startTimestamp != null -> event.startTimestamp.coerceIn(0L, safeEnd)
            safeDuration > 0L -> (safeEnd - safeDuration).coerceAtLeast(0L)
            else -> safeEnd
        }
        val normalizedDuration = (safeEnd - safeStart).coerceAtLeast(0L)
        val finalDuration = when {
            event.startTimestamp == null && safeDuration in 1 until normalizedDuration -> safeDuration
            else -> normalizedDuration
        }
        val finalStart = (safeEnd - finalDuration).coerceAtLeast(0L)
        return event.copy(
            songId = event.songId,
            timestamp = safeEnd,
            durationMs = finalDuration,
            startTimestamp = finalStart,
            endTimestamp = safeEnd,
            // 旧版 JSON 没有 playCount 字段。Kotlin 全默认参数会生成无参构造，Gson 走该构造时
            // 默认值 1 生效；但 Gson 仍可能用 Unsafe 分配（此时 Int 落 0），因此这里统一兜底为 1。
            playCount = event.weight
        )
    }

    private fun mergeSongEvents(events: List<PlaybackEvent>): List<PlaybackSegment> {
        if (events.isEmpty()) return emptyList()
        val sorted = events.sortedBy { it.startMillis() }
        val songId = sorted.first().songId
        val segments = mutableListOf<PlaybackSegment>()
        var currentStart = sorted.first().startMillis()
        var currentEnd = sorted.first().endMillis()
        // 区间会被合并成一份时长，但次数必须逐条累加（含带权重事件）。
        var currentPlayCount = sorted.first().weight
        for (index in 1 until sorted.size) {
            val event = sorted[index]
            val start = event.startMillis()
            val end = event.endMillis()
            if (start <= currentEnd + SEGMENT_JOIN_TOLERANCE_MS) {
                currentEnd = max(currentEnd, end)
                currentPlayCount += event.weight
            } else {
                segments += PlaybackSegment(songId, currentStart, currentEnd, currentPlayCount)
                currentStart = start
                currentEnd = end
                currentPlayCount = event.weight
            }
        }
        segments += PlaybackSegment(songId, currentStart, currentEnd, currentPlayCount)
        return segments
    }

    private fun mergeSpans(spans: List<PlaybackSpan>): List<PlaybackSpan> {
        if (spans.isEmpty()) return emptyList()
        val sorted = spans.sortedBy { it.startMillis }
        val merged = mutableListOf<PlaybackSpan>()
        var currentStart = sorted.first().startMillis
        var currentEnd = sorted.first().endMillis
        // 同 [mergeSongEvents]：区间合并成一份时长，次数逐段累加。
        var currentPlayCount = sorted.first().playCount
        for (index in 1 until sorted.size) {
            val span = sorted[index]
            val start = span.startMillis
            val end = span.endMillis
            if (start <= currentEnd + SEGMENT_JOIN_TOLERANCE_MS) {
                currentEnd = max(currentEnd, end)
                currentPlayCount += span.playCount
            } else {
                merged += PlaybackSpan(currentStart, currentEnd, currentPlayCount)
                currentStart = start
                currentEnd = end
                currentPlayCount = span.playCount
            }
        }
        merged += PlaybackSpan(currentStart, currentEnd, currentPlayCount)
        return merged
    }

    private data class DaySlice(
        val date: LocalDate,
        val durationMs: Long
    )

    private data class ArtistSongPlayback(
        val artist: String,
        val songId: String,
        val segments: List<PlaybackSegment>
    )

    private fun statsArtistNames(song: Song?): List<String> {
        if (song == null) return listOf(UNKNOWN_ARTIST)

        val separatedArtists = song.artists
            .sortedByDescending { it.isPrimary }
            .map { it.name.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.normalizedArtistKey() }
        if (separatedArtists.isNotEmpty()) {
            return separatedArtists
        }

        val fallbackArtist = song.displayArtist.trim()
        return listOf(fallbackArtist.ifBlank { UNKNOWN_ARTIST })
    }

    private fun String.normalizedArtistKey(): String = trim().lowercase(Locale.ROOT)

    private fun sliceSpanByDay(span: PlaybackSpan, zoneId: ZoneId): List<DaySlice> {
        if (span.durationMs <= 0L) return emptyList()
        val slices = mutableListOf<DaySlice>()
        var cursor = span.startMillis
        val end = span.endMillis
        while (cursor < end) {
            val zoned = Instant.ofEpochMilli(cursor).atZone(zoneId)
            val dayStart = zoned.toLocalDate().atStartOfDay(zoneId).toInstant().toEpochMilli()
            val nextDayStart = zoned.toLocalDate().plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val sliceEnd = min(end, nextDayStart)
            val sliceDuration = (sliceEnd - cursor).coerceAtLeast(0L)
            if (sliceDuration > 0L) {
                slices += DaySlice(zoned.toLocalDate(), sliceDuration)
            }
            cursor = sliceEnd
        }
        return slices
    }

    private fun computeDayListeningDistribution(
        spans: List<PlaybackSpan>,
        zoneId: ZoneId,
        range: StatsTimeRange,
        startBound: Long?,
        endBound: Long,
        bucketSizeMinutes: Int = 5
    ): DayListeningDistribution? {
        if (spans.isEmpty()) return null
        val bucketDurationMs = TimeUnit.MINUTES.toMillis(bucketSizeMinutes.toLong())
        val minutesPerDay = TimeUnit.DAYS.toMinutes(1)
        val bucketCount = (minutesPerDay / bucketSizeMinutes).toInt().coerceAtLeast(1)
        val totals = LongArray(bucketCount)
        val totalsByDay = mutableMapOf<LocalDate, LongArray>()
        spans.forEach { span ->
            var cursor = span.startMillis
            val end = span.endMillis
            while (cursor < end) {
                val zoned = Instant.ofEpochMilli(cursor).atZone(zoneId)
                val day = zoned.toLocalDate()
                val dayStart = day.atStartOfDay(zoneId).toInstant().toEpochMilli()
                var bucketIndex = ((cursor - dayStart) / bucketDurationMs).toInt()
                if (bucketIndex >= bucketCount) {
                    bucketIndex = bucketCount - 1
                } else if (bucketIndex < 0) {
                    bucketIndex = 0
                }
                val bucketStart = dayStart + bucketIndex * bucketDurationMs
                val bucketEnd = min(end, bucketStart + bucketDurationMs)
                val contribution = (bucketEnd - cursor).coerceAtLeast(0L)
                if (contribution > 0L) {
                    totals[bucketIndex] += contribution
                    val dayTotals = totalsByDay.getOrPut(day) { LongArray(bucketCount) }
                    dayTotals[bucketIndex] += contribution
                }
                cursor = if (bucketEnd > cursor) bucketEnd else end
            }
        }
        val buckets = buildList {
            for (index in 0 until bucketCount) {
                val durationMs = totals[index]
                if (durationMs > 0L) {
                    add(
                        DailyListeningBucket(
                            startMinute = index * bucketSizeMinutes,
                            endMinuteExclusive = (index + 1) * bucketSizeMinutes,
                            totalDurationMs = durationMs
                        )
                    )
                }
            }
        }
        if (buckets.isEmpty()) return null
        val maxBucketDuration = buckets.maxOf { it.totalDurationMs }.coerceAtLeast(0L)

        val daySequence: List<LocalDate> = when (range) {
            StatsTimeRange.DAY -> {
                val anchor = startBound?.let { Instant.ofEpochMilli(it) }
                    ?: spans.minOfOrNull { Instant.ofEpochMilli(it.startMillis) }
                    ?: Instant.ofEpochMilli(endBound)
                listOf(anchor.atZone(zoneId).toLocalDate())
            }
            StatsTimeRange.WEEK -> {
                val anchor = startBound?.let { Instant.ofEpochMilli(it) }
                    ?: spans.minOfOrNull { Instant.ofEpochMilli(it.startMillis) }
                    ?: Instant.ofEpochMilli(endBound)
                val startDate = anchor.atZone(zoneId).toLocalDate()
                (0 until 7).map { offset -> startDate.plusDays(offset.toLong()) }
            }
            else -> totalsByDay.keys.sorted()
        }

        val days = daySequence.map { date ->
            val bucketTotals = totalsByDay[date]
            val dayBuckets = buildList {
                if (bucketTotals != null) {
                    for (index in 0 until bucketCount) {
                        val duration = bucketTotals[index]
                        if (duration > 0L) {
                            add(
                                DailyListeningBucket(
                                    startMinute = index * bucketSizeMinutes,
                                    endMinuteExclusive = (index + 1) * bucketSizeMinutes,
                                    totalDurationMs = duration
                                )
                            )
                        }
                    }
                }
            }
            val totalDuration = bucketTotals?.sumOf { it } ?: 0L
            DailyListeningDay(
                date = date,
                buckets = dayBuckets,
                totalDurationMs = totalDuration
            )
        }

        return DayListeningDistribution(
            bucketSizeMinutes = bucketSizeMinutes,
            buckets = buckets,
            maxBucketDurationMs = maxBucketDuration,
            days = days
        )
    }

    private data class ListeningSessionAggregate(
        var start: Long,
        var end: Long,
        var totalDuration: Long,
        var playCount: Int
    )

    private fun computeListeningSessions(spans: List<PlaybackSpan>): List<ListeningSessionAggregate> {
        if (spans.isEmpty()) return emptyList()
        val sorted = spans.sortedBy { it.startMillis }
        val sessions = mutableListOf<ListeningSessionAggregate>()

        var current = ListeningSessionAggregate(
            start = sorted.first().startMillis,
            end = sorted.first().endMillis,
            totalDuration = sorted.first().durationMs,
            playCount = sorted.first().playCount
        )

        for (index in 1 until sorted.size) {
            val span = sorted[index]
            val spanStart = span.startMillis
            val spanEnd = span.endMillis
            val gap = spanStart - current.end
            if (gap <= sessionGapThresholdMs) {
                current.end = max(current.end, spanEnd)
                current.totalDuration += span.durationMs
                current.playCount += span.playCount
            } else {
                sessions += current
                current = ListeningSessionAggregate(
                    start = spanStart,
                    end = spanEnd,
                    totalDuration = span.durationMs,
                    playCount = span.playCount
                )
            }
        }

        sessions += current
        return sessions
    }

    private fun updateEventsAtomically(
        transform: (MutableList<PlaybackEvent>) -> MutableList<PlaybackEvent>
    ): Boolean {
        repeat(MAX_FILE_UPDATE_RETRIES) {
            val rawSnapshot = synchronized(fileLock) { readRawHistoryLocked() }
            val updatedEvents = transform(parseEvents(rawSnapshot))
            val payload = serializeEvents(updatedEvents)

            val writeSucceeded = synchronized(fileLock) {
                val latestRaw = readRawHistoryLocked()
                if (latestRaw != rawSnapshot) {
                    return@synchronized false
                }
                val result = writePayloadLocked(payload)
                if (result) cachedEvents = null
                result
            }
            if (writeSucceeded) {
                return true
            }
        }

        val fallbackRawSnapshot = synchronized(fileLock) { readRawHistoryLocked() }
        val payload = serializeEvents(transform(parseEvents(fallbackRawSnapshot)))
        return synchronized(fileLock) {
            val result = writePayloadLocked(payload)
            if (result) cachedEvents = null
            result
        }
    }

    private fun serializeEvents(events: List<PlaybackEvent>): ByteArray {
        val sanitized = events.map { sanitizeEvent(it) }
        return gson.toJson(sanitized).toByteArray(Charsets.UTF_8)
    }

    private fun writePayloadLocked(payload: ByteArray): Boolean {
        var outputStream: FileOutputStream? = null
        return runCatching {
            val parent = historyFile.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                Timber.w("Unable to ensure playback history directory: ${parent.absolutePath}")
            }
            outputStream = atomicHistoryFile.startWrite()
            outputStream?.write(payload)
            outputStream?.fd?.sync()
            outputStream?.let { atomicHistoryFile.finishWrite(it) }
            outputStream = null
            true
        }.onFailure { throwable ->
            outputStream?.let { stream -> atomicHistoryFile.failWrite(stream) }
            Timber.e(throwable, "Failed to persist playback history")
        }.getOrDefault(false)
    }

    private fun accumulateTimelineEntries(
        buckets: List<TimelineBucket>,
        spans: List<PlaybackSpan>
    ): List<TimelineEntry> {
        if (buckets.isEmpty()) return emptyList()
        val durationByBucket = LongArray(buckets.size)
        val playCountByBucket = DoubleArray(buckets.size)
        spans.forEach { span ->
            val spanStart = span.startMillis
            val spanEnd = span.endMillis
            val spanDuration = span.durationMs
            if (spanDuration <= 0L) return@forEach
            buckets.forEachIndexed { index, bucket ->
                val bucketEndExclusive = if (bucket.inclusiveEnd) bucket.endMillis + 1 else bucket.endMillis
                val overlapStart = max(spanStart, bucket.startMillis)
                val overlapEnd = min(spanEnd, bucketEndExclusive)
                val overlap = (overlapEnd - overlapStart).coerceAtLeast(0L)
                if (overlap > 0) {
                    durationByBucket[index] += overlap
                    // 按区间落在桶内的时长占比折算次数；带权重的导入事件（playCount = N）
                    // 会把它代表的 N 次播放一并分摊到覆盖到的桶里。
                    playCountByBucket[index] +=
                        span.playCount * (overlap.toDouble() / spanDuration.toDouble())
                }
            }
        }
        return buckets.mapIndexed { index, bucket ->
            TimelineEntry(
                label = bucket.label,
                totalDurationMs = durationByBucket[index],
                playCount = playCountByBucket[index].roundToInt()
            )
        }
    }

    private fun createTimelineBuckets(
        range: StatsTimeRange,
        zoneId: ZoneId,
        now: Instant,
        spans: List<PlaybackSpan>,
        fallbackStart: Long
    ): List<TimelineBucket> {
        return when (range) {
            StatsTimeRange.DAY -> createDayBuckets(zoneId, now)
            StatsTimeRange.WEEK -> createWeekBuckets(zoneId, now)
            StatsTimeRange.MONTH -> createMonthBuckets(zoneId, now)
            StatsTimeRange.YEAR -> createYearBuckets(zoneId, now)
            StatsTimeRange.ALL -> createAllTimeBuckets(zoneId, spans, fallbackStart, now)
        }
    }

    private fun createDayBuckets(zoneId: ZoneId, now: Instant): List<TimelineBucket> {
        val dayStart = now.atZone(zoneId).toLocalDate().atStartOfDay(zoneId).toInstant()
        val formatter = DateTimeFormatter.ofPattern("ha", Locale.US)
        return (0 until 6).map { index ->
            val bucketStart = dayStart.plus(Duration.ofHours((index * 4).toLong()))
            val bucketEnd = bucketStart.plus(Duration.ofHours(4))
            val label = formatter.format(bucketStart.atZone(zoneId)).lowercase(Locale.US)
            TimelineBucket(
                label = label,
                startMillis = bucketStart.toEpochMilli(),
                endMillis = bucketEnd.toEpochMilli(),
                inclusiveEnd = false
            )
        }
    }

    private fun createWeekBuckets(zoneId: ZoneId, now: Instant): List<TimelineBucket> {
        val startOfWeek = now.atZone(zoneId)
            .toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return (0 until 7).map { index ->
            val day = startOfWeek.plusDays(index.toLong())
            val start = day.atStartOfDay(zoneId).toInstant()
            val end = day.plusDays(1).atStartOfDay(zoneId).toInstant()
            TimelineBucket(
                label = day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                startMillis = start.toEpochMilli(),
                endMillis = end.toEpochMilli(),
                inclusiveEnd = false
            )
        }
    }

    private fun createMonthBuckets(zoneId: ZoneId, now: Instant): List<TimelineBucket> {
        val yearMonth = YearMonth.from(now.atZone(zoneId))
        val daysInMonth = yearMonth.lengthOfMonth()
        val bucketCount = 4
        return buildList {
            repeat(bucketCount) { index ->
                val startDay = index * 7 + 1
                if (startDay > daysInMonth) {
                    return@repeat
                }
                val endDay = if (index == bucketCount - 1) {
                    daysInMonth
                } else {
                    minOf(startDay + 6, daysInMonth)
                }
                val start = yearMonth.atDay(startDay).atStartOfDay(zoneId).toInstant()
                val end = yearMonth.atDay(endDay).plusDays(1).atStartOfDay(zoneId).toInstant()
                add(
                    TimelineBucket(
                        label = context.getString(com.theveloper.pixelplay.R.string.stats_week_label, index + 1),
                        startMillis = start.toEpochMilli(),
                        endMillis = end.toEpochMilli(),
                        inclusiveEnd = false
                    )
                )
            }
        }
    }

    private fun createYearBuckets(zoneId: ZoneId, now: Instant): List<TimelineBucket> {
        val year = Year.from(now.atZone(zoneId))
        return (1..12).map { monthIndex ->
            val start = year.atMonth(monthIndex).atDay(1).atStartOfDay(zoneId).toInstant()
            val end = year.atMonth(monthIndex).atEndOfMonth().plusDays(1).atStartOfDay(zoneId).toInstant()
            TimelineBucket(
                label = year.atMonth(monthIndex).month.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                startMillis = start.toEpochMilli(),
                endMillis = end.toEpochMilli(),
                inclusiveEnd = false
            )
        }
    }

    private fun createAllTimeBuckets(
        zoneId: ZoneId,
        spans: List<PlaybackSpan>,
        fallbackStart: Long,
        now: Instant
    ): List<TimelineBucket> {
        val allSpans = if (spans.isEmpty()) listOf(PlaybackSpan(fallbackStart, fallbackStart)) else spans
        val minTimestamp = allSpans.minOfOrNull { it.startMillis } ?: fallbackStart
        val maxTimestamp = allSpans.maxOfOrNull { it.endMillis } ?: now.toEpochMilli()
        val startYear = Instant.ofEpochMilli(minTimestamp).atZone(zoneId).year
        val endYear = Instant.ofEpochMilli(maxTimestamp).atZone(zoneId).year
        if (startYear > endYear) return emptyList()
        return (startYear..endYear).map { yearValue ->
            val year = Year.of(yearValue)
            val start = year.atDay(1).atStartOfDay(zoneId).toInstant()
            val end = year.plusYears(1).atDay(1).atStartOfDay(zoneId).toInstant()
            TimelineBucket(
                label = yearValue.toString(),
                startMillis = start.toEpochMilli(),
                endMillis = end.toEpochMilli(),
                inclusiveEnd = false
            )
        }
    }

    private fun StatsTimeRange.resolveBounds(
        events: List<PlaybackEvent>,
        nowMillis: Long,
        zoneId: ZoneId
    ): Pair<Long?, Long> {
        val nowInstant = Instant.ofEpochMilli(nowMillis)
        return when (this) {
            StatsTimeRange.DAY -> {
                val start = nowInstant.atZone(zoneId).toLocalDate().atStartOfDay(zoneId).toInstant().toEpochMilli()
                start to nowMillis
            }
            StatsTimeRange.WEEK -> {
                val start = nowInstant.atZone(zoneId)
                    .toLocalDate()
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
                start to nowMillis
            }
            StatsTimeRange.MONTH -> {
                val start = YearMonth.from(nowInstant.atZone(zoneId))
                    .atDay(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
                start to nowMillis
            }
            StatsTimeRange.YEAR -> {
                val start = nowInstant.atZone(zoneId)
                    .toLocalDate()
                    .withDayOfYear(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
                start to nowMillis
            }
            StatsTimeRange.ALL -> {
                val start = events.minOfOrNull { it.startMillis() }
                start to nowMillis
            }
        }
    }

    private data class TimelineBucket(
        val label: String,
        val startMillis: Long,
        val endMillis: Long,
        val inclusiveEnd: Boolean
    )

    private fun PlaybackEvent.startMillis(): Long {
        val end = (endTimestamp ?: timestamp).coerceAtLeast(0L)
        val inferredStart = (startTimestamp ?: (end - durationMs)).coerceAtLeast(0L)
        return min(inferredStart, end)
    }

    private fun PlaybackEvent.endMillis(): Long {
        val end = (endTimestamp ?: timestamp).coerceAtLeast(0L)
        val start = startMillis()
        return max(end, start)
    }

    private fun notifyStatsChanged() {
        _refreshVersion.update { current ->
            if (current == Long.MAX_VALUE) {
                1L
            } else {
                current + 1L
            }
        }
    }

    companion object {
        private const val DEFAULT_PLAYBACK_HISTORY_LIMIT = 500
        private const val MAX_PLAYBACK_HISTORY_LIMIT = 5_000
        private const val MAX_FILE_UPDATE_RETRIES = 3
        private const val UNKNOWN_ARTIST = "Unknown Artist"
        // D13：不再按时间（730 天）裁剪历史——导入的老数据（如 Poweramp played_at）会被误删。
        // 改为条数上限：超出时丢弃最旧事件。
        private const val MAX_HISTORY_EVENT_COUNT = 20_000
        private const val SEGMENT_JOIN_TOLERANCE_MS = 0L
        private const val MAX_SONG_STATS_COUNT = 100
    }
}

enum class StatsTimeRange(val displayName: String) {
    DAY("Today"),
    WEEK("Week to Date"),
    MONTH("Month to Date"),
    YEAR("Year to Date"),
    ALL("All Time")
}
