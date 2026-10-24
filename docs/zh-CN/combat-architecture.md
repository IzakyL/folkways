# Vanilla 基础战斗、居民命令与编队

[架构说明](architecture.md) · [English](../en/combat-architecture.md)

本增量从战斗初稿 `e40ebc7` 续作，并同步 master `2146c09`。已提供原版敌对、居民自卫与逃跑、服务端单人命令和最多 8 人的实际 UnitGroup。居民弓弩、真实武器会话、War 和枪械兼容尚未实现。

## 模块边界

| 模块               | 职责                                                           |
| ------------------ | -------------------------------------------------------------- |
| Core               | 居民、劳动、物品所有权、生命周期、通用出行与互斥执行           |
| Vanilla            | 日常敌对、自卫、巡逻、命令、实际小队、站位、具体行走及武器契约 |
| War，未来          | 袭击、PvE/PvP、战略 AI、RTS 界面、框选、拖线与数字快捷选择组   |
| 独立枪械扩展，未来 | 通过 Vanilla 契约调用 TaCZ、Point Blank 各自的原生逻辑         |

Core、Vanilla 不依赖 War 或枪械模组。Create、Cookery、Modular Golems 保持独立扩展。

## P0/P1：日常敌对与防御

`NativeHostility` 在服务端 EntityJoinLevelEvent 中只安装轻量 Goal，不查询世界。实际索敌延后到正常 AI tick，避免区块尚未 FULL 时查询世界。

- 为 Zombie、AbstractSkeleton 增加优先级 6 的居民目标，排在原版反击、玩家、村民、铁傀儡和幼龟之后。移动、近战和射箭继续由怪物原生 AI 执行。
- 排除 NeutralMob，避免继承 Zombie 的僵尸猪灵变成主动敌人；末影人保持原版中立语义。
- 通过 Bodies.of 和聚落身份筛选居民，兼容其他注册 ResidentKind。离籍、死亡和移除会结束追击。
- 重复进入不累加 Goal；NBT 重载的新实体重新安装。测试包含自动发现、实际追击和伤害、两类怪物重载，以及原版村民目标。
- Threats.hostile 供巡逻和实际打击共用，尊重同盟与中立怪物的当前愤怒状态。出手时再检查敌对资格和视线。
- 武装居民离岗后可反击 100 tick 内、12 格以内的怪物袭击者。生命不高于 35% 时，恐惧高于反击；巡逻继续复用 StrikeNode。
- 无武装居民沿用伤害或火焰触发的恐惧；袭击者仍在追赶时继续避险。安全点采用有用的距离增益，逃跑目标保持到抵达、失败或 120 tick 截止，避免随机采样为空时反复撤回。

工作工具仍由 Pack 和 ToolUse 管理。本次没有把展示主手改造成真实武器槽，居民仍只使用已有的近战工具流程。

## P2：单居民命令与返岗

公共 API 位于 Vanilla 的 `plugins.combat.command.ResidentCommands`：

```java
var commands = ResidentCommands.of(colony);
var order = commands.hold(body, destination);
order.close();
```

| API         | 行为                                          |
| ----------- | --------------------------------------------- |
| move        | 到达后标记 ARRIVED，自动交还劳动控制          |
| hold        | 到达后驻守，等待解除；可指定朝向              |
| retreat     | 以更高优先级及 1.3 倍行进速度撤退，到达后驻守 |
| Order.close | 只解除该句柄，旧句柄不能取消新命令            |
| current     | 查询当前命令和 MOVING、HOLDING 等状态         |
| validate    | 只验证，不接管，供小队整批校验                |

API 要求可信服务端线程、真实聚落登记、已加载且未乘坐载具的 PersonBody，以及同 realm 内 128 格以内的已加载目标。没有新增玩家网络入口；未来入口仍须校验殖民书权限、所属聚落与目标范围。其他 ResidentKind 的命令支持需要单独验证。

命令复用 Core 现有 Urge → ColonyLabor → BodyRunner 安全中断流程。旧任务释放展示工具和出行后，同一个执行器开始命令；没有第二个导航控制器。解除后执行最新计划，不重放已撤销任务。Core 仅补充过期临时 Urge 拒绝记录回收，以及通用 `Node.directTravel()` 选项。该选项使用居民现有 Locomotion 走本地路径；普通劳动仍采用原有多方式出行规划。

命令不持久化。死亡、卸载、离籍和聚落关闭会释放控制；移动超过 1200 tick 也释放并标记 FAILED。驻守和已到达的撤退等待显式解除，期间可能压制日常需求。通过通用 `Node.staysAtWorkSite()` 约束，居民受推离开后由同一执行器重新回位。失败或旧存档句柄不代表有效控制权。

## P3：实际 UnitGroup 与部署意图

`UnitGroups.of(colony).create(people)` 创建 1～8 人实际队伍，`ofResident(id)` 查询唯一归属。转组解除旧队伍对该成员的控制。队伍 ID、成员顺序写入聚落账本；部署命令不持久化，重启后队伍恢复为 IDLE。

```java
var group = UnitGroups.of(colony).create(people);
group.move(new Deployment(left, right, front, new Deployment.Formation(2)));
```

Deployment 接收两个同高度、同 realm 的端点、与阵线近乎垂直的水平正面方向，以及 2～8 格间距。当前只实现单排横队和最多 8 个不同的整数阵位。未来 War 可以提交这个意图，无须逐 tick 操纵居民。

- move 按顺序让成员进入目标纵队，再恢复横队，避免后排先占位堵住前导成员。纵队阵位无站立空间时尝试直接横队。
- hold 直接建立横队；gather 在中心附近集合；release 解除全部成员命令。
- 单人新命令或单人解除会让该成员退出队伍控制，旧队伍不会覆盖新句柄。
- 死亡、离籍成员移出；卸载成员保留为 missing，现有成员重排；重新加载后分配新阵位。
- 无路可走时报告 FAILED 并释放全部成员，可在开路后重新提交。没有强制传送或无限重试。

四人单格开口与恢复横队是本次具体验收场景。本实现不保证所有瓶颈、任意地形、多排、载具或舰船部署；16/32/64 单位性能尚无结论。War 的快捷选择集可以重叠，实际队伍归属不能重叠。

## P4：武器契约评估，尚未发布接口

presentHeldItem(copy) 是工作表现副本，sweepStrayHandStack 会回收其他主手；Core Equipment 当前管理衣甲。它们都不是枪械弹匣、组件和真实主手所有权的实现。因此本次只记录契约，不发布无行为的 WeaponAdapter、WeaponSession 或枪械桩。

| 环节           | 后续实现必须满足的行为                                                               |
| -------------- | ------------------------------------------------------------------------------------ |
| 取得           | Adapter 识别真实装备；Session 独占一个真实物品，Pack 与装备槽不得同时拥有副本        |
| 目标与权限     | 每次执行前校验服务端控制权、目标、敌对和伤害许可                                     |
| 持续动作       | tick 驱动宿主原生武器，区分 READY、FIRING、RELOADING、STOPPING、CLOSED，允许异步完成 |
| 换弹           | 仅变更真实装备与库存，调用原生换弹流程，报告完成、失败或取消                         |
| 停火           | 拒绝新射击意图，并等待原生持续动作结束，不能仅停止提交指令                           |
| 归还与生命周期 | 停火完成后归还物品；保存、卸载、死亡、权限撤销必须可取消，并仅保存或掉落一次         |

下一阶段先让 Vanilla 近战、弓弩通过真实装备会话验证耐久、弹药、停火和重载，再由独立模块验证 TaCZ、Point Blank 的原生接口。

## 验证入口

```sh
./gradlew check
./gradlew -PgameTestMod=vanilla runGameTestServer
./gradlew -PcookeryTests=true -PgameTestMod=vanilla runGameTestServer
./gradlew -PgameTestMod=core runGameTestServer
npm run test:unit
npm run typecheck
npm run test:e2e -- tests/e2e/native-hostility.spec.ts tests/e2e/resident-commands.spec.ts --workers=1
```

GameTestServer 默认关闭全局劳动执行。LaborFixture 仅在测试自己的聚落中显式推进真实劳动和出行，不改变实际游戏运行逻辑。客户端场景覆盖怪物伤害、恐惧接管、命令、窄路编队和保存后重启。

平台、EULA、依赖、CI 矩阵与日志位置见[测试指南](testing.md)。本地 Windows SDK 结果和 GitHub Linux CI 分别记录，未运行的场景不记为通过。
