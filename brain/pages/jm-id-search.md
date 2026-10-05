---
id: jm-id-search
title: "JM 编号搜索"
category: project
status: active
tags: [jm, search]
created: "2026-10-05T17:50:04"
updated: "2026-10-05T17:50:20"
---

<!-- compiled_truth -->
## 当前需求

PRD.md 的 REQ-001 为 JM 编号搜索：输入作品编号后返回对应作品的搜索结果卡片；用户点击卡片，按现有流程进入详情。普通关键词搜索保持现有行为。本次仅修改插件，不修改宿主。

## 范围变更

用户于 2026-10-05 在明确自动跳转需要修改宿主后，要求“修改prd和方案，不要求跳转详情页”。原 [[jm-id-direct-detail]] 已归档，其自动跳转完成判断及宿主配套方案不再适用。

## 方案与授权

修订方案仍维护在 .gantry/jm-id-direct-detail.md，沿用历史文件路径及 feat/jm-id-direct-detail 分支名，不代表仍要求自动跳转。用户已授权本次需求与方案文档更新；编号识别格式、筛选优先级和错误处理仍是待审方案，未授权功能实现。沿用现有 List<Content> 契约和作品身份，保护收藏关联；宿主继续负责现有结果展示和点击详情行为。


## Timeline

- time: 2026-10-05T17:50:04
  kind: decision
  summary: "Created this page: JM 编号搜索"
  source: "2026-10-05 用户要求修改 PRD 和方案，取消详情自动跳转"
  affects: [jm-id-search]

- time: 2026-10-05T17:50:20
  kind: decision
  summary: "当前需求缩减为插件编号搜索返回卡片，取消自动跳转及宿主改动"
  source: "用户本轮明确要求；PRD.md REQ-001；.gantry/jm-id-direct-detail.md"
  affects: [jm-id-search]
