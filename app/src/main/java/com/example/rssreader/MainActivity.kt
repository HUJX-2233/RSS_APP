package com.example.rssreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.rssreader.ui.addfeed.AddFeedScreen
import com.example.rssreader.ui.detail.DetailScreen
import com.example.rssreader.ui.feeds.FeedsScreen
import com.example.rssreader.ui.home.HomeScreen
import com.example.rssreader.ui.home.HomeViewModel
import com.example.rssreader.ui.theme.RssReaderTheme
import kotlinx.coroutines.launch

/**
 * 唯一的 Activity：负责组装 ViewModel、DEBUG 种子源、声明导航图，并把状态交给 Compose 渲染。
 *
 * 为什么 M1 时不需要 Navigation、D0 才开始用：
 * 只有一个页面时直接 setContent { HomeScreen() } 最省事；一旦出现二级页面，
 * 返回键、页面栈、进程被系统回收后的栈恢复都得有人管，自己写状态机就是重复造轮子，
 * 所以这里换成官方的 NavHost——它天然处理这些边缘情况。
 */
class MainActivity : ComponentActivity() {

    // 手动 DI 的组装点：ViewModel 的构造参数在这里注入。
    // 作用域仍是 Activity：首页数据挂在 Activity 上，来回切页面不会重建 ViewModel，
    // 也就不会因为一次“去订阅管理再回来”白白重跑一遍加载。
    private val viewModel: HomeViewModel by viewModels {
        viewModelFactory {
            initializer {
                val container = (application as RssApp).container
                HomeViewModel(container.feedRepository, container.articleRepository)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 35+ 强制 edge-to-edge：不处理 insets 的话标题会被状态栏压住
        enableEdgeToEdge()
        seedDebugFeedIfEmpty()
        setContent {
            // 主题包在 NavHost 外层：四个页面共用一套配色，
            // 每个页面内部不必再套 MaterialTheme，换主题只改这一处。
            RssReaderTheme {
                AppNavHost(viewModel = viewModel)
            }
        }
    }

    /**
     * M1 演示用：首次启动自动种一个真实源，先证明"拉取→解析→入库→上屏"闭环。
     * M2 做出「添加订阅」界面后移除。
     */
    private fun seedDebugFeedIfEmpty() {
        if (!BuildConfig.DEBUG) return
        val container = (application as RssApp).container
        lifecycleScope.launch {
            if (container.feedRepository.countFeeds() == 0) {
                container.feedRepository.addFeed("https://sspai.com/feed")
            }
        }
    }
}

/**
 * 全 App 的导航图，集中在一处声明——答辩时一眼能看出「有哪些页面、谁跳谁」。
 *
 * 为什么暂时放在 MainActivity 同文件里、而不是再建一个 ui/navigation 包：
 * 现在只有四条路由，再拆一个包只会增加跳转成本；
 * 等 M2 页面变多、出现参数校验或深链接时再抽出去，那时才有拆的价值。
 *
 * @param viewModel 首页的 ViewModel。因为项目是手动 DI（没有 Hilt），
 *                  依赖只能从 Activity 往下传，NavHost 在这里充当了“注入通道”。
 */
@Composable
private fun AppNavHost(viewModel: HomeViewModel) {
    // rememberNavController() 让控制器在重组之间保持同一个实例；
    // 它内部同时维护回退栈并做 SavedState 保存，所以旋转屏幕后栈不会丢。
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
    ) {
        composable(Routes.HOME) {
            // 状态订阅刻意放在目的地内部，而不是 NavHost 外层：
            // 离开首页时这个 lambda 会退出组合，Flow 订阅随之取消；
            // 回到首页时 Room 的 Flow 会重新发一次最新值，
            // 拿到的是“现在”的文章，而不是离开那一刻的旧快照。
            val articles by viewModel.articles.collectAsState()
            val isRefreshing by viewModel.isRefreshing.collectAsState()
            HomeScreen(
                articles = articles,
                isRefreshing = isRefreshing,
                onRefresh = viewModel::refresh,
                // 首页只发出“要跳哪”的意图，不自己操作导航栈：
                // 这样 HomeScreen 仍然可以脱离 NavHost 单独预览/测试。
                onOpenFeeds = { navController.navigate(Routes.FEEDS) },
                onOpenAddFeed = { navController.navigate(Routes.ADD_FEED) },
            )
        }

        composable(Routes.FEEDS) {
            // popBackStack() 是“回到上一层”，等价于系统返回键；
            // 不用 navigate(HOME) 是因为那会再压一个首页实例，反复点会把栈撑大。
            FeedsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.ADD_FEED) {
            AddFeedScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.DETAIL_PATTERN,
            arguments = listOf(
                // 声明参数类型后，Navigation 在进入页面前就会把非法值拦下来，
                // 不用在每个使用点各写一遍 toLongOrNull() 和容错分支。
                navArgument(Routes.ARG_ARTICLE_ID) { type = NavType.LongType },
            ),
        ) { backStackEntry ->
            // 从路由参数取出 id：这是列表页与详情页之间唯一的契约，
            // 详情页据此回数据库取正文（M2 实现）。
            val articleId = backStackEntry.arguments?.getLong(Routes.ARG_ARTICLE_ID) ?: 0L
            DetailScreen(
                articleId = articleId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/**
 * 路由表：所有路由字符串只在这里出现一次。
 *
 * 为什么不用魔法字符串：写错 "feeds" 编译期发现不了，要到运行时 navigate() 才抛异常；
 * 收敛成常量后改名只需改一处，IDE 也能安全重命名。
 */
private object Routes {
    const val HOME = "home"
    const val FEEDS = "feeds"
    const val ADD_FEED = "addfeed"

    // 详情页是带参数的路由：{id} 是占位符，真实跳转时用数字替换，
    // 写法是 navController.navigate("detail/${article.id}")，
    // 入口在 M2 的文章列表点击回调里补上（D0 的首页保持原有行为，不接这个跳转）。
    const val DETAIL_PATTERN = "detail/{id}"
    const val ARG_ARTICLE_ID = "id"
}
