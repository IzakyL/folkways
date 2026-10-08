import assert from "node:assert/strict";
import test from "node:test";
import { distributePopulation, populationSlots } from "./population.ts";
import { buildLayout } from "./fixture.ts";
import { syntheticTown, BENCH_SCALE } from "./synthetic.ts";

test("500 is the total, split into 400 humans and 100 golems with unique distributed slots", () => {
  const p = populationSlots(500);
  assert.deepEqual(distributePopulation(500), { total: 500, humans: 400, metal: 40, humanoid: 40, dog: 20 });
  assert.equal(p.humanIndices.length, 400);
  assert.equal(p.golems.length, 100);
  assert.equal(new Set([...p.humanIndices, ...p.golems.map(g => g.index)]).size, 500);
  assert.ok(p.golems.at(-1)!.index > 490);
  const layout = buildLayout(syntheticTown(), BENCH_SCALE);
  assert.equal(layout.beds.length, 400);
  assert.equal(layout.spawns.length, 500);
});

test("rounding preserves total and never overlaps human/golem slots", () => {
  for (const total of [1, 7, 31, 499, 501]) {
    const p = populationSlots(total);
    assert.equal(p.humans + p.metal + p.humanoid + p.dog, total);
    assert.equal(new Set([...p.humanIndices, ...p.golems.map(g => g.index)]).size, total);
  }
  assert.throws(() => distributePopulation(0));
  assert.throws(() => distributePopulation(2.5));
});
