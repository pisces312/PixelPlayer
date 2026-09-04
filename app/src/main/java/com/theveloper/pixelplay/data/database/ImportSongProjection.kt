package com.theveloper.pixelplay.data.database

import androidx.room.ColumnInfo

/**
 * 导入匹配用的轻量歌曲投影（Poweramp 导入前置条件，见 poweramp-import-feature-plan §3.1）。
 * 一次性载入全部本地歌曲建内存索引，避免 1600+ 规模下的逐首单点查询。
 */
data class ImportSongProjection(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "artist_name") val artistName: String,
    @ColumnInfo(name = "album_name") val albumName: String,
    @ColumnInfo(name = "duration") val duration: Long
)
