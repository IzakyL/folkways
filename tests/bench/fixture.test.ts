import assert from "node:assert/strict";
import test from "node:test";
import { buildLayout, buildColonySpec, validateFixture, materialsOf, DEMANDS } from "./fixture.ts";
import { syntheticTown, BENCH_SCALE } from "./synthetic.ts";

const town = syntheticTown();
const layout = buildLayout(town, BENCH_SCALE);

test("the 500-resident town has distinct facilities and achievable container capacities", () => {
  assert.equal(validateFixture(layout).ok, true);
  const spec = buildColonySpec(layout, []);
  const destinations = new Set(DEMANDS.map((_, i) => JSON.stringify(spec.orders[i].into)));
  assert.equal(destinations.size, DEMANDS.length);
  assert.ok(!DEMANDS.some(d => d.item === "minecraft:coal"));
  assert.equal(new Set(layout.spawns.map(p => `${p.x},${p.y},${p.z}`)).size, 500);
  assert.equal(layout.stores.filter(c => c.role === "fuel").length,
    4 + layout.lots.filter(l => l.kind === "smithy" || l.kind === "kitchen").length);
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

test("the construction blueprint sits on its anchor and asks for real blocks", () => {
  assert.ok(layout.construction.length > 300);
  assert.ok(layout.construction.every(b => b.offset.x >= 0 && b.offset.y >= 0 && b.offset.z >= 0));
  const need = materialsOf(layout.construction);
  assert.ok(need["minecraft:spruce_stairs"] > 0 && need["minecraft:spruce_planks"] > 0);
  assert.ok(!("minecraft:air" in need));
  assert.ok(layout.quarryBlocks.every(b => b.block === "minecraft:air"));
});

test("pasture fences, beds and stations never share a cell, and nobody spawns on one", () => {
  const occupied = new Set<string>();
  const put = (p: { x: number; y: number; z: number }) => {
    const key = `${p.x},${p.y},${p.z}`;
    assert.ok(!occupied.has(key), `overlap at ${key}`);
    occupied.add(key);
  };
  [...layout.chests, ...layout.furnaces, ...layout.tables, ...layout.stonecutters,
    ...layout.pens.flatMap(p => [...p.fences, p.gate]), ...layout.beds, ...layout.bedHeads].forEach(put);
  for (const p of layout.spawns) assert.ok(!occupied.has(`${p.x},${p.y},${p.z}`));
});
