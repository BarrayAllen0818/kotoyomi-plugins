---
id: upstream-update-notifications
title: "上游更新提醒与手动查询入口"
category: decision
status: active
tags: [upstream, notifications]
created: "2026-10-06T14:57:12"
updated: "2026-10-06T15:16:05"
---

<!-- compiled_truth -->
## 已确认的提醒语义

GitHub 托管 Actions 每天北京时间 06:00 检查 skepsun/kototoro-parsers/master 的提交是否已被 BarrayAllen0818/kotoyomi-plugins/master 的历史包含。只要仍有未合入提交，即使上游没再更新、昨天已提醒，也每天继续提醒；全部合并并推送到个人远端 master 后，下次检查停止提醒。

用户定义的“已拉取”是合并且推送到远端并保留上游原始提交记录。仅 fetch、本地合并未 push、其他远端分支包含或单纯阅读提醒，都不算完成。不要以通知水位、已读标记或本地状态代替远端历史。同日重试可以去重，但不能抑制次日提醒。检查失败不等于已同步。

## 初始基线

2026-10-06 用户将初始选项改为 B：指定当时本地已拉取的 upstream/master，即 ca313756e395b5ddbd201e01cc01ece01078d15c。不取部署或首次定时运行时最新提交来跳过尚未合入的变化。该 SHA 是初始已同步证据，后续判断始终比较两条远端 master，不需要人工更新本地水位。

## 手动查询约定

用户在本项目说“检查上游更新”时，按本页入口读取远端提醒及最近检查结果，说明检查时间、未合入提交和证据链接；不在每次任务开始时自动查询，不增加 Codex 定时轮询，不修改 AGENTS.md。旧提醒只是历史，不能据此断定当前尚未合并；结果过期或失败时如实说明。仅提醒，不自动合并、构建或发布插件。

## 入口状态

需求为 PRD.md REQ-004，方案为 .gantry/upstream-update-notifier.md。目标仓库：https://github.com/BarrayAllen0818/kotoyomi-plugins 。工作流、脚本和提醒 Issue 尚未建立，不能声称有可用入口；实现后在此补齐准确 Issue URL、工作流和命令。

2026-10-06 当前 API 核实 Actions enabled=false，仍未启用。方案已按远端合入语义修订，整体技术方案及实现授权尚未取得。此时个人远端 master=260a9c36c437506309c867faf4eab009cec6d24b，包含上游 ca313756，无待合入项；该结果是当时快照，后续查询需获取当前证据。


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

- time: 2026-10-06T15:00:44
  kind: decision
  summary: "用户确认 REQ-004 需求阶段完成并进入方案阶段；提醒入口尚未实现，方案批准与实现授权未取得"
  source: "2026-10-06 需求交付后用户回复：确认"
  affects: [upstream-update-notifications]

- time: 2026-10-06T15:16:05
  kind: decision
  summary: "按用户澄清改为持续提醒未合并推送的提交，确认 B 初始基线，撤销通知即推进水位的旧结论"
  source: "2026-10-06 用户：上游提交合并并推送到远端，远端有上游提交记录"
  affects: [upstream-update-notifications]

- time: 2026-10-06T15:16:05
  kind: reversal
  summary: "撤销旧的相同更新不重复提醒规则：跨日持续提醒，完成以个人远端 master 包含原始上游提交为准；初始 A 改为指定 ca313756 的 B"
  source: "2026-10-06 用户连续澄清"
  affects: [upstream-update-notifications]
