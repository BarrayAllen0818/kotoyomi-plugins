---
id: jm-id-direct-detail
title: "JM 编号搜索直达详情"
category: project
status: archived
tags: [jm, search]
created: "2026-10-05T17:40:32"
updated: "2026-10-05T17:49:56"
---

<!-- compiled_truth -->
## 已确认需求

PRD.md 的 REQ-001：用户在 JM 来源搜索框提交作品编号后，直接进入对应作品详情页，无需点击搜索结果；普通关键词搜索保持原行为。用户于 2026-10-05 在需求落档交付后确认需求内容，并明确同意进入方案阶段。

## 已核实职责边界

插件的 ContentParser.getList 返回 List<Content>，不提供页面导航；JM 的 search 当前请求 /search。宿主的来源搜索页通过 RemoteListViewModel 装载列表，已有 onOpenContent 一次性详情事件可供方案复用。因此完成自动导航需要宿主配合，单独返回一条插件搜索结果不等于完成直达详情需求。

## 约束与方案入口

方案统一维护在 .gantry/jm-id-direct-detail.md。宿主配套修改及具体识别、失败、筛选和导航时机仍为待审方案；需求确认不代表方案批准或实现授权。站点查询归插件，界面导航归宿主；兼容现有作品身份以保护收藏关联。


## Timeline

- time: 2026-10-05T17:40:32
  kind: decision
  summary: "Created this page: JM 编号搜索直达详情"
  source: "2026-10-05 用户确认 PRD REQ-001 及进入方案阶段"
  affects: [jm-id-direct-detail]

- time: 2026-10-05T17:40:51
  kind: decision
  summary: "记录已确认的直达详情需求及当前源码核实的插件与宿主职责边界"
  source: "PRD.md；JmComicParser.kt；ContentParser.kt；宿主 AppSearchContentListRoute 与 RemoteListViewModel"
  affects: [jm-id-direct-detail]

- time: 2026-10-05T17:49:56
  kind: reversal
  summary: "2026-10-05 用户取消自动跳转详情要求；原直达详情需求及宿主配套方案撤回，当前 REQ-001 改为编号搜索返回作品卡片，参见 [[jm-id-search]]。"
  source: brain archive-page
  affects: [jm-id-direct-detail]
