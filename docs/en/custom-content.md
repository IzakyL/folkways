# Custom content and configuration

[Documentation](README.md) · [简体中文](../zh-CN/custom-content.md)

## Content libraries

Load content from a data pack at `data/<namespace>/folkways/<shelf>/`, or from
`<gamedir>/folkways/<shelf>/` on the server. Server folders load as an additional
data pack under the `local` namespace; `/reload` picks up changes.

Run `/folkways library` to inspect each shelf, its entries and source packs, and
any files rejected during loading.

| Shelf            | Files                                                         | Purpose                                                                              |
| ---------------- | ------------------------------------------------------------- | ------------------------------------------------------------------------------------ |
| `pattern`        | `<name>.star`                                                 | [Starlark](https://github.com/bazelbuild/starlark) building patterns                 |
| `piece`          | `<name>.nbt`                                                  | Structures stamped by a pattern's `template(...)` call                               |
| `blueprint`      | `<name>.nbt`                                                  | Whole structures selected on the **Blueprints** page, then aimed, rotated and placed |
| `resident_skin`  | `<name>.png`, optional `<name>.json` with `arms` and `weight` | Resident skins; the server sends textures to players without a resource pack         |
| `resident_model` | `<name>/main.json`, `main.animation.json`, `textures/*.png`   | GeckoLib or unencrypted YSM models, with one look per texture                        |

Use the [built-in patterns](../../mods/vanilla/src/main/resources/data/folkways/folkways/pattern/)
as references. Pattern text and shared library dictionaries follow the
[translation conventions](../../CONTRIBUTING.md#translations-and-patterns).

On a client, `folkways/pattern/`, `folkways/piece/` and `folkways/blueprint/` form
the player's personal library. Drawings are prepared locally and sent to the server.
This personal library is separate from server-provided data pack content.

For Touhou Little Maid pack installation, see the [gameplay guide](gameplay.md#maid-model-packs).

## Server configuration

World-specific options live in `<world>/serverconfig/`:

| File                           | Configures                                             |
| ------------------------------ | ------------------------------------------------------ |
| `folkways_core-server.toml`    | Colony engine                                          |
| `folkways_vanilla-server.toml` | Work among vanilla blocks                              |
| `folkways_create-server.toml`  | Rail and dispatch, when Folkways × Create is installed |

Composting preferences and seed reserves are configured per colony in the
**Composting settings** page; see [Composting](gameplay.md#composting).
