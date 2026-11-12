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
| Blockwright SDK | Linux x64 使用 npm 包；Windows 11 x64 使用源码构建的 SDK |

[Blockwright](https://github.com/IzakyL/blockwright) 源码已实现原生 Windows 支持。
本仓库锁定的 npm 包 `0.1.0-alpha.1` 发布于这次实现之前，仍声明仅支持 Linux x64，
因此在 Windows 上直接执行 `npm ci` 会因 `EBADPLATFORM` 失败。
本仓库的 CI 使用 Ubuntu 24.04。使用发布包时，请在 Linux x64 上从仓库根目录执行：

```sh
npm ci
npm run setup
./gradlew build
```

`npm ci` 安装锁定的测试依赖；`npm run setup` 按 [`blockwright.toml`](blockwright.toml)
下载测试模组，并生成 `.blockwright/mods.json`。
**第一次运行 Gradle 前必须先执行 setup**，调整锁定依赖后也应重新执行。
Gradle 编译时需要可选集成的 jar，即使某个测试环境并不加载这些模组。

九个模组 jar 汇总到 `build/libs/`，运行时使用的 LDLib2 放在 `build/deps/`。
每个模组是 `mods/` 下的一个 Gradle 子项目，各自的作用见[模组一览](README.zh-CN.md#模组一览)。
小队编组和直接指挥属于 Core 的 **RTS** 模式（手持殖民书 → **V** → **RTS**）。
命令测试夹具应使用 RTS API；装备分配使用**装备方案**页。

Windows 下请按 Blockwright 的[本地构建说明](https://github.com/IzakyL/blockwright/blob/master/CONTRIBUTING.md)
和 [Windows 环境要求](https://github.com/IzakyL/blockwright/blob/master/docs/environment.md#windows-development-candidate)
准备兼容的源码 SDK。客户端测试需要已登录的 Windows 11 x64 桌面和支持 OpenGL 的 GPU，
显示设备使用 `auto` 或 `gpu`。通过该 SDK 准备好 `.blockwright/mods.json` 后，使用 `gradlew.bat` 构建。
报告测试结果时请注明 SDK 版本及平台；本地 Windows 测试与 Linux CI 是不同的验证运行。

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

运行单个模组的 GameTests：`./gradlew --offline -PgameTestMod=<mod> runGameTestServer`，`<mod>` 为 `mods/` 下的目录名；
扩展所联动的模组会自动加载。模组内部的子联动需单独开启：Firearms 用 `-PtaczTests=true` 或 `-PwarfareTests=true`，
Create 用 `-PfirefightingTests=true`，Cookery 用 `-PtavernTests=true`，Carving 用 `-PcreatePlacementTests=true`。

Carts GameTest 需要开启对应依赖：`./gradlew --offline -PgameTestMod=carts -PcartsTests=true runGameTestServer`。

存储端口 GameTest 每次只加载一个原生模组：

```sh
./gradlew --offline -PgameTestMod=core -PstorageProfile=sophisticatedStorage runGameTestServer
./gradlew --offline -PgameTestMod=core -PstorageProfile=sophisticatedBackpacks runGameTestServer
./gradlew --offline -PgameTestMod=core -PstorageProfile=drawers runGameTestServer
./gradlew --offline -PgameTestMod=core -PstorageProfile=functional runGameTestServer
./gradlew --offline -PgameTestMod=core -PstorageProfile=createStorage runGameTestServer
./gradlew --offline -PgameTestMod=core -PstorageProfile=toms runGameTestServer
./gradlew --offline -PgameTestMod=core -PstorageProfile=colossal runGameTestServer
./gradlew --offline -PgameTestMod=core -PstorageProfile=qio runGameTestServer
```

加 `-PstorageLaborTests=true` 运行居民劳动测试；省略该参数则仅测试原生端口。
增加 `-PstorageVanilla=false` 可验证未安装 Folkways Vanilla 时的原生端口（不可与居民测试共用）。
对应的 Blockwright 依赖 profile 是 `storage_sophisticated`、`storage_backpacks`、`storage_drawers` 和 `storage_functional`。

新 profile 分别固定 Create: Storage（ausmez）1.3.8 + Create 6.0.10、Tom’s Simple Storage 2.4.2、
Colossal Chests 1.9.1 + CyclopsCore 1.30.1、Mekanism 10.7.19.85。
对应 Blockwright 依赖 profile 为 `storage_createStorage`、`storage_toms`、`storage_colossal`、`storage_qio`。
先运行一次 `npm run setup` 获取固定下载，之后 Gradle 使用 `--offline`。
独立源码集分别为 `createStorageTest`、`tomsTest`、`colossalTest`、`qioTest`；
`-PstorageLaborTests=true` 会添加 `portsLaborTest`，验证居民经每种受支持访问点取放物品。
Create 控制器仅支持手动存入；巨型箱墙沿用已有 point claim 账本；QIO 仪表盘复用 take/put 方块标签，不提供合成。QIO 访问点及全部驱动器须位于同一维度，
因为 Core 在各维度分别预订库存。

```sh
./gradlew --offline :core:compileJava :core:compileCreateStorageTestJava :core:compileTomsTestJava :core:compileColossalTestJava :core:compileQioTestJava :core:compilePortsLaborTestJava
```

AE2 同样使用 Core 下的可选测试套件：

```sh
./gradlew --offline -PgameTestMod=core -Pae2Tests=true runGameTestServer
./gradlew --offline -PgameTestMod=core -Pae2Tests=true -Pae2LaborTests=true runGameTestServer
./gradlew --offline -PgameTestMod=core -Pae2Tests=true -Pae2Vanilla=false runGameTestServer
```

Refined Storage 2 也使用 Core 下的可选测试套件：

```sh
./gradlew --offline -PgameTestMod=core -Prs2Tests=true runGameTestServer
./gradlew --offline -PgameTestMod=core -Prs2Tests=true -Prs2LaborTests=true runGameTestServer
./gradlew --offline -PgameTestMod=core -Prs2Tests=true -Prs2Vanilla=false runGameTestServer
```

Blockwright 的 `rs2` profile 安装 Refined Storage 2.0.9，该版本没有外部必需模组依赖。
GameTest profile 请串行运行以控制内存占用；RS2、AE2 与存储 profile 互斥。

Blockwright 的 `ae2` profile 在 Core 之外安装 AE2 与 GuideME。可选套件只加载选定的原生模组、
Core 和未显式禁用的 Vanilla；每种组合使用独立世界，RS2、AE2 与存储 profile 分开运行。
默认 Core 套件使用 `folkways_core` 命名空间；可选套件使用同一命名空间和独立 fixture source set。
未适配的模组容器必须实现原版 `Container` 并加入 `folkways_core:store/allowed` 方块标签才能登记为仓库。

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
文档只保留概述层面：README、贡献指南、模组联动说明和存档兼容规则，不再添加详细参考页面或单个模组的 README。
`THIRD_PARTY.md` 是许可证清单，仅保留英文。
