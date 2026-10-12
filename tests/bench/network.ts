// The bench's railway, planned on the lie of the land: one loop from the city's station, on the city's west edge, out
// to a mine at the foot of a mountain west of it and back. The loop leaves the station northbound, runs round by the
// mine and comes back in from the south, so the city lies wholly east of its track. Between the two stations the line
// is routed over the land on an 8-block lattice, straight along an axis or a 45° diagonal, bending no more than 45° at
// a time with room for the curve, the way the railway pattern lays track: so it follows valleys and skirts hills.
import type { Box } from "./town.ts";
import type { Stop } from "../e2e/rail-footprint.ts";

export type Relief = {
  minX: number; minZ: number; step: number; width: number; depth: number; sea: number;
  height: number[]; kind: number[];
};

export type Pt = { x: number; y: number; z: number };

/** A pad beside a platform that the roads reach the station by; `y` is where one stands on it. */
export type Halt = { box: Box; door: "north" | "east" | "south" | "west"; y: number };

/** What riding to a works saves over walking there: the crow's distance, the walk's, and the seconds saved one way. */
export type Commute = { crow: number; walk: number; saved_s: number };

/** The mine's ground out at the mountain: its middle, its half side, the stop that serves it. */
export type Works = { name: string; x: number; z: number; half: number; stop: string; commute?: Commute };

/** The city as the railway sees it: its middle, the level of its ground, and half its width and depth. */
export type City = { x: number; z: number; level: number; halfW: number; halfD: number };

export type Line = {
  name: string;
  /** The track's corners, closed (the last runs back into the first), each at the height of the bed under the rail. */
  points: Pt[];
  /** Where the car is built: its first stop stands at (x + 1, z + 2), beside a northbound straight. */
  origin: Pt;
  /** Every stop in the order the train calls, the car's own first. */
  stops: Stop[];
  halts: Array<{ stop: string; halt: Halt; town: boolean }>;
  works: Works[];
  length: number;
};

export type Network = {
  centre: { x: number; z: number; y: number };
  lines: Line[];
  refused: Array<{ what: string; why: string }>;
};

/** A dwell long enough for a platform of riders, the seats a car carries. */
export const SEATS = 24;
/** Trains running each line, spread round it: each starts from its own stop, and signals past every station keep them
 * a stretch apart. */
export const TRAINS_PER_LINE = 2;
/** Trains each line runs: that count, or FOLKWAYS_LC_TRAINS to run fewer or more without raising the town anew. */
export const TRAINS = Number(process.env.FOLKWAYS_LC_TRAINS ?? TRAINS_PER_LINE);
const CAR = 8 + Math.ceil(SEATS / 2);
/** The lattice the lines are routed on, and how many lattice steps a run keeps between bends. */
const S = 8, RUN = 3;
/** A station's length. */
const STATION = 64;
/** How far west of the city's edge its track runs: the platform and the halt stand between. */
export const TRACK_OFF = 13;
/** How far out from the city's middle the mine may stand, its ground's half side, and how far its station stands back. */
const WORKS_NEAR = 440, WORKS_FAR = 900, WORKS_HALF = 72, STATION_BACK = 48;
/** How far the land must rise within reach of the mine to count as a mountain. */
const MOUNTAIN = 30, MOUNTAIN_REACH = 120;
/** Track keeps this far from other track, and the grade it may climb. */
const CLEAR = 10, GRADE = 1 / 10;
/**
 * How far the ground may lie above or below the town's level before a line is buried in it (or stood up on a bank),
 * and what each block beyond costs a lattice step: the bed keeps near the town's level, so a line run along a high
 * hillside would be one long tunnel, out of the films' sight.
 */
const OPEN = 6, OFF_LEVEL = 1.5;
/** How far the mine station's ground may lie above or below the city's. */
const RISE = 24;
/** Most of a line's length one drawing of the railway pattern takes. */
export const PIECE = 150;

// Lattice directions, clockwise from east on the map (z grows south): 0 east, 2 south, 4 west, 6 north.
const DIRS: Array<[number, number]> = [[1, 0], [1, 1], [0, 1], [-1, 1], [-1, 0], [-1, -1], [0, -1], [1, -1]];
const EAST = 0, SOUTH = 2, WEST = 4, NORTH = 6;

export class Lie {
  readonly r: Relief;
  constructor(r: Relief) { this.r = r; }
  private at(i: number, j: number) {
    const ci = Math.max(0, Math.min(this.r.width - 1, i)), cj = Math.max(0, Math.min(this.r.depth - 1, j));
    return ci * this.r.depth + cj;
  }
  inside(x: number, z: number) {
    const i = (x - this.r.minX) / this.r.step, j = (z - this.r.minZ) / this.r.step;
    return i >= 0 && j >= 0 && i <= this.r.width - 1 && j <= this.r.depth - 1;
  }
  /** The ground, interpolated between the grid's samples. */
  height(x: number, z: number) {
    const fi = (x - this.r.minX) / this.r.step, fj = (z - this.r.minZ) / this.r.step;
    const i = Math.floor(fi), j = Math.floor(fj), u = fi - i, v = fj - j;
    const h = (a: number, b: number) => this.r.height[this.at(a, b)];
    return (h(i, j) * (1 - u) + h(i + 1, j) * u) * (1 - v) + (h(i, j + 1) * (1 - u) + h(i + 1, j + 1) * u) * v;
  }
  kind(x: number, z: number) {
    return this.r.kind[this.at(Math.round((x - this.r.minX) / this.r.step), Math.round((z - this.r.minZ) / this.r.step))];
  }
  wet(x: number, z: number) { return this.kind(x, z) === 1; }
  /** Every grid sample inside a box. */
  samples(b: Box) {
    const out: Array<{ h: number; kind: number }> = [];
    const along = (lo: number, hi: number) => {
      const n = Math.floor((hi - lo) / this.r.step), from = (lo + hi) / 2 - (n * this.r.step) / 2;
      return Array.from({ length: n + 1 }, (_, i) => Math.round(from + i * this.r.step));
    };
    for (const x of along(b.minX, b.maxX)) for (const z of along(b.minZ, b.maxZ)) {
      if (!this.inside(x, z)) continue;
      out.push({ h: this.r.height[this.at(Math.round((x - this.r.minX) / this.r.step), Math.round((z - this.r.minZ) / this.r.step))], kind: this.kind(x, z) });
    }
    return out;
  }
}

// ---------------------------------------------------------------------------------------------------------
// Walking

/** A resident's pace on open ground, a train's average along its line, and the wait on a platform, in blocks and seconds. */
export const PACE = { walk: 5, train: 18, wait: 60 };
/** The rise over one grid step that a walker will not take: a cliff, gone round rather than climbed. */
const CLIFF = 14;
/** How much longer track runs than the crow flies, round its bends. */
const WINDING = 1.2;

/**
 * How far a resident walks from one spot to everywhere within reach, over the relief's grid: never across water or up
 * a cliff, and zigzagging up a slope (a slope costs as its steepness squared, so a gentle one is nearly free).
 */
export class Walk {
  private lie: Lie;
  private dist: Float64Array;
  private i0: number; private j0: number; private n: number;
  constructor(lie: Lie, from: { x: number; z: number }, reach: number) {
    this.lie = lie;
    const r = lie.r, R = Math.ceil(reach / r.step) + 1;
    const ci = Math.round((from.x - r.minX) / r.step), cj = Math.round((from.z - r.minZ) / r.step);
    this.i0 = ci - R; this.j0 = cj - R; this.n = 2 * R + 1;
    const n = this.n, dist = this.dist = new Float64Array(n * n).fill(Infinity);
    const ok = (i: number, j: number) => i >= 0 && j >= 0 && i < r.width && j < r.depth;
    const h = (i: number, j: number) => r.height[i * r.depth + j];
    const wet = (i: number, j: number) => r.kind[i * r.depth + j] === 1;
    if (!ok(ci, cj)) return;
    const heap = new Heap();
    dist[R * n + R] = 0; heap.push(0, R * n + R);
    while (heap.size) {
      const [d, k] = heap.pop()!;
      if (d > dist[k]) continue;
      const li = Math.floor(k / n), lj = k % n, i = li + this.i0, j = lj + this.j0;
      for (const [di, dj] of DIRS) {
        const ni = li + di, nj = lj + dj, gi = i + di, gj = j + dj;
        if (ni < 0 || nj < 0 || ni >= n || nj >= n || !ok(gi, gj) || wet(gi, gj)) continue;
        const dh = Math.abs(h(gi, gj) - h(i, j));
        if (dh > CLIFF) continue;
        const step = r.step * Math.hypot(di, dj);
        const nd = d + step * (1 + 30 * (dh / step) ** 2) + 2 * dh;
        if (nd < dist[ni * n + nj]) { dist[ni * n + nj] = nd; heap.push(nd, ni * n + nj); }
      }
    }
  }
  /** The walk to (x, z) in blocks, or Infinity when the land allows none. */
  to(x: number, z: number) {
    const r = this.lie.r;
    const i = Math.round((x - r.minX) / r.step) - this.i0, j = Math.round((z - r.minZ) / r.step) - this.j0;
    return i < 0 || j < 0 || i >= this.n || j >= this.n ? Infinity : this.dist[i * this.n + j];
  }
  /** What riding out to (x, z) saves over walking there, one way; nothing when it cannot be walked at all. */
  commute(from: { x: number; z: number }, x: number, z: number): Commute | undefined {
    const walk = this.to(x, z);
    if (!Number.isFinite(walk)) return undefined;
    const crow = Math.hypot(x - from.x, z - from.z);
    const saved = walk / PACE.walk - (PACE.wait + (crow * WINDING) / PACE.train);
    return { crow: Math.round(crow), walk: Math.round(walk), saved_s: Math.round(saved) };
  }
}

const median = (v: number[]) => { const s = [...v].sort((a, b) => a - b); return s.length ? s[Math.floor(s.length / 2)] : 0; };

// ---------------------------------------------------------------------------------------------------------
// Stations

/** A straight a station stands on: from a to b, running `dir` (an axis), with its stop. */
type Anchor = { a: { x: number; z: number }; b: { x: number; z: number }; dir: number; stop: Stop; town: boolean };

function anchor(name: string, mid: { x: number; z: number }, dir: number, side: 1 | -1, y: number, town: boolean, first = false): Anchor {
  const [dx, dz] = DIRS[dir];
  const half = STATION / 2;
  const a = { x: mid.x - dx * half, z: mid.z - dz * half }, b = { x: mid.x + dx * half, z: mid.z + dz * half };
  const axis = dx !== 0 ? "x" : "z";
  // The station stands toward the straight's far end, its car behind it; the car's own stop stands so the car
  // fits between it and the straight's start.
  const ahead = first ? half - 2 - CAR - 4 : half - 14;
  const tx = mid.x + dx * ahead, tz = mid.z + dz * ahead;
  const position = axis === "z" ? { x: tx + side, y, z: tz } : { x: tx, y, z: tz + side };
  const reverse = axis === "z" ? dz > 0 : dx < 0;
  return { a, b, dir, town, stop: { name, position, reverse, axis, side } };
}

/** The pad beside a station's platform, past its far strip, the length of the car, on the side away from the track. */
export function haltOf(s: Stop): Halt {
  const p = s.position, side = s.side ?? 1, back = s.axis === "x" ? (s.reverse ? 1 : -1) : (s.reverse ? -1 : 1);
  const along = s.axis === "x" ? p.x : p.z;
  const a0 = Math.min(along, along + back * (CAR - 2)), a1 = Math.max(along, along + back * (CAR - 2));
  const o0 = side * 4, o1 = side * 11;
  const box = s.axis === "x"
    ? { minX: a0, maxX: a1, minZ: p.z + Math.min(o0, o1), maxZ: p.z + Math.max(o0, o1) }
    : { minX: p.x + Math.min(o0, o1), maxX: p.x + Math.max(o0, o1), minZ: a0, maxZ: a1 };
  const door = s.axis === "x" ? (side > 0 ? "south" : "north") : (side > 0 ? "east" : "west");
  // The pad's top meets the platform's walk, which stands on the track's level.
  return { box, door, y: p.y + 1 };
}

// ---------------------------------------------------------------------------------------------------------
// Routing

class Heap {
  private a: Array<[number, number]> = [];
  push(k: number, v: number) {
    const a = this.a; a.push([k, v]);
    for (let i = a.length - 1; i > 0;) { const p = (i - 1) >> 1; if (a[p][0] <= a[i][0]) break; [a[p], a[i]] = [a[i], a[p]]; i = p; }
  }
  pop(): [number, number] | undefined {
    const a = this.a; if (!a.length) return undefined;
    const top = a[0], last = a.pop()!;
    if (a.length) {
      a[0] = last;
      for (let i = 0; ;) {
        const l = 2 * i + 1, r = l + 1; let m = i;
        if (l < a.length && a[l][0] < a[m][0]) m = l;
        if (r < a.length && a[r][0] < a[m][0]) m = r;
        if (m === i) break; [a[m], a[i]] = [a[i], a[m]]; i = m;
      }
    }
    return top;
  }
  get size() { return this.a.length; }
}

/** Where track already runs, on a 2-block grid, grown by a clearance (CLEAR unless said). */
class Taken {
  private cells = new Set<number>();
  private readonly clear: number;
  constructor(clear = CLEAR) { this.clear = clear; }
  private key(x: number, z: number) { return (Math.round(x / 2) + 50_000) * 100_000 + Math.round(z / 2) + 50_000; }
  mark(points: Array<{ x: number; z: number }>) {
    for (let i = 0; i + 1 < points.length; i++) {
      const a = points[i], b = points[i + 1], n = Math.max(1, Math.ceil(Math.hypot(b.x - a.x, b.z - a.z) / 2));
      for (let k = 0; k <= n; k++) {
        const x = a.x + (b.x - a.x) * k / n, z = a.z + (b.z - a.z) * k / n;
        for (let dx = -this.clear; dx <= this.clear; dx += 2) for (let dz = -this.clear; dz <= this.clear; dz += 2)
          if (dx * dx + dz * dz <= this.clear * this.clear) this.cells.add(this.key(x + dx, z + dz));
      }
    }
  }
  has(x: number, z: number) { return this.cells.has(this.key(x, z)); }
}

type Leash = (x: number, z: number) => boolean;

/**
 * The cheapest lattice route from one station's far end, running its way, to the next station's near end, arriving
 * its way: corners every RUN steps at the least, each bend 45°. Costs climb and water; keeps off track already laid
 * (save right at its own two ends) and inside the leash.
 */
function route(lie: Lie, from: Anchor, to: Anchor, taken: Taken, leash: Leash, level: number): Array<{ x: number; z: number }> | null {
  const ox = from.b.x, oz = from.b.z;
  const gi = (to.a.x - ox) / S, gj = (to.a.z - oz) / S;
  if (!Number.isInteger(gi) || !Number.isInteger(gj)) throw new Error(`${to.stop.name} is off ${from.stop.name}'s lattice`);
  const R = Math.max(Math.abs(gi), Math.abs(gj)) + 40;
  const W = 2 * R + 1;
  const states = W * W * 8 * (RUN + 1);
  const g = new Float64Array(states).fill(Infinity), came = new Int32Array(states).fill(-1);
  const id = (i: number, j: number, d: number, r: number) => (((i + R) * W + (j + R)) * 8 + d) * (RUN + 1) + r;
  const heap = new Heap();
  const start = id(0, 0, from.dir, RUN);
  g[start] = 0; heap.push(0, start);
  const near = (x: number, z: number, p: { x: number; z: number }) => Math.hypot(x - p.x, z - p.z) <= CLEAR + 4;
  let goal = -1, popped = 0;
  while (heap.size && popped++ < 3_000_000) {
    const [f, s] = heap.pop()!;
    const r = s % (RUN + 1), d = Math.floor(s / (RUN + 1)) % 8, cell = Math.floor(s / (RUN + 1) / 8);
    const i = Math.floor(cell / W) - R, j = (cell % W) - R;
    if (f - (Math.abs(i - gi) + Math.abs(j - gj)) * S * 0.7 > g[s] + 1e-6) continue;
    if (i === gi && j === gj && d === to.dir) { goal = s; break; }
    for (const turn of [0, -1, 1]) {
      if (turn !== 0 && r < RUN) continue;
      const nd = (d + turn + 8) % 8;
      const [dx, dz] = DIRS[nd];
      const ni = i + dx, nj = j + dz;
      if (Math.abs(ni) > R || Math.abs(nj) > R) continue;
      const x0 = ox + i * S, z0 = oz + j * S, x1 = ox + ni * S, z1 = oz + nj * S;
      if (!lie.inside(x1, z1) || !leash(x1, z1)) continue;
      // Track already laid, save near this route's own ends.
      let blocked = false;
      for (const k of [0.5, 1]) {
        const x = x0 + (x1 - x0) * k, z = z0 + (z1 - z0) * k;
        if (taken.has(x, z) && !near(x, z, from.b) && !near(x, z, to.a)) blocked = true;
      }
      if (blocked) continue;
      const len = dx && dz ? S * Math.SQRT2 : S;
      const climb = Math.abs(lie.height(x1, z1) - lie.height(x0, z0));
      const off = Math.max(0, Math.abs(lie.height(x1, z1) - level) - OPEN) * OFF_LEVEL;
      const cost = len + 2.5 * climb + Math.max(0, climb - len * GRADE) * 6 + off + (lie.wet(x1, z1) ? 30 : 0) + (turn ? 3 : 0);
      const nr = turn ? 1 : Math.min(RUN, r + 1);
      const n = id(ni, nj, nd, nr);
      if (g[s] + cost < g[n]) {
        g[n] = g[s] + cost; came[n] = s;
        heap.push(g[n] + (Math.abs(ni - gi) + Math.abs(nj - gj)) * S * 0.7, n);
      }
    }
  }
  if (goal < 0) return null;
  const cells: Array<{ x: number; z: number }> = [];
  for (let s = goal; s >= 0; s = came[s]) {
    const cell = Math.floor(s / (RUN + 1) / 8);
    cells.push({ x: ox + (Math.floor(cell / W) - R) * S, z: oz + ((cell % W) - R) * S });
    if (s === start) break;
  }
  return cells.reverse();
}

/** Only the corners of a run of lattice points: where its heading changes. */
function corners(points: Array<{ x: number; z: number }>) {
  const out = [points[0]];
  for (let i = 1; i + 1 < points.length; i++) {
    const a = out[out.length - 1], p = points[i], b = points[i + 1];
    const d1 = [Math.sign(p.x - a.x), Math.sign(p.z - a.z)], d2 = [Math.sign(b.x - p.x), Math.sign(b.z - p.z)];
    if (d1[0] !== d2[0] || d1[1] !== d2[1]) out.push(p);
  }
  out.push(points[points.length - 1]);
  return out;
}

// ---------------------------------------------------------------------------------------------------------
// Heights

/** Run length along a leg in the railway pattern's steps (a diagonal step is one block across and one along). */
const stepsOf = (a: { x: number; z: number }, b: { x: number; z: number }) => Math.max(Math.abs(b.x - a.x), Math.abs(b.z - a.z));

/**
 * Bed heights for a closed run of corners: each corner near its own ground, every corner of a straight that holds a
 * station at the station's height, and no leg climbing more than its room allows. The railway pattern lays a climb
 * as ramps in the middle of a leg, clear of the curves at both its ends, and takes no corner that does not turn.
 */
function heights(lie: Lie, ring: Corner[]): Pt[] {
  const n = ring.length;
  const want = ring.map((p) => p.fixed ?? Math.round(median([lie.height(p.x, p.z), lie.height(p.x + 8, p.z), lie.height(p.x - 8, p.z),
    lie.height(p.x, p.z + 8), lie.height(p.x, p.z - 8)])));
  const reach = (i: number) => {
    if (ring[i].level) return 0;
    const steps = stepsOf(ring[i], ring[(i + 1) % n]), room = steps - 22;
    // The most the pattern climbs in one ramp across that room: four blocks of run a block under four, three beyond.
    let most = 0;
    while (most < Math.max(1, Math.floor(steps * GRADE)) && rampOf(most + 1) <= room) most++;
    return most;
  };
  // A level leg's two corners share one height: each group of corners joined by level legs is one unknown.
  const group = ring.map((_, i) => i);
  const find = (i: number): number => group[i] === i ? i : (group[i] = find(group[i]));
  for (let i = 0; i < n; i++) if (ring[i].level) group[find(i)] = find((i + 1) % n);
  const fixedOf = new Map<number, number>();
  for (let i = 0; i < n; i++) if (ring[i].fixed !== undefined) fixedOf.set(find(i), ring[i].fixed!);
  const members = new Map<number, number[]>();
  for (let i = 0; i < n; i++) members.set(find(i), [...(members.get(find(i)) ?? []), i]);
  // Start level with the fixed corners (the city's station), which every leg allows, then move each group toward
  // its land only as far as its legs let it: the line stays buildable at every step.
  const start = fixedOf.size ? [...fixedOf.values()][0] : Math.round(median(want));
  const h = new Map<number, number>();
  for (const g of members.keys()) h.set(g, fixedOf.get(g) ?? start);
  for (let pass = 0; pass < 600; pass++) {
    let moved = false;
    for (const [g, list] of members) {
      if (fixedOf.has(g)) continue;
      let lo = -Infinity, hi = Infinity;
      for (const i of list) {
        const prev = (i + n - 1) % n, next = (i + 1) % n;
        if (find(prev) !== g) { lo = Math.max(lo, h.get(find(prev))! - reach(prev)); hi = Math.min(hi, h.get(find(prev))! + reach(prev)); }
        if (find(next) !== g) { lo = Math.max(lo, h.get(find(next))! - reach(i)); hi = Math.min(hi, h.get(find(next))! + reach(i)); }
      }
      const goal = Math.round(median(list.map((i) => want[i])));
      const v = Math.max(lo, Math.min(hi, goal));
      if (v !== h.get(g)) { h.set(g, v); moved = true; }
    }
    if (!moved) break;
  }
  const y = ring.map((_, i) => h.get(find(i))!);
  const out = ring.map((p, i) => ({ x: p.x, y: y[i], z: p.z }));
  for (let i = 0; i < n; i++) {
    const a = out[i], b = out[(i + 1) % n];
    if (Math.abs(a.y - b.y) > reach(i)) throw new Error(`the leg ${a.x},${a.z} -> ${b.x},${b.z} climbs ${b.y - a.y} with room for ${reach(i)}`);
  }
  return out;
}

/** The run of track the railway pattern needs for one ramp climbing `rise` blocks along an axis. */
const rampOf = (rise: number) => Math.max(6, rise < 4 ? rise * 4 : rise * 3);

/** A corner of a line: `fixed` holds its height, `level` keeps the leg after it level (a station stands on it). */
type Corner = { x: number; z: number; fixed?: number; level?: boolean };

/** Corners only: a run of points along one heading keeps its two ends; a run that holds a station stays level. */
function straighten(ring: Array<{ x: number; z: number; fixed?: number; station?: boolean }>): Corner[] {
  const n = ring.length;
  const dir = (a: { x: number; z: number }, b: { x: number; z: number }) => `${Math.sign(b.x - a.x)},${Math.sign(b.z - a.z)}`;
  const turns = (i: number) => dir(ring[(i + n - 1) % n], ring[i]) !== dir(ring[i], ring[(i + 1) % n]);
  const corners = ring.map((_, i) => i).filter(turns);
  const out: Corner[] = corners.map((i) => ({ x: ring[i].x, z: ring[i].z, fixed: ring[i].fixed }));
  corners.forEach((c, k) => {
    const next = corners[(k + 1) % corners.length];
    for (let i = c; i !== next; i = (i + 1) % n) {
      if (ring[i].station) out[k].level = true;
      const f = ring[i].fixed;
      if (f === undefined) continue;
      out[k].fixed ??= f;
      out[(k + 1) % out.length].fixed ??= f;
    }
  });
  return out;
}

// ---------------------------------------------------------------------------------------------------------
// The mine

type MineSite = { x: number; z: number; foot: { x: number; z: number }; score: number; rise: number; commute: Commute };

/**
 * The best few places for the mine west of the city, best first, each well clear of the ones before it: dry ground at
 * the foot of a mountain, where a station can stand near the city's level and the land climbs steeply beyond it. The
 * farther round the land makes a resident walk to it the better, as that is what the railway is for; it must still be
 * walkable, since a mine only the train reaches stands idle whenever the trains do.
 */
function mineSites(lie: Lie, walk: Walk, city: City) {
  const all: MineSite[] = [];
  for (let r = WORKS_NEAR; r <= WORKS_FAR; r += 24) for (let a = 100; a <= 260; a += 5) {
    const t = a * Math.PI / 180, ux = Math.cos(t), uz = -Math.sin(t);
    const x = Math.round(city.x + r * ux), z = Math.round(city.z + r * uz);
    const s = lie.samples({ minX: x - WORKS_HALF, maxX: x + WORKS_HALF, minZ: z - WORKS_HALF, maxZ: z + WORKS_HALF });
    if (s.length < 20 || s.filter((q) => q.kind !== 1).length / s.length < 0.92) continue;
    // The station stands back toward the city, on ground near the city's level and gentle enough for its straight.
    const foot = { x: Math.round(x - ux * STATION_BACK), z: Math.round(z - uz * STATION_BACK) };
    const at = lie.samples({ minX: foot.x - 40, maxX: foot.x + 40, minZ: foot.z - 40, maxZ: foot.z + 40 });
    if (at.length < 9 || at.some((q) => q.kind === 1)) continue;
    const base = median(at.map((q) => q.h));
    const rough = Math.max(...at.map((q) => q.h)) - Math.min(...at.map((q) => q.h));
    if (Math.abs(base - city.level) > RISE || rough > 16) continue;
    const up = lie.samples({ minX: x - MOUNTAIN_REACH, maxX: x + MOUNTAIN_REACH, minZ: z - MOUNTAIN_REACH, maxZ: z + MOUNTAIN_REACH });
    const rise = Math.max(...up.map((q) => q.h)) - base;
    // The mine's own ground climbs out of the station's, without being a cliff over it.
    const climb = lie.height(x, z) - base;
    if (rise < MOUNTAIN || climb < 8 || climb > 40) continue;
    const commute = walk.commute(city, x, z);
    if (!commute) continue;
    const score = Math.min(rise, 90) / 2 - rough - Math.abs(base - city.level) / 2 - r / 80 + commute.saved_s / 20;
    all.push({ x, z, foot, score, rise: Math.round(rise), commute });
  }
  const picked: MineSite[] = [];
  for (const c of all.sort((p, q) => q.score - p.score)) {
    if (picked.every((p) => Math.hypot(p.x - c.x, p.z - c.z) >= 80)) picked.push(c);
    if (picked.length === 4) break;
  }
  return picked;
}

// ---------------------------------------------------------------------------------------------------------
// The network

/** The track of every line laid down: its corners in order, ending where it began. */
export function trackOf(line: Line) { return [...line.points, line.points[0]]; }

/**
 * The railway the land allows a city: its station on the west edge, the mine line looped out to a mountain and back,
 * or no line, with what was refused and why.
 */
export function planNetwork(relief: Relief, city: City): Network {
  const lie = new Lie(relief);
  const cx = city.x, cz = city.z;
  // The rail lies in the city's ground, so the platform and the halt stand level with its streets.
  const bed = city.level - 1;
  const trackX = cx - city.halfW - TRACK_OFF;
  const refused: Network["refused"] = [];
  const centre = { x: cx, z: cz, y: city.level };
  const snap = (x: number, z: number) => ({ x: trackX + Math.round((x - trackX) / S) * S, z: cz + Math.round((z - cz) / S) * S });
  const walk = new Walk(lie, { x: cx, z: cz }, WORKS_FAR + WORKS_HALF);
  const sites = mineSites(lie, walk, city);
  if (!sites.length) return { centre, lines: [], refused: [{ what: "Mine line", why: `no mountain with a dry foot ${WORKS_NEAR}–${WORKS_FAR} west of the city` }] };
  let failed = "";
  for (const at of sites) for (const dir of headingsAt(at)) {
    const tried = loopThrough(at, dir);
    if ("line" in tried) return { centre, lines: [tried.line], refused };
    failed = tried.failed;
  }
  return { centre, lines: [], refused: [{ what: "Mine line", why: failed }] };

  // The ways the mine's station might run, likeliest first: the way the loop comes round past it (anticlockwise on
  // the map: out north, back in from the south), then the other axis the same way round.
  function headingsAt(at: MineSite) {
    const t = Math.atan2(cz - at.z, at.x - cx), tx = -Math.sin(t), tz = -Math.cos(t);
    const along = Math.abs(tx) > Math.abs(tz) ? [tx > 0 ? EAST : WEST, tz > 0 ? SOUTH : NORTH] : [tz > 0 ? SOUTH : NORTH, tx > 0 ? EAST : WEST];
    return [...new Set(along)];
  }

  function loopThrough(at: MineSite, dir: number): { line: Line } | { failed: string } {
    // The city's station: a northbound straight, its platform and halt on the east, toward the city.
    const station = anchor("City", { x: trackX, z: cz }, NORTH, 1, bed + 1, true, true);
    const [dx, dz] = DIRS[dir];
    // Right of the heading is (-dz, dx); the mine's side is the one facing the mountain, away from the city.
    const right = { x: -dz, z: dx };
    const outward = right.x * (at.x - cx) + right.z * (at.z - cz) > 0 ? 1 : -1;
    const sideSign = (dir === EAST || dir === WEST) ? (right.z * outward > 0 ? 1 : -1) : (right.x * outward > 0 ? 1 : -1);
    const mid = snap(at.x - right.x * outward * STATION_BACK, at.z - right.z * outward * STATION_BACK);
    const mine = anchor("Mine", mid, dir, sideSign as 1 | -1, Math.round(lie.height(mid.x, mid.z)), false);
    const order = [station, mine];
    // Each station's straight is track from the start; the route between keeps clear of it. The way out is never
    // crossed by the way back: a line crossing itself cuts its own track (a straight rail over a diagonal one is no
    // crossing Create makes), leaving it in pieces no train is timetabled on.
    const own = new Taken(), routed = new Taken();
    for (const st of order) own.mark([st.a, st.b]);
    const leash: Leash = (x) => x <= trackX;
    const ring: Array<{ x: number; z: number; fixed?: number; station?: boolean }> = [];
    for (let i = 0; i < order.length; i++) {
      const from = order[i], to = order[(i + 1) % order.length];
      const both = { has: (x: number, z: number) => routed.has(x, z) || ownExcept(own, order, i, x, z) } as Taken;
      const path = route(lie, from, to, both, leash, city.level);
      if (!path) return { failed: `no way over the land from ${from.stop.name} to ${to.stop.name}` };
      // The city's station holds the city's level; the mine's only keeps its own straight level.
      const fixed = from === station ? bed : undefined;
      ring.push({ ...from.a, fixed, station: true }, { ...from.b, fixed, station: true });
      ring.push(...corners(path).slice(1, -1));
      routed.mark(path);
    }
    let points: Pt[];
    try { points = heights(lie, straighten(dedupe(ring))); } catch (e) { return { failed: String((e as Error).message) }; }
    const over = selfCrossings({ points } as Line);
    if (over.length) return { failed: `the line would run over itself at ${over.map((p) => `${p.x},${p.z}`).join(" ")}` };
    for (const st of order) {
      const leg = points.findIndex((p, i) => onLeg(p, points[(i + 1) % points.length], st.a) && onLeg(p, points[(i + 1) % points.length], st.b));
      if (leg < 0) return { failed: `${st.stop.name} is off its own line` };
      // The station block stands on the track's level, one above the bed.
      st.stop.position = { ...st.stop.position, y: points[leg].y + 1 };
    }
    const first = station.stop.position;
    const line: Line = {
      name: "Mine", points, origin: { x: first.x - 1, y: first.y, z: first.z - 2 },
      stops: order.map((a) => a.stop),
      halts: order.map((a) => ({ stop: a.stop.name, halt: haltOf(a.stop), town: a.town })),
      works: [{ name: "Mine", x: at.x, z: at.z, half: WORKS_HALF, stop: "Mine", commute: at.commute }],
      length: lengthOf(points),
    };
    const clash = clashes({ centre, lines: [line], refused: [] });
    return clash.length ? { failed: clash.join("; ") } : { line };
  }
}

// Track already planned for this line, except the two stations this route runs between.
function ownExcept(own: Taken, order: Anchor[], i: number, x: number, z: number) {
  if (!own.has(x, z)) return false;
  const ends = [order[i], order[(i + 1) % order.length]];
  return !ends.some((a) => Math.hypot(x - a.a.x, z - a.a.z) <= CLEAR + STATION || Math.hypot(x - a.b.x, z - a.b.z) <= CLEAR + STATION);
}

function dedupe<T extends { x: number; z: number }>(ring: T[]) {
  const out: T[] = [];
  for (const p of ring) {
    const last = out[out.length - 1];
    if (last && last.x === p.x && last.z === p.z) { out[out.length - 1] = { ...last, ...p }; continue; }
    out.push(p);
  }
  if (out.length > 1 && out[0].x === out[out.length - 1].x && out[0].z === out[out.length - 1].z) out.pop();
  return out;
}

/** Whether p lies on the straight from a to b (ends included). */
function onLeg(a: { x: number; z: number }, b: { x: number; z: number }, p: { x: number; z: number }) {
  const n = stepsOf(a, b), k = stepsOf(a, p);
  if (k > n) return false;
  return a.x + Math.sign(b.x - a.x) * k === p.x && a.z + Math.sign(b.z - a.z) * k === p.z;
}

function lengthOf(points: Pt[]) {
  let n = 0;
  for (let i = 0; i < points.length; i++) n += stepsOf(points[i], points[(i + 1) % points.length]);
  return n;
}

/**
 * A line's track cut into drawings for the railway pattern: each starts and ends partway along a level straight (so
 * the pieces meet rail to rail, with no curve or climb at the seam) and runs no more than about PIECE blocks.
 */
export function piecesOf(line: Line): Pt[][] {
  const pts = line.points, n = pts.length;
  // A seam goes partway along a straight: two straight rails meet end to end there, two diagonal ones corner to
  // corner. Each seam is level, its height the leg's climb so far, and every stretch of the leg between corners and
  // seams must still have room for its share of the climb (the pattern ramps at most 1 in 3 over 30 blocks, clear of
  // a curve at either end). A long straight takes a seam every hundred blocks or so.
  const fits = (rise: number, length: number) => {
    if (rise === 0) return length >= 16;
    const room = Math.min(30, length - 12);
    return Math.max(6, Math.abs(rise) < 4 ? Math.abs(rise) * 4 : Math.abs(rise) * 3) <= room;
  };
  type Seam = { leg: number; at: Pt; along: number };
  const seams: Seam[] = [];
  let walked = 0;
  for (let i = 0; i < n; i++) {
    const a = pts[i], b = pts[(i + 1) % n], len = stepsOf(a, b);
    const parts = Math.max(2, Math.round(len / 100));
    const cut = Array.from({ length: parts - 1 }, (_, j) => Math.floor((len * (j + 1)) / parts));
    const ys = cut.map((k) => Math.round(a.y + ((b.y - a.y) * k) / len));
    const marks = [0, ...cut, len], heights = [a.y, ...ys, b.y];
    const ok = len >= 32 && marks.slice(1).every((m, j) => fits(heights[j + 1] - heights[j], m - marks[j]));
    if (ok) cut.forEach((k, j) => seams.push({ leg: i, along: walked + k,
      at: { x: a.x + Math.sign(b.x - a.x) * k, y: ys[j], z: a.z + Math.sign(b.z - a.z) * k } }));
    walked += len;
  }
  if (!seams.length) throw new Error(`${line.name} has no straight to begin its drawings at`);
  // From each cut, the farthest seam within PIECE, or the nearest one past it when none is.
  const s0 = seams[0], total = walked;
  const cuts = [0];
  for (let c = 0; c < seams.length;) {
    let next = c + 1;
    while (next + 1 < seams.length && seams[next + 1].along - seams[c].along <= PIECE) next++;
    if (next >= seams.length || total - (seams[c].along - s0.along) <= PIECE) break;
    cuts.push(next);
    c = next;
  }
  const out: Pt[][] = [];
  cuts.forEach((c, k) => {
    const from = seams[c], last = k === cuts.length - 1;
    const to = last ? s0 : seams[cuts[k + 1]];
    const piece: Pt[] = [from.at];
    // The corners between the two seams; none when both stand on one leg.
    if (to.leg !== from.leg || last) for (let i = (from.leg + 1) % n; ; i = (i + 1) % n) {
      piece.push(pts[i]);
      if (i === to.leg) break;
    }
    if (last) {
      // One block short of the first seam, so the closing piece meets the first rail to rail.
      const end = to.at, a = pts[to.leg];
      piece.push({ x: end.x - Math.sign(end.x - a.x), y: end.y, z: end.z - Math.sign(end.z - a.z) });
    } else piece.push(to.at);
    out.push(piece);
  });
  return out;
}

/** Boxes over every line's track, `pad` blocks either side, for keeping lots and roads off it. */
export function railBands(net: Network, pad = 4): Box[] {
  const out: Box[] = [];
  for (const l of net.lines) {
    const t = trackOf(l);
    for (let i = 0; i + 1 < t.length; i++) {
      const a = t[i], b = t[i + 1], n = Math.max(1, Math.ceil(stepsOf(a, b) / 4));
      for (let k = 0; k <= n; k++) {
        const x = Math.round(a.x + (b.x - a.x) * k / n), z = Math.round(a.z + (b.z - a.z) * k / n);
        out.push({ minX: x - pad - 3, maxX: x + pad + 3, minZ: z - pad - 3, maxZ: z + pad + 3 });
      }
    }
  }
  return out;
}

/** What would put a halt on track or two lines too near: each a sentence, none when the plan holds. */
export function clashes(net: Network): string[] {
  const out: string[] = [];
  const bands = railBands(net, 0);
  for (const l of net.lines) {
    const over = selfCrossings(l);
    if (over.length) out.push(`the ${l.name} line runs over itself at ${over.map((p) => `${p.x},${p.z}`).join(" ")}`);
  }
  for (const l of net.lines) for (const h of l.halts) for (const b of bands)
    if (!(h.halt.box.maxX < b.minX || h.halt.box.minX > b.maxX || h.halt.box.maxZ < b.minZ || h.halt.box.minZ > b.maxZ)) {
      out.push(`${h.stop}'s halt stands on track`);
      break;
    }
  return out;
}

/** Where a line's track runs over itself: cells two of its legs (not neighbours along it) both pass through. */
export function selfCrossings(line: Line): Array<{ x: number; z: number }> {
  const t = trackOf(line), by = new Map<string, number>(), out: Array<{ x: number; z: number }> = [];
  const legs = t.length - 1;
  for (let i = 0; i < legs; i++) for (let k = 0; k <= stepsOf(t[i], t[i + 1]); k++) {
    const x = t[i].x + Math.sign(t[i + 1].x - t[i].x) * k, z = t[i].z + Math.sign(t[i + 1].z - t[i].z) * k;
    const at = `${x},${z}`, was = by.get(at);
    if (was === undefined) { by.set(at, i); continue; }
    const apart = Math.min(Math.abs(i - was), legs - Math.abs(i - was));
    if (apart > 1) out.push({ x, z });
  }
  return out;
}
