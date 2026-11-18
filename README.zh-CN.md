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
- **照顾有自己生活的居民。** 居民睡在自己的床上，随身带三天口粮，会因工作和行走疲劳，并躲避危险。数据包可以替换殖民地的姓名与外观池；数据包、标签和 `folkways/` 资源库文件夹见 [DATAPACKS.zh-CN.md](DATAPACKS.zh-CN.md)。
- **在实践中成长。** 每个工种有十级，每升一级抽取一个特长；3、6、10 级还会抽取通用特长。
- **摆好餐桌，盖好住房。** 用餐区供应餐桌饭，床会评定所在房间的等级，心情让工作速度在 75% 到 115% 之间变化。空闲的居民会散步、聊天、抚摸动物；殖民地发展起来后，猫、狼和鹦鹉会来附近安家。
- **配装并守卫殖民地。** 为居民分配包含武器、护甲、补给和背包的装备方案，再为巡逻路线和哨点安排人手，或直接指挥居民和魔像；TACZ 和卓越前线扩展提供受支持的枪械。
- **保护并修复建筑。** 划定损害管制区域，让居民扑灭火焰；记录基准并开启修复后，他们会使用殖民地库存补回缺失的建筑方块。

## 模组一览

本仓库构建十个模组，`mods/` 下每个目录对应一个，产物为 `folkways-<目录名>-*.jar`。所有扩展都需要 Core 和 Vanilla。

| 模组                                      | 目录                 | 额外依赖                                                                              | 作用                                                                   |
| ----------------------------------------- | -------------------- | ------------------------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| Folkways Core                             | `mods/core`          | —                                                                                     | 殖民地引擎：自行规划工作的居民、寻路、殖民书和管理面板，以及适用于任意居民种类的直接指挥和编队；本身不提供工作 |
| Folkways Vanilla                          | `mods/vanilla`       | Core                                                                                  | 居民在原版方块中的工种：种植、畜牧、捕鱼、制作、搬运和建造；武器、装备方案和巡逻 |
| Folkways Carving                          | `mods/carving` | Chisels & Bits 21.1.25+                                                     | 居民使用整块建造和改造雕刻，有少量取整损耗                   |
| Folkways × Create | `mods/create` | 机械动力 6.0.7+；消防附加 0.2.3-beta+ 可选（其需要 Create [6.0.10,6.1)） | 列车、包裹、轨道和蓝图施工；装有消防附加时使用灭火器和消防栓水带并补液 |
| Folkways × Modular Golems                 | `mods/golems` | 模块化傀儡 3.1.6+                                                                    | 傀儡加入居民名单，像人类居民一样承担工种                               |
| Folkways × Kaleidoscope Cookery           | `mods/cookery`       | 森罗厨房 1.6.0+；可选森罗酒馆 1.2.0+                                                    | 居民用原生厨具种植、处理、烹饪和储存食物；装有酒馆时还会酿酒、供酒     |
| Folkways Firearms | `mods/firearms` | 可选：TACZ 1.1.8+（含 1.2.0）；卓越前线 [0.8.9.2,0.8.9.3) 及 Kotlin for Forge [5.12.0,6) | 装有相应原生模组时，居民使用实体弹药操作枪械和火炮阵地 |
| Folkways × AstikorCarts Redux | `mods/carts` | AstikorCarts Redux 1.2.2+ | 搬运居民套上手推车或运输车，把仓库间的货物装在车厢里，与背包分开 |
| Folkways × Epic Fight                     | `mods/epicfight`（jar：`folkways-epicfight`） | Epic Fight 21.17.1+；Core 和 Vanilla 须来自同一次 Folkways 构建 | 居民使用 Epic Fight 原生近战、受击动作和日常移动动画，并以 GEO 模型渲染 |
| Folkways Airline | `mods/airline` | Folkways × Create；机械动力 [6.0.10,6.1)；Sable 2.0.6 | 机场图纸、勘测、塔台、跑道、直升机坪、滑行道和停机位，机场可建在 Sable 结构上随之移动 |

升级到 Carving 时，先删除旧的 `folkways-chiselsandbits-*.jar`，再安装 `folkways-carving-*.jar`；
新旧同时安装会重复注册并崩溃。

Folkways Airline 提供机场图纸、勘测、塔台与移动着陆设施。
RTS 飞机收到移动或盘旋指令时自行起飞，按所选高度档（贴地、巡航、高空）保持在前方地形之上，默认降落到已保存的归属机场；机场自动分配适用的空闲位置。
跑道与滑行道图纸支持任意水平方向。跑道编号按着陆航向四舍五入到十度（正北为 36）；
平行跑道使用 L/C/R 标识，另一端的左右标识互换。

跑道由连续白色边线与两端钢琴键式入口标志界定，也支持斜向；图纸另绘制长虚线中线和成对瞄准点。
停机坪为连续灰色道面，黄色引导线末端的三格白色停止线标识机位；道面边界和相邻机位净空决定停放尺寸。
相连道面无需黄色中线即可滑行；路径优先靠近引导线并保持居中，按沿途最小边界净空限制机型翼展，无法到达的机位或跑道会报告缺陷。
直升机坪用白色边框与细长白色 H（宽约为高的六成）识别，可选黄色接地圆圈。各类设施均可位于连续道面中，无需草缝。

机场预订让 Superb Warfare 战斗出动（含弹射起飞）与降落按申请先后排队。未安装 Airline 时，Firearms 使用本地跑道队列。预订不写入存档。

卓越前线载具在乘员离乘后仍归属原殖民地，自动释放空闲乘员也保留归属。
载具停稳后，可在指令卡点击 **放弃归属**，让乘员离乘并放弃载具。未归属载具仅在玩家客户端正在追踪时显示并可接管；
已归属殖民地的载具只要仍已加载，就可在任意距离指挥。已锁定的载具不能接管或指挥。

## 安装

从上游的 [nightly 构建](https://github.com/IzakyL/folkways/releases/tag/nightly)下载预发布文件。
[正式版本](https://github.com/IzakyL/folkways/releases?q=-tag%3Anightly)与 nightly 分开列出。

| 运行条件                                 | 版本                          |
| ---------------------------------------- | ----------------------------- |
| Minecraft                                | 1.21.1                        |
| [NeoForge](https://neoforged.net/)       | 21.1.233 或更新版本           |
| [LDLib2](https://modrinth.com/mod/ldlib) | 2.2.37 或更新版本，需单独安装 |

**Core 和 Vanilla 必装**，其他扩展按需安装。**所有 Folkways jar 必须来自同一次构建。**

各个 jar 的作用和依赖见[模组一览](#模组一览)。
Firearms 仅在装有 TACZ 或卓越前线时启用相应集成，Create 仅在装有消防附加时启用消防集成。
装有这些原生模组时，版本要求为：TACZ 1.1.8+、卓越前线 [0.8.9.2,0.8.9.3)、消防附加 0.2.3-beta+。
直接指挥和编队属于 Core。

Core 已内置 Ponder 和 Flywheel；Vanilla 已内置 Starlark 解释器，无需单独安装这三个组件。

## 快速上手

1. 用一本书合成**殖民书**。
2. 右键打开面板，选择**新建殖民地**。
3. 手持殖民书，左键登记箱子、床和工作站。
4. 在已登记的箱子中准备食物，并留出空床。新人会陆续出现，从**人类居民**页允许他们入住。
5. 潜行并滚动鼠标滚轮，将殖民书切到**圈地**模式；左键点击地块的两个对角，再右键选择**农田**。

面板的**教程**页提供 Ponder 演示。

使用战斗功能时，打开**装备方案**页，创建方案、填好栏位并分配人类居民；把选定的武器、护甲和匹配弹药放入允许取出的已登记仓库。
启用**巡逻**工种并为巡逻路线或哨点安排人手，即可自主守卫。需要直接指挥时，手持殖民书按 **V**，
切换到 **RTS** 模式，在其中选中居民、编组并下达命令。拆分或重组编队后，武器规则仍随居民保留；
射程限制决定居民开火前需要接近到多远。

使用损害管制时，手持殖民书按 **V**，选择**损管**，标记两个角点并保护区域，再允许居民从事**损害管制**工作。
修复默认关闭：右键区域，点击**重制基准**并开启**补回缺失方块**。
容器、作物、流体和机器不计入基准；已被其他方块占据的位置会报告为冲突。

### 排查居民行为

在**设置**页开启**调试日志**后，任务中断和失败会即时出现在聊天栏，规划状态每 10 秒汇报一次。
开启期间，注视（32 格内）一名居民或一个已登记方块，屏幕右侧会显示调试卡片，按住 Alt 滚动滚轮可翻看：

- **居民**：当前状态与工作阶段、路线上的后续工作及其位置、下一项工作为何还没开始、冲动及其冷却、携带物品与预留，以及近期事件。
  世界中会用编号连线标出它接下来要去的地点。
- **已登记方块**：容器的记录状态、殖民地记录与实际内容的差异、这里的预留，以及在此安排的工作和负责的居民。
  世界中会连线到每个负责的居民。

附近已登记的仓库会按殖民地记录的新旧加上细框：绿色为已读取，黄色为待更新，灰色为未读取，蓝色为未加载，红色为不支持；
角上的红色标记表示记录与实际内容不一致。调试信息只有开启者能看到，退出服务器后自动关闭。
附近的路点图每秒刷新：象牙白小框为普通路点，亮粉小框为居民落脚点，靛紫小框和线为 Crossing 端点及跨越连接；
冰白线为步行，深青线为其他移动方式，荧光黄绿虚线为试用期，莓红线为失败边（试用期内为虚线，可重试后为实线）。
小箭头表示方向；显示范围为 48 格，超过 2000 个点或 4000 条边时会提示截断。

## 可选集成

Folkways 还可与约三十个模组配合使用，涵盖机械动力与 Create Aeronautics、烹饪、枪械与战斗、仓储网络、饰品以及客户端工具。
各联动的内容、游戏内用法和版本要求见[模组联动](INTEGRATIONS.zh-CN.md)。

## 从源码构建

准备 **Java 21 JDK** 和 **Node.js 22.14+**，在 **Linux x64** 或 **Windows 11 x64**（使用 `gradlew.bat`）上从仓库根目录执行锁定的构建流程：

```sh
npm ci
npm run setup
./gradlew build
```

Setup 会下载锁定版本的集成依赖，并生成 Gradle 必需的清单。
十个模组 jar 汇总到 `build/libs/`。
Blockwright 测试 SDK 对 Windows 的支持仍处于候选阶段，详见[开发环境](CONTRIBUTING.zh-CN.md#开发环境)。

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
