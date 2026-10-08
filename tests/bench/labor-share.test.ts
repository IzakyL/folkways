import assert from "node:assert/strict";
import test from "node:test";
import type { ColonyBeat } from "../shared/heartbeat.ts";
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

test("shares leave out the warm-up beats and count holding as working", () => {
  const share = laborShare([
    beat({ standing: { idle: 10 } }),
    beat({ standing: { idle: 10 } }),
    beat({ standing: { walking: 5, working: 2, holding: 1, idle: 2 }, done: 4 }),
    beat({ standing: { walking: 5, working: 3, stalled: 2 }, done: 6, why: { BEYOND_HORIZON: 7 } }),
  ], 200);
  assert.equal(share.beats, 2);
  assert.equal(share.warmup, 2);
  assert.deepEqual(share.standing_share, { walking: 0.5, working: 0.3, idle: 0.1, stalled: 0.1 });
  assert.equal(share.done_total, 10);
  assert.equal(share.throughput_per_1000_ticks, 25);
  assert.deepEqual(share.deferred, { median: 3.5, peak: 7, last: 7 });
});

test("no live beats measure nothing rather than zero output", () => {
  const share = laborShare([beat({ bodies: 0 })]);
  assert.equal(share.beats, 0);
  assert.equal(share.throughput_per_1000_ticks, 0);
  assert.deepEqual(share.standing_share, {});
});
