package com.theveloper.pixelplay.data.importer.poweramp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.theveloper.pixelplay.data.importer.ImportParseException
import com.theveloper.pixelplay.data.importer.ImportPlaylist
import com.theveloper.pixelplay.data.importer.ImportSongRecord
import com.theveloper.pixelplay.data.importer.ImportSource
import com.theveloper.pixelplay.data.importer.ImportSourceData
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Poweramp `.poweramp-backup` 解析器（poweramp-import-feature-plan §2.1）。
 *
 * 流程：zip → 取 `lists-export` 条目（跳过 settings-export / 封面图）→
 * 落地为临时文件后用 SQLiteDatabase 只读打开 → 读 `playlists` / `tracks` 两表。
 *
 * ⚠️ schema 随 Poweramp 版本变化（F4：`total_played_times` 仅新版有），
 * 必须用 PRAGMA table_info 动态探测列，禁止硬编码 SELECT 全部列。
 */
@Singleton
class PowerampBackupParser @Inject constructor(
    @ApplicationContext private val context: Context
) : ImportSource {

    override val id: String = SOURCE_ID
    override val displayName: String = "Poweramp"
    override val supportedFileExtensions: List<String> = listOf(".poweramp-backup")

    override suspend fun parse(uri: Uri): ImportSourceData = withContext(Dispatchers.IO) {
        val dbFile = extractListsExport(uri)
        try {
            parseDatabase(dbFile)
        } finally {
            dbFile.delete()
        }
    }

    /** 解 zip，找到 `lists-export` 条目写入临时文件。 */
    private fun extractListsExport(uri: Uri): File {
        val outFile = File.createTempFile("poweramp_lists_export_", ".db", context.cacheDir)
        var found = false
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val entryName = entry.name.substringAfterLast('/')
                        if (!entry.isDirectory && entryName == LISTS_EXPORT_ENTRY) {
                            outFile.outputStream().use { out -> zip.copyTo(out) }
                            found = true
                            break
                        }
                        zip.closeEntry()
                    }
                }
            } ?: throw ImportParseException("无法读取所选文件")
        } catch (e: ImportParseException) {
            outFile.delete()
            throw e
        } catch (e: Exception) {
            outFile.delete()
            throw ImportParseException("不是有效的 Poweramp 备份（zip 读取失败）", e)
        }
        if (!found) {
            outFile.delete()
            throw ImportParseException("不是有效的 Poweramp 备份（缺少 $LISTS_EXPORT_ENTRY）")
        }
        return outFile
    }

    private fun parseDatabase(dbFile: File): ImportSourceData {
        val db = try {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: Exception) {
            throw ImportParseException("不是有效的 Poweramp 备份（SQLite 打开失败）", e)
        }
        try {
            val trackColumns = tableColumns(db, TABLE_TRACKS)
            val playlistColumns = tableColumns(db, TABLE_PLAYLISTS)
            if (trackColumns.isEmpty() || playlistColumns.isEmpty()) {
                throw ImportParseException("不是有效的 Poweramp 备份（缺少 tracks/playlists 表）")
            }
            // 必需列校验（全版本都有，见 §2.1 字段映射表）
            val required = listOf(COL_PATH, COL_RATING, COL_PLAYED_AT, COL_PLAYED_TIMES)
            if (!trackColumns.containsAll(required)) {
                throw ImportParseException("不是有效的 Poweramp 备份（tracks 表缺少必需列）")
            }

            val playlists = readPlaylists(db)
            val tracks = readTracks(db, trackColumns)

            // N4/N5：去重键 = path（CUE 场景 path#cueOffset）；重复行优先取曲库行（见 N6）。
            val dedup = dedupeTracks(tracks).mapValues { it.value.toRecord() }

            // 列表引用行（playlist_id 非空）按 _id 递增即列表内顺序（§4.1 顺序保证）
            val importPlaylists = playlists.mapNotNull { (id, name) ->
                val keys = tracks
                    .filter { it.playlistId == id }
                    .map { track -> track.dedupKey() }
                if (keys.isEmpty()) null else ImportPlaylist(name = name, songKeys = keys)
            }

            return ImportSourceData(playlists = importPlaylists, songRecords = dedup)
        } finally {
            db.close()
        }
    }

    private fun tableColumns(db: SQLiteDatabase, table: String): Set<String> {
        val columns = mutableSetOf<String>()
        try {
            db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIdx >= 0) columns += cursor.getString(nameIdx)
                }
            }
        } catch (_: Exception) {
            // 表不存在 → 返回空集，由调用方报错
        }
        return columns
    }

    /** @return playlist_id → name（按 _id 递增） */
    private fun readPlaylists(db: SQLiteDatabase): List<Pair<Long, String>> {
        val result = mutableListOf<Pair<Long, String>>()
        db.rawQuery("SELECT _id, name FROM $TABLE_PLAYLISTS ORDER BY _id ASC", null).use { c ->
            while (c.moveToNext()) result += c.getLong(0) to c.getString(1)
        }
        return result
    }

    private fun readTracks(db: SQLiteDatabase, columns: Set<String>): List<TrackRow> {
        val selectCols = buildList {
            add("_id"); add(COL_PATH)
            if ("playlist_id" in columns) add("playlist_id")
            if ("readable_name" in columns) add("readable_name")
            add(COL_RATING); add(COL_PLAYED_AT)
            if ("played_fully_at" in columns) add("played_fully_at")
            add(COL_PLAYED_TIMES)
            if ("cue_offset_ms" in columns) add("cue_offset_ms")
            if ("export_type" in columns) add("export_type")
        }
        val rows = mutableListOf<TrackRow>()
        db.rawQuery("SELECT ${selectCols.joinToString()} FROM $TABLE_TRACKS ORDER BY _id ASC", null).use { c ->
            while (c.moveToNext()) {
                var i = 0
                c.getLong(i++) // _id
                val path = c.getString(i++) ?: continue
                val playlistId: Long? = if ("playlist_id" in columns) {
                    val v = if (c.isNull(i)) null else c.getLong(i)
                    i++
                    v
                } else null
                val readableName: String? = if ("readable_name" in columns) {
                    val v = if (c.isNull(i)) null else c.getString(i)
                    i++
                    v
                } else null
                // N1：rating IS NULL → 0
                val rating = if (c.isNull(i)) 0 else c.getInt(i); i++
                val playedAt = c.getLong(i++)
                val playedFullyAt = if ("played_fully_at" in columns) c.getLong(i++) else 0L
                val playedTimes = c.getInt(i++)
                val cueOffsetMs = if ("cue_offset_ms" in columns) c.getInt(i++) else 0
                val exportType = if ("export_type" in columns) c.getInt(i++) else -1

                rows += TrackRow(
                    path = path,
                    playlistId = playlistId,
                    readableName = readableName,
                    rating = rating,
                    playedAt = playedAt,
                    playedFullyAt = playedFullyAt,
                    playCount = normalizePlayedTimes(playedAt, playedTimes),
                    cueOffsetMs = cueOffsetMs,
                    isLibraryRow = isLibraryRow(exportType, playlistId)
                )
            }
        }
        return rows
    }

    companion object {
        const val SOURCE_ID = "poweramp"
        private const val LISTS_EXPORT_ENTRY = "lists-export"
        private const val TABLE_TRACKS = "tracks"
        private const val TABLE_PLAYLISTS = "playlists"
        private const val COL_PATH = "path"
        private const val COL_RATING = "rating"
        private const val COL_PLAYED_AT = "played_at"
        private const val COL_PLAYED_TIMES = "played_times"
    }
}

/** tracks 表一行的中间表示（internal 供单测构造样本）。 */
internal data class TrackRow(
    val path: String,
    val playlistId: Long?,
    val readableName: String?,
    val rating: Int,
    val playedAt: Long,
    val playedFullyAt: Long,
    val playCount: Int,
    val cueOffsetMs: Int,
    val isLibraryRow: Boolean
) {
    fun toRecord() = ImportSongRecord(
        path = path,
        titleHint = readableName,
        artistHint = null,          // 由 SongMatcher 从文件名解析（§3.2）
        albumHint = null,           // 由 SongMatcher 从目录名提取（加分项）
        rating = rating.coerceIn(0, 5),
        playCount = playCount,
        lastPlayedAt = playedAt.takeIf { it > 0 },
        playedFullyAt = playedFullyAt.takeIf { it > 0 },
        totalPlayDurationMs = null  // N2：Poweramp 无时长数据，不捏造
    )

    /** 去重键（N5：CUE 分割场景下同 path 对应多首曲子）。 */
    fun dedupKey(): String = if (cueOffsetMs > 0) "$path#$cueOffsetMs" else path
}

/** N3：played_at > 0 且 played_times == 0 → 至少播过 1 次（实测 424 行矛盾）。 */
internal fun normalizePlayedTimes(playedAt: Long, playedTimes: Int): Int =
    if (playedAt > 0 && playedTimes == 0) 1 else playedTimes

/** N6：export_type = 3 为曲库行；旧版无此列（-1）时回退 playlist_id IS NULL。 */
internal fun isLibraryRow(exportType: Int, playlistId: Long?): Boolean =
    if (exportType >= 0) exportType == EXPORT_TYPE_LIBRARY else playlistId == null

/**
 * N4/N5：按 dedupKey 去重；重复行优先取曲库行。
 * 实测重复组评分冲突为 0，仅需确定性规则；同类行保持先读到的。
 */
internal fun dedupeTracks(rows: List<TrackRow>): LinkedHashMap<String, TrackRow> {
    val dedup = LinkedHashMap<String, TrackRow>()
    for (row in rows) {
        val key = row.dedupKey()
        val existing = dedup[key]
        if (existing == null || (row.isLibraryRow && !existing.isLibraryRow)) {
            dedup[key] = row
        }
    }
    return dedup
}

private const val EXPORT_TYPE_LIBRARY = 3
