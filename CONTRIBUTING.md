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
| Blockwright SDK  | Linux x64 via npm; Windows 11 x64 via a source-built SDK     |

[Blockwright](https://github.com/IzakyL/blockwright) has native Windows support in
its source tree. This repository pins npm version `0.1.0-alpha.1`, published
before that implementation; it declares Linux x64 only, so `npm ci` rejects it on
Windows with `EBADPLATFORM`. This repository's CI uses Ubuntu 24.04. Run the
published-package setup on Linux x64 from the repository root:

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

The build gathers the nine mod jars in `build/libs/` and runtime LDLib2 in `build/deps/`.
Each mod is a Gradle project under `mods/`; see [Mods](README.md#mods) for what each one does.
Squad grouping and direct commands belong to Core’s **RTS** mode (Colony Book → **V** → **RTS**).
Keep command fixtures on the RTS APIs; equipment assignment uses the **Equipment profiles** page.

For Windows, prepare a compatible source-built SDK using Blockwright's
[local build instructions](https://github.com/IzakyL/blockwright/blob/master/CONTRIBUTING.md)
and [Windows requirements](https://github.com/IzakyL/blockwright/blob/master/docs/environment.md#windows-development-candidate).
Client tests require a logged-in Windows 11 x64 desktop with an OpenGL-capable GPU;
use display device `auto` or `gpu`. After preparing `.blockwright/mods.json` with
that SDK, build with `gradlew.bat`. Record the SDK revision and platform when
reporting results; local Windows tests and Linux CI are separate validation runs.

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

Run one mod's GameTests with `./gradlew --offline -PgameTestMod=<mod> runGameTestServer`, where
`<mod>` is a directory under `mods/`; an extension's target mod is loaded automatically.
Integrations inside a mod need their own switch: `-PtaczTests=true` or `-PwarfareTests=true`
for Firearms, `-PfirefightingTests=true` for Create, `-PtavernTests=true` for Cookery and
`-PcreatePlacementTests=true` for Carving.

Carts GameTests require their fixture switch: `./gradlew --offline -PgameTestMod=carts -PcartsTests=true runGameTestServer`.

Storage GameTests (one native mod per profile):

```sh
./gradlew --offline -PgameTestMod=core -PstorageProfile=sophisticatedStorage runGameTestServer
./gradlew --offline -PgameTestMod=core -PstorageProfile=sophisticatedBackpacks runGameTestServer
./gradlew --offline -PgameTestMod=core -PstorageProfile=drawers runGameTestServer
./gradlew --offline -PgameTestMod=core -PstorageProfile=functional runGameTestServer
./gradlew --offline -PgameTestMod=core -PstorageProfile=createStorage runGameTestServer
./gradlew --offline -PgameTestMod=core -PstorageProfile=toms runGameTestServer
./gradlew --offline -PgameTestMod=core -PstorageProfile=colossal runGameTestServer
./gradlew --offline -PgameTestMod=core -PstorageProfile=qio runGameTestServer
```

Add `-PstorageLaborTests=true` to include resident labour tests; omit it for native ports only.
Add `-PstorageVanilla=false` to verify native ports without Folkways Vanilla (incompatible with resident tests).
The corresponding Blockwright fixture profiles are `storage_sophisticated`, `storage_backpacks`,
`storage_drawers` and `storage_functional`.

The new profiles pin Create: Storage (ausmez) 1.3.8 + Create 6.0.10, Tom’s Simple Storage 2.4.2,
Colossal Chests 1.9.1 + CyclopsCore 1.30.1, and Mekanism 10.7.19.85 respectively.
Their Blockwright fixture profiles are `storage_createStorage`, `storage_toms`, `storage_colossal` and `storage_qio`.
Use `npm run setup` once to resolve the pinned downloads, then `--offline` for Gradle.
Each profile has its own source set (`createStorageTest`, `tomsTest`, `colossalTest`, `qioTest`);
`-PstorageLaborTests=true` adds `portsLaborTest` and tests resident take/put at each supported access kind.
The Create controller supports hand insertion only. Colossal walls use the existing point-claim ledger;
QIO dashboards reuse the take/put block tags and provide no crafting. QIO access and all drives must
be in one dimension, because Core reserves inventory independently in each dimension.

```sh
./gradlew --offline :core:compileJava :core:compileCreateStorageTestJava :core:compileTomsTestJava :core:compileColossalTestJava :core:compileQioTestJava :core:compilePortsLaborTestJava
```

AE2 is also a Core optional suite:

```sh
./gradlew --offline -PgameTestMod=core -Pae2Tests=true runGameTestServer
./gradlew --offline -PgameTestMod=core -Pae2Tests=true -Pae2LaborTests=true runGameTestServer
./gradlew --offline -PgameTestMod=core -Pae2Tests=true -Pae2Vanilla=false runGameTestServer
```

Refined Storage 2 is another Core optional suite:

```sh
./gradlew --offline -PgameTestMod=core -Prs2Tests=true runGameTestServer
./gradlew --offline -PgameTestMod=core -Prs2Tests=true -Prs2LaborTests=true runGameTestServer
./gradlew --offline -PgameTestMod=core -Prs2Tests=true -Prs2Vanilla=false runGameTestServer
```

The `rs2` Blockwright profile installs Refined Storage 2.0.9, which has no external required mod dependencies.
Run GameTest profiles serially to keep memory usage bounded. RS2, AE2 and storage profiles are mutually exclusive.

The `ae2` Blockwright profile installs AE2 and GuideME alongside Core. Optional suites use only the
selected native mod and Core, plus Vanilla unless disabled. Every combination owns a separate world;
RS2, AE2 and storage profiles run separately. The default Core suite uses `folkways_core`; optional suites use
the same namespace and their own fixture source sets. Unadapted modded containers must implement vanilla
`Container` and join the `folkways_core:store/allowed` block tag to enroll as stores.

## Pull requests

1. Make the change in your fork on a focused branch.
2. Follow the conventions below and update the relevant tests and documentation.
3. Run the checks and game suites appropriate to the change.
4. Open a pull request against [IzakyL/folkways](https://github.com/IzakyL/folkways/pulls).
   Explain the problem, resulting behavior and verification. List checks you
   could not run and any remaining limitations.

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
overview level: the READMEs, contribution guides, integrations page and save compatibility rules. Do not
add detailed reference pages or per-module READMEs. `THIRD_PARTY.md` is a license
inventory and stays in English only.
