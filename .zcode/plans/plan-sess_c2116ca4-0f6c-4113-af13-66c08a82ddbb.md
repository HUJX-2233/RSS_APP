# 计划导出为 Markdown 文档

## Context

完整实施计划已在对话中定稿（含三个参考项目研读结论），用户要求导出为 md 文档存档。目标路径已确认：`D:\android-app\RSSAPP\docs\开发计划.md`（项目 docs 目录）。plan mode 当前禁止写文件，需要解锁后执行一次写入。

## 要写入的内容

《本地优先 Android RSS 阅读器 · 开发计划》，共 11 节：

1. **项目概述** — MVP 主链路 + 答辩演示场景（竞赛信息订阅叙事）
2. **已确认的关键决策** — 解析用 prof18 RSS-Parser 6.2.0（API 已核实）、不做通知、开发期替代源、手动 DI、协作模式（我实现+讲解）、不到三周
3. **本机构建环境** — 已探查确认就绪（JDK 17 / SDK / Gradle 9.4.1 缓存 / Pixel 6 AVD / todo-app 脚手架平移）
4. **参考项目研读结论** — Feeder（schema 去重、WorkManager 2.6.33 模式、逐源错误）、Capyreader（条件请求、guid→link 兜底、信号量限流）、ReadYou（FilterBar、已读 alpha 0.5、WebView 详情、SAF OPML）；均只借设计不搬代码（GPL/AGPL）
5. **技术栈与版本基线** — Kotlin 2.2.10 / Compose BOM 2026.02.01 / Room 2.8.5 / minSdk 26 / rssparser 6.2.0 / Coil 3.2.0 / WorkManager 2.10.x
6. **数据库设计** — feeds（含 etag/lastModified 条件请求列、lastSyncError 逐源错误列）+ articles（唯一索引 (feedId, articleKey)，IGNORE 入库保已读）
7. **项目结构** — domain/data/opml/worker/ui 分层文件清单
8. **关键实现要点** — 添加先验证、刷新流程、WorkManager 调度、UI 模式、OPML、开发期测试源（sspai / V2EX Atom / 36kr）
9. **执行节奏** — M1 闭环 → M2 阅读体验 → M3 加分项+答辩，每步带讲解点
10. **验证方式** — 构建+单测、AVD 全链路（含断网离线）、双格式、OPML 往返、模拟答辩
11. **答辩准备清单** — RSSHub 竞赛路由、Changedetection、离线预同步、演示策略等 checklist

## 执行步骤

1. 创建 `D:\android-app\RSSAPP\docs\` 目录并写入上述 md 文档（内容即上轮 ExitPlanMode 计划全文 + 答辩准备清单）
2. 向用户确认文档已就位，供其 review / 存档 / 打印

## 后续（本次不执行）

计划获批后进入 M1 开发：Gradle 脚手架（平移 todo-app 配置）→ Room 两表 → 拉取解析入库 → 首页列表。