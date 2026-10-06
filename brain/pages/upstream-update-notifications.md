---
id: upstream-update-notifications
title: "上游更新提醒与手动查询入口"
category: decision
status: active
tags: [upstream, notifications]
created: "2026-10-06T14:57:12"
updated: "2026-10-06T14:57:26"
---

<!-- compiled_truth -->
## 已确认的需求

使用 GitHub 托管的 Actions，每天北京时间 06:00 检查 skepsun/kototoro-parsers 的 master 分支，仅在发现新提交时通过 BarrayAllen0818/kotoyomi-plugins 的 GitHub Issue 提醒。无更新不重复通知；检查失败不得报告为无更新。仅提醒，不自动合并、构建或发布插件。

## 手动查询约定

用户在本项目说“检查上游更新”时，读取本页记录的入口，查询远端提醒和最近检查状态，汇报检查时间、结果、提交摘要及链接。不要在每次开始任务时自动查询，也不要增加 Codex 定时轮询或修改 AGENTS.md。

## 入口状态

需求位置：PRD.md REQ-004。目标仓库：https://github.com/BarrayAllen0818/kotoyomi-plugins 。

提醒功能尚未实现，具体 Issue、工作流名称和查询命令尚未建立。当前不能声称已有可用提醒入口；实现后必须在本页补齐准确入口和使用方法，供后续 Codex 会话发现。

2026-10-06 只读 API 核实仓库 Actions enabled=false。后续上线需要启用 Actions，并区分配置完成、实际运行验证与用户验收。


## Timeline

- time: 2026-10-06T14:57:12
  kind: decision
  summary: "Created this page: 上游更新提醒与手动查询入口"
  source: "2026-10-06 用户确认；PRD.md REQ-004"
  affects: [upstream-update-notifications]

- time: 2026-10-06T14:57:26
  kind: decision
  summary: "记录每日六点仅提醒及按用户口令查询的约定，明确入口尚未实现"
  source: "2026-10-06 当前对话用户确认；PRD.md REQ-004"
  affects: [upstream-update-notifications]
