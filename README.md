# Folkways

[![License][badge-license]](LICENSE)
[![Last commit][badge-last-commit]](https://github.com/IzakyL/folkways/releases/tag/nightly)
[![Last release][badge-last-release]](https://github.com/IzakyL/folkways/releases?q=-tag%3Anightly)
[![Discord][badge-discord]](https://discord.gg/n5T4x3mk9q)
[![KOOK][badge-kook]](https://kook.vip/5RTovu)

English · [简体中文](README.zh-CN.md)

Folkways is a colony mod for **Minecraft 1.21.1** on **NeoForge**. Found a colony,
mark its blocks and work areas, and rank each resident's trades. The colony plans
what to fetch, make, haul and build to keep the settlement running.

**Pre-release:** saves, data pack formats and the Java API may change between
versions. Back up your world before trying a nightly build.

[Mods](#mods) · [Installation](#installation) · [Getting started](#getting-started) ·
[Integrations](#optional-integrations) · [Contributing](CONTRIBUTING.md)

## What it does

- **Mark the colony's blocks.** Register chests, beds and work stations with the
  Colony Book. Fencing off land claims nothing.
- **Rank trades, then let residents plan.** Set priorities on the Workforce page;
  the colony chooses tasks, supplies and the order of work.
- **Lay out work areas.** Frame fields, pens and fisheries, choose crops and herd
  sizes, and draw patrol routes.
- **Keep chests stocked.** Set an item and a target amount. Residents craft, smelt,
  harvest or haul until the order is filled.
- **Compost surplus seeds.** Mark a native composter, then configure automatic
  composting and seed reserves on its Composting settings page.
- **Build from drawings.** Choose patterns for roads, arch bridges, walls, docks,
  mines, railways, arcades and terraced fields. Builders clear the site and use
  the colony's stores.
- **Care for residents.** They sleep in their own beds, carry three days of
  rations, tire from work and travel, and flee danger. Data packs can replace
  colony name and appearance pools.
- **Let them grow with practice.** Each trade has ten levels, and every level
  draws a speciality; levels 3, 6 and 10 also draw a shared perk.
- **Set tables and build homes.** Dining areas serve table meals, beds grade their
  rooms, and mood moves work speed between 75% and 115%. Idle residents stroll,
  chat and pet animals; cats, wolves and parrots move in as the colony grows.
- **Equip and defend.** Assign loadout profiles with weapons, armor and supplies,
  then staff patrol routes and sentry posts. RTS adds direct resident commands;
  the TACZ and Superb Warfare extensions add supported firearms.
- **Protect and restore buildings.** Mark damage-control areas for residents to
  put out fires. Record a baseline and enable repairs to replace missing building
  blocks with materials from colony stores.

## Mods

This repository builds nine mods, one per directory under `mods/`. Each ships as
`folkways-<directory>-*.jar`. Every extension requires Core and Vanilla.

| Mod                                       | Directory            | Also requires                                                                       | What it does                                                                                                                    |
| ----------------------------------------- | -------------------- | ----------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| Folkways Core                             | `mods/core`          | —                                                                                   | The colony engine: residents who plan their own work, their pathfinding, and the Colony Book and panel. Adds no work on its own |
| Folkways Vanilla                          | `mods/vanilla`       | Core                                                                                | Residents and their trades among vanilla blocks: farming, herding, fishing, crafting, hauling and building                      |
| Folkways × Create                         | `mods/create`        | Create 6.0+                                                                         | Residents drive trains, send parcels through packaging and postboxes, lay track and build from schematics                       |
| Folkways × Modular Golems                 | `mods/modulargolems` | Modular Golems 3.1.43+                                                              | Golems join the roster and take trades like people                                                                              |
| Folkways × Kaleidoscope Cookery           | `mods/cookery`       | Kaleidoscope Cookery 1.6.x; Kaleidoscope Tavern 1.2.x optional                      | Residents grow, prepare, cook and store food with its native appliances; with Tavern, they make and serve wine                  |
| Folkways RTS                              | `mods/rts`           | —                                                                                   | Resident commands, squads and real-time controls                                                                                |
| Folkways × TACZ                           | `mods/tacz`          | TACZ 1.21.1 NeoForge port 1.1.8-hotfix-r7                                            | Residents use supported TACZ guns with physical ammunition from colony supplies                                                 |
| Folkways × Superb Warfare                 | `mods/warfare`       | Superb Warfare 0.8.9.2, Kotlin for Forge 5.12.0+ (below 6)                           | Residents use supported firearms and emplacements with ammunition from colony stores                                        |
| Folkways × Create: FireFighting Additions | `mods/firefighting`  | Folkways × Create, Create 6.0.10+ (below 6.1), Create: FireFighting Additions 0.2.5-beta-fix2 | Residents fight fires with portable extinguishers and hydrant hoses, and refill extinguishers                                           |

## Installation

Get pre-release jars from the upstream
[nightly build](https://github.com/IzakyL/folkways/releases/tag/nightly).
[Alpha and later releases](https://github.com/IzakyL/folkways/releases?q=-tag%3Anightly)
are listed separately from nightly builds. The Last release badge has no matching
date until the first versioned release is published.

| Requirement                              | Version                             |
| ---------------------------------------- | ----------------------------------- |
| Minecraft                                | 1.21.1                              |
| [NeoForge](https://neoforged.net/)       | 21.1.233 or newer                   |
| [LDLib2](https://modrinth.com/mod/ldlib) | 2.2.37 or newer; install separately |

Install **Core and Vanilla**, then add extensions for the mods you use.
**All Folkways jars must come from the same build.**

See [Mods](#mods) for what each jar adds and what it needs.
TACZ 1.1.8-hotfix-r7, Superb Warfare 0.8.9.2 and FireFighting Additions
0.2.5-beta-fix2 are exact requirements, not minimum versions. RTS is optional
for these integrations and for autonomous patrol and damage-control work.

Ponder and Flywheel are bundled in Core; the Starlark interpreter is bundled in
Vanilla. They do not need separate installation.

## Getting started

1. Craft a **Colony Book** from one book.
2. Right-click to open the colony panel and select **New Colony**.
3. Left-click chests, beds and work stations with the book to register them.
4. Stock food in a registered chest and leave a registered bed free. Newcomers
   arrive over time; admit them from the **Human residents** page.
5. Sneak and scroll to switch the book to **marking out**. Left-click two opposite
   corners of a plot, then right-click and choose **Farm**.

The panel's **Lessons** tab contains Ponder tutorials for the rest of the mod.

For combat, open **Loadout profiles**, create a profile and assign human residents.
Keep the selected weapons, armor and matching ammunition in registered stores
that allow taking items. Enable the **Patrol** trade and staff patrol routes
or sentry posts for autonomous defense; install RTS for direct commands.

For damage control, hold the Colony Book, press **V** and choose **Damage Control**.
Mark two corners, protect the area and allow residents to work in **Damage control**.
Repairs are off by default: right-click the area, use **Reset baseline** and enable
**Restore missing blocks**. Containers, crops, fluids and machines are excluded;
different blocks already occupying a recorded cell are reported as conflicts.

## Optional integrations

| Mod                                                                                                                         | What it adds                                                                                                                                                                             |
| --------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [Create](https://modrinth.com/mod/create) 6.0+, with Folkways × Create                                                      | Schematic building, train driving, elevator rides, track laying and orders through a stock keeper's package network. Core handles walking around contraptions even without the extension |
| [Sable](https://modrinth.com/mod/sable)                                                                                     | Residents path onto and off sub-levels, including Create Aeronautics ships                                                                                                               |
| [Modular Golems](https://modrinth.com/mod/modular-golems) 3.1.43+, with Folkways × Modular Golems                           | Golems join the roster and take trades alongside people                                                                                                                                  |
| [Kaleidoscope Cookery](https://github.com/KaleidoscopeMods/KaleidoscopeCookery) 1.6.x, with Folkways × Kaleidoscope Cookery | Flooded-rice farming, ingredient preparation, native appliances, shared tools, kitchen racks, grass-seed gathering and thirteen lessons                                                  |
| [Kaleidoscope Tavern](https://github.com/KaleidoscopeMods/KaleidoscopeTavern) 1.2.x, with the Cookery extension             | Grape vineyards under pergolas, pressing, fermenting and bottling wine, and dining-area bar counters residents stock and drink at; one lesson                                            |
| [TACZ 1.21.1 NeoForge port](https://modrinth.com/mod/OypNE65K) 1.1.8-hotfix-r7, with Folkways × TACZ | Residents fire and reload supported guns using physical matching ammunition; weapon choices preserve the gun model rather than matching only its shared item ID |
| [Superb Warfare](https://modrinth.com/mod/superb-warfare) 0.8.9.2, with Folkways × Superb Warfare and Kotlin for Forge 5.12.0+ (below 6) | Residents use supported firearms and emplacements, consuming ammunition from colony stores |
| [Create: FireFighting Additions](https://modrinth.com/mod/create-firefighting-additions) 0.2.5-beta-fix2, with Folkways × Create: FireFighting Additions and its dependencies listed above | Damage-control workers use and refill water-filled portable extinguishers, or use a hydrant cabinet supplied with a hose, nozzle and water |
| [GeckoLib](https://modrinth.com/mod/geckolib) 4.9+                                                                          | Bedrock-format resident models                                                                                                                                                           |
| [Touhou Little Maid](https://modrinth.com/mod/touhou-little-maid) 1.5+, with GeckoLib                                       | Built-in and installed add-on models become selectable resident looks, individually or by pack                                                                                           |
| [Shoulder Surfing Reloaded](https://modrinth.com/mod/shoulder-surfing-reloaded) 5.2.0+                                      | Colony Book aiming follows its third-person crosshair                                                                                                                                    |
| [JEI](https://modrinth.com/mod/jei) / [EMI](https://modrinth.com/mod/emi)                                                   | Item filters open the recipe viewer's ingredient list                                                                                                                                    |
| [FTB Teams](https://www.curseforge.com/minecraft/mc-mods/ftb-teams-forge) 2101.1+                                           | A founder can hand the colony to their party; party members keep it and the party owner holds the founder's rights                                                                       |
| [Just Enough Characters (JEC)](https://www.curseforge.com/minecraft/mc-mods/just-enough-characters) 4.5+                    | Pinyin, initials and mixed-input searches in item and folder pickers, Workforce and stock; install on the client only                                                                    |

JEC searches follow its keyboard and fuzzy-spelling settings.

## Building from source

With a **Java 21 JDK** and **Node.js 22.14+**, run the locked setup on **Linux x64**
from the repository root:

```sh
npm ci
npm run setup
./gradlew build
```

Setup downloads the pinned integration dependencies and writes the manifest
required by Gradle. The nine mod jars are collected in `build/libs/`.
Blockwright's native Windows support is available in its source tree; the pinned
npm release predates it. For Windows builds using a source-built SDK and
`gradlew.bat`, see [development setup](CONTRIBUTING.md#development-setup).

See [CONTRIBUTING.md](CONTRIBUTING.md) for the development and pull request workflow.
[Report bugs](https://github.com/IzakyL/folkways/issues/new?template=bug_report.yml)
or [request features](https://github.com/IzakyL/folkways/issues/new?template=feature_request.yml)
upstream. Join [Discord](https://discord.gg/n5T4x3mk9q) or [KOOK](https://kook.vip/5RTovu)
for community discussion.

## License

Folkways is released under the [MIT License](LICENSE).

Bundled [Starlark](https://github.com/bazelbuild/starlark) is Apache-2.0 licensed;
[Ponder](https://github.com/Creators-of-Create/Ponder) and
[Flywheel](https://github.com/Engine-Room/Flywheel) are MIT licensed. Their license
texts ship under `META-INF/licenses/` in the relevant jar. See
[THIRD_PARTY.md](THIRD_PARTY.md) for component details and asset licenses.

The Wine Fox models prepared under `tests/promo/models/` are **CC BY-NC-SA 4.0**,
outside Folkways' MIT license. They are used only for promotional footage and
are not shipped in the mod; see [THIRD_PARTY.md](THIRD_PARTY.md).

[badge-license]: https://img.shields.io/github/license/IzakyL/folkways?style=flat-square&label=license&color=22c55e
[badge-last-commit]: https://img.shields.io/github/last-commit/IzakyL/folkways/master?style=flat-square&logo=github&logoColor=white&label=last%20commit&color=0ea5e9
[badge-last-release]: https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fapi.github.com%2Frepos%2FIzakyL%2Ffolkways%2Freleases%3Fper_page%3D100&search=%22tag_name%22%5Cs*%3A%5Cs*%22v%3F%5B0-9%5D%2B%5C.%5B0-9%5D%2B%28%3F%3A%5C.%5B0-9%5D%2B%29%3F%28%3F%3A-%28%3F%3Aalpha%7Cbeta%7Crc%29%5B%5E%22%5D*%29%3F%28%3F%3A%5C%2B%5B%5E%22%5D*%29%3F%22%5B%5Cs%5CS%5D*%3F%22published_at%22%5Cs*%3A%5Cs*%22%28%5B0-9%5D%7B4%7D-%5B0-9%5D%7B2%7D-%5B0-9%5D%7B2%7D%29&replace=%241&flags=i&label=last+release&color=0ea5e9&style=flat-square&logo=github&logoColor=white
[badge-discord]: https://img.shields.io/badge/Discord-join-5865F2?style=flat-square&logo=discord&logoColor=white
[badge-kook]: https://img.shields.io/badge/KOOK-join-34D399?style=flat-square
