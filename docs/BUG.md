# 缺陷与验证记录

## JM 组合搜索

### BUG-003 查询末页重复返回导致组合搜索失败

- 状态：待验收。2026-11-17 用户确认返修，JM 插件代码和离线检查已完成，预期及宿主简繁标签、颜色、NH 名称兼容范围见[宿主 BUG-017、BUG-018](../../kotoyomi/docs/BUG.md#组合搜索)。
- 原因：JM 查询总数 35，第一页已返回全部 35 部；下一页仍返回同一批。`getCombinedCandidates` 只用非空判定还有后续，触发共享分页的重复页保护。
- 已确认处理：在 JM 组合查询中读取有效总数；总数未封顶且本页覆盖总数时结束，封顶总数仍允许查询后续页。保留共享重复页保护及其他网站的分页行为。
- 电脑验证：新增 1、35、80 部三种末页用例在旧实现上均因重复页保护失败，修复后 JM、响应解析、共享分页与 NH 共 106 项离线检查通过，无失败、错误或跳过。已有封顶总数及中间筛选空页用例仍通过，未修改共享分页。JVM JAR 与 D8 9.3.16 打包通过，打包日志为空；插件 SHA-256 `e62e85b6a1ec060efb996bfc993b78d676467a441c6be019b156373e47361586`。日志为 `build/jm-terminal-red.log`、`build/search-repair-green.log`、`build/search-repair-d8.log`。源码 `3aca9edd` 已推送 `origin/master`；插件 1.0.150 通过原仓库地址发布，发布提交 `8ff58ab50b58bb36746dfdfef3d06a162f803a8e`，索引及下载哈希已核验，`http_verified=true`。发布记录见 `build/search-repair-publish.log`；候选工作树 `build/plugin-releases/candidate-i_jmnmu3` 保留。正式宿主线上验证及设备体验状态另行更新。

- 配套宿主线上验证：`app/build/search-repair-final.log` 的正式插件 JAR 用例，以简体原始条件“大乳晕、大屁股”取得 20+15 部且末页后为空，只请求一次 JM 搜索；NH 带显示性别后缀条件取得 25+25 部。相关名称转换由宿主负责，插件只包含上述 JM 分页修复。宿主新包安装期间手机连接消失，设备与用户验收未完成，详见宿主 BUG-017、BUG-018。

## Hanime1 作品资料

### BUG-002 简介中双语标注的品牌漏取

- 状态：已通过。2026-11-15 用户在插件 1.0.147 的品牌查看指引后回复“通过”，确认本次品牌提取修复使用验收通过。离线及线上检查、JVM 构建与 DEX 打包证据继续适用。
- 预期依据：[作品搜索信息范围](PRD.md#作品搜索信息范围)要求提取网站明确提供的制作方／品牌。
- 实际偏差：作品 409178 简介明确标注 `Brand / ブランド: LAXIUS`，作品 409177 标注 `Brand / ブランド: Skello-on-sale`；正式插件入口取得的补充资料均缺少 `producers`。
- 根因与处理：现有匹配只识别行首的 `Brand:`，不接受双语标签。仅扩展明确品牌标签的识别，保留原英文标签、现有作者和其他资料提取行为，不从普通简介文字猜测品牌。
- 修复与验证：在正式插件工厂中新增八个品牌用例，旧实现的三个双语品牌用例因缺少 `producers` 失败；修复后所在测试类 22 项全部通过。涵盖双语标签、英文标签、半角／全角冒号、换行与 HTML 换行、无明确品牌及空值，不增加详情请求。
- 线上检查：新增 `Hanime1IntegrationTest`，使用默认 HTTP 协议协商，通过 `HANIME1_INTEGRATION_TEST=1` 显式启用。四部真实作品均通过 HTTP/2 返回 200，正式工厂验证作者、类型、时长、中文字幕标记、关联作品及品牌；409178、409177 分别取得 `LAXIUS`、`Skello-on-sale`。测试使用电脑现有代理，不加载账号或 Cookie 文件；没有修改宿主或其他来源的测试网络设置。日志见 `build/hanime1-brand-red.log`、`build/hanime1-brand-green.log`。
- 最终构建：`gradlew.bat test --tests org.skepsun.kototoro.parsers.site.all.ExHentaiSearchTest --tests org.skepsun.kototoro.parsers.site.zh.Hanime1IntegrationTest jar pluginDexClasspath --init-script .github/scripts/plugin-classpath.gradle --no-daemon --console=plain` 成功；显式开启上述线上测试并沿用电脑现有代理，22 项离线、1 项线上检查全部通过，无失败、错误或跳过。D8 9.3.16 使用 Android 34 API、最低 Android 21 打包成功，日志为空，产物仅含 `classes.dex`；SHA-256 为 `ed4436e347272f14530b07514b0807fad84b73a27b58b58c0fa6e8c23f43df80`。日志见 `build/hanime1-brand-final.log`、`build/hanime1-brand-d8.log`。
- 发布与验收入口：源码 `15e35b6d` 已推送至 `origin/codex/hanime1-brand`；1.0.147 发布提交为 `35f8928ebe85de01705f3104994c4d27593006c5`，原仓库 URL 的索引与插件下载哈希核对通过，`http_verified=true`。发布及验证证据见 `build/hanime1-brand-publish.log`、`build/hanime1-brand-verification.json`。验收指引为在 Kotoyomi 更新现有插件仓库至 1.0.147 后，在 Hanime1 来源分别搜索 `Malfoid`、`Double Trouble`，打开对应作品并刷新详情，核对品牌 `LAXIUS`、`Skello-on-sale`；用户随后确认通过，未补造逐项操作记录。本次没有重建或安装宿主 APK，验收后同步源码主线时继续使用同一插件产物，不重复发布。

## 作品补充元数据

### BUG-001 统一入口漏传作品补充元数据

- 状态：已通过。用户更新插件 1.0.143 后，收藏作品详情刷新完成仍不显示总页数、原作、人物。
- 预期依据：[PRD 的作品补充元数据](PRD.md#作品补充元数据)要求从网站取得资料并交给宿主保存、展示及备份。
- 根因与证据：2026-11-14 已核对手机宿主包含新增资料读取和展示代码，安装插件与 1.0.143 发布文件哈希一致。JM 303212 强制刷新后，插件内存记录含 total_pages=34，宿主作品对象及数据库 source_data 为空。实际仓库持有 ContentParserWrapper，它未暴露 getDetailsMetadata；正式插件工厂先将网站解析器包在此入口中，宿主因此找不到读取方法。直接调用 JM 解析器及宿主接收函数能取得资料，原测试遗漏统一入口。
- 已确认处理：仅修改插件统一入口，转交网站解析器已取得的可选补充资料；不增加请求，不改变宿主及原版接口。2026-11-14 用户确认修复方案。回归检查必须从正式插件工厂取得解析器，验证三个字段、缺失资料及现有返回内容。
- 修复与验证：统一入口已暴露可选 getDetailsMetadata，直接读取网站解析器随作品暂存的资料，未修改网站请求及作品对象的既有构造、复制接口。新增 JM 正式工厂回归、NH 原用例切换正式工厂后，在修复前均因统一入口缺少该方法失败；修复后 JM、NH、ExHentai、WNACG 正式入口及 Hanime 回归共 109 项通过，无失败、跳过。覆盖三个字段、缺失资料、未支持来源返回空及不增加请求。记录见 build/metadata-wrapper-red.log、build/metadata-wrapper-green.log。
- 兼容与产物：采用宿主现有 parser-api 模型并按正式加载顺序加载插件，从插件工厂取得 ContentParserWrapper 后，宿主接收函数实际取得总页数 42、两个原作和一个人物。记录见 build/metadata-wrapper-host-integration.log。JVM JAR 与 D8 构建通过，DEX 已核对包含统一入口读取方法，D8 无警告；产物与 SHA-256 见 build/work-metadata-wrapper/verification.json。代码审查及差异检查通过。
- 发布：源码修复提交 5cd634e0 已推送至 origin/master，插件 1.0.144 已通过现有仓库发布，origin/repo 发布提交为 a47416c568dffceebdd4a20ba0a36b5b8b7398fb；现有 URL 的索引及下载 SHA-256 核验通过。插件 SHA-256 为 c0279f5ba0c02d7ab02925965481f1607e7e133a48d4d0d6759b707dea8590ed，记录见 build/work-metadata-wrapper/publish.log。
- 设备验收：2026-11-14 用户确认更新并刷新后 JM 303212 显示总页数 34。通过 ADB 5037 只读核对 V2458A 上的插件哈希与 1.0.144 一致，数据库内原收藏记录的 source_data 已保存 total_pages=34、parodies=[]、characters=[]，details_loaded=1。本条缺陷的取得、转交、保存及显示流程已通过验收；原作、人物的非空值由正式入口离线用例覆盖，本次设备样本未提供非空值，不扩大为相关画面或完整备份恢复已验收。
- 取证边界：此前广泛方法观察造成调试干扰及无响应，未计为产品缺陷或验证通过证据；正式设备验收使用插件更新、用户刷新及只读数据库核对，未使用该观察方式。
