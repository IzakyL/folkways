# 自定义内容与配置

[文档](README.md) · [English](../en/custom-content.md)

## 内容库

数据包中的内容放在 `data/<namespace>/folkways/<shelf>/`；
服务器也可以从 `<gamedir>/folkways/<shelf>/` 读取散装文件。
服务器目录会作为 `local` 命名空间的额外数据包加载，修改后执行 `/reload` 即可重载。

使用 `/folkways library` 查看各内容分类、条目、来源数据包，以及被拒绝加载的文件与原因。

| 分类             | 文件                                                        | 用途                                                                   |
| ---------------- | ----------------------------------------------------------- | ---------------------------------------------------------------------- |
| `pattern`        | `<name>.star`                                               | 使用 [Starlark](https://github.com/bazelbuild/starlark) 编写的建筑样式 |
| `piece`          | `<name>.nbt`                                                | 由样式的 `template(...)` 调用拼放的结构片段                            |
| `blueprint`      | `<name>.nbt`                                                | 在**蓝图**页选择后，瞄准、旋转并摆放的完整结构                         |
| `resident_skin`  | `<name>.png`，可选带 `arms` 和 `weight` 的 `<name>.json`    | 居民皮肤；服务器会将纹理发送给玩家，无需资源包                         |
| `resident_model` | `<name>/main.json`、`main.animation.json`、`textures/*.png` | GeckoLib 或未加密的 YSM 模型，每张纹理形成一个外观                     |

可以参考[内置建筑样式](../../mods/vanilla/src/main/resources/data/folkways/folkways/pattern/)。
样式文本与共享库字典遵循[翻译约定](../../CONTRIBUTING.zh-CN.md#翻译与建筑样式)。

客户端的 `folkways/pattern/`、`folkways/piece/` 和 `folkways/blueprint/` 构成玩家自己的内容库。
图纸在本地准备，再将结果发送给服务器；这与服务器数据包提供的内容分开。

车万女仆模型包的安装方法见[玩法指南](gameplay.md#女仆模型包)。

## 居民数据

数据包可以调整居民的饮食、居住和特长：

| 数据                                                                                                   | 用途                                                                                                        |
| ------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------- |
| `folkways:resident_food` 及其 `_vanilla`、`_cookery`、`_farmers_delight` 物品标签                      | 默认随身口粮，见[随身口粮与食物名单](gameplay.md#随身口粮与食物名单)                                        |
| `folkways:unsafe_food` 物品标签                                                                        | 启用安全检查时拒绝的食物                                                                                    |
| `folkways:food_groups/*`（meat、fish、stew、grain、vegetable、fruit）与 `folkways:fine_meals` 物品标签 | 心情中食物一项的多样性与精致餐点                                                                            |
| `folkways:husbandry_products` 物品标签                                                                 | 「物尽其用」特长会增加的屠宰掉落                                                                            |
| `folkways:housing/*` 方块标签                                                                          | 住房评级使用的陈设类别和生产方块；桌子标签默认为空                                                          |
| `folkways:prospecting_stone`、`folkways:prospectable_minerals` 方块标签                                | 「寻矿」适用的石头和可找到的矿物；后者包含 `c:ores`                                                         |
| `data/<配方命名空间>/crafting_perks/<配方路径>.json`                                                   | 让某个配方参与「节料」返还和「副产物」，例如 `{"refund": true, "byproduct": "minecraft:stick", "count": 1}` |
| `folkways:gameplay/fishing_discoveries` 战利品表                                                       | 「寻宝」的发现，带有实际钓鱼位置，可按生物群系和维度设置条件                                                |

容器和方块名称的后备规则也能识别常见的储物、地毯、盆栽和桌子方块。默认的制作特长规则只覆盖部分普通配方，可逆的存储和打包配方不参与。

居民皮肤有专门的娱乐动作。自定义模型清单和外观条目可以通过 `leisure_animations` 对象映射 `chatting`、`petting`、`relaxing` 和 `strolling`；
缺少的动画会退回基础的待机和头部动作。

## 服务器配置

每个世界的配置位于 `<world>/serverconfig/`：

| 文件                           | 控制内容                             |
| ------------------------------ | ------------------------------------ |
| `folkways_core-server.toml`    | 殖民地引擎                           |
| `folkways_vanilla-server.toml` | 居民在原版方块中的工作               |
| `folkways_create-server.toml`  | 列车与调度，需安装 Folkways × Create |

堆肥偏好和种子保留量在每个殖民地的**堆肥设置**页调整，详见[堆肥](gameplay.md#堆肥)。
