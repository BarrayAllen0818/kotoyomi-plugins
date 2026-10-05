# JM 编号搜索

**Target:** 实现 PRD.md 的 REQ-001：JM 来源内提交作品编号后返回对应作品卡片，点击后按现有流程进入详情；普通关键词搜索保持原行为，仅修改插件。

<!-- gantry:workflow pseudocode=pending annotations=pending stabilization=pending implementation=pending -->

## 当前证据与职责

- 插件基线为 `53de8bfa7e57e84122415a65bcc8fe1d17935970`，任务分支为 `feat/jm-id-direct-detail`。沿用现有分支与文档路径，名称保留历史，不代表仍要求自动跳转。
- `JmParser.getListPage` 将 query 与搜索标签合成关键词；`search` 只调用 `/search` 并解析 content 数组，没有编号查询或导航信号。
- `JmParser.getDetails` 已调用 `/album?id=...`；`parseComic` 使用 `generateUid("jm:$id")` 构造身份。新增行为必须沿用身份，不改变收藏关联。
- `ContentParser.getList` 返回 `List<Content>`；接口没有页面导航能力。`AbstractContentParser.resolveLink` 默认返回 null，JM 未覆盖该方法。
- 宿主现有搜索入口装载并显示插件返回的列表，点击卡片进入详情。当前需求直接复用该流程，宿主代码不在本任务修改范围。
- JAR 解析器经 `ParserContentRepositoryProvider`、`ParserContentRepository.getList` 调用插件。`JarExtensionLoader` 按 source.name 匹配来源；JM 内部名为 `JMCOMIC`。
- 插件 JM 文件与本地 `upstream/master`（`ca313756`）无差异。本结论仅针对已有远端跟踪引用，不代表最新上游。

## Pseudocode

<!-- gantry:step id=gty-jm-query author=ai status=open -->
1. 在 JM 插件识别查询编号：去除首尾空白后匹配 `[1-9][0-9]*`。编号作为字符串处理；关键词、混合文字、JM 前缀、网址和前导零输入保持现有关键词搜索。编号路径按身份定位，不叠加当前标签、分类或排序条件，也不改写用户保存的筛选设置。

<!-- gantry:step id=gty-jm-plugin author=ai status=open -->
2. 在 JM 插件 getListPage 中、buildKeyword 之前识别原始 filter.query。编号请求仅在第一页调用 `/album?id=<编号>`，后续页返回空列表。复用 apiGet 的鉴权、解密与域名逻辑；校验响应的作品 ID 与请求相同且名称有效，再返回一条 Content。沿用 `jm:<id>` UID 和现有 URL 格式，正确解析详情响应的作者数组。普通查询走原有代码。

<!-- gantry:step id=gty-jm-errors author=ai status=open -->
3. 编号不存在、响应缺少必要字段或身份不匹配时，返回带 JM 和编号上下文的错误；网络及鉴权错误沿用现有错误流程。不回退成模糊搜索，不采用 search.redirect_aid 隐式替换作品，也不返回伪造的占位作品。

<!-- gantry:step id=gty-jm-compat author=ai status=open -->
4. 通过现有 List<Content> 接口返回精确结果，保持 ContentParser、Content 和 JAR ABI 不变。宿主照常显示结果卡片，并沿用成人内容、标签屏蔽、点击详情、刷新和返回行为。本任务不增加导航事件或宿主状态机；更新插件即可提供编号查询能力。其他调用 JM getList 的入口（包括全局搜索中的 JM 子结果）也会收到精确结果，其他来源不变。

## 修改范围与维护成本

- 当前仓库：`src/main/kotlin/org/skepsun/kototoro/parsers/site/zh/JmComicParser.kt`、`src/test/kotlin/org/skepsun/kototoro/parsers/site/zh/JmComicTest.kt`，按需要增加脱敏离线夹具。
- 插件负责站点查询，宿主继续展示列表和处理点击。没有共享接口或数据库迁移，不引入依赖。
- 必要直接补丁集中在 JM 查询入口及作品结果映射；后续同步上游复查编号识别、身份稳定性、分页与普通关键词查询。
- 宿主仓库、宿主测试及其任务分支均不在修改范围，也不需要为本任务安排宿主工作树。
- 架构自检仅针对本方案边界：编号与结果判定可离线测试；解析器不依赖导航；无持久化改动、组件反向依赖或新增循环。此为设计检查，不是实现通过证据。

## 验证计划

1. 插件离线测试：有效编号的精确结果和 UID、作者数组、原始标题；编号查询不访问 /search；第一页后不重复请求；错误/缺字段/ID 不匹配；普通关键词与标签组合回归。HTTP 通过现有 OkHttp 测试拦截方式返回脱敏夹具，不依赖线上服务。
2. 分页回归：通过公开 getList 接口验证第一页的一条精确结果及后续空结果，避免仅测 getListPage 而遗漏 Paginator 的 offset 换算。
3. 实现后执行插件 `gradlew.bat test --tests "org.skepsun.kototoro.parsers.site.zh.JmComicTest" --no-daemon` 与 `compileKotlin`。按现有发布方式验证插件 JAR 可由现有宿主加载，不构建或修改宿主。
4. 用户验收：载入新版插件，确保 JM 及目标作品未被现有屏蔽规则隐藏；输入有效编号，应停留在列表并显示对应卡片；点击后进入正确详情。另检查无效编号、关键词、翻页以及全局搜索中的 JM 子结果。界面操作由用户完成，自动检查不代替用户验收。

## 审阅状态

- 用户已明确要求取消自动跳转，并授权按新范围修改 PRD 和方案。
- 修订方案仍为 AI 起草，待用户审阅；纯数字识别范围、编号优先于筛选及失败处理尚未因本次文档修改自动获批。
- 本次提交仅保存需求、待审方案和项目记录，不代表方案批准或实现授权；尚未执行功能测试、构建、安装、合并或推送。

## 需求变更历史

- 2026-10-05：最初确认的需求要求编号提交后自动进入详情；首次方案因此提出插件与宿主配套修改，方案未获批准。
- 2026-10-05：用户明确取消跳转详情要求。当前目标改为插件返回精确结果卡片；撤回宿主跳转策略、一次性导航状态、宿主修改与隔离安排、宿主构建和对应测试计划。本文当前正文为修订方案，历史不构成执行要求。

## Code

尚未实现。
