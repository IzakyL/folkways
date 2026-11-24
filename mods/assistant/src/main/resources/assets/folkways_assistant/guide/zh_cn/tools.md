# 助手工具

## 内联建造图样
<!-- guide:pattern -->

路径点和区域角取建筑所站的地面格，也就是用聚落之书点击的那一格。
建筑内容从 Y+1 开始，地基和地形整平也可以在标记 Y 或其下方施工。
各图样的取点说明见 `patterns()` 返回的 `anchors` 字段。
`draft()` 的 `anchors` 返回解析后的世界坐标。

用 `print(help("pattern"))` 查看绘图词汇和示例。将定义了 `draw(site)` 的 Starlark 程序传给
`draft(source=..., dimension=..., at=...)`，即可查看方块数量、范围和材料。根据诊断位置修改程序后，
将相同的源码和标记传给 `set("builds/site", {"source": ..., "dimension": ..., "at": ...})`，请玩家确认。两个工具都接受 `pattern` 或
`source`，二选一。内联图样可以加载已安装的图样库。确认后，服务器会将程序与订单一起保存。

用 `ls("builds")` 列出建造订单，读取 `builds/<id>` 查看样式、标记和已有进度，删除该路径可请求取消订单。
向 `facilities/<name>` 写入 `kind`、`dimension` 以及 `from` 和 `to` 或 `points`，可提交区域或路径，
也可同时提供初始 `parameters`。向 `surveys/<name>` 写入 `dimension` 和 `points` 可请求勘测。
新建时使用的名称标识当前脚本中的暂存项。确认后使用分配的 ID。

## 引擎概念
<!-- guide:concepts -->

用这些概念理解工具的返回结果。向玩家解释时用玩法中的说法，不要直接使用这些术语。

**纳入与设施。** 殖民地只认得它纳入的东西：用殖民书登记的方块、画出的区域和路径、网络、载具以及玩家定义的设施。
殖民地操作的一切都是设施，在 `facilities/<id>` 下读取。设施的范围独占其中所有方块和实体；
农业、建造和损害管制等自动工作会跳过这些方块。

**需求与计划。** 工作只因需求而产生：订单、库存目标、区域设置、设施规程或居民自身的需要。
计划系统定期把需求变成由节点组成的图，节点就是取货、搬运、制作、操作或建造等工作步骤。
节点需要货物、站位（工作时站的地方）、通路（到达那里的路线）和人手（有许可、有装备、有空的居民）。
计划类读取描述的是上一次计划，而不是实时世界：与世界比较前先看 `capture_tick` 和 `age_ticks`。

**结果。** `trace()` 给每个节点一个结果。`assigned`：已有居民接手。`waiting`：暂时无人可接，附未分派代码。
`stalled`：在等另一个节点，沿 `root` 找到真正卡住的节点。`refused`：短缺代码说明货物无法供应。
`cooling`：最近失败过，已尝试 `failures` 次，到 `retry_at` 再试。`structural: true` 表示计划系统本不应产生的代码。

**预留。** 计划确定使用某些货物时会预订它们，避免两项工作用到同一批物品。预订分为 lot（仓库中的货物）、
transit（正运往仓库的货物）、space（仓库中的空位）、cargo（居民背包里的货物）和 handed（上一步交给下一步的货物）。
库存计数不扣除预订，所以仓库里显示的物品可能已经许给了别的工作。

**挂起的工作。** 居民可能被从工作中拉走，例如睡觉、吃饭等需要、直接指挥、卸货或许可被撤销。
工作保留已预订的货物，之后继续；快照读取会在 `suspended` 下列出它及原因。

**设施的组成。** 操作位是可以执行操作的位置：信号设定一个值（拉杆、齿轮、角度），端口传输物品、流体或能量。
读数是设施报告的值，参数是它的设置。规程是一串操作，会变成计划中的工作，由居民像其他工作一样执行；
远程操作位不需要居民。触发启动规程：手动（卡片按钮和 `run`）、定时、条件、常驻和按需（计划拉动的工坊）。
投影告诉殖民地其他部分这个设施意味着什么：居民可通过的大门、运货的物流路线、禁行区、提供配方的工坊和受保护的方格。

**设施运行。** `recent` 记录上一次运行的规程、结束方式和原因；`runs/<id>` 记录正在运行的脚本。
卡片按钮变灰的原因：规程正在运行（`running`）、读数与 `enabled_when` 不符（`state`），
或读不到读数（`unknown`，通常因为设施未加载）。脚本故障会暂停条件触发，需要重新开启。

**火控。** 居民目击和设施探针会把敌对实体上报给殖民地。火控把目标分配给触发使用
`folkways_core:fire_control` 的设施；RTS 的攻击命令会把目标标记五秒。

**未知不等于零。** 未加载的区块、未打开的战利品、不支持的仓储，以及来自旧快照的网络数据都是未知。不要说它们是空的。

## 排查问题
<!-- guide:troubleshooting -->

工作没有进行时，按以下顺序读取，找到原因即可停止：

1. `get("colony/problems")`：殖民地已能解释的停滞。
2. `trace(vocation=..., site=...)`：相关工作的结果和代码。
3. `plan_node(id)`：节点的依赖、诊断和冷却；停滞的节点要读它的 `root`。
4. 代码指向的检查（见下文）。
5. 涉及货物时读 `reachable(item)` 和 `stock(item)`。
6. `plan_events(node=...)`：节点随时间发生的事：派发、开始、失败、释放。
7. `recent_errors()`：异常。

对发现的原因分类。代码文本指向物资、通行或工作许可的，属于配置问题：说明玩家怎样修正。
`reachable` 报告为 `unsupported_storage` 的来源属于功能限制：说明限制并建议提功能请求。
有相关异常堆栈的属于 bug：建议附上堆栈报告。可见物资充足却出现 `no_source`、代码未知，
或 `structural: true` 的代码所涉及的设置其实正确，属于疑似 bug：建议附上两边的读数报告。
认定矛盾之前，先检查快照时间和来源是否完整。

### 短缺代码

`trace()` 在 `code` 中给出小写代码，在 `reason_code` 中给出大写代码。

表示计划无法供应节点所需的货物；`items` 列出缺少的物品。

- `NO_SOURCE`：没有可达的库存，也没有办法制作。读 `reachable(item)`，查看来源和 `invisible` 下列出的原因。
- `NO_ROOM`：产物没有地方放。检查已登记的仓库是否有空位并允许放入该物品。
- `NO_WAY`：物资存在但到不了。检查 `plan_inputs(kind="ways")` 和沿途地形。
- `NO_HAND`：没有既有许可又有装备的居民能制作。检查工作许可、工具和 `plan_inputs(kind="workshops")`。
- `TURNED`：某个操作以自己的原因拒绝了工作。查看现场、设施的 `recent`，以及 `plan_events` 中的失败记录。
- `COOLING`：最近失败过，等到 `retry_at` 再试。读 `plan_events(node=...)` 查看失败。

### 未分派代码

表示工作已存在，但暂时无法交给任何人。

- `NO_FREE_HAND`：合适的居民都在忙。负载高时属正常；对比工作优先级设置。
- `NO_LICENCE`：没有居民有该工作许可。检查 `residents/<id>/work/<vocation>`。结构性。
- `NO_TOOL`：没有居民带着合适的工具。检查仓库中的工具和装备方案。结构性。
- `NO_WAY`：没有到工作地点的路线。检查沿途地形、门和闸门。
- `WAY_PENDING`：路线仍在搜索。等下一次计划。
- `PACK_FULL`：居民背包装不下这些货物。结构性。
- `PACK_BUSY`：居民要先送完身上的货物。
- `CLAIM_TAKEN`：另一位居民已接手。
- `SAME_WORKER_SPLIT`：相关步骤必须交给同一居民，但没能做到。结构性。
- `WORKER_AWAY`：指派的居民未加载或离得太远。
- `NO_FREE_STAND`：工作地点没有空闲站位。查看站位是否被挡住或占用。
- `BEYOND_HORIZON`：有人能接，但时间超出计划的前瞻范围。等之后的计划。

### 不可见来源

`reachable(item)` 在 `invisible` 下列出计划用不了的仓库，并附原因：`unloaded`（未加载）、`unauthorized`（无权限）、
`managed_by_facility`（设施端口，只供该设施的工作使用）、`unsupported_storage`（不支持的仓储）、`not_container`（不是容器）、
`unknown_contents`（未打开的战利品）、`take_disabled`（仓库不允许取出）和 `no_slots`（没有槽位）。网络来源可能报告自己的原因。

### 设施

设施没有动作时，读 `facilities/<id>/readings`、`recent` 和 `runs`。失败或中断的 `recent` 会给出原因。
条件触发被暂停时，需要把 `triggers/<name>/enabled` 重新设为 true。卡片按钮变灰的原因是 `running`、`state` 或 `unknown`，
见 `help("concepts")`。需要居民执行的规程会像其他工作一样出现在 `trace()` 中。

## 更改聚落状态
<!-- guide: changing-colony-state -->

用 `help("state")` 查询路径和值格式，用 `ls("facilities")` 列出设施。已纳入的设施与殖民地设置都使用
`facilities/<id or name>/parameters/<key>`。`get(path)` 读取值，`set(path, value)` 暂存更改，
`delete("facilities/<id>")` 释放已纳入的区域或路径。最终的更改由玩家一次确认。

```python
id = ls("residents")[0]
set("residents/" + id + "/name", "阿达")
```

## 助手客户端数据
<!-- guide: assistant-scratch -->

助手通过 `run` 执行 Starlark 脚本。使用 `print(help("client"))` 查看客户端路径。
`client/view` 保存玩家提问时的视角和准星目标。`client/marks` 保存同一时刻的殖民地手册标记，包括工具、维度、最后标记的游戏刻，以及角点或路径点。
用 `ls("client/history")` 列出旧会话，
再通过对应的路径读取会话文本。

用 `get` 和 `set` 读写当前会话的 `client/scratch`。新会话从空字典开始，
暂存数据的 UTF-8 JSON 上限为 256 KiB。写入立即生效，无需确认，后续运行可以继续使用。

启用模型的图片输入后，用 `capture()` 请求附近视角的截图。脚本成功后，客户端拍摄截图，
每次运行最多附带六张图片；超过两张时每张会缩小。
`capture(around=..., views=...)` 一次从多个方向拍摄一块区域：`around` 为 `"changes"` 时取同一脚本暂存的更改，
也可以是区域 `{"from": ..., "to": ...}` 或两个 XYZ 角点。`views` 列出相机所在的方向（`north`、`east`、`south`、
`west`、四个斜向或 `top`），或给出 1 到 5 的数量（先斜向，再俯视）；默认为四个斜向。每台相机都位于区域外，
挡在中间的方块之前，并朝向区域中心。每张图片的 `pose` 注明其方向。

在玩家审阅前先检查建造草案或设施更改。在同一脚本中暂存更改并调用 `capture(around="changes")`：
暂存的建造显示为绘制的方块，区域或路径显示为标记。检查是否与现有结构对齐、衔接是否断开、高度和朝向是否正确。
拍摄暂存更改的脚本返回 `held`，不会提交审阅。如有问题，修改后再检查，然后不带 `capture()` 运行最终脚本。

```python
set("builds/site", {"pattern": "<pattern id>", "dimension": "minecraft:overworld",
                    "points": [{"x": 0, "y": 64, "z": 0}, {"x": 24, "y": 64, "z": 0}]})
capture(around="changes", views=["north", "southeast", "top"])
```

## 助手操作设施
<!-- guide: facilities -->

用 Starlark 表达玩家想要的设施行为。标准模块或库规程已经实现了其中一部分时，可按需选用。
下方的大门示例使用可选的 `write_all` 库规程和读数比较来操作拉杆。

`controls()` 和 `mechanism()` 的 `parts` 字典简述本次结果中的部件 ID。为陌生部件编写例程前，
请使用发现的 ID 调用 `help('parts/<part id>')` 阅读完整条目。

探索会建议非空气方块、操作位和实体，移动方块使用所属结构的局部坐标。
应用前可编辑 `extent.elements`。单个方块写成 `{"space": space, "block": [x, y, z]}`，
包含边界的方盒写成 `{"space": space, "min": [...], "max": [...]}`，实体写成 `{"entity": "UUID"}`。
`space` 包含 `dimension` 或 `structure`。用 `controls(extent=...)` 检查编辑后的范围。
实体操作位的声明可直接填写探索给出的 `entity` 和 `part`。
范围只有实体时，用 `anchor_space` 和 `anchor` 指定设施的站点。

用 `controls(area)` 查找方框中的操作位，两个角都包含在内。对于移动结构，用
`controls(structure="<UUID>")` 查询整个结构，坐标采用结构内的格坐标。结果包含
`extent`、`controls`、`prefabs` 和 `overlaps`。根据部件名、坐标、信号类型和反馈
确认哪一个拉杆或齿轮箱控制门。用齿轮箱时，填写查询返回的部件名和对应信号类型接受的目标值。
候选较多时，把列表存入当前会话的 `client/scratch`，只返回数量或少量匹配项。

示例的拉杆位于 (119, 64, -30)，铁门占正东的上下两格。实际使用时请替换为所选大门的坐标。

```python
area = {"dimension": "minecraft:overworld",
        "from": {"x": 119, "y": 64, "z": -30},
        "to": {"x": 120, "y": 65, "z": -30}}
found = controls(area)
set("client/scratch/north_gate_candidates", found["controls"])
result = {"count": len(found["controls"]), "prefabs": len(found["prefabs"])}
```

核对候选对应的方块后，用它的部件名和位置定义大门。操作位的偏移以锚点为原点，
闸格使用设施所在空间的格坐标。预览会列出预制设施的覆盖变化、大门投影和保护范围。

```python
lever = [c for c in get("client/scratch/north_gate_candidates")
         if c["part"] == "lever" and (c["x"], c["y"], c["z"]) == (119, 64, -30)][0]
set("facilities/north_gate/definition", {
    "name": "north_gate",
    "extent": {"elements": [{"space": {"dimension": lever["dimension"]},
               "min": [119, 64, -30], "max": [120, 65, -30]}]},
    "anchor": [lever["x"], lever["y"], lever["z"]],
    "controls": {"lever": {"part": lever["part"], "offset": [0, 0, 0]}},
    "routines": {
        "open": {"use": "folkways_core:write_all", "with": {"lever": True}},
        "close": {"use": "folkways_core:write_all", "with": {"lever": False}},
    },
    "triggers": {
        "open_button": {"kind": "manual", "routine": "open"},
        "close_button": {"kind": "manual", "routine": "close"},
        "morning": {"kind": "schedule", "routine": "open", "dayTime": 1000, "enabled": True},
        "night": {"kind": "schedule", "routine": "close", "dayTime": 13000, "enabled": True},
    },
    "projections": [{"gate": {"cells": [[120, 64, -30], [120, 65, -30]],
                              "axes": ["x"], "open_when": {"lever": True}}}],
    "ui": {"card": [{"button": "open", "icon": "minecraft:iron_door", "enabled_when": {"lever": False}},
                    {"button": "close", "icon": "minecraft:iron_bars", "enabled_when": {"lever": True}},
                    {"toggle": "morning", "icon": "minecraft:clock"},
                    {"toggle": "night", "icon": "minecraft:clock"},
                    {"readout": "lever", "as": "light", "label": "门已打开"}]},
})
```

大门投影可以用 `axes` 指定沿 `["x"]`、`["z"]` 或两个轴通行。省略时，一排方格只能沿垂直于该排的轴通行，
单列方格可以沿两个轴通行。`open_when` 可填读数比较字典，也可填定义 `when(readings)` 的源码，例如
`"def when(readings): return readings.get('lever', False)"`。谓词返回 `True` 后才开放大门，寻路使用缓存的布尔值。
通过规程操作大门的操作位。

聚落成员都可以使用 RTS 命令卡。状态行显示正在运行的规程或最近一次运行结果。`ui.card` 列出元素：
`{"button": 规程}` 不带参数地运行一段有手动触发的规程（规程默认值仍然生效）；`{"toggle": 触发}` 开关一条定时或条件触发；
`{"readout": 读数, "as": 样式}` 以 `text`、`light`、`progress` 或 `slots` 显示读数。
按钮和开关可以用 `icon` 指定物品 ID，选能表达用途的物品（开门用门、关门用铁栏杆、定时用时钟）。
按钮、目标和选项的 `enabled_when` 与大门一样，可填比较字典或定义 `when(readings)` 的源码。
条件满足后，命令卡才启用该操作。对应规程运行期间也会禁用该操作。读数可以用 `label` 指定显示的文字代替读数名。标签和规程名使用玩家的语言。
卡片上的名称都要短：用一两个词说出动作或状态，例如“挖掘”“开门”“已停驻”，不要写成说明用途的句子。
向玩家介绍卡片用法时也用这些名称。

为带 `target` 参数的手动规程添加 `{"target": 规程, "pick": "entity|pos"}`。
点击命令卡槽位，再用左键点击世界中的实体或方块，按 Esc 可取消。`pick` 为 `entity`
时传入实体 UUID，为 `pos` 时传入方块的世界坐标 `[x, y, z]`，为 `entity|pos` 时两者皆可，
优先取实体。`item` 传入掉落物的 UUID，`block` 传入点击的方块坐标，`ground` 传入方块上方可站立的位置。
`box` 用于选取区域：玩家点击两个方块作为对角，`target` 为 `[x0, y0, z0, x1, y1, z1]`，最低角在前；区域不能作为右键默认目标。
无论哪种 `pick`，选中的值都只以 `args["target"]` 传入，不会用其他名字；规程从这里读取，试运行时也以 `target` 传入。
选中设施后右键世界，命令卡会使用第一个设置了 `default: True` 且接受光标下目标的元素。
目标元素支持 `icon`、`label` 和 `enabled_when`，规程运行时禁用。

条件触发可写为 `{"kind": "condition", "routine": "close", "enabled": True,
"when": {"lever": True}, "period": 20, "cooldown": 100, "hysteresis": 2}`。
`when` 中的读数必须全部匹配。只在设施区块加载时求值，两次求值至少间隔 `period` tick（最少 10，默认 10）。
`hysteresis` 是需要连续匹配的求值次数（默认 1）。条件持续成立时，上一次运行结束且距离提交已过
`cooldown` tick（默认 0）即可再次运行。
已注册的 `source` 可提供规程参数，并可单独使用或与 `when` 一同使用。
设置 `"source": "folkways_core:fire_control"` 可要求火控已分配目标，并传入 `target` 参数，
值为实体 UUID 字符串或含三个坐标的世界位置。

在 `readings` 中加入 `"nearby": {"probe": "folkways_vanilla:hostile_within", "range": 48}`，即可统计设施周围球形范围内
已加载的敌对实体，并上报给聚落火控。半径大于 0，最多 128 格。可用 `offset` 指定设施内的
扫描起点，默认使用锚点。居民目击也会上报。RTS 对敌对实体的攻击命令获准后，会添加持续
五秒游戏时间的集火标记，火控优先分配此目标，下令的玩家会在目标上方看到标记。

用 `ls("facilities")` 列出设施。读取 `facilities/north_gate/definition` 获取可编辑的定义，
读取该设施下的 `readings`、`recent` 和 `runs` 查看运行信息。删除
`facilities/north_gate/definition` 会移除设施并恢复被覆盖的预制设施。
删除 `facilities/<id>/runs/<run id>` 可暂存对运行中脚本的取消请求。

向 `facilities/<id>/runs/<new name>` 写入 `routine` 可暂存一次手动触发。`args` 是可选的规程参数字典，值可以是嵌套的字典和列表，
因此试运行工坊规程时可以传入工坊运行会收到的 `recipe`。该规程需要一个 `kind` 为 `manual` 的触发器。
同一次确认会列出状态改动和手动运行。确认后先应用全部状态改动，再提交运行，
下面的脚本也按此顺序执行。

```python
set("facilities/north_gate/runs/open", {"routine": "open"})
set("facilities/north_gate/triggers/night/enabled", False)
```

命令卡可以让玩家从几个值中选一个，并调整一个数值。两者都保存在 `parameters` 中，例如
`{"mode":"a","range":96}`，并在 `ui.card` 中加入以下元素。

```json
[
  {"choice":"mode","options":["a","b"],"labels":["第一项","第二项"],"icons":["<item id>","<item id>"]},
  {"number":"range","min":32,"max":160,"step":16,"label":"射程"}
]
```

命令卡把选项并排显示并标出当前值；点击选项即可选中，按该选择的快捷键切到下一项。加减按钮每次调整一个步长。
两种控件都会保存设施参数。选择还可以写 `label`（卡上的名称）、`icon`、`icons`（每个选项一个物品 ID，`""` 表示无图标）、
`default`（参数未设置时的初值，缺省为第一项）和 `enabled_when`。写了 `"run": 规程` 时，选中后还会运行这段手动规程，
所选值作为与参数同名的参数传入。规程的 `with` 可用 `{"param": 名字}` 读取参数的当前值，
例如 `{"ammunition": [[{"param": "shell"}, 1]]}`。运行时显式传入的参数仍优先于默认值。

带 `"default": true` 的目标在 RTS 中选中设施并右键点击世界时也会运行，
因此 `{"target": "move", "pick": "pos", "default": true}` 会把设施派往所点的位置。

### 搭乘路线

搭乘投影让计划系统可以安排居民使用玩家自建的升降机、移动平台或渡船。在 `projections` 中添加
`ride`。停靠点的格子是设施所在空间内的站位。若设施锚定在移动结构上，则使用结构的局部坐标。
设施范围应包含停靠点及运送居民时所经过的空间。

下面是两个停靠点的升降机投影示例。

```json
{"ride": {
  "stops": {"ground": {"cells": [[120, 64, -30]]}, "top": {"cells": [[120, 72, -30]]}},
  "routine": "ride",
  "ticks": 200,
  "capacity": 2,
  "available_when": {"ready": true}
}}
```

信号反馈符合 `available_when`，或它作为字符串给出的 Starlark `when(readings)` 返回真时（与 `open_when` 相同），任意两个停靠点之间都提供双向路线。省略该字段可使路线始终可用。
`ticks` 填预计行程时间，包含调来轿厢的时间。它计入路线成本，也是计算超时的依据。每处设施每次运行
一批搭乘规程，同一行程中最多有 `capacity` 名等候的居民一起搭乘。规程收到 `args["from"]`、
`args["to"]` 和 `args["riders"]`，其中乘客列表包含居民的 UUID 字符串。规程成功且居民站在到站格子内，
这一段行程才算完成。若规程失败、居民中途离开或行程超时，这一段行程会返回通行失败。

用 `controls(area)` 查找升降机的输入和反馈。用
`probe(control={"x": x, "y": y, "z": z, "part": part}, value=value, at=cabin_pos)`
试验候选输入值。分别试验两个方向，确认哪个输入能移动轿厢，并记录各站的到站读数和所需时间。
将测得的值用于 `routines.ride.source`。只有乘客在场时也能写入远程操作位。本地操作位需要安排操作员。
此例假定 `destination` 用来选层，`cabin_at` 报告轿厢实际
抵达的楼层，且轿厢已在上客点，或机械会在处理请求时先调来轿厢。

```python
def step(state, readings, args):
    if readings["_failure"] != None:
        return state, [fail(readings["_failure"])]
    if state == None:
        floor = {"ground": 0, "top": 1}[args["to"]]
        return floor, [write("destination", floor), await("cabin_at", floor, 200)]
    return state, [done]
```

### 定位

沿各轴移动的机械，把每条轴声明为一个 `motion` 部件（`position`、`moving`），由一个或多个信号通道驱动。
在 `controls` 中为每个运动部件和通道命名。另一轴移动时地址会变的部件，用 `carried_by` 和 `per_unit` 声明。

将卡片目标绑定到手动脚本。可选模块 `@std//position` 通过 `@std//ik` 求出各轴的伸出量。
`frame` 给出停靠点及每个运动部件的轴向量，`response` 按轴提供校准得到的通道、停止值和速度档位
（见[校准机械](#校准机械)）。`order` 指定移动顺序，`hold` 让所选轴保持当前伸出量，
`clearance` 可给出一个世界 XYZ 点，让机械在前往目标前先单独移动到该点。

```python
load("@std//position", "position_step", "position_readings", "position_ready", "position_check")

def step(state, readings, args):
    return position_step(state, readings, args)

def readings(input, args):
    return position_readings(input, args)

def ready(readings, args):
    return position_ready(readings, args)

def check(args):
    return position_check(args)
```

名为 `move` 的规程发布 `move.bounds` 和 `move.current`。禁行区 `keepout` 可填写
`while_moving` 和 `margin`，也可用 `{"source": "..."}` 定义 `keepout(readings)`，返回世界坐标下的
方盒列表 `[[minX, minY, minZ, maxX, maxY, maxZ], ...]`。

### 设施脚本

设施可以用 `posts` 按名称声明值守岗位。`stance` 指定相对锚点的 `footing` 站位或
`seat_block` 座位方块，也可用 `seat_entity` 指定座位实体的 UUID，并选填 `seat` 座位编号。
触及范围内的信号和端口自动纳入覆盖范围，选填的 `controls` 列表可额外指定操作位。
`staffing: "standing"` 表示常驻值守，`staffing: "on_demand"` 表示定时运行需要时才安排值守。
卡片指示灯可读取 `posts.<name>.staffed`。值守居民使用自己的背包执行 `deliver` 和 `collect`。
缺少的货物可由普通搬运工作先送到值守居民手中。

使用 `folkways_core:write_sequence` 时，`steps` 从第 0 刻开始按 `atTick` 排序。
信号步骤填写 `control` 和 `value`；物品步骤填写 `item` 和 `count`，并填写 `from` 来源端口、
`into` 目的端口或两者。省略一端时使用居民背包。请在转移时间到达前备齐货物。
一个值守岗位可按时执行整个序列。若各步共用站位，也可由一位居民到达后执行。

带 `level` 信号的部件接受 0 到 15 的整数档位。它的反馈是部件实际输出的值，不一定等于写入的档位：
依赖它之前先在试验中比较两者。自带座位的部件会以按需值守岗位出现在 `controls()` 中；
对同一个这类岗位的操作位执行定时序列时，居民就座后才开始第 0 刻。

设施规程可写成 `{"source": "...", "with": {}}`。源码定义
`step(state, readings, args)`，返回 `(state, actions)`。每次运行从 `None` 开始。
状态可包含布尔值、有限数值、字符串、列表、字符串键字典和 `None`。
`args` 包含规程默认值与本次运行参数。`readings` 是读数和设施参数的新快照，读不到的值会省略。
`readings["_failure"]` 是 `None` 或上一批动作的失败原因。读档打断的动作批次会报告中断。

按执行顺序返回动作列表。计划系统跑完这一批动作后才调用下一步。

- `write(control, value)` 由值守居民写入具名信号，或派居民到操作位执行。
- `write_sequence(steps)` 执行定时步骤，使用与规程库相同的字段。
- `await(reading, value, timeout)` 等待具名读数达标，超时以 tick 计。`within=` 让读数的每个数值落在该容差内即算达标，
  例如炮塔的瞄准停在目标附近。
- `run(library_routine, arguments)` 用参数字典调用指定 ID 的库规程。
- `deliver(port, items)` 将物品送至端口，`collect(port, items=None)` 将物品从端口取回聚落仓储。
  物品列表中的条目为 `{item, count}`；送货条目还可加 `components`，即物品必须带有的数据组件，
  例如 `{"createbigcannons:power": 4}`。
- `done` 成功结束，`fail(reason)` 报告可重试的失败，`fail_permanent(reason)` 报告永久失败。
  三者均须单独放在列表中。

动作可指定 `on_fail="recover"`，节点失败后调用 `recover(state, failure, input, args)`。
处理函数返回 `(state, actions)`，其中 `failure` 包含从零开始的 `action_index`、`kind`、`reason` 和 `retryable`。

例如，设施有 `approach_left` 读数及 `left` 和 `right` 两个门的信号时，源码可写成

```python
def step(state, readings, args):
    if readings["_failure"] != None:
        return state, [fail(readings["_failure"])]
    if state == None:
        door = "left" if readings.get("approach_left", False) else "right"
        return door, [write(door, True), await(door, True, 60)]
    return state, [done]
```

在 `readings` 中可用 `{"source": "def read(readings, params): return readings.get('lever', False)"}`
发布派生读数。读不到时返回 `None`。探针读数填写 `probe` ID 和相对锚点的 `offset`。
核心模块提供 `folkways_core:block_state`（`id` 和 `properties`）、`folkways_core:block_entity`（方块实体数据）、
`folkways_core:item_counts`（按物品 ID 计数，用 `part` 指定端口）和
`folkways_core:entity_data`（实体数据，用 `entity` 指定该位置的实体 UUID）。

规程源码还可定义以下函数。

- `path(readings, args)` 返回世界 XYZ 点列表，供 `ui.overlay` 中的 `{"path": "move"}` 等条目绘制路径；
  也可返回 `{"points": [...], "strike": True}`，让绘制的路径停在碰到的第一个方块处并在那里标出。
- `readings(readings, args)` 返回字典，每个键以 `<规程>.<键>` 发布。`center` 键为世界 XYZ，作为设施射程圈的圆心。
- `check(args)` 返回 `None` 以接受运行，或返回说明拒绝原因的字符串。
- `ready(readings, args)` 返回布尔值。待运行的规程等到 `True` 才执行第一步。

路径和读数函数接收解析后的规程默认参数。运行检查接收覆盖默认值后的本次参数。
条件触发与大门和卡片操作一样，可填写定义 `when(readings)` 的源码。
设施加载时，两次采样至少间隔 10 刻。主线程获取快照，工作线程在步数、时间和内存预算内执行源码，
后续刻发布缓存结果。脚本故障会暂停该脚本，并记录在最近结果中。可用触发开关重新启用故障的条件触发，
或更新定义以重新启用其他故障的采样。

用 `load("//scripts/<name>", ...)` 加载聚落脚本库。标准模块包括 `@std//math`、`@std//text`、
`@std//ik`、`@std//position`、`@std//sweep` 和 `@std//ballistics`（见[武器](#武器)）。

用 `@std//ik` 在几个控制值下探测每个运动关节，并根据测得的位置进行拟合。
每个控制都让末端沿直线移动时，`fit_straight(samples)` 用 `[[c0, c1, ...], [x, y, z]]` 样本（每个样本是试过的控制向量和末端所在位置）
一起拟合所有控制并返回关节链；样本没有覆盖每个控制时返回 `None`。否则每个控制都从同一个起始值开始扫，其余控制保持在起始值，
用 `fit_prismatic` 或 `fit_revolute` 拟合它，并在这个姿态下建立关节链：把每次拟合的 `zero`
设为它的起始值，把末端的起始位置作为 `tip` 交给 `chain`。然后求解目标位置，检查返回的 `error`，再写入返回的控制值。

活动结构上的设施可以从 `readings["_frame"]` 读到这个结构的坐标系：它空间原点的世界坐标 `origin`，
以及它 `x`、`y`、`z` 三条轴的世界方向。`@std//ik` 的 `to_local(frame, point)` 和 `to_world(frame, point)`
在世界和这个空间之间换算坐标，所以在结构空间里拟合的关节链，无论结构走到哪里都仍然有效。

设施规程可用 `@std//sweep` 让校准过的工具扫过所选方框。平面就是只有一格厚的方框。
扫掠会根据工具当前位置自动安排停点，并读取 `readings._target`，让工具的作业范围沿空旷路径移动，只允许接触所选作业停点内的方块。
`offset` 是从关节链末端到工具作业点的世界坐标偏移，`at_stop` 用来设置每个作业停点的定时控制写入。具体作业由规程决定。
能移动的设施扫完当前够得到的格子后会自行挪位。设置 `relocate=False` 可固定在当前位置。
当 `sweep_step` 不再返回动作时，用其状态中的 `worked` 和 `left` 跟踪覆盖情况。
在规程源码中加载库，用 `probe(routine=...)` 试验。传入选框 `target`、校准样本 `fits` 和工具半尺寸 `reach`。
在移动结构的空间内校准过的工具应设置 `local=True`。把校准参数放进规程的 `with` 默认值中，命令卡的选取只需补上 `target`。

会破坏、放置或移动方块的规程，先用
`probe(routine=<规程>, definition=<定义>, args=<参数>)` 或
`probe(routine=<规程>, facility=<ID>, args=<参数>)` 试验。
只有试验以 `done` 结束，且玩家允许的范围之外没有改动，才在世界里运行。只用试验调试规程。
在世界里运行后，检查周围方块；如果允许的范围之外有改动，就停止运行。

### 加工设施

将 `workshop` 设为 `{"routine": "process"}`，计划系统就可在需要货物时调用指定的 Starlark 规程。
脚本可定义 `recipes(input)`，返回包含 `inputs`、`outputs`、`ticks` 及可选 `id`、`scale` 的配方批次列表。
采样读数改变时，工坊会刷新列表。在 `recipes(input)` 内可用 `recipes_of("minecraft:smelting")` 读取
原生配方批次，并用 Starlark 筛选或合并。固定批次列表也可直接填写在 `workshop.recipes` 中。
输入物品支持标签，例如 `#c:ingots`。

计划系统选定工坊时，核心模块会为本次运行预留配方原料。脚本任一步生成的工作都可按需使用匹配的预留原料。
居民把货物运到端口，向不同方块送货的动作可以并行执行。本次运行存入聚落仓库的货物计入已预订的产物。

```python
process = """
def recipes(input):
    return recipes_of("minecraft:smelting")

def step(state, input, args):
    if input["_failure"] != None:
        return state, [fail(input["_failure"])]
    if state == None:
        return "loaded", [deliver("inlet", args["recipe"]["inputs"])]
    if state == "loaded":
        return "collected", [await("outlet.count", 1, 600),
                             collect("outlet", args["recipe"]["outputs"])]
    return state, [done]
"""
set("facilities/workshop/definition", {
    "name": "workshop",
    "extent": {"elements": [{"space": {"dimension": "minecraft:overworld"},
               "min": [100, 64, 20], "max": [102, 64, 20]}]},
    "controls": {
        "inlet": {"offset": [0, 0, 0], "part": "folkways_core:item/up"},
        "outlet": {"offset": [2, 0, 0], "part": "folkways_core:item/down"},
    },
    "routines": {
        "process": {"source": process},
    },
    "workshop": {"routine": "process"},
})
```

`await` 等到读数等于给定值，因此本例适合出口为空、每批产出一件物品的情况。
`step` 中的 `args["recipe"]` 是选中的配方，`args["demand"]` 是所需物品及数量。
可用 `controls(area=...)` 查找机器的端口和信号，并用 `mechanics("workshops")` 查看工坊提供的配方。
机器所需的燃料需另行供给。

### 依赖机器状态的工坊

`recipes(input)` 能看到设施的读数，所以工坊可以只在机器能加工时提供配方批次。
读数表明机器无法运转时返回 `[]`，计划系统就会跳过这个工坊，直到它能运转为止。
`controls()` 列出各联动为设施范围内的方块提供的读数，每项附带探针、偏移、配置和当前值。
把能说明机器能否工作、还剩多少要加工的读数加入 `readings`。配方的 `scale` 允许一次运行最多合并这么多批。
运行要把东西放进机器加工的部件时，给这些动作加上 `on_fail="recover"`，并在 `recover` 中取回已送去的物品，
这样失败的运行不会留下任何东西。

```python
process = """
def recipes(input):
    if not input.get("ready"):
        return []
    return [{"id": "workshop:batch", "scale": 16, "ticks": 200,
             "inputs": [{"item": "<input item>", "count": 1}],
             "outputs": [{"item": "<output item>", "count": 1}]}]

def recover(state, failure, input, args):
    return "stopped", [collect("slot"), write("switch", False)]

def step(state, input, args):
    if input["_failure"] != None:
        return state, [fail(input["_failure"])]
    if state == None:
        return "started", [write("switch", True), await("ready", True, 200, on_fail="recover")]
    if state == "started":
        return "loaded", [deliver("slot", args["recipe"]["inputs"]),
                          await("left", 0, 600, on_fail="recover")]
    if state == "loaded":
        return "collected", [collect("slot", args["recipe"]["outputs"], on_fail="recover"),
                             write("switch", False)]
    if state == "stopped":
        return state, [fail("批次完成前机器已停止")]
    return state, [done]
"""
```

这里的 `switch` 是试验过的、能启动机器的操作位，`slot` 是机器加工用的物品端口，`ready` 和 `left` 是该端口的读数。
先试验这个操作位，弄清哪个值能启动机器。

### 运输通道

设施可以像传送装置那样，不经居民在自己的两个物品端口之间运送货物。声明一条从入口到出口的通道即可。
匹配的用料需求可以走这条通道，出口的物品数量增加后，计划会派居民取货。
出口等货超时后，计划可以改用别的运输方式。

```python
set("facilities/lane/definition", {
    "name": "lane",
    "extent": {"elements": [{"space": {"dimension": "minecraft:overworld"},
               "min": [80, 64, -10], "max": [96, 66, -10]}]},
    "anchor": [80, 65, -10],
    "controls": {
        "inlet": {"offset": [0, 0, 0], "part": "folkways_core:item"},
        "outlet": {"offset": [16, 0, 0], "part": "folkways_core:item"},
    },
    "projections": [{"lane": {
        "from": "inlet", "to": "outlet", "automatic": True,
        "items": ["<item id>"], "latency": 80, "rate": {"ticks": 20, "count": 8},
    }}],
})
```

`latency` 是首组物品的预计到货 tick 数。可选的 `rate` 表示之后每多一组 `count` 件就增加 `ticks` tick。
省略 `items` 时接受所有物品。两个具名操作位都必须是设施范围内的物品端口。
省略 `automatic` 或设为 `False` 时，居民先把货搬到入口。请使用专用出口，并把机器的中途缓冲容器留在
聚落仓储之外。登记为仓储的出口仍按实际存量计入库存。

### 武器

武器的 `aim` 部件发布 `aim.ballistics`（以控件名命名）：武器的转轴和炮口离转轴多远、炮架相对世界如何转动、
瞄准的范围、射击周期、是否已装填，以及武器模组提供时的原生弹道模型。机械动力火炮的大炮还逐格报告炮膛
（`bore`，含每个大型药筒的 `power` 及是否已 `spent`）、炮管、药室、安全发射药应力、推弹杆的限制，以及每种大炮
弹药的原生数值（`munitions`），因此规程在装填前就能算出某种装填的弹道。卓越前线的固定武器报告所选炮弹
（`ammunition`）、存弹数（`magazine`）、射程、伤害和爆炸范围。在武器上定义设施，设施范围要包含武器所在格；
武器是实体时还要包含该实体。用可选的 `@std//ballistics` 模块或自己写的规程射击。

`@std//ballistics` 让武器瞄准 `args["target"]`，经端口补足装填后开火。用它的 `shoot_*` 函数就能组成完整规程：

```python
SHOOT = """
load("@std//ballistics", "shoot_step", "shoot_path", "shoot_readings", "shoot_check")

def step(state, readings, args):
    return shoot_step(state, readings, args)

def path(readings, args):
    return shoot_path(readings, args)

def readings(readings, args):
    return shoot_readings(readings, args)

def check(args):
    return shoot_check(args)
"""
set("facilities/gun/definition", {
    "name": "gun",
    "extent": {"elements": [{"space": {"dimension": "minecraft:overworld"},
               "min": [130, 64, -30], "max": [130, 64, -30]}]},
    "controls": {
        "aim": {"part": "aim", "offset": [0, 0, 0]},
        "fire": {"part": "fire", "offset": [0, 0, 0]},
        "breech": {"part": "breech", "offset": [0, 0, 0]},
    },
    "routines": {"shoot": {"source": SHOOT, "with": {
        "aim": "aim", "fire": "fire", "breech": "breech",
        "ammunition": [["createbigcannons:solid_shot", 1], ["createbigcannons:powder_charge", 2]],
    }}},
    "readings": {"nearby": {"probe": "folkways_vanilla:hostile_within", "range": 48}},
    "triggers": {
        "auto": {"kind": "condition", "routine": "shoot",
                 "source": "folkways_core:fire_control", "enabled": True, "cooldown": 120},
        "manual": {"kind": "manual", "routine": "shoot"},
    },
    "ui": {"card": [{"target": "shoot", "pick": "entity|pos",
                     "default": True, "icon": "minecraft:target"}],
           "overlay": [{"range": "nearby"}, {"path": "shoot"}]},
})
```

这是炮架上带速射炮闩的机械动力火炮大炮，用实心弹和两份发射药直射；除非数据包调高，所有速射炮闩只承受两份。
卓越前线的固定武器要照 `controls()` 列出的原样复制部件名（实体武器的部件名带有它的 UUID），并经 `ammo`
弹药口装填：

```python
gun = "<UUID>"
set("facilities/turret/definition", {
    "name": "turret",
    "extent": {"elements": [{"space": {"dimension": "minecraft:overworld"},
               "min": [130, 64, -30], "max": [130, 64, -30]}, {"entity": gun}]},
    "controls": {"aim": {"part": "aim/" + gun, "offset": [0, 0, 0]},
                 "fire": {"part": "fire/" + gun, "offset": [0, 0, 0]},
                 "ammo": {"part": "ammo/" + gun, "offset": [0, 0, 0]}},
    "routines": {"shoot": {"source": SHOOT, "with": {
        "aim": "aim", "fire": "fire", "ammo": "ammo",
        "ammunition": [["superbwarfare:large_shell_he", 1]]}}},
    "triggers": {"manual": {"kind": "manual", "routine": "shoot"}},
    "ui": {"card": [{"target": "shoot", "pick": "entity|pos", "default": True, "icon": "minecraft:target"}],
           "overlay": [{"path": "shoot"}]},
})
```

居民坐进炮手座位、瞄准，武器内没有炮弹时往它的弹药容器里放一发，等武器装填完毕后开火。火控从聚落已收到的
上报中分配 `target`，手动触发也可以传入目标：
`set("facilities/gun/runs/shoot", {"routine": "shoot", "args": {"target": [145, 64, -30]}})`。

`shoot_step` 的参数（除 `target` 外，缺省时都读取同名的设施参数）：

- `target`：实体 UUID（瞄准其碰撞箱中心）或世界坐标 `[x, y, z]`。
- `aim`（默认 `"aim"`）和 `fire`：瞄准控件和开火脉冲；不给 `fire` 时规程只瞄准和装填。
- `breech`、`ammo` 或 `muzzle`：装填经过的端口。经 `muzzle` 装填时，`ram`（默认 `"ram"`）指定推弹杆脉冲。
- `ammunition`：按经过端口的顺序列出条目：`[item, count]`、`[item, count, components]` 或
  `{"item", "count", "components"}`。`components` 是数据组件字典，例如 `{"createbigcannons:power": 4}`。
- `indirect` 优先选高弹道，`range` 以格为单位限制目标距离，`damage` 是火控预计的单发伤害（默认取武器的，
  否则为 20），`avoid: True` 会拒绝预测弹道或爆炸范围碰到聚落的射击。
- `speed`（格/tick）、`gravity`（格/tick²，向下）和 `retention`（每 tick 保留的速度比例）：武器没有原生弹道
  模型时的实测值。

武器装不下这次装填时，运行会附原生原因永久失败；目标超出射程或没有弹道能命中时，运行失败并可重试。
发现炮膛里有不是自己装的东西时也会失败。`shoot_path` 沿武器当前的瞄准画出到第一个方块为止的弹道并标出落点；
`shoot_readings` 发布 `<规程>.center` 作为射程圈圆心，并发布 `<规程>.engagement`（`aim`、`model`、`indirect`、
`range`、`damage`、`blast`）供聚落火控使用。任何发布 `engagement` 读数的规程都会参与火控：只有当这条弹道在已加载
的世界中追踪下来能落到目标上、且避开聚落实体和方块时，火控才会分配该目标。玩家选定的目标跳过这项预测。同一聚落
的实体与成员玩家之间免疫伤害，爆炸遵循武器模组的原生规则。

自己写规程时可用的部件：`weapon_of(readings, aim="aim")`；`model(weapon, ammunition=None, measured=None)`
给出某种装填的 `{speed, gravity, retention}`（大炮按弹体和每份发射药计算，其他武器用自身的）；
`load_problem(weapon, ammunition, port)`；`fed(weapon, ammunition, port)` 计算已就位的部分，不计已用尽的大型
药筒；`load_actions(weapon, ammunition, port, name, ram="ram")` 返回补足装填所需的送货和推弹动作；
`target_point(readings, args)`、`reach(weapon, model)` 和 `blast(weapon, ammunition)`。弹道计算在 Java 中进行：
`aim(weapon, model, target, indirect=False)` 返回部件角度或 `None`，`flight(weapon, model, angles, target=None)`
返回自由飞行的 `{points, arrives}`，`solve(model, offset)` 返回到相对点的两条弹道，
`avoids_colony(points, blast, margin=0.35)` 用为规程拍下的聚落快照检查弹道。自行自动选目标的规程必须用
`avoids_colony` 检查目标。下面的规程用能打到目标的最少发射药射击：

```python
FEWEST = """
load("@std//ballistics", "PULSE", "aim", "load_actions", "load_problem", "model", "target_point", "weapon_of")

def step(state, readings, args):
    if readings["_failure"] != None:
        return state, [fail(readings["_failure"])]
    if state != None:
        return state, [done]
    gun = weapon_of(readings)
    point = target_point(readings, args)
    for charges in [1, 2]:
        load = [["createbigcannons:solid_shot", 1], ["createbigcannons:powder_charge", charges]]
        flight = model(gun, load)
        angles = aim(gun, flight, point) if flight and not load_problem(gun, load) else None
        loading = load_actions(gun, load, "breech", "breech") if angles != None else None
        if loading != None:
            return "fired", ([write("aim", angles), await("aim", angles, 200, within = 1)] + loading +
                             [write("fire", PULSE)])
    return state, [fail("no load reaches the target")]
"""
```

大炮的装填按手工放入的顺序列出。经速射 `breech` 先放弹体、再逐份放发射药，每次送一件，炮闩在两份发射药之间
推弹。经 `muzzle` 则先放发射药、最后放弹体，每件推进炮口后用推弹杆推到装填处；任何炮口敞开的大炮都提供
`muzzle` 和 `ram` 部件，与炮闩种类无关。装好药的大型药筒要连同威力一起请求，例如
`["createbigcannons:big_cartridge", 1, {"createbigcannons:power": 2}]`，弹道会计入这份威力。药筒射击后，空药筒
留在药室里，直到下一次经炮闩装填时打开炮闩将其退出，与玩家手动操作一样。

带楔式或镙式炮闩、或靠火炮装载器装填的大炮没有 `breech` 部件：经 `muzzle` 装填，或操作玩家搭的装填结构。
用 `mechanism()` 找到其中的拉杆、离合器和换挡器，再逐个 `probe()`，弄清哪个开关炮闩、哪个让装药机头伸出收回、
哪个组装炮架。把这些控件放进设施，并加上能说明每步完成的读数，例如装药机头的 `motion` 位置，以及炮架处的
`{"probe": "folkways_core:cbc/assembled"}` 和 `{"probe": "folkways_core:cbc/charges"}`，然后在规程源码中按顺序操作它们，
最后用炮架的 `fire` 部件开火：

```python
LOAD = """
STEPS = [("assemble", False, "assembled", False), ("breech", True, "breech_open", True),
         ("rammer", True, "rammer_out", True), ("rammer", False, "rammer_out", False),
         ("breech", False, "breech_open", False), ("assemble", True, "assembled", True)]

def step(state, readings, args):
    if readings["_failure"] != None:
        return state, [fail(readings["_failure"])]
    if state == None:
        actions = []
        for control, value, reading, shows in STEPS:
            actions += [write(control, value), await(reading, shows, 400)]
        return "loaded", actions + [await("charges", args["charges"], 40)]
    return state, [done]
"""
```

炮架上的自动炮经 `ammo` 弹药口装入装满的机炮弹药箱，`fire` 强度大于零时持续开火。投弹迫击炮会发射投入其
`ammo` 弹药口的炮弹。这两种火炮不报告 `ballistics`，需自行编写规程。

`ui.overlay` 在 RTS 模式下选中设施时画在世界里。`{"range": 名字}` 以武器为圆心画射程圈，
名字可以是数值参数、数值读数，或声明了范围的探针读数。`{"path": "shoot"}` 绘制设施中 `shoot` 规程提供的路径。

### 移动设施

设施可以建在移动结构上。对结构使用 `controls(structure="<UUID>")`：坐标采用结构自己的空间，设施随结构移动。
部件接受世界坐标的操作位（例如 `destination`）会把结构开到那里；规程 `folkways_core:move_to` 默认把规程的 `target`
写入 `destination`，运行持续到结构抵达或失败为止。结构提供的驻停或制动部件用 `folkways_core:write_all` 写入。
依赖各操作位之前，先试验弄清它们各自移动什么。

```python
found = controls(structure="replace-with-the-structure-uuid")
drive = [c for c in found["controls"] if c["part"] == "destination"][0]
set("facilities/vehicle/definition", {
    "name": "vehicle",
    "extent": found["extent"],
    "anchor": [drive["x"], drive["y"], drive["z"]],
    "controls": {"destination": {"part": "destination", "offset": [0, 0, 0]}},
    "routines": {"go": {"use": "folkways_core:move_to"}},
    "triggers": {"go": {"kind": "manual", "routine": "go"}},
    "ui": {"card": [{"target": "go", "pick": "pos", "default": True, "icon": "minecraft:compass"}]},
})
```

居民需要能走上结构的通路才能操作它的操作位。没有这样的通路时，先让乘员上去。运行期间该目标命令不可用。

## 校准机械
<!-- guide: calibration -->

`controls()` 和 `mechanism()` 的 `parts` 字典简述本次结果中的部件 ID。为陌生部件编写例程前，
请使用发现的 ID 调用 `help('parts/<part id>')` 阅读完整条目。

玩家自建的机械很少有直接说明用途的操作位：门可能靠转动手轮打开，一个按键可能作用于玩家把它链接到的任何东西。
写设施之前先校准：读出机械的构成，逐个试验候选操作位，再让例程按正确的方向操作起了正确作用的那个操作位。

校准整台机械，不只是玩家点名的部分。机械能做好几件事时，例如既能行走或行驶、又带着机械臂、工具或火炮的步行机和载具，
逐个试验，并把每一项都放进设施：驱动做成 `pick: "pos"` 的目标，用来派它前往；每件工具各做一个命令。再让命令把它们配合起来：
用校准过的工具处理所选方框时，加载 `@std//sweep`，不要自己编写扫过的路线：它从工具当前位置出发安排停点，并让工具避开立着的方块。方框按选取得到的原样传入。
工具扫完当前位置够得到的格子后，能移动的设施会自行挪位。

会破坏、放置或移动方块的规程，先用
`probe(routine=<规程>, definition=<定义>, args=<参数>)` 或
`probe(routine=<规程>, facility=<ID>, args=<参数>)` 试验整个规程。如果参数中没有给出玩家允许改动的方框，用 `keep` 指定。
只有 `routine.ending` 是 `done` 且 `outside.count` 为零，才在世界里运行。只用试验调试规程。

向玩家说明时只描述试验结果，例如哪个操作位让部件动了、动了多少。得不到结果时，用普通语言说明实际限制。
对玩家说的话及展示给玩家的文字都不要提沙盒、副本、仿真或其他内部实现。

玩家提问时若正指着这台机械，`get("client/view")["target"]` 就是准星所指的格子：从这里开始，不必在一片区域里搜索。

1. 对机械的任意一个方块调用 `mechanism(at=pos)`。也可以用 `area=` 指定包含边界的方框，每边最多 64 格；
   或用 `structure=` 读取整个移动结构，坐标采用结构内的格坐标。`nodes` 列出各部件及其 `roles`：
   `control`（拉杆、按钮、曲柄、手轮、武器底座、驾驶座，以及带有 Folkways `controls` 的部件）、
   `source`、`transmission`（离合器和换向齿轮箱，带 `powered` 与 `when_powered`）、
   `actuator`（活塞、轴承、滑轮、龙门架、门、武器底座、结构核心）和 `moving`（装置、武器、子世界）。
   `edges` 连接各部件：`drives` 传递转动，`ratio` 为转速比，折叠掉的传动杆和齿轮写在 `via` 中；
   `engaged: False` 表示离合器断开，`driven: False` 表示当前没有动力源带动；`powers` 表示红石能到达；
   `controls` 表示不经转动的指挥；`moves` 把执行器与它带动的结构连起来。`paths` 给出每个曲柄或动力源到各执行器的
   `factor` 与 `rpm_per_forward_turn`，`through` 列出路径所依赖的切换部件。
2. 选出候选：`edges` 或 `paths` 能到达玩家所指部件对应执行器的那些操作位。
3. 对每个候选调用 `probe(control={"x": x, "y": y, "z": z, "part": part}, value=value, at=pos)`。
   试验会像居民一样操作，持续 `ticks` 刻（默认 40），不会改变世界里的机械。
   先读 `summary`，再看 `changes`（活塞 `offset`、轴承 `angle`、底座 `yaw` 与 `pitch`、拉杆 `powered`、门 `open`，
   转动部件附带 `peak_speed`）、`entities`（装置的 `moved` [dx, dy, dz]）和 `blocks`（新增、移除和改变的格子）。
   `input.result` 说明写入结果，`input.done_tick` 是操作位显示出该值的时刻。若要在空格试验红石输入，
   用 `redstone={"x", "y", "z", "on"}` 代替 `control`。给试验留够时间：阀门手轮每次使用约 20 刻，手摇曲柄约 10 刻，
   所以 `ticks` 取每次使用约 20 刻再加 20（最多 100），并看 `input.done_tick` 确认每次使用都已完成。
   方向重要时两个方向都要试，并且先试少量次数：一两次使用移动了多少，就能算出完全打开需要几次。
   打印 `summary`，不要打印整个结果，以免超过输出上限。一个脚本最多运行 2 次试验，
   其余候选放到之后的脚本中试。试验不会改变世界，也无需确认。
4. 写设施定义，让例程以试验得出的值写入该操作位，并在回复中说明选了哪个操作位、试验显示了什么。

手摇曲柄和阀门手轮提供 `turn`，取值为 -64 到 64 的整数次使用：正数为普通使用，负数为潜行使用，向另一方向转。
阀门手轮每次使用转动 `degrees_per_turn` 度；手摇曲柄每次使用约转动十刻。转动是相对的，所以由它带动的门本身没有开关读数。
机械活塞、绳索滑轮和只有一台行车的龙门轴提供只读的 `motion` 部件，可读取 `position`、`moving` 和 `contraption`。

定位前，用 `probe(..., ticks=40, release_tick=20)` 测量各通道值。试验时让机械先停稳，并留出足够行程。
逐个试验 0–15 档或相关的拉杆状态。在 `position` 的 `response` 中为每轴填写 `channel`、
停止值 `stop` 和 `rates`。每条速率含 `value`、带方向的 `velocity`（格/刻）、`start_ticks`、
`stop_ticks` 和可选的带方向 `overshoot`。零速率表示死区。值守居民每刻根据读数调整。
没有值守时，居民到操作位按计时顺序启停，再读取位置并按 `tolerance` 纠偏。

在下面的例子中，`mechanism` 在 (10, 64, 5) 找到一个操作位，它的路径通向带动玩家所指部件的执行器。
用该部件可取的各个值（这里是 `1` 和 `-1`）分别试验，看哪一个让部件按玩家要求的方向动作，再把这个值写进对应的例程。
例程和卡片按钮按试验显示的效果命名，使用玩家的语言，各用一两个词。

```python
pos = {"dimension": "minecraft:overworld", "x": 10, "y": 64, "z": 5}
graph = mechanism(at=pos)
candidates = [n for n in graph["nodes"] if "control" in n["roles"]]
x, y, z = candidates[0]["at"]
part = "<part>"
forward = probe(control={"x": x, "y": y, "z": z, "part": part}, value=1, at=pos)
backward = probe(control={"x": x, "y": y, "z": z, "part": part}, value=-1, at=pos)
result = {"forward": forward["summary"], "backward": backward["summary"]}
```

```python
set("facilities/machine/definition", {
    "name": "machine",
    "extent": {"elements": [{"space": {"dimension": "minecraft:overworld"},
               "min": [10, 62, 5], "max": [10, 66, 7]}]},
    "anchor": [10, 64, 5],
    "controls": {"input": {"part": "<part>", "offset": [0, 0, 0]}},
    "routines": {
        "forward": {"use": "folkways_core:write_all", "with": {"input": 1}},
        "backward": {"use": "folkways_core:write_all", "with": {"input": -1}},
    },
    "triggers": {"forward": {"kind": "manual", "routine": "forward"},
                 "backward": {"kind": "manual", "routine": "backward"}},
    "ui": {"card": [{"button": "forward", "icon": "<item id>"},
                    {"button": "backward", "icon": "<item id>"}]},
})
```

居民站在操作位旁完成每一次使用。其他动力源正带动网络时阀门手轮不会转动，所以试验返回 `busy` 时，先检查马达或离合器。

对于武器，`mechanism` 会显示它的 `yaw`、`pitch`、`loaded` 状态、它承载的东西以及带动它的曲柄。
用 `[yaw, pitch]` 试验 `aim`，或试验曲柄的 `turn`，就能看出它朝哪个方向转。`fire` 试验绝不会发射任何抛射物或引发爆炸，
拦下的效果列在 `suppressed` 中。之后按 `guide:facilities` 中的武器模式操作。

对于移动结构，`mechanism(structure=uuid)` 会显示驱动它的部件和指挥这些部件的操作位。
移动结构的试验包括结构本身、它承载的东西和周围的地面。用前方几格处的世界坐标试验 `destination` 部件，
设 `ticks=100`，然后读 `structures`：每个结构的 `moved` [dx, dy, dz] 和 `turned` {yaw, pitch, roll}（单位为度，
yaw 为正表示从上方看顺时针转），随行的结构以 `brought_along` 列出；`changes` 和 `input.done_tick`
说明它是否到达。用 `True` 或 `False` 试验驻停部件，可以看到结构趴下或站起。

结构上的操作位可能还提供更多部件；`controls(structure=uuid)` 会列出它们，试验在 `input.reading` 中返回部件的读数：

- `key/<action>`：一个脉冲按下一次该键，为它链接的东西供能。
- `height`（高度偏移，单位为格）和 `lean`（`[roll, pitch]`，单位为弧度）：结构的姿态。
- `limb/<n>`，从 1 编号：接受该肢体自身平面内的 `[fwd, up]`。长度落在读数 `ring` 范围内的瞄准值照原样接受；
  更短或更长的会被移到范围边缘。读数给出肢体末端实际所在的世界坐标 `x`、`y`、`z`，同一点在结构空间中的 `local`，
  最后一节的方向 `axis`，该肢体是否为手臂 `arm`，`ring`，以及肢体被操纵时它保持的 `aim`。
  用 `@std//ik` 根据这些读数拟合关节链，即可够到某个点。

装在移动结构上的部件也这样试验：`structure=` 给出结构，并用该部件的 `at` 格。
试验会显示部件怎样移动，结构保持原来的姿态。

试验包括所选方框内的方块、方块实体和装置，坐标与原处相同。玩家、居民和其他生物不参与试验。
移动结构以原有姿态和速度开始试验，连同随行的结构（总共最多 32 个）
和周围最多 16 格的地面（地面方块很多时范围更小）。行走试验要短，离开试验区域的结构会退出试验并标记为 `left_area`。
试验不包含 Sable 的临时连接（例如船的系泊），因此船只可以自由移动。对结构操作位的输入只作用于正在试验的结构。
外部网络（例如铁轨和物流网络）不参与试验，并在 `notes` 中列出。
服务器上同一时间只运行一次试验，遇到 `sandbox_busy` 时稍后重试。
