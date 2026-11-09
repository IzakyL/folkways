# 存档兼容性

[English](SAVE_COMPATIBILITY.md) · 简体中文

## 版本与发布

全部模块以 `0.MINOR.PATCH` 同步发布，基线标签为 `v0.1.0`。该标签尚不存在，由所有者在合并后创建。
PATCH 为小更新（修复和小改动），MINOR 为大更新（新功能或新玩法）。存档格式变化不决定提升哪一位：
迁移会自动升级存档，格式由 `DataVersion` 单独记录。不支持降级，因此数据版本变化的发布须在发布说明中
注明旧版本无法再打开其存档。
1.0.0 前不承诺扩展 API 稳定，不使用 alpha/beta/rc 阶梯。`mods.toml` 使用纯模组版本，
现有 jar 名称单独包含 Minecraft 版本；模组间 `[${version}]` 精确依赖保持不变。
所有 payload registrar 使用模组发布版本，客户端与服务端必须完全一致，不承诺网络兼容。

`DataVersion.BASELINE = 1`、`DataVersion.CURRENT = 3` 是所有模块共享、单调递增的 Folkways 数据版本边界。
数据版本 1 指 master `9a45a4d52`（`9a45a4d5217b5927716b8c1120a86b2098bd1757`）写出的开发版格式；
版本 2 为 `v0.1.0` 的发布格式。
每次发布都有对应数据版本，只有格式变化时才递增。ColonyRegistry 保留必填的 `schemaVersion` 键，版本 1 colony 的 `holdings` 迁移为版本 2 的 `claims`，
原 `SaveVersion` 已合入框架。`StructureSnapshots.schemaVersion = 1` 仍为独立子记录形状标记，
其 Minecraft `DataVersion` 也独立。

## 兼容承诺

每个正式版本读取 `v0.1.0` 起任意正式版本的存档并向前迁移，不支持降级。
开发版仅额外支持 master `9a45a4d52` 的真实 writer 输出（数据版本 1）。更早开发版的形状不支持，
需先用该 master 构建打开并保存一次，再升级；仍不保证它未加载区块中的实体/方块数据已转换。
其他 nightly 格式不承诺兼容。
业务读取器只接受当前格式，迁移在反序列化前改写原始 NBT。同一开发周期内的格式变更合并为一个目标版本。
已发布步骤冻结，修正需要新版本及新步骤。保留旧步骤，因为实体和未加载区块可能在多个版本后才首次读取。
若平台升级迫使提高最低支持版本，在发布说明中明确该兼容中断。
外部模型、资源清单、导入模板和配置文件不属于世界原始 NBT 迁移链。
命令和模板 NBT 必须使用当前格式，不为这些输入提供版本兼容。

## 升级入口与版本戳

唯一引擎位于 Core 的 `base.persist.upgrade`，包含 `DataVersion`、`Surface`、`Step`、`Upgrades`、
`KeepSteps`、`RawUpgrades` 和 `RegistryAliases`。每个有世界持久化数据的模块有独立 `upgrade` 包，
由主类在构造期调用登记入口。Create 的可选消防集成只删除原生活跃连接数据，Modular Golems 不添加自有世界载荷，
版本 3 仍由各自模块登记有限的身份映射表。登记在 common setup 排队工作中冻结，此时全部 mod 构造器已完成。

- `COLONY_REGISTRY`：`ColonyRegistry.load` 验证 `schemaVersion`，迁移副本，再创建当前 Reader。
  缺失、畸形、过旧、过新版本及迁移失败沿用注册表封存、拒绝与文件保护语义；成功升级后标脏。
- `CROP_CARE`：SavedData 加载时先迁移副本再读取处理记录，保存时在根写入 `folkways_core:data_version`。
  缺戳视为基线；畸形或过旧版本经已有 SavedData 错误路径失败。超前版本记录错误日志，不解码处理记录，
  保留原始 tag 的不透明副本供后续保存原样写回（局限见下文）。
- `ENTITY`：必需 mixin 在 `Entity.load` HEAD 运行，先于 NeoForge 持久化数据、附件和业务读取。
  `saveWithoutId` RETURN 在含子类数据的完整结果上写 `NeoForgeData["folkways_core:data_version"]`，包括玩家。
  只有带戳输入才升级；玩家与其他实体遵循相同的自有数据判定，没有 Folkways 数据的原版实体不盖戳。
- `BLOCK_ENTITY`：两个 final 方法 `loadWithComponents`、`loadCustomOnly` 的 HEAD 钩子先于虚方法
  `loadAdditional`，包括 NeoForge 数据和附件读取。final `saveWithoutMetadata`、`saveCustomOnly`
  的 RETURN 在子类写入完成后盖戳，含元数据的包装方法委托给它们。覆盖 SupplyBox、TavernCounter、
  原生 cookery 工位、熔炉训练以及 C&B 样板字段。

自有数据判定：类型 id 为 `folkways:` 的实体/方块实体、命名空间为 `folkways:` 的附件，
或 NeoForgeData/根级存在以 `folkways:`、`folkways.`、`folkways_` 开头的键。
盘点发现现有持久化键均符合这些前缀；额外识别根级 `FolkwaysFurnace*` 字段，版本 3 按冻结表改写精确的命名空间业务键，点号和下划线业务键不变。
这些前缀保留给 Folkways。戳自身也计入判定，清空业务记录后仍保留版本。
仅用 codec 的附件可能使外层实体盖戳，但附件 codec 不依赖该戳。

实体和方块实体的加载方法也接收命令（包括 `/summon`）、刷怪蛋 `entity_data`、同步、物品、模板和复制的
NBT。对 **ENTITY 和 BLOCK_ENTITY**，无戳统一视为当前格式输入：加载时不升级、不改写、不补戳。
唯一例外是 `NeoForgeData` 或附件下带有已发布 `folkways:` 键的记录：只有版本 1 会无戳写出这类键，
因此它按版本 1 升级并盖戳。保存时自然为自有数据盖戳。版本 2 存档已盖戳，按戳升级。注册表别名在这些钩子之前解析旧类型、
调色板、组件和附件 ID；Core 的组件 codec 在解码时规范化字符串 ID 载荷，覆盖嵌套库存和无戳的
v1 物品堆。这不增加磁盘容器版本，也不改变无戳业务 NBT 的加载规则。
SupplyBox 更新标签省略 `generated`，当前部分载荷保持安全。未来步骤必须检查完整子记录是否存在，
并容忍复制/同步子记录的稀疏字段，不能仅凭缺字段就推断为旧存档。为受支持存档自定义构造旧 NBT 的代码
必须明确提供版本，或使用旧 codec。原始迁移不会初始化可选数据袋。

惰性实体/方块实体的范围外或畸形戳记录错误日志并跳过迁移，后续保存保留该戳，不伪装成当前格式。
这不封存整个世界，也不保证当前业务读取器保留未知字段，应避免用旧程序打开新世界。
`BOOK_GESTURE`、`FILTER_TAG`、`colony_member`、`current_colony` 的组件/附件自身不盖戳：
未来改变形状时使用 `Upgrades.codec(current, legacy.xmap(...))`，优先当前解码，其次旧 codec 转换，
不接受失败/部分成功结果，只写当前格式。注册表 id 改名由构造期调用集中帮助方法
`RegistryAliases.alias(deferredRegister, oldId, newId)`，委托 NeoForge `DeferredRegister.addAlias`。

## 修改持久化格式

1. 修改业务 writer/reader，只读当前形状。确实可省略字段仍可选；要求方块实体/附件字段前先检查全部调用方。
2. 本周期将 `DataVersion.CURRENT` 递增一次。在所属模块 `upgrade` 包新增 `V3_Description.java`
   （例如目标版本 3），实现 `Step`，只操作原始 NBT、使用字面量键，并在模块构造期入口登记。
   步骤表示 `target - 1 → target`，未变化的版本或存档面无需空步骤。
   多模块在同一目标版本修改同一存档面时，用 `Upgrades.part("module/name", new V3_Description())`
   登记独立具名片段。先执行根步骤，再按名字顺序执行片段；重复 `(surface, target, name)` 拒绝。
   这样组合出每个存档面的一个有效步骤，迁移包无需跨模块引用。
3. 仅修改 keeps 时，也可从 `V3_Description.register()` 调用
   `KeepSteps.bag("folkways:owner", 3, bag -> ...)`。帮助方法遍历全部 colony 中存在的该 owner 数据袋。
   多模块可能在同一版本改袋，因此这些 owner 贡献在注册表步骤之后按 owner id 顺序组合。
   重复 `(owner, target)` 拒绝，普通 Step 仍拒绝重复 `(surface, target)`；不同 owner 修改应互相独立。
4. 编写步骤时验证一次：用上个已发布版本真实写出的存档或 NBT 加载到新代码，确认迁移后数值完整、
   重新保存为新版本。这是一次性验证，不提交样本和专门的迁移回归测试；步骤发布后冻结，
   结果不会再变化，长期防护由 `checkDataUpgrades` 负责。
5. 执行 `./gradlew --offline check` 及受影响模块的 GameTest。

`checkDataUpgrades` 已加入 `check`：迁移包只允许 import `java.*`、`net.minecraft.nbt.*` 和框架，
完整限定引用也检查；业务代码除 mod 主类和下述组件适配器外不得引用迁移包。步骤文件名必须含字面量目标版本（`Vn_Description.java`）。
对每个含 DataVersion 的 `v*` 标签，用 `git show` 比较已发布步骤字节，检查修改、删除、改名和事后补入；
无发布标签时不比较冻结文件。不要求未变化的存档面有步骤。
Core 的 `upgrade.ComponentCodecs` 额外允许使用 `Codec`；只有 `FolkwaysDataComponents` 可以引用这个适配器，
用于注册持久化组件 codec。迁移步骤仍不得引用业务类型。

迁移框架自身的 GameTest（链式执行、版本边界、片段组合、盖戳规则）可单独运行：
`./gradlew --offline runGameTestServer -PgameTestMod=core -PgameTestNamespaces=folkways_core`

v1 → v2 只有 Core 登记的 `V2_Claims` 注册表具名片段（`holdings` → `claims`）。其余差异均为当前 reader
忽略的多余元数据（WorkDefaults/配装/BodyPerks 的局部 `version`、`toolVersion`、`payloadVersion`、
damagecontrol 袋 `schemaVersion`、`forage_version`、MillstoneDrive `target`），不需要迁移；
旧玩家附件 `kept_books` 不再恢复。该结论已用 master 真实 writer 输出一次性验证。

## 持久化数据清单

**现状：** `<dimension>` 对主世界表示世界根目录，对其他维度表示 `DIM-1`、`DIM1` 或 `dimensions/<namespace>/<path>`。“无独立版本”不排除外层 Minecraft `DataVersion`。

| 数据范围 | 世界目录存储位置 | 加载时机 | 版本 | 所属模组 | 升级入口/版本戳 |
|---|---|---|---|---|---|
| ColonyRegistry | `data/folkways_colonies.dat` | 服务器启动时（`onServerStarting → ColonyRuntime.opened → ColonyRegistry.get`） | Folkways schema 3；Minecraft 包装版本 | Core | COLONY_REGISTRY / `schemaVersion` |
| ColonyData 的 claims 和 keeps | 每个注册表 colony 内 | 注册表反序列化；服务就绪时读取插件状态 | 注册表版本 | Core | COLONY_REGISTRY / `schemaVersion` |
| 插件 presence、front/settings、工作默认值、名称/外观、配装、RTS 编组、工具借用 | 注册表 `keeps` 数据包 | 服务创建或就绪时 | 注册表版本 | Core、Vanilla、Create、Cookery | COLONY_REGISTRY / `schemaVersion` |
| CropCare | `<dimension>/data/folkways_crop_care.dat` | 首次访问该维度的 CropCare 时 | 全局数据版本 3 | Vanilla | CROP_CARE / root `folkways_core:data_version` |
| ResidentEntity 名称/外观 | `<dimension>/entities/r.*.mca` | 实体/区块加载时 | 全局数据版本 3 | Vanilla | ENTITY / NeoForgeData `folkways_core:data_version`; codec-only attachments: codec |
| BodyState、许可证、colony 成员关系附件 | 实体区域文件中的实体 NBT | 附件反序列化/访问时 | 全局数据版本 3 | Core | ENTITY / NeoForgeData `folkways_core:data_version`; codec-only attachments: codec |
| 玩家附件和库存 | `playerdata/<UUID>.dat`；集成服务器玩家也存于 `level.dat` | 玩家加载时 | 全局数据版本 3 | Core/front | ENTITY / NeoForgeData `folkways_core:data_version`; codec-only attachments: codec |
| SupplyBox | `<dimension>/region/r.*.mca` 中的方块实体 NBT | 区块/方块实体加载时 | 全局数据版本 3 | Vanilla | BLOCK_ENTITY / NeoForgeData `folkways_core:data_version` |
| TavernCounter | 同上，方块实体存储 | 区块/方块实体加载时 | 全局数据版本 3 | Cookery | BLOCK_ENTITY / NeoForgeData `folkways_core:data_version` |
| Cookery board/pot/stockpot/millstone/spit/steamer/tray/teapot/tavern 批次 | 区域文件中原版方块实体的 `NeoForgeData` | 原版方块实体加载；勘察/恢复时读取批次 | 全局数据版本 3 | Cookery | BLOCK_ENTITY / NeoForgeData `folkways_core:data_version` |
| LoanClothing | 实体文件中生物持久数据的槽位映射 | 生物加载；恢复/加入处理时 | 全局数据版本 3 | Cookery | ENTITY / NeoForgeData `folkways_core:data_version`; codec-only attachments: codec |
| EquipmentCoordinator | 生物持久数据的装备映射 | 装备协调时 | 全局数据版本 3 | Core | ENTITY / NeoForgeData `folkways_core:data_version`; codec-only attachments: codec |
| Dining/历史/座位和 colony 宠物 | 生物持久数据；宠物名册也在注册表 keeps 中 | 实体加载/行动/服务加载时 | 全局数据版本 3 | Vanilla | ENTITY / NeoForgeData `folkways_core:data_version`; codec-only attachments: codec |
| WeaponStations 座位 | 生物持久数据的租约/载具标记 | 实体加载和 tick 后恢复时 | 全局数据版本 3 | Vanilla | ENTITY / NeoForgeData `folkways_core:data_version`; codec-only attachments: codec |
| Ridership 目的地 | 骑乘者持久数据 | 骑乘者/车辆恢复及路线查询时 | 全局数据版本 3 | Create | ENTITY / NeoForgeData `folkways_core:data_version`; codec-only attachments: codec |
| BreedingPerks | 动物持久数据的繁殖标记 | 繁殖事件时 | 全局数据版本 3 | Vanilla | ENTITY / NeoForgeData `folkways_core:data_version`; codec-only attachments: codec |
| Warfare/TACZ 投射物归属 | 实体文件中的投射物持久数据 | 投射物加载、碰撞/伤害处理时 | 全局数据版本 3 | Firearms | ENTITY / NeoForgeData `folkways_core:data_version`; codec-only attachments: codec |
| 熔炉训练批次/小数 | 区域文件中的原版熔炉方块实体 NBT | 熔炉加载时 | 全局数据版本 3 | Vanilla | BLOCK_ENTITY / NeoForgeData `folkways_core:data_version` |
| StructureSnapshots | 注册表 keeps 中的 damage-control 数据包 | damage-control 服务创建时 | Snapshot schema 1 和 `DataVersion` | Core 格式，Vanilla 所有 | COLONY_REGISTRY / `schemaVersion` |
| 蓝图、建造订单和委托草图 | 注册表 keeps 中的 build 数据包 | build 服务创建时 | 注册表版本 | Vanilla | COLONY_REGISTRY / `schemaVersion` |
| ItemSpec 和 ColonySettings | 嵌入所属 keeps/settings/记录 | 所属记录加载时 | 全局数据版本 3 | Core/front | COLONY_REGISTRY / `schemaVersion` |
| 物品数据组件 | 物品堆保存处的 ItemStack `components` | 堆 codec 解码时 | 无独立版本 | Core/front | codec / — |
| 服务器配置文件 | `serverconfig/folkways*-server.toml` | 服务器配置加载时 | 无独立版本 | Core、Vanilla、Create、Firearms、Carving | —（不在世界 NBT 链内） |
| 客户端绘图设置/资源 | 世界外：`config/folkways-drawing-settings.properties`、客户端资源及编写的结构/样式文件 | 界面/资源加载时 | 取决于格式 | Core/front、Vanilla | —（不在世界 NBT 链内） |

**现状：** Create 可选消防集成的消防栓 mixin 从原版存档数据移除活跃居民连接，不增加独立的持久化数据。

## 已知缺口

迁移时必须保留拥有数据的模块。移除后再装回可选模块不在兼容承诺内：缺席模块的迁移代码无法在共享版本
提升前升级保留的不透明数据袋。同一目标版本的跨模块变化必须使用互相独立的具名片段，其名字/顺序属于迁移契约。

**现状：** `ColonyRegistry` 会封存不支持的版本、不可读的顶层 colony 行和注册表加载异常；Minecraft
磁盘/NBT 读取器拒绝的已有文件以及服务就绪时抛出的异常也会封存。
服务启动异常既可能来自畸形数据，也可能来自插件 bug；此路径使用独立提示，说明 colony 服务启动失败、
colony 文件未改动、服务器日志包含原因，解决故障后可以重新打开世界，不把它诊断为存档不可读，也不要求新建世界。拒绝只保护 `folkways_colonies.dat`：在 `PlayerLoggedInEvent` 或运行时服务就绪触发封存时
断开玩家连接后，服务器仍运行，仍可写区域、实体、各维度 SavedData、playerdata 和 `level.dat`。

**现状：** 比当前程序更新的 CropCare 文件无法解码。此前会经 Minecraft 的 SavedData 加载失败路径
以空数据替代，后续保存可能覆盖原文件。共享全局数据版本也会使殖民地注册表被封存并踢出玩家，
避免继续游玩，但服务器仍会运行并可能自动保存。现在 CropCare 为这一超前版本路径保留原始 data tag，
后续保存返回其未经改写的新副本，且不接受处理记录变化，防止载荷被空数据覆盖。
此保护不保证压缩文件字节及 Minecraft 外层包装不变；畸形、过旧文件和其他加载失败仍可能被空对象替代并覆盖。

**现状：** 新注册表从构造时即标记为脏，即使没有 colony，首次保存也会记录 schemaVersion。
但基线之前没有 colony 文件的世界仍无法与新世界区分。

**现状：** 没有自动检测“改了存档格式却忘记升版本和写迁移”的测试，依赖代码审查及第 4 步的一次性验证。
`StructureSnapshots` 有独立版本，不匹配时返回空值，没有可见拒绝。

**现状：** Minecraft/NeoForge 仍会记录并跳过加载失败的附件（`AttachmentHolder.deserializeAttachments`）
和方块实体（`BlockEntity.loadStatic`），没有为它们添加运行时封存。CropCare 等其他 SavedData 仍经过 `DimensionDataStorage.readSavedData`，
其捕获的失败可能使空对象替代原数据。Colony claims 遇到畸形行已改为失败；其余 `Entries.of` 调用方（colony 设置、蓝图、未加载方块、床位）保持原样：畸形行
仍可能记录日志后被丢弃，或在返回 `Optional.empty` 时静默丢弃。插件解析器也会为无法解析的注册表项/
世界坐标使用可选结果；后续保存仍可能使这些损失永久化。熔炉加载方法也接受部分命令/物品/模板数据：
两个顶层训练字段缺席时各自使用默认值，已提供的批次行则必须含 count 和 speed。
身体在构造时即创建 BodyState 与许可证并在保存时写 `folkways_core:body` 标记；缺少任一附件的已保存身体会记录含 UUID 的错误日志，
以新附件加载，因为抛出异常会使 Minecraft 丢弃该实体。

**现状：** 畸形武器座位记录会在下坐骑/删除标记前抛出验证失败，此事件路径没有封存。
畸形衣物身份/快照对会保留标记和实物以待恢复，不推测归属，也不会自动修复。模组自有方块状态调色板
验证 Name 及所有已定义属性；导入模板保留原版默认行为。快照字段缺失或畸形仍可能经已有解析失败路径
返回 Optional.empty。

## 模块命名空间：数据版本 3

九个模块的内容命名空间分别为 `folkways_core`、`folkways_vanilla`、`folkways_create`、
`folkways_cookery`、`folkways_firearms`、`folkways_carts`、`folkways_carving`、
`folkways_golems`、`folkways_epicfight`。RTS 属于 Core，消防属于 Create，TACZ 和 Warfare
属于 Firearms。网络协议判别版本增加 `/3` 后缀。

各模块在自己的 upgrade 包中登记 `V3_Namespaces`，构造期安装显式的旧 ID → 新 ID 表。
生成的 `Map.entry(oldId, newId)` 是发布后冻结的迁移契约，发布后不得重新生成或修改。
`NamespaceIds` 精确匹配 compound 键、字符串值和列表元素，还识别组件移除键 `!id` 及物品/标签过滤编码；
不按命名空间前缀一刀切。未知 `folkways:*` ID 原样保留，每个 ID 只警告一次；缺席模块的旧 ID 也如此。
Core 的 `V2_Claims` 保持不变，v1 先形成 v2 claims，再通过 v3 命名空间步骤。

原有 COLONY_REGISTRY、CROP_CARE、ENTITY、BLOCK_ENTITY 覆盖 claims/keeps、owner、工作默认值、
许可证/热忱、专长的职业轨迹、快照、作物护理、身体/玩家附件以及嵌入的物品堆和数据组件。
继续读取 v2 的旧戳 `folkways:data_version`，当前 writer 写 `folkways_core:data_version`。
NeoForge 注册表别名解析改名的方块、物品、实体类型、方块实体、组件和附件，覆盖旧区块调色板和库存。
不需要区块、实体区域、玩家容器或 ItemStack mixin，也不再给容器盖戳。原有实体/方块实体钩子保留自有数据的
版本规则。Core 的 `upgrade.ComponentCodecs` 在组件注册处提供 `book_gesture` 和 `filter_tag` 的解码规范化：
手势枚举名原样保留，标签 ID 使用已安装模块的冻结映射表。NBT、JSON ops 和嵌套物品 codec 均适用；
编码和物品相等性、堆叠行为不变。查询内嵌文本由所属模块负责：Core 的 v3 COLONY_REGISTRY 步骤改写
ColonySettings 的物品查询（包括持久化路径设置），Vanilla 的 v3 BLOCK_ENTITY 步骤改写 SupplyBox 白名单的
查询过滤器。两者使用全部已安装 ID 表，因此跨模块引用不依赖片段执行顺序。通用 `NamespaceIds` 工具不再包含
组件名或 query/whitelist NBT 键分支。
SavedData 文件名不变。

**不迁移**命令文本、外部数据包、资源包、配置文件、导入模板中的任意内容、Starlark 样式、未知或通配符查询样式和外部居民模型包；
这些输入必须改用当前 ID。仓库内置资源与测试夹具已更新。Vanilla 的禁用配方列表、TACZ 的允许枪械/射程
配置接受映射表中已知的精确旧 ID，在内存中规范化并只警告一次，不重写用户 TOML。
Create 配置没有 ID 值。迁移时必须保留所有拥有数据的模块，详见已知缺口。

投射物 NBT 键 `folkways_tacz_colony` 不是命名空间 ID，原样保留。
服务器配置文件改用各模组自己的名字：`folkways-tacz-server.toml` 改为 `folkways_firearms-server.toml`，
`folkways_chiselsandbits-server.toml` 改为 `folkways_carving-server.toml`。`ConfigRenames` 在模组加载时
移动 `config/` 和 `defaultconfigs/` 中的旧文件，并在 NeoForge 读取世界的 `serverconfig/` 之前移动其中的旧文件。
若新文件名已存在，则以新文件为准，旧文件保留原处并记录日志。
Golems 没有注册配置文件，v0.1.0 也没有以其已发布 modId 派生的键保存数据。
原有 `folkways:*` 业务 ID 在尚未发布的 v3 表中直接迁移到 `folkways_golems`；
本次项目改名不需要新增迁移步骤或保留旧配置文件名。

Carving 替代已发布的 Chisels & Bits 扩展。**安装 `folkways-carving-*.jar` 时必须删除旧的
`folkways-chiselsandbits-*.jar`；新旧同时安装会重复注册并崩溃。** 服务器配置按上文改名为
`folkways_carving-server.toml`，自有数据判定继续接受 `folkways_chiselsandbits`。
核对 v0.1.0 后，该命名空间中由资源生成的图样 ID 共七个：`curved_gallery`、`gothic_cloister`、
`pavilion`、`rampart`、`rotunda`、`stone_arch_bridge`、`torii`，以及对应的 `.star` 资源 ID。
Carving 的 v3 表逐项精确映射到 `folkways_carving`。持久化的 `BuildPresence.drafted[].pattern`
位于殖民地注册表的 Build keep 内，在 `Drafted.load` 前经过注册表 v1/v2 → v3 迁移链；
嵌入的蓝图 payload 同样经过该链。其余已发布的雕刻 ID（`folkways:chiselsandbits`、
`folkways:schematic`、`folkways:resolution`）在未发布的 v3 表内直接指向 Carving。

已导出的蓝图/schematic 是上述外部输入排除项的明确例外：结构 payload 读取时先在副本上应用
已安装模块的精确 ID 表，再校验 handler。因此 `folkways:structure` 和 `folkways_core:structure`
中值为 `folkways:chiselsandbits` 的描述符均解析为 `folkways_carving:chiselsandbits`，
包括世界升级后才从磁盘导入的文件。蓝图方块数据使用相同值边界；未知 ID 保持不变，缺失 handler
仍会拒绝。不新增容器级迁移 mixin，不重写磁盘文件；Carving 特有 ID 保留在自己的 `upgrade` 包内，
Core 的归一化工具不包含具体后端知识。

发布说明须注明数据版本 3、不支持降级、上述外部输入排除项以及升级时保留拥有数据模块的要求。
