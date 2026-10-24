# Resources

[Documentation](README.md) · [简体中文](../zh-CN/resources.md)

Install `folkways_resources` alongside Core and Vanilla to enable flat resources.
The extension adds four stackable items in the `folkways` namespace. Existing
blocks and items remain available.

| Resource         | Default recipe input (one item)                                                                                | Replaces at work (one resource per item)      |
| ---------------- | -------------------------------------------------------------------------------------------------------------- | --------------------------------------------- |
| Wood Resource    | Any log                                                                                                        | Logs, planks and tagged wooden building parts |
| Stone Resource   | Base stone, cobblestone, andesite, diorite, granite, deepslate, cobbled deepslate, blackstone, tuff or calcite | Tagged stone and stone building parts         |
| Sapling Resource | Any tagged sapling                                                                                             | Any tagged sapling                            |
| Seed Resource    | Tagged seeds                                                                                                   | Tagged seeds, carrots and potatoes            |

Residents haul the resource itself to a building site, fill zone or field and
consume it there. A blueprint still places its exact resolved blocks; a field
still plants its selected crop or sapling. This includes Create's schematicannon
placement adapter and `CropBehavior` planting through a `BlockItem`. There is no
workshop conversion into the requested item and no resource-to-material recipe.

Matching real materials in packs or stores are reserved first, then resources
already in packs or stores, then ordinary production of the original input.
Within each of these groups, existing hauling and source ranking still applies.
Resources can be crafted manually or produced by the existing wares workshops
and stock orders using the ordinary recipes. JEI and EMI show those recipes.

The Colony Book's **Resources** page displays physical resource stock in the
current dimension's colony stores, each category's substitution members and its
cost. Stock is never multiplied into separate totals for each possible variant.
Seed Resource has no food component, compost value, seed tag or crop-specific
`SeedReserves` group.

## Data packs

Override `data/folkways/resource_categories/wood.json` to change or disable a
category:

```json
{
  "resource": "folkways:wood_resource",
  "members": "folkways:flattening/wood/targets",
  "default_item": "minecraft:oak_planks",
  "cost": 1,
  "enabled": true
}
```

`members` is an item tag ID, without `#`. `cost` is a whole number from 1 through
64: resource items paid for one requested item. The default is one. Concrete
requests retain their own variant. For a broad tag or alternative input, the
category's `default_item` is used when it satisfies the request; otherwise a
matching representative is selected. Substitutions only cover default component
identities: a named, filled or otherwise customized target must be supplied as
that actual item.

Extend `tags/item/flattening/<category>/targets.json` for substitution membership
and `sources.json` for recipe membership. The built-in target tags refer to the
Minecraft log, plank, wooden-building-part and sapling tags and the common stone
and seed tags, so compatible mod content joins through those tags. Additional
stone parts can be added explicitly. Override the ordinary recipes under
`recipe/flattening/` to change recipe yields or losses. New category files may
refer to any already registered payment item; registering additional item IDs
still requires a mod.

Wood recipes accept logs only. Planks and crafted wood parts are substitution
targets but are not default recipe inputs. Stone recipes similarly exclude
stairs, slabs and walls. Thus placing and recovering a log costs and returns one
source, while crafting it into several planks cannot multiply resources. The
resource recipes have no crafting-perk refund or byproduct policy. A data pack
that broadens sources or adds such rewards must balance its own loops.

Ordinary wares recipe inputs deliberately retain their real ingredients. Letting
a resource act as a log in a plank recipe would turn one resource into several
planks and reopen resource multiplication. Equipment, rations, deliveries,
compost and stock order fulfillment also keep physical item identity.

## API and saves

Core owns the generic `InputSubstitutions`, `InputAllocation` and `InputReceipt`
mechanism. A consumer explicitly opts in with `Need.replacing()`. `Goods` and
ordinary stock matching retain item and component identity. Weaver reserves and
hauls the real payment at the declared cost. Commit exposes a temporary requested
input to the existing consumer and records both requested and paid items in
`Outcome.Done.inputs()`. Unused units, failure and exceptions return the real
payment; canceling hauled work uses the existing cargo recovery path. A single
physical resource cannot pay several competing variants at once.

Reload replaces immutable category rules, replans untouched work and checks
current rules again before spending. An invalidated payment cannot be consumed.
The extension adds no saved settings or persisted work format; old colonies,
blueprints and fields load without a migration. Without the extension installed,
no resource rules or items are registered and existing work uses real materials.

The four 16 × 16 transparent PNGs are in
`mods/resources/src/main/resources/assets/folkways/textures/item/`. They were
generated with the built-in imagegen tool and exported at inventory resolution
with nearest-neighbor sampling. Their prompts are retained in
`mods/resources/art/prompts.json`.

## Validation

All Gradle invocations use `--no-daemon` on shared development machines:

```sh
./gradlew --no-daemon build
./gradlew --no-daemon runGameTestServer
./gradlew --no-daemon -PresourcesTests=true runGameTestServer
./gradlew --no-daemon -PresourcesTests=true -PgameTestMod=resources runGameTestServer
npm run test:i18n
```

The `resources` Blockwright profile installs the optional extension with the
base profile's pinned fixtures. Consumer tests live in Vanilla's
`src/resourcesTest`, so package-private building, planting and fill scenarios
remain in their owning Java module. The resource module owns recipe, identity
and cargo-recovery tests; Core owns generic allocation and reservation tests.
