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
| [Sophisticated Storage](https://modrinth.com/mod/sophisticated-storage) 1.6.17+ | Core | 使用主库存，遵守原生过滤与堆叠容量；带合成、切石升级的容器纳入仓库后，也可供 Vanilla 的打包合成工作使用。铁砧升级若存在仅算可用，不提供铁砧工作；危险加工、消耗、销毁和未知升级会使容器不可使用 |
| [Curios](https://modrinth.com/mod/curios) 9.3.1+ / [Accessories](https://modrinth.com/mod/accessories) 1.1.0-beta.54+ | Core | 同步玩家实际槽位和数量，遵守原生限制，真实穿戴并获得效果；兼容层只呈现一套 Accessories 库存 |
| [Sophisticated Backpacks](https://modrinth.com/mod/sophisticated-backpacks) 3.26.21+ | Core | 使用放置背包的主库存与原生过滤；带合成、切石、锻造升级的容器也可作为 Vanilla 工作站，含 Inception 生效内层升级。铁砧升级仅算可用；危险升级使容器不可使用。库存与穿戴背包遵循玩家的原生生效规则，其真实库存、已装升级和唱片供居民搬运、工作和移动播放使用 |
| [Storage Drawers](https://modrinth.com/mod/storagedrawers) 13.11.1+ | Core | 每个抽屉独立计库存，控制器及控制器 IO 提供额外访问点；遵守原生锁定与过滤，转换、生成、销毁仅禁用对应成员 |
| [Functional Storage](https://modrinth.com/mod/functional-storage) 1.5.7+ | Core | 每个抽屉独立计库存，控制器及扩展提供额外访问点、不重复计数；压缩、转换、生成、销毁仅禁用对应成员，纯物品 IO 升级可用 |
| [Create: Storage（ausmez 移植）](https://modrinth.com/mod/create-storage-neo-forge) 1.3.5+、Create 6.0.7+ | Core | 储物箱及放置背包的主库存可入册，危险升级会拒绝；控制器是独立简易储物箱的仅存入代理，自动化接口及传递器拒绝 |
| [Tom’s Simple Storage](https://modrinth.com/mod/toms-storage) 2.0.8–2.x | Core | 库存连接器、存储/合成终端及朝向连接器的线缆连接器作为代理；每个成员须通过正常仓库准入检查，过滤、库存代理及自动转运拒绝 |
| [Colossal Chests](https://modrinth.com/mod/colossal-chests) 1.9.1+、CyclopsCore 1.30.1+ | Core | 完整巨型箱子的核心、墙及接口合并为一个仓库；未完成结构拒绝，微型箱子作为普通仓库 |
| [Mekanism QIO](https://modrinth.com/mod/mekanism) 10.7.0+ | Core | 仪表盘通过 `network_access/take`、`put` 标签访问同一维度的频率/驱动器网络；可用空间保守遵守每块盘的物品数及种类上限。驱动器槽与自动化设备不提供物品存取，本集成不提供 QIO 合成 |
| [Applied Energistics 2](https://modrinth.com/mod/ae2) 19.0.20-beta–19.x | Core | 用殖民书逐台纳入 ME 终端、合成终端或 ME 箱子；同一网络库存只计一份；`folkways_core:network_access/take`、`folkways_core:network_access/put`、`folkways_core:network_access/craft` 方块标签规定设备最多能做什么，再由设备的“网络设备”页面收窄（见下文）。居民到设备处下单网络合成并领取产物。ME 接口不作为仓库。网络也可调用殖民地工坊配方，由居民领取已接收的材料、加工并交付产物；须先开启默认关闭的“网络服务”职业。终端按钮按玩家保存，可隐藏仅殖民地提供的可合成条目，不影响合成计算。已接收的工作随殖民地存档保存，网络订单取消后仍继续完成。 |
| [Refined Storage 2](https://modrinth.com/mod/refined-storage) 2.0.9–2.x | Core | 用殖民书逐台纳入 Grid、Crafting Grid、Pattern Grid 或已配置物品的存储监视器；同网库存只计一份，与 AE2 共用 `folkways_core:network_access/take`、`folkways_core:network_access/put`、`folkways_core:network_access/craft` 标签，同样由“网络设备”页面收窄。居民到设备处下单网络合成并领取产物；接口和磁盘接口不作为仓库。网络也可调用殖民地工坊配方，由居民领取已接收的材料、加工并交付产物；须先开启默认关闭的“网络服务”职业。终端按钮按玩家保存，可隐藏仅殖民地提供的可合成条目，不影响合成计算。已接收的工作随殖民地存档保存，网络订单取消后仍继续完成。 |
| [GeckoLib](https://modrinth.com/mod/geckolib) 4.7.5+ | Vanilla，仅客户端 | 基岩版格式的居民模型 |
| [车万女仆 Touhou Little Maid](https://modrinth.com/mod/touhou-little-maid) 1.5+ | Vanilla | 女仆模型用作居民外观 |
| [FTB Teams](https://www.curseforge.com/minecraft/mc-mods/ftb-teams-forge) 2101.1+ | Core | 把殖民地交给队伍 |
| [JEI](https://modrinth.com/mod/jei) / [EMI](https://modrinth.com/mod/emi) | Core，仅客户端 | 把物品拖进 Folkways 的筛选格 |
| [Just Enough Characters（JEC）](https://www.curseforge.com/minecraft/mc-mods/just-enough-characters) 4.5+ | Core，仅客户端 | 拼音搜索 |
| [Shoulder Surfing Reloaded](https://modrinth.com/mod/shoulder-surfing-reloaded) 5.0.1+ | Core，仅客户端 | 殖民书沿其准星瞄准 |
| [AppleSkin](https://modrinth.com/mod/appleskin) 3.0+ | Core，仅客户端 | 俯瞰时隐藏其叠加层 |
| [Iris](https://modrinth.com/mod/iris) | Core，仅客户端 | 区域和路线标记不投射光影阴影 |

仓储联动面向 Minecraft 1.21.1 NeoForge。Sophisticated Storage 与 Backpacks 还需
Sophisticated Core 1.5.33+；Functional Storage 需 Titanium 4.0.52+。Functional Storage 向加载器
声明的完整版本为 `1.21.1-1.5.7`，包含 Minecraft 前缀。AE2、Refined Storage 与 Tom’s 的终端或
accessor mixin 依赖内部类，而下一个大版本没有可供验证的 1.21.1 NeoForge 发布，因此保留大版本上限；
其他仓储联动不设上限。

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

## 仓储与随身装备

### 仓库准入

仓储默认接受原版容器和显式集成。未适配的模组容器必须实现原版 `Container` 并加入方块标签
`folkways_core:store/allowed` 才能登记；`folkways_core:store/refused` 优先于允许标签及所有显式集成。
默认拒绝 Create 物品保险库（居民无法徒手存取）和 Lootr 容器（内容物因玩家而异）。
升级后被拒绝的已登记仓库仍保留登记，框线及面板显示原因；居民停止使用且库存不计入规划，不删除物品或登记。

### 控制器与访问点

Storage Drawers 控制器及控制器 IO、Functional Storage 控制器及扩展、Sophisticated Storage 控制器及存储 IO
都是各独立成员仓库的额外访问点，自身没有库存。登记访问点会原子登记所有成员；任何成员被拒绝或被占用时，全部不登记。
移除访问点会移除其成员；单独移除一个成员也会取消访问点登记，其余成员保留。新接入的可用成员自动登记，拒绝的新增成员跳过。
居民可以在成员或已登记访问点搬运，每个成员的库存只计算一次。曾关联但已断开的成员仍保持登记，退出访问点时一并移除。

### 网络配送

居民可以经同一可用仓库的两个已登记访问点完成配送：在货源旁存入，到目的地旁取出，再继续搬运。
网络内部传送按瞬时计算；只有两段搬运合计比直接搬运更省时，规划器才选择此路线。
在途物品占用共享仓储空间，直到取出完成，并遵守仓库的取出、允许存入、物品过滤及殖民地保留规则。
玩家或自动化消耗了在途物品时，居民会按当前库存重新规划。AE2 已分区或安装升级卡的存储元件不承诺规划空间，
种类上限保守计算；RS2 遵守原生磁盘及驱动器过滤。执行时仍以原生模拟插入为准。

AE2 或 RS2 原生合成任务接收的存入物品视为已交付，即使网络可见库存没有增加。
殖民地已接收的熔炼工作在重载后继续，不会重复装入原料或燃料。领取材料前，原居民无法继续服务时会改派其他合格居民。
工作无法继续或配方被移除时，会释放预约、取消委托该批工作的原生任务，并将缓冲材料退回网络；
网络已满或断开时，材料仍保留在缓冲区，待可以退回时再处理。

### 网络设备页面

用殖民书点击已登记的 AE2 或 RS2 设备，即打开它的“网络设备”页面，布局与仓库自己的页面相同。方块标签仍是上限：
设备种类做不到的开关不会显示。标签允许的项目默认全部开启，筛选默认为空黑名单，已有存档的行为不变。

- **存放**、**取出**：居民能否在此存入或取出物品。
- **允许居民下单**：居民能否在此设备提交网络自动合成。
- **允许指挥居民制作**：殖民地配方能否经此设备提供给网络。同一网络的设备全部关闭时，殖民地提供者不会接入。
- **物品筛选**：白名单或黑名单，同时限制存放、取出、下单，以及经此设备提供的殖民地配方（只要有一台允许指挥的
  设备接受配方的全部产物，该配方即提供给网络）。
- **整个网络**：殖民地自己的订单同时最多占用几个合成 CPU。默认是网络 CPU 数的一半，因此只有一个 CPU 的网络留给玩家；
  超出的订单排队等待或改用殖民地工坊，设为仅限玩家的 CPU 从不使用。Refined Storage 没有 CPU，也不限制任务数；
  殖民地本就每个网络同时只运行一个自己的任务，因此该项只决定允许这一个任务（默认）还是不允许。

网络本身没有可保存的身份，因此 CPU 配额写在该网络每台已登记设备上，读取时取其中最小值：网络合并时采用更严格的配额，
网络分裂后两部分各自保留。设置随殖民地存档保存，并在服务端执行转移、下单及提供配方时再次检查。
殖民地面板中的“网络设备”页面按网络列出所有已登记设备。

### 装备方案与随身装备

居民死亡或随被拆除的殖民地离开时，原生饰品槽和外观槽内的真实物品只掉落一次；穿戴背包的内部物品仍保存在掉落的背包内。
居民搬运时保留堆叠升级提供的完整数量；规划读取已捕获的槽位容量，之后更换装备不会使正在求解的规划访问失效槽位。


小队在 **RTS** 模式下编组和指挥：手持殖民书按 **V**，再切换到 **RTS**。
装备通过殖民地面板的**装备方案**页分配。

每名居民至多使用一个**装备方案**。具名槽位表示真实穿戴位置：头、胸、腿、脚，以及饰品框架提供的槽位。
不具名的库存条目逐项设置个人补给、弹药和武器的目标数量与补给线。工作借用主手，战斗会话从合并库存取用武器；
双手不再是方案栏位。“骑马”开关沿用巡逻规则领取牧场马匹并自动补给必需的鞍，马铠是开启骑马后才可设置的子选项。
关闭骑马后沿用巡逻逻辑归还马匹装备。不会因某件物品没有合适位置而自动占用另一个栏位。

库存条目复用 `PackKeeps`，按实际数量保护个人物品，不计入殖民地库存、不被卸货，其占用格不向货物规划承诺；
超出的数量仍是货物。具名装备和个人物品随时通过普通取货规划补给，不依赖巡逻。删除需求后，多余物品通过普通搬运归仓。

Core 可选集成 Curios **9.3.1+** 和 Accessories **1.1.0-beta.54+**。每次读取框架完成数据包重载或客户端同步后的
玩家实体类型实际槽位分配与数量，每个索引生成一个栏位，例如 `curios:ring/0`、`accessories:ring/0`。
两个真实框架共存时，即使槽位同名也保留两套。装有 Curios 兼容层（`cclayer` 或 Accessories Compatibility Layer）时，
只显示统一的 Accessories 槽位。旧 cclayer 取代 Curios jar，新兼容层则修改真实 Curios 的行为。
居民实体类型在框架自己的数据查询处使用相同的玩家分配，真实能力容器持有饰品并运行原生 tick、属性和效果。
移除框架后，方案条目保留，但不显示也不补给。

选择器使用原生槽位谓词；已分配居民加载时还按居民的装备规则过滤。服务端保存和穿戴时再次验证。
Curios 使用 `DynamicStackHandler.isItemValid`，包含槽位验证器、`ICurio.canEquip` 和装备事件；Accessories 使用
`AccessoriesAPI.canInsertIntoSlot`，包含槽位谓词、装备回调和 `Accessory.canEquip`。
没有加载居民时，只调用允许空实体的框架谓词，不猜测依赖实体的规则。饰品分组通过“饰品 ▾”下拉切换，并保留框架名称。

Sophisticated Backpacks **3.26.21+** 原生声明可穿在胸甲栏，也分别提供 Curios 和 Accessories 的 `back` 集成。
玩家和居民都能同时装备胸甲背包和饰品背包，遵守原生槽位的接纳规则与槽数；方案不额外限制只能穿一个。

居民身上显示穿戴装备的方式与其他玩家相同。护甲沿用原版护甲层。安装相应框架时，居民渲染器还会加上它们自己的玩家渲染层：
Sophisticated 的 `BackpackLayerRenderer`（胸甲栏背包）、Curios 的 `CuriosLayer` 和 Accessories 的 `AccessoriesRenderLayer`。
这些渲染层把每件已穿戴物品交给它在 `CuriosRendererRegistry` 或 `AccessoriesRendererRegistry` 中注册的渲染器，
其中包括 Sophisticated 的 back 槽背包渲染器。它们也会处理装饰栏物品和各槽位的显示开关。
Folkways 没有提供编辑这些开关的界面，所以开关保持默认的显示状态。与玩家一样，放在普通库存里的背包不会显示。
各框架会自行把容器同步给正在追踪该实体的客户端，所以 Folkways 不额外发包。居民渲染器对外提供当前绘制的人形姿态，
因此 `followBodyRotations` 一类的物品渲染器也会跟随居民的动作。GeckoLib 模型（YSM 与车万女仆外观）会把同样的渲染层
挂在躯干骨骼上（`body`、`upperBody` 或 `UpBody`），并用该骨骼中最大的立方体代替人形躯干。
挂在头部或四肢上的饰品按这个躯干推算的人形比例放置，所以在四肢偏长或偏短的模型上，位置可能略有偏差。
没有这类骨骼的模型不显示穿戴装备，正如它们也不显示胸甲。Epic Fight 只绘制它提供了对应版本的渲染层（护甲与手持物品），
因此在它驱动居民动画时，饰品和背包不会显示。

生效位置遵循所装版本的玩家规则（核对版本为 Backpacks 3.26.21.2249、Core 1.5.33.2470）。
`PlayerInventoryProvider.addPlayerInventoryHandler` 把新处理器放在最前面，基础顺序为 armor、offhand、main；
Curios/Accessories 处理器按注册的逆序排在它们前面。各处理器内部按原生标识符/容器遍历顺序，再按槽索引递增。
按键打开由 `BackpackOpenPayload.findAndOpenFirstBackpack` 取第一个，并不限制生效背包数。
居民合并库存、拾取候选及唱片选择沿用这个顺序：基础库存对应 main，胸甲对应 armor，副手对应 offhand，
饰品由真实框架能力容器提供。背包里面的背包不会递归成为基础库存根项。

默认 `nerfs.onlyWornBackpackTriggersUpgrades=false`，以上所有位置都参与，普通库存内的多个背包也生效，
没有“每人只 tick 前 N 个”的限制。启用后，`CommonEventHandler.onItemPickup` 调用
`runOnBackpacks(..., true)`，只让胸甲和饰品参与拾取，排除基础库存和副手。
tick 类升级的原生条件更窄：`BackpackItem.inventoryTick` 要求 `itemSlot <= -1`；NeoForge 的 `Inventory.tick`
给普通库存、护甲和副手传入正索引，Curios 的 `CuriosEventHandler.tick` 和 Accessories 的
`AccessoriesEventHandler.onLivingEntityTick` 传入 -1。因此这个版本开启该配置后，只有饰品背包的磁铁、喂食、
唱片机运行 tick；胸甲背包仍参与拾取事件。`MagnetUpgradeWrapper.tick` 与 `FeedingUpgradeWrapper.tick`
遵循这个 tick 条件；`PickupUpgradeWrapper.pickup` 与磁铁的 `pickup` 则由拾取事件调用。
禁用升级不提供能力；工作站升级不依赖 tick。

`CommonEventHandler.onWorldTick` 每 40 tick 检查重复 UUID，`tickDedupeLogicDisabled=true` 可关闭。
`UUIDDeduplicator.dedupeBackpackWrappers` 保留库存较大的背包（相同时保留先遇到的），移除重复者 UUID，
不是只选一个背包生效。居民复用这个原生操作，也检查当前维度内玩家与居民之间的重复。
`nerfs.tooManyBackpacksSlowness` 默认关闭；启用后超过 `maxNumberOfBackpacks`（默认 3）会按原生倍率、
80 tick 时长、最多十级施加配置效果，但所有背包仍可生效，不因这个数量拒绝装备方案。

所有生效背包各自加入真实库存，与居民原有 18 格（以及已有基础库存特性）合并；合并货物格是 Folkways 额外添加的规则。
每格过滤和每种物品的上限均来自原生处理器，取货、卸货、装载量和路线规划都遵守；堆叠升级改变每格上限，
不会生成额外的居民格，也没有随身容量倍率。

升级挂在具名槽位的背包下，唱片挂在唱片机升级下；嵌套编辑显示每个父项的已用/总槽数。
居民从仓库领取真实物品，通过 `IUpgradeItem.canAddUpgradeTo` 检查并写入 `UpgradeHandler`；
唱片写入唱片机 wrapper 的原生唱片库存。实际状态和能力从这些真实数据读取。
减少配置时先检查 `canRemoveUpgradeFrom`，包含移除堆叠升级后的库存上限检查；拒绝移除的升级保持安装状态。
成功取出的物品成为普通货物归仓；库存放不下时掉落，不能丢失。每次转移只按目标实际接收数扣减来源。
背包物品及其原生 storage UUID 是库存的唯一权威数据，Folkways 不另存一份影子库存。

实际安装的合成、切石、锻造升级提供居民自己的库存工作站；拾取、磁铁和喂食沿用现有行为。
禁用的升级不提供能力。唱片机从真实唱片库存读取歌曲，沿用移动音源、槽位顺序和持续循环播放；
更换方案、移除所需物品、死亡或卸载停止播放，声音状态不存档。
加工、销毁、压缩、套娃、工具切换、补货、存入及未知升级仍不能配置为子升级。

独立原生测试使用 `-PgameTestMod=core -PaccessoryProfile=curios` 或 `accessories`。
`-PaccessoryCoexistence=true` 验证两个真实框架共存；`-PaccessoryCompat=true` 在 Accessories 中加入旧 cclayer。
这些配置各用独立 source set，不加载基础 Core 测试。

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

把受支持的枪械放入**装备方案**的库存条目，分配给居民，并在殖民地库存中备好匹配的弹药。然后安排巡逻路线或哨位，或直接指挥居民。
居民只从背包装填实体弹药，按枪械本身的节奏射击，不会误伤本殖民地的居民和宠物。TACZ 退出的弹药会放回背包。

- **TACZ：** 支持默认的 `tacz:` 枪械；不支持爆炸弹、燃烧弹和无限弹药。服务器可在 `folkways_firearms-server.toml` 中调整允许的枪械和射程。
- **卓越前线：** 支持 Glock 17、MP5、AK-47、M4、M870 和 SVD；火炮阵地支持 MK-42、BL-132、MLE-1934、FH-77BW、迫击炮和 HPJ-11，
  使用普通高爆炮弹或迫击炮弹。点选火炮阵地并选择 **独立武器站**，或将其放在哨位范围内。

### Epic Fight

手持 Epic Fight 可识别的近战武器的居民会使用其原生攻击、受击反应和硬直，以及 Epic Fight 的待机、行走、进食和睡眠动作。
安装 GeckoLib 时，基岩版模型会套用 Epic Fight 的骨架渲染。装备方案和巡逻照常使用。居民不会使用 Epic Fight 的闪避、举盾或空手招式。

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
