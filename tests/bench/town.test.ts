import assert from "node:assert/strict";
import test from "node:test";
import { FIELD_CROPS, STREET, Terrain, Works, Frame, catalogue, layoutCity, levelCity, routeRoads, worksPlan, sow, walkway, STORE_AREAS, type Counts, type Lot, type Vec } from "./town.ts";
import { planTown } from "./town-build.ts";
import { syntheticSurvey } from "./synthetic.ts";

const COUNTS: Counts = { humans: 400, cookers: { furnace: 36, blast: 12, smoker: 16 },
  tables: { crafting: 56, smithing: 8 }, stonecutters: 8, pens: 24 };

test("the city lies in blocks on one level and holds everything the colony works at", () => {
  const terrain = new Terrain(syntheticSurvey(0, 0, 260));
  const started = Date.now();
  const plan = planTown(terrain, { x: 0, z: 0, level: 70, sea: 62 }, COUNTS);
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
  // Every station stands on the one works lot, with the stock the stations use; each trade keeps its own pantry and
  // fuel there. The stores of what the land gives and takes stand out in the town, each a yard of its own.
  const works = plan.lots.find(l => l.kind === "works")!;
  const out = plan.stores.filter((c) => c.lot.startsWith("store-") && STORE_AREAS[c.role as keyof typeof STORE_AREAS]);
  assert.equal(out.length, 32);
  assert.deepEqual([...new Set(out.map((c) => c.lot))].sort(),
    ["store-crop", "store-feed", "store-food-0", "store-food-1", "store-log", "store-meat", "store-plank", "store-seed", "store-stone"]);
  for (const c of out) {
    const lot = plan.lots.find((l) => l.name === c.lot)!;
    assert.ok(lot.kind === "store" && c.pos.x >= lot.box.minX && c.pos.x <= lot.box.maxX && c.pos.z >= lot.box.minZ && c.pos.z <= lot.box.maxZ,
      `${c.lot}'s chest stands off its yard`);
  }
  for (const p of [...plan.roleChests.map((c) => c.pos), ...plan.stores.filter((c) => !c.lot.startsWith("works-store") && !out.includes(c)).map((c) => c.pos),
    ...plan.outputChests, ...plan.cookers.map((c) => c.pos), ...plan.tables.map((c) => c.pos), ...plan.stonecutters])
    assert.ok(p.x >= works.box.minX && p.x <= works.box.maxX && p.z >= works.box.minZ && p.z <= works.box.maxZ, `off the works at ${p.x},${p.z}`);
  const trades = [...new Set(plan.stores.map(s => s.lot).filter(lot => /^(smithy|kitchen|workshop|masonry)-/.test(lot)))];
  assert.equal(trades.length, 9);
  for (const name of trades) {
    const local = plan.stores.filter(s => s.lot === name).map(s => s.role);
    assert.ok(local.includes("food"), `${name} has no local pantry`);
    if (/^(smithy|kitchen)-/.test(name)) assert.ok(local.includes("fuel"), `${name} has no fuel`);
  }
  // Chests are double chests in stacks: each kept at its lesser half, the other half beside it.
  assert.equal(plan.chestHalves.length, plan.roleChests.length + plan.stores.length + plan.outputChests.length + plan.yardChests.length);
  for (const { anchor, other } of plan.chestHalves) {
    assert.equal(Math.abs(anchor.x - other.x) + Math.abs(anchor.z - other.z), 1);
    assert.ok(anchor.y === other.y && (anchor.z < other.z || (anchor.z === other.z && anchor.x < other.x)));
  }
  assert.equal(plan.houses.length, 12);
  // One level; every lot inside a block, every block clear of every street, the streets straight along x or z.
  const { grid } = plan;
  const within = (a: { minX: number; maxX: number; minZ: number; maxZ: number }, b: typeof a) =>
    a.minX >= b.minX && a.maxX <= b.maxX && a.minZ >= b.minZ && a.maxZ <= b.maxZ;
  for (const l of plan.lots) {
    assert.equal(l.y, 71, `${l.name} stands off the city's level`);
    assert.equal(grid.blocks.filter((b) => within(l.box, b.box)).length, 1, `${l.name} stands in no one block`);
  }
  for (const b of grid.blocks) {
    assert.ok(within(b.box, grid.box), `${b.name} is outside the city`);
    for (const r of plan.roads) for (const [x, z] of r.cells)
      assert.ok(x < b.box.minX - 3 || x > b.box.maxX + 3 || z < b.box.minZ - 3 || z > b.box.maxZ + 3, `${r.name} runs into ${b.name}`);
  }
  for (const r of plan.roads) assert.ok(r.points.every((p) => p[1] === 70) && (r.points[0][0] === r.points[1][0] || r.points[0][2] === r.points[1][2]), r.name);
  assert.equal(plan.lots.filter((l) => l.kind === "plaza").length, 1);
  const plaza = plan.lots.find((l) => l.kind === "plaza")!;
  assert.ok(Math.abs((plaza.box.minX + plaza.box.maxX) / 2) < 50 && Math.abs((plaza.box.minZ + plaza.box.maxZ) / 2) < 50, "the plaza stands by the middle");
  assert.ok(plan.spawns.length >= 500, `spawns ${plan.spawns.length}`);
  assert.deepEqual(plan.unrouted, []);
  const cells = new Set<string>();
  for (const p of [...plan.roleChests.map((c) => c.pos), ...plan.stores.map((c) => c.pos), ...plan.outputChests, ...plan.yardChests,
    ...plan.chestHalves.map((c) => c.other), ...plan.spareChests,
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

test("the big field is sown in side-by-side strips, each crop its share", () => {
  const soil: Vec[] = [];
  for (let x = 0; x < 56; x++) for (let z = 0; z < 48; z++) if ((x + 1) % 19) soil.push({ x, y: 70 + Math.floor(z / 8), z });
  const sown = sow(soil);
  assert.deepEqual(sown.map((s) => s.crop), FIELD_CROPS.map((c) => c.crop));
  assert.equal(sown.reduce((n, s) => n + s.cells.length, 0), soil.length);
  sown.forEach((s, i) => {
    assert.ok(Math.abs(s.cells.length / soil.length - FIELD_CROPS[i].share) < 0.05, `${s.crop} has ${s.cells.length} of ${soil.length}`);
    // A strip across the field: whole columns of it, none shared with the next crop.
    if (i) assert.ok(Math.min(...s.cells.map((c) => c.x)) > Math.max(...sown[i - 1].cells.map((c) => c.x)), `${s.crop} overlaps its neighbour`);
  });
});

test("each cluster of the works holds one kind of station", () => {
  const plan = worksPlan(COUNTS);
  assert.equal(plan.stations.length, 136);
  const at = new Map<string, string>();
  for (const s of plan.stations) {
    assert.ok(s.along >= 0 && s.along < 17, `a ${s.kind} stands off its bench`);
    assert.ok(!at.has(`${s.bench},${s.row},${s.along}`), "two stations share a cell");
    at.set(`${s.bench},${s.row},${s.along}`, s.kind);
  }
  // Along a bench, and across its two rows, a station's neighbour is its own kind or a gap.
  for (const [key, kind] of at) {
    const [bench, row, along] = key.split(",").map(Number);
    for (const near of [`${bench},${row},${along + 1}`, `${bench},${1 - row},${along}`, `${bench},${1 - row},${along + 1}`, `${bench},${1 - row},${along - 1}`])
      assert.ok(!at.has(near) || at.get(near) === kind, `a ${kind} stands beside a ${at.get(near)}`);
  }
});

test("blocks are unequal, each sized to the one building or area it holds, a street between any two", () => {
  const city = layoutCity(catalogue(COUNTS));
  const held = city.blocks.filter((b) => b.lots.length);
  // One block to each single building, and to each area of lodges, paddocks, houses and gardens.
  assert.deepEqual(held.map((b) => b.name).sort(), ["field", "fish-ponds", "gardens", "lodges-0", "lodges-1",
    "paddocks-0", "paddocks-1", "plaza", "quarter-0", "quarter-1", "wood", "works"]);
  assert.ok(new Set(held.map((b) => `${b.w}x${b.d}`)).size >= 10, "the blocks are of many sizes");
  for (const b of city.blocks) {
    assert.ok(b.x >= STREET && b.z >= STREET && b.x + b.w <= city.w - STREET && b.z + b.d <= city.d - STREET, `${b.name} leaves the city`);
    for (const l of b.lots) assert.ok(l.u >= 0 && l.v >= 0 && l.u + l.spec.w <= b.w && l.v + l.spec.d <= b.d, `${l.spec.name} leaves its block`);
    for (const c of city.blocks) if (b !== c)
      assert.ok(b.x + b.w + STREET <= c.x || c.x + c.w + STREET <= b.x || b.z + b.d + STREET <= c.z || c.z + c.d + STREET <= b.z, `${b.name} crowds ${c.name}`);
  }
  // A block is no bigger than what it holds, give or take the row it shares: little ground is left over.
  const over = held.reduce((n, b) => n + b.w * b.d, 0) / held.reduce((n, b) =>
    n + (Math.max(...b.lots.map((l) => l.u + l.spec.w)) - Math.min(...b.lots.map((l) => l.u))) * (Math.max(...b.lots.map((l) => l.v + l.spec.d)) - Math.min(...b.lots.map((l) => l.v))), 0);
  assert.ok(over < 1.25, `the blocks are ${over.toFixed(2)} times what they hold`);
});

test("the ground round the city is eased to a block's rise a block out, and track is left alone", () => {
  // A 30-block cliff right at the city's east edge.
  const size = 81, ground: number[] = [];
  for (let i = 0; i < size; i++) for (let j = 0; j < size; j++) ground.push(i - 40 > 20 ? 100 : 70);
  const terrain = new Terrain({ minX: -40, minZ: -40, width: size, depth: size, sea: 60, ground, water: Array(size * size).fill(-9999), cover: Array(size * size).fill(0) });
  const works = new Works();
  const eased = levelCity(terrain, { minX: -20, maxX: 20, minZ: -20, maxZ: 20 }, 70, works, (x) => x === 25);
  const pads = works.ops.filter((op) => op[0] === "pad" && op[1] === op[3] && op[2] === op[4]);
  assert.equal(pads.length, eased);
  assert.ok(eased > 0);
  for (const op of pads) {
    const x = Number(op[1]), z = Number(op[2]), out = Math.max(-20 - x, x - 20, -20 - z, z - 20);
    assert.notEqual(x, 25, "a spared column was eased");
    assert.equal(Number(op[5]) - 1, 70 + out, `the column ${x},${z} stands ${Number(op[5]) - 1}`);
  }
});
