package com.example.rssreader.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 拉取结果。304 单独建模：它是"没更新"的正常分支，不是错误——
 * 整段跳过解析与入库，这正是条件请求省流量的意义。
 */
sealed interface FetchResult {
    data class NotModified(val etag: String?, val lastModified: String?) : FetchResult
    data class Fresh(val xml: String, val etag: String?, val lastModified: String?) : FetchResult
}

/**
 * 网络层：自持 OkHttp（解析库自带的那套网络我们不用——网络和解析分层，
 * 这样错误分类、超时、条件请求全在自己手里）。15s 超时防止慢源拖死整个刷新循环。
 */
class FeedFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(url: String, etag: String?, lastModified: String?): FetchResult =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (RSSReader/0.1; Android)")
            // 条件请求：上次带回来的凭证这次原样奉还
            etag?.let { builder.header("If-None-Match", it) }
            lastModified?.let { builder.header("If-Modified-Since", it) }
            try {
                client.newCall(builder.build()).execute().use { response ->
                    when {
                        response.code == 304 -> FetchResult.NotModified(etag, lastModified)
                        !response.isSuccessful -> throw SyncError.Http(response.code)
                        else -> FetchResult.Fresh(
                            xml = response.body?.string() ?: "",
                            etag = response.header("ETag"),
                            lastModified = response.header("Last-Modified"),
                        )
                    }
                }
            } catch (e: IOException) {
                throw SyncError.Network(e)
            } catch (e: SyncError) {
                throw e
            } catch (e: IllegalArgumentException) {
                // URL 非法（比如用户贴了普通网页文字）也归入网络类错误
                throw SyncError.Network(e)
            }
        }
}
