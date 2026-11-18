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
居民行为异常时，可先开启**调试日志**，注视相关居民或方块，把调试卡片截图附上；用法见 [README](README.zh-CN.md#排查居民行为)。

新功能请使用[功能建议表单](https://github.com/IzakyL/folkways/issues/new?template=feature_request.yml)，
描述期望行为、使用场景，以及考虑过的替代方案。

## 开发环境

| 工具或平台      | 要求                                                     |
| --------------- | -------------------------------------------------------- |
| Java            | JDK 21                                                   |
| Node.js         | 22.14 或更新版本                                         |
| Gradle          | 使用仓库内的 `./gradlew`，Windows 使用 `gradlew.bat`     |
| Blockwright SDK | npm 包：Linux x64，或 Windows 11 x64（候选支持）         |

本仓库锁定的 [Blockwright](https://github.com/IzakyL/blockwright) npm 包同时附带 Linux x64 与 Windows 11 x64 的原生驱动。
本仓库的 CI 使用 Ubuntu 24.04。请从仓库根目录执行（Windows 使用 `gradlew.bat`）：

```sh
npm ci
npm run setup
./gradlew build
```

`npm ci` 安装锁定的测试依赖；`npm run setup` 按 [`blockwright.toml`](blockwright.toml)
下载测试模组，并生成 `.blockwright/mods.json`。
**第一次运行 Gradle 前必须先执行 setup**，调整锁定依赖后也应重新执行。
Gradle 编译时需要可选集成的 jar，即使某个测试环境并不加载这些模组。

十个模组 jar 汇总到 `build/libs/`，运行时使用的 LDLib2 放在 `build/deps/`。
每个模组是 `mods/` 下的一个 Gradle 子项目，各自的作用见[模组一览](README.zh-CN.md#模组一览)。
小队编组和直接指挥属于 Core 的 **RTS** 模式（手持殖民书 → **V** → **RTS**）。
命令测试夹具应使用 RTS API；装备分配使用**装备方案**页。

Windows 11 x64 目前是 Blockwright 的候选支持平台，环境要求见其 [Windows 说明](https://github.com/IzakyL/blockwright/blob/master/docs/environment.md#windows-development-candidate)。
客户端测试需要已登录的 Windows 11 x64 桌面和支持 OpenGL 的 GPU，显示设备使用 `auto` 或 `gpu`。
报告测试结果时请注明平台；本地 Windows 测试与 Linux CI 是不同的验证运行。

## 验证修改

先运行无需启动 Minecraft 的检查：

```sh
npm run test:unit
npm run typecheck
./gradlew check
```

行为变更应新增或更新测试；玩家可观察到的行为优先用黑盒 e2e 测试覆盖。
运行相关模组的 GameTests 和客户端测试套件。

CI 会运行构建检查、按模组划分的 GameTests，以及真实客户端的建村冒烟测试，
但不会运行全部客户端和素材录制套件。完整矩阵见 `.github/workflows/build.yml`。

GameTests 统一使用以下模板，按需加上下表中的开关：

```sh
./gradlew --offline -PgameTestMod=<mod> [-P<开关>=<值> ...] runGameTestServer
```

| 属性 | 启用内容 | 限制 |
|---|---|---|
| `gameTestMod` | 单个模组的测试套件，取值为 `mods/` 下的目录名；扩展所联动的模组会自动加载 | 省略时只启用 Core 的基础套件 |
| `cookeryTests`、`modularGolemsTests`、`cartsTests`、`epicfightTests`、`chiselsTests` | 原生模组及其扩展的测试夹具；`cookeryTests` 还会加入 Core 与 Vanilla 的 Cookery 夹具 | `gameTestMod` 分别为 `cookery`、`golems`、`carts`、`epicfight`、`carving` 时默认开启 |
| `taczTests`、`warfareTests` | TACZ 或卓越前线夹具（Firearms） | 默认关闭 |
| `firefightingTests` | 消防附加夹具（Create） | 默认关闭 |
| `tavernTests` | 森罗酒馆夹具（Cookery） | 需要 `cookeryTests` |
| `createPlacementTests` | Create 放置夹具（Carving） | 需要 `gameTestMod=carving` |
| `storageProfile` | 单个仓储模组：`sophisticatedStorage`、`sophisticatedBackpacks`、`drawers`、`functional`、`createStorage`、`toms`、`colossal` 或 `qio` | 仅限 Core；AE2、RS2 与存储 profile 互斥 |
| `ae2Tests`、`rs2Tests` | Applied Energistics 2 或 Refined Storage 2 夹具 | 仅限 Core；两者互斥，也不能与存储 profile 同时使用 |
| `storageLaborTests`、`ae2LaborTests`、`rs2LaborTests` | 在原生端口测试之外加入居民劳动测试 | 需要对应的 profile 或套件，以及 Vanilla |
| `storageVanilla`、`ae2Vanilla`、`rs2Vanilla` | 设为 `false` 时在不装 Folkways Vanilla 的情况下测试原生端口 | 不能与劳动测试同用 |
| `accessoryProfile` | `curios` 或 `accessories`，并加载 Sophisticated Backpacks | 仅限 Core；不加载 Core 基础套件 |
| `accessoryCoexistence`、`accessoryCompat` | 在 Curios 旁加入真实 Accessories，或在 Accessories 中加入旧 cclayer | 只要设置即生效，与取值无关；分别搭配 `accessoryProfile=curios` 或 `accessories` |
| `coreGameTestClasses`、`vanillaGameTestClasses` | 只编译并注册列出的 GameTest 类（逗号分隔） | — |
| `gameTestFilter` | Airline 测试全名的正则（忽略大小写，方法名不带类名前缀） | 与 `gameTestMethods` 一起先过滤再分片 |
| `gameTestShard` | Airline batch 分片 `i/n`，`i` 从 0 开始 | 要求 `0 <= i < n`；先过滤再分片 |
| `gameTestMethods` | 只重跑列出的测试方法（限支持该功能的夹具） | — |
| `gameTestNamespaces`、`gameTestDirectory` | 覆盖启用的命名空间或运行目录 | 否则每种开关组合在 `run/gametest/` 下使用独立世界 |

先运行一次 `npm run setup` 下载锁定的测试模组，之后 Gradle 一律使用 `--offline`。原生模组套件在
[`blockwright.toml`](blockwright.toml) 中有对应的 Blockwright profile（`ae2`、`rs2`、`storage_<名称>`、`curios`、`accessories`）。
GameTest profile 请串行运行以控制内存占用。

Airline GameTests：`./gradlew --offline -PgameTestMod=airline runGameTestServer`。

用独立服务器进程并行运行 Airline（默认 3 个）：

```sh
npm run test:airline:shards -- --shards 3
npm run test:airline:shards -- --shards 2 '-PgameTestFilter=runway.*'
```

脚本先由 Gradle 准备、编译一次并导出 Java 启动命令，再并行启动 JVM，避开 Gradle 项目锁。
每个进程使用新的独立运行目录，自动写入 `eula.txt`，退出后删除世界。
日志、起止时间、耗时及通过/失败汇总保留在 `build/airline-shards/`，任一分片失败即非零退出。
额外 `-P` 参数会透传，包括 `gameTestMethods` ；
`gameTestMod`、`gameTestShard`、`gameTestDirectory` 由脚本管理。
每个服务器约需 6 GiB；Linux 上脚本等待可用内存达到 `6 × N + 2` GiB 后才启动。
分片保持 batch 完整：过滤后按 timeoutTicks 之和降序排列（同权按 batch 名），
依次分给负载最小的分片（同负载按分片号）。直接调用 Gradle 也可用 `-PgameTestShard=0/3`，
脚本在启动服务器前结束 Gradle，因此服务器执行不会被 Gradle 锁串行化，各分片也无需重复构建准备。
`gameTestFilter` 匹配日志中的完整测试名，如 `runwaymarkingssurveyandaircraftfit`，不带类名前缀。
Airline GameTest 模板与局部夹具使用单独分配的场地、带测试前缀的机场名，并在成功或失败时清理资源。
普通原生夹具共用 batch；统计服务器全局勘测预算的测试保留独占 batch，
在测试旁注明原因。生产环境的机场注册表与交通仍按维度共享。
需要加载大范围区块的夹具使用较小的共享 batch，限制同时驻留的区块内存。
分片 JVM 运行期间不要重新编译：导出的启动命令会直接读取当前 worktree 的构建输出。

`npm run test:airline` 运行机场塔台客户端夹具。

## 提交 PR

1. 在自己的 fork 中创建聚焦单一主题的分支。
2. 遵循下方约定，更新相关测试和文档。
3. 运行与改动相关的检查和游戏测试。
4. 向 [IzakyL/folkways](https://github.com/IzakyL/folkways/pulls) 提交 PR，说明问题、修改后的行为和验证方式；列明未运行的检查及剩余限制。

PR 以 squash 方式合并到 master。分支内的持久化格式变化只需对照 `origin/master` 考虑迁移，见[存档兼容性](SAVE_COMPATIBILITY.zh-CN.md#修改持久化格式)。

提交信息使用 `type(scope): summary` 格式，例如：

```text
fix(plan): keep rations out of cargo
feat(draft): add a dock pattern
```

## 贡献约定

修改持久化数据时，请遵循[存档兼容性](SAVE_COMPATIBILITY.zh-CN.md)。

### 代码与测试归属

- 沿用周围代码的命名、注释密度和写法。
- 不使用制表符和行尾空白；`./gradlew check` 会检查项目约定范围内的格式。
- GameTests 放在行为所属的模组中，包括需要访问包内成员的测试。
  共享准备逻辑放入 Core 的 `src/gameTestFixtures`，不注册测试。
- TypeScript 共享辅助代码放在 `tests/shared/`。套件可引用此处及 `tests/ldlib2/`，不可引用其他套件。

容器黑白名单和 `StoreRule.Access.store` 仅约束居民的日常存放。订单在规划和执行中均豁免，
也不得阻止修改容器配置。保留容器自身和槽位限制，玩家手动放入不受影响。

### 翻译与建筑样式

- 模组拥有的玩家可见文本统一加入 `mods/core/src/main/resources/assets/folkways_core/lang/` 的四份共享语言文件：
  `en_us.json`、`zh_cn.json`、香港 `zh_hk.json` 与台湾 `zh_tw.json`。
  使用各地区 Minecraft 的术语，不能只做简繁字符转换。
- 文本传到客户端前保留为 `Component`。界面同步使用 `Pages.text` / `Pages.readText`，信息卡使用 `Line.text`；
  在服务器调用 `getString()` 会丢失玩家的语言选择。
- 插值使用 `%s`；需要调整顺序时使用 `%1$s`；百分号字面量使用 `%%`。
- 样式的标题、控件、材料和拒绝提示属于自身的 `lang` 字典。内置样式和共享库应提供四种语言，
  无需在模组语言文件中重复登记。
- 自定义样式按词回退到其他中文地区，再回退到 `en_us`；`zh_hk` 与 `zh_tw` 优先互相回退，再尝试 `zh_cn`。
- 预期内的拒绝使用 `reject("error.key", ...)`，最多四个文本或数值参数。
  `reject` 与 `translated("another.key")` 都会解析样式及共享库字典；同语言、同键时，样式自身的词条优先。
  材料名等需要翻译的参数使用 `translated`；自定义样式的脚本诊断使用 `fail(...)`。

### 文档

同步更新英文和中文版，界面名称以游戏语言文件为准，命令、路径和 Java 符号保持原文。
文档只保留概述层面：README、贡献指南、模组联动说明、数据包指南和存档兼容规则，不再添加详细参考页面或单个模组的 README。
`THIRD_PARTY.md` 是许可证清单，`AGENTS.md` 是面向编程智能体的索引，两者都只保留英文。
