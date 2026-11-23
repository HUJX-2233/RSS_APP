package com.example.rssreader.data.repository

import com.example.rssreader.data.local.ArticleDao
import com.example.rssreader.data.local.ArticleWithFeed
import com.example.rssreader.domain.model.Article
import com.example.rssreader.domain.util.HtmlText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 文章查询与已读状态的唯一入口。UI 只从这里观察数据，不碰 DAO 细节。
 */
class ArticleRepository(private val articleDao: ArticleDao) {

    /** Room Flow → 领域模型：库一变，列表自动重画 */
    fun observeAll(): Flow<List<Article>> = articleDao.observeAllWithFeed().map { rows ->
        rows.map { it.toDomain() }
    }

    suspend fun markRead(id: Long) = articleDao.markRead(id)
}

private fun ArticleWithFeed.toDomain() = Article(
    id = article.id,
    feedTitle = feedTitle,
    title = article.title,
    link = article.link,
    // 摘要取值降级：有全文用全文，没全文用描述；展示前剥掉 HTML 标签
    summary = HtmlText.summary(article.content ?: article.description),
    pubDate = article.pubDate,
    isRead = article.isRead,
)
