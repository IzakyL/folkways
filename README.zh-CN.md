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
- **安排工种，由居民规划任务。** 在**居民分工**页设置优先级，殖民地负责选择任务、物资和执行顺序。
- **划出工作区域。** 圈出农田、牧场和渔场，选择作物、牲畜目标数量，或绘制巡逻路线。
- **维持容器库存。** 指定物品和目标数量，居民会通过制作、烧炼、收获或搬运补足库存。
  订单送货不受容器黑白名单和“允许放入”限制；这些设置只约束居民的日常存放，有订单时也可随时修改，玩家手动放入不受影响。
  居民把日常物品存入普通仓库；机器的输入口和输出口等设施部件通过所属设施的工作收发物品。
  登记为仓库的 Tom’s Simple Storage 终端也能用**下单…**按钮下同样的订单，见 [INTEGRATIONS.zh-CN.md](INTEGRATIONS.zh-CN.md)。
- **把多余种子变成骨粉。** 登记原版堆肥桶，居民就能把多余种子变成骨粉。
- **照着样式建造。** 道路、拱桥、城墙、码头、矿场、铁路、拱廊和梯田均有内置样式，居民会清理现场并使用殖民地库存施工。农业、建造和损害管制等自动工作会跳过受保护的设施方块。「整地」的「清理」命令可以拆除这些方块，确认预览会列出受影响的设施。
  开启 `blueprints.simpleConstruction`（默认 `false`）后，居民进入工地即停下，就地完成施工。
- **照顾有自己生活的居民。** 居民睡在自己的床上，随身带三天口粮，会因工作和行走疲劳，也会逃离看得见的袭击者或烧伤等危险，优先跑向靠近殖民地设施和成员且能走到的地点。数据包可以替换殖民地的姓名与外观池；数据包、标签和 `folkways/` 资源库文件夹见 [DATAPACKS.zh-CN.md](DATAPACKS.zh-CN.md)。
- **在实践中成长。** 每个工种有十级，每升一级抽取一个特长；3、6、10 级还会抽取通用特长。
- **摆好餐桌，盖好住房。** 用餐区供应餐桌饭，床会评定所在房间的等级，心情让工作速度在 75% 到 115% 之间变化。空闲的居民会散步、聊天、抚摸动物；殖民地发展起来后，猫、狼和鹦鹉会来附近安家。
- **配装并守卫殖民地。** 为居民分配包含武器、护甲、补给和背包的装备方案，再为巡逻路线和哨点安排人手，或直接指挥居民和魔像；TACZ 和卓越前线扩展提供受支持的枪械。
- **保护并修复建筑。** 划定损害管制区域，让居民扑灭火焰；记录基准并开启修复后，他们会使用殖民地库存补回缺失的建筑方块。

## 模组一览

本仓库构建四个模组，`mods/` 下每个目录对应一个，产物为 `folkways-<目录名>-*.jar`。所有扩展都需要 Core；除助手外还需要 Vanilla。

| 模组                                      | 目录                 | 额外依赖                                                                              | 作用                                                                   |
| ----------------------------------------- | -------------------- | ------------------------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| Folkways Core                             | `mods/core`          | —                                                                                     | 殖民地引擎：自行规划工作的居民、寻路、殖民书和管理面板，以及适用于任意居民种类的直接指挥和编队；包含 AE2/RS2 的**网络服务**职业 |
| Folkways Vanilla                          | `mods/vanilla`       | Core                                                                                  | 居民在原版方块中的工种：种植、畜牧、捕鱼、制作、搬运和建造；武器、装备方案和巡逻 |
| Folkways Airline                          | `mods/airline`       | Core、Vanilla、机械动力 6.0.10–6.0.x、Sable 2.0.6                                     | 机场图纸、勘测、塔台、跑道、直升机坪、滑行道和停机位，机场可建在 Sable 结构上随之移动 |
| Folkways Assistant                        | `mods/assistant`     | Core；客户端和服务器都需安装                                                          | 游戏内聊天助手，通过你配置的语言模型读取殖民地并回答问题               |

Assistant 提供工具注册表，通过 Core 的殖民地和设施 API 读取数据、预览并确认变更。在助手设置中配置模型，并按提供商的要求填写 API 密钥。支持的提供商见[聚落助手联动说明](INTEGRATIONS.zh-CN.md#聚落助手)。修改设施定义、设施参数或殖民地脚本始终需要确认。

AE2 和 RS2 的双向联动（包括**网络服务**职业）都由 Core 提供。模块化傀儡、Epic Fight 和 Chisels & Bits 的联动也随 Core 提供。森罗厨房、森罗酒馆和 AstikorCarts Redux 的适配也由 Core 提供，Vanilla 在相应联动开启时加载厨房、酿酒和马车线路玩法。版本见[联动说明](INTEGRATIONS.zh-CN.md)。
升级时移除旧的 `folkways-golems-*.jar`、`folkways-epicfight-*.jar`、`folkways-carving-*.jar` 、`folkways-chiselsandbits-*.jar`、`folkways-cookery-*.jar`、`folkways-carts-*.jar`、`folkways-firearms-*.jar` 和 `folkways-create-*.jar`。

Airline 的跑道标线、停机位和预订见[模组联动 · 机场](INTEGRATIONS.zh-CN.md#机场folkways-airline)；
指挥飞机和殖民地载具归属见[TACZ 与卓越前线](INTEGRATIONS.zh-CN.md#tacz-与卓越前线core-与-vanilla)。

## 安装

从上游的 [nightly 构建](https://github.com/IzakyL/folkways/releases/tag/nightly)下载预发布文件。
[正式版本](https://github.com/IzakyL/folkways/releases?q=-tag%3Anightly)与 nightly 分开列出。

| 运行条件                                 | 版本                          |
| ---------------------------------------- | ----------------------------- |
| Minecraft                                | 1.21.1                        |
| [NeoForge](https://neoforged.net/)       | 21.1.233 或更新版本           |
| [LDLib2](https://modrinth.com/mod/ldlib) | 2.2.37 或更新版本，需单独安装 |

**Core 和 Vanilla 必装**，Airline 和助手按需安装。**所有 Folkways jar 必须来自同一次构建。**

各个 jar 的作用和依赖见[模组一览](#模组一览)。
Core 和 Vanilla 只在装有[模组联动](INTEGRATIONS.zh-CN.md)所列受支持版本时启用 TACZ、卓越前线和机械动力集成，
装有机械动力时还可启用消防附加集成。直接指挥和编队属于 Core。

Core 已内置 Ponder、Flywheel 和 Starlark 解释器，无需单独安装这三个组件。

## 快速上手

1. 用一本书合成**殖民书**。
2. 右键打开面板，选择**新建殖民地**。
3. 手持殖民书左键点击箱子、床和工作站即可登记。新书默认处于点选手势。
4. 生存模式下，在已登记的箱子中准备食物，并留出空床。新人会陆续出现，在**居民分工**页点击**接纳一人**即可让一位新人入住。
   创造模式下，点击此按钮可立即接纳一位新人，无需等候队列、空床或存粮。
5. 潜行并滚动鼠标滚轮，把书切换到下一种手势：点选、圈地或画线。圈出地块的两个对角后右键并选择**农田**，农田的卡片随即打开供你设置。
   手持殖民书右键已纳入的区域、路径或方块，即可打开其卡片。
   在俯瞰视角（**V**）中，殖民书的工具按用途分组排在屏幕顶部，例如**农牧与食物**、**防御**；先选好工具，画出的形状就会成为它。

**概览**显示需要处理的问题，**居民分工**用于查看居民和调整工作优先级，**库存与订单**显示物资和订单。
**设施**按区域、路线等分组，所辖方块则按类型与之并列，并像**教程**一样折叠，点按一行即可打开卡片；**设置**提供殖民地设置卡片，**成员**用于管理成员，**教程**提供 Ponder 演示。

要启用自主守卫，开启**巡逻**工种，并在巡逻路线或哨点的卡片上安排人手。把武器、护甲和匹配弹药放入允许取出的已登记仓库。需要直接指挥时，手持殖民书按 **V**，
切换到**控制**模式，在其中选中居民、编组并下达命令。拆分或重组编队后，武器规则仍随居民保留；
射程限制决定居民开火前需要接近到多远。
同一殖民地的实体与成员玩家之间免疫伤害。自动射击会避开殖民地实体和方块，玩家选定的射击目标跳过瞄准预测。
爆炸遵循原版或所装模组的规则。

**聚落助手**按世界或服务器以及玩家将会话保存到当前客户端。
打开「历史会话」可以继续之前的会话，也可以请助手查阅旧会话的文本。
助手默认模型可以看图：它会从附近视角截图，并在把建造草案或设施更改交给你之前，先从几个方向拍摄检查。
使用纯文本模型时，在「设置」中关闭「模型支持图片输入」。
助手按对象将更改分组，并在对话旁的审阅窗口中逐组展示。窗口会说明当前组的更改，并提供「定位」「确认」和「拒绝」。
你可以分别决定每组更改；相互依赖的更改会放在同一组。
在你作出决定前，更改会在世界中标出：所涉居民周围的轮廓、所画各点之间的连线、建造图样的方块，
或设施的新范围及其旁淡显的旧范围，增减的格子会着色。
创造模式下，建造的审阅窗口还会像放置蓝图时一样，提供「直接建成」或「交给殖民地建造」。
已加载的聚落载具（例如已有驾驶员的卓越前线载具、已牵引的推车和机械动力列车）也是设施，助手可以把它们派往目的地；
在**控制**模式中选中载具即可使用其命令卡。

请**聚落助手**指定玩家自建的设施，例如由拉杆操纵的铁门。对于机械，例如由阀门手轮升起的门，
助手会先读出它的连接方式，并在不移动机械的情况下试验各个操作位，弄清该转哪一个、朝哪个方向转。
能把物品加工成别的物品的机械也能设成工坊：居民会把订单需要的原料送过去，再把产物带回来。
在**设施**中打开设施卡片，也可在**控制**模式中选中设施。
用卡片运行规程、修改参数、查看读数或开关定时触发。卡片命令也可以要求选取区域：在世界中点击两个对角。能移动的设施扫掠所选方框时会自行挪位。设施范围受到保护。大门的开启条件满足时，居民可以步行通过；关闭时，大门会阻断路线。

使用损害管制时，用殖民书标记两个角点后右键，选择**损害管制**保护区域，再允许居民从事**损害管制**工作。
修复默认关闭：在区域的卡片上点击**重置基准**并开启**补回缺失方块**。
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

安装机械动力后，可用**不记名打字机**将多个**控制终端**交由一个居民岗位操作。

## 从源码构建

准备 **Java 21 JDK** 和 **Node.js 22.14+**，在 **Linux x64** 或 **Windows 11 x64**（使用 `gradlew.bat`）上从仓库根目录执行锁定的构建流程：

```sh
npm ci
npm run setup
./gradlew build
```

Setup 会下载锁定版本的集成依赖，并生成 Gradle 必需的清单。
四个模组 jar 汇总到 `build/libs/`。
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
