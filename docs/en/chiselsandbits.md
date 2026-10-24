# Chisels & Bits blueprints

[Documentation](README.md) · [简体中文](../zh-CN/chiselsandbits.md)

Install `folkways-core`, `folkways-vanilla`, `folkways-chiselsandbits` and
**Chisels & Bits 21.1.33 for NeoForge 1.21.1** on both sides. Create is optional.
With Create 6.0.10 installed, residents and the schematicannon use the same
registered item requirements, safe data writer and block-entity transformer.

## Capture and reuse

Mark a box with the Colony Book and choose **Save as blueprint**. Choose an unused
lowercase file name, optionally inside folders, such as `carvings/window`.
The server reads only loaded, accessible cells and saves standard structure NBT
to both `folkways/blueprint/carvings/window.nbt` and
`folkways/piece/carvings/window.nbt`. Existing files are never overwritten.
The shared blueprint shelf updates immediately. Inventories and entities are not
copied; supported block configuration is kept.

Existing structure NBT and Create schematics can be imported through the normal
blueprint workflow. A structure placed in `folkways/piece/` or in a data pack at
`data/<namespace>/folkways/piece/` can be reused in a style:

```python
def draw(site):
    return [stamp("window", template("local:carvings/window"), site.zone)]
```

Use the existing `stretch` slices and `fit = "tile"` / `fit = "stretch"` options to
repeat complete Minecraft cells. The payload survives import, pieces, stamps,
fitting, export and preview. Horizontal rotations and mirrors move the internal
voxels as well as the surrounding cells. Swapping the container block to an
unrelated material is refused.

## Materials and completion

Supply ready-made bits in ordinary registered colony stores. Every occupied voxel
costs one `chiselsandbits:block_bit` with that material's exact component identity.
For example, a cell containing 1024 oak and 3072 stone voxels costs 1024 oak bits
and 3072 stone bits, with no container charge or rounding to whole blocks.
Uploaded cached statistics never determine the charge.

The sidebar lists bit materials separately. Planning, reservations, hauling and
consumption retain the component identities. Missing one material prevents the
shot and restores any goods already drawn for that attempt.
The cannon draws the outstanding amount across slots and containers, including
requirements above one stack; two stacks of 64 can pay for 100 bits and leave 28.
Its projectile and missing-material icons remain ordinary, persistable stacks.

The schematicannon's state-only completion rule remains: a cell already containing
the target block state is skipped, even if its carving differs. Remove the old
chiseled block before ordering a different shape there. Completed cells stay
completed after reload, so they are not charged or rebuilt again.

The first version covers static carvings and templates. It does not add automatic
chiselling, withdrawals from bit bags, a voxel scripting API or new Ponder lessons.

## Storage and loading

`StructurePayload` and `StructureCompat` are generic Core hooks. Vanilla owns the
style pipeline and construction orders. C&B classes, codecs, material accounting,
rendering and mixins all stay in `mods/chiselsandbits`.

External files still use `palette` and `blocks[].nbt`. The existing internal
`blockEntity:{nbt,turns}` format remains readable. New payloads carry an optional
`folkways:structure` descriptor with handler ID and version. Unknown handlers or
versions preserve the data and pause the affected order with a visible refusal.
Older confirmed chiseled blueprints without `payloadVersion:1` explicitly return
to pending confirmation once; ordinary targets and saved completion cells do not
change. Data discarded by an older style importer needs reimporting from its
original file.

C&B normally decodes block entities asynchronously. The integration synchronously
decodes schematic entities without enrolling them in the server's chunk tracker
or sending world updates. Its safe writer compensates for C&B's additional half
turn in `setPlacedBy` when the cannon supplies no player. Marked schematic placement
also decodes synchronously inside the existing placement call. Statistics are
recomputed with primary-state world changes suppressed during the read.
Ordinary chunk restoration keeps C&B's queued loading path. If a synchronous
schematic entity later joins a real level, its statistics are rebuilt then before
the attachment returns, so the carving is not treated as empty.
A corrupt schematic is marked errored before a real cannon can print it, including
when a creative crate bypasses material checks.

`folkways_chiselsandbits-server.toml` limits decompressed bytes, distinct materials
and processing time per cell, including decompression and NBT reads. Defaults are
1 MiB, 256 materials and 1000 ms. Legacy `data` / `compressed` wrappers are unfolded
up to sixteen layers with one shared decompression/allocation limit.

The whole capture, confirmation, template drawing, material quote or cannon load
also shares `blueprintReads` (4096 voxel read/count operations), `blueprintBytes`
(16 MiB) and `blueprintMillis` (500 ms). Nested calls share that budget; cache hits
still count towards read and decoded-byte limits. Excessive orders stay pending
with a refusal instead of being partly confirmed. A private snapshot and its
verified material counts are reused in a cache bounded to 128 entries and an
estimated 16 MiB; mutating an entity invalidates its remembered counts.

Palette indices are checked before counting. Existing structure volume and upload
limits still apply. Colony previews select nearby candidates and apply both the
requested count and 256 KiB payload limit before quoting their materials. All
orders in one preview share the processing budget, and retain only the checked
nearby ghosts when that budget runs out.

C&B 21.1.33 bundles Saecularia Core with `FMLModType: MOD` but no mod descriptor,
causing [upstream issue #1343](https://github.com/ChiselsAndBits/Chisels-and-Bits/issues/1343).
The integration packages that pinned library with a `GAMELIBRARY` manifest; its
class bytes are unchanged. `blockwright.toml` pins C&B with SHA-512 and extracts
its API, Core, Scena and Saecularia dependencies from the same verified archive.

## Verification

Prepare fixtures with `npm ci` and `npm run setup`. All Gradle commands use
`--no-daemon` so each run releases its memory:

```sh
./gradlew --no-daemon build
./gradlew --no-daemon runGameTestServer
./gradlew --no-daemon -PchiselsTests=true runGameTestServer
./gradlew --no-daemon -PchiselsTests=true -PgameTestMod=chiselsandbits runGameTestServer
./gradlew --no-daemon -PchiselsTests=true -PgameTestMod=chiselsandbits -PwithoutCreate=true runGameTestServer
npm run test:i18n
npm run test:e2e -- tests/e2e/chiselsandbits.spec.ts --workers=1
```

The optional Blockwright profile is `chiselsandbits`. It installs C&B and the
Folkways extension together. Focused GameTests cover decoded charges, distinct bit
hauls, missing-material rollback, capture/import, template tiling and stretching,
horizontal transforms, same-state skips, save migration, reload completion,
missing integrations, malformed/oversized data and actual cannon/resident parity.
The integration tests live in its own project; their same-package construction
scenarios are attached to Vanilla's test module to avoid Java split packages.

The client test checks that voxel previews produce meshes, a missing material holds the order, the resident hauls and consumes the exact bits, and a full server/client restart preserves the built voxels without another charge. Its preview screenshot and traces are saved under `tests/out/e2e/`.
