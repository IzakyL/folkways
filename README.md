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
  Orders bypass container whitelists, blacklists and Allow storing. These settings
  govern ordinary resident storage and can be changed while orders exist; player storage is unaffected.
- **Compost surplus seeds.** Mark a native composter, then configure automatic
  composting and seed reserves on its Composting settings page.
- **Build from drawings.** Choose patterns for roads, arch bridges, walls, docks,
  mines, railways, arcades and terraced fields. Builders clear the site and use
  the colony's stores.
  Enable `blueprints.simpleConstruction` (default `false`) to have builders stop on entering the site and work in place.
- **Care for residents.** They sleep in their own beds, carry three days of
  rations, tire from work and travel, and flee danger. Data packs can replace
  colony name and appearance pools.
- **Let them grow with practice.** Each trade has ten levels, and every level
  draws a speciality; levels 3, 6 and 10 also draw a shared perk.
- **Set tables and build homes.** Dining areas serve table meals, beds grade their
  rooms, and mood moves work speed between 75% and 115%. Idle residents stroll,
  chat and pet animals; cats, wolves and parrots move in as the colony grows.
- **Equip and defend.** Assign loadout profiles with weapons, armor and supplies,
  then staff patrol routes and sentry posts, or command people and golems directly;
  Firearms integrations for TACZ and Superb Warfare add supported firearms.
- **Protect and restore buildings.** Mark damage-control areas for residents to
  put out fires. Record a baseline and enable repairs to replace missing building
  blocks with materials from colony stores.

## Mods

This repository builds nine mods, one per directory under `mods/`. Each ships as
`folkways-<directory>-*.jar`. Every extension requires Core and Vanilla.

| Mod                                       | Directory            | Also requires                                                                       | What it does                                                                                                                    |
| ----------------------------------------- | -------------------- | ----------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| Folkways Core                             | `mods/core`          | —                                                                                   | The colony engine: residents who plan their own work, their pathfinding, the Colony Book and panel, and direct commands and squads for any resident kind. Adds no work on its own |
| Folkways Vanilla                          | `mods/vanilla`       | Core                                                                                | Residents and their trades among vanilla blocks: farming, herding, fishing, crafting, hauling and building; weapons, loadouts and patrols |
| Folkways Carving                          | `mods/carving` | Chisels & Bits 21.1.25+                                             | Residents build and reshape carvings using whole blocks, with a small rounding loss                                                 |
| Folkways × Create | `mods/create` | Create 6.0.7+; FireFighting Additions 0.2.3-beta+ optional (requires Create [6.0.10,6.1)) | Trains, parcels, track and schematics; with the addon, residents fight fires with extinguishers and hydrant hoses and refill extinguishers |
| Folkways × Modular Golems                 | `mods/golems` | Modular Golems 3.1.6+                                                              | Golems join the roster and take trades like people                                                                              |
| Folkways × Kaleidoscope Cookery           | `mods/cookery`       | Kaleidoscope Cookery 1.6.0+; Kaleidoscope Tavern 1.2.0+ optional                    | Residents grow, prepare, cook and store food with its native appliances; with Tavern, they make and serve wine                  |
| Folkways Firearms | `mods/firearms` | Optional: TACZ 1.1.8+ (including 1.2.0); Superb Warfare 0.8.9.2+ and Kotlin for Forge [5.12.0,6) | Residents use native firearms and emplacements with physical ammunition when their target mod is installed |
| Folkways × AstikorCarts Redux | `mods/carts` | AstikorCarts Redux 1.2.2+ | Haulers hitch hand and supply carts and carry store deliveries in the cart storage, separately from their packs |
| Folkways × Epic Fight                     | `mods/epicfight` (jar: `folkways-epicfight`) | Epic Fight 21.17.1+; Core and Vanilla from the same Folkways build | Residents use native Epic Fight melee, hit reactions, everyday movement and GEO rendering |

When updating to Carving, remove the old `folkways-chiselsandbits-*.jar` before installing
`folkways-carving-*.jar`; keeping both causes duplicate registrations and a crash.

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
Firearms activates each integration only when TACZ or Superb Warfare is installed;
Create activates firefighting only when FireFighting Additions is installed.
When present, they need TACZ 1.1.8+, Superb Warfare 0.8.9.2+ and FireFighting Additions
0.2.3-beta+. Direct commands and squads are part of Core.

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
or sentry posts for autonomous defense, or select residents and command them directly.

For damage control, hold the Colony Book, press **V** and choose **Damage Control**.
Mark two corners, protect the area and allow residents to work in **Damage control**.
Repairs are off by default: right-click the area, use **Reset baseline** and enable
**Restore missing blocks**. Containers, crops, fluids and machines are excluded;
different blocks already occupying a recorded cell are reported as conflicts.

### Investigating residents

Turn on **Debug logs** on the **Settings** page to have work interruptions and failures reported in chat as they happen,
with planning status every 10 seconds. While it is on, look at a resident or a registered block within 32 blocks
to show a debug card at the right edge of the screen; hold Alt and scroll to page through it.

- **Residents**: standing and work stage, the work queued next on their route and where it is, why the next work
  has not started, urges and their cooldowns, what they carry and reserve, and recent events. Numbered lines in the
  world lead to the places they will go next.
- **Registered blocks**: the store's record state, differences between the colony's record and the real contents,
  reservations on it, and the work planned there with the residents assigned. Lines lead to each assigned resident.

Nearby registered stores are outlined by how current the colony's record of them is: green read, yellow due for
update, grey not yet read, blue unloaded, red unsupported. A red corner mark means the record and the real contents
differ. Only you see this, and disconnecting turns it off.
The nearby waypoint graph refreshes every second: ivory boxes are waypoints, hot pink boxes residents' feet,
and indigo boxes/lines crossing endpoints/passages; ice white lines are walking, deep teal other locomotion,
electric lime dashed lines probation, and raspberry lines failed links (dashed during probation, solid when retryable).
Small arrows show direction; the view covers 48 blocks and reports truncation above 2000 points or 4000 edges.

## Optional integrations

Folkways also works with Create and its FireFighting Additions, Sable, AstikorCarts Redux,
Chisels & Bits, Kaleidoscope Cookery and Tavern, Modular Golems, TACZ, Superb Warfare,
Epic Fight, GeckoLib, Touhou Little Maid, FTB Teams, JEI/EMI, Just Enough Characters,
Shoulder Surfing Reloaded, AppleSkin and Iris. See [Integrations](INTEGRATIONS.md) for
what each one adds, how to use it in game and the versions it needs.

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
