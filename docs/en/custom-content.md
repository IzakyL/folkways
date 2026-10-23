# Custom content and configuration

[Documentation](README.md) · [简体中文](../zh-CN/custom-content.md)

## Content libraries

Load content from a data pack at `data/<namespace>/folkways/<shelf>/`, or from
`<gamedir>/folkways/<shelf>/` on the server. Server folders load as an additional
data pack under the `local` namespace; `/reload` picks up changes.

Run `/folkways library` to inspect each shelf, its entries and source packs, and
any files rejected during loading.

| Shelf            | Files                                                         | Purpose                                                                              |
| ---------------- | ------------------------------------------------------------- | ------------------------------------------------------------------------------------ |
| `pattern`        | `<name>.star`                                                 | [Starlark](https://github.com/bazelbuild/starlark) building patterns                 |
| `piece`          | `<name>.nbt`                                                  | Structures stamped by a pattern's `template(...)` call                               |
| `blueprint`      | `<name>.nbt`                                                  | Whole structures selected on the **Blueprints** page, then aimed, rotated and placed |
| `resident_skin`  | `<name>.png`, optional `<name>.json` with `arms` and `weight` | Resident skins; the server sends textures to players without a resource pack         |
| `resident_model` | `<name>/main.json`, `main.animation.json`, `textures/*.png`   | GeckoLib or unencrypted YSM models, with one look per texture                        |

Use the [built-in patterns](../../mods/vanilla/src/main/resources/data/folkways/folkways/pattern/)
as references. Pattern text and shared library dictionaries follow the
[translation conventions](../../CONTRIBUTING.md#translations-and-patterns).

On a client, `folkways/pattern/`, `folkways/piece/` and `folkways/blueprint/` form
the player's personal library. Drawings are prepared locally and sent to the server.
This personal library is separate from server-provided data pack content.

For Touhou Little Maid pack installation, see the [gameplay guide](gameplay.md#maid-model-packs).

## Resident data

Data packs can tune how residents eat, live and use their perks:

| Data                                                                                                     | Purpose                                                                                                                      |
| -------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| `folkways:resident_food` and its `_vanilla`, `_cookery`, `_farmers_delight` item tags                    | Default pocket rations; see [Rations and food lists](gameplay.md#rations-and-food-lists)                                     |
| `folkways:unsafe_food` item tag                                                                          | Foods refused while unsafe food is refused                                                                                   |
| `folkways:food_groups/*` (meat, fish, stew, grain, vegetable, fruit) and `folkways:fine_meals` item tags | Food variety and fine meals for the food part of mood                                                                        |
| `folkways:husbandry_products` item tag                                                                   | Carcass drops that the Butcher speciality increases                                                                          |
| `folkways:housing/*` block tags                                                                          | Furnishing categories and production stations for room grades; the table tag starts empty                                    |
| `folkways:prospecting_stone`, `folkways:prospectable_minerals` block tags                                | Stone eligible for Prospector and the minerals it can find; the latter includes `c:ores`                                     |
| `data/<recipe namespace>/crafting_perks/<recipe path>.json`                                              | Opts a recipe into Frugal refunds and Byproducts, for example `{"refund": true, "byproduct": "minecraft:stick", "count": 1}` |
| `folkways:gameplay/fishing_discoveries` loot table                                                       | Treasure finds, with the actual fishing origin for biome and dimension conditions                                            |

Container and block-name fallbacks also recognize common storage, carpet,
potted-plant and table blocks. Default crafting perk rules cover selected ordinary
recipes and exclude reversible storage and packing recipes.

Resident skins have dedicated leisure motions. Custom model manifests and look
entries may map `chatting`, `petting`, `relaxing` and `strolling` through a
`leisure_animations` object; missing clips fall back to basic idle and head motions.

## Server configuration

World-specific options live in `<world>/serverconfig/`:

| File                           | Configures                                             |
| ------------------------------ | ------------------------------------------------------ |
| `folkways_core-server.toml`    | Colony engine                                          |
| `folkways_vanilla-server.toml` | Work among vanilla blocks                              |
| `folkways_create-server.toml`  | Rail and dispatch, when Folkways × Create is installed |

Composting preferences and seed reserves are configured per colony in the
**Composting settings** page; see [Composting](gameplay.md#composting).
