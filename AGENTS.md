# Repository Guidelines

本文件是本仓库的代理协作约定。通用贡献要求以 `CONTRIBUTING.md` 为准；开始修改前，应先阅读与任务相关的现有解析器、模型和工具函数，避免复制已有能力。

## 项目结构

- `src/main/kotlin/org/skepsun/kototoro/parsers/core/`：解析器基类与核心契约。
- `src/main/kotlin/org/skepsun/kototoro/parsers/model/`：内容、章节、页面、筛选及来源模型。
- `src/main/kotlin/org/skepsun/kototoro/parsers/site/<language>/`：按语言组织的站点解析器；中文源位于 `site/zh/`。
- `src/main/kotlin/org/skepsun/kototoro/parsers/util/`：网络、JSoup、JSON 等共享工具，优先复用这里的扩展。
- `src/test/kotlin/`：JUnit 5 测试，目录结构应与主代码对应。
- `src/test/resources/fixtures/`：可重复运行的离线 HTML/JSON 测试夹具。
- `kototoro-parsers-ksp/`：解析器注解处理与来源清单生成逻辑。
- `.github/summary.yaml`：KSP 生成的来源摘要；增删解析器后检查其变化。

## 构建与测试

使用仓库自带的 Gradle Wrapper。CI 使用 Temurin JDK 21 运行 Gradle，库的 JVM toolchain 目标为 8。

```bash
./gradlew compileKotlin --no-daemon
./gradlew test --no-daemon
./gradlew test --tests "org.skepsun.kototoro.parsers.site.zh.ParserNameTest" --no-daemon
./gradlew generateTestsReport --no-daemon
```

- 小范围改动先运行对应测试类，再按风险扩大到完整 `test` 或 `check`。
- 新站点至少覆盖列表、详情/章节、阅读页解析，以及站点特有的解密或 URL 处理。
- 默认测试必须离线、确定且可重复；将最小化且脱敏的响应保存到 `src/test/resources/fixtures/`。
- 真实网络测试命名为 `*IntegrationTest.kt`，使用 `@EnabledIfEnvironmentVariable` 显式启用。环境变量采用 `<SOURCE>_INTEGRATION_TEST=1`，不得让普通 `test` 隐式访问线上站点。
- 在线验证应检查首尾图片可访问及响应类型，但不要把短期章节数量、易变化标题等不必要细节写成脆弱断言。

## 解析器实现约定

- 解析器必须使用 `@MangaSourceParser` 注册唯一、稳定的内部名称，并保持一个以 `MangaLoaderContext` 为唯一主构造参数的类。
- 不要硬编码可变域名。默认域名放在 `configKeyDomain`，请求使用解析器的 `domain`。
- 内容和章节 ID 必须与域名无关；优先用相对 URL 或站点内部 ID 调用 `generateUid`。
- 根据站点分页方式选择 `PagedMangaParser`、`SinglePageMangaParser` 或合适的现有基类。
- `availableSortOrders` 不得为空。只暴露站点真实支持的搜索、筛选和排序，不模拟不存在的能力。
- 优先使用 `util` 包中的 JSoup、网络和 JSON 扩展，以获得统一的空值处理和错误信息。
- 图片链接不能在 `getPages` 中直接得到时，可以返回中间页 URL，并在 `getPageUrl` 中解析最终地址。
- 防盗链、来源 Header 或响应改写统一通过页面 headers 或 `Interceptor` 处理，不把网络细节散落到多个方法。
- 多个同引擎站点存在相同算法时，提取窄职责的共享实现；不要为了单个站点预先设计通用框架。
- 解析失败应提供包含站点和字段上下文的错误，避免无说明的 `!!`、静默空列表或吞掉异常。
- 不绕过账号权限、付费内容或验证码，也不要把 Cookie、Token、个人账号数据及抓包中的敏感信息提交到仓库。

## 代码风格

- 遵循 `.editorconfig`：UTF-8、LF、4 空格缩进、120 字符行宽、Kotlin 尾随逗号。
- 命名遵循 Kotlin 约定：类型使用 `PascalCase`，函数和属性使用 `camelCase`，测试类使用 `*Test` 或 `*IntegrationTest`。
- 保持改动简单且聚焦：优先小型私有函数和已有抽象，不增加无当前用途的配置、依赖或兼容层。
- 注释解释站点协议、混淆或非直观约束，不复述代码；保持所在文件已有的注释语言。
- 未明确必要时不要新增依赖。确需新增时说明为何现有标准库和项目依赖无法满足。

## 工作流与提交

- 工作区可能包含用户改动；修改前后检查 `git status`，只触碰任务范围内的文件，不覆盖或清理无关变更。
- 不提交缓存、IDE 配置、凭据、抓包文件或包含个人信息的完整网页快照。
- **构建产物归属**：插件构建产物仅发布到专用插件仓库分支（当前为 `origin/repo`），不提交到源码分支。
- 提交应保持单一职责，信息简洁准确。交付时报告实际运行的验证命令，以及因网络或环境原因未执行的检查。
- **验收前推送**：本项目构建及适用自动检查完成、需要人工验收时，自动将当前任务分支推送到已确认的可写远端，并通过既有发布流程更新 Kotoyomi 使用的远端插件仓库，使用户可以通过现有仓库 URL 更新本次插件进行验收，无需再次询问是否推送。用户验收前保留任务分支，合入主线仍遵循验收后的合并规则。

<!-- BEGIN brain.md -->
## Project Brain

This project keeps a **Project Brain**: a persistent memory layer of its durable decisions, requirements, and constraints. Read `./BRAIN.md` for the full read/write contract.

The `brain` CLI is not guaranteed to be on `PATH`. From the project root, invoke it as `node <brain-page-skill-dir>/bin/brain.mjs <subcommand> [flags]`, resolving `<brain-page-skill-dir>` to the installed `brain-page` skill directory.

Maintain the brain as part of normal coding work — not as a separate task. While discussing or implementing features:
- **Start of a task:** load relevant context with the `brain` CLI (`list-pages`, `read-page`, `read-root`). Prefer a narrow read over scanning everything.
- **When a decision, requirement, constraint, or durable insight settles** (in chat or while coding): capture it immediately via the `brain` CLI. Do not wait to be asked and do not batch it for later.
- **Pure implementation with no new decision:** do not write to the brain.
- **When overturning a prior conclusion:** update the page (`update-truth` and/or `append-timeline` with `kind: reversal`, or `archive-page`).
- Only store what will still matter in six months and is hard to reconstruct from the code alone.
- Never hand-edit brain files. If a brain MCP server is connected and authenticated, prefer it; otherwise use the `brain` CLI.

The brain skills (`brain-setup`, `brain-page`, `brain-ingest`, `brain-bootstrap`) are installed in your global skills directory. To scaffold a new project, run `node <brain-page-skill-dir>/bin/brain.mjs init` from its root.

If native notes/history are available, keep relevant brain page IDs and unresolved task state in notes; search history for earlier task evidence. After context rollover, re-read relevant pages through the CLI for current project facts. Do not copy task history into the brain.
<!-- END brain.md -->
