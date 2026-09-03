package com.theveloper.pixelplay.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

@Dao
interface FavoritesDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setFavorite(favorite: FavoritesEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(favorites: List<FavoritesEntity>)

    @Query("DELETE FROM favorites WHERE songId = :songId")
    suspend fun removeFavorite(songId: Long)

    /**
     * 软删除：仅清除收藏标记，**保留评分**。
     * 替代原 removeFavorite 在业务逻辑中的调用——直接 DELETE 会连带丢掉评分。
     */
    @Query("UPDATE favorites SET isFavorite = 0 WHERE songId = :songId")
    suspend fun clearFavoriteFlag(songId: Long)

    /** 清除无意义行（既未收藏也未评分），避免表中残留空记录。 */
    @Query("DELETE FROM favorites WHERE songId = :songId AND isFavorite = 0 AND rating = 0")
    suspend fun purgeIfEmpty(songId: Long)

    /**
     * 设置评分（1..5），保留 isFavorite 原值；传 0 表示清除评分。
     * 行不存在时插入一条 isFavorite = 0 的「仅评分」行。
     */
    @Query(
        """
        INSERT INTO favorites (songId, isFavorite, timestamp, rating)
        VALUES (:songId, COALESCE((SELECT isFavorite FROM favorites WHERE songId = :songId), 0),
                :timestamp, :rating)
        ON CONFLICT(songId) DO UPDATE SET rating = :rating
        """
    )
    suspend fun setRating(songId: Long, rating: Int, timestamp: Long)

    /** 获取单曲评分；无记录返回 null，有记录但未评分返回 0。 */
    @Query("SELECT rating FROM favorites WHERE songId = :songId")
    suspend fun getRating(songId: Long): Int?

    /** 响应式观察单曲评分；无记录时流不发射（调用方按 0 处理）。 */
    @Query("SELECT rating FROM favorites WHERE songId = :songId")
    fun observeRating(songId: Long): Flow<Int?>

    /** 全量评分快照（rating > 0），供导入校验与推荐算法接入。 */
    @Query("SELECT songId, rating FROM favorites WHERE rating > 0")
    suspend fun getAllRatingsOnce(): List<SongRatingProjection>

    @Query("SELECT isFavorite FROM favorites WHERE songId = :songId")
    suspend fun isFavorite(songId: Long): Boolean?

    @Query("SELECT songId FROM favorites WHERE isFavorite = 1 ORDER BY songId")
    fun getFavoriteSongIdsRaw(): Flow<List<Long>>

    fun getFavoriteSongIds(): Flow<List<Long>> = getFavoriteSongIdsRaw().distinctUntilChanged()

    @Query("SELECT songId FROM favorites WHERE isFavorite = 1 ORDER BY songId")
    suspend fun getFavoriteSongIdsOnce(): List<Long>

    @Query("SELECT * FROM favorites")
    suspend fun getAllFavoritesOnce(): List<FavoritesEntity>

    @Query("DELETE FROM favorites")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceAll(favorites: List<FavoritesEntity>) {
        clearAll()
        if (favorites.isNotEmpty()) insertAll(favorites)
    }
}
