package com.example.rssreader.ui.detail

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
 * 「文章详情」页骨架：D0 只占住路由 detail/{id}，正文渲染留到 M2。
 *
 * 为什么参数是 Long 类型的 articleId，而不是直接把整个 Article 对象传进来：
 * 1) 路由参数只能是可序列化的简单值，Room 的实体对象没法跟着路由走；
 * 2) 更关键的是“单一数据源”：详情页拿着 id 回数据库取当前数据，
 *    即使列表页已经刷新、文章被更新，详情页显示的也不会是几秒前的旧快照；
 * 3) id 是 Long 而数据库主键就是 Long，用 String 传再转数字等于把类型错误推迟到运行时。
 *
 * 为什么页面里要把 articleId 显示出来：
 * 这是 D0 唯一能肉眼验证「detail/{id} 的参数有没有串位」的手段——
 * 从列表点进去时如果显示的不是那一篇的 id，说明路由拼错了。
 * M2 用真实正文替换掉这行占位文案即可。
 *
 * onBack 用回调而不是直接拿 NavController：页面只管发出“要返回”的意图，
 * 返回后落到哪一层由导航图决定，页面本身可以脱离 NavHost 单独预览。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    articleId: Long,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("文章详情") },
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
                text = "文章 ID：$articleId",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "详情页骨架：M2 会用这个 id 从数据库取正文，在这里渲染并标记已读。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
