import { screen, tick, world } from "@izakyl/blockwright-minecraft";
import { expect, test } from "@playwright/test";
import { writeFileSync } from "node:fs";
import path from "node:path";
import { foundColonyFast, tryCommand, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { buildRailLine } from "../shared/rail/rail-fixture";
import { sampleTrain } from "../shared/rail/rail-probe";
import { drawRailway, type Point } from "../shared/rail/rail-drawn";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { REPO_ROOT, ensureOut, reportPath, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { newRunId } from "../shared/harness/run-id";

const REPORT_PATH = reportPath("e2e", "rail-drawn-report");
const RUN_TICKS = Number(process.env.FOLKWAYS_RAIL_DRAWN_TICKS ?? 6000);
const SAMPLE_TICKS = 100;

// A loop drawn with the railway pattern, an octagon of straights and 45° diagonals in four drawings, then given its
// stations, a car and a timetable on track already down: the train calls at all three stops, one of them at a
// station on a southbound straight and one on an eastbound one.
test("a colony train runs a loop drawn with the railway pattern", async ({}, testInfo) => {
  test.setTimeout(BUDGET.slow);
  ensureOut("e2e");
  const runId = newRunId();
  let pair: E2EPair | undefined;
  const report: any = { scope: "an octagonal loop drawn by the railway pattern, stations put on the laid track" };
  try {
    pair = await launchPair({
      config: "blockwright.toml",
      server: { trace: tracePath("e2e", "rail-drawn-server"), instance: seededServer(`rail-drawn-server-${runId}`) },
      client: { trace: tracePath("e2e", "rail-drawn-client"), instance: `rail-drawn-client-${runId}` },
    });
    const { server, client } = pair;
    const player = await waitForGroundedPlayer(server);
    await screen.dismiss(client);
    await tryCommand(server, `op ${player.name}`);
    await tryCommand(server, `gamemode creative ${player.name}`);
    const playerUuid = (await world.players(server)).find((p) => p.name === player.name)!.uuid;
    const p: Vec = { x: Math.round(player.pos.x), y: Math.round(player.pos.y), z: Math.round(player.pos.z) };
    const founding = await foundColonyFast(server, client, player.name, { origin: { x: p.x - 30, y: p.y, z: p.z - 30 }, residentCount: 1 });
    report.founding = { residents: founding.residents };
    await tryCommand(server, `item replace entity ${player.name} inventory.0 from entity ${player.name} weapon.mainhand`);

    // North up the east side, west along the top, south down the west side, east along the bottom; corners cut at 45°.
    const o = { x: p.x + 160, z: p.z };
    const at = (x: number, z: number): Point => ({ x: o.x + x, y: p.y - 1, z: o.z + z });
    const pieces = [
      [at(40, 0), at(40, -40), at(0, -80), at(-30, -80)],
      [at(-30, -80), at(-60, -80), at(-100, -40), at(-100, 0)],
      // One seam halfway along a diagonal: the pieces meet corner to corner there.
      [at(-100, 0), at(-100, 40), at(-60, 80), at(0, 80), at(20, 60)],
      [at(20, 60), at(40, 40), at(40, 1)],
    ];
    await tryCommand(server, `forceload add ${o.x - 120} ${o.z - 100} ${o.x + 60} ${o.z + 100}`);
    report.drawn = await drawRailway(server, pieces, "octagon");
    expect(report.drawn.ok, `the loop should be drawn whole: ${JSON.stringify(report.drawn).slice(0, 800)}`).toBe(true);

    const y = p.y;
    const line = await buildRailLine(server, client, player.name, playerUuid, { x: o.x + 40, y, z: o.z - 24 }, 0, "inventory.0", {
      cyclic: true, laid: true, passengerSeats: 4, names: ["East"], anchor: p, stops: [
        { name: "West", position: { x: o.x - 101, y, z: o.z + 10 }, reverse: true, axis: "z", side: -1 },
        { name: "South", position: { x: o.x - 14, y, z: o.z + 81 }, reverse: false, axis: "x", side: 1 },
      ] });
    report.line = { ok: line.ok, assembled: line.assembled, scheduled: line.scheduled, handed_over: line.handedOver, error: line.error, steps: line.steps };
    expect(line.ok, `the line should be built and handed over: ${JSON.stringify(line.steps).slice(0, 800)}`).toBe(true);

    const crew = (await world.entities(server, { types: ["folkways:resident"] })).entities ?? [];
    await tryCommand(server, `tp ${crew[0].uuid} ${line.stationA.x + 3.5} ${y} ${line.stationA.z + 4.5}`);
    await tryCommand(server, `gamemode spectator ${player.name}`);
    const visited = new Set<string>();
    // The stations in the order the train calls, so a full round (back to East, over the diagonal seam) shows.
    const calls: string[] = [];
    const samples: any[] = [];
    for (let t = 0; t < RUN_TICKS; t += SAMPLE_TICKS) {
      await tick.sprint(server, SAMPLE_TICKS);
      const s = await sampleTrain(server, line.carriageUuid!);
      if (s.station) visited.add(s.station);
      if (s.station && calls[calls.length - 1] !== s.station) calls.push(s.station);
      samples.push({ t: t + SAMPLE_TICKS, x: Math.round(s.x), z: Math.round(s.z), speed: s.speed, derailed: s.derailed, station: s.station });
      if (s.derailed || calls.length >= 4) break;
    }
    report.samples = samples;
    report.visited = [...visited];
    report.calls = calls;
    expect(samples.some((s) => s.derailed), `the train may not derail: ${JSON.stringify(samples.slice(-3))}`).toBe(false);
    expect(visited.size, `the train should call at all three stations: ${[...visited]}`).toBe(3);
    expect(calls.slice(0, 4), "a whole round, back over the diagonal seam to where it began").toEqual(["East", "West", "South", "East"]);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    const body = JSON.stringify(report, null, 2);
    writeFileSync(path.join(REPO_ROOT, REPORT_PATH), body);
    testInfo.attach("rail-drawn-report", { body, contentType: "application/json" });
    await pair?.teardown(testInfo);
  }
});
