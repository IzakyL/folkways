# 测试指南

[文档](README.md) · [English](../en/testing.md)

先按[开发环境](../../CONTRIBUTING.zh-CN.md#开发环境)完成准备。

## 无需启动 Minecraft 的检查

| 命令                      | 检查范围                                                               |
| ------------------------- | ---------------------------------------------------------------------- |
| `npm run format:md:check` | 全部项目 Markdown 的 Prettier 格式检查；提交前和 CI 中也会执行         |
| `npm run test:unit`       | TypeScript 辅助代码的 Node 测试，包含 i18n                             |
| `npm run test:i18n`       | 语言键、占位符、通知/拒绝覆盖及内置样式字典；无需安装 npm 依赖         |
| `npm run typecheck`       | 全部 TypeScript 套件的类型检查                                         |
| `./gradlew check`         | 各模组检查、游戏测试源码编译，以及制表符和行尾空白检查；不启动游戏测试 |
| `./gradlew build`         | Gradle 检查、jar 打包与汇总                                            |

## NeoForge GameTests

选择测试所属模组和运行环境：

| 命令                                                                                | 范围                                                               |
| ----------------------------------------------------------------------------------- | ------------------------------------------------------------------ |
| `./gradlew runGameTestServer`                                                       | 基础环境中的 Core、Vanilla、Create 合并测试                        |
| `./gradlew -PcookeryTests=true runGameTestServer`                                   | 加载 Cookery 的合并测试，包含 Core 和 Vanilla 的 `src/cookeryTest` |
| `./gradlew -PgameTestMod=core runGameTestServer`                                    | 基础环境中的 Core 测试；也支持 `vanilla` 或 `create`               |
| `./gradlew -PgameTestMod=core -PcookeryTests=true runGameTestServer`                | 加载 Cookery 的 Core 测试；也支持 `vanilla`                        |
| `./gradlew -PgameTestMod=cookery -PcookeryTests=true runGameTestServer`             | 加载 Cookery，仅运行 Cookery 所属测试                              |
| `./gradlew -PgameTestMod=modulargolems -PmodularGolemsTests=true runGameTestServer` | 加载模块化傀儡及其依赖，运行集成测试                               |

测试归属与已安装模组分开：选择 Core 时仍会加载 Vanilla 居民和相关集成。
模块化傀儡需要 `-PmodularGolemsTests=true`，其默认工作配置测试覆盖三类傀儡。
Core 会一并运行已有 LDLib2 依赖测试，其他模组不重复运行。

游戏目录为 `run/gametest/<mod>/<profile>/`，合并运行的模组名为 `all`。
环境名按固定顺序拼接已启用的可选套件 `cookery`、`modulargolems`、`tavern`，
未启用时为 `base`，例如 `cookery-modulargolems`。
Tavern 需要 Cookery；虽然复用 Cookery 的 fixtures，仍有独立后缀，确保每种模组组合使用独立世界。
直接通过 Gradle 启动服务器前，需要先在对应目录的 `eula.txt` 中接受 [Minecraft EULA](https://www.minecraft.net/eula)。
仅在同意其条款后执行。

## Blockwright 套件

[Blockwright](https://github.com/IzakyL/blockwright) 驱动真实 Minecraft 客户端和服务器。
上游源码已支持原生 Windows，但当前锁定的 npm 发布包早于这次改动。
使用发布包时需要 Linux x64，Windows 下可使用源码构建的 SDK，见[开发环境](../../CONTRIBUTING.zh-CN.md#开发环境)。
本仓库的 CI 使用 Ubuntu 24.04。首次运行会下载 Minecraft、NeoForge 和测试模组。
`blockwright.toml` 为测试服务器设置了 `eula = true`，
运行这些套件要求接受 Minecraft EULA。

| 命令                    | 用途                                         |
| ----------------------- | -------------------------------------------- |
| `npm run test:e2e`      | 地面上的玩家可见行为                         |
| `npm run test:sable`    | 成对 e2e 场景的船上版本，以及 Sable 专属场景 |
| `npm run test:coverage` | 带覆盖率采集运行两套 e2e，然后生成报告       |
| `npm run visual:take`   | 界面、面板、居民外观和世界画面的截图         |
| `npm run ponder:take`   | 视觉套件的 Ponder 筛选入口：视频及文字关键帧 |
| `npm run pattern:take`  | 建筑样式展示                                 |
| `npm run bench:take`    | 大规模殖民地性能基准                         |
| `npm run promo:take`    | 宣传素材录制                                 |

使用 `--workers=1` 限制并发游戏实例。单个场景示例：

```sh
npm run test:e2e -- tests/e2e/founding-smoke.spec.ts --workers=1
```

`tests/` 下每个套件有自己的 `playwright.config.ts` 和 `tsconfig.json`。
套件输出放在 Git 忽略的 `tests/out/`。

### 编写 e2e 场景

玩家可观察到的行为优先使用黑盒测试。共享辅助代码放入 `tests/shared/`；
套件可以引用此处及 `tests/ldlib2/`，不可引用其他套件。

殖民地方块场景使用 [`tests/shared/harness/stage.ts`](../../tests/shared/harness/stage.ts) 中的成对环境。
地面版本由 `test:e2e` 运行，船上版本由 `test:sable` 运行。
布局使用规划坐标，地面准备用 `stage.lay`，方块命令与读取用 `stage.at(cell)`，
可见世界位置用 `stage.world(cell)`，玩家站位用 `stage.stand(at)`。
船舶专属场景放在 `tests/sable/`，完整例子见 [`founding-smoke.spec.ts`](../../tests/e2e/founding-smoke.spec.ts)。

仓库的 `.mcp.json` 向支持 MCP 的助手暴露相同测试框架，LDLib2 界面适配器位于 `tests/ldlib2/`。

### 可选 RTS 输入

默认配置不加载 `folkways_war`。两个 RTS 场景显式使用 `blockwright.toml#combat`，
在客户端和服务端安装可选输入模块：

```sh
npm run test:e2e -- tests/e2e/combat-overhead-suspend.spec.ts tests/e2e/combat-rts-input.spec.ts --workers=1
```

挂起场景检查真实服务端回复、未绑数字组的任务续行，以及客户端追踪缺失后重开界面。
输入场景以实际鼠标和键盘事件验证选择、快捷组、路线编辑与命令按钮；Java 任务仅布置初始场景或读取状态。
路线截图需要另外看图验收。两个场景均不能证明大规模性能或任意地形可靠性。

验证未安装 War 的 Vanilla 时，`combat-camera.spec.ts`、`combat-page.spec.ts` 和
`weapon-sessions.spec.ts` 保持默认配置；此时战斗工具的世界右键打开书页，不下达 RTS 输入。

### JEC 搜索兼容

```sh
npm run test:e2e -- tests/e2e/jech-search.spec.ts --workers=1
```

该场景分别在未安装 JEC 和 `blockwright.toml#jech` 环境运行；后者只在客户端安装 JEC，两组服务器均不安装。
覆盖物品、目录、居民分工和库存匹配、原有查询运算符，以及复用同一个已准备查询时切换 JEC 模糊音和键盘方案的行为。
默认测试环境不安装 JEC；CI 的建村冒烟测试不会运行此场景。

### Ponder 与界面录制

Ponder 录制位于 `tests/visual/ponder.spec.ts`，随 `npm run visual:take` 一起运行；
`npm run ponder:take` 使用 `-g ponder` 筛选。通过 `Scenes.declared()` 发现课程并按名称打开。
基础配置录制全部 Folkways 课程；`blockwright.toml#cookery-tavern` 录制
`folkways:cookery` 文件夹的全部课程，包括酒馆。住房、用餐和起居课程还录制 `zh_cn` 版本，
农业课程还检查 GUI 缩放 3。`PONDER_SCENES=housing,tavern npm run ponder:take` 只录制指定课程。每个场景在
`tests/out/visual/takes/ponder-<profile>-<locale>-scale-<scale>-<scene>/` 下保存
`<scene>.mp4`、`NN-keyframe.png` 和 `index.json`。索引记录每帧的时间、显示文本、英文和翻译键。

`living-ui.spec.ts` 和 `resident-detail.spec.ts` 分别以 `en_us` 和 `zh_cn` 录制起居界面，
输出目录为 `living-ui-<locale>` 和 `resident-detail-<locale>`。筛选重跑只刷新自己的目录，保留其他视觉输出。

### 宣传素材

录制前先运行 `npm run setup`；`npm run setup:promo-models` 准备酒狐模型。
每个 Cookery 片段都在空地上搭一个小场景、放几名酒狐居民，用一个固定机位拍摄；画面里没有发生对应功能时片段判为失败：

| 片段                      | 内容                                                       |
| ------------------------- | ---------------------------------------------------------- |
| `cookery-kitchen.take.ts` | 厨师使用汤锅、两口炒锅和砧板，把菜摆上用餐区的餐桌         |
| `cookery-dining.take.ts`  | 一名居民把菜摆满一张相连的长桌，饥饿的居民找空椅子坐下用餐 |
| `cookery-wine.take.ts`    | 酿酒师榨葡萄、灌满酒桶；装瓶后，食客佐餐喝酒               |
| `create-elevator.take.ts` | 疲倦的居民乘坐唯一通往阁楼的机械动力电梯回床睡觉           |

`npm run promo:cookery` 录制厨房、酿酒和电梯三个片段；用餐片段用 `npm run promo:take -- cookery-dining.take.ts` 单独录制。
片段写入 `tests/out/promo/takes/<take>/`，每秒的采样放在旁边。
`npm run promo:cookery:film` 把所有已录制片段复制到 `tests/out/promo/film/`，并写出酒狐模型的署名信息。

## Cookery 测试环境

`blockwright.toml` 的 `cookery` 和 `cookery-sable` 环境同时加载 `folkways_cookery` 与森罗厨房 1.6.0。
`cookery-native` 只加载原模组，用于检查 Folkways 扩展仍可选装。

Cookery e2e 场景覆盖原生生产、中断与恢复、共享工具、水田从收获到补种的循环，以及十三节教程。
`creative-supplies.spec.ts` 还会在没有 Cookery 的情况下运行。

NeoForge 测试位于 `mods/cookery/src/gametest`。
需要 Core 或 Vanilla 包内访问的测试位于 `mods/{core,vanilla}/src/cookeryTest`，
仅在 `-PcookeryTests=true` 时加入各自所属模组的测试。

原生配方审计检查**锁定的 Cookery 1.6.0 测试版本中的 612 个配方资源**，
将 `cookery-recipe-audit.json` 写入游戏目录。完成按模组选中的 Cookery 运行后，可以生成配方图：

```sh
node tests/shared/cookery/recipe-graph.mjs \
  run/gametest/cookery/cookery/cookery-recipe-audit.json \
  tests/out/cookery-recipe-graph.json
```

若使用合并运行，审计输入路径为 `run/gametest/all/cookery/cookery-recipe-audit.json`。

## CI 实际运行内容

[构建工作流](../../.github/workflows/build.yml)在 push 和 PR 时运行：

| 任务                    | 检查内容                                                                                                                                |
| ----------------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| Build                   | `npm ci`、`npm run format:md:check`、`npm run setup`、`./gradlew build`、`npm run typecheck`、`npm run test:unit`，上传 `folkways-jars` |
| GameTests (core)        | 基础与 Cookery 环境，包含 LDLib2 依赖测试                                                                                               |
| GameTests (vanilla)     | 基础与 Cookery 环境                                                                                                                     |
| GameTests (create)      | 基础环境                                                                                                                                |
| GameTests (cookery)     | Cookery 环境                                                                                                                            |
| Client smoke (founding) | 真实客户端/服务器运行 `founding-smoke.spec.ts`，单 worker                                                                               |

同一模组的两个环境都会尝试运行，即使第一个失败；任一环境失败都会使该模组检查失败。
每次服务器进程限时 50 分钟，模组任务限时 110 分钟，客户端冒烟任务限时 60 分钟。
CI 不运行完整 e2e、Sable、界面截图、Ponder、性能基准或宣传录制套件。

向 `master` 推送后的 nightly 发布要求 Build、全部四项 GameTests 和 Client smoke 通过。

### 失败产物

即使测试失败，CI 仍上传控制台日志、游戏日志、崩溃报告和 JUnit 套件级结果。
每次调用后，控制台日志与 JUnit 文件写入 `run/gametest/ci/<mod>-<profile>.*`，
上传产物名为 `gametest-<mod>-reports`。
JUnit 用例表示本次服务器调用是否通过；单个 GameTest 的详细结果保留在服务器日志中。

客户端冒烟测试将 `tests/out/` 上传为 `client-smoke-results`。
