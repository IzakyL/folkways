// The long work the colony is given on the land as it lies, each commissioned the way a player draws it: a quarry cut
// open in the mountain the railway runs out to (a shaft mine where there is no railway), and in the city a stretch of wall beyond its last street, an arcade along
// a street, a stone bridge over the nearest river and a dock. Every knob that names a material names one the colony can
// make from its own timber and stone. Pure planning: the bench files them.
import type { Box, Lot, Road, Terrain } from "./town.ts";
import { railBands, type Network } from "./network.ts";

export type Cell = { x: number; y: number; z: number };
export type Order = { pattern: string; name: string; points?: Cell[]; min?: Cell; max?: Cell; knobs?: Record<string, unknown>; why: string };
/** The mine's ground, as the plan answers it: where it stands, its halt and the lots by it. */
export type Works = { name: string; site: { x: number; z: number }; box: Box; halt: Box; y: number;
  door: { x: number; z: number; out: { x: number; z: number } }; lots: Array<{ box: Box }> };
/** A mine's site, and the footpath up to it from the halt: its cells, and the ground's height at both its ends. */
export type Dig = { min: Cell; max: Cell; y: number; x: number; z: number; path: Road };

const MINE_HALF = 5;
/**
 * Out at the railway's far end the mountain is quarried in the open, not mined by shaft: a shaft is dug a few cells
 * at a time, by a hand or two, where a quarry's whole face is work at once, and gives a trainload somewhere to go.
 * Its half side, how many there are, and how far from the halt their middles are looked for.
 */
const QUARRY_HALF = 18;
const MINES = 1, DIG_NEAR = QUARRY_HALF + 10, DIG_FAR = 110;
/** How much the ground under a quarry may rise from its lowest to its highest. */
const QUARRY_RELIEF = 22;
/** How far up the mountain from the halt a mine is the better for standing. */
const DIG_RISE = 20;

/** The materials each pattern is built in, all of the colony's own making. */
export const MAKEABLE = {
  mine: { y: 16, pairs: 6, branch: 16, timber: ["minecraft:oak_log"], planks: ["minecraft:oak_planks"],
    stairs: ["minecraft:oak_stairs"], rail: ["minecraft:oak_fence"], gate: ["minecraft:oak_fence_gate"], seal: ["minecraft:cobblestone"] },
  arch_bridge: { width: 5 },
  arcade: { lights: false, pillar: ["minecraft:oak_log"], beam: ["minecraft:oak_planks"], tiles: ["minecraft:stone_bricks"],
    ridge: ["minecraft:polished_andesite"], fence: ["minecraft:oak_fence"] },
  city_wall: { height: 6, thickness: 3, spacing: 16, gate: true, timber: ["minecraft:oak_log"], panel: ["minecraft:oak_planks"],
    roof: ["minecraft:stone_bricks"] },
  dock: { width: 3, head: "t", deck: ["minecraft:oak_planks"], beam: ["minecraft:oak_log"], pile: ["minecraft:oak_log"], rail: ["minecraft:oak_fence"] },
} as const;

/** Where the town is busy: lots, roads and track, for keeping the long work off them. */
class Busy {
  private cells = new Set<string>();
  constructor(lots: Lot[], roads: Road[], net: Network | null, railPad = 2) {
    for (const l of lots) this.box(l.box, 4);
    for (const r of roads) for (let i = 0; i + 1 < r.points.length; i++) {
      const [a, b] = [r.points[i], r.points[i + 1]], n = Math.max(1, Math.ceil(Math.hypot(b[0] - a[0], b[2] - a[2])));
      for (let k = 0; k <= n; k++) this.box({ minX: Math.round(a[0] + (b[0] - a[0]) * k / n), maxX: Math.round(a[0] + (b[0] - a[0]) * k / n),
        minZ: Math.round(a[2] + (b[2] - a[2]) * k / n), maxZ: Math.round(a[2] + (b[2] - a[2]) * k / n) }, r.width);
    }
    if (net) for (const b of railBands(net, railPad)) this.box(b, 0);
  }
  private box(b: Box, pad: number) {
    for (let x = b.minX - pad; x <= b.maxX + pad; x++) for (let z = b.minZ - pad; z <= b.maxZ + pad; z++) this.cells.add(`${x},${z}`);
  }
  has(x: number, z: number) { return this.cells.has(`${x},${z}`); }
  any(b: Box) {
    for (let x = b.minX; x <= b.maxX; x++) for (let z = b.minZ; z <= b.maxZ; z++) if (this.has(x, z)) return true;
    return false;
  }
}

/** How near the city its own long work is looked for: the bridge, and without a railway the mine. */
const NEAR = 200, FAR = 300;

export function planOrders(t: Terrain, centre: { x: number; z: number }, city: Box, lots: Lot[], roads: Road[], net: Network | null,
                           docks: Array<{ from: number[]; to: number[] }>, digs: Dig[] | null) {
  const busy = new Busy(lots, roads, net);
  const orders: Order[] = [];
  const skipped: Array<{ what: string; why: string }> = [];
  const mines = digs ?? [mineSite(t, centre, Math.hypot(city.maxX - centre.x, city.maxZ - centre.z) + 20, NEAR + 100, busy)].filter((m) => !!m);
  mines.forEach((mine, i) => orders.push({ pattern: "folkways:mine", name: i ? `Mine ${i + 1}` : "Mine", min: mine.min, max: mine.max,
    ...(digs ? { pattern: "folkways:quarry", name: i ? `Quarry ${i + 1}` : "Quarry", knobs: { depth: 3 } } : { knobs: MAKEABLE.mine }), why: digs ? `up the mountain from the mine's halt (${mine.y})` : `the highest free ground past the city's edge (${mine.y})` }));
  if (!mines.length) skipped.push({ what: "mine", why: "no free high ground past the city's edge" });
  // The bridge as near the city as a river allows: within NEAR, or failing that a little further out.
  const bridge = bridgeSite(t, centre, busy, NEAR) ?? bridgeSite(t, centre, busy, FAR);
  if (bridge) orders.push({ pattern: "folkways:arch_bridge", name: "River bridge", points: bridge, knobs: MAKEABLE.arch_bridge,
    why: "the narrowest river crossing near the city" });
  else skipped.push({ what: "bridge", why: `no river narrow enough within ${FAR} of the city` });
  const arcade = arcadeSite(city, centre, roads);
  if (arcade) orders.push({ pattern: "folkways:arcade", name: "Market arcade", points: arcade, knobs: MAKEABLE.arcade,
    why: "a street by the middle of the city" });
  else skipped.push({ what: "arcade", why: "no street runs straight long enough" });
  // The wall east of the city (the railway runs along its west), just past the ground eased round it.
  const half = Math.ceil((city.maxX - city.minX) / 2);
  const wall = wallAcross(t, centre, 0, half + 18, half + 80, busy);
  if (wall) orders.push({ pattern: "folkways:city_wall", name: "City wall", points: wall, knobs: MAKEABLE.city_wall,
    why: "beyond the city's east side, where nothing else stands" });
  else skipped.push({ what: "wall", why: "no free ground east of the city" });
  const dock = docks[0];
  if (dock) orders.push({ pattern: "folkways:dock", name: "New dock",
    points: [{ x: dock.from[0], y: dock.from[1], z: dock.from[2] }, { x: dock.to[0], y: dock.to[1], z: dock.to[2] }], knobs: MAKEABLE.dock,
    why: "a shore the laid docks left free" });
  return { orders, skipped };
}

/**
 * Where the quarry goes out at the mountain: a square of dry hillside up the slope from the halt, the higher the better
 * and the nearer the better, with a footpath to it that climbs no more than a block a step, crosses no track and
 * keeps off the lots by the halt. None when the mountain offers no such ground.
 */
export function digSites(t: Terrain, works: Works, net: Network | null): Dig[] {
  const rail = new Busy([], [], net, 4);
  const from = { x: works.door.x, z: works.door.z }, top = works.y - 1;
  const kept = [works.halt, ...works.lots.map((l) => l.box)].map((b) => ({ minX: b.minX - 3, maxX: b.maxX + 3, minZ: b.minZ - 3, maxZ: b.maxZ + 3 }));
  const within = (b: Box, x: number, z: number) => x >= b.minX && x <= b.maxX && z >= b.minZ && z <= b.maxZ;
  const all: Array<Dig & { score: number }> = [];
  for (let x = works.box.minX + QUARRY_HALF; x <= works.box.maxX - QUARRY_HALF; x += 3) for (let z = works.box.minZ + QUARRY_HALF; z <= works.box.maxZ - QUARRY_HALF; z += 3) {
    const far = Math.hypot(x - from.x, z - from.z);
    if (far < DIG_NEAR || far > DIG_FAR) continue;
    const b: Box = { minX: x - QUARRY_HALF, maxX: x + QUARRY_HALF, minZ: z - QUARRY_HALF, maxZ: z + QUARRY_HALF };
    if (!t.has(b.minX - 4, b.minZ - 4) || !t.has(b.maxX + 4, b.maxZ + 4)) continue;
    let ok = true, hi = -Infinity, lo = Infinity;
    for (let i = b.minX - 4; i <= b.maxX + 4 && ok; i++) for (let j = b.minZ - 4; j <= b.maxZ + 4 && ok; j++) {
      ok = !rail.has(i, j) && !kept.some((k) => within(k, i, j)) && !(within(b, i, j) && t.wet(i, j));
      if (within(b, i, j)) { hi = Math.max(hi, t.ground(i, j)); lo = Math.min(lo, t.ground(i, j)); }
    }
    const y = t.ground(x, z);
    if (!ok || hi - lo > QUARRY_RELIEF || y < top) continue;
    // The path: straight from the halt's door to the mine's nearest edge, a cell at a time along x or z.
    const end = { x: Math.max(b.minX - 1, Math.min(b.maxX + 1, from.x)), z: Math.max(b.minZ - 1, Math.min(b.maxZ + 1, from.z)) };
    const cells = lineOf(from, end);
    if (cells.length < 2 || Math.abs(t.ground(end.x, end.z) - top) > cells.length - 2) continue;
    if (cells.slice(3).some(([cx, cz]) => !t.has(cx, cz) || t.wet(cx, cz) || rail.has(cx, cz) || kept.slice(1).some((k) => within(k, cx, cz)))) continue;
    all.push({ x, z, y, min: { x: b.minX, y, z: b.minZ }, max: { x: b.maxX, y, z: b.maxZ }, score: Math.min(y - top, DIG_RISE) * 1.5 - far * 0.25,
      path: { name: "mine-path", width: 3, points: [], cells, ends: [top, t.ground(end.x, end.z)] } });
  }
  const picked: Dig[] = [];
  for (const { score: _, ...c } of all.sort((p, q) => q.score - p.score)) {
    if (picked.every((p) => Math.hypot(p.x - c.x, p.z - c.z) >= 4 * QUARRY_HALF + 8)) picked.push({ ...c, path: { ...c.path, name: `mine-path-${picked.length}` } });
    if (picked.length === MINES) break;
  }
  return picked;
}

/** The cells of a straight line between two points, each a step along x or z from the last. */
function lineOf(a: { x: number; z: number }, b: { x: number; z: number }): Array<[number, number]> {
  const out: Array<[number, number]> = [[a.x, a.z]];
  const dx = Math.abs(b.x - a.x), dz = Math.abs(b.z - a.z), sx = Math.sign(b.x - a.x), sz = Math.sign(b.z - a.z);
  let x = a.x, z = a.z, ix = 0, iz = 0;
  while (ix < dx || iz < dz) {
    if (iz >= dz || (ix < dx && (ix + 0.5) * dz < (iz + 0.5) * dx)) { x += sx; ix++; } else { z += sz; iz++; }
    out.push([x, z]);
  }
  return out;
}

/** The highest free 11x11 of dry ground `from`–`to` out from a point. */
function mineSite(t: Terrain, c: { x: number; z: number }, from: number, to: number, busy: Busy) {
  let best: { min: Cell; max: Cell; y: number; x: number; z: number } | undefined;
  for (let r = from; r <= to; r += 4) for (let a = 0; a < 360; a += r ? Math.max(2, Math.round(360 / (r * 1.5))) : 360) {
    const x = Math.round(c.x + r * Math.cos(a * Math.PI / 180)), z = Math.round(c.z + r * Math.sin(a * Math.PI / 180));
    const b: Box = { minX: x - MINE_HALF, maxX: x + MINE_HALF, minZ: z - MINE_HALF, maxZ: z + MINE_HALF };
    if (!t.has(b.minX, b.minZ) || !t.has(b.maxX, b.maxZ) || busy.any({ minX: b.minX - 4, maxX: b.maxX + 4, minZ: b.minZ - 4, maxZ: b.maxZ + 4 })) continue;
    let wet = false, hi = -Infinity, lo = Infinity;
    for (let i = b.minX; i <= b.maxX && !wet; i++) for (let j = b.minZ; j <= b.maxZ; j++) {
      if (t.wet(i, j)) { wet = true; break; }
      hi = Math.max(hi, t.ground(i, j)); lo = Math.min(lo, t.ground(i, j));
    }
    if (wet || hi - lo > 6) continue;
    const y = t.ground(x, z);
    if (!best || y > best.y) best = { x, z, y, min: { x: b.minX, y, z: b.minZ }, max: { x: b.maxX, y, z: b.maxZ } };
  }
  return best;
}

const DIRS = [[1, 0], [1, 1], [0, 1], [-1, 1], [-1, 0], [-1, -1], [0, -1], [1, -1]];

/** The shortest dry-to-dry crossing of 8–40 blocks of water within `reach` of the centre, its banks free. */
function bridgeSite(t: Terrain, c: { x: number; z: number }, busy: Busy, reach: number): Cell[] | null {
  let best: { pts: Cell[]; score: number } | undefined;
  for (let x = c.x - reach; x <= c.x + reach; x += 2) for (let z = c.z - reach; z <= c.z + reach; z += 2) {
    if (!t.has(x, z) || t.wet(x, z) || busy.has(x, z)) continue;
    for (const [dx, dz] of [[1, 0], [0, 1]]) {
      if (!t.wet(x + dx, z + dz)) continue;
      let k = 1;
      while (k <= 41 && t.has(x + dx * k, z + dz * k) && t.wet(x + dx * k, z + dz * k)) k++;
      if (k < 9 || k > 40 || !t.has(x + dx * k, z + dz * k)) continue;
      const far = { x: x + dx * k, z: z + dz * k };
      // The approaches either side dry, free, and for twelve blocks out within two blocks of the deck: the pattern lands
      // each end gently on its bank.
      let ok = true;
      const heights: number[] = [];
      for (let s = 0; s <= 12 && ok; s++) for (let w = -3; w <= 3 && ok; w++) {
        const p = { x: x - dx * s + dz * w, z: z - dz * s + dx * w }, q = { x: far.x + dx * s + dz * w, z: far.z + dz * s + dx * w };
        ok = t.has(p.x, p.z) && t.has(q.x, q.z) && !t.wet(p.x, p.z) && !t.wet(q.x, q.z) && !busy.has(p.x, p.z) && !busy.has(q.x, q.z);
        if (ok) heights.push(t.ground(p.x, p.z), t.ground(q.x, q.z));
      }
      if (!ok) continue;
      const y = Math.max(...heights);
      if (Math.min(...heights) < y - 2) continue;
      const score = k + Math.hypot(x - c.x, z - c.z) / 20;
      if (!best || score < best.score) best = { score, pts: [{ x: x - dx * 4, y, z: z - dz * 4 }, { x: far.x + dx * 4, y, z: far.z + dz * 4 }] };
    }
  }
  return best?.pts ?? null;
}

/** Fifty blocks from the middle of a city street, the nearest the middle of the city first: its ground is the city's. */
function arcadeSite(city: Box, c: { x: number; z: number }, roads: Road[]): Cell[] | null {
  const inside = (p: number[]) => p[0] >= city.minX && p[0] <= city.maxX && p[2] >= city.minZ && p[2] <= city.maxZ;
  let best: { a: number[]; b: number[]; out: number } | undefined;
  for (const r of roads) for (let i = 0; i + 1 < r.points.length; i++) {
    const a = r.points[i], b = r.points[i + 1];
    if (!inside(a) || !inside(b) || Math.hypot(b[0] - a[0], b[2] - a[2]) < 40) continue;
    const out = Math.hypot((a[0] + b[0]) / 2 - c.x, (a[2] + b[2]) / 2 - c.z);
    if (!best || out < best.out) best = { a, b, out };
  }
  if (!best) return null;
  // Clear of the crossings at both its ends.
  const length = Math.hypot(best.b[0] - best.a[0], best.b[2] - best.a[2]), keep = Math.min(50, length - 12) / length, from = (1 - keep) / 2;
  const at = (k: number) => ({ x: Math.round(best!.a[0] + (best!.b[0] - best!.a[0]) * k), y: best!.a[1],
    z: Math.round(best!.a[2] + (best!.b[2] - best!.a[2]) * k) });
  return [at(from), at(from + keep)];
}

/** A 64-block straight of wall `from`–`to` out from a point along `base` (or a little either side), square to that way. */
function wallAcross(t: Terrain, c: { x: number; z: number }, base: number, from: number, to: number, busy: Busy): Cell[] | null {
  for (let r = from; r <= to; r += 6) for (const turn of [0, -0.25, 0.25, -0.5, 0.5]) {
    const a = base + turn, ox = Math.cos(a), oz = Math.sin(a);
    // Square to the way out, along whichever axis or diagonal is nearest.
    const d = DIRS[(Math.round(Math.atan2(ox, -oz) / (Math.PI / 4)) + 8) % 8];
    const mx = Math.round(c.x + r * ox), mz = Math.round(c.z + r * oz);
    const half = d[0] && d[1] ? 22 : 32;
    const p = { x: mx - d[0] * half, z: mz - d[1] * half }, q = { x: mx + d[0] * half, z: mz + d[1] * half };
    let ok = true;
    for (let k = -half; k <= half && ok; k++) for (let w = -4; w <= 4 && ok; w++) {
      const x = mx + d[0] * k - d[1] * w, z = mz + d[1] * k + d[0] * w;
      ok = t.has(x, z) && !t.wet(x, z) && !busy.has(x, z);
    }
    if (ok) return [{ ...p, y: t.ground(p.x, p.z) }, { ...q, y: t.ground(q.x, q.z) }];
  }
  return null;
}
