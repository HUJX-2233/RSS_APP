package com.example.rssreader.data.remote

/**
 * 刷新失败的分类（密封类）：
 * 网络不可达 / HTTP 状态码错误 / XML 解析失败 / 其他未知。
 * UI 拿到哪一类，就给用户看哪句话——错误永远不静默。
 */
sealed class SyncError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: Throwable) : SyncError("网络错误：${cause.message ?: cause.javaClass.simpleName}", cause)
    class Http(val code: Int) : SyncError("HTTP 错误：$code")
    class Parsing(cause: Throwable) : SyncError("解析失败：${cause.message ?: cause.javaClass.simpleName}", cause)
    class Unknown(cause: Throwable) : SyncError("未知错误：${cause.message ?: cause.javaClass.simpleName}", cause)
}
