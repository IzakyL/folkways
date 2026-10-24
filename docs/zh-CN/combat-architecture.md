# Vanilla 基础战斗、居民命令与编队

[架构说明](architecture.md) · [English](../en/combat-architecture.md)

本增量从战斗初稿 `e40ebc7` 续作，并同步 master `506eec3`。已提供可配置敌意、居民自卫与逃跑、服务端单人命令、最多 8 人的实际 UnitGroup、编队巡逻，以及真实近战、弓和弩会话。可选 [War](raids.md) 已提供殖民地袭击，枪械兼容仍由后续独立模块实现。

## 模块边界

| 模块               | 职责                                                                  |
| ------------------ | --------------------------------------------------------------------- |
| Core               | 居民、劳动、物品所有权、生命周期、通用出行与互斥执行                  |
| Vanilla            | 日常敌对、自卫、巡逻、命令、实际小队、站位、具体行走及武器契约        |
| War                | 殖民地袭击、破坏额度、火焰来源、劫掠和战报；更广泛的 RTS 操作留待后续 |
| 独立枪械扩展，未来 | 通过 Vanilla 契约调用 TaCZ、Point Blank 各自的原生逻辑                |

Core、Vanilla 不依赖 War 或枪械模组。Create、Cookery、Modular Golems 保持独立扩展。

## P0/P1：日常敌对与防御

`NativeHostility` 在服务端 EntityJoinLevelEvent 中只安装轻量 Goal，不查询世界。实际索敌延后到正常 AI tick，避免区块尚未 FULL 时查询世界。

- 为实现原版 Enemy 接口的怪物安装一个轻量居民目标；只有进入实体类型标签 `folkways:resident_enemies` 的类型才执行索敌。优先级不早于 6，且排在安装时的所有原生目标之后。移动、近战、射箭和苦力怕引爆继续由怪物原生 AI 执行。
- 排除 NeutralMob，避免继承 Zombie 的僵尸猪灵变成主动敌人；末影人保持原版中立语义。
- 通过 Bodies.of 和殖民地身份筛选居民，兼容其他注册 ResidentKind。离籍、死亡和移除会结束追击。
- 重复进入不累加 Goal；NBT 重载的新实体重新安装。测试包含自动发现、实际追击和伤害、两类怪物重载，以及原版村民目标。
- Threats.hostile 供巡逻和实际打击共用，尊重同盟与中立怪物的当前愤怒状态。出手时再检查敌对资格和视线。
- 默认名单含僵尸和骷髅的原版变种、蜘蛛、洞穴蜘蛛与苦力怕。蜘蛛仅在原版亮度值低于 0.5 时主动索敌；明亮处未交战的蜘蛛也不被巡逻主动挑衅，已发生的战斗按原版攻击 Goal 脱离。
- 武装居民离岗后可反击 100 tick 内、12 格以内的怪物袭击者。生命不高于 35% 时，恐惧高于反击；巡逻继续复用 StrikeNode。
- 无武装居民沿用伤害或火焰触发的恐惧；袭击者仍在追赶时继续避险。安全点采用有用的距离增益，逃跑目标保持到抵达、失败或 120 tick 截止，避免随机采样为空时反复撤回。

### 可继承的敌意标签

1.21.1 的原版 `Enemy` 是 Java 标记接口，NeoForge 的通用实体标签没有提供等价的完整敌意名单。巡逻保留对 Enemy 实现的自动识别，数据包可使用以下实体类型标签补充或排除。

| 标签                                | 用途                                                             |
| ----------------------------------- | ---------------------------------------------------------------- |
| `folkways:resident_enemies`         | 允许已有原生攻击 AI 的 Enemy 类型主动索敌殖民地居民              |
| `folkways:undead_resident_enemies`  | 被上一个标签引用的原版僵尸、骷髅变种名单                         |
| `folkways:patrol_targets`           | 巡逻的额外候选；默认继承 resident_enemies，已有 Enemy 仍自动识别 |
| `folkways:ignored_resident_enemies` | 优先排除 Folkways 附加索敌和巡逻主动攻击                         |

例如在数据包 `data/folkways/tags/entity_type/resident_enemies.json` 中追加自己的分类：

```json
{
  "values": [
    "#my_pack:hostile_undead",
    { "id": "my_mod:monster", "required": false }
  ]
}
```

标签可以嵌套引用，`replace: true` 可替换既有名单。每次尝试索敌及继续追踪均读取当前标签，`/reload` 对已加载实体生效，不额外叠加 Goal。中立状态、殖民地居民身份与同盟检查仍然生效。标签不会补出没有的原生攻击动作；排除名单也不关闭原版反击或已经开始的苦力怕引线。

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
| guard       | 保留阵位，允许战斗和恐惧安全抢占，结束后回位  |
| Order.close | 只解除该句柄，旧句柄不能取消新命令            |
| current     | 查询当前命令和 MOVING、HOLDING 等状态         |
| validate    | 只验证，不接管，供小队整批校验                |

API 要求可信服务端线程、真实殖民地登记、已加载且未乘坐载具的 PersonBody，以及同 realm 内 128 格以内的已加载目标。战斗页在每次服务端操作时重新确认当前殖民地管理权限，并在调用 API 前验证成员归属与目标范围。其他 ResidentKind 的命令支持需要单独验证。

命令复用 Core 现有 Urge → ColonyLabor → BodyRunner 安全中断流程。旧任务释放展示工具和出行后，同一个执行器开始命令；没有第二个导航控制器。解除后执行最新计划，不重放已撤销任务。Core 仅补充过期临时 Urge 拒绝记录回收，以及通用 `Node.directTravel()` 选项。该选项使用居民现有 Locomotion 走本地路径；普通劳动仍采用原有多方式出行规划。

命令不持久化。死亡、卸载、离籍和殖民地关闭会释放控制；移动超过 1200 tick 也释放并标记 FAILED。驻守和已到达的撤退等待显式解除，期间可能压制日常需求。通过通用 `Node.staysAtWorkSite()` 约束，居民受推离开后由同一执行器重新回位。失败或旧存档句柄不代表有效控制权。

GUARD 让出同一执行器给恐惧或战斗时，不消耗移动计时；实际重新取得执行器后继续计时。从未取得执行器的命令仍保留有限等待上限。

## P3：实际 UnitGroup 与部署意图

`UnitGroups.of(colony).create(people)` 创建 1～8 人实际队伍，`ofResident(id)` 查询唯一归属。转组解除旧队伍对该成员的控制。队伍 ID、成员顺序写入殖民地账本；部署命令不持久化，重启后队伍恢复为 IDLE。

```java
var group = UnitGroups.of(colony).create(people);
group.move(new Deployment(left, right, front, new Deployment.Formation(2)));
```

Deployment 接收两个同高度、同 realm 的端点、与阵线近乎垂直的水平正面方向，以及 2～8 格间距。当前只实现单排横队和最多 8 个不同的整数阵位。未来 War 可以提交这个意图，无须逐 tick 操纵居民。

- move 按顺序让成员进入目标纵队，再恢复横队，避免后排先占位堵住前导成员。纵队阵位无站立空间时尝试直接横队。
- hold 直接建立横队；gather 在中心附近集合；release 解除全部成员命令。
- guard 建立可被战斗和恐惧安全抢占的站岗编队，使用真实 GUARD 命令并沿用队伍生命周期。
- retreat 通过实际队伍维护高优先级撤退命令，到达后驻守；其他有权管理殖民地的玩家也可从战斗页解除，旧队伍句柄不能覆盖成员后来收到的个人命令。
- 单人新命令或单人解除会让该成员退出队伍控制，旧队伍不会覆盖新句柄。
- 死亡、离籍成员移出；卸载成员保留为 missing，现有成员重排；重新加载后分配新阵位。
- 无路可走时报告 FAILED 并释放全部成员，可在开路后重新提交。没有强制传送或无限重试。

四人单格开口与恢复横队是本次具体验收场景。本实现不保证所有瓶颈、任意地形、多排、载具或舰船部署；单独的 16/32/64 总实体战斗采样不能推广为大编队性能保证。War 的快捷选择集可以重叠，实际队伍归属不能重叠。

临时纵列阵位可以在预定高度上下两格内寻找安全落脚点，最终横队仍要求精确站位。高度调整后的临时阵位不可达时，重新验证并尝试最终横队一次；最终横队仍不可达就失败释放。另有四人通过两次单格转弯及两级台阶、再返回巡逻的验收场景。

### 编队巡逻与主动交战

`group.patrol(List<Deployment>)` 接受 2～16 个同 realm 的部署点，循环执行纵列入场与横队重组。`patrolRoute()`、`patrolStop()` 和 `phase()` 提供观察；路线及交战状态不写入存档。

- GUARD 的权重为 1.5，健康交战为 2.5，恐惧为 2，显式撤退为 4。仍由同一 BodyRunner 交接，不增加战斗导航控制器。
- 携带可用武器的守卫无需先受伤即索敌；目标与交战阵位均限制在当前守卫槽 12 格内，避免无界追击。远程武器在有视线及射程内优先原地射击。
- 健康守卫短期续交战租约，队伍显示 ENGAGING、暂停路线与移动超时。低血战斗不续租，恐惧可以接管。
- 敌情解除后先返回本次阵位，现有成员全部归队并停留 20 tick 后再去下一站。死亡、失联、回归与单人撤退沿用真实队伍的成员处理。
- 所有路线点先验证，再替换现有控制。不可达时 FAILED 并释放；全员失联时保留临时路线并 WAITING，重载后从当前站重新组队。

### 战斗页与点位配置

Vanilla 通过既有 Front 扩展接口，在殖民书注册独立 `folkways:combat` 战斗页。页面可以选择已加载居民、创建或选择实际队伍，从玩家脚下或服务端验证的瞄准支撑面添加点位，再显式执行。一个点配置站岗点，两个至十六个点配置循环巡逻；每个点是阵型中心，不是部署线的一端。

页面同时提供撤退、解除及近战／弓／弩偏好。每次操作在服务端重验管理权限；失效或其他殖民地的队伍 ID、部署时失联的成员、无效地形和越界目的地均不能接管控制。含卸载成员的队伍仍可解除。点位草稿保留在当前服务端会话中，重启不会恢复运行命令。此页不增加 War 框选工具或快捷键。

## P4：实际原版武器会话

`plugins.combat.weapon` 公开 `WeaponAdapter`、`WeaponSession` 和 `Weapons`。`Weapons.register(id, adapter)` 在模组初始化时注册独立适配器、拒绝重复 ID；默认提供真实近战、弓和弩实现，没有枪械桩。

| API／状态                | 实际行为                                                                          |
| ------------------------ | --------------------------------------------------------------------------------- |
| accepts／usable／range   | 识别物品、真实弹药或已装填弩，以及有效射程；无弹远程武器可退选库存中的剑          |
| open                     | 从真实 Pack 移出唯一物品到独占主手，不复制装备；由当前劳动节点取得并推进          |
| READY／FIRING／RELOADING | tick 驱动近战挥击、拉弓、弩原生装填与发射，每次执行检查敌意、视线、距离与生命周期 |
| stop／STOPPING           | 终止新射击、清除蓄力；原版停止无需异步等待，不调用会误射箭的 releaseUsingItem     |
| close／CLOSED            | 幂等归还真实装备；背包满时仅掉落一次                                              |

工作展示副本与真实主手会话分开管理。弓复用原版 ProjectileWeaponItem 的 draw/shoot；弩复用原生 releaseUsing/performShooting，真实消耗箭、保存装填组件与修改耐久。Native 会话的 stop 是终止操作，继续射击须取得新会话；已发出的投射物继续按原版飞行。

Entity 在 addAdditionalSaveData 前序列化附件，因此 ResidentEntity 在 saveWithoutId 入口先停止并归还武器，保证 Pack 快照仅拥有一份。卸载、离籍、死亡及殖民地撤销均终止会话；弩已装弹组件随真实物品保存。

原生持械会话目前支持 ResidentEntity。其他 ResidentKind 保留原有 ToolUse 近战及已声明的裸手战斗，不因携带剑或弓而失去原有攻击；真实持械会话需所属扩展接入。TaCZ、Point Blank 与异步持续枪械动作仍需各模块实际适配与验证。衣甲仍由 Core Equipment 管理。

### 有限巡逻补给

`PatrolSupplies.of(colony)` 提供 `loadout(body, Loadout)`、`clear(body)`、`preference(id)` 与 `status(id)`。显式偏好优先选择已携带且可用的相应真实武器；缺失或不可用时沿用原有回退策略。改变偏好会安全关闭当前原生会话，再允许新选择射击。

守卫和当班巡逻员最多补一把指定武器与八支箭，已装入弩的弹药也计入。已有弓弩可直接推断弹药补充，无须新设偏好。补给 Urge 让位于战斗、恐惧及撤退，实际走到 32 格内的登记仓库，在领取时重查访问规则、库存预留、真实容量与现货。全程沿用同一个 BodyRunner 和 Staged 结算并保留物品组件。单次行程上限 200 tick；缺货和满背包有可查询状态及有限退避重试，新命令撤销旧补给请求。PackKeeps 让有限装备在普通返存时继续由居民保管。

补给目标不会移除玩家原先给予的其他武器。第三方库存适配器在修改后抛错时，需要可读取同组件物品数量才能回滚；任意不可读或受外部异步修改的库存不属于已验证兼容范围。

弓弩通过通用 `CoreSettings.defaultTool(ItemSpec)` 声明默认许可，不加入每名巡逻员必备的职业工具。已保存的殖民地工具列表继续有效；旧殖民地需要在生产设置中允许弓弩，仓库还需有相应现货。

Core 的声明期扩展 `KitRules.supplyPolicy(owner, policy)` 只决定普通自动装备配送能否参与。Vanilla 在手动命令或真实武器借用期间让这类配送暂停；原有请求通过正常撤销流程交还预留，控制解除后恢复。Survey 观察策略变化，即使背包未变也重新读取装备需求。该策略不改变工具保留，也不跳过其他任务的库存预留。

## 验证入口

```sh
./gradlew check
./gradlew -PgameTestMod=vanilla runGameTestServer
./gradlew -PcookeryTests=true -PgameTestMod=vanilla runGameTestServer
./gradlew -PgameTestMod=core runGameTestServer
npm run test:unit
npm run typecheck
npm run test:e2e -- tests/e2e/native-hostility.spec.ts tests/e2e/resident-commands.spec.ts tests/e2e/combat-hostility-policy.spec.ts tests/e2e/formation-patrol.spec.ts tests/e2e/weapon-sessions.spec.ts --workers=1
npm run test:e2e -- tests/e2e/combat-page.spec.ts tests/e2e/combat-supplies.spec.ts tests/e2e/combat-endurance.spec.ts tests/e2e/formation-terrain.spec.ts tests/e2e/combat-golems.spec.ts --workers=1
```

GameTestServer 默认关闭全局劳动执行。LaborFixture 仅在测试自己的殖民地中显式推进真实劳动和出行，不改变实际游戏运行逻辑。客户端场景覆盖怪物伤害、恐惧接管、命令、窄路编队和保存后重启。

`FOLKWAYS_COMBAT_BENCH=1` 时可选运行 `combat-performance.spec.ts`，采集 16/32/64 总实体（居民与僵尸各半），预热 200 tick、采样 100 tick。居民使用真实铁剑和 GUARD 命令，双方设为 1024 生命以在交战中保持实体数量。输出引擎与包含 Post 阶段的完整 tick 配对样本及实际索敌、寻路数量；没有预设性能通过线，也不代表所有地形和模组组合。

平台、EULA、依赖、CI 矩阵与日志位置见[测试指南](testing.md)。本地 Windows SDK 结果和 GitHub Linux CI 分别记录，未运行的场景不记为通过。

可选 War 模组已实现殖民地袭击、破坏额度、火焰来源、劫掠和战报，详见[殖民地袭击](raids.md)。更广泛的 RTS 操作和枪械集成仍为后续工作。
