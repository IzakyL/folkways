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
./gradlew build  # the four mod jars, in build/libs/
```

`npm run setup` must run before the first Gradle build. The build compiles against the
optional integrations (Create, JEI, EMI, GeckoLib, Modular Golems, Sable, FTB Teams) from the jars it
downloads, as pinned in `blockwright.toml`.

## Layout

Folkways is four mods, each a Gradle project under `mods/`, all registering under the `folkways` namespace.
`./gradlew build` builds them and gathers their jars into `build/libs/`.

| Mod | Project | What it is |
| --- | --- | --- |
| Folkways Core (`folkways_core`) | `mods/core` | The engine and the screens; on its own it gives residents no work |
| Folkways Vanilla (`folkways_vanilla`) | `mods/vanilla` | The work residents do among vanilla blocks |
| Folkways × Create (`folkways_create`) | `mods/create` | What Create adds: rail, dispatch, and Create's side of building (track joints, schematic hand-off) |
| Folkways × Modular Golems (`folkways_modulargolems`) | `mods/modulargolems` | Modular Golems as a second sort of resident |

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
| `mods/vanilla/src/main/resources/data/folkways/folkways/pattern` | Built-in building patterns (Starlark) |
| `mods/*/src/gametest` | NeoForge game tests, run together by the root's `runGameTestServer` |
| `tests/` | Blockwright and Playwright suites, listed below |

## Tests

| Command | What it runs |
| --- | --- |
| `npm run test:unit` | Fast Node tests for the TypeScript helpers; no game |
| `npm run test:i18n` | Language keys, placeholders and notice/refusal coverage; no game or dependencies |
| `npm run typecheck` | Type checks every TypeScript suite |
| `./gradlew check` | Compiles everything and lints for tabs and trailing whitespace |
| `./gradlew runGameTestServer` | NeoForge game tests in every `mods/*/src/gametest`, with all mods but Modular Golems loaded |
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
- Every player-facing string goes in all four catalogs under `assets/folkways/lang/`:
  `en_us.json`, `zh_cn.json`, `zh_hk.json` (Hong Kong) and `zh_tw.json` (Taiwan).
  Use each region's Minecraft terminology, not just converted characters.
- Keep translated text as a `Component` until it reaches the client. Use `Pages.text`
  / `Pages.readText` for UI sync and `Line.text` for information cards; resolving a name
  with `getString()` on the server loses the player's language. Translation arguments
  use `%s` (or indexed `%1$s` when reordered), and a literal percent uses `%%`.
- Run `npm run test:i18n` after editing translations. It also runs in `test:unit` in CI;
  it checks catalog keys and arguments, direct translation calls, notice/refusal enums,
  and the built-in patterns' dictionaries and errors, not every computed Java key.
- Built-in patterns also translate their `lang` dictionaries in all four locales.
  Custom patterns fall back per word to another Chinese locale, then to `en_us`;
  `zh_hk` and `zh_tw` prefer one another before `zh_cn`.
- A pattern's expected refusal uses `reject("translation.key", ...)`, with at most four
  text or numeric arguments. Use `translated("another.key")` for an argument that
  also needs translation. Keep ordinary `fail(...)` for script diagnostics in custom
  patterns; built-in player-facing failures belong in the language catalogs.
- Commit messages follow `type(scope): summary`, for example
  `fix(plan): keep rations out of cargo` or `feat(draft): add a dock pattern`.
- Add or update a test for behavioural changes. Prefer a black-box e2e spec for anything
  a player can see.

## Reporting bugs

Include the Minecraft, NeoForge and Folkways versions, your mod list, and `latest.log`
(and the crash report, if there is one).
