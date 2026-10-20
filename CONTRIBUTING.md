# Contributing

Thanks for helping with Folkways. Issues and pull requests are welcome at
<https://github.com/IzakyL/folkways>.

## Environment

- Java 21 JDK
- Node.js 22.14 or newer
- Linux x64 for the game tests. [Blockwright](https://github.com/IzakyL/blockwright),
  the harness that drives real Minecraft clients and servers, supports only Linux x64
  for now. Building the mod works anywhere Gradle does.

```sh
npm install      # Blockwright, Playwright and TypeScript
npm run setup    # downloads the integration mods and writes .blockwright/mods.json
./gradlew build  # the five mod jars, in build/libs/
```

`npm run setup` must run before the first Gradle build. The build compiles against the
optional integrations (Create, JEI, EMI, GeckoLib, Modular Golems, Sable, FTB Teams) from the jars it
downloads, as pinned in `blockwright.toml`.

## Layout

Folkways has four base mods and a Cookery extension, each a Gradle project under `mods/`, all registering under the `folkways` namespace.
`./gradlew build` builds them and gathers their jars into `build/libs/`.

| Mod | Project | What it is |
| --- | --- | --- |
| Folkways Core (`folkways_core`) | `mods/core` | The engine and the screens; on its own it gives residents no work |
| Folkways Vanilla (`folkways_vanilla`) | `mods/vanilla` | The work residents do among vanilla blocks |
| Folkways × Create (`folkways_create`) | `mods/create` | What Create adds: rail, dispatch, and Create's side of building (track joints, schematic hand-off) |
| Folkways × Modular Golems (`folkways_modulargolems`) | `mods/modulargolems` | Modular Golems as a second sort of resident |
| Folkways × Kaleidoscope Cookery (`folkways_cookery`) | `mods/cookery` | Native Cookery farming, appliances and shared kitchen tools |

An integration whose absence would leave the colony misreading a world that has that mod (Sable's sub-levels,
Create's contraptions in the way) belongs in the core; one that only adds something when its mod is there is a mod
of its own.

| Path | What lives there |
| --- | --- |
| `mods/core/.../base` | What every layer stands on: the declaration ledger, NBT persistence and the mixin plugin base |
| `mods/core/.../effect` | Effects on residents (stats, flags, modifiers), with perks as one source of them under `effect/perk` |
| `mods/core/.../library` | The resource library: data packs and loose files under `<gamedir>/folkways/` |
| `mods/core/.../core` | The colony engine: residents, planning, labour and travel. `core/api` is the public API; `core/shell` fits the engine to the world, including other mods' structures (Sable, Create) |
| `mods/core/.../front` | Screens, the colony book, networking and lessons. `front/api` is the public API |
| `mods/vanilla/.../plugins` | Each feature (farming, pasture, build, …) as a plugin over the two APIs |
| `mods/create/.../create` | Rail, dispatch and `create/build` |
| `mods/modulargolems/.../modulargolems` | Golems as residents |
| `mods/cookery/.../cookery` | Cookery appliances, farming adapters, tools and lessons |
| `mods/vanilla/src/main/resources/data/folkways/folkways/pattern` | Built-in building patterns (Starlark) |
| `mods/*/src/gametest` | NeoForge game tests, run together by the root's `runGameTestServer` |
| `tests/` | Blockwright and Playwright suites, listed below |

## Batched work

Core exposes the `BatchSession` protocol for work that advances through multiple steps. The
remaining-input ledger and `StepReceipt` record what is still owed and what a completed step
actually did. Pausing keeps the worker assigned; it does not release the worker for
reassignment. Recovery checks the real device and inventory before continuing. Vanilla
composting and the Cookery stockpot are the first integrations.

`StockCheck` describes a non-consuming inventory target. Known stock stays in its warehouse or
resident pack; only missing stock grows production, which must be put away before the check runs.
These claims neither promise free storage space nor appear as consumable `ReservedInput`s. The
implementation rechecks its inventory scope on the server thread. Checks use `Workload.Once`
without tool or clothing wrappers.

Compost discovery shares one inventory observation across its registered stations. Each native input still
rechecks current inventories and reservations; a full sixteen-seed batch can take sixteen fresh inventory scans.
There is no reliable inventory revision for every container provider, so execution does not cache that check.
Measure large-colony tick costs separately from the reduced warehouse trips before claiming a TPS improvement.

`Jobs` lives in Core's public `base/task` package. Virtual threads perform file I/O only;
CPU work stays bounded, and results return to the owning thread before game state changes.
The `jobsTest` suite runs as part of Gradle `check` and CI.

## Tests

| Command | What it runs |
| --- | --- |
| `npm run test:unit` | Fast Node tests for the TypeScript helpers; no game |
| `npm run test:i18n` | Language keys, placeholders and notice/refusal coverage; no game or dependencies |
| `npm run typecheck` | Type checks every TypeScript suite |
| `./gradlew check` | Compiles everything and lints for tabs and trailing whitespace |
| `./gradlew runGameTestServer` | NeoForge game tests in every `mods/*/src/gametest`, with the base mods but not Modular Golems or Cookery loaded |
| `./gradlew -PcookeryTests=true runGameTestServer` | Also loads Cookery and runs its native recipe and lifecycle fixtures |
| `npm run test:e2e` | End-to-end tests against a real client and server, on the ground |
| `npm run test:sable` | The same against a [Sable](https://modrinth.com/mod/sable) ship: each twin spec's aboard test, and the ship-only specs |
| `npm run visual:take` | Screenshots of screens, panels, looks and in-world visuals |
| `npm run ponder:take` | Films every ponder scene in the colony book |
| `npm run pattern:take` | Lays out each work pattern's gallery and shoots it |
| `npm run bench:take` | Performance benchmarks on a large colony |
| `npm run promo:take` | Films the promotional takes |

The game suites download Minecraft, NeoForge and the fixture mods on first run, and their
test servers accept the [Minecraft EULA](https://www.minecraft.net/eula) through
`blockwright.toml`. Run them only if you accept it. Use `--workers=1` to limit resource
use, for example:

```sh
npm run test:e2e -- tests/e2e/founding-smoke.spec.ts --workers=1
```

CI runs the base and Cookery GameTestServer profiles as separate required checks before publishing the nightly release.
Each check has a 50-minute process timeout and uploads its console log, game logs, crash reports, and a JUnit suite-level
result under `run/gametest/ci/` even when it fails. The JUnit case records whether that GameTestServer invocation passed;
the detailed individual GameTest outcomes remain in the uploaded server logs.

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
the same run only with `-PcookeryTests=true`. The native audit covers all 612 pinned
recipe resources and writes `run/gametest/cookery-recipe-audit.json`. Generate its graph
with `node tests/shared/cookery/recipe-graph.mjs run/gametest/cookery-recipe-audit.json tests/out/cookery-recipe-graph.json`.
