package com.example.rssreader.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * articles 表：一行一篇文章。
 *
 * 全项目最核心的 schema 设计是下面这个唯一索引：
 *   (feedId, articleKey) 唯一 + 入库用 OnConflictStrategy.IGNORE
 * → RSS 是"每次全量重发最近 N 条"的拉取模型，同一篇文章会被反复送达；
 *   唯一索引让重复送达整行跳过，于是刷新天然幂等、已读状态永不丢。
 * 正确性押在这里，而不是押在 304（304 只是省流量的优化开关）。
 */
@Entity(
    tableName = "articles",
    foreignKeys = [
        ForeignKey(
            entity = FeedEntity::class,
            parentColumns = ["id"],
            childColumns = ["feedId"],
            // 删订阅时文章自动清理，不留孤儿行
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["feedId", "articleKey"], unique = true)],
)
data class ArticleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val feedId: Long,
    /** 文章身份：guid 优先，link 兜底（解析映射层决定） */
    val articleKey: String,
    val title: String? = null,
    val link: String? = null,
    /** 原始 HTML 摘要，原样入库 */
    val description: String? = null,
    /** 原始 HTML 正文，原样入库（详情页 WebView 渲染用） */
    val content: String? = null,
    val imageUrl: String? = null,
    /** 解析后的 epoch 毫秒；解析失败置 null，排序时沉底 */
    val pubDate: Long? = null,
    val fetchedAt: Long,
    val isRead: Boolean = false,
)
