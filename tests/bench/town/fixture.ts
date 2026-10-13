import type { ColonySpec, MemberSpec, ZoneSpec, OrderSpec, BlueprintBlockSpec, BlueprintSpec, BuildOrderSpec } from "../../shared/colony/colony-nbt.ts";
import { SCOPE } from "../../shared/colony/colony-nbt.ts";
import type { Box, Vec } from "./town.ts";
import type { BlueprintCell } from "./town-build.ts";
import type { Town } from "./raise.ts";
import { TRAINS } from "./network.ts";
export type { Vec } from "./town.ts";

export type Scale = {
  residents: number;
  humans: number;
  furnaces: number;
  blastFurnaces: number;
  smokers: number;
  craftingTables: number;
  smithingTables: number;
  stonecutters: number;
  pens: number;
  fishSpots: number;
};

export const COLONY_ID = "00000000-0000-4000-8000-00000000c064";
const houseBlueprintId = (i: number) => `00000000-0000-4000-8000-0000000b01${String(i).padStart(2, "0")}`;

export function countsOf(scale: Scale) {
  return { humans: scale.humans, cookers: { furnace: scale.furnaces, blast: scale.blastFurnaces, smoker: scale.smokers },
    tables: { crafting: scale.craftingTables, smithing: scale.smithingTables }, stonecutters: scale.stonecutters, pens: scale.pens };
}

// Parses "ns:block[a=b,c=d]" into a blueprint cell at its offset from the anchor.
export function blueprintBlocks(cells: BlueprintCell[], anchor: Vec): BlueprintBlockSpec[] {
  return cells.map((c) => {
    const m = c.state.match(/^([^[]+)(?:\[(.*)\])?$/)!;
    const properties = m[2] ? Object.fromEntries(m[2].split(",").map((kv) => kv.split("=") as [string, string])) : undefined;
    return { offset: { x: c.x - anchor.x, y: c.y - anchor.y, z: c.z - anchor.z }, block: m[1], ...(properties ? { properties } : {}) };
  });
}

// Spread residents across the town's free standing cells.
function spread(candidates: Vec[], n: number, taken: Set<string>): Vec[] {
  const seen = new Set<string>();
  const free = candidates.filter((c) => {
    const k = `${c.x},${c.y},${c.z}`;
    if (taken.has(k) || seen.has(k)) return false;
    seen.add(k);
    return true;
  });
  if (free.length < n) throw new Error(`The town has only ${free.length} free standing cells for ${n} residents`);
  return Array.from({ length: n }, (_, i) => free[Math.floor(i * free.length / n)]);
}

export function buildLayout(town: Town, scale: Scale) {
  const base = layoutBase(town, scale);
  return { ...base, demands: demandsOf(base) };
}

function layoutBase(town: Town, scale: Scale) {
  const p = town.plan;
  const role = (r: string) => p.roleChests.find((c) => c.role === r)!.pos;
  const plaza = p.lots.find((l) => l.kind === "plaza")!;
  const origin: Vec = { x: Math.round((plaza.box.minX + plaza.box.maxX) / 2), y: plaza.y, z: Math.round((plaza.box.minZ + plaza.box.maxZ) / 2) };
  const chests = [...p.roleChests.map((c) => c.pos), ...p.stores.map((c) => c.pos), ...p.outputChests, ...p.yardChests];
  const furnaces = p.cookers.map((c) => c.pos);
  const tables = p.tables.map((t) => t.pos);
  const beds = p.beds.map((b) => b.foot);
  // A town saved before chests were doubled has neither list.
  const chestHalves = p.chestHalves ?? [], spareChests = p.spareChests ?? [];
  const taken = new Set([...chests, ...chestHalves.map((c) => c.other), ...spareChests, ...furnaces, ...tables, ...p.stonecutters,
    ...beds, ...p.beds.map((b) => b.head)]
    .map((c) => `${c.x},${c.y},${c.z}`));
  const spawns = spread(p.spawns, scale.residents, taken);
  const works = new Set(p.stores.filter((c) => c.lot.startsWith("works-store")).map((c) => `${c.pos.x},${c.pos.y},${c.pos.z}`));
  const isWorks = (c: { pos: Vec }) => works.has(`${c.pos.x},${c.pos.y},${c.pos.z}`);
  return {
    origin, y: origin.y, ground: origin.y - 1, site: town.site, lots: p.lots, extent: town.extent as Box,
    roleChests: p.roleChests, stores: p.stores, outputChests: p.outputChests, yardChests: p.yardChests, chests,
    /** Every chest is a double chest, kept at one half: each one's other half. */
    chestHalves,
    seedChest: role("seed"), smeltChest: role("iron"), outputChest: role("output"), foodChest: role("food"),
    feedChest: role("feed"), logChest: role("log"), plankChest: role("plank"), stoneChest: role("stone"),
    meatChest: role("meat"), toolChest: role("tool"), dairyChest: role("dairy"), smeltOutChest: role("smeltOut"),
    cookers: p.cookers, furnaces, tables, tableKinds: p.tables, stonecutters: p.stonecutters, campfires: p.campfires,
    beds, bedHeads: p.beds.map((b) => b.head), pens: p.pens, plots: town.plots, grove: p.grove, fishing: town.fishing,
    spawns,
    /** Houses for the builders: the new quarter's. */
    houses: p.houses.map((h) => ({ anchor: h.anchor, blocks: blueprintBlocks(h.blocks, h.anchor), lot: h.lot.name, style: h.style })),
    /** The long work commissioned on the land: mines, bridge, arcade, wall, dock. */
    orders: town.orders,
    network: town.network, districts: town.districts,
    /** The city's stores, and the store out at the mine. */
    townStores: p.stores.filter((c) => !isWorks(c)), worksStores: p.stores.filter(isWorks),
    /** Where each resident starts the day: beside its own bed. */
    homes: homesOf(p.beds, p.spawns, taken),
  };
}

type LayoutBase = ReturnType<typeof layoutBase>;
export type Layout = LayoutBase & { demands: Demand[] };

// Each bed's sleeper starts standing in the lodge's aisle nearest the bed, so the day begins at home.
function homesOf(beds: Array<{ foot: Vec; head: Vec }>, spawns: Vec[], taken: Set<string>): Vec[] {
  const free = spawns.filter((c) => !taken.has(`${c.x},${c.y},${c.z}`));
  return beds.map((bed) => {
    let best = free[0], bestD = Infinity;
    for (const c of free) {
      const d = Math.abs(c.x - bed.foot.x) + Math.abs(c.z - bed.foot.z) + Math.abs(c.y - bed.foot.y) * 4;
      if (d < bestD) { bestD = d; best = c; }
    }
    return best;
  });
}

export function summarizeLayout(l: Layout) {
  const kinds = (list: Array<{ kind: string }>) => list.reduce((n: Record<string, number>, c) => ({ ...n, [c.kind]: (n[c.kind] ?? 0) + 1 }), {});
  return {
    lots: l.lots.length,
    chests: l.chests.length,
    cookers: kinds(l.cookers),
    tables: kinds(l.tableKinds),
    stonecutters: l.stonecutters.length,
    pens: l.pens.length,
    pen_heads: l.pens.reduce((sum, p) => sum + p.heads, 0),
    fishing_spots: l.fishing.length,
    docks: l.fishing.filter((f) => f.name.startsWith("dock")).length,
    beds: l.beds.length,
    plots: l.plots.map((p) => ({ name: p.name, crop: p.crop, cells: p.cells.length, ripe: p.ripe.length })),
    grove_cells: l.grove.length,
    houses: l.houses.map((h) => ({ lot: h.lot, style: h.style, cells: h.blocks.length,
      dug: h.blocks.filter((b) => b.block === "minecraft:air").length })),
    orders: l.orders.map((o) => ({ pattern: o.pattern, name: o.name, why: o.why })),
    works: l.districts.map((d) => ({ name: d.name, lots: d.lots.length })),
    extent: l.extent,
  };
}

export function buildColonySpec(layout: Layout, residentUuids: string[]): ColonySpec {
  const members: MemberSpec[] = [...new Map(
    [
      ...layout.chests.map((at): MemberSpec => ({ at, as: "store" })),
      ...[...layout.furnaces, ...layout.stonecutters, ...layout.tables].map((at): MemberSpec => ({ at, as: "station" })),
      ...layout.beds.map((at): MemberSpec => ({ at, as: "bed" })),
    ].map((m): [string, MemberSpec] => [`${m.at.x},${m.at.y},${m.at.z}`, m]),
  ).values()];
  const box = (cells: Vec[]) => ({
    min: { x: Math.min(...cells.map((c) => c.x)), y: Math.min(...cells.map((c) => c.y)), z: Math.min(...cells.map((c) => c.z)) },
    max: { x: Math.max(...cells.map((c) => c.x)), y: Math.max(...cells.map((c) => c.y)), z: Math.max(...cells.map((c) => c.z)) },
  });

  const zones: ZoneSpec[] = [
    ...layout.plots.filter((p) => p.cells.length).map((p): ZoneSpec => ({ kind: "farm", ...box(p.cells), cells: p.cells, crop: p.crop })),
    { kind: "farm", ...box(layout.grove), cells: layout.grove, crop: "minecraft:oak_sapling" },
    ...layout.pens.map((p): ZoneSpec => ({ kind: "pasture", min: p.min, max: p.max, animal: `minecraft:${p.animal}`, target: p.target, shear: true })),
    ...layout.fishing.map((f): ZoneSpec => ({ kind: "fish", ...box(f.cells), cells: f.cells })),
  ];

  // Standing orders, each kept between its refill mark and its fill-to count in the chest where it is used.
  const orders: OrderSpec[] = layout.demands.map((d) => ({ into: d.into, item: d.item, count: d.high, low: d.low, standing: true }));

  const bedClaims = layout.beds.slice(0, residentUuids.length).map((bed, i) => ({ bed, resident: residentUuids[i] }));

  const blueprints: BlueprintSpec[] = layout.houses.map((h, i) =>
    ({ id: houseBlueprintId(i), name: h.lot, blocks: h.blocks, createdGameTime: 50 + i }));
  const buildOrders: BuildOrderSpec[] = layout.houses.map((h, i) =>
    ({ blueprintId: houseBlueprintId(i), anchor: h.anchor, createdGameTime: 80 + i }));

  return {
    colonyId: COLONY_ID,
    residents: residentUuids,
    members,
    zones,
    orders,
    bedClaims,
    blueprints,
    buildOrders,
    settings: {
      [SCOPE.wares]: {
        fuel: { items: [{ item: "minecraft:coal" }, { item: "minecraft:charcoal" }, { item: "minecraft:oak_planks" }] },
      },
    },
  };
}

export type Demand = { label: string; item: string; low: number; high: number; stack: number; into: Vec };

const mc = (item: string) => `minecraft:${item}`;

/**
 * The colony's standing orders. It lives off its own land: food is the one thing it is asked to keep up for itself -
 * bread where people eat, meat and fish cooked in its kitchens. Everything else it keeps is what its own work uses
 * up: charcoal for the cookers, seed and feed for the fields and herds, spare stone tools, and in the builders' yards
 * the timber and stone the houses and the commissioned work (the wall, the arcade) are made of, cut and crafted from
 * what its grove and mines bring in.
 */
function demandsOf(l: LayoutBase): Demand[] {
  const out: Demand[] = [];
  const add = (label: string, item: string, low: number, high: number, into: Vec | undefined, stack = 64) => {
    if (into) out.push({ label, item: mc(item), low, high, stack, into });
  };
  const store = (role: string, nth = 0, works = false) => (works ? l.worksStores : l.townStores).filter((c) => c.role === role)[nth]?.pos;
  const pantry = l.townStores.filter((c) => c.role === "food" && c.lot.startsWith("store-")).map((c) => c.pos);
  // Four hundred mouths eat some thousand meals a day between them: the pantries keep a day's worth in hand.
  pantry.forEach((into, i) => add(`bread-${i}`, "bread", 72, 192, into));
  ["cooked_beef", "cooked_porkchop", "cooked_mutton", "cooked_chicken"].forEach((meat, i) =>
    add(meat.replace("cooked_", "cooked-"), meat, 24, 96, pantry[i % Math.max(1, pantry.length)]));
  add("cooked-cod", "cooked_cod", 24, 96, l.foodChest);
  add("baked-potato", "baked_potato", 24, 96, pantry[pantry.length - 1] ?? l.foodChest);
  add("charcoal", "charcoal", 32, 96, store("fuel"));
  add("feed-wheat", "wheat", 32, 96, store("feed", 0, true) ?? l.feedChest);
  add("seed-wheat", "wheat_seeds", 32, 96, store("seed", 0, true) ?? l.seedChest);
  for (const tool of ["stone_pickaxe", "stone_shovel", "stone_axe", "wooden_hoe"]) add(tool.replace("_", "-"), tool, 1, 3, l.toolChest, 1);
  // The yards: the most used of what the work is built in, each kept in its own chest.
  const yard = [...l.yardChests];
  for (const [item, low, high] of yardStock(l.houses.flatMap((h) => h.blocks), YARD_ITEMS))
    add(`yard-${item.replace("minecraft:", "").replace(/_/g, "-")}`, item.replace("minecraft:", ""), low, high, yard.shift());
  return out;
}

/** The commissioned work is drawn when it is filed, so the yard also keeps what stone works are mostly made of. */
const WORKS_STONE = ["minecraft:stone_bricks", "minecraft:cobblestone", "minecraft:smooth_stone", "minecraft:polished_andesite", "minecraft:oak_planks"];
const YARD_ITEMS = 9;

/** The `n` items most used by these blocks (with the works' stone), each with a refill mark and a fill-to count. */
function yardStock(blocks: BlueprintBlockSpec[], n: number): Array<[string, number, number]> {
  const need = materialsOf(blocks);
  for (const item of WORKS_STONE) need[item] = (need[item] ?? 0) + 256;
  return Object.entries(need).filter(([item]) => !UNMADE.test(item)).sort((a, b) => b[1] - a[1]).slice(0, n)
    .map(([item, count]) => [item, 16, Math.min(192, Math.max(32, Math.ceil(count / 4 / 16) * 16))]);
}

/**
 * The raw goods a store of each role starts with, per chest (a town store has four, a trade one more of some):
 * enough for about three days of building - the houses take some 1,800 cobblestone and 900 logs, the wall as much again.
 */
const RAW = {
  log: (stacks: (item: string, n: number) => Array<[string, number]>) => stacks("oak_log", 8),
  // Stone comes already smelted for now - plain and smooth - so no furnace stands between the quarry and the masons.
  // The quarry's first stone is a day off, so the cobblestone is enough that none has to be made back out of stone
  // (by way of slabs, a recipe of Terralith's) while the bricks wait on that stone.
  stone: (stacks: (item: string, n: number) => Array<[string, number]>) =>
    [...stacks("cobblestone", 12), ...stacks("stone", 14), ...stacks("smooth_stone", 3), ...stacks("andesite", 4)],
  plank: (stacks: (item: string, n: number) => Array<[string, number]>) => [...stacks("bamboo", 3), ...stacks("string", 1)],
  fuel: (stacks: (item: string, n: number) => Array<[string, number]>) => stacks("coal", 4),
};

/** What the stores hold raw already, and what furnishes a house rather than builds it: neither is stocked worked. */
const BROUGHT_RAW = /^minecraft:(oak_log|cobblestone)$/;
const FURNISHING = /_bed$|^minecraft:(crafting_table|torch)$/;
/** Worked goods stocked beyond the houses' share, for the wall, the arcade and the scaffolds: each with how many. */
const WORKED_BESIDES: Array<[string, number]> = [["minecraft:oak_planks", 512], ["minecraft:stone_bricks", 896],
  ["minecraft:polished_andesite", 128], ["minecraft:oak_fence", 64], ["minecraft:stick", 128], ["minecraft:scaffolding", 256]];

/** The roles whose goods the houses and the commissioned work are built from. */
const BUILT_FROM = new Set(["log", "plank", "stone"]);

/** Items the colony cannot make: Create's track, and lanterns until its mine finds iron. A yard holds these from the start. */
export const UNMADE = /^(create:track|minecraft:lantern)$/;
/** How much of each the yards start with: a branch line's track and a mine's worth of lanterns. */
const BROUGHT: Array<[string, number]> = [["create:track", 64 * 4], ["minecraft:lantern", 48]];

/**
 * What each chest starts with. The settlers bring seed and a few days' bread, buckets for the dairy, the few things
 * the colony cannot make, and what its building is made from, at every stage: raw (logs, quarried cobblestone, stone,
 * smooth stone and andesite, coal, bamboo and string), worked (planks, bricks, sticks) and finished (stairs, slabs,
 * fences, scaffolding) - of the worked and the finished about half of what the houses take, the rest made at its own
 * stations. No meat or crops: those it raises itself.
 */
export function stockOf(l: Layout): Array<{ chest: Vec; entries: Array<[string, number]> }> {
  const stacks = (item: string, n: number) => Array.from({ length: n }, () => [mc(item), 64] as [string, number]);
  // Two thirds of the field stand unsown at the start (raise.ts), so every seed chest holds enough to sow its share.
  const seeds = [...stacks("wheat_seeds", 8), ...stacks("carrot", 4), ...stacks("potato", 4), ...stacks("beetroot_seeds", 3),
    ...stacks("pumpkin_seeds", 1), ...stacks("melon_seeds", 1), ...stacks("sugar_cane", 1), ...stacks("oak_sapling", 1)];
  const byRole: Record<string, Array<[string, number]>> = {
    seed: seeds, log: RAW.log(stacks), food: stacks("bread", 4), feed: stacks("wheat", 1),
    stone: RAW.stone(stacks), plank: RAW.plank(stacks), fuel: RAW.fuel(stacks),
  };
  const out: Array<{ chest: Vec; entries: Array<[string, number]> }> = [];
  // What the building is made from starts out in the town's own stores of it, by the builders' yard: a trade's chest
  // of logs, planks or stone at the works starts empty, its share out in the store, so the builders and the haulers
  // who fetch for them do not all come to the works for it. The crafters are brought what their benches use.
  const piled = new Map<string, Array<[string, number]>>();
  for (const c of l.townStores) {
    const yards = BUILT_FROM.has(c.role) ? l.townStores.filter((s) => s.role === c.role && s.lot === `store-${c.role}`) : [];
    if (!yards.length) out.push({ chest: c.pos, entries: byRole[c.role] ?? [] });
    else piled.set(c.role, [...(piled.get(c.role) ?? []), ...(byRole[c.role] ?? [])]);
  }
  piled.set("log", [...(piled.get("log") ?? []), ...stacks("oak_log", 6)]);
  // Beside the raw goods, some of what is made from them, worked and finished: half of what the houses are built
  // from, and planks, bricks, sticks and scaffolding besides for the commissioned work. So the first courses are
  // laid from stock while the benches catch up, and not every builder waits on the same few crafters.
  const worked = materialsOf(l.houses.flatMap((h) => h.blocks));
  for (const [item, more] of WORKED_BESIDES) worked[item] = (worked[item] ?? 0) + 2 * more;
  for (const [item, count] of Object.entries(worked)) {
    if (UNMADE.test(item) || BROUGHT_RAW.test(item) || FURNISHING.test(item)) continue;
    const role = /oak_|scaffolding|stick/.test(item) ? "plank" : "stone";
    if (!piled.has(role)) continue;
    const half = Math.ceil(count / 2);
    for (let left = half; left > 0; left -= 64) piled.get(role)!.push([item, Math.min(64, left)]);
  }
  for (const [role, pile] of piled) {
    const yards = l.townStores.filter((s) => s.lot === `store-${role}`);
    if (!yards.length) { out.push({ chest: l.logChest, entries: pile }); continue; }
    yards.forEach((c, i) => out.push({ chest: c.pos, entries: pile.filter((_, k) => k % yards.length === i) }));
  }
  const worksStock: Record<string, Array<[string, number]>> = { seed: stacks("wheat_seeds", 2), feed: stacks("wheat", 1),
    log: stacks("oak_sapling", 1) };
  for (const c of l.worksStores) out.push({ chest: c.pos, entries: worksStock[c.role] ?? [] });
  out.push({ chest: l.seedChest, entries: seeds });
  out.push({ chest: l.feedChest, entries: stacks("wheat", 4) });
  out.push({ chest: l.dairyChest, entries: [[mc("bucket"), 16]] });
  out.push({ chest: l.foodChest, entries: stacks("bread", 4) });
  // What the colony cannot make, in a yard chest no order keeps.
  const kept = new Set(l.demands.map((d) => key(d.into)));
  const free = l.yardChests.filter((c) => !kept.has(key(c)));
  BROUGHT.forEach(([item, count], i) => {
    const chest = free[i % Math.max(1, free.length)];
    if (!chest) return;
    const entries: Array<[string, number]> = [];
    for (let left = count; left > 0; left -= 64) entries.push([item, Math.min(64, left)]);
    out.push({ chest, entries });
  });
  return out;
}

const key = (p: Vec) => `${p.x},${p.y},${p.z}`;
const same = (a: Vec, b: Vec) => a.x === b.x && a.y === b.y && a.z === b.z;

/** A trade: the vocations it is keen on, the tools it carries. */
export type Trade = { name: string; priorities: Record<string, number>; tools: string[] };
export type Staffing = Array<{ trade: Trade; start: number; size: number; basis: string }>;

function trade(name: string, keen: string[], tools: string[], hauls = true): Trade {
  const priorities: Record<string, number> = {};
  for (const vocation of keen) priorities[vocation] = 1;
  if (hauls && !priorities["folkways:hauling"]) priorities["folkways:hauling"] = 3;
  return { name, priorities, tools };
}

// The share of the town that only carries (FOLKWAYS_LC_HAULERS, a fraction): none, unless a run asks for some.
const HAULER_SHARE = Number(process.env.FOLKWAYS_LC_HAULERS ?? 0);

/**
 * How many of each trade the town keeps. Everyone but the drivers, who keep to their trains (two a train), carries
 * goods when their own trade has no work for them; no one only carries, unless HAULER_SHARE says so. All are shared out by the
 * work the town has: a farmer to so many cells of field, a herder to two pens, a fisher to a fishing spot, a crafter to most
 * stations, builders to the blocks on order - each trade's share of everyone, in that proportion.
 */
export function staffing(l: Layout, humans: number): Staffing {
  const fieldCells = l.plots.reduce((n, p) => n + p.cells.length, 0) + l.grove.length;
  const stations = l.cookers.length + l.tables.length + l.stonecutters.length;
  const trains = (l.network?.lines.length ?? 0) * TRAINS;
  // The commissioned work is drawn when it is filed; each piece is reckoned at two thousand blocks.
  const blocks = l.houses.reduce((n, h) => n + h.blocks.length, 0) + 2000 * l.orders.length;
  const drivers = Math.min(humans, 2 * trains), haulers = Math.round((humans - drivers) * HAULER_SHARE);
  const work: Array<[Trade, number, string]> = [
    [trade("farmer", ["folkways:farming"], [mc("iron_hoe"), mc("iron_axe")]), fieldCells / 45, `${fieldCells} field cells / 45`],
    [trade("herder", ["folkways:herding"], [mc("shears")]), l.pens.length / 2, `${l.pens.length} pens / 2`],
    [trade("fisher", ["folkways:fishing"], [mc("fishing_rod")]), l.fishing.length, `${l.fishing.length} fishing spots`],
    [trade("crafter", ["folkways:crafting"], []), stations * 0.4, `${stations} stations x 0.4`],
    [trade("builder", ["folkways:building"], [mc("iron_pickaxe"), mc("iron_axe"), mc("iron_shovel")]), blocks / 50, `${blocks} blocks on order / 50`],
  ];
  // Each trade's whole share, then the hands left over one by one to the trades cut shortest by the rounding.
  const wanted = work.reduce((n, [, k]) => n + k, 0), shared = humans - drivers - haulers;
  const sizes = work.map(([, k]) => wanted > 0 ? Math.floor(k * shared / wanted) : 0);
  const short = work.map(([, k], i) => ({ i, by: wanted > 0 ? k * shared / wanted - sizes[i] : 0 })).sort((a, b) => b.by - a.by);
  for (let left = shared - sizes.reduce((n, k) => n + k, 0), k = 0; left > 0; left--, k++) sizes[short[k % short.length].i]++;
  const want: Array<[Trade, number, string]> = [
    ...work.map(([t, , basis], i): [Trade, number, string] => [t, sizes[i], `${basis}, of ${shared} shared by the work`]),
    ...(haulers ? [[trade("hauler", ["folkways:hauling"], []), haulers, `${HAULER_SHARE} of everyone but the drivers`] as [Trade, number, string]] : []),
    [trade("driver", ["folkways:conducting"], [], false), drivers, `${trains} trains x 2`],
  ];
  let start = 0;
  return want.map(([t, size, basis]) => { const seg = { trade: t, start, size, basis }; start += size; return seg; });
}

// What building the blueprints takes, as item counts, for stocking the builders' yard.
export function materialsOf(blocks: BlueprintBlockSpec[]): Record<string, number> {
  const out: Record<string, number> = {};
  for (const b of blocks) {
    if (b.block === "minecraft:air" || b.block === "minecraft:water" || b.block === "minecraft:lava") continue;
    const item = b.block.replace(/^minecraft:wall_/, "minecraft:").replace(/_wall_(torch|sign|banner)$/, "_$1");
    out[item] = (out[item] ?? 0) + (b.properties?.type === "double" ? 2 : 1);
  }
  return out;
}

export function validateFixture(layout: Layout) {
  const seen = new Set<string>();
  for (const p of [...layout.chests, ...layout.tables, ...layout.furnaces, ...layout.stonecutters, ...layout.beds, ...layout.bedHeads]) {
    const k = `${p.x},${p.y},${p.z}`;
    if (seen.has(k)) throw new Error(`Overlapping fixture blocks at ${k}`);
    seen.add(k);
  }
  // Every chest an order fills keeps room for all it is asked to hold, beside what it starts with.
  const slots = new Map<string, number>();
  for (const d of layout.demands) slots.set(key(d.into), (slots.get(key(d.into)) ?? 0) + Math.ceil(d.high / d.stack));
  for (const { chest, entries } of stockOf(layout)) if (slots.has(key(chest))) slots.set(key(chest), slots.get(key(chest))! + entries.length);
  for (const [k, n] of slots) if (n > 27) throw new Error(`the chest at ${k} is asked to hold ${n} stacks`);
  return { ok: true, demands: layout.demands.map((d) => ({ label: d.label, item: d.item, low: d.low, high: d.high, into: d.into })),
    stores: layout.stores.length, works_stores: layout.worksStores.length, fishing: layout.fishing.length, houses: layout.houses.length, orders: layout.orders.length,
    note: "Standing orders kept between a refill mark and a fill-to count, in the chest each item is used from; no chest is asked to hold more than it can." };
}
