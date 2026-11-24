# Third-party components

## Bundled in the mod jars

These libraries are nested in a Folkways jar through NeoForge's Jar-in-Jar. Each one's
license text ships in that jar under `META-INF/licenses/`, and in this repository under
that mod's `src/main/resources/META-INF/licenses/`
([Core](mods/core/src/main/resources/META-INF/licenses/),
[Vanilla](mods/vanilla/src/main/resources/META-INF/licenses/),
[Assistant](mods/assistant/src/main/resources/META-INF/licenses/)).

| Component                | Version         | In                        | License    | Copyright         | Source                                                                                                |
| ------------------------ | --------------- | ------------------------- | ---------- | ----------------- | ----------------------------------------------------------------------------------------------------- |
| Starlark (Java)          | 4.2.1           | Folkways Core             | Apache-2.0 | The Bazel Authors | [bazelbuild/bazel](https://github.com/bazelbuild/bazel), packaged as `com.eed3si9n.starlark:starlark` and instrumented by Folkways for allocation limits |
| Ponder                   | 1.0.82+mc1.21.1 | Folkways Core             | MIT        | The Create Team   | [Creators-of-Create/Ponder](https://github.com/Creators-of-Create/Ponder)                             |
| Flywheel                 | 1.0.6           | Folkways Core             | MIT        | Jozufozu          | [Engine-Room/Flywheel](https://github.com/Engine-Room/Flywheel)                                       |
| Saecularia Caudices Core | 21.1.11         | Folkways Core             | MIT        | Communi Suggestu  | [Communi-Suggestu/Saecularia-Caudices](https://github.com/Communi-Suggestu/Saecularia-Caudices)       |

Core’s Chisels & Bits integration repackages the pinned Saecularia Core library with
`FMLModType: GAMELIBRARY` to make its classes load on NeoForge. Its class bytes are
unchanged.

Assistant bundles [jpi-mini for Minecraft](https://github.com/IzakyL/jpi-mini) 0.1.0
(`com.github.IzakyL.jpi-mini:jpi-mini-mc`, MIT, Copyright © 2026 IzakyL) through Jar-in-Jar.
The license ships in Assistant's `META-INF/licenses/jpi-mini.txt`.

Vanilla's Epic Fight Bedrock-geometry rendering (`vanilla.epicfight.client.geo`) is derived from YSM GEO Compat
(MIT, Copyright (c) 2026 HSZK2017). Its license is in Vanilla's `META-INF/licenses/YSM-GEO-Compat-MIT.txt`.

## Required or optional at runtime, not bundled

Players install these separately. Folkways only compiles against them.

| Component                                         | License                                   | Use                                                                                                                                                             |
| ------------------------------------------------- | ----------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| LDLib2                                            | see upstream                              | Required: the UI toolkit for every Folkways screen                                                                                                              |
| Create, Sable, Modular Golems, GeckoLib, JEI, EMI | see upstream                              | Optional Core and Vanilla integrations; Create and Sable are required by Folkways Airline                        |
| [Create Big Cannons](https://github.com/Cannoneers-of-Create/CreateBigCannons), [Ritchie’s Projectile Library](https://github.com/Wagers-of-Industrial-Warfare/RitchiesProjectileLib) | CBC MIT and CC BY-NC-SA, RPL MIT | Optional Core cannon mount integration and its runtime dependency |
| Kaleidoscope Cookery                              | code BSD-3-Clause, assets CC BY-NC-SA 4.0 | Optional Core and Vanilla integration: native appliances, crops and equipment. Models and textures stay in the player-installed mod; none are bundled     |
| Chisels & Bits, Scena                             | MIT                                       | Optional Core integration; the pinned C&B jar supplies Scena and the remaining runtime dependencies                                                 |
| Touhou Little Maid                                | code MIT, assets CC BY-NC-SA 4.0          | Optional: its maid models are drawn on residents straight from the player's own copy of that mod. Folkways ships none of them and a server sends only their ids |
| [AstikorCarts Redux](https://github.com/jusipat/astikor-carts-redux) | MIT | Optional Core and Vanilla integration; native cargo carts and their storage. Installed separately, not bundled |

TACZ and Superb Warfare (with Kotlin for Forge) and Create: FireFighting Additions are optional,
separately installed integrations in Folkways Core and Vanilla. Their models, textures and
other native assets are not bundled in Folkways jars.

## Repository assets, not shipped in the mod

| Path                                                      | License         | Notes                                                                                                                                                 |
| --------------------------------------------------------- | --------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| `tests/shared/assets/resident-models/`                    | CC0             | Yes Steve Model built-in models (Steve, Default Boy), used by end-to-end tests                                                                        |
| `tests/promo/models/` (downloaded on demand, not tracked) | CC BY-NC-SA 4.0 | Wine Fox models from Yes Steve Model's built-in pack, used only to film promotional footage. Downloads retain each folder's `ysm.json` author credits |
