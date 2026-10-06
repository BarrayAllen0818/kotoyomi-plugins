# 上游未合入提交提醒与手动查询

**Target:** 实现 PRD.md REQ-004：每天北京时间 06:00 检查个人远端 master 是否包含上游提交，未全部包含则每天提醒；用户说“检查上游更新”时由 Codex 读取固定入口。

<!-- gantry:workflow pseudocode=approved annotations=complete stabilization=complete implementation=authorized -->

## 当前状态与事实

用户已确认需求阶段完成并进入方案阶段，随后明确选 B，以当前已拉取的 ca313756e395b5ddbd201e01cc01ece01078d15c 为初始基线。“已拉取”指上游提交已合并并推送到个人远端、保留原提交历史；未完成则每天提醒。2026-10-06 用户在收到四步运作解释、范围及验证说明后批准当前整体方案。六项 AI 步骤已接受，注解与稳定性复核完成，无新待决项；用户在单独实现授权请求后回复确认，已授权按当前稳定方案实施；用户已在实现交付后确认验收并授权上线；主线合并推送、Actions 启用和首次运行验证已完成，详见上线记录。

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

实现提交为 9941a37268f1d874d3711eb113f7dc92418ae941，完整源码快照见下节。固定提醒 Issue 为 https://github.com/BarrayAllen0818/kotoyomi-plugins/issues/1 。

## 实施验证与审查

2026-10-06 单独实现授权已取得，lint --gate 通过。实际入口为 GitSnapshot.refresh/matches（隔离完整 Git 图）、check/reminder_for_day（每日提醒）、GitHub（API）、status_report/result_from_logs（只读查询）及 main（默认只读，写模式限制到固定默认分支工作流）。工作流是 check-upstream.yml，cron、权限和串行配置与方案一致。

31 个离线测试通过，分别覆盖真实 Git 图、通知与失败恢复、只读 API/日志；原有 9 个发布逻辑测试通过。actionlint、Python AST 语法、Git 差异检查通过。新增测试最初分别因缺 Git 实现、缺通知引擎、缺 API 适配取得失败证据。审查发现离线测试打印生产日志标记会破坏实际日志读取，已捕获测试输出，增加重复/错误日志等验证；最终无未处理缺陷。

真实只读隔离 Git 获取通过：个人远端 master=260a9c36c437506309c867faf4eab009cec6d24b，上游=ca313756e395b5ddbd201e01cc01ece01078d15c，pending_count=0，B 被两端包含，引用复核一致。Issue #1 已创建；--status 能读 Issue 并正确报告未部署工作流 API 404。

实现未更改既有解析器、发布逻辑或 AGENTS，未构建插件。用户已在实现交付后确认审阅验收；2026-10-06 依第 6 步完成合并推送（f4fabb5d）、Actions 启用、Issue 上线状态更新及首次真实运行。运行 37432055483 的 31 个测试与 Git 比较成功，15:49:23+08:00 返回 synced、pending_count=0，--status 解析成功、stale=false。没有待合入提交，因此未发真实更新评论；待合入通知和邮件送达仍未实测。已配置启用每天北京时间六点调度，尚未实际经历下一次 schedule 事件。

## Code snapshot (2026-10-06 @ 9941a37268f1d874d3711eb113f7dc92418ae941)

### .github/scripts/check_upstream.py

```python
"""Remind about upstream commits missing from the published source history.

Default CLI is read-only. Only the dedicated Actions workflow can post reminders.
"""

import argparse
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request

TARGET = "BarrayAllen0818/kotoyomi-plugins"
SOURCE = "skepsun/kototoro-parsers"
BRANCH = "master"
INITIAL_BASE = "ca313756e395b5ddbd201e01cc01ece01078d15c"
ISSUE_NUMBER = 1
WORKFLOW = "check-upstream.yml"
SHANGHAI = timezone(timedelta(hours=8))
RESULT_PREFIX = "UPSTREAM_CHECK_RESULT "
MARKER = "<!-- upstream-reminder-v1 "
SHA = re.compile(r"[0-9a-f]{40}")


class CheckError(RuntimeError):
    pass


def command(args, *, cwd=None, input=None, timeout=120, allowed=(0,), env=None):
    try:
        result = subprocess.run(args, cwd=cwd, input=input, text=True, encoding="utf-8",
                                errors="replace", capture_output=True, timeout=timeout, env=env)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise CheckError(f"{args[0]} unavailable or timed out") from exc
    if result.returncode not in allowed:
        # Never include raw stderr: it may contain remote credentials or untrusted text.
        raise CheckError(f"{args[0]} failed (exit {result.returncode})")
    return result


def valid_sha(value):
    if not isinstance(value, str) or not SHA.fullmatch(value):
        raise CheckError("Invalid Git SHA")
    return value


class GitSnapshot:
    def __init__(self, target_url=f"https://github.com/{TARGET}.git",
                 source_url=f"https://github.com/{SOURCE}.git", initial_base=INITIAL_BASE,
                 *, target_branch=BRANCH, source_branch=BRANCH, temp_root=None):
        self.urls = (target_url, source_url)
        self.branches = (target_branch, source_branch)
        self.initial_base = valid_sha(initial_base)
        self.temp_root = temp_root
        self.tmp = None

    def __enter__(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="upstream-check-", dir=self.temp_root)
        self.path = Path(self.tmp.name)
        try:
            self.git("init", "--bare", ".")
        except BaseException:
            self.tmp.cleanup()
            raise
        return self

    def __exit__(self, *args):
        self.tmp.cleanup()

    def git(self, *args, allowed=(0,)):
        env = dict(os.environ, GIT_TERMINAL_PROMPT="0")
        return command(["git", *args], cwd=self.path, allowed=allowed, env=env)

    def ancestor(self, base, tip):
        return self.git("merge-base", "--is-ancestor", base, tip, allowed=(0, 1)).returncode == 0

    def refresh(self):
        for role, url, branch in zip(("target", "source"), self.urls, self.branches):
            self.git("fetch", "--no-tags", url, f"+refs/heads/{branch}:refs/check/{role}")
        origin = valid_sha(self.git("rev-parse", "refs/check/target").stdout.strip())
        upstream = valid_sha(self.git("rev-parse", "refs/check/source").stdout.strip())
        if self.git("rev-parse", "--is-shallow-repository").stdout.strip() != "false":
            raise CheckError("Incomplete shallow Git history")
        self.git("fsck", "--connectivity-only", "--no-dangling", origin, upstream)
        self.git("merge-base", origin, upstream)  # Exit 1 means unrelated histories.
        count = int(self.git("rev-list", "--count", upstream, "--not", origin).stdout.strip())
        commits = []
        lines = self.git("log", "--max-count=20", "--format=%H%x09%s", upstream, "--not", origin).stdout
        for line in lines.splitlines():
            sha, title = line.split("\t", 1)
            commits.append({"sha": valid_sha(sha), "title": title})
        base_exists = self.git("cat-file", "-e", f"{self.initial_base}^{{commit}}",
                               allowed=(0, 1, 128)).returncode == 0
        baseline_contained = base_exists and all(self.ancestor(self.initial_base, tip)
                                                for tip in (origin, upstream))
        return {"schema": 1, "status": "pending" if count else "synced",
                "origin_sha": origin, "upstream_sha": upstream, "pending_count": count,
                "commits": commits, "baseline_contained": baseline_contained}

    def matches(self, result):
        for url, branch, expected in zip(self.urls, self.branches,
                                         (result["origin_sha"], result["upstream_sha"])):
            lines = self.git("ls-remote", "--exit-code", url, f"refs/heads/{branch}").stdout.splitlines()
            if len(lines) != 1 or valid_sha(lines[0].split()[0]) != expected:
                return False
        return True


def now_utc():
    return datetime.now(timezone.utc)


def issue_url():
    return f"https://github.com/{TARGET}/issues/{ISSUE_NUMBER}"


def parse_time(value):
    try:
        result = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if result.tzinfo is None:
            raise ValueError("timezone missing")
        return result
    except (ValueError, TypeError, AttributeError) as exc:
        raise CheckError("Invalid timestamp") from exc


def reminder_for_day(comments, day):
    found = None
    for comment in comments:
        if comment.get("user", {}).get("login") != "github-actions[bot]":
            continue
        body = comment.get("body") or ""
        if MARKER not in body:
            continue
        matches = re.findall(re.escape(MARKER) + r"(.*?) -->", body, re.DOTALL)
        try:
            if len(matches) != 1:
                raise ValueError("ambiguous marker")
            record = json.loads(matches[0])
            if (record["schema"] != 1 or record["source"] != SOURCE or record["target"] != TARGET
                    or record["branch"] != BRANCH or type(record["pending_count"]) is not int
                    or record["pending_count"] < 1):
                raise ValueError("unexpected record")
            valid_sha(record["origin_sha"])
            valid_sha(record["upstream_sha"])
            expected_day = parse_time(record["checked_at"]).astimezone(SHANGHAI).date().isoformat()
            if record["day"] != expected_day:
                raise ValueError("date mismatch")
            if not re.fullmatch(r"https://github\.com/" + re.escape(TARGET) + r"/actions/runs/[0-9]+",
                                record["run_url"]):
                raise ValueError("invalid run URL")
            if record["day"] == day:
                found = comment
        except (KeyError, TypeError, ValueError, CheckError) as exc:
            raise CheckError("Malformed upstream reminder from Actions bot") from exc
    return found


def safe_title(title):
    value = " ".join(title.split())[:200].replace("@", "＠")
    return re.sub(r"([\\\`*_{}\[\]<>#!|])", r"\\\1", value)


def reminder_body(result, run_url):
    record = {key: result[key] for key in
              ("schema", "day", "origin_sha", "upstream_sha", "pending_count", "checked_at")}
    record.update(source=SOURCE, target=TARGET, branch=BRANCH, run_url=run_url)
    lines = [f"仍有 **{result['pending_count']}** 个上游提交尚未合并并推送到远端 master。",
             "", f"检查时间：{result['checked_at']}（北京时间 {result['day']}）",
             f"- 个人远端：[master @ {result['origin_sha'][:8]}](https://github.com/{TARGET}/commit/{result['origin_sha']})",
             f"- 上游：[master @ {result['upstream_sha'][:8]}](https://github.com/{SOURCE}/commit/{result['upstream_sha']})",
             f"- [检查记录]({run_url})", "", "尚未合入的提交：", ""]
    for commit in result["commits"]:
        lines.append(f"- [{commit['sha'][:8]}](https://github.com/{SOURCE}/commit/{commit['sha']}) "
                     + safe_title(commit["title"]))
    if result["pending_count"] > len(result["commits"]):
        lines.append(f"仅展示前 {len(result['commits'])} 条，共 {result['pending_count']} 条。")
    if not result["baseline_contained"]:
        lines.extend(["", "初始已同步基线不再被两端完整包含，请留意历史回退或改写。"])
    lines.extend(["", "合并并推送到个人远端 master 后，下一次成功检查停止提醒。", "",
                  MARKER + json.dumps(record, ensure_ascii=True, sort_keys=True) + " -->"])
    return "\n".join(lines)


def check(api, snapshot, clock=now_utc, run_url=""):
    issue = api.issue()
    if issue.get("pull_request") is not None or issue.get("locked"):
        raise CheckError("Reminder Issue is unavailable, locked or a pull request")
    comments = api.list_comments()
    for attempt in range(2):
        result = snapshot.refresh()
        if snapshot.matches(result):
            break
    else:
        raise CheckError("snapshot_changed: remote branches kept moving")
    # Take the date after network/Git work, so a midnight crossing uses the new day.
    checked = clock().astimezone(SHANGHAI)
    result.update(checked_at=checked.isoformat(), day=checked.date().isoformat(),
                  issue_url=issue["html_url"], notification="none", run_url=run_url)
    prior = reminder_for_day(comments, result["day"])
    if result["status"] == "synced":
        return result
    if prior:
        result.update(notification="daily_reminder_exists", comment_url=prior["html_url"])
        return result
    body = reminder_body(result, run_url)
    try:
        saved = api.post_comment(body)
        result.update(notification="posted", comment_url=saved["html_url"])
    except (CheckError, KeyError, TypeError) as exc:
        saved = reminder_for_day(api.list_comments(), result["day"])
        if not saved:
            raise CheckError("Reminder POST failed or is uncertain; no blind retry") from exc
        result.update(notification="recovered", comment_url=saved["html_url"])
    return result


def get_token():
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not token:
        token = command(["gh", "auth", "token", "--hostname", "github.com"]).stdout.strip()
    if not token:
        raise CheckError("GitHub authentication is required")
    return token


class GitHub:
    def __init__(self, token, issue_number=ISSUE_NUMBER):
        self.token = token
        self.issue_number = issue_number

    def request(self, path, method="GET", body=None):
        if not path.startswith(f"/repos/{TARGET}/"):
            raise CheckError("Unexpected API target")
        headers = {"Authorization": "Bearer " + self.token, "Accept": "application/vnd.github+json",
                   "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "kotoyomi-upstream-check"}
        data = None if body is None else json.dumps(body).encode("utf-8")
        if data is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request("https://api.github.com" + path, data=data,
                                         headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.load(response)
        except urllib.error.HTTPError as exc:
            raise CheckError(f"GitHub API HTTP {exc.code} ({method})") from exc
        except (OSError, ValueError) as exc:
            raise CheckError(f"GitHub API response unavailable or invalid ({method})") from exc

    def issue(self):
        if self.issue_number < 1:
            raise CheckError("Reminder Issue has not been initialized")
        result = self.request(f"/repos/{TARGET}/issues/{self.issue_number}")
        expected = f"https://github.com/{TARGET}/issues/{self.issue_number}"
        if (not isinstance(result, dict) or result.get("number") != self.issue_number
                or result.get("html_url") != expected):
            raise CheckError("Unexpected reminder Issue")
        return result

    def list_comments(self):
        comments = []
        page = 1
        while True:
            batch = self.request(f"/repos/{TARGET}/issues/{self.issue_number}/comments?per_page=100&page={page}")
            if not isinstance(batch, list) or not all(isinstance(item, dict) for item in batch):
                raise CheckError("Invalid Issue comments page")
            comments.extend(batch)
            if len(batch) < 100:
                return comments
            page += 1

    def post_comment(self, body):
        return self.request(f"/repos/{TARGET}/issues/{self.issue_number}/comments",
                            "POST", {"body": body})

    def runs(self, conclusion=None):
        path = f"/repos/{TARGET}/actions/workflows/{WORKFLOW}/runs?branch={BRANCH}&per_page=1"
        if conclusion:
            path += "&status=" + conclusion
        result = self.request(path)
        if not isinstance(result, dict) or not isinstance(result.get("workflow_runs"), list):
            raise CheckError("Invalid workflow runs response")
        return result["workflow_runs"]

    def run_logs(self, run_id):
        if type(run_id) is not int or run_id < 1:
            raise CheckError("Invalid run id")
        return command(["gh", "run", "view", str(run_id), "--repo", TARGET, "--log"],
                       env=dict(os.environ, GH_TOKEN=self.token, GH_HOST="github.com")).stdout


def result_from_logs(logs, run_url):
    results = []
    for line in logs.splitlines():
        if RESULT_PREFIX not in line:
            continue
        raw = line.split(RESULT_PREFIX, 1)[1]
        try:
            record = json.loads(raw)
            count = record["pending_count"]
            if (record["schema"] != 1 or record["run_url"] != run_url
                    or record["source"] != SOURCE or record["target"] != TARGET
                    or type(count) is not int or count < 0
                    or record["status"] != ("synced" if count == 0 else "pending")):
                raise ValueError("mismatched result")
            valid_sha(record["origin_sha"])
            valid_sha(record["upstream_sha"])
            parse_time(record["checked_at"])
            results.append(record)
        except (ValueError, KeyError, TypeError, CheckError) as exc:
            raise CheckError("Invalid structured check result in logs") from exc
    if len(results) != 1:
        raise CheckError("Expected exactly one structured check result in logs")
    return results[0]


def status_report(api, clock=now_utc):
    issue = api.issue()
    report = {"mode": "read-only", "issue_url": issue["html_url"],
              "scope": "Result as of the last check, not a live comparison",
              "latest_run": None, "last_successful_check": None, "stale": None}
    try:
        runs = api.runs()
    except CheckError as exc:
        report["detail_error"] = "Workflow unavailable: " + str(exc)
        return report
    if not runs:
        report["detail_error"] = "Workflow has never run"
        return report
    latest = runs[0]
    report["latest_run"] = {key: latest.get(key) for key in
                            ("id", "status", "conclusion", "html_url", "created_at", "updated_at")}
    try:
        successes = [latest] if latest.get("conclusion") == "success" else api.runs("success")
        if not successes:
            report["detail_error"] = "No successful check"
            return report
        success = successes[0]
        report["last_successful_run"] = {key: success.get(key) for key in
                                       ("id", "html_url", "created_at", "updated_at")}
        result = result_from_logs(api.run_logs(success["id"]), success["html_url"])
        report["last_successful_check"] = result
        age = clock() - parse_time(result["checked_at"])
        report["stale"] = age > timedelta(hours=30)
        if age < timedelta(minutes=-5):
            raise CheckError("Check timestamp is unexpectedly in the future")
    except (CheckError, KeyError, TypeError) as exc:
        report["last_successful_check"] = None
        report["stale"] = None
        report["detail_error"] = "Check log details unavailable: " + str(exc)
    return report


def emit_result(result):
    print(RESULT_PREFIX + json.dumps(result, ensure_ascii=True, sort_keys=True))
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as stream:
            stream.write("## 上游提交检查\n\n")
            stream.write("```json\n" + json.dumps(result, ensure_ascii=True, indent=2) + "\n```\n")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--status", action="store_true", help="Read the last remote check (default)")
    modes.add_argument("--notify", action="store_true", help="Dedicated Actions workflow only")
    args = parser.parse_args(argv)
    try:
        if not args.notify:
            print(json.dumps(status_report(GitHub(get_token())), ensure_ascii=True, indent=2))
            return 0
        expected_ref = f"{TARGET}/.github/workflows/{WORKFLOW}@refs/heads/{BRANCH}"
        if (os.environ.get("GITHUB_ACTIONS") != "true"
                or os.environ.get("GITHUB_REPOSITORY") != TARGET
                or os.environ.get("GITHUB_EVENT_NAME") not in ("schedule", "workflow_dispatch")
                or os.environ.get("GITHUB_WORKFLOW_REF") != expected_ref):
            raise CheckError("Notification writes require the dedicated default-branch Actions workflow")
        run_id = os.environ.get("GITHUB_RUN_ID", "")
        if not run_id.isdigit():
            raise CheckError("Invalid Actions run id")
        api = GitHub(get_token())
        run_url = f"https://github.com/{TARGET}/actions/runs/{run_id}"
        with GitSnapshot(temp_root=os.environ.get("RUNNER_TEMP")) as snapshot:
            result = check(api, snapshot, run_url=run_url)
        result.update(source=SOURCE, target=TARGET)
        emit_result(result)
        return 0
    except (CheckError, OSError, ValueError, KeyError, TypeError) as exc:
        result = {"schema": 1, "status": "error", "checked_at": now_utc().isoformat(),
                  "error": str(exc), "issue_url": issue_url()}
        if args.notify:
            emit_result(result)
        else:
            print(json.dumps(result, ensure_ascii=True))
        return 1


if __name__ == "__main__":
    sys.exit(main())
```

### .github/scripts/test_check_upstream.py

```python
"""Offline notification contracts; temporary Git histories stay under build/."""

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from unittest.mock import patch

sys.dont_write_bytecode = True
SCRIPT = Path(__file__).with_name("check_upstream.py")
ROOT = Path(__file__).resolve().parents[2]
TMP = ROOT / "build" / "upstream-tests"
TMP.mkdir(parents=True, exist_ok=True)
if SCRIPT.exists():
    spec = importlib.util.spec_from_file_location("check_upstream", SCRIPT)
    monitor = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = monitor
    spec.loader.exec_module(monitor)
else:
    monitor = None


class GitHistoryTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(monitor, "upstream monitor has not been implemented")
        self.tmp = tempfile.TemporaryDirectory(dir=TMP)
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name)
        self.remote = self.path / "remote.git"
        self.git("init", "--bare", str(self.remote))
        self.tree = self.git("--git-dir", str(self.remote), "mktree", input="").strip()
        self.base = self.commit("base")
        self.up = self.commit("upstream one", self.base)
        self.personal = self.commit("personal", self.base)
        self.set_ref("master", self.personal)
        self.set_ref("upstream", self.up)

    def git(self, *args, input=None):
        env = dict(os.environ, GIT_AUTHOR_NAME="Test", GIT_AUTHOR_EMAIL="test@example.invalid",
                   GIT_COMMITTER_NAME="Test", GIT_COMMITTER_EMAIL="test@example.invalid")
        return subprocess.run(["git", *args], input=input, text=True, encoding="utf-8",
                              capture_output=True, check=True, env=env).stdout

    def commit(self, message, *parents):
        args = ["--git-dir", str(self.remote), "commit-tree", self.tree]
        for parent in parents:
            args += ["-p", parent]
        return self.git(*args, input=message).strip()

    def set_ref(self, branch, sha):
        self.git("--git-dir", str(self.remote), "update-ref", f"refs/heads/{branch}", sha)

    def snapshot(self):
        return monitor.GitSnapshot(str(self.remote), str(self.remote), self.base,
                                   target_branch="master", source_branch="upstream", temp_root=self.path)

    def test_only_remote_master_ancestry_counts(self):
        self.set_ref("local-merged-but-not-pushed", self.commit("merge", self.personal, self.up))
        with self.snapshot() as snapshot:
            result = snapshot.refresh()
            self.assertEqual(result["pending_count"], 1)
            self.assertEqual(result["commits"][0]["sha"], self.up)
            self.set_ref("master", self.commit("merged and pushed", self.personal, self.up))
            result = snapshot.refresh()
            self.assertEqual(result["pending_count"], 0)
            self.assertEqual(result["status"], "synced")

    def test_partial_merge_and_remote_rollback(self):
        second = self.commit("upstream two", self.up)
        self.set_ref("upstream", second)
        self.set_ref("master", self.commit("partial", self.personal, self.up))
        with self.snapshot() as snapshot:
            self.assertEqual(snapshot.refresh()["pending_count"], 1)
            self.set_ref("master", self.personal)
            self.assertEqual(snapshot.refresh()["pending_count"], 2)

    def test_equal_trees_do_not_hide_missing_original_commit(self):
        with self.snapshot() as snapshot:
            self.assertEqual(snapshot.refresh()["pending_count"], 1)

    def test_summary_limit_does_not_truncate_count(self):
        tip = self.up
        for i in range(24):
            tip = self.commit(f"change {i}", tip)
        self.set_ref("upstream", tip)
        with self.snapshot() as snapshot:
            result = snapshot.refresh()
            self.assertEqual(result["pending_count"], 25)
            self.assertEqual(len(result["commits"]), 20)

    def test_history_rewrite_without_common_ancestor_fails(self):
        self.set_ref("upstream", self.commit("unrelated"))
        with self.snapshot() as snapshot:
            with self.assertRaises(monitor.CheckError):
                snapshot.refresh()

    def test_remote_changed_and_fetch_failure_are_not_synced(self):
        with self.snapshot() as snapshot:
            result = snapshot.refresh()
            self.set_ref("master", self.commit("later", self.personal))
            self.assertFalse(snapshot.matches(result))
            self.git("--git-dir", str(self.remote), "update-ref", "-d", "refs/heads/upstream")
            with self.assertRaises(monitor.CheckError):
                snapshot.refresh()


class FakeAPI:
    def __init__(self):
        self.comments = []
        self.posts = 0
        self.lose_response = False
        self.fail_post = False
        self.locked = False

    def issue(self):
        return {"number": 1, "locked": self.locked, "html_url": "https://github.com/" + monitor.TARGET + "/issues/1"}

    def list_comments(self):
        return list(self.comments)

    def post_comment(self, body):
        self.posts += 1
        if self.fail_post:
            raise monitor.CheckError("HTTP unavailable")
        comment = {"id": self.posts, "body": body, "user": {"login": "github-actions[bot]"},
                   "html_url": "https://github.com/" + monitor.TARGET + f"/issues/1#issuecomment-{self.posts}"}
        self.comments.append(comment)
        if self.lose_response:
            raise monitor.CheckError("response lost")
        return comment


class FakeSnapshot:
    def __init__(self, counts=(1,), stable=(True,)):
        self.counts = iter(counts)
        self.stable = iter(stable)

    def refresh(self):
        count = next(self.counts)
        return {"schema": 1, "status": "pending" if count else "synced",
                "origin_sha": "a" * 40, "upstream_sha": "b" * 40, "pending_count": count,
                "commits": [{"sha": "b" * 40, "title": "@someone **title**"}] if count else [],
                "baseline_contained": True}

    def matches(self, result):
        return next(self.stable)


class NotificationTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(callable(getattr(monitor, "check", None)), "notification engine is missing")
        self.api = FakeAPI()
        self.now = datetime(2026, 10, 6, 22, tzinfo=timezone.utc)
        self.url = "https://github.com/" + monitor.TARGET + "/actions/runs/1"

    def run_check(self, snapshot=None, when=None):
        return monitor.check(self.api, snapshot or FakeSnapshot(), lambda: when or self.now, self.url)

    def test_repeat_next_day_but_not_same_day(self):
        self.assertEqual(self.run_check()["notification"], "posted")
        self.assertEqual(self.run_check()["notification"], "daily_reminder_exists")
        self.assertEqual(self.run_check(when=self.now + timedelta(days=1))["notification"], "posted")
        self.assertEqual(self.api.posts, 2)

    def test_new_commits_same_day_update_result_without_extra_reminder(self):
        self.run_check()
        result = self.run_check(FakeSnapshot((2,)))
        self.assertEqual(result["pending_count"], 2)
        self.assertEqual(result["notification"], "daily_reminder_exists")
        self.assertEqual(self.api.posts, 1)

    def test_midnight_crossing_during_fetch_uses_new_date(self):
        class CrossingSnapshot(FakeSnapshot):
            def refresh(inner):
                self.now = datetime(2026, 10, 6, 16, tzinfo=timezone.utc)
                return super().refresh()
        self.now = datetime(2026, 10, 6, 15, 59, tzinfo=timezone.utc)
        result = self.run_check(CrossingSnapshot())
        self.assertEqual(result["day"], "2026-10-07")

    def test_recovery_read_failure_preserves_uncertainty_without_retry(self):
        self.api.lose_response = True
        original = self.api.list_comments
        def comments():
            if self.api.posts:
                raise monitor.CheckError("read failed")
            return original()
        self.api.list_comments = comments
        with self.assertRaises(monitor.CheckError):
            self.run_check()
        self.assertEqual(self.api.posts, 1)

    def test_synced_does_not_post_even_with_old_reminder(self):
        self.run_check()
        result = self.run_check(FakeSnapshot((0,)))
        self.assertEqual(result["status"], "synced")
        self.assertEqual(self.api.posts, 1)

    def test_lost_post_response_is_recovered(self):
        self.api.lose_response = True
        result = self.run_check()
        self.assertEqual(result["notification"], "recovered")
        self.assertEqual(self.api.posts, 1)
        self.run_check()
        self.assertEqual(self.api.posts, 1)

    def test_failed_post_does_not_retry(self):
        self.api.fail_post = True
        with self.assertRaises(monitor.CheckError):
            self.run_check()
        self.assertEqual(self.api.posts, 1)

    def test_locked_issue_is_failure(self):
        self.api.locked = True
        with self.assertRaises(monitor.CheckError):
            self.run_check()
        self.assertEqual(self.api.posts, 0)

    def test_remote_merge_before_post_recalculates(self):
        result = self.run_check(FakeSnapshot((1, 0), (False, True)))
        self.assertEqual(result["status"], "synced")
        self.assertEqual(self.api.posts, 0)

    def test_continuously_changing_snapshot_fails(self):
        with self.assertRaises(monitor.CheckError):
            self.run_check(FakeSnapshot((1, 1), (False, False)))
        self.assertEqual(self.api.posts, 0)

    def test_user_marker_does_not_suppress_notification(self):
        self.run_check()
        self.api.comments[0]["user"]["login"] = "ordinary-user"
        self.run_check()
        self.assertEqual(self.api.posts, 2)

    def test_malformed_bot_record_fails(self):
        self.api.comments = [{"user": {"login": "github-actions[bot]"}, "body": monitor.MARKER + "{} -->"}]
        with self.assertRaises(monitor.CheckError):
            self.run_check()

    def test_local_date_and_midnight(self):
        self.run_check(when=datetime(2026, 10, 6, 15, 59, tzinfo=timezone.utc))
        self.run_check(when=datetime(2026, 10, 6, 16, 0, tzinfo=timezone.utc))
        self.assertEqual(self.api.posts, 2)
        self.assertIn("2026-10-07", self.api.comments[-1]["body"])

    def test_commit_title_cannot_mention_user(self):
        self.run_check()
        self.assertNotIn("@someone", self.api.comments[0]["body"])


class APIAndStatusTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(hasattr(monitor, "GitHub"), "GitHub adapter is missing")
        self.now = datetime(2026, 10, 7, 6, tzinfo=monitor.SHANGHAI)

    def test_comments_paginate(self):
        api = monitor.GitHub("fake", issue_number=1)
        calls = []
        def request(path, method="GET", body=None):
            calls.append(path)
            return [{}] * 100 if path.endswith("&page=1") else [{"id": 101}]
        api.request = request
        self.assertEqual(len(api.list_comments()), 101)
        self.assertEqual(len(calls), 2)

    def test_transport_error_is_not_empty_success(self):
        api = monitor.GitHub("fake", issue_number=1)
        with patch.object(monitor.urllib.request, "urlopen", side_effect=OSError("token=secret")):
            with self.assertRaises(monitor.CheckError) as caught:
                api.issue()
        self.assertNotIn("secret", str(caught.exception))

    def test_status_stale_and_latest_failed_stay_distinct(self):
        api = self.fake_status_api("failure", self.now - timedelta(hours=40))
        result = monitor.status_report(api, clock=lambda: self.now)
        self.assertEqual(result["latest_run"]["conclusion"], "failure")
        self.assertTrue(result["stale"])
        self.assertEqual(result["last_successful_check"]["pending_count"], 0)
        self.assertEqual(api.posts, 0)

    def test_old_issue_does_not_override_synced_log(self):
        api = self.fake_status_api("success", self.now)
        result = monitor.status_report(api, clock=lambda: self.now)
        self.assertEqual(result["last_successful_check"]["status"], "synced")
        self.assertFalse(result["stale"])
        self.assertEqual(api.posts, 0)

    def test_missing_logs_are_unknown(self):
        api = self.fake_status_api("success", self.now)
        api.run_logs = lambda run: (_ for _ in ()).throw(monitor.CheckError("expired"))
        result = monitor.status_report(api, clock=lambda: self.now)
        self.assertIsNone(result["last_successful_check"])
        self.assertIn("log", result["detail_error"])

    def test_error_or_duplicate_log_records_cannot_be_a_success(self):
        api = self.fake_status_api("success", self.now)
        text = api.run_logs(1)
        for bad_logs in (text + "\n" + text,
                         monitor.RESULT_PREFIX + json.dumps({"schema": 1, "status": "error"})):
            with self.subTest(logs=bad_logs):
                api.run_logs = lambda run: bad_logs
                self.assertIsNone(monitor.status_report(api, clock=lambda: self.now)["last_successful_check"])

    def test_future_check_timestamp_is_unknown(self):
        api = self.fake_status_api("success", self.now + timedelta(hours=2))
        self.assertIsNone(monitor.status_report(api, clock=lambda: self.now)["last_successful_check"])

    def test_no_workflow_runs_is_not_synced(self):
        api = self.fake_status_api("success", self.now)
        api.runs = lambda conclusion=None: []
        result = monitor.status_report(api, clock=lambda: self.now)
        self.assertIsNone(result["latest_run"])
        self.assertIsNone(result["last_successful_check"])

    def test_log_from_other_run_rejected(self):
        api = self.fake_status_api("success", self.now)
        text = api.run_logs(1).replace("/runs/1", "/runs/999")
        api.run_logs = lambda run: text
        result = monitor.status_report(api, clock=lambda: self.now)
        self.assertIsNone(result["last_successful_check"])

    def fake_status_api(self, conclusion, checked):
        api = FakeAPI()
        run = {"id": 1, "status": "completed", "conclusion": conclusion,
               "html_url": "https://github.com/" + monitor.TARGET + "/actions/runs/1",
               "created_at": self.now.isoformat(), "updated_at": self.now.isoformat()}
        success = dict(run, conclusion="success")
        api.runs = lambda conclusion=None: [success if conclusion else run]
        result = {"schema": 1, "status": "synced", "origin_sha": "a" * 40,
                  "upstream_sha": "b" * 40, "pending_count": 0, "checked_at": checked.isoformat(),
                  "run_url": run["html_url"], "source": monitor.SOURCE, "target": monitor.TARGET}
        api.run_logs = lambda run: "timestamp\t" + monitor.RESULT_PREFIX + json.dumps(result)
        return api

    def test_default_cli_never_enters_writer(self):
        with patch.object(monitor, "GitHub") as cls, patch.object(monitor, "get_token", return_value="fake"), \
                patch.object(monitor, "status_report", return_value={"state": "read-only"}) as status, \
                patch.object(monitor, "check") as writer, patch("builtins.print"):
            self.assertEqual(monitor.main([]), 0)
            status.assert_called_once()
            writer.assert_not_called()

    def test_write_cli_rejects_local_execution(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(monitor, "get_token") as token, patch("builtins.print"):
            self.assertEqual(monitor.main(["--notify"]), 1)
            token.assert_not_called()


if __name__ == "__main__":
    unittest.main(verbosity=2)
```

### .github/workflows/check-upstream.yml

```yaml
name: Check upstream commits

on:
  schedule:
    # 22:00 UTC = 06:00 Asia/Shanghai on the following day.
    - cron: '0 22 * * *'
  workflow_dispatch:

permissions:
  contents: read
  issues: write

concurrency:
  group: upstream-master-reminder
  cancel-in-progress: false

jobs:
  check:
    if: github.repository == 'BarrayAllen0818/kotoyomi-plugins' && github.ref == 'refs/heads/master'
    runs-on: ubuntu-latest
    timeout-minutes: 10
    env:
      PYTHONDONTWRITEBYTECODE: '1'
    steps:
      - uses: actions/checkout@08c6903cd8c0fde910a37f88322edcfb5dd907a8 # v5.0.0
        with:
          persist-credentials: false
      - name: Verify reminder behavior offline
        run: python3 -B .github/scripts/test_check_upstream.py
      - name: Check published history and remind
        env:
          GH_TOKEN: ${{ github.token }}
        run: python3 -B .github/scripts/check_upstream.py --notify
```
