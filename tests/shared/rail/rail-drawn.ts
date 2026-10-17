// Railway lines drawn with the mod's own railway pattern, laid at once (track, bed, cuttings, bridges, tunnels and
// curves joined the way a builder joins them). A line too long for one drawing goes down in pieces that meet rail to
// rail on level straights.
import { readFileSync } from "node:fs";
import path from "node:path";
import type { TaskLimits } from "@izakyl/blockwright-client";
import { defineTask, tick, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { REPO_ROOT } from "../harness/out-paths";

const PATTERNS = path.join(REPO_ROOT, "mods/vanilla/src/main/resources/data/folkways/folkways/pattern");
const LIMITS: TaskLimits = { maxDepth: 10, maxNodes: 1_000_000, maxItems: 100_000, maxString: 1_000_000 };

export const DRAW_PATTERNS = defineTask<Record<string, unknown>, any>({
  name: "folkways.shared.draw-patterns", side: "server", timeoutMs: 600_000, limits: LIMITS,
  source: readFileSync(path.join(REPO_ROOT, "tests/shared/rail/DrawPatterns.java"), "utf8"),
});

export type Point = { x: number; y: number; z: number };

/** Each piece's report; the line stands whole when none was refused and every joint joined. */
export async function drawRailway(server: MinecraftServer, pieces: Point[][], name = "line") {
  // The pattern and the libraries it loads.
  const sources = Object.fromEntries(["railway", "materials"]
    .map((one) => [`folkways:${one}`, readFileSync(path.join(PATTERNS, `${one}.star`), "utf8")]));
  const reports: any[] = [];
  for (const [i, piece] of pieces.entries()) {
    const out = await server.run(DRAW_PATTERNS, { sources, drawings: [{ name: `${name}-${i}`, pattern: "folkways:railway", kind: "path",
      points: piece.map((p) => [p.x, p.y, p.z]), collect: ["track", "ballast"] }] }, { timeoutMs: 600_000 });
    reports.push(...out.drawings);
  }
  // Create builds its track graph from the track blocks on their next tick.
  await tick.sprint(server, 20);
  const refused = reports.filter((r) => r.refused).map((r) => `${r.name}: ${r.refused}`);
  const unjoined = reports.reduce((n, r) => n + (r.joints ?? 0) - (r.joined ?? 0), 0);
  // The track's cells and the ballast under it, for what is laid after to keep clear of.
  const track: number[][] = [], ballast: number[][] = [];
  for (const r of reports) for (const [role, into] of [["track", track], ["ballast", ballast]] as const) {
    const flat: number[] = r.roles?.[role] ?? [];
    for (let i = 0; i + 2 < flat.length; i += 3) into.push([flat[i], flat[i + 1], flat[i + 2]]);
  }
  return { ok: !refused.length && !unjoined, pieces: reports.length, refused, unjoined,
    cells: reports.reduce((n, r) => n + (r.cells ?? 0), 0), track, ballast, reports: reports.map(({ roles: _, ...r }) => r) };
}
