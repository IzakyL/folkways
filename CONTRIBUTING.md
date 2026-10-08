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
optional integrations (Create, JEI, EMI, GeckoLib, Modular Golems, Sable) from the jars it
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
| `npm run typecheck` | Type checks every TypeScript suite |
| `./gradlew check` | Compiles everything and lints for tabs and trailing whitespace |
| `./gradlew runGameTestServer` | NeoForge game tests in `src/gametest` |
| `npm run test:e2e` | End-to-end tests against a real client and server |
| `npm run visual:take` | Screenshot suites for screens and in-world visuals |
| `npm run bench:take` | Performance benchmarks on a large colony |
| `npm run promo:take` | Films the promotional takes |
| `npm run fuzz:run` | Planner, travel and build fuzzers on a headless server |
| `npm run test:all` | e2e, visual, bench and promo in a row |

The game suites download Minecraft, NeoForge and the fixture mods on first run, and their
test servers accept the [Minecraft EULA](https://www.minecraft.net/eula) through
`blockwright.toml`. Run them only if you accept it. Use `--workers=1` to limit resource
use, for example:

```sh
npm run test:e2e -- tests/e2e/founding-smoke.spec.ts --workers=1
```

The repository's `.mcp.json` exposes the same harness to MCP-capable assistants, with
the LDLib2 UI adapter in `tests/ldlib2/`.

## Conventions

- Match the surrounding code: its naming, comment density and idiom.
- No tabs and no trailing whitespace. `./gradlew check` enforces this.
- Every player-facing string goes in both `assets/folkways/lang/en_us.json` and
  `zh_cn.json`.
- Commit messages follow `type(scope): summary`, for example
  `fix(plan): keep rations out of cargo` or `feat(draft): add a dock pattern`.
- Add or update a test for behavioural changes. Prefer a black-box e2e spec for anything
  a player can see.

## Reporting bugs

Include the Minecraft, NeoForge and Folkways versions, your mod list, and `latest.log`
(and the crash report, if there is one).
