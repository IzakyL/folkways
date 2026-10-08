import type { ColonyBeat } from "../shared/heartbeat";

// One heartbeat is BEAT_TICKS * heartbeatCycles server ticks (Labor.java: 20 * 10 by default).
export const BEAT_TICKS = 200;

// Beats at the start of a run while residents are still being admitted and the first plans laid; left out of the
// shares so that the empty colony of the first seconds does not read as idleness.
export const WARMUP_BEATS = 2;

export type LaborShare = {
  beats: number;
  warmup: number;
  standing_share: Record<string, number>;
  done_total: number;
  throughput_per_1000_ticks: number;
  deferred: { median: number; peak: number; last: number };
  note: string;
};

const median = (numbers: number[]): number => {
  if (numbers.length === 0) return 0;
  const sorted = [...numbers].sort((a, b) => a - b);
  const half = sorted.length >> 1;
  return sorted.length % 2 ? sorted[half] : (sorted[half - 1] + sorted[half]) / 2;
};

const round = (value: number, places = 3) => Math.round(value * 10 ** places) / 10 ** places;

// What the residents were doing and how much work got done, read from the engine's own heartbeat rather than from
// sampled bodies. The schedule only hands out work it could start within its horizon, so a resident with nothing
// near to do is idle, free for the next plan, not stalled behind work laid far ahead: idle alone no longer says the
// colony is short of work or wasting hands. Throughput - nodes finished per 1000 ticks - is the measure of output.
export function laborShare(beats: ColonyBeat[], beatTicks = BEAT_TICKS): LaborShare {
  const live = beats.filter((beat) => beat.bodies > 0);
  const judged = live.slice(Math.min(WARMUP_BEATS, Math.max(0, live.length - 1)));
  const totals: Record<string, number> = {};
  let bodies = 0;
  for (const beat of judged) {
    if (Object.keys(beat.standing).length === 0) continue;
    bodies += beat.bodies;
    for (const [state, many] of Object.entries(beat.standing)) {
      const key = state === "holding" ? "working" : state;
      totals[key] = (totals[key] ?? 0) + many;
    }
  }
  const share: Record<string, number> = {};
  for (const [state, many] of Object.entries(totals)) {
    share[state] = bodies > 0 ? round(many / bodies) : 0;
  }
  const done = judged.reduce((total, beat) => total + beat.done, 0);
  const ticks = judged.length * beatTicks;
  const deferred = judged.map((beat) => beat.why.BEYOND_HORIZON ?? 0);
  return {
    beats: judged.length,
    warmup: live.length - judged.length,
    standing_share: share,
    done_total: done,
    throughput_per_1000_ticks: ticks > 0 ? round((done * 1000) / ticks, 1) : 0,
    deferred: {
      median: median(deferred),
      peak: deferred.reduce((most, n) => Math.max(most, n), 0),
      last: deferred.length ? deferred[deferred.length - 1] : 0,
    },
    note: `standing_share = each standing={...} state over bodies, summed over beats after the first ${WARMUP_BEATS}`
      + " (holding counts as working). idle = no work handed out: with the plan horizon that is a resident free for"
      + " the next plan, not a fault. stalled = work handed out but none of it can start yet. throughput = nodes"
      + ` finished (done=) per 1000 ticks, ${beatTicks} ticks a beat. deferred = work left past the plan horizon`
      + " (BEYOND_HORIZON) per beat: backlog waiting for a later plan.",
  };
}
