# Save compatibility

English · [简体中文](SAVE_COMPATIBILITY.zh-CN.md)

## Versions

All modules release together as `0.MINOR.PATCH`, starting at `v0.1.0`. PATCH is a small update
(fixes and minor changes); MINOR is a large update (new features or gameplay). Save formats are
tracked separately by `DataVersion` and never decide the version number. There is no alpha/beta/rc
ladder and no extension API stability before 1.0.0. Clients, servers and the Folkways jars on each
side must all be the same build.

`DataVersion.BASELINE = 1` and `DataVersion.CURRENT = 4` bound the single, increasing
Folkways save version shared by every module.
Version 1 is the development format of master `9a45a4d52`, version 2 is `v0.1.0`, and version 3
gives each module its own namespace. Only format changes increment it.

## Compatibility promise

- Every release opens saves from every release since `v0.1.0` and migrates them forward.
  Master `9a45a4d52` is the one supported pre-release bridge; older development worlds must be
  opened and saved with that build first. Other nightlies have no promise.
- Downgrades are unsupported. Release notes say when the data version changes.
- Keep every data-owning module installed while upgrading. Code from a missing module cannot
  migrate its data.
- Migration rewrites world NBT only. Commands, datapacks, resource packs, configuration files,
  Starlark patterns, resident-model packs and NBT passed to commands must use current ids.
  Exported blueprints are the exception: they are normalized when imported.

## Data version 3: module namespaces

Each module now has its own mod id: `folkways_core`, `folkways_vanilla`, `folkways_create`,
`folkways_cookery`, `folkways_firearms`, `folkways_carts`, `folkways_carving`, `folkways_golems`
and `folkways_epicfight`. Worlds, items, blocks and inventories migrate automatically.

- **Carving replaces the Chisels & Bits extension. Remove `folkways-chiselsandbits-*.jar` when
  installing `folkways-carving-*.jar`; loading both crashes the game.**
- Server configs are renamed on load: `folkways-tacz-server.toml` → `folkways_firearms-server.toml`,
  `folkways_chiselsandbits-server.toml` → `folkways_carving-server.toml`. If both exist, the new one
  wins and the old one is left in place.
- Old ids in Vanilla's refused-recipe list and TACZ's gun/range settings still work and log a
  warning once; the TOML file itself is not rewritten.

## Data version 4: storage, network devices and equipment profiles

Version 4 adds the optional `folkways_core:store_verdicts` keeps bag for lasting store verdicts.
Its `verdicts` list contains WorldPos records with a serialized Component `reason`; an absent bag means no
verdicts. No existing storage fields need rewriting, so no empty migration step is registered for this bag and it is
not initialized during upgrades. Registering the block again clears its verdict. Positions follow the existing
keeps relocation path. Version 4 saves cannot be downgraded.

Unadapted modded containers must implement vanilla `Container` and have their block in
`folkways_core:store/allowed` to enroll; `folkways_core:store/refused` overrides even explicit integrations. Existing
claims are retained, including Create vaults and Lootr containers. They show an unusable verdict and their goods
are excluded from resident planning; no inventory or enrollment is deleted. Stores enrolled solely through the
removed generic item-handler fallback remain enrolled and show the existing unsupported verdict.

Version 4 also permits the optional `folkways_core:store_proxies` keeps bag. Its `proxies` list contains records
with `at` (a WorldPos) and `members` (a list of WorldPos records previously enrolled through that access point).
An absent bag means no remembered relationships. Existing proxy claims discover usable members on observation;
unsafe existing claims remain enrolled. Independent member claims remain ordinary claims. The bag lets withdrawal
remove remembered members even after disconnection or reload; both addresses and members follow keeps relocation.
No existing field is rewritten and no migration initialization is needed within this development cycle.

Native store aliases without a block entity (Colossal Chest walls, and refused automation blocks) now use the
existing `Claimed.Point` representation with their ordinary store owner. Existing block claims stay unchanged.
Their acceptance predicate validates the live native store through the optional candidate tag; no new field,
bag, data version or migration initialization is added. Network identities and QIO drive capacity are resolved
from the live world and are never persisted. Tom’s and Create proxies reuse `folkways_core:store_proxies`.

The resident `folkways_core:body_state` attachment adds optional `Kit`, containing an `items` list of opaque saved
ItemStacks. Missing `Kit` means empty; the ordinary `Pack` is unchanged and kit items never enter colony stock or
plannable cells. The ordinary pack's `Items[].Slot` writer now uses an integer for capacities above 255; readers
also accept the old unsigned byte slots. Existing cargo survives capacity loss as unloadable overflow, excluded
from promised room. Capacity, abilities, workshop publications, revisions and requests are recomputed, not saved.
No backpack item inventory/component is decoded as kit configuration or mutated; ordinary stack serialization
preserves components as opaque item identity. These are additive current-cycle version-4 changes; no empty
migration or initialization step is registered. Downgrades remain unsupported.

Version 4 replaces patrol loadouts (`folkways_vanilla:patrol_loadouts`) with the
`folkways_core:equipment` colony keeps bag. Each `profiles` record has UUID `id`, string `name`, optional boolean
`riding` (absent means false), and `entries`. Entries contain UUID `id`, optional string `slot`, optional UUID `parent`,
ItemSpec compound `items`, and integer `target` / `threshold`. A named `slot` holds one worn item, with both counts 1.
An entry with neither `slot` nor `parent` is personal inventory, with target count and refill threshold. A child has
`parent` but no `slot`; its ancestor root must name a worn slot. Only these worn-item trees may contain upgrades
and discs. Trees are acyclic, native child counts are validated, and removing a parent removes its descendants.
`assignments` contains UUID `resident` / `profile` pairs, at most one profile per resident. An absent bag means none.

The body slots are `folkways_core:head`, `chest`, `legs`, `feet`. Weapons are inventory entries. Riding has no saddle
entry: `riding: true` requests a pasture horse and mandatory saddle through the patrol rules. An optional
`folkways_vanilla:horse_armor` entry is used only while riding is enabled. Accessory IDs are indexed separately:
`curios:<type>/<index>` and `accessories:<type>/<index>`, starting at zero, even for one slot. Namespaced Accessories
type names encode their colon as `/`, e.g. `accessories:example/charm/0`; existing `/` characters are doubled to avoid collisions. Compatibility layers use only Accessories
IDs. Missing framework slots and undecodable item specs remain in the save unchanged, hidden and unsupplied.

Worn backpacks save contents, installed upgrades and jukebox discs through Sophisticated's own item data and
storage UUID. The flattened `Pack` view never serializes those inventories a second time. Personal goods stay in
`Pack` or these native inventories and use existing personal-item allocation. `KitCapabilities` no longer saves or
computes a capacity multiplier; stack upgrades change native cell limits. Native removal restrictions may leave an
upgrade installed until its contents permit removal. Jukebox playlist and sound state remain runtime-only.

`folkways_core:body_state` no longer writes a `Kit` field or maintains a separate equipment container. When loading
an old development save containing `Kit.items`, each actual stack (including components) is returned to the
resident's inventory after joining the level; any remainder drops at their feet. Pending recovery is preserved in
`Pack.Recovered` if the entity is saved before it joins. Old enlarged Pack slots outside the base inventory use the
same lossless recovery path. This is defensive handling for unpublished saves, not a registered migration.

The existing Vanilla version-4 upgrade from version 3 now writes weapons as inventory entries, armour into its
four body slots, the old saddle as `riding: true`, and horse armour as the optional horse-armour entry. It preserves
profile and resident IDs, the remaining entry IDs, names, amounts and staffing references. The synthetic saddle
entry is not retained. No new data version or upgrade step is introduced: version 4 is still unpublished. Core
requires no equipment bag initialization. Version 4 saves cannot be downgraded.

Version 4 also permits the optional `folkways_core:ae2_network_devices` and `folkways_core:rs2_network_devices`
colony keeps bags for the network device panel. Each `devices` list holds records with `at` (a WorldPos), flags
`put`, `take`, `order`, `commission` and `only`, a `filters` list of `rule` strings (the ItemFilter line format) and an
optional integer `cpus`, the network's CPU (RS2: task) share remembered at that device. An absent bag or record means
every switch is on, an empty blacklist and the default share; records at their defaults are not written. Positions
are not pruned when a device leaves the colony, so re-enrolling it restores its settings. This is an additive
current-cycle change: no field is rewritten and no migration or version increment is registered.

The optional `folkways_core:ae2_network_buffers` and `folkways_core:rs2_network_buffers` keeps bags store accepted
colony work in `batches`. Alongside the existing `stage` and `finished` stage numbers, optional `committed` integer
arrays record workshop steps whose inputs have already been spent but whose settlement may still be pending.
Optional `continuation` compounds preserve a furnace's original node IDs, kinds, goods, quantities, cooking times,
dependency links and booking (`taker`, `makes`, `load`, `ticks`, `most`), rather than rebuilding from current fuel stock.
Optional `commissions` lists contain native job UUIDs as integer arrays, used only to cancel impossible work;
canceling a native job still does not reclaim accepted colony inputs. Optional `returning` marks a batch awaiting
safe return of its buffered goods. Missing fields mean empty arrays/continuation and false; older development
batches retain their existing completed-stage recovery path. These are additive version-4 changes in the current
release cycle, with no rewriting or migration initialization. Downgrades remain unsupported.

## How migration works

The engine lives in Core's `base.persist.upgrade`. Each module with world data registers its
steps from its own `upgrade` package at construction. Raw NBT is upgraded before any business
reader sees it, on four surfaces:

| Surface | Covers | Version stored in |
|---|---|---|
| `COLONY_REGISTRY` | `data/folkways_colonies.dat`: colonies, claims and every module's keeps | `schemaVersion` |
| `CROP_CARE` | `<dimension>/data/folkways_crop_care.dat` | root `folkways_core:data_version` |
| `ENTITY` | Folkways data on residents, players, animals and projectiles | `NeoForgeData` `folkways_core:data_version` |
| `BLOCK_ENTITY` | Folkways block entities and data added to native ones (furnaces, cookery stations) | `NeoForgeData` `folkways_core:data_version` |

Renamed registry ids use NeoForge aliases (`RegistryAliases`), and item components without a
stamp are normalized by their codecs (`Upgrades.codec`, `ComponentCodecs`). An entity or block entity
without a stamp is treated as current, so commands and templates are never migrated by accident.

## Changing persisted formats

1. Update the writer and reader to the new shape. Fields that can legitimately be missing stay optional.
2. Increment `DataVersion.CURRENT` once per release cycle. Add a `Vn_Description.java` step in the
   owning module's `upgrade` package, working on raw NBT with literal keys, and register it.
   When several modules change the same surface at one version, register named fragments with
   `Upgrades.part`; keeps-only changes can use `KeepSteps.bag`.
3. Verify once by loading a save written by the previous release and re-saving it. Do not commit
   sample saves or migration regression tests.
4. Run `./gradlew --offline check` and the affected modules' GameTests.

Released steps are frozen: fix mistakes with a new version and step. `checkDataUpgrades` (part of
`check`) compares released steps against their tags and limits migration packages to `java.*`,
`net.minecraft.nbt.*` and the framework.

## Known gaps

- A colony registry that cannot be read is sealed: players are disconnected and the file is left
  untouched, but other world files can still be saved.
- A CropCare file from a newer build is kept as-is. Malformed CropCare files and other failed
  SavedData can be replaced with empty data on the next save.
- Minecraft skips attachments and block entities that fail to load, with no sealing. Malformed rows
  in colony settings, blueprints and similar lists may be dropped.
- No automated check catches a format change shipped without a version bump. Review relies on
  step 3 above.
