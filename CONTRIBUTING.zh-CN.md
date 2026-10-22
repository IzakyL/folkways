# 参与 Folkways 贡献

[English](CONTRIBUTING.md) · 简体中文

欢迎向 [IzakyL/folkways](https://github.com/IzakyL/folkways) 提交问题报告、文档、翻译、建筑样式和代码贡献。
安装与玩法入门请先阅读 [README](README.zh-CN.md)。

[报告问题](#报告问题) · [准备开发环境](#开发环境) · [验证修改](#验证修改) ·
[提交 PR](#提交-pr) · [贡献约定](#贡献约定)

## 报告问题

使用[问题报告表单](https://github.com/IzakyL/folkways/issues/new?template=bug_report.yml)，说明操作步骤、预期结果与实际现象。
列出 Minecraft、NeoForge、LDLib2、所有已安装 Folkways jar 的版本，以及其他模组清单。
附上 `logs/latest.log`；发生崩溃时，再附上 `crash-reports/` 中对应的报告。

新功能请使用[功能建议表单](https://github.com/IzakyL/folkways/issues/new?template=feature_request.yml)，
描述期望行为、使用场景，以及考虑过的替代方案。

## 开发环境

| 工具或平台         | 要求                                                 |
| ------------------ | ---------------------------------------------------- |
| Java               | JDK 21                                               |
| Node.js            | 22.14 或更新版本                                     |
| Gradle             | 使用仓库内的 `./gradlew`，Windows 使用 `gradlew.bat` |
| Blockwright 客户端 | 支持 Windows 和 Linux，随 npm 依赖安装               |

[Blockwright](https://github.com/IzakyL/blockwright) 客户端支持 Windows 和 Linux。
本仓库的 CI 使用 Ubuntu 24.04。在仓库根目录执行：

```sh
npm ci
npm run setup
./gradlew build
```

`npm ci` 安装锁定的测试依赖；`npm run setup` 按 [`blockwright.toml`](blockwright.toml)
下载测试模组，并生成 `.blockwright/mods.json`。
**第一次运行 Gradle 前必须先执行 setup**，调整锁定依赖后也应重新执行。
Gradle 编译时需要可选集成的 jar，即使某个测试环境并不加载这些模组。

五个模组 jar 汇总到 `build/libs/`，运行时使用的 LDLib2 放在 `build/deps/`。
模块边界与源码位置见[架构说明](docs/zh-CN/architecture.md)。

在本地 Git 仓库中，`npm ci` 还会安装 Husky 的 pre-commit hook。

## 验证修改

先运行无需启动 Minecraft 的检查：

```sh
npm run format:md:check
npm run test:unit
npm run typecheck
./gradlew check
```

行为变更应新增或更新测试；玩家可观察到的行为优先用黑盒 e2e 测试覆盖。
按[测试指南](docs/zh-CN/testing.md)选择相关模组、运行环境和客户端测试套件，并留意其中的 EULA 与平台要求。

CI 会运行构建检查、按模组划分的 GameTests，以及真实客户端的建村冒烟测试，
但不会运行全部客户端和素材录制套件。完整矩阵、筛选参数和失败产物见测试指南。

## 提交 PR

1. 在自己的 fork 中创建聚焦单一主题的分支。
2. 遵循下方约定，更新相关测试和文档。
3. 运行与改动相关的检查和游戏测试。
4. 向 [IzakyL/folkways](https://github.com/IzakyL/folkways/pulls) 提交 PR，说明问题、修改后的行为和验证方式；列明未运行的检查及剩余限制。

提交信息使用 `type(scope): summary` 格式，例如：

```text
fix(plan): keep rations out of cargo
feat(draft): add a dock pattern
```

## 贡献约定

### 代码与测试归属

- 沿用周围代码的命名、注释密度和写法。
- 不使用制表符和行尾空白；`./gradlew check` 会检查项目约定范围内的格式。
- GameTests 放在行为所属的模组中，包括需要访问包内成员的测试。
  共享准备逻辑放入 Core 的 `src/gameTestFixtures`，不注册测试，详见[生命周期与测试归属](docs/zh-CN/architecture.md#生命周期与测试归属)。
- TypeScript 共享辅助代码放在 `tests/shared/`。套件可引用此处及 `tests/ldlib2/`，不可引用其他套件。

### 翻译与建筑样式

- 模组拥有的玩家可见文本统一加入 `mods/core/src/main/resources/assets/folkways/lang/` 的四份共享语言文件：
  `en_us.json`、`zh_cn.json`、香港 `zh_hk.json` 与台湾 `zh_tw.json`。
  使用各地区 Minecraft 的术语，不能只做简繁字符转换。
- 文本传到客户端前保留为 `Component`。界面同步使用 `Pages.text` / `Pages.readText`，信息卡使用 `Line.text`；
  在服务器调用 `getString()` 会丢失玩家的语言选择。
- 插值使用 `%s`；需要调整顺序时使用 `%1$s`；百分号字面量使用 `%%`。
- 修改翻译后运行 `npm run test:i18n`。它会检查键、参数、直接翻译调用、通知/拒绝枚举和内置样式字典与错误，
  但不能覆盖全部动态生成的 Java 翻译键。
- 样式的标题、控件、材料和拒绝提示属于自身的 `lang` 字典。内置样式和共享库应提供四种语言，
  无需在模组语言文件中重复登记。
- 自定义样式按词回退到其他中文地区，再回退到 `en_us`；`zh_hk` 与 `zh_tw` 优先互相回退，再尝试 `zh_cn`。
- 预期内的拒绝使用 `reject("error.key", ...)`，最多四个文本或数值参数。
  `reject` 与 `translated("another.key")` 都会解析样式及共享库字典；同语言、同键时，样式自身的词条优先。
  材料名等需要翻译的参数使用 `translated`；自定义样式的脚本诊断使用 `fail(...)`。

### 文档

暂存 Markdown 改动前先运行 `npm run format:md`。
每次提交都会检查全部项目 Markdown 及其暂存版本，格式不合格会阻止提交。
CI 也会运行 `npm run format:md:check`。

同步更新英文和中文版，界面名称以游戏语言文件为准，命令、路径和 Java 符号保持原文。
详细玩法、自定义内容、测试与架构说明放入 `docs/en/` 和 `docs/zh-CN/`。
目录和维护说明见[文档索引](docs/README.md)。
