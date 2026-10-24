# Third-party components

## Bundled in the mod jars

These libraries are nested in a Folkways jar through NeoForge's Jar-in-Jar. Each one's
license text ships in that jar under `META-INF/licenses/`, and in this repository under
that mod's `src/main/resources/META-INF/licenses/`
([core](mods/core/src/main/resources/META-INF/licenses/),
[vanilla](mods/vanilla/src/main/resources/META-INF/licenses/),
[chiselsandbits](mods/chiselsandbits/src/main/resources/META-INF/licenses/)).

| Component                | Version         | In                        | License    | Copyright         | Source                                                                                                |
| ------------------------ | --------------- | ------------------------- | ---------- | ----------------- | ----------------------------------------------------------------------------------------------------- |
| Starlark (Java)          | 4.2.1           | Folkways Vanilla          | Apache-2.0 | The Bazel Authors | [bazelbuild/bazel](https://github.com/bazelbuild/bazel), packaged as `com.eed3si9n.starlark:starlark` |
| Ponder                   | 1.0.82+mc1.21.1 | Folkways Core             | MIT        | The Create Team   | [Creators-of-Create/Ponder](https://github.com/Creators-of-Create/Ponder)                             |
| Flywheel                 | 1.0.6           | Folkways Core             | MIT        | Jozufozu          | [Engine-Room/Flywheel](https://github.com/Engine-Room/Flywheel)                                       |
| Saecularia Caudices Core | 21.1.11         | Folkways × Chisels & Bits | MIT        | Communi Suggestu  | [Communi-Suggestu/Saecularia-Caudices](https://github.com/Communi-Suggestu/Saecularia-Caudices)       |

The Chisels & Bits extension repackages the pinned Saecularia Core library with
`FMLModType: GAMELIBRARY` to make its classes load on NeoForge. Its class bytes are
unchanged; see the [integration guide](docs/en/chiselsandbits.md#storage-and-loading).

## Required or optional at runtime, not bundled

Players install these separately. Folkways only compiles against them.

| Component                                         | License                                   | Use                                                                                                                                                             |
| ------------------------------------------------- | ----------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| LDLib2                                            | see upstream                              | Required: the UI toolkit for every Folkways screen                                                                                                              |
| Create, Sable, Modular Golems, GeckoLib, JEI, EMI | see upstream                              | Optional integrations; Create is required by Folkways × Create, and Modular Golems by Folkways × Modular Golems                                                 |
| Kaleidoscope Cookery                              | code BSD-3-Clause, assets CC BY-NC-SA 4.0 | Required by Folkways × Kaleidoscope Cookery: native appliances, crops and equipment. Models and textures stay in the player-installed mod; none are bundled     |
| Chisels & Bits, Scena                             | MIT                                       | Required by Folkways × Chisels & Bits; the pinned C&B jar supplies Scena and the remaining runtime dependencies                                                 |
| Touhou Little Maid                                | code MIT, assets CC BY-NC-SA 4.0          | Optional: its maid models are drawn on residents straight from the player's own copy of that mod. Folkways ships none of them and a server sends only their ids |

## Repository assets, not shipped in the mod

| Path                                                      | License         | Notes                                                                                                                                                                                                          |
| --------------------------------------------------------- | --------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `tests/shared/assets/resident-models/`                    | CC0             | Yes Steve Model built-in models (Steve, Default Boy), used by end-to-end tests                                                                                                                                 |
| `tests/promo/models/` (downloaded on demand, not tracked) | CC BY-NC-SA 4.0 | Wine Fox models from Yes Steve Model's built-in pack, used only to film promotional footage. Downloads retain each folder's `ysm.json` author credits; see [the folder's README](tests/promo/models/README.md) |
