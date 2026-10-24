# Colony raids

[Architecture](architecture.md) · [简体中文](../zh-CN/raids.md)

Install the optional `folkways_war` jar beside Core and Vanilla. War adds colony raids; Vanilla still owns hostility, guards, formations, commands and finite weapon supplies. No third-party combat mod is required.

## Playing

Raids are enabled for every colony, including existing saves. Installing War starts a full seven-day grace period for each colony. Only simulated time with at least four residents and a nearby online keeper counts. After a raid, eligibility returns in five to eight simulated days. Automatic warnings start at dusk, followed by a two-minute warning. Peaceful difficulty, `disableRaids`, `doMobSpawning=false` and disabled dimensions prevent starts. The End is disabled by default.

The colony book's **Raids** page shows the countdown, approach direction, wave, remaining enemies, latest damage and loot report, and the colony opt-out button. Each reading and change checks current keeper authority. The server boss bar and chat warnings follow online keepers; reconnecting restores the current bar. Use Vanilla's Combat page to deploy guards. War never replaces player orders or creates another resident controller.

The default three waves use zombies, skeletons, pillagers, vindicators, creepers and a ravager. Witch entries are also supported. Resident count, simulated age and Hard difficulty increase counts, with a default strength cap of three. War restricts resident acquisition to the attacked colony while native AI executes combat. Village raids cannot recruit these members.

Spawn searches stay outside the colony's nearby sites, reject close players, other colonies, fluids, unsafe footing and unloaded simulation chunks, and require a route to the outer approach. Routes and distant outposts do not enlarge the attack area. No valid approach delays the raid. Frame-based colonies, including ships, are deferred; this version does not establish a verified boarding route, spawn on decks, or navigate to ship storage coordinates.

Raids pause timers and member AI when no nearby keeper is online. Missing UUIDs remain unresolved, rather than counting as kills or causing replacement spawns. Clearing all waves wins. Losing every resident or holding an undefended registered store for ten seconds begins plunder. An ordinary raid times out after twenty simulated minutes and withdraws. Ending or relocating a colony cancels its raid; server shutdown preserves it.

## Buildings, fire and plunder

Breaching and explosions share a default cap of 96 blocks per raid. Breachers work through ordinary stone, brick and deepslate with a default hardness cap of five. Direct breaches exclude block entities. Obsidian, unbreakable blocks and the `folkways:raid_protected` block tag are protected; TNT, beds and respawn anchors are included to prevent secondary damage. Creepers retain native explosions and container drops, filtered through the raid budget. Ravager leaf destruction and farmland trampling use the same budget. Both halves of doors require authorization and budget. Native door-breaking and egg-removal goals cannot bypass it. Raider projectile block reactions also use the permission and budget checks, preventing burning arrows from igniting protected TNT.

Arsonists create real `Blocks.FIRE` with a hand swing and the flint-and-steel sound. Defaults allow twelve ignition sources per wave, three per arsonist, 192 spread cells and 96 burned blocks per raid. These are separate caps. A narrow fire mixin runs the original fire algorithm and checks attributed spread and burning before changing each destination, including before `onCaughtFire`. Fire provenance and remaining budgets survive the attackers, the raid, colony deletion and server restart. Extinguishing a cell removes its provenance immediately. Unattributed player fire keeps its usual behaviour.

Every destructive path checks `mobGriefing` and NeoForge events through `WarEdits`; fire also checks `doFireTick`. Disabling either relevant gamerule stops further attributed fire damage. Break events use a dedicated anonymous raid identity, and entity-destruction and placement events retain the raider source. There is no FTB Chunks bridge on this base. A future bridge belongs in this permission entry point, including attributed fire. Damage control needs no War API: fire and missing blocks are ordinary world state for scanning.

Defeat creates a finite plunder manifest without removing inventory. Raiders walk to registered stores, spend time looting within reach and sight, carry the actual removed stacks, and flee. The default share is twenty percent of each selected stack, rounded down with a minimum of one item, capped at eight stacks and 256 items per raid and two stacks per carrier. The `raid_precious` and `raid_valuable` item tags prioritize valuable goods. Item components survive extraction, carrying, restart and death drops. Killing a looter drops its cargo for recovery. The report distinguishes taken goods from goods dropped for recovery. Escaped cargo remains lost; admin cancellation drops loaded cargo.

Withdrawal rules, slot restrictions and vanilla locks are rechecked at extraction. Both halves of a double chest must be unlocked. Only registered `BaseContainerBlockEntity` stores in dimension realms are eligible: machine inventories with unknown security contracts are deliberately excluded. Plunder has a one-minute limit, does not repeatedly take the same manifest entry, and does not delete colonies or grant extra victory rewards.

## Configuration and data packs

Server settings live in `folkways_war-server.toml`. They include the master switch, grace, resident threshold, intervals, warning and timeout, disabled dimensions, concurrent raid/member caps, strength, damage, ignition, spread, burning, store hold time and plunder limits. Colony opt-outs persist separately. Server caps always take precedence over a definition's caps.

Definitions use `data/<namespace>/raid/*.json`, including `data/folkways/raid/siege.json`. `schemaVersion` is `1`. Each `waves` entry supplies `delayTicks` and `entries`; an entry contains `entity`, `role` (`fighter`, `breacher`, `arsonist`), `count`, and optional equipment slot-to-item IDs. The supported entity pool excludes bosses and evokers: summoned attackers need their own attribution before inclusion. Unknown entities, roles, equipment, versions and out-of-range counts are rejected during reload.

The optional `limits` object accepts `brokenBlocks`, `hardness`, `fireSourcesPerWave`, `fireSourcesPerRaider`, `fireSpreadBlocks`, `burnedBlocks`, `plunderShare`, `plunderStacks`, `plunderItems` and `plunderStacksPerRaider`. Omitted limits use the shipped defaults. Active raids save the parsed, scaled definition snapshot, so `/reload` only affects later raids. Equipment uses real item IDs, not a separate weapon implementation.

Administrators with permission level two can use:

```text
/folkways raids start <colony-uuid> [definition-id]
/folkways raids stop <colony-uuid>
/folkways raids status <colony-uuid>
```

Starts still validate gamerules, limits and outside spawn positions. Without a definition ID, the first definition by ID is used. There are no player-triggered cooldown bypasses.

`colony.keep(folkways:raids)` stores schema version one, opt-out, simulated age and cooldown, raid identity, target `WorldPos`, seed, wave slots and entity UUIDs, damage counters, store hold progress and plunder manifest. Missing plugin data explicitly initializes enabled raids with the full grace period. Additive counters and manifests default to empty/zero when loading earlier stage-one saves. Fire data uses a separate version-one `folkways_raid_fires` SavedData per dimension so closing a colony cannot erase protection of its remaining fires. Server shutdown preserves both ledgers; UUID re-association never fills a missing slot by spawning a replacement.

## Validation

```sh
./gradlew build
./gradlew runGameTestServer
./gradlew -PwarTests=true -PgameTestMod=war runGameTestServer
./gradlew -PwarTests=true runGameTestServer
npm run test:i18n
```

War tests explicitly advance colony simulation, use fixed raid seeds and isolate gamerule changes. They exercise real native damage, the existing guard runner, wave progression, missing/reloaded members, physical breach, capped explosions, fire provenance and budgets, actual plunder and death drops, store locks, foreign-colony targeting and keeper revocation. Client rendering, actual process restarts, FTB protection, boarding and third-party raider pools are outside this GameTest coverage.
