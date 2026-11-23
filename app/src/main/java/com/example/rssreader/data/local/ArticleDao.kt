package com.example.rssreader.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 列表行需要的联查结果：文章行 + 所属订阅名 */
data class ArticleWithFeed(
    @Embedded val article: ArticleEntity,
    val feedTitle: String,
)

@Dao
interface ArticleDao {

    // 去重地基：feedId+articleKey 撞唯一索引 → 整行忽略，已读状态永不丢
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<ArticleEntity>)

    /**
     * 列表查询：未读优先（isRead ASC），时间倒序（pubDate DESC）。
     * SQLite 里 NULL 视为最小值，DESC 排序时 null 天然沉底——正好是"坏日期垫底"的预期行为。
     */
    @Query(
        """
        SELECT a.*, f.title AS feedTitle
        FROM articles a
        JOIN feeds f ON a.feedId = f.id
        ORDER BY a.isRead ASC, a.pubDate DESC
        """
    )
    fun observeAllWithFeed(): Flow<List<ArticleWithFeed>>

    @Query("UPDATE articles SET isRead = 1 WHERE id = :id")
    suspend fun markRead(id: Long)
}
