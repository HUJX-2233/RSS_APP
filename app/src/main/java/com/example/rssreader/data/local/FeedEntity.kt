package com.example.rssreader.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * feeds 表：一行一个订阅源。
 *
 * url 永不改写——feed 自报的 link 经常是错的，用户输入的地址才是唯一可信的身份（Feeder 的经验教训）。
 * 其余列都服务于两件事：驱动 UI 的同步状态（lastSyncAt / lastSyncError）和条件请求凭证（etag / lastModified）。
 */
@Entity(tableName = "feeds")
data class FeedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val url: String,
    val title: String,
    /** 上次同步时间：UI 显示 + 判断"该不该刷新" */
    val lastSyncAt: Long? = null,
    /** 上次同步失败原因：逐源可见，绝不静默吞错 */
    val lastSyncError: String? = null,
    /** HTTP 条件请求凭证：下次带上换 304，内容没变就跳过解析 */
    val etag: String? = null,
    val lastModified: String? = null,
)
