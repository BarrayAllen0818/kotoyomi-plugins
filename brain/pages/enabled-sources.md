---
id: enabled-sources
title: "用户已启用的内容来源"
category: project
status: active
tags: [sources, user-preferences]
created: "2026-11-09T21:09:48"
updated: "2026-11-14T06:38:54"
---


<!-- compiled_truth -->
## 已启用清单

用户当前确认启用以下七个来源；HA 于 2026-11-14 更正为 Hanime1（hanime1.me）。

| 用户简称 | 来源名称 | 已明确的域名 |
|---|---|---|
| JM | JM／禁漫 | 未指定 |
| NH | nhentai | 未指定 |
| WN | WNACG | 未指定 |
| HI | Hitomi.La | hitomi.la |
| HA | Hanime1 | hanime1.me |
| EH | ExHentai | e-hentai.org、exhentai.org |
| Rule34 | Rule34Video | rule34video.com |

HA 指 Hanime1；Rule34 指 Rule34Video（rule34video.com）。EH 使用支持 e-hentai.org 和 exhentai.org 的 ExHentai 插件。本表只记录用户明确启用的七个来源。

漫画与视频的作品信息字段表、排除日期和网站运营资料的范围，以及各网站实际提供情况，统一见 [EDD/PRD.md 的作品搜索信息范围](../../EDD/PRD.md#作品搜索信息范围)。Hanime.tv 的网站核查不适用于 Hanime1。

## 关联规则

合并前的影响说明要求见项目根目录 `AGENTS.md` 的“工作流与提交”章节“已启用来源影响说明”条目。


## Timeline

- time: 2026-11-09T21:09:48
  kind: decision
  summary: "Created this page: 用户已启用的内容来源"
  source: "2026-11-09 用户确认启用清单及 HI、HA 对应关系"
  affects: [enabled-sources]

- time: 2026-11-09T21:10:11
  kind: decision
  summary: "记录用户确认的 JM、NH、WN、HI、HA 启用清单及完整名称，明确 HA 为 hanime.tv"
  source: "2026-11-09 当前对话用户来源确认及写入授权"
  affects: [enabled-sources]

- time: 2026-11-14T05:31:57
  kind: decision
  summary: "补充已启用的 EH 与 Rule34Video，清单更新为七个来源"
  source: "2026-11-14 用户要求记下 EH、Rule34 并确认写入预览"
  affects: [enabled-sources]

- time: 2026-11-14T06:38:54
  kind: decision
  summary: "按用户更正将 HA 改为 Hanime1，并关联已确认的漫画与视频字段表"
  source: "2026-11-14 用户明确更正 Hanime 为 Hanime1，要求去掉日期并记录其余字段表"
  affects: [enabled-sources]
