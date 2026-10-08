import assert from "node:assert/strict";
import test from "node:test";
import { engineDiagnostics } from "./engine-diagnostics.ts";

test("keeps Post work separate from vanilla MSPT and queue wait separate from solving", () => {
  const report = engineDiagnostics(`
labor heartbeat colonies=1 asksPending=546971 budget=250us(floor250us/ceil8000us) aimd=+0/-200 spent=4573usavg/18192usmax work=43143usavg/229638usmax late=200 over=200/200
solve timing tick=12000 queue_us=1000 messages_us=2000 weave_us=3000 schedule_us=12000000 total_us=12006000 bodies=500 messages=900 nodes=1200
`);
  assert.equal(report.pending_last, 546971);
  assert.equal(report.labor_ms_per_tick_median, 43.143);
  assert.equal(report.labor_ms_per_tick_max, 229.638);
  assert.equal(report.slow_solve_medians_ms.queue, 1);
  assert.equal(report.slow_solve_medians_ms.schedule, 12000);
});

test("missing diagnostic evidence stays unknown", () => {
  const report = engineDiagnostics("ordinary server output");
  assert.equal(report.pending_last, null);
  assert.equal(report.pending_peak, null);
  assert.equal(report.labor_ms_per_tick_median, null);
  assert.equal(report.slow_solve_medians_ms.total, null);
});

test("a complete circuit requires all four stations in order and a return to Alpha", () => {
  const log = (stations: string[]) => stations.map(s => `rail snapshot lines=1 [conducted=true at=${s}]`).join("\n");
  assert.equal(engineDiagnostics(log(["Alpha", "Alpha", "-", "Beta", "Gamma", "Delta"])).rail_complete_laps, 0);
  assert.equal(engineDiagnostics(log(["Alpha", "Beta", "Gamma", "Delta", "Alpha"])).rail_complete_laps, 1);
  assert.equal(engineDiagnostics(log(["Alpha", "Gamma", "Beta", "Delta", "Alpha"])).rail_complete_laps, 0);
});


test("reports failed planning batches even without a completed solve", () => {
  const report = engineDiagnostics(`planning failed; its 8 messages go back on the queue
planning failed; its 53763 messages go back on the queue`);
  assert.equal(report.planning_failures, 2);
  assert.equal(report.failed_batch_peak, 53763);
  assert.equal(report.slow_solve_medians_ms.total, null);
});
