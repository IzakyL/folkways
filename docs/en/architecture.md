# Architecture

[Documentation](README.md) · [简体中文](../zh-CN/architecture.md)

## Modules

Folkways has **two required mods and four optional extensions**, each a Gradle
project under `mods/`. They use separate mod IDs and share the `folkways`
content namespace. The root project runs tests and collects jars rather than
building a seventh mod.

| Gradle project                            | Mod ID                    | Responsibility                                                                         |
| ----------------------------------------- | ------------------------- | -------------------------------------------------------------------------------------- |
| `:core` → `mods/core`                     | `folkways_core`           | Colony engine, public APIs, screens and lessons; supplies no resident work on its own  |
| `:vanilla` → `mods/vanilla`               | `folkways_vanilla`        | Residents and their work among vanilla blocks                                          |
| `:create` → `mods/create`                 | `folkways_create`         | Rail, dispatch, elevators, track joints and schematic hand-off                         |
| `:modulargolems` → `mods/modulargolems`   | `folkways_modulargolems`  | Golems as residents                                                                    |
| `:cookery` → `mods/cookery`               | `folkways_cookery`        | Native Cookery farming, appliances, shared tools, Kaleidoscope Tavern wine and lessons |
| `:chiselsandbits` → `mods/chiselsandbits` | `folkways_chiselsandbits` | Chiseled templates, exact bit costs, schematic loading and Create registries           |

```mermaid
flowchart BT
    Vanilla --> Core
    Create --> Vanilla
    ModularGolems[Modular Golems] --> Vanilla
    Cookery --> Vanilla
    ChiselsAndBits --> Vanilla
```

Core handles integrations needed to read an existing world correctly, such as
Sable sub-levels and Create contraptions. Integrations that add work belong in
their own extension mod.

## Source map

Java package paths below are relative to
`src/main/java/io/github/izakyl/folkways/` within the module:

| Module / package                 | Contents                                                                                       |
| -------------------------------- | ---------------------------------------------------------------------------------------------- |
| Core / `base`                    | Declaration ledger, NBT persistence and mixin plugin base                                      |
| Core / `effect`                  | Resident stats, flags and modifiers; perks in `effect/perk`                                    |
| Core / `library`                 | Data pack and loose-file content library                                                       |
| Core / `core`                    | Residents, planning, labor and travel; public `core/api` and world integration in `core/shell` |
| Core / `front`                   | Screens, Colony Book, networking and lessons; public `front/api`                               |
| Vanilla / `plugins`              | Farming, pasture, building and other plugins over the public APIs                              |
| Create / `create`                | Rail, dispatch, `create/elevator` and `create/build`                                           |
| Modular Golems / `modulargolems` | Golem residents                                                                                |
| Cookery / `cookery`              | Native appliance and farming adapters, tools, `cookery/tavern` and lessons                     |

Built-in patterns live under
[`mods/vanilla/src/main/resources/data/folkways/folkways/pattern/`](../../mods/vanilla/src/main/resources/data/folkways/folkways/pattern/).
Integration dependencies and mod IDs are declared in each module's `build.gradle`
and `src/main/resources/META-INF/neoforge.mods.toml`.

## Display-text search

[`TextSearch`](../../mods/core/src/main/java/io/github/izakyl/folkways/front/api/ui/TextSearch.java)
is the shared display-text matching entry point for item and folder pickers,
Workforce and stock. Client setup installs the optional JEC provider when present.
Literal hits and ASCII-only misses avoid JEC calls. The provider shares JEC's
dictionary and settings, renewing the calling thread's spelling cache when those
settings change. JEC and its PinIn library are compile-only inputs, not bundled dependencies.

[`ItemQuery`](../../mods/core/src/main/java/io/github/izakyl/folkways/core/api/terms/ItemQuery.java)
accepts a `BiPredicate<String, String>` in its display-name search overload, with
the name first and the lower-case query term second. Only bare words consult
that matcher. Mod IDs, tags, whole item IDs and wildcard patterns retain their
registry semantics. Saved rules use the original overload without display names,
so client language and optional integrations cannot change server filtering.

## Lifecycle and test ownership

[`ColonyLifecycle`](../../mods/core/src/main/java/io/github/izakyl/folkways/core/api/colony/ColonyLifecycle.java)
provides relocation and closing hooks. Closing listeners use
[`Cleanup`](../../mods/core/src/main/java/io/github/izakyl/folkways/base/task/Cleanup.java)
so that an ordinary failure does not stop the remaining cleanup phases. Lifecycle
changes should retain this isolation; the regression scenarios live in
[`ClosingIsolationGameTests`](../../mods/core/src/gametest/java/io/github/izakyl/folkways/core/engine/colony/ClosingIsolationGameTests.java).

Tests belong to the module owning the behavior, including tests that need
package-private internals. Core's `src/gameTestFixtures` contains reusable helpers
without test registration. An integration can reuse them without discovering
Core's tests. Selecting a GameTest owner does not remove the other runtime mods
its scenarios need. See [Testing](testing.md) for selection flags and CI profiles.

[Chisels & Bits integration](chiselsandbits.md) describes payload ownership, save migration and the synchronous schematic bridge.
