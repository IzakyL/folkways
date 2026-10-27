# 架构说明

[文档](README.md) · [English](../en/architecture.md)

## 模组划分

Folkways 包含**两个必装模组和八个可选扩展**，分别对应 `mods/` 下的 Gradle 子项目。
它们有独立的模组 ID，共用 `folkways` 内容命名空间。
根项目负责运行测试和收集 jar，不会构建自己的模组。

| Gradle 项目                             | 模组 ID                  | 职责                                                          |
| --------------------------------------- | ------------------------ | ------------------------------------------------------------- |
| `:core` → `mods/core`                   | `folkways_core`          | 殖民地引擎、公共 API、界面与教程；单独安装不提供居民工作      |
| `:vanilla` → `mods/vanilla`             | `folkways_vanilla`       | 居民及其在原版方块中的工作                                    |
| `:create` → `mods/create`               | `folkways_create`        | 列车、调度、电梯、轨道接缝与机械动力蓝图交接                  |
| `:modulargolems` → `mods/modulargolems` | `folkways_modulargolems` | 傀儡居民                                                      |
| `:rts` → `mods/rts`                     | `folkways_rts`           | 可选直接战斗命令、小队及战术界面                              |
| `:cookery` → `mods/cookery`             | `folkways_cookery`       | 原生 Cookery 农业、厨具、共享工具、森罗酒馆酿酒和教程         |
| `:warfare` → `mods/warfare`             | `folkways_warfare`       | 可选 Superb Warfare 武器与武器站                              |
| `:tacz` → `mods/tacz`                   | `folkways_tacz`          | 可选 TACZ 枪械与配置                                          |
| `:raid` → `mods/raid`                   | `folkways_raid`          | 可选殖民地袭击、额度、劫掠与战报                              |
| `:firefighting` → `mods/firefighting`   | `folkways_firefighting`  | Create: FireFighting Additions 0.2.5-beta-fix2 的消防工具整合 |

```mermaid
flowchart BT
    Vanilla --> Core
    Create --> Vanilla
    ModularGolems[Modular Golems] --> Vanilla
    Cookery --> Vanilla
    RTS --> Vanilla
    Raid --> Vanilla
    Warfare --> Vanilla
    TACZ --> Vanilla
    Firefighting --> Vanilla
    Firefighting --> Create
```

为正确理解已存在的世界结构所需的兼容逻辑放入 Core，例如 Sable 子世界和机械动力装置。
为某个模组新增工作能力的集成则放入独立扩展。

## 源码位置

下表 Java 包路径均相对于各模组的 `src/main/java/io/github/izakyl/folkways/`：

| 模组 / 包                        | 内容                                                                        |
| -------------------------------- | --------------------------------------------------------------------------- |
| Core / `base`                    | 声明账本、NBT 持久化和 Mixin 插件基础                                       |
| Core / `effect`                  | 居民属性、标记和修饰项；特长位于 `effect/perk`                              |
| Core / `library`                 | 数据包与散装文件内容库                                                      |
| Core / `core`                    | 居民、规划、劳动与出行；公共 API 位于 `core/api`，世界适配位于 `core/shell` |
| Core / `front`                   | 界面、殖民书、网络与教程；公共 API 位于 `front/api`                         |
| Vanilla / `plugins`              | 基于公共 API 实现的农业、牧场、建筑等插件                                   |
| Create / `create`                | 列车、调度、`create/elevator` 与 `create/build`                             |
| Modular Golems / `modulargolems` | 傀儡居民                                                                    |
| Cookery / `cookery`              | 原生厨具和农业适配、工具、`cookery/tavern` 与教程                           |

内置样式位于 [`mods/vanilla/src/main/resources/data/folkways/folkways/pattern/`](../../mods/vanilla/src/main/resources/data/folkways/folkways/pattern/)。
集成依赖与模组 ID 以各模组的 `build.gradle` 和 `src/main/resources/META-INF/neoforge.mods.toml` 为准。

## 显示文本搜索

[`TextSearch`](../../mods/core/src/main/java/io/github/izakyl/folkways/front/api/ui/TextSearch.java)
是物品选择器、目录选择器、居民分工和库存共用的显示文本匹配入口。
客户端初始化时按需安装 JEC 适配器；文字直接命中和纯 ASCII 文本未命中时都不会调用 JEC。
适配器复用 JEC 的字典和设置，在设置变化时更新当前线程的拼写缓存。
JEC 及其 PinIn 库只用于编译，不随 Folkways 内嵌。

[`ItemQuery`](../../mods/core/src/main/java/io/github/izakyl/folkways/core/api/terms/ItemQuery.java)
的显示名称搜索重载接受 `BiPredicate<String, String>`，参数依次为名称和已转为小写的查询词。
只有普通查询词使用该匹配器；模组 ID、标签、完整物品 ID 和通配符保留注册标识符语义。
已保存的规则使用不带显示名称的原有重载，因此客户端语言或可选集成不会改变服务器的筛选行为。

## 生命周期与测试归属

Vanilla 的日常防御与可选 RTS 模块的居民命令、实际小队见[战斗与编队说明](combat-architecture.md)。
RTS 依赖 Vanilla 的武器和防御 API，命令复用 Core 安全劳动中断；枪械兼容仍待独立模块实现。

[`ColonyLifecycle`](../../mods/core/src/main/java/io/github/izakyl/folkways/core/api/colony/ColonyLifecycle.java)
提供迁移与关闭钩子。关闭监听器通过
[`Cleanup`](../../mods/core/src/main/java/io/github/izakyl/folkways/base/task/Cleanup.java)
隔离普通失败，避免一个阶段失败后跳过其余清理阶段。修改生命周期逻辑时应保留这一隔离行为，
对应回归场景见 [`ClosingIsolationGameTests`](../../mods/core/src/gametest/java/io/github/izakyl/folkways/core/engine/colony/ClosingIsolationGameTests.java)。

测试放在行为所属的模组中，包括需要访问包内成员的测试。
Core 的 `src/gameTestFixtures` 提供不注册测试的共享辅助代码，因此其他模组复用准备逻辑时不会顺带发现 Core 的测试。
选择某个 GameTest 所属模组，不代表移除该场景所需的其他运行时模组。
筛选参数和 CI 环境见[测试指南](testing.md)。

## 战斗配装

Vanilla 负责 `LoadoutProfiles`、不可变 `LoadoutProfile`／`Entry`、
`LoadoutRegistry`／`LoadoutProvider`、聚落书「配装」页和居民有限领取。
多名居民可共用一份聚落配装，但每人各需完整实物。
有序 `ItemSpec` 条目支持组件身份，分别定义武器、四个护甲栏位及带目标／阈值的补给。
Core 保留护甲所有权；可选 RTS 只显示分配，并复用目标相关的 `Weapons.select` 和独占 `WeaponSession`。

`LoadoutProvider` 说明实物识别、弹药身份、远程／射程、弹匣容量、已装弹量、周期和建议数量，
不再决定居民携带内容。添加武器会预填可编辑补给值。
原生执行仍由 `WeaponAdapter`／`WeaponSession` 负责，枪械模块分别实现参数和真实执行。
`profile.hasRanged()` 供后续哨所任务查询。

沿用 `folkways:patrol_loadouts` 账本，第三版用 `profiles` 保存
`id`、`name`、`entries` 和可选原始 `legacy`，用 `assignments` 将 `resident` 指向 `profile`。
旧枚举和提供者标识／选项一次迁移去重；未知扩展偏好在不可用时仍完整往返保存。
巡逻的聚落设置决定无配装策略，默认逃跑；使用已有装备迎战也不领取新装备。
战斗不再依赖职业武器工具默认值，其他职业白名单不变。
完整格式、界面、生命周期、审计验收及暂缓的副手所有权见[战斗架构](combat-architecture.md)。

哨位由原版巡逻职业拥有，使用核心区域工具与持久化派驻记录。共享目击、支援呼叫与固定武器租约均为临时状态，
不依赖可选 RTS；详见[战斗架构](combat-architecture.md)。

## 额外俯瞰工具

插件用 `ToolKinds` 注册专用委托，并用 `OverheadTools` 注册客户端控制器。
Core 提供工具栏、框选编辑、专注显示和悬停卡片；专注显示只屏蔽普通图层，不改写其偏好设置。
Vanilla 负责损害管制控制器及有数量限制的命令与状态数据包；基准保存和工作行为仍沿用现有 presence 与 colony front。
