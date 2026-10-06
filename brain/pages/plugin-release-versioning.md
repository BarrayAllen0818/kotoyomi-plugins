---
id: plugin-release-versioning
title: "插件版本与验收发布约束"
category: decision
status: active
tags: [release, versioning]
created: "2026-10-06T13:59:49"
updated: "2026-10-06T14:11:34"
---

<!-- compiled_truth -->
## 已确认的交付原则

插件发布版本独立于上游源码版本和 GitHub Actions 运行编号。上游同步或本地修复都从远端 repo 的当前索引 code 递增，version 保持 1.0.<code>，避免不同计数来源造成冲突或倒退。

保持既有仓库 URL、index.min.json 和 apk/plugin.jar；插件产物只进入 repo 分支。发布保留 Git 历史，不使用 force_orphan 或强推。

先发布验收产物，用户验收通过后合并源码主线；主线合并继续使用已经验收的产物，不重复构建发布或另增版本，也不依赖每次人工填写 skip ci。远端索引和下载哈希核对与用户验收分别记录。

## 统一入口与失败边界

本地已有验证产物优先复用，Actions 仅显式 workflow_dispatch 构建发布，两者使用 .github/scripts/publish_plugin.py。默认仅准备本地候选，--push 才发布；读取远端索引，相同插件字节跳过提交和递增。普通推送拒绝竞争导致的非快进，保留候选供检查，不自动覆盖或重试。

发布后核对原 URL 的索引与下载 SHA-256。推送成功但 HTTP 未验证时分别报告，不能按失败提示回退历史或直接重增版本。操作说明在 .github/PLUGIN_RELEASE.md，权威需求为 PRD.md REQ-003。

## 与上游同步的边界

同步上游时保留个人交付约束，重点检查 release.yml 是否重新引入主线自动发布、run_number 编号或 force_orphan。发布流程维护本身不需要消耗新插件版本，验收过的产物保持不变。

## 任务流程例外

2026-10-06 用户明确要求本任务“不需要用该skill”，指 dev-workflow；按已确认发布目标直接实施，不使用其阶段确认门槛。此例外仅针对本任务，不修改全局规则，不撤销 Git 策略和验收后合并边界。


## Timeline

- time: 2026-10-06T13:59:49
  kind: decision
  summary: "Created this page: 插件版本与验收发布约束"
  source: "2026-10-06 用户确认统一发布流程；PRD.md REQ-003"
  affects: [plugin-release-versioning]

- time: 2026-10-06T13:59:49
  kind: decision
  summary: "记录用户确认的独立版本与同产物验收发布约束，具体实现待设计"
  source: "2026-10-06 本对话用户确认；PRD.md REQ-003"
  affects: [plugin-release-versioning]

- time: 2026-10-06T14:11:34
  kind: decision
  summary: "落实共用发布入口与失败边界，并记录本任务停用 dev-workflow 的明确指令"
  source: "2026-10-06 用户明确指令；REQ-003 实施与 .github/PLUGIN_RELEASE.md"
  affects: [plugin-release-versioning]
