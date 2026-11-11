# 存档兼容性

[English](SAVE_COMPATIBILITY.md) · 简体中文

## 版本

所有模组统一以 `0.MINOR.PATCH` 发布，起点为 `v0.1.0`。PATCH 是小更新（修复与细节调整），MINOR 是大更新
（新功能或新玩法）。存档格式由 `DataVersion` 单独记录，不决定版本号。不设 alpha/beta/rc 阶段，1.0.0 之前
不承诺扩展 API 稳定。客户端、服务器以及两端的 Folkways jar 必须来自同一构建。

`DataVersion.CURRENT` 是所有模组共享、单调递增的 Folkways 存档版本。版本 1 为 master `9a45a4d52` 的开发版格式，
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
