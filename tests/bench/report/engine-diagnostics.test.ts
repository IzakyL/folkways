import assert from "node:assert/strict";
import test from "node:test";
import { engineDiagnostics } from "./engine-diagnostics.ts";

test("keeps Post work separate from vanilla MSPT and each planning stage apart", () => {
  const heartbeat = "labor heartbeat colonies=1 searches=0 nodes=0 peakSearches/tick=0 peakNodes/tick=0" +
    " slices=0steps/0wall graph=0pts/0edges/0shut/0parts disproved=0pts asksPending=546971" +
    " quests=0found/0denied/0gaveup denied=0farEnd renumbered=0 straightened=0pts" +
    " publish=0usstraighten/0usfreeze/0usmax rambles=0/0edges hops=0" +
    " attached=0laid/0closed/0far segments=0struck/0walked" +
    " slack=500usavg budget=250usavg(ceil8000us) spent=4573usavg/18192usmax" +
    " work=43143usavg/229638usmax skipped=20/200";
  const report = engineDiagnostics(`${heartbeat}
weave timing tick=12000 queue_us=1000 messages_us=2000 weave_us=3000 total_us=6000 bodies=500 messages=900 nodes=1200 put=40 removed=3
schedule timing tick=12000 changes=2 apply_us=500 schedule_us=12000000 total_us=12000500 nodes=1200 tours=300 unassigned=10
`);
  assert.equal(report.pending_last, 546971);
  assert.equal(report.budget_skipped_ticks, 20);
  assert.equal(report.budget_ticks, 200);
  assert.equal(report.navigation[0].budget?.grantedAvgMicros, 250);
  assert.equal(report.labor_ms_per_tick_median, 43.143);
  assert.equal(report.labor_ms_per_tick_max, 229.638);
  assert.equal(report.slow_weave_medians_ms.queue, 1);
  assert.equal(report.slow_weave_medians_ms.total, 6);
  assert.equal(report.slow_schedule_medians_ms.schedule, 12000);
});

test("missing diagnostic evidence stays unknown", () => {
  const report = engineDiagnostics("ordinary server output");
  assert.equal(report.pending_last, null);
  assert.equal(report.pending_peak, null);
  assert.equal(report.budget_skipped_ticks, null);
  assert.equal(report.budget_ticks, null);
  assert.equal(report.labor_ms_per_tick_median, null);
  assert.equal(report.slow_weave_medians_ms.total, null);
  assert.equal(report.slow_schedule_medians_ms.total, null);
});

test("a complete circuit calls at every station the line serves and returns to the first", () => {
  const log = (stations: string[]) => stations.map(s => `rail snapshot lines=1 [conducted=true stations=4 timetable=[] at=${s}]`).join("\n");
  assert.equal(engineDiagnostics(log(["Alpha", "Alpha", "-", "Beta", "Gamma", "Delta"])).rail_complete_laps, 0);
  assert.equal(engineDiagnostics(log(["Alpha", "Beta", "Gamma", "Delta", "Alpha"])).rail_complete_laps, 1);
  assert.equal(engineDiagnostics(log(["Alpha", "Beta", "Gamma", "Delta", "Alpha", "Beta", "Gamma", "Delta", "Alpha"])).rail_complete_laps, 2);
});

test("laps are counted per line when several trains run", () => {
  const log = [["East Works", "North Town"], ["East Town", "North Town"], ["East Works", "North Works"], ["East Town", "North Town"],
    ["East Works", "North Town"]].map(([e, n]) => `rail snapshot lines=2 [conducted=true footings={a=1} at=${e}] [conducted=true at=${n}]`).join("\n");
  const report = engineDiagnostics(log);
  assert.deepEqual(report.rail_laps_by_line, [2, 1]);
  assert.equal(report.rail_complete_laps, 3);
});


test("reports failed weaves and schedules even without a completed one", () => {
  const report = engineDiagnostics(`weaving failed; its 8 messages go back on the queue
weaving failed; its 53763 messages go back on the queue
scheduling failed over 3 changes`);
  assert.equal(report.planning_failures, 3);
  assert.equal(report.failed_batch_peak, 53763);
  assert.equal(report.slow_weave_medians_ms.total, null);
});
