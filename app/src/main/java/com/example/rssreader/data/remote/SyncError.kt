package com.example.rssreader.data.remote

/**
 * 刷新失败的分类（密封类）：
 * 网络不可达 / HTTP 状态码错误 / XML 解析失败 / 重复订阅 / 其他未知。
 * UI 拿到哪一类，就给用户看哪句话——错误永远不静默。
 */
sealed class SyncError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: Throwable) : SyncError("网络错误：${cause.message ?: cause.javaClass.simpleName}", cause)
    class Http(val code: Int) : SyncError("HTTP 错误：$code")
    class Parsing(cause: Throwable) : SyncError("解析失败：${cause.message ?: cause.javaClass.simpleName}", cause)

    /**
     * 重复订阅：同一个 URL 已经在库里。
     * 为什么单独分类而不复用 Unknown：这是用户可自行修正的输入错误，
     * 界面要提示"已经订阅过了"而不是"未知错误"，语义必须区分。
     */
    class Duplicate(val url: String) : SyncError("已经订阅过这个地址了：$url")

    class Unknown(cause: Throwable) : SyncError("未知错误：${cause.message ?: cause.javaClass.simpleName}", cause)
}
