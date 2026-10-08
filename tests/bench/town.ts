// The bench town, planned on real terrain: lots are fitted to the ground the survey read, levelled onto pads,
// joined by roads the road pattern grades, and built as timber halls. Everything here is pure planning; the
// Mason task lays what it answers.
export type Vec = { x: number; y: number; z: number };
export type Box = { minX: number; maxX: number; minZ: number; maxZ: number };

export type Survey = {
  minX: number; minZ: number; width: number; depth: number;
  ground: number[]; water: number[]; cover: number[]; sea: number;
};

export type SiteChoice = { x: number; z: number; level: number; sea: number };

export class Terrain {
  readonly s: Survey;
  constructor(s: Survey) { this.s = s; }
  has(x: number, z: number) {
    return x >= this.s.minX && z >= this.s.minZ && x < this.s.minX + this.s.width && z < this.s.minZ + this.s.depth;
  }
  private k(x: number, z: number) { return (x - this.s.minX) * this.s.depth + (z - this.s.minZ); }
  ground(x: number, z: number) { return this.has(x, z) ? this.s.ground[this.k(x, z)] : this.s.sea; }
  wet(x: number, z: number) { return !this.has(x, z) || this.s.water[this.k(x, z)] > this.s.ground[this.k(x, z)]; }
  wooded(x: number, z: number) { return this.has(x, z) && this.s.cover[this.k(x, z)] === 3; }
  surface(x: number, z: number) { return Math.max(this.ground(x, z), this.has(x, z) ? this.s.water[this.k(x, z)] : 0); }
}

// Mason ops over a shared block-state palette.
export class Works {
  readonly palette: string[] = [];
  readonly ops: Array<Array<string | number>> = [];
  private index = new Map<string, number>();
  s(state: string) {
    let i = this.index.get(state);
    if (i === undefined) { i = this.palette.length; this.palette.push(state); this.index.set(state, i); }
    return i;
  }
  clear(b: Box) { this.ops.push(["clear", b.minX, b.minZ, b.maxX, b.maxZ]); }
  pad(b: Box, y: number, top: string, core = "minecraft:dirt", edge = "minecraft:cobblestone", headroom = 8) {
    this.ops.push(["pad", b.minX, b.minZ, b.maxX, b.maxZ, y, this.s(top), this.s(core), this.s(edge), headroom]);
  }
  fill(a: Vec, b: Vec, state: string, keep = false) {
    this.ops.push(["fill", a.x, a.y, a.z, b.x, b.y, b.z, this.s(state), keep ? 1 : 0]);
  }
  set(p: Vec, state: string) { this.ops.push(["set", p.x, p.y, p.z, this.s(state)]); }
  footing(x: number, z: number, y: number, state: string) { this.ops.push(["footing", x, z, y, this.s(state)]); }
  tree(p: Vec, kind: 0 | 1 | 2 | 3) { this.ops.push(["tree", p.x, p.y, p.z, kind]); }
}

const COMPASS = ["north", "east", "south", "west"] as const;
type Facing = (typeof COMPASS)[number];

// A lot's own frame: u runs along its length, v across it, and +v (local south) is the side its door faces.
// rot turns the frame clockwise in quarter turns.
export class Frame {
  readonly ox: number; readonly oz: number; readonly y: number; readonly rot: number; readonly w: number; readonly d: number;
  constructor(ox: number, oz: number, y: number, rot: number, w: number, d: number) {
    this.ox = ox; this.oz = oz; this.y = y; this.rot = rot; this.w = w; this.d = d;
  }
  at(u: number, dy: number, v: number): Vec {
    const { ox, oz, w, d } = this;
    switch (this.rot & 3) {
      case 0: return { x: ox + u, y: this.y + dy, z: oz + v };
      case 1: return { x: ox + d - 1 - v, y: this.y + dy, z: oz + u };
      case 2: return { x: ox + w - 1 - u, y: this.y + dy, z: oz + d - 1 - v };
      default: return { x: ox + v, y: this.y + dy, z: oz + w - 1 - u };
    }
  }
  turn(state: string): string {
    const r = this.rot & 3;
    if (!r) return state;
    return state
      .replace(/facing=(north|east|south|west)/, (_, f: Facing) => `facing=${COMPASS[(COMPASS.indexOf(f) + r) % 4]}`)
      .replace(/axis=(x|z)/, (_, a: string) => `axis=${r % 2 ? (a === "x" ? "z" : "x") : a}`);
  }
  box(): Box {
    const a = this.at(0, 0, 0), b = this.at(this.w - 1, 0, this.d - 1);
    return { minX: Math.min(a.x, b.x), maxX: Math.max(a.x, b.x), minZ: Math.min(a.z, b.z), maxZ: Math.max(a.z, b.z) };
  }
}

// Draws into a frame: local coordinates in, world ops out.
export class Draft {
  readonly f: Frame; readonly works: Works;
  constructor(f: Frame, works: Works) { this.f = f; this.works = works; }
  set(u: number, dy: number, v: number, state: string) { this.works.set(this.f.at(u, dy, v), this.f.turn(state)); }
  fill(u0: number, dy0: number, v0: number, u1: number, dy1: number, v1: number, state: string, keep = false) {
    this.works.fill(this.f.at(u0, dy0, v0), this.f.at(u1, dy1, v1), this.f.turn(state), keep);
  }
}

export type Style = { post: string; beam: string; wall: string; base: string; roof: string; slab: string; floor: string; gable: string };

export const STYLES: Record<string, Style> = {
  timber: { post: "minecraft:stripped_dark_oak_log", beam: "minecraft:dark_oak_log", wall: "minecraft:calcite", base: "minecraft:cobblestone",
    roof: "minecraft:dark_oak_stairs", slab: "minecraft:dark_oak_slab", floor: "minecraft:spruce_planks", gable: "minecraft:spruce_planks" },
  spruce: { post: "minecraft:spruce_log", beam: "minecraft:stripped_spruce_log", wall: "minecraft:spruce_planks", base: "minecraft:stone_bricks",
    roof: "minecraft:spruce_stairs", slab: "minecraft:spruce_slab", floor: "minecraft:oak_planks", gable: "minecraft:stripped_spruce_wood" },
  forge: { post: "minecraft:stone_bricks", beam: "minecraft:stripped_spruce_log", wall: "minecraft:bricks", base: "minecraft:stone_bricks",
    roof: "minecraft:deepslate_tile_stairs", slab: "minecraft:deepslate_tile_slab", floor: "minecraft:polished_andesite", gable: "minecraft:bricks" },
  barn: { post: "minecraft:oak_log", beam: "minecraft:stripped_oak_log", wall: "minecraft:oak_planks", base: "minecraft:mossy_cobblestone",
    roof: "minecraft:mangrove_stairs", slab: "minecraft:mangrove_slab", floor: "minecraft:coarse_dirt", gable: "minecraft:oak_planks" },
};

const axis = (block: string, a: "x" | "y" | "z") => /_(log|wood|stem|hyphae)$|hay_block$|pillar$/.test(block) ? `${block}[axis=${a}]` : block;

type HallOptions = { style: Style; height: number; open: boolean; doors: Array<"west" | "east" | "south" | "north">; windows?: boolean };

// A gabled hall over u0..u1 x v0..v1 (the ridge runs along u). Open halls are posts, beams and a roof.
export function hall(t: Draft, u0: number, v0: number, u1: number, v1: number, o: HallOptions) {
  const s = o.style, h = o.height;
  t.fill(u0, -1, v0, u1, -1, v1, s.floor);
  t.fill(u0 - 1, 0, v0 - 1, u1 + 1, h + Math.ceil((v1 - v0) / 2) + 2, v1 + 1, "minecraft:air");
  const posts = (a: number, b: number) => {
    const out = [a];
    for (let i = a + 4; i < b - 1; i += 4) out.push(i);
    out.push(b);
    return out;
  };
  const along = posts(u0, u1), across = posts(v0, v1);
  if (!o.open) {
    t.fill(u0, 0, v0, u1, 0, v1, s.base);
    t.fill(u0 + 1, 0, v0 + 1, u1 - 1, 0, v1 - 1, "minecraft:air");
    t.fill(u0, 1, v0, u1, h - 1, v0, s.wall);
    t.fill(u0, 1, v1, u1, h - 1, v1, s.wall);
    t.fill(u0, 1, v0, u0, h - 1, v1, s.wall);
    t.fill(u1, 1, v0, u1, h - 1, v1, s.wall);
    if (o.windows !== false) {
      for (let i = 0; i + 1 < along.length; i++) {
        if (along[i + 1] - along[i] < 3) continue;
        for (const v of [v0, v1]) t.fill(along[i] + 1, 2, v, along[i + 1] - 1, 2, v, "minecraft:glass_pane");
      }
    }
  }
  for (const u of along) for (const v of [v0, v1]) t.fill(u, 0, v, u, h - 1, v, axis(s.post, "y"));
  for (const v of across) for (const u of [u0, u1]) t.fill(u, 0, v, u, h - 1, v, axis(s.post, "y"));
  t.fill(u0, h, v0, u1, h, v0, axis(s.beam, "x"));
  t.fill(u0, h, v1, u1, h, v1, axis(s.beam, "x"));
  t.fill(u0, h, v0, u0, h, v1, axis(s.beam, "z"));
  t.fill(u1, h, v0, u1, h, v1, axis(s.beam, "z"));
  const mid = (v0 + v1) / 2;
  for (const door of o.doors) {
    if (door === "west" || door === "east") {
      const u = door === "west" ? u0 : u1;
      t.fill(u, 0, Math.floor(mid) - 1, u, 2, Math.ceil(mid) + 1, "minecraft:air");
      t.set(u, 3, Math.floor(mid), "minecraft:lantern[hanging=true]");
    } else {
      const v = door === "north" ? v0 : v1;
      const c = Math.floor((u0 + u1) / 2);
      t.fill(c - 1, 0, v, c + 1, 2, v, "minecraft:air");
      t.set(c, 3, v, "minecraft:lantern[hanging=true]");
    }
  }
  // Roof: stairs rising from both eaves to a ridge along u, one block of overhang all round.
  for (let i = 0; ; i++) {
    const a = v0 - 1 + i, b = v1 + 1 - i;
    const y = h + 1 + i;
    if (a > b) break;
    if (a === b) {
      t.fill(u0 - 1, y, a, u1 + 1, y, a, s.slab + "[type=bottom]");
      break;
    }
    t.fill(u0 - 1, y, a, u1 + 1, y, a, s.roof + "[facing=south,half=bottom]");
    t.fill(u0 - 1, y, b, u1 + 1, y, b, s.roof + "[facing=north,half=bottom]");
    if (b - a === 1) break;
    if (a + 1 <= b - 1) {
      for (const u of [u0, u1]) t.fill(u, y, a + 1, u, y, b - 1, s.gable);
    }
  }
}

// ---------------------------------------------------------------------------------------------------------
// Lot catalogue

export type LotKind = "plaza" | "warehouse" | "store" | "smithy" | "kitchen" | "workshop" | "masonry" | "lodge"
  | "paddock" | "field" | "garden" | "grove" | "quarry" | "site" | "yard" | "post" | "wall" | "ponds";

export type LotSpec = {
  kind: LotKind; name: string; w: number; d: number; ring: [number, number]; range: number; pad: boolean;
  role?: string; crop?: string; near?: LotKind; fixed?: boolean;
};

export type Lot = LotSpec & { frame: Frame; box: Box; y: number; door: { x: number; z: number; out: { x: number; z: number } } };

export type Counts = { humans: number; cookers: { furnace: number; blast: number; smoker: number };
  tables: { crafting: number; smithing: number }; stonecutters: number; pens: number };

export const STORE_ROLES = ["seed", "log", "plank", "iron", "stone", "meat", "feed", "food", "fuel"] as const;
const STORE_NEAR: Record<string, LotKind> = { seed: "field", log: "workshop", plank: "workshop", iron: "smithy",
  stone: "masonry", meat: "kitchen", feed: "paddock", food: "lodge", fuel: "smithy" };

export const FIELDS: Array<{ crop: string; side: number; kind: "field" | "garden" }> = [
  { crop: "minecraft:wheat", side: 28, kind: "field" }, { crop: "minecraft:wheat", side: 24, kind: "field" },
  { crop: "minecraft:carrots", side: 20, kind: "field" }, { crop: "minecraft:potatoes", side: 20, kind: "field" },
  { crop: "minecraft:beetroots", side: 16, kind: "field" }, { crop: "minecraft:pumpkin_stem", side: 15, kind: "garden" },
  { crop: "minecraft:melon_stem", side: 15, kind: "garden" }, { crop: "minecraft:sugar_cane", side: 15, kind: "garden" },
];

export const PER_HALL = 16;
export const BEDS_PER_LODGE = 26;
export const PENS_PER_PADDOCK = 4;

export function catalogue(n: Counts): LotSpec[] {
  const hall = (kind: LotKind, name: string, ring: [number, number]): LotSpec =>
    ({ kind, name, w: 23, d: 15, ring, range: 6, pad: true });
  const lots: LotSpec[] = [{ kind: "plaza", name: "plaza", w: 31, d: 31, ring: [0, 0], range: 5, pad: true },
    { kind: "warehouse", name: "warehouse", w: 29, d: 13, ring: [16, 34], range: 6, pad: true }];
  const cookers = n.cookers.furnace + n.cookers.blast;
  for (let i = 0; i * PER_HALL < cookers; i++) lots.push(hall("smithy", `smithy-${i}`, [30, 70]));
  for (let i = 0; i * PER_HALL < n.cookers.smoker; i++) lots.push(hall("kitchen", `kitchen-${i}`, [24, 60]));
  for (let i = 0; i * PER_HALL < n.tables.crafting + n.tables.smithing; i++) lots.push(hall("workshop", `workshop-${i}`, [26, 70]));
  lots.push({ kind: "masonry", name: "masonry", w: 23, d: 11, ring: [40, 90], range: 6, pad: true });
  for (let i = 0; i * BEDS_PER_LODGE < n.humans; i++)
    lots.push({ kind: "lodge", name: `lodge-${i}`, w: 21, d: 13, ring: [30, 110], range: 6, pad: true });
  for (let i = 0; i * PENS_PER_PADDOCK < n.pens; i++)
    lots.push({ kind: "paddock", name: `paddock-${i}`, w: 25, d: 25, ring: [60, 130], range: 5, pad: true });
  FIELDS.forEach((f, i) => lots.push({ kind: f.kind, name: `${f.kind}-${i}`, w: f.side + 4, d: f.side + 4,
    ring: [60, 130], range: f.kind === "field" ? 14 : 5, pad: f.kind === "garden", crop: f.crop }));
  lots.push({ kind: "grove", name: "grove", w: 28, d: 28, ring: [70, 140], range: 6, pad: true, crop: "minecraft:oak_sapling" });
  lots.push({ kind: "quarry", name: "quarry", w: 22, d: 14, ring: [50, 120], range: 6, pad: true });
  lots.push({ kind: "site", name: "site", w: 21, d: 13, ring: [40, 110], range: 6, pad: true });
  lots.push({ kind: "yard", name: "builders-yard", w: 17, d: 11, ring: [30, 100], range: 6, pad: true, near: "site" });
  lots.push({ kind: "post", name: "post-office", w: 28, d: 16, ring: [20, 70], range: 4, pad: true, fixed: true });
  lots.push({ kind: "ponds", name: "fish-ponds", w: 25, d: 15, ring: [50, 130], range: 5, pad: true });
  lots.push({ kind: "wall", name: "town-wall", w: 40, d: 11, ring: [100, 160], range: 10, pad: false });
  for (const role of STORE_ROLES)
    lots.push({ kind: "store", name: `store-${role}`, w: 13, d: 11, ring: [18, 110], range: 6, pad: true, role, near: STORE_NEAR[role] });
  return lots;
}

// ---------------------------------------------------------------------------------------------------------
// Placement

const GAP = 7;

class Grid {
  readonly sat: Float64Array;
  readonly minX: number; readonly minZ: number; readonly w: number; readonly d: number;
  constructor(minX: number, minZ: number, w: number, d: number, cell: (x: number, z: number) => number) {
    this.minX = minX; this.minZ = minZ; this.w = w; this.d = d;
    this.sat = new Float64Array((w + 1) * (d + 1));
    for (let i = 0; i < w; i++) for (let j = 0; j < d; j++)
      this.sat[(i + 1) * (d + 1) + j + 1] = cell(minX + i, minZ + j) + this.sat[i * (d + 1) + j + 1]
        + this.sat[(i + 1) * (d + 1) + j] - this.sat[i * (d + 1) + j];
  }
  sum(b: Box) {
    const i0 = Math.max(0, b.minX - this.minX), i1 = Math.min(this.w, b.maxX - this.minX + 1);
    const j0 = Math.max(0, b.minZ - this.minZ), j1 = Math.min(this.d, b.maxZ - this.minZ + 1);
    if (i1 <= i0 || j1 <= j0) return 0;
    const D = this.d + 1;
    return this.sat[i1 * D + j1] - this.sat[i0 * D + j1] - this.sat[i1 * D + j0] + this.sat[i0 * D + j0];
  }
}

export const grow = (b: Box, n: number): Box => ({ minX: b.minX - n, maxX: b.maxX + n, minZ: b.minZ - n, maxZ: b.maxZ + n });
export const centre = (b: Box) => ({ x: (b.minX + b.maxX) / 2, z: (b.minZ + b.maxZ) / 2 });

export function median(values: number[]) {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.floor(sorted.length / 2)] ?? 0;
}

function heightsIn(t: Terrain, b: Box, step = 2) {
  const out: number[] = [];
  for (let x = b.minX; x <= b.maxX; x += step) for (let z = b.minZ; z <= b.maxZ; z += step) out.push(t.ground(x, z));
  out.push(t.ground(b.maxX, b.maxZ));
  return out;
}

const SPILL = 32;

export function placeLots(t: Terrain, site: SiteChoice, specs: LotSpec[], half: number) {
  const core: Box = { minX: site.x - half, maxX: site.x + half, minZ: site.z - half, maxZ: site.z + half };
  const outer = grow(core, SPILL);
  const W = outer.maxX - outer.minX + 1, D = outer.maxZ - outer.minZ + 1;
  const wet = new Grid(outer.minX, outer.minZ, W, D, (x, z) => t.wet(x, z) ? 1 : 0);
  const taken = new Uint8Array(W * D);
  let occupied = new Grid(outer.minX, outer.minZ, W, D, () => 0);
  const lots: Lot[] = [];
  const skipped: string[] = [];
  for (const spec of specs) {
    let best: { cost: number; lot: Lot } | undefined;
    for (let relax = 0; relax < 4 && !best; relax++) {
      const range = spec.range + Math.min(relax, 2) * 3;
      const area = relax === 3 ? outer : core;
      for (let x = area.minX; x <= area.maxX; x += 2) for (let z = area.minZ; z <= area.maxZ; z += 2) {
        const toward = { x: site.x - x, z: site.z - z };
        const rot = spec.fixed ? 0 : Math.abs(toward.x) > Math.abs(toward.z) ? (toward.x > 0 ? 3 : 1) : (toward.z > 0 ? 0 : 2);
        const W = rot % 2 ? spec.d : spec.w, Dd = rot % 2 ? spec.w : spec.d;
        const box: Box = { minX: x - Math.floor(W / 2), maxX: x - Math.floor(W / 2) + W - 1, minZ: z - Math.floor(Dd / 2), maxZ: z - Math.floor(Dd / 2) + Dd - 1 };
        if (box.minX < area.minX || box.maxX > area.maxX || box.minZ < area.minZ || box.maxZ > area.maxZ) continue;
        if (spec.kind === "plaza" && (Math.abs(x - site.x) > 24 || Math.abs(z - site.z) > 24)) continue;
        if (occupied.sum(grow(box, GAP)) > 0) continue;
        if (wet.sum(grow(box, 1)) > 0) continue;
        const heights = heightsIn(t, box);
        const lo = Math.min(...heights), hi = Math.max(...heights);
        if (hi - lo > range) continue;
        const r = Math.hypot(x - site.x, z - site.z);
        let pull = Math.max(0, spec.ring[0] - r, r - spec.ring[1]) * 1.5;
        if (spec.near) {
          const mates = lots.filter((l) => l.kind === spec.near);
          if (mates.length) pull += Math.min(...mates.map((m) => Math.hypot(centre(m.box).x - x, centre(m.box).z - z))) * 0.25;
        }
        const cost = (hi - lo) * 2 + pull + Math.abs(median(heights) - site.level) * 1.5 + (spec.kind === "plaza" ? r : 0);
        if (!best || cost < best.cost) {
          const y = median(heights) + 1;
          const frame = new Frame(box.minX, box.minZ, y, rot, spec.w, spec.d);
          const doorLocal = frame.at(Math.floor(spec.w / 2), 0, spec.d - 1), outLocal = frame.at(Math.floor(spec.w / 2), 0, spec.d + 1);
          best = { cost, lot: { ...spec, frame, box, y, door: { x: doorLocal.x, z: doorLocal.z, out: { x: outLocal.x, z: outLocal.z } } } };
        }
      }
    }
    if (!best) { skipped.push(spec.name); continue; }
    lots.push(best.lot);
    const b = best.lot.box;
    for (let x = b.minX; x <= b.maxX; x++) for (let z = b.minZ; z <= b.maxZ; z++) taken[(x - outer.minX) * D + (z - outer.minZ)] = 1;
    occupied = new Grid(outer.minX, outer.minZ, W, D, (x, z) => taken[(x - outer.minX) * D + (z - outer.minZ)]);
  }
  return { lots, skipped, core };
}

// ---------------------------------------------------------------------------------------------------------
// Roads: a spanning tree over the lots' doors from the plaza, each edge routed by A* around lots and water.

export type Road = { name: string; points: number[][]; width: number; cells: Array<[number, number]>; ends: [number, number] };

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

export function routeRoads(t: Terrain, lots: Lot[], bounds: Box, ground: number) {
  const W = bounds.maxX - bounds.minX + 1, D = bounds.maxZ - bounds.minZ + 1;
  const idx = (x: number, z: number) => (x - bounds.minX) * D + (z - bounds.minZ);
  const blocked = new Uint8Array(W * D), road = new Uint8Array(W * D);
  for (const l of lots) {
    const b = grow(l.box, 2);
    for (let x = Math.max(bounds.minX, b.minX); x <= Math.min(bounds.maxX, b.maxX); x++)
      for (let z = Math.max(bounds.minZ, b.minZ); z <= Math.min(bounds.maxZ, b.maxZ); z++) blocked[idx(x, z)] = 1;
  }
  for (let x = bounds.minX; x <= bounds.maxX; x++) for (let z = bounds.minZ; z <= bounds.maxZ; z++) {
    // Keep the centreline dry. The graded road supplies its own shoulders; a two-cell
    // water exclusion also seals dry doorways beside ponds and along the waterfront.
    if (t.wet(x, z)) blocked[idx(x, z)] = 1;
  }
  const inside = (x: number, z: number) => x >= bounds.minX && x <= bounds.maxX && z >= bounds.minZ && z <= bounds.maxZ;
  const astar = (from: { x: number; z: number }, to: { x: number; z: number }): Array<[number, number]> | null => {
    const g = new Float64Array(W * D).fill(Infinity), came = new Int32Array(W * D).fill(-1), done = new Uint8Array(W * D);
    const heap = new Heap();
    const s = idx(from.x, from.z), goal = idx(to.x, to.z);
    g[s] = 0; heap.push(0, s);
    const dirs = [[1, 0], [-1, 0], [0, 1], [0, -1]];
    let steps = 0;
    while (heap.size && steps++ < 400_000) {
      const [, cur] = heap.pop()!;
      if (cur === goal) break;
      if (done[cur]) continue;
      done[cur] = 1;
      const cx = Math.floor(cur / D) + bounds.minX, cz = (cur % D) + bounds.minZ;
      for (const [dx, dz] of dirs) {
        const nx = cx + dx, nz = cz + dz;
        if (!inside(nx, nz)) continue;
        const n = idx(nx, nz);
        if (blocked[n] && n !== goal) continue;
        const climb = Math.abs(t.ground(nx, nz) - t.ground(cx, cz));
        const turn = came[cur] >= 0 && (came[cur] - cur) !== (cur - n) ? 0.6 : 0;
        const cost = g[cur] + (road[n] ? 0.35 : 1) + climb * climb * 1.5 + turn;
        if (cost < g[n]) {
          g[n] = cost; came[n] = cur;
          heap.push(cost + (Math.abs(nx - to.x) + Math.abs(nz - to.z)) * 0.35, n);
        }
      }
    }
    if (came[goal] < 0) return null;
    const path: Array<[number, number]> = [];
    for (let c = goal; c >= 0; c = came[c]) { path.push([Math.floor(c / D) + bounds.minX, (c % D) + bounds.minZ]); if (c === s) break; }
    return path.reverse();
  };

  const plaza = lots.find((l) => l.kind === "plaza")!;
  const joined = [plaza];
  const waiting = lots.filter((l) => l !== plaza && l.kind !== "wall");
  const roads: Road[] = [];
  const unrouted: string[] = [];
  const door = (l: Lot) => l.door.out;
  // Clear the doorstep and a short run straight out. Water across that run would leave the doorstep
  // walled in by the lot's own margin, so the street may then also leave sideways along the lot's front.
  const openDoor = (l: Lot) => {
    const dx = Math.sign(l.door.out.x - l.door.x), dz = Math.sign(l.door.out.z - l.door.z);
    let wet = false;
    for (let k = 0; k <= 4; k++) {
      const x = l.door.out.x + dx * k, z = l.door.out.z + dz * k;
      if (!inside(x, z)) continue;
      if (t.wet(x, z)) wet = true;
      else blocked[idx(x, z)] = 0;
    }
    if (!wet) return;
    const reach = Math.max(l.box.maxX - l.box.minX, l.box.maxZ - l.box.minZ) + 4;
    for (const side of [-1, 1]) for (let s = 1; s <= reach; s++) {
      const x = l.door.out.x + dz * side * s, z = l.door.out.z + dx * side * s;
      if (!inside(x, z) || t.wet(x, z)) break;
      blocked[idx(x, z)] = 0;
    }
  };
  while (waiting.length) {
    let pick = 0, mate = joined[0], bestD = Infinity;
    waiting.forEach((l, i) => joined.forEach((j) => {
      const d = Math.hypot(door(l).x - door(j).x, door(l).z - door(j).z);
      if (d < bestD) { bestD = d; pick = i; mate = j; }
    }));
    const lot = waiting.splice(pick, 1)[0];
    const from = door(lot);
    openDoor(lot);
    openDoor(mate);
    // The nearest door can be blocked by a slope, water or a neighbouring lot. Try the
    // other connected streets before declaring this lot isolated; never grow from an island.
    let path: Array<[number, number]> | null = null;
    for (const candidate of [...joined].sort((a, b) =>
      Math.hypot(door(a).x - from.x, door(a).z - from.z) - Math.hypot(door(b).x - from.x, door(b).z - from.z))) {
      const target = door(candidate);
      openDoor(candidate);
      path = inside(from.x, from.z) && inside(target.x, target.z) ? astar(from, target) : null;
      if (path) { mate = candidate; break; }
    }
    if (!path) { unrouted.push(lot.name); continue; }
    joined.push(lot);
    for (const [x, z] of path) for (let dx = -1; dx <= 1; dx++) for (let dz = -1; dz <= 1; dz++)
      if (inside(x + dx, z + dz)) road[idx(x + dx, z + dz)] = 1;
    const ends = [[lot.door.x, lot.door.z] as [number, number], ...path, [mate.door.x, mate.door.z] as [number, number]];
    const corners = ends.filter((p, i) => i === 0 || i === ends.length - 1
      || (ends[i - 1][0] - p[0]) * (ends[i + 1][1] - p[1]) !== (ends[i - 1][1] - p[1]) * (ends[i + 1][0] - p[0]));
    const main = mate === plaza || lot.kind === "plaza";
    const pieces: number[][][] = [[corners[0]]];
    let run = 0;
    for (let i = 1; i < corners.length; i++) {
      const last = pieces.at(-1)!;
      const seg = Math.abs(corners[i][0] - corners[i - 1][0]) + Math.abs(corners[i][1] - corners[i - 1][1]);
      if (run + seg > 80 && last.length > 1) { pieces.push([corners[i - 1]]); run = 0; }
      pieces.at(-1)!.push(corners[i]);
      run += seg;
    }
    pieces.filter((p) => p.length > 1).forEach((p, k) => roads.push({ name: `road-${lot.name}-${k}`, width: main ? 5 : 3,
      points: p.map(([x, z]) => [x, ground, z]), cells: ends, ends: [lot.y - 1, mate.y - 1] }));
  }
  return { roads, unrouted };
}

// A footpath that residents can always climb: the ground along a routed road, cut and filled so no step is more
// than one block, meeting each lot at its pad. Laid where the road pattern refuses a slope.
export function walkway(t: Terrain, road: Road, works: Works) {
  const cells = road.cells.filter((c, i) => i === 0 || c[0] !== road.cells[i - 1][0] || c[1] !== road.cells[i - 1][1]);
  const n = cells.length;
  if (n < 2) return 0;
  const [first, last] = road.ends;
  const h: number[] = [];
  for (let i = 0; i < n; i++) {
    const lo = Math.max(first - i, last - (n - 1 - i)), hi = Math.min(first + i, last + (n - 1 - i));
    let want = i === 0 ? first : i === n - 1 ? last : t.ground(cells[i][0], cells[i][1]);
    want = Math.max(lo, Math.min(hi, want));
    if (i > 0) want = Math.max(h[i - 1] - 1, Math.min(h[i - 1] + 1, want));
    h.push(want);
  }
  for (let i = 0; i < n; i++) {
    const [x, z] = cells[i];
    const side = cells[Math.min(n - 1, i + 1)][0] === x && cells[Math.max(0, i - 1)][0] === x ? [1, 0] : [0, 1];
    for (const k of [-1, 0, 1]) {
      const cx = x + side[0] * k, cz = z + side[1] * k;
      const g = t.ground(cx, cz);
      // h and road.ends are the top solid block, not the resident's feet. Laying at
      // h-1 excavates every fallback path into a trench below its doorsteps.
      if (g < h[i]) works.fill({ x: cx, y: g + 1, z: cz }, { x: cx, y: h[i], z: cz }, "minecraft:cobblestone");
      works.fill({ x: cx, y: h[i] + 1, z: cz }, { x: cx, y: Math.max(h[i] + 4, g), z: cz }, "minecraft:air");
      works.set({ x: cx, y: h[i], z: cz }, k === 0 ? "minecraft:gravel" : "minecraft:coarse_dirt");
    }
  }
  return n;
}
