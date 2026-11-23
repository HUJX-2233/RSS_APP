package com.example.rssreader.domain.model

/** 订阅源（UI 层纯模型，不带任何存储细节） */
data class Feed(
    val id: Long,
    val url: String,
    val title: String,
)
