package com.example.rssreader.ui.feeds

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 「订阅管理」页骨架：D0 只占住路由 feeds，列表与删除留到 M2。
 *
 * 为什么它和「添加订阅」是两个页面而不是一个：
 * 管理页是“看有哪些源、删掉不要的”，添加页是“录入新源”，两者的入口、返回行为和
 * 将来的 ViewModel 都不同。拆成两条路由后，从首页可以直接进添加页，少一次点击。
 *
 * 为什么单独一个包 ui/feeds：
 * 订阅管理与首页的文章流是两种数据（Feed 列表 vs Article 列表），
 * 分包是为了将来 ViewModel 各管各的，避免一个 HomeViewModel 越滚越大。
 *
 * onBack 用回调而不是直接依赖 NavController：页面不关心导航栈，只发出“要返回”的意图。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedsScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("订阅管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "订阅管理页（骨架）",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "M2 在这里列出全部订阅源，并支持删除。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
