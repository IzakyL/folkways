import assert from "node:assert/strict";
import test from "node:test";
import type { ColonyBeat } from "../../shared/colony/heartbeat.ts";
import { laborShare } from "./labor-share.ts";

function beat(over: Partial<ColonyBeat> = {}): ColonyBeat {
  return {
    bodies: 10, messages: 0, nodes: 0, tours: 0, phases: {},
    done: 0, released: 0,
    why: {}, unassigned: 0, shortfall: 0, turned: 0,
    refused: {}, broke: {}, standing: {}, skipped: 0, solving: false, solving_seconds: 0,
    ...over,
  };
}

test("shares count every beat given and count holding as working", () => {
  const share = laborShare([
    beat({ standing: { idle: 10 } }),
    beat({ standing: { idle: 10 } }),
    beat({ standing: { walking: 5, working: 2, holding: 1, idle: 2 }, done: 4 }),
    beat({ standing: { walking: 5, working: 3, stalled: 2 }, done: 6, why: { BEYOND_HORIZON: 7 } }),
  ], 200);
  assert.equal(share.beats, 4);
  assert.deepEqual(share.standing_share, { idle: 0.55, walking: 0.25, working: 0.15, stalled: 0.05 });
  assert.equal(share.done_total, 10);
  assert.equal(share.throughput_per_1000_ticks, 12.5);
  assert.deepEqual(share.deferred, { median: 0, peak: 7, last: 7 });
});

test("no live beats measure nothing rather than zero output", () => {
  const share = laborShare([beat({ bodies: 0 })]);
  assert.equal(share.beats, 0);
  assert.equal(share.throughput_per_1000_ticks, 0);
  assert.deepEqual(share.standing_share, {});
});
