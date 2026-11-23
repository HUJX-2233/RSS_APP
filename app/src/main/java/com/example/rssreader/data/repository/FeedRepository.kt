package com.example.rssreader.data.repository

import com.example.rssreader.data.local.ArticleDao
import com.example.rssreader.data.local.FeedDao
import com.example.rssreader.data.local.FeedEntity
import com.example.rssreader.data.remote.FeedFetcher
import com.example.rssreader.data.remote.FetchResult
import com.example.rssreader.data.remote.RssMapper
import com.example.rssreader.data.remote.SyncError
import com.prof18.rssparser.RssParser
import com.prof18.rssparser.RssParserBuilder
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * 订阅仓库：增删与刷新全流程的唯一入口。
 * 三条铁律：
 *   1. 添加订阅先真实拉取验证，成功才入库（用户贴错地址立刻能知道）；
 *   2. 刷新逐源并发但限流 4——同时打爆十几个源既慢又容易被限流（Capyreader 的经验值）；
 *   3. 一个源失败只记录在它自己的行上，绝不中断其他源的同步。
 */
class FeedRepository(
    private val feedDao: FeedDao,
    private val articleDao: ArticleDao,
    private val fetcher: FeedFetcher,
) {
    // 只用 parse(xml) 纯解析；6.0.10 源码确认它是挂起函数、内部自带 Default 调度器
    private val parser: RssParser = RssParserBuilder().build()
    private val mapper = RssMapper()
    private val permits = Semaphore(4)

    suspend fun countFeeds(): Int = feedDao.count()

    /**
     * 添加订阅：拉取 + 解析全部成功才入库，失败原因带回给 UI。
     */
    suspend fun addFeed(rawUrl: String): Result<Long> {
        val url = rawUrl.trim()
        return try {
            when (val result = fetcher.fetch(url, etag = null, lastModified = null)) {
                is FetchResult.Fresh -> {
                    val channel = parser.parse(result.xml)
                    val feedId = feedDao.insert(
                        FeedEntity(
                            url = url,
                            // 标题用 channel 自报的；空缺时用 URL 顶上
                            title = channel.title?.trim()?.ifBlank { null } ?: url,
                        )
                    )
                    articleDao.insertAll(mapper.toEntities(feedId, channel.items))
                    feedDao.updateSyncState(feedId, System.currentTimeMillis(), null, result.etag, result.lastModified)
                    Result.success(feedId)
                }
                is FetchResult.NotModified ->
                    // 首次请求不该出现 304；出现了如实报错而不是静默
                    Result.failure(SyncError.Http(304))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncError) {
            Result.failure(e)
        } catch (e: Exception) {
            // 解析器抛的 RssParsingException 等，归入解析失败分类
            Result.failure(SyncError.Parsing(e))
        }
    }

    /**
     * 全源刷新：并发上限 4，单源失败不中断整体。
     */
    suspend fun refreshAll() {
        val feeds = feedDao.getAll()
        coroutineScope {
            feeds.forEach { feed ->
                launch {
                    permits.withPermit { refreshFeed(feed) }
                }
            }
        }
    }

    private suspend fun refreshFeed(feed: FeedEntity) {
        val now = System.currentTimeMillis()
        try {
            when (val result = fetcher.fetch(feed.url, feed.etag, feed.lastModified)) {
                is FetchResult.NotModified ->
                    // 内容没变：跳过解析入库，只回写状态
                    withContext(NonCancellable) {
                        feedDao.updateSyncState(feed.id, now, null, result.etag, result.lastModified)
                    }
                is FetchResult.Fresh -> {
                    val channel = parser.parse(result.xml)
                    // 唯一索引 + IGNORE：重复送达的旧文章整行跳过，已读状态天然保留
                    articleDao.insertAll(mapper.toEntities(feed.id, channel.items))
                    withContext(NonCancellable) {
                        feedDao.updateSyncState(feed.id, now, null, result.etag, result.lastModified)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val reported = if (e is SyncError) e else SyncError.Parsing(e)
            // NonCancellable：外层协程被取消（用户退出页面）也不能丢"上次失败"的记录
            withContext(NonCancellable) {
                feedDao.updateSyncState(feed.id, now, reported.message, feed.etag, feed.lastModified)
            }
        }
    }
}
