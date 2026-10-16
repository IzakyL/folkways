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
./gradlew build  # the mod jar, in build/libs/
```

`npm run setup` must run before the first Gradle build. The build compiles against the
optional integrations (Create, JEI, EMI, GeckoLib, Modular Golems, Sable, FTB Teams) from the jars it
downloads, as pinned in `blockwright.toml`.

## Layout

| Path | What lives there |
| --- | --- |
| `src/main/java/.../core` | The colony engine: residents, planning, labour and travel. `core/api` is the public API |
| `src/main/java/.../front` | Screens, the colony book, networking and lessons. `front/api` is the public API |
| `src/main/java/.../plugins` | Each feature (farming, pasture, build, rail, …) as a plugin over the two APIs |
| `src/main/resources/data/folkways/folkways/pattern` | Built-in building patterns (Starlark) |
| `src/gametest` | NeoForge game tests |
| `tests/` | Blockwright and Playwright suites, listed below |

## Tests

| Command | What it runs |
| --- | --- |
| `npm run test:unit` | Fast Node tests for the TypeScript helpers; no game |
| `npm run test:i18n` | Language keys, placeholders and notice/refusal coverage; no game or dependencies |
| `npm run typecheck` | Type checks every TypeScript suite |
| `./gradlew check` | Compiles everything and lints for tabs and trailing whitespace |
| `./gradlew runGameTestServer` | NeoForge game tests in `src/gametest` |
| `./gradlew -PcookeryTests=true runGameTestServer` | All game tests, including the real optional Kaleidoscope Cookery mod |
| `npm run test:e2e` | End-to-end tests against a real client and server |
| `npm run visual:take` | Screenshot suites for screens and in-world visuals |
| `npm run bench:take` | Performance benchmarks on a large colony |
| `npm run promo:take` | Films the promotional takes |
| `npm run fuzz:run` | Planner, travel and build fuzzers on a headless server |

The game suites download Minecraft, NeoForge and the fixture mods on first run, and their
test servers accept the [Minecraft EULA](https://www.minecraft.net/eula) through
`blockwright.toml`. Run them only if you accept it. Use `--workers=1` to limit resource
use, for example:

```sh
npm run test:e2e -- tests/e2e/founding-smoke.spec.ts --workers=1
```

The `cookery` and `cookery-sable` Blockwright profiles install Kaleidoscope Cookery
1.6.0 on both sides. Run `tests/e2e/cookery-blackbox.spec.ts` to exercise the colony
book, production orders, chopping-board byproducts, carrier shortages, repeated
stockpot batches and a board-to-stockpot recipe chain on the ground and a Sable ship.
The default profiles and game tests omit Cookery to also verify that it remains optional.

The expanded kitchen suites are `cookery-complete.spec.ts` (one final order from
base materials), `cookery-millstone.spec.ts` (interruption and safe exit),
`cookery-tools.spec.ts` (one borrowed tool and explicit extra stock),
`cookery-farming.spec.ts` (flooded dirt through real tilling, rice harvest and replanting,
plus the compost-to-grass seed loop), and
`cookery-ponder.spec.ts` (optional loading, real book entries and every lesson's
keyframes). The production suites run both on the ground and aboard Sable. Set
`FOLKWAYS_PONDER_LANGUAGE=zh_cn` (or `zh_hk`/`zh_tw`) to capture the lessons in
that player's language. `creative-supplies.spec.ts` also runs without Cookery.

The optional game tests audit all 612 pinned native recipe resources against the
running recipe manager and execute the native production paths. They write
`run/gametest/cookery-recipe-audit.json`. Generate the final-dish graph with:

```sh
node tests/shared/cookery/recipe-graph.mjs run/gametest/cookery-recipe-audit.json tests/out/cookery-recipe-graph.json
```

The graph includes served platters and drinks without a food component, and lists
native unmatched/burnt results separately. Its base ingredients, containers, tools
and fuel boundaries are explicit; it does not use reverse-packing cycles as food
sources. A graph result complements actual final-order client tests.

Each suite is a directory under `tests/` (`e2e`, `visual`, `bench`, `promo`, `fuzz`) with
its own `playwright.config.ts` and `tsconfig.json`. Code more than one suite uses lives in
`tests/shared/`; a suite imports from there and from `tests/ldlib2/`, never from another
suite. Everything the suites write goes to `tests/out/`, which git ignores.

An e2e spec about a colony's blocks runs twice, on the ground and on the deck of a
[Sable](https://modrinth.com/mod/sable) ship (`for (const stage of twins()) test(stage.title(...), ...)`,
from `tests/shared/harness/stage.ts`). Write it in plan coordinates, lay the floor with
`stage.lay`, and cross into the game through the stage: `stage.at(cell)` for a block a command or
read addresses, `stage.world(cell)` for where it shows, `stage.stand(at)` to set the player down. The
`sable-*` specs cover what only a ship has.

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
