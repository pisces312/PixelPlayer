package com.theveloper.pixelplay.data.stats

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.model.ArtistRef
import com.theveloper.pixelplay.data.model.Song
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import io.mockk.every
import io.mockk.mockk
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class PlaybackStatsRepositoryTest {

    @Test
    fun `loadSummary excludes event that only touches the start boundary`() = runTest {
        val repository = createRepository()
        val zoneId = ZoneId.systemDefault()
        val now = LocalDate.of(2026, 4, 10)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()

        val boundaryTouchingEvent = PlaybackStatsRepository.PlaybackEvent(
            songId = "song-1",
            timestamp = now,
            durationMs = 10_000L,
            startTimestamp = now - 10_000L,
            endTimestamp = now
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.DAY,
            songs = listOf(song("song-1")),
            allEvents = listOf(boundaryTouchingEvent),
            nowMillis = now
        )

        assertThat(summary.totalDurationMs).isEqualTo(0L)
        assertThat(summary.totalPlayCount).isEqualTo(0)
    }

    @Test
    fun `loadSummary preserves playback longer than track duration`() = runTest {
        val repository = createRepository()
        val zoneId = ZoneId.systemDefault()
        val start = LocalDate.of(2026, 4, 10)
            .atTime(10, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        val listenedMs = TimeUnit.MINUTES.toMillis(15)
        val event = PlaybackStatsRepository.PlaybackEvent(
            songId = "song-1",
            timestamp = start + listenedMs,
            durationMs = listenedMs,
            startTimestamp = start,
            endTimestamp = start + listenedMs
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.DAY,
            songs = listOf(song("song-1", durationMs = TimeUnit.MINUTES.toMillis(3))),
            allEvents = listOf(event),
            nowMillis = start + listenedMs + 1_000L
        )

        assertThat(summary.totalDurationMs).isEqualTo(listenedMs)
        assertThat(summary.totalPlayCount).isEqualTo(1)
        assertThat(summary.songs.single().totalDurationMs).isEqualTo(listenedMs)
    }

    @Test
    fun `loadSummary does not count short gaps between spans as listened time`() = runTest {
        val repository = createRepository()
        val zoneId = ZoneId.systemDefault()
        val start = LocalDate.of(2026, 4, 10)
            .atTime(12, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        val firstDurationMs = 10_000L
        val secondDurationMs = 10_000L
        val gapMs = 1_000L
        val events = listOf(
            PlaybackStatsRepository.PlaybackEvent(
                songId = "song-1",
                timestamp = start + firstDurationMs,
                durationMs = firstDurationMs,
                startTimestamp = start,
                endTimestamp = start + firstDurationMs
            ),
            PlaybackStatsRepository.PlaybackEvent(
                songId = "song-2",
                timestamp = start + firstDurationMs + gapMs + secondDurationMs,
                durationMs = secondDurationMs,
                startTimestamp = start + firstDurationMs + gapMs,
                endTimestamp = start + firstDurationMs + gapMs + secondDurationMs
            )
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.DAY,
            songs = listOf(song("song-1"), song("song-2")),
            allEvents = events,
            nowMillis = start + firstDurationMs + gapMs + secondDurationMs + 1_000L
        )

        assertThat(summary.totalDurationMs).isEqualTo(firstDurationMs + secondDurationMs)
        assertThat(summary.totalPlayCount).isEqualTo(2)
    }

    @Test
    fun `buildSummaryFromEvents uses event spans without filesystem persistence`() = runTest {
        val repository = createRepository()
        val start = LocalDate.of(2026, 4, 10)
            .atTime(9, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val durationMs = 30_000L
        val events = listOf(
            PlaybackStatsRepository.PlaybackEvent(
                songId = "song-1",
                timestamp = start + durationMs,
                durationMs = durationMs,
                startTimestamp = start,
                endTimestamp = start + durationMs
            )
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.DAY,
            songs = listOf(song("song-1")),
            allEvents = events,
            nowMillis = start + durationMs + 1_000L
        )

        assertThat(summary.totalDurationMs).isEqualTo(durationMs)
        assertThat(summary.uniqueSongs).isEqualTo(1)
    }

    @Test
    fun `loadSummary separates multi artist playback in top artists`() = runTest {
        val repository = createRepository()
        val zoneId = ZoneId.systemDefault()
        val start = LocalDate.of(2026, 4, 10)
            .atTime(14, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        val durationMs = 60_000L
        val event = PlaybackStatsRepository.PlaybackEvent(
            songId = "song-1",
            timestamp = start + durationMs,
            durationMs = durationMs,
            startTimestamp = start,
            endTimestamp = start + durationMs
        )
        val collaboration = song(
            songId = "song-1",
            artist = "Artist A",
            artists = listOf(
                ArtistRef(id = 1L, name = "Artist A", isPrimary = true),
                ArtistRef(id = 2L, name = "Artist B", isPrimary = false)
            )
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.DAY,
            songs = listOf(collaboration),
            allEvents = listOf(event),
            nowMillis = start + durationMs + 1_000L
        )

        assertThat(summary.topArtists.map { it.artist })
            .containsExactly("Artist A", "Artist B")
            .inOrder()
        summary.topArtists.forEach { artist ->
            assertThat(artist.totalDurationMs).isEqualTo(durationMs)
            assertThat(artist.playCount).isEqualTo(1)
            assertThat(artist.uniqueSongs).isEqualTo(1)
        }
    }

    @Test
    fun `loadSummary counts separated artists in genre uniqueness`() = runTest {
        val repository = createRepository()
        val zoneId = ZoneId.systemDefault()
        val start = LocalDate.of(2026, 4, 10)
            .atTime(15, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        val durationMs = 30_000L
        val event = PlaybackStatsRepository.PlaybackEvent(
            songId = "song-1",
            timestamp = start + durationMs,
            durationMs = durationMs,
            startTimestamp = start,
            endTimestamp = start + durationMs
        )
        val collaboration = song(
            songId = "song-1",
            artist = "Artist A",
            artists = listOf(
                ArtistRef(id = 1L, name = "Artist A", isPrimary = true),
                ArtistRef(id = 2L, name = "Artist B", isPrimary = false)
            ),
            genre = "Pop"
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.DAY,
            songs = listOf(collaboration),
            allEvents = listOf(event),
            nowMillis = start + durationMs + 1_000L
        )

        assertThat(summary.topGenres.single().uniqueArtists).isEqualTo(2)
    }

    @Test
    fun `zero duration imported events are counted using library song duration`() = runTest {
        val repository = createRepository()
        val playedAt = LocalDate.of(2026, 4, 10)
            .atTime(9, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val songDuration = 4 * 60 * 1000L

        // Poweramp 导入合成的事件：只有 last_played 时间戳，durationMs=0，start/end 为 null。
        val importedEvent = PlaybackStatsRepository.PlaybackEvent(
            songId = "song-1",
            timestamp = playedAt,
            durationMs = 0L
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.ALL,
            songs = listOf(song("song-1", durationMs = songDuration)),
            allEvents = listOf(importedEvent),
            nowMillis = playedAt + songDuration + 60_000L
        )

        // 修复前：uniqueSongs==0、totalDurationMs==0（事件被 clippedDuration<=0 整条丢弃），
        // 导致听歌统计看不到导入历史（真机复现：All Time 只剩安装后的本地播放）。
        // 修复后：用曲库歌曲时长兜底，应计 1 首、1 次、时长=songDuration。
        assertThat(summary.uniqueSongs).isEqualTo(1)
        assertThat(summary.totalPlayCount).isEqualTo(1)
        assertThat(summary.totalDurationMs).isEqualTo(songDuration)
    }

    @Test
    fun `weighted imported events expand play count and duration by N`() = runTest {
        val repository = createRepository()
        val playedAt = LocalDate.of(2026, 4, 10)
            .atTime(9, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val songDuration = 4 * 60 * 1000L
        val playCount = 5

        // Poweramp 只有 played_times + played_at，导入压缩成 1 条带权重事件：
        // 次数应为 N，总时长与本地「播 N 次」口径一致，也应为 N × 歌曲时长。
        val importedEvent = PlaybackStatsRepository.PlaybackEvent(
            songId = "song-1",
            timestamp = playedAt,
            durationMs = 0L,
            playCount = playCount
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.ALL,
            songs = listOf(song("song-1", durationMs = songDuration)),
            allEvents = listOf(importedEvent),
            nowMillis = playedAt + songDuration * playCount + 60_000L
        )

        assertThat(summary.uniqueSongs).isEqualTo(1)
        assertThat(summary.totalPlayCount).isEqualTo(playCount)
        assertThat(summary.totalDurationMs).isEqualTo(songDuration * playCount)
        assertThat(summary.songs.single().playCount).isEqualTo(playCount)
        // 平均每次时长应回到歌曲时长，而不是「1 次时长 / N 次」。
        assertThat(summary.songs.single().totalDurationMs / playCount).isEqualTo(songDuration)
    }

    @Test
    fun `weighted events merged into one span still count every play`() = runTest {
        val repository = createRepository()
        val start = LocalDate.of(2026, 4, 10)
            .atTime(9, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val spanMs = 60_000L

        // 两条带权重的事件区间完全相同（不同来源合并导入），合并成一段后
        // 时长只算一份，但次数必须累加为 N1 + N2。
        val events = listOf(
            PlaybackStatsRepository.PlaybackEvent(
                songId = "song-1",
                timestamp = start + spanMs,
                durationMs = spanMs,
                startTimestamp = start,
                endTimestamp = start + spanMs,
                playCount = 3
            ),
            PlaybackStatsRepository.PlaybackEvent(
                songId = "song-1",
                timestamp = start + spanMs,
                durationMs = spanMs,
                startTimestamp = start,
                endTimestamp = start + spanMs,
                playCount = 4
            )
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.ALL,
            songs = listOf(song("song-1")),
            allEvents = events,
            nowMillis = start + spanMs + 60_000L
        )

        assertThat(summary.totalDurationMs).isEqualTo(spanMs)
        assertThat(summary.totalPlayCount).isEqualTo(7)
    }

    @Test
    fun `re-importing weighted events upgrades the legacy weight-less ones`() = runTest {
        val repository = createRepository()
        val start = LocalDate.of(2026, 4, 10)
            .atTime(9, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val spanMs = 60_000L

        // 首次导入（旧版）落下的无权重事件：去重键与重新导入的带权重事件完全相同。
        val legacyEvent = PlaybackStatsRepository.PlaybackEvent(
            songId = "song-1",
            timestamp = start + spanMs,
            durationMs = spanMs,
            startTimestamp = start,
            endTimestamp = start + spanMs
        )
        val reImportedEvent = legacyEvent.copy(playCount = 6)

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.ALL,
            songs = listOf(song("song-1")),
            // 模拟 importEventsFromBackup 的合并结果：区间相同的两条只保留权重更大的一条。
            allEvents = listOf(reImportedEvent),
            nowMillis = start + spanMs + 60_000L
        )

        // 关键：不是 1 + 6 = 7 次，而是被顶替后的 6 次。
        assertThat(summary.totalPlayCount).isEqualTo(6)
        assertThat(summary.totalDurationMs).isEqualTo(spanMs)
    }

    @Test
    fun `events with invalid play count fall back to a single play`() = runTest {
        val repository = createRepository()
        val playedAt = LocalDate.of(2026, 4, 10)
            .atTime(9, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val songDuration = 4 * 60 * 1000L

        // 旧版 JSON 缺 playCount 字段时 Gson 可能落 0，必须退化成 1 而不是 0。
        val legacyEvent = PlaybackStatsRepository.PlaybackEvent(
            songId = "song-1",
            timestamp = playedAt,
            durationMs = 0L,
            playCount = 0
        )

        val summary = repository.buildSummaryFromEvents(
            range = StatsTimeRange.ALL,
            songs = listOf(song("song-1", durationMs = songDuration)),
            allEvents = listOf(legacyEvent),
            nowMillis = playedAt + songDuration + 60_000L
        )

        assertThat(summary.totalPlayCount).isEqualTo(1)
        assertThat(summary.totalDurationMs).isEqualTo(songDuration)
    }

    private fun createRepository(): PlaybackStatsRepository {
        val uniqueDir = createTempDirectory(
            "playback-stats-test-${Instant.now().toEpochMilli()}-"
        ).toFile()
        val testContext = mockk<android.content.Context>(relaxed = true)
        every { testContext.filesDir } returns uniqueDir
        return PlaybackStatsRepository(testContext)
    }

    private fun song(
        songId: String,
        durationMs: Long = 5 * 60 * 1000L,
        artist: String = "Artist",
        artists: List<ArtistRef> = emptyList(),
        genre: String? = null
    ): Song = Song(
        id = songId,
        title = "Song $songId",
        artist = artist,
        artistId = 1L,
        artists = artists,
        album = "Album",
        albumId = 1L,
        path = "/music/$songId.mp3",
        contentUriString = "content://media/external/audio/media/$songId",
        albumArtUriString = null,
        duration = durationMs,
        genre = genre,
        mimeType = "audio/mpeg",
        bitrate = 320_000,
        sampleRate = 44_100
    )
}
