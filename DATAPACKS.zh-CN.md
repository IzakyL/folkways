# 数据包、标签与资源库文件夹

[English](DATAPACKS.md) · 简体中文

> **预发布阶段：** 数据包格式、文件夹结构和标签 ID 仍可能随版本变化，更新后请重新核对本页。

## 资源库文件夹

服务器会把 `<游戏目录>/folkways/` 当作一个额外的数据包读取。这个数据包名为“Folkways 资源文件夹”，始终启用；其中的文件以 `local` 命名空间载入，不会与其他数据包的 ID 冲突。不同种类的文件放在各自的分类文件夹中：

| 分类 | 文件夹 | 数据包中的对应位置 | 内容 |
|---|---|---|---|
| `pattern` | `<游戏目录>/folkways/pattern/` | `data/<ns>/folkways/pattern/` | Starlark 建筑样式（`.star`） |
| `piece` | `<游戏目录>/folkways/piece/` | `data/<ns>/folkways/piece/` | 供样式拼接使用的结构构件（`.nbt`） |
| `blueprint` | `<游戏目录>/folkways/blueprint/` | `data/<ns>/folkways/blueprint/` | 整栋建筑，在“蓝图”页放置（`.nbt`） |
| `resident_model` | `<游戏目录>/folkways/resident_model/` | `data/<ns>/folkways/resident_model/` | 基岩版格式的居民模型 |
| `resident_skin` | `<游戏目录>/folkways/resident_skin/` | `data/<ns>/folkways/resident_skin/` | 玩家布局的皮肤（`.png`） |

- 文件夹可以多层嵌套。文件的 ID 是它在分类文件夹内的路径（去掉后缀）。例如 `<游戏目录>/folkways/blueprint/farm/barn.nbt` 的 ID 是 `local:blueprint/farm/barn`。
- 文件名可以使用任意字符，包括大写字母、空格和中文。资源 ID 只允许 `a-z 0-9 _ - .`，所以其他名字在内部会编码成以 `__utf8_` 开头的形式；书和选择列表中仍显示原名。本来就是小写的名字保持不变。
- 启动时会自动创建各分类文件夹。修改文件后执行 `/reload` 生效。
- `/folkways library`（需要管理员权限）会显示文件夹路径，点击即可打开；随后按分类列出各数据包载入的条目数，以及每个被拒绝的文件和原因。

### 玩家自己的文件夹

玩家客户端也会读取自己的 `<游戏目录>/folkways/`，但只用于建造：

- **样式和构件：** 玩家自己的文件会加到服务器提供的内容之中。如果 ID 与服务器的相同，以玩家的为准。
- **蓝图：** 显示在“蓝图”页的“我的文件夹”下，放置时上传到服务器，单个文件上限为 2 MiB。
- **居民模型和皮肤只来自服务器。** 客户端会把服务器发来的内容缓存在 `<游戏目录>/folkways/.cache/resident_models/`，请勿手动修改这个缓存。

### 各分类的规则与上限

**`pattern`（`.star`）**
- 样式需要定义 `draw(site)`。没有 `draw` 的文件视为共享库，供其他样式用 `load()` 引用，例如 `load("folkways_vanilla:materials.star", "NAME")`。
- 样式还可以设置 `knobs`、`lang`、`accepts`（例如 `["zone"]` 或 `["path"]`）、`phases`、`capture`、`detail` 和 `grain`。`detail = "bits"` 需要 Carving 联动。
- 载入时最多执行 200,000 步 Starlark；绘制时间受 `blueprints.drawSeconds` 限制（默认 30 秒）。
- 服务器发给每名玩家的内容有以下上限：
  - 每个样式 131,072 个字符；
  - 最多 256 个样式；
  - 总计 900,000 字节。
- 样式自身文本的翻译方式见 [CONTRIBUTING.zh-CN.md](CONTRIBUTING.zh-CN.md#翻译与建筑样式)。

**`piece`（`.nbt`）**
- 原版结构文件，压缩或未压缩均可，每个上限 8 MiB。
- 样式通过 `template("ns:name", ...)` 使用构件。
- 构件和蓝图合计最多向玩家发送 256 个。

**`blueprint`（`.nbt`）**
- 原版结构文件，整体放置，不会被样式拼接。
- 放置时按 `blueprints.maxBuildCells` 和 `blueprints.maxBlueprintVolume` 检查尺寸。
- 使用“采集蓝图”可以把选中的区域按指定名字保存。服务器会同时写入 `<游戏目录>/folkways/blueprint/<名字>.nbt` 和 `<游戏目录>/folkways/piece/<名字>.nbt`。名字已被占用或文件超过 2 MiB 时会拒绝保存。

**`resident_model`（每个模型一个文件夹）**

```text
resident_model/<模型>/
  main.json              基岩版几何模型
  main.animation.json    至少包含一个动画
  textures/*.png         每张贴图各自成为一种外观
  folkways.json          可选
```

- `folkways.json` 可以设置：
  - 动画片段名 `idle_animation`、`walk_animation`、`work_animation` 和 `seated_animation`；
  - `leisure_animations`；
  - `weight`（被抽中的权重，默认 1）；
  - `fallback` 备用皮肤。
- 没有 `folkways.json` 时，默认片段名沿用 Yes Steve Model：`idle`、`walk`、`swing_hand`，以及 `ride` 或 `sit`。
- 外观 ID 为 `<ns>:model/<模型>/<贴图名>`。
- 模型通过 GeckoLib 渲染；客户端没有安装 GeckoLib 时显示备用皮肤。

**`resident_skin`（`.png`，可附 `.json`）**
- 使用玩家皮肤布局：正方形，宽度为 64 的整数倍（64、128……）。
- 同名的 `<名字>.json` 可以写 `{"arms": "slim", "weight": 2}`；不写时会根据像素自动判断细手臂还是粗手臂。
- 外观 ID 为 `<ns>:skin/<名字>`。
- 模型和皮肤共用同一份额度：单个文件上限 8 MiB，合计上限 32 MiB。

## 数据包注册表

以下是数据包注册表，在载入世界时读取，`/reload` 不会更新它们。

| 注册表 | 文件夹 | 内容 |
|---|---|---|
| `folkways_vanilla:resident_look` | `data/<ns>/folkways_vanilla/resident_look/` | 一种外观，可以是皮肤或模型 |
| `folkways_vanilla:resident_look_set` | `data/<ns>/folkways_vanilla/resident_look_set/` | 一组外观，殖民地可在“模型池”页选用 |
| `folkways_vanilla:resident_name` | `data/<ns>/folkways_vanilla/resident_name/` | 名字和姓氏，供“姓名池”页使用 |

```json
{"type": "skin", "texture": "mypack:textures/entity/farmer.png", "arms": "slim", "weight": 2}
```

- 外观用到的贴图和模型属于客户端资源，需要由资源包提供。
- 模型外观还需要 `geometry`、`animations`、`texture` 和一个 `fallback` 备用皮肤。
- 注册表外观与资源库文件夹中的外观 ID 相同时，以注册表为准。

```json
{"looks": ["mypack:farmer", "local:skin/farmers/wren"]}
```

```json
{"names": ["Ada", "Bram"], "surnames": ["Holt", "Ward"]}
```

- 每个名字 1–16 个字符，`surnames` 可省略。
- 新殖民地从所有姓名组合并后的列表中抽取。
- 要替换内置名字，覆盖 `folkways_vanilla:default` 即可。

## 其他数据文件

| 文件 | 作用 |
|---|---|
| `data/<配方命名空间>/crafting_perks/<配方路径>.json` | 居民工匠拥有对应专长时，为某个配方返还材料或产出副产品，例如 `{"refund": true, "byproduct": "minecraft:wheat_seeds", "count": 1}`。没有对应文件的配方不会获得这些效果。 |
| `folkways_vanilla:gameplay/fishing_discoveries`（战利品表） | 钓鱼时的宝藏发现。稀有鱼专长抽取的是 `minecraft:gameplay/fishing`，修改该表同样生效。 |
| `folkways_core:colony_book`（配方） | 殖民之书的配方。 |

## 标签

内置标签都没有使用 `"replace": true`，数据包可以直接追加条目。样式和殖民地设置中的物品过滤器也接受任意 `#标签`。

**仓库与网络**

| 标签 | 类型 | 作用 |
|---|---|---|
| `folkways_core:store/allowed` | 方块 | 允许普通 `Container` 方块纳入为仓库 |
| `folkways_core:store/refused` | 方块 | 永不作为仓库，优先于所有联动和 `store/allowed` |
| `folkways_core:store/candidates` | 方块 | 客户端提示哪些方块可以纳入 |
| `folkways_core:network_access/take`、`/put`、`/craft` | 方块 | AE2、RS2 或 QIO 设备最多能做什么，再由“网络设备”页收窄（见 [INTEGRATIONS.zh-CN.md](INTEGRATIONS.zh-CN.md)） |

**居民与移动**

| 标签 | 类型 | 作用 |
|---|---|---|
| `folkways_core:no_footing` | 方块 | 居民永远不会站在这些方块上 |
| `folkways_core:climbable` | 方块 | 居民像梯子一样攀爬这些方块 |

**食物与心情**

| 标签 | 类型 | 作用 |
|---|---|---|
| `folkways_vanilla:resident_food` | 物品 | 殖民地食物设置的默认值，由 `resident_food_vanilla`、`_cookery` 和 `_farmers_delight` 组成 |
| `folkways_vanilla:unsafe_food` | 物品 | 永远不会被食用 |
| `folkways_vanilla:fine_meals` | 物品 | 吃到后心情提升 |
| `folkways_vanilla:food_groups/{meat,fish,stew,grain,vegetable,fruit}` | 物品 | 用于心情的饮食多样性；未加标签的食物算作“其他” |

**住房与用餐**

| 标签 | 类型 | 作用 |
|---|---|---|
| `folkways_vanilla:housing/{storage,textile,decoration,table,lighting}` | 方块 | 用于房间评级的家具；也会按名字推测 |
| `folkways_vanilla:housing/production` | 方块 | 工作方块；房间里有这类方块时算作混用 |
| `folkways_vanilla:dining/tables` | 方块 | 餐桌 |
| `folkways_vanilla:dining/chairs` | 方块 | 座位；下半台阶始终算作座位 |

**耕作、采集与整地**

| 标签 | 类型 | 作用 |
|---|---|---|
| `folkways_vanilla:weeds` | 方块 | 农田开启清除杂草时清除 |
| `folkways_vanilla:flowers` | 方块 | 农田开启清除花朵时清除 |
| `folkways_vanilla:tillable_under_weeds` | 方块 | 清除杂草后可以耕地的地面 |
| `folkways_vanilla:forage` | 方块 | 采集工清理并收获的植物 |
| `folkways_vanilla:fallen_logs` | 方块 | 倒木，`#minecraft:logs` 之外的补充 |
| `folkways_vanilla:fallen_log_decorations` | 方块 | 清理倒木时一并清除的装饰 |
| `folkways_vanilla:husbandry_products` | 物品 | 屠宰时的额外掉落（屠夫专长） |

**战斗**

| 标签 | 类型 | 作用 |
|---|---|---|
| `folkways_vanilla:resident_enemies` | 实体类型 | 居民会主动攻击的生物，包含 `undead_resident_enemies` |
| `folkways_vanilla:patrol_targets` | 实体类型 | 巡逻队视为威胁的非敌对生物 |
| `folkways_vanilla:ignored_resident_enemies` | 实体类型 | 永不攻击，优先于以上两个标签 |
| `folkways_vanilla:luminous_targets` | 实体类型 | 像发光一样容易被发现 |

**建造与修复**

| 标签 | 类型 | 作用 |
|---|---|---|
| `folkways_vanilla:prospectable_minerals` | 方块 | 勘探专长可能找到的矿石 |
| `folkways_vanilla:prospecting_stone` | 方块 | 计入勘探的石头 |
| `folkways_vanilla:damage_control_excluded` | 方块 | 不记录到修复基准中 |

**扩展模组**

| 标签 | 类型 | 作用 |
|---|---|---|
| `folkways_cookery:cookery_sickles` | 物品 | Cookery 收割模式使用的镰刀 |

Folkways 还会读取 `c:foods`、`c:tools` 和 `c:tools/melee_weapon`。

## 配置文件

服务器设置位于各世界的 `serverconfig/`：

- `folkways_core-server.toml`
- `folkways_vanilla-server.toml`
- `folkways_create-server.toml`
- `folkways_firearms-server.toml`
- `folkways_carving-server.toml`

客户端设置位于 `config/`：`folkways_core-client.toml`、`folkways-windows.properties` 和 `folkways-drawing-settings.properties`。
