// Saved towns: a kept town (town-cache.ts) copied out under a name, so the bench runs on the same world however the
// sources that key the cache change. A run picks one with FOLKWAYS_LC_TOWN=<name>; with none named it runs on the save
// called "default" when there is one, and FOLKWAYS_LC_TOWN=none goes back to the keyed cache.
//
//   node tests/bench/tools/save-town.ts <name>   saves the town kept now (the latest raised) under <name>
import { cpSync, existsSync, readdirSync, rmSync, statSync } from "node:fs";
import path from "node:path";
import { REPO_ROOT } from "../../shared/harness/out-paths.ts";

export const KEPT_ROOT = path.join(REPO_ROOT, ".blockwright", "bench-town");
export const SAVES_ROOT = path.join(REPO_ROOT, ".blockwright", "bench-saves");
const DEFAULT_SAVE = "default";

const holdsTown = (dir: string) => existsSync(path.join(dir, "town.json")) && existsSync(path.join(dir, "world", "level.dat"));

export const saveDir = (name: string) => path.join(SAVES_ROOT, name);

/** The save this run is pinned to, if any: the one named, else "default" when it is there. */
export function pinnedSave(): string | undefined {
  const named = process.env.FOLKWAYS_LC_TOWN;
  if (named === "none" || named === "") return undefined;
  if (named) {
    if (!holdsTown(saveDir(named))) throw new Error(`FOLKWAYS_LC_TOWN=${named}: no saved town at ${saveDir(named)}`);
    return named;
  }
  return holdsTown(saveDir(DEFAULT_SAVE)) ? DEFAULT_SAVE : undefined;
}

/** Copies a kept town (the latest raised, or the one under `key`) out under `name`, replacing any save of that name. */
export function saveTown(name: string, key?: string): string {
  const keys = existsSync(KEPT_ROOT) ? readdirSync(KEPT_ROOT).filter((k) => holdsTown(path.join(KEPT_ROOT, k))) : [];
  const from = key ?? keys.sort((a, b) => statSync(path.join(KEPT_ROOT, b)).mtimeMs - statSync(path.join(KEPT_ROOT, a)).mtimeMs)[0];
  if (!from || !holdsTown(path.join(KEPT_ROOT, from))) throw new Error(`no kept town${key ? ` under ${key}` : ""} in ${KEPT_ROOT}`);
  const to = saveDir(name);
  rmSync(to, { recursive: true, force: true });
  cpSync(path.join(KEPT_ROOT, from), to, { recursive: true });
  return to;
}
