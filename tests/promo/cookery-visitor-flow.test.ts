import assert from "node:assert/strict";
import test from "node:test";
import { cookeryVisitorPlans, summarizeCookeryVisitors, summarizeCookeryTakeVisits, summarizeCookeryUpstairs } from "./cookery-visitor-flow.ts";

test("56 patrons arrive in terrace-led waves and fit on the paved off-camera street", () => {
  const plans = cookeryVisitorPlans({ x: 0, y: 64, z: 0 }, 56);
  assert.equal(new Set(plans.map(plan => JSON.stringify(plan.start))).size, 56);
  for (const plan of plans) {
    assert.ok(plan.start.x >= -65 && plan.start.x <= 55);
    assert.ok(plan.start.x <= -34 || plan.start.x >= 24);
    assert.ok(plan.start.z >= -17 && plan.start.z <= -15);
  }
  for (let wave = 0; wave < 7; wave++) {
    const patrons = plans.filter(plan => plan.wave === wave);
    assert.equal(patrons.length, 8);
    assert.equal(patrons.filter(plan => plan.area === "balcony").length, 2);
    assert.equal(patrons.filter(plan => plan.area === "courtyard").length, 6);
    assert.equal(new Set(patrons.filter(plan => plan.area === "balcony").map(plan => plan.entrance.x)).size, 2);
    assert.ok(patrons.every(plan => plan.release === wave * 12));
  }
  assert.ok(Math.max(...plans.map(plan => plan.release)) < 120);
});

const person = (overrides: Record<string, any>) => ({
  uuid: "patron", wave: 2, area: "balcony", phase: "waiting", doing: "walking",
  local: [-37, 0, -16], dined: -24000, offscreen: false, insideInn: false, departures: 0,
  ...overrides,
});
const sample = (seconds: number, overrides: Record<string, any>) => ({
  seconds, started: 1000, people: [person(overrides)],
});

test("a completed visit requires observed arrival, actual meal history, and departure", () => {
  const [visit] = summarizeCookeryVisitors([
    sample(0, { offscreen: true }),
    sample(20, { phase: "dining", insideInn: true, doing: "dining_eating", dining: true, seatedAtTable: true, local: [-7, 6, -3] }),
    sample(26, { phase: "departing", insideInn: true, dined: 1520, local: [-7, 6, -3] }),
    sample(40, { phase: "holding", offscreen: true, dined: 1520, departures: 1 }),
  ], ["patron"]);
  assert.equal(visit.complete, true);
  assert.equal(visit.visits, 1);
  assert.equal(visit.wave, 2);
  assert.equal(visit.area, "balcony");
  assert.equal(visit.diningSamples, 1);
  assert.equal(visit.upperFloorSamples, 2);
});

test("walking in and out without a fresh completed meal does not pass", () => {
  const [visit] = summarizeCookeryVisitors([
    sample(0, { offscreen: true, dined: 900 }),
    sample(20, { insideInn: true, dined: 900 }),
    sample(40, { offscreen: true, dined: 900, departures: 1 }),
  ], ["patron"]);
  assert.equal(visit.complete, false);
  assert.equal(visit.incompleteReason, "no completed table meal observed");
});

test("courtyard arrival can select ground-floor tables but cannot see upstairs meals", () => {
  const origin = { x: 100, y: 64, z: -200 };
  const plans = cookeryVisitorPlans(origin, 39);
  const distanceSquared = (a: { x: number; y: number; z: number }, b: { x: number; y: number; z: number }) =>
    (a.x - b.x) ** 2 + (a.y - b.y) ** 2 + (a.z - b.z) ** 2;
  for (const patron of plans.filter(plan => plan.area === "courtyard")) {
    for (const left of [-15, -11, -7]) for (const dx of [0, 1]) {
      const groundTable = { x: origin.x + left + dx, y: origin.y, z: origin.z - 12 };
      assert.ok(distanceSquared(patron.entrance, groundTable) <= 16 ** 2);
    }
    for (const left of [-16, -12, -8]) for (const dx of [0, 1]) for (const z of [-3, -2]) {
      const upperTable = { x: origin.x + left + dx, y: origin.y + 6, z: origin.z + z };
      // Native arrival accepts feet within 0.9 blocks of the stance centre.
      for (const offsetX of [-0.9, 0, 0.9]) for (const offsetZ of [-0.9, 0, 0.9]) {
        const feet = { ...patron.entrance, x: patron.entrance.x + offsetX, z: patron.entrance.z + offsetZ };
        assert.ok(distanceSquared(feet, upperTable) > 16 ** 2);
      }
    }
  }
});

test("walking toward a meal is not counted as seated eating", () => {
  const [visit] = summarizeCookeryVisitors([
    sample(0, { offscreen: true }),
    sample(20, { phase: "dining", insideInn: true, doing: "dining_eating", dining: false, seatedAtTable: false }),
  ], ["patron"]);
  assert.equal(visit.diningSamples, 0);
  assert.equal(visit.mealActivitySamples, 1);
});

test("upstairs density requires an actual table seat on that floor", () => {
  const upstairs = summarizeCookeryUpstairs([{ seconds: 0, visitors: { people: [
    person({ dining: true, seatedAtTable: true, local: [-7, 5.95, -3] }),
    person({ dining: true, seatedAtTable: true, local: [-11, 5.95, -3] }),
    person({ dining: true, seatedAtTable: true, local: [-7, 0, -12] }),
    person({ dining: false, seatedAtTable: false, local: [0, 6, -4] }),
    person({ dining: true, seatedAtTable: false, local: [-7, 6, -3] }),
  ] } }]);
  assert.equal(upstairs.meanEating, 2);
  assert.equal(upstairs.samples[0].eating, 2);
});

test("pre-roll arrivals count only when their real meal and exit are recorded", () => {
  const initial = sample(0, { offscreen: true });
  const preRoll = [{ seconds: 20, visitors: sample(20, { insideInn: true }) }];
  const recording = [
    { seconds: 0, visitors: sample(0, { insideInn: true, phase: "dining", dining: true, seatedAtTable: true }) },
    { seconds: 60, visitors: sample(60, { insideInn: true, dined: 2200, phase: "departing" }) },
    { seconds: 75, visitors: sample(75, { offscreen: true, dined: 2200, departures: 1 }) },
  ];
  const [visit] = summarizeCookeryTakeVisits(initial, preRoll, recording, ["patron"]);
  assert.equal(visit.complete, true);
  assert.equal(visit.outside, -20);
  assert.equal(visit.ate, 60);
  assert.equal(visit.departed, 75);
  const [before] = summarizeCookeryTakeVisits(initial,
    [...recording.map(state => ({ seconds: state.seconds + 20, visitors: state.visitors }))], [], ["patron"]);
  assert.equal(before.complete, false);
});
