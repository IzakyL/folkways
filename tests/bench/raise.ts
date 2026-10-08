import { readFileSync, writeFileSync } from "node:fs";
import type { TaskLimits } from "@izakyl/blockwright-client";
import { defineTask, type MinecraftServer, type TaskTemplate } from "@izakyl/blockwright-minecraft";
import { outAbs } from "../out-paths";
import { Terrain, Works, grow, median, walkway, type Box, type Counts, type SiteChoice, type Survey, type Vec } from "./town.ts";
import { planTown, type TownPlan, type BlueprintCell, type Plot } from "./town-build.ts";

// Raises the bench town on real ground: finds the site, reads the ground, levels the lots, grades the roads,
// builds the halls, terraces the fields and runs docks out into the nearest water.
const PATTERNS = "src/main/resources/data/folkways/folkways/pattern";
const star = (name: string) => readFileSync(`${PATTERNS}/${name}.star`, "utf8");
const SOURCES = ["road", "grading", "terraced_field", "dock", "city_wall"];

export const HALF = 128;
const SURVEY_MARGIN = 96;
const DOCKS = 8;
export const FISH_SPOTS = 12;

// Answers are big (a whole survey, every drawing's report), so the node budget is raised past the task default.
const ANSWER_LIMITS: TaskLimits = { maxDepth: 10, maxNodes: 1_000_000, maxItems: 100_000, maxString: 1_000_000 };

// Each whole-file task is read once, when this module loads.
const fileTask = (name: string, file: string) => defineTask<Record<string, unknown>, any>({
  name, side: "server", source: readFileSync(file, "utf8"), timeoutMs: 300_000, limits: ANSWER_LIMITS,
});
export const MASON = fileTask("folkways.bench.mason", "tests/bench/Mason.java");
export const DRAW_PATTERNS = fileTask("folkways.bench.draw-patterns", "tests/shared/DrawPatterns.java");
export const SCOUT = fileTask("folkways.bench.scout", "tests/bench/Scout.java");
export const SURVEY = fileTask("folkways.bench.survey", "tests/bench/Survey.java");

async function run(server: MinecraftServer, task: TaskTemplate<Record<string, unknown>, any>, args: Record<string, unknown>, label: string, timeoutMs = 300_000, partial = false) {
  let value: any;
  try {
    value = await server.run(task, args, { timeoutMs });
  } catch (error) {
    throw new Error(`${label} failed: ${String(error).slice(0, 2000)}`, { cause: error });
  }
  if (value?.ok === false && !partial) throw new Error(`${label} refused: ${JSON.stringify(value).slice(0, 2000)}`);
  return value;
}

async function lay(server: MinecraftServer, works: Works, label: string) {
  const chunk = 400;
  const totals: Record<string, number> = { placed: 0, reshaped: 0, trees: 0 };
  for (let i = 0; i < works.ops.length; i += chunk) {
    const out = await run(server, MASON, { palette: works.palette, ops: works.ops.slice(i, i + chunk) }, `${label} ${i}`);
    for (const k of Object.keys(totals)) totals[k] += Number(out[k] ?? 0);
  }
  return { ops: works.ops.length, ...totals };
}

type Drawing = { name: string; pattern: string; kind: "zone" | "path"; points: number[][]; knobs?: Record<string, unknown>;
  lay?: boolean; collect?: string[]; blueprint?: boolean; seed?: number };

export async function draw(server: MinecraftServer, drawings: Drawing[], keep: number[][] = [], label = "drawing") {
  const sources = Object.fromEntries(SOURCES.map((s) => [`folkways:${s}`, star(s)]));
  const reports: any[] = [];
  for (let i = 0; i < drawings.length; i += 12) {
    const out = await run(server, DRAW_PATTERNS,
      { sources, drawings: drawings.slice(i, i + 12), keep, guard: ["post", "lamp"] }, label, 300_000, true);
    reports.push(...out.drawings);
  }
  return reports;
}

export async function scout(server: MinecraftServer, from: { x: number; z: number }) {
  return await run(server, SCOUT, { from, reach: 1280, half: HALF, ring: 40 }, "scout", 300_000);
}

export async function survey(server: MinecraftServer, box: Box): Promise<Survey & { generate_ms: number }> {
  const read = await run(server, SURVEY, box, "survey", 600_000);
  const flat = (rows: string[]) => rows.flatMap((r) => r.split(",").map(Number));
  const out = { ...read, ground: flat(read.ground), water: flat(read.water), cover: flat(read.cover) };
  if (out.ground.length !== read.width * read.depth) throw new Error(`survey came back with ${out.ground.length} of ${read.width * read.depth} columns`);
  return out;
}

const cellsOf = (flat: number[]): Vec[] => {
  const out: Vec[] = [];
  for (let i = 0; i + 2 < flat.length; i += 3) out.push({ x: flat[i], y: flat[i + 1], z: flat[i + 2] });
  return out;
};

// The ground cells of a drawn terraced field: the top soil of every column nothing else of the field stands on.
function soilOf(roles: Record<string, number[]>): Vec[] {
  const top = new Map<string, number>();
  for (const flat of Object.values(roles)) for (const c of cellsOf(flat)) {
    const k = `${c.x},${c.z}`;
    top.set(k, Math.max(top.get(k) ?? -9999, c.y));
  }
  return cellsOf(roles.fill ?? []).filter((c) => top.get(`${c.x},${c.z}`) === c.y);
}

const RIPE: Record<string, string> = { "minecraft:wheat": "age=7", "minecraft:carrots": "age=7", "minecraft:potatoes": "age=7", "minecraft:beetroots": "age=3" };

function dockCandidates(t: Terrain, plan: TownPlan) {
  const { x: cx, z: cz } = plan.site;
  const avoid = plan.lots.map((l) => grow(l.box, 5));
  const free = (x: number, z: number) => !avoid.some((b) => x >= b.minX && x <= b.maxX && z >= b.minZ && z <= b.maxZ);
  const out: Array<{ shore: { x: number; z: number }; from: number[]; to: number[]; score: number }> = [];
  const reach = HALF + 48;
  for (let x = cx - reach; x <= cx + reach; x++) for (let z = cz - reach; z <= cz + reach; z++) {
    if (t.wet(x, z) || !free(x, z)) continue;
    for (const [dx, dz] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
      if (!t.wet(x + dx, z + dz)) continue;
      let open = true;
      for (let k = 1; k <= 16 && open; k++) for (let s = -3; s <= 3 && open; s++)
        open = t.wet(x + dx * k + dz * s, z + dz * k + dx * s);
      if (!open) continue;
      let dry = true;
      for (let k = 0; k <= 4 && dry; k++) dry = !t.wet(x - dx * k, z - dz * k) && free(x - dx * k, z - dz * k);
      if (!dry || Math.abs(t.ground(x, z) - t.surface(x + dx * 2, z + dz * 2)) > 3) continue;
      const y = t.ground(x, z);
      out.push({ shore: { x, z }, from: [x - dx * 3, y, z - dz * 3], to: [x + dx * 12, y, z + dz * 12],
        score: Math.hypot(x - cx, z - cz) });
    }
  }
  out.sort((a, b) => a.score - b.score);
  const picked: typeof out = [];
  for (const c of out) {
    if (picked.some((p) => Math.hypot(p.shore.x - c.shore.x, p.shore.z - c.shore.z) < 14)) continue;
    picked.push(c);
    if (picked.length >= 30) break;
  }
  return picked;
}

const overlaps = (a: number[], b: number[], pad: number) =>
  !(a[3] + pad < b[0] || b[3] + pad < a[0] || a[5] + pad < b[2] || b[5] + pad < a[2]);

export type Town = Awaited<ReturnType<typeof raiseTown>>;

export async function raiseTown(server: MinecraftServer, from: { x: number; z: number }, counts: Counts, note: (k: string, v: unknown) => void) {
  const scouted = await scout(server, from);
  note("scout", scouted);
  const site: SiteChoice = { x: scouted.x, z: scouted.z, level: scouted.level, sea: scouted.sea };
  const reach = HALF + SURVEY_MARGIN;
  const box = { minX: site.x - reach, maxX: site.x + reach, minZ: site.z - reach, maxZ: site.z + reach };
  const read = await survey(server, box);
  note("survey", { ...box, generate_ms: read.generate_ms });
  writeFileSync(outAbs("bench", "reports", "survey.json"), JSON.stringify({ site, survey: read }));
  const terrain = new Terrain(read);
  const plan = planTown(terrain, site, counts, HALF);
  note("plan", { lots: plan.lots.length, skipped: plan.skipped, roads: plan.roads.length, unrouted: plan.unrouted, extent: plan.extent });
  if (plan.skipped.length) throw new Error(`the site could not hold ${plan.skipped.join(", ")}`);
  if (plan.unrouted.length) throw new Error(`the town has no street connection to ${plan.unrouted.join(", ")}`);

  note("pads", await lay(server, plan.pads, "pads"));
  const keep: number[][] = [];
  for (const l of plan.lots) for (let x = l.box.minX - 1; x <= l.box.maxX + 1; x++) for (let z = l.box.minZ - 1; z <= l.box.maxZ + 1; z++) keep.push([x, z]);
  const roads = await draw(server, plan.roads.map((r) => ({ name: r.name, pattern: "folkways:road", kind: "path" as const,
    points: r.points, knobs: { width: r.width, spacing: 14 } })), keep, "roads");
  const paths = new Works();
  const refused = roads.map((r, i) => ({ r, road: plan.roads[i] })).filter(({ r }) => r.refused);
  for (const { road } of refused) walkway(terrain, road, paths);
  note("roads", { drawn: roads.filter((r) => !r.refused).length, walkways: refused.length,
    refused: refused.map(({ r }) => `${r.name}: ${String(r.refused).split(" / ")[0]}`), laid: await lay(server, paths, "walkways") });
  note("buildings", await lay(server, plan.works, "buildings"));

  // Terraced fields: the pattern shapes the slope, the soil it leaves becomes the farm.
  const fieldDrawings = plan.fields.map((f) => ({ name: f.lot.name, pattern: "folkways:terraced_field", kind: "zone" as const,
    points: [[f.zone[0].x, site.level, f.zone[0].z], [f.zone[1].x, site.level, f.zone[1].z]],
    knobs: { fenced: false, rise: 1, spacing: 6 }, collect: ["fill", "bund", "path", "wall", "kerb", "steps", "water", "fence", "post", "lamp"] }));
  const drawnFields = await draw(server, fieldDrawings, [], "fields");
  const plots: Plot[] = [...plan.plots];
  const planting = new Works();
  drawnFields.forEach((d, i) => {
    const field = plan.fields[i];
    if (d.refused || !d.roles) return;
    const soil = soilOf(d.roles).sort((a, b) => a.x - b.x || a.z - b.z);
    const xs = soil.map((c) => c.x), lo = Math.min(...xs), span = Math.max(...xs) - lo + 1;
    const ripe: Vec[] = [];
    for (const c of soil) {
      const band = Math.floor(((c.x - lo) * 3) / span);
      if (band === 2) continue;
      planting.set(c, "minecraft:farmland[moisture=7]");
      if (band === 0) { planting.set({ ...c, y: c.y + 1 }, `${field.crop}[${RIPE[field.crop] ?? "age=7"}]`); ripe.push(c); }
    }
    plots.push({ name: field.lot.name, crop: field.crop, cells: soil, ripe });
  });
  note("fields", { drawn: drawnFields.map((d) => ({ name: d.name, cells: d.cells, refused: d.refused })),
    planting: await lay(server, planting, "planting") });

  // Docks where the town meets the water.
  const candidates = dockCandidates(terrain, plan);
  const dry = await draw(server, candidates.map((c, i) => ({ name: `dock-${i}`, pattern: "folkways:dock", kind: "path" as const,
    points: [c.from, c.to], knobs: { width: 3, head: i % 3 === 0 ? "l" : "t", shelter: i % 4 === 1 }, lay: false, seed: i })), [], "docks (draft)");
  const chosen: number[] = [];
  dry.forEach((d, i) => {
    if (d.refused || !d.bounds || chosen.length >= DOCKS) return;
    if (chosen.some((j) => overlaps(dry[j].bounds, d.bounds, 3))) return;
    chosen.push(i);
  });
  const docks = await draw(server, chosen.map((i) => ({ name: `dock-${i}`, pattern: "folkways:dock", kind: "path" as const,
    points: [candidates[i].from, candidates[i].to], knobs: { width: 3, head: i % 3 === 0 ? "l" : "t", shelter: i % 4 === 1 },
    collect: ["deck"], seed: i })), [], "docks");
  const fishing: Array<{ name: string; cells: Vec[] }> = [];
  for (const d of docks) if (!d.refused && d.roles?.deck) fishing.push({ name: d.name, cells: cellsOf(d.roles.deck) });
  for (const [i, p] of plan.ponds.entries()) if (fishing.length < FISH_SPOTS) fishing.push({ name: `pond-${i}`, cells: p.cells });
  note("docks", { candidates: candidates.length, drafted: dry.filter((d) => !d.refused).length, laid: fishing.filter((f) => f.name.startsWith("dock")).length,
    refusals: [...new Set(dry.filter((d) => d.refused).map((d) => String(d.refused).split("/")[0].trim()))] });

  // The town wall, drafted but left for the builders.
  let wall: { anchor: Vec; blocks: BlueprintCell[] } | null = null;
  if (plan.wall) {
    const [drafted] = await draw(server, [{ name: "town-wall", pattern: "folkways:city_wall", kind: "path",
      points: plan.wall.points.map((p) => [p.x, p.y, p.z]), knobs: { height: 6, thickness: 3, spacing: 16, gate: true },
      lay: false, blueprint: true }], [], "wall (draft)");
    if (drafted && !drafted.refused && drafted.blueprint?.length) {
      const blocks: BlueprintCell[] = drafted.blueprint.map(([x, y, z, state]: [number, number, number, string]) => ({ x, y, z, state }));
      wall = { anchor: { x: Math.min(...blocks.map((b) => b.x)), y: Math.min(...blocks.map((b) => b.y)), z: Math.min(...blocks.map((b) => b.z)) }, blocks };
    }
    note("wall", drafted?.refused ? { refused: drafted.refused } : { cells: wall?.blocks.length ?? 0 });
  }

  const dockBoxes: Box[] = dry.filter((_, i) => chosen.includes(i)).map((d) => ({ minX: d.bounds[0], maxX: d.bounds[3], minZ: d.bounds[2], maxZ: d.bounds[5] }));
  const all = [plan.extent, ...dockBoxes];
  const extent: Box = { minX: Math.min(...all.map((b) => b.minX)), maxX: Math.max(...all.map((b) => b.maxX)),
    minZ: Math.min(...all.map((b) => b.minZ)), maxZ: Math.max(...all.map((b) => b.maxZ)) };
  return { plan, terrain, site, plots, fishing, wall, extent, level: median(plan.lots.map((l) => l.y)) };
}

// A graded bed for the railway ring: embankment over hollows, a cutting through rises, ballast on top.
export function railBed(t: Terrain, loop: Box, far: Vec | null, extras: Box[]) {
  const band = 3, r = 20;
  const lines: Box[] = [
    { minX: loop.maxX - band, maxX: loop.maxX + band, minZ: loop.minZ, maxZ: loop.maxZ },
    { minX: loop.minX - band, maxX: loop.minX + band, minZ: loop.minZ, maxZ: loop.maxZ },
    { minX: loop.minX, maxX: loop.maxX, minZ: loop.minZ - band, maxZ: loop.minZ + band },
    { minX: loop.minX, maxX: loop.maxX, minZ: loop.maxZ - band, maxZ: loop.maxZ + band },
    { minX: loop.maxX - r, maxX: loop.maxX + band, minZ: loop.minZ - band, maxZ: loop.minZ + r },
    { minX: loop.minX - band, maxX: loop.minX + r, minZ: loop.minZ - band, maxZ: loop.minZ + r },
    { minX: loop.minX - band, maxX: loop.minX + r, minZ: loop.maxZ - r, maxZ: loop.maxZ + band },
    { minX: loop.maxX - r, maxX: loop.maxX + band, minZ: loop.maxZ - r, maxZ: loop.maxZ + band },
    ...extras,
  ];
  const heights: number[] = [];
  for (const b of lines.slice(0, 4)) for (let x = b.minX; x <= b.maxX; x += 4) for (let z = b.minZ; z <= b.maxZ; z += 4)
    if (!t.wet(x, z)) heights.push(t.ground(x, z));
  const y = median(heights) + 1;
  const works = new Works();
  for (const b of lines) {
    works.clear(grow(b, 2));
    works.pad(b, y, "minecraft:tuff", "minecraft:dirt", "minecraft:stone_bricks", 8);
  }
  if (far) {
    const b = { minX: far.x - 3, maxX: far.x + 12, minZ: far.z - 3, maxZ: far.z + 12 };
    works.clear(grow(b, 2));
    works.pad(b, y, "minecraft:grass_block", "minecraft:dirt", "minecraft:stone_bricks", 8);
  }
  return { y, works };
}

export async function layRailBed(server: MinecraftServer, works: Works) {
  return await lay(server, works, "rail bed");
}
