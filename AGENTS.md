# Agent guide

An index of this repository's documentation for coding agents. Read the linked page before acting;
this file does not repeat their content.

## Layout

`mods/<dir>` holds one mod each (Core, Vanilla and extensions; Gradle subprojects). `tests/` holds the
Playwright client suites driven by Blockwright. Locale catalogs live in `mods/*/src/main/resources/assets/*/lang/`.

## Environment

Worktrees share the heavy dependencies of the main checkout. The reference setup is
[`.codex/environments/environment.toml`](.codex/environments/environment.toml); follow it by hand in other tools.

1. In the main checkout, run `npm ci` and `npm run setup` once, so `node_modules/` and `.blockwright-store/` exist.
2. In each new worktree, symlink `node_modules` and `.blockwright-store` to the main checkout's directories,
   keeping any that already exist, then run `npm run setup` to write that worktree's `.blockwright/mods.json`.
3. Never copy `.blockwright/mods.json` between checkouts: it holds absolute paths into the store of the
   checkout that wrote it and breaks once that checkout is removed. Rerun `npm run setup` instead.
4. If the worktree's `package-lock.json` differs from the main checkout's, replace the `node_modules` link with
   a local `npm ci`. A changed `blockwright.toml` only needs `npm run setup`; the shared store is a content cache.
5. After setup, keep Gradle `--offline`. The Blockwright MCP server is declared in both `.codex/config.toml` and
   `.mcp.json`; keep them in sync and let them inherit the current worktree rather than pinning the main checkout.

## Where to look

- [README.md](README.md): what the mods do, the mods table, installation and in-game debugging.
  Consult for player-facing behavior and which mod owns a feature.
- [INTEGRATIONS.md](INTEGRATIONS.md): each third-party integration from the player's side, with supported
  versions and limits. Consult when changing an integration's behavior or version range.
- [CONTRIBUTING.md](CONTRIBUTING.md): setup, checks, pull requests and code conventions.
  - Running checks or GameTests, and every `-P` test switch: [Validation](CONTRIBUTING.md#validation).
  - Naming, test placement, translations and patterns: [Conventions](CONTRIBUTING.md#conventions).
- [SAVE_COMPATIBILITY.md](SAVE_COMPATIBILITY.md): versioning, the upgrade promise and the migration engine.
  Consult before changing anything persisted (NBT, keeps bags, attachments, block entities, components).
- [THIRD_PARTY.md](THIRD_PARTY.md): license inventory for bundled code and assets.

Each page except this one and `THIRD_PARTY.md` has a `.zh-CN.md` counterpart.

## Rules

- PRs are squash-merged, so judge migrations and compatibility only against `origin/master`; a format
  changed within a branch or not yet merged gets no migration or legacy code
  ([details](SAVE_COMPATIBILITY.md#changing-persisted-formats)).
- Update English and Chinese pages together, section by section ([Documentation](CONTRIBUTING.md#documentation)).
- Docs stay at overview level: no detailed reference pages or per-module READMEs
  ([Documentation](CONTRIBUTING.md#documentation)).
- Take UI names from the locale catalogs; keep commands, paths and Java symbols literal
  ([Documentation](CONTRIBUTING.md#documentation)).
- Commit messages follow `type(scope): summary` ([Pull requests](CONTRIBUTING.md#pull-requests)).
