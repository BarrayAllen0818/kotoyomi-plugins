---
id: nhentai-authenticated-rate-limits
title: "NHentai 登录接口的限流实测"
category: reference
status: active
tags: [nhentai, rate-limit]
created: "2026-11-23T23:33:27"
updated: "2026-11-24T00:03:24"
---

<!-- compiled_truth -->
2026-11-23 23:30–23:33（北京时间），使用同一个已登录的 Chrome 用户资料及网络出口，按插件的 `Authorization: User …` 方式请求 NHentai 的 v2 API。`/api/v2/user` 返回 HTTP 200 和有效的账号资料，确认会话有效；记录只保留状态和限流响应头。

| 接口 | 同一分钟内成功次数 | 首次 HTTP 429 序号 | `X-RateLimit-Limit` |
| --- | ---: | ---: | ---: |
| 搜索 `/api/v2/search` | 20 | 21 | 20 |
| 作品详情 `/api/v2/galleries/{id}` | 45 | 46 | 45 |
| 相关作品 `/api/v2/galleries/{id}/related` | 30 | 31 | 30 |
| 批量标签 `/api/v2/tags/ids` | 15 | 16 | 15 |

上述四次 429 均返回 `X-RateLimit-Remaining: 0` 和 `Retry-After: 60`；成功响应未带限流响应头。搜索仍处于其 60 秒等待期内时，批量标签和相关作品仍有成功请求；标签被限流后相关作品仍成功，相关作品被限流后详情仍成功。搜索在 429 后约 99 秒补测一次恢复为 HTTP 200；未测最早恢复的时刻或窗口算法。

接口额度与[站点定义](https://nhentai.net/api/v2/openapi.json)中此前核查的登录额度一致。按出口 IP 计数来自站点定义，本轮只使用一个出口，未单独验证计数归属。账号和 CDN 配置接口成功返回，但未压到上限；没有测试图片下载、匿名额度或 Android 端行为。此前匿名实测和插件实施背景见 [BUG-005](../../docs/BUG.md#bug-005-相关作品逐词搜索及多个入口未共享网站请求额度)。


## Timeline

- time: 2026-11-23T23:33:27
  kind: decision
  summary: "Created this page: NHentai 登录接口的限流实测"
  source: "2026-11-23 Chrome 登录会话的真实 v2 API 响应"
  affects: [nhentai-authenticated-rate-limits]

- time: 2026-11-23T23:33:27
  kind: decision
  summary: "实测确认登录搜索、详情、相关作品及标签额度，并记录 429 等待和适用范围"
  source: "2026-11-23 23:30–23:33（北京时间）Playwright 请求状态及响应头"
  affects: [nhentai-authenticated-rate-limits]

- time: 2026-11-24T00:03:24
  kind: decision
  summary: "用户基于实测确认将限流判断交给 NH：网站的 429 已直接说明额度耗尽及建议重试时间，取消插件额外等待；该决定不改变先前实测的服务端额度。"
  source: "用户确认的插件 docs/PRD.md「网站错误反馈」"
  affects: [nhentai-authenticated-rate-limits]
