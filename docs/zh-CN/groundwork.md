# 整地：采集与倒木

[文档](README.md) · [English](../en/groundwork.md)

## 采集野生食物

开启**采集**并关闭**清空**，农夫就会拆掉圈定的食物植物，
把方块正常掉落的物品运入殖民地容器。采集默认关闭。
整地用于清出土地：甜浆果灌木、洞穴藤蔓、森罗厨房作物和其他采集目标都会被破坏，
不再原地采收或补种。未成熟植物也会清走，按当前状态掉落；幼小的浆果灌木可能没有产物。
果实、种子和其他方块掉落只收集一次，不额外执行右键采收。

同时开启时，**清空优先**，沿用现有拆除流程。除草本身不采食物；
开启采集后，同一株植物只交给一项工作。其他已开启的工作仍照常执行，
例如砍掉支撑植物的树或填充植物所在的地面。
殖民地已登记的方块和选定的填充材料受保护，动工前还会复查。
未登记的玩家植物与自然生成植物没有来源记录，无法区分。

区域内相连的洞穴藤蔓头部和茎部会按竖列合成一项任务，没有发光浆果的部分也会清走。
从下往上拆，确保圈定的各段先收集掉落，再失去支撑；只把圈定范围内的藤蔓作为目标。
任何一段受保护时，整列都不会拆除。其他两格高的标签野生植物从圈定的下半株采集，
上半株也会一并占用；先在下半株计算正常掉落，再移除支撑。
上半株受保护时不会采集。方块更新即时产生的其他掉落也会收进产物。
包含小型红蘑菇、棕蘑菇、南瓜和西瓜，不包含雕刻过的南瓜、巨型蘑菇、普通原版农田作物或树叶。

数据包统一使用 `#folkways:forage`，文件为 `data/folkways/tags/block/forage.json`。
原有 `forage_pick.json` 和 `forage_harvest.json` 已删除，数据包的自定义条目需要移入新文件。
不再使用成熟度适配或原地采收规则。森罗厨房的可选方块 ID 已移入 Vanilla 标签，
与其他可选植物一起处理，不需要厨房专属采集接线。
产物由方块战利品和 `SuitableFor` 工具规则决定，规划会为食物和繁殖材料预留背包空间。
世界修改均在 `Node.commit` 中执行，邻居更新产生的掉落由已有的 `ItemDrops`
捕获后交给 `Outcome.Done` 入库。

整地仍是一次性任务。持久化的 `forage_version` 现为二；
读取原地采收版本的区域时，只重建采集目标，补上幼小灌木和无果藤蔓，
其他工作的完成记录保持不动。更早、尚无采集开关的区域仍迁移为 `forage=false`。
重新圈定或主动修改设置时，仍会像以前一样发现新目标。

### 已核对的模组与方块 ID

于 2026-10-24 核对发布的 NeoForge 1.21.1 模组文件、战利品表和方块定义。
可选 ID 均为 `required: false`，没有新增依赖或测试配置。
可选测试实际加载森罗厨房；其余模组仅核对文件，通用采下流程由同时掉落食物和种子的测试植物验证。

| 模组及核对版本                                                                                                           | 支持的植物与行为                                                                                                                                                                                                                                                              |
| ------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [农夫乐事 1.3.4](https://modrinth.com/mod/farmers-delight/version/1.21.1-1.3.4)                                          | 采下 `farmersdelight:wild_cabbages`、`wild_onions`、`wild_tomatoes`、`wild_carrots`、`wild_potatoes`、`wild_beetroots`、`wild_rice`。野生稻米包含上下两格，并保留原处的水。                                                                                                   |
| [森罗厨房 1.6.0](https://modrinth.com/mod/kaleidoscope-cookery/version/1.6.0-neoforge%2Bmc1.21.1)                        | 拆除 `kaleidoscope_cookery:tea_tree`、`tomato_crop`、`chili_crop`，成熟株和幼株都采集，收集正常的食物、茶叶和种子掉落，不留植株，不调用农田 `CropBehavior`。此版本没有独立野生作物方块；`kaleidoscope_cookery:wild_rice` 是从草中获取的种子物品。栽培稻米和生菜仍由农田处理。 |
| [Pam's HarvestCraft 2 Crops 1.0.9](https://www.curseforge.com/minecraft/mc-mods/pams-harvestcraft-2-crops/files/8064883) | 采下 `pamhc2crops:aridgarden`、`frostgarden`、`shadedgarden`、`soggygarden`、`tropicalgarden`、`windygarden`。同时核对了 Modrinth 发布的 1.0.0 文件，六个 ID 一致；未加入栽培用的 `pam...crop` 方块。                                                                         |
| [Supplementaries 4.0.2](https://modrinth.com/mod/supplementaries/version/rWq0ko29)                                       | 采下 `supplementaries:wild_flax` 获取其种子。亚麻本身不是食物，但种子适合作为可采集的繁殖材料；不包含栽培用的 `supplementaries:flax`。                                                                                                                                        |
| [Croptopia 4.2.4](https://www.curseforge.com/minecraft/mc-mods/croptopia/files/7958876)                                  | 确有 NeoForge 1.21.1 版本，例如 `croptopia:blueberry_crop`、`blackberry_crop`、`corn_crop`；`#croptopia:crops` 还包含 `apple_crop` 等树上果实。默认仍不加入这个混合标签；数据包可以把选定植物加入 `#folkways:forage`，按正常破坏流程采集，不调用玩家专属右键交互。            |

## 倒木

整地区域的**砍树**也会清理倒木。农夫会清走 `#minecraft:logs` 或
`#folkways:fallen_logs` 中横放在坚实地面上的方块，前提是相连木头中没有竖直原木，
周围也没有相连的树叶。只移除圈定的原木；紧贴其上方或侧面的苔藓地毯、藤蔓、蘑菇和蕨
会先被收集，即使这些小植物刚好在框外，也会随倒木一起清走。这样支撑消失时的方块更新
不会让掉落留在地上，产物会运入殖民地容器。工具沿用清空工作的 `SuitableFor` 路径。

检查会沿面、棱和角的连接搜索，最多访问 64 格原木。每格都必须横放且有支撑；
连接体过大或相邻区块未加载时会保留。殖民地已登记的方块、作为支撑的建筑方块以及
区域选定的填充材料会受保护，实际动工前还会复查。游戏没有记录这些原木是否由玩家放置，
所以未登记且直接铺在地上的木头也可能被视为倒木；悬空木梁和竖直木柱不算倒木。
碰到树木的倒木会保守地留给原有砍树流程。

**清空**原本就会拆掉框内所有可破坏方块，包括原木，同时保留殖民地已登记的方块、
液体、不可破坏方块和选定的填充材料。这个范围没有变化；现在会先收集倒木装饰，
再拆支撑。此前**砍树**会跳过没有树冠的木头，附着藤蔓也可能被误认成树冠。
站立树仍沿用整棵砍倒的处理方式。

数据包可在 `data/folkways/tags/block/fallen_logs.json` 中加入带 `axis` 属性的类原木方块，
在 `data/folkways/tags/block/fallen_log_decorations.json` 中加入附着的小植物。
Quark 空心原木条目均为 `required: false`，不依赖 Quark 类或模组文件，
不新增集成模块，也不改变存档格式。已记录的整地目标仍只发现一次；重新圈定旧区域后，
才能发现新增的倒木目标。

## Quark 调研

于 2026-10-24 核对 Modrinth 的 NeoForge 1.21.1 版本列表：
[Quark 4.1-487](https://modrinth.com/mod/quark/version/4.1-487) 发布于
2026-10-05，必需库 [Zeta](https://modrinth.com/mod/zeta/version/1.1-40)
对应的最新版本是 1.1-40。本次核对发布信息和源码，测试环境没有加载这两个模组，
因此没有在 `blockwright.toml` 中新增配置或固定依赖。

固定版本的
[FallenLogGenerator](https://github.com/VazkiiMods/Quark/blob/release-4.1-487%2B1.21.1/src/main/java/org/violetmoon/quark/content/world/gen/FallenLogGenerator.java)
会生成三格或四格长的直线木头，轴向为 `axis=x` 或 `axis=z`。
[默认配置](https://github.com/VazkiiMods/Quark/blob/release-4.1-487%2B1.21.1/src/main/java/org/violetmoon/quark/content/world/module/FallenLogsModule.java)
使用橡木、云杉、白桦、丛林、金合欢、深色橡木、红树和樱花的原版原木。
开启空心原木功能后，每排默认有 70% 的概率换成对应的 `quark:hollow_<wood>_log`。
顶部装饰是苔藓地毯，侧面和端部按气候生成苔藓地毯、藤蔓或蕨；该生成器不放蘑菇。
它也可能生成以水为支撑的木头；本次实现只处理有坚实地面支撑的倒木。

可选标签还列出 Quark 的 `quark:hollow_crimson_stem` 和 `quark:hollow_warped_stem`，
供数据包把空心菌柄用作倒木。
[HollowLogBlock](https://github.com/VazkiiMods/Quark/blob/release-4.1-487%2B1.21.1/src/main/java/org/violetmoon/quark/content/building/block/HollowLogBlock.java)
根据对应原木或菌柄命名，并保留柱状方块的轴向属性。

Java 版自身的倒木生成始于
[1.21.5](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-5)，
原版 1.21.1 没有这项生成。其他模组或数据包可能生成类似残木；
本功能根据标签和方块状态识别，不检查生成器来源。

## 回归测试

Vanilla GameTests 验证橡木倒木及苔藓进入殖民地木桶、清空时收集框外顶部苔藓，
以及只通过标签识别的柱状方块走完整规划和执行流程。测试资源单独把
`minecraft:hay_block` 加入 `#folkways:fallen_logs` 作为空心原木替身，正式内容不包含它。
其余测试覆盖站立树、对角连接、已登记方块、悬空木梁、动工前的变化、装饰、
圈定边界和搜索上限。采集测试还覆盖各阶段浆果灌木、含无果部分的整列藤蔓、关闭设置、清空优先、
两格植物、方块保护与替换、两项旧目标迁移以及食物和种子实际入库。
`./gradlew -PcookeryTests=true runGameTestServer` 另验证森罗厨房成熟株和幼株的拆除及食物、种子掉落。运行 `./gradlew runGameTestServer` 验证组合套件。
