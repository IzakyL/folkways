# Promo resident models

Wine Fox (酒狐) models from Yes Steve Model's built-in pack, taken from
[OpenYSM](https://github.com/OpenYSM/OpenYSM) at
`common/src/main/resources/assets/yes_steve_model/builtin/wine_fox/`.

They are licensed **CC BY-NC-SA 4.0**: credit the authors, no commercial use, and share
derivatives under the same licence. Each folder keeps its original `ysm.json`, which names
the authors to credit. Wine Fox variants marked "All Rights Reserved" upstream
(`09_hailuo`, `10_zhiban`, `17_mini`) are deliberately left out.

`vendor.py <OpenYSM root>` rebuilds every folder. It reshapes each model into the layout
`ModelLibrary` reads (`main.json`, `main.animation.json` and the one body skin under
`textures/`, since upstream's arrow, boat and vehicle textures would otherwise become extra
looks). It also drops the bones YSM keeps hidden at rest, such as vehicles, weapons, magic
circles, the fox form and alternate expressions. The resident renderer plays only idle and
walk, so it would draw those bones.

The picks are the variants with the most cubes left after stripping; `08_sta` has about 4,500
cubes upstream, but nearly all of them are its tank.

| Folder | Upstream | Skin |
| --- | --- | --- |
| `wine_fox_wedding` | `18_wedding` | `skin.png` |
| `wine_fox_magical` | `05_magical` | `winefox.png` |
| `wine_fox_tactics` | `16_tactics` | `tactics.png` |
| `wine_fox_survivor` | `20_survivor` | `mitao.png` |
| `wine_fox_hanfu` | `06_hanfu` | `default.png` |
| `wine_fox_momo` | `14_momo` | `skin_pink.png` |
| `wine_fox_nine_tailed` | `19_nine_tailed` | `skin.png` |
| `wine_fox_saint` | `21_saint` | `skin.png` |
| `wine_fox_matured` | `13_matured` | `default.png` |
