# Folkways

Settlers who plan their own work. Found a colony, mark out fields, pens and fishing
waters, and the settlement schedules the whole day's labour for itself.

**Status: pre-release.** Folkways targets **Minecraft 1.21.1** on **NeoForge 21.1.233+**.
Saves, data pack formats and the Java API may still change between versions.

## What it does

- **A colony is the blocks you mark.** A Colony Book founds the colony. Clicking a chest,
  a bed or a work station with it makes that block part of the colony. Nothing is claimed
  by fencing off land.
- **Colonies belong to players, not books.** Whoever founds a colony keeps it, invites other
  players from its Members page (they join once they accept), and alone can end it. One
  player can keep several colonies and switch between them from the panel's title. Any
  Colony Book opens the colony you are acting for, so a lost or stolen book loses nothing.
- **Residents plan their own day.** Each trade (farm, herd, fish, haul, craft, build,
  patrol, drive, dispatch) is ranked per resident on the Workforce page. You rank trades,
  never tasks. The colony works out what to fetch, from where, in what order.
- **Zones instead of job blocks.** Frame a plot and say what it grows. Frame a pen and say
  how many head to keep. Frame a riverbank as a fishery, or draw a patrol route.
- **Orders keep things stocked.** Name an item and an amount for a chest, and the colony
  crafts, smelts, harvests or hauls until it holds that much.
- **Containers have their own rules.** Choose whether residents may take goods or use a
  container for ordinary storage. Orders still deliver to containers that refuse ordinary
  storage.
  Blacklists and whitelists use the shared item picker, including item, tag and search
  rules. Container rules apply to residents; players and external automation keep their
  normal access.
- **Buildings from drawings.** Frame ground or draw a line, pick a pattern (roads, arch
  bridges, walls, docks, mines, railways, arcades, terraced fields), and builders clear
  the site and lay it from the colony's stores. Patterns are small
  [Starlark](https://github.com/bazelbuild/starlark) scripts. Data packs add their own
  under `data/<namespace>/folkways/pattern/`, and players under `folkways/pattern/` in
  the game directory; the
  [built-in patterns](mods/vanilla/src/main/resources/data/folkways/folkways/pattern/) are the reference.
- **Residents are people.** They sleep in their own beds, carry a couple of meals, tire
  from walking and working, level up in their trades and draw perks, and run from danger.
  Names and looks come from per-colony pools that data packs can replace.
- **In-game lessons.** Every part of Folkways ships Ponder scenes, playable from the
  colony panel's Lessons tab or by holding the ponder key over a page tab.

### Adding your own content

Everything below loads from data packs at `data/<namespace>/folkways/<shelf>/`, or from
the `folkways/<shelf>/` folder in the server's game directory. The folder loads as one
more data pack under the `local` namespace, and `/reload` picks up changes to it. Run
`/folkways library` to see what each shelf holds, which pack each entry came from, and
which files were turned away and why.

| Shelf | Files | What it adds |
| --- | --- | --- |
| `pattern` | `<name>.star` | A building pattern |
| `piece` | `<name>.nbt` | A structure file that patterns stamp with `template(...)` |
| `blueprint` | `<name>.nbt` | A structure file set down whole: pick it on the colony panel's Blueprints page, then aim, turn and click to place it |
| `resident_skin` | `<name>.png`, optional `<name>.json` with `arms` and `weight` | A resident look. The server sends the texture to its players, so they need no resource pack |
| `resident_model` | `<name>/main.json`, `main.animation.json`, `textures/*.png` | A GeckoLib or unencrypted YSM model, one look per texture |

On a client, `folkways/pattern/`, `piece/` and `blueprint/` also make up the player's
own library. The player draws these on their side and sends the result to the server.

## Installation

There is no tagged release yet. The
[Nightly build](https://github.com/IzakyL/folkways/releases/tag/nightly) is rebuilt from
every push to `master`; back up your world before trying it.

Folkways has four base jars and a Cookery extension. Install the first two; add the others only with the mod they pair with:

| Jar | When |
| --- | --- |
| Folkways Core (`folkways-core-*.jar`) | Always: the colony engine, the Colony Book and its panel |
| Folkways Vanilla (`folkways-vanilla-*.jar`) | Always: the work residents do (farming, herding, fishing, hauling, crafting, building, patrol) |
| Folkways × Create (`folkways-create-*.jar`) | With Create: trains, parcels, track and schematics |
| Folkways × Modular Golems (`folkways-modulargolems-*.jar`) | With Modular Golems: golems as residents |
| Folkways × Kaleidoscope Cookery (`folkways-cookery-*.jar`) | With Kaleidoscope Cookery 1.6.0: crops, appliances and shared kitchen tools |

The Cookery extension supplies native appliance production, flooded-rice farming, shared tools,
kitchen racks, seed reserves and thirteen lessons. The creative supply box belongs to
Folkways Vanilla and also works without Cookery.

All installed Folkways jars must be the same version. Folkways also needs these mods installed alongside it:

| Mod | Version |
| --- | --- |
| [NeoForge](https://neoforged.net/) | 21.1.233 or newer |
| [LDLib2](https://modrinth.com/mod/ldlib) | 2.2.37 or newer |

Ponder (the lesson engine) and Flywheel ship inside the Folkways Core jar, and the
Starlark interpreter inside the Folkways Vanilla jar; none needs a separate install.

### Optional integrations

| Mod | What it adds |
| --- | --- |
| [Create](https://modrinth.com/mod/create) 6.0+, with Folkways × Create | Hand a schematic to the colony to build; hand over an assembled train for residents to drive; order from a stock keeper's package network. Without Folkways × Create, residents still walk round Create's contraptions |
| [Sable](https://modrinth.com/mod/sable) | Residents path onto and off Sable sub-levels, such as Create Aeronautics ships |
| [Modular Golems](https://modrinth.com/mod/modular-golems) 3.1.43+, with Folkways × Modular Golems | Golems join the roster as a second kind of resident and take trades like people |
| [Kaleidoscope Cookery](https://github.com/KaleidoscopeMods/KaleidoscopeCookery) 1.6.0, with Folkways × Kaleidoscope Cookery | Residents grow crops, prepare ingredients and cook with native appliances, borrowing and returning shared tools |
| [GeckoLib](https://modrinth.com/mod/geckolib) 4.9+ | Residents can wear bedrock-format models instead of player skins |
| [Touhou Little Maid](https://modrinth.com/mod/touhou-little-maid) 1.5+, with GeckoLib | Its built-in and installed add-on maid models become looks a colony can switch on, one by one or a whole pack at once. Each client reads its own installed packs; pack assets are not sent over the network |
| [Shoulder Surfing Reloaded](https://modrinth.com/mod/shoulder-surfing-reloaded) 5.2.0+ | The Colony Book aims along its third-person crosshair |
| [JEI](https://modrinth.com/mod/jei) / [EMI](https://modrinth.com/mod/emi) | Item filters in the colony panel open the recipe viewer's ingredient list |
| [FTB Teams](https://www.curseforge.com/minecraft/mc-mods/ftb-teams-forge) 2101.1+ | A colony's founder can hand it to their party: from then on the party's members keep the colony and its owner holds the founder's rights |

Touhou Little Maid add-ons load from ZIP files or pack folders directly under
`<gamedir>/tlm_custom_pack/`; ZIP files do not need extracting. Install the same packs
on the server and each client. After changing packs, run `/reload` on the server and
reload client resources with F3+T, or restart the game. Files in
`config/touhou_little_maid/file/` are download caches and do not count as installed packs.
Model looks still require GeckoLib; without it they are disabled with a dependency tooltip.

## Getting started

1. Craft a **Colony Book** from a book.
2. Right-click to open the colony panel and press **New Colony**. You are the new
   colony's founder; click the panel's title to switch colonies or found another.
3. Left-click chests, beds and work stations (crafting table, furnace, …) to add them to
   the colony.
4. Stock some food and leave a free bed. Newcomers arrive over time; let them in from the
   Human residents page.
5. Sneak and scroll to turn the book to **marking out**, left-click two opposite corners
   to frame a plot, then right-click and choose **Farm**.

The colony panel's **Lessons** tab walks through everything else.

Server options live in each world's `serverconfig/`: `folkways_core-server.toml` for the engine, `folkways_vanilla-server.toml` for the work among vanilla blocks, and `folkways_create-server.toml` for rail and dispatch.

## Building from source

Requirements: Java 21 JDK and Node.js 22.14+. The build reads the optional
integrations' jars from a manifest that `npm run setup` downloads and writes, so run it
once before the first build:

```sh
npm install
npm run setup
./gradlew build
```

The five mod jars land in `build/libs/`. To work on Folkways, see
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
