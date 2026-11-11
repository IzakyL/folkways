# Save compatibility

English · [简体中文](SAVE_COMPATIBILITY.zh-CN.md)

## Versions

All modules release together as `0.MINOR.PATCH`, starting at `v0.1.0`. PATCH is a small update
(fixes and minor changes); MINOR is a large update (new features or gameplay). Save formats are
tracked separately by `DataVersion` and never decide the version number. There is no alpha/beta/rc
ladder and no extension API stability before 1.0.0. Clients, servers and the Folkways jars on each
side must all be the same build.

`DataVersion.CURRENT` is a single, increasing Folkways save version shared by every module.
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
