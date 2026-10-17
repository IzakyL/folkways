// The bench town is raised once and kept: the world as it stood the moment raiseTown finished (before any colony
// data, stock or entity), and the Town that describes it. A later run with the same inputs copies that world into
// its fresh server instance before launch and skips the relief, the site pick, the surveys and the laying.
//
// The key covers what decides the town: the bench profile's server side in blockwright.toml (seed, terrain mods), the counts, the
// rail switch, and the planning and laying sources. Changes to the mod's own pattern engine are not in it:
// FOLKWAYS_LC_RERAISE=1 raises afresh whatever the key says. A saved town (town-saves.ts) stands in for the cache
// whatever the key: a run pinned to one reads its world and Town from the save and never raises.
import { createHash } from "node:crypto";
import { existsSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";
import { seedInstance } from "@izakyl/blockwright-client";
import { world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { REPO_ROOT } from "../../shared/harness/out-paths";
import type { Town } from "./raise";
import { Frame, Terrain, Works, type Counts, type Survey } from "./town.ts";
import { pinnedSave, saveDir } from "./town-saves.ts";

const ROOT = path.join(REPO_ROOT, ".blockwright", "bench-town");
const SOURCES = [
  "tests/bench/town/raise.ts", "tests/bench/town/town.ts", "tests/bench/town/town-build.ts", "tests/bench/town/network.ts", "tests/bench/town/site.ts",
  "tests/bench/town/Mason.java", "tests/bench/town/Relief.java", "tests/bench/town/Survey.java", "tests/shared/rail/DrawPatterns.java",
  "tests/shared/rail/rail-footprint.ts", "tests/shared/rail/rail-drawn.ts", "tests/bench/town/orders.ts",
  ...["road", "grading", "terraced_field", "dock", "city_wall", "railway"].map((p) => `mods/vanilla/src/main/resources/data/folkways/folkways/pattern/${p}.star`),
];
// What of the world is the player's, or the server's own lock: a run starts its player at the world spawn.
const LEFT_OUT = new Set(["session.lock", "playerdata", "stats", "advancements"]);

export type Notes = Record<string, unknown>;

export function townKey(config: string, counts: Counts, rail: boolean) {
  const profile = config.split("#")[1] ?? "";
  const toml = readFileSync(path.join(REPO_ROOT, config.split("#")[0]), "utf8");
  // Only what the server is given decides the town: the client's options and its client-only mods (the shaders) do not.
  const ours = toml.split(/\n(?=\[)/).filter((s) => s.startsWith(`[profile.${profile}.`)
    && !s.startsWith(`[profile.${profile}.neoforge.client`))
    .map((s) => s.split("\n").filter((line) => !line.startsWith("#") && !/target = \["client"\]/.test(line)).join("\n")).join("\n");
  const hash = createHash("sha256").update(ours).update(JSON.stringify({ counts, rail }));
  for (const f of SOURCES) hash.update(f).update(readFileSync(path.join(REPO_ROOT, f)));
  return hash.digest("hex").slice(0, 16);
}

const dirOf = (key: string) => path.join(ROOT, key);

// Where a run reads its town from: the save it is pinned to, else the cache under its key.
function readFrom(key: string) {
  const pinned = pinnedSave();
  return pinned ? saveDir(pinned) : dirOf(key);
}

/** The save this run's town comes from, if it is pinned to one. */
export const townSave = () => pinnedSave();

/** Whether a kept town answers this key, or the run is pinned to a save (and a fresh raise was not asked for). */
export function hasTown(key: string) {
  if (process.env.FOLKWAYS_LC_RERAISE === "1") return false;
  const from = readFrom(key);
  return existsSync(path.join(from, "town.json")) && existsSync(path.join(from, "world", "level.dat"));
}

/** Copies the kept world into a server instance that has not launched yet. */
export function seedWorld(key: string, serverInstance: string) {
  seedInstance(serverInstance, { cwd: REPO_ROOT }).copy(path.join(readFrom(key), "world"), "world");
}

export function loadTown(key: string): { town: Town; notes: Notes } {
  const saved = JSON.parse(readFileSync(path.join(readFrom(key), "town.json"), "utf8"),
    (k, v) => k === "frame" && v ? Object.setPrototypeOf(v, Frame.prototype) : v);
  const { surveys } = saved.town.terrain as { surveys: Survey[] };
  const town: Town = { ...saved.town, terrain: new Terrain(surveys[0], surveys.slice(1)),
    // Already laid: the ops are not kept.
    plan: { ...saved.town.plan, pads: new Works(), works: new Works() } };
  return { town, notes: saved.notes };
}

/**
 * Keeps the world the server stands in now and the town it holds, under the key; other keys' towns go, so only the
 * latest is on disk. Saving is switched off while the world is copied, so no chunk is half written.
 */
export async function keepTown(server: MinecraftServer, serverInstance: string, key: string, town: Town, notes: Notes) {
  const to = dirOf(key);
  if (existsSync(ROOT)) for (const old of readdirSync(ROOT)) rmSync(path.join(ROOT, old), { recursive: true, force: true });
  await world.keepSave(server, path.join(to, "world"), { leaveOut: [...LEFT_OUT] });
  const kept = { ...town, terrain: { surveys: town.terrain.all }, plan: { ...town.plan, pads: undefined, works: undefined } };
  writeFileSync(path.join(to, "town.json"), JSON.stringify({ key, kept_at: new Date().toISOString(), notes, town: kept }));
  return { key, dir: to };
}
