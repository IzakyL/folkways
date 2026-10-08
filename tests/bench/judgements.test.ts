import assert from "node:assert/strict";
import test from "node:test";
import type { ColonyBeat } from "../shared/heartbeat.ts";
import {
  judgeConservation, judgeFailures, judgeUtilisation, type Tally,
} from "./judgements.ts";

function beat(over: Partial<ColonyBeat> = {}): ColonyBeat {
  return {
    bodies: 4, messages: 3, nodes: 4, tours: 2, phases: {},
    done: 0, released: 0,
    why: {}, unassigned: 0, shortfall: 0, turned: 0,
    refused: {}, broke: {}, standing: {}, skipped: 0, solving: false, solving_seconds: 0,
    ...over,
  };
}

const working = beat({
  standing: { walking: 2, working: 1, idle: 1 },
  phases: { READY: 2, WORKING: 2 },
  done: 2,
});

test("too few beats: no verdict, and it says too few, not all clear", () => {
  const found = judgeUtilisation(Array(4).fill(beat({ standing: { walking: 4 } })));
  assert.equal(found.length, 1);
  assert.equal(found[0].severity, "caveat");
  assert.match(found[0].rule, /too-few-beats/);
});

test("a healthy stretch yields no findings", () => {
  assert.deepEqual(judgeUtilisation(Array(20).fill(working)), []);
  assert.deepEqual(judgeFailures(Array(20).fill(working)), []);
});

test("no beat reports bodies - says not measured, not didn't happen", () => {
  const found = judgeUtilisation(Array(14).fill(beat({ bodies: 0 })));
  assert.equal(found.length, 1);
  assert.equal(found[0].severity, "caveat");
  assert.match(found[0].rule, /no-data/);
});

test("idle signature: tours and bodies, all five counters zero", () => {
  const found = judgeUtilisation(Array(14).fill(beat({ standing: { walking: 4 } })));
  assert.ok(found.some((one) => one.rule === "utilisation/nothing-happens"));
});

test("idle signature must be consecutive, scattered beats don't count", () => {
  const beats = Array.from({ length: 16 }, (_, i) =>
    i % 2 === 0 ? working : beat({ standing: { walking: 4 } }));
  assert.ok(!judgeUtilisation(beats).some((one) => one.rule === "utilisation/nothing-happens"));
});

test("red only when the stalled share is high and sustained", () => {
  const jammed = beat({
    standing: { stalled: 3, walking: 1 },
    phases: { WORKING: 1 },
    done: 1,
  });
  assert.ok(judgeUtilisation(Array(14).fill(jammed)).some((one) => one.rule === "utilisation/stalled"));
  assert.ok(!judgeUtilisation([jammed, jammed, ...Array(12).fill(working)])
    .some((one) => one.rule === "utilisation/stalled"));
});

test("took work but finished none", () => {
  const busy = beat({
    standing: { walking: 4 },
    phases: { WORKING: 3 },
  });
  assert.ok(judgeUtilisation(Array(14).fill(busy)).some((one) => one.rule === "utilisation/never-finishes"));
});

test("jam: a reason that should be transient keeps persisting", () => {
  const stuck = beat({ ...working, unassigned: 1, why: { NO_FREE_STAND: 1 },
    unassignedNodes: { "task-a": "NO_FREE_STAND" } });
  const found = judgeFailures(Array(9).fill(stuck));
  assert.ok(found.some((one) => one.rule === "failures/jam"));
  assert.ok(!judgeFailures([stuck, stuck, stuck, working, working])
    .some((one) => one.rule === "failures/jam"));
});

test("a path unreachable in a clean world is red on first occurrence", () => {
  const found = judgeFailures([beat({ refused: { WORK_BROKE: 1 } })]);
  assert.equal(found.length, 1);
  assert.equal(found[0].severity, "red");
});

test("a reason missing from the tables is red too - the source added a failure reason this list lacks", () => {
  assert.ok(judgeFailures([beat({ refused: { SOMETHING_NEW: 2 } })])
    .some((one) => one.rule === "failures/unknown-kind"));
  assert.ok(judgeFailures([beat({ unassigned: 1, why: { BRAND_NEW_REASON: 1 } })])
    .some((one) => one.rule === "failures/unknown-unassigned"));
});

const tally = (over: Partial<Tally> = {}): Tally =>
  ({ packs: {}, containers: {}, ground: {}, player: {}, capped: false, ...over });

test("one tracer tool missing means it was swallowed", () => {
  const before = tally({ containers: { "minecraft:shears": 4 } });
  const after = tally({ containers: { "minecraft:shears": 2 }, packs: { "minecraft:shears": 1 } });
  const found = judgeConservation(before, after);
  assert.equal(found.length, 1);
  assert.equal(found[0].rule, "conservation/tool-vanished");
});

test("a worn-out tool is accounted for, not swallowed", () => {
  const before = tally({ packs: { "minecraft:wooden_hoe": 13 } });
  const after = tally({ packs: { "minecraft:wooden_hoe": 12 } });
  assert.deepEqual(judgeConservation(before, after, { "minecraft:wooden_hoe": 1 }), []);
  const found = judgeConservation(before, after, { "minecraft:wooden_hoe": 0 });
  assert.equal(found.length, 1);
  assert.equal(found[0].severity, "red");
});

test("without a wear count in the heartbeat, the tool group is noted, not judged", () => {
  const found = judgeConservation(
    tally({ packs: { "minecraft:wooden_hoe": 13 } }),
    tally({ packs: { "minecraft:wooden_hoe": 12 } }),
    {},
    false,
  );
  assert.equal(found.length, 1);
  assert.equal(found[0].severity, "caveat");
});

test("a tool that just moved elsewhere is not missing", () => {
  const before = tally({ containers: { "minecraft:shears": 4 } });
  const after = tally({ packs: { "minecraft:shears": 3 }, ground: { "minecraft:shears": 1 } });
  assert.deepEqual(judgeConservation(before, after), []);
});

test("bucket plus milk bucket is constant", () => {
  const before = tally({ containers: { "minecraft:bucket": 3 } });
  assert.deepEqual(
    judgeConservation(before, tally({ containers: { "minecraft:bucket": 1, "minecraft:milk_bucket": 2 } })),
    [],
  );
  assert.ok(judgeConservation(before, tally({ containers: { "minecraft:milk_bucket": 2 } }))
    .some((one) => one.rule === "conservation/pair-broken"));
});

test("no red when the tally is truncated", () => {
  const found = judgeConservation(
    tally({ containers: { "minecraft:shears": 4 }, capped: true }),
    tally(),
  );
  assert.equal(found.length, 1);
  assert.equal(found[0].severity, "caveat");
});


test("an empty dimension doesn't break a sustained overworld block", () => {
  const beats = Array.from({ length: 6 }, () => [
    beat({ colony: "a", dimension: "overworld", why: { NO_WAY: 4 }, unassignedNodes: { task: "NO_WAY" } }),
    beat({ colony: "a", dimension: "nether", bodies: 0 }),
    beat({ colony: "a", dimension: "end", bodies: 0 }),
  ]).flat();
  assert.ok(judgeFailures(beats).some((one) => one.rule === "failures/jam"));
});

test("scattered blocks across colonies don't add up to a sustained one", () => {
  const beats = Array.from({ length: 4 }, () => [
    beat({ colony: "a", dimension: "overworld", why: { NO_WAY: 1 } }),
    beat({ colony: "b", dimension: "overworld", why: { NO_WAY: 1 } }),
  ]).flat();
  assert.deepEqual(judgeFailures(beats), []);
});

test("waiting on pathfinding may appear briefly but must converge", () => {
  const pending = beat({ why: { WAY_PENDING: 3 }, unassignedNodes: { task: "WAY_PENDING" } });
  assert.deepEqual(judgeFailures([pending, working]), []);
  assert.ok(judgeFailures(Array(6).fill(pending)).some((one) => one.rule === "failures/jam"));
});

test("another colony working normally doesn't mask this colony idling", () => {
  const beats = Array.from({ length: 14 }, () => [
    beat({ colony: "a", standing: { walking: 4 } }),
    beat({ ...working, colony: "b" }),
  ]).flat();
  assert.ok(judgeUtilisation(beats).some((one) => one.rule === "utilisation/nothing-happens"));
});


test("tasks rotating under the same reason are not judged as one stuck task", () => {
  const beats = Array.from({ length: 12 }, (_, i) => beat({
    why: { WAY_PENDING: 1 }, unassigned: 1,
    unassignedNodes: { [`task-${i}`]: "WAY_PENDING" },
  }));
  assert.deepEqual(judgeFailures(beats), []);
});

test("old logs with only totals report missing evidence, not a deadlock", () => {
  const findings = judgeFailures(Array(12).fill(beat({ why: { WAY_PENDING: 1 }, unassigned: 1 })));
  assert.equal(findings.length, 1);
  assert.equal(findings[0].rule, "failures/task-identity-missing");
  assert.equal(findings[0].severity, "caveat");
});

test("other tasks finishing doesn't mask one task staying unassigned", () => {
  const beats = Array.from({ length: 8 }, () => beat({
    done: 20, why: { WAY_PENDING: 1 }, unassigned: 1,
    unassignedNodes: { stuck: "WAY_PENDING" },
  }));
  assert.ok(judgeFailures(beats).some(f => f.rule === "failures/jam" && f.severity === "red"));
});

test("a brief assignment resets the waiting run", () => {
  const pending = beat({ why: { WAY_PENDING: 1 }, unassigned: 1, unassignedNodes: { task: "WAY_PENDING" } });
  assert.deepEqual(judgeFailures([...Array(4).fill(pending), beat({ unassignedNodes: {} }), ...Array(4).fill(pending)]), []);
});

test("work left past the plan horizon is backlog: a caveat, never a jam or an unknown reason", () => {
  const deferred = beat({ ...working, unassigned: 3, why: { BEYOND_HORIZON: 3 },
    unassignedNodes: { "task-a": "BEYOND_HORIZON", "task-b": "BEYOND_HORIZON", "task-c": "BEYOND_HORIZON" } });
  const found = judgeFailures(Array(20).fill(deferred));
  assert.equal(found.length, 1);
  assert.equal(found[0].rule, "failures/deferred");
  assert.equal(found[0].severity, "caveat");
});
