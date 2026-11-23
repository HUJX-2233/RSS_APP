package com.example.rssreader.data.remote

import com.prof18.rssparser.model.RssItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RssMapper 的 L1 单元测试（纯 JVM）。
 *
 * 覆盖 docs/测试计划.md §2「RssMapperTest」的三条核心规则：
 *   1) 文章身份：guid 优先 → link 兜底 → 两者皆无则丢弃（绝不发明标题哈希）；
 *   2) pubDate 坏串 → null（不抛异常，排序沉底）；
 *   3) 空白标题 → null。
 * 另补字段透传、顺序保持、fetchedAt 写入等"映射层不该偷偷改数据"的检查。
 *
 * RssItem 是 KMP 的 data class，主构造器 17 个参数且无默认值，这里用 item() 工厂统一补齐，
 * 让每个用例只写与断言相关的那几个字段。
 */
class RssMapperTest {

    private val mapper = RssMapper()

    /** 构造 RssItem：只暴露用例关心的字段，其余按"解析器没读到"置 null/空。 */
    private fun item(
        guid: String? = null,
        title: String? = null,
        link: String? = null,
        pubDate: String? = null,
        description: String? = null,
        content: String? = null,
        image: String? = null,
    ) = RssItem(
        guid = guid,
        title = title,
        author = null,
        link = link,
        pubDate = pubDate,
        description = description,
        content = content,
        image = image,
        audio = null,
        video = null,
        sourceName = null,
        sourceUrl = null,
        categories = emptyList(),
        itunesItemData = null,
        commentsUrl = null,
        youtubeItemData = null,
        rawEnclosure = null,
    )

    @Test
    fun `guid优先作为文章身份`() {
        // 两者都有时取 guid：guid 是源保证的永久 ID，link 可能带跟踪参数或改版。
        val entities = mapper.toEntities(7L, listOf(item(guid = "urn:uuid:1", link = "https://example.com/a")))
        assertEquals(1, entities.size)
        assertEquals("urn:uuid:1", entities[0].articleKey)
        assertEquals(7L, entities[0].feedId)
    }

    @Test
    fun `guid为null时link兜底`() {
        // 只缺 guid 时用 link 顶上，不能因为 guid 缺失就把文章丢掉。
        val entities = mapper.toEntities(1L, listOf(item(guid = null, link = "https://example.com/final")))
        assertEquals(1, entities.size)
        assertEquals("https://example.com/final", entities[0].articleKey)
    }

    @Test
    fun `guid与link皆无时丢弃整条`() {
        // 不能发明标题哈希（"周报"这类重复标题会被误判成同一篇），宁可丢弃。
        val noId = mapper.toEntities(
            1L,
            listOf(
                item(guid = null, link = null, title = "没有身份的文章"),
                item(guid = "   ", link = null, title = "空白 guid 也算没有身份"),
            ),
        )
        assertTrue(noId.isEmpty())
    }

    @Test
    fun `空串或空白guid回退到link而不是丢弃整条`() {
        // 真实源里 guid 为空串很常见（生成工具没写 ID）。
        // 身份规则是「guid 优先 → link 兜底 → 两者皆无才丢弃」，
        // 因此空串/纯空白 guid 必须视为「没有 guid」，交给 link 兜底，而不是当成无效身份丢弃。
        val blankGuid = mapper.toEntities(1L, listOf(item(guid = "", link = "https://example.com/final")))
        assertEquals(1, blankGuid.size)
        assertEquals("https://example.com/final", blankGuid[0].articleKey)

        val spaceGuid = mapper.toEntities(1L, listOf(item(guid = "   ", link = "https://example.com/final")))
        assertEquals(1, spaceGuid.size)
        assertEquals("https://example.com/final", spaceGuid[0].articleKey)

        // 对照：guid 与 link 都是空白时仍然丢弃——绝不发明标题哈希。
        assertTrue(mapper.toEntities(1L, listOf(item(guid = "  ", link = ""))).isEmpty())
    }

    @Test
    fun `pubDate坏串置null且不抛异常`() {
        // 关键契约：一条坏 pubDate 不能炸掉整个映射，坏值置 null 后由排序兜底（沉底）。
        val bad = mapper.toEntities(1L, listOf(item(guid = "g1", title = "标题", pubDate = "2026-11-22 10:30")))
        assertEquals(1, bad.size)
        assertNull(bad[0].pubDate)
        assertEquals("标题", bad[0].title) // 其它字段不受牵连

        // pubDate 缺失（null）同样置 null，且不影响条目本身是否入库。
        val missing = mapper.toEntities(1L, listOf(item(guid = "g2")))
        assertEquals(1, missing.size)
        assertNull(missing[0].pubDate)
    }

    @Test
    fun `pubDate正常串解析为毫秒`() {
        // 2026-11-22T10:30:00+08:00 = 1795314600000（与 DateParserTest 同一期望值）。
        val entities = mapper.toEntities(1L, listOf(item(guid = "g", pubDate = "Sun, 22 Nov 2026 10:30:00 +0800")))
        assertEquals(1795314600000L, entities[0].pubDate)
    }

    @Test
    fun `空白标题归一为null`() {
        // RssItem.title 可能全是空格/换行；入库前归一成 null，列表行才能显示"无标题"占位。
        val entities = mapper.toEntities(
            1L,
            listOf(
                item(guid = "g1", title = "   "),
                item(guid = "g2", title = "\n\t\n"),
                item(guid = "g3", title = null),
            ),
        )
        assertEquals(3, entities.size) // 标题为空不代表丢弃条目
        entities.forEach { assertNull(it.title) }
    }

    @Test
    fun `标题去除首尾空白后保留`() {
        // 非空标题只做 trim，正文内容不做任何清洗。
        val entities = mapper.toEntities(1L, listOf(item(guid = "g", title = "  少数派周报  ")))
        assertEquals("少数派周报", entities[0].title)
    }

    @Test
    fun `混合列表只丢弃坏条目_其余顺序与字段透传`() {
        // 一坏两好：坏条目丢弃，其余保持源顺序，字段原样透传（description/content/image 不清洗）。
        val items = listOf(
            item(
                guid = "g-1",
                title = "第一篇",
                link = "https://a.example/1",
                description = "<p>摘要1</p>",
                content = "<p>正文1</p>",
                image = "https://a.example/1.png",
                pubDate = "Sun, 22 Nov 2026 10:30:00 +0800",
            ),
            item(guid = null, link = null, title = "没有身份，应被丢弃"),
            item(guid = "g-3", title = "第三篇"),
        )

        val entities = mapper.toEntities(9L, items)

        assertEquals(2, entities.size)
        assertEquals(listOf("g-1", "g-3"), entities.map { it.articleKey })
        val first = entities[0]
        assertEquals(9L, first.feedId)
        assertEquals("第一篇", first.title)
        assertEquals("https://a.example/1", first.link)
        assertEquals("<p>摘要1</p>", first.description)
        assertEquals("<p>正文1</p>", first.content)
        assertEquals("https://a.example/1.png", first.imageUrl)
        assertEquals(1795314600000L, first.pubDate)
    }

    @Test
    fun `空列表映射为空列表`() {
        assertTrue(mapper.toEntities(1L, emptyList()).isEmpty())
    }

    @Test
    fun `fetchedAt记录映射时刻且默认未读`() {
        // fetchedAt 由映射层打戳（不是解析器给的）；新入库行默认未读。
        val before = System.currentTimeMillis()
        val entities = mapper.toEntities(1L, listOf(item(guid = "g")))
        val after = System.currentTimeMillis()

        assertTrue(entities[0].fetchedAt in before..after)
        assertFalse(entities[0].isRead)
    }
}
