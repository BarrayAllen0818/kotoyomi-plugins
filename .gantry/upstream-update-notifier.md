# 上游未合入提交提醒与手动查询

**Target:** 实现 PRD.md REQ-004：每天北京时间 06:00 检查个人远端 master 是否包含上游提交，未全部包含则每天提醒；用户说“检查上游更新”时由 Codex 读取固定入口。

<!-- gantry:workflow pseudocode=approved annotations=complete stabilization=complete implementation=authorized -->

## 当前状态与事实

用户已确认需求阶段完成并进入方案阶段，随后明确选 B，以当前已拉取的 ca313756e395b5ddbd201e01cc01ece01078d15c 为初始基线。“已拉取”指上游提交已合并并推送到个人远端、保留原提交历史；未完成则每天提醒。2026-10-06 用户在收到四步运作解释、范围及验证说明后批准当前整体方案。六项 AI 步骤已接受，注解与稳定性复核完成，无新待决项；用户在单独实现授权请求后回复确认，已授权按当前稳定方案实施；主线合并和 Actions 启用仍待交付验收。

沿用 codex/upstream-update-notifier，任务代码基线 ce6231862a9a6eb4b5f9b081b2a55c8527cbb60b。Gantry guided、聊天审阅，不启动浏览器。行为、数据归属和失败边界是设计约束；私有函数划分与测试接线是可调整建议。

- 2026-10-06 API 核实目标仓库 BarrayAllen0818/kotoyomi-plugins 为 public、fork=false，默认分支 master，Issues 可用，账号有 admin/push 权限；Actions enabled=false。方案不依赖 GitHub 跨 fork compare。
- 当前个人远端 master=260a9c36c437506309c867faf4eab009cec6d24b，与本地 master 相同；上游远端 master 与本地 upstream/master 均为 ca313756e395b5ddbd201e01cc01ece01078d15c。本地完整图证明该 SHA 是 master 祖先，git rev-list --count upstream/master --not master 为 0。本轮未 fetch。
- release.yml 仅 workflow_dispatch；test-branch.yml 在 master push 编译，test-parsers.yml 对解析器 PR/手动运行编译，test-release.yml 对 scripts 目录及发布 YAML 变化运行发布逻辑离线测试。全部保留；提醒不调用发布。
- GitHub schedule 只运行默认分支，可延迟或漏调度；公共仓库 60 天无活动可能停用。仅推送任务分支不能宣称定时生效。参考：https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows 。
- Serena 已核对可用且绑定本仓库；GitNexus 可用但索引落后起草时基线 14 个提交。本轮依据当前 YAML、Git 图和 API，不依赖旧图谱，不需要修改 Kotlin 或重新索引。

## 修改入口与兼容边界

新增 .github/workflows/check-upstream.yml、.github/scripts/check_upstream.py、.github/scripts/test_check_upstream.py。分别负责调度、Git 差集/通知/只读查询、隔离离线验证。经 brain CLI 更新 upstream-update-notifications 页面与自动索引；PRD 和本方案记录批准、验证与交付。

不改 AGENTS、解析器、宿主契约、既有发布脚本或插件索引，不新增依赖。个人逻辑集中在新文件，后续上游同步保留它们和 Brain 入口。scripts 变更会匹配现有 test-release 离线测试，这是检查而非发布。

## Pseudocode

<!-- gantry:step id=gty-upstream-entry author=ai status=accept -->
1. 固定两个远端 master，记录初始已同步基线，创建一个提醒 Issue。
  - 上游为 skepsun/kototoro-parsers/master，接收方为 BarrayAllen0818/kotoyomi-plugins/master。只判断接收方 master 的祖先，不因任务分支、repo 发布分支或孤立对象存在就视为完成。
  - 固定初始 B=ca313756e395b5ddbd201e01cc01ece01078d15c。初始化核对 B 被两个远端历史包含；失败报告，不静默改成部署时 HEAD。B 是初始化证据，不是移动水位，运行时不能用它隐藏远端回退造成的缺失。
  - 部署时创建标题为 [上游更新] kototoro-parsers/master 的 Issue，正文说明初始化时间、B、监控分支和持续提醒规则。Issue 仅保存提醒历史与同日去重记录，不是同步状态的事实源。
  - 编号是脚本唯一配置，工作流与本地查询共用，brain CLI 写入准确 URL 和命令。关闭 Issue 不表示已同步，不自动重开、不删历史；仍可按规则评论。删除、锁定或无法访问则明确失败。
<!-- gantry:item id=gty-upstream-initial type=edge status=choice-b mode=choice -->
- [x] **edge:** [choice-b] 初始基线固定为本次已拉取的 ca313756e395b5ddbd201e01cc01ece01078d15c，不取未来首次执行时最新提交。
  - A: 以部署时最新 SHA 建立基线，仅提醒后续更新。
  - B: 指定当前已拉取的 SHA，首次检查纳入其后尚未合入的变化。
  - comment: 用户最初回复 A，随后明确改用 B 并指定当前已拉取提交，以后一次澄清为准。
<!-- gantry:item id=gty-upstream-meaning type=edge status=accept mode=decision -->
- [x] **edge:** [accept] 上游原提交合并并推送到个人远端 master 后才停止提醒；否则即使昨天已通知，今天仍提醒。
  - comment: 用户明确“上游提交合并并推送到远端，远端有上游提交记录”；仅 fetch、本地合并、未 push 或仅其他分支包含均不满足条件。

<!-- gantry:step id=gty-upstream-schedule author=ai status=accept -->
2. GitHub 托管 ubuntu-latest 每天北京时间 06:00 执行独立工作流。
  - UTC cron 为 0 22 * * *，另提供无任意输入的 workflow_dispatch；限制为固定目标仓库，不增加 pull_request 写通知触发器。
  - GITHUB_TOKEN 仅 contents:read、issues:write；公开上游 Git 获取无需 PAT。固定网络地址，不执行远端提交、Issue 或日志内的指令。
  - 固定 concurrency group，cancel-in-progress=false，定时和手动运行串行；job 限时 10 分钟。checkout 使用既有固定 SHA、persist-credentials=false，仅加载受信任脚本和测试。
  - 正式检查前运行自身离线测试。Python 标准库与 Git 足够；写模式只在 Actions 使用，本机默认只读，无 Gradle/D8/发布调用。

<!-- gantry:step id=gty-upstream-check author=ai status=accept -->
3. 每次从两个远端取得完整历史快照，以提交可达性计算未合入集合。
  - 在 runner 临时目录新建独立 bare Git 仓库，分别从固定 HTTPS URL fetch 两条 master 到不同 refs，使用 --no-tags、不使用 depth/shallow；不修改 checkout 分支，不 push。finally 仅清理本次创建并校验过的临时目录。
  - 两次 fetch 成功后记录 O=个人 master SHA、U=上游 master SHA；只对固定 SHA 计算。核对 Git 返回码、对象完整性和共同祖先；不完整、超时或无共同历史均 error，不能降级为已同步。
  - P=git rev-list U --not O；空集才是 synced，非空就是 pending。数量取全部差集，消息最多展示 20 条提交标题并标明截断。U 与昨天相同但 P 不为空，仍每天提醒。
  - O 有额外个人提交但包含 U 仍 synced；部分合并只列剩余。squash/cherry-pick 若不保留原提交可达性则仍 pending，不用内容相同、patch-id 或标题相似替代历史包含。
  - 历史回退/改写但仍有共同祖先时按当前 P 判断，B 不再被包含则附历史变动说明；无共同历史明确失败，不把整仓差异冒充正常更新。
  - 发送前 ls-remote 复核两端 master 仍为 O/U；变化则重新获取和计算一次，仍变化则 snapshot_changed 失败退出。消息表明“截至检查时间”的快照，不承诺消除发送瞬间的竞态。
  - 标题仅作数据：单行、限长、转义 Markdown、中和 @提及。链接使用固定仓库与完整 SHA，不依赖跨 fork compare URL。

<!-- gantry:step id=gty-upstream-persist author=ai status=accept -->
4. pending 时每天追加一条提醒，同日重试去重不影响次日。
  - 以检查时刻的 Asia/Shanghai 日期分组，不用提交日期；跨午夜则写入前刷新日期。去重键为仓库、监控分支对、YYYY-MM-DD，不能只用 U 或上次通知 SHA。
  - 分页读取固定 Issue 的评论，只识别 github-actions[bot] 的预期 schema 标记。当日已有有效提醒则 daily_reminder_exists，仍报告本次 O/U/P；当日出现更多提交也不追加第二条，日志显示新差集。次日仍 pending 必须再次提醒。
  - 评论包含仍未合并推送的数量、时间、O/U 链接、最多 20 条缺失提交链接。尾部机器标记包含 schema、day、source/target、origin_sha、upstream_sha、pending_count、checked_at、run_url，与消息一次 POST。
  - POST 失败/超时不盲目重发，只读回查同日键；找到则报告已保存，无法确认则 error，保留不确定事实供重跑核对。普通用户标记不参与去重；预期机器人标记损坏则失败。所有写入共用串行组，不宣称严格跨网络 exactly-once。
  - synced 不发提醒、不关 Issue、不删旧评论。日志和 Step Summary 写 status=synced/pending/error、检查时间、O/U、pending_count、通知结果及链接；是否同步只由 Git 图决定，与今日是否已通知无关。
  - Git、API、鉴权、Issue 或消息错误令工作流失败，不报告已同步。GitHub Actions 自身通知用于失败提示；是否发邮件由用户订阅设置决定。

<!-- gantry:step id=gty-upstream-manual author=ai status=accept -->
5. Codex 收到“检查上游更新”后通过默认只读入口报告最近检查。
  - Brain 记录 python .github/scripts/check_upstream.py --status；使用既有 gh 登录或 GH_TOKEN，只读 Issue 和默认分支的 check-upstream.yml 运行信息，通过 gh run view --log 解析结构化结果。不发评论、不 dispatch、不 fetch 到用户仓库、不合并推送、不输出凭据。
  - 报告最近检查时间、当时 O/U、pending_count、运行结论、提醒链接。最新失败、排队、取消、从未运行及超过 30 小时无成功检查分别说明，旧成功不能覆盖新失败。
  - synced 时旧评论仅属历史；pending 且当日已通知仍报告待合入。日志缺失时同步状态/数量标为未知，不从最后提醒推断现在是否已合并。
  - 明确是“截至最近一次检查”的结果，不声称实时检查调用当刻的远端；缺少 gh/凭据/API 权限如实报告。不加任务启动检查、Codex 自动化或 AGENTS 规则。

<!-- gantry:step id=gty-upstream-deploy author=ai status=accept -->
6. 本地验证及审阅验收后部署，配置完成与真实运行分别报告。
  - 先实现 Git 差集与隔离回归，再接每日通知/去重和只读查询，最后接工作流；完成验证、审查、阶段提交和任务分支推送，不发布插件。
  - 在相应实施/外部写入授权覆盖后创建固定 Issue、绑定真实编号和 B、更新 Brain 入口；创建成功后其他步骤失败则复用原编号。初始 B 校验失败不擅自换基线。
  - 提供离线证据与完整差异供用户审阅验收；验收后按 Git 策略 no-ff 合入 master 并推送。默认分支未包含工作流前不宣称每日检查已生效。
  - 当前 Actions 禁用时完成 master 推送，然后只启用 Actions、保留其他设置；未来事件恢复原 CI，release 仍手动触发。dispatch 一次本工作流验证真实 Git 获取、日志、Issue 读取和 --status。
  - 首次 pending 就真实提醒；synced 则零更新提醒，POST 路径标为仅离线验证，不人为造公开测试消息。次日仍 pending 应再次提醒。
  - 线上失败保留并报告已合并、启用或发消息的事实，不自动合并上游、不强推、不改发布分支、不新增 PAT 绕过权限。

## 实施顺序与验证依据

使用标准库 unittest、临时本地 bare 仓库、假 API 和可控时钟，默认离线。以实际本地 Git 图验证可达性，不只 mock rev-list；Git 超时、HTTP 失败及时间可注入。私有函数划分可调整，远端 master 判定、隔日持续提醒及默认只读不可变。

| 场景 | 必须观察到的结果 |
| --- | --- |
| O 包含 U 且有额外个人提交 | synced，零更新提醒 |
| 同一 U 连续两天未包含 | 两天各提醒一次 |
| 同日重跑、POST 已成功但响应丢失 | 当日去重，仍报告 pending |
| 本地 fetch/合并未推送，仅其他远端分支包含 | 继续提醒 |
| 合并推送 master、部分合并、之后回退 | 停止、仅剩余、恢复缺失提醒 |
| squash/cherry-pick 等价但缺原提交 | pending，不冒称已合入原历史 |
| 超过 20 条缺失、多页评论 | 数量完整、摘要截断明确、去重不漏页 |
| 同日 U 再变化、跨北京时间午夜 | 当日只一条；日志新结果；次日可再提醒 |
| fetch/API 失败、无共同历史、快照持续变动 | error，不据不完整或过时快照通知 |
| 发送前远端变化 | 重算一次；已同步则不发；仍变化报错 |
| Issue 锁定/删除、标记损坏、POST 回查失败 | 明确 error/不确定，不误报 synced，不盲目重发 |
| --status、旧成功之后失败、日志缺失 | 零写请求；缺失如实未知；旧评论不决定现状 |

静态验证使用已有 actionlint、Gantry lint、brain lint-links、git diff --check；不安装新工具替代简单检查。scripts 变动匹配现有 test-release，实施时运行适用的发布逻辑离线验证，不重建 JVM/D8。

## 修订后的交接检查

- 删除原“发通知即推进基线”的状态链；同步事实源改为两个远端 master 的完整差集。Issue 只用于历史和同日去重，历史草案保留在 Git 与差异记录。
- fork=false 使跨 fork compare 不宜作为核心机制；采用隔离 bare 完整 fetch，避免浅历史/分页误判。本轮当前本地完整图与远端 SHA 对照证明 P=0；runner 获取、耗时及权限待授权实施验证。
- 固定 B 是初始已同步记录，不能隐藏远端回退；同日去重、隔日持续提醒分别覆盖。补齐只 fetch、merge 未 push、仅其他分支、部分合并、squash、午夜和快照变化边界。
- 手动查询区分历史评论与最近检查，旧提醒不能代表现状；日志不可得明确未知。默认分支生效、Actions 启用连带影响及仅提醒边界保留。
- 2026-10-06 整体方案获批后，注解审阅和稳定性复核覆盖初始化 B、远端差集、每日去重、网络不确定结果、只读状态以及部署顺序，未发现新增实质决策；六项 AI 技术步骤均 accept，B 和合入定义沿用已确认记录。真实权限、Git 网络耗时、POST 与用户订阅须在授权部署验证；不可擅自降级为浅历史或扩大凭据权限。

## 批准与稳定性记录

2026-10-06 用户针对完整方案解释回复“确认”。接受第 1–6 步整体，不等同于单独的实现授权。复核结论：同一未合入集合隔日持续提醒、同日只一条；仅接收方远端 master 的可达性可消除待合入项；错误和历史消息都不能覆盖新检查事实；上线合并仍须实现交付后用户验收。六条执行链与验证矩阵一致，无新增注解或正文行为变更。runner 网络、GITHUB_TOKEN 写入和实际通知仍为实施阶段验证项，不宣称已通过。

## Code

实现已完成，源码快照将在实施提交后绑定提交记录。固定提醒 Issue 为 https://github.com/BarrayAllen0818/kotoyomi-plugins/issues/1 。

## 实施验证与审查

2026-10-06 单独实现授权已取得，lint --gate 通过。实际入口为 GitSnapshot.refresh/matches（隔离完整 Git 图）、check/reminder_for_day（每日提醒）、GitHub（API）、status_report/result_from_logs（只读查询）及 main（默认只读，写模式限制到固定默认分支工作流）。工作流是 check-upstream.yml，cron、权限和串行配置与方案一致。

31 个离线测试通过，分别覆盖真实 Git 图、通知与失败恢复、只读 API/日志；原有 9 个发布逻辑测试通过。actionlint、Python AST 语法、Git 差异检查通过。新增测试最初分别因缺 Git 实现、缺通知引擎、缺 API 适配取得失败证据。审查发现离线测试打印生产日志标记会破坏实际日志读取，已捕获测试输出，增加重复/错误日志等验证；最终无未处理缺陷。

真实只读隔离 Git 获取通过：个人远端 master=260a9c36c437506309c867faf4eab009cec6d24b，上游=ca313756e395b5ddbd201e01cc01ece01078d15c，pending_count=0，B 被两端包含，引用复核一致。Issue #1 已创建；--status 能读 Issue 并正确报告未部署工作流 API 404。

实现未更改既有解析器、发布逻辑或 AGENTS，未构建插件。当前仍待用户审阅验收，未合并默认分支、未启用 Actions、未执行真实 runner，真实更新评论及通知送达尚无证据。验收后依第 6 步上线，并将 Issue 正文中的部署准备状态改为实际状态；不把本地测试标记为远端定时已生效。
