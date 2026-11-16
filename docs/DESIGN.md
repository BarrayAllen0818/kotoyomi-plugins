# 作品资料、搜索与详情展示方案

状态：插件字段调整已通过验收。2026-11-17 用户确认插件 1.0.148 的品牌、独立系列字段删除及明确英语字幕识别通过，允许按已展示预演合入源码主线并推送。宿主配套已实现、构建并保留数据安装到手机；本次插件验收不扩大为宿主界面、逐名称操作及整机备份恢复已验收。此前 1.0.147 品牌返修的历史证据见 [BUG-002](BUG.md#bug-002-简介中双语标注的品牌漏取)。

## 依据与范围

产品字段及交互以 [PRD 的作品搜索信息范围](PRD.md#作品搜索信息范围)和[详情页作品信息展示](PRD.md#详情页作品信息展示)为准。网站字段依据见[网站资料记录](../brain/pages/enabled-sources.md)。本文件只记录实现方案，不另行维护产品字段清单。

插件仓库负责站点资料提取；相邻 `../kotoyomi` 宿主仓库负责持久保存、搜索、标准备份及 Android 详情展示。Iwara 使用已有来源实现，只适配资料获取。保留播放、阅读、作品身份及现有入口。

## 资料传递与保存

复用 `WebsiteWorkMetadata`、正式工厂的 `ContentParserWrapper.getDetailsMetadata` 和宿主 `withDetailsMetadataFrom`；不改变插件 `Content` 构造及复制方法的二进制接口。标题、作者等基础资料继续使用现有字段，补充资料保存在现有 `source_data.kotoyomi_metadata` 中。

插件输出沿用现有 JSON 字段约定：`total_pages`、`parodies`、`characters`、`groups`、`types`、`languages`、`subtitle_languages`、`audio_languages`、`duration_seconds`、`chapter_count`、`related_works`。`producers`、`series` 已从插件辅助模型移除；未修改 `Content` 的构造、复制接口或正式工厂的可选资料读取方法。关联作品包含 `source`、`url`、`title`；不保存临时媒体地址。宿主的漫画／视频内容种类与网站作品类型分别处理。

联网更新依据原始 JSON 的字段存在性与数据库旧资料逐字段合并。省略字段表示不更新，明确空值表示清除，错型值不覆盖旧值；保留其他 `source_data` 内容。列表只更新列表返回的资料，不覆盖已取得的完整详情，也不将列表结果标记为详情已加载。完整目录仍使用现有章节存储；章节数只由网站明确的完整目录取得。

本轮插件不再输出品牌和独立系列，但不会向宿主发送清空旧资料的补丁。后续宿主实现已停用这些旧字段的读取、搜索及备份输出，并在更新或恢复时清理；“字幕”“配音”仍通过原来的 `subtitle_languages`、`audio_languages` 字段传递，以兼容当前宿主。

## 查询与显示

宿主将已保存资料统一转换成现有组合搜索使用的 `CombinedSearchFacts`，候选和搜索结果共用同一字段解释。姓名及分类按独立字段匹配，简介和章节名称按文字包含匹配，总页数、实际章节数和时长使用数值范围。缺失值沿用现有未知判断，排除条件不会把未知当作已确认不含该值。收藏和历史搜索不联网补资料。

网站搜索继续服从每个来源已经声明的能力；新增本地字段不自动成为网站搜索能力。详情中的具体名称通过现有搜索选择弹窗传递单个名称和字段类别，不支持的入口说明原因，不退化为关键词后声称准确执行了字段条件。

Android 详情目标以 [PRD 展示要求](PRD.md#详情页作品信息展示)为准；宿主配套提交为 `48cae747b`、`c720b9c8d`，已本地保存，尚未推送。197 项自动检查、APK 构建及签名核验通过；2026-11-15 用户再次要求安装后已在 V2458A 保留数据覆盖安装成功，设备 APK 哈希与本次构建一致；实际画面与使用仍待验收。详细进展与产物证据统一见 [宿主 PRD](../../kotoyomi/docs/PRD.md#2026-11-15-作品信息调整)。

## 标准备份与重新获取

沿用原版作品定位信息、收藏、阅读历史及书签备份；在现有备份作品记录中保存已经取得的补充资料和简介。备份不额外访问网站，不新增完整章节目录、正文图片、视频文件或临时播放地址的备份机制。

恢复后先使用备份资料；旧备份缺少新增字段时按缺失值恢复，新增字段由修改版读取，原版可忽略。用户打开详情或主动刷新时按既有加载方式取得网站资料；已经缓存的旧详情需刷新后才能补齐新增字段，不自动全库联网。备份恢复服从原有快照／合并规则，与联网字段增量更新分开。

## 验证与交付

先验证可选通道及正式插件工厂，再验证宿主保存、搜索、标准备份回环及详情点击范围。覆盖旧插件、旧备份、缺失字段、明确清空、错型字段、列表与详情更新、刷新失败及重启后的资料使用。

逐站点离线验证实际字段，回归相应阅读或视频解析；线上检查只报告实际取得的响应。宿主定向测试后构建 Debug APK，插件构建并经现有仓库发布。构建、安装、线上检查与用户实际体验分别记录，不以其中一项代替其他项。

## 当前验证记录

### 2026-11-15 插件字段调整

- 2026-11-17 用户回复“通过”，确认本节插件字段调整验收通过。生产源码和构建配置自已验证提交 `4db26822` 后未变，保留的 1.0.148 发布产物 SHA-256 仍为 `db1038bbc67139ac0d9bc9721f24db4d130d0a320640ed3eed2f6bc5690a16f6`；复用既有验证，不重建或重复发布。

- 本轮仅修改插件仓库。生产代码覆盖辅助资料模型、Hanime1 和 Rule34Video；所有静态调用已编译，正式插件工厂继续通过 `getDetailsMetadata` 返回可选 JSON，不修改宿主所用的 `Content` 构造及复制接口。
- 修改前定向运行 40 项离线检查，7 项因旧品牌仍输出或英语字幕未输出而失败；修改后六个测试类共 156 项通过，0 失败、0 错误、0 跳过。覆盖正式入口、品牌标注不再单独提取但简介保留、作者和关联作品保留、明确英语字幕标签、歧义或异站标签不误判、无额外请求，以及 JM、NH、EH、Hitomi、WNACG、Iwara 相关回归。
- 实际执行命令：`gradlew.bat test --tests org.skepsun.kototoro.parsers.site.all.ExHentaiSearchTest --tests org.skepsun.kototoro.parsers.site.en.Rule34VideoTest --tests org.skepsun.kototoro.parsers.site.all.NhentaiParserTest --tests org.skepsun.kototoro.parsers.site.all.IwaraTest --tests org.skepsun.kototoro.parsers.site.zh.JmComicTest --tests org.skepsun.kototoro.parsers.site.zh.JmComicResponseTest --tests org.koitharu.kotatsu.parsers.site.zh.WnacgReadTest --tests org.koitharu.kotatsu.parsers.site.galleryadults.zh.WnacgReadFlowTest jar pluginDexClasspath --init-script .github/scripts/plugin-classpath.gradle --no-daemon --console=plain`。后两个旧包名未匹配实际测试类，没有运行 WNACG 真实请求用例；WNACG 离线资料断言由 `ExHentaiSearchTest` 覆盖。
- `HANIME1_INTEGRATION_TEST=1` 下单独执行 `Hanime1IntegrationTest`，首轮因 `connect timed out` 失败，未到达字段断言。当时本机没有此前代理端口；随后检测到 `127.0.0.1:7892` 恢复监听，沿用该代理仅为测试进程设置 `HTTPS_PROXY`，未变更系统网络设置。补跑 1 项线上检查成功，四部真实作品均 HTTP/2 200，正式工厂确认作者、字幕及关联作品保持有效，品牌和独立系列均不输出；首轮失败仍保留为事实。Rule34Video 英语字幕标签依据本对话此前实际取得的网站标签目录及分类页，本轮由离线正式入口验证，不扩大为新增字段完整线上取值已通过。
- JVM JAR、运行时 classpath 收集及 D8 9.3.16 打包成功；使用 Android 34 API 和 `--min-api 21`，D8 无警告。插件仅含 `classes.dex`，SHA-256 为 `db1038bbc67139ac0d9bc9721f24db4d130d0a320640ed3eed2f6bc5690a16f6`。发布脚本的 9 项检查通过，代码审查未发现未处理缺陷；宿主界面及设备使用验收尚未进行。
- 源码 `4db26822ac764d93d93eee44e402f6f18f53238a` 已推送 `origin/codex/plugin-metadata-fields`，首次连接重置后核对远端分支尚未建立，再推送成功；未合入源码主线。发布提交 `9f0ebede9a4a231591fc3598a77a9b80b34f6855` 保留 `repo` 历史，版本由远端索引 147 递增为 1.0.148。初次 HTTP 索引仍为 147，之后直接复核既有 URL 的 `index.min.json` 返回 148、1.0.148，`apk/plugin.jar` 下载 1035605 字节，SHA-256 与上述产物一致；没有重复构建或发布。发布候选工作树保留于 `build/plugin-releases/candidate-ksua_j88`。
- 最后仅调整测试函数排版后重跑 `ExHentaiSearchTest`、`Rule34VideoTest`，40 项通过；生产源码未变，前述 156 项与 D8 产物证据继续适用。验收时通过 Kotoyomi 原仓库 URL 更新至 1.0.148，再刷新 Hanime1、Rule34Video 作品详情；现有宿主已存品牌值、字段显示名称、视频版本与角标仍待宿主后续实现，不能以这些未变行为判断插件输出失败。

### 此前验证记录

- 插件源码 `35b54ab3`：适用于当前输入的离线证据共 144 项，分轮取得；正式工厂覆盖资料提取与无额外请求。JVM JAR 与 D8 9.3.16 打包通过，D8 日志为空。插件仅包含 `classes.dex`，SHA-256 为 `fd5dda40f9814bb1f59bcfe666f3cf83e377daab69f715096860f894d8f56721`。
- 1.0.146 发布提交 `b59d2fd4173b7eca1fd4c9ad416feceb0ab4e631`，既有仓库 URL 的索引与实际下载哈希核对通过；证据见忽略目录中的 `build/metadata-publish.log`。
- Rule34Video 线上检查通过。Iwara 取得列表后，因所选列表项缺少封面地址而停止，未完成该轮详情及媒体检查；没有放宽断言或记为通过。Hanime1 此前电脑测试强制使用 HTTP/1.1 时返回 403；2026-11-15 保留相同代理及请求头、恢复默认协议协商后，四部详情均通过 HTTP/2 返回 200，已经由正式插件工厂验证资料提取。独立系列名称没有可靠字段而保持为空。
- 2026-11-15，用户分别确认 Iwara、Rule34Video 使用验收通过。记录用户整体验收结论，不补造逐项操作、所用版本或自动检查通过记录。
- 宿主源码本地提交 `602b0645a`，135 项当前适用定向检查分轮通过；最后新增的真实历史保存回归先复现旧资料回写，再验证修正后 9 项通过。普通偏好及进度保存不更新已有资料，明确列表回调和详情更新负责补充资料。核心、备份、解析入口及应用证据分别见宿主 `app/build/meta-host-*.log`。
- 宿主 `:app:assembleDebug` 与最终定向检查同轮通过，APK 位于 `../kotoyomi/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`，包名 `org.skepsun.kotoyomi.debug`、版本 2.4.1（1258）、arm64-v8a，签名核对通过，SHA-256 为 `29c179267b72c8d1c65e580c8c756508e2db2ece9aa5ae9d959d57fa398136b2`。
- 目标手机 V2458A（`10AF7U234D00540`）此前覆盖安装被拒绝后已停止；用户再次授权安装后，2026-11-14 18:40:24 保留数据安装成功，设备版本及 APK SHA-256 与上述产物一致，安装阻塞已解除。依据为宿主 `app/build/metadata-install.log` 和 `app/build/metadata-verification.json`。新详情实际画面、逐名称操作及标准备份完整恢复仍待设备验收；Iwara 用户使用验收已通过，此前自动检查中断保留为历史记录。
