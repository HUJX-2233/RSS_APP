package com.example.rssreader.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FeedDao {

    @Insert
    suspend fun insert(feed: FeedEntity): Long

    // 删订阅：文章靠外键 CASCADE 自动清理，这里不单独发 DELETE
    @Query("DELETE FROM feeds WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE feeds SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("SELECT * FROM feeds")
    suspend fun getAll(): List<FeedEntity>

    @Query("SELECT * FROM feeds")
    fun observeAll(): Flow<List<FeedEntity>>

    @Query("SELECT COUNT(*) FROM feeds")
    suspend fun count(): Int

    /**
     * 同步状态回写：时间、逐源错误、条件请求凭证一次更新。
     * 调用方必须用 NonCancellable 包裹（见 FeedRepository），外层协程取消时也不能丢状态。
     */
    @Query(
        """
        UPDATE feeds
        SET lastSyncAt = :at,
            lastSyncError = :error,
            etag = :etag,
            lastModified = :lastModified
        WHERE id = :id
        """
    )
    suspend fun updateSyncState(
        id: Long,
        at: Long?,
        error: String?,
        etag: String?,
        lastModified: String?,
    )
}
