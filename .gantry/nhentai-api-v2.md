# NH 网站改版兼容修复

**Target:** 实现 PRD.md 的 REQ-002，恢复 NH 列表封面、作品详情和正文图片，并保持搜索、筛选与分页排序可用。

<!-- gantry:workflow pseudocode=approved annotations=complete stabilization=complete implementation=authorized -->

## 状态与阅读约定

用户已于 2026-10-06 确认根因、需求、策略 A、整体方案及修正后的第 3.2–3.4 项。全部步骤 accept，交接和稳定性检查完成。本接续对话在展示当前稳定版的实施、验证、打包及本地阶段提交范围后，用户回复“确认”，implementation 已 authorized；不含合并、推送、发布或设备验收。分支为 `fix/nhentai-api-v2`，源码基线为 `f9965c747a848c34b7cab1da39c69e17979254ca`。当前已实现，自动与线上结果见执行记录，用户验收待完成。

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
  - 自动检查完成后按既有插件打包流程交付；发布/推送按后续授权执行。用户在 Kotoyomi 更新插件后验收列表、详情、阅读、翻页及原收藏入口。

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

2026-10-06：已按稳定方案实现；代码提交后在本节补记绑定该提交的源码快照。

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
