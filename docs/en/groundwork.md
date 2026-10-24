# Groundwork: forage and fallen logs

[Documentation](README.md) · [简体中文](../zh-CN/groundwork.md)

## Gathering wild food

Enable **Forage** and turn **Clear** off to have farmers gather marked food plants
into colony storage. Forage defaults off. Sweet berry bushes at age two or three
use the vanilla empty-hand interaction and reset to age one; cave-vine heads and
bodies use vanilla berry picking and keep their vine block. Unripe plants stay.
These are one-time groundwork targets, not a recurring farm: the zone finishes
after its discovered work, without waiting for unripe plants or later regrowth.

**Clear takes priority** when enabled, removing the plant through the existing
clearing path. Weeding does not harvest berries; when Forage is enabled it leaves
renewable forage alone. Other enabled work, such as felling a supporting tree or
filling a plant's ground, still has its ordinary effect. Colony-registered blocks
and the selected fill material are protected, with a second check at commit.
Unregistered player plants cannot be distinguished from naturally generated ones.

Small brown/red mushrooms, pumpkins and melons are picked whole, using normal
block loot and tools, without planting them back. Carved pumpkins, mushroom trees,
ordinary vanilla crop fields and tree leaves are not included. A marked lower half
of a tagged two-block wild plant claims its matching upper half too; loot is rolled
once at the lower half before removing its support. Protected upper halves prevent
picking. Any immediate neighbor-update drops are captured with the harvest.

Data packs select plants through `#folkways:forage_pick` (one-off plants) and
`#folkways:forage_harvest` (renewable plants). Tagged `CropBlock` plants in Pick
must be fully grown. Harvest requires a known lifecycle: the two vanilla berry
rules or an owning integration's registered `CropBehavior.ripe/gather`. An unknown
Harvest entry is skipped rather than broken. Three pack slots are reserved for
food and propagation items. All mutations stay in `Node.commit`; native spawned
drops enter `Outcome.Done` through the existing `ItemDrops` capture.

### Checked mods and block IDs

Checked on 2026-10-24 using the published NeoForge 1.21.1 jars and their loot and
block definitions. Optional block IDs are `required: false`; no extra dependency
or test profile was added. Only Cookery is actually loaded in the optional test
run; the other mods were inspected, with generic picking covered by a test-only
wild plant that drops both food and a seed.

| Mod and checked version                                                                                                  | Covered plants and behavior                                                                                                                                                                                                                                                                                                                                                                                                                     |
| ------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [Farmer's Delight 1.3.4](https://modrinth.com/mod/farmers-delight/version/1.21.1-1.3.4)                                  | Pick `farmersdelight:wild_cabbages`, `wild_onions`, `wild_tomatoes`, `wild_carrots`, `wild_potatoes`, `wild_beetroots`, `wild_rice`. Wild rice has two halves and retains its water.                                                                                                                                                                                                                                                            |
| [Kaleidoscope Cookery 1.6.0](https://modrinth.com/mod/kaleidoscope-cookery/version/1.6.0-neoforge%2Bmc1.21.1)            | Its Folkways integration opts `kaleidoscope_cookery:tea_tree`, `tomato_crop`, `chili_crop` into Harvest, reusing its existing crop behaviors and resetting to ages three/five/five. These are ordinary crop blocks; this version has no separate wild crop blocks. `kaleidoscope_cookery:wild_rice` is a seed item obtained from grass, already handled by the integration's seed-gathering area. Cultivated rice and lettuce remain farm work. |
| [Pam's HarvestCraft 2 Crops 1.0.9](https://www.curseforge.com/minecraft/mc-mods/pams-harvestcraft-2-crops/files/8064883) | Pick `pamhc2crops:aridgarden`, `frostgarden`, `shadedgarden`, `soggygarden`, `tropicalgarden`, `windygarden`. The published Modrinth 1.0.0 jar was also checked; its six IDs agree. Cultivated `pam...crop` blocks are not opted in.                                                                                                                                                                                                            |
| [Supplementaries 4.0.2](https://modrinth.com/mod/supplementaries/version/rWq0ko29)                                       | Pick `supplementaries:wild_flax` for its normal seeds. This includes a useful propagation plant even though flax itself is not food. Cultivated `supplementaries:flax` is not opted in.                                                                                                                                                                                                                                                         |
| [Croptopia 4.2.4](https://www.curseforge.com/minecraft/mc-mods/croptopia/files/7958876)                                  | NeoForge 1.21.1 is available. Examples are `croptopia:blueberry_crop`, `blackberry_crop`, `corn_crop`; `#croptopia:crops` also includes tree fruit such as `apple_crop`. Not opted in: native picking changes player stats/exhaustion, resets crops and drops loot; fruit leaves have another lifecycle. Supporting it needs its own integration, rather than breaking renewable crops or guessing resets.                                      |

Saved sites without the new configuration field migrate to `forage=false`, keeping
their previous target sets. Turning Forage on deliberately rediscovers the zone,
like any settings change. No other persisted format changes are made.

## Fallen logs

A groundwork zone's **Fell trees** setting also clears fallen wood. Farmers take
horizontal blocks in `#minecraft:logs` or `#folkways:fallen_logs` when they rest on
solid ground and their connected wood has no vertical logs or neighboring leaves.
Only marked logs are removed. Moss carpet, vines, mushrooms and ferns immediately
above or beside each log come away first, including just outside the mark, so their
normal loot reaches colony storage instead of dropping when its support disappears.
Tools use the same `SuitableFor` path as clearing.

The check follows face, edge and corner joins through at most 64 logs. Every log
must be horizontal and supported; an oversized body or an unloaded neighbor is
left alone. It respects colony-registered blocks, their supporting building blocks,
and the zone's chosen fill block. Protection is checked again when work executes.
There is no placement-history tracking: unregistered player wood resting directly
on terrain can look like fallen wood. Elevated beams and vertical posts are not
fallen wood. A fallen log touching a tree is conservatively left to tree felling.

**Clear** already removes every breakable block inside its box, including logs,
while preserving colony-registered blocks, liquids, unbreakable blocks and the
chosen fill material. This behavior is unchanged; fallen wood's decorations are
now collected before its support is removed. Previously, **Fell trees** rejected
wood without a leaf canopy; attached vines could also be mistaken for that canopy.
A living tree still follows the existing whole-tree felling path.

Data packs can extend `data/folkways/tags/block/fallen_logs.json` with log-like
blocks exposing the `axis` property, and
`data/folkways/tags/block/fallen_log_decorations.json` with small attached plants.
Quark hollow logs are optional `required: false` entries. No Quark classes, jars,
new integration module or save-format changes are required. Existing recorded
work targets keep their discovery-once behavior; redraw an old zone to discover
new fallen-wood targets.

## Quark research

Verified on 2026-10-24 against Modrinth's NeoForge 1.21.1 listings:
[Quark 4.1-487](https://modrinth.com/mod/quark/version/4.1-487), published
2026-10-05, requires [Zeta](https://modrinth.com/mod/zeta/version/1.1-40), whose
latest matching release is 1.1-40. This is source verification; the test environment
does not install either mod, so `blockwright.toml` has no new profile or pins.

Quark's pinned
[FallenLogGenerator](https://github.com/VazkiiMods/Quark/blob/release-4.1-487%2B1.21.1/src/main/java/org/violetmoon/quark/content/world/gen/FallenLogGenerator.java)
generates straight, three- or four-block rows with `axis=x` or `axis=z`. Its
[default configuration](https://github.com/VazkiiMods/Quark/blob/release-4.1-487%2B1.21.1/src/main/java/org/violetmoon/quark/content/world/module/FallenLogsModule.java)
uses oak, spruce, birch, jungle, acacia, dark oak, mangrove and cherry vanilla
logs. With Hollow Logs enabled, each row has a default 70% chance of using the
matching `quark:hollow_<wood>_log` instead. Top decoration is moss carpet;
climate-dependent side/end decorations are moss carpet, vines and ferns. This
generator does not place mushrooms. Water-supported rows are possible; this
implementation deliberately requires solid ground.

The optional tag also includes Quark's hollow crimson and warped stems
(`quark:hollow_crimson_stem`, `quark:hollow_warped_stem`) for data packs that use
them as debris. [HollowLogBlock](https://github.com/VazkiiMods/Quark/blob/release-4.1-487%2B1.21.1/src/main/java/org/violetmoon/quark/content/building/block/HollowLogBlock.java)
derives IDs from the source log or stem and keeps the pillar axis.

Java Edition's own fallen trees arrived in
[1.21.5](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-5),
so they are absent from vanilla 1.21.1. Other mods or data packs can generate
similar debris; recognition uses tags and block states rather than generator IDs.

## Regression coverage

Vanilla GameTests cover ordinary oak plus moss reaching an actual colony barrel,
Clear collecting moss outside the box, and a tag-only pillar using the same
planning and execution pipeline. The tag-only fixture adds `minecraft:hay_block`
to `#folkways:fallen_logs` in test resources only; it is not shipped as content.
Other tests cover standing trees, diagonal connections, protected blocks,
unsupported beams, changes before commit, decorations, marked boundaries and the
connectivity budget. Forage tests additionally cover vanilla ripeness/reset, both
cave-vine blocks, disabled settings, Clear priority, two-block plants, protection,
replacement, old saved configurations, and actual food/seed storage.
`./gradlew -PcookeryTests=true runGameTestServer` also verifies Cookery regrowth. Run `./gradlew runGameTestServer` for the combined suite.
