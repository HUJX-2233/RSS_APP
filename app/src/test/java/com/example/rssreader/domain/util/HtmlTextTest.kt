package com.example.rssreader.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HtmlText 的 L1 单元测试（纯 JVM）。
 *
 * 覆盖 docs/测试计划.md §2「HtmlTextTest」的五条：
 *   剥 script/style 整段丢弃 / 命名实体与数字实体解码 / 超长截断加省略号 / 纯文本直通 / 全空返回 null。
 * 断言的是"列表行要显示的字符串"——顺序很关键：先剥标签再解实体，
 * 所以 &lt;div&gt; 解码出来的尖括号不能再被当成标签剥掉。
 */
class HtmlTextTest {

    @Test
    fun `script与style整段丢弃`() {
        // 脚本/样式内容（含代码文本）必须整段消失，不能只剩标签、内容漏到摘要里。
        val html = "<p>正文在这里</p><script>var a = 1; console.log(a);</script><style>p { color: red; }</style>"
        assertEquals("正文在这里", HtmlText.summary(html))
    }

    @Test
    fun `大写STYLE同样整段丢弃`() {
        // 正则带 IGNORE_CASE，大小写混写的 style 也要整段剥离。
        assertEquals("正文", HtmlText.summary("<STYLE>p{color:red}</STYLE>正文"))
    }

    @Test
    fun `命名实体解码`() {
        // &amp; &lt; &gt; &quot; &middot; 等命名实体按表解码。
        assertEquals("Tom & Jerry", HtmlText.summary("Tom &amp; Jerry"))
        assertEquals("<b>粗体</b>", HtmlText.summary("&lt;b&gt;粗体&lt;/b&gt;"))
        assertEquals("·", HtmlText.summary("&middot;"))
    }

    @Test
    fun `数字实体与十六进制数字实体解码`() {
        // &#8212; 是 em dash（—），&#x2014; 是同一个字符的十六进制写法；
        // 计划表里点名的 &#8212; 必须解成 —，而不是留下原文或变成空格。
        assertEquals("Tom & Jerry — 番外", HtmlText.summary("Tom &amp; Jerry &#8212; 番外"))
        assertEquals("— 六十四", HtmlText.summary("&#x2014; 六十四"))
    }

    @Test
    fun `先剥标签再解实体_解出的尖括号不再被剥`() {
        // 顺序性回归：实体解码发生在标签剥离之后，&lt;div&gt; 解出的 <div> 应当作为文本保留。
        assertEquals("<div>", HtmlText.summary("&lt;div&gt;"))
    }

    @Test
    fun `超长文本截断并加省略号`() {
        // 150 字符的 "word " 重复：折叠空白后按 max=120 截断，末尾补省略号。
        val result = HtmlText.summary("word ".repeat(30))
        assertNotNull("超长内容不应返回 null", result)
        val text = result!!
        assertTrue("截断后应带省略号", text.endsWith("…"))
        // 恰好 120 个字符：take(120) 末尾若是空格会被 trimEnd 去掉，所以是 119 + "…"
        assertEquals(120, text.length)
        assertFalse("省略号前不应残留空格", text.endsWith(" …"))
        assertTrue("应保留原文前缀", text.startsWith("word word word"))
    }

    @Test
    fun `自定义max截断且不残留空格`() {
        // 显式传 max 也要生效；截断点落在空格上时先 trimEnd 再加省略号。
        assertEquals("abcdefghij…", HtmlText.summary("abcdefghijklmno", 10))
        assertEquals("word word…", HtmlText.summary("word word word", 9))
    }

    @Test
    fun `纯文本直通_标签与换行折叠为空格`() {
        // 纯文本原样返回；<br/> 这类行内标签替换成空格，不能把两个词粘在一起。
        assertEquals("纯文本，没有标签", HtmlText.summary("纯文本，没有标签"))
        assertEquals("a b", HtmlText.summary("<p>a<br/>b</p>"))
    }

    @Test
    fun `全空内容返回null`() {
        // null / 空串 / 纯空白 / 只有标签 / 只有 &nbsp; 都视为"没有摘要"。
        val empties = listOf<String?>(null, "", "   ", "<div>   </div>", "<p>&nbsp;</p>", "<!-- 注释 -->")
        empties.forEach { raw ->
            assertNull("输入 [$raw] 应返回 null", HtmlText.summary(raw))
        }
    }

    @Test
    fun `未超长时不加省略号`() {
        // 边界：正好等于 max 不截断；短文本绝不能被画蛇添足加 "…"。
        assertFalse(HtmlText.summary("短文本")!!.endsWith("…"))
        assertEquals("x".repeat(120), HtmlText.summary("x".repeat(120)))
    }
}
