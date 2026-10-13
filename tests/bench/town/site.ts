// Where the bench city goes, from the lie of the land around the player: the flattest dry ground that holds the whole
// grid, in green country, with a mountain to its west near enough for the railway to run out to the mine at its foot
// and far enough that riding there pays.
import { Lie, planNetwork, type Pt, type Relief } from "./network.ts";
import type { Box } from "./town.ts";

const STEP = 32;

export type SiteScore = {
  x: number; z: number; level: number; score: number; spread: number; range: number; temperate: number; water: number;
  backdrop: number; lines: number; length: number; climb: number; saved_s: number; buried: number;
};

/** How far around the city the land counts as its backdrop, and how much a block of its relief weighs. */
const BACKDROP = 432, BACKDROP_WEIGHT = 0.3;
/** The most the city's ground may vary (its standard deviation): it is all levelled to one height. */
const FLAT = 3.5;
/** Seconds a ride saves over the walk, per point of a site's score. */
const SAVED_PER_POINT = 60;
/**
 * How deep under the ground track counts as buried (cut or tunnel), the share of the railway that may be (a tunnel
 * through a ridge is what makes riding pay), and the blocks beyond that share that cost a point.
 */
const BURIED_DEPTH = 6, BURIED_SHARE = 0.25, BURIED_PER_POINT = 60;

/** The best site for a city `halfW` by `halfD` (half its sides) within `reach` of the player. */
export function pickSite(relief: Relief, from: { x: number; z: number }, reach: number, shape: { halfW: number; halfD: number }) {
  const lie = new Lie(relief);
  const scored: SiteScore[] = [];
  // The candidates sit on a grid fixed to the world, not to wherever in the spawn area the player landed, so the same
  // world always gets the same city.
  from = { x: Math.round(from.x / 128) * 128, z: Math.round(from.z / 128) * 128 };
  for (let dx = -reach; dx <= reach; dx += STEP) for (let dz = -reach; dz <= reach; dz += STEP) {
    const x = Math.round((from.x + dx) / 16) * 16, z = Math.round((from.z + dz) / 16) * 16;
    // The city's box, with the ground eased round it.
    const T: Box = { minX: x - shape.halfW - 16, maxX: x + shape.halfW + 16, minZ: z - shape.halfD - 16, maxZ: z + shape.halfD + 16 };
    const core = lie.samples(T);
    if (!core.length) continue;
    const land = core.filter((s) => s.kind !== 1);
    const landShare = land.length / core.length;
    if (landShare < 0.97) continue;
    const heights = land.map((s) => s.h).sort((a, b) => a - b);
    const level = heights[heights.length >> 1];
    if (level < relief.sea + 1 || level > relief.sea + 40) continue;
    const mean = heights.reduce((a, h) => a + h, 0) / heights.length;
    const spread = Math.sqrt(heights.reduce((a, h) => a + (h - mean) ** 2, 0) / heights.length);
    const range = heights[heights.length - 1] - heights[0];
    if (spread > FLAT || range > 16) continue;
    const shunned = land.filter((s) => s.kind === 2).length / land.length;
    const temperate = land.filter((s) => s.kind === 3).length / land.length;
    const around = lie.samples({ minX: T.minX - 64, maxX: T.maxX + 64, minZ: T.minZ - 64, maxZ: T.maxZ + 64 });
    const far = lie.samples({ minX: x - BACKDROP, maxX: x + BACKDROP, minZ: z - BACKDROP, maxZ: z + BACKDROP }).filter((s) => s.kind !== 1);
    const farMean = far.reduce((a, s) => a + s.h, 0) / Math.max(1, far.length);
    const backdrop = Math.sqrt(far.reduce((a, s) => a + (s.h - farMean) ** 2, 0) / Math.max(1, far.length));
    const water = around.filter((s) => s.kind === 1).length / Math.max(1, around.length);
    // Flat before all else, in green country, with water near for the docks and high land around
    // for the films to frame it against.
    const town = -spread * 8 - range - 30 * shunned - 60 * (1 - landShare) + 16 * temperate
      + (water >= 0.02 && water <= 0.3 ? 6 : 0) - Math.hypot(dx, dz) / 400 + BACKDROP_WEIGHT * Math.min(60, backdrop);
    scored.push({ x, z, level, score: town, spread: round(spread), range, temperate: round(temperate), water: round(water),
      backdrop: round(backdrop), lines: 0, length: 0, climb: 0, saved_s: 0, buried: 0 });
  }
  if (!scored.length) throw new Error("no flat, dry site within reach of the player");
  // The railway is the costly reading: only the better sites get one. A line to a mountain counts most; then the time
  // it saves over walking out to the mine; then a line that stays out in the open for the most part, where the films
  // see the trains, rather than in deep cuttings and tunnels; then a short and level one.
  scored.sort((a, b) => b.score - a.score);
  const shortlist = scored.slice(0, 120);
  for (const s of shortlist) {
    const net = planNetwork(relief, { x: s.x, z: s.z, level: s.level, ...shape });
    s.lines = net.lines.length;
    s.length = net.lines.reduce((n, l) => n + l.length, 0);
    s.climb = net.lines.reduce((n, l) => n + l.points.reduce((c, p, i) => c + Math.abs(p.y - l.points[(i + 1) % l.points.length].y), 0), 0);
    s.saved_s = net.lines.flatMap((l) => l.works).reduce((n, w) => n + Math.max(0, w.commute?.saved_s ?? 0), 0);
    s.buried = net.lines.reduce((n, l) => n + buried(lie, l.points), 0);
    const hidden = Math.max(0, s.buried - BURIED_SHARE * s.length);
    s.score += 40 * s.lines - s.length / 200 - s.climb / 10 + s.saved_s / SAVED_PER_POINT - hidden / BURIED_PER_POINT;
  }
  shortlist.sort((a, b) => b.score - a.score);
  // Runners-up stand well apart: a site turned down for its ground is not followed by its next-door neighbour.
  const apart: SiteScore[] = [];
  for (const s of shortlist) if (apart.every((p) => Math.hypot(p.x - s.x, p.z - s.z) >= 96)) apart.push(s);
  return { best: apart[0], runners_up: apart.slice(1, 8), candidates: scored.length };
}

/** Blocks of a closed line whose bed runs more than BURIED_DEPTH under the ground. */
function buried(lie: Lie, points: Pt[]) {
  let n = 0;
  for (let i = 0; i < points.length; i++) {
    const a = points[i], b = points[(i + 1) % points.length], steps = Math.max(Math.abs(b.x - a.x), Math.abs(b.z - a.z));
    for (let k = 0; k < steps; k += 4) {
      const t = k / steps;
      if (lie.height(a.x + (b.x - a.x) * t, a.z + (b.z - a.z) * t) > a.y + (b.y - a.y) * t + BURIED_DEPTH) n += 4;
    }
  }
  return n;
}

const round = (v: number) => Math.round(v * 100) / 100;
