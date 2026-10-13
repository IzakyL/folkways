import { screen, tick, world } from "@izakyl/blockwright-minecraft";
import { expect, test } from "@playwright/test";
import { writeFileSync } from "node:fs";
import path from "node:path";
import { foundColonyFast, tryCommand, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { buildRailLine, type RailResult } from "../shared/rail/rail-fixture";
import { crossLoops, crossingsOf, signalCrossings, type LoopBounds } from "../shared/rail/rail-loop";
import { defineTask, javaTask } from "@izakyl/blockwright-minecraft";
import { runColonyTask } from "../shared/colony/colony-tasks";
import { sampleTrain } from "../shared/rail/rail-probe";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { REPO_ROOT, ensureOut, reportPath, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { newRunId } from "../shared/harness/run-id";

const CONFIG_PATH = "blockwright.toml";
const REPORT_PATH = reportPath("e2e", "rail-crossing-report");
const RUN_TICKS = Number(process.env.FOLKWAYS_RAIL_CROSSING_TICKS ?? 6000);
const SAMPLE_TICKS = 100;

// Two loops that cross each other twice on the level, each with its own train and a resident to drive it: the
// trains run their rounds through the crossings for a few minutes, and neither may derail or stand stuck.
test("two colony trains run looping lines that cross on the level", async ({}, testInfo) => {
  test.setTimeout(BUDGET.slow);
  ensureOut("e2e");
  const runId = newRunId();
  let pair: E2EPair | undefined;
  const report: any = { scope: "two looping lines crossing at grade, one train each" };
  try {
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: tracePath("e2e", "rail-crossing-server"), instance: seededServer(`rail-crossing-server-${runId}`) },
      client: { trace: tracePath("e2e", "rail-crossing-client"), instance: `rail-crossing-client-${runId}` },
    });
    const { server, client } = pair;
    const player = await waitForGroundedPlayer(server);
    await screen.dismiss(client);
    await tryCommand(server, `op ${player.name}`);
    await tryCommand(server, `gamemode creative ${player.name}`);
    const playerUuid = (await world.players(server)).find((p) => p.name === player.name)!.uuid;
    const p: Vec = { x: Math.round(player.pos.x), y: Math.round(player.pos.y), z: Math.round(player.pos.z) };

    // Two founding residents, one to drive each train; every vocation is open to them, conducting among them.
    const founding = await foundColonyFast(server, client, player.name, { origin: { x: p.x - 20, y: p.y, z: p.z - 20 }, residentCount: 2 });
    report.founding = { residents: founding.residents };
    await tryCommand(server, `item replace entity ${player.name} inventory.0 from entity ${player.name} weapon.mainhand`);

    // Loop B's top side crosses loop A's east side, and B's west side crosses A's bottom.
    const a: LoopBounds = { minX: p.x + 20, maxX: p.x + 140, minZ: p.z, maxZ: p.z + 200 };
    const b: LoopBounds = { minX: p.x + 80, maxX: p.x + 240, minZ: p.z + 180, maxZ: p.z + 300 };
    const lines: RailResult[] = [];
    lines.push(await buildRailLine(server, client, player.name, playerUuid, { x: a.maxX, y: p.y, z: p.z + 120 }, 80, "inventory.0",
      { cyclic: true, loop: a, passengerSeats: 4, names: ["A1"], anchor: p, stops: [
        { name: "A2", position: { x: p.x + 100, y: p.y, z: a.minZ - 1 }, reverse: true, axis: "x", side: -1 },
        { name: "A3", position: { x: a.minX - 1, y: p.y, z: p.z + 60 }, reverse: true, side: -1 },
        { name: "A4", position: { x: p.x + 60, y: p.y, z: a.maxZ + 1 }, reverse: false, axis: "x", side: 1 },
      ] }));
    lines.push(await buildRailLine(server, client, player.name, playerUuid, { x: b.maxX, y: p.y, z: p.z + 240 }, 40, "inventory.0",
      { cyclic: true, loop: b, passengerSeats: 4, names: ["B1", "B2", "B3", "B4"], anchor: p,
        afterTrack: () => crossLoops(server, b, [a], p.y) }));
    report.lines = lines.map((l) => ({ ok: l.ok, assembled: l.assembled, scheduled: l.scheduled, handed_over: l.handedOver,
      carriage: l.carriageUuid, error: l.error, crossings: l.steps.after_track }));
    for (const l of lines) expect(l.ok, `the line should be built and handed over: ${JSON.stringify(l.steps).slice(0, 600)}`).toBe(true);

    report.signals = await signalCrossings(server, crossingsOf(b, [a]), p.y);
    report.graph = await runColonyTask(server, SIGNAL_POINTS, {});
    expect(report.graph.signals, `Create should register the crossing signals on its track graph: ${JSON.stringify(report.graph)}`).toBeGreaterThan(0);

    // Each resident walks over to a train's platform.
    const crew = (await world.entities(server, { types: ["folkways:resident"] })).entities ?? [];
    expect(crew.length, "the founding should leave two residents").toBe(2);
    for (const [i, l] of lines.entries()) {
      const at = { x: l.stationA.x + 3, y: p.y, z: l.stationA.z + 4 };
      await tryCommand(server, `tp ${crew[i].uuid} ${at.x + 0.5} ${at.y} ${at.z + 0.5}`);
      report[`driver_${i}`] = at;
    }
    await tryCommand(server, `gamemode spectator ${player.name}`);

    const samples: any[] = [];
    const visited = lines.map(() => new Set<string>());
    for (let t = 0; t < RUN_TICKS; t += SAMPLE_TICKS) {
      await tick.sprint(server, SAMPLE_TICKS);
      const row = await Promise.all(lines.map((l) => sampleTrain(server, l.carriageUuid!)));
      row.forEach((s, i) => { if (s.station) visited[i].add(s.station); });
      const held = await runColonyTask(server, SIGNAL_POINTS, {});
      report.held_at_signal = (report.held_at_signal ?? 0) + Number(held.waiting ?? 0);
      samples.push({ t: t + SAMPLE_TICKS, waiting: held.waiting, trains: row.map((s) => ({ x: Math.round(s.x), z: Math.round(s.z), speed: s.speed,
        derailed: s.derailed, station: s.station, entry: s.schedule_entry })) });
      if (row.some((s) => s.derailed)) break;
    }
    report.samples = samples;
    report.visited = visited.map((v) => [...v]);
    const last = samples.at(-1)?.trains ?? [];
    expect(last.some((s: any) => s.derailed), `no train may derail at the crossings: ${JSON.stringify(samples.slice(-3))}`).toBe(false);
    for (const [i, v] of visited.entries()) {
      expect(v.size, `train ${i} should call at all four of its stations: ${[...v]}`).toBe(4);
    }
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    const body = JSON.stringify(report, null, 2);
    writeFileSync(path.join(REPO_ROOT, REPORT_PATH), body);
    testInfo.attach("rail-crossing-report", { body, contentType: "application/json" });
    await pair?.teardown(testInfo);
  }
});

// Signal points on Create's track graphs, and how many trains stand held at one right now.
const SIGNAL_POINTS = defineTask<Record<string, never>, Record<string, any>>({
  name: "folkways.e2e.signal-points",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["com.simibubi.create.Create", "com.simibubi.create.content.trains.graph.EdgePointType"],
    body: `
int signals = 0, graphs = 0, waiting = 0;
for (var graph : Create.RAILWAYS.trackNetworks.values()) { graphs++; signals += graph.getPoints(EdgePointType.SIGNAL).size(); }
for (var train : Create.RAILWAYS.trains.values()) if (train.navigation.waitingForSignal != null) waiting++;
return Map.of("ok", true, "graphs", graphs, "signals", signals, "waiting", waiting);
`,
  }),
});
