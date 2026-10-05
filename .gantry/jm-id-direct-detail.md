# JM 编号搜索

**Target:** 实现 PRD.md 的 REQ-001：编号查询返回接口提供的有效作品卡片，由用户点击进入详情；仅修改插件。

<!-- gantry:workflow pseudocode=approved annotations=complete stabilization=complete implementation=authorized -->

## 阅读与执行边界

“Pseudocode”是用户已逐项确认的行为与接口约束；“实现建议”可在这些约束内依据代码和测试反馈调整。函数名称、数量、夹具组织不构成审批门槛。改变行为、公共契约、关键结构或扩大范围时，才暂停受影响部分并审阅最小修订。

分支沿用 `feat/jm-id-direct-detail`，文档沿用当前路径；direct-detail 是历史名称，当前没有自动跳转需求。用户于 2026-10-05 在接续消息中明确授予当前方案实现授权，沿用既有行为确认。

## 已核实的实现入口

源码调查基线为 `53de8bfa`；实施前的后续提交仅修改规则和文档。下表记录实施前源码事实；本次局部实现及验证见下文，可复用未变化部分的证据。

| 文件 / 符号 | 与实现直接相关的事实 |
| --- | --- |
| `src/main/kotlin/org/skepsun/kototoro/parsers/site/zh/JmComicParser.kt`：getListPage、buildKeyword、search | getListPage 先将 query 与标签合并，再请求 /search；编号分支应在 buildKeyword 前识别原始 query |
| 同文件：apiGet、ensureDomains、refreshImageHost | 已有鉴权、解密、重试、动态域名及图片域名初始化；初始化也会产生请求 |
| 同文件：parseComic、getDetails | parseComic 生成 jm:<id> 身份、链接和封面，并被推荐、分类、每周、搜索、收藏共用；详情响应 author 是数组，现有 getDetails 取首项 |
| `src/main/kotlin/org/skepsun/kototoro/parsers/core/PagedContentParser.kt` 与 `util/Paginator.kt`（同 parsers 根目录） | offset 0 对应第一页；返回一条后，offset 1 映射到下一页，无需另建分页器 |
| `src/test/kotlin/org/koitharu/kotatsu/parsers/ContentLoaderContextMock.kt` | 会读取本机 Cookie、环境变量和 token 文件，不适合作为隔离离线测试的默认上下文 |
| `.github/workflows/release.yml` | 实际插件包是 JVM JAR 经 D8 转换后封装的含 classes.dex 的 plugin.jar |

JM 源码与本地 upstream/master（ca313756）此前无差异；未刷新远端，不宣称已核实最新上游。

## Pseudocode

<!-- gantry:step id=gty-jm-query author=ai status=accept -->
1. 在 JM 插件识别查询编号：去除首尾空白后匹配 `[1-9][0-9]*`。编号作为字符串处理；关键词、混合文字、JM 前缀、网址和前导零输入保持现有关键词搜索。编号路径按身份定位，不叠加当前标签、分类或排序条件，也不改写用户保存的筛选设置。

用户于 2026-10-05 确认本项：纯数字标题也按编号处理；编号查询优先于站点筛选，宿主内容屏蔽仍生效。

<!-- gantry:step id=gty-jm-plugin author=ai status=accept -->
2. 在 JM 插件 getListPage 中、buildKeyword 之前识别原始 filter.query。编号请求仅在第一页调用 `/album?id=<输入编号>`，后续页返回空列表。复用 apiGet 的鉴权、解密与域名逻辑；校验响应具有有效作品 ID 和名称，再返回一条 Content，允许响应 ID 与输入不同。沿用现有 UID 生成规则和 URL 格式，正确解析详情响应的作者数组。普通查询走原有代码。

用户于 2026-10-05 确认本项：详情接口仅用于取得数据，结果仍显示为普通卡片；沿用作品身份以保护收藏关联，后续分页不重复请求或返回作品。

用户随后明确调整：原先要求返回编号与输入相同，现改为编号不同也返回有效作品；这项新确认取代原编号相等约束。

<!-- gantry:step id=gty-jm-returned-identity author=ai status=accept -->
2.1. 以响应中实际返回的作品 ID 生成 `jm:<返回编号>` UID、详情 URL 和封面 URL，确保卡片、点击详情与收藏对应同一个返回作品。输入编号仅用于发起查询；不把返回作品的内容与输入编号的身份混合，也不迁移已有收藏。

用户于 2026-10-05 确认本项：输入编号与返回编号不同时，以实际返回编号统一确定作品身份、封面及详情链接。

<!-- gantry:step id=gty-jm-errors author=ai status=accept -->
3. 编号不存在且接口未返回有效作品、响应缺少必要字段时，返回带 JM 和输入编号上下文的错误；网络及鉴权错误沿用现有错误流程。接口返回有效作品时，即使作品编号与输入不同也正常显示。不回退成关键词搜索，不额外调用 search.redirect_aid 路径，也不返回伪造的占位作品。

用户于 2026-10-05 确认第 3 项并修正编号不匹配分支：仍返回作品；其余失败处理保持。

<!-- gantry:step id=gty-jm-compat author=ai status=accept -->
4. 通过现有 List<Content> 接口返回编号查询结果，保持 ContentParser、Content 和 JAR ABI 不变。宿主照常显示结果卡片，并沿用成人内容、标签屏蔽、点击详情、刷新和返回行为。本任务不增加导航事件或宿主状态机；更新插件即可提供编号查询能力。其他调用 JM getList 的入口（包括全局搜索中的 JM 子结果）也会收到编号查询结果，其他来源不变。

用户于 2026-10-05 确认第 4 项：仅修改插件及对应测试，复用现有宿主行为，全局搜索中的 JM 子结果同步支持编号查询，其他来源和普通关键词搜索保持原行为。

## 改动范围与薄适配约束

- 生产代码集中在 `JmComicParser.kt` 的查询入口及局部响应转换；测试扩展 `src/test/kotlin/org/skepsun/kototoro/parsers/site/zh/JmComicTest.kt`，按需要增加同目录测试辅助文件和 `src/test/resources/fixtures/jm/` 最小夹具。
- 用少量私有逻辑处理编号分流和详情响应形态，复用已有网络、身份和列表映射能力；不复制整段 getDetails 或章节解析，不建立单站点专用框架。
- 保持普通查询、推荐、分类、每周及收藏的既有语义。特别注意 parseComic 被多入口复用，局部适配不能无意改变其所有调用方。
- 宿主、ContentParser/Content 公共契约、共享分页器、网络基础设施、依赖与发布工作流不在改动范围。出现必须修改这些部分的证据时，先说明具体缺口和新增范围。
- 同步上游时重点复查查询入口、结果映射及其回归测试；上游已有等效能力时再评估移除个人补丁。薄适配以职责集中和兼容证据判断，不以文件数或行数判断。

## 实现建议（可自主调整）

1. getListPage 先识别 query；编号且非第一页时尽早返回空列表，第一页调用局部编号查询逻辑。非编号保留原 filter/query 进入旧分流。优先沿用 searchPaginator.firstPage，避免复制分页状态。
2. 可将“输入识别、请求、响应转换”拆成私有函数，或按实际代码合并。请求调用 apiGet；结果映射优先局部规范化详情 JSON 后复用 parseComic，再补齐必要字段。无需固定三个函数、精确签名或新建文件。
3. 对照 parseComic/getDetails 处理返回 ID、原始标题、作者数组、描述及标签；参考既有首作者语义，避免数组或 JSON null 被当作展示字符串。UID 使用 generateUid，URL 使用当前 baseUrl/imageHost，chapters 可保留未加载状态，点击后走原 getDetails。
4. 新增解析错误优先复用现有 ParseException，带 JM、输入编号、字段或请求 URL 上下文；网络与鉴权异常保留既有类型及处理路径，不用 catch-all 转为空结果。JSON null 可复用 util/json/JsonExt.kt 的 getStringOrNull。错误类的局部选用可调整，但不能改变已确认的失败语义或破坏宿主识别。
5. 离线测试优先使用独立 ContentLoaderContext 和 OkHttp 拦截器提供合成响应；拦截域名初始化、setting 及业务请求，禁止实际联网和读取本机凭据。响应构造参考 apiHeaders 的 tokenparam、convertData 的协议及 SourceConfigMock；测试辅助类、调度方式与夹具文件名由实现者选择。

上述建议不是新增产品要求。若复用方式不适配，先在局部转换或测试层调整并验证；不得以“建议可调整”为由改变已确认语义、扩大范围或跳过失败用例。

## 待验证假设与处理边界

| 假设 / 未验证项 | 何时及如何取得证据 | 失败后如何处理 |
| --- | --- | --- |
| 局部详情响应转换可复用 parseComic，并保持标题、作者、标签和身份正确 | 实现初期用最小响应夹具经过公开 getList 检查映射 | 可调整私有转换和字段传递；若必须影响普通列表或共享模型，暂停对应范围 |
| 独立上下文可覆盖域名初始化、动态 token 加密响应及协程重试 | 先跑一个现有普通搜索成功例，再跑编号查询的 RED 用例 | 先修测试接线；环境/加密错误不能当成功能缺失证据，不为测试放宽生产接口 |
| 当前线上 /album 响应与既有解析代码及合成夹具一致 | 合成测试只证明约定；交付时以允许的在线验证或用户实际编号验收 | 调整局部解析后补回归；涉及已确认行为变化才重审，不凭空回填输入 ID |
| 本机可制作且宿主可加载本次 dex 插件包 | 打包阶段检查既有 SDK/D8；交付后检查真实加载 | 缺少条件时报告未完成，不把 JVM 编译通过当设备加载通过，也不修改宿主或部署流程 |

已核实的是现有 /album 调用路径、List<Content> 接口和分页契约；上述局部实现或环境检查允许延后，不是已经通过的实验。没有已知的方案可行性阻塞；实施中出现相反证据，应保留现场并按影响范围处理。

## 必需验证与通过条件

| 场景 | 关键断言 |
| --- | --- |
| 编号与非编号分流 | 首尾空白处理符合规则；正整数走 /album，前导零、JM 前缀和关键词走原路径；标签/排序不覆盖编号，filter 未改写 |
| 输入与返回编号不同 | 如输入 123456、返回 654321，得到一张卡片；UID、封面和详情链接均对应 654321；没有 /search 回退 |
| 字段及身份兼容 | 标题原文保留，作者不显示 JSON 数组文本，返回 ID 缺失不使用输入补齐；同一作品在原列表与编号路径的 UID 一致，域名变化不改变 UID |
| 公开分页和后续详情 | 同一 parser 经 getList offset 0 返回一条，offset 1 返回空且不重复业务查询；切换查询从 offset 0 正常工作；结果交给 getDetails 后请求实际返回编号 |
| 失败与恢复 | 无有效作品、缺必要字段或非法响应可观察地报错；网络、鉴权继续原处理；返回不同编号是成功例，失败不伪造卡片或改成关键词搜索 |
| 原有行为 | 普通关键词及标签组合、受影响的原列表映射、已有每周排序测试保持原结果；未改的路径按实际影响决定回归深度 |

核心测试通过公开 getList/getDetails，不能只测正则或私有 helper。全部默认测试离线、隔离且可重复；区分初始化与业务请求次数。具体测试数量和夹具接线可调整，表内行为覆盖不能删除。

## 实施与交付顺序

1. 核对源码、已确认文档和实现授权；先验证最小测试接线，再使代表性的编号行为测试因缺少功能而失败，随后实现局部补丁并通过适用回归。
2. 执行 `./gradlew.bat test --tests "org.skepsun.kototoro.parsers.site.zh.JmComicTest" --no-daemon` 及 `./gradlew.bat compileKotlin jar --no-daemon`。运行 Gradle 优先使用 CI 的 JDK 21，保留 JVM toolchain 8。只因新变更或未解决风险扩大验证。
3. 审查实际 diff 的职责边界和受影响调用方；记录有意义的实现调整及证据，不逐项记录命名和语法变化。按 Git 策略阶段提交，既有文档修改保留，不夹带宿主或规则修改。
4. 按现有 release 工作流制作 dex plugin.jar，构建产物放项目 build 等既有 D 盘目录。用户后续明确要求通过远端 URL 更新仓库再验收：将已验证产物发布到既有 repo 分支并推送实现分支，不推送或合并主线。分别报告离线测试/编译、发布、实际插件加载、用户验收。
5. 用户在现有宿主载入新插件后验证有效/无效编号、返回编号不同的作品、点击详情、关键词和全局 JM 结果；确认作品未被现有屏蔽规则隐藏。界面操作由用户完成，需要 ADB 时沿用既有连接及失败停止规则。未验收不合并。

## 确认历史与当前状态

- 2026-10-05：用户原先要求自动进入详情，后明确取消；当前仅返回卡片，宿主改动撤回。
- 2026-10-05：用户逐项确认上文第 1–4 项及第 2.1 项；原“返回编号必须等于输入”已被“返回有效作品并使用实际返回编号”取代，原确认不再适用。
- 2026-10-05：曾新增未获批准的第 5 项及 A–J 详细蓝图；用户随后确认新版 dev-workflow，并要求重写方案。该未决整包提案撤回，局部写法改列为建议，不增加行为或接口决定，不重开已有确认。
- 当前已核对约束、建议、假设及验证要求的一致性；没有新增必须用户选择的设计项。原行为审阅和稳定性结论继续有效；2026-10-05 接续消息已授权实现，implementation 更新为 authorized。
- 本次已完成局部实现、37 个隔离离线用例、JVM 构建、D8 打包和远端发布；用户于 2026-10-05 对 1.0.135 明确回复“验收通过”，进入主线收尾。验收记录与自动检查分别保留，不沿用已撤回的固定函数数目和测试蓝图。

## 实现与验证证据（2026-10-05）

- `getListPage` 在域名初始化与 `buildKeyword` 前识别原始 query；非第一页立即返回空列表。`searchAlbum` 仅规范化本次详情响应后复用 `parseComic`，按首作者语义和详情标签生成卡片；共享列表映射、getDetails、apiGet 均未改写。
- 接线先验证普通搜索及每周排序通过；修正测试中嵌套 runTest 后，37 个用例中 23 个因缺少编号功能而失败。实现后全部通过；网络异常断言检查类型、消息及重试次数，不依赖协程堆栈恢复前后的异常对象引用相同。
- 实际命令：`./gradlew.bat test --tests "org.skepsun.kototoro.parsers.site.zh.JmComicTest" compileKotlin jar --no-daemon -Dorg.gradle.java.installations.paths=D:/Tools/Java/temurin-8,D:/Tools/Java/temurin-21`，运行 JDK 为 `D:/Tools/Java/temurin-21`。结果：37 tests，0 failures/errors/skipped，BUILD SUCCESSFUL。日志及 RED/GREEN XML 位于忽略目录 `build/jm-verification/`。
- 核对本次及完整任务差异，未发现阻塞性缺陷；其他调用方继续使用未改变的 parseComic，非编号分支保持原代码。未运行可能包含在线请求的全仓测试；当前修改仅触及 JM 局部路径。
- `javap -public` 前后对比：既有签名无删除或变化；仅新增编译器为私有挂起函数生成的 `access$searchAlbum` 合成桥接方法。ContentParser、Content、共享分页器、依赖及发布工作流未修改。
- 按 release 工作流执行 D8 36.0.0：`d8.bat --release --lib D:/Tools/Android/sdk/platforms/android-34/android.jar --output build/jm-plugin build/libs/kototoro-parsers-1.0.jar`，再将 classes.dex 封装为 `build/jm-plugin/plugin.jar`。转换和封装退出码均为 0；DEX magic 为 dex 035，包含 JmParser 及 searchAlbum。D8 提示宿主依赖类型（Kotlin、OkHttp、JSoup 等）未在单库输入中提供；用户随后对该发布产物整体验收通过，未另行提供加载日志。
- JVM JAR SHA-256：`33066545784848AC9031DF21FFC37E208A2D85D3F37CF43C5DBAB73B60632B0B`；dex plugin.jar SHA-256：`31B70BC64392C9DF596E1067371D5B446E6ED9E9450FA084A7BB17A805474AE7`。按后续发布授权，dex 产物仅进入 repo 发布分支；源码分支不提交构建产物、日志或缓存。
- 插件已按用户后续授权发布远端，用户对 1.0.135 整体验收通过。助手未执行在线 JM 接口或设备 UI 验证，不将用户整体回复扩展为逐项测试日志；既有离线覆盖与用户验收分别作为证据保留。

## 远端验收发布（2026-10-05）

- 用户明确要求推送远端，通过 URL 更新 Kotoyomi 仓库后开始验收；这项新授权覆盖此前“不推送或部署”的本轮边界，仍不构成验收通过或主线合并授权。
- 发布提交 `9e0bfa996eed9e8a459446198694c7095cb09df5` 是原 repo 提交 `58950ebc43b53cbfd8d4523544e870fd96b0c1cc` 的子提交；只更新 `apk/plugin.jar` 及 `index.min.json` 的 code/version（134 → 135、1.0.134 → 1.0.135），保留 .nojekyll、其他索引字段及发布历史，无强制推送。
- 原子推送实现分支和发布分支成功，master 仍为 `53de8bfa7e57e84122415a65bcc8fe1d17935970`。实现源码与测试未变化，复用 37 个用例、JVM 构建及 D8 的有效证据，不重复构建。
- 仓库根 URL：`https://raw.githubusercontent.com/BarrayAllen0818/kotoyomi-plugins/repo/`；索引：`https://raw.githubusercontent.com/BarrayAllen0818/kotoyomi-plugins/repo/index.min.json`；插件：`https://raw.githubusercontent.com/BarrayAllen0818/kotoyomi-plugins/repo/apk/plugin.jar`。
- HTTP 实测索引 version=1.0.135、code=135；下载发布插件，SHA-256 与已验证产物一致：`31B70BC64392C9DF596E1067371D5B446E6ED9E9450FA084A7BB17A805474AE7`，JAR 含 classes.dex。Git 与 HTTP 发布通过不代替 Kotoyomi 的真实加载和用户界面验收。

## 用户验收与收尾（2026-10-05）

- 用户在远端 1.0.135 交付后明确回复“验收通过”；验收对象为 repo 提交 `9e0bfa99` 发布的 dex 插件，SHA-256 为 `31B70BC64392C9DF596E1067371D5B446E6ED9E9450FA084A7BB17A805474AE7`。整体接受当前交付，不生成用户未提供的逐项操作记录。
- 收尾前工作区干净，master 与 origin/master 同为 `53de8bfa`，任务分支与远端同为 `93d591da`；源码、测试、构建配置和发布工作流与验证实现相同，37 用例报告及产物哈希核对通过，复用已有验证，不重复构建或安装。
- 按 Git 策略将 `feat/jm-id-direct-detail` 以 `--no-ff` 合入 master 并自动推送；保留任务分支、repo 发布分支和当前 1.0.135 产物。合并范围包含此前已提交的项目级额外 Git 限制移除、需求与方案记录、Brain 页面归档与现行规则、JM 实现、离线测试及验收发布记录，不新增宿主改动。

## Code

日期：2026-10-05。实现提交：`b1791fa236f24fe55f228f61bcfc6f979a1aded2`。

以下保存新增属性、查询入口和局部转换的原样片段；完整源码、测试和夹具以该提交为准。此快照不作为第二份可编辑实现。

```kotlin
    private val albumIdPattern = Regex("[1-9][0-9]*")

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val albumId = filter.query?.trim()?.takeIf { albumIdPattern.matches(it) }
        if (albumId != null) {
            return if (page == searchPaginator.firstPage) listOf(searchAlbum(albumId)) else emptyList()
        }
        ensureDomains()
        val sort = sortParam(order)
        val weeklyTag = filter.tags.firstOrNull { it.key.startsWith("w:") }
        val categoryTag = filter.tags.firstOrNull { it.key.startsWith("c:") }
        // 其余标签（含无前缀的详情页标签）都作为搜索关键字
        val searchTags = filter.tags.filterNot { it.key.startsWith("c:") || it.key.startsWith("w:") }

        // jm.js 里分类标签走 categories/filter，不与搜索组合
        val keyword = buildKeyword(filter.query, searchTags)
        return when {
            !keyword.isNullOrBlank() -> search(keyword, page, sort)
            weeklyTag != null -> weekList(weeklyTag.key.removePrefix("w:"), order, page)
            categoryTag != null -> categoryList(sort, page, categoryTag.key.removePrefix("c:"))
            else -> promote(sort, page)
        }
    }

    private suspend fun searchAlbum(inputId: String): Content {
        val path = "/album?id=$inputId"
        val jsonText = apiGet(path)
        val json = try {
            JSONObject(jsonText)
        } catch (e: JSONException) {
            throw ParseException("JM 编号 $inputId: 无效作品响应", "$baseUrl$path", e)
        }
        val returnedId = (json.getStringOrNull("id") ?: json.getStringOrNull("album_id"))
            ?.takeIf { albumIdPattern.matches(it) }
            ?: throw ParseException("JM 编号 $inputId: 缺少有效作品 id", "$baseUrl$path")
        if ((json.opt("name") as? String).isNullOrBlank()) {
            throw ParseException("JM 编号 $inputId: 缺少作品 name", "$baseUrl$path")
        }

        // /album 的作者是数组；仅规范化本次响应，保持其他列表调用方的映射不变。
        json.put("id", returnedId)
        json.put("author", (json.optJSONArray("author")?.opt(0) as? String).orEmpty())
        json.put("description", (json.opt("description") as? String).orEmpty())
        val comic = parseComic(json)
            ?: throw ParseException("JM 编号 $inputId: 无有效作品", "$baseUrl$path")
        val tags = buildSet {
            val array = json.optJSONArray("tags")
            if (array != null) {
                for (i in 0 until array.length()) {
                    val tag = (array.opt(i) as? String)?.takeIf { it.isNotBlank() } ?: continue
                    add(ContentTag(tag, tag, source))
                }
            }
        }
        return comic.copy(tags = tags)
    }
```
