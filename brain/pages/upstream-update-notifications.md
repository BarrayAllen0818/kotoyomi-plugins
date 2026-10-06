---
id: upstream-update-notifications
title: "上游更新提醒与手动查询入口"
category: decision
status: active
tags: [upstream, notifications]
created: "2026-10-06T14:57:12"
updated: "2026-10-06T15:43:03"
updated: "2026-10-06T15:51:26"
---


<!-- compiled_truth -->
## 已确认的提醒语义

GitHub 托管 Actions 每天北京时间 06:00 比较 skepsun/kototoro-parsers/master 与 BarrayAllen0818/kotoyomi-plugins/master 的提交历史。只要上游原提交仍未被个人远端 master 包含，就每天提醒；全部合并并推送后下次成功检查停止。仅 fetch、本地合并未 push、其他分支包含、读过或关闭 Issue 都不算完成。同日重试去重不影响次日提醒。检查失败不等于已同步。

初始已同步基线固定为 ca313756e395b5ddbd201e01cc01ece01078d15c（用户选择 B），不在首次运行时更换为届时最新提交。运行时直接比较两端完整历史，不以通知水位决定同步状态。仅提醒，不自动合并、构建或发布插件。

## 手动查询入口

用户在本项目说“检查上游更新”时，在项目根目录运行：

```powershell
python -B .github/scripts/check_upstream.py --status
```

使用既有 GitHub CLI 登录或 GH_TOKEN，默认只读，不发送消息、不触发工作流、不 fetch 到用户仓库。项目位于 D:/B_Files/A_Work/AgentWorkspace/kotoyomi-plugins。

固定 Issue：https://github.com/BarrayAllen0818/kotoyomi-plugins/issues/1 。
工作流：.github/workflows/check-upstream.yml，显示名称 Check upstream commits。
工作流入口：https://github.com/BarrayAllen0818/kotoyomi-plugins/actions/workflows/check-upstream.yml 。

解释输出时同时报告 latest_run 和 last_successful_check：最新失败不能被旧成功掩盖；无运行、日志不可得或 detail_error 时明确未知，不从历史评论推断已同步。stale=true 表示超过 30 小时未成功检查。结果只代表截至 checked_at 的远端快照，不是手动查询当刻的实时 Git 比较。

synced 表示该次检查远端 master 已包含全部上游提交；pending 表示仍需合入，daily_reminder_exists 只代表今日已通知，不能说已经完成。不要每次开始任务自动查询，不增加 Codex 定时轮询，不修改 AGENTS.md。

## 实现与部署边界

需求 PRD.md REQ-004，稳定方案 .gantry/upstream-update-notifier.md。用户已确认整体方案并在单独请求后授权实现。检查脚本、工作流及 31 个离线测试已实现；Issue #1 已创建并绑定。真实远端隔离比较得到个人 master 260a9c36c437506309c867faf4eab009cec6d24b 包含上游 ca313756，pending_count=0；这是 2026-10-06 验证快照，不能当作未来现状。

2026-10-06 用户在交付后确认审阅验收并授权合并上线。实现已 no-ff 合入 master 并推送（f4fabb5d），仓库 Actions enabled=true，Check upstream commits 工作流 active。每天北京时间 06:00 的调度已配置启用。

首次真实手动运行成功：https://github.com/BarrayAllen0818/kotoyomi-plugins/actions/runs/37432055483 。runner 上 31 个离线测试通过；15:49:23+08:00 比较个人 master f4fabb5d 与上游 ca313756，synced、pending_count=0，因此没有更新评论。--status 已成功读取并解析该运行，stale=false。Issue #1 已更新上线说明。

后续自然定时触发、有待合入提交时的真实 GITHUB_TOKEN 发评论及邮件/手机送达仍需相应事件验证，不将 synced 的无通知运行当作发消息验证。需要邮件或手机提醒时用户须自行确认 GitHub 的 Issue 订阅与通知设置。当前远端是否同步须查询新证据，不将上述快照视为永久结果。


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

- time: 2026-10-06T15:24:39
  kind: decision
  summary: "用户批准整体方案，完成注解与稳定性复核，等待独立实现授权"
  source: "2026-10-06 完整方案解释后用户回复：确认"
  affects: [upstream-update-notifications]

- time: 2026-10-06T15:43:03
  kind: decision
  summary: "实现手动查询入口并绑定 Issue 1，记录离线和远端只读证据，明确未上线待验收"
  source: "2026-10-06 用户实现授权；check_upstream.py；Issue #1"
  affects: [upstream-update-notifications]

- time: 2026-10-06T15:51:26
  kind: decision
  summary: "用户验收后合并推送并启用 Actions，首次真实运行和手动查询成功"
  source: "2026-10-06 用户确认；GitHub run 37432055483"
  affects: [upstream-update-notifications]
