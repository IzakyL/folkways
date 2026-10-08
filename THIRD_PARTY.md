# Third-party components

## Bundled in the mod jar

These libraries are nested in the Folkways jar through NeoForge's Jar-in-Jar. Their
license texts ship in the jar under `META-INF/licenses/` and in this repository under
[`src/main/resources/META-INF/licenses/`](src/main/resources/META-INF/licenses/).

| Component | Version | License | Copyright | Source |
| --- | --- | --- | --- | --- |
| Starlark (Java) | 4.2.1 | Apache-2.0 | The Bazel Authors | [bazelbuild/bazel](https://github.com/bazelbuild/bazel), packaged as `com.eed3si9n.starlark:starlark` |
| Ponder | 1.0.82+mc1.21.1 | MIT | The Create Team | [Creators-of-Create/Ponder](https://github.com/Creators-of-Create/Ponder) |
| Flywheel | 1.0.6 | MIT | Jozufozu | [Engine-Room/Flywheel](https://github.com/Engine-Room/Flywheel) |

## Required or optional at runtime, not bundled

Players install these separately. Folkways only compiles against them.

| Component | License | Use |
| --- | --- | --- |
| LDLib2 | see upstream | Required: the UI toolkit for every Folkways screen |
| Create, Sable, Modular Golems, GeckoLib, JEI, EMI | see upstream | Optional integrations |

## Repository assets, not shipped in the mod

| Path | License | Notes |
| --- | --- | --- |
| `tests/e2e/assets/resident-models/` | CC0 | Yes Steve Model built-in models (Steve, Default Boy), used by end-to-end tests |
| `tests/promo/models/` | CC BY-NC-SA 4.0 | Wine Fox models from Yes Steve Model's built-in pack, used only to film promotional footage. Authors are credited in each folder's `ysm.json`; see [the folder's README](tests/promo/models/README.md) |
