# Save compatibility

English · [简体中文](SAVE_COMPATIBILITY.zh-CN.md)

## Versions and releases

All modules release together as `0.MINOR.PATCH`, starting at baseline tag `v0.1.0`.
The owner will cut that tag after merging; it does not exist yet. PATCH is a small update (fixes and
minor changes); MINOR is a large update (new features or gameplay). Save-format changes do not pick the
version component: migrations upgrade saves automatically, and the format is tracked separately by
`DataVersion`. Downgrades are unsupported, so release notes say when a release changes the data version
and older builds can no longer open its saves. No extension API stability is promised before 1.0.0. There is no alpha/beta/rc release ladder. `mods.toml` uses the plain
mod version; the existing jar names include the Minecraft version separately. Exact inter-mod
`[${version}]` dependencies stay unchanged. Payload registrars all use the mod release version;
clients and servers must match exactly, with no network compatibility promise.

`DataVersion.BASELINE = 1` and `DataVersion.CURRENT = 2` bound the shared, monotonically increasing
Folkways save version. Data version 1 is the development format written by master `9a45a4d52`
(`9a45a4d5217b5927716b8c1120a86b2098bd1757`); version 2 is the `v0.1.0` release format. Every release
has a data version, but only format changes increment it. ColonyRegistry keeps its required
`schemaVersion` key; version 1 colony `holdings` becomes version 2 `claims`. `SaveVersion` has been
replaced by this framework.
`StructureSnapshots.schemaVersion = 1` remains a separate subrecord shape marker; its Minecraft
`DataVersion` is also independent.

## Compatibility promise

Every official release reads saves from every official release since `v0.1.0`, migrating forward.
There is no downgrade support. The supported development bridge is specifically master `9a45a4d52`,
whose actual writer output defines data version 1. Earlier development shapes are unsupported: first
open and save them with a build of that master, then upgrade. This still cannot guarantee conversion
of entities or blocks in chunks that master never loaded. Other nightly formats have no compatibility
promise. Business readers only accept current formats; migrations rewrite raw NBT before deserialization. Combine changes
within one development cycle into one target version. Released steps are immutable: corrections
need a new version and step. Keep old steps, including for entities and unloaded chunks that may
first load many releases later. If a platform change forces a higher minimum, document that break
in the release notes. External models, resource manifests, imported templates and configuration
files are outside the raw world-NBT migration chain. Command and template NBT must use the current
format; no version compatibility is provided for those inputs.

## Upgrade entry points and stamps

The single engine lives in Core's `base.persist.upgrade`: `DataVersion`, `Surface`, `Step`, `Upgrades`,
`KeepSteps`, `RawUpgrades` and `RegistryAliases`. Each module with world data has a separate `upgrade`
package and a construction-time registration entry. Firefighting only removes native live
connection data and Modular Golems adds no owned world payload, so neither needs an empty package.
Registration freezes in queued common setup work, after all mod constructors.

- `COLONY_REGISTRY`: `ColonyRegistry.load` validates `schemaVersion`, upgrades a copy, then creates
  the current Reader. Missing, malformed, too old, too new and failed migrations seal the registry
  with the existing refusal/file-preservation behavior. Successful upgrades mark it dirty.
- `CROP_CARE`: its SavedData load upgrades a copy before reading treatments; save writes
  `folkways:data_version` at the root. Missing means baseline; malformed/too-old versions fail
  through the existing SavedData error path. Future versions log an error and retain an opaque copy
  of the original tag for any later save, without decoding treatments (see the limitations below).
- `ENTITY`: a required mixin at `Entity.load` HEAD runs before NeoForge persistent data, attachments
  and business reads. `saveWithoutId` RETURN stamps the complete result, including players and
  subclass data, in `NeoForgeData["folkways:data_version"]`. Only stamped input is upgraded.
  Untouched vanilla entities are not stamped.
- `BLOCK_ENTITY`: required HEAD hooks at both final `loadWithComponents` and `loadCustomOnly` run
  before virtual `loadAdditional`, including NeoForge data/attachments. RETURN hooks at the final
  `saveWithoutMetadata` and `saveCustomOnly` stamp after subclass writers; metadata wrappers delegate
  to these methods. This covers SupplyBox, TavernCounter, native cookery stations, furnace training
  and C&B schematic fields.

Owned data means an owned `folkways:` entity/block-entity type, a `folkways:` attachment, or keys
starting with `folkways:`, `folkways.` or `folkways_` in NeoForgeData or the root. The audit found all
persistent keys fit these existing prefixes; root `FolkwaysFurnace*` fields are additionally recognized.
No business keys were renamed. These prefix families are reserved for Folkways. Detection includes
the stamp itself so emptied records retain their version. Codec-only attachments may cause the
enclosing entity to be stamped, but their codecs do not use that stamp.

Entity and block-entity load methods also receive commands (`/summon` included), spawn-egg
`entity_data`, sync, items, templates and copied tags. For **both ENTITY and BLOCK_ENTITY**, no stamp
means current-format input: loading neither migrates nor rewrites it, and adds no stamp.
Saving owned data stamps it naturally. Master v1 entity/block-entity writer output is already readable
as current format despite having no stamp; it loads through this same unchanged rule. Unused v1 local
metadata does not require migration. Version 2 saves
are stamped. There is no disk-only baseline recognition hook. Stamped inputs upgrade according to their stamp.
SupplyBox update tags omit `generated`; current partial payloads remain safe. Future steps must
check whole-record presence and tolerate sparse copied/sync subrecords; they must never infer a
missing field alone is legacy data. Custom code constructing legacy NBT for a supported save must
explicitly supply its version or use a legacy codec. Raw migrations do not initialize optional data bags.

Unsupported/malformed lazy entity or block-entity stamps log an error and skip migration. Saves retain
that stamp rather than relabelling it current; there is no whole-world sealing or guarantee that
current business readers preserve unknown fields. Avoid opening newer worlds with older builds.
`BOOK_GESTURE`, `FILTER_TAG`, `colony_member` and `current_colony` have no component/attachment stamp:
use `Upgrades.codec(current, legacy.xmap(...))` for future shape changes. It tries current decoding
first, then converted legacy codecs, rejects unsuccessful/partial results, and only writes current.
Registry id renames use the central `RegistryAliases.alias(deferredRegister, oldId, newId)` helper
at construction time, delegating to NeoForge `DeferredRegister.addAlias`.

## Changing persisted formats

1. Update the business writer and reader to the current shape. Keep legitimately omitted fields
   optional; inspect all block-entity/attachment load callers before requiring a field.
2. Increment `DataVersion.CURRENT` once for the cycle. Add `V3_Description.java` (target 3, for example)
   in the owning module's `upgrade` package, implementing `Step` with literal keys and only raw NBT.
   Register it in that module's construction-time entry. A step targets `target - 1 → target`;
   unchanged versions/surfaces need no no-op steps. When multiple modules change the same surface
   at that target, register independent named fragments with
   `Upgrades.part("module/name", new V3_Description())`. The root step runs first, then fragments
   in name order; duplicate `(surface, target, name)` fragments fail. This composes one effective
   per-surface step without cross-importing module migrations.
3. A keeps-only migration can instead register `KeepSteps.bag("folkways:owner", 3, bag -> ...)` from
   `V3_Description.register()`. The helper visits present bags of every colony. Because many modules
   can change bags at the same version, these owner contributions compose after the registry step,
   in owner-id order. Duplicate `(owner, target)` contributions fail, while ordinary Steps still
   reject duplicate `(surface, target)`. Bag changes must be independent across owners.
4. Verify the step once while writing it: load a save or NBT really written by the previous release into
   the new code and confirm values survive and re-save at the new version. This is a one-time check; do not
   commit samples or dedicated migration regression tests. Released steps are frozen, so their result cannot
   change, and `checkDataUpgrades` provides the lasting guard.
5. Run `./gradlew --offline check` and the affected modules' GameTests.

`checkDataUpgrades` is part of `check`. Migration packages may import only `java.*`,
`net.minecraft.nbt.*` and the framework, with fully qualified references also checked. Business code
cannot reference migration packages except from mod entry classes. Step filenames contain their
literal target (`Vn_Description.java`). For every `v*` tag with a DataVersion, the check compares released
step bytes with `git show`, detecting edits, deletions, renames and retroactive additions. No release
tags means no frozen-file comparisons. The check does not require a step for unchanged surfaces.

The framework's own GameTests (chaining, version bounds, fragment composition, stamping rules) can be run with
`./gradlew --offline runGameTestServer -PgameTestNamespaces=folkways_persistence`

v1 → v2 consists only of Core's `V2_Claims` named registry fragment (`holdings` → `claims`). Every other
difference is extra metadata that current readers ignore (local `version` in WorkDefaults/loadouts/BodyPerks,
`toolVersion`, `payloadVersion`, the damage-control bag `schemaVersion`, `forage_version`, MillstoneDrive
`target`) and needs no migration; the old `kept_books` player attachment is not restored. This was verified
once against real master writer output.

## Persisted data inventory

**Current:** `<dimension>` is the world root for Overworld, `DIM-1`, `DIM1`, or `dimensions/<namespace>/<path>`. “No own version” does not exclude Minecraft's enclosing `DataVersion`.

| Surface | World-folder storage | Loaded when | Version | Owner | Upgrade entry / version stamp |
|---|---|---|---|---|---|
| ColonyRegistry | `data/folkways_colonies.dat` | Server startup (`onServerStarting → ColonyRuntime.opened → ColonyRegistry.get`) | Folkways schema 2; Minecraft wrapper version | Core | COLONY_REGISTRY / `schemaVersion` |
| ColonyData claims and keeps | Inside each registry colony | Registry deserialization; plugin state consumed when services settle | Registry version | Core | COLONY_REGISTRY / `schemaVersion` |
| Plugin presences, front/settings, work defaults, names/looks, loadouts, RTS groups, tool loans | Registry `keeps` bags | Service creation/settlement | Registry version | Core, Vanilla, Create, Cookery, RTS | COLONY_REGISTRY / `schemaVersion` |
| CropCare | `<dimension>/data/folkways_crop_care.dat` | First CropCare access in that dimension | Global data version 2 | Vanilla | CROP_CARE / root `folkways:data_version` |
| ResidentEntity names/looks | `<dimension>/entities/r.*.mca` | Entity/chunk loading | Global data version 2 | Vanilla | ENTITY / NeoForgeData `folkways:data_version`; codec-only attachments: codec |
| BodyState, licences, colony membership attachments | Entity NBT in entity region files | Attachment deserialization/access | Global data version 2 | Core | ENTITY / NeoForgeData `folkways:data_version`; codec-only attachments: codec |
| Player attachments | `playerdata/<UUID>.dat`; integrated-server player also represented in `level.dat` | Player loading | Global data version 2 | Core/front | ENTITY / NeoForgeData `folkways:data_version`; codec-only attachments: codec |
| SupplyBox | Block-entity NBT in `<dimension>/region/r.*.mca` | Chunk/block-entity loading | Global data version 2 | Vanilla | BLOCK_ENTITY / NeoForgeData `folkways:data_version` |
| TavernCounter | Same block-entity storage | Chunk/block-entity loading | Global data version 2 | Cookery | BLOCK_ENTITY / NeoForgeData `folkways:data_version` |
| Cookery board/pot/stockpot/millstone/spit/steamer/tray/teapot/tavern batches | Native block entity’s `NeoForgeData`, in region files | Native block entity loads; batch read during surveying/recovery | Global data version 2 | Cookery | BLOCK_ENTITY / NeoForgeData `folkways:data_version` |
| LoanClothing | Mob persistent-data slot maps, in entity files | Mob loads; recovery/join processing | Global data version 2 | Cookery | ENTITY / NeoForgeData `folkways:data_version`; codec-only attachments: codec |
| EquipmentCoordinator | Mob persistent-data equipment map | Equipment reconciliation | Global data version 2 | Core | ENTITY / NeoForgeData `folkways:data_version`; codec-only attachments: codec |
| Dining/history/seats and colony pets | Mob persistent data; pet roster also in registry keeps | Entity loads/actions/service load | Global data version 2 | Vanilla | ENTITY / NeoForgeData `folkways:data_version`; codec-only attachments: codec |
| WeaponStations seats | Mob persistent-data lease/vehicle marker | Entity loading and post-tick restoration | Global data version 2 | Vanilla | ENTITY / NeoForgeData `folkways:data_version`; codec-only attachments: codec |
| Ridership destination | Rider persistent data | Rider/carriage restoration and route queries | Global data version 2 | Create | ENTITY / NeoForgeData `folkways:data_version`; codec-only attachments: codec |
| BreedingPerks | Animal persistent-data breeding marker | Breeding event | Global data version 2 | Vanilla | ENTITY / NeoForgeData `folkways:data_version`; codec-only attachments: codec |
| Warfare/TACZ projectile ownership | Projectile persistent data in entity files | Projectile loads, impact/damage processing | Global data version 2 | Warfare, TACZ | ENTITY / NeoForgeData `folkways:data_version`; codec-only attachments: codec |
| Furnace training batches/fraction | Native furnace block-entity NBT in region files | Furnace loading | Global data version 2 | Vanilla | BLOCK_ENTITY / NeoForgeData `folkways:data_version` |
| StructureSnapshots | Damage-control bag inside registry keeps | Damage-control service creation | Snapshot schema 1 plus `DataVersion` | Core format, Vanilla owner | COLONY_REGISTRY / `schemaVersion` |
| Blueprints, build orders and commissioned drafts | Build bag inside registry keeps | Build service creation | Registry version | Vanilla | COLONY_REGISTRY / `schemaVersion` |
| ItemSpec and ColonySettings | Embedded in their owning keeps/settings/records | Owning record loads | No separate version | Core/front | COLONY_REGISTRY / `schemaVersion` |
| Item data components | ItemStack `components`, wherever that stack is saved | Stack codec decoding | No own version | Core/front | codec / — |
| Server config files | `serverconfig/folkways*-server.toml` | Server config loading | No own version | Core, Vanilla, Create, TACZ | — (outside world NBT) |
| Client drawing settings/resources | Outside world: `config/folkways-drawing-settings.properties`, client resources and authored structure/pattern files | UI/resource loading | Format-dependent | Core/front, Vanilla | — (outside world NBT) |


**Current:** The firefighting hydrant mixin removes a live resident connection from native saved data; it adds no separate persistent payload.

## Known gaps

The data-owning modules must remain installed while migrating. Removing and later reinstalling an
optional module is outside this promise: its absent migration code cannot upgrade retained opaque
bags before the shared version advances. Cross-module changes at one target must use independent
named fragments; their names/order are part of the migration contract.

**Current:** `ColonyRegistry` seals unsupported versions, unreadable top-level colony rows and registry-load
exceptions. An existing file rejected by Minecraft's disk/NBT reader and exceptions during service settlement are also sealed.
Service startup exceptions can be plugin bugs as well as malformed data: their separate message says the colony
service failed to start, the colony file was not changed, the server log has the cause, and the world can be
reopened once the failure is resolved. It does not diagnose an unreadable save or require a new world. The refusal protects only
`folkways_colonies.dat`: players are disconnected at `PlayerLoggedInEvent` or when runtime settlement seals the registry, while the server continues running
and can write region, entity, per-dimension SavedData, playerdata and `level.dat`.

**Current:** A CropCare file newer than the running program cannot be decoded. Previously this
failed through Minecraft's SavedData loader, which substitutes empty data that a later save can overwrite.
The shared data version also seals the colony registry and disconnects players, preventing continued play,
but the running server can still autosave. CropCare now protects this specific future-version path by
retaining the original data tag and returning a fresh, unchanged copy on save; no treatment changes are
accepted. This preserves the payload, not the exact compressed file bytes or Minecraft's outer wrapper.
Malformed/too-old files and other load failures still use the empty-replacement path and may be overwritten.

**Current:** New registries are dirty from construction and record schemaVersion on their first save, even
without a colony. A pre-baseline world with no colony file still cannot be distinguished from a new world.

**Current:** No test detects a format change shipped without a version bump and migration; this relies on
review and the one-time verification in step 4. `StructureSnapshots` has a separate version and returns empty
on mismatch without visible refusal.

**Current:** Minecraft/NeoForge still logs and skips failed attachments (`AttachmentHolder.deserializeAttachments`)
and block entities (`BlockEntity.loadStatic`); there is no runtime sealing for them. Bodies create BodyState and licences when
constructed and save a `folkways:body` marker; a saved body missing either store is logged with its UUID and
loads with fresh stores, because throwing would make Minecraft drop the entity. Other SavedData such as CropCare
still passes through `DimensionDataStorage.readSavedData`, whose caught failures can allow replacement by an
empty object. Colony claims now fail on malformed rows; the remaining `Entries.of` callers (colony settings, blueprints, unloaded blocks, beds) are unchanged: their malformed rows
may still be logged and dropped, or silently dropped on `Optional.empty`. Plugin parsers also use optional
results for unresolved registries/world positions. These can still make later saves permanently lose data.
Furnace load methods also accept partial command/item/template data: each top-level training field defaults
independently when absent, while present batch rows require count and speed.

**Current:** Malformed weapon-seat records propagate validation failure before dismount/marker removal; that
event path has no sealing. Malformed clothing identity/snapshot pairs retain the marker and garment for
recovery rather than guessing ownership; they are not automatically repaired. Mod-owned blockstate palettes
validate Name and all defined properties, while imported templates keep native defaults. Missing or malformed
snapshot fields can still return Optional.empty through the existing parser failure path.
