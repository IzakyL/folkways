# Promo resident models

Wine Fox (酒狐) models from Yes Steve Model's built-in pack, taken from
[OpenYSM](https://github.com/OpenYSM/OpenYSM) at
`common/src/main/resources/assets/yes_steve_model/builtin/wine_fox/`.

They are licensed **CC BY-NC-SA 4.0**: credit the authors, no commercial use, and share
derivatives under the same licence. Each folder keeps its original `ysm.json`, which names
the authors to credit. Wine Fox variants marked "All Rights Reserved" upstream
(`09_hailuo`, `10_zhiban`, `17_mini`) are deliberately left out.

The model folders are downloaded on demand and ignored by Git. The models and elevator takes run
`vendor.py` before launching Minecraft; other takes and ordinary tests do not download them.
To prepare them manually (Python 3, Linux, network access on the first run):

```sh
npm run setup:promo-models
```

`sources.json` pins the upstream commit and SHA-256 of all 36 source files. Downloads
are verified and cached under `.blockwright-store/promo-models/`, which can be shared
between worktrees. Prepared files are also checked on reuse. Subsequent runs work
offline; missing or changed files are rebuilt from the cached sources. To reclaim disk
space, remove the ignored `wine_fox_*` folders, `.prepared.json` and that download cache.

`python3 tests/promo/models/vendor.py <OpenYSM root>` still rebuilds every folder from
a local checkout without downloading. It reshapes each model into the layout
`ModelLibrary` reads (`main.json`, `main.animation.json` and the one body skin under
`textures/`, since upstream's arrow, boat and vehicle textures would otherwise become extra
looks). It also drops the bones YSM keeps hidden at rest, such as vehicles, weapons, magic
circles, the fox form and alternate expressions. The resident renderer plays only idle and
walk, so it would draw those bones.

Downloader checks run offline with
`python3 -m unittest discover -s tests/promo/models -p '*_test.py'`.

The picks are the variants with the most cubes left after stripping; `08_sta` has about 4,500
cubes upstream, but nearly all of them are its tank.

| Folder                 | Upstream         | Skin            |
| ---------------------- | ---------------- | --------------- |
| `wine_fox_wedding`     | `18_wedding`     | `skin.png`      |
| `wine_fox_magical`     | `05_magical`     | `winefox.png`   |
| `wine_fox_tactics`     | `16_tactics`     | `tactics.png`   |
| `wine_fox_survivor`    | `20_survivor`    | `mitao.png`     |
| `wine_fox_hanfu`       | `06_hanfu`       | `default.png`   |
| `wine_fox_momo`        | `14_momo`        | `skin_pink.png` |
| `wine_fox_nine_tailed` | `19_nine_tailed` | `skin.png`      |
| `wine_fox_saint`       | `21_saint`       | `skin.png`      |
| `wine_fox_matured`     | `13_matured`     | `default.png`   |
