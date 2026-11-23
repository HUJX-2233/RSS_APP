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
            // 注意 takeIf：真实源里 guid 出现空串或纯空白是很常见的（生成工具没写 ID）。
            // 若写成 item.guid ?: item.link，空串 guid 会让 key 变成空白串，
            // 随后被下面的校验拦掉 → 明明有 link 也被丢弃，兜底形同虚设。
            val key = item.guid?.takeIf { it.isNotBlank() }
                ?: item.link?.takeIf { it.isNotBlank() }
                ?: return@forEach
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
