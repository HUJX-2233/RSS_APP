package com.example.rssreader.domain.util

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * RSS 日期解析器。为什么需要它：RSS-Parser 的 RssItem.pubDate 返回的是【原始字符串】，
 * 而 RSS 2.0 用 RFC-822（Sat, 22 Nov 2026 10:30:00 +0800）、Atom 用 ISO-8601（2026-11-22T10:30:00+08:00），
 * 两个格式都必须吃下。
 *
 * 失败策略：返回 null 而不是抛异常——一条坏数据绝不打断整个同步（对应排序沉底）。
 */
object DateParser {

    // RFC-822 风格。Locale 必须钉死 ENGLISH：EEE/dd/MMMM 解析依赖语言环境
    private val rfc822 = listOf(
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm z", Locale.ENGLISH),
    )

    // ISO-8601 风格（含时区形式）
    private val iso = listOf(
        DateTimeFormatter.ISO_OFFSET_DATE_TIME,
        DateTimeFormatter.ISO_ZONED_DATE_TIME,
    )

    fun parse(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()

        for (fmt in rfc822) {
            try {
                return ZonedDateTime.parse(s, fmt).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
            }
        }
        for (fmt in iso) {
            try {
                return ZonedDateTime.parse(s, fmt).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
            }
        }
        // 少数源不带时区：按本机时区解释，排序时至少有相对顺序
        try {
            return LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
        }
        // 纯日期（yyyy-MM-dd）
        try {
            return LocalDate.parse(s).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
        }
        return null
    }
}
