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

[安装](#安装) · [快速上手](#快速上手) · [可选集成](#可选集成) ·
[文档](docs/zh-CN/README.md) · [参与贡献](CONTRIBUTING.zh-CN.md)

## 玩法特色

- **用方块定义殖民地。** 用殖民书登记箱子、床和工作站；围起一片土地不会自动认领其中的方块。
- **安排工种，由居民规划任务。** 在“居民分工”页设置优先级，殖民地负责选择任务、物资和执行顺序。
- **划出工作区域。** 圈出农田、牧场和渔场，选择作物、牲畜目标数量，或绘制巡逻路线。
- **维持容器库存。** 指定物品和目标数量，居民会通过制作、烧炼、收获或搬运补足库存。
- **把多余种子变成骨粉。** 登记原版堆肥桶，在“堆肥设置”中管理自动堆肥和种子保留量。
- **照着样式建造。** 道路、拱桥、城墙、码头、矿场、铁路、拱廊和梯田均有内置样式，居民会清理现场并使用殖民地库存施工。
- **照顾有自己生活的居民。** 居民睡在自己的床上，随身带三天口粮，会因工作和行走疲劳，并躲避危险。数据包可以替换殖民地的姓名与外观池。
- **在实践中成长。** 每个工种有十级，每升一级抽取一个特长；3、6、10 级还会抽取通用特长。
- **摆好餐桌，盖好住房。** 用餐区供应餐桌饭，床会评定所在房间的等级，心情让工作速度在 75% 到 115% 之间变化。空闲的居民会散步、聊天、抚摸动物；殖民地发展起来后，猫、狼和鹦鹉会来附近安家。

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

| Jar                                                         | 安装条件                                         |
| ----------------------------------------------------------- | ------------------------------------------------ |
| Folkways Core（`folkways-core-*.jar`）                      | 必装：殖民地引擎、殖民书和管理面板               |
| Folkways Vanilla（`folkways-vanilla-*.jar`）                | 必装：居民及其在原版方块中的工作                 |
| Folkways × Create（`folkways-create-*.jar`）                | 配合机械动力：列车、包裹、轨道和蓝图             |
| Folkways × Modular Golems（`folkways-modulargolems-*.jar`） | 配合模块化傀儡：让傀儡成为居民                   |
| Folkways × Kaleidoscope Cookery（`folkways-cookery-*.jar`） | 配合森罗厨房 1.6.0：作物、原生厨具和共享厨房工具 |

Core 已内置 Ponder 和 Flywheel；Vanilla 已内置 Starlark 解释器，无需单独安装这三个组件。

## 快速上手

1. 用一本书合成**殖民书**。
2. 右键打开面板，选择**新建殖民地**。
3. 手持殖民书，左键登记箱子、床和工作站。
4. 在已登记的箱子中准备食物，并留出空床。新人会陆续出现，从**人类居民**页允许他们入住。
5. 潜行并滚动鼠标滚轮，将殖民书切到**圈地**模式；左键点击地块的两个对角，再右键选择**农田**。

面板的**教程**页提供 Ponder 演示。成员管理、容器规则、特长、饮食、用餐、住房、堆肥、建筑和外观设置见[玩法指南](docs/zh-CN/gameplay.md)。

## 可选集成

| 模组                                                                                                                                   | 集成内容                                                                                                            |
| -------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| [Create / 机械动力](https://modrinth.com/mod/create) 6.0+，配合 Folkways × Create                                                      | 按蓝图施工、驾驶列车、乘坐电梯、铺设轨道，以及通过仓储管理器的包裹网络下单。未安装扩展时，Core 仍会处理绕行机械装置 |
| [Sable](https://modrinth.com/mod/sable)                                                                                                | 居民可以进出子世界结构，例如 Create Aeronautics 的飞船                                                              |
| [Modular Golems / 模块化傀儡](https://modrinth.com/mod/modular-golems) 3.1.43+，配合 Folkways × Modular Golems                         | 傀儡加入居民名单，像人类居民一样承担工种                                                                            |
| [Kaleidoscope Cookery / 森罗厨房](https://github.com/KaleidoscopeMods/KaleidoscopeCookery) 1.6.0，配合 Folkways × Kaleidoscope Cookery | 水田种植、食材处理、原生厨具、共享工具、厨房置物架、割草采种，以及十三节教程                                        |
| [Kaleidoscope Tavern / 森罗酒馆](https://github.com/KaleidoscopeMods/KaleidoscopeTavern) 1.2.0，配合 Cookery 扩展                      | 搭棚种葡萄，榨汁、发酵、装瓶酿酒，居民为用餐区吧台补货并在那里喝酒；一节教程                                        |
| [GeckoLib](https://modrinth.com/mod/geckolib) 4.9+                                                                                     | 使用基岩版格式的居民模型                                                                                            |
| [Touhou Little Maid / 车万女仆](https://modrinth.com/mod/touhou-little-maid) 1.5+，需 GeckoLib                                         | 将内置及已安装扩展包中的模型用作居民外观，可逐个或按包选择                                                          |
| [Shoulder Surfing Reloaded](https://modrinth.com/mod/shoulder-surfing-reloaded) 5.2.0+                                                 | 殖民书沿第三人称相机的准星瞄准                                                                                      |
| [JEI](https://modrinth.com/mod/jei) / [EMI](https://modrinth.com/mod/emi)                                                              | 在容器和物品筛选中使用配方查看器的物品列表                                                                          |
| [FTB Teams](https://www.curseforge.com/minecraft/mc-mods/ftb-teams-forge) 2101.1+                                                      | 创始人可将殖民地交给队伍；队员共同管理，队长持有创始人权限                                                          |
| [Just Enough Characters（JEC）](https://www.curseforge.com/minecraft/mc-mods/just-enough-characters) 4.5+                              | 在物品选择器、目录选择器、居民分工和库存页使用全拼、首字母与混合输入搜索；仅需客户端安装                            |

JEC 搜索沿用其键盘方案和模糊音设置。输入示例，以及物品搜索与已保存筛选规则的区别，见[搜索](docs/zh-CN/gameplay.md#搜索)。
车万女仆扩展模型包的安装方法见[女仆模型包](docs/zh-CN/gameplay.md#女仆模型包)。

## 文档

- [玩法指南](docs/zh-CN/gameplay.md)：成员、工作、特长、饮食、用餐、住房、娱乐、容器、堆肥和居民外观。
- [自定义内容](docs/zh-CN/custom-content.md)：样式、蓝图、皮肤、模型和服务器配置。
- [测试指南](docs/zh-CN/testing.md)：本地检查、游戏测试、Cookery 测试环境和 CI。
- [架构说明](docs/zh-CN/architecture.md)：模组边界、公共 API、源码位置和生命周期。

## 从源码构建

准备 **Java 21 JDK** 和 **Node.js 22.14+**，在 **Linux x64** 上从仓库根目录执行锁定的构建流程：

```sh
npm ci
npm run setup
./gradlew build
```

Setup 会下载锁定版本的集成依赖，并生成 Gradle 必需的清单。
五个模组 jar 汇总到 `build/libs/`。
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
它们仅用于宣传视频，不随模组分发，详见[模型说明](tests/promo/models/README.md)。

[badge-license]: https://img.shields.io/github/license/IzakyL/folkways?style=flat-square&label=license&color=22c55e
[badge-last-commit]: https://img.shields.io/github/last-commit/IzakyL/folkways/master?style=flat-square&logo=github&logoColor=white&label=last%20commit&color=0ea5e9
[badge-last-release]: https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fapi.github.com%2Frepos%2FIzakyL%2Ffolkways%2Freleases%3Fper_page%3D100&search=%22tag_name%22%5Cs*%3A%5Cs*%22v%3F%5B0-9%5D%2B%5C.%5B0-9%5D%2B%28%3F%3A%5C.%5B0-9%5D%2B%29%3F%28%3F%3A-%28%3F%3Aalpha%7Cbeta%7Crc%29%5B%5E%22%5D*%29%3F%28%3F%3A%5C%2B%5B%5E%22%5D*%29%3F%22%5B%5Cs%5CS%5D*%3F%22published_at%22%5Cs*%3A%5Cs*%22%28%5B0-9%5D%7B4%7D-%5B0-9%5D%7B2%7D-%5B0-9%5D%7B2%7D%29&replace=%241&flags=i&label=last+release&color=0ea5e9&style=flat-square&logo=github&logoColor=white
[badge-discord]: https://img.shields.io/badge/Discord-join-5865F2?style=flat-square&logo=discord&logoColor=white
[badge-kook]: https://img.shields.io/badge/KOOK-join-34D399?style=flat-square
