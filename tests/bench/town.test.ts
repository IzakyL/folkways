import assert from "node:assert/strict";
import test from "node:test";
import { Terrain, Works, Frame, routeRoads, walkway, type Counts, type Lot } from "./town.ts";
import { planTown } from "./town-build.ts";
import { syntheticSurvey } from "./synthetic.ts";

const COUNTS: Counts = { humans: 400, cookers: { furnace: 36, blast: 12, smoker: 16 },
  tables: { crafting: 56, smithing: 8 }, stonecutters: 8, pens: 24 };

test("the town fits on rolling ground and holds everything the colony works at", () => {
  const terrain = new Terrain(syntheticSurvey(0, 0, 260));
  const started = Date.now();
  const plan = planTown(terrain, { x: 0, z: 0, level: 70, sea: 62 }, COUNTS, 128);
  const ms = Date.now() - started;
  assert.deepEqual(plan.skipped, []);
  assert.ok(ms < 60_000, `planning took ${ms} ms`);
  assert.equal(plan.beds.length, COUNTS.humans);
  assert.equal(plan.cookers.length, 64);
  assert.equal(plan.tables.length, 64);
  assert.equal(plan.stonecutters.length, 8);
  assert.equal(plan.pens.length, 24);
  assert.equal(plan.roleChests.length, 12);
  assert.equal(plan.outputChests.length, 19);
  assert.ok(plan.stores.length > 36);
  for (const l of plan.lots.filter(l => ["smithy", "kitchen", "workshop", "masonry"].includes(l.kind))) {
    const local = plan.stores.filter(s => s.pos.x >= l.box.minX && s.pos.x <= l.box.maxX
      && s.pos.z >= l.box.minZ && s.pos.z <= l.box.maxZ).map(s => s.role);
    assert.ok(local.includes("food"), `${l.name} has no local pantry`);
    if (["smithy", "kitchen"].includes(l.kind)) assert.ok(local.includes("fuel"), `${l.name} has no fuel`);
  }
  assert.ok(plan.dispatchOrigin && plan.quarry && plan.construction && plan.wall);
  assert.ok(plan.spawns.length >= 500, `spawns ${plan.spawns.length}`);
  assert.deepEqual(plan.unrouted, []);
  const cells = new Set<string>();
  for (const p of [...plan.roleChests.map((c) => c.pos), ...plan.stores.map((c) => c.pos), ...plan.outputChests,
    ...plan.cookers.map((c) => c.pos), ...plan.tables.map((c) => c.pos), ...plan.stonecutters, ...plan.beds.flatMap((b) => [b.foot, b.head])]) {
    const key = `${p.x},${p.y},${p.z}`;
    assert.ok(!cells.has(key), `two things at ${key}`);
    cells.add(key);
  }
  for (const s of plan.spawns) assert.ok(!cells.has(`${s.x},${s.y},${s.z}`), `spawn on a station at ${s.x},${s.z}`);
  // Follow the planned street into the actual furnished dormitory. An exterior door
  // alone is insufficient if the next two cells have been filled with beds.
  const stateAt = (x: number, y: number, z: number) => {
    let state = "minecraft:air";
    for (const op of plan.works.ops) {
      if (op[0] === "set" && op[1] === x && op[2] === y && op[3] === z) state = plan.works.palette[Number(op[4])];
      if (op[0] === "fill" && x >= Math.min(Number(op[1]), Number(op[4])) && x <= Math.max(Number(op[1]), Number(op[4]))
        && y >= Math.min(Number(op[2]), Number(op[5])) && y <= Math.max(Number(op[2]), Number(op[5]))
        && z >= Math.min(Number(op[3]), Number(op[6])) && z <= Math.max(Number(op[3]), Number(op[6]))) state = plan.works.palette[Number(op[7])];
    }
    return state;
  };
  for (const l of plan.lots.filter(l => l.kind === "lodge")) {
    for (let u = Math.floor(l.w / 2) - 1; u <= Math.floor(l.w / 2) + 1; u++)
      for (let v = Math.floor(l.d / 2); v < l.d; v++) for (let dy = 0; dy < 3; dy++) {
        const at = l.frame.at(u, dy, v);
        assert.equal(stateAt(at.x, at.y, at.z), "minecraft:air", `${l.name} entrance obstructed at ${JSON.stringify(at)}`);
      }
  }
  for (const l of plan.lots) for (const m of plan.lots) if (l !== m)
    assert.ok(l.box.maxX < m.box.minX || m.box.maxX < l.box.minX || l.box.maxZ < m.box.minZ || m.box.maxZ < l.box.minZ, `${l.name} overlaps ${m.name}`);
  console.log(`planned ${plan.lots.length} lots and ${plan.roads.length} roads in ${ms} ms; ops ${plan.pads.ops.length}+${plan.works.ops.length}`);
});

test("fallback streets meet both doorsteps at ground height, with golem headroom", () => {
  const terrain = new Terrain({ minX: 0, minZ: 0, width: 11, depth: 11, sea: 60,
    ground: Array(121).fill(70), water: Array(121).fill(-9999), cover: Array(121).fill(0) });
  const works = new Works();
  walkway(terrain, { name: "street", width: 3, points: [], ends: [70, 72],
    cells: Array.from({ length: 7 }, (_, i) => [2 + i, 5]) }, works);
  const blocks = new Map<string, string>();
  for (const op of works.ops) {
    if (op[0] === "set") blocks.set(`${op[1]},${op[2]},${op[3]}`, works.palette[Number(op[4])]);
    if (op[0] === "fill") for (let x = Number(op[1]); x <= Number(op[4]); x++)
      for (let y = Number(op[2]); y <= Number(op[5]); y++) for (let z = Number(op[3]); z <= Number(op[6]); z++)
        blocks.set(`${x},${y},${z}`, works.palette[Number(op[7])]);
  }
  assert.equal(blocks.get("2,70,5"), "minecraft:gravel");
  assert.equal(blocks.get("8,72,5"), "minecraft:gravel");
  for (const [x, y] of [[2, 70], [8, 72]]) for (let dy = 1; dy <= 3; dy++)
    assert.equal(blocks.get(`${x},${y + dy},5`), "minecraft:air");
});

test("a dry street can leave a doorway beside small pools", () => {
  const size = 65;
  const survey = { minX: 0, minZ: 0, width: size, depth: size, sea: 60,
    ground: Array(size * size).fill(70), water: Array(size * size).fill(-9999), cover: Array(size * size).fill(0) };
  const lot = (name: string, x: number, z: number): Lot => {
    const frame = new Frame(x, z, 71, 0, 9, 9);
    return { kind: name === "plaza" ? "plaza" : "lodge", name, w: 9, d: 9, ring: [0, 50], range: 0, pad: true,
      frame, box: frame.box(), y: 71, door: { x: x + 4, z: z + 8, out: { x: x + 4, z: z + 10 } } };
  };
  const homes = [lot("plaza", 8, 36), lot("home", 28, 8)];
  for (const [x, z] of [[30, 19], [32, 20], [34, 21]]) survey.water[x * size + z] = 71;
  const routed = routeRoads(new Terrain(survey), homes, { minX: 0, maxX: 64, minZ: 0, maxZ: 64 }, 70);
  assert.deepEqual(routed.unrouted, []);
  assert.ok(routed.roads.length > 0);
});

test("a street leaves sideways when a stream runs right past the doorstep", () => {
  const size = 65;
  const survey = { minX: 0, minZ: 0, width: size, depth: size, sea: 60,
    ground: Array(size * size).fill(70), water: Array(size * size).fill(-9999), cover: Array(size * size).fill(0) };
  const lot = (name: string, x: number, z: number): Lot => {
    const frame = new Frame(x, z, 71, 0, 9, 9);
    return { kind: name === "plaza" ? "plaza" : "lodge", name, w: 9, d: 9, ring: [0, 50], range: 0, pad: true,
      frame, box: frame.box(), y: 71, door: { x: x + 4, z: z + 8, out: { x: x + 4, z: z + 10 } } };
  };
  const homes = [lot("plaza", 8, 36), lot("home", 28, 8)];
  // The home's doorstep is (32, 18); the stream crosses the cell straight in front of it.
  for (let k = 0; k < 9; k++) survey.water[(32 + k) * size + 19 + k] = 71;
  const routed = routeRoads(new Terrain(survey), homes, { minX: 0, maxX: 64, minZ: 0, maxZ: 64 }, 70);
  assert.deepEqual(routed.unrouted, []);
});
