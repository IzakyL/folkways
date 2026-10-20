# Owner saves

Owners can adopt a versioned save gradually. Declare an `OwnerSave` through `Declaring.save` alongside the
owner's contribution, then use `Colony.kept(OwnerSave)` and `Colony.keep(OwnerSave, payload)`. The old
`ResourceLocation` overloads remain available for owners which have not adopted a schema. Raw writes cannot
replace data belonging to a versioned owner.

`OwnerSave` takes its owner and an ordered list of pure NBT migrations. Step zero turns the old unwrapped
payload into version one; step one turns version one into version two, and so on. Its optional validator checks
the current payload on load, after migration and before a write. Steps and validators receive private copies,
must preserve fields they do not own and must not access game state. An absent owner has an empty payload;
reading it does not create a save, run migrations or mark the colony dirty.

Core stores `folkways:ownerSave = "folkways:owner-save/1"`, `owner`, integer `version`, and compound `payload`
in an envelope. Ordinary legacy fields named `owner`, `version` or `payload` do not identify an envelope.
All required migrations must succeed before Core replaces the blob and marks it dirty. The first successful
migration stores the entire previous blob in the envelope's `original` compound; later writes and migrations
retain it. **This is a backup inside the same NBT save, not an independent disk backup.** Coordinate relocation
rewrites only the live payload and never rewrites `original` or unknown envelope fields.

Future versions, malformed envelopes, unavailable schemas, and failed migration or validation leave the
original blob untouched and disable that owner's contributions. `kept(OwnerSave)` returns empty in that case;
the owner must not start with an empty substitute. Both raw and versioned writes are refused. Other owners
continue to load, and missing plugins' blobs survive save/reload. Relocating a colony with any protected
versioned blob is rejected before holdings move: Core cannot assume an opaque payload has no coordinates.

Create's railway roster is the first adopter. Version one preserves the legacy UUID list and unknown payload
fields; the other owners continue using their existing raw payloads.
