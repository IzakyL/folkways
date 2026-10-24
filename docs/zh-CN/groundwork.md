# 整地：采集与倒木

[文档](README.md) · [English](../en/groundwork.md)

## 采集野生食物

开启**采集**并关闭**清空**，农夫就会把圈定的食物植物采回殖民地容器。
采集默认关闭。生长阶段二、三的甜浆果灌木会调用原版空手交互，采后回到阶段一；
洞穴藤蔓的头部和茎部会调用原版浆果采收，保留藤蔓方块。未成熟植物保持不动。
整地仍是一次性工作，完成发现的目标后结束，不会等待未成熟植物或下一轮再生。

同时开启时，**清空优先**，沿用现有拆除流程移走植物。除草本身不采浆果，
开启采集后也不会拔掉可再生采集植物。其他已开启的工作仍照常执行，
例如砍掉支撑植物的树或填充植物所在的地面。
殖民地已登记的方块和选定的填充材料受保护，动工前还会复查。
未登记的玩家植物与自然生成植物没有来源记录，无法区分。

小型红蘑菇、棕蘑菇、南瓜和西瓜会整株采下，按方块本身的战利品和工具规则收集，不补种。
不包含雕刻过的南瓜、巨型蘑菇、普通原版农田作物或树叶。
两格高的标签野生植物从圈定的下半株采集，上半株也会一并占用；
先在下半株计算一次掉落，再移除支撑。上半株受保护时不会采集。
方块更新即时产生的其他掉落也会一并收进产物。

数据包通过 `#folkways:forage_pick` 选择一次性植物，
通过 `#folkways:forage_harvest` 选择可再生植物。
Pick 中属于 `CropBlock` 的作物必须完全成熟；Harvest 还需要已知的生长流程，
即两项原版浆果规则，或所属集成登记的 `CropBehavior.ripe/gather`。
没有对应规则的 Harvest 条目会被跳过，不会拆掉。
规划会为食物和繁殖材料预留三个背包格。
世界修改均在 `Node.commit` 中执行，交互生成的掉落由已有的 `ItemDrops`
捕获后交给 `Outcome.Done` 入库。

### 已核对的模组与方块 ID

于 2026-10-24 核对发布的 NeoForge 1.21.1 模组文件、战利品表和方块定义。
可选 ID 均为 `required: false`，没有新增依赖或测试配置。
可选测试实际加载森罗厨房；其余模组仅核对文件，通用采下流程由同时掉落食物和种子的测试植物验证。

| 模组及核对版本                                                                                                           | 支持的植物与行为                                                                                                                                                                                                                                                                                            |
| ------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [农夫乐事 1.3.4](https://modrinth.com/mod/farmers-delight/version/1.21.1-1.3.4)                                          | 采下 `farmersdelight:wild_cabbages`、`wild_onions`、`wild_tomatoes`、`wild_carrots`、`wild_potatoes`、`wild_beetroots`、`wild_rice`。野生稻米包含上下两格，并保留原处的水。                                                                                                                                 |
| [森罗厨房 1.6.0](https://modrinth.com/mod/kaleidoscope-cookery/version/1.6.0-neoforge%2Bmc1.21.1)                        | 由所属 Folkways 集成把 `kaleidoscope_cookery:tea_tree`、`tomato_crop`、`chili_crop` 加入 Harvest，复用已有采收规则，分别回到阶段三、五、五。它们是普通作物方块；这个版本没有独立野生作物方块。`kaleidoscope_cookery:wild_rice` 是从草中获取的种子物品，已有集成的采种区域处理；栽培稻米和生菜仍由农田处理。 |
| [Pam's HarvestCraft 2 Crops 1.0.9](https://www.curseforge.com/minecraft/mc-mods/pams-harvestcraft-2-crops/files/8064883) | 采下 `pamhc2crops:aridgarden`、`frostgarden`、`shadedgarden`、`soggygarden`、`tropicalgarden`、`windygarden`。同时核对了 Modrinth 发布的 1.0.0 文件，六个 ID 一致；未加入栽培用的 `pam...crop` 方块。                                                                                                       |
| [Supplementaries 4.0.2](https://modrinth.com/mod/supplementaries/version/rWq0ko29)                                       | 采下 `supplementaries:wild_flax` 获取其种子。亚麻本身不是食物，但种子适合作为可采集的繁殖材料；不包含栽培用的 `supplementaries:flax`。                                                                                                                                                                      |
| [Croptopia 4.2.4](https://www.curseforge.com/minecraft/mc-mods/croptopia/files/7958876)                                  | 确有 NeoForge 1.21.1 版本，例如 `croptopia:blueberry_crop`、`blackberry_crop`、`corn_crop`；`#croptopia:crops` 还包含 `apple_crop` 等树上果实。本次未加入：其交互会修改玩家统计和消耗值、重置作物并生成掉落，果树叶还有另一套流程，需要独立集成，不能直接拆掉可再生作物或猜测重置规则。                     |

旧整地区域缺少新配置项时，显式迁移为 `forage=false`，保留已有目标集合。
主动开启采集时，与其他设置变化一样重新发现区域目标。没有其他存档格式变化。

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
圈定边界和搜索上限。采集测试还覆盖浆果成熟度与再生、两种洞穴藤蔓、关闭设置、清空优先、
两格植物、方块保护与替换、旧存档配置以及食物和种子实际入库。
`./gradlew -PcookeryTests=true runGameTestServer` 另验证森罗厨房再生规则。运行 `./gradlew runGameTestServer` 验证组合套件。
