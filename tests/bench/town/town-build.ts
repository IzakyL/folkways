import {
  CHEST_TIERS, ISLAND, Draft, Frame, HOUSE_STYLES, STYLES, WORKS_ROW, Works, yard, catalogue, grow, hall, levelCity, levelled,
  mineLots, placeGrid, placeLots, routeRoads, worksPlan,
  type Box, type Counts, type Lot, type LotKind, type SiteChoice, type Terrain, type Vec,
} from "./town.ts";
import { railBands, type Halt, type Network } from "./network.ts";

// What each lot holds, laid by Mason and remembered for the colony's save.
export type Cooker = { pos: Vec; kind: "furnace" | "blast_furnace" | "smoker" };
export type Table = { pos: Vec; kind: "crafting_table" | "smithing_table" };
export type Bed = { foot: Vec; head: Vec };
export type Pen = { min: Vec; max: Vec; gate: Vec; fences: Vec[]; center: Vec; animal: string; heads: number; target: number };
export type Plot = { name: string; crop: string; cells: Vec[]; ripe: Vec[] };
export type BlueprintCell = { x: number; y: number; z: number; state: string };

export const ANIMALS = { breed: ["sheep", "cow"], cull: ["pig", "chicken"] } as const;
export const BREED = { heads: 4, target: 12 };
export const CULL = { heads: 10, target: 4 };

// Captures what a builder would lay instead of laying it, for blueprints residents build themselves.
export class Recorder extends Works {
  readonly cells = new Map<string, BlueprintCell>();
  override fill(a: Vec, b: Vec, state: string) {
    for (let x = Math.min(a.x, b.x); x <= Math.max(a.x, b.x); x++) for (let y = Math.min(a.y, b.y); y <= Math.max(a.y, b.y); y++)
      for (let z = Math.min(a.z, b.z); z <= Math.max(a.z, b.z); z++) this.set({ x, y, z }, state);
  }
  override set(p: Vec, state: string) {
    const key = `${p.x},${p.y},${p.z}`;
    if (state === "minecraft:air") this.cells.delete(key);
    else this.cells.set(key, { ...p, state });
  }
}

const FLOWERS = ["minecraft:poppy", "minecraft:dandelion", "minecraft:cornflower", "minecraft:oxeye_daisy", "minecraft:allium"];

class Builder {
  readonly roleChests: Array<{ role: string; pos: Vec }> = [];
  readonly stores: Array<{ role: string; pos: Vec; lot: string }> = [];
  readonly outputChests: Vec[] = [];
  readonly yardChests: Vec[] = [];
  /** Each kept chest's other half, and the cells of the chests no one keeps (those topping a stack up to its height). */
  readonly chestHalves: Array<{ anchor: Vec; other: Vec }> = [];
  readonly spareChests: Vec[] = [];
  readonly cookers: Cooker[] = [];
  readonly tables: Table[] = [];
  readonly stonecutters: Vec[] = [];
  readonly campfires: Vec[] = [];
  readonly beds: Bed[] = [];
  readonly pens: Pen[] = [];
  readonly plots: Plot[] = [];
  readonly grove: Vec[] = [];
  readonly ponds: Array<{ water: Vec[]; cells: Vec[] }> = [];
  readonly spawns: Vec[] = [];
  readonly fields: Array<{ lot: Lot; zone: [Vec, Vec] }> = [];
  /** Houses left for the builders: the new quarter's, each with its own earthwork. */
  readonly houses: Array<{ anchor: Vec; blocks: BlueprintCell[]; lot: Lot; style: string }> = [];
  private penQueue: Array<{ animal: string; heads: number; target: number }> = [];
  private readonly counts: Counts;
  private bedsLeft: number;
  private seed = 7;

  readonly works: Works;
  readonly terrain: Terrain;

  constructor(works: Works, n: Counts, terrain: Terrain) {
    this.works = works;
    this.terrain = terrain;
    this.counts = n;
    this.bedsLeft = n.humans;
    for (let i = 0; i < n.pens; i++) {
      const breed = i < n.pens / 2;
      this.penQueue.push({ animal: (breed ? ANIMALS.breed : ANIMALS.cull)[i % 2], ...(breed ? BREED : CULL) });
    }
  }

  random() { this.seed = (this.seed * 1103515245 + 12345) & 0x7fffffff; return this.seed / 0x7fffffff; }

  apron(lot: Lot) {
    const f = lot.frame;
    if (["lodge", "store", "yard"].includes(lot.kind)) {
      // Occupy the hall's aisles instead of lining everyone up against exterior walls.
      const mid = Math.floor(f.d / 2);
      for (let u = 3; u <= f.w - 4; u += 2) for (let v = mid - 1; v <= mid + 1; v++) this.spawns.push(f.at(u, 0, v));
      return;
    }
    for (let u = 0; u < f.w; u++) for (const v of [0, f.d - 1]) this.spawns.push(f.at(u, 0, v));
    for (let v = 1; v < f.d - 1; v++) for (const u of [0, f.w - 1]) this.spawns.push(f.at(u, 0, v));
  }

  lot(lot: Lot) {
    const t = new Draft(lot.frame, this.works);
    const f = lot.frame;
    switch (lot.kind) {
      case "plaza": return this.plaza(lot, t);
      case "works": return this.worksLot(lot, t);
      case "store": {
        yard(t, 1, 1, f.w - 2, f.d - 2, "minecraft:oak_planks");
        for (const pos of this.chests(t, Math.floor(f.w / 2) - 1, 3, lot.chests ?? 4)) this.stores.push({ role: lot.role!, pos, lot: lot.name });
        return this.apron(lot);
      }
      case "lodge": return this.lodge(lot, t, false);
      case "paddock": return this.paddock(lot, t);
      case "field":
        this.fields.push({ lot, zone: [f.at(2, -1, 2), f.at(f.w - 3, -1, f.d - 3)] });
        return;
      case "garden": return this.garden(lot, t);
      case "grove": return this.groveLot(lot, t);
      case "house": return this.house(lot);
      case "yard": {
        yard(t, 1, 1, f.w - 2, f.d - 2, "minecraft:spruce_planks");
        this.yardChests.push(...this.chests(t, Math.floor(f.w / 2) - 2, 3, 4 * CHEST_TIERS));
        t.fill(3, 0, f.d - 4, 5, 1, f.d - 3, "minecraft:oak_log[axis=x]");
        t.fill(f.w - 6, 0, f.d - 4, f.w - 4, 0, f.d - 3, "minecraft:stone_bricks");
        return this.apron(lot);
      }
      case "ponds": return this.pondLot(lot, t);
      case "halt":
        // The station's forecourt: lamp posts at the corners, a bench by the platform.
        for (const [u, v] of [[0, 0], [f.w - 1, 0], [0, f.d - 1], [f.w - 1, f.d - 1]]) {
          t.fill(u, 0, v, u, 1, v, "minecraft:spruce_fence");
          t.set(u, 2, v, "minecraft:lantern");
        }
        t.set(Math.floor(f.w / 2), 0, 0, "minecraft:spruce_stairs[facing=south]");
        return;
    }
  }

  plaza(lot: Lot, t: Draft) {
    const f = lot.frame;
    for (let u = 0; u < f.w; u++) for (let v = 0; v < f.d; v++) {
      const ring = Math.max(Math.abs(u - 15), Math.abs(v - 15));
      if (ring % 4 === 0) t.set(u, -1, v, "minecraft:stone_bricks");
      else if ((u + v) % 7 === 0) t.set(u, -1, v, "minecraft:andesite");
    }
    // The well.
    t.fill(12, -3, 12, 18, -1, 18, "minecraft:stone_bricks");
    t.fill(13, -2, 13, 17, -1, 17, "minecraft:water");
    t.fill(12, 0, 12, 18, 0, 18, "minecraft:stone_brick_wall");
    t.fill(13, 0, 13, 17, 0, 17, "minecraft:air");
    for (const [u, v] of [[12, 12], [18, 12], [12, 18], [18, 18]]) {
      t.fill(u, 0, v, u, 2, v, "minecraft:spruce_fence");
      t.set(u, 3, v, "minecraft:spruce_planks");
    }
    t.fill(11, 4, 11, 19, 4, 19, "minecraft:spruce_slab[type=bottom]");
    t.fill(13, 4, 13, 17, 4, 17, "minecraft:spruce_planks");
    t.set(15, 5, 15, "minecraft:lantern");
    // A planted bed in each quarter of the square: a flowering azalea and flowers round it.
    [[4, 4], [22, 4], [4, 22], [22, 22]].forEach(([u0, v0], i) => {
      t.fill(u0 + 1, -1, v0 + 1, u0 + 3, -1, v0 + 3, "minecraft:moss_block");
      t.set(u0 + 2, 0, v0 + 2, "minecraft:flowering_azalea");
      for (const [du, dv] of [[1, 2], [3, 2], [2, 1], [2, 3]]) t.set(u0 + du, 0, v0 + dv, FLOWERS[(i + du + dv) % FLOWERS.length]);
    });
    for (const [u, v] of [[9, 2], [21, 2], [9, 28], [21, 28], [2, 9], [2, 21], [28, 9], [28, 21]]) {
      t.fill(u, 0, v, u, 2, v, "minecraft:spruce_fence");
      t.set(u, 3, v, "minecraft:lantern");
    }
    for (let u = 0; u < f.w; u += 2) for (let v = 0; v < f.d; v += 2) {
      const near = (a: number, b: number) => Math.abs(u - a) <= 4 && Math.abs(v - b) <= 4;
      if (near(6, 6) || near(24, 6) || near(6, 24) || near(24, 24) || near(15, 15)) continue;
      if ([9, 21, 2, 28].includes(u) || [9, 21, 2, 28].includes(v)) continue;
      this.spawns.push(f.at(u, 0, v));
    }
  }

  /**
   * Double chests in a wall along u from u0, two cells thick (v and v + 1) and CHEST_TIERS high, each chest lying across
   * the wall with its long sides against its neighbours'. Answers where each of the n chests is kept: the half the
   * colony marks a double chest by. Every stack stands its full height, the last topped up with chests no one keeps.
   */
  chests(t: Draft, u0: number, v: number, n: number): Vec[] {
    const f = t.f, kept: Vec[] = [];
    for (let i = 0; i < Math.ceil(n / CHEST_TIERS) * CHEST_TIERS; i++) {
      const u = u0 + Math.floor(i / CHEST_TIERS), dy = i % CHEST_TIERS;
      t.set(u, dy, v, "minecraft:chest[facing=west,type=right]");
      t.set(u, dy, v + 1, "minecraft:chest[facing=west,type=left]");
      const a = f.at(u, dy, v), b = f.at(u, dy, v + 1);
      if (i >= n) { this.spareChests.push(a, b); continue; }
      const [anchor, other] = (a.z === b.z ? a.x < b.x : a.z < b.z) ? [a, b] : [b, a];
      kept.push(anchor);
      this.chestHalves.push({ anchor, other });
    }
    return kept;
  }

  worksLot(lot: Lot, t: Draft) {
    const f = lot.frame, plan = worksPlan(this.counts);
    yard(t, 1, 1, f.w - 2, f.d - 2, "minecraft:smooth_stone");
    // The benches: each row faces its own aisle, its back to the other row's.
    plan.stations.forEach(({ kind, bench, row, along }) => {
      const u = plan.first + along, v = plan.band(plan.benchBand(bench)) + row;
      const pos = f.at(u, 0, v), facing = row ? "south" : "north";
      if (kind === "stonecutter") {
        t.set(u, 0, v, `minecraft:stonecutter[facing=${facing}]`);
        this.stonecutters.push(pos);
      } else if (kind === "crafting_table" || kind === "smithing_table") {
        t.set(u, 0, v, `minecraft:${kind}`);
        this.tables.push({ pos, kind });
      } else {
        t.set(u, 0, v, `minecraft:${kind}[facing=${facing}]`);
        this.cookers.push({ pos, kind });
      }
    });
    const perWall = plan.stacks.length * CHEST_TIERS, perIsland = ISLAND * CHEST_TIERS;
    for (let wall = 0; wall < plan.walls; wall++) {
      const part = plan.chests.slice(wall * perWall, (wall + 1) * perWall);
      const at: Vec[] = [];
      for (let i = 0; i < part.length; i += perIsland)
        at.push(...this.chests(t, plan.first + plan.stacks[i / CHEST_TIERS], plan.band(plan.wallBand(wall)), Math.min(perIsland, part.length - i)));
      part.forEach((c, i) => {
        if (c.keep === "role") this.roleChests.push({ role: c.role, pos: at[i] });
        else if (c.keep === "output") this.outputChests.push(at[i]);
        else this.stores.push({ role: c.role, pos: at[i], lot: c.lot });
      });
    }
    // People start the day in the aisles, off the kerb where the lamp posts stand.
    for (let k = 0; k <= plan.benches + plan.walls; k++) for (let v = plan.band(k) - 3; v < plan.band(k); v++) {
      if (v <= 1 || v >= f.d - 2) continue;
      for (let u = plan.first; u < plan.first + WORKS_ROW; u += 2) this.spawns.push(f.at(u, 0, v));
    }
  }

  lodge(lot: Lot, t: Draft, blueprint: boolean) {
    const f = lot.frame;
    const style = blueprint ? STYLES.spruce : this.random() < 0.5 ? STYLES.timber : STYLES.spruce;
    hall(t, 1, 1, f.w - 2, f.d - 2, { style, height: 4, open: false, doors: ["west", "east", "south"] });
    if (blueprint) return;
    const door = Math.floor(f.w / 2);
    for (let u = 3; u <= f.w - 4 && this.bedsLeft > 0; u++) for (const [head, foot, facing] of [[2, 3, "north"], [f.d - 3, f.d - 4, "south"]] as const) {
      if (this.bedsLeft <= 0) break;
      if (facing === "south" && Math.abs(u - door) <= 1) continue;
      const color = ["red", "blue", "green", "brown", "cyan"][u % 5];
      t.set(u, 0, head, `minecraft:${color}_bed[part=head,facing=${facing}]`);
      t.set(u, 0, foot, `minecraft:${color}_bed[part=foot,facing=${facing}]`);
      this.beds.push({ foot: f.at(u, 0, foot), head: f.at(u, 0, head) });
      this.bedsLeft--;
    }
    for (let u = 2; u < f.w - 2; u += 3) if (Math.abs(u - door) > 1)
      t.set(u, 0, f.d - 1, FLOWERS[(((u + f.ox) % FLOWERS.length) + FLOWERS.length) % FLOWERS.length]);
    this.apron(lot);
  }

  paddock(lot: Lot, t: Draft) {
    const f = lot.frame;
    t.fill(10, -1, 0, 14, -1, f.d - 1, "minecraft:dirt_path");
    t.fill(0, -1, 10, f.w - 1, -1, 14, "minecraft:dirt_path");
    for (const [u0, v0] of [[1, 1], [15, 1], [1, 15], [15, 15]]) {
      const want = this.penQueue.shift();
      if (!want) break;
      const u1 = u0 + 8, v1 = v0 + 8;
      const gateU = u0 === 1 ? u1 : u0, gateV = v0 + 4;
      const fences: Vec[] = [];
      for (let u = u0; u <= u1; u++) for (let v = v0; v <= v1; v++) {
        if (u > u0 && u < u1 && v > v0 && v < v1) continue;
        if (u === gateU && v === gateV) continue;
        t.set(u, 0, v, "minecraft:oak_fence");
        fences.push(f.at(u, 0, v));
      }
      t.set(gateU, 0, gateV, `minecraft:oak_fence_gate[facing=${u0 === 1 ? "east" : "west"},open=false]`);
      const a = f.at(u0 + 1, -1, v0 + 1), b = f.at(u1 - 1, -1, v1 - 1);
      this.pens.push({ min: { x: Math.min(a.x, b.x), y: a.y, z: Math.min(a.z, b.z) }, max: { x: Math.max(a.x, b.x), y: a.y, z: Math.max(a.z, b.z) },
        gate: f.at(gateU, 0, gateV), fences, center: f.at(u0 + 4, 0, v0 + 4), ...want });
    }
    t.fill(11, 0, 11, 13, 0, 13, "minecraft:hay_block[axis=y]");
    t.set(12, 1, 12, "minecraft:hay_block[axis=x]");
    for (let k = 0; k < f.w; k += 3) if (k < 10 || k > 14) {
      this.spawns.push(f.at(12, 0, k));
      this.spawns.push(f.at(k, 0, 12));
    }
    this.apron(lot);
  }

  garden(lot: Lot, t: Draft) {
    const f = lot.frame;
    const crop = lot.crop!;
    const cells: Vec[] = [], ripe: Vec[] = [];
    const u0 = 2, u1 = f.w - 3, v0 = 2, v1 = f.d - 3;
    t.fill(u0 - 1, -1, v0 - 1, u1 + 1, -1, v1 + 1, "minecraft:mud_bricks");
    for (let v = v0; v <= v1; v++) {
      if (crop === "minecraft:sugar_cane") {
        const water = (v - v0) % 3 === 1;
        t.fill(u0, -1, v, u1, -1, v, water ? "minecraft:water" : "minecraft:grass_block");
        if (water) continue;
        for (let u = u0; u <= u1; u++) {
          cells.push(f.at(u, -1, v));
          if ((u + v) % 2 === 0) { t.fill(u, 0, v, u, 2, v, "minecraft:sugar_cane"); ripe.push(f.at(u, -1, v)); }
        }
        continue;
      }
      const row = (v - v0) % 4;
      const fruit = crop === "minecraft:pumpkin_stem" ? "minecraft:pumpkin" : "minecraft:melon";
      const attached = crop === "minecraft:pumpkin_stem" ? "minecraft:attached_pumpkin_stem" : "minecraft:attached_melon_stem";
      if (row === 3) { t.fill(u0, -1, v, u1, -1, v, "minecraft:water"); continue; }
      if (row === 1) { t.fill(u0, -1, v, u1, -1, v, "minecraft:dirt"); continue; }
      t.fill(u0, -1, v, u1, -1, v, "minecraft:farmland[moisture=7]");
      for (let u = u0; u <= u1; u++) {
        cells.push(f.at(u, -1, v));
        if (u % 3 === 0) {
          t.set(u, 0, v, `${attached}[facing=${row === 0 ? "south" : "north"}]`);
          t.set(u, 0, row === 0 ? v + 1 : v - 1, fruit);
          ripe.push(f.at(u, -1, v));
        } else if (u % 3 === 1) t.set(u, 0, v, `${crop}[age=${u % 2 ? 7 : 4}]`);
      }
    }
    this.plots.push({ name: lot.name, crop, cells, ripe });
    this.apron(lot);
  }

  /** A wood rather than an orchard: trunks a few blocks off a five-block grid, a third of them still saplings. */
  groveLot(lot: Lot, t: Draft) {
    const f = lot.frame;
    let k = 0;
    for (let u = 3; u <= f.w - 4; u += 5) for (let v = 3; v <= f.d - 4; v += 5) {
      const at = { u: u + Math.floor(this.random() * 3) - 1, v: v + Math.floor(this.random() * 3) - 1 };
      this.grove.push(f.at(at.u, -1, at.v));
      if (k++ % 3 !== 2) this.works.tree(f.at(at.u, 0, at.v), 0);
      else t.set(at.u, 0, at.v, "minecraft:oak_sapling[stage=1]");
    }
    t.fill(1, 0, f.d - 2, 3, 0, f.d - 2, "minecraft:oak_log[axis=x]");
    this.apron(lot);
  }

  /**
   * A house on the ground as it lies, as a blueprint the builders work through: a cobblestone footing up to the floor
   * where the ground falls away, the hill dug out where it rises, then the house itself in its style.
   */
  house(lot: Lot) {
    const f = lot.frame, rec = new Recorder(), t = new Draft(f, rec);
    const style = HOUSE_STYLES[lot.role ?? "oak"] ?? HOUSE_STYLES.oak;
    const floor = lot.y - 1;
    const dug: BlueprintCell[] = [];
    for (let u = 0; u < f.w; u++) for (let v = 0; v < f.d; v++) {
      const p = f.at(u, 0, v), g = this.terrain.ground(p.x, p.z);
      if (g < floor) rec.fill({ x: p.x, y: g + 1, z: p.z }, { x: p.x, y: floor, z: p.z }, style.base);
      for (let y = Math.max(lot.y, floor + 1); y <= Math.min(g, lot.y + 14); y++) dug.push({ x: p.x, y, z: p.z, state: "minecraft:air" });
    }
    const height = f.w * f.d >= 180 ? 5 : 4;
    hall(t, 1, 1, f.w - 2, f.d - 2, { style, height, open: false, doors: ["south"] });
    t.set(2, 0, 2, "minecraft:white_bed[part=head,facing=north]");
    t.set(2, 0, 3, "minecraft:white_bed[part=foot,facing=north]");
    t.set(f.w - 3, 0, 2, "minecraft:crafting_table");
    t.set(Math.floor(f.w / 2) + 2, 2, f.d - 1, "minecraft:wall_torch[facing=south]");
    // What the house stands in replaces the dug cells; the rest of the cut is left open air.
    const built = new Set([...rec.cells.keys()]);
    const blocks = [...dug.filter((c) => !built.has(`${c.x},${c.y},${c.z}`)), ...rec.cells.values()];
    const anchor = { x: Math.min(...blocks.map((c) => c.x)), y: Math.min(...blocks.map((c) => c.y)), z: Math.min(...blocks.map((c) => c.z)) };
    this.houses.push({ anchor, blocks, lot, style: lot.role ?? "oak" });
  }

  pondLot(lot: Lot, t: Draft) {
    const f = lot.frame;
    for (let k = 0; k < 6; k++) {
      const u = 1 + 4 * k;
      t.fill(u, -3, 6, u + 2, -3, 8, "minecraft:clay");
      t.fill(u, -2, 6, u + 2, -1, 8, "minecraft:water");
      t.fill(u, -1, 5, u + 2, -1, 5, "minecraft:spruce_planks");
      const water: Vec[] = [], cells: Vec[] = [];
      for (let du = 0; du < 3; du++) {
        cells.push(f.at(u + du, -1, 5));
        for (let v = 6; v <= 8; v++) water.push(f.at(u + du, -1, v));
      }
      t.set(u + 1, 0, 7, "minecraft:lily_pad");
      this.ponds.push({ water, cells });
    }
    this.apron(lot);
  }
}

const TOPS: Partial<Record<LotKind, string>> = {
  plaza: "minecraft:polished_andesite", paddock: "minecraft:grass_block",
  lodge: "minecraft:grass_block", grove: "minecraft:grass_block", garden: "minecraft:grass_block", ponds: "minecraft:grass_block",
  yard: "minecraft:coarse_dirt", halt: "minecraft:polished_andesite",
};

export type TownPlan = ReturnType<typeof planTown>;

export function planTown(t: Terrain, site: SiteChoice, counts: Counts) {
  return planSettlement(t, site, counts);
}

const FACING_ROT = { south: 0, west: 1, north: 2, east: 3 } as const;

/** A halt as a lot: a pad at its platform's height, its door away from the track. */
function haltLot(name: string, h: Halt): Lot {
  const rot = FACING_ROT[h.door];
  const dx = h.box.maxX - h.box.minX + 1, dz = h.box.maxZ - h.box.minZ + 1;
  const [w, d] = rot % 2 ? [dz, dx] : [dx, dz];
  const frame = new Frame(h.box.minX, h.box.minZ, h.y, rot, w, d);
  const door = frame.at(Math.floor(w / 2), 0, d - 1), out = frame.at(Math.floor(w / 2), 0, d + 1);
  return { kind: "halt", name, w, d, ring: [0, 0], range: 0, pad: true, frame, box: frame.box(), y: h.y,
    door: { x: door.x, z: door.z, out: { x: out.x, z: out.z } } };
}

export type District = { name: string; site: SiteChoice; box: Box; lots: Lot[]; halt: Lot };

/**
 * The city on its grid, and with a railway, the station on its west edge and the mine out at the mountain: the mine's
 * store stands by the halt its line sets down at, fitted to the ground there, and keeps off the track.
 */
export function planSettlement(t: Terrain, site: SiteChoice, counts: Counts, net?: Network) {
  const bands = net ? railBands(net) : [];
  const placed = placeGrid(site, catalogue(counts));
  const city = placed.grid.box;
  const townHalts = (net?.lines ?? []).flatMap((l) => l.halts.filter((h) => h.town)
    .map((h) => haltLot(`halt-${h.stop.toLowerCase().replace(/\s+/g, "-")}`, h.halt)));
  const skipped: string[] = [];
  const works: District[] = (net?.lines ?? []).flatMap((l) => l.works.map((w) => {
    const h = l.halts.find((x) => x.stop === w.stop)!;
    const halt = haltLot(`halt-${w.name.toLowerCase().replace(/\s+/g, "-")}`, h.halt);
    // The store gathers round the halt, on ground near its level.
    const mid = { x: Math.round((halt.box.minX + halt.box.maxX) / 2), z: Math.round((halt.box.minZ + halt.box.maxZ) / 2) };
    const at: SiteChoice = { x: mid.x, z: mid.z, level: halt.y - 1, sea: site.sea };
    const lots = placeLots(t, at, mineLots(), 48, [...bands, grow(halt.box, 3)]);
    const name = w.name.toLowerCase().replace(/\s+/g, "-");
    skipped.push(...lots.skipped.map((n) => `${name}:${n}`));
    const box = grow({ minX: w.x - w.half, maxX: w.x + w.half, minZ: w.z - w.half, maxZ: w.z + w.half }, 32);
    return { name, site: at, box, lots: lots.lots, halt };
  }));
  const allLots = [...placed.lots, ...townHalts, ...works.flatMap((w) => [...w.lots, w.halt])];
  const pads = new Works(), lay = new Works();
  const b = new Builder(lay, counts, levelled(t, city, site.level));
  // The city's ground first, then each lot's own pad on it, so the streets run level between them; the buildings go on
  // after the streets.
  const railway = [...bands, ...townHalts.map((h) => grow(h.box, 1))];
  const eased = levelCity(t, city, site.level, pads,
    (x, z) => railway.some((r) => x >= r.minX && x <= r.maxX && z >= r.minZ && z <= r.maxZ));
  // Each block is floored for what it holds, edge to edge, so a building or an area fills its block.
  for (const block of placed.grid.blocks)
    if (block.pad && block.kind !== "common") pads.pad(block.box, site.level + 1, TOPS[block.kind] ?? "minecraft:gravel", "minecraft:dirt", "minecraft:dirt", 12);
  for (const lot of allLots) {
    if (!placed.lots.includes(lot)) pads.clear(grow(lot.box, 3));
    if (lot.pad) pads.pad(lot.box, lot.y, TOPS[lot.kind] ?? "minecraft:gravel", "minecraft:dirt", "minecraft:stone_bricks", 12);
  }
  for (const lot of allLots) b.lot(lot);
  const roads = [...placed.grid.streets], unrouted: string[] = [];
  for (const w of works) {
    const r = routeRoads(t, [w.halt, ...w.lots], w.box, w.site.level, net ? railBands(net, 0) : []);
    roads.push(...r.roads.map((road) => ({ ...road, name: `${w.name}-${road.name}` })));
    unrouted.push(...r.unrouted.map((n) => `${w.name}:${n}`));
  }
  const boxes = [city, ...townHalts.map((l) => l.box)];
  const key = (p: Vec) => `${p.x},${p.y},${p.z}`;
  const occupied = new Set([...b.roleChests.map(c => c.pos), ...b.stores.map(c => c.pos), ...b.outputChests, ...b.yardChests,
    ...b.chestHalves.map(c => c.other), ...b.spareChests,
    ...b.cookers.map(c => c.pos), ...b.tables.map(c => c.pos), ...b.stonecutters, ...b.beds.flatMap(bed => [bed.foot, bed.head])].map(key));
  const spawns = b.spawns.filter(p => !occupied.has(key(p)));
  const extent: Box = { minX: Math.min(...boxes.map((x) => x.minX)), maxX: Math.max(...boxes.map((x) => x.maxX)),
    minZ: Math.min(...boxes.map((x) => x.minZ)), maxZ: Math.max(...boxes.map((x) => x.maxZ)) };
  return { site, lots: allLots, skipped, core: city, grid: placed.grid, eased, pads, works: lay, roads, unrouted, extent, network: net ?? null,
    districts: works.map((w) => ({ name: w.name, site: w.site, box: w.box, halt: w.halt.box, y: w.halt.y,
      door: w.halt.door, lots: w.lots.map((l) => ({ kind: l.kind, name: l.name, box: l.box })) })),
    roleChests: b.roleChests, stores: b.stores, outputChests: b.outputChests, yardChests: b.yardChests,
    chestHalves: b.chestHalves, spareChests: b.spareChests,
    cookers: b.cookers, tables: b.tables, stonecutters: b.stonecutters, campfires: b.campfires, beds: b.beds,
    pens: b.pens, plots: b.plots, grove: b.grove, ponds: b.ponds, spawns, fields: b.fields,
    houses: b.houses };
}
