import { readFileSync, writeFileSync } from "node:fs";
import type { TaskLimits } from "@izakyl/blockwright-client";
import { defineTask, type MinecraftServer, type TaskTemplate } from "@izakyl/blockwright-minecraft";
import { outAbs } from "../../shared/harness/out-paths";
import { Terrain, Works, catalogue, cityShape, grow, levelled, sow, walkway, SKIRT, type Box, type Counts, type SiteChoice, type Survey, type Vec } from "./town.ts";
import { planSettlement, type TownPlan, type Plot } from "./town-build.ts";
import { clashes, piecesOf, planNetwork, type Network, type Relief } from "./network.ts";
import { drawRailway } from "../../shared/rail/rail-drawn.ts";
import { pickSite } from "./site.ts";
import { digSites, planOrders, type Dig } from "./orders.ts";

// Raises the bench city on real ground: reads the lie of the land, picks the flattest site with a mountain in reach,
// reads the ground, levels the city and lays its grid of streets, builds the halls, draws the field, runs docks out into
// the nearest water, and beds the railway out to the mine at the mountain's foot.
const PATTERNS = "mods/vanilla/src/main/resources/data/folkways/folkways/pattern";
const star = (name: string) => readFileSync(`${PATTERNS}/${name}.star`, "utf8");
const SOURCES = ["road", "grading", "materials", "terraced_field", "dock"];

const SURVEY_MARGIN = 96;
// How far from the player a site may lie, the lie of the land read around it to judge sites and lines, and its grain.
const SITE_REACH = 1500, RELIEF_REACH = 2400, RELIEF_STEP = 24;
const DOCKS = 8;
export const FISH_SPOTS = 12;

// Answers are big (a whole survey, every drawing's report), so the node budget is raised past the task default.
const ANSWER_LIMITS: TaskLimits = { maxDepth: 10, maxNodes: 1_000_000, maxItems: 100_000, maxString: 1_000_000 };

// Each whole-file task is read once, when this module loads.
const fileTask = (name: string, file: string, thread?: "worker") => defineTask<Record<string, unknown>, any>({
  name, side: "server", source: readFileSync(file, "utf8"), timeoutMs: 300_000, limits: ANSWER_LIMITS, ...(thread ? { thread } : {}),
});
export const MASON = fileTask("folkways.bench.mason", "tests/bench/town/Mason.java");
export const DRAW_PATTERNS = fileTask("folkways.bench.draw-patterns", "tests/shared/rail/DrawPatterns.java");
// The relief only samples the generator's noise, so it reads off the server thread: minutes of it there would stall
// the server long enough for the client to time out.
export const RELIEF = fileTask("folkways.bench.relief", "tests/bench/town/Relief.java", "worker");
export const SURVEY = fileTask("folkways.bench.survey", "tests/bench/town/Survey.java");

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

// The railway's track, the ballast under it and the room over it: drawn first, and nothing laid after may touch it (a
// road's slabs over the rails, a pad's clearing through its bed would cut Create's track graph into pieces).
let railKept: number[][] = [];

async function lay(server: MinecraftServer, works: Works, label: string, chunk = 400) {
  const totals: Record<string, number> = { placed: 0, reshaped: 0, trees: 0 };
  for (let i = 0; i < works.ops.length; i += chunk) {
    const out = await run(server, MASON, { palette: works.palette, ops: works.ops.slice(i, i + chunk), spare: railKept }, `${label} ${i}`);
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
      { sources, drawings: drawings.slice(i, i + 12), keep, guard: ["post", "lamp"], spare: railKept }, label, 300_000, true);
    reports.push(...out.drawings);
  }
  return reports;
}

export async function relief(server: MinecraftServer, box: Box, step: number): Promise<Relief & { ms: number }> {
  const started = Date.now();
  const read = await run(server, RELIEF, { ...box, step }, "relief", 600_000);
  const flat = (rows: string[]) => rows.flatMap((r) => r.split(",").map(Number));
  return { ...read, height: flat(read.height), kind: flat(read.kind), ms: Date.now() - started };
}

// Read in strips a few chunks wide: each strip generates its chunks on the server thread, and a whole town's box at
// once would hold that thread for minutes, long enough for the client to time out.
const SURVEY_STRIP = 32;

export async function survey(server: MinecraftServer, box: Box): Promise<Survey & { generate_ms: number }> {
  const flat = (rows: string[]) => rows.flatMap((r) => r.split(",").map(Number));
  const ground: number[] = [], water: number[] = [], cover: number[] = [];
  let generate_ms = 0, sea = 0;
  // Rows run x outer, so strips across x join end to end.
  for (let x = box.minX; x <= box.maxX; x += SURVEY_STRIP) {
    const strip = { ...box, minX: x, maxX: Math.min(box.maxX, x + SURVEY_STRIP - 1) };
    const read = await run(server, SURVEY, strip, "survey", 600_000);
    ground.push(...flat(read.ground));
    water.push(...flat(read.water));
    cover.push(...flat(read.cover));
    generate_ms += Number(read.generate_ms ?? 0);
    sea = read.sea;
  }
  const width = box.maxX - box.minX + 1, depth = box.maxZ - box.minZ + 1;
  if (ground.length !== width * depth) throw new Error(`survey came back with ${ground.length} of ${width * depth} columns`);
  return { minX: box.minX, minZ: box.minZ, width, depth, ground, water, cover, sea, generate_ms };
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
  // Past the ground eased round the city, and never across its track: the railway runs along the west side.
  const avoid = [grow(plan.core, SKIRT + 2), ...plan.lots.map((l) => grow(l.box, 5))];
  const free = (x: number, z: number) => !avoid.some((b) => x >= b.minX && x <= b.maxX && z >= b.minZ && z <= b.maxZ);
  const out: Array<{ shore: { x: number; z: number }; from: number[]; to: number[]; score: number }> = [];
  const within = grow(plan.core, SURVEY_MARGIN - 20);
  if (plan.network) within.minX = plan.core.minX;
  for (let x = within.minX; x <= within.maxX; x++) for (let z = within.minZ; z <= within.maxZ; z++) {
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

export async function raiseTown(server: MinecraftServer, from: { x: number; z: number }, counts: Counts,
                                note: (k: string, v: unknown) => void, options: { rail: boolean }) {
  // On a grid fixed to the world, so the same world always reads the same lie of the land.
  from = { x: Math.round(from.x / 128) * 128, z: Math.round(from.z / 128) * 128 };
  const lie = await relief(server, { minX: from.x - RELIEF_REACH, maxX: from.x + RELIEF_REACH,
    minZ: from.z - RELIEF_REACH, maxZ: from.z + RELIEF_REACH }, RELIEF_STEP);
  note("relief", { width: lie.width, depth: lie.depth, step: lie.step, ms: lie.ms });
  const shape = cityShape(catalogue(counts));
  const picked = pickSite(lie, from, SITE_REACH, shape);
  note("scout", { ok: true, ...picked.best, sea: lie.sea, candidates: picked.candidates, runners_up: picked.runners_up });
  // The lie of the land kept beside the report, for planning the city and its line again without a game.
  writeFileSync(outAbs("bench", "reports", "relief.json"), JSON.stringify(lie));
  // The best site that holds everything: the scout weighs the land and the line, but only the ground itself shows
  // whether the mine's store finds room by its halt and the mountain a place to dig, so a site that cannot gives way to
  // the next best.
  let fitting: { site: SiteChoice; net: Network | null; terrain: Terrain; plan: ReturnType<typeof planSettlement>; digs: Dig[] | null } | undefined;
  const turnedDown: string[] = [];
  for (const candidate of [picked.best, ...picked.runners_up]) {
    const site: SiteChoice = { x: candidate.x, z: candidate.z, level: candidate.level, sea: lie.sea };
    let net: Network | null = options.rail ? planNetwork(lie, { ...site, ...shape }) : null;
    const box = { minX: site.x - shape.halfW - SURVEY_MARGIN, maxX: site.x + shape.halfW + SURVEY_MARGIN,
      minZ: site.z - shape.halfD - SURVEY_MARGIN, maxZ: site.z + shape.halfD + SURVEY_MARGIN };
    const read = await survey(server, box);
    note("survey", { ...box, generate_ms: read.generate_ms });

    const surveys: Survey[] = [];
    if (net) {
      const clash = clashes(net);
      note("network", { centre: net.centre, lines: net.lines.map((l) => ({ name: l.name, length: l.length, corners: l.points.length,
        stops: l.stops.map((s) => ({ name: s.name, at: s.position })), works: l.works })), refused: net.refused, clashes: clash });
      if (clash.length) throw new Error(`the railway plan clashes with itself: ${clash.join("; ")}`);
      for (const w of net.lines.flatMap((l) => l.works)) {
        const r = w.half + 32 + 8;
        const at = await survey(server, { minX: w.x - r, maxX: w.x + r, minZ: w.z - r, maxZ: w.z + r });
        note(`survey_${w.name}`, { generate_ms: at.generate_ms });
        surveys.push(at);
      }
      if (!net.lines.length) net = null;
    }
    const terrain = new Terrain(read, surveys);
    const plan = planSettlement(terrain, site, counts, net ?? undefined);
    const digs = plan.districts.length ? digSites(terrain, plan.districts[0], net) : null;
    note("plan", { site: { x: site.x, z: site.z }, city: plan.grid.box, blocks: plan.grid.blocks.map((b) => ({ name: b.name, box: b.box })), lots: plan.lots.length,
      skipped: plan.skipped, roads: plan.roads.length, unrouted: plan.unrouted, eased: plan.eased, extent: plan.extent,
      districts: plan.districts.map((d) => ({ name: d.name, lots: d.lots.length, level: d.site.level, digs: digs?.length ?? 0 })) });
    const short = [plan.skipped.length ? `no room for ${plan.skipped.join(", ")}` : "",
      plan.unrouted.length ? `no street to ${plan.unrouted.join(", ")}` : "",
      digs && !digs.length ? "no ground to dig up the mountain from the halt" : ""].filter(Boolean);
    if (short.length) {
      turnedDown.push(`${site.x},${site.z}: ${short.join("; ")}`);
      note("turned_down", turnedDown);
      continue;
    }
    writeFileSync(outAbs("bench", "reports", "survey.json"), JSON.stringify({ site, survey: read, works: surveys }));
    fitting = { site, net, terrain, plan, digs };
    break;
  }
  if (!fitting) throw new Error(`no site could hold the city: ${turnedDown.join("; ")}`);
  const { site, terrain, plan, digs } = fitting;
  const net = fitting.net;

  // The railway first: its cuttings, banks, bridges and tunnels shape the ground the lots then keep clear of.
  if (net) note("railway", await drawLines(server, net));
  note("pads", await lay(server, plan.pads, "pads"));
  const keep: number[][] = [];
  for (const l of plan.lots) for (let x = l.box.minX - 1; x <= l.box.maxX + 1; x++) for (let z = l.box.minZ - 1; z <= l.box.maxZ + 1; z++) keep.push([x, z]);
  const roads = await draw(server, plan.roads.map((r) => ({ name: r.name, pattern: "folkways:road", kind: "path" as const,
    points: r.points, knobs: { width: r.width, spacing: 14 } })), keep, "roads");
  const paths = new Works();
  const refused = roads.map((r, i) => ({ r, road: plan.roads[i] })).filter(({ r }) => r.refused);
  for (const { road } of refused) walkway(levelled(terrain, plan.core, site.level), road, paths);
  // The footpaths up the mountain from the mine's halt to each mine.
  for (const dig of digs ?? []) walkway(terrain, dig.path, paths);
  note("roads", { drawn: roads.filter((r) => !r.refused).length, walkways: refused.length, mine_paths: digs?.length ?? 0,
    refused: refused.map(({ r }) => `${r.name}: ${String(r.refused).split(" / ")[0]}`), laid: await lay(server, paths, "walkways") });
  note("buildings", await lay(server, plan.works, "buildings"));

  // The field: the pattern terraces the slope, and the soil it leaves is sown crop by crop.
  const fieldDrawings = plan.fields.map((f) => ({ name: f.lot.name, pattern: "folkways:terraced_field", kind: "zone" as const,
    points: [[f.zone[0].x, f.zone[0].y, f.zone[0].z], [f.zone[1].x, f.zone[1].y, f.zone[1].z]],
    knobs: { fenced: false, rise: 1, spacing: 6 }, collect: ["fill", "bund", "path", "wall", "kerb", "steps", "water", "fence", "post", "lamp"] }));
  const drawnFields = await draw(server, fieldDrawings, [], "fields");
  const plots: Plot[] = [...plan.plots];
  const planting = new Works();
  drawnFields.forEach((d, i) => {
    if (d.refused || !d.roles) return;
    for (const section of sow(soilOf(d.roles))) {
      // A third of each crop's ground stands ripe, a third is tilled and waiting, a third is left for the farmers to till.
      const ripe: Vec[] = [];
      section.cells.forEach((c, k) => {
        const band = Math.floor((k * 3) / section.cells.length);
        if (band === 2) return;
        planting.set(c, "minecraft:farmland[moisture=7]");
        if (band === 0) { planting.set({ ...c, y: c.y + 1 }, `${section.crop}[${RIPE[section.crop] ?? "age=7"}]`); ripe.push(c); }
      });
      plots.push({ name: `${plan.fields[i].lot.name}-${section.crop.replace("minecraft:", "")}`, crop: section.crop, cells: section.cells, ripe });
    }
  });
  note("fields", { drawn: drawnFields.map((d) => ({ name: d.name, cells: d.cells, refused: d.refused })),
    planting: await lay(server, planting, "planting") });
  const unsown = drawnFields.filter((d) => d.refused || !d.roles);
  if (unsown.length) throw new Error(`the field could not be drawn: ${unsown.map((d) => `${d.name}: ${d.refused}`).join("; ")}`);

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

  // The long work: the mines out at the mountain, a wall, a bridge, an arcade and a dock, filed once the colony stands.
  const spare = dry.map((d, i) => ({ d, i })).filter(({ d, i }) => !d.refused && d.bounds && !chosen.includes(i)
    && !chosen.some((j) => overlaps(dry[j].bounds, d.bounds, 6))).map(({ i }) => candidates[i]);
  const work = planOrders(terrain, site, plan.core, plan.lots, plan.roads, net, spare, digs);
  note("orders", { planned: work.orders.map((o) => ({ pattern: o.pattern, name: o.name, why: o.why })), skipped: work.skipped });

  const dockBoxes: Box[] = dry.filter((_, i) => chosen.includes(i)).map((d) => ({ minX: d.bounds[0], maxX: d.bounds[3], minZ: d.bounds[2], maxZ: d.bounds[5] }));
  const all = [plan.extent, ...dockBoxes];
  const extent: Box = { minX: Math.min(...all.map((b) => b.minX)), maxX: Math.max(...all.map((b) => b.maxX)),
    minZ: Math.min(...all.map((b) => b.minZ)), maxZ: Math.max(...all.map((b) => b.maxZ)) };
  return { plan, terrain, site, plots, fishing, orders: work.orders, extent, network: net, districts: plan.districts,
    level: site.level + 1 };
}

// Every line drawn with the railway pattern, piece by piece, its curves joined.
async function drawLines(server: MinecraftServer, net: Network) {
  const out: Record<string, unknown> = {};
  railKept = [];
  for (const line of net.lines) {
    const drawn = await drawRailway(server, piecesOf(line), line.name.toLowerCase());
    for (const [x, y, z] of drawn.track) for (let up = 0; up <= 3; up++) railKept.push([x, y + up, z]);
    railKept.push(...drawn.ballast);
    out[line.name] = { ok: drawn.ok, pieces: drawn.pieces, cells: drawn.cells, refused: drawn.refused, unjoined: drawn.unjoined,
      track: drawn.track.length };
    if (!drawn.ok) throw new Error(`the ${line.name} line could not be drawn: ${drawn.refused.join("; ")} (${drawn.unjoined} joints unjoined)`);
  }
  return out;
}
