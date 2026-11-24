# Save compatibility

English · [简体中文](SAVE_COMPATIBILITY.zh-CN.md)

## Versions

All modules release together as `0.MINOR.PATCH`. PATCH is a small update (fixes and minor
changes); MINOR is a large update (new features or gameplay). There is no alpha/beta/rc ladder and
no extension API stability before 1.0.0. Clients, servers and the Folkways jars on each side must
all be the same build.

Save formats are tracked separately by `DataVersion`, a single increasing number shared by every
module. It never decides the release version. It is bumped by each pull request that changes a
persisted format when it merges into master; a release does not bump it on its own.

## Compatibility promise

- Every release and every master commit, nightly builds included, opens saves from every earlier
  release and master commit and migrates them forward. Unmerged branches and local builds carry no promise.
- Downgrades are unsupported. Release notes say when the data version changes.
- Keep every data-owning module installed while upgrading. Code from a missing module cannot
  migrate its data.
- Migration rewrites world NBT only. Commands, datapacks, resource packs, configuration files,
  Starlark patterns, resident-model packs and NBT passed to commands must use current ids.
  Exported blueprints are the exception: they are normalized when imported.

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

Pull requests are always squash-merged into master, so intermediate commits on a feature branch never
reach master's history. When deciding whether a migration or compatibility path is needed, compare only
against the format already on `origin/master`. A format written and then changed within a branch, including
a new module not yet on master, goes straight to its final shape before merging: no migration step,
missing-field fallback, version check or old-format test, and no `DataVersion` increment.

1. Update the writer and reader to the new shape. Fields that can legitimately be missing stay optional.
2. Increment `DataVersion.CURRENT` once per pull request. Add a `Vn_Description.java` step in the
   owning module's `upgrade` package, working on raw NBT with literal keys, and register it.
   When several modules change the same surface at one version, register named fragments with
   `Upgrades.part`; keeps-only changes can use `KeepSteps.bag`.
3. Verify once by loading a save written by the current `origin/master` and re-saving it. The dedicated
   [release upgrade fixture](CONTRIBUTING.md#validation) is the exception to keeping sample saves
   and migration regression tests out of the repository.
4. Run `./gradlew --offline check` and the affected modules' GameTests.

Steps on master are frozen: fix mistakes with a new version and step. `checkDataUpgrades` (part of
`check`) compares them against every release tag and the branch's merge base with `origin/master`,
and limits migration packages to `java.*`,
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

Airport resource bookings are transient and are never saved.
