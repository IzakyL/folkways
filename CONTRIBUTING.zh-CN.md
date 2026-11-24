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

四个模组 jar 汇总到 `build/libs/`，运行时使用的 LDLib2 放在 `build/deps/`。
每个模组是 `mods/` 下的一个 Gradle 子项目，各自的作用见[模组一览](README.zh-CN.md#模组一览)。
小队编组和直接指挥属于 Core 的**控制**模式（手持殖民书 → **V** → **控制**）。
命令测试夹具应使用 RTS API；装备分配使用装备 API。

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
| `cookeryTests` | 森罗厨房及其联动夹具 | `gameTestMod=vanilla` 运行厨房玩法套件，`gameTestMod=core` 加入 Core 的厨房夹具；默认关闭 |
| `cartsTests` | AstikorCarts Redux 及货箱、马车线路夹具 | 使用 `gameTestMod=vanilla` 运行套件；默认关闭 |
| `modularGolemsTests`、`chiselsTests` | 分别加载模块化傀儡、Chisels & Bits 及对应的 Core 联动夹具 | 需要 `gameTestMod=core`，默认关闭；只运行所选联动夹具 |
| `epicfightTests` | 加载 Epic Fight 和人类居民联动夹具 | 使用 `gameTestMod=vanilla`，默认关闭 |
| `sableTests` | Sable 设施夹具 | 闸边搭配 `gameTestMod=vanilla`，炮架搭配 `gameTestMod=core` 和 `cbcTests=true`。默认关闭 |
| `taczTests`、`warfareTests` | TACZ 或卓越前线集成夹具 | 使用 `gameTestMod=vanilla`。默认关闭，`warfareTests` 也加载 Airline |
| `cbcTests` | Create Big Cannons 炮架夹具，同时加载机械动力和 Ritchie’s Projectile Library | 使用 `gameTestMod=core`，默认关闭 |
| `letItWalkTests` | Let it Walk! 步行机夹具（驾驶、驻停、居民乘员），同时加载机械动力和 Sable | 使用 `gameTestMod=core`，默认关闭 |
| `createTests` | 机械动力集成套件 | 使用 `gameTestMod=vanilla`，默认关闭 |
| `firefightingTests` | 机械动力集成套件及消防附加夹具 | 使用 `gameTestMod=vanilla`，默认关闭 |
| `vanillaBaseTests` | 在 Vanilla 的厨房联动测试中加入原有 Vanilla 夹具和原版套件 | 与 `gameTestMod=vanilla`、`cookeryTests=true` 一起使用 |
| `tavernTests` | 森罗酒馆夹具（Cookery） | 需要 `cookeryTests` |
| `createPlacementTests` | 为 Chisels & Bits 套件加入 Create 与打印放置夹具 | 需要 `gameTestMod=core` 和 `chiselsTests=true` |
| `storageProfile` | 单个仓储模组：`sophisticatedStorage`、`sophisticatedBackpacks`、`drawers`、`functional`、`createStorage`、`toms`、`colossal` 或 `qio` | 仅限 Core；AE2、RS2 与存储 profile 互斥 |
| `ae2Tests`、`rs2Tests` | Applied Energistics 2 或 Refined Storage 2 夹具 | 仅限 Core；两者互斥，也不能与存储 profile 同时使用 |
| `storageLaborTests`、`ae2LaborTests`、`rs2LaborTests` | 在原生端口测试之外加入居民劳动测试 | 需要对应的 profile 或套件，以及 Vanilla |
| `storageVanilla`、`ae2Vanilla`、`rs2Vanilla` | 设为 `false` 时在不装 Folkways Vanilla 的情况下测试原生端口 | 不能与劳动测试同用 |
| `accessoryProfile` | `curios` 或 `accessories`，并加载 Sophisticated Backpacks | 仅限 Core；不加载 Core 基础套件 |
| `accessoryCoexistence`、`accessoryCompat` | 在 Curios 旁加入真实 Accessories，或在 Accessories 中加入旧 cclayer | 只要设置即生效，与取值无关；分别搭配 `accessoryProfile=curios` 或 `accessories` |
| `coreGameTestClasses`、`vanillaGameTestClasses` | 只编译并注册列出的 GameTest 类（逗号分隔） | 搭配 `gameTestMod=vanilla` 和 `createTests=true` 时，Core 开关筛选适配测试，Vanilla 开关筛选玩法测试 |
| `gameTestFilter` | Airline 测试全名的正则（忽略大小写，方法名不带类名前缀） | 与 `gameTestMethods` 一起先过滤再分片 |
| `gameTestShard` | Airline batch 分片 `i/n`，`i` 从 0 开始 | 要求 `0 <= i < n`；先过滤再分片 |
| `gameTestMethods` | 只重跑列出的测试方法（限支持该功能的夹具） | — |
| `gameTestNamespaces`、`gameTestDirectory` | 覆盖启用的命名空间或运行目录 | 否则每种开关组合在 `run/gametest/` 下使用独立世界 |
| `saveUpgradeTests` | 加载并重新保存发布版 V4 集成夹具 | 单独使用 `-PsaveUpgradeTests=true` |

先运行一次 `npm run setup` 下载锁定的测试模组，再联网运行 `./gradlew build` 解析依赖（包括从 JitPack 获取的助手依赖 jpi-mini）。之后 Gradle 一律使用 `--offline`。原生模组套件在
[`blockwright.toml`](blockwright.toml) 中有对应的 Blockwright profile（`ae2`、`rs2`、`storage_<名称>`、`curios`、`accessories`）。
GameTest profile 请串行运行以控制内存占用。

助手的 Blockwright profile 可用 JVM 属性 `folkways.assistant.provider`、
`folkways.assistant.baseUrl` 和 `folkways.assistant.model` 覆盖客户端设置，并在一条警告中列出被覆盖的字段。

运行 `./gradlew --offline -PsaveUpgradeTests=true runGameTestServer` 验证 V4 → V5 世界升级。
每次运行时，测试会从 `tests/fixtures/saves/v4/` 复制一份存档到 `build/save-upgrade/`。
夹具由 `20680d4b87a28940bb1ac6853320b7dde3737d8d`（`origin/master`，Folkways 数据版本 4）
的 GameTest 服务器写出，保留了场景所在区块、聚落注册表，以及机械动力、TACZ 和雕凿工艺的旧服务器配置。
二进制存档夹具放在 `tests/fixtures/saves/`，只保留测试所需的文件和区块。

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

### 模组划分

- **Core 提供引擎。** 包括（聚落、居民、计划、世界、库存、出行）、设施、卡片、Starlark 运行时和指挥界面，
  以及 `core.shell.<mod>` 下的全部第三方适配。适配只回答「怎么做」：端口、操作位、操作协议、乘坐方式、武器怎么开火、
  结构怎么编解码，不登记职业、区域和工作。
- **Vanilla 包含居民玩法**：职业、工作、预制设施和带玩法判断的规程，也包括各集成的玩法部分（`vanilla.<mod>`），
  只在目标模组加载时初始化。模组可用 `HoldingKind` 扩充已纳入设施的卡片，并用 `ColonyPanel` 在
  **概览**、**居民分工**和**库存与订单**中提供殖民地级别的条目，或放在**设置**中自己的设置卡片下。
  卡片放不下的编辑器由**居民分工**上的按钮打开（`RegisteringUi.editor`）。
- AE2 和 RS2 是布局规则的例外。双向联动（包括**网络服务**）都和适配放在 Core。
  网络服务把网络与任意居民种类可用的工坊配方接通，与规划器和端口处于同一抽象层。
- 殖民书能画的每一项都要声明它服务的 `Category`（`Delegation.in`、`BoxOffers.Offer.in`、`PathOffers.Offer.in`、
  `Placing.Shelf.in`）；俯瞰视角按它给工具分组，形状选择框也按它列出候选。可使用内置分类，或用 `Category.register` 注册新分类；未声明的归入**其他**。
- 被适配的对象在哪一层，适配就放在哪一层。Core 不能依赖 Vanilla，所以针对人类居民的适配（例如 Epic Fight 的居民动画）
  留在 Vanilla。傀儡是没有需求的居民种类，整体放在 Core；人类居民及其需求属于 Vanilla。
- 只有独立玩法才单独成模组（Airline、Assistant）。对接了某个第三方模组不算理由：装没装那个模组本身就是开关。
  集成都是可选依赖；Core 启动时检查版本，不支持时关闭该集成的两半。

### 设施

- **Control（操作位）**描述在哪里可以执行什么操作。**Signal（信号）**设置值，
  **Port（端口）**传输物品、流体或能量。操作位本身不携带归属或设施语义。
- **Facility（设施）**独占其范围内的所有组成部分，并赋予操作位具体用途。范围由方块、方盒和实体组成，
  也可包含以移动结构的局部坐标记录的方块与方盒。殖民地仓储是一种 **Container（容器）**设施。
  存储控制器扩展各成员仓储的可用站位。工坊是设施的投影。
- **Facility（设施）**通过 `Operating` 提供参数、规程和读数，殖民书和其他操作界面共用声明式 `Card`。
  已纳入的区域、路径和方块，以及建造订单、列车、网络和殖民地设置分组，都使用这个接口。
  各纳入类型可为相应设施的卡片补充操作和状态。
- `FacilityDefinition` 可描述操作位（`Rig`）、范围、触发和投影。
  内置设施可来自方块或已加载的实体。玩家可通过助手用 Starlark 表达自己的设施行为，按需选用标准模块和基本库规程（`write_all`、`write_stages`、
  `write_sequence`、`move_to`、`deposit`）；装填什么、何时开火等玩法决策写在脚本里。
- Shell 适配器按原生部件命名操作位。被承载的操作位随承载部件的读数移动，分阶段写入可依次修改多个操作位。
  本地操作位需要居民到达指定站位，远程操作位可以直接运行规程，无需居民执行。
- **Post（值守岗位）**覆盖站位或座位触及范围内的信号和端口、额外指定的操作位，以及适配器补充的操作位。
  设施和联动模组通过 `Posts` 提供岗位。常驻需求和按需请求可发出计划工作，联动模组也可用冲动安排已分配的乘员。
  通用值守工作处理到达、原生登乘、在岗检查和租约。运行时由值守居民写入信号，并在覆盖的端口之间或端口与背包之间转移物品。
  远处库存由普通计划工作先运到岗位。定时序列可以混合转移和写入。若各步共用站位，也可由一位居民连续执行。
- 卡片提供参数字段、规程按钮和读数。设施还可提供目标选择和路径叠加层，并绑定到相应的规程或读数。
- **Routine（规程）**定义一次运行，展开成计划子图的居民工作由计划系统安排。失败后由发起方重新提交，规程按新的读数重新规划，
  设施状态以世界为准。
- **触发**分手动、常驻、按需（经投影出的 `Workshop` 拉动）、条件和定时。**投影**向通行边、禁行区、封闭区、障碍物、工坊、
  保护区和物流路线贡献内容，是可以从世界和设施数据重建的派生数据。物流路线只由设施声明，不从方块摆放识别。
  大门和卡片操作可用读数比较或 Starlark 谓词。大门根据缓存的谓词结果提供步行连接；搭乘投影在满足
  `available_when`（同样可用这两种谓词）时连接各停靠点。禁行区可来自运动部件或脚本。
  写入操作位由规程执行。
- 玩家定义的设施工坊用 Starlark 规程提供配方和动作批次。计划系统选定工坊时，核心模块会为本次运行预留配方原料。
  脚本任一步生成的工作都可按需使用匹配的预留原料。每个动作可指定失败处理函数。
  脚本可用 `recipes_of` 筛选原生配方，定义也可提供固定批次列表。
- 脚本读数可由探针和参数计算。规程可发布读数和路径，并检查参数和就绪状态。
  基于 `@std//ik` 的 `@std//position` 是校准轴定位的可选模块，`@std//ballistics` 根据瞄准部件的 `ballistics`
  读数让武器瞄准、装填并开火。Starlark 跑得太慢的引擎运算（如弹道求解）放在 Java 内置函数中，脚本所需的世界数据
  以主线程获取的读数或快照传入。发布 `engagement` 读数的规程会参与聚落火控。
- 主线程对每项采样至少间隔 10 刻获取快照，工作线程在步数、时间和内存预算内执行 Starlark，后续刻缓存结果。
  故障会暂停该项采样，并记录在最近结果中。

### 代码与测试归属

- 沿用周围代码的命名、注释密度和写法。
- 不使用制表符和行尾空白；`./gradlew check` 会检查项目约定范围内的格式。
- 模组测试源码放在 `tests/gametest/<mod>/<set>/java`，资源放在
  `tests/gametest/<mod>/<set>/resources`，按行为所属的模组归档。
  使用 Vanilla 居民或玩法的 Core 夹具可以放在 Vanilla 对应的目录中。
  Vanilla 编译跨模组夹具，启动器将它们与 Core 一起加载，以便访问包内成员。
  可复用的准备逻辑放在 `gameTestFixtures` 源集中。
- Assistant 在 `assistant/tool` 中维护工具注册表，以及权限检查、预览、确认令牌和助手的 Starlark 绑定。
  它通过 Core 接口读取领域数据并调用操作。Starlark 运行时及设施和卡片 API 由 Core 提供。
- TypeScript 共享辅助代码放在 `tests/shared/`。套件可引用此处及 `tests/ldlib2/`，不可引用其他套件。
- `tests/promo/` 存放宣传视频的拍摄脚本，仅供参考，不随玩法改动同步维护：它们不属于验证范围，拍摄脚本失效也不阻碍改动合入。

容器黑白名单和 `StoreRule.Access.store` 仅约束居民的日常存放。订单在规划和执行中均豁免，
也不得阻止修改容器配置。保留容器自身和槽位限制，玩家手动放入不受影响。

### 翻译与建筑样式

- 模组拥有的玩家可见文本加入所属模组 `mods/<目录>/src/main/resources/assets/<命名空间>/lang/` 下的四份语言文件：
  `en_us.json`、`zh_cn.json`、香港 `zh_hk.json` 与台湾 `zh_tw.json`。
  使用各地区 Minecraft 的术语，不能只做简繁字符转换。
- 文本传到客户端前保留为 `Component`。界面同步使用 `Pages.text` / `Pages.readText`，信息卡使用 `Line.text`；
  在服务器调用 `getString()` 会丢失玩家的语言选择。
- 插值使用 `%s`；需要调整顺序时使用 `%1$s`；百分号字面量使用 `%%`。
- 样式的标题、控件、材料和拒绝提示属于自身的 `lang` 字典。内置样式和共享库应提供四种语言，
  无需在模组语言文件中重复登记。
- 可在 `lang` 的各语言中添加 `marking`，为助手的 `patterns()` 结果中的 `anchors` 提供取点说明。
- 自定义样式按词回退到其他中文地区，再回退到 `en_us`；`zh_hk` 与 `zh_tw` 优先互相回退，再尝试 `zh_cn`。
- 预期内的拒绝使用 `reject("error.key", ...)`，最多四个文本或数值参数。
  `reject` 与 `translated("another.key")` 都会解析样式及共享库字典；同语言、同键时，样式自身的词条优先。
  材料名等需要翻译的参数使用 `translated`；自定义样式的脚本诊断使用 `fail(...)`。

### 文档

同步更新英文和中文版，界面名称以游戏语言文件为准，命令、路径和 Java 符号保持原文。
文档只保留概述层面：README、贡献指南、模组联动说明、数据包指南和存档兼容规则，不再添加详细参考页面或单个模组的 README。
`THIRD_PARTY.md` 是许可证清单，`AGENTS.md` 是面向编程智能体的索引，两者都只保留英文。
