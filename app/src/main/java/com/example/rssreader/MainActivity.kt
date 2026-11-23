package com.example.rssreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.rssreader.ui.home.HomeScreen
import com.example.rssreader.ui.home.HomeViewModel
import com.example.rssreader.ui.theme.RssReaderTheme
import kotlinx.coroutines.launch

/**
 * 唯一的 Activity：只做三件事——组装 ViewModel、DEBUG 种子源、把状态交给 Compose 渲染。
 */
class MainActivity : ComponentActivity() {

    // 手动 DI 的组装点：ViewModel 的构造参数在这里注入
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
            RssReaderTheme {
                val articles by viewModel.articles.collectAsState()
                val isRefreshing by viewModel.isRefreshing.collectAsState()
                HomeScreen(
                    articles = articles,
                    isRefreshing = isRefreshing,
                    onRefresh = viewModel::refresh,
                )
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
