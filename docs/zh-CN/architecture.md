# 架构说明

[文档](README.md) · [English](../en/architecture.md)

## 模组划分

Folkways 包含**两个必装模组和三个可选扩展**，分别对应 `mods/` 下的 Gradle 子项目。
它们有独立的模组 ID，共用 `folkways` 内容命名空间。
根项目负责运行测试和收集 jar，不会构建第六个模组。

| Gradle 项目                             | 模组 ID                  | 职责                                                     |
| --------------------------------------- | ------------------------ | -------------------------------------------------------- |
| `:core` → `mods/core`                   | `folkways_core`          | 殖民地引擎、公共 API、界面与教程；单独安装不提供居民工作 |
| `:vanilla` → `mods/vanilla`             | `folkways_vanilla`       | 居民及其在原版方块中的工作                               |
| `:create` → `mods/create`               | `folkways_create`        | 列车、调度、电梯、轨道接缝与机械动力蓝图交接             |
| `:modulargolems` → `mods/modulargolems` | `folkways_modulargolems` | 傀儡居民                                                 |
| `:cookery` → `mods/cookery`             | `folkways_cookery`       | 原生 Cookery 农业、厨具、共享工具、森罗酒馆酿酒和教程    |

```mermaid
flowchart BT
    Vanilla --> Core
    Create --> Vanilla
    ModularGolems[Modular Golems] --> Vanilla
    Cookery --> Vanilla
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

## 增量地形证明

`Grounds.Watch.now(body)` 保留每个身体自己的普通可达起点。`Ground.beginAccess(exits)` 的显式出口
证明只在移动方式、实体类型、体型、步高／跌落限制、火焰免疫、铁轨／禁行区域起点规则及寻路代价
全部一致时共享地形分区和搜索结果；虚拟落块及出口集合也属于缓存键。`Ground.with(changes)` 不修改世界。
铁轨起点权限还包含实际起点位置；含铁轨的虚拟落块按身体起点保守隔离。自定义移动方式在能够完整
声明其求值器依赖的能力前，按身体单独缓存。

每个殖民地 tick 为所有规划循环共用一个 `Ground.Budget`，调用 `Access.advance` 续算。
分区扫描和图搜索都消耗预算。纳秒数非正值表示只限制工作量；`unlimited()` 保留同步 `accessFrom`
的行为。搜索未完成时不能将未证明可达当成不可达。对尚未扫描的位置查询 `standable` 前，先调用
`Ground.prepare(feet, budget)`。
达到固定格数上限时 `Access.complete()` 也会返回真，已经证明的正向路线仍可使用；
只有 `Access.exhaustive()` 为真时，负距离才能证明两处不连通。

地形写入、区块可用性、移动装置障碍及禁行规则变化会更新 `Watch.revision()`，使旧证明失效。
动态来源实现 `Keepout.refresh(level)`，在其返回结果改变时调用 `Keepouts.changed()`；安装或移除来源
也会更新版本。每批身体视图开始及实际落块前调用一次 `Watch.refresh()`，检查同 tick 内的环境变化；
普通身体视图共用每个服务器 tick 一次的环境扫描。
已完成的 `Access` 描述建立时的移动能力，原身体移动后仍可共享；调用者还须通过 `Ground.valid()`
检查身体能力，并在实际落块前取得当前身体视图。所有世界读取和最终复核均在服务器线程执行。
缓存有固定条目／格数上限；淘汰不会取消调用者持有的搜索，关闭监视后清空查询缓存。
`Watch.stats()` 无需开启聊天日志即可读取累计请求、命中、分区扫描、工作量、失效次数、环境扫描次数和当前缓存大小。

## 生命周期与测试归属

[`ColonyLifecycle`](../../mods/core/src/main/java/io/github/izakyl/folkways/core/api/colony/ColonyLifecycle.java)
提供迁移与关闭钩子。关闭监听器通过
[`Cleanup`](../../mods/core/src/main/java/io/github/izakyl/folkways/base/task/Cleanup.java)
隔离普通失败，避免一个阶段失败后跳过其余清理阶段。修改生命周期逻辑时应保留这一隔离行为，
对应回归场景见 [`ClosingIsolationGameTests`](../../mods/core/src/gametest/java/io/github/izakyl/folkways/core/engine/colony/ClosingIsolationGameTests.java)。

测试放在行为所属的模组中，包括需要访问包内成员的测试。
Core 的 `src/gameTestFixtures` 提供不注册测试的共享辅助代码，因此其他模组复用准备逻辑时不会顺带发现 Core 的测试。
选择某个 GameTest 所属模组，不代表移除该场景所需的其他运行时模组。
筛选参数和 CI 环境见[测试指南](testing.md)。
