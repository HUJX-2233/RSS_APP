package com.example.rssreader.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.rssreader.domain.model.Article
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 首页：标题栏 + 文章列表 + 手动刷新入口。
 * D0 起标题栏多两个入口图标（订阅管理 → feeds、添加订阅 → addfeed），
 * 列表与刷新行为保持 M1 不变；M2 再加 FilterBar（未读/全部）、下拉刷新、
 * 详情页跳转与已读半透明样式。
 *
 * 为什么入口放 AppBar 而不是底部导航栏：
 * 「订阅管理 / 添加订阅」是偶尔用的设置类入口，不是与首页平级的主视图；
 * 底部 Tab 应该留给 M2 的首页/收藏这类需要频繁切换的主页面。
 *
 * 为什么这里只暴露 onOpenXxx 回调、不直接传 NavController：
 * 页面只声明“用户点了哪个入口”，具体跳到哪条路由由 MainActivity 的导航图决定。
 * 这样 HomeScreen 仍是纯展示组件，可以脱离 NavHost 单独预览和测试。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    articles: List<Article>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenFeeds: () -> Unit,
    onOpenAddFeed: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RSS 阅读器") },
                actions = {
                    // 顺序按使用频率从低到高排：管理 → 添加 → 刷新，最常用的刷新留在最右侧
                    // 靠近拇指，单手操作时最容易点到。
                    IconButton(onClick = onOpenFeeds) {
                        Icon(Icons.Default.Settings, contentDescription = "订阅管理")
                    }
                    IconButton(onClick = onOpenAddFeed) {
                        Icon(Icons.Default.Add, contentDescription = "添加订阅")
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { padding ->
        if (articles.isEmpty()) {
            EmptyHint(isRefreshing)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                items(articles, key = { it.id }) { article ->
                    ArticleRow(article)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(isRefreshing: Boolean) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isRefreshing) {
                CircularProgressIndicator()
                Text("正在拉取订阅…")
            } else {
                Text("还没有文章，点右上角刷新试试")
            }
        }
    }
}

@Composable
private fun ArticleRow(article: Article) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "${article.feedTitle} · ${formatDate(article.pubDate)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = article.title ?: "(无标题)",
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        article.summary?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatDate(millis: Long?): String {
    // 解析失败的日期置空沉底，界面如实显示"时间未知"
    if (millis == null) return "时间未知"
    return ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}
