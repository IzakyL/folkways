import assert from "node:assert/strict";
import test from "node:test";
import { buildLayout, buildColonySpec, validateFixture, materialsOf, staffing, stockOf } from "./fixture.ts";
import { syntheticTown, BENCH_SCALE } from "./synthetic.ts";

const town = syntheticTown();
const layout = buildLayout(town, BENCH_SCALE);

test("the 500-resident town has distinct facilities and achievable container capacities", () => {
  assert.equal(validateFixture(layout).ok, true);
  const spec = buildColonySpec(layout, []);
  assert.equal(spec.orders.length, layout.demands.length);
  for (const order of spec.orders) assert.ok(order.standing && order.low! < order.count, `${order.item} refills below its fill-to count`);
  assert.ok(!layout.demands.some(d => d.item === "minecraft:coal"));
  assert.equal(new Set(layout.spawns.map(p => `${p.x},${p.y},${p.z}`)).size, 500);
  assert.equal(layout.stores.filter(c => c.role === "fuel").length,
    4 + new Set(layout.stores.map(c => c.lot).filter(lot => /^(smithy|kitchen)-/.test(lot))).size);
  assert.equal(layout.chestHalves.length, layout.chests.length);
  assert.deepEqual(Object.keys(layout.cookers.reduce((n: Record<string, number>, c) => ({ ...n, [c.kind]: 1 }), {})).sort(),
    ["blast_furnace", "furnace", "smoker"]);
});

test("every zone the colony works is written cell by cell where the ground is uneven", () => {
  const spec = buildColonySpec(layout, []);
  const farms = spec.zones.filter(z => z.kind === "farm");
  assert.ok(farms.some(z => "crop" in z && z.crop === "minecraft:oak_sapling"), "the grove is a sapling farm");
  for (const crop of ["minecraft:pumpkin_stem", "minecraft:melon_stem", "minecraft:sugar_cane"])
    assert.ok(farms.some(z => "crop" in z && z.crop === crop), crop);
  for (const zone of spec.zones.filter(z => z.cells)) {
    for (const c of zone.cells!) {
      assert.ok(c.x >= zone.min.x && c.x <= zone.max.x && c.z >= zone.min.z && c.z <= zone.max.z);
    }
  }
  assert.equal(spec.zones.filter(z => z.kind === "pasture").length, BENCH_SCALE.pens);
});

test("each house blueprint sits on its anchor and is built of what the colony can make", () => {
  assert.equal(layout.houses.length, 12);
  for (const h of layout.houses) {
    assert.ok(h.blocks.length > 200, `${h.lot}: ${h.blocks.length} cells`);
    assert.ok(h.blocks.every(b => b.offset.x >= 0 && b.offset.y >= 0 && b.offset.z >= 0));
    const need = materialsOf(h.blocks);
    for (const item of Object.keys(need))
      assert.ok(/oak|cobblestone|stone_brick|andesite|white_bed|torch|crafting_table/.test(item), `${h.lot} wants ${item}`);
  }
});

test("pasture fences, beds and stations never share a cell, and nobody spawns on one", () => {
  const occupied = new Set<string>();
  const put = (p: { x: number; y: number; z: number }) => {
    const key = `${p.x},${p.y},${p.z}`;
    assert.ok(!occupied.has(key), `overlap at ${key}`);
    occupied.add(key);
  };
  [...layout.chests, ...layout.chestHalves.map(c => c.other), ...layout.furnaces, ...layout.tables, ...layout.stonecutters,
    ...layout.pens.flatMap(p => [...p.fences, p.gate]), ...layout.beds, ...layout.bedHeads].forEach(put);
  for (const p of layout.spawns) assert.ok(!occupied.has(`${p.x},${p.y},${p.z}`));
});

test("the colony starts with seed, bread and what it builds from - raw, worked and finished - and nothing it should raise or smelt", () => {
  const stocked = stockOf(layout).flatMap((c) => c.entries);
  const stock = stocked.map(([item]) => item);
  const count = (item: string) => stocked.reduce((n, [i, k]) => n + (i === item ? k : 0), 0);
  for (const made of ["minecraft:raw_iron", "minecraft:beef", "minecraft:iron_ingot"]) assert.ok(!stock.includes(made), `${made} is the colony's to make`);
  // Some of what is made from the raw goods is there from the start, and less of it than the houses alone take.
  const need = materialsOf(layout.houses.flatMap((h) => h.blocks));
  for (const worked of ["minecraft:oak_planks", "minecraft:stone_bricks", "minecraft:scaffolding", "minecraft:oak_stairs"])
    assert.ok(count(worked) > 0, `no ${worked} to begin with`);
  for (const finished of ["minecraft:oak_stairs", "minecraft:cobblestone_stairs"])
    assert.ok(count(finished) < need[finished], `${finished}: the colony still has most of it to make`);
  for (const raw of ["minecraft:wheat_seeds", "minecraft:bread", "minecraft:oak_log", "minecraft:cobblestone", "minecraft:stone",
    "minecraft:smooth_stone", "minecraft:andesite",
    "minecraft:coal", "minecraft:bamboo", "minecraft:string"]) assert.ok(stock.includes(raw), `${raw} is brought`);
  assert.ok(stock.includes("create:track") && stock.includes("minecraft:lantern"), "what it cannot make is brought");
});

test("standing orders keep food up, and otherwise only what the colony's own work uses", () => {
  const items = new Set(layout.demands.map((d) => d.item));
  for (const food of ["minecraft:bread", "minecraft:cooked_beef", "minecraft:cooked_cod"]) assert.ok(items.has(food), food);
  for (const bought of ["minecraft:iron_ingot", "minecraft:iron_pickaxe", "minecraft:milk_bucket"]) assert.ok(!items.has(bought), bought);
});

test("every resident has a trade and the trades follow the work", () => {
  const staff = staffing(layout, BENCH_SCALE.humans);
  assert.equal(staff.reduce((n, s) => n + s.size, 0), BENCH_SCALE.humans);
  const of = (name: string) => staff.find((s) => s.trade.name === name)!.size;
  // No one only carries: every trade but the drivers' carries goods when its own work is done.
  assert.ok(!staff.some((s) => s.trade.name === "hauler"));
  for (const s of staff) assert.equal(s.trade.priorities["folkways:hauling"], s.trade.name === "driver" ? undefined : 3, s.trade.name);
  assert.ok(of("driver") < 10);
  // The trades stand in the proportion of their work: herders to fishers as half the pens to the fishing spots.
  assert.ok(of("fisher") > 0);
  assert.ok(Math.abs(of("herder") / of("fisher") - layout.pens.length / 2 / layout.fishing.length) < 0.2);
  assert.equal(layout.homes.length, layout.beds.length);
});
