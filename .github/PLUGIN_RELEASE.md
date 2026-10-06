# 插件发布

插件版本独立于上游源码版本与 GitHub Actions 运行编号。每次实际发布读取远端
`repo:index.min.json` 中解析器条目的 `code`，递增为 `code + 1`，显示版本为
`1.0.<code>`。当前入口仍为原仓库 URL、`index.min.json` 和 `apk/plugin.jar`。

## 使用已有验收产物（优先）

在已验证源码提交的干净工作区执行。产物应存放在被忽略的 `build/` 内，提供
此前构建验证记录中的 SHA-256；不要为了通过检查而替换成未知文件的新哈希。

```powershell
python .github/scripts/publish_plugin.py --artifact build/libs/kototoro-parsers-plugin.jar --expected-sha256 <已验证的SHA256> --source-ref HEAD --push --verify-base-url https://raw.githubusercontent.com/BarrayAllen0818/kotoyomi-plugins/repo/
```

省略 `--push` 和 `--verify-base-url` 只在本地准备候选提交，不修改远端。
省略推送的候选和失败候选保留在 `build/plugin-releases/` 的独立工作树，供核查；
不要直接删除含 `.git` 的目录。重新执行脚本会重新获取远端状态并建立候选。
脚本不会清理或覆盖之前的候选。

所有自动发布与本地发布均使用此脚本，不再手工填写版本，不使用 `--force`。
脚本校验远端索引及产物 DEX/哈希，保留其他插件条目、字段和发布文件，新增
`release.json` 记录源码提交与产物哈希，普通推送到 `repo`。仓库必须已有有效
`repo` 分支和解析器条目；缺失或损坏时停止，不能默认为版本 1 重新初始化。

若远端插件字节与输入完全相同，跳过提交和版本递增。源码合并不触发发布，因此
无需用 `[skip ci]` 阻止重复发布；正常编译检查仍可运行。字节不同的重构建不视作
同一产物，故验收通过后应直接保留原产物，不能重新触发构建来“完成合并”。

## 需要 Actions 构建时

完成对应功能的适用检查后，显式触发 **Build and Release Plugin**，选择待验收的
任务分支。工作流只接受 `workflow_dispatch`，不会因 push、PR 或合并自动发布。
Actions 提供构建/打包和发布，不替代本次功能检查或手机验收。选择分支必须已经
包含新工作流和发布脚本；本规则首次合入默认分支后，可通过 Actions 界面或
`gh workflow run release.yml --ref <任务分支>` 调用。

使用 JDK 21、D8 9.3.16 和项目 runtime classpath，产出仅含 DEX 的插件。产物和
发布 JSON 输出保存为 workflow artifact；源码分支不提交二进制。索引版本以
发布时远端值为准，与该次 workflow 的 run number 无关。

## 并发及失败

- Actions 发布使用仓库级并发组，不中途取消正在发布的运行；GitHub 可能替换
  等待中的旧运行，需要发布每个候选时按顺序触发。
- 本地与 Actions 竞争同一 `repo` 时，普通非快进推送拒绝落后候选。停止并核对
  远端，再判断是否仍需发布；不自动 rebase、重试推送或覆盖别人的版本。
- 索引/产物校验失败时不发布。推送成功但 HTTP 缓存仍旧或下载校验失败时，明确
  报告“已推送、HTTP 未验证”，不要回退远端历史或凭失败消息再递增版本。
  对同一产物重新运行脚本会跳过发布并重新核对 HTTP。

## 同步上游

上游源码同步在独立任务分支完成，保留当前个人解析器改动及发布规则。关注
`.github/workflows/release.yml` 的冲突：不能恢复 `github.run_number`、主线 push
自动发布或 `force_orphan`。通过测试后发布验收版，用户验收后合并主线；不再
发布第二份产物。该流程维护任务本身不递增插件版本，也不修改当前验收产物。

发布逻辑测试使用本地临时 Git 远端，不连接 GitHub、不发布插件：

```text
python .github/scripts/test_publish_plugin.py
```
