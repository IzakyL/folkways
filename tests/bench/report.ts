import { writeFileSync } from "node:fs";
import path from "node:path";

const escape = (value: unknown) => String(value ?? "—").replace(/[&<>"']/g,
  c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);

const fixed = (value: unknown) => typeof value === "number" ? value.toFixed(2) : "?";

export function writeBenchViewer(report: any, jsonPath: string) {
  const time = report.analysis?.resident_time ?? {};
  const coverage = report.plugin_coverage?.plugins ?? [];
  const population = report.setup?.population_breakdown;
  const mix = population ? `<p>Humans ${escape(population.humans)} · metal golems ${escape(population.metal)} · humanoid golems ${escape(population.humanoid)} · dog golems ${escape(population.dog)}; alive and loaded ${escape(population.loaded_alive)}, roster ${escape(population.roster)}.</p>` : "";
  const stats = [
    ["Active residents", report.setup?.population], ["Humans", report.setup?.human_population ?? report.setup?.population], ["Golems", report.setup?.integrations?.golems?.uuids?.length],
    ["Rounds completed", `${report.samples?.length ?? 0}/${report.completion?.requested_rounds ?? "?"}`],
    ["Server tick, full (incl. Post)", `mean ${fixed(report.analysis?.tick_cost?.mspt_full?.mean)} · p95 ${fixed(report.analysis?.tick_cost?.mspt_full?.p95)} · max ${fixed(report.analysis?.tick_cost?.mspt_full?.max)} ms`],
    ["Ticks over 50 ms", `${report.analysis?.tick_cost?.mspt_full?.over_50 ?? "?"}/${report.perf?.tick_meter?.ticks ?? "?"} · headroom at p50 ${report.analysis?.tick_cost?.headroom_pct_at_p50 ?? "?"}%`],
    ["Post phase (Folkways labor)", `mean ${fixed(report.analysis?.tick_cost?.mspt_post?.mean)} · p95 ${fixed(report.analysis?.tick_cost?.mspt_post?.p95)} ms`],
    ["Vanilla tick median (excl. Post)", `${Number(report.analysis?.tick_cost?.mspt_vanilla_median ?? 0).toFixed(2)} ms`],
    ["Folkways labor loop median of window means", `${report.analysis?.engine?.labor_ms_per_tick_median?.toFixed(2) ?? "?"} ms`],
    ["Graph publish median (linearize + freeze)", `${report.analysis?.engine?.publish_ms_per_tick_median?.toFixed(2) ?? "?"} ms/tick · share of labor loop ${report.analysis?.engine?.publish_share_of_labor_pct?.toFixed(1) ?? "?"}%`],
    ["Peak pending pathfinding", report.analysis?.engine?.pending_peak ?? "?"],
    ["Complete rail laps", report.analysis?.engine?.rail_complete_laps ?? "?"],
    ["Throughput", `${report.analysis?.labor?.throughput_per_1000_ticks ?? "?"} nodes / 1000 ticks`],
    ["Heartbeat standing", Object.entries(report.analysis?.labor?.standing_share ?? {})
      .map(([state, share]) => `${state} ${Math.round(100 * Number(share))}%`).join(" · ") || "?"],
    ["Deferred past horizon", `median ${report.analysis?.labor?.deferred?.median ?? "?"} · peak ${report.analysis?.labor?.deferred?.peak ?? "?"}`],
    ["Sampled idle rate (incl. stalled)", `${time.idle_pct ?? "?"}%`],
    ["Construction", `${report.final_construction?.built ?? "?"}/${report.final_construction?.total ?? "?"}`],
  ];
  const movie = report.video?.overview
    ? '<video controls preload="metadata" poster="../film/overview.png" src="../film/large-colony-overview.mp4"></video><p>8x overview · <a href="../film/large-colony-realtime.mp4">raw footage</a> · <a href="../film/overview.png">panorama</a></p>'
    : '<p>No video was produced this run.</p>';
  const html = `<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Large colony · ${escape(report.generated_at)}</title><style>
body{margin:0;background:#101719;color:#e9eeee;font:16px/1.6 system-ui,sans-serif}main{max-width:1240px;margin:auto;padding:28px}
a{color:#8bd3b1}video{width:100%;max-height:78vh;background:#050909}h1{font-size:26px;margin:0}p{color:#b8c9c7}
.stats{display:flex;gap:12px;flex-wrap:wrap;margin:24px 0}.stat{padding:12px 20px;background:#1c292c;border-radius:8px}.stat strong{display:block;font-size:22px}
table{border-collapse:collapse;width:100%;margin:20px 0}td,th{text-align:left;padding:10px;border-bottom:1px solid #33474b}details{margin:16px 0}pre{white-space:pre-wrap;overflow-wrap:anywhere}li{margin:8px 0}
</style><main><h1>Large colony · run log</h1><p>${escape(report.generated_at)} · ${escape(report.completion?.status)} · ${escape(report.completion?.health)} · <a href="large-colony.json">full JSON</a></p>
${movie}${mix}<div class="stats">${stats.map(([name, value]) => `<div class="stat">${escape(name)}<strong>${escape(value)}</strong></div>`).join("")}</div>
<p>A plugin marked "observed" has real evidence of activity; it does not mean all its features pass. The scene is fixed at daytime and recording overhead is included in the measurements; numbers from the old set can't be compared causally.</p>
<table><thead><tr><th>Plugin</th><th>Activity evidence</th><th>Completed task types</th></tr></thead><tbody>${coverage.map((p: any) => `<tr><td>${escape(p.plugin)}</td><td>${p.status === "observed" ? "observed" : "not observed"}</td><td>${Object.entries(p.completed).map(([key, n]) => `${escape(key)} × ${escape(n)}`).join("<br>") || "see resident / inventory / ride samples"}<details><summary>Per-feature evidence</summary><pre>${escape(JSON.stringify(p.feature_checks ?? {}, null, 2))}</pre></details></td></tr>`).join("")}</tbody></table>
<details><summary>Rail and engine timings</summary><pre>${escape(JSON.stringify({rail: report.analysis?.rail, engine: report.analysis?.engine}, null, 2))}</pre></details>
<details open><summary>Diagnostics</summary><ul>${(report.diagnostics ?? []).map((d: string) => `<li>${escape(d)}</li>`).join("") || "<li>No diagnostics</li>"}</ul></details>
<details open><summary>Run caveats</summary><ul>${(report.caveats ?? []).map((d: string) => `<li>${escape(d)}</li>`).join("")}</ul></details>
<details><summary>Measurement and coverage</summary><pre>${escape(JSON.stringify({ environment: report.environment, limitations: report.plugin_coverage?.limitations, video: report.video }, null, 2))}</pre></details>
</main></html>`;
  const file = path.join(path.dirname(jsonPath), "index.html");
  writeFileSync(file, html);
  return file;
}
