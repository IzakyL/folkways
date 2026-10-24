# 卓越前线

[玩法](gameplay.md) · [架构](architecture.md) · [English](../en/warfare.md)

将 `folkways_warfare` 与 Core、Vanilla、卓越前线 0.8.9.2 和 Kotlin for Forge 5.12
一起安装。已于 2026-10-24 查询 [Modrinth](https://modrinth.com/mod/superb-warfare/versions)，0.8.9.2 仍是最新的 NeoForge 1.21.1
兼容版本；两个下载文件均在 `blockwright.toml` 中以 SHA-512 固定。

## 手持枪械

在殖民书战斗页为实际队伍选择**枪械（卓越前线）**。守卫与编队巡逻员会从三十二格内
已登记的仓库领取一把支持的枪，以及最多十六发对应散装弹药。旧殖民地需要在已保存的
工具许可中允许相应枪械。缺货时不会生成弹药。

| 枪械     | 散装弹药 | 有效射程 |
| -------- | -------- | -------- |
| Glock 17 | 手枪弹药 | 24 格    |
| MP5      | 手枪弹药 | 32 格    |
| AK-47    | 步枪弹药 | 48 格    |
| M4       | 步枪弹药 | 48 格    |
| M870     | 霰弹     | 20 格    |
| SVD      | 狙击弹药 | 64 格    |

每次射击将一发实际散装弹药交给 SBW 弹匣事务，再由 `GunData.shoot` 发射。
玩家原先装入弹匣的子弹会保留，不代替散装弹药需求。热量和枪栓状态在实际物品上推进，
重新取得武器会话不能绕过射速或泵动时间。停止、更换物品、死亡、保存和卸载都会归还独占主手物品。

补给、缺额统计和背包保留都通过 `LoadoutProvider.acceptsWeapon` 筛选可用实物。
物品名称和玩家装弹会保留。带特性、属性覆盖、爆炸或穿透属性、自定义投射物、自动装填、不兼容弹药或
不支持的弹匣／耗弹组件的枪械不自动使用；HEBullet 和燃烧效果也在其中。
主手枪械变成不安全配置时，射击前关闭会话。

目标沿用 Vanilla 的敌意与守卫活动范围规则。友方居民、宠物或玩家进入射线时停止射击。

## 炮位

把静止炮位放在已分配守卫阵位十二格内，且位于同一 realm。这沿用现有守卫活动区域，
不增加新的持久化建筑标记。居民选择最近的空闲、未上锁炮位，不占用玩家或其他居民的座位。

自动操作支持使用普通炮弹的迫击炮，以及使用 HE 高爆炮弹的 Mk42、Mle1934 和静止的
FH77BW 榴弹炮。指派守卫前，先通过 SBW 选用 HE。其他固定火炮和防御实体也必须使用
相同的已知投射物配置。穿甲弹、TOW 制导、集束弹、燃烧弹和自定义投射物不自动使用，
玩家仍可自行操作。不驾驶坦克、飞机或船只；FH77BW 仅在静止时作为炮位使用。
迫击炮在 SBW 中没有座位，居民会走到旁边操作。其他炮位使用炮塔的实际座位，包括榴弹炮的第二个座位。

守卫通过同一巡逻补给流程领取两发实际炮弹。需求由 SBW 当前所选弹药类型生成，
使用保留组件的精确 `ItemSpec`，不同组件的炮弹不能互相替代。弹药进入炮位库存后，
沿用 SBW 的装填计时。炮塔角度、初速、重力、冷却和发射由 SBW 接口处理；
目标还必须在武器设置的射程和投射物寿命内。

射击前预测弹道和落点，并计入散布。生物保护范围使用 SBW 配置爆炸半径的两倍；
方块保护范围使用锁定版本扁平破坏范围的上限，即配置半径的 0.6 倍，两者都加上散布余量。
友方生物、已登记的殖民地设施或仓库位于对应区域时不开火；弹道经过未加载区域或无法预测时拒绝发射。

自动炮击必须通过生物破坏许可钩子。居民投射物实际爆炸前再次检查规则和影响区域，
并在 SBW 造成伤害前发出可取消的 NeoForge `ExplosionEvent.Start`；取消后不伤害生物或方块。
每批方块破坏前通过 `ExplosionEvent.Detonate` 让保护模组删减候选方块。
这些修补只作用于居民射击，玩家操作的 SBW 武器沿用自身行为。

步行、取弹和操作都由同一劳动执行器控制。撤退、新命令、解除、撤销巡逻职业许可、
低生命值、死亡、卸载和炮位移除都会终止操作并离座。取消迫击炮的延迟发射时，炮弹保留
在炮位中。SBW 排队中的射击会在实际发射时重新验证命令与安全条件。
已发出的投射物继续按 SBW 的物理规则飞行，其爆炸仍需通过落点保护检查。

## 扩展接口

Vanilla 不引用 SBW 类。其他枪械集成可以使用相同注册接口：

```java
Weapons.register(adapterId, adapter);
LoadoutRegistry.register(loadoutId, provider);
```

`loadoutId` 是稳定的命名空间标识。`LoadoutProvider` 提供武器需求、弹药数量、
选项验证、持有武器推断和界面选项。战斗页从 `LoadoutRegistry.entries()` 生成选项；
六把 SBW 枪械共用 `folkways:warfare/firearm`，选项中保留所选枪械。提供者的
`loaded(stack, ammunition)` 可将已装填弹药计入补给目标；SBW 单独计算散装弹药以保留
玩家装弹。`WeaponSession.continuousEngagement()` 让连续射击保留同一主手物品。

`PatrolSupplies.registerSupply(id, (colony, body) -> amounts)` 将其他当班装备加入同一
检查库存预留的领取流程和背包保留列表。既有 GUARD 命令通过 `mount(entity)` 取得实际座位，
通过 `dismount()` 归还；命令被替换或关闭时只释放自己拥有的座位。

补给偏好保存为第二版及命名空间标识。第一版的 `MELEE/BOW/CROSSBOW` 行有显式迁移；
停用扩展时保留未知标识。命令和射击意图不持久化。居民身上的第一版
`folkways:command_mount` 标记记录所拥有载具和命令的 UUID；恢复乘坐关系后，若没有对应的
运行中命令，就只释放标记所指的载具并清除标记。不含新标记的旧存档保留普通乘坐行为。
标记本身不会恢复命令权限。

## 验证

```sh
npm run setup
./gradlew --no-daemon build
./gradlew --no-daemon runGameTestServer
./gradlew --no-daemon -PwarfareTests=true -PgameTestMod=warfare runGameTestServer
./gradlew --no-daemon -PwarfareTests=true runGameTestServer
npm run test:i18n
```

专项测试覆盖从仓库领取枪弹、逐发消耗、缺弹、保留弹匣、枪栓计时、炮位检测、实际出行、
乘坐、瞄准、发射、撤退、组件精确炮弹补给、扩大后的 HE 伤害范围、不安全实物筛选、
拒绝 AP／TOW、爆炸许可与取消，以及运行中载具和乘客快照的恢复。
