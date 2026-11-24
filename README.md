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

- **Mark the colony's blocks.** Left-click chests, beds and work stations with the Colony Book to register them. Fencing off land claims nothing.
- **Rank trades, then let residents plan.** Set priorities on the **Workforce** page;
  the colony chooses tasks, supplies and the order of work.
- **Lay out work areas.** Frame fields, pens and fisheries, choose crops and herd
  sizes, and draw patrol routes.
- **Keep chests stocked.** Set an item and a target amount. Residents craft, smelt,
  harvest or haul until the order is filled.
  Orders bypass container allowlists, denylists and Allow storing. These settings
  govern ordinary resident storage and can be changed while orders exist; player storage is unaffected.
  Residents put goods away in ordinary stores. Facility parts such as machine inputs and outputs receive
  and release goods through the facility's own work. A Tom's Simple Storage terminal registered as a store
  places the same orders from its **Order…** button; see [INTEGRATIONS.md](INTEGRATIONS.md#stores-from-storage-mods).
- **Compost surplus seeds.** Register a native composter so residents can turn excess seed stock into bone meal.
- **Build from drawings.** Choose patterns for roads, arch bridges, walls, docks,
  mines, railways, arcades and terraced fields. Builders clear the site and use
  the colony's stores. Automatic work such as farming, construction and damage control
  skips protected facility blocks. Explicit **Clear** orders under **Groundwork** can remove
  them, and the confirmation preview lists affected facilities.
  Enable `blueprints.simpleConstruction` (default `false`) to have builders stop on entering the site and work in place.
- **Care for residents.** They sleep in their own beds, carry three days of
  rations, and tire from work and travel. Residents flee visible attackers or
  dangers such as burns, favoring reachable spots near colony facilities and
  members. Data packs can replace
  colony name and appearance pools; see [DATAPACKS.md](DATAPACKS.md) for data packs,
  tags and the `folkways/` library folder.
- **Let them grow with practice.** Each trade has ten levels, and every level
  draws a speciality; levels 3, 6 and 10 also draw a shared perk.
- **Set tables and build homes.** Dining areas serve table meals, beds grade their
  rooms, and mood moves work speed between 75% and 115%. Idle residents stroll, chat and pet animals. Cats, wolves
  and parrots move in as the colony grows.
- **Equip and defend.** Assign equipment profiles with weapons, armor, supplies and backpacks,
  then staff patrol routes and sentry posts, or command people and golems directly;
  Core and Vanilla integrate supported TACZ and Superb Warfare firearms.
- **Protect and restore buildings.** Mark damage-control areas for residents to
  put out fires. Record a baseline and enable repairs to replace missing building
  blocks with materials from colony stores.

## Mods

This repository builds four mods, one per directory under `mods/`. Each ships as
`folkways-<directory>-*.jar`. Every extension requires Core; extensions other than Assistant also require Vanilla.

| Mod                                       | Directory            | Also requires                                                                       | What it does                                                                                                                    |
| ----------------------------------------- | -------------------- | ----------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| Folkways Core                             | `mods/core`          | —                                                                                   | The colony engine: residents who plan their own work, their pathfinding, the Colony Book and panel, and direct commands and squads for any resident kind. Includes the AE2/RS2 **Network service** vocation |
| Folkways Vanilla                          | `mods/vanilla`       | Core                                                                                | Residents and their trades among vanilla blocks: farming, herding, fishing, crafting, hauling and building; weapons, equipment profiles and patrols |
| Folkways Airline                          | `mods/airline`       | Core, Vanilla, Create 6.0.10–6.0.x, Sable 2.0.6                                     | Airport patterns, surveys, towers, runways, helipads, taxiways and stands, including moving airports on Sable structures |
| Folkways Assistant                        | `mods/assistant`     | Core; install on both client and server                                             | An in-game chat assistant that reads your colony and answers questions through a language model you configure                   |

Assistant provides the tool registry for reading colony data and previewing and confirming changes. It uses
Core’s colony and facility APIs. Configure a model in the assistant settings and add an API key if your
provider requires one. See the [colony assistant integration](INTEGRATIONS.md#colony-assistant) for supported
providers. Facility definitions, facility parameters and colony script writes always require confirmation.

Core includes both directions of the AE2 and RS2 integrations, including the **Network service** vocation.
It also includes the Modular Golems, Epic Fight and Chisels & Bits integrations. See [Integrations](INTEGRATIONS.md)
for verified versions. Core also provides Cookery, Tavern and AstikorCarts Redux adapters. Vanilla adds
kitchens, winemaking and cart lines when the corresponding integrations are enabled. When upgrading, remove the old `folkways-golems-*.jar`, `folkways-epicfight-*.jar`,
`folkways-carving-*.jar`, `folkways-chiselsandbits-*.jar`, `folkways-cookery-*.jar`, `folkways-carts-*.jar`, `folkways-firearms-*.jar` and `folkways-create-*.jar`.

Airline's runway markings, stands and bookings are described under
[Airports](INTEGRATIONS.md#airports-folkways-airline); commanding aircraft and colony vehicle ownership are under
[TACZ and Superb Warfare](INTEGRATIONS.md#tacz-and-superb-warfare-core-and-vanilla).

## Installation

Get pre-release jars from the upstream
[nightly build](https://github.com/IzakyL/folkways/releases/tag/nightly).
[Versioned releases](https://github.com/IzakyL/folkways/releases?q=-tag%3Anightly)
are listed separately from nightly builds.

| Requirement                              | Version                             |
| ---------------------------------------- | ----------------------------------- |
| Minecraft                                | 1.21.1                              |
| [NeoForge](https://neoforged.net/)       | 21.1.233 or newer                   |
| [LDLib2](https://modrinth.com/mod/ldlib) | 2.2.37 or newer; install separately |

Install **Core and Vanilla**, then add Airline or Assistant if you want them.
**All Folkways jars must come from the same build.**

See [Mods](#mods) for what each jar adds and what it needs.
Core and Vanilla activate TACZ, Superb Warfare and Create support, and FireFighting Additions support alongside
Create, only for the versions listed in [Integrations](INTEGRATIONS.md). Direct commands and squads are part of Core.

Ponder, Flywheel and the Starlark interpreter are bundled in Core. They do not need separate installation.

## Getting started

1. Craft a **Colony Book** from one book.
2. Right-click to open the colony panel and select **New Colony**.
3. Left-click chests, beds and work stations with the book to register them. A new book starts out taking things in.
4. In Survival, stock food in a registered chest and leave a registered bed free. Newcomers
   arrive over time. Click **Let one in** on **Workforce** to admit one. In Creative, the button
   admits a settler immediately, regardless of the queue, beds or food.
5. Sneak and scroll to turn the book to its next gesture: taking in, marking out a box or drawing a line.
   Mark out two opposite corners of a plot, right-click and choose **Farm**: the farm's card opens to set it up.
   With the book in hand, right-click a held zone, path or block to open its card.
   In the view from above (**V**), the book's tools are grouped along the top by what they serve, such as
   **Food & farming** or **Defense**; pick one first and the shape you draw becomes it.

**Overview** shows what needs attention. **Workforce** covers residents and their priorities, and
**Stock & orders** shows supplies and orders. **Facilities** groups zones, routes and more, and lists held
blocks by kind beside them, folded like **Lessons**; open a facility's card from its row. **Settings** opens cards
for colony-wide options. **Members** manages membership and **Lessons** contains Ponder tutorials.

For autonomous defense, enable the **Patrol** trade and configure staffing on the patrol route or
sentry post card. Keep weapons, armor and matching ammunition in registered stores that allow taking items. For direct control, hold the Colony Book, press **V**
and switch the view to **Control** to group and command residents. Weapon rules stay with residents when squads are split or regrouped;
range limits determine how close they approach before firing. Same-colony entities and player members cannot damage
one another. Automatic fire avoids colony entities and blocks. Player-chosen shots skip aim prediction, and
explosions follow vanilla or the installed mod's rules.

The **Colony assistant** saves conversations on this client by world or server and by player.
Open **History** to resume a conversation, or ask the assistant to look up text from previous sessions.
The assistant assumes your model can see images. It photographs nearby views, and checks a drafted build or
facility change from several sides before showing it to you. Turn off **Model supports image input** in
**Settings** for a text-only model.
The assistant groups changes by subject and presents them one at a time in a review window beside the conversation.
The window describes the current group and offers **Locate**, **Confirm** and **Reject**. You can decide on each group
independently. Changes that depend on each other share a group. Until you decide, the change is marked in the world: an outline round the
resident it touches, a line through the points it draws, the drawn blocks of a build, or a facility's old extent
faint beside its new one, with the cells it gains or gives up tinted.
In creative, a build's review window also offers **Build now** or **Have the colony build it**, as placing a blueprint does.
Loaded colony vehicles, such as crewed Superb Warfare vehicles, hitched carts and Create trains, are also
facilities the assistant can send to a destination; select one in **Control** to use its command card.

Ask the **Colony assistant** to designate player-built facilities such as an iron gate operated by a lever.
For a machine, such as a door raised by a valve handle, the assistant reads how it is connected and tries
its controls without moving the machine, so it knows which control to turn and which way. A machine that turns
goods into others can become a workshop: residents bring it what orders need and carry the products back.
Open the facility card from **Facilities**, or select the facility in **Control**. Its card can run routines,
show readings, change parameters or switch scheduled triggers on and off. A card command can also ask for an
area: click two corners in the world. A mobile facility can reposition itself while sweeping a picked box.
The facility area is protected.
Residents can walk through a projected gate while its opening conditions hold. Closed gates block their routes.

For damage control, mark two corners with the book, right-click and choose **Damage control** to
protect the area, and allow residents to work in **Damage control**.
Repairs are off by default: on the area's card, use **Reset baseline** and enable
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

Folkways also works with about thirty other mods, from Create and Create Aeronautics, cooking, firearms and combat mods to
storage networks, accessories and client tools. See [Integrations](INTEGRATIONS.md) for what each one
adds, how to use it in game and the versions it needs.

With Create installed, an **Anonymous Typewriter** links **Control Terminals** to one resident duty seat.

## Building from source

With a **Java 21 JDK** and **Node.js 22.14+**, run the locked setup on **Linux x64** or
**Windows 11 x64** (use `gradlew.bat` there) from the repository root:

```sh
npm ci
npm run setup
./gradlew build
```

Setup downloads the pinned integration dependencies and writes the manifest
required by Gradle. The four mod jars are collected in `build/libs/`.
Windows support in the Blockwright test SDK is a candidate; see
[development setup](CONTRIBUTING.md#development-setup).

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
