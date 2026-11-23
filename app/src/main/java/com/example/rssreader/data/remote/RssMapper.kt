package com.example.rssreader.data.remote

import com.example.rssreader.data.local.ArticleEntity
import com.example.rssreader.domain.util.DateParser
import com.prof18.rssparser.model.RssItem

/**
 * 解析器输出 → 数据库行。
 * 解析器只负责"读 XML"，入库前的身份与字段决策都在这里（分层原则：解析器只输出内存对象）。
 */
class RssMapper {

    fun toEntities(feedId: Long, items: List<RssItem>): List<ArticleEntity> = buildList {
        items.forEach { item ->
            // 文章身份：guid 优先、link 兜底；两者皆无则丢弃——不发明标题哈希
            // （"周报"这类重复标题会误判成同一篇，宁可丢弃也不误判）
            val key = item.guid ?: item.link
            if (key.isNullOrBlank()) return@forEach
            add(
                ArticleEntity(
                    feedId = feedId,
                    articleKey = key,
                    title = item.title?.trim()?.ifBlank { null },
                    link = item.link,
                    description = item.description,
                    content = item.content,
                    imageUrl = item.image,
                    // 原始字符串 → 毫秒；失败置 null，排序沉底
                    pubDate = DateParser.parse(item.pubDate),
                    fetchedAt = System.currentTimeMillis(),
                )
            )
        }
    }
}
