package com.example.rssreader.ui.addfeed

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
 * 「添加订阅」页骨架：D0 只占住路由 addfeed，表单与校验留到 M2。
 *
 * 为什么先把空页面建出来：
 * 导航骨架要能“跑通”就必须有真实的落点。只加 NavHost 不建页面，编译能过，
 * 但点图标会白屏——那等于没验证过导航。空页面能证明「图标 → 路由 → 页面」整条链路是通的。
 *
 * 为什么单独一个包 ui/addfeed：
 * 这个页面将来会有自己的 ViewModel（输入校验、去重、试拉一次 feed 确认可用），
 * 把它和首页分开，M2 加代码时不会牵动 home 包，也方便答辩时按功能模块讲目录结构。
 *
 * 为什么用回调 onBack 而不是让页面直接拿 NavController：
 * 页面只负责“点返回了”这件事，至于返回到哪、栈里还有几层，是导航层的职责。
 * 这样 AddFeedScreen 可以脱离 NavHost 单独预览/测试。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFeedScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("添加订阅") },
                navigationIcon = {
                    // 二级页必须给显式返回入口：全面屏手势下系统返回键并不总是可见，
                    // 左上角箭头是 Android 用户最习惯的退出方式。
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
        // 用 Scaffold 给的 padding 而不是自己算状态栏高度：insets 由组件统一处理，
        // 换主题/换设备时不用改页面代码。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "添加订阅页（骨架）",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "M2 在这里放 RSS 地址输入框、格式校验和“保存并试拉一次”的按钮。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
