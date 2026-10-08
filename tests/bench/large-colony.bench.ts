import { samplePathQueue, samplePathFlow } from "./path-probe";
import { engineDiagnostics } from "./engine-diagnostics";
import { sampleTrain } from "./rail-probe";
import { populationSlots } from "./population";
import { safeSpawnSites } from "./spawn-sites";
import { snapshotBodies } from "./body-snapshot";
import { writeBenchViewer } from "./report";
import { coverageOf } from "./coverage";
import { runColonyTask } from "../e2e/colony-tasks";
import { COLONY_PACKS } from "../shared/integration-tasks";
import { setupIntegrations, sampleIntegrations } from "./integrations";
import { startOverview, finishOverview, heroShots, type Shot } from "./video";
import { raiseTown, railBed, layRailBed, type Town } from "./raise";
import { installTickMeter, readTicks, tickCursor, TICK_METER_NOTE, type TickWindow } from "./tick-meter";
import { buildLayout, buildColonySpec, summarizeLayout, farHutBlocks, validateFixture, countsOf, materialsOf, DEMANDS, COLONY_ID, type Scale, type Layout } from "./fixture";
import { BREED, CULL } from "./town-build";
import { launchPair, type E2EPair } from "../shared/pair";
import { test } from "@playwright/test";
import {
  errorSummary,
} from "@izakyl/blockwright-client";
import { defineTask, javaTask, reflect, tick, world, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { copyFileSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import {
  commandPos,
  countResidents,
  isColonyMember,
  waitForGroundedPlayer,
  type Vec,
} from "../e2e/colony-founding";
import { doingKind } from "../e2e/resident-probe";
import { isNavLine, parseColony, type ColonyBeat } from "../shared/heartbeat";
import { laborShare } from "./labor-share";
import { countInResidentPacks, readResidentPack } from "../e2e/world-ui";
import {
  judgeConservation, judgeFailures, judgeUtilisation, type Finding, type Tally,
} from "./judgements";

import {
  newUuids,
  uuidToSnbt,
  writeColonyDat,
  colonyDatPath,
  type BlueprintBlockSpec,
} from "../e2e/colony-nbt";
import { colonyRegistry, memberCellCount, zoneCount } from "../e2e/zone-tools";
import { buildRailLine, conductorAboard, type RailResult } from "../e2e/rail-fixture";
import { REPO_ROOT, ensureOut, outAbs, tracePath } from "../out-paths";
import { seededOffThreadServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { tryCommand, introspect } from "../shared/bw-helpers";
import { newRunId } from "../shared/run-id";

const CONFIG = "blockwright.toml#bench";

const RAIL_ENABLED = process.env.FOLKWAYS_LC_RAIL !== "0";
const RAIL_LINE = Number(process.env.FOLKWAYS_LC_RAIL_LINE ?? 150);

const POPULATION = populationSlots(Number(process.env.FOLKWAYS_LC_RESIDENTS ?? 500));
const SCALE: Scale = {
  residents: POPULATION.total,
  humans: POPULATION.humans,
  furnaces: 36,
  blastFurnaces: 12,
  smokers: 16,
  craftingTables: 56,
  smithingTables: 8,
  stonecutters: 8,
  pens: 24,
  fishSpots: 12,
};

type Trade = { name: string; share: number; priorities: Record<string, number>; tools: string[] };

const ALL_VOCATIONS = [
  "folkways:farming", "folkways:herding", "folkways:building", "folkways:crafting",
  "folkways:hauling", "folkways:fishing", "folkways:conducting", "folkways:dispatching",
] as const;

function trade(name: string, share: number, keen: string[], tools: string[]): Trade {
  const priorities: Record<string, number> = {};
  for (const vocation of keen) priorities[vocation] = 1;
  if (!priorities["folkways:hauling"]) priorities["folkways:hauling"] = 3;
  return { name, share, priorities, tools };
}

const TRADES: Trade[] = [
  trade("farmer", 0.24, ["folkways:farming"], ["minecraft:wooden_hoe", "minecraft:iron_axe"]),
  trade("crafter", 0.3, ["folkways:crafting"], []),
  trade("builder", 0.2, ["folkways:building"], ["minecraft:iron_pickaxe", "minecraft:iron_axe", "minecraft:iron_shovel"]),
  trade("herder", 0.12, ["folkways:herding"], ["minecraft:shears"]),
  trade("fisher", 0.05, ["folkways:fishing"], ["minecraft:fishing_rod"]),
  trade("hauler", 0.06, ["folkways:hauling"], []),
  trade("dispatcher", 0.01, ["folkways:dispatching"], []),
  trade("driver", 0.02, ["folkways:conducting"], []),
];

const UNEQUIPPED_SHARE = 0;

const ROUNDS = Number(process.env.FOLKWAYS_LC_ROUNDS ?? 24);
const ROUND_TICKS = Number(process.env.FOLKWAYS_LC_ROUND_TICKS ?? 500);
const MEASURE_BUDGET_MS = 25 * 60_000;
const realtimePollMs = (ticks: number) => Math.min(200, Math.max(50, Math.round((ticks * 50) / 4)));
const REALTIME_SLOWDOWN_CAP = 40;
const STALL_MS = 30_000;
const CURRENT_SAMPLE = Number(process.env.FOLKWAYS_LC_CURRENT_SAMPLE ?? SCALE.residents);

const ANIMALS = ["sheep", "cow", "chicken", "pig"] as const;

test(`large colony: ${SCALE.residents} residents in a town on real terrain (reflection/NBT fixture, no UI)`, async ({}, testInfo) => {
  const scale = SCALE;
  test.setTimeout(BUDGET.bench);
  ensureOut("bench");
  const runId = newRunId();
  const serverInstance = `large-colony-server-${runId}`;
  const jsonPath = outAbs("bench", "reports", "large-colony.json");

  const report: any = {
    schema_version: "0.3.0",
    generated_at: new Date().toISOString(),
    scale,
    scope: "Folkways whole-colony flow engine measured at real scale in a town raised on generated terrain (round trips / time allocation / furnace parallelism / tick cost / idle rate); fixtures use reflection + NBT, not the real UI",
    environment: { config: CONFIG, heap: "3G", server_view_distance: 20, recording: process.env.FOLKWAYS_LC_VIDEO !== "0" },
    fixture: "reflection+NBT (summon residents / data-merge colony_member / write folkways_colonies.dat + reflect cache.remove reload)",
    setup: {},
    samples: [],
    perf: {},
    analysis: {},
    caveats: [],
  };

  let pair: E2EPair | undefined;
  let server: MinecraftServer;
  let client: MinecraftClient;
  let video: any;

  try {
    pair = await launchPair({
      config: CONFIG,
      server: { trace: tracePath("bench", "large-colony-server"), instance: seededOffThreadServer(serverInstance) },
      client: { trace: tracePath("bench", "large-colony-client"), instance: `large-colony-client-${runId}` },
    });
    ({ server, client } = pair);

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    const playerUuid = (await world.players(server))[0]?.uuid;
    await tryCommand(server, `gamemode survival ${playerName}`);
    await installTickMeter(server);

    report.setup.town = {};
    const town: Town = await raiseTown(server, { x: Math.round(grounded.pos.x), z: Math.round(grounded.pos.z) }, countsOf(scale),
      (key, value) => { report.setup.town[key] = value; });
    const layout = buildLayout(town, scale);
    const origin: Vec = layout.origin;
    report.setup.origin = origin;
    report.setup.fixture_validation = validateFixture(layout);
    report.setup.layout_summary = summarizeLayout(layout);

    const railOrigin: Vec = { x: layout.extent.maxX + 12, y: origin.y, z: town.site.z };
    const loopBounds = { minX: layout.extent.minX - 14, maxX: railOrigin.x,
      minZ: Math.min(railOrigin.z - RAIL_LINE - 32, layout.extent.minZ - 14), maxZ: layout.extent.maxZ + 24 };
    const farDraft: Vec | null = RAIL_ENABLED ? { x: railOrigin.x + 8, y: origin.y, z: railOrigin.z - RAIL_LINE + 2 } : null;
    if (RAIL_ENABLED) {
      const bed = railBed(town.terrain, loopBounds, farDraft,
        [{ minX: railOrigin.x + 3, maxX: railOrigin.x + 11, minZ: railOrigin.z - 2, maxZ: railOrigin.z + 40 }]);
      railOrigin.y = bed.y;
      report.setup.town.rail_bed = { y: bed.y, ...(await layRailBed(server, bed.works)) };
    }
    const farWorksite: Vec | null = farDraft ? { ...farDraft, y: railOrigin.y } : null;
    await forceloadTown(server, layout.extent);
    report.perf_controls = { p0_town_no_colony: await onRealClock(server, () => tickCostAt(server, playerUuid)),
      note_p0: "P0 is measured with the town standing but before any resident, animal or colony data exists." };

    report.setup.world = await stockTown(server, layout);
    report.setup.pen_fences = await probePenFences(server, layout.pens);

    report.setup.membership = await setMembershipAttachments(server, layout);

    const residentUuids = newUuids(POPULATION.humans);
    const spec = buildColonySpec(layout, residentUuids, farWorksite ?? undefined);
    const datInfo = writeColonyDat(REPO_ROOT, serverInstance, spec);
    report.setup.dat = {
      ...datInfo, path: colonyDatPath(REPO_ROOT, serverInstance),
      zones: spec.zones.length, orders: spec.orders.length,
      build_orders: spec.buildOrders?.length ?? 0,
      blueprint_cells: (spec.blueprints ?? []).reduce((sum, b) => sum + b.blocks.length, 0),
      member_cells: spec.members.length,
      bed_claims: spec.bedClaims?.length ?? 0,
    };
    report.setup.reload = await forceReloadColony(server, playerUuid);
    await settle(server, 5);
    report.setup.zones_written = spec.zones.length;
    report.setup.zones_loaded = await zonesInWorld(server).catch((e: unknown) => ({ error: errorSummary(e) }));
    report.setup.member_cells_written = spec.members.length;
    report.setup.member_cells_loaded = await membersInWorld(server).catch((e: unknown) => ({ error: errorSummary(e) }));

    report.setup.animals = await summonAnimals(server, layout);

    let rail: RailResult | null = null;
    if (RAIL_ENABLED) {
      try {
        await tryCommand(
          server,
          `item replace entity ${playerName} inventory.0 with folkways:colony_book[folkways:colony_id=${uuidToSnbt(COLONY_ID)}] 1`,
        );
        rail = await buildRailLine(server, client, playerName, playerUuid, railOrigin, RAIL_LINE, "inventory.0", { cyclic: true, loop: loopBounds, passengerSeats: 20 });
        report.setup.rail = { enabled: true, ok: rail.ok, assembled: rail.assembled, scheduled: rail.scheduled, handed_over: rail.handedOver, carriage: rail.carriageUuid, line: RAIL_LINE, topology: "closed_loop", stations: rail.stations, passenger_seats: rail.passengerSeats, loop_bounds: rail.loopBounds, stationB: rail.stationB, farPlatform: rail.farPlatform, steps: rail.steps };
        if (rail.ok && farWorksite) {
          await tryCommand(server, `fill ${farWorksite.x - 1} ${farWorksite.y} ${farWorksite.z - 1} ${farWorksite.x + 8} ${farWorksite.y + 1} ${farWorksite.z + 8} minecraft:air`);
          await settle(server, 5);
          report.setup.rail.far_worksite = farWorksite;
        } else {
          report.caveats.push(`rail not ready (assembled=${rail.assembled} scheduled=${rail.scheduled} handedOver=${rail.handedOver}): commuting disabled, other measurements proceed as usual.`);
        }
      } catch (e) {
        report.setup.rail = { enabled: true, error: errorSummary(e) };
        report.caveats.push("rail stage failed (isolated, main measurement unaffected): " + errorSummary(e));
      }
    } else {
      report.setup.rail = { enabled: false };
    }

    await world.command(server, "gamerule doDaylightCycle false");
    await world.command(server, "gamerule doWeatherCycle false");
    await world.command(server, "gamerule doMobSpawning false");
    await world.command(server, "weather clear");
    await world.command(server, "time set noon");
    report.setup.daylight = { fixed: true, scope: "Daytime production; sleep and fear are reported as separate coverage gaps." };
    await settle(server, 20);

    await tryCommand(server, "gamerule maxEntityCramming 0");
    const candidates = layout.spawns.map(p => ({ ...p, kind: "human" }));
    for (const g of POPULATION.golems) candidates[g.index].kind = g.kind;
    for (let i = 0; i < residentUuids.length; i++) {
      const name = tradeOf(i).name;
      if (name === "dispatcher") candidates[POPULATION.humanIndices[i]] = {
        x: layout.dispatchOrigin.x + 1 + (i % 5) * 2, y: layout.dispatchOrigin.y, z: layout.dispatchOrigin.z + 6, kind: "human" };
      if (name === "driver") candidates[POPULATION.humanIndices[i]] = {
        x: railOrigin.x + 5 + (i % 3) * 2, y: railOrigin.y, z: railOrigin.z + 10 + Math.floor((i % 10) / 3) * 2, kind: "human" };
    }
    const safeSpawns = await safeSpawnSites(server, candidates);
    report.setup.spawn_validation = safeSpawns;
    layout.spawns.splice(0, layout.spawns.length, ...safeSpawns.positions);
    const colonyIA = uuidToSnbt(COLONY_ID);
    const unequippedUntil = Math.round(UNEQUIPPED_SHARE * POPULATION.humans);
    for (let i = 0; i < residentUuids.length; i++) {
      const trade = tradeOf(i);
      const p = layout.spawns[POPULATION.humanIndices[i]];
      await tryCommand(
        server,
        `summon folkways:resident ${p.x + 0.5} ${p.y} ${p.z + 0.5} `
          + residentNbt(residentUuids[i], colonyIA, trade, i >= unequippedUntil),
      );
    }

    report.setup.clock = await handToRealClock(server);
    await advance(server, 20);
    const population = await countResidents(server);
    report.setup.human_population = population;
    if (population !== POPULATION.humans) {
      report.caveats.push(`only ${population}/${POPULATION.humans} humans settled (summon failed or entities not loaded)`);
    }
    report.setup.trades = TRADE_SEGMENTS.map(({ trade, start, size }) => ({
      name: trade.name, head_count: size, first_index: start, tools: trade.tools,
      keen: Object.entries(trade.priorities).filter(([, p]) => p === 1).map(([v]) => v),
    }));
    report.setup.unequipped = unequippedUntil;

    report.setup.trade_checks = await verifyTrades(server, residentUuids);
    report.perf_controls.note = "P1 (residents with no work) has no matching stage in the reflection/NBT flow: the colony loads with all stations/zones/orders at once, so it is skipped and only the P0/P2 delta is kept";

    report.setup.integrations = await setupIntegrations(server, playerName, COLONY_ID, layout.dispatchOrigin,
      POPULATION.golems.map(g => ({ kind: g.kind, position: layout.spawns[g.index] })));
    const integrations = report.setup.integrations;
    const combined = Array<string>(scale.residents);
    POPULATION.humanIndices.forEach((slot, i) => { combined[slot] = residentUuids[i]; });
    POPULATION.golems.forEach((g, i) => { combined[g.index] = integrations.golems.uuids[i]; });
    const uuids = combined;
    const allBodies = await snapshotBodies(server, COLONY_ID, uuids);
    report.setup.population = allBodies.loaded_alive;
    report.setup.population_breakdown = { total: POPULATION.total, humans: POPULATION.humans,
      metal: POPULATION.metal, humanoid: POPULATION.humanoid, dog: POPULATION.dog,
      loaded_alive: allBodies.loaded_alive, roster: allBodies.roster,
      actual_by_kind: Object.values(allBodies.workers).reduce((counts: any, w: any) => {
        counts[w.kind] = (counts[w.kind] ?? 0) + 1; return counts;
      }, {}) };
    if (allBodies.loaded_alive !== scale.residents || allBodies.roster !== scale.residents
        || uuids.some(id => !allBodies.workers[id])) throw new Error("Total active population does not match requested human/golem composition");
    report.setup.resident_uuids = uuids.length;
    report.perf_controls.p2_full_setup = await tickCostAt(server, uuids[0] ?? playerUuid);

    const holdsThings: Vec[] = [
      ...layout.chests, ...layout.furnaces, ...layout.stonecutters,
      ...layout.tables, ...integrations.containers,
    ];
    report.conservation = { note: TALLY_NOTE };
    await tick.freeze(server, true);
    report.conservation.before = await tallyWorld(server, holdsThings, playerUuid);
    await tick.freeze(server, false);

    if (process.env.FOLKWAYS_LC_VIDEO !== "0") {
      const extent = RAIL_ENABLED ? { minX: loopBounds.minX - 8, maxX: loopBounds.maxX + 14,
        minZ: loopBounds.minZ - 8, maxZ: loopBounds.maxZ + 8 } : layout.extent;
      video = await startOverview(client, server, playerName, extent, origin.y);
      report.video = video;
    }
    const measureStart = Date.now();
    const measureCursor = await tickCursor(server);
    for (let round = 0; round < ROUNDS; round++) {
      if (Date.now() - measureStart > MEASURE_BUDGET_MS) {
        report.caveats.push(`observation loop hit the wall-clock budget (${MEASURE_BUDGET_MS / 60000} min) at round ${round} - stepping plus probe reads exceeded the budget; this alone doesn't mean ticks are too slow.`);
        break;
      }
      const roundCursor = await tickCursor(server);
      const advanced = await advance(server, ROUND_TICKS);
      const ticked = await readTicks(server, roundCursor);
      await tick.freeze(server, true);
      const sample = await takeSample(server, uuids, layout, round, advanced, ticked, rail, farWorksite, playerUuid);
      sample.integrations = await sampleIntegrations(server, integrations);
      noteLostGolems(report, sample, round);
      for (const c of sample.residents) if (c.absent && report.lost_golems?.[c.uuid]) c.lost_golem = true;
      sample.order_outputs = [];
      for (const p of layout.outputChests) sample.order_outputs.push(await probeChest(server, p));
      sample.output_chest = {};
      for (const chest of sample.order_outputs) for (const [item, n] of Object.entries(chest))
        if (typeof n === "number") sample.output_chest[item] = (sample.output_chest[item] ?? 0) + n;
      Object.assign(sample, { path_flow: await samplePathFlow(server) });
      report.samples.push(sample);
      writeFileSync(jsonPath, JSON.stringify(report, null, 2));
    }

    report.perf = await measurePerformance(server, playerUuid ?? uuids[0], measureCursor);
    await tick.freeze(server, true);
    if (video) {
      report.video = await finishOverview(client, video);
      video = undefined;
    }
    report.conservation.after = await tallyWorld(server, holdsThings, playerUuid);

    report.final_construction = {
      ...(await probeConstruction(server, layout.nearWorksite, layout.construction)),
      wall: layout.wall ? await probeConstruction(server, layout.wall.anchor, layout.wall.blocks) : null,
    };
    report.analysis = analyze(report, scale);
    report.analysis.path_queue = await samplePathQueue(server);
    // Show the working settlement after residents have dispersed and the builders have
    // made progress, rather than a frozen population still standing at its spawn marks.
    if (process.env.FOLKWAYS_LC_VIDEO !== "0") {
      report.hero_shots = await heroShots(client, server, playerName, heroViews(layout))
        .catch((error: unknown) => ({ error: errorSummary(error) }));
    }
  } catch (error) {
    pair?.failing(error);
    report.fatal = errorSummary(error);
    report.fatal_full = String(error).slice(0, 2000);
  } finally {
    try {
      if (video) {
        try { report.video = await finishOverview(client, video); }
        catch (error) { report.video = { ...video, error: errorSummary(error) }; report.fatal ??= errorSummary(error); }
        video = undefined;
      }
      report.analysis = report.analysis ?? {};
      report.analysis.claimed_kinds = readClaimCensus(REPO_ROOT, serverInstance);
      report.analysis.labor = laborShare(report.analysis.claimed_kinds?.beats ?? []);
      try {
        const logPath = path.join(REPO_ROOT, ".blockwright", "instances", serverInstance, "logs", "latest.log");
        copyFileSync(logPath, outAbs("bench", "traces", "server.log"));
        report.analysis.engine = engineDiagnostics(readFileSync(logPath, "utf8"));
      } catch (error) { report.caveats.push("Engine log unavailable: " + errorSummary(error)); }
      report.plugin_coverage = coverageOf(report);
      report.findings = judge(report);
      deriveCaveats(report, SCALE);
      if (!report.perf?.tick_meter) report.caveats.push("tick meter unavailable: vanilla MSPT excludes the NeoForge Post labor loop where Folkways runs, so headroom can't be judged from the numbers left in this report.");
      collectDiagnostics(report, SCALE);
      writeFileSync(jsonPath, JSON.stringify(report, null, 2));
      writeBenchViewer(report, jsonPath);
      printSummary(report, jsonPath);
      const measured = (report.samples ?? []).length;
      if (report.fatal || measured !== ROUNDS) {
        throw new Error(`large-colony measurement incomplete: ${report.fatal ?? `only ${measured}/${ROUNDS} rounds completed`}`);
      }
    } catch (error) {
      pair?.failing(error);
      throw error;
    } finally {
      await pair?.teardown(testInfo);
    }
  }
});

function heroViews(layout: Layout): Shot[] {
  const e = layout.extent, y = layout.y, o = layout.origin;
  const mid = (l: Layout["lots"][number]) => ({ x: (l.box.minX + l.box.maxX) / 2, y: l.y, z: (l.box.minZ + l.box.maxZ) / 2 });
  const first = (kind: string) => layout.lots.find((l) => l.kind === kind);
  const close = (name: string, kind: string, up: number, back: number) => {
    const lot = first(kind);
    if (!lot) return [];
    const c = mid(lot);
    const poses = [up + 4, up + 10, up + 16].flatMap(height =>
      [[lot.w / 2 + 8, lot.d + back], [-back, lot.d / 2], [lot.w + back, lot.d / 2],
        [lot.w / 2, -back], [lot.w + back, lot.d + back], [-back, -back]]
        .map(([u, v]) => lot.frame.at(u, height, v)));
    return [{ name, eye: poses[0], alternatives: poses.slice(1), subject: lot.box, target: { ...c, y: c.y + 3 } }];
  };
  return [
    { name: "town", eye: { x: (e.minX + e.maxX) / 2 - 110, y: y + 210, z: (e.minZ + e.maxZ) / 2 + 150 },
      target: { x: (e.minX + e.maxX) / 2, y: y + 5, z: (e.minZ + e.maxZ) / 2 } },
    { name: "plaza", eye: { x: o.x + 22, y: y + 14, z: o.z + 26 }, target: o },
    ...close("forges", "smithy", 14, 12), ...close("lodges", "lodge", 16, 14), ...close("pastures", "paddock", 18, 12),
    ...close("terraces", "field", 20, 14), ...close("gardens", "garden", 14, 10), ...close("quarry", "quarry", 14, 10),
  ];
}

async function forceReloadColony(server: MinecraftServer, playerUuid: string) {
  try {
    const removed = await reflect.invoke(server, {
      target: { kind: "entity", uuid: playerUuid },
      path: "level().getDataStorage().cache",
      method: "remove",
      args: ["folkways_colonies"],
      argTypes: ["java.lang.Object"],
    });
    return { ok: removed?.found === true, ownerType: removed?.ownerType };
  } catch (error) {
    return { ok: false, error: errorSummary(error) };
  }
}

async function forceloadTown(server: MinecraftServer, e: { minX: number; maxX: number; minZ: number; maxZ: number }) {
  for (let x = Math.floor(e.minX / 16); x <= Math.floor(e.maxX / 16); x += 8)
    for (let z = Math.floor(e.minZ / 16); z <= Math.floor(e.maxZ / 16); z += 8)
      await world.command(server, `forceload add ${x * 16} ${z * 16} ${Math.min(Math.floor(e.maxX / 16), x + 7) * 16} ${Math.min(Math.floor(e.maxZ / 16), z + 7) * 16}`);
}

// Fills the town's chests: role chests at the market, the stores by trade, and the builders' yard with what
// the blueprints need.
async function stockTown(server: MinecraftServer, layout: Layout) {
  const fill = async (chest: Vec, entries: Array<[string, number]>) => {
    let slot = 0;
    for (const [item, count] of entries) {
      if (slot >= 27) break;
      await tryCommand(server, `item replace block ${commandPos(chest)} container.${slot++} with ${item} ${count}`);
    }
  };
  const stacksOf = (item: string, n: number) => Array.from({ length: n }, () => [item, 64] as [string, number]);
  const seeds = [...stacksOf("minecraft:wheat_seeds", 4), ...stacksOf("minecraft:carrot", 2), ...stacksOf("minecraft:potato", 2),
    ...stacksOf("minecraft:beetroot_seeds", 1), ...stacksOf("minecraft:pumpkin_seeds", 1), ...stacksOf("minecraft:melon_seeds", 1),
    ...stacksOf("minecraft:sugar_cane", 1), ...stacksOf("minecraft:oak_sapling", 1)];
  const stock: Record<string, Array<[string, number]>> = {
    seed: seeds, log: stacksOf("minecraft:oak_log", 8),
    plank: stacksOf("minecraft:oak_planks", 8), iron: stacksOf("minecraft:raw_iron", 8),
    stone: stacksOf("minecraft:stone", 8), meat: [...stacksOf("minecraft:beef", 2), ...stacksOf("minecraft:porkchop", 2), ...stacksOf("minecraft:mutton", 2), ...stacksOf("minecraft:chicken", 2)],
    feed: [...stacksOf("minecraft:wheat", 4), ...stacksOf("minecraft:carrot", 2), ...stacksOf("minecraft:wheat_seeds", 2)],
    food: [...stacksOf("minecraft:bread", 4), ...stacksOf("minecraft:cooked_beef", 4)],
    fuel: [...stacksOf("minecraft:coal", 6), ...stacksOf("minecraft:charcoal", 2)],
  };
  for (const store of layout.stores) await fill(store.pos, stock[store.role]);
  await fill(layout.seedChest, [...seeds, ...stacksOf("minecraft:wheat_seeds", 13)]);
  await fill(layout.logChest, [...stacksOf("minecraft:oak_log", 20), ...stacksOf("minecraft:spruce_log", 7)]);
  await fill(layout.plankChest, stacksOf("minecraft:oak_planks", 27));
  await fill(layout.smeltChest, stacksOf("minecraft:raw_iron", 27));
  await fill(layout.stoneChest, [...stacksOf("minecraft:stone", 20), ...stacksOf("minecraft:cobblestone", 7)]);
  await fill(layout.meatChest, [
    ...stacksOf("minecraft:beef", 8), ...stacksOf("minecraft:porkchop", 8),
    ...stacksOf("minecraft:mutton", 6), ...stacksOf("minecraft:chicken", 5),
  ]);
  await fill(layout.feedChest, [
    ...stacksOf("minecraft:wheat", 12), ...stacksOf("minecraft:carrot", 8), ...stacksOf("minecraft:wheat_seeds", 7),
  ]);
  await fill(layout.toolChest, [
    ...Array.from({ length: 8 }, () => ["minecraft:wooden_hoe", 1] as [string, number]),
    ...Array.from({ length: 4 }, () => ["minecraft:shears", 1] as [string, number]),
    ...Array.from({ length: 4 }, () => ["minecraft:fishing_rod", 1] as [string, number]),
    ...Array.from({ length: 4 }, () => ["minecraft:iron_pickaxe", 1] as [string, number]),
    ...Array.from({ length: 4 }, () => ["minecraft:iron_axe", 1] as [string, number]),
    ...Array.from({ length: 3 }, () => ["minecraft:iron_shovel", 1] as [string, number]),
  ]);
  await fill(layout.dairyChest, Array.from({ length: 12 }, () => ["minecraft:bucket", 16] as [string, number]));
  await fill(layout.foodChest, [...stacksOf("minecraft:bread", 4), ...stacksOf("minecraft:cooked_beef", 4)]);

  // The yard holds what the lodge and the town wall are made of, the rarest first.
  const need = materialsOf([...layout.construction, ...(layout.wall?.blocks ?? [])]);
  const stacks: Array<[string, number]> = [];
  for (const [item, n] of Object.entries(need).sort((a, b) => a[1] - b[1]))
    for (let left = n; left > 0; left -= 64) stacks.push([item, Math.min(64, left)]);
  const shelved = stacks.slice(0, layout.yardChests.length * 27);
  for (const [i, chest] of layout.yardChests.entries()) await fill(chest, shelved.slice(i * 27, i * 27 + 27));

  return {
    placed: {
      cookers: layout.cookers.length, stonecutters: layout.stonecutters.length, tables: layout.tables.length,
      chests: layout.chests.length, beds: layout.beds.length, campfires: layout.campfires.length,
    },
    yard: { materials: need, stacks: stacks.length, shelved: shelved.length },
  };
}

function penFenceSide(pen: { min: Vec; max: Vec }, fence: Vec): "west" | "east" | "north" | "south" {
  if (fence.x < pen.min.x) return "west";
  if (fence.x > pen.max.x) return "east";
  if (fence.z < pen.min.z) return "north";
  return "south";
}

async function probePenFences(server: MinecraftServer, pens: Layout["pens"]) {
  const gaps: any[] = [];
  const gapPensByAnimal: Record<string, number> = {};
  let probed = 0;
  let intact = 0;
  for (const pen of pens) {
    let missing = 0;
    let read = 0;
    const sides = new Set<string>();
    for (const fence of pen.fences) {
      const blk = await world.block(server, fence).catch(() => null);
      const id = blk?.id;
      if (typeof id !== "string") continue;
      read++;
      if (id !== "minecraft:oak_fence") {
        missing++;
        sides.add(penFenceSide(pen, fence));
      }
    }
    probed += read;
    if (read > 0 && missing === 0) intact++;
    if (missing > 0) {
      gaps.push({ animal: pen.animal, at: { x: pen.min.x, z: pen.min.z }, missing, ring: pen.fences.length, sides: [...sides] });
      gapPensByAnimal[pen.animal] = (gapPensByAnimal[pen.animal] ?? 0) + 1;
    }
  }
  return {
    pens: pens.length,
    intact,
    probed,
    expected: pens.reduce((n, p) => n + p.fences.length, 0),
    gap_pens: gaps.length,
    gap_pens_by_animal: gapPensByAnimal,
    gaps: gaps.slice(0, 8),
    note: "A gap in a pen's fence ring => PastureValidator flags that edge as leaking => the whole pen posts no work. "
      + "Grouped by animal because trades are tied to animals: SHEAR only takes sheep, COLLECT only cows, so if every pen for an animal has gaps, that whole trade is gone.",
  };
}

async function setMembershipAttachments(server: MinecraftServer, layout: Layout) {
  const colonyIA = uuidToSnbt(COLONY_ID);
  const targets: Vec[] = [
    ...layout.chests, ...layout.furnaces, ...layout.stonecutters, ...layout.tables, ...layout.beds,
  ];
  let ok = 0;
  const failures: any[] = [];
  const refused: any[] = [];
  for (const t of targets) {
    const wrote = await tryCommand(
      server,
      `data merge block ${commandPos(t)} {"neoforge:attachments":{"folkways:colony_member":${colonyIA}}}`,
    );
    if (wrote?.success === false) {
      refused.push({ pos: t, say: (wrote?.output?.[0] ?? "").slice(0, 80) });
    }
  }
  await settle(server, 3);
  const probes = [
    layout.chests[0], layout.chests[layout.chests.length - 1],
    layout.furnaces[0], layout.stonecutters[0], layout.tables[0], layout.beds[0],
  ].filter(Boolean);
  for (const t of probes) {
    try {
      if (await isColonyMember(server, t)) ok++;
      else failures.push({ pos: t, member: false });
    } catch (e) {
      failures.push({ pos: t, error: errorSummary(e) });
    }
  }
  return {
    targets: targets.length,
    sampled_ok: ok,
    sampled: probes.length,
    failures,
    refused_writes: refused.length,
    refused: refused.slice(0, 8),
  };
}

async function summonAnimals(server: MinecraftServer, layout: Layout) {
  const out: any[] = [];
  for (const pen of layout.pens) {
    for (let i = 0; i < pen.heads; i++) {
      const state = pen.animal === "sheep" ? "{Age:0,Sheared:0b}" : "{Age:0}";
      await tryCommand(server, `summon minecraft:${pen.animal} ${pen.center.x + 0.5} ${pen.center.y} ${pen.center.z + 0.5} ${state}`);
    }
    out.push({ animal: pen.animal, at: pen.center, heads: pen.heads, target: pen.target });
  }
  await settle(server, 5);
  return out;
}

const TRADE_SEGMENTS: Array<{ trade: Trade; start: number; size: number }> = (() => {
  const total = POPULATION.humans;
  const crafter = TRADES.findIndex((t) => t.name === "crafter");
  const sizes = TRADES.map((t) => Math.round(t.share * total));
  sizes[crafter] += total - sizes.reduce((a, b) => a + b, 0);
  let start = 0;
  return TRADES.map((trade, i) => {
    const segment = { trade, start, size: sizes[i] };
    start += sizes[i];
    return segment;
  });
})();

function tradeOf(index: number): Trade {
  for (const segment of TRADE_SEGMENTS) {
    if (index < segment.start + segment.size) return segment.trade;
  }
  return TRADES[TRADES.length - 1];
}

function residentNbt(uuid: string, colonyIA: string, trade: Trade, equipped: boolean): string {
  const priorities = Object.entries(trade.priorities).map(([vocation, p]) => `"${vocation}":${p}`).join(",");
  const withheld = ALL_VOCATIONS.filter((v) => !trade.priorities[v]).map((v) => `"${v}"`).join(",");
  const items = equipped
    ? trade.tools.map((tool, slot) => `{Slot:${slot}b,Stack:{id:"${tool}",count:1}}`).join(",")
    : "";
  return `{UUID:${uuidToSnbt(uuid)},PersistenceRequired:1b,`
    + `"neoforge:attachments":{"folkways:body_state":{Colony:${colonyIA},Pack:{Items:[${items}]}},`
    + `"folkways:resident_capabilities":`
    + `{withheld:[${withheld}],keenness:{${priorities}}}}}`;
}

const ATTACHMENTS_KEY = "neoforge:attachments";
const CAPABILITIES_KEY = "folkways:resident_capabilities";

async function verifyTrades(server: MinecraftServer, uuids: string[]) {
  const checks: any[] = [];
  for (const { trade, start, size } of TRADE_SEGMENTS) {
    if (size <= 0) continue;
    const index = start + Math.floor(size / 2);
    const uuid = uuids[index];
    if (!uuid) continue;
    const entry: any = { trade: trade.name, index, uuid, tools_expected: trade.tools };

    const holder: any = await world
      .entity(server, { uuid }, { nbtPath: ATTACHMENTS_KEY })
      .catch((e: unknown) => ({ $probe_error: errorSummary(e) }));
    entry.attachments_seen = holder?.nbt && typeof holder.nbt === "object" ? Object.keys(holder.nbt) : [];
    entry.attachments_error = holder?.$probe_error ?? null;

    const caps: any = await world
      .entity(server, { uuid }, { nbtPath: `${ATTACHMENTS_KEY}.${CAPABILITIES_KEY}.keenness` })
      .catch((e: unknown) => ({ $probe_error: errorSummary(e) }));
    const table = caps?.nbt;
    entry.priorities = table ?? caps?.$probe_error ?? null;
    const readPriorities = table != null && typeof table === "object" && Object.keys(table).length > 0;
    entry.priorities_ok = !readPriorities
      ? null
      : Object.entries(trade.priorities).every(([v, p]) => Number((table as any)[v]) === p);

    const pack = await readResidentPack(server, uuid);
    entry.pack_read = pack.read;
    entry.pack_source = pack.read ? "body_state.Pack" : null;
    entry.pack_error = pack.error;
    entry.carried = pack.carried;
    entry.tools_ok = !entry.pack_read || trade.tools.length === 0
      ? null
      : trade.tools.every((tool) => entry.carried.includes(tool));
    checks.push(entry);
  }
  return checks;
}

async function zonesInWorld(server: MinecraftServer): Promise<number> {
  return zoneCount(server, await colonyRegistry(server));
}

async function membersInWorld(server: MinecraftServer): Promise<number> {
  return memberCellCount(server, await colonyRegistry(server));
}

// Read-only: each furnace's burn and cook progress and its slots, by reflection over AbstractFurnaceBlockEntity.
const FURNACES = defineTask<{ cells: number[] }, Record<string, any>>({
  name: "folkways.bench.furnaces",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "java.lang.reflect.Field",
      "net.minecraft.core.BlockPos",
      "net.minecraft.core.registries.BuiltInRegistries",
      "net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity",
    ],
    body: `
var level = ctx.server().overworld();
var cells = Args.of(ctx).list("cells");
if (cells == null) throw Fail.invalid("args.cells is required (flat x, y, z triples)");
Field lit = AbstractFurnaceBlockEntity.class.getDeclaredField("litTime"); lit.setAccessible(true);
Field cooking = AbstractFurnaceBlockEntity.class.getDeclaredField("cookingProgress"); cooking.setAccessible(true);
Field total = AbstractFurnaceBlockEntity.class.getDeclaredField("cookingTotalTime"); total.setAccessible(true);
var out = new ArrayList<Object>();
for (int i = 0; i + 2 < cells.size(); i += 3) {
  var pos = new BlockPos(((Number) cells.get(i)).intValue(), ((Number) cells.get(i + 1)).intValue(), ((Number) cells.get(i + 2)).intValue());
  var entry = new LinkedHashMap<String,Object>();
  entry.put("pos", Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ()));
  if (!(level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace)) {
    entry.put("error", "no furnace block entity");
    out.add(entry);
    continue;
  }
  entry.put("litTime", read(lit, furnace));
  entry.put("cookingProgress", read(cooking, furnace));
  entry.put("cookingTotalTime", read(total, furnace));
  var slots = new LinkedHashMap<String,Object>();
  for (int slot = 0; slot < furnace.getContainerSize(); slot++) {
    var stack = furnace.getItem(slot);
    if (stack.isEmpty()) continue;
    slots.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), (a, b) -> (Integer) a + (Integer) b);
  }
  entry.put("slots", slots);
  out.add(entry);
}
return Map.of("ok", true, "furnaces", out);
`,
    members: `
static int read(Field field, Object owner) throws Exception { return field.getInt(owner); }
`,
  }),
});

async function probeFurnaces(server: MinecraftServer, furnaces: Vec[]) {
  try {
    const result = await runColonyTask(server, FURNACES, { cells: furnaces.flatMap((p) => [p.x, p.y, p.z]) });
    return result.furnaces as any[];
  } catch (error) {
    return furnaces.map((pos) => ({ pos, error: errorSummary(error) }));
  }
}

async function tallyWorld(server: MinecraftServer, containers: Vec[], playerUuid: string): Promise<Tally> {
  const tally: Tally = { packs: {}, containers: {}, ground: {}, player: {}, capped: false };

  const packs = await runColonyTask(server, COLONY_PACKS, { colony_id: COLONY_ID });
  if (packs.read !== packs.expected) tally.capped = true;
  Object.assign(tally.packs, packs.totals);

  for (const pos of containers) {
    const held = await world.container(server, pos).catch(() => null);
    for (const slot of held?.slots ?? []) {
      const id = slot?.item?.id ?? slot?.id;
      if (typeof id !== "string" || id === "minecraft:air") continue;
      const count = Number(slot?.item?.count ?? slot?.count ?? 1);
      tally.containers[id] = (tally.containers[id] ?? 0) + count;
    }
  }

  // The entity query takes at most 1000; `limited` marks the tally capped when there are more.
  const GROUND_LIMIT = 1_000;
  const loose = await world
    .entities(server, { dimension: "minecraft:overworld", types: ["minecraft:item"], nbtPath: "Item", limit: GROUND_LIMIT })
    .catch(() => ({ entities: [] as world.EntityJson[], limited: false }));
  const dropped: any[] = loose.entities ?? [];
  if (loose.limited || dropped.length >= GROUND_LIMIT) tally.capped = true;
  for (const one of dropped) {
    const id = one?.nbt?.id;
    if (typeof id !== "string") continue;
    tally.ground[id] = (tally.ground[id] ?? 0) + Number(one?.nbt?.count ?? 1);
  }

  const carrier: any = await world.entity(server, { uuid: playerUuid }, { inventory: true }).catch(() => null);
  for (const slot of carrier?.inventory?.slots ?? []) {
    const id = slot?.item?.id ?? slot?.id;
    if (typeof id !== "string" || id === "minecraft:air") continue;
    tally.player[id] = (tally.player[id] ?? 0) + Number(slot?.item?.count ?? slot?.count ?? 1);
  }
  return tally;
}

async function probeChest(server: MinecraftServer, pos: Vec) {
  try {
    const t = await introspect(server, { target: { kind: "blockentity", position: pos }, limits: { maxDepth: 3, maxItems: 30, maxFields: 30, maxNodes: 6000 } });
    const items = t?.tree?.$fields?.items;
    return countItems(Array.isArray(items) ? items : []);
  } catch (error) {
    return { $error: errorSummary(error) } as any;
  }
}

async function probeConstruction(server: MinecraftServer, anchor: Vec, blocks: BlueprintBlockSpec[], stride = 1) {
  let built = 0;
  let looked = 0;
  for (let i = 0; i < blocks.length; i += stride) {
    const b = blocks[i];
    const pos = { x: anchor.x + b.offset.x, y: anchor.y + b.offset.y, z: anchor.z + b.offset.z };
    const blk: any = await world.block(server, pos).catch(() => ({}) as any);
    looked++;
    if (String(blk?.id ?? "") === b.block) built++;
  }
  return { built, sampled: looked, total: blocks.length, stride };
}

async function probeShearables(server: MinecraftServer) {
  const result = await world
    .entities(server, { dimension: "minecraft:overworld", types: ["minecraft:sheep"], limit: 1000, nbtPath: "Sheared" })
    .catch(() => ({ entities: [] as world.EntityJson[] }));
  const sheep: any[] = result.entities ?? [];
  let unshorn = 0;
  let read = 0;
  for (const s of sheep) {
    if (s?.nbt === undefined || s?.nbt === null) continue;
    read++;
    if (!s.nbt) unshorn++;
  }
  return { sheep: sheep.length, sheared_read: read, unshorn };
}

async function probeLivestock(server: MinecraftServer) {
  const result = await world
    .entities(server, { dimension: "minecraft:overworld", types: ANIMALS.map((a) => `minecraft:${a}`), limit: 1000 })
    .catch(() => ({ entities: [] as world.EntityJson[] }));
  const counts: Record<string, number> = {};
  for (const e of result.entities ?? []) {
    const id = String(e?.type ?? "").replace(/^entity\./, "").replace(/\./g, ":");
    counts[id] = (counts[id] ?? 0) + 1;
  }
  return { by_type: counts, total: (result.entities ?? []).length };
}

function countItems(items: any[]) {
  const counts: Record<string, number> = {};
  for (const it of items) {
    const s = String(it?.$string ?? "");
    const m = s.match(/^(\d+)\s+(\S+)/);
    if (!m || m[2] === "air" || m[2] === "minecraft:air") continue;
    counts[m[2]] = (counts[m[2]] ?? 0) + Number(m[1]);
  }
  return counts;
}

function noteLostGolems(report: any, sample: any, round: number) {
  const missing: string[] = sample.integrations?.golem?.missing ?? [];
  report.lost_golems ??= {};
  for (const uuid of missing) {
    if (report.lost_golems[uuid]) continue;
    const last = [...(report.samples ?? [])].reverse()
      .flatMap((earlier: any) => earlier.integrations?.golem?.workers ?? [])
      .find((worker: any) => worker.uuid === uuid);
    report.lost_golems[uuid] = { round, last_seen: last ?? null };
    report.caveats.push(`golem ${uuid} vanished at round ${round} (died or left the loaded area); last seen: `
      + `${last ? `${Math.round(last.x)},${Math.round(last.z)} health ${last.health}/${last.max_health}` : "never sampled"}`);
  }
}

async function serverTickNanos(server: MinecraftServer, uuid: string) {
  for (const [method, returnType] of [
    ["getAverageTickTimeNanos", "long"],
    ["getCurrentSmoothedTickTime", "float"],
  ] as const) {
    try {
      const r = await reflect.invoke(server, { target: { kind: "entity", uuid }, path: "level.server", method, returnType });
      const v = numeric(r?.returned);
      if (v != null) return { method, value: v, mspt: method === "getAverageTickTimeNanos" ? v / 1e6 : v };
    } catch {
    }
  }
  return { method: null, value: null, mspt: null, note: "none of MinecraftServer's tick timing methods worked" };
}

async function tickCostAt(server: MinecraftServer, uuid: string | null) {
  const cursor = await tickCursor(server);
  const advanced = await advance(server, 40);
  const full = await readTicks(server, cursor);
  const vanilla = uuid ? await serverTickNanos(server, uuid) : { method: null, value: null, mspt: null, note: "no usable entity entry point" };
  return { ticks_advanced: advanced.actual, wall_ms: advanced.wall_ms, mspt_wall: advanced.mspt_wall, mspt_vanilla: vanilla.mspt, method: vanilla.method,
    mspt_full: full.full, mspt_post: full.post };
}

async function probeRail(server: MinecraftServer, rail: RailResult | null, farWorksite: Vec | null) {
  if (!rail?.carriageUuid) return null;
  const conductor = await conductorAboard(server, rail.carriageUuid).catch(() => false);
  const train = await sampleTrain(server, rail.carriageUuid);
  const seatedResidents = train.seated_residents ?? 0;
  const far = farWorksite ? await probeConstruction(server, farWorksite, farHutBlocks()) : null;
  let atFar = 0;
  if (farWorksite) {
    const cs = (await world.entities(server, { dimension: "minecraft:overworld", types: ["folkways:resident"], limit: 1000 }).catch(() => ({ entities: [] as world.EntityJson[] }))).entities ?? [];
    atFar = cs.filter((c: any) => c.position && Math.abs(c.position.z - farWorksite.z) < 12).length;
  }
  return { train, conductor, seated_residents: seatedResidents, far_built: far?.built ?? null, far_total: far?.total ?? null, residents_at_far: atFar };
}

const WALK_TICKS = 5;

async function stepTicks(server: MinecraftServer, ticks: number) {
  const before = (await world.time(server).catch(() => null))?.gameTime ?? null;
  const wall0 = Date.now();
  await tick.sprint(server, ticks);
  const after = (await world.time(server).catch(() => null))?.gameTime ?? null;
  return { clock: "stepped", requested: ticks, actual: before != null && after != null ? after - before : null, wall_ms: Date.now() - wall0 };
}

async function takeSample(server: MinecraftServer, uuids: string[], layout: Layout, round: number, advanced: any, ticked: TickWindow, rail: RailResult | null = null, farWorksite: Vec | null = null, anchor: string | null = null) {
  const t0 = Date.now();
  const before = (await snapshotBodies(server, COLONY_ID, uuids)).workers;
  const stride = await stepTicks(server, WALK_TICKS);
  const snapshot = await snapshotBodies(server, COLONY_ID, uuids);
  const after = snapshot.workers;

  const currentN = Math.min(uuids.length, CURRENT_SAMPLE);
  const chosen = Array.from({ length: currentN }, (_, i) => uuids[(Math.floor(i * uuids.length / currentN) + round) % uuids.length]);
  const residents: any[] = [];
  for (let i = 0; i < currentN; i++) {
    const uuid = chosen[i];
    const c: any = after[uuid] ? { ...after[uuid] } : { uuid, absent: true };
    const a = before[uuid];
    const b = after[uuid];
    c.moved = a && b ? Math.hypot(b.x - a.x, b.z - a.z) : null;
    c.walking = c.moved != null && c.moved > 0.15;
    residents.push(c);
  }
  const furnaces = await probeFurnaces(server, layout.furnaces);
  const tickCost = await serverTickNanos(server, anchor ?? uuids[0]);
  const time = await world.time(server).catch(() => null);
  return {
    integrations: null as any,
    order_outputs: [] as any[],
    output_chest: {} as Record<string, number>,
    round,
    gameTime: time?.gameTime ?? null,
    advanced,
    walk_window: stride,
    probe_ms: Date.now() - t0,
    tick: tickCost,
    tick_full: ticked,
    residents,
    current_sampled: residents.length,
    loaded_population: snapshot.loaded_alive,
    sampled_by_kind: residents.reduce((counts, c) => { const kind = c.kind ?? "missing"; counts[kind] = (counts[kind] ?? 0) + 1; return counts; }, {}),
    furnaces,
    furnace_sampled: furnaces.length,
    lit_furnaces: furnaces.filter((f: any) => (f.litTime ?? 0) > 0).length,
    busy_cookers: furnaces.reduce((n: Record<string, number>, f: any, i: number) => {
      const kind = layout.cookers[i]?.kind ?? "unknown";
      n[kind] = (n[kind] ?? 0) + ((f.litTime ?? 0) > 0 || (f.cookingProgress ?? 0) > 0 ? 1 : 0);
      return n;
    }, {}),
    cooking_furnaces: furnaces.filter((f: any) => (f.cookingProgress ?? 0) > 0).length,
    seed_chest: await probeChest(server, layout.seedChest),
    smelt_chest: await probeChest(server, layout.smeltChest),
    food_chest: await probeChest(server, layout.foodChest),
    ground_items: await probeGroundItems(server),
    livestock: await probeLivestock(server),
    shearables: await probeShearables(server),
    dairy_chest: await probeChest(server, layout.dairyChest),
    construction: await probeConstruction(server, layout.nearWorksite, layout.construction, LODGE_STRIDE),
    wall: layout.wall ? await probeConstruction(server, layout.wall.anchor, layout.wall.blocks, WALL_STRIDE) : null,
    demolition: await probeDemolition(server, layout),
    rail: await probeRail(server, rail, farWorksite),
  };
}

const LODGE_STRIDE = 8;
const WALL_STRIDE = 16;
const QUARRY_STRIDE = 3;

// Rock still standing in the quarry, sampled over the columns the demolition order clears.
async function probeDemolition(server: MinecraftServer, layout: Layout) {
  let standing = 0;
  let probed = 0;
  for (let i = 0; i < layout.quarryBlocks.length; i += QUARRY_STRIDE) {
    const b = layout.quarryBlocks[i];
    const pos = { x: layout.quarry.x + b.offset.x, y: layout.quarry.y + b.offset.y, z: layout.quarry.z + b.offset.z };
    const blk = await world.block(server, pos).catch(() => null);
    const id = blk?.id;
    if (typeof id !== "string") continue;
    probed++;
    if (id !== "minecraft:air") standing++;
  }
  return { standing, probed, total: layout.quarryBlocks.length, stride: QUARRY_STRIDE, cleared: probed - standing };
}

async function probeGroundItems(server: MinecraftServer) {
  const result = await world.entities(server, { dimension: "minecraft:overworld", types: ["minecraft:item"], limit: 1000 }).catch(() => ({ entities: [] as world.EntityJson[] }));
  const count = (result.entities ?? []).length;
  return { $item_entities: count, $capped: count >= 1000 };
}


async function advance(server: MinecraftServer, ticks: number) {
  return settle(server, ticks);
}

async function awaitRealTicks(server: MinecraftServer, ticks: number) {
  const gameTime = async () => (await world.time(server).catch(() => ({ gameTime: 0 })))?.gameTime ?? 0;
  const start = await gameTime();
  const wall0 = Date.now();
  const cap = Math.max(30_000, ticks * 50 * REALTIME_SLOWDOWN_CAP);
  const pollMs = realtimePollMs(ticks);
  let now = start;
  let lastSeen = start;
  let lastMoveAt = wall0;
  let completed = true;
  while (now - start < ticks) {
    await new Promise((resolve) => setTimeout(resolve, pollMs));
    now = await gameTime();
    if (now > lastSeen) {
      lastSeen = now;
      lastMoveAt = Date.now();
    } else if (Date.now() - lastMoveAt > STALL_MS) {
      throw new Error(
        `on the real clock the world sat at gameTime=${now} for ${Math.round(STALL_MS / 1000)}s without advancing - the server isn't running`
          + " (deadlock, watchdog, or the control channel dropped), it's not just slow",
      );
    }
    if (Date.now() - wall0 > cap) {
      completed = false;
      break;
    }
  }
  const wallMs = Date.now() - wall0;
  const actual = now - start;
  return {
    clock: "real",
    requested: ticks,
    actual,
    completed,
    wall_ms: wallMs,
    ticks_per_second: actual > 0 ? (actual / wallMs) * 1000 : 0,
    mspt_wall: actual > 0 ? wallMs / actual : null,
  };
}

async function onRealClock<T>(server: MinecraftServer, take: () => Promise<T>): Promise<T> {
  const was = (await tick.status(server).catch(() => ({}) as any))?.frozen;
  await tick.freeze(server, false);
  try {
    return await take();
  } finally {
    if (was !== false) {
      await tick.freeze(server, true);
    }
  }
}

async function settle(server: MinecraftServer, ticks: number) {
  return onRealClock(server, () => awaitRealTicks(server, ticks));
}

async function handToRealClock(server: MinecraftServer) {
  await tick.freeze(server, false);
  const status = await tick.status(server).catch((e: unknown) => ({ error: errorSummary(e) }) as any);
  return {
    frozen: status?.frozen ?? null,
    sprinting: status?.sprinting ?? null,
    tick_rate: status?.tickRate ?? null,
    note: "After frozen=false the world runs at its own pace; every ms/tick in the report is measured this way. "
      + "Whether solving runs on a worker is unrelated to this step - config decides it; `Labor.begin` reads "
      + "`mode` once at server start and it stays fixed; this bench explicitly sets DEFAULT (see shared/mod-settings.ts).",
  };
}

async function measurePerformance(server: MinecraftServer, uuid: string, measureCursor: number) {
  const out: any = {};
  try {
    out.tick_meter = { ...(await readTicks(server, measureCursor)), note: TICK_METER_NOTE + " Window: every real-clock tick of the observation loop." };
  } catch (error) {
    out.tick_meter_error = errorSummary(error);
  }
  await tick.freeze(server, false);
  try {
    const trace = await world.performance(server, { durationMs: 15_000, sampleIntervalMs: 250 });
    const samples: any[] = trace?.samples ?? [];
    const mspt = samples.map((s) => s.mspt).filter((v) => typeof v === "number" && v > 0);
    out.bw_trace = {
      samples: samples.length,
      mspt_median: median(mspt),
      mspt_p90: percentile(mspt, 0.9),
      mspt_max: mspt.length ? Math.max(...mspt) : null,
      entity_count: samples.at(-1)?.entityCount ?? null,
      loaded_chunks: samples.at(-1)?.loadedChunks ?? null,
      memory_mb: samples.at(-1)?.memoryMb ?? null,
      mspt_max_single: trace?.summary?.maxMspt ?? null,
      game_tps_median: median(samples.map((s) => s.gameTps).filter((v) => typeof v === "number")),
      note: "mspt = mean of MC's own tickTimes over each 250 ms sample window (time spent inside ticks, not wall clock or gameTime delta, so it stays below 50 while the server keeps up); mspt_max_single = the longest single tick. game_tps = world ticks per second on the real clock. MC's built-in stats (excluding Post) are also in vanilla_tick; the Folkways labor loop is in analysis.engine.",
    };
  } catch (error) {
    out.bw_trace_error = errorSummary(error);
  }
  try {
    out.vanilla_tick = await serverTickNanos(server, uuid);
  } catch (error) {
    out.vanilla_tick_error = errorSummary(error);
  }
  return out;
}

function deriveCaveats(report: any, scale: Scale) {
  const c: string[] = report.caveats;
  c.push(
    "Fixtures use reflection/NBT, not the real UI: residents are summoned with their colony UUID in neoforge:attachments folkways:body_state.Colony and register automatically; members via /data merge colony_member; " +
      "zones/orders/blueprints/beds/member index are written into folkways_colonies.dat (each compound copies the mod's matching save(), "
      + "matching field by field the file the real UI produces, schemaVersion 1 / DataVersion 3955) + reflective cache.remove to force a reload. " +
      "Small-N equivalence was verified in the probe (residents really claim and run farm/craft/smelt). Real-UI founding/immigration is covered by founding-smoke and others.",
  );
  const curSampled = report.analysis?.resident_time?.current_sampled_per_round ?? CURRENT_SAMPLE;
  c.push(
    `idle/working/walking basis = ${curSampled} residents sampled per round, rotating across the colony` +
      ` (of ${scale.residents}; humans and golems share the Body API batch snapshot); cooker utilisation = all ${scale.furnaces + scale.blastFurnaces + scale.smokers} cookers every round.`,
  );
  const lastOutput = report.samples?.at(-1)?.output_chest ?? {};
  if (lastOutput["minecraft:iron_ingot"] == null) {
    c.push(
      "No iron ingots found in the order chest during the observation window; judge from furnace input, output slots, completion events and the delivery chain, not from the shortage alone.",
    );
  }
  const summary = report.setup?.layout_summary ?? {};
  c.push(
    `work surfaces, in a town raised on generated terrain (site ${JSON.stringify(report.setup?.town?.scout ?? {})}): ` +
      `farming(${(summary.plots ?? []).map((p: any) => `${p.crop.replace("minecraft:", "")} ${p.cells}`).join(", ")} cells; grove of ${summary.grove_cells ?? 0} oaks felled and replanted)/` +
      `pasture(${scale.pens / 2} under-target pens ${BREED.heads} head->target ${BREED.target} + ${scale.pens / 2} over-target pens ${CULL.heads} head->target ${CULL.target}, breed/slaughter/shear/milk/eggs)/` +
      `fishing(${summary.fishing_spots ?? 0} spots, ${summary.docks ?? 0} of them on docks over natural water)/craft(${scale.craftingTables} crafting + ${scale.smithingTables} smithing tables + ${scale.stonecutters} stonecutters)/` +
      `smelting(${scale.furnaces} furnaces + ${scale.blastFurnaces} blast furnaces + ${scale.smokers} smokers)/haul(harvest drops + MAINTAIN deliveries)/provide(2 PROVIDE orders)/` +
      `construction(a ${summary.construction_cells ?? 0}-block lodge${summary.wall_cells ? ` and a ${summary.wall_cells}-cell town wall` : ""})/break(${summary.quarry_cells ?? 0}-cell quarry)/needs(cooked food stocked for eat)/rail commute. ` +
      "UI/UX is out of scope. This describes what the set contains, not what happened - "
      + "see plugin_coverage for plugin tasks actually completed and items not observed; the set itself is no evidence of execution.",
  );
  const short = (report.setup?.trade_checks ?? []).filter((t: any) => t.tools_ok === false);
  if (short.length) {
    c.push(
      `spot-checked residents of these trades lack their trade's tools: ${short.map((t: any) => t.trade).join(", ")}. ` +
        "Tools gate task assignment; this only reflects the spot-checked residents, not that the whole trade lacks tools.",
    );
  }
  const unknown = (report.setup?.trade_checks ?? []).filter((t: any) => t.priorities_ok === null || t.tools_ok === null);
  if (unknown.length) {
    c.push(
      `spot checks for these trades have unreadable items (recorded as null, not false): ${unknown.map((t: any) => t.trade).join(", ")}. ` +
        "null only means that cell is no evidence (the probe didn't read it, or the trade carries no tools), not that the resident failed to start work - " +
        "check the self-check values attachments_seen / pack_read before concluding.",
    );
  }
  const r = report.analysis?.rail;
  if (r && r.enabled !== false) {
    c.push(
      "rail: a real Create train (the only step using real client interaction - contraptions can't be built from NBT; colony/construction are still all .dat + reflection, UI-free). " +
        `conductor (resident) boarding ${r.conductor_boarded ? "observed" : "not observed"} (on board ${r.conductor_rounds}/${r.samples} rounds); ` +
        `passenger commute ${r.passenger_commute_observed ? `observed (far_built=${r.far_worksite_built}/${r.far_worksite_total})` : "not observed this run"}` +
        " (closed four-station loop Alpha->Beta->Gamma->Delta->Alpha; boarding and far-site construction are recorded separately, and not observed does not count as a pass). " +
        "rail is isolated in a try; assembly/commute problems only record a caveat and don't void the main measurement.",
    );
  } else if (r) {
    c.push("rail: disabled this run (FOLKWAYS_LC_RAIL=0).");
  }
  const idle = report.analysis?.resident_time?.idle_pct;
  const census = report.analysis?.claimed_kinds;
  if (typeof idle === "number") {
    c.push(
      `sampled idle ${idle}% (a reading, not a red; it lumps the heartbeat's idle and stalled together). ` +
        "The schedule hands out only work that can start within its plan horizon, so a resident with nothing near to do " +
        "is free for the next plan: measure output by analysis.labor.throughput_per_1000_ticks and the split of " +
        "analysis.labor.standing_share, not by idleness. " +
        `* Don't use the heartbeat's in-hand node count (per-beat peak ${census?.peak_in_hand ?? "?"}, ${census?.work_events ?? "?"} node-beats overall) ` +
        "to infer how many people work at once: it is the **node count** in WORKING/SETTLING per beat, and one person holding three nodes counts three times. " +
        "unassigned must be explained by reason, task identity and prerequisites; the count alone can't separate set problems from engine problems.",
    );
  }
  const clock = report.setup?.clock;
  c.push(
    `clock: the set is built and each round's ${ROUND_TICKS} ticks run on the real clock (frozen=${clock?.frozen ?? "?"}, tickRate=${clock?.tick_rate ?? "?"}); `
      + `after each round the world is frozen while it is sampled, and the ${WALK_TICKS}-tick walk window is stepped, so every probe of a round reads the same tick. `
      + "Off-thread solves keep running on the wall clock while the world is frozen, so they get slightly more wall time per game tick than in play. "
      + "Whether solving runs on the tick or a worker is decided by config, read once at server start (`Labor.begin` reads `mode`; this bench explicitly sets DEFAULT). "
      + "Real tick cost is analysis.tick_cost.mspt_full (tick meter, includes Post); mspt_vanilla excludes Post and mspt_wall is pinned at 50 while the server keeps up.",
  );
}

function analyze(report: any, scale: Scale) {
  const samples: any[] = report.samples ?? [];
  let idle = 0;
  let sleeping = 0;
  let planned = 0;
  let walking = 0;
  let working = 0;
  let lostGolems = 0;
  let absent = 0;
  const currentKinds: Record<string, number> = {};

  for (const s of samples) {
    for (const c of s.residents ?? []) {
      if (c.absent) {
        if (c.lost_golem) lostGolems++;
        else absent++;
        continue;
      }
      if (c.idle) {
        idle++;
        continue;
      }
      if (c.sleep) {
        sleeping++;
        continue;
      }
      planned++;
      if (c.walking) walking++;
      else working++;
      const kind = doingKind(c.current);
      currentKinds[kind] = (currentKinds[kind] ?? 0) + 1;
    }
  }

  const lit = samples.map((s) => s.lit_furnaces ?? 0);
  const cooking = samples.map((s) => s.cooking_furnaces ?? 0);
  const observations = planned + idle + sleeping;
  const currentSampled = samples.at(-1)?.current_sampled ?? Math.min(scale.residents, CURRENT_SAMPLE);
  const furnaceSampled = samples.at(-1)?.furnace_sampled ?? scale.furnaces + scale.blastFurnaces + scale.smokers;

  return {
    resident_time: {
      observations,
      residents_total: scale.residents,
      current_sampled_per_round: currentSampled,
      idle_pct: pct(idle, observations),
      sleep_pct: pct(sleeping, observations),
      walking_pct: pct(walking, observations),
      working_pct: pct(working, observations),
      absent_lost_golems: lostGolems,
      absent_unexplained: absent,
      note: `idle/working/walking basis = ${currentSampled} residents sampled per round, rotating (of ${scale.residents}; humans and golems share the Body API batch snapshot); `
        + `walking = displacement > 0.15 blocks over exactly ${WALK_TICKS} ticks stepped on a frozen world; absent = no living body in the colony census (dead or unloaded), not counted as an observation`,
    },
    current_action_histogram: currentKinds,
    furnaces: {
      installed: scale.furnaces + scale.blastFurnaces + scale.smokers,
      sampled: furnaceSampled,
      lit_mean: mean(lit),
      lit_max: lit.length ? Math.max(...lit) : 0,
      cooking_mean: mean(cooking),
      cooking_max: cooking.length ? Math.max(...cooking) : 0,
      utilisation_pct: pct(mean(lit), furnaceSampled),
      note: `${scale.furnaces + scale.blastFurnaces + scale.smokers} cookers (furnaces, blast furnaces, smokers) placed; utilisation is computed from all ${furnaceSampled} furnaces, read in one task per round on a frozen world`,
    },
    stock_series: {
      seed_wheat_seeds: samples.map((s) => stockOf(s.seed_chest, "wheat_seeds")),
      smelt_raw_iron: samples.map((s) => stockOf(s.smelt_chest, "raw_iron")),
      smelt_oak_planks: samples.map((s) => stockOf(s.smelt_chest, "oak_planks")),
      output_iron_ingot: samples.map((s) => stockOf(s.output_chest, "iron_ingot")),
      output_all: samples.at(-1)?.output_chest ?? null,
      note: stockDropNote(samples.map((s) => stockOf(s.seed_chest, "wheat_seeds"))),
    },
    tick_cost: {
      mspt_full: report.perf?.tick_meter?.full ?? null,
      mspt_post: report.perf?.tick_meter?.post ?? null,
      headroom_pct_at_p50: headroom(report.perf?.tick_meter?.full?.p50),
      headroom_pct_at_p95: headroom(report.perf?.tick_meter?.full?.p95),
      mspt_full_mean_series: samples.map((s) => s.tick_full?.full?.mean ?? null),
      mspt_full_p95_series: samples.map((s) => s.tick_full?.full?.p95 ?? null),
      mspt_full_max_series: samples.map((s) => s.tick_full?.full?.max ?? null),
      mspt_post_mean_series: samples.map((s) => s.tick_full?.post?.mean ?? null),
      over_budget_series: samples.map((s) => s.tick_full?.full?.over_50 ?? null),
      mspt_vanilla_series: samples.map((s) => s.tick?.mspt ?? null),
      mspt_vanilla_median: median(samples.map((s) => s.tick?.mspt).filter((v: any) => typeof v === "number")),
      mspt_wall_series: samples.map((s) => s.advanced?.mspt_wall ?? null),
      real_ticks_per_second: samples.map((s) => Math.round(s.advanced?.ticks_per_second ?? 0)),
      note: "mspt_full is the real per-tick server cost measured in game (tick meter, see perf.tick_meter.note), including the Post phase where Folkways labor runs; "
        + "headroom is the share of the 50 ms budget left. mspt_vanilla is MinecraftServer's own tick timing, which is tallied before Post and so excludes Folkways; "
        + "mspt_wall is wall clock / gameTime on the real clock and stays pinned at 50 while the server keeps up.",
    },
    construction: {
      lodge_built_sampled_series: samples.map((s) => s.construction?.built ?? null),
      lodge_sampled_per_round: samples.at(-1)?.construction?.sampled ?? null,
      lodge_total: samples.at(-1)?.construction?.total ?? null,
      lodge_final_built: report.final_construction?.built ?? null,
      lodge_final_total: report.final_construction?.total ?? null,
      note: "Per-round values are stride samples (sampled/total are labelled separately, don't read sampled values against total); lodge_final_* is a full read at the end. "
        + "final_built > 0 means BUILD really is placing.",
    },
    livestock: {
      total_series: samples.map((s) => s.livestock?.total ?? null),
      by_type_final: samples.at(-1)?.livestock?.by_type ?? null,
      note: "Time series of total livestock. Under-target pens push it up and over-target pens pull it down, so any change means pasture is active; only a flat line means it isn't.",
    },
    shearing: {
      sheep_series: samples.map((s) => s.shearables?.sheep ?? null),
      unshorn_series: samples.map((s) => s.shearables?.unshorn ?? null),
      sheared_read_final: samples.at(-1)?.shearables?.sheared_read ?? null,
      note: "SHEAR needs the pen's shear setting on (zones loaded from .dat default a missing key to off, so the fixture writes shear=true) and readyForShearing(). unshorn always 0 => no sheep to shear (set problem); "
        + "unshorn always equals sheep and SHEAR never claimed => sheep to shear but nobody goes (scheduling problem). "
        + "sheared_read is a self-check: 0 means the Sheared NBT wasn't read and both numbers above are void. "
        + "* There is a third case, in setup.pen_fences: one missing block in a sheep pen's fence ring makes PastureValidator flag that edge as leaking "
        + "and the whole pen posts no work - then SHEAR's 0 is neither the sheep nor scheduling; the work never existed.",
    },
    milk: {
      dairy_chest_series: samples.map((s) => ({
        buckets: stockOf(s.dairy_chest, "bucket"), milk: stockOf(s.order_outputs?.[MILK_ORDER], "milk_bucket"),
      })),
      note: "Milking is a demand-driven recipe, not a task: empty buckets are the input, milk_bucket the output, both in the same chest. "
        + "buckets don't drop => the chain never fired; buckets drop and milk rises => COLLECT really is running.",
    },
    demolition: {
      standing_series: samples.map((s) => s.demolition?.standing ?? null),
      probed_final: samples.at(-1)?.demolition?.probed ?? null,
      total: samples.at(-1)?.demolition?.total ?? null,
      cleared_final: samples.at(-1)?.demolition?.cleared ?? null,
      note: "standing is the cobblestone left in the demolition build order's footprint, BREAK's only work surface (via BuildWork). "
        + "probed is a self-check: when it is 0, cleared's 0 means \"not checked\", not \"not demolished\".",
    },
    rail: railSummary(samples),
  };
}

const MILK_ORDER = DEMANDS.findIndex((d) => d.item === "minecraft:milk_bucket");

const TRADE_CENSUS_NOTE =
  "Per-trade claim attempts are not recorded; this run uses plugin_coverage to sum actual completion events by plugin and node type instead.";

function readClaimCensus(repoRoot: string, serverInstance: string) {
  const file = path.join(repoRoot, ".blockwright", "instances", serverInstance, "logs", "latest.log");
  let text = "";
  try {
    text = readFileSync(file, "utf8");
  } catch (error) {
    return {
      log: file, heartbeats: 0, read_error: errorSummary(error),
      beats: [], trade_census: null, trade_census_note: TRADE_CENSUS_NOTE,
    };
  }
  let heartbeats = 0;
  const bodiesSeries: number[] = [];
  const beats: ColonyBeat[] = [];
  type Plan = { nodes: number; tours: number; unassigned: number; shortfall: number; turned: number };
  const planSeries: Plan[] = [];
  const noPlan = (): Plan => ({ nodes: 0, tours: 0, unassigned: 0, shortfall: 0, turned: 0 });
  const phaseTotals: Record<string, number> = {};
  let workEvents = 0;
  let peakInHand = 0;
  let openBeat = false;
  let beatBodies = 0;
  let beatInHand = 0;
  let beatPlan = noPlan();
  const closeBeat = () => {
    if (!openBeat) return;
    heartbeats++;
    bodiesSeries.push(beatBodies);
    planSeries.push(beatPlan);
    peakInHand = Math.max(peakInHand, beatInHand);
    openBeat = false;
    beatBodies = 0;
    beatInHand = 0;
    beatPlan = noPlan();
  };
  for (const line of text.split("\n")) {
    if (isNavLine(line)) {
      closeBeat();
      openBeat = true;
      continue;
    }
    const beat = parseColony(line);
    if (!beat || !openBeat) continue;
    beats.push(beat);
    const inHand = (beat.phases.WORKING ?? 0) + (beat.phases.SETTLING ?? 0);
    beatInHand += inHand;
    workEvents += inHand;
    for (const [phase, many] of Object.entries(beat.phases)) {
      phaseTotals[phase] = (phaseTotals[phase] ?? 0) + many;
    }
    if (beat.bodies >= beatBodies) {
      beatBodies = beat.bodies;
      beatPlan = {
        nodes: beat.nodes, tours: beat.tours,
        unassigned: beat.unassigned, shortfall: beat.shortfall, turned: beat.turned,
      };
    }
  }
  closeBeat();
  const peakBodies = bodiesSeries.reduce((most, n) => Math.max(most, n), 0);
  const lastBodies = bodiesSeries.length ? bodiesSeries[bodiesSeries.length - 1] : 0;
  const stuck = beats
    .filter((beat) => beat.solving_seconds > 0)
    .sort((a, b) => b.solving_seconds - a.solving_seconds)[0];
  return {
    log: file,
    heartbeats,
    beats,
    worst_solve_seconds: stuck?.solving_seconds ?? 0,
    worst_solve_at: stuck ? `${stuck.colony}@${stuck.dimension}` : null,
    peak_bodies: peakBodies,
    last_bodies: lastBodies,
    bodies_series: bodiesSeries,
    plan_series: planSeries,
    plan_last: planSeries.length ? planSeries[planSeries.length - 1] : null,
    phase_totals: phaseTotals,
    work_events: workEvents,
    peak_in_hand: peakInHand,
    trade_census: null,
    trade_census_note: TRADE_CENSUS_NOTE,
    note: "Reads, beat by beat, the line written by core/engine/labor/Readout.line."
      + " phase_totals / work_events sum the phases={...} table: WORKING+SETTLING is how many"
      + " nodes were held this beat (one person holding three nodes counts three times; it's a rate, not a headcount)."
      + " Warning: bodies is how many bodies this driver actually stepped this tick, not the roster size: residents in unloaded chunks don't count."
      + " Warning: per-trade claim counts are no longer in the heartbeat, see trade_census_note.",
  };
}

function printSummary(report: any, jsonPath: string) {
  const time = report.analysis?.resident_time ?? {};
  const labor = report.analysis?.labor ?? {};
  const cost = report.analysis?.tick_cost ?? {};
  const census = report.analysis?.claimed_kinds ?? {};
  const perf = report.perf?.bw_trace ?? {};
  const say = (v: unknown) => (typeof v === "number" ? Math.round(v * 10) / 10 : "?");
  const lines = [
    `tests/bench/large-colony · ${report.scale?.residents} residents (incl. golems) · ${(report.samples ?? []).length}/${ROUNDS} rounds · real clock (frozen=${report.setup?.clock?.frozen})`,
    `  tick   mspt(full, incl. Post) mean ${say(cost.mspt_full?.mean)} / p50 ${say(cost.mspt_full?.p50)} / p95 ${say(cost.mspt_full?.p95)} / max ${say(cost.mspt_full?.max)}`
      + `  over 50ms=${cost.mspt_full?.over_50 ?? "?"}/${report.perf?.tick_meter?.ticks ?? "?"} ticks  headroom@p50=${say(cost.headroom_pct_at_p50)}%`
      + `  Post mean ${say(cost.mspt_post?.mean)} / p95 ${say(cost.mspt_post?.p95)}`,
    `         mspt(MC excl. Post)=${say(cost.mspt_vanilla_median)}  real TPS=${say(median((cost.real_ticks_per_second ?? []).filter((v: any) => typeof v === "number")))}`
      + `  trace: median ${say(perf.mspt_median)} / p90 ${say(perf.mspt_p90)} / max ${say(perf.mspt_max)}`,
    `  scale  bodies=${census.last_bodies ?? "?"}/${report.scale?.residents}  entities=${perf.entity_count ?? "?"}  chunks=${perf.loaded_chunks ?? "?"}  memory=${perf.memory_mb ?? "?"}MB`,
    `  plan   nodes=${census.plan_last?.nodes ?? "?"}  tours=${census.plan_last?.tours ?? "?"}`
      + `  unassigned=${census.plan_last?.unassigned ?? "?"}  shortfall=${census.plan_last?.shortfall ?? "?"}`
      + `  turned=${census.plan_last?.turned ?? "?"} (last beat)`,
    `  output ${say(labor.throughput_per_1000_ticks)} nodes per 1000 ticks (${labor.done_total ?? "?"} done over ${labor.beats ?? "?"} beats)`
      + `  deferred past horizon median ${say(labor.deferred?.median)} / peak ${labor.deferred?.peak ?? "?"}`,
    `  labor  ${Object.entries(labor.standing_share ?? {}).map(([state, share]) => `${state} ${say(100 * Number(share))}%`).join(" / ") || "?"} (heartbeat standing)`,
    `  sample idle ${say(time.idle_pct)}% / working ${say(time.working_pct)}% / walking ${say(time.walking_pct)}% / sleep ${say(time.sleep_pct)}% (${time.current_sampled_per_round ?? "?"} sampled, rotating; idle includes stalled)`,
    `  held   node-beats=${census.work_events ?? "?"}  per-beat peak=${census.peak_in_hand ?? "?"}`
      + `  phase totals=${Object.entries(census.phase_totals ?? {}).map(([k, v]) => `${k}:${v}`).join(" ") || "-"}`
      + "  (per-trade claim counts are no longer in the heartbeat, see trade_census_note)",
    `  output lodge ${report.analysis?.construction?.lodge_final_built ?? "?"}/${report.analysis?.construction?.lodge_final_total ?? "?"}`
      + `  demolition ${report.analysis?.demolition?.cleared_final ?? "?"}/${report.analysis?.demolition?.total ?? "?"}`
      + `  town wall ${report.final_construction?.wall?.built ?? "-"}/${report.final_construction?.wall?.total ?? "-"}`,
    `  report ${jsonPath}`,
  ];
  console.log("\n" + lines.join("\n") + "\n");
}

const TALLY_NOTE = "World-wide item tally in four buckets: packs / containers / ground / players. Conservation only judges tracers "
  + "(tools, buckets) - everything else appears from farming and fishing and disappears into smelting and building, so it was never conserved. "
  + "If any read hits the query cap, capped is set and this group is noted, not judged.";

function judge(report: any): { findings: Finding[]; judged: Record<string, boolean> } {
  const beats: ColonyBeat[] = report.analysis?.claimed_kinds?.beats ?? [];
  const before = report.conservation?.before;
  const after = report.conservation?.after;
  const findings: Finding[] = [];
  const judged = {
    utilisation: beats.length > 0,
    failures: beats.length > 0,
    conservation: Boolean(before && after),
    trade_coverage: false,
  };
  const broke: Record<string, number> = {};
  let brokeKnown = false;
  for (const beat of beats) {
    for (const [id, count] of Object.entries(beat.broke ?? {})) {
      broke[id] = (broke[id] ?? 0) + count;
      brokeKnown = true;
    }
  }
  report.conservation = report.conservation ?? {};
  report.conservation.broke = broke;
  if (judged.utilisation) {
    const active = new Set(beats.filter(b => b.bodies > 0).map(b => `${b.colony}@${b.dimension}`));
    findings.push(...judgeUtilisation(active.size ? beats.filter(b => active.has(`${b.colony}@${b.dimension}`)) : beats));
  }
  if (judged.failures) findings.push(...judgeFailures(beats));
  if (judged.conservation) findings.push(...judgeConservation(before, after, broke, brokeKnown));
  for (const [name, ran] of Object.entries(judged)) {
    if (!ran) {
      findings.push({
        rule: `${name}/not-judged`,
        severity: "caveat",
        said: name === "trade_coverage"
          ? report.analysis?.claimed_kinds?.trade_census_note
            ?? "the trade_coverage group has no data source, nothing was judged"
          : `the ${name} group got no data, nothing was judged - an empty result doesn't mean all clear`,
      });
    }
  }
  if ((report.analysis?.engine?.planning_failures ?? 0) > 0) {
    findings.push({ rule: "planning/exception", severity: "red",
      said: `${report.analysis.engine.planning_failures} planning exceptions, failed batch peak ${report.analysis.engine.failed_batch_peak} messages` });
  }
  return { findings, judged };
}

function collectDiagnostics(report: any, scale: Scale) {
  const failures: string[] = [];

  if (report.fatal) failures.push(`measurement failed midway: ${report.fatal}`);

  for (const finding of report.findings?.findings ?? []) {
    if (finding.severity === "red") {
      failures.push(`[${finding.rule}] ${finding.said}`);
    } else {
      report.caveats.push(`[${finding.rule}] ${finding.said}`);
    }
  }

  const time = report.analysis?.resident_time ?? {};
  const observations = Number(time.observations ?? 0);
  if (!report.samples?.length) failures.push("the observation loop didn't finish a single round");
  if (observations <= 0) failures.push("the resident probe produced no observations - 0 doesn't mean \"all idle\", it means nobody was found");

  const absent = Number(time.absent_unexplained ?? 0);
  if (absent > 0) {
    failures.push(`${absent} resident observations found no living body in the colony census and are not recorded lost golems - a resident died or left the loaded area; those samples are excluded, not idle`);
  }

  const drifted = (what: string, written: number, loaded: unknown, alsoBecause = "") => {
    if (written > 0 && loaded !== written) {
      failures.push(
        `.dat wrote ${written} ${what}, colony read back ${JSON.stringify(loaded)}`
          + " - the save format no longer matches the `world/colony` save() methods (`ColonyData.load` neither throws"
          + " nor logs on records it can't read, it just skips them); fix tests/e2e/colony-nbt.ts first" + alsoBecause
          + "; until then none of the coverage / output assertions below count",
      );
    }
  };
  drifted("zones", Number(report.setup?.zones_written ?? 0), report.setup?.zones_loaded);
  drifted(
    "member cells",
    Number(report.setup?.member_cells_written ?? 0),
    report.setup?.member_cells_loaded,
    ` (membership.refused_writes=${report.setup?.membership?.refused_writes ?? "not counted"}: non-zero means those cells`
      + " aren't members in the world and `Members.reconcile` removed them; fix the set, not the format)",
  );

  const tradeChecks: any[] = report.setup?.trade_checks ?? [];
  if (!tradeChecks.length) {
    failures.push("no trade was spot-checked - the coverage premise (residents working their licensed trade) was never shown");
  }
  if (tradeChecks.length && !tradeChecks.some((t) => (t.attachments_seen ?? []).length > 0)) {
    failures.push(
      `no spot-checked resident had a non-empty ${ATTACHMENTS_KEY} - the attachment probe failed to read, the priorities aren't wrong`
        + ` (first error=${tradeChecks[0]?.attachments_error ?? "none"})`,
    );
  }
  if (tradeChecks.length && !tradeChecks.some((t) => (t.carried ?? []).length > 0)) {
    failures.push("no spot-checked resident had a non-empty pack - the pack probe failed to read, tools weren't withheld");
  }
  const badTrades = tradeChecks.filter(
    (t) => t.priorities_ok !== true || (t.tools_expected?.length > 0 && t.tools_ok !== true),
  );
  if (badTrades.length) {
    const say = (v: unknown, yes: string, no: string) => (v === true ? yes : v === false ? no : "unknown");
    failures.push(
      "spot-checked residents of these trades aren't working their trade: "
        + badTrades.map((t) => `${t.trade}(priorities ${say(t.priorities_ok, "ok", "wrong")}/tools ${say(t.tools_ok, "complete", "missing")})`).join(", "),
    );
  }

  const population = Number(report.setup?.population ?? 0);
  if (population < scale.residents) {
    failures.push(`only ${population}/${scale.residents} residents settled`);
  }
  const census = report.analysis?.claimed_kinds ?? {};
  const stuckSeconds = Number(census.worst_solve_seconds ?? 0);
  if (Number(census.heartbeats ?? 0) <= 0) {
    failures.push(`no planning heartbeat read (${census.log}) - for every heartbeat-derived number below, 0 means "not read", not "didn't happen"`);
  } else if (stuckSeconds > 0) {
    failures.push(
      `a solve at ${census.worst_solve_at} ran ${stuckSeconds}s without returning`
        + " (wait time at heartbeat sampling, including solve-queue wait, not the full round time). "
        + "This shows a slow solve occurred; whether it recovered depends on later plans, it doesn't prove the whole run hung. "
        + "Record queue/expand/schedule stage timings, or capture folkways-solve-* thread stacks to locate it.",
    );
  } else if (Number(census.last_bodies ?? 0) < scale.residents) {
    failures.push(
      `at the end the driver only stepped ${census.last_bodies}/${scale.residents} bodies (per beat: ${(census.bodies_series ?? []).join(",")})`
        + " - someone died or dropped out of the colony during the run",
    );
  }

  const demo = report.analysis?.demolition ?? {};
  if (Number(demo.probed_final ?? 0) <= 0) {
    failures.push("not one demolition-site cell was checked - cleared's 0 means \"not checked\", not \"not demolished\"");
  }
  const penFences = report.setup?.pen_fences ?? {};
  if (Number(penFences.probed ?? 0) <= 0) {
    failures.push("not one pasture fence block was checked - whether pens are valid zones is unknown, so BREED/SLAUGHTER/SHEAR/COLLECT zeros can't be attributed");
  } else {
    if (Number(penFences.probed ?? 0) < Number(penFences.expected ?? 0)) {
      failures.push(
        `only ${penFences.probed}/${penFences.expected} pasture fence blocks read - no evidence the unread pens are intact`,
      );
    }
    if (Number(penFences.gap_pens ?? 0) > 0) {
      const byAnimal = Object.entries(penFences.gap_pens_by_animal ?? {}).map(([a, n]) => `${a}×${n}`).join(", ");
      const shown = (penFences.gaps ?? [])
        .map((g: any) => `${g.animal}@x${g.at?.x},z${g.at?.z} missing ${g.missing}/${g.ring}(${(g.sides ?? []).join("/")})`)
        .join("; ");
      failures.push(
        `${penFences.gap_pens}/${penFences.pens} pens have gaps in their fence ring (by animal: ${byAnimal}; first few: ${shown})`
          + " - PastureValidator flags these pens as leaking and they silently post nothing, so their BREED/SLAUGHTER/SHEAR/COLLECT"
          + " means \"the work never existed\", not \"work nobody did\". Trades are tied to animals (SHEAR only takes sheep, COLLECT only cows), "
          + "so if every pen for an animal has gaps, that whole trade doesn't exist.",
      );
    }
  }
  const shearing = report.analysis?.shearing ?? {};
  if (Number(shearing.sheared_read_final ?? 0) <= 0) {
    failures.push("no sheep's Sheared was read - the shearable count is unreliable, SHEAR's 0 can't be attributed");
  } else if (Number((shearing.unshorn_series ?? []).at(0) ?? 0) <= 0) {
    failures.push("no shearable sheep at start - the set never met SHEAR's trigger, it's not that nobody sheared");
  }

  report.diagnostics = failures;
  report.completion = {
    status: report.fatal || report.samples?.length !== ROUNDS ? "failed" : "passed",
    health: report.findings?.findings?.some((f: Finding) => f.severity === "red") ? "diagnostics_present" : "no_red_findings",
    measured_rounds: report.samples?.length ?? 0,
    requested_rounds: ROUNDS,
  };
}

function railSummary(samples: any[]) {
  const rs = samples.map((s) => s.rail).filter(Boolean);
  if (!rs.length) return { enabled: false };
  const conductorRounds = rs.filter((r: any) => r.conductor).length;
  const maxSeated = Math.max(0, ...rs.map((r: any) => r.seated_residents ?? 0));
  const maxFarBuilt = Math.max(0, ...rs.map((r: any) => r.far_built ?? 0));
  const maxAtFar = Math.max(0, ...rs.map((r: any) => r.residents_at_far ?? 0));
  return {
    samples: rs.length,
    stations_observed: [...new Set(rs.map((r: any) => r.train?.station).filter(Boolean))],
    moving_rounds: rs.filter((r: any) => Math.abs(r.train?.speed ?? 0) > 0.001).length,
    train_series: rs.map((r: any) => r.train),
    conductor_rounds: conductorRounds,
    conductor_boarded: conductorRounds > 0,
    max_seated_residents: maxSeated,
    far_worksite_built: maxFarBuilt,
    far_worksite_total: rs.at(-1)?.far_total ?? null,
    max_residents_at_far: maxAtFar,
    passenger_commute_observed: maxSeated > 1,
    remote_construction_observed: maxFarBuilt > 0,
    conductor_series: rs.map((r: any) => (r.conductor ? 1 : 0)),
    far_built_series: rs.map((r: any) => r.far_built),
    note:
      "conductor means a resident conductor was seen on board that round. max_seated>1 is evidence of passengers boarding; far_built>0 alone proves far-site construction, residents may also walk there. " +
      "Closed four-station loop Alpha->Beta->Gamma->Delta->Alpha; conductor boarding and passenger far-site building are recorded separately.",
  };
}

function stockOf(chest: any, item: string): number | null {
  if (!chest || typeof chest !== "object") return null;
  return chest[`minecraft:${item}`] ?? chest[item] ?? null;
}

function stockDropNote(series: (number | null)[]) {
  const drops: number[] = [];
  for (let i = 1; i < series.length; i++) {
    const before = series[i - 1];
    const after = series[i];
    if (before != null && after != null && before > after) drops.push(before - after);
  }
  return drops.length
    ? `largest net drop of the input chest in one round: ${Math.max(...drops)}; with many stores and residents in parallel, a single chest's delta can't tell the per-trip pickup.`
    : "no net drop in this input chest; other scattered stores may supply it, so this doesn't show the trade didn't run.";
}

function numeric(node: any): number | null {
  if (typeof node === "number") return node;
  if (node && typeof node === "object" && typeof node.value === "number") return node.value;
  return null;
}

function median(values: number[]) {
  const v = values.filter((x) => typeof x === "number").sort((a, b) => a - b);
  if (v.length === 0) return null;
  const mid = Math.floor(v.length / 2);
  return v.length % 2 ? v[mid] : (v[mid - 1] + v[mid]) / 2;
}

function percentile(values: number[], p: number) {
  const v = values.filter((x) => typeof x === "number").sort((a, b) => a - b);
  if (v.length === 0) return null;
  return v[Math.min(v.length - 1, Math.floor(v.length * p))];
}

function mean(values: number[]) {
  const v = values.filter((x) => typeof x === "number");
  return v.length ? v.reduce((a, b) => a + b, 0) / v.length : 0;
}

function headroom(mspt: unknown) {
  return typeof mspt === "number" ? Math.round((1 - mspt / 50) * 1000) / 10 : null;
}

function pct(part: number, total: number) {
  return total > 0 ? Math.round((part / total) * 1000) / 10 : null;
}
