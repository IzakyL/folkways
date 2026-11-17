# Data packs, tags and the library folder

English · [简体中文](DATAPACKS.zh-CN.md)

> **Pre-release:** data-pack formats, folder layouts and tag ids may still change
> between versions. Check this page again after you update.

## The library folder

Every server reads `<game>/folkways/` as one more data pack. The pack is called
"Folkways library folder" and is always on. Its files load under the `local`
namespace, so they never clash with ids from other data packs. Each kind of file
goes on its own *shelf*:

| Shelf | Folder | Same thing in a data pack | Contents |
|---|---|---|---|
| `pattern` | `<game>/folkways/pattern/` | `data/<ns>/folkways/pattern/` | Starlark building patterns (`.star`) |
| `piece` | `<game>/folkways/piece/` | `data/<ns>/folkways/piece/` | Structure pieces that patterns stamp (`.nbt`) |
| `blueprint` | `<game>/folkways/blueprint/` | `data/<ns>/folkways/blueprint/` | Whole buildings, placed from the **Blueprints** page (`.nbt`) |
| `resident_model` | `<game>/folkways/resident_model/` | `data/<ns>/folkways/resident_model/` | Bedrock-format resident models |
| `resident_skin` | `<game>/folkways/resident_skin/` | `data/<ns>/folkways/resident_skin/` | Player-layout skins (`.png`) |

- Folders may be nested. A file's id is its path inside the shelf without the
  suffix. For example, `<game>/folkways/blueprint/farm/barn.nbt` becomes
  `local:blueprint/farm/barn`.
- File names may use any characters, including uppercase letters, spaces and
  Chinese. Resource ids only allow `a-z 0-9 _ - .`, so Folkways stores other names
  internally in an encoded form beginning with `__utf8_`. Books and pickers still
  show the original name. Names that are already lowercase are unchanged.
- The shelf folders are created on start. Run `/reload` after changing files.
- `/folkways library` (operators) prints the folder path; click it to open the
  folder. For each shelf it then lists how many entries loaded from each pack,
  and every file that was rejected, with the reason.

### What the player's own folder adds

A player's client reads its own `<game>/folkways/` too, but only for building:

- **Patterns and pieces:** the player's own copies are added to what the server
  offers. If one has the same id as the server's, the player's copy wins.
- **Blueprints:** they appear under **Your folder** on the **Blueprints** page and are
  uploaded when placed. The limit is 2 MiB per file.
- **Resident models and skins come from the server only.** The client keeps what
  the server sent in `<game>/folkways/.cache/resident_models/`. Do not edit that
  cache.

### Shelf rules and limits

**`pattern`** (`.star`)
- A pattern defines `draw(site)`. A file with no `draw` is a library that other
  patterns can `load()`, for example
  `load("folkways_vanilla:materials.star", "NAME")`.
- A pattern may also set `knobs`, `lang`, `accepts` (for example `["zone"]` or
  `["path"]`), `phases`, `capture`, `detail` and `grain`. `detail = "bits"` needs
  the Carving integration.
- Loading is capped at 200,000 Starlark steps. Drawing time is capped by
  `blueprints.drawSeconds` (default 30 s).
- Limits on what the server sends to each player:
  - 131,072 characters per pattern;
  - 256 patterns;
  - 900,000 bytes in total.
- [CONTRIBUTING.md](CONTRIBUTING.md#translations-and-patterns) explains how patterns
  translate their own words.

**`piece`** (`.nbt`)
- Vanilla structure files, compressed or plain, up to 8 MiB each.
- Patterns use them as `template("ns:name", ...)`.
- At most 256 pieces and blueprints combined are sent to players.

**`blueprint`** (`.nbt`)
- Vanilla structure files, placed whole and never stamped by patterns.
- At placement, size is checked against `blueprints.maxBuildCells` and
  `blueprints.maxBlueprintVolume`.
- **Capture Blueprint** saves a selected area under the name you give it. The
  file goes to both `<game>/folkways/blueprint/<name>.nbt` and
  `<game>/folkways/piece/<name>.nbt` on the server. Capture is refused when the
  name is already taken or the file would be larger than 2 MiB.

**`resident_model`** (one folder per model)

```text
resident_model/<model>/
  main.json              Bedrock geometry
  main.animation.json    at least one animation
  textures/*.png         each texture becomes its own look
  folkways.json          optional
```

- `folkways.json` can set:
  - the clip names `idle_animation`, `walk_animation`, `work_animation` and
    `seated_animation`;
  - `leisure_animations`;
  - `weight` (how often the look is drawn, default 1);
  - a `fallback` skin.
- Without `folkways.json`, the default clip names follow Yes Steve Model:
  `idle`, `walk`, `swing_hand`, and `ride` or `sit`.
- The look id is `<ns>:model/<model>/<texture>`.
- Models render through GeckoLib. A client without GeckoLib shows the fallback
  skin instead.

**`resident_skin`** (`.png`, optional `.json`)
- Use the player skin layout: square and a multiple of 64 wide (64, 128, …).
- `<name>.json` beside the png may set `{"arms": "slim", "weight": 2}`. Without
  it, slim or wide arms are detected from the pixels.
- The look id is `<ns>:skin/<name>`.
- Models and skins share one budget: 8 MiB per file and 32 MiB in total.

## Data-pack registries

These are datapack registries. They load when the world loads; `/reload` does
not change them.

| Registry | Folder | What it is |
|---|---|---|
| `folkways_vanilla:resident_look` | `data/<ns>/folkways_vanilla/resident_look/` | One look, either a skin or a model |
| `folkways_vanilla:resident_look_set` | `data/<ns>/folkways_vanilla/resident_look_set/` | A named set a colony can pick on the **Look pool** page |
| `folkways_vanilla:resident_name` | `data/<ns>/folkways_vanilla/resident_name/` | First names and surnames, used on the **Name pool** page |

```json
{"type": "skin", "texture": "mypack:textures/entity/farmer.png", "arms": "slim", "weight": 2}
```

- A look's textures and models are client assets, so a resource pack has to
  ship them.
- A model look also needs `geometry`, `animations`, `texture` and a `fallback`
  skin.
- If a registry look has the same id as a look from the library folder, the
  registry look wins.

```json
{"looks": ["mypack:farmer", "local:skin/farmers/wren"]}
```

```json
{"names": ["Ada", "Bram"], "surnames": ["Holt", "Ward"]}
```

- Names are 1–16 characters each, and `surnames` is optional.
- New colonies draw from every name set merged.
- To replace the built-in names, override `folkways_vanilla:default`.

## Other data files

| File | What it does |
|---|---|
| `data/<recipe ns>/crafting_perks/<recipe path>.json` | Refund or byproduct for one recipe when a resident crafter has the matching perk, e.g. `{"refund": true, "byproduct": "minecraft:wheat_seeds", "count": 1}`. Recipes without a file get nothing. |
| `folkways_vanilla:gameplay/fishing_discoveries` (loot table) | Treasure finds while fishing. The uncommon-fish perk samples `minecraft:gameplay/fishing`, so edits to that table carry through. |
| `folkways_core:colony_book` (recipe) | The colony book recipe. |

## Tags

None of the shipped tags use `"replace": true`, so a data pack can add entries.
Item filters in patterns and colony settings also accept any `#tag`.

**Storage and networks**

| Tag | Kind | Purpose |
|---|---|---|
| `folkways_core:store/allowed` | block | Lets a plain `Container` block enroll as a store |
| `folkways_core:store/refused` | block | Never used as a store; overrides every integration and `store/allowed` |
| `folkways_core:store/candidates` | block | Client hint for blocks that can be enrolled |
| `folkways_core:network_access/take`, `/put`, `/craft` | block | The most an AE2, RS2 or QIO device may do; the **Network devices** page narrows it ([INTEGRATIONS.md](INTEGRATIONS.md)) |

**Residents and movement**

| Tag | Kind | Purpose |
|---|---|---|
| `folkways_core:no_footing` | block | Residents never stand on top of these |
| `folkways_core:climbable` | block | Residents climb these like ladders |

**Food and mood**

| Tag | Kind | Purpose |
|---|---|---|
| `folkways_vanilla:resident_food` | item | Default for the colony's food setting; built from `resident_food_vanilla`, `_cookery` and `_farmers_delight` |
| `folkways_vanilla:unsafe_food` | item | Never eaten |
| `folkways_vanilla:fine_meals` | item | Eating one lifts mood |
| `folkways_vanilla:food_groups/{meat,fish,stew,grain,vegetable,fruit}` | item | Meal variety for mood; untagged food counts as "other" |

**Housing and dining**

| Tag | Kind | Purpose |
|---|---|---|
| `folkways_vanilla:housing/{storage,textile,decoration,table,lighting}` | block | Furnishings that grade a room; names are also guessed |
| `folkways_vanilla:housing/production` | block | Workstations; a room holding one counts as mixed |
| `folkways_vanilla:dining/tables` | block | Dining tables |
| `folkways_vanilla:dining/chairs` | block | Seats; bottom-half stairs always count |

**Farming, foraging and groundwork**

| Tag | Kind | Purpose |
|---|---|---|
| `folkways_vanilla:weeds` | block | Cleared when a field clears grass |
| `folkways_vanilla:flowers` | block | Cleared when a field clears flowers |
| `folkways_vanilla:tillable_under_weeds` | block | Ground that can be tilled once weeds are gone |
| `folkways_vanilla:forage` | block | Plants foragers clear and harvest |
| `folkways_vanilla:fallen_logs` | block | Fallen logs, in addition to `#minecraft:logs` |
| `folkways_vanilla:fallen_log_decorations` | block | Cleared around fallen logs |
| `folkways_vanilla:husbandry_products` | item | Extra drops from culling (butcher perk) |

**Combat**

| Tag | Kind | Purpose |
|---|---|---|
| `folkways_vanilla:resident_enemies` | entity type | Mobs residents fight; includes `undead_resident_enemies` |
| `folkways_vanilla:patrol_targets` | entity type | Non-hostile mobs patrols treat as threats |
| `folkways_vanilla:ignored_resident_enemies` | entity type | Never fought; overrides both tags above |
| `folkways_vanilla:luminous_targets` | entity type | Spotted as if glowing |

**Building and repair**

| Tag | Kind | Purpose |
|---|---|---|
| `folkways_vanilla:prospectable_minerals` | block | Ores the prospecting perk may find |
| `folkways_vanilla:prospecting_stone` | block | Stone that counts for prospecting |
| `folkways_vanilla:damage_control_excluded` | block | Never recorded for repair |

**Extension mods**

| Tag | Kind | Purpose |
|---|---|---|
| `folkways_cookery:cookery_sickles` | item | Sickles for the Cookery harvest mode |

Folkways also reads `c:foods`, `c:tools` and `c:tools/melee_weapon`.

## Config files

Server settings live in each world's `serverconfig/`:

- `folkways_core-server.toml`
- `folkways_vanilla-server.toml`
- `folkways_create-server.toml`
- `folkways_firearms-server.toml`
- `folkways_carving-server.toml`

Client settings live in `config/`: `folkways_core-client.toml`,
`folkways-windows.properties` and `folkways-drawing-settings.properties`.
