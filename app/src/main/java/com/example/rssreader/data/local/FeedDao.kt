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
     * 按 URL 查重：同一条订阅不允许加两次。
     * 为什么在应用层查而不是只靠 schema 唯一索引：加索引要升数据库版本写迁移，
     * 开发期迁移出错就是启动即崩溃；这里用一次索引查询换来等价效果，风险为零。
     * （已记录为已知限制：生产环境应当在 schema 层兜底。）
     */
    @Query("SELECT * FROM feeds WHERE url = :url LIMIT 1")
    suspend fun findByUrl(url: String): FeedEntity?

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
