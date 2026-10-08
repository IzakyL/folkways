import { parseNav } from "../shared/heartbeat.ts";

const median = (xs: number[]) => {
  const sorted = [...xs].sort((a, b) => a - b);
  return sorted.length ? (sorted[Math.floor((sorted.length - 1) / 2)] + sorted[Math.floor(sorted.length / 2)]) / 2 : null;
};

export function engineDiagnostics(log: string) {
  const lines = log.split("\n");
  const planningFailures = lines.flatMap(line => {
    const match = line.match(/planning failed; its (\d+) messages go back on the queue/);
    return match ? [Number(match[1])] : [];
  });
  const navigation = lines.map(parseNav).filter(x => x !== null);
  const solves = lines.filter(line => line.includes("solve timing ")).map(line =>
    Object.fromEntries([...line.matchAll(/\b(\w+)=(\d+)/g)].map(m => [m[1], Number(m[2])])));
  const budgets = navigation.flatMap(n => n.budget ? [n.budget] : []);
  const publishes = navigation.flatMap(n => n.publish && n.budget ? [{ ...n.publish, ticks: n.budget.ticks, workAvgMicros: n.budget.workAvgMicros }] : []);
  const stationVisits: string[] = [];
  for (const line of lines) {
    if (!line.includes("rail snapshot lines=1 ")) continue;
    const station = line.match(/ at=(Alpha|Beta|Gamma|Delta)\]/)?.[1];
    if (station && station !== stationVisits.at(-1)) stationVisits.push(station);
  }
  const waiting = lines.flatMap(line => {
    const match = line.match(/ unassignedKinds=\{([^}]*)\}/);
    return match ? [Object.fromEntries([...match[1].matchAll(/([^, ]+)=(\d+)/g)].map(m => [m[1], Number(m[2])]))] : [];
  });
  return {
    navigation,
    planning_failures: planningFailures.length,
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
    slow_solves: solves,
    attached_totals: navigation.reduce((sum, n) => n.attached
      ? { laid: sum.laid + n.attached.laid, closed: sum.closed + n.attached.closed, far: sum.far + n.attached.far }
      : sum, { laid: 0, closed: 0, far: 0 }),
    segment_totals: navigation.reduce((sum, n) => n.segments
      ? { struck: sum.struck + n.segments.struck, walked: sum.walked + n.segments.walked }
      : sum, { struck: 0, walked: 0 }),
    rail_station_visits: stationVisits,
    rail_complete_laps: stationVisits.filter((station, i) => station === "Alpha"
      && stationVisits.slice(i, i + 5).join(",") === "Alpha,Beta,Gamma,Delta,Alpha").length,
    unassigned_kinds_last: waiting.at(-1) ?? null,
    unassigned_kinds_series: waiting,
    slow_solve_medians_ms: Object.fromEntries(["queue", "messages", "weave", "schedule", "total"].map(key =>
      [key, median(solves.map(s => s[`${key}_us`] / 1000))])),
    note: "Navigation/labor windows include setup and measurement. Labor.work runs in ServerTickEvent.Post, after vanilla tickTimes are tallied; vanilla MSPT excludes it. Labor.work timing also excludes the subsequent plugin pulse. Slow solves include only completed solves taking >=100ms including queue wait; their medians are not all-solve medians.",
  };
}
