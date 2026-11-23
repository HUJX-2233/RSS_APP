package com.example.rssreader.domain.model

/**
 * 文章（UI 层纯模型）。
 * UI 层永远只看到"能直接上屏"的字段：摘要已剥标签、日期已解析成毫秒。
 */
data class Article(
    val id: Long,
    val feedTitle: String,
    val title: String?,
    val link: String?,
    val summary: String?,
    val pubDate: Long?,
    val isRead: Boolean,
)
