package com.example.rssreader.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.rssreader.data.repository.ArticleRepository
import com.example.rssreader.data.repository.FeedRepository
import com.example.rssreader.domain.model.Article
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 首页状态层：UI 只看这里的 StateFlow，永不直接碰网络和数据库。
 */
class HomeViewModel(
    private val feedRepository: FeedRepository,
    articleRepository: ArticleRepository,
) : ViewModel() {

    /** 文章列表：Room Flow → UI 状态，库一变列表自动更新 */
    val articles: StateFlow<List<Article>> = articleRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        // M1：进入即拉一次，保证首次演示就有数据；M3 换成 WorkManager 周期调度
        refresh()
    }

    fun refresh() {
        if (_isRefreshing.value) return   // 防重复触发
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                feedRepository.refreshAll()
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}
