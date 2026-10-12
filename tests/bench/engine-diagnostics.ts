import { parseNav } from "../shared/heartbeat.ts";

const median = (xs: number[]) => {
  const sorted = [...xs].sort((a, b) => a - b);
  return sorted.length ? (sorted[Math.floor((sorted.length - 1) / 2)] + sorted[Math.floor(sorted.length / 2)]) / 2 : null;
};

export function engineDiagnostics(log: string) {
  const lines = log.split("\n");
  const planningFailures = lines.flatMap(line => {
    const match = line.match(/weaving failed; its (\d+) messages go back on the queue/);
    return match ? [Number(match[1])] : [];
  });
  const navigation = lines.map(parseNav).filter(x => x !== null);
  const timings = (stage: string) => lines.filter(line => line.includes(`${stage} timing `)).map(line =>
    Object.fromEntries([...line.matchAll(/\b(\w+)=(\d+)/g)].map(m => [m[1], Number(m[2])])));
  const weaves = timings("weave");
  const schedules = timings("schedule");
  const scheduleFailures = lines.filter(line => line.includes("scheduling failed over ")).length;
  const budgets = navigation.flatMap(n => n.budget ? [n.budget] : []);
  const publishes = navigation.flatMap(n => n.publish && n.budget ? [{ ...n.publish, ticks: n.budget.ticks, workAvgMicros: n.budget.workAvgMicros }] : []);
  // Each "[conducted=..." group of a rail snapshot is one line, in a steady order; its last field names the station
  // its train stands at, if any. Visits are kept per line, a repeat of the same station counted once.
  const visitsByLine: string[][] = [];
  for (const line of lines) {
    if (!line.includes("rail snapshot lines=")) continue;
    line.split("[conducted=").slice(1).forEach((group, i) => {
      const station = group.match(/ at=([^\]]*)\]/)?.[1]?.trim();
      const seen = (visitsByLine[i] ??= []);
      if (station && station !== "-" && station !== seen.at(-1)) seen.push(station);
    });
  }
  // A lap is back at the line's first station having called at every other station it serves on the way.
  const laps = (visits: string[]) => {
    const all = new Set(visits);
    let count = 0, since = new Set<string>();
    for (const station of visits.slice(1)) {
      since.add(station);
      if (station === visits[0]) {
        if (since.size === all.size) count++;
        since = new Set();
      }
    }
    return count;
  };
  const waiting = lines.flatMap(line => {
    const match = line.match(/ unassignedKinds=\{([^}]*)\}/);
    return match ? [Object.fromEntries([...match[1].matchAll(/([^, ]+)=(\d+)/g)].map(m => [m[1], Number(m[2])]))] : [];
  });
  return {
    navigation,
    planning_failures: planningFailures.length + scheduleFailures,
    failed_batch_peak: planningFailures.length ? Math.max(...planningFailures) : 0,
    pending_peak: navigation.length ? Math.max(...navigation.map(n => n.asksPending)) : null,
    pending_last: navigation.at(-1)?.asksPending ?? null,
    labor_ms_per_tick_median: median(budgets.map(b => b.workAvgMicros / 1000)),
    labor_ms_per_tick_max: budgets.length ? Math.max(...budgets.map(b => b.workMaxMicros / 1000)) : null,
    publish_ms_per_tick_median: median(publishes.map(p =>
      (p.straightenMicros + p.freezeMicros) / Math.max(1, p.ticks) / 1000)),
    publish_ms_per_tick_max: publishes.length
      ? Math.max(...publishes.map(p => p.maxMicros / 1000)) : null,
    publish_share_of_labor_pct: median(publishes.map(p => {
      const work = p.workAvgMicros * Math.max(1, p.ticks);
      return work ? (p.straightenMicros + p.freezeMicros) / work * 100 : 0;
    })),
    slow_weaves: weaves,
    slow_schedules: schedules,
    attached_totals: navigation.reduce((sum, n) => n.attached
      ? { laid: sum.laid + n.attached.laid, closed: sum.closed + n.attached.closed, far: sum.far + n.attached.far }
      : sum, { laid: 0, closed: 0, far: 0 }),
    segment_totals: navigation.reduce((sum, n) => n.segments
      ? { struck: sum.struck + n.segments.struck, walked: sum.walked + n.segments.walked }
      : sum, { struck: 0, walked: 0 }),
    rail_station_visits: visitsByLine.length <= 1 ? visitsByLine[0] ?? [] : visitsByLine,
    rail_complete_laps: visitsByLine.reduce((n, v) => n + laps(v), 0),
    rail_laps_by_line: visitsByLine.map(laps),
    unassigned_kinds_last: waiting.at(-1) ?? null,
    unassigned_kinds_series: waiting,
    slow_weave_medians_ms: Object.fromEntries(["queue", "messages", "weave", "total"].map(key =>
      [key, median(weaves.map(s => s[`${key}_us`] / 1000))])),
    slow_schedule_medians_ms: Object.fromEntries(["apply", "schedule", "total"].map(key =>
      [key, median(schedules.map(s => s[`${key}_us`] / 1000))])),
    note: "Navigation/labor windows include setup and measurement. Labor.work runs in ServerTickEvent.Post, after vanilla tickTimes are tallied; vanilla MSPT excludes it. Labor.work timing also excludes the subsequent plugin pulse. Planning runs in two stages on their own threads, the weave (growing the plan from what was reported, timed from when it was queued) and the schedule (laying routes over every change handed on since it last ran); slow ones are only those taking >=100ms, so their medians are not all-run medians.",
  };
}
