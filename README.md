# Folkways

Settlers who plan their own work. Found a colony, mark out fields, pens and fishing
waters, and the settlement schedules the whole day's labour for itself.

**Status: pre-release.** Folkways targets **Minecraft 1.21.1** on **NeoForge 21.1.233+**.
Saves, data pack formats and the Java API may still change between versions.

## What it does

- **A colony is the blocks you mark.** A Colony Book founds the colony. Clicking a chest,
  a bed or a work station with it makes that block part of the colony. Nothing is claimed
  by fencing off land.
- **Residents plan their own day.** Each trade (farm, herd, fish, haul, craft, build,
  patrol, drive, dispatch) is ranked per resident on the Workforce page. You rank trades,
  never tasks. The colony works out what to fetch, from where, in what order.
- **Zones instead of job blocks.** Frame a plot and say what it grows. Frame a pen and say
  how many head to keep. Frame a riverbank as a fishery, or draw a patrol route.
- **Orders keep things stocked.** Name an item and an amount for a chest, and the colony
  crafts, smelts, harvests or hauls until it holds that much.
- **Buildings from drawings.** Frame ground or draw a line, pick a pattern (roads, arch
  bridges, walls, docks, mines, railways, arcades, terraced fields), and builders clear
  the site and lay it from the colony's stores. Patterns are small
  [Starlark](https://github.com/bazelbuild/starlark) scripts. Data packs add their own
  under `data/<namespace>/folkways/pattern/`, and players under `folkways/pattern/` in
  the game directory; the
  [built-in patterns](src/main/resources/data/folkways/folkways/pattern/) are the reference.
- **Residents are people.** They sleep in their own beds, carry a couple of meals, tire
  from walking and working, level up in their trades and draw perks, and run from danger.
  Names and looks come from per-colony pools that data packs can replace.
- **In-game lessons.** Every part of Folkways ships Ponder scenes, playable from the
  colony panel's Lessons tab or by holding the ponder key over a page tab.

## Installation

There is no tagged release yet. The
[Nightly build](https://github.com/IzakyL/folkways/releases/tag/nightly) is rebuilt from
every push to `master`; back up your world before trying it.

Folkways needs these mods installed alongside it:

| Mod | Version |
| --- | --- |
| [NeoForge](https://neoforged.net/) | 21.1.233 or newer |
| [LDLib2](https://modrinth.com/mod/ldlib) | 2.2.37 or newer |

Ponder (the lesson engine) and Flywheel ship inside the Folkways jar and need no separate
install.

### Optional integrations

| Mod | What it adds |
| --- | --- |
| [Create](https://modrinth.com/mod/create) 6.0+ | Hand a schematic to the colony to build; hand over an assembled train for residents to drive; order from a stock keeper's package network |
| [Sable](https://modrinth.com/mod/sable) | Residents path onto and off Sable sub-levels, such as Create Aeronautics ships |
| [Modular Golems](https://modrinth.com/mod/modular-golems) 3.1.43+ | Golems join the roster as a second kind of resident and take trades like people |
| [GeckoLib](https://modrinth.com/mod/geckolib) 4.9+ | Residents can wear bedrock-format models instead of player skins |
| [JEI](https://modrinth.com/mod/jei) / [EMI](https://modrinth.com/mod/emi) | Item filters in the colony panel open the recipe viewer's ingredient list |

## Getting started

1. Craft a **Colony Book** from a book.
2. Right-click to open the colony panel and press **New Colony**. The book becomes the
   colony's deed.
3. Left-click chests, beds and work stations (crafting table, furnace, …) to add them to
   the colony.
4. Stock some food and leave a free bed. Newcomers arrive over time; let them in from the
   Human residents page.
5. Sneak and scroll to turn the book to **marking out**, left-click two opposite corners
   to frame a plot, then right-click and choose **Farm**.

The colony panel's **Lessons** tab walks through everything else.

Server options live in `serverconfig/folkways-server.toml` in each world.

## Building from source

Requirements: Java 21 JDK and Node.js 22.14+. The build reads the optional
integrations' jars from a manifest that `npm run setup` downloads and writes, so run it
once before the first build:

```sh
npm install
npm run setup
./gradlew build
```

The mod jar lands in `build/libs/`. To work on Folkways, see
[CONTRIBUTING.md](CONTRIBUTING.md).

## License

Folkways is released under the [MIT License](LICENSE).

The mod jar bundles [Starlark](https://github.com/bazelbuild/starlark) (Apache-2.0),
[Ponder](https://github.com/Creators-of-Create/Ponder) (MIT) and
[Flywheel](https://github.com/Engine-Room/Flywheel) (MIT). Their license texts ship in
the jar under `META-INF/licenses/`. See [THIRD_PARTY.md](THIRD_PARTY.md) for details.

The Wine Fox resident models under `tests/promo/models/` are **not** covered by the MIT
License. They are licensed CC BY-NC-SA 4.0 by their authors and are used only to film
promotional footage; they are not part of the mod. See
[their README](tests/promo/models/README.md).
