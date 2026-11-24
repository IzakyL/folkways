# Contributing to Folkways

English · [简体中文](CONTRIBUTING.zh-CN.md)

Bug reports, documentation, translations, building patterns and code contributions
are welcome at [IzakyL/folkways](https://github.com/IzakyL/folkways).
For installing and playing the mod, start with the [README](README.md).

[Report a bug](#reporting-bugs) · [Set up](#development-setup) ·
[Run checks](#validation) · [Open a pull request](#pull-requests) ·
[Conventions](#conventions)

## Reporting bugs

Use the [bug report form](https://github.com/IzakyL/folkways/issues/new?template=bug_report.yml)
and include what you did, what you expected, what happened, and reproduction steps.
List Minecraft, NeoForge and LDLib2 versions, every installed Folkways jar and
its version, and your other mods. Attach `logs/latest.log` and the crash report
from `crash-reports/` if the game crashed.
For resident behaviour problems, turn on **Debug logs**, look at the resident or block
involved and attach a screenshot of its debug card; see [Investigating residents](README.md#investigating-residents).

For new capabilities, use the
[feature request form](https://github.com/IzakyL/folkways/issues/new?template=feature_request.yml)
and explain the expected behavior, use case and alternatives you considered.

## Development setup

| Tool or platform | Requirement                                                  |
| ---------------- | ------------------------------------------------------------ |
| Java             | JDK 21                                                       |
| Node.js          | 22.14 or newer                                               |
| Gradle           | Checked-in wrapper: `./gradlew`, or `gradlew.bat` on Windows |
| Blockwright SDK  | npm package: Linux x64, or Windows 11 x64 (candidate)        |

The pinned [Blockwright](https://github.com/IzakyL/blockwright) npm packages bundle
native drivers for Linux x64 and Windows 11 x64. This repository's CI uses
Ubuntu 24.04. From the repository root, run (use `gradlew.bat` on Windows):

```sh
npm ci
npm run setup
./gradlew build
```

`npm ci` installs the locked test dependencies. `npm run setup` downloads fixtures
pinned in [`blockwright.toml`](blockwright.toml) and writes `.blockwright/mods.json`.
**Run setup before the first Gradle invocation**; rerun it after changing pinned
fixtures. Gradle compiles against optional integration jars even when a particular
test profile does not load them.

The build gathers the four mod jars in `build/libs/` and runtime LDLib2 in `build/deps/`.
Each mod is a Gradle project under `mods/`; see [Mods](README.md#mods) for what each one does.
Squad grouping and direct commands belong to Core’s **Control** mode (Colony Book → **V** → **Control**).
Keep command fixtures on the RTS APIs; equipment assignment uses the equipment APIs.

Windows 11 x64 is a Blockwright development candidate; see its
[Windows requirements](https://github.com/IzakyL/blockwright/blob/master/docs/environment.md#windows-development-candidate).
Client tests require a logged-in Windows 11 x64 desktop with an OpenGL-capable GPU;
use display device `auto` or `gpu`. Record the platform when reporting results;
local Windows tests and Linux CI are separate validation runs.

## Validation

Start with checks that do not launch Minecraft:

```sh
npm run test:unit
npm run typecheck
./gradlew check
```

Add or update tests for behavioral changes. Prefer a black-box e2e spec for
behavior a player can observe. Run the relevant mod's GameTests and client
suite.

CI runs the build checks, per-mod GameTests and a focused real-client founding
smoke test. It does not run every client or capture suite. See
`.github/workflows/build.yml` for the exact matrix.

Run GameTests with one template, adding switches from the table below:

```sh
./gradlew --offline -PgameTestMod=<mod> [-P<switch>=<value> ...] runGameTestServer
```

| Property | Enables | Constraints |
|---|---|---|
| `gameTestMod` | One mod's suite: a directory under `mods/`. An extension's target mod is loaded automatically | Without it, only Core's base suite is enabled |
| `cookeryTests` | Kaleidoscope Cookery and its integration fixtures | Use `gameTestMod=vanilla` for the kitchen gameplay suite or `gameTestMod=core` to add Core's kitchen fixtures. Off by default |
| `cartsTests` | AstikorCarts Redux and the cargo and cart line fixtures | Use `gameTestMod=vanilla` for the suite. Off by default |
| `modularGolemsTests`, `chiselsTests` | Modular Golems or Chisels & Bits and the corresponding Core integration fixtures | Require `gameTestMod=core`; off by default; run only the selected integration fixtures |
| `epicfightTests` | Epic Fight and the resident integration fixtures | Use `gameTestMod=vanilla`; off by default |
| `sableTests` | Sable facility fixtures | Use `gameTestMod=vanilla` for the gate or pair with `gameTestMod=core` and `cbcTests=true` for the cannon mount. Off by default |
| `taczTests`, `warfareTests` | TACZ or Superb Warfare integration fixtures | Use `gameTestMod=vanilla`. Off by default. `warfareTests` also loads Airline |
| `cbcTests` | Create Big Cannons cannon mount fixtures, with Create and Ritchie’s Projectile Library | Use `gameTestMod=core`. Off by default |
| `letItWalkTests` | Let it Walk! walker fixtures (drive, park, a resident crew aboard), with Create and Sable | Use `gameTestMod=core`. Off by default |
| `createTests` | Create integration suite | Use `gameTestMod=vanilla`. Off by default |
| `firefightingTests` | Create integration suite and FireFighting Additions fixtures | Use `gameTestMod=vanilla`. Off by default |
| `vanillaBaseTests` | Adds the existing Vanilla Cookery fixtures and the Vanilla base suite | Pair with `gameTestMod=vanilla` and `cookeryTests=true` |
| `tavernTests` | Kaleidoscope Tavern fixtures (Cookery) | Requires `cookeryTests` |
| `createPlacementTests` | Adds Create and its printing fixture to the Chisels & Bits suite | Requires `gameTestMod=core` and `chiselsTests=true` |
| `storageProfile` | One storage mod: `sophisticatedStorage`, `sophisticatedBackpacks`, `drawers`, `functional`, `createStorage`, `toms`, `colossal` or `qio` | Core only; AE2, RS2 and storage profiles are mutually exclusive |
| `ae2Tests`, `rs2Tests` | Applied Energistics 2 or Refined Storage 2 fixtures | Core only; mutually exclusive with each other and storage profiles |
| `storageLaborTests`, `ae2LaborTests`, `rs2LaborTests` | Resident labour tests on top of the native port tests | Require the matching profile or suite, and Vanilla |
| `storageVanilla`, `ae2Vanilla`, `rs2Vanilla` | `false` runs the native ports without Folkways Vanilla | Not with labour tests |
| `accessoryProfile` | `curios` or `accessories`, with Sophisticated Backpacks | Core only; does not load the base Core suite |
| `accessoryCoexistence`, `accessoryCompat` | Adds genuine Accessories beside Curios, or the old cclayer beside Accessories | Enabled by being set, whatever the value; pair with `accessoryProfile=curios` or `accessories` |
| `coreGameTestClasses`, `vanillaGameTestClasses` | Compile and register only the listed GameTest classes (comma-separated) | With `gameTestMod=vanilla` and `createTests=true`, use the Core selector for adapter tests and the Vanilla selector for gameplay tests |
| `gameTestFilter` | Case-insensitive full-name regex for Airline tests (method names without class prefixes) | Applied before sharding, together with `gameTestMethods` |
| `gameTestShard` | Airline batch shard `i/n`, with zero-based `i` | Requires `0 <= i < n`; filters run first |
| `gameTestMethods` | Rerun only the listed methods of fixtures that support it | — |
| `gameTestNamespaces`, `gameTestDirectory` | Override the enabled namespaces or the run directory | Each switch combination otherwise gets its own world under `run/gametest/` |
| `saveUpgradeTests` | Reopens and resaves the published V4 integration fixture | Run on its own with `-PsaveUpgradeTests=true` |

Run `npm run setup` once to download the pinned fixtures. Run `./gradlew build` online to resolve dependencies,
including Assistant’s jpi-mini from JitPack, then keep Gradle `--offline`. Native-mod suites have matching
Blockwright profiles in [`blockwright.toml`](blockwright.toml) (`ae2`, `rs2`, `storage_<name>`,
`curios`, `accessories`). Run GameTest profiles serially to keep memory usage bounded.

The Assistant Blockwright profiles can use the JVM properties `folkways.assistant.provider`,
`folkways.assistant.baseUrl` and `folkways.assistant.model` to override the client settings,
with one warning listing the overridden fields.

Run the V4 → V5 world upgrade with `./gradlew --offline -PsaveUpgradeTests=true runGameTestServer`.
The profile stages a fresh copy from `tests/fixtures/saves/v4/` in `build/save-upgrade/`.
The fixture was written by the GameTest server at `20680d4b87a28940bb1ac6853320b7dde3737d8d`
(`origin/master`, Folkways data version 4) and retains the occupied chunk, colony registry,
and the old Create, TACZ and Chisels & Bits server configs. Keep binary save fixtures in
`tests/fixtures/saves/` and retain only the files and chunks the test needs.

Airline GameTests: `./gradlew --offline -PgameTestMod=airline runGameTestServer`.

Run Airline in independent server processes (three by default):

```sh
npm run test:airline:shards -- --shards 3
npm run test:airline:shards -- --shards 2 '-PgameTestFilter=runway.*'
```

The script prepares/compiles once with Gradle, exports its Java launch command, then starts the JVMs
concurrently without Gradle project locks. Each uses a fresh run directory with `eula.txt`; worlds are
removed after exit. Logs, timestamps, durations and pass/fail summaries remain under `build/airline-shards/`.
Any failed shard makes the command fail. Extra `-P` properties are forwarded, including `gameTestMethods`
; the script owns `gameTestMod`, `gameTestShard` and `gameTestDirectory`.
Allow about 6 GiB per server; on Linux the script waits for `6 × N + 2` GiB available before launching.
Batches stay intact: after filtering, sort by summed timeout ticks descending (ties by batch name), then
assign to the least-loaded shard (ties by shard index). `-PgameTestShard=0/3` also works with a direct
Gradle invocation. The script finishes Gradle before starting servers, so Gradle locks cannot serialize
server execution and each shard avoids repeating build preparation.
`gameTestFilter` matches the whole logged test name, e.g. `runwaymarkingssurveyandaircraftfit`,
without a class prefix.
Airline GameTest structures and local fixtures use separately allocated sites, with scoped airport names and
cleanup on both success and failure. Ordinary native fixtures share batches. Tests that measure server-wide
survey budgets keep an exclusive batch, with the reason beside the test. Production airport registries and
traffic remain shared per dimension.
Survey-heavy fixtures use small shared batches to bound live chunk memory.
Do not rebuild classes while shard JVMs are running: the exported launch command reads the worktree's build outputs.

`npm run test:airline` runs the airport tower client fixture.

## Pull requests

1. Make the change in your fork on a focused branch.
2. Follow the conventions below and update the relevant tests and documentation.
3. Run the checks and game suites appropriate to the change.
4. Open a pull request against [IzakyL/folkways](https://github.com/IzakyL/folkways/pulls).
   Explain the problem, resulting behavior and verification. List checks you
   could not run and any remaining limitations.

Pull requests are squash-merged into master. Persisted-format changes within a branch only need
migrations relative to `origin/master`; see [save compatibility](SAVE_COMPATIBILITY.md#changing-persisted-formats).

Commit messages follow `type(scope): summary`, for example:

```text
fix(plan): keep rations out of cargo
feat(draft): add a dock pattern
```

## Conventions

Changing persisted data? Follow [save compatibility](SAVE_COMPATIBILITY.md).

### Mod layout

- **Core holds the engine.** It includes the engine (colonies, residents, planning, world, inventory, travel),
  facilities, cards, the Starlark runtime and the command UI, plus every third-party adapter under
  `core.shell.<mod>`. Adapters answer *how*: ports, controls, operating protocols, conveyances, how a weapon
  fires, how a structure is encoded. They do not register trades, zones or work.
- **Vanilla holds resident gameplay**: trades, work, prefab facilities and routines that make gameplay decisions,
  including the gameplay half of each integration under `vanilla.<mod>`, initialized only when the target mod loads. Modules extend held facilities with `HoldingKind` and supply
  colony-wide rows through `ColonyPanel` on **Overview**, **Workforce** and **Stock & orders**, or under their own
  settings card on **Settings**. An editor too large for a card opens from a **Workforce** button (`RegisteringUi.editor`).
- AE2 and RS2 are a layout exception. Both directions of the integration, including **Network service**,
  belong in Core with the adapters. Network service connects networks to Workshop recipes for any resident
  kind, at the same abstraction level as the planner and ports.
- Everything the book can draw names a `Category` for what it serves (`Delegation.in`, `BoxOffers.Offer.in`,
  `PathOffers.Offer.in`, `Placing.Shelf.in`); the view from above groups its tools by it and the shape picker lists
  candidates under it. Use a built-in category, or register one with `Category.register`; unset falls under **Other**.
- An adapter lives on the layer of the thing it adapts. Core cannot depend on Vanilla, so adapters for human
  residents (such as Epic Fight resident animation) stay in Vanilla. Golems are a resident kind without needs and
  live entirely in Core; human residents and their needs belong to Vanilla.
- Only independent gameplay gets its own mod (Airline, Assistant). Integrating a third-party mod is not a reason:
  that mod being installed is already the switch. Integrations are optional dependencies; Core checks versions at
  startup and disables both halves of an unsupported one.

### Facilities

- A **Control** describes where an action happens and what it can do. A **Signal** sets a value,
  and a **Port** transfers items, fluids or energy. Controls carry no ownership or facility semantics.
- A **Facility** owns everything in its extent and gives its controls meaning. An extent comprises
  blocks, boxes and entities, including blocks and boxes in a moving structure's local coordinates.
  A colony store is a **Container** facility. Storage controllers extend the stances of the individual
  stores they proxy. A workshop is a projection of a facility.
- A **Facility** exposes its parameters, routines and readings through `Operating`. Its declarative `Card`
  serves the Colony Book and other controls. Held zones, paths and blocks, build orders, trains, networks and
  colony settings scopes all use this interface. A holding kind adds controls and status to the card of a holding.
- A `FacilityDefinition` can describe controls
  (`Rig`), an extent, triggers and projections. Built-in facilities can come from blocks or loaded entities.
  Players can define their own facilities through the assistant, using Starlark to express the intended behavior.
  Standard modules and the primitive library routines (`write_all`, `write_stages`, `write_sequence`, `move_to`,
  `deposit`) are optional helpers; gameplay decisions such as what to load and when to fire live in the script.
- Shell adapters name parts after native elements. Carried controls move with their carrier's reading, and staged
  writes sequence changes across controls. Local controls need a resident at their stance. Remote controls can
  support direct runs without resident work.
- A **Post** covers signals and ports in reach of its footing or seat, plus named controls and any additions
  supplied by adapters. Facilities and integrations contribute posts to `Posts`. Standing demand and requests can
  offer planned duty, while integrations can use urges for assigned crew. The generic duty handles arrival, native
  boarding, presence and leases. The runtime uses the occupant to write signals
  and move items between covered ports or the occupant's pack. Ordinary plan work supplies distant stock before
  the direct transfer. Timed sequences can mix transfers and writes. They can also run as one resident's
  continuous work if all steps share a stance.
- Cards include parameter fields, routine buttons and readouts. Facilities can also offer target picks and
  path overlays tied to their routines or readings.
- A **Routine** defines a run; the planner assigns any resident work it expands into a plan subgraph.
  After a failure the caller resubmits and the routine replans from fresh readings, since facility state is the world.
- **Triggers** are manual, standing, on demand (pulled through a projected `Workshop`), condition or schedule.
  **Projections** contribute hops, keep-outs, closures, obstacles, workshops, protection and lanes;
  they are derived data rebuilt from the world and facility data. Lanes come only from facilities, never from block layout.
  Gates and card actions accept reading comparisons or Starlark predicates. Gates publish walking links from
  the cached predicate result, and ride projections publish links between their stops while `available_when`
  holds, which takes the same predicates. Keep-outs can come from moving parts or a script. Control writes belong
  to routine runs.
- Player-defined facility workshops use Starlark routines for recipes and action batches. When the planner chooses
  a workshop, Core reserves the recipe inputs for the run. Any work produced by a script step draws from that
  reservation when its needs match. Individual actions can attach failure handlers. Scripts can filter native
  recipes with `recipes_of`, or the definition can provide a fixed batch list.
- Script readings derive values from probes and parameters. Routines can publish readings and paths and
  check arguments and readiness. `@std//position` is an optional helper for calibrated axes built on `@std//ik`, and
  `@std//ballistics` lays, loads and fires a gun from its aim part's `ballistics` reading. Keep engine arithmetic
  that Starlark would run too slowly, such as flight solving, in Java builtins, and pass world data the script needs
  as readings or snapshots taken on the main thread. A routine that publishes an `engagement` reading takes part in
  colony fire control.
- On the main thread, take a snapshot at most once every 10 ticks per sample. Evaluate Starlark on a worker
  within step, time and memory budgets, then cache the result on a later tick. A fault pauses the sample and appears
  in the latest result.

### Code and test ownership

- Match the surrounding naming, comment density and idiom.
- Use no tabs or trailing whitespace; `./gradlew check` checks scaffold formatting.
- Put mod test sources in `tests/gametest/<mod>/<set>/java` and their resources in
  `tests/gametest/<mod>/<set>/resources`. Use the mod that owns the behavior.
  Core fixtures that use Vanilla residents or gameplay can live in Vanilla's matching
  directory. Vanilla compiles the cross-mod fixtures and the launcher loads them with
  Core for package-private access. Put reusable setup in the `gameTestFixtures` set.
- Assistant owns the tool registries under `assistant/tool`, including permission checks,
  previews, confirmation tokens and the assistant's Starlark bindings. It reads domain data
  and invokes operations through Core interfaces. Core owns the Starlark runtime and
  the facility and card APIs.
- Shared TypeScript helpers belong in `tests/shared/`. A suite can import from
  there and `tests/ldlib2/`, but not from another suite.
- `tests/promo/` holds footage takes for promotional videos. They are kept for reference only and are not
  maintained alongside gameplay changes: they are not part of validation, and a broken take does not block a change.

Container allowlists, denylists and `StoreRule.Access.store` govern ordinary resident storage only.
Directed orders bypass them in planning and execution, and never prevent configuration changes.
Keep native container and slot restrictions intact; player insertion is unaffected.

### Translations and patterns

- Add mod-owned player-facing strings to all four catalogs of the owning mod, under
  `mods/<dir>/src/main/resources/assets/<namespace>/lang/`: `en_us.json`, `zh_cn.json`,
  `zh_hk.json` (Hong Kong) and `zh_tw.json` (Taiwan). Use each region's Minecraft
  terminology, rather than converting characters alone.
- Keep translated text as a `Component` until it reaches the client. Use
  `Pages.text` / `Pages.readText` for UI sync and `Line.text` for information cards.
  Calling `getString()` on the server loses the player's language.
- Arguments use `%s`, or indexed `%1$s` when reordered; a literal percent uses `%%`.
- Pattern-owned titles, controls, materials and refusal messages belong in the
  pattern's `lang` dictionary. Built-in patterns and shared libraries provide all
  four locales; matching mod catalog entries are not needed.
- Add an optional `marking` entry to each locale in `lang` for the marking
  instructions shown by the assistant in `patterns()` under `anchors`.
- Custom patterns fall back per word to another Chinese locale, then `en_us`.
  `zh_hk` and `zh_tw` prefer each other before `zh_cn`.
- Use `reject("error.key", ...)` for expected refusals, with at most four text
  or numeric arguments. Both `reject` and `translated("another.key")` resolve
  pattern and shared library words. The pattern's own words override library
  words for the same locale and key. Use `translated` for arguments such as
  material names, and `fail(...)` for script diagnostics in custom patterns.

### Documentation

Update English and Chinese pages together. Use the game's locale catalogs for UI
names and keep commands, paths and Java symbols literal. Documentation stays at the
overview level: the READMEs, contribution guides, integrations page, data pack guide and save compatibility rules. Do not
add detailed reference pages or per-module READMEs. `THIRD_PARTY.md` is a license
inventory and `AGENTS.md` an index for coding agents; both stay in English only.
