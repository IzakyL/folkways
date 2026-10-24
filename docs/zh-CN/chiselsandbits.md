# Chisels & Bits 蓝图

[文档](README.md) · [English](../en/chiselsandbits.md)

客户端和服务端同时安装 `folkways-core`、`folkways-vanilla`、
`folkways-chiselsandbits` 及 **NeoForge 1.21.1 的 Chisels & Bits 21.1.33**。
机械动力为可选项。安装机械动力 6.0.10 时，居民与蓝图炮共用注册的材料需求、
可信数据写入器和方块实体变换规则。

## 保存与复用

用殖民书圈地，选择**保存为蓝图**。输入未使用的小写文件名，也可包含目录，
例如 `carvings/window`。服务端仅读取已加载且允许访问的方块，把标准结构 NBT
同时保存到 `folkways/blueprint/carvings/window.nbt` 与
`folkways/piece/carvings/window.nbt`，不会覆盖已有文件。共享蓝图库立即更新。
容器物品和实体不会被复制；支持的方块配置会保留。

已有结构 NBT 和机械动力蓝图仍使用原来的导入入口。
放在 `folkways/piece/`，或数据包 `data/<namespace>/folkways/piece/` 中的结构，
可以在样式里复用：

```python
def draw(site):
    return [stamp("window", template("local:carvings/window"), site.zone)]
```

使用已有 `stretch` 切片及 `fit = "tile"`、`fit = "stretch"` 选项，按完整方块格平铺或拉伸。
建筑数据会经过导入、模板、盖印、拟合、导出和预览完整保留。
水平旋转和镜像同时变换格子及其内部体素。把雕刻容器替换成不相干的方块会被拒绝。

## 材料与完成规则

把现成的碎块存入已登记的普通殖民地仓储。每个非空气体素消耗一个对应材质、
组件完全相同的 `chiselsandbits:block_bit`。例如，一格含 1024 个橡木体素及
3072 个石头体素，就消耗 1024 个橡木碎块及 3072 个石头碎块，不另收容器，
也不按完整方块向上取整。上传文件内缓存的统计值不会决定费用。

侧栏按材质分别显示碎块。规划、预留、搬运和消耗始终保留组件身份。
缺少任意一种材质时不会放置，已为本次尝试取出的物品会退回。
蓝图炮按尚缺数量跨格、跨容器取料，需求超过一组也一样；两组各有 64 个碎块时，
支付 100 个后保留 28 个。炮弹及缺料图标保持为可正常保存的物品栈。

继续遵守蓝图炮只比较方块状态的完成规则：目标状态已经存在时跳过，
即使内部造型不同。换造型前先拆除原来的雕刻方块。
已完成格在重载后仍然完成，不会重新收费或重建。

首版支持静态雕刻和模板，不增加整块自动切碎、碎块袋取料、逐体素脚本接口或新的 Ponder 教程。

## 数据与读取

`StructurePayload` 和 `StructureCompat` 是 Core 的通用接口。
Vanilla 负责样式链路和建筑订单；C&B 类、编解码、材料核算、渲染和 mixin
均位于 `mods/chiselsandbits`。

外部文件仍使用 `palette` 和 `blocks[].nbt`。内部原有
`blockEntity:{nbt,turns}` 格式仍可读取。新数据包含可选的 `folkways:structure`
描述，记录处理器 ID 和版本。缺少处理器或版本不符时保留数据，暂停订单并显示原因。
没有 `payloadVersion:1` 的旧版已确认雕刻蓝图会显式退回待确认状态，重新核价一次；
普通目标和已完成格不变。旧样式导入器已经丢失的数据，需要从原文件重新导入。

C&B 通常异步读取方块实体。集成会同步解码蓝图临时实体，不让它加入服务端区块跟踪器，
也不发送世界更新。可信数据写入器补偿 C&B 在没有玩家的 `setPlacedBy` 中额外进行的半圈旋转。
带标记的蓝图放置也在现有放置调用内同步读取；重建统计时抑制主材质更新造成的世界修改。
普通区块恢复保留 C&B 的排队读取流程。如果同步读取的蓝图实体随后关联实际世界，
会在关联返回前补算统计，避免已有雕刻被判为空方块。
损坏的蓝图会在真正的蓝图炮开炮前标记为读取失败，创造板条箱绕过材料检查时也一样。

`folkways_chiselsandbits-server.toml` 限制每格解压大小、材质数和处理时间，
时间包含解压和 NBT 读取，默认分别为 1 MiB、256 种及 1000 ms。
旧式 `data` / `compressed` 包装最多展开十六层，各层共用累计解压及分配上限。

整次捕获、确认、样式绘制、材料核算或蓝图炮读取，还共用 `blueprintReads`
（4096 次体素读取或计数）、`blueprintBytes`（16 MiB）及 `blueprintMillis`（500 ms）。
嵌套调用共用限额，缓存命中也计入读取次数及解码量。过大的订单保留为待处理并显示原因，
不会只确认一部分。经过验证的体素快照和材料数量放入缓存复用，缓存最多保留 128 项，
估算容量不超过 16 MiB；方块实体改变后会丢弃它记住的数量。

核算前检查调色板索引，既有结构体积和上传限制仍然有效。
殖民地预览先选择较近的候选格，应用数量及 256 KiB 建筑数据上限，再核算材料。
一次预览中的所有订单共用处理限额；耗尽后只保留已检查的较近预览。

C&B 21.1.33 随包提供的 Saecularia Core 声明了 `FMLModType: MOD`，但没有模组描述文件，
导致[上游问题 #1343](https://github.com/ChiselsAndBits/Chisels-and-Bits/issues/1343)。
集成把固定版本的该依赖以 `GAMELIBRARY` 清单打包，类文件内容不变。
`blockwright.toml` 用 SHA-512 固定 C&B，并从同一个已校验归档提取 API、Core、Scena 和 Saecularia 依赖。

## 验证

先运行 `npm ci` 和 `npm run setup` 准备固定依赖。
所有 Gradle 命令使用 `--no-daemon`，每轮完成后释放内存：

```sh
./gradlew --no-daemon build
./gradlew --no-daemon runGameTestServer
./gradlew --no-daemon -PchiselsTests=true runGameTestServer
./gradlew --no-daemon -PchiselsTests=true -PgameTestMod=chiselsandbits runGameTestServer
./gradlew --no-daemon -PchiselsTests=true -PgameTestMod=chiselsandbits -PwithoutCreate=true runGameTestServer
npm run test:i18n
npm run test:e2e -- tests/e2e/chiselsandbits.spec.ts --workers=1
```

Blockwright 的可选配置为 `chiselsandbits`，同时安装 C&B 和 Folkways 扩展。
专项 GameTests 覆盖实际体素核价、异材质搬运、缺料回滚、保存及导入、模板平铺和拉伸、
水平变换、同状态跳过、存档迁移、重载完成格、缺少集成、损坏及超限数据，
以及真实蓝图炮与居民施工的结果和耗材对比。
集成测试源码保留在自己的项目中；同包访问建筑内部实现的测试场景挂到 Vanilla 的测试模块，
避免 Java 包被拆分到不同模块。

客户端测试检查微方块预览生成网格、缺料时订单等待、居民搬运并消耗精确材质的碎块，以及完整重启服务端和客户端后保留体素且不再收费。预览截图和运行轨迹位于 `tests/out/e2e/`。
