package com.example.rssreader.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [FeedEntity::class, ArticleEntity::class],
    version = 1,
    // schema 导出成 JSON 存档：答辩时能直接展示表结构，后续演进也有据可依
    exportSchema = true,
)
abstract class RssDatabase : RoomDatabase() {
    abstract fun feedDao(): FeedDao
    abstract fun articleDao(): ArticleDao

    companion object {
        fun build(context: Context): RssDatabase =
            Room.databaseBuilder(context.applicationContext, RssDatabase::class.java, "rss.db").build()
    }
}
