import {
  Draft, STYLES, Works, catalogue, grow, hall, placeLots, routeRoads,
  type Box, type Counts, type Lot, type LotKind, type SiteChoice, type Terrain, type Vec,
} from "./town.ts";

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
export const ROLE_CHESTS = ["seed", "log", "plank", "iron", "stone", "meat", "feed", "tool", "output", "smeltOut", "food", "dairy"] as const;

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
  readonly stores: Array<{ role: string; pos: Vec }> = [];
  readonly outputChests: Vec[] = [];
  readonly yardChests: Vec[] = [];
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
  readonly fields: Array<{ lot: Lot; crop: string; zone: [Vec, Vec] }> = [];
  quarry: { anchor: Vec; blocks: BlueprintCell[] } | undefined;
  site: { anchor: Vec; blocks: BlueprintCell[]; lot: Lot } | undefined;
  wall: { lot: Lot; points: Vec[] } | undefined;
  dispatchOrigin: Vec | undefined;
  private cookerQueue: Cooker["kind"][];
  private tableQueue: Table["kind"][];
  private penQueue: Array<{ animal: string; heads: number; target: number }> = [];
  private smokers: number;
  private cutters: number;
  private bedsLeft: number;
  private seed = 7;

  readonly works: Works;

  constructor(works: Works, n: Counts) {
    this.works = works;
    this.cookerQueue = [...Array(n.cookers.blast).fill("blast_furnace"), ...Array(n.cookers.furnace).fill("furnace")];
    this.smokers = n.cookers.smoker;
    this.tableQueue = [...Array(n.tables.smithing).fill("smithing_table"), ...Array(n.tables.crafting).fill("crafting_table")];
    this.cutters = n.stonecutters;
    this.bedsLeft = n.humans;
    for (let i = 0; i < n.pens; i++) {
      const breed = i < n.pens / 2;
      this.penQueue.push({ animal: (breed ? ANIMALS.breed : ANIMALS.cull)[i % 2], ...(breed ? BREED : CULL) });
    }
  }

  random() { this.seed = (this.seed * 1103515245 + 12345) & 0x7fffffff; return this.seed / 0x7fffffff; }

  apron(lot: Lot) {
    const f = lot.frame;
    if (["lodge", "warehouse", "store", "smithy", "kitchen", "workshop", "masonry", "yard"].includes(lot.kind)) {
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
      case "warehouse": {
        hall(t, 1, 1, f.w - 2, f.d - 2, { style: STYLES.timber, height: 4, open: false, doors: ["west", "east", "south"] });
        const door = Math.floor(f.w / 2);
        for (let u = 3; u <= f.w - 4 && this.outputChests.length < 19; u++) {
          if (Math.abs(u - door) <= 1) continue;
          t.set(u, 0, 2, "minecraft:chest[facing=south]");
          this.outputChests.push(f.at(u, 0, 2));
        }
        for (let u = 3; u <= f.w - 4; u += 3) if (Math.abs(u - door) > 2) t.set(u, 0, f.d - 3, "minecraft:barrel[facing=up]");
        return this.apron(lot);
      }
      case "store": {
        hall(t, 1, 1, f.w - 2, f.d - 2, { style: STYLES.spruce, height: 4, open: false, doors: ["south"] });
        for (let i = 0; i < 4; i++) {
          const u = 3 + i + (i >= 2 ? 1 : 0);
          t.set(u, 0, 2, "minecraft:chest[facing=south]");
          this.stores.push({ role: lot.role!, pos: f.at(u, 0, 2) });
        }
        t.set(2, 0, 2, "minecraft:barrel[facing=up]");
        t.set(f.w - 3, 0, 2, "minecraft:barrel[facing=up]");
        return this.apron(lot);
      }
      case "smithy": case "kitchen": case "workshop": case "masonry": return this.workshop(lot, t);
      case "lodge": return this.lodge(lot, t, false);
      case "paddock": return this.paddock(lot, t);
      case "field":
        this.fields.push({ lot, crop: lot.crop!, zone: [f.at(2, -1, 2), f.at(f.w - 3, -1, f.d - 3)] });
        return;
      case "garden": return this.garden(lot, t);
      case "grove": return this.groveLot(lot, t);
      case "quarry": return this.quarryLot(lot, t);
      case "site": {
        const rec = new Recorder();
        this.lodge(lot, new Draft(lot.frame, rec), true);
        const blocks = [...rec.cells.values()];
        const anchor = { x: Math.min(...blocks.map((c) => c.x)), y: Math.min(...blocks.map((c) => c.y)), z: Math.min(...blocks.map((c) => c.z)) };
        this.site = { anchor, blocks, lot };
        for (const [u, v] of [[0, 0], [f.w - 1, 0], [0, f.d - 1], [f.w - 1, f.d - 1]]) t.set(u, 0, v, "minecraft:oak_fence");
        return this.apron(lot);
      }
      case "yard": {
        hall(t, 1, 1, f.w - 2, f.d - 2, { style: STYLES.spruce, height: 4, open: true, doors: [] });
        for (let u = 3; u <= f.w - 4; u++) {
          t.set(u, 0, 2, "minecraft:chest[facing=south]");
          this.yardChests.push(f.at(u, 0, 2));
        }
        t.fill(3, 0, f.d - 4, 5, 1, f.d - 3, "minecraft:oak_log[axis=x]");
        t.fill(f.w - 6, 0, f.d - 4, f.w - 4, 0, f.d - 3, "minecraft:stone_bricks");
        return this.apron(lot);
      }
      case "post": {
        this.dispatchOrigin = f.at(Math.floor(f.w / 2), 0, Math.floor(f.d / 2));
        t.fill(0, -1, 0, f.w - 1, -1, 0, "minecraft:stone_bricks");
        t.fill(0, -1, f.d - 1, f.w - 1, -1, f.d - 1, "minecraft:stone_bricks");
        for (const [u, v] of [[0, 0], [f.w - 1, 0], [0, f.d - 1], [f.w - 1, f.d - 1]]) {
          t.fill(u, 0, v, u, 1, v, "minecraft:spruce_fence");
          t.set(u, 2, v, "minecraft:lantern");
        }
        return this.apron(lot);
      }
      case "ponds": return this.pondLot(lot, t);
      case "wall":
        this.wall = { lot, points: [f.at(1, -1, Math.floor(f.d / 2)), f.at(f.w - 2, -1, Math.floor(f.d / 2))] };
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
    // Market stalls, three role chests under each awning.
    const wool = ["minecraft:red_wool", "minecraft:yellow_wool", "minecraft:blue_wool", "minecraft:green_wool"];
    [[4, 4], [22, 4], [4, 22], [22, 22]].forEach(([u0, v0], i) => {
      for (const [du, dv] of [[0, 0], [4, 0], [0, 4], [4, 4]]) t.fill(u0 + du, 0, v0 + dv, u0 + du, 2, v0 + dv, "minecraft:oak_fence");
      t.fill(u0, 3, v0, u0 + 4, 3, v0 + 4, wool[i]);
      t.fill(u0 + 1, 3, v0 + 1, u0 + 3, 3, v0 + 3, "minecraft:white_wool");
      for (let k = 0; k < 3; k++) {
        t.set(u0 + 1 + k, 0, v0 + 1, "minecraft:chest[facing=south]");
        this.roleChests.push({ role: ROLE_CHESTS[i * 3 + k], pos: f.at(u0 + 1 + k, 0, v0 + 1) });
      }
      t.set(u0 + 2, 0, v0 + 3, "minecraft:potted_red_tulip");
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

  workshop(lot: Lot, t: Draft) {
    const f = lot.frame;
    const hot = lot.kind === "smithy" || lot.kind === "kitchen";
    const v1 = f.d - 2;
    hall(t, 1, 1, f.w - 2, v1, { style: hot ? STYLES.forge : STYLES.spruce, height: 4, open: true, doors: [] });
    const rows = lot.kind === "masonry" ? [2] : [2, v1 - 1];
    // Working stock belongs inside each hall. The market and bulk stores still supply
    // the wider town, but every firing must not start with a cross-town fuel errand.
    const supplies = lot.kind === "smithy" ? ["iron", "fuel"]
      : lot.kind === "kitchen" ? ["meat", "fuel"]
      : lot.kind === "workshop" ? ["log", "plank"] : ["stone"];
    for (const [i, role] of [...supplies, "food"].entries()) {
      const u = 5 + i * 3;
      t.set(u, 0, 5, "minecraft:chest[facing=south]");
      this.stores.push({ role, pos: f.at(u, 0, 5) });
    }
    for (const v of rows) for (let u = 3; u <= f.w - 4; u += 2) {
      const p = f.at(u, 0, v);
      const facing = v === 2 ? "south" : "north";
      if (lot.kind === "smithy") {
        const kind = this.cookerQueue.shift();
        if (!kind) continue;
        t.set(u, 0, v, `minecraft:${kind}[facing=${facing}]`);
        this.cookers.push({ pos: p, kind });
      } else if (lot.kind === "kitchen") {
        if (this.smokers <= 0) continue;
        this.smokers--;
        t.set(u, 0, v, `minecraft:smoker[facing=${facing}]`);
        this.cookers.push({ pos: p, kind: "smoker" });
      } else if (lot.kind === "workshop") {
        const kind = this.tableQueue.shift();
        if (!kind) continue;
        t.set(u, 0, v, `minecraft:${kind}`);
        this.tables.push({ pos: p, kind });
      } else {
        if (this.cutters <= 0) continue;
        this.cutters--;
        t.set(u, 0, v, "minecraft:stonecutter[facing=south]");
        this.stonecutters.push(p);
      }
    }
    if (hot) {
      // A chimney at each gable, a smouldering campfire on its crown.
      const v = Math.floor(f.d / 2);
      for (const u of [0, f.w - 1]) {
        t.fill(u, 0, v, u, 10, v, "minecraft:bricks");
        if (this.campfires.length < 8) {
          t.set(u, 11, v, "minecraft:campfire[lit=true]");
          this.campfires.push(f.at(u, 11, v));
        }
      }
      if (lot.kind === "smithy") {
        t.set(Math.floor(f.w / 2), 0, v, "minecraft:anvil[facing=east]");
        t.set(Math.floor(f.w / 2) + 2, 0, v, "minecraft:grindstone[face=floor,facing=north]");
      }
    }
    if (lot.kind === "masonry") {
      for (let u = 3; u <= f.w - 5; u += 4) t.fill(u, 0, v1 - 2, u + 1, 0, v1 - 1, "minecraft:smooth_stone");
    }
    this.apron(lot);
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

  groveLot(lot: Lot, t: Draft) {
    const f = lot.frame;
    let k = 0;
    for (let u = 3; u <= f.w - 4; u += 5) for (let v = 3; v <= f.d - 4; v += 5) {
      this.grove.push(f.at(u, -1, v));
      if (k++ % 3 !== 2) this.works.tree(f.at(u, 0, v), 0);
      else t.set(u, 0, v, "minecraft:oak_sapling[stage=1]");
    }
    t.fill(1, 0, f.d - 2, 3, 0, f.d - 2, "minecraft:oak_log[axis=x]");
    this.apron(lot);
  }

  quarryLot(lot: Lot, t: Draft) {
    const f = lot.frame;
    const rocks = ["minecraft:stone", "minecraft:stone", "minecraft:andesite", "minecraft:cobblestone", "minecraft:granite", "minecraft:stone"];
    const blocks: BlueprintCell[] = [];
    for (let u = 3; u <= 18; u++) for (let v = 3; v <= 10; v++) {
      const height = Math.max(1, Math.min(4, 1 + Math.floor(3.5 * Math.sin(u * 0.35) * Math.cos(v * 0.45) + 2.5 * this.random())));
      for (let dy = 0; dy < 5; dy++) {
        if (dy < height) t.set(u, dy, v, rocks[Math.floor(this.random() * rocks.length)]);
        blocks.push({ ...f.at(u, dy, v), state: "minecraft:air" });
      }
    }
    const anchor = { x: Math.min(...blocks.map((c) => c.x)), y: Math.min(...blocks.map((c) => c.y)), z: Math.min(...blocks.map((c) => c.z)) };
    this.quarry = { anchor, blocks };
    this.apron(lot);
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
  plaza: "minecraft:polished_andesite", post: "minecraft:polished_andesite", paddock: "minecraft:grass_block",
  lodge: "minecraft:grass_block", grove: "minecraft:grass_block", garden: "minecraft:grass_block", ponds: "minecraft:grass_block",
  site: "minecraft:coarse_dirt", yard: "minecraft:coarse_dirt",
};

export type TownPlan = ReturnType<typeof planTown>;

export function planTown(t: Terrain, site: SiteChoice, counts: Counts, half: number) {
  const placed = placeLots(t, site, catalogue(counts), half);
  const pads = new Works(), works = new Works();
  const b = new Builder(works, counts);
  // Pads first, so the roads grade up to them; the buildings go on after the roads.
  for (const lot of placed.lots) {
    pads.clear(grow(lot.box, 3));
    if (lot.pad) pads.pad(lot.box, lot.y, TOPS[lot.kind] ?? "minecraft:gravel", "minecraft:dirt", "minecraft:stone_bricks", 12);
  }
  for (const lot of placed.lots) b.lot(lot);
  const { roads, unrouted } = routeRoads(t, placed.lots, grow(placed.core, 60), site.level);
  const boxes = placed.lots.map((l) => l.box);
  const key = (p: Vec) => `${p.x},${p.y},${p.z}`;
  const occupied = new Set([...b.roleChests.map(c => c.pos), ...b.stores.map(c => c.pos), ...b.outputChests, ...b.yardChests,
    ...b.cookers.map(c => c.pos), ...b.tables.map(c => c.pos), ...b.stonecutters, ...b.beds.flatMap(bed => [bed.foot, bed.head])].map(key));
  const spawns = b.spawns.filter(p => !occupied.has(key(p)));
  const extent: Box = { minX: Math.min(...boxes.map((x) => x.minX)), maxX: Math.max(...boxes.map((x) => x.maxX)),
    minZ: Math.min(...boxes.map((x) => x.minZ)), maxZ: Math.max(...boxes.map((x) => x.maxZ)) };
  return { site, lots: placed.lots, skipped: placed.skipped, core: placed.core, pads, works, roads, unrouted, extent,
    roleChests: b.roleChests, stores: b.stores, outputChests: b.outputChests, yardChests: b.yardChests,
    cookers: b.cookers, tables: b.tables, stonecutters: b.stonecutters, campfires: b.campfires, beds: b.beds,
    pens: b.pens, plots: b.plots, grove: b.grove, ponds: b.ponds, spawns, fields: b.fields,
    quarry: b.quarry, construction: b.site, wall: b.wall, dispatchOrigin: b.dispatchOrigin };
}
