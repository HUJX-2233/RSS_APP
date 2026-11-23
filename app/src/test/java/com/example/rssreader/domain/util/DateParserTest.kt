package com.example.rssreader.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * DateParser 的 L1 单元测试（纯 JVM，不依赖 Robolectric / 真网）。
 *
 * 覆盖 docs/测试计划.md §2「DateParserTest」表格的 7 类输入：
 *   RFC-822 带时区 / GMT / 单数字日 / ISO-8601 带时区 / ISO 无时区 / 纯日期 / 坏串-null-空串。
 * 另外补两类规格外但生产代码显式支持的形态：无秒的 GMT 简式、输入首尾空白。
 *
 * 核心契约：解析失败必须返回 null、绝不抛异常——一条坏 pubDate 不能打断整轮同步。
 * 期望值优先写成 epoch 毫秒常量（用 java.time 独立算得），避免"拿被测实现算期望值"的循环论证；
 * 只有"无时区"两类必须跟随机器时区，才用 ZoneId.systemDefault() 现算。
 */
class DateParserTest {

    /**
     * 2026-11-22T10:30:00+08:00 的 epoch 毫秒。
     * 独立算法：ZonedDateTime.of(2026,11,22,10,30,0,0, +08:00).toInstant().toEpochMilli()
     */
    private val epoch1030Plus0800 = 1795314600000L

    /** 2026-11-22T10:30:00Z（即 10:30 GMT）的 epoch 毫秒 = 1795343400000。 */
    private val epoch1030Utc = 1795343400000L

    /** 2026-11-03T10:30:00+08:00 的 epoch 毫秒（单数字日"3 Nov"用例用）。 */
    private val epochNov3 = 1793673000000L

    @Test
    fun `rfc822_标准带时区_返回正确毫秒`() {
        // RSS 2.0 最常见形态：星期 + 两位日 + 数字时区 +0800。
        // 注意：日期必须与星期自洽（2026-11-22 是周日），否则 java.time 会按 SMART 策略校验失败。
        assertEquals(epoch1030Plus0800, DateParser.parse("Sun, 22 Nov 2026 10:30:00 +0800"))
    }

    @Test
    fun `rfc822_时区写成GMT字母名_按UTC解释`() {
        // 时区名 "GMT"（字母）而非 +0000，由第 3 个 pattern（z）兜住；应等价于 UTC 10:30。
        assertEquals(epoch1030Utc, DateParser.parse("Sun, 22 Nov 2026 10:30:00 GMT"))
    }

    @Test
    fun `rfc822_单数字日_兼容解析`() {
        // 脏格式：日写成 "3" 而不是 "03"，由第 2 个 pattern（d）兜住。
        assertEquals(epochNov3, DateParser.parse("Tue, 3 Nov 2026 10:30:00 +0800"))
    }

    @Test
    fun `rfc822_无秒的简式_兼容解析`() {
        // 少数源省略秒：HH:mm 形式，由第 4 个 pattern 兜住；时刻应与带秒的 GMT 用例一致。
        assertEquals(epoch1030Utc, DateParser.parse("Sun, 22 Nov 2026 10:30 GMT"))
    }

    @Test
    fun `iso8601_带时区_与同时刻rfc822等价`() {
        // Atom 常见形态；同一时刻，解析结果必须与 RFC-822 用例完全相等。
        assertEquals(epoch1030Plus0800, DateParser.parse("2026-11-22T10:30:00+08:00"))
    }

    @Test
    fun `iso_无时区_按本机时区解释`() {
        // 生产约定（DateParser 注释）：不带时区时按系统默认时区解释，
        // 好歹保证同期文章有相对顺序。期望值只能现算，否则换机器就红。
        val expected = LocalDateTime.of(2026, 11, 22, 10, 30)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals(expected, DateParser.parse("2026-11-22T10:30:00"))
    }

    @Test
    fun `纯日期_当日零点`() {
        // 只有 yyyy-MM-dd 时取当天 00:00:00（本机时区），不能被当成 UTC 午夜。
        val actual = DateParser.parse("2026-11-22")
        val expected = LocalDate.of(2026, 11, 22)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals(expected, actual)
        // 再确认落点确实是当天 0 点（而不是 10:30 之类）
        assertNotNull(actual)
        assertEquals(
            LocalDate.of(2026, 11, 22),
            java.time.Instant.ofEpochMilli(actual!!).atZone(ZoneId.systemDefault()).toLocalDate(),
        )
    }

    @Test
    fun `坏串_null_空串_空白串_一律返回null且不抛异常`() {
        // 契约：parse 永不抛异常。下面任何一条抛异常，本用例就会直接失败；
        // 全部返回 null 才算通过（同步流程不会被一条脏 pubDate 打断）。
        val dirty = listOf<String?>("不是日期", "2026-13-45T99:99:99+08:00", "", "   ", "\n\t", "Sat, 22 Nov 2026", null)
        dirty.forEach { raw ->
            assertNull("输入 [$raw] 应返回 null", DateParser.parse(raw))
        }
    }

    @Test
    fun `首尾空白被trim后仍能解析`() {
        // 真实 XML 里偶尔混入换行/空格；parse 内部 trim 后应照常解析。
        assertEquals(epoch1030Plus0800, DateParser.parse("  Sun, 22 Nov 2026 10:30:00 +0800  "))
    }

    @Test
    fun `星期写错时仍能解析_由无星期兜底格式接管`() {
        // 测试计划表格的样例写的是 "Sat, 22 Nov 2026 …"，但 2026-11-22 实际是周日。
        // java.time 的 SMART 解析会校验 day-of-week，带星期的格式因此全部失败；
        // 此后由 DateParser 里的 rfc822WithoutDay 兜底接管 → 仍能拿到正确时刻。
        // 设计理由：星期对时间戳是冗余信息，源写错不该导致文章日期丢失（沉底）。
        assertEquals(epoch1030Plus0800, DateParser.parse("Sat, 22 Nov 2026 10:30:00 +0800"))
        // 对照：星期写对时走带星期的格式，两条路径结果必须完全一致。
        assertEquals(epoch1030Plus0800, DateParser.parse("Sun, 22 Nov 2026 10:30:00 +0800"))
    }
}
