# Groundwork and fallen logs

[Documentation](README.md) · [简体中文](../zh-CN/groundwork.md)

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
connectivity budget. Run `./gradlew runGameTestServer` for the combined suite.
