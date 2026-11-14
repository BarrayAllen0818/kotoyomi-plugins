---
id: enabled-sources
title: "用户已启用的内容来源"
category: project
status: active
tags: [sources, user-preferences]
created: "2026-11-09T21:09:48"
updated: "2026-11-14T08:27:02"
---


<!-- compiled_truth -->
## 网站资料范围与证据边界

本页记录网站本身公开提供的作品信息，包括产品当前不提取的日期、统计、发布与访问状态。产品需要提取哪些字段以及怎样显示，见 [EDD/PRD.md](../../EDD/PRD.md#作品搜索信息范围)。事实表中的“有”表示字段或区域已观察到，不表示每部作品都非空，也不表示当前插件或网站搜索支持此字段。

核查覆盖 JM、NHentai、WNACG、Hitomi、EH、Hanime1、Rule34Video、Iwara。HA 当前对应 Hanime1（hanime1.me）；此前 Hanime.tv 的接口结论不适用于它。Hanime1 分里番与其他类型记录；其他类型已检查的作品样本为2D动画，未逐类核查3DCG、MMD等全部类目。

表中“—”及“已查无独立字段”只描述已取得的响应与页面。“样本为空”表示字段存在但本次没有值。“未核实”表示证据不足，不能等同于网站没有。账号语言、文件处理状态、网站分类等字段各有不同含义。

## 漫画作品资料

| 信息 | JM | NHentai | WNACG | Hitomi | EH |
|---|---|---|---|---|---|
| 标题 | 有 | 有 | 有 | 有 | 有 |
| 其他语言标题／别名 | — | 有 | — | 有 | 有 |
| 封面 | 有 | 有 | 有 | 有 | 有 |
| 作者 | 有 | 有 | — | 有 | 有 |
| 社团 | — | 有 | — | 有 | 有 |
| 原作 | 有 | 有 | — | 有 | 有 |
| 人物 | 有 | 有 | — | 有 | 有 |
| 标签 | 有 | 有 | 有 | 有 | 有 |
| 类型 | 有，来自列表分类 | 有 | 有 | 有 | 有 |
| 语言 | — | 有 | — | 有 | 有 |
| 总页数 | 有 | 有 | 普通图集有，合集无总数 | 可按完整图片列表计数 | 有 |
| 实际章节目录及章节数 | 有 | — | 合集有 | — | — |
| 剧情简介 | 有 | — | 有，可为空 | — | — |

### 漫画信息的含义

- WNACG 普通标签可包含作者、原作和人物名称，但未分类为三个独立字段。
- NHentai、Hitomi 的单篇阅读入口，以及 EH 的缩略图分页入口，与作品实际章节目录不同；JM 多章节和 WNACG 合集具有实际章节信息。
- JM 详情响应包含作者、原作、人物和总页数，分类及子分类来自列表响应。Hitomi 提供完整图片列表，列表数量可用于计算总页数。

## 视频作品资料

| 信息 | Hanime1：里番 | Hanime1：其他类型 | Rule34Video | Iwara |
|---|---|---|---|---|
| 标题 | 有 | 有 | 有 | 有 |
| 其他语言标题／别名 | 无独立别名字段 | 无独立别名字段；部分简介有明确 Title 行，不视为多语言别名目录 | — | 已查详情无独立字段 |
| 封面 | 有 | 有 | 有 | 有 |
| 作者／创作者 | 有制作公司署名，未提供独立个人作者 | 有创作者或社团署名，个人与组织未统一区分 | 有，页面标为 Artist | 提供上传账号 user.name、user.username；独立原创署名未见 |
| 制作方／品牌 | 有制作公司署名 | 部分简介明确列 Brand，与创作署名可能是同一对象 | — | 已查详情无独立字段 |
| 原作 | 样本无独立字段 | 无独立类别；部分主题名称通过关键词链接展示 | 无独立字段，部分名称混在分类中 | 已查详情无独立字段，名称可混在普通标签中 |
| 人物 | 样本无独立字段 | 无独立类别；部分人物名称通过关键词链接展示 | 有，页面标为 Characters | 已查详情无独立字段，名称可混在普通标签中 |
| 标签 | 有 | 有 | 有 | 有，标签含类型信息 |
| 类型 | 有，网站标为“裏番” | 有，例如2D动画、3DCG、MMD等网站类目；已核查样本为2D动画 | 有“3D”等类型，但与所属作品、制作工具等分类混放 | 有 category 标签；样本为 Koikatsu、Virt-A-Mate 等工具或内容类别 |
| 字幕语言 | 作品标签明确提供“中文字幕”时可读，已有命中样本 | 共用网站“中文字幕”标签选项，两个2D样本未命中 | — | 已查详情无独立字段 |
| 配音语言 | 网站有“中文配音”标签选项，两个里番样本未命中 | 共用网站“中文配音”标签选项，两个2D样本未命中 | — | 已查详情无独立字段 |
| 时长 | 有，作品页提供以秒为单位的总时长 | 有，作品页提供以秒为单位的总时长 | 有，作品页提供总时长 | 有，file.duration；两个样本分别为244秒、186秒 |
| 系列及同系列作品 | 有以当前作品名组织的作品清单，保留网站清单含义 | 样本展示同社团作品，不能据此认定为当前作品的系列 | — | 已查详情无独立字段 |
| 简介 | 有 | 有，部分仅有作品说明、购买链接等 | 有，部分仅填写链接 | 有，body |

### 视频信息的含义

- Hanime1 页面创作署名可为个人、社团或公司，元信息中固定的 author=Hanime1 是网站名；上传账号另有用户链接。个人作者、制作方与社团并未由同一个署名字段完整区分。
- Hanime1 里番样本有以作品名组织的清单，其他类型样本显示同社团作品。这两类关联含义不同。搜索页的“中文配音”选项说明该标签存在，不能证明本次四部样本有中文配音。
- Hanime1 的主题和人物关键词链接没有独立的字段类别。Rule34Video 的 Artist 区域使用 /models/ 链接，Characters 区域另列人物；Categories 混合3D类型、所属作品与制作工具。
- Iwara 的 user 是发布作品的账号，不是接口单独确认的原创署名。如何在应用中命名该账号是 PRD 中的产品约定；账号 locale 表示用户语言，不是字幕或配音语言。

## 各网站的完整已知信息类型

以下包括本次产品范围之外的资料。记录字段类型和含义，不保存个人凭据、临时播放签名或会话值。

| 网站 | 基本、创作与关联资料 | 时间及统计 | 媒体、发布与访问资料 | 尚未核实或样本为空 |
|---|---|---|---|---|
| JM | id、name、description、author数组、tags、works原作、actors人物、series章节、series_id、related_list相关作品；列表category及category_sub | 详情addtime、total_views、likes、comment_total；列表update_at、adddate | total_photos总页数、images字段、real_link；liked及is_favorite为当前访问者状态；price及purchased为价格和购买状态 | 303212及另两部详情没有独立别名、社团、语言字段；images样本为空；is_aids字段存在但含义未核实 |
| NHentai | id、media_id、title中的english/japanese/pretty、artist作者、group社团、parody原作、character人物、category类型、language语言、tag普通标签、scanlator | upload_date上传时间、num_favorites收藏数；每个标签的count是标签关联作品数量 | 封面及缩略图路径和尺寸、num_pages、每页number/path/width/height及缩略图尺寸；标签还含id、slug、url、description | scanlator在样本中为空；不存在已核实的独立剧情简介、连载状态或网站评分字段 |
| WNACG | 标题、分类及上级分类、普通标签、简介、网站编号、合集目录 | 收藏数；详情未取得独立发行、上传、更新时间字段 | 普通图集明确页数；合集明确章节数及章节链接 | 作者、原作、人物、语言没有独立类别；别名未见独立字段；合集总页数未取得 |
| Hitomi | id、title、japanese_title、artists、groups、parodys、characters、tags、type、language、language_localname、language_url、languages其他语言版本、related | date；datepublished存在但样本为空，不能只凭名称确认其日期含义 | galleryurl、files完整图片列表及name/width/height/hash/hasavif；另有video、videofilename、scene_indexes、blocked字段 | 部分作者、社团、原作、人物、日文标题为空；video及videofilename样本为空；scene_indexes和blocked的具体含义未核实；未见剧情简介字段 |
| EH | gid及作品定位字段、title、title_jpn、category、thumb、uploader；带分类标签含artist/group/parody/character/language；Parent及其他版本链接 | posted上传时间、rating网站评分、Favorited收藏数 | filecount总页数、filesize文件大小、Visible可见信息、expunged是否被删除；torrentcount及torrents中的名称、添加时间、种子大小、内容大小和标识 | 没有统一连载或完结字段；expunged不表示连载状态；本次部分样本未含原作或人物标签 |
| Hanime1：里番 | 标题、封面、制作公司署名、类型、标签、简介、作品清单及关联视频 | 页面上传日期、观看次数；点赞比例及标签投票数可见 | og:video:duration总秒数；中文字幕等属性标签、画质标签；页面提供下载及相关作品入口 | 未见独立多语言别名、个人作者、原作、人物和配音语言字段；配音标签选项存在但样本未命中；完整可用画质与媒体格式未逐项核查 |
| Hanime1：其他类型 | 标题、封面、创作者或社团署名、类型、标签、简介、同社团作品；部分简介明确Title、Brand、Release、File size及创作者支持链接 | 页面上传日期、观看次数、点赞比例及标签投票数；部分简介有销售日期文本 | og:video:duration总秒数；分辨率、无码等属性标签；下载入口 | 已查为2D动画样本，不代表全部其他类型；原作和人物关键词未独立分类；语言标签在样本中未命中；简介中的文件大小不是统一媒体字段 |
| Rule34Video | 标题、封面、description简介、Artist创作者、Characters人物、Categories分类、Tags标签；另有Uploaded by上传账号区域 | VideoObject中的uploadDate、interactionStatistic；详情可见观看次数及相对上传时间；评论区有数量 | VideoObject中的duration、contentUrl；播放器及下载链接提供不同画质候选；上传者与Artist分别展示 | 已查无独立别名、制作公司、语言、发行日期、系列字段；分类混合类型、作品和工具；点赞、收藏、下载等完整统计字段尚未逐项核实 |
| Iwara | id、slug、title、body简介、user发布账号、tags及类型；thumbnail和customThumbnail封面信息 | createdAt、updatedAt、numLikes、numViews、numComments；文件另有自己的创建和更新时间 | file中的id/type/path/name/mime/size/width/height/duration/numThumbnails/animatedPreview；status处理状态、rating内容分级、private、unlisted、embedUrl、fileUrl；liked是当前访问者状态 | 已查无独立别名、原创署名、制作公司、原作、人物、字幕、配音及系列字段；fileUrl有时效；status枚举含义未穷举，不能认定为作品完结状态 |

### 不能混用的字段

- Iwara 的 rating 是 general/ecchi 等内容分级，不是数值评分；private和unlisted描述访问与展示状态。账号中的premium、role、locale等是用户资料，不属于作品语言或制作资料。
- Iwara 标签样本出现general、category，目录样本还出现source。已见category为koikatsu、virt_a_mate；已见source示例为3d_custom_girl，不能将source统一解释为作品原作。标签请求虽指定limit=5000，服务器只返回50项；没有穷举全部标签类型。
- JM addtime、NH upload_date、EH posted等是网站添加或上传时间，不能自动当成作品发行时间。媒体文件自己的createdAt/updatedAt不等同于作品日期。
- Hanime1 元信息、标题、关键词中的文字及网站通用宣传描述，与作品正文的剧情简介、署名和明确属性标签分别记录。标签投票数不是作品收藏数。
- 网站具有某项详情资料，并不直接证明网站搜索能够按此字段准确筛选；完整搜索能力仍需针对查询接口核查。

## 核查来源与范围

- 2026-11-14：核查 [JM 303212 作品接口](https://www.cdnhjk.net/album?id=303212)及另外两部作品、[JM 列表分类](https://www.cdnhjk.net/categories/filter?o=mr&c=0&page=1)、[NHentai 作品接口](https://nhentai.net/api/v2/galleries/696362)及[人物目录](https://nhentai.net/characters/)、[WNACG 普通图集](https://www.wnacg.com/photos-index-aid-404467.html)及[合集](https://www.wnacg.com/photos-index-aid-404468.html)、[Hitomi 作品资料](https://ltn.gold-usergeneratedcontent.net/galleries/4327262.js)及另外两部作品、[EH 作品页](https://e-hentai.org/g/4327184/4244b23b98/)。以上为网站资料核查，不能作为插件新增功能已交付的证据。
- Hanime1：实际核查 [搜索页](https://hanime1.me/search)、[作品 409178](https://hanime1.me/watch?v=409178)、[作品 409177](https://hanime1.me/watch?v=409177)、[作品 408317](https://hanime1.me/watch?v=408317)、[作品 408319](https://hanime1.me/watch?v=408319)。作品 409178 总时长为 471 秒；作品 408317 标签明确包含“中文字幕”。“中文配音”在搜索页作为可用标签提供，不能将搜索选项当成每部作品的实际值。
- Rule34Video：已取得[作品 4727752](https://rule34video.com/video/4727752/scarlet-nipple-penetration/)和[作品 4661654](https://rule34video.com/video/4661654/bedtime-boogey-descent-ii-mini-animation-jackerman/)完整详情，核实 Artist、Characters、Categories、Tags 与总时长。
- Iwara：成功取得[公开列表](https://api.iwara.tv/videos?page=0&limit=2&sort=date)、[作品详情一](https://api.iwara.tv/video/cQT4xzL47zrwa7)、[作品详情二](https://api.iwara.tv/video/g8fQGmeutOJoia)和[标签接口](https://api.iwara.tv/tags?page=0&limit=100)。两部作品file.duration为244和186秒，文件尺寸分别为1280×720、3840×2160；此前403与浏览器验证后，本次接口成功，网页仍出现验证。用户可见资料与接口字段核查不包含绕过账号权限。
- EH 还取得[公开元数据接口](https://api.e-hentai.org/api.php)的gdata响应，核实文件大小、页数、评分、上传者、种子信息、带分类标签和expunged字段。该接口以作品编号及定位值查询。

## 产品要求的位置

[要提取的字段与用途](../../EDD/PRD.md#作品搜索信息范围)；[详情页分行展示](../../EDD/PRD.md#详情页作品信息展示)。


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

- time: 2026-11-14T06:59:58
  kind: decision
  summary: "清理与 PRD 重复的当前正文，仅保留对应章节链接"
  source: "2026-11-14 用户确认 PRD 与 Brain 去重预览"
  affects: [enabled-sources]

- time: 2026-11-14T08:25:11
  kind: decision
  summary: "按用户确认将网站事实表迁入本页，补充时间、统计、媒体及访问资料，加入 Iwara"
  source: "2026-11-14 用户确认：Brain记录网站所有作品信息类型，PRD仅记录提取与展示需求"
  affects: [enabled-sources]

- time: 2026-11-14T08:27:02
  kind: decision
  summary: "核对并修正 Iwara 新增列的表格分隔，补明空字段符号含义"
  source: "2026-11-14 同次迁移后的表格校验"
  affects: [enabled-sources]
