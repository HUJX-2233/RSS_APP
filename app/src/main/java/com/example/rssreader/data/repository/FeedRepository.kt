package com.example.rssreader.data.repository

import androidx.room.withTransaction
import com.example.rssreader.data.local.ArticleDao
import com.example.rssreader.data.local.FeedDao
import com.example.rssreader.data.local.FeedEntity
import com.example.rssreader.data.local.RssDatabase
import com.example.rssreader.data.remote.FeedFetcher
import com.example.rssreader.data.remote.FetchResult
import com.example.rssreader.data.remote.RssMapper
import com.example.rssreader.data.remote.SyncError
import com.prof18.rssparser.RssParser
import com.prof18.rssparser.RssParserBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * 订阅仓库：增删与刷新全流程的唯一入口。
 *
 * 四条铁律：
 *   1. 添加订阅先真实拉取验证，成功才入库（用户贴错地址立刻能知道）；
 *   2. 添加的三步写库必须在同一事务里——否则中途失败会留下"有订阅没文章"的半成品；
 *   3. 刷新逐源并发但限流 4——同时打爆十几个源既慢又容易被限流（Capyreader 的经验值）；
 *   4. 一个源失败只记录在它自己的行上，绝不中断其他源的同步。
 */
class FeedRepository(
    private val database: RssDatabase,
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
     * 添加订阅：查重 → 拉取 + 解析 → 事务写入。任一步失败都不留下脏数据。
     */
    suspend fun addFeed(rawUrl: String): Result<Long> {
        val url = rawUrl.trim()
        if (url.isEmpty()) {
            return Result.failure(SyncError.Network(IllegalArgumentException("订阅地址不能为空")))
        }
        // 先查重：重复订阅会产生两个 feedId，而文章唯一索引是 (feedId, articleKey)，
        // 作用域不同 → 同一篇文章会在列表里出现两份，且刷新永远修不回来。
        if (feedDao.findByUrl(url) != null) {
            return Result.failure(SyncError.Duplicate(url))
        }
        return try {
            when (val result = fetcher.fetch(url, etag = null, lastModified = null)) {
                is FetchResult.Fresh -> {
                    // 解析与映射都是 CPU 活儿，放 Default 上做：
                    // 调用方是 viewModelScope（主线程），不切走的话大源会卡 UI。
                    val channel = withContext(Dispatchers.Default) { parser.parse(result.xml) }
                    val drafts = withContext(Dispatchers.Default) { mapper.toEntities(0L, channel.items) }
                    // 标题用 channel 自报的；空缺时用 URL 顶上
                    val title = channel.title?.trim()?.ifBlank { null } ?: url

                    // 三步写库必须原子：插入 feed 行 → 批量插入文章 → 回写同步状态
                    val feedId = database.withTransaction {
                        val id = feedDao.insert(FeedEntity(url = url, title = title))
                        if (drafts.isNotEmpty()) {
                            articleDao.insertAll(drafts.map { it.copy(feedId = id) })
                        }
                        feedDao.updateSyncState(id, System.currentTimeMillis(), null, result.etag, result.lastModified)
                        id
                    }
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
            // 解析器抛的 RssParsingException / 合法 XML 但不是 RSS 抛的 IllegalArgumentException 等
            Result.failure(SyncError.Parsing(e))
        }
    }

    /**
     * 全源刷新：并发上限 4，单源失败不中断整体。
     *
     * 为什么是 supervisorScope 而不是 coroutineScope：
     * coroutineScope 里任一子协程抛异常会取消其余所有兄弟协程，
     * 那样"一个源挂了，其它源继续"这条铁律就名存实亡——正是测试计划用例 7 要防的情形。
     */
    suspend fun refreshAll() {
        val feeds = feedDao.getAll()
        supervisorScope {
            feeds.forEach { feed ->
                launch {
                    permits.withPermit { refreshFeed(feed) }
                }
            }
        }
    }

    private suspend fun refreshFeed(feed: FeedEntity) {
        try {
            when (val result = fetcher.fetch(feed.url, feed.etag, feed.lastModified)) {
                is FetchResult.NotModified ->
                    // 内容没变：跳过解析入库，只回写状态（凭证用 304 带回来的新值）
                    writeSyncState(feed.id, System.currentTimeMillis(), null, result.etag, result.lastModified)

                is FetchResult.Fresh -> {
                    val channel = withContext(Dispatchers.Default) { parser.parse(result.xml) }
                    // 唯一索引 + IGNORE：重复送达的旧文章整行跳过，已读状态天然保留
                    val drafts = withContext(Dispatchers.Default) { mapper.toEntities(feed.id, channel.items) }
                    articleDao.insertAll(drafts)
                    writeSyncState(feed.id, System.currentTimeMillis(), null, result.etag, result.lastModified)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 只接 Exception 不接 Throwable：OutOfMemoryError / LinkageError 这类
            // 不该被伪装成"解析失败"——它们必须冒出来，而不是被记成一条同步错误。
            val reported = if (e is SyncError) e else SyncError.Unknown(e)
            // 失败时 lastSyncAt 保持原值（传旧值等效"不变"），避免"失败也显示刚同步过"；
            // 凭证同样保留旧值，绝不用失败时快照里的旧 etag 覆盖新凭证。
            writeSyncState(feed.id, feed.lastSyncAt, reported.message, feed.etag, feed.lastModified)
        }
    }

    /**
     * 状态回写：NonCancellable 防取消，外层 try-catch 防"兜底本身失败"。
     *
     * 为什么两层都要：NonCancellable 只保证不被取消，并不保证不抛异常。
     * 这段代码跑在 supervisorScope 的子协程里，一旦 SQLiteException 逃出去，
     * 这个源的失败原因就彻底丢失了，所以回写失败必须就地吞掉。
     */
    private suspend fun writeSyncState(
        id: Long,
        at: Long?,
        error: String?,
        etag: String?,
        lastModified: String?,
    ) {
        try {
            withContext(NonCancellable) {
                feedDao.updateSyncState(id, at, error, etag, lastModified)
            }
        } catch (e: Exception) {
            // 磁盘满/库损坏时已无处可写，吞掉以避免把一次写失败放大成整轮刷新中断
        }
    }
}
