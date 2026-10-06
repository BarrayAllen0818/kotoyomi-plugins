# 上游更新提醒与手动查询

**Target:** 实现 PRD.md REQ-004：GitHub Actions 每天北京时间 06:00 检查上游 master，仅提醒；用户说“检查上游更新”时，Codex 根据项目 Brain 读取固定入口。

<!-- gantry:workflow pseudocode=pending annotations=pending stabilization=pending implementation=pending -->

## 当前状态

2026-10-06 用户确认需求内容、需求阶段完成及进入方案阶段。以下方案由 AI 起草，尚未批准，尚无实现授权。沿用 `codex/upstream-update-notifier`；基线 `ce6231862a9a6eb4b5f9b081b2a55c8527cbb60b`。本轮仅写文档，不启用 Actions、创建 Issue、发布提醒或合并主线。

采用 Gantry guided 与聊天审阅，不启动浏览器。Pseudocode 规定行为、状态归属、失败边界及上线顺序；私有函数命名、同义数据结构与测试组织属于实现建议，可在不改变上述约束时调整。

## 已核实的事实与影响

- 2026-10-06 GitHub API：目标仓库 `BarrayAllen0818/kotoyomi-plugins` 为公开仓库，默认分支 `master`，Issues 可用，当前账号具有 admin/push 权限，仓库 Actions `enabled=false`。尚无提醒 Issue。
- 上游 `skepsun/kototoro-parsers` 的 `master` 当前指向 `ca313756e395b5ddbd201e01cc01ece01078d15c`。此 SHA 是本轮观测值，不能直接当作未来部署时的最新基线。
- `.github/workflows/release.yml` 仅 `workflow_dispatch`，与发布脚本相连；提醒流程不调用它。`.github/workflows/test-branch.yml` 在 master push 时编译；`test-parsers.yml` 对解析器 PR/手动运行编译；`test-release.yml` 对脚本目录与发布 YAML 的变动运行发布脚本测试。
- 新提醒工作流独立新增，保留全部现有工作流。启用仓库 Actions 后，未来匹配事件会恢复现有检查；本次上线先在 Actions 禁用状态下合并推送，再启用并手动运行提醒，不为本任务触发 Gradle 或插件发布。
- GitHub 官方规则：schedule 只运行默认分支上的工作流，可延迟或漏调度；公共仓库连续 60 天无活动可能停用。上线不能只推送任务分支便宣称每日检查生效；手动查询必须检查运行是否过期。
- Serena 可用且项目为本仓库；GitNexus 可用但索引停在 `f9965c74`，落后当前任务基线 14 个提交。本轮根据当前工作流原文和 GitHub API 核对影响，未使用旧图谱推断调用链，也未重新索引。无需读取或修改 Kotlin 解析器。

参考：[GitHub 工作流触发规则](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows)。

## 修改入口

| 文件 | 计划职责 |
| --- | --- |
| `.github/workflows/check-upstream.yml`（新增） | 定时与手动执行检查、固定仓库限制、权限及并发控制 |
| `.github/scripts/check_upstream.py`（新增） | GitHub API 读取、检查点解析、比较与提醒；只读查询模式 |
| `.github/scripts/test_check_upstream.py`（新增） | 隔离 API 响应的确定性状态与失败回归测试，不连接 GitHub |
| `brain/pages/upstream-update-notifications.md` 与自动索引 | 经 brain CLI 更新准确 Issue URL、工作流名称、手动查询命令与失败解释 |
| `PRD.md`、本方案及差异记录 | 记录批准、实际验证、上线及用户验收证据 |

不改 AGENTS、现有源码、发布脚本或插件索引；不新增依赖。个人逻辑集中在新脚本及新工作流，后续上游同步保留这些文件和 Brain 入口。没有共享解析契约变化；现有 `test-release.yml` 对 scripts 目录的宽匹配会额外触发发布逻辑的离线测试，这是检查，不是发布。

## Pseudocode

<!-- gantry:step id=gty-upstream-entry author=ai status=open -->
1. 使用一个固定 Issue 作为提醒历史和检查点的唯一持久记录。
  - 部署时由当前账号创建标题为 `[上游更新] kototoro-parsers/master` 的 Issue，正文说明用途并包含 schema=1 的机器标记：上游仓库、分支、初始完整 SHA、初始化时间。该次创建是一次初始化通知，不描述为已有新更新。
  - 将返回的 Issue 编号写入脚本的唯一配置常量，与固定目标仓库、上游仓库和分支放在一起；工作流和本地查询均调用该脚本，避免两处编号漂移。经 brain CLI 把准确 URL、编号及入口命令写入项目 Brain；不在每次运行时搜索同名 Issue，不依赖会过期的 cache/artifact 或易变分支保存状态。
  - Issue 正文中的初始 SHA 是根检查点；后续有效机器人评论同时承担通知和新检查点，正常运行不修改正文。用户关闭 Issue 不抹去历史，也不表示已合并；检查仍读取同一编号并可继续评论，不自动重开。Issue 删除、锁定或状态损坏时明确失败，不静默创建替代品。

<!-- gantry:item id=gty-upstream-initial type=edge status=open mode=choice -->
- [ ] **edge:** 首次启用从哪里开始提醒？推荐 A，符合持续监控后续更新的目标，避免将全部旧提交一次性报出。
  - A: 以部署时上游最新 SHA 建立基线，仅提醒之后的更新。
  - B: 用户指定一个上游起始 SHA，首次运行补报该提交之后的变化。

<!-- gantry:step id=gty-upstream-schedule author=ai status=open -->
2. 在 GitHub 托管的 ubuntu-latest 上执行独立提醒工作流。
  - `schedule` 使用 UTC cron `0 22 * * *`，对应每天北京时间 06:00；同时提供无任意输入的 `workflow_dispatch` 供安装验证和恢复检查。任务只允许在 `BarrayAllen0818/kotoyomi-plugins` 执行，避免 fork 意外写入其他位置。
  - 使用仓库内置 GITHUB_TOKEN，仅授予 contents:read、issues:write；查询模式在本机使用现有 gh 登录凭据。上游为公开仓库，不需要额外 PAT。网络地址限定 GitHub API 和固定仓库；不执行上游提交内容或 Issue 内的指令。
  - 固定 concurrency group，cancel-in-progress=false；定时与手动检查共用同一组，串行执行，job 设 10 分钟上限。状态写入只允许该工作流；本地默认只读，不提供直接写提醒的 CLI 入口。
  - checkout 使用仓库已有的固定 SHA 并关闭 persist-credentials，运行 Python 标准库脚本。正式检查前运行自身离线测试；只读/离线模式不需要远端写权限。

<!-- gantry:step id=gty-upstream-check author=ai status=open -->
3. 从固定 Issue 恢复最近检查点，再抓取本次上游 HEAD 快照并比较。
  - 校验 Issue 不是 PR、属于目标仓库且初始标记完整；分页读取全部评论，按创建顺序处理。只识别 github-actions[bot] 的预期 schema 标记，其余用户评论作为普通文本，不改变检查点。
  - 每条状态评论包含 source、branch、from_sha、to_sha、previous_record_id、checked_at、run_url、kind；previous_record_id 指向前一条状态评论，根记录为 root。字段和值必须匹配已恢复的状态链；机器人标记损坏或链断裂时失败，不能回退根记录后重复通知。
  - GET 上游 master 得到完整 SHA，固定本次比较目标；与检查点相同则输出 no_change，不写 Issue。目标不同则按两个 SHA 调用 compare API；比较不可用、403/429、超时或响应缺字段均判为检查失败，不推进检查点。
  - ahead 正常汇报新增提交数量和 compare 链接，摘要最多列 20 条提交标题，明确截断并以 compare 链接为完整入口。behind/diverged 单独说明上游历史回退或改写及关系，不把它们冒充正常新增；成功记录这一变化后采用新 HEAD 继续监控。
  - 提交摘要仅作为数据：限制长度、转为单行、转义 Markdown 并中和 @提及，避免标题意外通知第三方；链接从已验证仓库与 SHA 生成。

<!-- gantry:step id=gty-upstream-persist author=ai status=open -->
4. 每次 HEAD 变化只追加一条评论，将提醒和检查点一起保存。
  - 评论正文是中文可读摘要、检查时间和链接；尾部机器标记与正文在同一次 POST 中提交。不给正文或另一分支再写单独的 last_seen，避免“状态已前进、提醒未送出”。
  - 去重键由 previous_record_id 和目标 SHA 组成，并校验 from_sha。POST 前再次读取最新状态；若状态已变，终止本次写入并报告需要重新检查，不使用旧快照发提醒。同一源分支的所有写入受第 2 步串行组约束。
  - POST 失败或超时不立即重发：只读回查是否已有相同去重键的有效评论。找到则记录已保存及评论 URL；无法确认则失败，保留不确定状态供下次运行恢复。下次运行先读远端链，已保存评论不会再次发送。不得声称跨网络故障提供严格的 exactly-once 保证。
  - 运行日志及 Step Summary 输出 schema、status（no_change/notified/error）、检查时间、baseline/head、Issue URL，成功提醒另附 comment URL。通知已保存但后续输出失败时分别记录，不能撤销评论或重发。
  - 错误使工作流失败，保留最后成功的检查点；不以无更新掩盖失败。失败提醒使用 GitHub 自身 Actions 通知，是否发邮件取决于用户订阅设置，不另开错误 Issue。

<!-- gantry:step id=gty-upstream-manual author=ai status=open -->
5. 提供默认只读入口，供 Codex 收到口令后查询。
  - Brain 记录 `python .github/scripts/check_upstream.py --status`；该模式使用现有 gh 登录（或已提供的 GH_TOKEN），只发 GET，请求固定 Issue、分页评论以及 check-upstream.yml 最近运行，绝不发通知、不触发工作流、不合并代码。缺少登录/配置时返回明确错误，不输出凭据。
  - 最近运行限定当前仓库默认分支上的该工作流；详情通过 gh run view --log 只读取得并解析第 4 步的结构化结果，不把普通提交文本或评论当日志状态。gh 不可用、日志尚未生成或已过期时，保留 API 已知状态并明确缺少详情；最近成功运行时间与脚本实际检查时间分别标注。
  - 报告最近运行的时间与 conclusion、最近成功检查时间（若可取得）、最近已记录 HEAD 及提醒摘要/链接。最新执行失败、排队、取消或未运行都单列；超过 30 小时没有成功检查标记为“检查状态过期”。最近成功不覆盖较新的失败状态。
  - 明确这是“截至最近一次成功检查的提醒”，不是手动查询当刻重新检查上游。即便 no_change，之前记录的提醒仍列出；不推断用户已经阅读或将变更合入本地。
  - 入口尚未部署、Issue 缺失、API 不可用或最新日志无法取得时报告具体未知项。若最新运行成功但只能读到记录的 HEAD，说明日志详情未取得，不捏造新增数量或最近查询到的实时 HEAD。
  - 不加项目启动检查、Codex 自动化或 AGENTS 规则。现有 Brain 启动发现机制只加载页面索引，实际远端查询仍由用户口令触发。

<!-- gantry:step id=gty-upstream-deploy author=ai status=open -->
6. 先本地验证与审阅验收，再上线，并保留真实运行结果。
  - 实现时先完成脚本和离线测试、工作流静态检查；通过后完成差异审查、阶段提交和任务分支推送。此任务没有插件产物，不执行插件发布流程。
  - 验收材料包含离线状态/失败证据与完整差异；用户审阅验收后，按 Git 策略 no-ff 合入 master 并推送。默认分支存在工作流是 GitHub 的调度前提，合并之前不宣称定时已生效。
  - Issue 初始化、真实编号绑定与 Brain 更新在实现部署阶段进行；若创建已成功但后续失败，保留并复用该编号，不反复创建 Issue。首次基线根据已选 A/B 校验；未获得 B 的有效 SHA 时不能猜测或上线。
  - master 推送时仍保持 Actions 禁用；之后保留仓库其他设置，仅启用 Actions。启用本身不会补跑过去 push，但未来匹配事件将恢复已有编译及离线检查；插件 release 仍为手动触发。
  - 手动 dispatch 一次真实检查，读取运行结论、日志/摘要、Issue 及 --status 输出；对 A 允许部署到首次运行间出现新提交并产生真实提醒。无真实更新时不人为制造公开测试评论，真实通知路径标记为仅离线验证，等待首次更新验证。
  - 检查失败按实际证据修正或报告，不能自动合并上游、改写发布分支或换用 PAT 绕过权限问题。线上失败不抹去已合并或已启用的事实。

## 实施顺序与验证矩阵

先实现并离线验证读取/状态链，再实现比较/单次评论，再接工作流和只读命令，最后更新准确 Brain 入口并按第 6 步上线。测试采用标准库 unittest 与可注入的 HTTP/时钟，默认不访问网络。可以调整私有函数划分及 mock 接线，不改变状态链、消息边界和默认只读行为。

| 场景 | 必须观察到的结果 |
| --- | --- |
| 有效根记录且 HEAD 相同 | no_change；零写请求 |
| 正常新增、多页评论、超过 20 条新增 | 恢复真正末检查点；单条评论；摘要截断明确且完整比较链接有效 |
| 下次重复检查、评论 POST 已成功但响应丢失 | 恢复远端记录；不重复发相同提醒 |
| HEAD A→B→A | 回退明确标注；previous_record_id 区分历史转换，不因曾见过 A 丢弃当前变化 |
| 普通用户伪造标记、机器人状态损坏或链断裂 | 普通文本不影响状态；预期机器人标记损坏明确失败；均不静默重置 |
| 403/429/5xx/超时/compare 缺字段、Issue 锁定或删除 | 非零退出且无检查点推进；查询不报告无更新 |
| POST 失败且回查也失败 | 状态不确定明确报告；本次不盲目重发 |
| 两次调度或部署间 HEAD 变化 | concurrency 配置一致；写前状态核对；基于 SHA 快照而非移动分支比较 |
| 手动 --status、失败新于成功、30 小时过期 | 零写请求；分别显示历史提醒、最近检查与错误/过期状态 |
| 默认无参数、凭据缺失、--status 日志读取受限 | 默认只读或显示帮助；无凭据泄漏；限制如实报告 |
| 对比生产与测试工作流 | cron 对应北京时间 06:00；没有 Gradle/发布调用，没有 pull_request 写通知触发器 |

工作流静态验证优先使用已有 actionlint，不安装新工具替代简单检查。文档用 Gantry lint、brain lint-links、git diff --check。新脚本变化会匹配现有 test-release 的发布逻辑测试，必要时复用其独立离线验证；不重建 JVM/D8。

## 首次交接检查与剩余验证

- 初稿中的“双写 Issue 状态与通知”存在漏报窗口：正文已改为单条评论同时保存通知与检查点（第 4 步），复查包含响应丢失与次日重跑。
- 只保存最后 SHA 无法区分“没更新”和“根本没检查”：加入 Actions 最近运行、成功时间与 30 小时过期判断（第 5 步），历史提醒与检查健康分开报告。
- 任务分支即可生效的假设不成立：已按 GitHub 默认分支限制补齐审阅验收、合并、启用、手动 dispatch 的顺序（第 6 步）。未将未部署功能标为完成。
- 启用 Actions 会恢复其他工作流：已列出现有触发器与本次避免编译的上线顺序，保留未来正常 CI 行为。
- GITHUB_TOKEN 的真实写入权限、Actions 启用策略、通知订阅与真实 runner 行为只能在授权部署时验证。失败保持当前状态并报告；不扩大权限或更换通知渠道。邮件/手机推送需用户自行订阅确认，API 成功不代表邮件已送达。
- 起草者按以上状态链、失败出口和上线时序完成初次交接复查，无其他关键机制留给实现者自行猜测。首次基线 A/B、整体方案批准及后续独立实现授权仍待用户决定；后续 Gantry 注解与稳定性复核未冒记为完成。

## Code

尚未实现，无代码快照。
