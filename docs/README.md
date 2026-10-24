# Folkways documentation

[English](en/README.md) · [简体中文](zh-CN/README.md)

Start with the [project README](../README.md) for installation and your first
colony, or the [contribution guide](../CONTRIBUTING.md) for development setup.

| Guide                                                                           | English                                | 简体中文                              |
| ------------------------------------------------------------------------------- | -------------------------------------- | ------------------------------------- |
| Colony management, storage, perks, food, housing, composting and resident looks | [Gameplay](en/gameplay.md)             | [玩法指南](zh-CN/gameplay.md)         |
| Data packs, local libraries and server settings                                 | [Custom content](en/custom-content.md) | [自定义内容](zh-CN/custom-content.md) |
| Local checks, GameTests, Blockwright suites and CI                              | [Testing](en/testing.md)               | [测试指南](zh-CN/testing.md)          |
| Modules, public APIs, source layout and lifecycle                               | [Architecture](en/architecture.md)     | [架构说明](zh-CN/architecture.md)     |

| Colony raids, building damage, plunder and server controls | [Colony raids](en/raids.md) | [殖民地袭击](zh-CN/raids.md) |

## Maintaining these docs

Keep English and Chinese pages in sync in the same change. Use in-game locale
catalogs for UI names; keep commands, file names, mod IDs and Java symbols literal.
Root READMEs introduce the mod, contribution guides explain the contribution
workflow, and the pages here hold detailed reference material.

Run `npm run format:md` before staging Markdown. The local Husky hook checks all
project Markdown and the staged copies; CI repeats `npm run format:md:check`.

Check versions against `gradle.properties` and each mod's `neoforge.mods.toml`,
commands against `package.json` and Gradle files, and CI claims against
`.github/workflows/build.yml`. A documented test command is not evidence that a
particular build passed it.

The Last commit badge tracks upstream `master` and links to `nightly`. The Last
release badge extracts the first published version tag of the form
`vMAJOR.MINOR[.PATCH]`, optionally followed by `-alpha`, `-beta` or `-rc` and a
build suffix, from the latest 100 GitHub release records. Its Shields dynamic
regex URL is shared by both READMEs. Until such a release exists, Shields reports
`no result`; do not substitute the nightly date. The click target is the GitHub
release list with `nightly` excluded. If the project's tag convention changes,
update the badge selector in both READMEs.
