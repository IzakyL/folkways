import type { ColonySpec, MemberSpec, ZoneSpec, OrderSpec, BlueprintBlockSpec, BlueprintSpec, BuildOrderSpec } from "../e2e/colony-nbt.ts";
import { SCOPE } from "../e2e/colony-nbt.ts";
import type { Box, Vec } from "./town.ts";
import type { BlueprintCell } from "./town-build.ts";
import type { Town } from "./raise.ts";
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
const SITE_BLUEPRINT_ID = "00000000-0000-4000-8000-0000000b0001";
const FAR_BLUEPRINT_ID = "00000000-0000-4000-8000-0000000b0002";
const QUARRY_BLUEPRINT_ID = "00000000-0000-4000-8000-0000000b0003";
const WALL_BLUEPRINT_ID = "00000000-0000-4000-8000-0000000b0004";

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
  const p = town.plan;
  const role = (r: string) => p.roleChests.find((c) => c.role === r)!.pos;
  const plaza = p.lots.find((l) => l.kind === "plaza")!;
  const origin: Vec = { x: Math.round((plaza.box.minX + plaza.box.maxX) / 2), y: plaza.y, z: Math.round((plaza.box.minZ + plaza.box.maxZ) / 2) };
  const chests = [...p.roleChests.map((c) => c.pos), ...p.stores.map((c) => c.pos), ...p.outputChests, ...p.yardChests];
  const furnaces = p.cookers.map((c) => c.pos);
  const tables = p.tables.map((t) => t.pos);
  const beds = p.beds.map((b) => b.foot);
  const taken = new Set([...chests, ...furnaces, ...tables, ...p.stonecutters, ...beds, ...p.beds.map((b) => b.head)]
    .map((c) => `${c.x},${c.y},${c.z}`));
  const spawns = spread(p.spawns, scale.residents, taken);
  const construction = p.construction!;
  const quarry = p.quarry!;
  return {
    origin, y: origin.y, ground: origin.y - 1, site: town.site, lots: p.lots, extent: town.extent as Box,
    roleChests: p.roleChests, stores: p.stores, outputChests: p.outputChests, yardChests: p.yardChests, chests,
    seedChest: role("seed"), smeltChest: role("iron"), outputChest: role("output"), foodChest: role("food"),
    feedChest: role("feed"), logChest: role("log"), plankChest: role("plank"), stoneChest: role("stone"),
    meatChest: role("meat"), toolChest: role("tool"), dairyChest: role("dairy"), smeltOutChest: role("smeltOut"),
    cookers: p.cookers, furnaces, tables, tableKinds: p.tables, stonecutters: p.stonecutters, campfires: p.campfires,
    beds, bedHeads: p.beds.map((b) => b.head), pens: p.pens, plots: town.plots, grove: p.grove, fishing: town.fishing,
    spawns, dispatchOrigin: p.dispatchOrigin!,
    nearWorksite: construction.anchor, construction: blueprintBlocks(construction.blocks, construction.anchor),
    quarry: quarry.anchor, quarryBlocks: blueprintBlocks(quarry.blocks, quarry.anchor),
    wall: town.wall ? { anchor: town.wall.anchor, blocks: blueprintBlocks(town.wall.blocks, town.wall.anchor) } : null,
  };
}

export type Layout = ReturnType<typeof buildLayout>;

export function farHutBlocks(): BlueprintBlockSpec[] {
  const blocks: BlueprintBlockSpec[] = [];
  for (let dx = 0; dx < 8; dx++) for (let dz = 0; dz < 8; dz++) blocks.push({ offset: { x: dx, y: 0, z: dz }, block: "minecraft:oak_planks" });
  return blocks;
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
    construction_cells: l.construction.length,
    quarry_cells: l.quarryBlocks.length,
    wall_cells: l.wall?.blocks.length ?? 0,
    extent: l.extent,
  };
}

export function buildColonySpec(layout: Layout, residentUuids: string[], farWorksite?: Vec): ColonySpec {
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

  const M = (item: string, count: number, container: Vec, rank = 0): OrderSpec => ({ into: container, item, count, standing: true, rank });
  const P = (item: string, count: number, container: Vec, rank = 0): OrderSpec => ({ into: container, item, count, standing: false, rank });
  const orders: OrderSpec[] = DEMANDS.map((d, i) => M(d.item, d.count, layout.outputChests[i], d.rank ?? 0));
  orders.push(P("minecraft:oak_planks", 128, layout.smeltOutChest, -1), P("minecraft:cooked_mutton", 64, layout.foodChest, -1));

  const bedClaims = layout.beds.slice(0, residentUuids.length).map((bed, i) => ({ bed, resident: residentUuids[i] }));

  const blueprints: BlueprintSpec[] = [
    { id: SITE_BLUEPRINT_ID, name: "lc_lodge", blocks: layout.construction, createdGameTime: 50 },
    { id: QUARRY_BLUEPRINT_ID, name: "lc_quarry", blocks: layout.quarryBlocks, createdGameTime: 52 },
  ];
  const buildOrders: BuildOrderSpec[] = [
    { blueprintId: SITE_BLUEPRINT_ID, anchor: layout.nearWorksite, createdGameTime: 60 },
    { blueprintId: QUARRY_BLUEPRINT_ID, anchor: layout.quarry, createdGameTime: 62 },
  ];
  if (layout.wall) {
    blueprints.push({ id: WALL_BLUEPRINT_ID, name: "lc_town_wall", blocks: layout.wall.blocks, createdGameTime: 54 });
    buildOrders.push({ blueprintId: WALL_BLUEPRINT_ID, anchor: layout.wall.anchor, createdGameTime: 64 });
  }
  if (farWorksite) {
    blueprints.push({ id: FAR_BLUEPRINT_ID, name: "lc_far_hut", blocks: farHutBlocks(), createdGameTime: 70 });
    buildOrders.push({ blueprintId: FAR_BLUEPRINT_ID, anchor: farWorksite, createdGameTime: 80 });
  }

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

export const DEMANDS: Array<{ item: string; count: number; stack: number; rank?: number }> = [
  ["iron_ingot", 512, 64], ["oak_planks", 1024, 64], ["stick", 512, 64],
  ["crafting_table", 64, 64], ["chest", 64, 64], ["ladder", 128, 64],
  ["bowl", 64, 64], ["stone_slab", 256, 64], ["bread", 256, 64],
  ["wheat_seeds", 512, 64], ["cooked_beef", 256, 64], ["cooked_porkchop", 128, 64],
  ["cooked_mutton", 128, 64], ["cooked_chicken", 128, 64], ["milk_bucket", 16, 1],
  ["bucket", 32, 16], ["iron_pickaxe", 8, 1], ["iron_shovel", 8, 1], ["cobblestone", 64, 64],
].map(([item, count, stack]) => ({ item: `minecraft:${item}`, count: Number(count), stack: Number(stack) }));

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
    const key = `${p.x},${p.y},${p.z}`;
    if (seen.has(key)) throw new Error(`Overlapping fixture blocks at ${key}`);
    seen.add(key);
  }
  if (layout.outputChests.length < DEMANDS.length) throw new Error(`Only ${layout.outputChests.length} order chests for ${DEMANDS.length} demands`);
  for (const d of DEMANDS) if (Math.ceil(d.count / d.stack) > 20)
    throw new Error(`Demand leaves insufficient delivery headroom: ${d.item}`);
  return { ok: true, demands: DEMANDS.map((d, i) => ({ ...d, into: layout.outputChests[i],
    required_slots: Math.ceil(d.count / d.stack), capacity: 27 })),
    stores: layout.stores.length, fishing: layout.fishing.length,
    note: "One target chest per maintained item; at least seven spare slots per target." };
}
