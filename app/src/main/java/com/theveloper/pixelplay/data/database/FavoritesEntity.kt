package com.theveloper.pixelplay.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

@Entity(
    tableName = "favorites",
    indices = [
        Index(value = ["timestamp"], unique = false),
        Index(value = ["rating"], unique = false)
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
    /**
     * 0 = 未评分；1..5 = 评分星级。与 isFavorite 相互独立：
     * 允许 isFavorite = 0 且 rating > 0 的「仅评分」行（Poweramp 导入产生）。
     */
    @SerializedName(value = "rating", alternate = ["rating_stars"])
    val rating: Int = 0
)

/** 评分投影：仅取 songId 与 rating，避免加载整行。 */
data class SongRatingProjection(
    val songId: Long,
    val rating: Int
)
