# 存档兼容性

[English](SAVE_COMPATIBILITY.md) · 简体中文

## 版本

所有模组统一以 `0.MINOR.PATCH` 发布，起点为 `v0.1.0`。PATCH 是小更新（修复与细节调整），MINOR 是大更新
（新功能或新玩法）。存档格式由 `DataVersion` 单独记录，不决定版本号。不设 alpha/beta/rc 阶段，1.0.0 之前
不承诺扩展 API 稳定。客户端、服务器以及两端的 Folkways jar 必须来自同一构建。

`DataVersion.BASELINE = 1`、`DataVersion.CURRENT = 4` 是所有模组共享、单调递增的 Folkways 存档版本边界。版本 1 为 master `9a45a4d52` 的开发版格式，
版本 2 为 `v0.1.0`，版本 3 为各模组拆分独立命名空间。只有格式变化时才递增。

## 兼容承诺

- 每个正式版都能打开 `v0.1.0` 以来所有正式版的存档，并向前迁移。master `9a45a4d52` 是唯一受支持的预发布过渡版本；
  更早的开发版世界需先用该构建打开并保存一次。其他 nightly 不作承诺。
- 不支持降级。数据版本变化时，发布说明会注明。
- 升级期间请保留所有持有数据的模组；缺失模组的迁移代码无法升级其数据。
- 迁移只改写世界 NBT。命令、数据包、资源包、配置文件、Starlark 样式、居民模型包以及命令中传入的 NBT
  须使用当前 ID。导出的蓝图例外：导入时会自动规范化。

## 数据版本 3：模组命名空间

每个模组现在拥有自己的 mod id：`folkways_core`、`folkways_vanilla`、`folkways_create`、`folkways_cookery`、
`folkways_firearms`、`folkways_carts`、`folkways_carving`、`folkways_golems` 与 `folkways_epicfight`。
世界、物品、方块和物品栏均自动迁移。

- **Carving 取代了 Chisels & Bits 扩展。安装 `folkways-carving-*.jar` 时请移除 `folkways-chiselsandbits-*.jar`，
  两者同时加载会导致游戏崩溃。**
- 服务端配置在加载时自动改名：`folkways-tacz-server.toml` → `folkways_firearms-server.toml`，
  `folkways_chiselsandbits-server.toml` → `folkways_carving-server.toml`。新旧文件同时存在时以新文件为准，旧文件保留不动。
- Vanilla 的拒绝配方列表和 TACZ 的枪械/射程配置中的旧 ID 仍可使用，并只记录一次警告；TOML 文件本身不会被改写。

## 数据版本 4：仓储、网络设备与装备方案

版本 4 新增可选的 `folkways_core:store_verdicts` keeps 袋，保存仓库的持久不可使用判定。
袋内 `verdicts` 列表的每项为 WorldPos 与序列化 Component `reason`；缺少该袋表示没有判定。
旧存档没有需要重写的字段，因此不注册空的仓储迁移步骤，也不在升级时初始化该袋。
重新登记对应方块会清除判定；位置使用现有 keeps 重定位流程。版本 4 存档不支持降级。

未适配的模组容器必须实现原版 `Container` 并加入 `folkways_core:store/allowed` 标签才能登记；
`folkways_core:store/refused` 优先于所有显式集成。已有登记全部保留，包括 Create 保险库和 Lootr 容器，
显示不可用原因，库存不参与居民规划；不删除物品或登记。此前只靠通用物品处理器兜底登记的方块，
在删除该兜底后仍保留登记，并沿用既有的不受支持判定。

版本 4 还允许可选的 `folkways_core:store_proxies` keeps 数据袋。`proxies` 列表中每条记录包含 `at`（WorldPos）
和 `members`（曾通过该访问点登记的 WorldPos 列表）。缺少数据袋表示尚未记住关联。已有代理登记会在观察时发现可用成员，
已有不安全成员仍保留登记。成员继续使用普通登记记录。数据袋确保断网或重载后退出访问点时仍能移除曾关联的成员；
访问点和成员地址都遵循 keeps 的搬迁流程。本开发周期内无需重写已有字段或在迁移时初始化数据袋。

没有方块实体的原生仓库访问点（巨型箱墙以及被拒绝的自动化方块）沿用已有 `Claimed.Point` 表示，
owner 仍为普通仓库。既有方块声明保持原状；加载后的接受谓词通过可选候选标签校验实时原生仓库。
不新增字段、数据包、数据版本或迁移初始化。网络标识及 QIO 驱动器容量从世界实时解析，不持久化；
Tom’s 和 Create 的代理复用 `folkways_core:store_proxies`。

居民的 `folkways_core:body_state` 附件新增可选 `Kit`，其 `items` 列表保存不透明的完整 ItemStack。
缺省 `Kit` 表示空装备栏；普通 `Pack` 保持独立，随身装备不计殖民地库存或可规划格数。
普通背包 `Items[].Slot` 为支持超过 255 格而改用整数写入，读取端兼容原有无符号字节。失去容量后保留已有货物作为待卸货
溢出格，但不承诺额外空间。容量、能力、工作站发布、修订号和领取请求均重新计算，不持久化。
不会将背包物品内部库存或组件解码为装备配置，也不会修改它们；普通物品序列化仅原样保留组件身份。
这些属于当前开发周期版本 4 的增量字段，不注册空迁移或初始化步骤，仍不支持降级。

版本 4 将巡逻配装（`folkways_vanilla:patrol_loadouts`）替换为殖民地 keeps 包 `folkways_core:equipment`。
`profiles` 每项包含 UUID `id`、字符串 `name`、可选布尔 `riding`（缺省为 false）和 `entries`。
条目包含 UUID `id`、可选字符串 `slot`、可选 UUID `parent`、ItemSpec 复合标签 `items`、整数 `target` 与 `threshold`。
带 `slot` 的具名条目对应一件穿戴物品，两个数量均为 1；既无 `slot` 也无 `parent` 的条目是个人库存物品，保留目标数量和补给线。
子条目有 `parent` 而无 `slot`，其祖先根必须是具名穿戴槽位；只有这种树可以包含升级与唱片。
校验无环关系与原生子槽数量，删除父条目级联删除后代。`assignments` 保存 UUID `resident` / `profile` 配对，每名居民至多一个方案。
缺省 keeps 包表示没有方案。

身体栏位为 `folkways_core:head`、`chest`、`legs`、`feet`，武器改为库存条目。
骑马不再保存鞍条目；`riding: true` 按巡逻规则请求牧场马匹和必需的鞍。
可选的 `folkways_vanilla:horse_armor` 条目仅在开启骑马时生效。
饰品 ID 分别为 `curios:<类型>/<索引>`、`accessories:<类型>/<索引>`，索引从 0 起，单槽也保留 `/0`。
Accessories 类型中的原有 `/` 编码为 `//`，命名空间冒号编码为 `/`，如 `accessories:example/charm/0`，避免类型名碰撞。
兼容层只用 Accessories ID。框架缺失的槽位与无法解码的物品规格原样保留，但不显示、不补给。

穿戴背包的库存、已装升级和唱片由 Sophisticated 自己的物品数据及 storage UUID 保存；
合并的 `Pack` 视图不会再保存这些库存的副本。个人物品存于 `Pack` 或这些真实库存，沿用既有个人物品数量分配。
`KitCapabilities` 不再保存或计算容量倍率；堆叠升级改变原生格子上限。
原生限制可能使升级保持安装状态，直到内容允许移除。唱片机播放列表和声音状态仍只存在于运行时。

`folkways_core:body_state` 不再写 `Kit` 字段，也不维护独立的装备容器。
读取旧开发存档的 `Kit.items` 时，保留每个物品的全部组件，实体加入世界后放回其库存，剩余物品掉落在脚下。
若加入世界前再次保存，待回收物品暂存于 `Pack.Recovered`；旧扩大 Pack 中超出基础库存的格子也走相同回收路径。
这是未发布开发存档的防丢兼容处理，不注册迁移。

既有 Vanilla 版本 4 升级从版本 3 写入武器库存条目、四个护甲栏、由旧鞍转换的 `riding: true` 和可选马铠条目。
保留方案、居民及其余条目 ID、名称、数量和巡逻人员引用，删除鞍的合成条目。
版本 4 尚未发布，直接调整其格式，不新增数据版本或升级步骤；Core 无需初始化装备 keeps 包。版本 4 存档不支持降级。

版本 4 还允许网络设备页面使用的可选殖民地 keeps 包 `folkways_core:ae2_network_devices` 和
`folkways_core:rs2_network_devices`。其 `devices` 列表的每条记录包含 `at`（WorldPos）、标志 `put`、`take`、`order`、
`commission` 和 `only`、由 `rule` 字符串（ItemFilter 单行格式）组成的 `filters` 列表，以及可选整数 `cpus`，即该设备记住的
网络 CPU（RS2 为任务）配额。缺少包或记录表示全部开关开启、空黑名单和默认配额；处于默认值的记录不写入。设备离开殖民地时
记录不清除，重新登记后恢复原设置。这是本周期的增量改动：不改写字段，不注册迁移，也不增加版本号。

可选 keeps 包 `folkways_core:ae2_network_buffers` 和 `folkways_core:rs2_network_buffers` 的 `batches` 保存殖民地已接收的工作。
除现有 `stage`、`finished` 外，可选整数数组 `committed` 记录已消耗材料、但可能尚未结算完成的工坊步骤。
可选复合字段 `continuation` 保留熔炉原有步骤 ID、种类、物品规格、数量、烹饪时间、依赖边，以及预约的
`taker`、`makes`、`load`、`ticks`、`most`，不按当前燃料库存重新定价。
可选列表 `commissions` 用整数数组保存原生任务 UUID，仅用于取消无法继续的委托；取消原生任务仍不会收回殖民地已接收的材料。
可选布尔字段 `returning` 表示该批材料正在等待安全退回网络。缺少这些字段分别表示空数组、空续行数据和 false；
较早开发存档中的批次仍沿用已有的已完成阶段恢复路径。这是当前发布周期内版本 4 的增量变更，不重写旧字段，不添加迁移初始化；仍不支持降级。

## 迁移机制

迁移引擎位于 Core 的 `base.persist.upgrade`。每个持有世界数据的模组在构造时从自己的 `upgrade` 包注册迁移步骤。
原始 NBT 在业务代码读取之前完成升级，共四个入口：

| 入口 | 覆盖范围 | 版本记录位置 |
|---|---|---|
| `COLONY_REGISTRY` | `data/folkways_colonies.dat`：殖民地、领地及各模组的 keeps | `schemaVersion` |
| `CROP_CARE` | `<dimension>/data/folkways_crop_care.dat` | 根节点 `folkways_core:data_version` |
| `ENTITY` | 居民、玩家、动物和投射物上的 Folkways 数据 | `NeoForgeData` 中的 `folkways_core:data_version` |
| `BLOCK_ENTITY` | Folkways 方块实体，以及附加在原生方块实体（熔炉、烹饪设备）上的数据 | `NeoForgeData` 中的 `folkways_core:data_version` |

改名的注册 ID 通过 NeoForge 别名（`RegistryAliases`）解析；没有版本戳的物品组件由其编解码器规范化
（`Upgrades.codec`、`ComponentCodecs`）。没有版本戳的实体或方块实体按当前格式处理，因此命令和模板不会被误迁移。

## 修改持久化格式

1. 将写入和读取代码改为新格式。可能合法缺失的字段保持可选。
2. 每个发布周期只递增一次 `DataVersion.CURRENT`。在所属模组的 `upgrade` 包中新增 `Vn_Description.java`，
   只用字面量键处理原始 NBT，并完成注册。多个模组在同一版本修改同一入口时，用 `Upgrades.part` 注册具名片段；
   只涉及 keeps 的修改可使用 `KeepSteps.bag`。
3. 编写时验证一次：用上一正式版写出的存档加载并重新保存。不要提交样例存档或专门的迁移回归测试。
4. 运行 `./gradlew --offline check` 及受影响模组的 GameTests。

已发布的步骤冻结不可改，修正需新增版本和步骤。`checkDataUpgrades`（属于 `check`）会与对应标签比对已发布步骤，
并限制迁移包只能引用 `java.*`、`net.minecraft.nbt.*` 和框架本身。

## 已知缺口

- 无法读取的殖民地注册表会被封存：玩家被断开，文件保持不动，但其他世界文件仍可能被保存。
- 来自更新版本的 CropCare 文件会原样保留；格式错误的 CropCare 及其他加载失败的 SavedData 可能在下次保存时被空数据覆盖。
- Minecraft 会跳过加载失败的附加数据和方块实体，不会封存。殖民地设置、蓝图等列表中的错误条目可能被丢弃。
- 没有自动检查能发现未递增版本、未写迁移就发布的格式变化，只能依靠评审和上面的第 3 步。
