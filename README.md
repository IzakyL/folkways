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

[Installation](#installation) · [Getting started](#getting-started) ·
[Integrations](#optional-integrations) · [Documentation](docs/en/README.md) ·
[Contributing](CONTRIBUTING.md)

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
- **Care for residents.** They sleep in their own beds, carry three days of rations, tire from work
  and travel, learn trades, gain perks and flee danger. Data packs can replace
  colony name and appearance pools.

### Dining and pocket rations

Residents carry three days of food by default, replenishing when their pocket rations fall below one
working day. Choose permitted pocket foods in the colony's Living settings. A day's ration is calculated
from nutrition rather than item count, so different foods require different amounts.

Mark a dining area, select its table foods, and place adjacent tables and chairs inside it. Leave an
accessible aisle beside each chair. Residents bring one serving at a time from colony stores and keep
food on display. With several table foods selected, each empty serving slot requests a random
dish from the menu and keeps that choice until delivery; these requests can drive production directly. Basic tables, including top slabs, support placeable foods such as cake; supported
native tables accept edible foods and use the area's servings-per-table setting. Residents preserve
food components, leave manually placed servings alone, and return unused supplies if an area is removed.

Hungry residents prefer an available nearby table and return native food containers after eating.
Finishing a table meal grants +6 dining mood for one day. If no suitable table meal is available, they
can eat their pocket rations. Cancelling a table meal leaves its food untouched and frees the seat.

With Kaleidoscope Tavern installed alongside the Cookery extension, a dining area's Drinks setting
reserves the last slot of each supported table for a drink. Residents serve it separately from food,
hold it at the end of the meal, apply its native effects on completion, and return its empty bottle.
Drinks add no nutrition, and cancelling a meal leaves its drink on the table. Mark a pressing tub and
a wine barrel with a tap to produce grape wine from grapes, empty buckets and empty bottles. Workers
jump in the tub, wait for the barrel's native second brewing level, and bottle its actual wine.

### Resident growth and everyday life

Residents learn automatically. Each completed second of active work earns one experience point;
walking on hauling and patrol duties also counts while the resident actually moves. A trade has ten
levels, at 3, 8, 15, 25, 40, 55, 75, 100, 130 and 160 minutes of cumulative effective work. Every
level draws a rank from that trade's five independent, three-rank specialities. By level five a resident
has discovered at least three different specialities. Levels 3, 6 and 10 additionally draw from the shared
perk pool; these shared ranks carry between trades. Fully ranked perks leave their draw pool; once all shared ranks are held, further shared milestones grant nothing. There are
fifteen occupational ranks but only ten draws, so residents develop different combinations automatically.

| Trade | Independent specialities (rank I / II / III defaults) |
| --- | --- |
| Building and excavation | Skilled Builder: work speed +25% / 50% / 80%; Prospector: 8% / 14% / 22% mineral finds; Thrifty Builder: 10% / 20% / 30% material refunds; Quarry Momentum: up to +30% / 60% / 100% consecutive stone mining speed; Tool Expert: 25% / 50% / 75% less wear |
| Farming | Skilled Farmer: speed +25% / 50% / 80%; Chain Harvest: 1 / 3 / 7 additional adjacent mature crops; Bountiful: +20% / 40% / 70% food yield; Replant: automatic replanting with 0% / 25% / 50% seed saving; Care: +20% / 40% / 60% growth for tended age-bearing crops |
| Husbandry | Skilled Herder: speed +25% / 50% / 80%; Butcher: +25% / 50% / 100% ordinary carcass yield; Fleece: +1 / 2 / 3 wool; Breeder: 10% / 20% / 35% twin births within the pasture target; Nurture: once per baby, feeding additionally shortens remaining growth by 15% / 30% / 50% |
| Fishing | Skilled Angler: 20% / 35% / 50% shorter waits; School: 25% / 45% / 70% chance of 1 / 1 / 2 extra fish; Treasure: 3% / 6% / 10% extra discoveries; Water Sense: increasingly favors less common local fish; Rod Care: 25% / 50% / 75% less wear |
| Hauling | Heavy Hauler: capacity +50% / 100% / 200%; Fleet Courier: hauling movement +15% / 30% / 50%; Quick Exchange: 25% / 45% / 65% shorter transfer actions; Along the Way: gather 2 / 3 / 4 compatible loads sharing a source or destination; Long Haul: 30% / 55% / 80% less walking fatigue |
| Crafting | Quick Hands: manual speed +30% / 60% / 100%; Batch: 2 / 4 / 8 crafts per operation; Frugal: 8% / 15% / 25% ingredient refunds on eligible recipes; Byproducts: 15% / 30% / 50% extra configured byproducts; Furnace Experience: +15% / 30% / 50% cooking speed for the batch loaded by that resident |
| Patrol | Deterrence: ward radius +3 / 6 / 10 blocks and duration +25% / 50% / 100%; Martial: +20% / 40% / 70% damage against hostiles; Stalwart: 10% / 20% / 35% less damage from hostiles; Alert: detection +4 / 8 / 12 blocks and shared threats; Reassuring: +3 / 6 / 10 ward safety mood and faster recovery from attacks |

Prospector selects minerals from the mined position's actual biome ore features. Relative weights account
for placement count, rarity, configured vein size, height distribution and replacement targets; vanilla
uniform and trapezoid height probabilities are calculated exactly. Custom height/count providers use
cached deterministic sampling. Nonstandard placement modifiers are not simulated. Discoveries use ordinary
ore loot, without Fortune or Silk Touch; rank III has a 25% chance of a second portion. Placed stone
and repeated mining at the same position are eligible; no placement or prospecting history is stored.
Ordinary stone and eligible minerals use `folkways:prospecting_stone`
and `folkways:prospectable_minerals` block tags; the latter includes `c:ores` for modded minerals.

Material refunds use the `folkways:returnable_building_materials` block tag, excluding block entities.
Chain harvesting stays inside the field and respects other active crop jobs. Crop care persists for a
plant's lifetime without stacking; it supports age-bearing crops that publish growth events, not saplings.
Along the Way reorders only ready work whose remaining route still fits the pack, keeping individual
reservations and deliveries intact. Shared-destination pickups permit at most eight additional blocks of estimated travel, using straight-line distances; obstacles can increase the actual walk. Furnace Experience tracks loaded input batches in FIFO order across
saves; removing or replacing inputs removes the corresponding training, and manual additions remain ordinary.

Crafting refund and byproduct rules live at `data/<recipe namespace>/crafting_perks/<recipe path>.json`.
For example, `{"refund": true, "byproduct": "minecraft:stick", "count": 1}` opts one recipe into both
abilities. Defaults cover selected ordinary recipes; reversible storage and packing recipes are excluded.
Fishing discoveries use the `folkways:gameplay/fishing_discoveries` loot table with the actual fishing origin,
so data packs can condition discoveries on biome and dimension.

Shared perks grant reach +1 / 2 / 3 blocks, movement +10% / 20% / 30%, hunger and fatigue consumption
reductions of 15% / 30% / 50%, food/housing/weather penalties reduced by 20% / 40% / 60%, and chatting
recovery increased by 30% / 60% / 100% for both partners. Focused is a single-rank patience perk. Legacy
Packmule remains effective for existing residents but leaves the draw pool. Older saves retain their
levels and independent perks, with excess ranks capped and experience moved to the new level threshold;
version-two shared ranks are moved back into shared storage. Work-time ladders remain configurable.

Human residents have a persistent mood from 0 to 100. Food, housing, rest, recreation, company,
safety and surroundings determine its target; mood moves gradually toward it. Work speed ranges
from 75% at mood 0, through 100% at 60, to 115% at 100. The resident's detail window shows every
contribution, recreation, housing grade and the next housing improvement. Their overhead card
keeps the summary beside fullness and rest.

Idle residents stroll, look around, chat in pairs, relax, or pet a colony member's tame cat or wolf.
Mark a **Recreation area** for a garden or square they can visit. Activities restore recreation as
they happen; repeated activities give diminishing returns. Work interrupts ordinary recreation,
while a resident deprived of recreation may take a short break between jobs. Petting preserves
the owner's sit command and never takes control of the pet's navigation. Residents can also interact
with parrots and the colony's visiting animals. Player skins have dedicated
leisure motions; custom model manifests and look entries may map `chatting`, `petting`, `relaxing`
and `strolling` through a `leisure_animations` object. Missing clips fall back to basic idle/head motions.

Registered beds automatically survey their room. Doors separate rooms even while open, and need
usable approaches on both sides. Floors, roof coverage, lighting, beds and different furnishing
categories determine housing quality:

| Housing | Floor area per bed | Beds per room | Furnishing categories | Mood |
| --- | --- | --- | --- | --- |
| Basic bed | Requirements unmet | Any | Any | −6 |
| Suitable dormitory | 4+ | Any | Any | +2 |
| Comfortable housing | 6+ | Up to 4 | 2+ | +6 |
| Excellent bedroom | 9+ | Up to 2 | 3+, without production stations | +10 |

As a colony grows, cats, wolves (Minecraft's dogs) and parrots can settle nearby. Development counts
human residents with suitable beds in each dimension: three unlock cats, six unlock wolves and ten
unlock parrots. The colony supports one visitor per three housed residents across all dimensions, with no fixed
colony-wide maximum. Every half Minecraft day there is a 50% chance to attempt one arrival near suitable
housing, while residents are loaded and mob spawning is enabled. Arrivals require loaded, lit, dry,
unobstructed ground away from nearby hostiles. These animals remain untamed and can be adopted
normally; they persist like named animals. Saved arrival times and animal identities prevent reloading
or unloading chunks from producing extra visitors. Death or removal frees a place; population decline
pauses new arrivals without removing existing animals.

All suitable rooms need enclosure, a roof, an entrance and brightness of at least 8 in usable spaces.
A highest-grade bedroom must include decoration among its furnishing categories.
A resident without a bed receives −12. Sleeping records the room's housing experience. Room surveys
are shared, incremental and bounded to 1,024 floor cells, 32 blocks from the bed and a roof within
12 blocks above; unloaded or oversized rooms remain awaiting inspection. Block edits invalidate
nearby surveys; periodic inspection also catches command and machine changes.

Data packs can extend `folkways:food_groups/*`, `folkways:fine_meals`,
`folkways:husbandry_products` item tags and `folkways:housing/*` block tags. Furnishing categories
are storage, textiles, decoration and tables; duplicate furniture of one category does not increase
the category count. Defaults include chests and barrels, wool carpets, bookshelves and potted flowers;
the table tag starts empty for data packs to extend. Container and block-name fallbacks also recognize
common storage, carpet, potted-plant and table blocks. Crafting tables, furnaces and similar stations
count as production equipment rather than furnishing categories.


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

| Jar                                                        | When to install                                                                    |
| ---------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| Folkways Core (`folkways-core-*.jar`)                      | Always: colony engine, Colony Book and panel                                       |
| Folkways Vanilla (`folkways-vanilla-*.jar`)                | Always: residents and their work among vanilla blocks                              |
| Folkways × Create (`folkways-create-*.jar`)                | With Create: trains, parcels, track and schematics                                 |
| Folkways × Modular Golems (`folkways-modulargolems-*.jar`) | With Modular Golems: golems as residents                                           |
| Folkways × Kaleidoscope Cookery (`folkways-cookery-*.jar`) | With Kaleidoscope Cookery 1.6.0: native crops, appliances and shared kitchen tools |

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
See the [gameplay guide](docs/en/gameplay.md) for colony membership, storage rules,
composting, buildings and resident looks.

## Optional integrations

| Mod                                                                                                                         | What it adds                                                                                                                                                             |
| --------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| [Create](https://modrinth.com/mod/create) 6.0+, with Folkways × Create                                                      | Schematic building, train driving, track laying and orders through a stock keeper's package network. Core handles walking around contraptions even without the extension |
| [Sable](https://modrinth.com/mod/sable)                                                                                     | Residents path onto and off sub-levels, including Create Aeronautics ships                                                                                               |
| [Modular Golems](https://modrinth.com/mod/modular-golems) 3.1.43+, with Folkways × Modular Golems                           | Golems join the roster and take trades alongside people                                                                                                                  |
| [Kaleidoscope Cookery](https://github.com/KaleidoscopeMods/KaleidoscopeCookery) 1.6.0, with Folkways × Kaleidoscope Cookery | Flooded-rice farming, ingredient preparation, native appliances, shared tools, kitchen racks, grass-seed gathering and thirteen lessons                                  |
| [GeckoLib](https://modrinth.com/mod/geckolib) 4.9+                                                                          | Bedrock-format resident models                                                                                                                                           |
| [Touhou Little Maid](https://modrinth.com/mod/touhou-little-maid) 1.5+, with GeckoLib                                       | Built-in and installed add-on models become selectable resident looks, individually or by pack                                                                           |
| [Shoulder Surfing Reloaded](https://modrinth.com/mod/shoulder-surfing-reloaded) 5.2.0+                                      | Colony Book aiming follows its third-person crosshair                                                                                                                    |
| [JEI](https://modrinth.com/mod/jei) / [EMI](https://modrinth.com/mod/emi)                                                   | Item filters open the recipe viewer's ingredient list                                                                                                                    |
| [FTB Teams](https://www.curseforge.com/minecraft/mc-mods/ftb-teams-forge) 2101.1+                                           | A founder can hand the colony to their party; party members keep it and the party owner holds the founder's rights                                                       |
| [Just Enough Characters (JEC)](https://www.curseforge.com/minecraft/mc-mods/just-enough-characters) 4.5+                    | Pinyin, initials and mixed-input searches in item and folder pickers, Workforce and stock; install on the client only                                                    |

JEC searches follow its keyboard and fuzzy-spelling settings. See [Searching](docs/en/gameplay.md#searching)
for examples and how item searches differ from saved filter rules.
For Touhou Little Maid add-on installation, see [Maid model packs](docs/en/gameplay.md#maid-model-packs).

## Documentation

- [Gameplay](docs/en/gameplay.md): members, work, storage, composting and resident looks.
- [Custom content](docs/en/custom-content.md): patterns, blueprints, skins, models and server configuration.
- [Testing](docs/en/testing.md): local checks, game tests, Cookery fixtures and CI.
- [Architecture](docs/en/architecture.md): modules, public APIs, source layout and lifecycle.

## Building from source

With a **Java 21 JDK** and **Node.js 22.14+**, run the locked setup on **Linux x64**
from the repository root:

```sh
npm ci
npm run setup
./gradlew build
```

Setup downloads the pinned integration dependencies and writes the manifest
required by Gradle. The five mod jars are collected in `build/libs/`.
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
are not shipped in the mod; see [their README](tests/promo/models/README.md).

[badge-license]: https://img.shields.io/github/license/IzakyL/folkways?style=flat-square&label=license&color=22c55e
[badge-last-commit]: https://img.shields.io/github/last-commit/IzakyL/folkways/master?style=flat-square&logo=github&logoColor=white&label=last%20commit&color=0ea5e9
[badge-last-release]: https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fapi.github.com%2Frepos%2FIzakyL%2Ffolkways%2Freleases%3Fper_page%3D100&search=%22tag_name%22%5Cs*%3A%5Cs*%22v%3F%5B0-9%5D%2B%5C.%5B0-9%5D%2B%28%3F%3A%5C.%5B0-9%5D%2B%29%3F%28%3F%3A-%28%3F%3Aalpha%7Cbeta%7Crc%29%5B%5E%22%5D*%29%3F%28%3F%3A%5C%2B%5B%5E%22%5D*%29%3F%22%5B%5Cs%5CS%5D*%3F%22published_at%22%5Cs*%3A%5Cs*%22%28%5B0-9%5D%7B4%7D-%5B0-9%5D%7B2%7D-%5B0-9%5D%7B2%7D%29&replace=%241&flags=i&label=last+release&color=0ea5e9&style=flat-square&logo=github&logoColor=white
[badge-discord]: https://img.shields.io/badge/Discord-join-5865F2?style=flat-square&logo=discord&logoColor=white
[badge-kook]: https://img.shields.io/badge/KOOK-join-34D399?style=flat-square
