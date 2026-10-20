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

The build gathers five mod jars in `build/libs/` and runtime LDLib2 in `build/deps/`.
See [Architecture](docs/en/architecture.md) for module boundaries and the source map.

For Windows, prepare a compatible source-built SDK using Blockwright's
[local build instructions](https://github.com/IzakyL/blockwright/blob/master/CONTRIBUTING.md)
and [Windows requirements](https://github.com/IzakyL/blockwright/blob/master/docs/environment.md#windows-development-candidate).
Client tests require a logged-in Windows 11 x64 desktop with an OpenGL-capable GPU;
use display device `auto` or `gpu`. After preparing `.blockwright/mods.json` with
that SDK, build with `gradlew.bat`. Record the SDK revision and platform when
reporting results; local Windows tests and Linux CI are separate validation runs.

In local Git checkouts, `npm ci` also installs the Husky pre-commit hook.

## Validation

Start with checks that do not launch Minecraft:

```sh
npm run format:md:check
npm run test:unit
npm run typecheck
./gradlew check
```

Add or update tests for behavioral changes. Prefer a black-box e2e spec for
behavior a player can observe. Choose the relevant mod, environment and client
suite from the [testing guide](docs/en/testing.md), including its EULA and platform
requirements.

CI runs the build checks, per-mod GameTests and a focused real-client founding
smoke test. It does not run every client or capture suite. The testing guide
documents the exact matrix, selection flags and failure artifacts.

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

### Code and test ownership

- Match the surrounding naming, comment density and idiom.
- Use no tabs or trailing whitespace; `./gradlew check` checks scaffold formatting.
- Keep GameTests in the module owning the behavior, including package-private
  scenarios. Shared setup belongs in Core's `src/gameTestFixtures`, without test
  registration. See [test ownership](docs/en/architecture.md#lifecycle-and-test-ownership).
- Shared TypeScript helpers belong in `tests/shared/`. A suite can import from
  there and `tests/ldlib2/`, but not from another suite.

### Translations and patterns

- Add mod-owned player-facing strings to all four shared catalogs in
  `mods/core/src/main/resources/assets/folkways/lang/`: `en_us.json`, `zh_cn.json`,
  `zh_hk.json` (Hong Kong) and `zh_tw.json` (Taiwan). Use each region's Minecraft
  terminology, rather than converting characters alone.
- Keep translated text as a `Component` until it reaches the client. Use
  `Pages.text` / `Pages.readText` for UI sync and `Line.text` for information cards.
  Calling `getString()` on the server loses the player's language.
- Arguments use `%s`, or indexed `%1$s` when reordered; a literal percent uses `%%`.
- Run `npm run test:i18n` after translation changes. It checks keys, arguments,
  direct translation calls, notice/refusal enums and built-in pattern dictionaries
  and errors, but not every computed Java key.
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

Run `npm run format:md` before staging Markdown changes. Every commit checks all
project Markdown and its staged copies; formatting failures block the commit.
CI also runs `npm run format:md:check`.

Update English and Chinese pages together. Use the game's locale catalogs for UI
names and keep commands, paths and Java symbols literal. Put detailed gameplay,
content, testing and architecture material under `docs/en/` and `docs/zh-CN/`.
See the [documentation index](docs/README.md) for the layout and maintenance notes.

| Command | What it runs |
| --- | --- |
| `npm run test:unit` | Fast Node tests for the TypeScript helpers; no game |
| `npm run test:i18n` | Language keys, placeholders and notice/refusal coverage; no game or dependencies |
| `npm run typecheck` | Type checks every TypeScript suite |
| `./gradlew check` | Compiles everything and lints for tabs and trailing whitespace |
| `./gradlew runGameTestServer` | NeoForge game tests in every `mods/*/src/gametest`, with the base mods but not Modular Golems or Cookery loaded |
| `./gradlew -PcookeryTests=true runGameTestServer` | Also loads Cookery and runs its native recipe and lifecycle tests |
| `./gradlew -PgameTestMod=core runGameTestServer` | Only Core-owned tests in the base environment; also accepts `vanilla` or `create` |
| `./gradlew -PgameTestMod=core -PcookeryTests=true runGameTestServer` | Core-owned tests with Cookery installed, including `core/src/cookeryTest`; also accepts `vanilla` |
| `./gradlew -PgameTestMod=cookery -PcookeryTests=true runGameTestServer` | Only Cookery-owned tests, with Cookery installed |
| `npm run test:e2e` | End-to-end tests against a real client and server, on the ground |
| `npm run test:sable` | The same against a [Sable](https://modrinth.com/mod/sable) ship: each twin spec's aboard test, and the ship-only specs |
| `npm run visual:take` | Screenshots of screens, panels, looks and in-world visuals |
| `npm run ponder:take` | Films every ponder scene in the colony book |
| `npm run pattern:take` | Lays out each work pattern's gallery and shoots it |
| `npm run bench:take` | Performance benchmarks on a large colony |
| `npm run promo:take` | Films the promotional takes |
| `npm run promo:cookery` | Films one 50-second, real-time shot of the downstairs kitchen, Create elevator and upstairs dining room |
| `npm run promo:cookery:film` | Copies the complete original recording to `folkways-cookery-raw.mp4` and writes model credits |

Run `npm run setup` before filming. The scene and recording are defined by
`tests/promo/cookery.take.ts` and `tests/promo/cookery-elevator.ts`: a native kitchen
downstairs, an open dining room upstairs, and a native Create elevator as their only
vertical route. Residents walk into the cabin, stand on its moving floor, and walk
out at the destination to reach their meal. The cabin has no seat cushions or rider
mounts. Its capacity is discovered from usable standing places; evidence records
both total capacity and free places rather than assuming two passengers. Verified
cabin occupants with active journeys are observed separately from nearby residents
on the landings and residents seated at dining tables.

The elevator's `places`, `freePlaces` and `placeOf` describe standing room, replacing
the earlier `seats`, `freeSeats` and `seatOf` model. They do not refer to native Create
seat slots. Boarding, alighting and cancellation use ordinary movement and physical
support on the cabin floor; cancellation does not teleport a resident to a landing.
Dining chairs retain their native seated interaction.

Passengers wait away from the doorway, then follow a path across the stopped cabin's
actual blocks to their reserved standing places. The dispatcher waits for complete
boarding, a clear entrance, and complete alighting before the next journey. Exit
selection follows the onward route, including when another nearby ledge is closer.
Route estimates use immutable queue snapshots with physical occupancy, reserved
capacity, same-destination batching and the passenger's expected arrival time;
asking for an estimate does not call the elevator or reserve a place.

The continuous, 50-second real-time take is saved as
`tests/out/promo/takes/cookery/01-kitchen-dining.mp4`, alongside production and elevator
journey evidence. `npm run promo:cookery:film` copies that file byte for byte to
`tests/out/promo/film/folkways-cookery-raw.mp4`. The final recording keeps the source's
1080p60 video, original audio, complete duration and metadata. Export performs no
trimming, transcoding, captions, music mixing, gradients or fades.
The script stages the set, residents and dining food: the upstairs tables are supplied
with Dongpo pork (`dongpo_pork`), while stock targets maintain 24 plates of steamed
buns (`baozi_plate`), 12 pepper-pork stir-fries and eight bowls of pork-bone soup through
the planner, real recipes and six working appliances in the compact downstairs kitchen.
The pantry supplies ingredients, oil, bowls and pot lids. Chairs face the camera from
behind the dining tables, and the closer elevated camera exposes the food and work
surfaces. A ninth resident, admitted after rehearsal, performs real harvesting,
replanting and storage work in two courtyard fields, staged by
`tests/promo/cookery-work.ts`. Kitchen work and table replenishment are warmed up before
recording. Ordinary colony errands determine the elevator calls and journeys.
The film observes the kitchen, elevator and dining room together in one uninterrupted
shot at normal speed; it does not demonstrate a complete raw-ingredient chain.
The set uses pale plaster, dark timber frames and weathered copper roofs around a
landscaped inn courtyard. The former standalone elevator promo is replaced by this
combined take. Wine Fox cast models retain their source and author metadata in
`folkways-cookery-elevator-credits.txt` beside the raw export. Credits are a separate
text file and are not overlaid on the recording.

The game suites download Minecraft, NeoForge and the fixture mods on first run, and their
test servers accept the [Minecraft EULA](https://www.minecraft.net/eula) through
`blockwright.toml`. Run them only if you accept it. Use `--workers=1` to limit resource
use, for example:

```sh
npm run test:e2e -- tests/e2e/founding-smoke.spec.ts --workers=1
```

GameTest ownership is separate from the installed mods: selecting `-PgameTestMod=core`
runs only Core's tests, but still loads the Vanilla residents and other integrations needed
by those scenarios. Shared helpers live in Core's `src/gameTestFixtures` source set, so a mod can reuse
a helper without discovering another mod's tests. Keep tests in the mod that owns the
behavior, including tests that need package-private internals; use fixtures for shared setup.

CI has one GameTest check per tested mod: Core, Vanilla, Create and Cookery. Core and Vanilla
each run twice, with Cookery absent and present, preserving optional-integration coverage;
Create runs in the base environment and Cookery runs with Cookery installed. Core also retains
the existing LDLib2 dependency tests; the other mod checks do not repeat them. Both environments
are attempted even if the first fails, and either failure fails that mod's check. Modular
Golems has no GameTests yet, so it does not get an empty check. The nightly release requires
all four checks and the real-client smoke check to pass.

Each server invocation has a 50-minute process timeout; a mod's CI job has a 110-minute
limit to allow both environments. Selected runs use `run/gametest/<mod>/<base|cookery>/`
for their isolated game directories. Without `gameTestMod`, the combined run still uses
`run/gametest/`. For a direct Gradle run, accept the Minecraft EULA in the selected game
directory's `eula.txt` before starting the server.

CI uploads console logs, game logs, crash reports, and a JUnit suite-level result even when
tests fail. Console logs and JUnit files are written immediately after each invocation under
`run/gametest/ci/<mod>-<profile>.*`. The JUnit case records whether that GameTestServer
invocation passed; detailed individual GameTest outcomes remain in the uploaded server logs.

Each suite is a directory under `tests/` (`e2e`, `sable`, `visual`, `ponder`, `pattern`, `bench`, `promo`) with
its own `playwright.config.ts` and `tsconfig.json`. Code more than one suite uses lives in
`tests/shared/`; a suite imports from there and from `tests/ldlib2/`, never from another
suite. Everything the suites write goes to `tests/out/`, which git ignores.

An e2e spec about a colony's blocks runs twice, on the ground in `test:e2e` and on the deck of a
Sable ship in `test:sable` (`for (const stage of twins()) test(stage.title(...), ...)`,
from `tests/shared/harness/stage.ts`). Write it in plan coordinates, lay the floor with
`stage.lay`, and cross into the game through the stage: `stage.at(cell)` for a block a command or
read addresses, `stage.world(cell)` for where it shows, `stage.stand(at)` to set the player down. The
specs in `tests/sable/` cover what only a ship has.

The repository's `.mcp.json` exposes the same harness to MCP-capable assistants, with
the LDLib2 UI adapter in `tests/ldlib2/`.

## Conventions

- Match the surrounding code: its naming, comment density and idiom.
- No tabs and no trailing whitespace. `./gradlew check` enforces this.
- Mod-owned player-facing strings go in all four catalogs under `assets/folkways/lang/`:
  `en_us.json`, `zh_cn.json`, `zh_hk.json` (Hong Kong) and `zh_tw.json` (Taiwan).
  Pattern-owned strings belong in the pattern's `lang` dictionary. Use each region's
  Minecraft terminology, not just converted characters.
- Keep translated text as a `Component` until it reaches the client. Use `Pages.text`
  / `Pages.readText` for UI sync and `Line.text` for information cards; resolving a name
  with `getString()` on the server loses the player's language. Translation arguments
  use `%s` (or indexed `%1$s` when reordered), and a literal percent uses `%%`.
- Run `npm run test:i18n` after editing translations. It also runs in `test:unit` in CI;
  it checks catalog keys and arguments, direct translation calls, notice/refusal enums,
  and the built-in patterns' dictionaries and errors, not every computed Java key.
- Built-in patterns and shared pattern libraries translate their `lang` dictionaries in all four locales.
  These dictionaries own their titles, control labels, material names and refusal messages;
  they do not need matching entries in the mod's language catalogs.
  Custom patterns fall back per word to another Chinese locale, then to `en_us`;
  `zh_hk` and `zh_tw` prefer one another before `zh_cn`.
- A pattern's expected refusal uses `reject("error.key", ...)`, with at most four
  text or numeric arguments. Both `reject` and `translated("another.key")` resolve
  keys in the pattern's `lang` dictionary, including words brought by its shared libraries.
  The pattern's own words override library words for the same locale and key. Use `translated`
  for an argument such as a material name that also needs translation. Keep ordinary
  `fail(...)` for script diagnostics in custom patterns.
- Commit messages follow `type(scope): summary`, for example
  `fix(plan): keep rations out of cargo` or `feat(draft): add a dock pattern`.
- Add or update a test for behavioural changes. Prefer a black-box e2e spec for anything
  a player can see.

## Reporting bugs

Include the Minecraft, NeoForge and Folkways versions, your mod list, and `latest.log`
(and the crash report, if there is one).

The Cookery profiles load both `folkways_cookery` and Kaleidoscope Cookery 1.6.0.
`cookery-native` loads only the native mod to verify that the extension stays optional.
The complete, millstone, tools, farming, blackbox and Ponder e2e suites run native
production, interruptions, shared tool stock, flooded dirt through harvest/replanting,
and all thirteen lessons. `creative-supplies.spec.ts` also runs without Cookery.

Cookery game tests live in `mods/cookery/src/gametest`. The core and vanilla tests that
need their own package internals live in `mods/{core,vanilla}/src/cookeryTest` and join
their owning mod's run only with `-PcookeryTests=true`. The native audit covers all 612
pinned recipe resources and writes `cookery-recipe-audit.json` in the game directory
(`run/gametest/cookery/cookery/` for the selected Cookery run). Generate its graph with
`node tests/shared/cookery/recipe-graph.mjs run/gametest/cookery/cookery/cookery-recipe-audit.json tests/out/cookery-recipe-graph.json`.
