# 存档兼容性

[English](SAVE_COMPATIBILITY.md) · 简体中文

## 版本

所有模组统一以 `0.MINOR.PATCH` 发布。PATCH 是小更新（修复与细节调整），MINOR 是大更新（新功能或新玩法）。
不设 alpha/beta/rc 阶段，1.0.0 之前不承诺扩展 API 稳定。客户端、服务器以及两端的 Folkways jar 必须来自同一构建。

存档格式由 `DataVersion` 单独记录，这是所有模组共享、单调递增的一个数字。它不决定发布版本号。
修改持久化格式的 PR 合入 master 时递增一次；发布本身不会递增它。

## 兼容承诺

- 每个正式版和每个 master 提交（包括 nightly 构建）都能打开此前所有正式版和 master 提交的存档，并向前迁移。
  未合入的分支和本地构建不作承诺。
- 不支持降级。数据版本变化时，发布说明会注明。
- 升级期间请保留所有持有数据的模组；缺失模组的迁移代码无法升级其数据。
- 迁移只改写世界 NBT。命令、数据包、资源包、配置文件、Starlark 样式、居民模型包以及命令中传入的 NBT
  须使用当前 ID。导出的蓝图例外：导入时会自动规范化。

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

PR 一律 squash 合并到 master，功能分支上的中间提交不会进入 master 历史。判断是否需要迁移或兼容时，
只与 `origin/master` 上已有的格式比较。分支内先写后改的格式，包括尚未合入 master 的新模组，合入前直接改成最终形态：
不写迁移步骤、缺字段回退、版本判断或旧格式测试，也不递增 `DataVersion`。

1. 将写入和读取代码改为新格式。可能合法缺失的字段保持可选。
2. 每个 PR 只递增一次 `DataVersion.CURRENT`。在所属模组的 `upgrade` 包中新增 `Vn_Description.java`，
   只用字面量键处理原始 NBT，并完成注册。多个模组在同一版本修改同一入口时，用 `Upgrades.part` 注册具名片段；
   只涉及 keeps 的修改可使用 `KeepSteps.bag`。
3. 编写时验证一次：用当前 `origin/master` 写出的存档加载并重新保存。样例存档和专门的迁移回归测试通常不提交，
   [发布版升级夹具](CONTRIBUTING.zh-CN.md#验证修改)除外。
4. 运行 `./gradlew --offline check` 及受影响模组的 GameTests。

master 上的步骤冻结不可改，修正需新增版本和步骤。`checkDataUpgrades`（属于 `check`）会将其与每个发布标签
以及当前分支与 `origin/master` 的合并基点比对，并限制迁移包只能引用 `java.*`、`net.minecraft.nbt.*` 和框架本身。

## 已知缺口

- 无法读取的殖民地注册表会被封存：玩家被断开，文件保持不动，但其他世界文件仍可能被保存。
- 来自更新版本的 CropCare 文件会原样保留；格式错误的 CropCare 及其他加载失败的 SavedData 可能在下次保存时被空数据覆盖。
- Minecraft 会跳过加载失败的附加数据和方块实体，不会封存。殖民地设置、蓝图等列表中的错误条目可能被丢弃。
- 没有自动检查能发现未递增版本、未写迁移就发布的格式变化，只能依靠评审和上面的第 3 步。

机场资源预订仅存在于内存中，不写入存档。
