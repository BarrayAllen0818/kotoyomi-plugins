---
id: plugin-release-versioning
title: "插件版本与验收发布约束"
category: decision
status: active
tags: [release, versioning]
created: "2026-10-06T13:59:49"
updated: "2026-10-06T13:59:49"
---

<!-- compiled_truth -->
## 已确认的交付原则

插件发布版本独立于上游源码版本和 GitHub Actions 运行编号。上游同步或本地修复都从远端 repo 的当前索引 code 递增，version 保持 1.0.<code>，避免手动与自动发布使用不同计数来源造成冲突或倒退。

保持既有仓库 URL、index.min.json 和 apk/plugin.jar；插件产物只进入 repo 分支。发布保留 Git 历史，不使用 force_orphan 或强推。

先发布验收产物，用户验收通过后合并源码主线；主线合并继续使用已经验收的产物，不重复构建发布或另增版本，也不依赖每次人工填写 skip ci。远端索引和下载哈希核对与用户验收分别记录。

## 与上游同步的边界

同步上游时保留这些个人交付约束，不能仅因无文本冲突或上游修改工作流就认定发布兼容。解析器、宿主契约及具体上游同步任务保持各自范围。

## 记录与状态边界

权威需求为 PRD.md REQ-003。用户已确认上述目标；发布工作流的具体触发、并发和失败处理待方案阶段确定。本页记录约束，不代表流程已经实现。


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
