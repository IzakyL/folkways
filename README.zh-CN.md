# Folkways

[![License][badge-license]](LICENSE)
[![Last commit][badge-last-commit]](https://github.com/IzakyL/folkways/releases/tag/nightly)
[![Last release][badge-last-release]](https://github.com/IzakyL/folkways/releases?q=-tag%3Anightly)
[![Discord][badge-discord]](https://discord.gg/n5T4x3mk9q)
[![KOOK][badge-kook]](https://kook.vip/5RTovu)

[English](README.md) · 简体中文

Folkways 是面向 **Minecraft 1.21.1 / NeoForge** 的殖民地模组。
建立殖民地、登记方块和工作区域，再为居民设置工种优先级；殖民地会规划取料、生产、搬运和建造。

**预发布阶段：** 存档、数据包格式与 Java API 仍可能随版本变化。体验 nightly 构建前，请备份世界。

[模组一览](#模组一览) · [安装](#安装) · [快速上手](#快速上手) · [可选集成](#可选集成) · [参与贡献](CONTRIBUTING.zh-CN.md)

## 玩法特色

- **用方块定义殖民地。** 用殖民书登记箱子、床和工作站；围起一片土地不会自动认领其中的方块。
- **安排工种，由居民规划任务。** 在“居民分工”页设置优先级，殖民地负责选择任务、物资和执行顺序。
- **划出工作区域。** 圈出农田、牧场和渔场，选择作物、牲畜目标数量，或绘制巡逻路线。
- **维持容器库存。** 指定物品和目标数量，居民会通过制作、烧炼、收获或搬运补足库存。
  订单送货不受容器黑白名单和“允许放入”限制；这些设置只约束居民的日常存放，有订单时也可随时修改，玩家手动放入不受影响。
- **把多余种子变成骨粉。** 登记原版堆肥桶，在“堆肥设置”中管理自动堆肥和种子保留量。
- **照着样式建造。** 道路、拱桥、城墙、码头、矿场、铁路、拱廊和梯田均有内置样式，居民会清理现场并使用殖民地库存施工。
  开启 `blueprints.simpleConstruction`（默认 `false`）后，居民进入工地即停下，就地完成施工。
- **照顾有自己生活的居民。** 居民睡在自己的床上，随身带三天口粮，会因工作和行走疲劳，并躲避危险。数据包可以替换殖民地的姓名与外观池。
- **在实践中成长。** 每个工种有十级，每升一级抽取一个特长；3、6、10 级还会抽取通用特长。
- **摆好餐桌，盖好住房。** 用餐区供应餐桌饭，床会评定所在房间的等级，心情让工作速度在 75% 到 115% 之间变化。空闲的居民会散步、聊天、抚摸动物；殖民地发展起来后，猫、狼和鹦鹉会来附近安家。
- **配装并守卫殖民地。** 为居民分配包含武器、护甲和补给的配装方案，再为巡逻路线和哨点安排人手，或直接指挥居民和魔像；TACZ 和卓越前线扩展提供受支持的枪械。
- **保护并修复建筑。** 划定损害管制区域，让居民扑灭火焰；记录基准并开启修复后，他们会使用殖民地库存补回缺失的建筑方块。

## 模组一览

本仓库构建九个模组，`mods/` 下每个目录对应一个，产物为 `folkways-<目录名>-*.jar`。所有扩展都需要 Core 和 Vanilla。

| 模组                                      | 目录                 | 额外依赖                                                                              | 作用                                                                   |
| ----------------------------------------- | -------------------- | ------------------------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| Folkways Core                             | `mods/core`          | —                                                                                     | 殖民地引擎：自行规划工作的居民、寻路、殖民书和管理面板，以及适用于任意居民种类的直接指挥和编队；本身不提供工作 |
| Folkways Vanilla                          | `mods/vanilla`       | Core                                                                                  | 居民在原版方块中的工种：种植、畜牧、捕鱼、制作、搬运和建造；武器、配装和巡逻 |
| Folkways Carving                          | `mods/carving` | Chisels & Bits 21.1.25+                                                     | 居民使用整块建造和改造雕刻，有少量取整损耗                   |
| Folkways × Create | `mods/create` | 机械动力 6.0.7+；消防附加 0.2.3-beta+ 可选（其需要 Create [6.0.10,6.1)） | 列车、包裹、轨道和蓝图施工；装有消防附加时使用灭火器和消防栓水带并补液 |
| Folkways × Modular Golems                 | `mods/golems` | 模块化傀儡 3.1.6+                                                                    | 傀儡加入居民名单，像人类居民一样承担工种                               |
| Folkways × Kaleidoscope Cookery           | `mods/cookery`       | 森罗厨房 1.6.0+；可选森罗酒馆 1.2.0+                                                    | 居民用原生厨具种植、处理、烹饪和储存食物；装有酒馆时还会酿酒、供酒     |
| Folkways Firearms | `mods/firearms` | 可选：TACZ 1.1.8+（含 1.2.0）；卓越前线 0.8.9.2+ 及 Kotlin for Forge [5.12.0,6) | 装有相应原生模组时，居民使用实体弹药操作枪械和火炮阵地 |
| Folkways × AstikorCarts Redux | `mods/carts` | AstikorCarts Redux 1.2.2+ | 搬运居民套上手推车或运输车，把仓库间的货物装在车厢里，与背包分开 |
| Folkways × Epic Fight                     | `mods/epicfight`（jar：`folkways-epicfight`） | Epic Fight 21.17.1+；Core 和 Vanilla 须来自同一次 Folkways 构建 | 居民使用 Epic Fight 原生近战、受击动作和日常移动动画，并以 GEO 模型渲染 |

升级到 Carving 时，先删除旧的 `folkways-chiselsandbits-*.jar`，再安装 `folkways-carving-*.jar`；
新旧同时安装会重复注册并崩溃。

## 安装

从上游的 [nightly 构建](https://github.com/IzakyL/folkways/releases/tag/nightly)下载预发布文件。
[Alpha 及以上版本](https://github.com/IzakyL/folkways/releases?q=-tag%3Anightly)与 nightly 分开列出。
首次发布带版本号的构建之前，Last release 徽章没有可显示的对应日期。

| 运行条件                                 | 版本                          |
| ---------------------------------------- | ----------------------------- |
| Minecraft                                | 1.21.1                        |
| [NeoForge](https://neoforged.net/)       | 21.1.233 或更新版本           |
| [LDLib2](https://modrinth.com/mod/ldlib) | 2.2.37 或更新版本，需单独安装 |

**Core 和 Vanilla 必装**，其他扩展按需安装。**所有 Folkways jar 必须来自同一次构建。**

各个 jar 的作用和依赖见[模组一览](#模组一览)。
Firearms 仅在装有 TACZ 或卓越前线时启用相应集成，Create 仅在装有消防附加时启用消防集成。
装有这些原生模组时，版本要求为：TACZ 1.1.8+、卓越前线 0.8.9.2+、消防附加 0.2.3-beta+。
直接指挥和编队属于 Core。

Core 已内置 Ponder 和 Flywheel；Vanilla 已内置 Starlark 解释器，无需单独安装这三个组件。

## 快速上手

1. 用一本书合成**殖民书**。
2. 右键打开面板，选择**新建殖民地**。
3. 手持殖民书，左键登记箱子、床和工作站。
4. 在已登记的箱子中准备食物，并留出空床。新人会陆续出现，从**人类居民**页允许他们入住。
5. 潜行并滚动鼠标滚轮，将殖民书切到**圈地**模式；左键点击地块的两个对角，再右键选择**农田**。

面板的**教程**页提供 Ponder 演示。

使用战斗功能时，打开**配装**页，创建方案并分配人类居民；把选定的武器、护甲和匹配弹药放入允许取出的已登记仓库。
启用**巡逻**工种并为巡逻路线或哨点安排人手，即可自主守卫；也可以选中居民直接指挥。

使用损害管制时，手持殖民书按 **V**，选择**损管**，标记两个角点并保护区域，再允许居民从事**损害管制**工作。
修复默认关闭：右键区域，点击**重制基准**并开启**补回缺失方块**。
容器、作物、流体和机器不计入基准；已被其他方块占据的位置会报告为冲突。

## 可选集成

Folkways 还可与机械动力及其消防附加、Sable、AstikorCarts Redux、Chisels & Bits、森罗厨房与森罗酒馆、
模块化傀儡、TACZ、卓越前线、Epic Fight、GeckoLib、车万女仆、FTB Teams、JEI/EMI、Just Enough Characters、
Shoulder Surfing Reloaded、AppleSkin 和 Iris 配合使用。各联动的内容、游戏内用法和版本要求见[模组联动](INTEGRATIONS.zh-CN.md)。

## 从源码构建

准备 **Java 21 JDK** 和 **Node.js 22.14+**，在 **Linux x64** 上从仓库根目录执行锁定的构建流程：

```sh
npm ci
npm run setup
./gradlew build
```

Setup 会下载锁定版本的集成依赖，并生成 Gradle 必需的清单。
九个模组 jar 汇总到 `build/libs/`。
Blockwright 源码已支持 Windows，当前锁定的 npm 发布包早于这次改动。
Windows 下可使用从源码构建的 SDK 和 `gradlew.bat`，详见[开发环境](CONTRIBUTING.zh-CN.md#开发环境)。

开发与 PR 流程见[贡献指南](CONTRIBUTING.zh-CN.md)。
请在上游[报告问题](https://github.com/IzakyL/folkways/issues/new?template=bug_report.yml)或[提出功能建议](https://github.com/IzakyL/folkways/issues/new?template=feature_request.yml)，
也可以加入 [Discord](https://discord.gg/n5T4x3mk9q) 或 [KOOK](https://kook.vip/5RTovu) 参与社区讨论。

## 许可证

Folkways 使用 [MIT 许可证](LICENSE)。

内置的 [Starlark](https://github.com/bazelbuild/starlark) 使用 Apache-2.0；
[Ponder](https://github.com/Creators-of-Create/Ponder) 和 [Flywheel](https://github.com/Engine-Room/Flywheel) 使用 MIT。
对应许可证文本随 jar 放在 `META-INF/licenses/` 下。第三方组件与素材许可见 [THIRD_PARTY.md](THIRD_PARTY.md)。

`tests/promo/models/` 中按需准备的酒狐模型使用 **CC BY-NC-SA 4.0**，不属于 Folkways 的 MIT 授权范围；
它们仅用于宣传视频，不随模组分发，详见 [THIRD_PARTY.md](THIRD_PARTY.md)。

[badge-license]: https://img.shields.io/github/license/IzakyL/folkways?style=flat-square&label=license&color=22c55e
[badge-last-commit]: https://img.shields.io/github/last-commit/IzakyL/folkways/master?style=flat-square&logo=github&logoColor=white&label=last%20commit&color=0ea5e9
[badge-last-release]: https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fapi.github.com%2Frepos%2FIzakyL%2Ffolkways%2Freleases%3Fper_page%3D100&search=%22tag_name%22%5Cs*%3A%5Cs*%22v%3F%5B0-9%5D%2B%5C.%5B0-9%5D%2B%28%3F%3A%5C.%5B0-9%5D%2B%29%3F%28%3F%3A-%28%3F%3Aalpha%7Cbeta%7Crc%29%5B%5E%22%5D*%29%3F%28%3F%3A%5C%2B%5B%5E%22%5D*%29%3F%22%5B%5Cs%5CS%5D*%3F%22published_at%22%5Cs*%3A%5Cs*%22%28%5B0-9%5D%7B4%7D-%5B0-9%5D%7B2%7D-%5B0-9%5D%7B2%7D%29&replace=%241&flags=i&label=last+release&color=0ea5e9&style=flat-square&logo=github&logoColor=white
[badge-discord]: https://img.shields.io/badge/Discord-join-5865F2?style=flat-square&logo=discord&logoColor=white
[badge-kook]: https://img.shields.io/badge/KOOK-join-34D399?style=flat-square
