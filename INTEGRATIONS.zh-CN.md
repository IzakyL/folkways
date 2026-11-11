# 模组联动

[English](INTEGRATIONS.md) · 简体中文

Folkways 可以与不少模组配合使用。本页介绍各联动为殖民地带来什么、如何开始使用以及需要注意的地方。
请先安装 **Core 和 Vanilla**；所有 Folkways jar 须来自同一次构建。除标注“仅客户端”的项目外，服务器和每个客户端都需要安装。

## 一览

| 模组 | Folkways jar | 联动内容 |
|---|---|---|
| [机械动力 Create](https://modrinth.com/mod/create) 6.0.7+ | Folkways × Create | 蓝图施工、列车、电梯、铺设铁路、通过物流网络订货 |
| [机械动力：消防附加](https://modrinth.com/mod/create-firefighting-additions) 0.2.3-beta+ | Folkways × Create | 损害管制使用灭火器和消防栓水带 |
| [Sable](https://modrinth.com/mod/sable) | Core | 居民在 Create Aeronautics 飞船等子世界结构上生活和工作 |
| [AstikorCarts Redux](https://modrinth.com/mod/astikorcarts-redux) 1.2.2+ | Folkways × AstikorCarts Redux | 车站之间的马车线路 |
| [Chisels & Bits](https://www.curseforge.com/minecraft/mc-mods/chisels-bits) 21.1.25+ | Folkways Carving | 雕刻建筑与体素细节样式 |
| [森罗厨房 Kaleidoscope Cookery](https://github.com/KaleidoscopeMods/KaleidoscopeCookery) 1.6.0+ | Folkways × Kaleidoscope Cookery | 完整运作的殖民地厨房、水田和采种 |
| [森罗酒馆 Kaleidoscope Tavern](https://github.com/KaleidoscopeMods/KaleidoscopeTavern) 1.2.0+ | Folkways × Kaleidoscope Cookery | 葡萄园、酿酒与吧台 |
| [模块化傀儡 Modular Golems](https://modrinth.com/mod/modular-golems) 3.1.6+ | Folkways × Modular Golems | 傀儡作为劳动力加入殖民地 |
| [TACZ](https://modrinth.com/mod/timeless-and-classics-zero) 1.1.8+（含 1.2.0） | Folkways Firearms | 居民使用实体弹药射击 |
| [卓越前线 Superb Warfare](https://modrinth.com/mod/superb-warfare) 0.8.9.2+ 及 Kotlin for Forge 5.x | Folkways Firearms | 居民使用枪械并操作火炮阵地 |
| [Epic Fight](https://modrinth.com/mod/epic-fight) 21.17.1+ | Folkways × Epic Fight | Epic Fight 原生近战、受击反应与动画 |
| [GeckoLib](https://modrinth.com/mod/geckolib) 4.7.5+ | Vanilla，仅客户端 | 基岩版格式的居民模型 |
| [车万女仆 Touhou Little Maid](https://modrinth.com/mod/touhou-little-maid) 1.5+ | Vanilla | 女仆模型用作居民外观 |
| [FTB Teams](https://www.curseforge.com/minecraft/mc-mods/ftb-teams-forge) 2101.1+ | Core | 把殖民地交给队伍 |
| [JEI](https://modrinth.com/mod/jei) / [EMI](https://modrinth.com/mod/emi) | Core，仅客户端 | 把物品拖进 Folkways 的筛选格 |
| [Just Enough Characters（JEC）](https://www.curseforge.com/minecraft/mc-mods/just-enough-characters) 4.5+ | Core，仅客户端 | 拼音搜索 |
| [Shoulder Surfing Reloaded](https://modrinth.com/mod/shoulder-surfing-reloaded) 5.0.1+ | Core，仅客户端 | 殖民书沿其准星瞄准 |
| [AppleSkin](https://modrinth.com/mod/appleskin) 3.0+ | Core，仅客户端 | 俯瞰时隐藏其叠加层 |
| [Iris](https://modrinth.com/mod/iris) | Core，仅客户端 | 区域和路线标记不投射光影阴影 |

下文提到殖民书的 **点选**、**圈地**、**画线** 三种模式，潜行并滚动滚轮即可切换。提到的 **教程** 都在殖民地面板的 **教程** 页。

## 建造与运输

### 机械动力

即使不装扩展，Core 也会让居民绕开运动中的机械装置。安装 Folkways × Create 后：

- **蓝图施工。** 用机械动力的工具摆放蓝图，另一只手拿着殖民书，选择 **交给殖民地**。
  可以选择保留或挖掉蓝图空格处原有的方块。建造者会像对待普通建造单一样施工，创造模式下也不会瞬间完成。
- **列车。** 副手持殖民书，用列车时刻表右键一列尚无时刻表的列车的驾驶座。拥有 **驾驶** 工种的居民负责开车，
  其他居民可作为乘客搭乘。列车至少需要两个站点，每站对应一个具名车站，等待条件只能是时间类。空手右键驾驶座即可收回列车。
- **电梯。** 建好有动力的机械动力电梯后，以点选模式左键滑轮。居民会走进轿厢、乘坐并走出。
  载客量取决于空出的地板面积，各层落脚处须平整无遮挡。
- **铺设铁路。** 画线并选择 **铁路** 路线。居民会平整路基、开凿隧道、架桥过河，并铺设真正互相连通的机械动力轨道。
  线路须为直线或 45° 斜线，坡度平缓，转弯不超过 90°。
- **通过物流网络订货。** 以点选模式左键仓储管理器或库存链接以接管网络，左键青蛙港或邮箱用于收发货。
  拥有 **调度** 工种的居民坐在仓储管理器旁下单。**调度** 页会列出妨碍网络使用的问题。

教程：铁路、调度、电梯，以及把机械动力蓝图交给殖民地。

### 机械动力：消防附加

损害管制居民优先使用备有水带、喷头和水的消防栓柜，否则借用灭火器。灭火器可从区域内的水源、流体储罐或殖民地库存中的水补充。
在损害管制区域设置中开启 **领用消防工具并补液** 即可。只使用水。

### Sable

居民可以走上走下飞船等子世界结构，结构移动时也能继续在上面工作，已登记的方块会随飞船一起移动。
只有当飞船平稳停靠、与坚实的岸边齐平时才能上下船。

### AstikorCarts Redux

在殖民地中画线并选择 **马车线路**。每个点都是一个车站，最多 16 个。选择车型，以及 **往返** 或 **环线** 行驶方式。
殖民地会制作或取用一辆车，放在第一站。搬运居民拉手推车；运输车需要从牧场调用一匹驯服的成年马。
车辆在每站停靠，装卸殖民地的配送货物。线路需要宽敞的入口和转角，不能有跳跃、落差或攀爬。马车不会随居民搭乘列车或电梯。

教程：马车线路：车站与往返。

### Chisels & Bits（Folkways Carving）

建造者用整块材料雕出造型，会因取整损失少量材料。自带样式包括鸟居、穹顶圆厅、哥特回廊、飞檐亭、石拱桥、收分城防和曲顶长廊。
可在 **蓝图** 页把选定区域保存为蓝图或模板。自定义样式可声明 `detail = "bits"`，以体素分辨率绘制。

> Folkways Carving 取代了旧的 Folkways × Chisels & Bits。安装 `folkways-carving-*.jar` 前请删除
> `folkways-chiselsandbits-*.jar`，两者同时存在会导致游戏崩溃。

## 饮食

### 森罗厨房

把厨具登记为 **厨房工作站**，在 **厨房** 页查看状态。只需为想要的成品菜下订单，拥有 **制作** 工种的居民会规划中间所有步骤，
从磨面到烹饪。居民还会耕种水田、割草采种、堆肥余料，并共用厨具和农夫装备，用完即还。
热源须点燃并遮雨；炒菜放太久会烧焦。

教程：**厨房** 目录下共十三节。

### 森罗酒馆

需要同时安装 Cookery 扩展。居民会在葡萄地上搭棚架，然后榨汁、发酵并装瓶酿酒。
在用餐区内登记吧台并选择 **吧台饮品**，居民会补货，用餐者会在那里喝酒。酒桶顶部需要能走上去，库存中还需备有桶和空瓶。

教程：酒馆：从葡萄园到一杯葡萄酒。

## 居民与防御

### 模块化傀儡

以点选模式左键自己拥有的傀儡即可接纳，再次点击则释放。傀儡按种类承担工种：金属傀儡负责搬运、建造和巡逻；
犬型傀儡负责搬运和巡逻；人形傀儡还能制作、驾驶列车和调度。傀儡不吃不睡，技能不会成长，会用背包里的修复材料自行修补。

教程：傀儡：让你的傀儡为殖民地干活。

### TACZ 与卓越前线（Folkways Firearms）

在 **配装** 中添加受支持的枪械，分配给居民，并在殖民地库存中备好匹配的弹药。然后安排巡逻路线或哨位，或直接指挥居民。
居民只从背包装填实体弹药，按枪械本身的节奏射击，不会误伤本殖民地的居民和宠物。TACZ 退出的弹药会放回背包。

- **TACZ：** 支持默认的 `tacz:` 枪械；不支持爆炸弹、燃烧弹和无限弹药。服务器可在 `folkways_firearms-server.toml` 中调整允许的枪械和射程。
- **卓越前线：** 支持 Glock 17、MP5、AK-47、M4、M870 和 SVD；火炮阵地支持 MK-42、BL-132、MLE-1934、FH-77BW、迫击炮和 HPJ-11，
  使用普通高爆炮弹或迫击炮弹。点选火炮阵地并选择 **独立武器站**，或将其放在哨位范围内。

### Epic Fight

手持 Epic Fight 可识别的近战武器的居民会使用其原生攻击、受击反应和硬直，以及 Epic Fight 的待机、行走、进食和睡眠动作。
安装 GeckoLib 时，基岩版模型会套用 Epic Fight 的骨架渲染。配装和巡逻照常使用。居民不会使用 Epic Fight 的闪避、举盾或空手招式。

### GeckoLib 与车万女仆

GeckoLib 让居民可以在 **模型池** 页使用基岩版格式模型；未安装时这些外观会退回为皮肤。
安装车万女仆后，内置女仆和已安装的模型包会出现在模型池中，可逐个或按包选用；客户端同样需要 GeckoLib。

## 殖民地管理与界面

- **FTB Teams：** 创始人可在 **成员** 页选择 **交由 FTB 队伍管理**。之后成员由队伍管理，队长持有创始人权限，并可收回殖民地。
- **JEI / EMI：** 把配方查看器中的物品拖进 Folkways 的筛选格和选择格。
- **Just Enough Characters：** 在物品与目录选择器、居民分工和库存页支持全拼、首字母与混合输入搜索，沿用 JEC 自身设置。
- **Shoulder Surfing Reloaded：** 殖民书沿第三人称准星瞄准。
- **AppleSkin：** 俯瞰时，其叠加层随生命条和饥饿条一起隐藏。
- **Iris：** 使用光影包时，Folkways 的区域与路线标记不会投射阴影。
