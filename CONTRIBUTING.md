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

The build gathers the ten mod jars in `build/libs/` and runtime LDLib2 in `build/deps/`.
Each mod is a Gradle project under `mods/`; see [Mods](README.md#mods) for what each one does.
Squad grouping and direct commands belong to Core’s **RTS** mode (Colony Book → **V** → **RTS**).
Keep command fixtures on the RTS APIs; equipment assignment uses the **Equipment profiles** page.

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
| `cookeryTests`, `modularGolemsTests`, `cartsTests`, `epicfightTests`, `chiselsTests` | The native mod and its extension's fixtures; `cookeryTests` also adds Core and Vanilla Cookery fixtures | On by default when `gameTestMod` is `cookery`, `golems`, `carts`, `epicfight` or `carving` respectively |
| `taczTests`, `warfareTests` | TACZ or Superb Warfare fixtures (Firearms) | Off by default |
| `firefightingTests` | FireFighting Additions fixtures (Create) | Off by default |
| `tavernTests` | Kaleidoscope Tavern fixtures (Cookery) | Requires `cookeryTests` |
| `createPlacementTests` | Create placement fixtures (Carving) | Requires `gameTestMod=carving` |
| `storageProfile` | One storage mod: `sophisticatedStorage`, `sophisticatedBackpacks`, `drawers`, `functional`, `createStorage`, `toms`, `colossal` or `qio` | Core only; AE2, RS2 and storage profiles are mutually exclusive |
| `ae2Tests`, `rs2Tests` | Applied Energistics 2 or Refined Storage 2 fixtures | Core only; mutually exclusive with each other and storage profiles |
| `storageLaborTests`, `ae2LaborTests`, `rs2LaborTests` | Resident labour tests on top of the native port tests | Require the matching profile or suite, and Vanilla |
| `storageVanilla`, `ae2Vanilla`, `rs2Vanilla` | `false` runs the native ports without Folkways Vanilla | Not with labour tests |
| `accessoryProfile` | `curios` or `accessories`, with Sophisticated Backpacks | Core only; does not load the base Core suite |
| `accessoryCoexistence`, `accessoryCompat` | Adds genuine Accessories beside Curios, or the old cclayer beside Accessories | Enabled by being set, whatever the value; pair with `accessoryProfile=curios` or `accessories` |
| `coreGameTestClasses`, `vanillaGameTestClasses` | Compile and register only the listed GameTest classes (comma-separated) | — |
| `gameTestFilter` | Case-insensitive full-name regex for Airline tests (method names without class prefixes) | Applied before sharding, together with `gameTestMethods` |
| `gameTestShard` | Airline batch shard `i/n`, with zero-based `i` | Requires `0 <= i < n`; filters run first |
| `gameTestMethods` | Rerun only the listed methods of fixtures that support it | — |
| `gameTestNamespaces`, `gameTestDirectory` | Override the enabled namespaces or the run directory | Each switch combination otherwise gets its own world under `run/gametest/` |

Run `npm run setup` once to download the pinned fixtures, then keep Gradle `--offline`. Native-mod suites
have matching Blockwright profiles in [`blockwright.toml`](blockwright.toml) (`ae2`, `rs2`, `storage_<name>`,
`curios`, `accessories`). Run GameTest profiles serially to keep memory usage bounded.

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

### Code and test ownership

- Match the surrounding naming, comment density and idiom.
- Use no tabs or trailing whitespace; `./gradlew check` checks scaffold formatting.
- Keep GameTests in the module owning the behavior, including package-private
  scenarios. Shared setup belongs in Core's `src/gameTestFixtures`, without test
  registration.
- Shared TypeScript helpers belong in `tests/shared/`. A suite can import from
  there and `tests/ldlib2/`, but not from another suite.

Container whitelists, blacklists and `StoreRule.Access.store` govern ordinary resident storage only.
Directed orders bypass them in planning and execution, and never prevent configuration changes.
Keep native container and slot restrictions intact; player insertion is unaffected.

### Translations and patterns

- Add mod-owned player-facing strings to all four shared catalogs in
  `mods/core/src/main/resources/assets/folkways_core/lang/`: `en_us.json`, `zh_cn.json`,
  `zh_hk.json` (Hong Kong) and `zh_tw.json` (Taiwan). Use each region's Minecraft
  terminology, rather than converting characters alone.
- Keep translated text as a `Component` until it reaches the client. Use
  `Pages.text` / `Pages.readText` for UI sync and `Line.text` for information cards.
  Calling `getString()` on the server loses the player's language.
- Arguments use `%s`, or indexed `%1$s` when reordered; a literal percent uses `%%`.
- Pattern-owned titles, controls, materials and refusal messages belong in the
  pattern's `lang` dictionary. Built-in patterns and shared libraries provide all
  four locales; matching mod catalog entries are not needed.
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
