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

The build gathers the ten mod jars in `build/libs/` and runtime LDLib2 in `build/deps/`.
Each mod is a Gradle project under `mods/`; see [Mods](README.md#mods) for what each one does.

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
  `mods/core/src/main/resources/assets/folkways/lang/`: `en_us.json`, `zh_cn.json`,
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
overview level: the READMEs and contribution guides. Do not add detailed reference pages.
