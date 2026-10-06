# NH 网站改版兼容修复

**Target:** 实现 PRD.md 的 REQ-002，恢复 NH 列表封面、作品详情和正文图片，并保持搜索、筛选与分页排序可用。

<!-- gantry:workflow pseudocode=approved annotations=complete stabilization=complete implementation=authorized -->

## 状态与阅读约定

用户已于 2026-10-06 确认根因、需求、策略 A、整体方案及修正后的第 3.2–3.4 项。全部步骤 accept，交接和稳定性检查完成。实施接续对话取得当前稳定版的实施、验证、打包及本地阶段提交授权，implementation 为 authorized。此后用户确认项目 AGENTS 的验收前自动推送与插件发布规则，并在发布接续消息明确要求执行，覆盖此前不推送/发布边界；主线合并仍须用户验收。分支为 `fix/nhentai-api-v2`，源码基线为 `f9965c747a848c34b7cab1da39c69e17979254ca`。当前已实现并发布 1.0.136，发布证据见下文，宿主加载及用户验收待完成。

Pseudocode 中的行为、身份及错误边界属于设计约束。私有函数拆分、夹具命名和局部代码写法属于实现建议，可在已确认约束内调整。按 dev-workflow 在聊天中审阅，不启动浏览器编辑器。

## 已核实的事实

以下网络证据取得于 2026-10-06，属于电脑端公开匿名 GET 对照，不替代用户手机验收，也不保证其他网络始终可用。

| 证据 | 结论及限制 |
| --- | --- |
| 用户截图与操作说明 | 列表有标题但封面空白，详情报 403，点击“开始阅读”后加载页报 403；同一手机浏览器图片正常，图像加载优化代理已关闭 |
| `/api/gallery/385440` 多次返回 403，正文 `Use new API https://nhentai.net/api/v2/docs` | 旧 API 已失效，此响应不是验证码页面 |
| `/api/v2/galleries/385440` 和 `/api/v2/galleries/686046` 返回 200 | v2 使用顶层 `cover.path`、`thumbnail.path`、`pages[].number/path/thumbnail`；保留 id、media_id、title、tags 等信息 |
| `/api/v2/openapi.json` 的 gallery 文档 | 详情读取是 Public，Token/API Key 为可选；匿名限速为每 IP 每分钟 20 次，本任务不新增账号或鉴权配置 |
| `/api/v2/cdn` 返回 200 | `image_servers` 为 i1–i4 的 HTTPS 基址，`thumb_servers` 为 t1–t4 的 HTTPS 基址；由站点提供 CDN 配置 |
| JPG 作品 385440、WebP 作品 686046 | 各自封面、首图、末图按 CDN 配置加返回路径请求，均为 200 且 Content-Type 为对应图片类型 |
| 686046 的 `cover.path` 为 `galleries/4225353/cover.webp.webp` | 重复后缀是有效站点路径，不做去重或扩展名猜测 |
| 首页样本 30 张图片均有非空 `src`，没有 `data-src` | JSoup 的 `attr()` 返回空字符串，旧 Elvis 空值回退无法取得 `src` |
| 旧语言排序 URL 的 `page=2` 最终落到内嵌 API 的 `page=1`；旧标签排序路径为 404 | 新地址将排序放在 `sort` 查询参数中；同样请求头下新语言第 2 页及新标签排序地址均为 200 |
| 阅读网页数据位于 `script[type=application/json][data-sveltekit-fetched]` 的 JSON `body` 字符串 | 原 `window._gallery` 回退不能解析新网页；主流程采用 v2 后不再需要旧 JSON.parse 解码方式 |

## 实现入口与影响边界

生产改动集中在 `src/main/kotlin/org/skepsun/kototoro/parsers/site/all/NhentaiParser.kt`。下表为当前源码事实。

| 符号或文件 | 作用与拟影响 |
| --- | --- |
| `getListPage`、`parseGalleryList` | 现有列表仍可返回卡片；修复图片属性读取与语言、标签排序 URL 的分页保持 |
| `fetchGallery` | 被 `getDetails` 和 `getPages` 调用，是替换旧 API 的共用入口 |
| `getDetails` | 当前从旧 `images.thumbnail.t` 拼封面；改为读取 v2 的封面路径；作者、标签、语言和翻译标记沿用既有映射 |
| `getPages`、`getPageUrl` | 当前从旧 `images.pages[].t` 猜扩展名并使用固定主机；改为消费真实页面路径并返回可加载的 URL |
| `fetchGalleryFromHtml`、`imageExt`、`imageHost`、`checkProtection` | 按已选 A 移除旧网页回退和不可达的响应检测；旧类型码及固定主机逻辑由已确认 v2 路径/CDN 方案替换 |
| `network/OkHttpWebClient.httpGet/ensureSuccess` | 400–599 会先抛异常，原 httpGet 后面的 403 检测及失败回退不可达；共享网络实现不在本任务修改范围 |
| `util/Jsoup.kt` 的 `src`、`attrAsAbsoluteUrlOrNull` | 已具备跳过空属性和相对 URL 解析能力，应直接复用 |
| `core/AbstractContentParser.domain`、NH `intercept` | domain 每次读取当前配置；NH 拦截器目前会覆盖 Referer 为实时 domain，必须与请求域名快照衔接 |
| `util/ContentParserEnv.generateUid(String)`、`model/ContentPage.headers` | UID 会散列整个输入字符串，不自动去域名；页面支持独立请求头，可携带创建时的 Referer |
| `NhentaiParserTest`、`NhentaiParserIntegrationTest` | 当前仅有列表夹具测试和“列表成功或 Cloudflare 挑战”的线上测试；需要覆盖详情、图片路径、错误与网络请求契约 |

GitNexus 绑定本仓库，索引提交与源码基线相同；`fetchGallery` 的影响查询未解析到目标，不将空结果视为无调用。Serena 与源码搜索确认其直接调用方为本类 `getDetails/getPages`。宿主动态调用通过现有解析器接口进入，图谱不是完整宿主影响证明。

当前 NH 文件与本地 `upstream/master`（`ca313756e395b5ddbd201e01cc01ece01078d15c`）无差异；未刷新 upstream 远端，不宣称已核实最新上游。采用 NH 类内局部适配，不改变共享模型、宿主或解析器公共签名。后续同步的冲突集中于 NH 的列表、详情和图片解析方法，应逐项比较上游是否已有等效 v2 支持，不复制整份解析器或改造通用网络层。

## Pseudocode

<!-- gantry:step id=gty-nh-list author=ai status=accept -->
1. 保留现有列表 HTML 请求与卡片映射，恢复真实图片地址和筛选分页。
  - 使用现有 JSoup URL 工具，按 `data-src`、`src`、`data-cfsrc` 依次取首个非空地址，支持协议相对及页面相对 URL；全部缺失时封面为 null，不传空字符串给图片加载器。
  - 保留查询优先级：关键词、语言标签、普通标签、全局排序、首页；不新增过滤能力或更改已保存的标签 key。
  - 语言和普通标签地址使用 `?page=页码&sort=排序值`；默认排序省略 sort，排序值沿用现有映射。关键词和全局排序继续使用已验证的搜索入口。
  - 保留作品 ID、标题、来源、公共链接和现有语言提取行为。主站/API 请求使用配置中的 domain。

<!-- gantry:step id=gty-nh-detail author=ai status=accept -->
2. 详情和阅读共用 v2 作品响应，将站点数据映射到现有模型。
  - `fetchGallery` 请求 `/api/v2/galleries/{galleryId}`，不再调用已停用的 `/api/gallery/{galleryId}`。
  - 校验返回 id 与请求作品一致；身份不匹配或必需字段损坏时报包含 NH、作品编号和字段名的 ParseException，不把另一作品数据绑定到现有收藏。
  - `getDetails` 使用 `cover.path`，保留原作品 `id/url/publicUrl`，章节仍使用现有 `${manga.id}-0` 生成方式和原章节 URL；作者、标题回退、标签、语言及 translated 映射复用当前逻辑。
  - `getPages` 读取顶层 pages，按 number 升序输出；页面路径必须非空，页号不能重复，声明的 num_pages 与实际页数应一致。损坏或空页集报解析错误，不静默返回半本或空列表。
  - 使用站点原始 path，保留 `.webp.webp` 等有效路径，不再由类型码拼后缀。正文预览可以沿用正文 URL，以维持现有预览语义。

<!-- gantry:step id=gty-nh-cdn author=ai status=accept -->
3. 通过公开 CDN 配置解析相对图片路径，使正文与封面跟随站点当前图片服务器。
  - 从 `https://{domain}/api/v2/cdn` 取得 image_servers、thumb_servers；两个列表必须含可用 HTTPS 基址。正文选择 image_servers，封面选择 thumb_servers，固定选配置中首个有效项，避免每次随机导致缓存抖动。
  - 在解析器实例内按 domain 缓存成功配置 10 分钟；并发初始化合并，切换 domain 时失效。失败不缓存，不自动轮询或遍历多个 CDN 重试。
  - 相对 path 用现有 HttpUrl 解析能力拼接，绝对 HTTPS 图片地址直接使用；不修改路径中的扩展名，不猜备用地址。配置获取失败或没有可用服务器时保留真实错误，不写死网络回退主机。
  - 新生成 ContentPage 的 UID 使用规范化的相对媒体路径，避免 CDN 主机切换改变页身份；作品和章节 UID 不变。旧网络页缓存可能需要重新获取，不执行收藏迁移、数据删除或下载目录清理。
  - 继续沿用现有 Referer 和 User-Agent 策略；本次证据不要求修改宿主 Cookie、Cloudflare 策略或网络代理。

<!-- gantry:step id=gty-nh-error-policy author=ai status=accept -->
3.1. v2 请求失败时直接传播权限、验证码、限速、网络异常及取消，不请求网页回退。删除 `fetchGalleryFromHtml` 和不可达的插件级 `checkProtection` 检测及调用；保留宿主和共享网络层现有处理。失败不伪装成空列表或未更新的详情成功结果。

<!-- gantry:item id=gty-nh-errors type=edge status=choice-a mode=choice -->
- [x] **edge:** [choice-a] 用户选择仅使用 v2 API 并保留真实错误，删除失效旧网页回退；不维护第二套网页解析，避免额外请求及掩盖 403/429。
  - A: 使用 v2 API，传播权限、验证码、限速及网络异常，删除失效的旧网页回退和不可达的插件级响应检测。
  - B: 额外维护新版网页内嵌 JSON 回退，仅在 v2 返回 5xx 时请求公开阅读网页一次；403、429、404、验证码及取消不回退。该分支需补充网页封装解析和第二次请求的测试。
  - comment: 用户于 2026-10-06 明确回复“A”确认本错误策略；随后在单独的整体确认请求后回复“确认”，批准其余四项方案。

<!-- gantry:step id=gty-nh-request-context author=ai status=accept -->
3.2. 在每次 `getListPage/getDetails/getPages` 入口读取一次 domain，形成该操作的不可变请求上下文，向下传递域名和 Referer；各私有方法不再独立读取 domain 来决定同一操作的请求目的地。
  - 列表/API/CDN 请求显式携带 `Referer: https://{快照域名}/`；正文 `ContentPage.headers` 保存同一 Referer。NH 拦截器保留已有 Referer，仅在缺失时补默认值；User-Agent 沿用原策略。
  - 一次操作从 A 开始，即使配置中途切到 B，其 API、CDN 和返回页面请求头仍属于 A；下一次操作使用 B。保留已经开始的 A 操作，不因设置变化额外取消或自动重发。详情沿用原 publicUrl 的既有约束；列表新建 publicUrl 使用该次快照。
  - 列表及详情封面使用既有 Content.coverUrl 契约，不新增封面请求头模型；后续封面请求由宿主按当时来源配置补头。本项保证插件一次解析操作及其正文页面的上下文一致，不承诺域名切换后旧列表封面仍使用历史 Referer。

<!-- gantry:step id=gty-nh-cache-mechanism author=ai status=accept -->
3.3. CDN 缓存放在 NH 实例内，用一个协程 Mutex 保护“检查缓存 → 必要时请求 CDN → 校验 → 发布成功值”的全过程；其他 API 和图片请求不持有此锁。
  - 缓存是一条不可变记录：所属 domain、两类已校验基址、成功校验时的单调时间。获得锁后检查实时 domain；与缓存所属 domain 不同时清除旧记录。仅当操作快照、实时 domain、缓存 domain 三者相同且缓存年龄小于 10 分钟时命中。
  - 未命中时，请求操作快照所属的 `/api/v2/cdn`。请求期间持有 Mutex；在当前域名保持稳定、首次请求成功且缓存未到期的条件下，后续同域等待者获取锁后命中缓存，合并成功初始化。域名切换后的旧操作仍按自己的快照执行，其结果不能发布到当前域缓存，因此多个旧操作可能分别请求 CDN，新域操作须等待排在前面的操作，不承诺最多等待一次请求。不引入后台任务、独立 CoroutineScope 或额外优先级调度。
  - 发布前再次读取实时 domain；仅与操作快照相同时写入带该 domain 的记录，比较和写入位于同一缓存临界区。配置的外部写入不受此锁控制，因此所有后续读取仍必须校验 domain，不能依赖这一次比较保证全局原子性。旧域响应可供原操作本地使用，不能覆盖新域缓存。
  - 例：A 请求未完成时配置切 B，B 操作等锁；A 返回后不发布，随后 B 请求并发布。若 B 已发布而旧 A 操作才取得锁，A 的本地请求结果同样不得覆盖 B。只在缓存访问时观察域名变化，不增加配置监听器；两次访问间未被观察的 A→B→A 不构成额外失效事件。
  - 取消是协作式的，Mutex 的可取消等待和 finally 释放锁不能替代操作中的取消检查。取得锁后先检查当前协程是否仍活动；拿到响应后在保证响应必定关闭的 use/finally 作用域内，解析前再检查；同步解析及字段校验完成后、缓存发布或返回本地结果前再次调用 ensureActive 或等价检查。发布前检查与后续域名核对、记录赋值之间不插入挂起点。
  - 发布前检查已观察到取消时，传播 CancellationException，不发布本次结果，并释放锁和响应；检查通过后的同步提交视为已进入提交边界，此后到达的取消不追溯撤销有效缓存。该规则不声称外部取消与赋值原子化，也不强行中断正在执行的同步解析。
  - HTTP/解析失败或取消均通过 withLock/finally 释放锁，不写失败缓存，不使用过期值冒充成功；等待者取消不影响持锁者。持锁者失败后等待者可执行自己的首次请求，当前操作内部不自动重试；这里合并的是成功初始化，并不建立共享失败任务。
  - 到期边界为年龄大于或等于 10 分钟。生产使用单调时间，时间只在成功校验后记录。时钟接入和状态结构可采用局部 helper，但不改变 NH 只有 ContentLoaderContext 的主构造契约，不增加公共配置项。

<!-- gantry:item id=gty-nh-queue-review type=edge status=accept mode=decision -->
- [x] **edge:** [accept] 保留单锁串行机制，成功初始化合并只保证当前域名稳定的场景；删除“新域最多等一次请求”的错误承诺，补测多个旧操作排队后新域操作的行为。
  - comment: 2026-10-06 交接审查通过 A1/A2/A3/B1 排队推演发现原承诺不成立；用户对审查结论回复“确认”，授权按建议修正。

<!-- gantry:item id=gty-nh-cancel-review type=edge status=accept mode=decision -->
- [x] **edge:** [accept] 补充解析前及缓存发布前的取消检查，取消检查位于响应关闭和锁释放的保护范围内；以发布前检查确定提交边界，已提交缓存不因后续取消回滚。
  - comment: 2026-10-06 核对 parseJson 为同步解析，发现仅靠 withLock/finally 不能阻止取消后发布；用户对审查结论回复“确认”，授权补齐检查及测试。

<!-- gantry:step id=gty-nh-page-identity author=ai status=accept -->
3.4. 将图片加载 URL 与页面 UID 输入分开计算，统一复用 OkHttp HttpUrl 的解析结果。
  - 相对路径与选定基址解析成 HTTPS URL；绝对 HTTPS URL保留原主机。路径大小写、有效编码和扩展名由 HttpUrl 规范化后保留，不额外解码、转小写或替换后缀。仅有绝对地址时无需为拼接请求 CDN，已有配置异常不能被吞掉。
  - UID 输入固定为 `resolvedUrl.encodedPath.removePrefix("/")`，存在 encodedQuery 时追加 `?` 和原 encodedQuery；排除 scheme、host、port、fragment。查询参数的内容和顺序保留，以免把可能不同的资源合并；相同媒体路径与相同查询在不同 CDN 上必须同 UID。
  - 例：`galleries/42/1.webp`、`/galleries/42/1.webp`、`https://i2.nhentai.net/galleries/42/1.webp` 的 UID 输入均为 `galleries/42/1.webp`；`?v=2` 与 `?v=3` 不同；`cover.webp.webp` 的重复后缀原样保留。使用现有 generateUid(String)，不创建第二套散列算法。
  - 页面先完整校验再组装返回：number 为正整数、唯一且为 1..num_pages，num_pages 为正整数，所有 path 均非空并能解析为 HTTPS URL。先验证全体，再按 number 排序，不因发现后段坏页而返回前半本；错误包含作品编号、字段和页号。

<!-- gantry:step id=gty-nh-validation author=ai status=accept -->
4. 以隔离离线回归定义行为，再验证线上接口和宿主可见结果。
  - NH 单元测试使用局部 ContentLoaderContext 与返回合成响应的 OkHttp 拦截器，任何未预期请求立即失败；复用 JM 离线上下文的接线方式，不读取本机 Cookie、Token 或真实网络，不新增测试依赖。
  - 先让旧实现触发封面 src 回退失败和旧 API 请求失败，再实现已确认方案。覆盖真实 v2 字段结构、JPG/WebP 路径、页序、身份、错误策略、CDN 缓存和筛选分页。
  - 线上测试由 `NHENTAI_INTEGRATION_TEST=1` 显式启用；实际抓取详情和页面列表，检查封面、首图、末图为成功图片响应。遇到 Cloudflare 挑战记为线上验证受阻，不能作为“图片验证通过”。
  - 自动检查完成后按既有插件打包流程交付；按已确认的项目 AGENTS 规则自动推送任务分支并更新既有 repo 发布入口，产物仅进入发布分支。用户在 Kotoyomi 更新插件后验收列表、详情、阅读、翻页及原收藏入口，明确验收后再合并主线。

## 验证矩阵

| 检查 | 必须证明的结果 |
| --- | --- |
| 列表图片属性 | 新 src-only、旧 data-src、空 data-src、data-cfsrc、协议相对/相对 URL 均按顺序解析；缺图不输出空字符串 |
| 请求路由 | 仅调用 v2 作品接口；详情和 getPages 均覆盖；默认/关键词/语言/标签及所有声明排序生成正确 URL，第 2 页不丢失 |
| v2 映射 | JPG/WebP、混合扩展名和重复后缀原样保留；封面不再使用 thumbnail 类型猜测；作者、标题、标签及语言兼容 |
| 页面契约 | 乱序输入按 number 排序；缺失 path、重复/非正/断档页号、页数不符、空页集、响应 id 不匹配均报上下文错误，失败不是半本成功 |
| CDN 缓存 | 当前域名稳定、初始化成功且未到期时，并发首次请求合计只发一次 CDN 请求；未到期不重取，到期边界重取；成功时间从校验完成计算，失败不发布、不返回过期值 |
| 域名与并发 | 用可控闸门暂停 A 请求，切 B 后放行，断言 A 的请求/页面 Referer 仍为 A、B 只消费 B 配置；再验证 A1 持锁、A2/A3 在 B1 前排队时旧域请求可分别发生，B 最终成功且缓存属于 B；另测 B 已发布后旧 A 请求到达不能覆盖 B；使用会执行 NH 拦截器的离线链检查请求头，不只检查构造参数 |
| 取消 | 等锁者取消不取消持锁者；响应已取得、同步解析完成但发布前取消时，观察到 CancellationException，响应关闭、锁释放、未发布本次结果，下一操作可以初始化；缓存成功发布后才取消，不删除该缓存。用可控闸门或等价测试接线区分两个时序，不使用长 sleep，也不把测试用挂起点放进生产发布临界段 |
| URL 与 UID | 相对、根相对及不同 CDN 的绝对地址按第 3.4 项示例得到相同 UID；不同 path/query 不合并，路径编码和重复后缀保留；加载 URL 仍可访问 |
| 错误 | 403、404、429、5xx、IOException、取消按 A 直接传播且不追加网页请求；ParseException 带字段上下文；不抓取验证码或建立自动破解机制 |
| 离线执行 | `./gradlew test --tests "org.skepsun.kototoro.parsers.site.all.NhentaiParserTest" --no-daemon`，随后 `./gradlew compileKotlin --no-daemon`；依实际回归风险运行关联测试 |
| 线上执行 | 显式启用 NH IntegrationTest，使用新旧两种图片格式的有效作品，验证真实封面与首尾图片类型；不固定易变化标题或章节数量 |
| 用户验收 | 列表封面、详情作者/章节、开始阅读、首末页及中间翻页；语言/标签排序后翻到第 2 页；原收藏入口仍指向同一作品 |

## 文件与维护范围

- 修改 `NhentaiParser.kt`、`NhentaiParserTest.kt`、`NhentaiParserIntegrationTest.kt`。
- 更新或新增 `src/test/resources/fixtures/nhentai/` 下最小脱敏 HTML/JSON，保留旧列表夹具用于兼容测试；不保存完整网页、图片文件、凭据或个人数据。
- 持续更新 PRD.md 和本 Gantry 主文档/差异记录；不修改宿主、共享模型、来源注册和其他来源。
- 上游已有公共 `src`/URL/JSON/异常工具足够；仅在 NH 类内提取窄职责方法，不新增通用框架或依赖。

## 实现建议与计划验证（方案稳定时记录）

关键机制按第 3.2–3.4 项落实，私有函数名称、helper 拆分、夹具组织和与依赖版本对应的库调用可调整。时钟建议通过内部 helper 的函数参数接入，生产传单调时间，单元测试传可推进的假时钟；通过局部 helper 注入不改 NH 主构造参数，也不将测试时钟写入来源配置。

### 实施顺序与完成判断

| 顺序 | 修改入口与衔接 | 完成判断 |
| --- | --- | --- |
| 1 | 扩展 NH 离线上下文与最小列表/v2/CDN 夹具；参考 JmComicTest 的 JmOfflineContext 接线，但独立于其私有类 | 未预期请求立即失败；src-only 与 v2 请求回归在旧实现下因目标行为失败，而非测试装配失败 |
| 2 | 修改 getListPage/parseGalleryList，补入请求上下文；同步 intercept 保留显式 Referer | 新旧图片属性和各筛选排序的第 2 页测试通过；无共享模型变更 |
| 3 | 实现类内 CDN 状态、Mutex 和单调时钟接入，接入第 2 步的上下文 | 用假时钟和闸门验证命中/到期/域名切换/取消；不实际等待 10 分钟，不使用真实网络 |
| 4 | 替换 fetchGallery/getDetails/getPages，接入路径/UID 规则，移除旧协议及回退 | 两个调用方都只请求 v2；详情、页序、错误策略 A、作品/章节身份及页面 UID 回归通过 |
| 5 | 扩展显式线上测试，编译并按既有流程打包，然后审查完整差异 | 离线与编译成功；线上首尾图片成功或明确报告受阻；宿主加载和用户验收独立报告 |

步骤 2–4 可以在同一次定向测试中验证，不要求每改一个文件就编译。代码级回归通过后只扩大到实际受影响的检查，不以重复构建制造新证据。

### 延后验证与调整边界

| 尚未执行的验证 | 时机与成功证据 | 失败后可自主调整 | 必须回到方案审阅的条件 |
| --- | --- | --- | --- |
| 当前 Kotlin/OkHttp/协程版本能否使用建议的调用和内部 helper | 步骤 1–3 最小代码可验证时，定向测试完成编译并实际执行 | 等价库调用、锁的 try/finally 写法、局部类型和测试接线 | 需要改变构造契约、公共接口、锁覆盖范围或取消语义 |
| 假时钟、同步闸门能否稳定控制缓存场景 | 步骤 3，以请求次数、捕获的 URL/headers、缓存后续行为断言，无长 sleep | 使用现有 kotlinx-coroutines-test、Deferred 或等价闸门；不增加依赖 | 无法满足单调到期或域名隔离约束，需改变关键机制 |
| 线上接口和图片在执行时仍可访问 | 自动回归后显式开启 IntegrationTest，成功状态与图片类型证据 | 选取同类仍有效作品；网络失败如实报告 | 必须新增回退、鉴权、重试或修改已确认 URL 契约 |
| 插件包与已安装宿主兼容、界面效果恢复 | JVM/D8 打包后，按授权交付并由用户验收 | 已有工具链内的打包接线修正 | 需要宿主/共享模型改动，或改变既有收藏和章节身份 |

方案稳定时，旧封面/API/网页回退缺陷已获用户确认；公开 v2、CDN 响应、JPG/WebP 首尾图片和新版筛选 URL 已完成只读可行性检查。当时代码级回归、编译、宿主插件加载和用户验收尚未运行；接续实施结果见执行记录。不能把此前网络请求当作代码修复已完成的证据。

本次按最新 design-handoff 检查，补上原“稳定”结论未覆盖的请求头覆写、缓存状态提交与 UID 规范化机制。基于当前源码核实 domain 动态读取、拦截器覆写、generateUid 整串散列和 ContentPage.headers 支持；宿主 CommonHeadersInterceptor、ReaderPage 和 PageLoader 的相关实现与已读索引基线一致，能保留并传递显式页面头。

### 交接审查与修正后复核

2026-10-06 首次实质交接审查未通过：单锁排队机制不能保证新域只等一次请求；同步 JSON 解析期间收到取消时，仅释放锁不能阻止缓存发布。用户确认两项修正后，规则与验证矩阵已同步，受影响路径逐项复核如下。这是方案与当前源码的语义复核，不是尚未实现代码的测试结果。

| 检查路径 | 复核结论与对应验证 |
| --- | --- |
| 稳定域名下多个成功初始化请求 | 首个持锁者发布，等待者重新检查后命中；一次请求的承诺限定在缓存有效及域名稳定期间 |
| A1/A2/A3 排队，随后切 B 并发起 B1 | 旧域请求按快照分别完成且不写当前缓存；B 可以等待多个旧请求，最终只使用 B 配置；文档不再给出无法保证的等待上限 |
| B 缓存存在，旧 A 操作晚到 | 在当前域仍为 B 时，旧 A 不能命中或覆盖 B；本地结果可返回 A 操作，身份和请求头仍使用 A 快照 |
| 解析前或发布前观察到取消 | 检查点抛出取消，use/finally 关闭响应、withLock/finally 释放锁；同域后续调用仍可正常初始化 |
| 最后取消检查通过后发生取消 | 同步提交允许完成，不撤销有效缓存；不承诺与外部取消或设置写入具备原子性 |
| 超时、HTTP 或解析失败 | 释放资源，不发布失败或过期值；等待者执行自己的首次请求，不产生当前操作内的自动重试 |

修正后的缓存、取消、请求头和 UID 链路未发现新的交接阻塞；剩余库调用、测试接线和设备验证已有验证时机及调整边界。用户在修正后交付说明中明确指出第 3.2–3.4 项仍待整体审阅后回复“确认”，将三项整体标记 accept。再次核对批准正文、审查修正和验证矩阵一致，该轮没有行为变化或新增待决项，完成稳定性检查；当时等待修订版单独实现授权，现已在本接续对话取得。

## Code

源码快照：2026-10-06 @ 638fe1bcc4c9587d7f9a1e42ad55a97e294d8722  文件：src/main/kotlin/org/skepsun/kototoro/parsers/site/all/NhentaiParser.kt。下文是该提交的完整原文；后续修改不覆盖此历史快照。  ```kotlin @file:OptIn(org.skepsun.kototoro.parsers.InternalParsersApi::class)

package org.skepsun.kototoro.parsers.site.all

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Document
import org.skepsun.kototoro.parsers.InternalParsersApi
import org.skepsun.kototoro.parsers.ContentLoaderContext
import org.skepsun.kototoro.parsers.ContentSourceParser
import org.skepsun.kototoro.parsers.core.PagedContentParser
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentParserSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentTagGroup
import org.skepsun.kototoro.parsers.model.SortOrder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONException
import org.skepsun.kototoro.parsers.exception.ParseException
import org.skepsun.kototoro.parsers.util.src
import org.skepsun.kototoro.parsers.network.UserAgents
import org.skepsun.kototoro.parsers.util.generateUid
import org.skepsun.kototoro.parsers.util.parseHtml
import org.skepsun.kototoro.parsers.util.parseJson
import org.skepsun.kototoro.parsers.util.urlEncoded
import org.json.JSONObject
import org.skepsun.kototoro.parsers.model.ContentType
import java.util.EnumSet

/**
 * nhentai (nhentai.net)
 */
@ContentSourceParser("NHENTAI", "nhentai", type=ContentType.HENTAI_MANGA)
internal class NhentaiParser(context: ContentLoaderContext) :
    PagedContentParser(context, ContentParserSource.NHENTAI, pageSize = 25), Interceptor {

    override val configKeyDomain = org.skepsun.kototoro.parsers.config.ConfigKey.Domain("nhentai.net")
    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.NEWEST,
        SortOrder.POPULARITY_TODAY,
        SortOrder.POPULARITY_WEEK,
        SortOrder.POPULARITY_MONTH,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities: ContentListFilterCapabilities
        get() = ContentListFilterCapabilities(isSearchSupported = true)

	private val languageTags = listOf(
		ContentTag("中文", "language:chinese", source),
		ContentTag("English", "language:english", source),
		ContentTag("日本語", "language:japanese", source),
	)

	private val nhTagMap: Map<String, String> by lazy {
		val raw = """
2937=big breasts
35762=sole female
35763=sole male
8010=group
14283=anal
19440=lolicon
24201=stockings
10314=schoolgirl uniform
13720=nakadashi
29859=blowjob
8378=glasses
20905=full color
32341=shotacon
27553=rape
15658=bondage
23895=yaoi
27473=mosaic censorship
13989=ahegao
22942=incest
21712=males only
1207=milf
19018=dark skin
22945=double penetration
25614=paizuri
20035=x-ray
779=futanari
23237=tankoubon
21572=multi-work series
20525=defloration
14971=sex toys
8653=netorare
3735=swimsuit
19954=yuri
15348=ffm threesome
8368=full censorship
15408=femdom
29224=impregnation
29013=dilf
85295=twintails
31044=collar
85288=ponytail
24380=pantyhose
9260=cheating
28031=sister
16828=hairy
31880=bbm
30555=big penis
15782=crossdressing
31775=tentacles
27384=mind break
19175=bikini
8739=story arc
30473=muscle
24102=lactation
7752=schoolboy uniform
20617=mind control
9083=big ass
29023=tomgirl
81774=kemonomimi
1590=sweating
9162=masturbation
7256=mmf threesome
28550=teacher
190=maid
8693=uncensored
19899=exhibitionism
6343=pregnant
8050=females only
6817=unusual pupils
25871=lingerie
10988=anthology
20282=footjob
15853=mother
15785=harem
14072=huge breasts
30035=gender bender
1643=kissing
130025=anal intercourse
1033=handjob
12824=condom
31386=catgirl
10476=urination
3666=garter belt
26130=fingering
81707=beauty mark
22079=drugs
105833=gloves
4435=gag
25601=small breasts
5820=piercing
12695=prostitution
16228=demon girl
7155=cunnilingus
22950=tanlines
832=elf
31012=blindfold
17773=kimono
2820=scat
29182=blackmail
23132=bunny girl
32484=stomach deformation
2515=virginity
27063=filming
7142=bbw
21989=inflation
88846=horns
104227=tail
26953=bukkake
28800=bloomers
25050=gyaru
24676=rimjob
23632=big areolae
16533=sleeping
73750=bald
18567=monster
35972=sole dickgirl
18328=thigh high boots
5810=strap-on
29565=school swimsuit
32996=deepthroat
370=business suit
7550=monster girl
1067=inseki
50585=webtoon
12523=bestiality
27697=leotard
30645=dick growth
29631=inverted nipples
29366=tomboy
24412=bodysuit
15492=scanmark
9406=enema
35970=dickgirl on dickgirl
29399=daughter
18613=military
11941=replaced
6525=nurse
9661=cervix penetration
33129=slave
4573=corruption
5529=urethra insertion
10542=snuff
683=squirting
51399=crotch tattoo
122908=very long hair
7838=magical girl
24726=apron
23183=breast expansion
20074=latex
28426=hairy armpits
27217=guro
31285=fox girl
106119=no penetration
24764=drunk
9990=prostate massage
35968=dickgirl on male
2956=old man
32752=shibari
6900=miko
2153=wings
706=birth
10794=breast feeding
14069=ryona
25822=smell
5357=humiliation
5962=spanking
2531=transformation
21538=bike shorts
31101=incomplete
32745=chikan
16236=shemale
36957=bisexual
26952=tall girl
25663=oppai loli
7995=big nipples
32602=fisting
106733=hair buns
1088=bdsm
21283=masked face
15225=blowjob face
2633=leg lock
27378=artbook
35971=male on dickgirl
27112=tiara
107705=facial hair
24933=eyepatch
4549=torture
30206=tribadism
1037=oni
89056=hidden sex
13136=facesitting
3391=nun
25766=gokkun
5200=pegging
17531=cosplaying
28521=voyeurism
19479=nipple fuck
17349=tracksuit
22221=blood
50505=oyakodon
50486=tail plug
560=twins
23965=chloroform
15425=vore
25457=possession
129668=eye-covering bang
24984=orgasm denial
144644=extraneous ads
28589=hotpants
17752=foot licking
32282=piss drinking
19390=cousin
32589=feminization
11376=body modification
20362=gyaru-oh
28778=large insertions
27720=smegma
10811=double vaginal
3614=triple penetration
3455=chastity belt
2452=scar
31319=yandere
7354=amputee
28335=giantess
26848=waitress
28349=cbt
24967=sumata
104893=vtuber
8516=emotionless sex
26380=demon
17591=robot
17801=solo action
13640=frottage
25996=gaping
23035=aunt
23967=huge penis
31846=body writing
25744=cheerleader
24708=cowgirl
25085=swinging
18322=brother
101724=leash
10354=milking
97795=pixie cut
11089=body swap
32224=eggs
10606=pasties
3947=onahole
14573=tall man
10604=dog
14362=low lolicon
15242=lab coat
4935=farting
13468=shimapan
5620=double anal
14138=freckles
50390=josou seme
15119=dog girl
93324=fishnets
22025=prolapse
15471=asphyxiation
21774=human pet
31337=kunoichi
15712=eyemask
30126=big clit
92409=thick eyebrows
109360=cumflation
7208=catboy
31687=randoseru
24529=bride
19561=big balls
24450=chinese dress
121738=focus anal
22967=diaper
29347=miniguy
29001=parasite
25296=armpit licking
6220=orc
7546=witch
30895=sunglasses
7372=corset
28119=nose hook
8429=machine
7684=armpit sex
14516=wolf girl
15045=niece
13882=tutor
8391=public use
30811=christmas
104245=small penis
266=sundress
17501=phimosis
17800=tickling
25794=widow
7288=vomit
1215=unusual teeth
72471=dickgirls only
107503=soushuuhen
138044=exposed clothing
1352=slime
31986=age regression
23917=long tongue
24115=angel
114993=shimaidon
13722=moral degeneration
26898=age progression
27120=selfcest
7577=vampire
17676=ghost
88103=clothed female nude male
13515=coach
141098=nipple stimulation
9116=unbirth
5936=time stop
18420=all the way through
72139=clothed paizuri
27530=ball sucking
16518=coprophagia
28869=stuck in wall
2527=bandages
24621=insect
11399=metal armor
106006=large tattoo
3843=fundoshi
20120=multiple paizuri
8400=goblin
129321=mesuiki
124610=mouth mask
10693=dougi
31371=mecha girl
21450=minigirl
10685=double blowjob
118056=petplay
20789=policewoman
3031=underwater
31173=first person perspective
78262=shaved head
19064=pubic stubble
14280=bunny boy
25949=gothic lolita
23463=wrestling
16947=horse
11247=skinsuit
11073=living clothes
30786=watermarked
23073=assjob
52826=dark sclera
107478=drill hair
23225=non-h
109930=domination loss
20170=poor grammar
138200=gender change
16759=artistcg
80978=nudity only
15749=oil
30176=petrification
25848=human cattle
559=ttf threesome
14010=snake girl
11276=multiple penises
90671=original
18024=touhou project
1841=kantai collection
35605=fate grand order
20925=the idolmaster
972=granblue fantasy
78245=azur lane
17137=neon genesis evangelion
3185=love live
391=girls und panzer
11219=pokemon
15021=sailor moon
4505=mahou shoujo lyrical nanoha
128408=blue archive
10222=fate stay night
27431=to love-ru
13159=naruto
123503=genshin impact
3984=sword art online
3603=street fighter
22174=one piece
16285=puella magi madoka magica
91195=princess connect
12232=my hero academia
3163=king of fighters
26172=k-on
7259=touken ranbu
19080=code geass
37544=love live sunshine
17077=cardcaptor sakura
27547=the melancholy of haruhi suzumiya
13508=final fantasy vii
10954=shingeki no kyojin
25430=vocaloid
32687=free
4577=toheart2
22146=dead or alive
20025=gochuumon wa usagi desu ka
8485=dragon ball z
5037=bleach
3218=bakemonogatari
12624=ore no imouto ga konna ni kawaii wake ga nai
37109=kono subarashii sekai ni syukufuku o
4369=monster hunter
127065=hololive
74788=girls frontline
24886=fate kaleid liner prisma illya
6999=toaru kagaku no railgun
22032=boku wa tomodachi ga sukunai
18350=ragnarok online
21674=dragon quest iii
14345=ojamajo doremi
7832=darkstalkers
24135=ah my goddess
32394=samurai spirits
1283=queens blade
16639=haikyuu
13924=yu-gi-oh
79467=kimetsu no yaiba
18238=danganronpa
26336=yu-gi-oh zexal
16984=persona 4
18569=kuroko no basuke
1910=smile precure
30587=sakura taisen
16166=mahou sensei negima
12285=ranma 12
8470=infinite stratos
32363=toaru majutsu no index
22708=saki
8708=to heart
108082=arknights
16707=detective conan
22210=guilty gear
947=gundam seed destiny
22677=tenchi muyo
23429=pretty cure
18512=strike witches
31027=lucky star
7408=league of legends
394=love hina
23201=kanon
27704=amagami
127052=nijisanji
70802=kemono friends
52098=persona 5
22215=super robot wars
27567=hayate no gotoku
35251=osomatsu-san
7633=pripara
34823=ensemble stars
37914=re zero kara hajimeru isekai seikatsu
74918=bang dream
15041=martian successor nadesico
24783=dragon ball
120519=love live nijigasaki high school idol club
2803=love plus
5085=senki zesshou symphogear
28474=zero no tsukaima
15197=gundam build fighters
15427=dragon quest iv
1163=rozen maiden
23859=yu-gi-oh arc-v
75023=dragon quest xi
2112=dungeon ni deai o motomeru no wa machigatteiru darou ka
36418=voiceroid
28281=mitsudomoe
11624=the legend of zelda
14694=fullmetal alchemist
16847=dragon quest v
2497=urusei yatsura
5671=tengen toppa gurren lagann
22754=amagi brilliant park
20606=tsukihime
5165=gundam build fighters try
4114=macross frontier
20763=inazuma eleven
14550=sister princess
19083=jojos bizarre adventure
21052=fate hollow ataraxia
29922=teitoku
51810=gudao
16643=producer
13848=reimu hakurei
25125=asuka langley soryu
17279=sakuya izayoi
10496=patchouli knowledge
37739=shielder
3206=shinji ikari
38068=gran
4675=sanae kochiya
21779=rei ayanami
14040=fate testarossa
3870=flandre scarlet
23902=remilia scarlet
21688=atago
11373=marisa kirisame
35128=kashima
17154=sakura kinomoto
31462=satori komeiji
30080=kaga
10802=alice margatroid
17017=aya shameimaru
17862=yukari yakumo
5340=shimakaze
18935=nanoha takamachi
18896=shirou emiya
16555=rin tosaka
16130=rito yuuki
15890=reisen udongein inaba
7724=takao
27060=jeanne darc
78989=jeanne alter
7718=naruto uzumaki
5337=nami
22975=chun-li
17502=illyasviel von einzbern
20111=tifa lockhart
21131=youmu konpaku
18026=kazuto kirigaya
92923=shikikan
29856=saber
71442=minamoto no raikou
1843=asuna yuuki
51419=gudako
7488=mai shiranui
9835=koishi komeiji
16916=kasumi
30026=maki nishikino
143975=sensei
26906=izuku midoriya
37275=scathach
7696=momiji inubashiri
38039=astolfo
27794=mikoto misaka
20062=hamakaze
78285=artoria pendragon
34860=katsuki bakugou
3328=homura akemi
37687=djeeta
32200=suzuya
21108=rin shibuya
35964=nico yazawa
27494=levi ackerman
609=eren jaeger
11920=sakura haruno
20427=sailor mercury
24714=chino kafuu
31456=mikan yuuki
866=koyomi araragi
12149=kyousuke kousaka
277=haruka nanase
19926=haruna
3763=haruhi suzumiya
26427=mio akiyama
25439=hinata hyuga
17811=ran yakumo
14857=kongou
18548=kotori minami
32364=rider
15641=madoka kaname
2613=hong meiling
491=makoto tachibana
20702=koakuma
15315=tomoyo daidouji
10730=shigure
14265=touma kamijou
80311=bb
4203=mami tomoe
37706=kazuma satou
33070=umi sonoda
27172=yuyuko saigyouji
3353=yuuka kazami
2078=nagato
6311=arisu tachibana
647=belldandy
9274=maya
24889=sena kashiwazaki
15125=golden darkness
6555=sailor jupiter
25695=mika jougasaki
50929=shuten douji
33077=sailor mars
8293=minami nitta
7451=lelouch vi britannia
389=rika jougasaki
22469=prinz eugen
16108=azusa nakano
12812=tenryuu
7311=ami mizuno
6642=byakuren hijiri
7097=suwako moriya
19172=miki hoshii
9657=ayane
29433=c.c.
25220=sakura matou
14499=tsunade
10665=tenshi hinanai
16564=miku hatsune
29190=kallen stadtfeld
3312=kirino kousaka
1234=yuki nagato
26261=ranma saotome
19002=rin kaenbyou
12748=nico robin
32765=rin matsuoka
4241=fumika sagisawa
1729=tamaki kousaka
23997=ruri gokou
29684=sailor venus
19160=nitori kawashiro
27302=uzuki shimamura
23216=android 18
8489=hibiki
7333=suguha kirigaya
1267=kodaka hasegawa
2345=morrigan aensland
9371=yamato
26087=inazuma
27532=archer
26587=miho nishizumi
12346=utsuho reiuji
37108=megumin
22407=takane shijou
15914=sasuke uchiha
2774=kyouko sakura
80930=abigail williams
81288=gudao | ritsuka fujimaru
73756=nightingale
6109=eri ayase
27492=akagi
17899=sakura kasugano
32137=cirno
11760=yui kotegawa
75029=eli ayase
11740=sailor moon
49158=narmaya
29693=ikazuchi
126586=aether
20918=iori minase
24832=misato katsuragi
2883=kasen ibara
6932=souji okita
28555=tamamo-no-mae
14428=kokoa hoto
26783=taihou
12763=rumia
401=nakoruru
72475=musashi miyamoto
23122=maho nishizumi
29188=eirin yagokoro
466=usagi tsukino
29638=kyon
15995=makoto kino
11744=amatsukaze
6175=cammy white
30331=ichika orimura
23473=mikuru asahina
28807=ruri hoshino
2572=hatate himekaidou
15291=chen
23386=fujiwara no mokou
9237=shoukaku
28763=tewi inaba
23851=gilgamesh
10672=aqua
9702=ro-500
31074=keine kamishirasawa
32443=charlotte dunois
2196=sayaka miki
1645=zuikaku
5925=akatsuki
4196=hestia
33171=shiho nishizumi
19534=hayate yagami
79507=belfast
12433=kaede takagaki
12872=warrior
8170=len kagamine
50415=rem
14409=momoka sakurai
2211=mari illustrious makinami
99075=kokkoro
1907=rei hino
15651=miyu edelfelt
26169=musashi
8053=lum
50596=you watanabe
9883=kagami hiiragi
24509=darjeeling
11992=lala satalin deviluke
32683=hachiman hikigaya
31076=kuroko shirai
20836=red saber
12902=isuzu sento
10379=bianca whitaker
16181=nozomi toujou
27774=bismarck
28219=yui hirasawa
1271=momo velia deviluke
49852=subaru natsuki
5918=shinobu oshino
28056=link
25605=rangiku matsumoto
35313=cagliostro
18453=hero
75102=nozomi tojo
20722=mutsu
29170=yuma tsukumo
9486=nue houjuu
33049=ritsuko akizuki
23626=murakumo
20323=tsumugi kotobuki
16566=ritsu tainaka
14016=yuu narukami
11609=yoko ritona
107011=chloe von einzbern
52132=riko sakurauchi
32114=onpu segawa
11924=kagerou imaizumi
		""".trimIndent()
		raw.lineSequence()
			.mapNotNull { line ->
				val parts = line.split('=')
				if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
					parts[0] to parts[1]
				} else null
			}.toMap()
	}

    override suspend fun getFilterOptions(): ContentListFilterOptions =
        ContentListFilterOptions(
            availableTags = (languageTags + nhTagMap.map { ContentTag(it.value, "tag:${it.key}", source) }).toSet(),
            tagGroups = listOf(
				ContentTagGroup("语言", languageTags.toSet()),
				ContentTagGroup("标签", nhTagMap.map { ContentTag(it.value, "tag:${it.key}", source) }.toSet()),
			),
            availableContentRating = EnumSet.of(ContentRating.ADULT),
        )

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", UserAgents.CHROME_DESKTOP)
        .build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return chain.proceed(request.newBuilder()
            .header("Referer", request.header("Referer") ?: "https://$domain/")
            .header("User-Agent", UserAgents.CHROME_DESKTOP)
            .build())
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: ContentListFilter): List<Content> {
        val operation = requestContext()
		val query = filter.query.orEmpty()
		val langTag = filter.tags.firstOrNull { it.key.startsWith("language:") }
		val tagFilter = filter.tags.firstOrNull { it.key.startsWith("tag:") }

		val sortParam = when (order) {
			SortOrder.POPULARITY_TODAY -> "popular-today"
			SortOrder.POPULARITY_WEEK -> "popular-week"
			SortOrder.POPULARITY_MONTH -> "popular-month"
			SortOrder.POPULARITY -> "popular"
			else -> null
		}
		val searchSortParam = if (sortParam != null) "&sort=$sortParam" else ""

		val url = when {
			query.isNotEmpty() -> "https://${operation.domain}/search/?q=${query.urlEncoded()}&page=$page$searchSortParam"
            langTag != null -> {
                val language = langTag.key.substringAfter("language:")
                "https://${operation.domain}/language/$language/?page=$page$searchSortParam"
            }
			tagFilter != null -> {
				val slug = tagFilter.title.lowercase().replace(' ', '-')
				"https://${operation.domain}/tag/$slug/?page=$page$searchSortParam"
			}
			sortParam != null -> "https://${operation.domain}/search/?q=%22%22&page=$page$searchSortParam"
			else -> "https://${operation.domain}/?page=$page"
		}
        val doc = webClient.httpGet(url, operation.headers).parseHtml()
        return parseGalleryList(doc, operation.domain)
    }

    internal fun parseGalleryList(doc: Document, requestDomain: String = domain): List<Content> {
        return doc.select(".gallery").mapNotNull { el ->
            val a = if (el.tagName() == "a") el else el.selectFirst("a")
            if (a == null) return@mapNotNull null
            val href = a.attr("href")
            val id = normalizeId(href.replace(Regex("\\D"), ""))
            val title = el.selectFirst(".caption")?.text()?.trim()
                ?: el.selectFirst("a > div")?.text()?.trim()
                ?: el.select("div").lastOrNull()?.text()?.trim()
                ?: ""
            val img = el.selectFirst("img") ?: el.selectFirst("a > img")
            val cover = img?.src(arrayOf("data-src", "src", "data-cfsrc"))
			val langAttribute = el.attr("data-tags")
			val lang = if (langAttribute.isNotEmpty()) {
				langAttribute.split(" ").firstOrNull {
					it == "12227" || it == "6346" || it == "29963"
				}?.let {
					when (it) {
						"12227" -> "English"
						"6346" -> "日本語"
						"29963" -> "中文"
						else -> ""
					}
				}.orEmpty()
			} else ""
            if (id.isEmpty() || title.isEmpty()) return@mapNotNull null
            val coverUrl = cover
                ?.let { if (it.startsWith("//")) "https:$it" else it }
                ?.replace("http://", "https://")
            Content(
                id = generateUid(id),
                title = title,
                altTitles = emptySet(),
                url = id,
                publicUrl = "https://$requestDomain/g/$id",
                rating = org.skepsun.kototoro.parsers.model.RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                coverUrl = coverUrl,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
                description = lang,
            )
        }
    }

    private fun normalizeId(raw: String): String {
        return raw.removePrefix("nhentai").removePrefix("nh")
    }

    private data class RequestContext(val domain: String, val headers: Headers)

    private val cdnCache = NhentaiCdnCache()

    private fun requestContext(): RequestContext {
        val requestDomain = domain
        return RequestContext(requestDomain, getRequestHeaders().newBuilder()
            .add("Referer", "https://$requestDomain/").build())
    }

    private fun invalid(operation: RequestContext, id: String, field: String, cause: Throwable? = null): Nothing {
        throw ParseException("NH $id: invalid $field", "https://${operation.domain}/api/v2/galleries/$id", cause)
    }

    private suspend fun fetchGallery(id: String, operation: RequestContext): JSONObject {
        val json = webClient.httpGet("https://${operation.domain}/api/v2/galleries/$id", operation.headers).use {
            currentCoroutineContext().ensureActive()
            try {
                it.parseJson()
            } catch (e: JSONException) {
                invalid(operation, id, "response JSON", e)
            }
        }
        currentCoroutineContext().ensureActive()
        if (json.opt("id")?.toString() != id) invalid(operation, id, "id")
        return json
    }

    private suspend fun cdn(operation: RequestContext): NhentaiCdnConfig =
        cdnCache.get(operation.domain, { domain }) {
            val url = "https://${operation.domain}/api/v2/cdn"
            webClient.httpGet(url, operation.headers).use { response ->
                currentCoroutineContext().ensureActive()
                val json = try {
                    response.parseJson()
                } catch (e: JSONException) {
                    throw ParseException("NH CDN: invalid JSON", url, e)
                }
                fun server(field: String): HttpUrl {
                    val array = json.optJSONArray(field)
                    val selected = (0 until (array?.length() ?: 0)).asSequence()
                        .mapNotNull { (array?.opt(it) as? String)?.toHttpUrlOrNull() }
                        .firstOrNull { it.isHttps }
                    return selected ?: throw ParseException("NH CDN: invalid $field", url)
                }
                NhentaiCdnConfig(server("image_servers"), server("thumb_servers"))
            }
        }

    private suspend fun imageUrl(
        path: String,
        operation: RequestContext,
        id: String,
        field: String,
        thumbnail: Boolean,
    ): HttpUrl {
        path.toHttpUrlOrNull()?.let {
            if (!it.isHttps) invalid(operation, id, field)
            return it
        }
        val config = cdn(operation)
        val base = if (thumbnail) config.thumbnails else config.images
        return base.resolve(path)?.takeIf { it.isHttps } ?: invalid(operation, id, field)
    }

    private fun requiredPath(json: JSONObject?, operation: RequestContext, id: String, field: String): String =
        (json?.opt("path") as? String)?.takeIf { it.isNotBlank() } ?: invalid(operation, id, field)

    override suspend fun getDetails(manga: Content): Content {
        val operation = requestContext()
        val galleryId = normalizeId(manga.url)
        val json = fetchGallery(galleryId, operation)

        val title = json.optJSONObject("title")?.optString("english")
            ?.ifEmpty { json.optJSONObject("title")?.optString("japanese") }
            ?.ifEmpty { manga.title }
            ?: manga.title

        val coverPath = requiredPath(json.optJSONObject("cover"), operation, galleryId, "cover.path")
        val coverUrl = imageUrl(coverPath, operation, galleryId, "cover.path", thumbnail = true).toString()

		var languageName: String? = null
		var translated = false
		val languageCandidates = mutableListOf<String>()
		val authors = mutableSetOf<String>()
        val tags = json.optJSONArray("tags")?.let { arr ->
            buildSet {
                for (i in 0 until arr.length()) {
                    val tag = arr.optJSONObject(i)
                    val name = tag?.optString("name")?.trim().orEmpty()
					val type = tag?.optString("type").orEmpty()
					val id = tag?.optInt("id") ?: 0
                    if (name.isNotEmpty()) {
						if (type == "language") {
							if (name.equals("translated", ignoreCase = true)) {
								translated = true
							} else {
								languageCandidates.add(name)
							}
						}
						if (name.equals("translated", ignoreCase = true)) translated = true
						if (type == "artist") authors.add(name)
						add(ContentTag(name, name, source))
						// Backup: derive language by id if not set
						if (type == "language") {
							when (id) {
								12227 -> languageCandidates.add("English")
								6346 -> languageCandidates.add("日本語")
								29963 -> languageCandidates.add("中文")
								else -> {}
							}
						}
					}
                }
            }
        } ?: emptySet()

		languageName = languageCandidates.firstOrNull {
			it.equals("中文", ignoreCase = true) || it.equals("chinese", ignoreCase = true)
		} ?: languageCandidates.firstOrNull {
			it.equals("日本語", ignoreCase = true) || it.contains("japanese", ignoreCase = true)
		} ?: languageCandidates.firstOrNull {
			it.equals("English", ignoreCase = true) || it.contains("english", ignoreCase = true)
		} ?: languageCandidates.firstOrNull()

		val langTag = languageName ?: run {
			when {
				tags.any { it.title.equals("中文", ignoreCase = true) || it.title.contains("chinese", ignoreCase = true) } -> "中文"
				tags.any { it.title.contains("日本", ignoreCase = true) || it.title.contains("japanese", ignoreCase = true) } -> "日本語"
				tags.any { it.title.equals("English", ignoreCase = true) || it.title.contains("english", ignoreCase = true) } -> "English"
				else -> null
			}
		}

        return manga.copy(
            title = title,
            tags = if (tags.isNotEmpty()) tags else manga.tags,
            chapters = listOf(
                ContentChapter(
                    id = generateUid("${manga.id}-0"),
                    title = "Chapter 1",
                    number = 1f,
                    volume = 0,
                    url = manga.url,
                    scanlator = null,
                    uploadDate = 0,
                    branch = null,
                    source = source,
                )
            ),
            contentRating = ContentRating.ADULT,
            description = langTag?.let { if (translated) "$it / translated" else it } ?: manga.description,
            coverUrl = coverUrl,
            authors = if (authors.isNotEmpty()) authors else manga.authors,
        )
    }

    override suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
        val operation = requestContext()
        val galleryId = normalizeId(chapter.url)
        val json = fetchGallery(galleryId, operation)
        val count = json.opt("num_pages")?.toString()?.toIntOrNull()?.takeIf { it > 0 }
            ?: invalid(operation, galleryId, "num_pages")
        val array = json.optJSONArray("pages") ?: invalid(operation, galleryId, "pages")
        if (array.length() == 0) invalid(operation, galleryId, "pages")
        if (array.length() != count) invalid(operation, galleryId, "num_pages")
        val paths = sortedMapOf<Int, String>()
        for (index in 0 until array.length()) {
            val page = array.optJSONObject(index)
            val number = page?.opt("number")?.toString()?.toIntOrNull()
                ?: invalid(operation, galleryId, "pages[$index].number")
            if (number !in 1..count || paths.containsKey(number)) {
                invalid(operation, galleryId, "pages[$index].number=$number")
            }
            paths[number] = requiredPath(page, operation, galleryId, "pages[number=$number].path")
        }
        return paths.map { (number, path) ->
            val url = imageUrl(path, operation, galleryId, "pages[number=$number].path", thumbnail = false)
            val identity = url.encodedPath.removePrefix("/") + (url.encodedQuery?.let { "?$it" } ?: "")
            ContentPage(
                id = generateUid(identity),
                url = url.toString(),
                preview = url.toString(),
                headers = mapOf("Referer" to "https://${operation.domain}/"),
                source = source,
            )
        }
    }

    override suspend fun getPageUrl(page: ContentPage): String = page.url
}

internal data class NhentaiCdnConfig(val images: HttpUrl, val thumbnails: HttpUrl)

/** NH 局部串行缓存；旧域操作可以完成，但不能发布到当前域缓存。 */
internal class NhentaiCdnCache(private val nanoTime: () -> Long = System::nanoTime) {
    private data class Entry(val domain: String, val value: NhentaiCdnConfig, val validatedAt: Long)
    private val mutex = Mutex()
    private var entry: Entry? = null

    suspend fun get(
        requestDomain: String,
        currentDomain: () -> String,
        load: suspend () -> NhentaiCdnConfig,
    ): NhentaiCdnConfig = mutex.withLock {
        currentCoroutineContext().ensureActive()
        val current = currentDomain()
        if (entry?.domain != current) entry = null
        val cached = entry
        if (requestDomain == current && cached != null && nanoTime() - cached.validatedAt < 600_000_000_000L) {
            return@withLock cached.value
        }
        val loaded = load()
        val validatedAt = nanoTime()
        currentCoroutineContext().ensureActive()
        // 最后取消检查与同步提交之间不挂起；提交后的取消不回滚有效缓存。
        if (currentDomain() == requestDomain) entry = Entry(requestDomain, loaded, validatedAt)
        loaded
    }
} ```

## 执行记录（2026-10-06）

- RED：在旧源码下，src-only 封面实际为为空字符串；详情与阅读请求 /api/gallery/42；语言排序请求旧路径。这些失败对应目标缺陷，旧列表兼容用例通过。首次默认 Gradle 4 GiB 堆导致测试进程无法启动，不计行为证据；后以进程级 1 GiB 堆和 max-workers=2 获得有效 RED，不修改仓库构建配置。
- 实现：NH 类内请求上下文贯穿列表/API/CDN；拦截器保留显式 Referer，正文页面保存快照 Referer；v2 cover.path/pages[].path 不改后缀。两个调用方共用身份校验，页面完整校验、排序及路径/query UID 规则。NhentaiCdnCache 仅在同文件内封装串行状态和可控单调时钟，不改主构造及共享契约。
- 离线：NhentaiParserTest 13 个、NhentaiCdnCacheTest 5 个、关联 JmComicTest 37 个全部通过。场景包含新旧图片属性、全部声明排序与第 2 页、详情/章节身份、损坏数据、编码/query UID、真实错误不回退、域名快照、缓存命中/到期/排队/晚到、取消与响应关闭。上下文没有真实网络、Cookie 或 Token 文件读取。
- 线上：显式 NHENTAI_INTEGRATION_TEST=1，匿名上下文仅复用代理环境。385440 和 686046 两个参数用例通过，实际详情及封面、首图、末图成功且类型为 image/*；未固定标题或页数断言。首页 https://nhentai.net/?page=1 返回 Forbidden: Just a moment，状态 403，列表用例失败/受阻，不记作图片检查通过。总测试结果 58 个，57 通过、1 失败；其中离线 55/55、线上 2/3。
- 编译：最终轮次 compileKotlin 与 jar 均执行成功，相关日志先于受阻的 test 任务；Gradle 总退出码为 1，原因是首页线上用例失败，不是编译失败。
- 验证命令：Windows Wrapper 执行 test，--tests 分别选择 org.skepsun.kototoro.parsers.site.all.NhentaiParserTest、NhentaiCdnCacheTest、NhentaiParserIntegrationTest 和 org.skepsun.kototoro.parsers.site.zh.JmComicTest，并执行 compileKotlin jar --no-daemon -Dorg.gradle.jvmargs="-Xmx1g -XX:MaxMetaspaceSize=512m" --max-workers=2。完整类名的前三项包前缀相同；仅最后线上轮次显式设置 NHENTAI_INTEGRATION_TEST=1。
- 审查：以源码基线为比较对象核对完整任务差异，覆盖两个 v2 调用方、列表路由、资源释放、锁内取消/缓存发布、域名切换及 UID。无新增实质设计决定或未处理可行动缺陷；宿主、共享模型、来源注册及其他来源保持基线内容。不声称覆盖动态宿主 UI 的全部调用。
- 待完成：列表线上验证、宿主插件加载、手机实际显示/翻页/原收藏入口及用户验收；尚未合并、推送或发布。
- 打包：SDK 34 的 D8 8.2.2 虽退出 0，但出现 Kotlin 2.2 元数据兼容警告，弃用该轮产物。改用已安装的 cmdline-tools/latest/lib/r8.jar（D8 9.3.16）与 JDK 21，-Xmx512m、--release、--min-api 21、--lib android-34/android.jar；以当前 Kotlin stdlib、coroutines、OkHttp、Okio、collection、JSON 和 JSoup 的七个依赖 JAR 仅作为 classpath，最终退出 0、build/nh-d8.log 为空。产物 build/libs/kototoro-parsers-plugin.jar 含 classes.dex，并核对其中存在 NH 缓存类和 /api/v2/galleries/ 字符串；没有新增工具或依赖，没有改变宿主打包契约。
- 产物绑定：NhentaiParser.kt Git blob 为 07b75f8c4a1bb2fb70ee79a42ef6431dcdfb9140。JVM JAR SHA-256 为 138327cff76e3b41cf7131cad40d7c4cfd60729f53554965a71c6cde6a3325d7；DEX 插件 JAR SHA-256 为 003d550d25883a5a8db4c8d7eee87c9901e9791098bfc763782456ca051fd6f9。构建/缓存/日志/产物保留在 D 盘既有目录，不提交 Git；插件加载仍待用户环境验证。

## 验收发布记录（2026-10-06）

- 授权：用户明确要求从发布阶段接续，复用根因、方案及实施确认；项目 AGENTS 的新发布规则及本次消息覆盖历史记录中的不推送/发布限制。未授权强推或用户验收前合并主线。
- 身份核对：接续 HEAD 为 07f423651e2f5e10325dc69894af68d7c3b1dc42，工作区干净；638fe1bc 之后仅文档变化。当前 NH Git blob、JVM JAR 和 DEX 插件 SHA-256 均与上述绑定相同。现存 XML 报告为 NH 离线 18/18、JM 37/37、线上 2/3，D8 日志为空；复用编译、打包及完整差异审查证据，不重建、不重跑测试，不称全部测试通过。
- 发布：fix/nhentai-api-v2 已推送至 origin。由 origin/repo 的 9e0bfa996eed9e8a459446198694c7095cb09df5 建立发布分支 build/nh-acceptance-release，仅修改 index.min.json 的 version/code 为 1.0.136/136，并替换 apk/plugin.jar；其余字段和 .nojekyll 保留。提交 c73bc18b36204b236db95c2944249936bbf76bed 已普通快进推送至 origin/repo，未使用 force_orphan 或强推。
- 交付入口保持 https://raw.githubusercontent.com/BarrayAllen0818/kotoyomi-plugins/repo/ 。实际请求 index.min.json 返回 HTTP 200、1.0.136/code 136；根据索引下载 apk/plugin.jar 返回 HTTP 200、846915 字节，SHA-256 为 003d550d25883a5a8db4c8d7eee87c9901e9791098bfc763782456ca051fd6f9，与本地验收产物一致。下载核对副本位于 build/nh-published-plugin.jar，不进入源码提交。
- Git 与检查：git fetch/ls-remote 核实远端；索引 JSON 解析、文件差异与暂存 diff --check、产物哈希、发布历史包含关系均通过。master 仍为 f9965c747a848c34b7cab1da39c69e17979254ca，保留任务分支、发布分支和 D 盘 build/nh-release-repo 工作树。
- 待用户验收：通过已有仓库更新到 1.0.136，核对 NH 列表封面、详情和章节、JPG/WebP 首末页及连续翻页、普通关键词搜索、语言/标签排序第 2 页和原收藏入口；可顺带核对 JM 编号搜索。匿名首页 403 的自动验证限制保留，插件加载及所有设备/UI 结果不得由发布成功替代。用户反馈通过项、未测试项或具体失败步骤后继续；明确验收后再合并主线并自动推送。
