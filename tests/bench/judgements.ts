import type { ColonyBeat } from "../shared/heartbeat";

export type Severity = "red" | "caveat";

export type Finding = {
  rule: string;
  severity: Severity;
  said: string;
  evidence?: unknown;
};

const median = (numbers: number[]): number => {
  if (numbers.length === 0) return 0;
  const sorted = [...numbers].sort((a, b) => a - b);
  const half = sorted.length >> 1;
  return sorted.length % 2 ? sorted[half] : (sorted[half - 1] + sorted[half]) / 2;
};

const sum = (numbers: number[]): number => numbers.reduce((total, n) => total + n, 0);

function longestRun<T>(items: T[], holds: (one: T) => boolean): number {
  let best = 0;
  let run = 0;
  for (const one of items) {
    run = holds(one) ? run + 1 : 0;
    best = Math.max(best, run);
  }
  return best;
}

export const UTILISATION = {
  minBeats: 12,
  minOnDuty: 0.35,
  maxStalled: 0.15,
  runLength: 5,
  idleRunLength: 3,
  onDutyIsRed: false,
};

function byDriver(beats: ColonyBeat[]): ColonyBeat[][] {
  const groups = new Map<string, ColonyBeat[]>();
  for (const beat of beats) {
    const key = JSON.stringify([beat.colony, beat.dimension]);
    const group = groups.get(key) ?? [];
    group.push(beat);
    groups.set(key, group);
  }
  return [...groups.values()];
}

export function judgeUtilisation(beats: ColonyBeat[]): Finding[] {
  if (beats.length === 0) return judgeDriverUtilisation(beats);
  return byDriver(beats).flatMap(judgeDriverUtilisation);
}

function judgeDriverUtilisation(beats: ColonyBeat[]): Finding[] {
  const found: Finding[] = [];
  const live = beats.filter((beat) => beat.bodies > 0);
  if (live.length > 0 && live.length < UTILISATION.minBeats) {
    return [{
      rule: "utilisation/too-few-beats",
      severity: "caveat",
      said: `only ${live.length} beats had bodies (need ${UTILISATION.minBeats} to judge), this group was not judged`,
      evidence: { liveBeats: live.length },
    }];
  }
  if (live.length === 0) {
    return [{
      rule: "utilisation/no-data",
      severity: "caveat",
      said: "no beat in the whole run reported bodies>0, so the utilisation group judged nothing - this 0 means not measured, not didn't happen",
      evidence: { beats: beats.length },
    }];
  }

  const census = live.filter((beat) => Object.keys(beat.standing).length > 0);
  if (census.length === 0) {
    found.push({
      rule: "utilisation/no-standing-census",
      severity: "caveat",
      said: "heartbeat has no standing={...} section, can't read on-duty and stalled rates (engine older than the tests?)",
      evidence: { liveBeats: live.length },
    });
  } else {
    const onDuty = census.map((beat) => 1 - (beat.standing.idle ?? 0) / beat.bodies);
    const stalled = census.map((beat) => (beat.standing.stalled ?? 0) / beat.bodies);
    const slack = longestRun(census, (beat) =>
      1 - (beat.standing.idle ?? 0) / beat.bodies < UTILISATION.minOnDuty);
    if (median(onDuty) < UTILISATION.minOnDuty && slack >= UTILISATION.runLength) {
      found.push({
        rule: "utilisation/on-duty",
        severity: UTILISATION.onDutyIsRed ? "red" : "caveat",
        said: `median on-duty rate ${median(onDuty).toFixed(2)}, and ${slack} consecutive beats below ${UTILISATION.minOnDuty}`
          + " - most people have no work handed out; the schedule hands out only what can start within its horizon,"
          + " so read this with throughput and deferred work rather than as hands wasted",
        evidence: { median: median(onDuty), longestRun: slack, samples: census.length },
      });
    }
    const jammed = longestRun(census, (beat) =>
      (beat.standing.stalled ?? 0) / beat.bodies > UTILISATION.maxStalled);
    if (median(stalled) > UTILISATION.maxStalled && jammed >= UTILISATION.runLength) {
      found.push({
        rule: "utilisation/stalled",
        severity: "red",
        said: `median stalled-body share ${median(stalled).toFixed(2)}, ${jammed} consecutive beats above ${UTILISATION.maxStalled}`
          + " - the head of the queue is blocked, and the blocker won't clear by itself",
        evidence: { median: median(stalled), longestRun: jammed },
      });
    }
  }

  const inHand = (beat: ColonyBeat) =>
    (beat.phases.WORKING ?? 0) + (beat.phases.SETTLING ?? 0);

  const quiet = longestRun(live, (beat) =>
    beat.tours > 0
    && inHand(beat) === 0
    && beat.done === 0
    && beat.released === 0
    && Object.keys(beat.refused).length === 0
    && beat.skipped === 0);
  if (quiet >= UTILISATION.idleRunLength) {
    found.push({
      rule: "utilisation/nothing-happens",
      severity: "red",
      said: `${quiet} consecutive beats with tours and bodies, yet in-hand, done, released, refused and skipped are all zero`
        + " - workers have started but are stuck in a job that never ends",
      evidence: { longestRun: quiet },
    });
  }

  const worked = sum(live.map(inHand));
  const done = sum(live.map((beat) => beat.done));
  if (worked > 0 && done === 0) {
    found.push({
      rule: "utilisation/never-finishes",
      severity: "red",
      said: `nodes were held for ${worked} node-beats over the run, and none were finished`,
      evidence: { workingNodeBeats: worked, done, beats: live.length },
    });
  }
  return found;
}

export type Tally = {
  packs: Record<string, number>;
  containers: Record<string, number>;
  ground: Record<string, number>;
  player: Record<string, number>;
  capped: boolean;
};

export function totalOf(tally: Tally, id: string): number {
  return (tally.packs[id] ?? 0) + (tally.containers[id] ?? 0)
    + (tally.ground[id] ?? 0) + (tally.player[id] ?? 0);
}

export const TRACERS = {
  tools: [
    "minecraft:wooden_hoe", "minecraft:iron_axe", "minecraft:iron_shovel",
    "minecraft:shears", "minecraft:fishing_rod",
  ],
  pairs: [["minecraft:bucket", "minecraft:milk_bucket"]] as Array<[string, string]>,
};

export function judgeConservation(
  before: Tally, after: Tally, broke: Record<string, number> = {}, brokeKnown = true,
): Finding[] {
  const found: Finding[] = [];
  if (before.capped || after.capped) {
    return [{
      rule: "conservation/capped",
      severity: "caveat",
      said: "the tally hit the query cap and is truncated - this group won't go red",
      evidence: { beforeCapped: before.capped, afterCapped: after.capped },
    }];
  }
  for (const id of TRACERS.tools) {
    const was = totalOf(before, id);
    const now = totalOf(after, id);
    const worn = broke[id] ?? 0;
    if (now >= was - worn) {
      continue;
    }
    found.push({
      rule: "conservation/tool-vanished",
      severity: brokeKnown ? "red" : "caveat",
      said: `${id} dropped from ${was} to ${now}, ${worn} of them worn out`
        + `, leaving ${was - worn - now} unaccounted for`
        + (brokeKnown
          ? " - wear is the only way out, so the rest can only have been swallowed in transit"
          : " - but the heartbeat has no broke=, so wear counts as zero and this is noted, not judged"),
      evidence: {
        id, was, now, worn,
        before: { packs: before.packs[id] ?? 0, containers: before.containers[id] ?? 0, ground: before.ground[id] ?? 0 },
        after: { packs: after.packs[id] ?? 0, containers: after.containers[id] ?? 0, ground: after.ground[id] ?? 0 },
      },
    });
  }
  for (const [left, right] of TRACERS.pairs) {
    const was = totalOf(before, left) + totalOf(before, right);
    const now = totalOf(after, left) + totalOf(after, right);
    if (was !== now) {
      found.push({
        rule: "conservation/pair-broken",
        severity: "red",
        said: `${left} + ${right} went from ${was} to ${now}. They only convert into each other, so the total should be constant`,
        evidence: { left, right, was, now },
      });
    }
  }
  return found;
}

export const NEVER_HERE = new Set([
  "WORK_BROKE",
  "UNKNOWN_BLOCK", "NO_WAY_TO_BUILD", "NO_SUCH_ORDER",
  "NOT_YOURS", "ANOTHER_COLONY", "NO_NETWORK", "NOT_ENROLLABLE", "NO_BLOCK_ENTITY",
  "NOT_YOURS_TO_HAND_OVER", "ALREADY_SETTLED_ELSEWHERE", "TOO_MANY", "UNKNOWN_ITEM",
  "NO_ROOM_IN_THE_WORLD", "NOBODY_TAKES_ANYONE_IN", "HELD_ELSEWHERE", "NOBODY_WAITING", "NOT_ON_THE_ROSTER",
  "SITE_TOO_LARGE", "NOTHING_TO_BUILD_WITH", "NOTHING_DRAWN", "PATTERN_FAILED",
  "NO_GROUND", "UNLOADED", "READ_TOO_FAR", "READ_BUDGET", "WRONG_MARK",
  "NO_WATER", "SWITCHED_OFF",
  "NOT_ADMITTED", "ORDERED_HERE",
]);

export const PASSING = new Set([
  "NOT_A_CONTAINER", "NOTHING_TO_TAKE", "NOTHING_CARRIED", "NO_ROOM",
  "PACK_FULL", "NOTHING_FREE",
  "NOT_A_STATION", "NOTHING_TO_WORK_WITH", "STATION_FULL",
  "BEAST_GONE", "NOT_FEEDABLE", "STILL_STANDING",
  "NOTHING_TO_WORK",
  "CELL_NOT_LOADED",
  "NO_PORT", "NOTHING_ARRIVED", "NO_SUPPLY", "REQUEST_REJECTED", "SEAT_TAKEN", "SEAT_GONE",
  "NOTHING_TO_MEND_WITH", "NOTHING_TO_EAT", "NOT_ON_THE_BOOKS",
  "NO_WAY_THERE",
  "NO_STANCE", "ACCESS_DEPENDENCY", "CONTESTED",
  "GROUND_TAKEN", "BEAST_TAKEN", "LINE_ALREADY_OUT", "STATION_BUSY",
  "GOODS_GONE", "NOWHERE_TO_PUT_IT",
  "WAITS_ON_ITSELF", "NO_ONE_FIT", "BEFORE_NOT_DONE", "OCCUPIED", "RUN_MISSED", "LINE_WITHDRAWN",
]);


export const UNASSIGNED_PASSING = new Set([
  "CLAIM_TAKEN", "PACK_FULL", "NO_LICENCE", "NO_TOOL",
]);

export const UNASSIGNED_TRANSIENT = new Set([
  "NO_WAY", "WAY_PENDING", "SAME_WORKER_SPLIT", "NO_FREE_STAND",
  "NO_FREE_HAND", "PACK_BUSY", "WORKER_AWAY",
]);

// Work the schedule left past its horizon on purpose: it waits for a later plan, not for the world, so however long
// it waits it is backlog, not a jam.
export const UNASSIGNED_DEFERRED = new Set([
  "BEYOND_HORIZON",
]);

export const FAILURES = {
  jamRunLength: 5,
  noWayRunLength: 3,
};

export function judgeFailures(beats: ColonyBeat[]): Finding[] {
  return byDriver(beats).flatMap(judgeDriverFailures);
}

function judgeDriverFailures(beats: ColonyBeat[]): Finding[] {
  const found: Finding[] = [];
  const seen: Record<string, number> = {};
  for (const beat of beats) {
    for (const [kind, count] of Object.entries(beat.refused)) {
      seen[kind] = (seen[kind] ?? 0) + count;
    }
  }
  for (const [kind, count] of Object.entries(seen)) {
    if (NEVER_HERE.has(kind)) {
      found.push({
        rule: "failures/never-here",
        severity: "red",
        said: `${kind} occurred ${count} times. This world has no player actions, no third-party changes, and terrain we filled ourselves, `
          + "so this path should be unreachable",
        evidence: { kind, count },
      });
    } else if (!PASSING.has(kind)) {
      found.push({
        rule: "failures/unknown-kind",
        severity: "red",
        said: `${kind} is in no table. Most likely the source added a failure reason and this list didn't keep up - `
          + "either whitelist it, or it should never have happened",
        evidence: { kind, count },
      });
    }
  }

  const reasons = new Set(beats.flatMap((beat) => Object.keys(beat.why)));
  for (const reason of reasons) {
    const run = longestRun(beats, (beat) => (beat.why[reason] ?? 0) > 0);
    if (UNASSIGNED_DEFERRED.has(reason)) {
      const counts = beats.map((beat) => beat.why[reason] ?? 0);
      found.push({
        rule: "failures/deferred",
        severity: "caveat",
        said: `${reason}: median ${median(counts)} tasks per beat (peak ${Math.max(...counts)}) left for a later plan`
          + " - backlog past the plan horizon, read with throughput, not a jam",
        evidence: { reason, median: median(counts), peak: Math.max(...counts), longestRun: run },
      });
      continue;
    }
    if (UNASSIGNED_TRANSIENT.has(reason) && run >= FAILURES.jamRunLength) {
      const nodes = new Set(beats.flatMap(beat => Object.entries(beat.unassignedNodes ?? {})
        .filter(([, why]) => why === reason).map(([id]) => id)));
      const stuck = [...nodes].map(node => ({
        node,
        longestRun: longestRun(beats, beat => beat.unassignedNodes?.[node] === reason),
      })).filter(node => node.longestRun >= FAILURES.jamRunLength);
      if (stuck.length > 0) {
        found.push({
          rule: "failures/jam",
          severity: "red",
          said: `${reason}: ${stuck.length} identical tasks unassigned for at least ${FAILURES.jamRunLength} consecutive beats; `
            + "check their paths, station contention and prerequisites; this does not mean the whole colony is deadlocked",
          evidence: { reason, nodes: stuck },
        });
      } else if (beats.some(beat => (beat.why[reason] ?? 0) > 0 && beat.unassignedNodes === undefined)) {
        found.push({
          rule: "failures/task-identity-missing",
          severity: "caveat",
          said: `${reason} persisted for ${run} consecutive beats, but the log lacks task identity, so we can't tell if the same task kept waiting`,
          evidence: { reason, longestRun: run },
        });
      }
    } else if (!UNASSIGNED_PASSING.has(reason) && !UNASSIGNED_TRANSIENT.has(reason)) {
      found.push({
        rule: "failures/unknown-unassigned",
        severity: "red",
        said: `unassigned reason ${reason} is in no table`,
        evidence: { reason, longestRun: run },
      });
    }
  }
  return found;
}
