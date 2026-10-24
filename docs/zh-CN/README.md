# 文档

[English](../en/README.md) · 简体中文

| 我想……                                               | 从这里开始                                 |
| ---------------------------------------------------- | ------------------------------------------ |
| 安装 Folkways 并建立殖民地                           | [项目 README](../../README.zh-CN.md)       |
| 管理成员、容器、居民饮食住房与特长、堆肥桶或居民外观 | [玩法指南](gameplay.md)                    |
| 添加建筑样式、结构、皮肤或模型                       | [自定义内容](custom-content.md)            |
| 配置服务器                                           | [服务器配置](custom-content.md#服务器配置) |
| 构建模组并参与开发                                   | [贡献指南](../../CONTRIBUTING.zh-CN.md)    |
| 选择和运行测试                                       | [测试指南](testing.md)                     |
| 查找模组、API 或生命周期钩子                         | [架构说明](architecture.md)                |

## 维护这些文档

英文和中文版应在同一次修改中同步更新。界面名称以游戏语言文件为准，命令、文件名、模组 ID 和 Java 符号保持原文。
根目录 README 面向初次接触模组的玩家，贡献指南说明参与流程，详细参考内容放入本目录。

暂存 Markdown 前运行 `npm run format:md`。
本地 Husky hook 会检查全部项目 Markdown 及其暂存版本，CI 也会执行 `npm run format:md:check`。

版本以 `gradle.properties` 及各模组的 `neoforge.mods.toml` 为准，命令以 `package.json` 和 Gradle 文件为准，
CI 行为以 `.github/workflows/build.yml` 为准。文档列出了某个测试命令，并不表示某次构建已经通过该测试。

Last commit 徽章跟踪上游 `master`，点击进入 `nightly`。
Last release 徽章从最近 100 条 GitHub 发布记录中提取首个符合 `v主版本.次版本[.修订版本]` 格式的发布日期；
可带 `-alpha`、`-beta` 或 `-rc` 后缀及构建元数据。两份 README 使用同一个 Shields 动态正则 URL。
没有符合条件的版本时，Shields 显示 `no result`，不能用 nightly 日期替代。
点击进入排除 `nightly` 的 GitHub 发布列表。若项目改变标签命名规则，应同步更新两份 README 的筛选表达式。

完整目录见[文档索引](../README.md)。

[TACZ 枪械](tacz.md)
