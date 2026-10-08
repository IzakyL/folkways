import assert from "node:assert/strict";
import test from "node:test";
import { inside, offsetBox, railSite, type Box, type Vec } from "../e2e/rail-footprint.ts";

const origin: Vec = { x: -8, y: -60, z: 16 };
const stations = [
  { name: "Alpha", position: { x: -7, y: -60, z: 18 }, reverse: false },
  { name: "Beta", position: { x: -7, y: -60, z: -184 }, reverse: false },
];
const decoration: Box = { min: { x: -2, y: 0, z: 1 }, max: { x: 7, y: 5, z: 16 } };
const within = (boxes: Box[], p: Vec) => boxes.some((b) => inside(b, p));

test("residents standing where the promo car is built fall inside the footprint", () => {
  const site = railSite({ origin, lineLength: 200, passengerCount: 1, stations, carTop: -54, decoration: offsetBox(origin, decoration) });
  for (const cell of [{ x: -9, y: -60, z: 19 }, { x: -9, y: -60, z: 20 }, { x: -10, y: -59, z: 22 }, { x: -6, y: -60, z: 17 }]) {
    assert.ok(within(site.footprint, cell), JSON.stringify(cell));
  }
  assert.ok(within(site.structures, { x: -1, y: -60, z: 30 }), "the promo platform is part of what gets built");
  assert.ok(!within(site.footprint, { x: 0, y: -60, z: 12 }), "the colony side stays free");
  assert.ok(!within(site.footprint, { x: -11, y: -60, z: 19 }), "beside the car is free");
});

test("the travel envelope covers the whole line to car height, and the bed sits under the track", () => {
  const site = railSite({ origin, lineLength: 200, passengerCount: 1, stations, carTop: -56 });
  assert.ok(within(site.envelope, { x: -10, y: -56, z: -190 }));
  assert.ok(within(site.envelope, { x: -6, y: -60, z: 24 }));
  assert.ok(!within(site.envelope, { x: -8, y: -55, z: 0 }));
  assert.ok(within(site.bed, { x: -8, y: -61, z: -192 }));
  assert.ok(within(site.bed, { x: -7, y: -61, z: 18 }), "stations stand on the bed too");
  assert.ok(!within(site.bed, { x: -9, y: -61, z: 0 }));
});

test("platforms match the stone the fixture lays", () => {
  const site = railSite({ origin, lineLength: 200, passengerCount: 1, stations, carTop: -56 });
  assert.deepEqual(site.platforms[0], { min: { x: -5, y: -60, z: 17 }, max: { x: -4, y: -59, z: 25 } });
  assert.deepEqual(site.platforms[1], { min: { x: -5, y: -60, z: -186 }, max: { x: -4, y: -59, z: -174 } });
});

test("a loop footprint follows its bends, not the whole corner", () => {
  const loop = { minX: -100, maxX: 0, minZ: -120, maxZ: 0 };
  const site = railSite({ origin: { x: 0, y: -60, z: -40 }, lineLength: 24, passengerCount: 20, stations: [
    { name: "Alpha", position: { x: 1, y: -60, z: -38 }, reverse: false },
    { name: "Beta", position: { x: 1, y: -60, z: -64 }, reverse: false },
  ], carTop: -56, loop });
  const mid = Math.round(16 - 16 * Math.SQRT1_2);
  assert.ok(within(site.footprint, { x: -mid, y: -60, z: -120 + mid }), "the bend itself is cleared");
  assert.ok(!within(site.footprint, { x: -14, y: -60, z: -106 }), "inside the bend stays untouched");
  assert.equal(site.track.length, 4);
});
