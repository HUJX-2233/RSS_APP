# M1 并发与错误处理 · 独立代码评审

> **评审对象**：`FeedRepository.kt` / `FeedFetcher.kt` / `SyncError.kt`（配合 `ArticleDao.kt` / `FeedDao.kt` / `ArticleEntity.kt` / `FeedEntity.kt` 作为上下文）
> **评审方式**：严格**只读**。未修改 `app/src/main` 下任何文件，未执行 `gradlew`（避免与构建 agent 争锁），未 `git commit`。
> **评审时间**：2026-11-23
> **评审基线**：`b145810`。三个被评审文件在整个评审过程中**未被改动**（已用 `git status` 复核）；同时仓库里 `MainActivity.kt` / `ui/home/HomeScreen.kt` 与新增的 `ui/addfeed`、`ui/feeds`、`ui/detail` 正由另一个 agent 并行修改，故本报告对这三个文件之外的位置只以"语义位置"为准，行号是评审时刻的值。
> **计分口径**：只对"会改变行为 / 会丢数据 / 会误导用户 / 会卡住 UI"的问题计分；命名与纯风格问题不进正文。

---

## 0. 评审范围与证据

| 项 | 内容 |
| --- | --- |
| 被评审文件 | 3 个：Repository / Fetcher / SyncError |
| 交叉核对上下文 | `FeedEntity`、`ArticleEntity`、`FeedDao`、`ArticleDao`、`RssMapper`、`DateParser`、`RssDatabase`、`HomeViewModel`、`MainActivity`、`app/build.gradle.kts` |
| 依赖实查版本 | okhttp **4.12.0**、rssparser **6.0.10**、kotlinx-coroutines **1.10.2**、Room **2.8.5**、Kotlin **2.2.10**、minSdk 26 |
| 库源码实证 | `rssparser-android-6.0.10-sources.jar`（`RssParser.kt` / `AndroidXmlParser.kt` / `RssParsingException.kt`）、`okhttp-4.12.0-sources.jar`（`Response.kt` / `HttpUrl.kt`） |

---

## 1. 结论摘要

**问题总数 20 条：🔴 严重 2 条 · 🟡 中等 9 条 · 🟢 建议 9 条。**

### 最严重的三个问题

| # | 问题 | 位置 | 后果 |
| --- | --- | --- | --- |
| 1 | `addFeed` 的"插订阅 → 插文章 → 写同步状态"三步写库**没有事务**，且 `feeds.url` **没有唯一约束**、`addFeed` 也不做重复校验 | `FeedRepository.kt:48-57`、`FeedEntity.kt:12-24` | 失败时残留半成品订阅行；重复添加同一 URL 会产生两条订阅，于是**同一篇文章在两行 feedId 下各入一份，列表里永久重复**，唯一索引 `(feedId, articleKey)` 拦不住（作用域不同） |
| 2 | 单源失败状态的**回写动作本身没有兜底**，而 `refreshAll` 用的是 `coroutineScope` 不是 `supervisorScope` | `FeedRepository.kt:76-85, 107-113` | `updateSyncState` 抛 `SQLiteException`（磁盘满/库异常）时，异常逃出 `launch` → **取消所有兄弟协程 → 整轮刷新作废**，直接违背文件头"铁律 3"与《测试计划》用例 7"整体不中断" |
| 3 | `refreshFeed` 用 `catch (e: Throwable)`，把 `OutOfMemoryError` / `LinkageError` 一并降级成"解析失败" | `FeedRepository.kt:107-108` | 致命错误被静默吞掉并写成用户可见的"解析失败"；且兜底代码本身要分配字符串，OOM 场景下二次失败后仍然逃逸（反而触发问题 2） |

> 说明：**`Semaphore` 本身没有泄漏**，`CancellationException` 的重抛顺序**也正确**——这两点是"看起来危险但实际没问题"的地方，已在第 5 章逐条说明，避免过度改造。

---

## 2. 🔴 严重

### 🔴 R1 · `addFeed` 三步写库非原子 + `url` 无唯一约束 → 半成品订阅与重复订阅

**【文件:行号】** `FeedRepository.kt:48-57`（另涉 `FeedEntity.kt:12-24`、`FeedDao.kt:11-12`、`MainActivity.kt:71-72`）

**【问题描述】**

`addFeed` 的成功路径是三次彼此独立的数据库写入：

```kotlin
val feedId = feedDao.insert(FeedEntity(...))                                 // ① 已提交
articleDao.insertAll(mapper.toEntities(feedId, channel.items))                // ② 可能抛
feedDao.updateSyncState(feedId, now, null, result.etag, result.lastModified)  // ③ 可能抛
```

1. **半成品**：②或③抛异常（磁盘满 `SQLITE_FULL`、约束冲突、库被关）时，①已经提交。`addFeed` 返回 `Result.failure`，UI 提示"添加失败"，但库里已经多了一行 `lastSyncAt = null` 的订阅。用户重试 → 再插一行。
2. **重复订阅**：`feeds` 表没有 `url` 唯一索引，`addFeed` 也没有"已存在"判断，所以同一个 URL 可以被插入任意多次。因为文章去重的地基是 `Index(value = ["feedId", "articleKey"], unique = true)`，`feedId` 不同就是不同的行——**同一篇文章会入库两份**，`observeAllWithFeed` 也不去重，列表里每条文章出现两次，刷新也永远修不掉，只能手动删一条。
3. 现有 `MainActivity.seedDebugFeedIfEmpty()` 已经是 `countFeeds() == 0 → addFeed(...)` 这种"先查后写"的模式，M2 的"添加订阅"按钮同样会踩到（双击、粘贴两次、重试）。这类 TOCTOU 只能靠 DB 唯一约束兜底，靠调用方自觉不可靠。

**【为什么这么改会更好】** 本地优先应用里 DB 就是事实来源，订阅行是用户资产的顶层容器：要么完整存在（有同步状态、有文章），要么不存在。用 `withTransaction` 把三步合成一次原子提交后，(1) 失败即回滚，UI 的"添加失败"与库状态一致，重试安全；(2) `url` 唯一索引把"是否重复"的判断下沉到数据库，并发和重试都不可能产生重复订阅，`SyncError.Duplicate` 还能给用户一句准确的"这个地址已经订阅过了"，而不是让他面对两条一模一样的订阅。

**【建议代码片段】**

```kotlin
// 1) FeedEntity：把"一个 URL 只能订阅一次"变成 schema 约束
@Entity(
    tableName = "feeds",
    indices = [Index(value = ["url"], unique = true)],   // 注意：需要 version+1 与 Migration
)
data class FeedEntity(/* ... */)

// 2) FeedDao：原子写入口 + 重复查询
@Query("SELECT * FROM feeds WHERE url = :url LIMIT 1")
suspend fun findByUrl(url: String): FeedEntity?

// 3) FeedRepository：注入 RssDatabase（room-ktx 已在依赖里）
class FeedRepository(
    private val db: RssDatabase,
    private val feedDao: FeedDao,
    private val articleDao: ArticleDao,
    private val fetcher: FeedFetcher,
) {
    suspend fun addFeed(rawUrl: String): Result<Long> {
        val url = rawUrl.trim()
        return try {
            when (val result = fetcher.fetch(url, etag = null, lastModified = null)) {
                is FetchResult.Fresh -> {
                    val channel = parser.parse(result.xml)
                    val feedId = db.withTransaction {
                        if (feedDao.findByUrl(url) != null) throw SyncError.Duplicate(url)
                        val id = feedDao.insert(
                            FeedEntity(
                                url = url,
                                title = channel.title?.trim()?.ifBlank { null } ?: url,
                            ),
                        )
                        // 映射也在事务外先算好更稳（见 M7），这里为体现原子性保持顺序
                        articleDao.insertAll(mapper.toEntities(id, channel.items))
                        feedDao.markSynced(id, System.currentTimeMillis(), result.etag, result.lastModified)
                        id
                    }
                    Result.success(feedId)
                }
                is FetchResult.NotModified -> Result.failure(SyncError.Protocol("首次请求返回 304"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncError) {
            Result.failure(e)
        } catch (e: SQLiteException) {                 // 唯一索引冲突 / 磁盘满
            Result.failure(SyncError.Storage(e))
        } catch (e: Exception) {
            Result.failure(SyncError.Parse(e))          // 仅解析库异常落到这里（见 M2）
        }
    }
}
```

> 注意：加唯一索引属于 schema 变更，需要 `version = 2` + `Migration`（或 M1 阶段直接改 `version` 并保留 `fallbackToDestructiveMigration` 以外的正式迁移），否则已安装的调试包会崩溃。若本阶段不想动 schema，最低限度是**把三步包进 `withTransaction`**，并让 `addFeed` 先 `findByUrl` 判重。

---

### 🔴 R2 · 失败回写路径自身可抛 + `refreshAll` 缺 `supervisorScope` → 单源回写失败会取消整轮刷新

**【文件:行号】** `FeedRepository.kt:76-85`、`FeedRepository.kt:107-113`、`FeedRepository.kt:93-95`、`FeedRepository.kt:100-102`

**【问题描述】**

`refreshAll` 的结构是 `coroutineScope { feeds.forEach { launch { permits.withPermit { refreshFeed(feed) } } } }`。`coroutineScope` 的语义是：**任意子协程以非 `CancellationException` 失败，立刻取消所有兄弟协程并把异常抛给调用方**。而 `refreshFeed` 里唯一还能向外抛的地方，恰好是"记录失败"这一步：

```kotlin
} catch (e: Throwable) {
    val reported = if (e is SyncError) e else SyncError.Parsing(e)
    withContext(NonCancellable) {
        feedDao.updateSyncState(feed.id, now, reported.message, feed.etag, feed.lastModified)
    }   // ← 这一行自己可能抛 SQLiteException，且没有任何兜底
}
```

`NonCancellable` 只挡取消，**不挡异常**。于是某一次 `updateSyncState` 抛错（磁盘满/`SQLITE_BUSY`/库被关）时：

1. 该 `launch` 以异常失败 → `coroutineScope` 取消其余所有源（包括正在 `execute()` 的和排队等 permit 的）；
2. 被取消的源走 `catch (e: CancellationException) { throw e }` 直接重抛，**既不写失败原因也不写 `lastSyncAt`**，它们的错误从 UI 上彻底消失；
3. `refreshAll` 把异常抛给 `HomeViewModel.refresh()` 的 `viewModelScope`——因为 `HomeViewModel` 只 `try/finally` 复位 `isRefreshing`、没有 `catch`，这会变成一次未捕获的协程异常（`SupervisorJob` 下不会崩进程，但会走默认 `handler` 打日志）。

这与文件头写的"铁律 3：一个源失败只记录在它自己的行上，绝不中断其他源的同步"以及《测试计划》L2 用例 7"多源中一个抛错 → 其他源照常入库……整体不中断"是直接冲突的。目前的"单源隔离"靠的是"单源内部吞掉所有异常"这一层，一旦吞异常的动作本身出错，隔离就整体失效。

**【为什么这么改会更好】** 把"单源隔离"从"依赖内部吞异常"升级为"结构上不可能传染"：`supervisorScope` 保证任意子协程失败不影响兄弟；子协程最外层再包一层 `catch` 保证连记录失败也不会冒泡（降级为日志）。这样 `refreshFeed` 的返回值/异常模型变成"要么成功，要么已在内部处理完"，`refreshAll` 的契约就稳定了，M3 接 WorkManager 时也不会出现"一个源的 DB 错误让整批任务标记 failed 并触发重试风暴"。

**【建议代码片段】**

```kotlin
suspend fun refreshAll() = supervisorScope {
    val feeds = feedDao.getAll()
    feeds.forEach { feed ->
        launch {
            try {
                permits.withPermit { refreshFeed(feed) }
            } catch (e: CancellationException) {
                throw e                                  // 取消照常上抛
            } catch (e: Throwable) {
                // 兜底：到这里说明连"记录失败"都失败了，只能进日志，绝不再往外抛
                Log.e(TAG, "refresh failed for ${feed.url}", e)
            }
        }
    }
}

private suspend fun refreshFeed(feed: FeedEntity) {
    val startedAt = System.currentTimeMillis()
    try {
        // ... fetch / parse / insert / markSynced
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val reported = e as? SyncError ?: SyncError.Unknown(e)
        try {
            withContext(NonCancellable) {
                feedDao.markFailed(feed.id, startedAt, reported.userMessage)   // 只写错误，不动 etag（见 M3）
            }
        } catch (dbError: Exception) {
            Log.e(TAG, "写回失败状态失败: ${feed.url}", dbError)          // 记录失败本身失败 → 日志
        }
    }
}
```

---

## 3. 🟡 中等

### 🟡 M1 · `catch (e: Throwable)` 过宽：吞掉 `OutOfMemoryError` / `LinkageError`，且兜底动作在 OOM 下会二次失败

**【文件:行号】** `FeedRepository.kt:107-108`

**【问题描述】** `catch (e: Throwable)` 的捕获面包含 `Error` 家族：`OutOfMemoryError`、`StackOverflowError`、`NoClassDefFoundError`/`LinkageError`。这里的处理方式是把它们变成 `SyncError.Parsing(e)`——即"这个源解析失败了"，然后继续跑下一轮。后果有两层：

1. **信号被抹掉**：`LinkageError`（比如某机型缺 `XmlPullParserFactory` 的某个实现）是"所有源都会失败"的系统性问题，被降级成逐源"解析失败"后，日志里只剩几十条一模一样的中文提示，没有任何崩溃上报会看到它；
2. **兜底本身会二次失败**：`SyncError.Parsing(e)` 要拼 `"解析失败：" + e.message` 字符串，`withContext(NonCancellable)` 和 Room 的写入也要分配内存。在真正 OOM 的时刻，这段代码大概率再抛一次 OOM 逃出 `launch`——反而精准触发 R2 描述的"整轮刷新被取消"。也就是说这层 `Throwable` 兜底在它最该起作用的场景里必定失效。

**【为什么这么改会更好】** `Exception` 已经覆盖了所有业务可恢复的错误（`SyncError`、`IOException`、`RssParsingException`、`IllegalArgumentException`、`SQLiteException` 全是 `Exception` 的子类），而 `Error` 的正确处理方式是**让进程/崩溃上报看见它**。把捕获面收窄到 `Exception` 后：可恢复错误照旧逐源记录，致命错误快速失败——同时 `CancellationException` 仍要显式前置重抛（它也是 `Exception`）。另外 `SyncError.Unknown` 在 `SyncError.kt:12` 已定义却从未被构造，正好用来给"未预期的 `Exception`"做兜底，而不是一律说成"解析失败"（见 M2）。

**【建议代码片段】**

```kotlin
} catch (e: CancellationException) {
    throw e
} catch (e: SyncError) {
    recordFailure(feed, e)                       // 已分类
} catch (e: Exception) {
    // 未预期异常：分类为 Unknown，保留 cause 供日志/崩溃上报
    recordFailure(feed, SyncError.Unknown(e))
}
// 不再 catch Throwable：Error 交给上层崩溃上报
```

---

### 🟡 M2 · 错误分类不完备：URL 非法 / 非 RSS 内容 / 数据库错误都被归成"解析失败"，`RssParsingException` 只是碰巧归类正确

**【文件:行号】** `FeedRepository.kt:67-69`、`FeedRepository.kt:108`、`SyncError.kt:8-13`

**【问题描述】** `addFeed` 的 `catch (e: Exception) → SyncError.Parsing(e)` 把四类语义完全不同的失败压成了一类：

| 真实原因 | 实际抛出的类型 | 现在的分类 | 应得的分类 |
| --- | --- | --- | --- |
| XML 坏 / 非法实体 | `RssParsingException`（`Exception` 直接子类） | Parsing ✅（碰巧对） | Parsing |
| 合法 XML 但不是 RSS/Atom | `IllegalArgumentException`（`AndroidXmlParser.kt:69`） | Parsing ⚠️ | NotAFeed |
| URL 没有 scheme / 非 http(s) | `IllegalArgumentException`（见 M6） | Parsing ❌ | InvalidUrl |
| 磁盘满 / 约束冲突 / 库异常 | `SQLiteException` | Parsing ❌ | Storage |
| 缺 INTERNET 权限（清单被裁剪） | `SecurityException` | Parsing ❌ | Network/Permission |

关键实证：查 `rssparser-android-6.0.10-sources.jar`，`AndroidXmlParser.parseXML()` **只把 `XmlPullParserException` 包成 `RssParsingException`**，而"XML 合法但不是受支持的 feed"抛的是 `IllegalArgumentException("The provided XML is not supported...")`（第 69-71 行）。所以注释写的"解析器抛的 `RssParsingException` 等，归入解析失败分类"实际上**从未显式捕获过 `RssParsingException`**——它落进 `catch (Exception)` 纯属运气，同一条路径也把 DB 错误一起收了进来。

**【为什么这么改会更好】** 错误分类的价值全在"UI 能据此给出不同的下一步动作"：网络错 → "重试"；URL 错 → "检查地址格式"；已经不是 feed → "这个网址不是 RSS/Atom"；存储错 → "清理空间后重试"。混成"解析失败"后，用户唯一能做的只有反复重试同一个错误。显式 `catch` 具体类型还有一个副作用收益：将来 rssparser 升级若改变异常类型（比如把"非 feed"也改成 `RssParsingException`），编译期不会提醒，但**分类测试**会立刻红掉。

**【建议代码片段】**

```kotlin
import com.prof18.rssparser.exception.RssParsingException
import android.database.sqlite.SQLiteException

} catch (e: CancellationException) {
    throw e
} catch (e: SyncError) {
    Result.failure(e)
} catch (e: RssParsingException) {
    Result.failure(SyncError.Parse(e))            // 显式，不靠 catch-all
} catch (e: IllegalArgumentException) {
    // 同时覆盖 "unexpected scheme" 与 "The provided XML is not supported"
    Result.failure(SyncError.InvalidFeed(rawUrl, e))
} catch (e: SQLiteException) {
    Result.failure(SyncError.Storage(e))
} catch (e: Exception) {
    Result.failure(SyncError.Unknown(e))
}
```

配套的 `SyncError` 扩展（见 G4：同时补 `kind` 让 UI 可分支）：

```kotlin
sealed class SyncError(message: String, cause: Throwable? = null, val kind: Kind) : Exception(message, cause) {
    enum class Kind { NETWORK, HTTP, INVALID_URL, INVALID_FEED, PARSE, STORAGE, DUPLICATE, PROTOCOL, UNKNOWN }

    class Network(cause: Throwable) : SyncError("网络不可达，请检查网络后重试", cause, Kind.NETWORK)
    class Http(val code: Int) : SyncError("服务器返回 $code，请稍后重试", null, Kind.HTTP)
    class InvalidUrl(val url: String, cause: Throwable) :
        SyncError("地址格式不对：$url（需要以 http:// 或 https:// 开头）", cause, Kind.INVALID_URL)
    class InvalidFeed(val url: String, cause: Throwable) :
        SyncError("这个地址返回的不是 RSS/Atom 源", cause, Kind.INVALID_FEED)
    class Parse(cause: Throwable) : SyncError("内容解析失败", cause, Kind.PARSE)
    class Storage(cause: Throwable) : SyncError("本地保存失败，请检查存储空间", cause, Kind.STORAGE)
    class Duplicate(val url: String) : SyncError("已经订阅过这个地址", null, Kind.DUPLICATE)
    class Protocol(val detail: String) : SyncError("协议异常：$detail", null, Kind.PROTOCOL)
    class Unknown(cause: Throwable) : SyncError("未知错误", cause, Kind.UNKNOWN)
}
```

---

### 🟡 M3 · 失败路径把快照里的**旧** `etag`/`lastModified` 又写回去 → 重叠刷新时用旧凭证覆盖新凭证

**【文件:行号】** `FeedRepository.kt:111`

**【问题描述】** 失败分支调用 `updateSyncState(feed.id, now, reported.message, feed.etag, feed.lastModified)`，其中 `feed` 是 `refreshAll` 开头 `feedDao.getAll()` 拿到的**快照**。如果两次刷新重叠（M2 阶段"添加订阅"、下拉刷新、WorkManager 可能同时触发；«当前`_isRefreshing` 只是 UI 层防抖，不是仓库层互斥»），A 轮读到 `etag=E1`，B 轮用 `E1` 拿到 200 后写入 `E2`，随后 A 轮出错并把 `E1` 写回——**新凭证被旧凭证覆盖**。之后每次刷新都带着已失效的 `E1` 发条件请求，服务器只能返回 200 全量，条件请求的省流量收益永久丢失；某些弱一致性 CDN 上甚至会让 304 判定的键值永远对不上。

即便没有并发，这行写入也是纯粹的冗余：失败时 `etag`/`lastModified` 根本没变，为什么要写？

**【为什么这么改会更好】** 失败回写应该只碰"失败状态"这两个字段，**不去碰和失败无关的列**——这样既消灭了"旧值覆盖新值"的竞态，也让 `updateSyncState` 这个"一次更新四列"的胖接口拆成"成功/失败两条窄路径"，语义与注释一致（`FeedDao.kt:30-33` 现在把"凭证"和"错误"绑在同一个方法里，正是 R2/M3 的共同根因）。

**【建议代码片段】**

```kotlin
// FeedDao：把"成功"与"失败"拆成两个只改自己该改的列的语句
@Query("""
    UPDATE feeds
    SET lastSyncAt = :at, lastSyncError = NULL, etag = :etag, lastModified = :lastModified
    WHERE id = :id
""")
suspend fun markSynced(id: Long, at: Long, etag: String?, lastModified: String?)

@Query("""
    UPDATE feeds
    SET lastAttemptAt = :at, lastSyncError = :error
    WHERE id = :id
""")
suspend fun markFailed(id: Long, at: Long, error: String)

// FeedEntity 增加 lastAttemptAt: Long? = null（见 M4）
```

---

### 🟡 M4 · `lastSyncAt` 在失败时也被写入 → 分不清"上次成功"与"上次尝试"，且写的是**开始**时间

**【文件:行号】** `FeedRepository.kt:88`、`FeedRepository.kt:94`、`FeedRepository.kt:101`、`FeedRepository.kt:111`

**【问题描述】**

1. 字段的语义（`FeedEntity.kt:17`）是"上次同步时间：UI 显示 + 判断'该不该刷新'"。失败路径同样写 `lastSyncAt = now`，于是：
   - UI 无法表达"上次成功同步：3 小时前（本次失败）"；
   - 如果后续用 `lastSyncAt < now - 15min` 做"该刷新了"的判定（字段注释正是这个用途），**一个持续失败的源会被当成"刚刚同步过"而被跳过**，用户永远等不到它恢复；
   - 失败时 `etag` 与"上次成功时间"一起被"刷新"了，数据自相矛盾。
2. `now` 是在 `refreshFeed` **开头**取的时间戳（`:88`），一次 15s 超时或排队等 permit 之后，写入的是十几秒甚至几分钟前的值。`lastSyncAt` 记录"同步发生的时间"应该取完成时刻，而不是开始时刻。

**【为什么这么改会更好】** 把"成功时间"和"尝试时间"分成两列（`lastSyncAt` / `lastAttemptAt`）后：UI 可以显示"上次成功 10:00 · 上次尝试 10:30 失败"；节流逻辑可以安全地用 `lastSyncAt` 判断"内容新鲜度"、用 `lastAttemptAt` 判断"要不要再试"；测试用例"失败源 `lastSyncError` 有值"也能顺带断言 `lastSyncAt` 未被污染。

**【建议代码片段】**

```kotlin
private suspend fun refreshFeed(feed: FeedEntity) {
    val startedAt = System.currentTimeMillis()
    try {
        when (val result = fetcher.fetch(feed.url, feed.etag, feed.lastModified)) {
            is FetchResult.Fresh -> {
                val channel = parser.parse(result.xml)
                val entities = withContext(Dispatchers.Default) { mapper.toEntities(feed.id, channel.items) }
                db.withTransaction {                               // 见 M8
                    articleDao.insertAll(entities)
                    feedDao.markSynced(feed.id, System.currentTimeMillis(), result.etag, result.lastModified)
                }
            }
            is FetchResult.NotModified ->
                feedDao.markSynced(feed.id, System.currentTimeMillis(), result.etag, result.lastModified)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 只写"尝试"与"错误"，不写 lastSyncAt / etag（M3）
        feedDao.markFailed(feed.id, System.currentTimeMillis(), (e as? SyncError ?: SyncError.Unknown(e)).userMessage)
    }
}
```

---

### 🟡 M5 · `execute()` 不可取消 + 没有 `callTimeout`：一个慢源可长期占住 permit，用户离开页面请求仍在跑

**【文件:行号】** `FeedFetcher.kt:30-39`、`FeedRepository.kt:81`

**【问题描述】**

1. **取消传不进去**：`client.newCall(...).execute()` 是阻塞调用，`withContext(Dispatchers.IO)` 只能把线程切走，**无法中断正在跑的 socket**，也没有 `call.cancel()`。用户在刷新途中退出页面 → `viewModelScope` 取消 → 子协程仍卡在 `execute()` 里，直到连接/读取结束才在"切回原调度器"时抛出 `CancellationException`。这段时间里：permit 被占着、IO 线程被占着、`refreshAll` 也无法返回（`coroutineScope` 要等所有子协程走完）。
2. **15s 不是总时长**：只配了 `connectTimeout(15s)` + `readTimeout(15s)`。OkHttp 的总耗时 ≈ (最多 20 次重定向 / 多次 IP 重连) × (连接+读取超时)；一个"每 14 秒吐一个字节"的慢滴流服务器能让 `readTimeout` 永远不触发。文件头注释承诺的"15s 超时防止慢源拖死整个刷新循环"因此并不成立——4 个 permit 全部被拖住时，整轮刷新会无限期挂起，`isRefreshing` 的转圈也停不下来（UI 侧没有总超时）。

**【为什么这么改会更好】** 用 `enqueue` + `suspendCancellableCoroutine` 把 OkHttp 的 `Call` 接到协程取消上：取消即 `call.cancel()`，socket 立刻关，permit 立刻还，`refreshAll` 立刻返回——这是"用户退出页面"这个最常见交互的正确行为。再加 `callTimeout(20, SECONDS)` 给整次调用一个硬 deadline（包含重定向链），与注释承诺一致，也为 M3 接 WorkManager 的 `runAttemptTime` 打好基础。

**【建议代码片段】**

```kotlin
private val client = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS)   // 整次调用（含重定向）的总上限
    .build()

suspend fun fetch(url: String, etag: String?, lastModified: String?): FetchResult {
    val request = /* 见 M6 */
    val response = try {
        client.newCall(request).await()
    } catch (e: IOException) {
        throw SyncError.Network(e)
    }
    return response.use { r ->
        when {
            r.code == 304 -> FetchResult.NotModified(r.header("ETag") ?: etag, r.header("Last-Modified") ?: lastModified)
            !r.isSuccessful -> throw SyncError.Http(r.code)
            else -> FetchResult.Fresh(
                xml = r.body?.string() ?: "",
                etag = r.header("ETag"),
                lastModified = r.header("Last-Modified"),
            )
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
    })
    cont.invokeOnCancellation { cancel() }      // 取消 → 立刻断连，permit 立刻归还
}
```

> **附**（同一主题）：`RssParser.parse()` 内部用 `withContext(SupervisorJob() + Dispatchers.Default)`（rssparser 6.0.10 `RssParser.kt:17-18,49`）**重建了 Job**，调用方的取消同样传不进去——一个超大 XML 的解析会在用户退出后继续跑完。这是库的设计，无法从外部修正；能做的是在调用侧加"体积上限"（G1）来缩短这个不可取消窗口，并在注释里记下这个已知限制。

---

### 🟡 M6 · URL 非法时 `IllegalArgumentException` **逃出了 `try`**，`catch (IllegalArgumentException)` 是死代码；而且不该伪装成 `Network`

**【文件:行号】** `FeedFetcher.kt:32-38`（`try` 起点）、`FeedFetcher.kt:54-57`（死分支）

**【问题描述】**

```kotlin
val builder = Request.Builder()
    .url(url)                                   // ← 第 33 行，在 try 之外
    .header("User-Agent", ...)
etag?.let { builder.header("If-None-Match", it) }
try {                                           // ← 第 38 行才开始 try
    client.newCall(builder.build()).execute().use { ... }
} catch (e: IOException) { ... }
  catch (e: SyncError) { ... }
  catch (e: IllegalArgumentException) { throw SyncError.Network(e) }   // 永远进不来
```

实证：`okhttp-4.12.0-sources.jar` 的 `HttpUrl.Builder.parse()` 对"没有 scheme"或"非 http/https"输入抛 `IllegalArgumentException`（见 `HttpUrl.kt:930, 1254`）。`Request.Builder.url(String)` 正是走 `toHttpUrl() → parse()`。所以非法 URL 的异常在**构造 Request 时就抛出**，位置在 `try` 之前，第 54 行那个分支对 URL 场景是**不可达代码**；它只能接住 `execute()`/`body?.string()` 里偶发的 `IllegalArgumentException`（基本不存在）。

异常逃出 `fetch()` 之后的旅程：`addFeed` 的 `catch (e: Exception)` → `SyncError.Parsing` → 用户看到 **"解析失败：Expected URL scheme 'http' or 'https' but was 'foo'"**。这恰好是 `FeedRepository.kt:23` 承诺"用户贴错地址立刻能知道"、以及《开发计划》220 行验收项"错误 URL 添加 → 明确报错"想避免的结果——错误确实不静默了，但报的是**错误的类别 + 英文库内部信息**。

另外，"URL 非法"在语义上**也不该**映射成 `SyncError.Network`：网络错误暗示"稍后重试/检查网络"，而地址格式错误是**确定性失败**，重试一万次也一样。UI 若按类别给按钮，就会给用户一个永远无效的"重试"。

**【为什么这么改会更好】** 把请求构造纳入 `try`，并新增 `SyncError.InvalidUrl` 分类后：错误在**最接近源头的地方**被正确归类，`addFeed` 能说出"地址格式不对（需要以 http:// 或 https:// 开头）"，`refreshFeed` 也不会把一个不可能恢复的错误反复记成"解析失败"。同时那条死分支被真实启用，代码承诺与行为一致。

**【建议代码片段】**

```kotlin
suspend fun fetch(url: String, etag: String?, lastModified: String?): FetchResult =
    withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (RSSReader/0.1; Android)")
                .apply {
                    etag?.let { header("If-None-Match", it) }
                    lastModified?.let { header("If-Modified-Since", it) }
                }
                .build()
        } catch (e: IllegalArgumentException) {
            throw SyncError.InvalidUrl(url, e)     // 确定性失败，不再伪装成 Network
        }
        // ... 后续 newCall().await() / use
    }
```

---

### 🟡 M7 · `mapper.toEntities()` 跑在**主线程**：`refreshAll` 从 `viewModelScope`（Main.immediate）发起，IO 与 parse 都切了线程，唯独映射没切

**【文件:行号】** `FeedRepository.kt:79-81`（`launch` 继承调用方调度器）、`FeedRepository.kt:99`、`FeedRepository.kt:55`

**【问题描述】** 调用链是 `HomeViewModel.refresh() → viewModelScope.launch { feedRepository.refreshAll() }`，`viewModelScope` 的调度器是 `Dispatchers.Main.immediate`；`refreshAll` 里的 `coroutineScope { launch { ... } }` 会**继承**这个调度器。于是 `refreshFeed` 的每一段"自己的代码"都在主线程执行：

| 语句 | 实际线程 | 结论 |
| --- | --- | --- |
| `fetcher.fetch(...)` | `withContext(Dispatchers.IO)` | ✅ 已切走 |
| `parser.parse(xml)` | 库内部 `Dispatchers.Default` | ✅ 已切走 |
| `mapper.toEntities(...)` | **Main** | ❌ 未切走 |
| `articleDao.insertAll / updateSyncState` | Room 查询线程 | ✅ 已切走 |

`mapper.toEntities` 不是"纯对象拷贝"：它对每条调用 `title?.trim()?.ifBlank{}`，更要紧的是 `DateParser.parse(item.pubDate)`——最多 4 种 RFC-822 + 2 种 ISO 格式 + 本地时间 + 纯日期，**每次失败都要构造 `DateTimeParseException`**（异常构造在 Android 上很贵）。一个 500 条、日期格式不规范的源，轻松在主线程产生数千次异常构造；`refreshAll` 的 4 个并发子协程还会**同时回到主线程**竞争。这是掉帧到 ANR 的量级，而且和"4 并发限流"叠加后更明显。

**【为什么这么改会更好】** 仓库层不应该把"调度器选择"外包给调用方——§当前这个漏洞本质上是"DTO 映射没有声明自己的调度器"§。把映射包进 `withContext(Dispatchers.Default)`（或干脆让 `refreshAll` 整体 `withContext(Dispatchers.IO)`）后，无论调用方是 `viewModelScope`、`lifecycleScope` 还是 WorkManager 的 `CoroutineWorker`，主线程都只负责 UI；这条不变量也让 M3 的 WorkManager 迁移零风险。`DateParser` 本身是线程安全的（`java.time.DateTimeFormatter` 不可变），移动到 Default 没有副作用。

**【建议代码片段】**

```kotlin
// refreshFeed（Fresh 分支）
val channel = parser.parse(result.xml)
val entities = withContext(Dispatchers.Default) {          // 映射 + 日期解析离开主线程
    mapper.toEntities(feed.id, channel.items)
}
db.withTransaction {
    articleDao.insertAll(entities)
    feedDao.markSynced(feed.id, System.currentTimeMillis(), result.etag, result.lastModified)
}

// 或更省事：让整个刷新体不依赖调用方调度器
suspend fun refreshAll() = withContext(Dispatchers.IO) {   // 或 Default
    supervisorScope { /* ... */ }
}
```

---

### 🟡 M8 · `insertAll` 与 `updateSyncState` 不在同一事务 → 取消/异常正好落在两步之间时"有文章、无同步状态"

**【文件:行号】** `FeedRepository.kt:99-102`、`FeedRepository.kt:55-56`

**【问题描述】** `articleDao.insertAll(...)` 成功后、`withContext(NonCancellable) { updateSyncState(...) }` 之前存在一个挂起点。如果此时协程被取消（用户退出/下拉刷新被打断），`NonCancellable` 块照样会执行——这一层其实没问题；但**如果 `insertAll` 本身以 `CancellationException` 结束**（Room 的挂起插入会在恢复时检查取消），文章可能已写入或未写入，而 `lastSyncAt`/`etag` 一定不会更新，且 `CancellationException` 被前置 `catch` 直接重抛（`:105-106`），**没有任何一方记录这次"部分完成"**。表现是：文章列表里有新内容，但 feed 行显示的还是上一次的同步时间，下次刷新会再拉一遍（幂等，不重复入库，但白拉）。`addFeed` 同理（R1 的事务化会顺带修掉）。

另外，`NonCancellable` 写在两个分支里各一份（`:93-95`、`:100-102`），是"用 `NonCancellable` 手动补偿事务缺失"的信号——如果这两步是一个事务，就只需要一个 `NonCancellable`（或都不需要，见下）。

**【为什么这么改会更好】** 把"文章入库 + 同步状态"合并为一次原子提交，就不存在"半完成"这个状态机分支：要么两者都生效（下次带新 `etag` 走 304），要么都不生效（下次重拉，仍然幂等）。代码也少一条 `NonCancellable` 路径，取消语义更容易推理。注意：`withTransaction` 内部同样是可取消的，所以事务外仍可保留一个 `NonCancellable` 包住"提交"这个动作，但只需要一处。

**【建议代码片段】**

```kotlin
// FeedDao + ArticleDao 共用同一个 RssDatabase 事务
suspend fun persistFeed(feedId: Long, entities: List<ArticleEntity>, etag: String?, lastModified: String?) =
    db.withTransaction {
        articleDao.insertAll(entities)
        feedDao.markSynced(feedId, System.currentTimeMillis(), etag, lastModified)
    }

// 调用侧
withContext(NonCancellable) { persistFeed(feed.id, entities, result.etag, result.lastModified) }
```

---

### 🟡 M9 · 304 分支回写的是**请求时的旧 validator**，忽略了 304 响应头

**【文件:行号】** `FeedFetcher.kt:41`

**【问题描述】** `response.code == 304 -> FetchResult.NotModified(etag, lastModified)` 直接返回**请求时传进去的参数**，不读 304 响应的 `ETag` / `Last-Modified`。按 RFC 9111 §4.3.4（以及 RFC 7232 §4.1），304 响应**可以**携带更新后的 validator，收到时应当更新本地存储的凭证。现实中没有 304 头的服务器占多数，所以"看起来能用"，但以下几类会踩到：

- CDN/反代在两个节点间轮换 `ETag`，304 上给出新的（如 `W/"..."` 弱化形式）；
- 服务器在 200 时不发 `Last-Modified`、在 304 时补发，我们永远拿不到它，永远只能走 `If-None-Match`；
- 部分框架（如某些 Varnish 配置）会改写 `ETag` 为弱标签，我们继续用强标签比较，命中率下降。

**【为什么这么改会更好】** 一行改动的成本，换来"凭证始终以服务器最后一次表态为准"的不变量：304 时优先取响应头、缺失才回退到请求值。这条不变量也顺便让 M3 的"失败时不动凭证"更好论证——凭证只由 200/304 两个**成功**分支更新。

**【建议代码片段】**

```kotlin
response.code == 304 -> FetchResult.NotModified(
    etag = response.header("ETag") ?: etag,                     // 304 上的新值优先
    lastModified = response.header("Last-Modified") ?: lastModified,
)
```

> 附带收益：`refreshFeed` 的 304 分支（`:93-95`）写回 `result.etag` 与 `feed.etag` 相同时是空更新，改完后它才可能有实际作用。

---

## 4. 🟢 建议

### 🟢 G1 · 响应体没有大小上限，解析前就有 3 份内存副本

**【文件:行号】** `FeedFetcher.kt:44`、`FeedRepository.kt:97`

**【问题描述】** `response.body?.string()` 把整个响应读成 String（`String` 在 Android 上是 UTF-16，约 2 倍于字节数），随后 `AndroidXmlParser.generateParserInputFromString()` 又 `byteInputStream()` 复制一份；若首次解析失败，库的兜底路径 `escapeInvalidXmlEntities()` 会对整个 XML 做 **6 次全文正则替换**（每次产生一个新 String）。一个 5MB 的畸形 feed 峰值可到 60MB+，低端机直接 OOM——而 OOM 又会被 M1 的 `catch (Throwable)` 吞成"解析失败"。

**【为什么这么改会更好】** 在 IO 层用 `Content-Length` + 实际读取字节数双重设限（比如 8MB），超限抛 `SyncError.TooLarge` 并给出"这个源体积过大"的明确提示。这既是防御恶意/异常源，也让上表的峰值可控；同时缩短 M5 附注里"不可取消的解析窗口"。

**【建议代码片段】**

```kotlin
private const val MAX_BODY_BYTES = 8L * 1024 * 1024

val body = r.body ?: throw SyncError.Protocol("响应体为空")
if (body.contentLength() > MAX_BODY_BYTES) throw SyncError.Protocol("源体积过大（> 8MB）")
val xml = body.source().use { source ->
    val buffer = source.buffer()
    if (buffer.size > MAX_BODY_BYTES) throw SyncError.Protocol("源体积过大（> 8MB）")
    body.string()
}
```

---

### 🟢 G2 · `body?.string() ?: ""` 把空 body 静默降级成空串，最终以"解析失败"呈现

**【文件:行号】** `FeedFetcher.kt:44`

**【问题描述】** OkHttp 4.12 的 `Response.body` 确实可空（已实证 `Response.kt:78`）。空 body（204、某些反代的空 200）会变成 `parse("")` → `RssParsingException` → "解析失败"。`""` 这一步把"服务器给了空内容"的信息丢掉了，日志里无法区分"服务器空响应"和"XML 真的坏了"。

**【为什么这么改会更好】** 空 body 是协议层事实，应在 IO 层直接归为 `SyncError.Protocol("服务器返回空内容")`，与"解析失败"分开——排查线上问题时，"谁给的空响应"这个信息只在 IO 层还在。

**【建议代码片段】** 见 G1（`?: throw SyncError.Protocol("响应体为空")`）。

---

### 🟢 G3 · 首次请求返回 304 时归类为 `SyncError.Http(304)` 语义别扭

**【文件:行号】** `FeedRepository.kt:59-61`

**【问题描述】** 304 不是错误，"HTTP 错误：304" 会让用户和日志都困惑。按 `SyncError` 的注释（`SyncError.kt:4-6`），`Http` 表示"HTTP 状态码错误"，304 不在其列。

**【为什么这么改会更好】** 单独归为 `SyncError.Protocol`（或直接当"缓存已存在"处理），日志里能一眼区分"服务器异常返回 304"和"服务器真的 500 了"。这也和 M9、G4 一起把 `SyncError` 的四个格子补齐。

**【建议代码片段】**

```kotlin
is FetchResult.NotModified -> Result.failure(SyncError.Protocol("首次请求返回 304（服务器状态异常）"))
```

---

### 🟢 G4 · 错误文案直接拼 `cause.message`，英文库内部信息会入库并上屏；且没有 error code 供 UI 分支

**【文件:行号】** `SyncError.kt:9-12`、`FeedEntity.kt:20`、`FeedDao.kt:34-50`

**【问题描述】** `"解析失败：${cause.message ?: cause.javaClass.simpleName}"` 会把 `"Content is not allowed in prolog."`、`"Expected URL scheme 'http' or 'https' but was '...'"` 这类英文内部信息直接写进 `feeds.lastSyncError`，并在 M2 之后原样显示在 Snackbar / 订阅行上。同时 `lastSyncError` 只有一个 String 列，**错误类别在跨过 DB 边界后就丢了**——UI 想"只有网络错才显示重试按钮"就只能做字符串匹配。另外核查发现：`lastSyncError` 目前**没有任何读取方**（全仓库只有写入），`FeedDao.observeAll()`（`:25`）也从未被调用；连并行开发中的 `ui/addfeed`、`ui/feeds`、`ui/detail` 三个新页面里也搜不到 `lastSyncError`/`SyncError` 的任何引用（评审时用 grep 全量核对过），所以"错误永远不静默"在 M1 阶段尚未闭环。

**【为什么这么改会更好】** 分成三层：`userMessage`（中文、可操作）、`kind`（枚举，落库为增列或字符串 code，UI 用 `when` 分支）、原始 `cause`（只进 Logcat/崩溃上报）。这样 UI 文案可以统一审校、可以本地化、可以按类别决定交互，而 `cause.message` 仍然保留在日志里不丢失。

**【建议代码片段】**

```kotlin
// 落库改为 code + 可读文案两列（或复用一个 "KIND|message" 结构，但两列更干净）
@Query("UPDATE feeds SET lastSyncErrorCode = :code, lastSyncError = :msg, lastAttemptAt = :at WHERE id = :id")
suspend fun markFailed(id: Long, at: Long, code: String, msg: String)

// UI 侧
val text = when (row.errorKind) {
    SyncErrorKind.NETWORK -> "网络不可达，点击重试"
    SyncErrorKind.INVALID_URL, SyncErrorKind.INVALID_FEED -> "地址不可用，请更换订阅源"
    SyncErrorKind.HTTP -> "服务器异常，稍后重试"
    else -> "同步失败"
}
```

---

### 🟢 G5 · `permits` 只约束 `refreshFeed`，`addFeed` 走同一条网络路径却完全不限流；permit 覆盖范围也大于注释所述

**【文件:行号】** `FeedRepository.kt:35`、`FeedRepository.kt:45`、`FeedRepository.kt:81`

**【问题描述】** 注释（`:24`）说"刷新逐源并发但限流 4"，但 `addFeed`（`:45`）也发起一次真实网络请求，不受 `permits` 约束——用户在 M2 里连续添加 10 个源时会瞬间打出 10 个并发连接，与设计初衷相反。另一头，`withPermit` 包住的是**整个** `refreshFeed`（fetch + parse + 两次 DB 写），而注释的意图是"限流网络并发"；这意味着 4 个 permit 也被 CPU 解析和 Room 写入占用，吞吐低于设计预期（同时副作用是解析并发被意外限制在 4，对 CPU 反而是好事，所以这半条更像"注释与实现不符"）。

**【为什么这么改会更好】** 要么让 `addFeed` 也 `permits.withPermit { fetch(...) }`（对"同时打爆源"的一致防护），要么把 permit 收窄到只包 `fetcher.fetch`。两种都自洽，但**必须与注释一致**，否则下一个人按注释推理就会出错。

**【建议代码片段】**

```kotlin
// 方案 A：addFeed 也进限流
val result = permits.withPermit { fetcher.fetch(url, etag = null, lastModified = null) }

// 方案 B：只限流网络段
private suspend fun refreshFeed(feed: FeedEntity) {
    val result = permits.withPermit { fetcher.fetch(feed.url, feed.etag, feed.lastModified) }
    // ... parse / insert / 状态回写不进 permit
}
```

---

### 🟢 G6 · `refreshAll` 一次性 `launch` 全部源，且返回 `Unit`（无法告诉 UI "3/10 失败"）

**【文件:行号】** `FeedRepository.kt:76-85`

**【问题描述】** N 个订阅就 N 个协程挂起在信号量上（功能正确，但 N=200 时是 200 个协程对象）；更实际的问题是返回值：`refreshAll(): Unit` 让 UI 无法呈现"本轮 10 个源，7 成功 3 失败"，`HomeViewModel` 只能靠 `feeds` 表里的 `lastSyncError` 事后间接看（而该字段当前无人读，见 G4）。

**【为什么这么改会更好】** 返回一个轻量汇总（成功数/失败数/失败列表），UI 就能在下拉刷新结束后给一句"3 个源同步失败"，这比逐源去翻错误行更符合"错误永远不静默"的目标；同时把"并发度"从 `launch 数量` 换成 `flatMapMerge(concurrency = 4)` 或 `Semaphore` 内部的流式消费，协程数量与并发度解耦。

**【建议代码片段】**

```kotlin
data class RefreshSummary(val total: Int, val succeeded: Int, val failed: List<Pair<Long, String>>)

suspend fun refreshAll(): RefreshSummary = supervisorScope {
    val feeds = feedDao.getAll()
    val results = feeds.map { feed ->
        async {
            permits.withPermit { refreshFeed(feed) }      // refreshFeed 返回 Boolean
        }
    }.awaitAll()
    RefreshSummary(feeds.size, results.count { it }, emptyList())   // 失败明细由 DB 承载
}
```

---

### 🟢 G7 · 304 / Fresh 两个分支各写一遍 `withContext(NonCancellable) { updateSyncState(...) }`，且 304 那遍是空更新

**【文件:行号】** `FeedRepository.kt:93-95`、`FeedRepository.kt:100-102`

**【问题描述】** 两处代码字面相同（只有 `now` 相同、`etag` 来源不同），而 304 分支写入的 `result.etag`/`result.lastModified` 与 `feed.etag`/`feed.lastModified` **完全相同**（因为 `FetchResult.NotModified` 就是拿请求参数构造的，见 M9），所以那次 UPDATE 除了刷 `lastSyncAt` 之外没有信息增量。重复的收尾逻辑意味着以后改状态回写要改两处，R2/M3 的修补也容易漏一处。

**【为什么这么改会更好】** 把"写同步状态"收敛成一个 `persistSuccess(feed, etag, lastModified)` 私有方法（或像 M8 那样并进事务），一个悬挂点、一处 `NonCancellable`、一处测试点。

**【建议代码片段】** 见 M4 的 `refreshFeed` 重构（两个分支都调 `feedDao.markSynced(...)`）。

---

### 🟢 G8 · 进程内实际存在 3 套 `OkHttpClient`，与 build.gradle 注释"共用一套 HTTP 栈"不符

**【文件:行号】** `FeedFetcher.kt:25-28`、`FeedRepository.kt:33`、`app/build.gradle.kts:97-99`

**【问题描述】** `app/build.gradle.kts` 的注释写"图片和 RSS 共用一套 HTTP 栈，复用连接池/超时配置"，但实际是：(1) `FeedFetcher` 自建 `OkHttpClient`；(2) `RssParserBuilder()` 的默认参数又 `new OkHttpClient()`（`RssParserBuilder.kt:20`，而这个 client 只会被 `getRssChannel()` 用到，本项目只用 `parse(xml)`，属于纯浪费）；(3) Coil 自己的 client。三份连接池/线程池，其中一份完全空转。

**【为什么这么改会更好】** 把 `FeedFetcher` 的 client 抽成 `AppContainer` 里的单例并传给 `RssParserBuilder(callFactory = sharedClient)` 与 Coil（`OkHttpNetworkFetcherFactory(callFactory = { sharedClient })`），注释就从"愿景"变成"事实"：连接池、超时、UA 统一，少一套空闲线程池（4 个 `Dispatcher` 线程 + `ConnectionPool` 的 `TaskRunner`）。

**【建议代码片段】**

```kotlin
// AppContainer
val httpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS)
    .build()

val feedRepository = FeedRepository(db, db.feedDao(), db.articleDao(), FeedFetcher(httpClient))

// FeedRepository
private val parser: RssParser = RssParserBuilder(callFactory = httpClient).build()
```

---

### 🟢 G9 · `addFeed` 的 `Result` 在唯一的调用点被丢弃 → M1 阶段错误实际无处可去

**【文件:行号】** `MainActivity.kt:67-75`（`addFeed` 调用在 `:72`，仍是全仓库唯一调用方）

**【问题描述】** `lifecycleScope.launch { if (countFeeds() == 0) { container.feedRepository.addFeed("...") } }` 完全不看返回值，也不 `catch` 协程异常。于是 M1 阶段一个种子源拉取失败是**完全静默**的（只有 `lastSyncError` 落库，而它当前无人读，见 G4）——正好和 `SyncError.kt` 头注释"错误永远不静默"相反。另外这里是 `lifecycleScope`（Main）；`addFeed` 内部若将来加了重活（M7），会直接卡主线程。

**【为什么这么改会更好】** 至少 `.onFailure { Log.w(TAG, "seed feed failed", it) }`（或 M2 的 Snackbar 通道）。这样"种子失败"在 Logcat 里可见，调试期能第一时间发现网络/解析层回归；也让 `addFeed` 的 `Result` 契约有了真实的消费方（可写测试）。

**【建议代码片段】**

```kotlin
lifecycleScope.launch {
    if (container.feedRepository.countFeeds() == 0) {
        container.feedRepository.addFeed("https://sspai.com/feed")
            .onFailure { Log.w("Seed", "种子源添加失败", it) }
            .onSuccess { Log.i("Seed", "种子源就绪 feedId=$it") }
    }
}
```

---

## 5. 逐条回答 8 项重点审查项

| # | 审查项 | 结论 | 详见 |
| --- | --- | --- | --- |
| 1 | `Semaphore(4)` 限流正确性；`coroutineScope + launch` 结构；信号量泄漏；异常传播导致整体取消 | **无泄漏**：`withPermit` 用 `try/finally` 释放，取消/异常路径都会归还 permit。**整体取消确实存在**，但触发点不是信号量，而是"失败回写路径抛异常 + 没有 `supervisorScope`"。另有两个结构性问题：`addFeed` 绕过限流、permit 覆盖范围大于注释。 | R2、G5、G6、M5 |
| 2 | `withContext(NonCancellable)` 是否恰当；状态回写是否会丢 | **机制上恰当且不丢**：`NonCancellable` 把 Job 换掉，取消不会打断写入，写完后才会在下一个挂起点抛 `CancellationException`。真正的问题不在"会不会丢"，而在**写什么**：失败时用旧快照覆盖 `etag`（M3）、把失败也当"同步时间"（M4）、以及"插入文章 + 写状态"不是一个事务（M8）。 | M3、M4、M8、G7 |
| 3 | `CancellationException` 重抛路径完整性（`addFeed`: Cancellation→SyncError→Exception；`refreshFeed`: Cancellation→Throwable） | **两条路径的顺序都正确且必要**。Kotlin 的 `kotlin.coroutines.cancellation.CancellationException` 在 JVM 上是 `java.util.concurrent.CancellationException` 的 typealias（`IllegalStateException` → `RuntimeException` → `Exception`），不前置拦截就会被后面的 `catch (Exception)` 吞掉，破坏结构化并发。`refreshFeed` 用的是 `Throwable`，所以前置重抛**更是必需**。两处都没有漏，建议保留这个形状，只把 M1 的 `Throwable` 收窄为 `Exception`（重抛仍需保留）。 | 第 6 章、M1 |
| 4 | `refreshFeed` 捕获 `Throwable` 是否过宽 | **过宽**。会吞 `OutOfMemoryError` / `StackOverflowError` / `LinkageError` 并伪装成"解析失败"；且兜底动作本身要分配内存，在 OOM 场景下必然二次失败，再逃出去触发 R2。`Exception` 已覆盖全部业务可恢复错误。 | M1、R2 |
| 5 | `addFeed` 错误分类是否完备；`RssParsingException` 是否被正确归类 | **不完备**。`RssParsingException` 确实落进了 Parsing，但那是 `catch (Exception)` 的副产物，**从未被显式捕获**；而"合法 XML 但不是 feed"抛的是 `IllegalArgumentException`（已实证库源码）、URL 非法、`SQLiteException`、`SecurityException` 也全部落进同一个"解析失败"格子。需要拆出 `InvalidUrl` / `InvalidFeed` / `Storage`，并显式 catch `RssParsingException`。 | M2、M6 |
| 6 | OkHttp 资源释放（`.use`）；错误映射是否把 URL 非法误判成网络错误 | `.use` **用法正确**：OkHttp 4.12 的 `Response` 实现 `Closeable`，`close()` 关闭 body 并归还连接（已实证 `Response.kt:301`）。URL 非法的问题比"误判为网络错误"更糟：IAE 在 `try` **之外**抛出，`catch (IllegalArgumentException)` 是死代码，异常最终被 `addFeed` 归成"解析失败"并附英文信息。此外响应体无上限（G1）、`execute()` 不可取消且无 `callTimeout`（M5）。 | M6、M5、G1、G2 |
| 7 | 304 分支的 `etag` 用的是请求时的旧值，是否需要从 304 响应头更新 | **需要**。RFC 9111 §4.3.4 / RFC 7232 §4.1 允许且建议在 304 上更新本地 validator；应"响应头优先、缺失才回退请求值"。现状不仅不更新，还让 304 分支那次 UPDATE 变成空更新（只有 `lastSyncAt` 有意义）。 | M9、G7 |
| 8 | 其他（并发、数据一致性、异常吞噬、可空性） | 见 R1（事务/唯一约束）、M7（主线程映射）、M8（半完成状态）、M4（时间语义）、G1/G2（可空性与体积）、G4（错误文案与 code）、G8（三套 OkHttpClient）、G9（Result 被丢弃）。 | 全文 |

---

## 6. 已核查、确认**没有问题**的点（避免误改 / 过度设计）

1. **`Semaphore.withPermit` 不会泄漏**：`kotlinx.coroutines.sync` 的实现是 `acquire(); try { block() } finally { release() }`，取消、`Throwable`、正常返回三条路径都会释放。R2 修完之后限流仍是安全的，不必给 permit 再加 `NonCancellable`。
2. **`CancellationException` 的前置重抛是"正确设计"而非冗余**：`addFeed` 的 `CancellationException → SyncError → Exception` 与 `refreshFeed` 的 `CancellationException → Throwable` 都是必要的；后者因为用了 `Throwable`，若去掉前置重抛会直接破坏取消传播。**不要**在"收窄 `Throwable` 为 `Exception`"时顺手删掉前置 `catch`。
3. **`withContext(NonCancellable)` 的机制正确**：它把 Job 换成 `NonCancellable`，因此块内的 Room 写不会被取消打断；写完成后才在后续挂起点抛出 `CancellationException`。所以"用户退出页面导致状态回写丢失"这个担心**不成立**——真正要修的是写的内容（M3/M4）与事务性（M8）。
4. **`Response.use { }` 正确**：OkHttp 4.12 `Response` 实现 `Closeable`，`close()` 关 body 并归还连接/释放 `Call`；`body?.string()` 在 `use` 内读完后关闭，没有连接泄漏。
5. **304 必须放在 `!isSuccessful` 之前判断——顺序是对的**：304 不属于 2xx，先判断 304 再判断 `isSuccessful` 是正确的写法（`FeedFetcher.kt:40-42`）。
6. **`RssParser` 单实例并发使用是安全的**：查 6.0.10 源码，`RssParser` 只持有 `XmlFetcher`/`XmlParser`；`AndroidXmlParser` **没有任何可变字段**，每次 `parseXML` 都新 `XmlPullParserFactory.newInstance()` 并新建 `XmlPullParser`，没有共享可变状态。4 个协程并发 `parse` 不会串数据，**不需要**加 `Mutex` 或每次新建 parser。
7. **`DateParser` 线程安全**：用的是 `java.time.DateTimeFormatter`（不可变、线程安全），不是 `SimpleDateFormat`。所以 M7 把映射挪到 `Dispatchers.Default` 不会引入新问题。
8. **`catch (e: IOException)` 与 `catch (e: SyncError)` 的先后不影响正确性**：`SyncError` 直接继承 `Exception`，不是 `IOException`，两者互不覆盖；当前顺序只是"读起来像有依赖"，不是 bug（真正的顺序问题在 M6 的 IAE 分支，它是**不可达**，性质不同）。
9. **`feeds` 行的 `WHERE id = :id` 更新在 feed 已被删除时是安全空操作**：不会抛异常，也不会新建行。
10. **`articleDao.insertAll` 的 `OnConflictStrategy.IGNORE` 语义正确**：Room 对集合插入会自动包事务，单条冲突不会导致整批回滚，`(feedId, articleKey)` 唯一索引起的"整行跳过"符合"已读状态永不丢"的设计。

---

## 7. 建议补充的测试用例（对应《测试计划》L2 仓储集成）

| 用例 | 断言 | 覆盖问题 |
| --- | --- | --- |
| 失败回写自身抛错 | Fake `FeedDao` 在 `markFailed`/`updateSyncState` 抛 `SQLiteException` → 其余源仍全部入库，`refreshAll` 不抛异常 | R2（当前会挂） |
| 单源抛 `OutOfMemoryError` | `refreshFeed` 不吞 `Error`（可断言异常向上传播 / 不写 `lastSyncError`） | M1 |
| URL 非法 | `addFeed("abc")` 返回 `SyncError.InvalidUrl`（不是 Parsing、不是 Network） | M6、M2 |
| 重复订阅 | 连续两次 `addFeed` 同一 URL → 第二次失败且 `feeds` 只有 1 行；列表无重复文章 | R1 |
| 304 更新 validator | Mock 304 + `ETag: "v2"` → DB 里 `etag == "v2"` | M9 |
| 取消发生在插入后 | `insertAll` 后取消协程 → 文章与 `lastSyncAt` 同时存在或同时不存在 | M8 |
| 失败不污染成功时间 | 一次成功后一次失败 → `lastSyncAt` 保持第一次的值 | M4、M3 |
| 主线程无映射 | 断言 `mapper` 调用发生在非 Main（如用 `Dispatchers.setMain` 的 `TestDispatcher` 记录） | M7 |

---

## 8. 附：问题索引（按文件）

**FeedRepository.kt**

| 行号 | 问题 |
| --- | --- |
| 33 | G8（`RssParserBuilder()` 空转 client） |
| 35 / 45 / 81 | G5（限流不覆盖 `addFeed`、permit 范围过大） |
| 48-57 | **R1**（三步写库非原子、无重复校验） |
| 55-56 | M8（文章与状态不在一个事务） |
| 59-61 | G3（304 归类为 Http 错误） |
| 63-70 | ✅ 重抛顺序正确；M2（`catch (Exception)` 分类过粗） |
| 67-69 | M2（所有意外异常 → Parsing） |
| 76-85 | **R2**（缺 `supervisorScope`）、G6（全量 `launch` + `Unit` 返回） |
| 88 / 94 / 101 / 111 | M4（`lastSyncAt` 语义） |
| 93-95 / 100-102 | G7（重复 `NonCancellable` 收尾） |
| 99-102 | M8（半完成状态） |
| 107-108 | **M1**（`Throwable` 过宽）、M2（Unknown 未使用） |
| 110-112 | **R2**（回写无兜底）、M3（旧 etag 覆盖） |

**FeedFetcher.kt**

| 行号 | 问题 |
| --- | --- |
| 25-28 | M5（缺 `callTimeout`）、G8（私有 client） |
| 30-39 | M5（`execute()` 不可取消） |
| 33 vs 38 | M6（IAE 逃出 `try`） |
| 41 | M9（304 不回读响应头） |
| 44 | G1（无体积上限）、G2（空 body 静默降级） |
| 50-51 | ✅ `IOException → Network` 正确 |
| 54-57 | M6（不可达分支 + 误判 Network） |

**SyncError.kt**

| 行号 | 问题 |
| --- | --- |
| 8-13 | G4（文案拼 `cause.message`、缺 `kind`/code） |
| 12 | M1/M2（`Unknown` 已定义但从未使用） |

---

*本报告为只读评审产物，未对 `app/src/main` 做任何修改。*
