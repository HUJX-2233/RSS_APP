package com.example.rssreader

import android.app.Application
import android.content.Context
import com.example.rssreader.data.local.RssDatabase
import com.example.rssreader.data.remote.FeedFetcher
import com.example.rssreader.data.repository.ArticleRepository
import com.example.rssreader.data.repository.FeedRepository

class RssApp : Application() {

    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/**
 * 手动依赖容器：数据库、网络、两个仓库都是进程级单例，构造关系一目了然。
 * 课程项目这个规模上 Hilt 是为简历而非为项目——手动注入足够且更好讲。
 */
class AppContainer(context: Context) {
    val database: RssDatabase = RssDatabase.build(context)
    // 仓库额外拿到 database 本体，是为了用 withTransaction 把"加订阅"的三步写库合并成原子操作
    val feedRepository: FeedRepository =
        FeedRepository(database, database.feedDao(), database.articleDao(), FeedFetcher())
    val articleRepository: ArticleRepository = ArticleRepository(database.articleDao())
}
