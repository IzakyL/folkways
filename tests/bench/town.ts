// The bench town, planned on real terrain: a city of square blocks on one level, laid on the flattest ground the land
// offers, its lots packed into the blocks, straight streets between them, built as timber halls. Out at the mine the
// lots are still fitted to the ground as it lies. Everything here is pure planning; the Mason task lays what it answers.
export type Vec = { x: number; y: number; z: number };
export type Box = { minX: number; maxX: number; minZ: number; maxZ: number };

export type Survey = {
  minX: number; minZ: number; width: number; depth: number;
  ground: number[]; water: number[]; cover: number[]; sea: number;
};

export type SiteChoice = { x: number; z: number; level: number; sea: number };

// The surveyed ground: the city's box first, then any outlying boxes (the mine's).
export class Terrain {
  readonly s: Survey;
  readonly all: Survey[];
  constructor(s: Survey, more: Survey[] = []) { this.s = s; this.all = [s, ...more]; }
  private of(x: number, z: number) {
    for (const s of this.all) if (x >= s.minX && z >= s.minZ && x < s.minX + s.width && z < s.minZ + s.depth) return s;
    return undefined;
  }
  has(x: number, z: number) { return this.of(x, z) !== undefined; }
  private k(s: Survey, x: number, z: number) { return (x - s.minX) * s.depth + (z - s.minZ); }
  ground(x: number, z: number) { const s = this.of(x, z); return s ? s.ground[this.k(s, x, z)] : this.s.sea; }
  wet(x: number, z: number) { const s = this.of(x, z); return !s || s.water[this.k(s, x, z)] > s.ground[this.k(s, x, z)]; }
  wooded(x: number, z: number) { const s = this.of(x, z); return !!s && s.cover[this.k(s, x, z)] === 3; }
  surface(x: number, z: number) { const s = this.of(x, z); return Math.max(this.ground(x, z), s ? s.water[this.k(s, x, z)] : 0); }
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
  /** A rail bed: levelled like a pad, but bored through a rise rather than cut open to the sky. */
  bed(b: Box, y: number, top: string, core = "minecraft:dirt", edge = "minecraft:stone_bricks", headroom = 8) {
    this.ops.push(["bed", b.minX, b.minZ, b.maxX, b.maxZ, y, this.s(top), this.s(core), this.s(edge), headroom]);
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

export type Style = { post: string; beam: string; wall: string; base: string; roof: string; slab: string; floor: string; gable: string;
  /** What fills a window (glass panes by default) and what hangs over a door (a lantern; none when null). */
  window?: string; lamp?: string | null };

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

/**
 * The houses the colony builds itself, in what it can make from its own timber and stone: oak felled in its grove,
 * cobblestone and the stones dug in its mine, cut and smelted at its own stations. No glass (no sand), no lanterns.
 */
export const HOUSE_STYLES: Record<string, Style> = {
  oak: { post: "minecraft:oak_log", beam: "minecraft:oak_log", wall: "minecraft:oak_planks", base: "minecraft:cobblestone",
    roof: "minecraft:oak_stairs", slab: "minecraft:oak_slab", floor: "minecraft:oak_planks", gable: "minecraft:oak_planks",
    window: "minecraft:oak_fence", lamp: null },
  stone: { post: "minecraft:oak_log", beam: "minecraft:oak_log", wall: "minecraft:stone_bricks", base: "minecraft:cobblestone",
    roof: "minecraft:cobblestone_stairs", slab: "minecraft:cobblestone_slab", floor: "minecraft:oak_planks", gable: "minecraft:oak_planks",
    window: "minecraft:oak_fence", lamp: null },
  cobble: { post: "minecraft:oak_log", beam: "minecraft:oak_log", wall: "minecraft:cobblestone", base: "minecraft:stone_bricks",
    roof: "minecraft:oak_stairs", slab: "minecraft:oak_slab", floor: "minecraft:oak_planks", gable: "minecraft:cobblestone",
    window: "minecraft:oak_fence", lamp: null },
  andesite: { post: "minecraft:oak_log", beam: "minecraft:oak_log", wall: "minecraft:polished_andesite", base: "minecraft:cobblestone",
    roof: "minecraft:stone_brick_stairs", slab: "minecraft:stone_brick_slab", floor: "minecraft:oak_planks", gable: "minecraft:oak_planks",
    window: "minecraft:oak_fence", lamp: null },
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
        for (const v of [v0, v1]) t.fill(along[i] + 1, 2, v, along[i + 1] - 1, 2, v, s.window ?? "minecraft:glass_pane");
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
      if (s.lamp !== null) t.set(u, 3, Math.floor(mid), s.lamp ?? "minecraft:lantern[hanging=true]");
    } else {
      const v = door === "north" ? v0 : v1;
      const c = Math.floor((u0 + u1) / 2);
      t.fill(c - 1, 0, v, c + 1, 2, v, "minecraft:air");
      if (s.lamp !== null) t.set(c, 3, v, s.lamp ?? "minecraft:lantern[hanging=true]");
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

/**
 * An open work yard over u0..u1 x v0..v1: paved and kerbed, open to the sky so a camera over the town sees what stands
 * in it, with a lamp post at each corner and along the long sides.
 */
export function yard(t: Draft, u0: number, v0: number, u1: number, v1: number, floor: string, kerb = "minecraft:stone_bricks") {
  t.fill(u0 - 1, 0, v0 - 1, u1 + 1, 10, v1 + 1, "minecraft:air");
  t.fill(u0, -1, v0, u1, -1, v1, kerb);
  t.fill(u0 + 1, -1, v0 + 1, u1 - 1, -1, v1 - 1, floor);
  const along = [u0, u1];
  for (let u = u0 + 6; u < u1 - 3; u += 6) along.push(u);
  for (const u of along) for (const v of [v0, v1]) {
    t.fill(u, 0, v, u, 1, v, "minecraft:spruce_fence");
    t.set(u, 2, v, "minecraft:lantern");
  }
}

// ---------------------------------------------------------------------------------------------------------
// Lot catalogue

export type LotKind = "plaza" | "works" | "store" | "lodge"
  | "paddock" | "field" | "garden" | "grove" | "yard" | "ponds" | "halt" | "house";

/**
 * A lot to place. In the city `block` names the area it stands in with others of its kind (none: a block of its own); out at the mine `ring` (how
 * far from the halt) and `range` (how much the ground under it may rise) fit it to the land.
 */
export type LotSpec = {
  kind: LotKind; name: string; w: number; d: number; pad: boolean;
  block?: string; ring?: [number, number]; range?: number;
  role?: string; crop?: string; near?: LotKind; fixed?: boolean;
  /** How many chests a store keeps (four when not said). */
  chests?: number;
  /** Stands in a column down the side of its block's lots, not as one of them in their rows. */
  beside?: boolean;
};

export type Lot = LotSpec & { frame: Frame; box: Box; y: number; door: { x: number; z: number; out: { x: number; z: number } } };

export type Counts = { humans: number; cookers: { furnace: number; blast: number; smoker: number };
  tables: { crafting: number; smithing: number }; stonecutters: number; pens: number };

export const STORE_ROLES = ["seed", "crop", "log", "plank", "iron", "stone", "meat", "feed", "food", "fuel"] as const;
/**
 * The stores that stand out in the town, each a small yard in the block of what uses it, so that not everyone who
 * fetches or brings something goes to the works: seed, and empty chests for the crops brought in, beside the field;
 * the builders' yard with logs, planks and stone beside the quarter being built; feed and meat by the paddocks; the
 * pantries among the lodges. The rest (ore, fuel) are kept at the works.
 */
export const STORE_AREAS: Partial<Record<(typeof STORE_ROLES)[number], string>> = {
  seed: "field", crop: "field", feed: "paddocks", meat: "paddocks", log: "quarter", plank: "quarter", stone: "quarter", food: "lodges" };
const STORE_CHESTS = 4;
export const ROLE_CHESTS = ["seed", "log", "plank", "iron", "stone", "meat", "feed", "tool", "output", "smeltOut", "food", "dairy"] as const;

/** The gardens: crops that want a bed of their own (room for fruit beside the stem, water beside the cane). */
export const GARDENS: Array<{ crop: string; side: number }> = [
  { crop: "minecraft:pumpkin_stem", side: 15 }, { crop: "minecraft:melon_stem", side: 15 }, { crop: "minecraft:sugar_cane", side: 15 },
];

/** The one big field, terraced by the field pattern, and the share of it each crop is sown in, side by side. */
export const FIELD_SIDE = 56;
export const FIELD_CROPS: Array<{ crop: string; share: number }> = [
  { crop: "minecraft:wheat", share: 0.5 }, { crop: "minecraft:carrots", share: 0.18 },
  { crop: "minecraft:potatoes", share: 0.18 }, { crop: "minecraft:beetroots", share: 0.14 },
];

/**
 * The field's soil shared out among its crops, each its share of the cells in a band across the field's long side, so
 * the crops lie side by side in strips; within a band the cells run across it, row by row.
 */
export function sow(soil: Vec[]): Array<{ crop: string; cells: Vec[] }> {
  if (!soil.length) return [];
  const span = (k: "x" | "z") => Math.max(...soil.map((c) => c[k])) - Math.min(...soil.map((c) => c[k]));
  const [along, across] = span("x") >= span("z") ? ["x", "z"] as const : ["z", "x"] as const;
  const rows = new Map<number, Vec[]>();
  for (const c of soil) rows.set(c[along], [...(rows.get(c[along]) ?? []), c]);
  const order = [...rows.keys()].sort((a, b) => a - b);
  const out: Array<{ crop: string; cells: Vec[] }> = [];
  let done = 0, share = 0, row = 0;
  for (const [i, { crop, share: part }] of FIELD_CROPS.entries()) {
    share += part;
    const cells: Vec[] = [];
    const last = i === FIELD_CROPS.length - 1;
    while (row < order.length && (last || done + rows.get(order[row])!.length / 2 <= share * soil.length)) {
      const here = rows.get(order[row++])!.sort((a, b) => a[across] - b[across]);
      cells.push(...here);
      done += here.length;
    }
    if (cells.length) out.push({ crop, cells: rowsAcross(cells, along, across) });
  }
  return out;
}

// A band's cells ordered across the field, so its first third is a strip the length of the band.
function rowsAcross(cells: Vec[], along: "x" | "z", across: "x" | "z") {
  return [...cells].sort((a, b) => a[across] - b[across] || a[along] - b[along]);
}

/** The woodland's side: one wood the foresters fell and replant. */
export const WOOD_SIDE = 44;

export const PER_HALL = 16;

/** Every chest is a double chest, stacked this many high, long sides together. */
export const CHEST_TIERS = 3;
/** How many stations stand in a row of the works: the length of a bench, and of a wall of chests. */
export const WORKS_ROW = 17;
export const OUTPUT_CHESTS = 19;
const AISLE = 3;
/** A wall of chests is islands of this many stacks, this many cells of open floor between one and the next. */
export const ISLAND = 3;
const ISLAND_GAP = 4;

export type Station = "blast_furnace" | "furnace" | "smoker" | "smithing_table" | "crafting_table" | "stonecutter";

/** The cells left between two clusters of stations along one bench. */
const CLUSTER_GAP = 2;

/**
 * The works: every station and every chest the town keeps, on one open floor. The stations stand in clusters, each of
 * one kind only, two rows back to back; clusters share a bench where they fit end to end, a gap between them. The
 * chests stand in walls two cells thick, each wall broken into islands with open floor between, and the walls stand
 * between the benches, one after each, so the stock is spread over the whole floor; an aisle runs between each band
 * and the next. Each trade still has its own working stock (a chest of each thing it uses and a pantry, to every
 * PER_HALL stations) beside the town's stores, four chests to a role.
 */
export function worksPlan(n: Counts) {
  const kinds: Array<[Station, number]> = [["blast_furnace", n.cookers.blast], ["furnace", n.cookers.furnace], ["smoker", n.cookers.smoker],
    ["smithing_table", n.tables.smithing], ["crafting_table", n.tables.crafting], ["stonecutter", n.stonecutters]];
  // A kind too many for one bench is split into clusters of equal size; the longest clusters find their bench first.
  const clusters = kinds.filter(([, count]) => count > 0).flatMap(([kind, count]) => {
    const parts = Math.ceil(count / (2 * WORKS_ROW));
    return Array.from({ length: parts }, (_, k) => ({ kind, size: Math.floor(count / parts) + (k < count % parts ? 1 : 0) }));
  }).map((c) => ({ ...c, length: Math.ceil(c.size / 2) })).sort((a, b) => b.length - a.length);
  const bands: Array<typeof clusters> = [];
  for (const c of clusters) {
    const used = (band: typeof clusters) => band.reduce((sum, x) => sum + x.length + CLUSTER_GAP, 0);
    let band = bands.find((b) => used(b) + c.length <= WORKS_ROW);
    if (!band) bands.push(band = []);
    band.push(c);
  }
  /** Every station: its bench, its row of the two (0 faces north, 1 south), and how far along the bench it stands. */
  const stations: Array<{ kind: Station; bench: number; row: 0 | 1; along: number }> = [];
  bands.forEach((band, bench) => {
    let along = Math.floor((WORKS_ROW - band.reduce((sum, c) => sum + c.length + CLUSTER_GAP, -CLUSTER_GAP)) / 2);
    for (const c of band) {
      for (let i = 0; i < c.size; i++) stations.push({ kind: c.kind, bench, row: (i < c.length ? 0 : 1), along: along + (i % c.length) });
      along += c.length + CLUSTER_GAP;
    }
  });
  const chests: Array<{ keep: "role" | "output" | "store"; role: string; lot: string }> = [
    ...ROLE_CHESTS.map((role) => ({ keep: "role" as const, role, lot: "works" })),
    ...Array.from({ length: OUTPUT_CHESTS }, () => ({ keep: "output" as const, role: "output", lot: "works" })),
    ...STORE_ROLES.filter((role) => !STORE_AREAS[role])
      .flatMap((role) => Array.from({ length: STORE_CHESTS }, () => ({ keep: "store" as const, role, lot: `store-${role}` }))),
  ];
  const trade = (name: string, stationed: number, supplies: string[]) => {
    for (let i = 0; i * PER_HALL < stationed; i++)
      for (const role of [...supplies, "food"]) chests.push({ keep: "store", role, lot: `${name}-${i}` });
  };
  trade("smithy", n.cookers.furnace + n.cookers.blast, ["iron", "fuel"]);
  trade("kitchen", n.cookers.smoker, ["meat", "fuel"]);
  trade("workshop", n.tables.crafting + n.tables.smithing, ["log", "plank"]);
  trade("masonry", n.stonecutters, ["stone"]);
  const benches = bands.length;
  /** How far along its wall each stack of chests stands: the islands, end to end with their gaps. */
  const stacks = Array.from({ length: Math.floor((WORKS_ROW + ISLAND_GAP) / (ISLAND + ISLAND_GAP)) * ISLAND },
    (_, i) => Math.floor(i / ISLAND) * (ISLAND + ISLAND_GAP) + i % ISLAND);
  const walls = Math.ceil(chests.length / (CHEST_TIERS * stacks.length));
  /** Where band k starts across the lot. */
  const band = (k: number) => 1 + AISLE + k * (2 + AISLE);
  // A wall of chests after each bench while there are both, then whichever is left over.
  const shared = Math.min(benches, walls);
  const benchBand = (i: number) => i < shared ? 2 * i : shared + i;
  const wallBand = (j: number) => j < shared ? 2 * j + 1 : shared + j;
  return { stations, chests, benches, walls, stacks, band, benchBand, wallBand, first: 3, w: WORKS_ROW + 6, d: band(benches + walls) + 1 };
}
export const BEDS_PER_LODGE = 26;
export const PENS_PER_PADDOCK = 4;

/** The houses left for the builders: their sizes and the style each is built in. */
export const QUARTER: Array<{ w: number; d: number; style: string }> = [
  { w: 13, d: 11, style: "oak" }, { w: 15, d: 11, style: "stone" }, { w: 11, d: 9, style: "cobble" }, { w: 17, d: 13, style: "andesite" },
  { w: 13, d: 11, style: "stone" }, { w: 11, d: 9, style: "oak" }, { w: 15, d: 13, style: "cobble" }, { w: 13, d: 9, style: "andesite" },
  { w: 13, d: 11, style: "cobble" }, { w: 15, d: 11, style: "oak" }, { w: 11, d: 9, style: "stone" }, { w: 13, d: 13, style: "andesite" },
];

/**
 * How the lots that share a `block` stand together as one area of the city: so many across and so many down, an alley
 * between them. More lots than one area holds make further areas. A lot with no entry here has a block to itself.
 */
export const AREAS: Record<string, { cols: number; rows: number }> = {
  lodges: { cols: 3, rows: 3 }, paddocks: { cols: 2, rows: 2 }, quarter: { cols: 3, rows: 2 }, gardens: { cols: 3, rows: 1 },
};

/**
 * The city's lots, each naming the area it belongs to: the plaza, the ponds and the works each on their own, the
 * builders' yard with the stores of what it builds in, the gardens together, the lodges, the paddocks and the new quarter's houses (left
 * for the builders) in areas of a few, the wood and the field.
 */
export function catalogue(n: Counts): LotSpec[] {
  const works = worksPlan(n);
  const lots: LotSpec[] = [{ kind: "plaza", name: "plaza", w: 31, d: 31, pad: true },
    { kind: "ponds", name: "fish-ponds", w: 25, d: 15, pad: true },
    { kind: "works", name: "works", w: works.w, d: works.d, pad: true }];
  const store = (role: (typeof STORE_ROLES)[number], name = `store-${role}`, chests = STORE_CHESTS): LotSpec =>
    ({ kind: "store", name, w: 13, d: 11, pad: true, role, chests, block: STORE_AREAS[role] });
  const beside = (spec: LotSpec): LotSpec => ({ ...spec, beside: true });
  GARDENS.forEach((g, i) => lots.push({ kind: "garden", name: `garden-${i}`, w: g.side + 4, d: g.side + 4, pad: true, crop: g.crop, block: "gardens" }));
  // A pantry to each area of lodges, the town's four chests of food shared among them.
  const lodges = Math.ceil(n.humans / BEDS_PER_LODGE), perArea = AREAS.lodges.cols * AREAS.lodges.rows - 1;
  const pantry = Math.max(1, Math.floor(STORE_CHESTS / Math.ceil(lodges / perArea)));
  for (let i = 0; i < lodges; i++) {
    lots.push({ kind: "lodge", name: `lodge-${i}`, w: 21, d: 13, pad: true, block: "lodges" });
    if ((i + 1) % perArea === 0 || i === lodges - 1) lots.push(store("food", `store-food-${Math.floor(i / perArea)}`, pantry));
  }
  // What the houses are built from stands down the side of the quarter they are built in.
  lots.push(...[{ kind: "yard", name: "builders-yard", w: 17, d: 11, pad: true, block: "quarter" } as LotSpec,
    store("log"), store("plank"), store("stone")].map(beside));
  QUARTER.forEach((h, i) => lots.push({ kind: "house", name: `house-${i}`, w: h.w, d: h.d, pad: false, role: h.style, block: "quarter" }));
  for (let i = 0; i * PENS_PER_PADDOCK < n.pens; i++)
    lots.push({ kind: "paddock", name: `paddock-${i}`, w: 25, d: 25, pad: true, block: "paddocks" });
  lots.push(store("feed"), store("meat"));
  lots.push({ kind: "grove", name: "wood", w: WOOD_SIDE, d: WOOD_SIDE, pad: true, crop: "minecraft:oak_sapling" });
  lots.push({ kind: "field", name: "field", w: FIELD_SIDE + 4, d: FIELD_SIDE + 4, pad: false, block: "field" },
    beside(store("seed")), beside(store("crop")));
  return lots;
}

/** The lots out at the mine, gathered round its halt: a store, so what is dug has somewhere near to go. */
export function mineLots(): LotSpec[] {
  return [{ kind: "store", name: "works-store-stone", w: 13, d: 11, ring: [0, 30], range: 6, pad: true, role: "stone" }];
}

// ---------------------------------------------------------------------------------------------------------
// The city's blocks

/** The width of the street between two blocks, and the alley left between two lots of one area. */
export const STREET = 7, ALLEY = 3;
/** How wide a street is paved; the rest of its width is verge. */
export const PAVED = 5;
const MID = Math.floor(STREET / 2);
/** The least width of a common: a row shorter than the widest by this much and a street ends in one. */
const COMMON = 20;

/** What one block holds: a single building, or an area of lots of a kind, `u`/`v` each lot's corner within it. */
type Unit = { name: string; kind: LotKind; pad: boolean; w: number; d: number; lots: Array<{ spec: LotSpec; u: number; v: number }> };

/** A block of the city, measured from the city's north-west corner: its own size, and the lots standing in it. */
export type CityBlock = { name: string; kind: LotKind | "common"; pad: boolean; x: number; z: number; w: number; d: number;
  lots: Array<{ spec: LotSpec; u: number; v: number }> };

/** The lots gathered into what each block holds: single buildings, and areas laid out as AREAS says. */
export function unitsOf(specs: LotSpec[]): Unit[] {
  const groups: string[] = [];
  for (const s of specs) if (!groups.includes(s.block ?? s.name)) groups.push(s.block ?? s.name);
  const out: Unit[] = [];
  for (const group of groups) {
    const all = specs.filter((s) => (s.block ?? s.name) === group);
    const mine = all.filter((s) => !s.beside), side = all.filter((s) => s.beside);
    const { cols, rows } = AREAS[group] ?? { cols: 1, rows: 1 };
    const areas = Math.ceil(mine.length / (cols * rows));
    for (let i = 0; i < mine.length; i += cols * rows) {
      const part = mine.slice(i, i + cols * rows), area = i / (cols * rows);
      const sw = Math.max(...part.map((s) => s.w)), sd = Math.max(...part.map((s) => s.d));
      const across = Math.min(cols, part.length), down = Math.ceil(part.length / cols);
      const w = across * sw + (across - 1) * ALLEY, d = down * sd + (down - 1) * ALLEY;
      const lots = part.map((spec, k) => ({ spec, u: (k % cols) * (sw + ALLEY) + Math.floor((sw - spec.w) / 2),
        v: Math.floor(k / cols) * (sd + ALLEY) + Math.floor((sd - spec.d) / 2) }));
      // What stands beside is shared among the group's areas in turn, and laid in a column down the area's east side.
      const beside = side.filter((_, k) => k % areas === area), wide = Math.max(0, ...beside.map((s) => s.w));
      let v = 0;
      for (const spec of beside) {
        lots.push({ spec, u: w + ALLEY + Math.floor((wide - spec.w) / 2), v });
        v += spec.d + ALLEY;
      }
      out.push({ name: areas > 1 ? `${group}-${area}` : group, kind: part[0].kind, pad: part[0].pad,
        w: beside.length ? w + ALLEY + wide : w, d: Math.max(d, v - ALLEY), lots });
    }
  }
  return out;
}

type Row = { depth: number; cols: Unit[][] };

/**
 * Rows of blocks for a city about `target` wide: the deepest unit left opens a row, units nearly as deep (`share` of
 * its depth) stand beside it, and under a shallower one whatever still fits is stacked, a street between.
 */
function rowsOf(units: Unit[], target: number, share: number): Row[] {
  const left = [...units].sort((a, b) => b.d - a.d || b.w - a.w);
  const rows: Row[] = [];
  while (left.length) {
    const head = left.shift()!;
    const row: Row = { depth: head.d, cols: [[head]] };
    let width = head.w;
    for (let i = 0; i < left.length;) {
      const u = left[i];
      if (u.d >= row.depth * share && width + STREET + u.w <= target) { row.cols.push([u]); width += STREET + u.w; left.splice(i, 1); } else i++;
    }
    for (const col of row.cols) for (;;) {
      const room = row.depth - col.reduce((n, u) => n + u.d + STREET, 0);
      const k = left.findIndex((u) => u.d <= room && Math.abs(u.w - col[0].w) <= 12);
      if (k < 0) break;
      col.push(left.splice(k, 1)[0]);
    }
    rows.push(row);
  }
  return rows;
}

const widthOf = (row: Row) => row.cols.reduce((n, col) => n + Math.max(...col.map((u) => u.w)) + STREET, -STREET);

/**
 * The city's blocks: rows of unequal depth, each cut into blocks of unequal width, every block holding one building or
 * one area and sized to it. Of the ways to fill the rows, the one that leaves the least ground over in its blocks, in
 * a city not much longer than wide. A row a little short of the widest has its blocks widened to it; one well short
 * ends in a common, a block of open grass. The plaza's row stands in the middle, the plaza in the middle of it.
 * Streets are answered as their centre lines.
 */
export function layoutCity(specs: LotSpec[]) {
  const units = unitsOf(specs);
  const side = Math.sqrt(units.reduce((n, u) => n + (u.w + STREET) * (u.d + STREET), 0));
  let best: { rows: Row[]; cost: number } | undefined;
  for (let target = Math.round(side * 0.8); target <= side * 1.6; target += 4) for (const share of [0.5, 0.6, 0.7, 0.8]) {
    const rows = rowsOf(units, target, share);
    const W = Math.max(...rows.map(widthOf)), D = rows.reduce((n, r) => n + r.depth + STREET, -STREET);
    const held = units.reduce((n, u) => n + u.w * u.d, 0);
    const blocks = rows.reduce((n, r) => n + (W - STREET * (r.cols.length - 1)) * r.depth
      - r.cols.reduce((m, col) => m + STREET * (col.length - 1) * Math.max(...col.map((u) => u.w)), 0), 0);
    const cost = (blocks - held) * (1 + Math.max(0, Math.max(W / D, D / W) - 1.4));
    if (!best || cost < best.cost) best = { rows, cost };
  }
  const rows = best!.rows;
  const middle = <T>(list: T[], has: (t: T) => boolean) => {
    const k = list.findIndex(has);
    if (k >= 0) list.splice(Math.floor((list.length - 1) / 2), 0, list.splice(k, 1)[0]);
  };
  const plaza = (col: Unit[]) => col.some((u) => u.kind === "plaza");
  middle(rows, (r) => r.cols.some(plaza));
  for (const r of rows) middle(r.cols, plaza);
  const W = Math.max(...rows.map(widthOf));
  const blocks: CityBlock[] = [];
  const streets: Array<{ a: [number, number]; b: [number, number] }> = [];
  const w = W + 2 * STREET;
  let z = 0;
  for (const row of rows) {
    streets.push({ a: [MID, z + MID], b: [w - 1 - MID, z + MID] });
    // A row well short of the widest ends in a common; what is left over is shared among its blocks.
    const short = W - widthOf(row), common = short >= COMMON + STREET ? short - STREET : 0;
    const spare = common ? 0 : short;
    let x = 0;
    row.cols.forEach((col, i) => {
      // The row's spare width is shared among its blocks.
      const cw = Math.max(...col.map((u) => u.w)) + Math.floor(spare / row.cols.length) + (i < spare % row.cols.length ? 1 : 0);
      streets.push({ a: [x + MID, z + MID], b: [x + MID, z + STREET + row.depth + MID] });
      const room = row.depth - col.reduce((n, u) => n + u.d + STREET, -STREET);
      let v = z + STREET;
      col.forEach((u, k) => {
        const cd = u.d + Math.floor(room / col.length) + (k < room % col.length ? 1 : 0);
        if (k) streets.push({ a: [x + MID, v - STREET + MID], b: [x + STREET + cw + MID, v - STREET + MID] });
        const du = Math.floor((cw - u.w) / 2), dv = Math.floor((cd - u.d) / 2);
        blocks.push({ name: u.name, kind: u.kind, pad: u.pad, x: x + STREET, z: v, w: cw, d: cd,
          lots: u.lots.map((l) => ({ spec: l.spec, u: l.u + du, v: l.v + dv })) });
        v += cd + STREET;
      });
      x += STREET + cw;
    });
    streets.push({ a: [x + MID, z + MID], b: [x + MID, z + STREET + row.depth + MID] });
    if (common) {
      blocks.push({ name: `common-${blocks.length}`, kind: "common", pad: false, x: x + STREET, z: z + STREET, w: common, d: row.depth, lots: [] });
      streets.push({ a: [w - 1 - MID, z + MID], b: [w - 1 - MID, z + STREET + row.depth + MID] });
    }
    z += STREET + row.depth;
  }
  streets.push({ a: [MID, z + MID], b: [w - 1 - MID, z + MID] });
  return { w, d: z + STREET, blocks, streets };
}

/** The city's shape for its lots: half its width and depth, outer streets included. */
export function cityShape(specs: LotSpec[]) {
  const { w, d } = layoutCity(specs);
  return { w, d, halfW: Math.ceil(w / 2), halfD: Math.ceil(d / 2) };
}

/** The city on the ground: its box, each block's box and what it holds, and its streets. */
export type CityGrid = { box: Box; blocks: Array<{ name: string; kind: LotKind | "common"; pad: boolean; box: Box }>; streets: Road[] };

/**
 * The city laid out round its site: every lot in its block, on the city's one level with its door to the south, and
 * the streets between the blocks, each cut into roads of a block's length or so.
 */
export function placeGrid(site: SiteChoice, specs: LotSpec[]) {
  const city = layoutCity(specs);
  const minX = site.x - Math.ceil(city.w / 2), minZ = site.z - Math.ceil(city.d / 2);
  const box: Box = { minX, maxX: minX + city.w - 1, minZ, maxZ: minZ + city.d - 1 };
  const y = site.level + 1;
  const lots: Lot[] = [];
  for (const block of city.blocks) for (const { spec, u, v } of block.lots) {
    const frame = new Frame(minX + block.x + u, minZ + block.z + v, y, 0, spec.w, spec.d);
    const door = frame.at(Math.floor(spec.w / 2), 0, spec.d - 1), out = frame.at(Math.floor(spec.w / 2), 0, spec.d + 1);
    lots.push({ ...spec, frame, box: frame.box(), y, door: { x: door.x, z: door.z, out: { x: out.x, z: out.z } } });
  }
  const streets: Road[] = [];
  city.streets.forEach(({ a, b }, k) => {
    const length = Math.abs(b[0] - a[0]) + Math.abs(b[1] - a[1]), parts = Math.max(1, Math.round(length / 64));
    const at = (i: number): [number, number] => [minX + a[0] + Math.round((b[0] - a[0]) * i / parts), minZ + a[1] + Math.round((b[1] - a[1]) * i / parts)];
    for (let i = 0; i < parts; i++) {
      const [p, q] = [at(i), at(i + 1)], n = Math.abs(q[0] - p[0]) + Math.abs(q[1] - p[1]);
      const cells = Array.from({ length: n + 1 }, (_, j): [number, number] => [p[0] + Math.sign(q[0] - p[0]) * j, p[1] + Math.sign(q[1] - p[1]) * j]);
      streets.push({ name: `street-${k}-${i}`, width: PAVED, points: [[p[0], site.level, p[1]], [q[0], site.level, q[1]]], cells,
        ends: [site.level, site.level] });
    }
  });
  const grid: CityGrid = { box, streets, blocks: city.blocks.map((b) => ({ name: b.name, kind: b.kind, pad: b.pad,
    box: { minX: minX + b.x, maxX: minX + b.x + b.w - 1, minZ: minZ + b.z, maxZ: minZ + b.z + b.d - 1 } })) };
  return { lots, grid };
}

/** The ground as it lies once the city is levelled: the city's own level inside its box, the survey's beyond it. */
export function levelled(t: Terrain, city: Box, level: number): Terrain {
  const inside = (x: number, z: number) => x >= city.minX && x <= city.maxX && z >= city.minZ && z <= city.maxZ;
  return Object.assign(Object.create(t), { ground: (x: number, z: number) => inside(x, z) ? level : t.ground(x, z) }) as Terrain;
}

/** How far round the city its ground is eased into the land. */
export const SKIRT = 12;

/**
 * The city's ground: every column of its box levelled, and a skirt round it where the land is cut or banked to within a
 * block's rise per block out, so the city can be walked out of on every side. `spared` columns (the railway's) are left.
 */
export function levelCity(t: Terrain, box: Box, level: number, works: Works, spared: (x: number, z: number) => boolean) {
  const y = level + 1, TILE = 64;
  for (let x = box.minX; x <= box.maxX; x += TILE) for (let z = box.minZ; z <= box.maxZ; z += TILE) {
    const tile: Box = { minX: x, maxX: Math.min(box.maxX, x + TILE - 1), minZ: z, maxZ: Math.min(box.maxZ, z + TILE - 1) };
    works.clear(tile);
    works.pad(tile, y, "minecraft:grass_block", "minecraft:dirt", "minecraft:dirt", 12);
  }
  let eased = 0;
  for (let x = box.minX - SKIRT; x <= box.maxX + SKIRT; x++) for (let z = box.minZ - SKIRT; z <= box.maxZ + SKIRT; z++) {
    const out = Math.max(box.minX - x, x - box.maxX, box.minZ - z, z - box.maxZ);
    // Water at the city's edge is left as it lies.
    if (out <= 0 || !t.has(x, z) || t.wet(x, z) || spared(x, z)) continue;
    const g = t.ground(x, z), want = Math.max(level - out, Math.min(level + out, g));
    if (want === g) continue;
    works.pad({ minX: x, maxX: x, minZ: z, maxZ: z }, want + 1, "minecraft:grass_block", "minecraft:dirt", "minecraft:dirt", 4);
    eased++;
  }
  return eased;
}

// ---------------------------------------------------------------------------------------------------------
// Placement

const GAP = 5;

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

/** Lots fitted to the ground as it lies within `half` of a site: each on the gentlest free ground its ring allows. */
export function placeLots(t: Terrain, site: SiteChoice, specs: LotSpec[], half: number, blocked: Box[] = []) {
  const core: Box = { minX: site.x - half, maxX: site.x + half, minZ: site.z - half, maxZ: site.z + half };
  const outer = grow(core, SPILL);
  const W = outer.maxX - outer.minX + 1, D = outer.maxZ - outer.minZ + 1;
  const wet = new Grid(outer.minX, outer.minZ, W, D, (x, z) => t.wet(x, z) ? 1 : 0);
  const taken = new Uint8Array(W * D);
  // Ground kept for something else (the railway) counts as taken from the start.
  for (const b of blocked) for (let x = Math.max(outer.minX, b.minX); x <= Math.min(outer.maxX, b.maxX); x++)
    for (let z = Math.max(outer.minZ, b.minZ); z <= Math.min(outer.maxZ, b.maxZ); z++) taken[(x - outer.minX) * D + (z - outer.minZ)] = 1;
  let occupied = new Grid(outer.minX, outer.minZ, W, D, (x, z) => taken[(x - outer.minX) * D + (z - outer.minZ)]);
  const lots: Lot[] = [];
  const skipped: string[] = [];
  for (const spec of specs) {
    let best: { cost: number; lot: Lot } | undefined;
    const ring = spec.ring ?? [0, 60];
    for (let relax = 0; relax < 4 && !best; relax++) {
      const range = (spec.range ?? 6) + Math.min(relax, 2) * 3;
      const area = relax === 3 ? outer : core;
      for (let x = area.minX; x <= area.maxX; x += 2) for (let z = area.minZ; z <= area.maxZ; z += 2) {
        const toward = { x: site.x - x, z: site.z - z };
        const rot = spec.fixed ? 0 : Math.abs(toward.x) > Math.abs(toward.z) ? (toward.x > 0 ? 3 : 1) : (toward.z > 0 ? 0 : 2);
        const W = rot % 2 ? spec.d : spec.w, Dd = rot % 2 ? spec.w : spec.d;
        const box: Box = { minX: x - Math.floor(W / 2), maxX: x - Math.floor(W / 2) + W - 1, minZ: z - Math.floor(Dd / 2), maxZ: z - Math.floor(Dd / 2) + Dd - 1 };
        if (box.minX < area.minX || box.maxX > area.maxX || box.minZ < area.minZ || box.maxZ > area.maxZ) continue;
        if (occupied.sum(grow(box, GAP)) > 0) continue;
        if (wet.sum(grow(box, 1)) > 0) continue;
        const heights = heightsIn(t, box);
        const lo = Math.min(...heights), hi = Math.max(...heights);
        if (hi - lo > range) continue;
        const r = Math.hypot(x - site.x, z - site.z);
        let pull = Math.max(0, ring[0] - r, r - ring[1]) * 1.5;
        if (spec.near) {
          const mates = lots.filter((l) => l.kind === spec.near);
          if (mates.length) pull += Math.min(...mates.map((m) => Math.hypot(centre(m.box).x - x, centre(m.box).z - z))) * 0.25;
        }
        const cost = (hi - lo) * 2 + pull + Math.abs(median(heights) - site.level) * 1.5;
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
// Roads out at the mine: a spanning tree over the lots' doors from the halt, each edge routed by A* around lots and water.

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

/**
 * Streets from the plaza (or a works' halt) to every lot's door, routed by A* over the ground in eight directions, so
 * they run diagonally where the land and the lots let them. `track` is crossed only where it must be, straight over
 * on the level.
 */
export function routeRoads(t: Terrain, lots: Lot[], bounds: Box, ground: number, track: Box[] = []) {
  const W = bounds.maxX - bounds.minX + 1, D = bounds.maxZ - bounds.minZ + 1;
  const idx = (x: number, z: number) => (x - bounds.minX) * D + (z - bounds.minZ);
  const blocked = new Uint8Array(W * D), road = new Uint8Array(W * D), rail = new Uint8Array(W * D);
  for (const b of track) for (let x = Math.max(bounds.minX, b.minX); x <= Math.min(bounds.maxX, b.maxX); x++)
    for (let z = Math.max(bounds.minZ, b.minZ); z <= Math.min(bounds.maxZ, b.maxZ); z++) rail[idx(x, z)] = 1;
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
    const dirs = [[1, 0], [-1, 0], [0, 1], [0, -1], [1, 1], [1, -1], [-1, 1], [-1, -1]];
    let steps = 0;
    while (heap.size && steps++ < 1_200_000) {
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
        const diagonal = dx !== 0 && dz !== 0;
        // Corners are not cut past a lot or water, and track is crossed square on, never along or aslant.
        if (diagonal && (blocked[idx(cx + dx, cz)] || blocked[idx(cx, cz + dz)])) continue;
        if (diagonal && (rail[n] || rail[cur])) continue;
        if (rail[n] && rail[cur] && came[cur] >= 0 && came[cur] - cur !== cur - n) continue;
        const climb = Math.abs(t.ground(nx, nz) - t.ground(cx, cz));
        const turn = came[cur] >= 0 && (came[cur] - cur) !== (cur - n) ? 0.6 : 0;
        const step = diagonal ? Math.SQRT2 : 1;
        const cost = g[cur] + step * (road[n] ? 0.35 : 1) + climb * climb * 1.5 + turn + (rail[n] ? 12 : 0);
        if (cost < g[n]) {
          g[n] = cost; came[n] = cur;
          heap.push(cost + Math.hypot(nx - to.x, nz - to.z) * 0.35, n);
        }
      }
    }
    if (came[goal] < 0) return null;
    const path: Array<[number, number]> = [];
    for (let c = goal; c >= 0; c = came[c]) { path.push([Math.floor(c / D) + bounds.minX, (c % D) + bounds.minZ]); if (c === s) break; }
    return path.reverse();
  };

  // Streets grow from the plaza, or out at the works from the halt the train sets down at.
  const plaza = lots.find((l) => l.kind === "plaza") ?? lots.find((l) => l.kind === "halt") ?? lots[0];
  const joined = [plaza];
  const waiting = lots.filter((l) => l !== plaza);
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
    // The stair-steps of a grid route smoothed into straight runs at whatever angle the ground took them.
    const corners = simplify(ends, 1.2);
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

/** Douglas–Peucker: the fewest corners that keep every point within `tolerance` of the line. */
function simplify(points: Array<[number, number]>, tolerance: number): Array<[number, number]> {
  if (points.length < 3) return points;
  const [ax, az] = points[0], [bx, bz] = points[points.length - 1];
  const len = Math.hypot(bx - ax, bz - az) || 1;
  let far = 0, at = 0;
  for (let i = 1; i < points.length - 1; i++) {
    const [px, pz] = points[i];
    const d = Math.abs((bx - ax) * (az - pz) - (ax - px) * (bz - az)) / len;
    if (d > far) { far = d; at = i; }
  }
  if (far <= tolerance) return [points[0], points[points.length - 1]];
  return [...simplify(points.slice(0, at + 1), tolerance).slice(0, -1), ...simplify(points.slice(at), tolerance)];
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
