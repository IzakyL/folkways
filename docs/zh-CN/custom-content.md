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

## 服务器配置

每个世界的配置位于 `<world>/serverconfig/`：

| 文件                           | 控制内容                             |
| ------------------------------ | ------------------------------------ |
| `folkways_core-server.toml`    | 殖民地引擎                           |
| `folkways_vanilla-server.toml` | 居民在原版方块中的工作               |
| `folkways_create-server.toml`  | 列车与调度，需安装 Folkways × Create |

堆肥偏好和种子保留量在每个殖民地的**堆肥设置**页调整，详见[堆肥](gameplay.md#堆肥)。
