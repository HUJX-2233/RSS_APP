package com.example.rssreader.domain.util

/**
 * HTML → 纯文本摘要：RSS 的 description/content 是转义 HTML，
 * 列表行必须剥掉标签、解码实体后再展示。纯 Kotlin 实现，不依赖 Android 框架类，
 * 这样 M3 的普通 JUnit 单测可以直接测它。
 */
object HtmlText {

    private val scriptOrStyle = Regex("<(script|style)\\b[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE)
    private val anyTag = Regex("<[^>]+>")
    private val entity = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z\\d]+);")
    private val whitespace = Regex("\\s+")

    private val namedEntities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "hellip" to "…", "mdash" to "—", "ndash" to "–", "middot" to "·",
    )

    fun summary(html: String?, max: Int = 120): String? {
        if (html.isNullOrBlank()) return null
        var text = html
            .replace(scriptOrStyle, " ")   // 脚本与样式整段丢弃
            .replace(anyTag, " ")
        text = entity.replace(text) { m ->
            val body = m.groupValues[1]
            when {
                body.startsWith("#") -> runCatching {
                    val code = if (body.startsWith("#x", true)) body.substring(2).toInt(16) else body.substring(1).toInt()
                    code.toChar().toString()
                }.getOrDefault(" ")
                else -> namedEntities[body] ?: " "
            }
        }
        text = whitespace.replace(text, " ").trim()
        if (text.isEmpty()) return null
        // 截断加省略号，列表行保持两行以内
        return if (text.length > max) text.take(max).trimEnd() + "…" else text
    }
}
