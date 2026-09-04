package com.theveloper.pixelplay.data.backup.module

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.theveloper.pixelplay.data.backup.model.BackupSection
import com.theveloper.pixelplay.data.backup.model.PlaybackHistoryBackupEntry
import com.theveloper.pixelplay.data.stats.PlaybackStatsRepository
import com.theveloper.pixelplay.di.BackupGson
import com.theveloper.pixelplay.utils.LogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackHistoryModuleHandler @Inject constructor(
    private val playbackStatsRepository: PlaybackStatsRepository,
    @BackupGson private val gson: Gson
) : BackupModuleHandler {

    override val section = BackupSection.PLAYBACK_HISTORY

    override suspend fun export(): String = withContext(Dispatchers.IO) {
        val entries = playbackStatsRepository.exportEventsForBackup().map { event ->
            PlaybackHistoryBackupEntry(
                songId = event.songId,
                timestamp = event.timestamp,
                durationMs = event.durationMs,
                startTimestamp = event.startTimestamp,
                endTimestamp = event.endTimestamp,
                playCount = event.weight
            )
        }
        gson.toJson(entries)
    }

    override suspend fun countEntries(): Int = withContext(Dispatchers.IO) {
        playbackStatsRepository.exportEventsForBackup().size
    }

    override suspend fun snapshot(): String = export()

    override suspend fun restore(payload: String): Unit = withContext(Dispatchers.IO) {
        val type = TypeToken.getParameterized(List::class.java, PlaybackHistoryBackupEntry::class.java).type
        val entries: List<PlaybackHistoryBackupEntry> = gson.fromJson(payload, type)
        val events = entries.map { entry ->
            PlaybackStatsRepository.PlaybackEvent(
                songId = entry.songId,
                timestamp = entry.timestamp,
                durationMs = entry.durationMs,
                startTimestamp = entry.startTimestamp,
                endTimestamp = entry.endTimestamp,
                // 旧版备份没有该字段，反序列化后为 1；非法值同样退化为 1。
                playCount = entry.playCount.coerceAtLeast(1)
            )
        }
        val writeSucceeded = playbackStatsRepository.importEventsFromBackup(
            events = events,
            clearExisting = true
        )
        LogUtils.i(
            this@PlaybackHistoryModuleHandler,
            "恢复播放历史 %d 条，写入成功=%b", events.size, writeSucceeded
        )
    }

    override suspend fun rollback(snapshot: String): Unit = restore(snapshot)
}
