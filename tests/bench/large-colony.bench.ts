import { samplePathQueue } from "./path-probe";
import { sampleLoosePieces } from "./piece-probe";
import { sampleRailGraphs, sampleRailReach } from "./rail-reach-probe";
import { sampleTrain } from "./rail-probe";
import { populationSlots } from "./population";
import { safeSpawnSites } from "./spawn-sites";
import { snapshotBodies } from "./body-snapshot";
import { runColonyTask } from "../e2e/colony-tasks";
import { COLONY_PACKS } from "../shared/integration-tasks";
import { setupIntegrations, sampleIntegrations } from "./integrations";
import { startFilm, finishFilm, heroShots, launchCameras, closeCameras, filmShaders, seedFilmClient, FILMING, SHADERS, ROAMERS,
  type Camera, type Shot } from "./video";
import { direct, installFilmFeed, scout, type Directing } from "./director";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { raiseTown, type Town } from "./raise";
import { hasTown, keepTown, loadTown, seedWorld, townKey, townSave } from "./town-cache";
import { dumpTicks, installTickMeter, TICK_METER_NOTE } from "./tick-meter";
import { dumpWorkTap, installWorkTap, WORK_TAP_NOTE } from "./work-tap";
import { censusLines, installCensus, CENSUS_NOTE } from "./census";
import {
  buildLayout, buildColonySpec, summarizeLayout, validateFixture, countsOf, staffing, stockOf as stockPlan, COLONY_ID,
  type Scale, type Layout, type Staffing, type Trade,
} from "./fixture";
import { SEATS, type Line } from "./network";
import { callingFrom, dressCar, furtherStarts, signalStations } from "./trains";
import type { Stop } from "../e2e/rail-footprint";
import { launchPair, type E2EPair } from "../shared/pair";
import { test } from "@playwright/test";
import {
  errorSummary,
} from "@izakyl/blockwright-client";
import { defineTask, javaTask, reflect, tick, world, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { commissionAll } from "../shared/commission";
import { copyFileSync, existsSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";
import {
  commandPos,
  countResidents,
  isColonyMember,
  waitForGroundedPlayer,
  type Vec,
} from "../e2e/colony-founding";
import { readResidentPack } from "../e2e/world-ui";
import type { Tally } from "./judgements";

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
// Seconds a train stands at each stop: long enough for a platform of riders to take their seats.
const DWELL_S = 8;

// The colony is its humans alone: FOLKWAYS_LC_GOLEMS is the share of it cast as golems instead (0.2 was the mix
// once), which crowd the works with little to do.
const POPULATION = populationSlots(Number(process.env.FOLKWAYS_LC_RESIDENTS ?? 400), Number(process.env.FOLKWAYS_LC_GOLEMS ?? 0));
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

const ALL_VOCATIONS = [
  "folkways:farming", "folkways:herding", "folkways:building", "folkways:crafting",
  "folkways:hauling", "folkways:fishing", "folkways:conducting", "folkways:dispatching",
  // Human residents do not patrol: the golems keep the watch, and no human is kitted with a weapon for it.
  "folkways:patrolling",
] as const;

// Who does what, from the town's work (fixture.staffing): set once the town is laid out.
let STAFF: Staffing = [];

const UNEQUIPPED_SHARE = 0;
// Twelve loaves nourish sixty points: three days at a belly a day.
const STARTING_BREAD = 12;

// How long the colony runs once it is set: FOLKWAYS_LC_DAYS Minecraft days (fractions allowed, at most ten), or
// FOLKWAYS_LC_TICKS exactly. Three days by default: three nights' sleep and a harvest come round.
const DAY_TICKS = 24_000;
const MOST_DAYS = 10;
const RUN_TICKS = runTicks();
const POLL_MS = 2_000;
// The wall-clock time the run may take: a server down to a quarter of real speed still finishes, and never less than
// the 25 minutes a short run always had.
const RUN_BUDGET_MS = Math.max(25 * 60_000, RUN_TICKS * 50 * 4);
const realtimePollMs = (ticks: number) => Math.min(200, Math.max(50, Math.round((ticks * 50) / 4)));
const REALTIME_SLOWDOWN_CAP = 40;
const STALL_MS = 30_000;
// A run is ended early, and reported and filmed as far as it got, by making this file while it runs:
//   touch tests/out/bench/stop
const STOP_FILE = outAbs("bench", "reports", "..", "stop");

// Crops and saplings grow this many times as fast as in play (FOLKWAYS_LC_GROWTH, 1 for the game's own pace): a run
// of a day or so then sees fields ripen and the grove grow back more than once, and the farmers and fellers at them.
const GROWTH = Number(process.env.FOLKWAYS_LC_GROWTH ?? 4);
if (!(GROWTH >= 1 && GROWTH <= 20)) throw new Error(`FOLKWAYS_LC_GROWTH must be from 1 to 20, not ${process.env.FOLKWAYS_LC_GROWTH}`);
const RANDOM_TICK_SPEED = Math.round(3 * GROWTH);

const ANIMALS = ["sheep", "cow", "chicken", "pig"] as const;

function runTicks() {
  if (process.env.FOLKWAYS_LC_TICKS) return Math.max(1, Math.round(Number(process.env.FOLKWAYS_LC_TICKS)));
  const days = Number(process.env.FOLKWAYS_LC_DAYS ?? 3);
  if (!(days > 0) || days > MOST_DAYS) throw new Error(`FOLKWAYS_LC_DAYS must be above 0 and at most ${MOST_DAYS}, not ${process.env.FOLKWAYS_LC_DAYS}`);
  return Math.round(days * DAY_TICKS);
}

test(`large colony: ${SCALE.residents} residents in a town on real terrain (reflection/NBT fixture, no UI)`, async ({}, testInfo) => {
  const scale = SCALE;
  // Raising and stocking the town takes the bench budget; the run itself comes on top.
  test.setTimeout(BUDGET.bench + RUN_BUDGET_MS);
  ensureOut("bench");
  const runId = newRunId();
  const serverInstance = `large-colony-server-${runId}`;
  const jsonPath = outAbs("bench", "reports", "large-colony.json");

  const report: any = {
    schema_version: "0.4.0",
    generated_at: new Date().toISOString(),
    scale,
    scope: "Folkways whole-colony flow engine run at real scale in a grid city raised on the flattest generated terrain in reach, with a railway out to mines at a mountain's foot; every tick and every work "
      + "report is kept raw (report.raw) and nothing is summarised yet; fixtures use reflection + NBT, not the real UI",
    environment: { config: CONFIG, heap: "3G", server_view_distance: 20, recording: FILMING, shaders: SHADERS,
      roaming_cameras: FILMING ? ROAMERS : 0,
      terrain: "Terralith biomes over Tectonic landforms (Lithostitched), seed folkways-promo-land, no Distant Horizons" },
    fixture: "reflection+NBT (summon residents / data-merge colony_member / write folkways_colonies.dat + reflect cache.remove reload)",
    setup: {},
    perf: {},
    caveats: [],
  };

  let pair: E2EPair | undefined;
  let server: MinecraftServer;
  let client: MinecraftClient;
  let video: any;
  let cameras: Camera[] = [];
  let directing: Directing | undefined;
  const clientInstance = `large-colony-client-${runId}`;

  // The town is raised once and kept (town-cache.ts): with a kept one, its world goes in before the server starts.
  const townAt = townKey(CONFIG, countsOf(scale), RAIL_ENABLED);
  const keptTown = hasTown(townAt);

  try {
    // The colony plans four times as far ahead as the mod does by default (FOLKWAYS_LC_HORIZON, in ticks): getting
    // out to the quarry by train takes about the mod's own 3,000, and at that horizon the schedule puts the quarry
    // off for good once everyone's route holds a few hauls. FOLKWAYS_LC_HORIZON_DEPTH sets the horizon's depth too.
    const labor: Record<string, number> = { planHorizonTicks: Number(process.env.FOLKWAYS_LC_HORIZON ?? 12_000) };
    if (process.env.FOLKWAYS_LC_HORIZON_DEPTH) labor.planHorizonDepth = Number(process.env.FOLKWAYS_LC_HORIZON_DEPTH);
    report.environment.labor = labor;
    const serverDir = seededOffThreadServer(serverInstance, undefined, undefined, labor);
    seedFilmClient(clientInstance);
    if (keptTown) seedWorld(townAt, serverInstance);
    pair = await launchPair({
      config: CONFIG,
      server: { trace: tracePath("bench", "large-colony-server"), instance: serverDir },
      client: { trace: tracePath("bench", "large-colony-client"), instance: clientInstance },
    });
    ({ server, client } = pair);

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    const playerUuid = (await world.players(server))[0]?.uuid;
    await tryCommand(server, `gamemode survival ${playerName}`);
    // Both recorders keep everything from here to the end: every tick, and every work report the colony makes.
    await installTickMeter(server);
    await installWorkTap(server);
    // The census writes its count of the colony's labor to disk once a game second, as it goes.
    await installCensus(server, COLONY_ID, outAbs("bench", "reports", "large-colony.census.ndjson"));

    let town: Town;
    if (keptTown) {
      const kept = loadTown(townAt);
      town = kept.town;
      report.setup.town = { ...kept.notes, kept: { key: townAt, reused: true, save: townSave() ?? null } };
    } else {
      report.setup.town = {};
      town = await raiseTown(server, { x: Math.round(grounded.pos.x), z: Math.round(grounded.pos.z) }, countsOf(scale),
        (key, value) => { report.setup.town[key] = value; }, { rail: RAIL_ENABLED });
      const notes = { ...report.setup.town };
      report.setup.town.kept = { ...await keepTown(server, serverInstance, townAt, town, notes), reused: false };
    }
    const layout = buildLayout(town, scale);
    const origin: Vec = layout.origin;
    report.setup.origin = origin;
    report.setup.fixture_validation = validateFixture(layout);
    report.setup.layout_summary = summarizeLayout(layout);
    writeFileSync(outAbs("bench", "reports", "large-colony.layout.json"), JSON.stringify(layout));
    STAFF = staffing(layout, POPULATION.humans);
    const net = layout.network;
    // The player stands in the plaza, the town being far from where the world spawned it: on open paving, not in the
    // well at its middle, and in creative, so nothing about the set can hurt it while it is built.
    const stand = layout.spawns.reduce((best, c) => Math.hypot(c.x - origin.x, c.z - origin.z) < Math.hypot(best.x - origin.x, best.z - origin.z)
      && Math.max(Math.abs(c.x - origin.x), Math.abs(c.z - origin.z)) > 4 ? c : best, layout.spawns[0]);
    await tryCommand(server, `gamemode creative ${playerName}`);
    await tryCommand(server, `tp ${playerName} ${stand.x + 0.5} ${stand.y} ${stand.z + 0.5}`);
    await forceloadTown(server, layout.extent);
    for (const d of layout.districts) await forceloadTown(server, d.box);
    report.perf_controls = { p0_town_no_colony: await onRealClock(server, () => tickCostAt(server, playerUuid)),
      note_p0: "P0 runs with the town standing but before any resident, animal or colony data exists; its ticks are the rows of "
        + "the tick meter between from and to." };

    report.setup.world = await stockTown(server, layout);
    report.setup.staffing = STAFF.map(({ trade, size, basis }) => ({ trade: trade.name, size, basis }));
    report.setup.pen_fences = await probePenFences(server, layout.pens);

    report.setup.membership = await setMembershipAttachments(server, layout);

    const residentUuids = newUuids(POPULATION.humans);
    const spec = buildColonySpec(layout, residentUuids);
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

    // The railway: the lines were drawn when the town was raised; each gets its stations, its car and its timetable
    // on the track already down.
    const trains: Array<{ line: Line; rail: RailResult; first: Stop }> = [];
    if (net && net.lines.length) {
      report.setup.rail = { enabled: true, centre: net.centre, lines: [], refused: net.refused };
      await tryCommand(server,
        `item replace entity ${playerName} inventory.0 with folkways:colony_book[folkways:colony_id=${uuidToSnbt(COLONY_ID)}] 1`);
      for (const [index, line] of net.lines.entries()) {
        const haltBy = (stop: Stop) => {
          const halt = line.halts.find((h) => h.stop === stop.name)!.halt.box;
          return { x: Math.round((halt.minX + halt.maxX) / 2), y: stop.position.y + 1, z: Math.round((halt.minZ + halt.maxZ) / 2) };
        };
        const dwell = Object.fromEntries(line.stops.map((s) => [s.name, DWELL_S]));
        const record = (rail: RailResult, first: Stop) => {
          report.setup.rail.lines.push({ name: line.name, first: first.name, ok: rail.ok, assembled: rail.assembled,
            scheduled: rail.scheduled, handed_over: rail.handedOver, carriage: rail.carriageUuid, length: line.length,
            stations: rail.stations, works: line.works, error: rail.error, steps: rail.steps });
          if (rail.ok) trains.push({ line, rail, first });
          else report.caveats.push(`rail: a ${line.name} train is not running (assembled=${rail.assembled} scheduled=${rail.scheduled} handedOver=${rail.handedOver}): ${rail.error ?? ""}`);
        };
        // The first train, built where the line's stations go up; then each further one behind a stop further round,
        // on stations already standing.
        try {
          const [first, ...stops] = line.stops;
          record(await buildRailLine(server, client, playerName, playerUuid, line.origin, 0, "inventory.0", {
            cyclic: true, laid: true, passengerSeats: SEATS, names: [first.name], stops, dwell, anchor: haltBy(first),
            decorate: dressCar(index), clickThrough: true }), first);
        } catch (e) {
          report.setup.rail.lines.push({ name: line.name, error: errorSummary(e) });
          report.caveats.push(`rail: building the ${line.name} line failed: ${errorSummary(e)}`);
          continue;
        }
        for (const start of furtherStarts(line)) {
          try {
            const [, ...stops] = callingFrom(line, start);
            record(await buildRailLine(server, client, playerName, playerUuid, start.position, 0, "inventory.0", {
              cyclic: true, laid: true, first: start, standing: true, passengerSeats: SEATS, stops, dwell,
              anchor: haltBy(start), decorate: dressCar(index), clickThrough: true }), start);
          } catch (e) {
            report.caveats.push(`rail: building a further ${line.name} train at ${start.name} failed: ${errorSummary(e)}`);
          }
        }
        report.setup.rail.signals ??= {};
        report.setup.rail.signals[line.name] = await signalStations(server, line.stops)
          .catch((error: unknown) => ({ error: errorSummary(error) }));
      }
      report.setup.rail.ok = trains.length > 0;
      report.setup.rail.graphs = await sampleRailGraphs(server, net.lines.flatMap((l) => l.stops),
        JSON.parse(process.env.FOLKWAYS_LC_RAIL_GAPS ?? "[]"))
        .catch((error: unknown) => ({ error: errorSummary(error) }));
      await tryCommand(server, `tp ${playerName} ${stand.x + 0.5} ${stand.y} ${stand.z + 0.5}`);
    } else {
      report.setup.rail = { enabled: RAIL_ENABLED, lines: [], refused: net?.refused ?? [] };
      if (RAIL_ENABLED) report.caveats.push(`rail: the land allows no line here (${(net?.refused ?? []).map((r) => `${r.what}: ${r.why}`).join("; ")}); everything stands in town.`);
    }

    // Day turns to night as it would in play: the run starts at sunrise, the residents beside their beds.
    await world.command(server, "gamerule doDaylightCycle true");
    await world.command(server, "gamerule doWeatherCycle false");
    await world.command(server, "gamerule doMobSpawning false");
    await world.command(server, `gamerule randomTickSpeed ${RANDOM_TICK_SPEED}`);
    const growing = await runColonyTask(server, TICK_TOWN, { boxes: [layout.extent, ...layout.districts.map((d) => d.box)] });
    await world.command(server, "weather clear");
    await world.command(server, "time set day");
    report.setup.daylight = { fixed: false, starts: "day (1000)", run_ticks: RUN_TICKS, run_days: RUN_TICKS / DAY_TICKS,
      scope: "The daylight cycle runs, so a long enough run takes in dusk and the night's sleep; weather is held clear and no mob spawns." };
    report.setup.growth = { times: GROWTH, random_tick_speed: RANDOM_TICK_SPEED, ticking_chunks: growing.chunks,
      scope: "Random ticks come this many times as often as in play, so whatever grows by them does: crops, saplings, grass, leaves. "
        + "The town's chunks are held ticking, with or without a player by them." };
    await settle(server, 20);

    await tryCommand(server, "gamerule maxEntityCramming 0");
    // Everyone starts the day at home beside their bed, and each train's drivers on its first platform. Golems stand about the town.
    const candidates = layout.spawns.map(p => ({ ...p, kind: "human" }));
    for (const g of POPULATION.golems) candidates[g.index].kind = g.kind;
    let drivers = 0;
    for (let i = 0; i < residentUuids.length; i++) {
      const name = tradeOf(i).name;
      const slot = POPULATION.humanIndices[i];
      const home = layout.homes[i] ?? layout.spawns[slot];
      candidates[slot] = { ...home, kind: "human" };
      if (name === "driver" && trains.length) {
        // On the halt beside the car's first platform.
        const { line, first } = trains[drivers % trains.length];
        const halt = line.halts.find((h) => h.stop === first.name)!.halt;
        const k = Math.floor(drivers / trains.length);
        candidates[slot] = { x: halt.box.minX + 2 + (k % 3) * 2, y: halt.y, z: halt.box.minZ + 2 + Math.floor(k / 3) * 2, kind: "human" };
        drivers++;
      }
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
    report.setup.trades = STAFF.map(({ trade, start, size }) => ({
      name: trade.name, head_count: size, first_index: start, tools: trade.tools,
      keen: Object.entries(trade.priorities).filter(([, p]) => p === 1).map(([v]) => v),
    }));
    report.setup.unequipped = unequippedUntil;

    report.setup.trade_checks = await verifyTrades(server, residentUuids);
    report.perf_controls.note = "P1 (residents with no work) has no matching stage in the reflection/NBT flow: the colony loads with all stations/zones/orders at once, so it is skipped and only the P0/P2 delta is kept";

    report.setup.integrations = await setupIntegrations(server, playerName, COLONY_ID, layout.origin, layout.outputChest,
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
    // Who each worker in the work tap's rows is: a human's trade, or a golem's kind.
    report.setup.roster = Object.fromEntries([
      ...residentUuids.map((id, i) => [id, tradeOf(i).name]),
      ...(integrations.golems.uuids as string[]).map((id, i) => [id, `golem:${POPULATION.golems[i].kind}`]),
    ]);
    // The long work, commissioned on the land the way a player draws it.
    report.setup.orders = await commissionAll(server, COLONY_ID, layout.orders.map(({ why: _, ...o }) => o))
      .then((out) => out.said.map((s, i) => ({ ...s, name: layout.orders[i].name, why: layout.orders[i].why })))
      .catch((error: unknown) => ({ error: errorSummary(error) }));
    for (const o of Array.isArray(report.setup.orders) ? report.setup.orders : [])
      if (!o.filed) report.caveats.push(`order: ${o.name} (${o.pattern}) was not filed: ${o.said}`);
    report.perf_controls.p2_full_setup = await tickCostAt(server, uuids[0] ?? playerUuid);

    const holdsThings: Vec[] = [
      ...layout.chests, ...layout.chestHalves.map((c) => c.other), ...layout.furnaces, ...layout.stonecutters,
      ...layout.tables,
    ];
    const state = () => worldState(server, layout, integrations, uuids, trains);
    report.conservation = { note: TALLY_NOTE };
    await tick.freeze(server, true);
    report.conservation.before = await tallyWorld(server, holdsThings, playerUuid);
    report.start_state = await state();

    if (FILMING) {
      // The camera takes in the city and as much of the railway as stays legible: the line's near half.
      const boxes = [layout.extent, ...(net?.lines ?? []).map((l) => ({ minX: Math.min(...l.points.map((p) => p.x)),
        maxX: Math.max(...l.points.map((p) => p.x)), minZ: Math.min(...l.points.map((p) => p.z)), maxZ: Math.max(...l.points.map((p) => p.z)) }))];
      const all = { minX: Math.min(...boxes.map((b) => b.minX)), maxX: Math.max(...boxes.map((b) => b.maxX)),
        minZ: Math.min(...boxes.map((b) => b.minZ)), maxZ: Math.max(...boxes.map((b) => b.maxZ)) };
      // The overview looks west along the city's short side, from close enough that the city more than fills the frame.
      const e = layout.extent, span = 0.45;
      const cx = (e.minX + e.maxX) / 2, cz = (e.minZ + e.maxZ) / 2, hx = (e.maxX - e.minX) / 2 * span, hz = (e.maxZ - e.minZ) / 2 * span;
      const extent = { minX: Math.max(all.minX, Math.round(cx - hx)), maxX: Math.min(all.maxX, Math.round(cx + hx)),
        minZ: Math.max(all.minZ, Math.round(cz - hz)), maxZ: Math.min(all.maxZ, Math.round(cz + hz)) };
      // The further cameras join only now, so they stand over the town from the first and load nothing else.
      const joined = await launchCameras(pair.game, runId);
      cameras = joined.cameras;
      for (const why of joined.failed) report.caveats.push(`film: ${why}`);
      const main: Camera = { name: "overview", client, player: playerName, instanceId: clientInstance };
      // Where the roaming cameras may stand is found now, on the still world; each starts on one of the first spots.
      const scouted = cameras.length ? await scout(server, roamViews(layout)) : { spots: [], blind: [] };
      if (cameras.length && scouted.spots.length < cameras.length) {
        report.caveats.push(`film: only ${scouted.spots.length} spots have a clear view, so no camera roams`);
        await closeCameras(cameras);
        cameras = [];
      }
      const stands = cameras.map((_, i) => scouted.spots[i]);
      video = await startFilm(main, server, extent, origin.y, cameras,
        stands.map((s) => ({ name: s.name, eye: s.eye, underground: s.underground, ...cameraLookingAt(s.eye, s.target) })));
      video.roaming = { spots: scouted.spots.map((s) => s.name), blind: scouted.blind };
      if (cameras.length) {
        const feed = outAbs("bench", "reports", "large-colony.film-feed.csv");
        await installFilmFeed(server, feed, COLONY_ID);
        directing = direct(server, cameras, scouted.spots, stands, feed);
      }
      if (video.warning) report.caveats.push(`film: ${video.warning}`);
      video.shaders_checked = filmShaders([main, ...cameras]);
      for (const [name, check] of Object.entries<any>(video.shaders_checked))
        if (!check.ok) report.caveats.push(`film: the ${name} camera filmed without shaders: ${check.error}`);
      report.video = video;
    }
    // The colony runs on the real clock, never frozen or probed while it does; the recorders keep what happens. A
    // roaming camera is teleported every so often, which is all the film asks of the server meanwhile.
    report.run = await runFor(server, RUN_TICKS);
    if (report.run.stopped) {
      report.caveats.push(`the run was stopped by hand after ${report.run.ran}/${RUN_TICKS} ticks (${STOP_FILE})`);
    } else if (!report.run.completed) {
      report.caveats.push(`the run hit the wall-clock budget (${RUN_BUDGET_MS / 60000} min) after ${report.run.ran}/${RUN_TICKS} ticks: `
        + "the server ran slower than the budget allows.");
    }

    if (video) {
      const filmed = video, directed = await directing?.stop();
      video = undefined;
      directing = undefined;
      for (const why of directed?.notes ?? []) report.caveats.push(`film: ${why}`);
      report.video = await finishFilm(filmed, directed?.cuts);
    }
    await closeCameras(cameras);
    cameras = [];
    report.conservation.after = await tallyWorld(server, holdsThings, playerUuid);
    report.end_state = await state();
    report.end_state.path_queue = await samplePathQueue(server);
    report.end_state.rail_reach = await sampleRailReach(server).catch((error: unknown) => ({ error: errorSummary(error) }));
    report.end_state.loose_pieces = await sampleLoosePieces(server).catch((error: unknown) => ({ error: errorSummary(error) }));
    // The server's own log goes with the report: its heartbeats tell when the ways were renumbered or fell in pieces.
    try { copyFileSync(path.join(REPO_ROOT, ".blockwright", "instances", serverDir, "logs", "latest.log"), outAbs("bench", "reports", "large-colony.server.log")); } catch { /* no log yet */ }
    report.perf.bw_trace = await performanceTrace(server);
    // Show the working settlement after residents have dispersed and the builders have
    // made progress, rather than a frozen population still standing at its spawn marks.
    if (FILMING) {
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
        try { report.video = await finishFilm(video, (await directing?.stop())?.cuts); }
        catch (error) { report.video = { ...video, error: errorSummary(error), films: (error as any)?.films }; report.fatal ??= errorSummary(error); }
        video = undefined;
      }
      await closeCameras(cameras);
      // What the recorders kept, written out as it was kept, and the server log beside it.
      if (pair) {
        const server = pair.server;
        report.raw = {
          ticks: await dumpTicks(server, outAbs("bench", "reports", "large-colony.ticks.csv"))
            .catch((error: unknown) => ({ error: errorSummary(error) })),
          tap: await dumpWorkTap(server, { work: outAbs("bench", "reports", "large-colony.work.csv"),
            exertion: outAbs("bench", "reports", "large-colony.exertion.csv"),
            yield: outAbs("bench", "reports", "large-colony.yield.csv") })
            .catch((error: unknown) => ({ error: errorSummary(error) })),
          census: await censusLines(server).catch((error: unknown) => ({ error: errorSummary(error) })),
          notes: { ticks: TICK_METER_NOTE, tap: WORK_TAP_NOTE, census: CENSUS_NOTE },
        };
      }
      const logPath = path.join(REPO_ROOT, ".blockwright", "instances", serverInstance, "logs", "latest.log");
      try { copyFileSync(logPath, outAbs("bench", "traces", "server.log")); }
      catch (error) { report.caveats.push("server log unavailable: " + errorSummary(error)); }
      writeFileSync(jsonPath, JSON.stringify(report, null, 2));
      console.log(`\ntests/bench/large-colony · ${report.run?.ran ?? 0}/${RUN_TICKS} ticks run · report ${jsonPath}\n`);
      if (report.fatal || !(report.run?.completed || report.run?.stopped)) {
        throw new Error(`large-colony run incomplete: ${report.fatal ?? `only ${report.run?.ran ?? 0}/${RUN_TICKS} ticks ran`}`);
      }
    } catch (error) {
      pair?.failing(error);
      throw error;
    } finally {
      await pair?.teardown(testInfo);
    }
  }
});

// A lot from close by: from above one of its sides or corners, whichever sees most of it.
function closeOn(name: string, lot: Layout["lots"][number], up: number, back: number): Shot {
  const c = { x: (lot.box.minX + lot.box.maxX) / 2, y: lot.y, z: (lot.box.minZ + lot.box.maxZ) / 2 };
  const poses = [up + 4, up + 10, up + 16].flatMap(height =>
    [[lot.w / 2 + 8, lot.d + back], [-back, lot.d / 2], [lot.w + back, lot.d / 2],
      [lot.w / 2, -back], [lot.w + back, lot.d + back], [-back, -back]]
      .map(([u, v]) => lot.frame.at(u, height, v)));
  return { name, eye: poses[0], alternatives: poses.slice(1), subject: lot.box, target: { ...c, y: c.y + 3 } };
}

// How high above a kind of lot, and how far back from it, a close view stands.
const CLOSE: Record<string, [number, number]> = { lodge: [16, 14], paddock: [18, 12], field: [20, 14], garden: [14, 10], house: [14, 12] };

// Where a roaming camera may stand: the open work floor, the mines, the stations, and every lot from close by.
function roamViews(layout: Layout): Shot[] {
  return [...yardsView(layout), ...mineView(layout), ...stationViews(layout),
    ...layout.lots.filter((l) => l.kind !== "halt").map((l) => closeOn(l.name, l, ...(CLOSE[l.kind] ?? [16, 12])))];
}

function heroViews(layout: Layout): Shot[] {
  const e = layout.extent, y = layout.y, o = layout.origin;
  const close = (name: string, kind: string, up: number, back: number) => {
    const lot = layout.lots.find((l) => l.kind === kind);
    return lot ? [closeOn(name, lot, up, back)] : [];
  };
  const plaza = layout.lots.find((l) => l.kind === "plaza");
  const half = Math.max(e.maxX - e.minX, e.maxZ - e.minZ) / 2;
  return [
    // Near enough that the city fills the frame: the eye stands off by its half side.
    { name: "town", eye: { x: (e.minX + e.maxX) / 2 - half * 0.45, y: y + half * 1.05, z: (e.minZ + e.maxZ) / 2 + half * 0.85 },
      target: { x: (e.minX + e.maxX) / 2 - half * 0.05, y: y, z: (e.minZ + e.maxZ) / 2 + half * 0.2 } },
    ...yardsView(layout), ...mineView(layout).slice(0, 1),
    { name: "plaza", eye: { x: o.x + 22, y: y + 14, z: o.z + 26 }, target: o, subject: plaza && plaza.box,
      alternatives: ring(o, [30, 40], [14, 22]) },
    ...stationViews(layout),
    ...close("lodges", "lodge", ...CLOSE.lodge), ...close("pastures", "paddock", ...CLOSE.paddock),
    ...close("terraces", "field", ...CLOSE.field), ...close("gardens", "garden", ...CLOSE.garden), ...close("quarter", "house", ...CLOSE.house),
  ];
}

const YARD_KINDS = new Set(["works", "store", "yard"]);

// Eyes on rings round a target, from the south-east round: the first of them that sees the subject is taken.
function ring(c: Vec, radii: number[], heights: number[]): Vec[] {
  return heights.flatMap((h) => radii.flatMap((r) => Array.from({ length: 8 }, (_, i) => {
    const a = Math.PI / 4 + i * Math.PI / 4;
    return { x: Math.round(c.x + r * Math.cos(a)), y: c.y + h, z: Math.round(c.z + r * Math.sin(a)) };
  })));
}

// The town's open work floor: the works, with whatever store or yard stands by it.
function yardsView(layout: Layout): Shot[] {
  const o = layout.origin;
  const mid = (l: Layout["lots"][number]) => ({ x: (l.box.minX + l.box.maxX) / 2, y: l.y, z: (l.box.minZ + l.box.maxZ) / 2 });
  const yards = layout.lots.filter((l) => YARD_KINDS.has(l.kind) && Math.hypot(mid(l).x - o.x, mid(l).z - o.z) < 160);
  if (!yards.length) return [];
  const near = (a: Layout["lots"][number]) => yards.filter((b) => Math.hypot(mid(a).x - mid(b).x, mid(a).z - mid(b).z) <= 40);
  const group = yards.map(near).sort((a, b) => b.length - a.length)[0];
  const subject = { minX: Math.min(...group.map((l) => l.box.minX)), maxX: Math.max(...group.map((l) => l.box.maxX)),
    minZ: Math.min(...group.map((l) => l.box.minZ)), maxZ: Math.max(...group.map((l) => l.box.maxZ)) };
  const target = { x: (subject.minX + subject.maxX) / 2, y: Math.round(group.reduce((n, l) => n + l.y, 0) / group.length),
    z: (subject.minZ + subject.maxZ) / 2 };
  const reach = Math.max(subject.maxX - subject.minX, subject.maxZ - subject.minZ);
  const [eye, ...alternatives] = ring(target, [reach * 0.55 + 10, reach * 0.75 + 14], [30, 40]);
  return [{ name: "yards", eye, alternatives, subject, target: { ...target, y: target.y + 1 } }];
}

// The quarry from above one of its sides, whichever sees most of it; a shaft mine from inside its hill, a spectator
// in the rock beside the shaft, looking across at the stair as it is dug.
function mineView(layout: Layout): Shot[] {
  const dug = layout.orders.filter((o: any) => (o.pattern === "folkways:quarry" || o.pattern === "folkways:mine") && o.min && o.max);
  return dug.map((mine: any, i): Shot => {
    const c = { x: (mine.min.x + mine.max.x) / 2 + 0.5, z: (mine.min.z + mine.max.z) / 2 + 0.5 }, top = mine.min.y;
    if (mine.pattern === "folkways:mine")
      return { name: i ? `mine-${i + 1}` : "mine", eye: { x: c.x + 15, y: top - 8, z: c.z + 6 }, target: { x: c.x, y: top - 14, z: c.z }, underground: true };
    const target = { x: c.x, y: top, z: c.z }, reach = mine.max.x - mine.min.x;
    const [eye, ...alternatives] = ring(target, [reach * 0.75, reach], [28, 40]);
    return { name: i ? `quarry-${i + 1}` : "quarry", eye, alternatives, target,
      subject: { minX: mine.min.x, maxX: mine.max.x, minZ: mine.min.z, maxZ: mine.max.z } };
  });
}

// Each station from its platform's side of the track, so the train that stands there does not hide who gets on and
// off it: looking along the platform from one end or the other, or failing those from over an end of it.
function stationViews(layout: Layout): Shot[] {
  const net = layout.network;
  if (!net) return [];
  const shots: Shot[] = [];
  for (const l of net.lines) for (const [i, stop] of l.stops.entries()) {
    const s = stop.position, halt = l.halts.find((h) => h.stop === stop.name)?.halt.box;
    const name = i ? `station-${stop.name.toLowerCase()}` : "central-station";
    if (!halt) {
      shots.push({ name, eye: { x: s.x + 26, y: s.y + 18, z: s.z + 30 }, target: { x: s.x, y: s.y + 1, z: s.z }, station: stop.name,
        alternatives: ring(s, [30, 44], [16, 26]) });
      continue;
    }
    // The platform lies along the track, on one side of it.
    const c = { x: (halt.minX + halt.maxX) / 2, z: (halt.minZ + halt.maxZ) / 2 };
    const alongX = halt.maxX - halt.minX > halt.maxZ - halt.minZ;
    const side = Math.sign(alongX ? c.z - s.z : c.x - s.x) || 1;
    const at = (out: number, along: number, up: number): Vec => ({ x: Math.round(c.x + (alongX ? along : side * out)), y: s.y + up,
      z: Math.round(c.z + (alongX ? side * out : along)) });
    const [eye, ...alternatives] = [...[14, 22].flatMap((up) => [20, -20].map((along) => at(20, along, up))),
      ...[30, -30].map((along) => at(6, along, 24))];
    shots.push({ name, eye, alternatives, subject: halt, station: stop.name,
      target: { x: (c.x + s.x) / 2, y: s.y + 1, z: alongX ? (c.z + s.z) / 2 : c.z } });
  }
  return shots;
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

// The game ticks a chunk's blocks only with a player within 128 blocks who is no spectator, and every camera of the
// film is one: the town's chunks are held ticking whoever stands by, so its crops and saplings grow all the same.
const TICK_TOWN = defineTask<{ boxes: Array<{ minX: number; maxX: number; minZ: number; maxZ: number }> }, Record<string, any>>({
  name: "folkways.bench.tick-town",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.server.level.TicketType", "net.minecraft.world.level.ChunkPos"],
    body: `
var level = ctx.server().overworld();
var held = new HashSet<ChunkPos>();
for (var raw : Args.of(ctx).list("boxes")) {
  var box = (Map<?, ?>) raw;
  int minX = ((Number) box.get("minX")).intValue() >> 4, maxX = ((Number) box.get("maxX")).intValue() >> 4;
  int minZ = ((Number) box.get("minZ")).intValue() >> 4, maxZ = ((Number) box.get("maxZ")).intValue() >> 4;
  for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
    var pos = new ChunkPos(x, z);
    if (held.add(pos)) level.getChunkSource().addRegionTicket(TicketType.FORCED, pos, 2, pos, true);
  }
}
return Map.of("ok", true, "chunks", held.size());
`,
  }),
});

async function forceloadTown(server: MinecraftServer, e: { minX: number; maxX: number; minZ: number; maxZ: number }) {
  for (let x = Math.floor(e.minX / 16); x <= Math.floor(e.maxX / 16); x += 8)
    for (let z = Math.floor(e.minZ / 16); z <= Math.floor(e.maxZ / 16); z += 8)
      await tryCommand(server, `forceload add ${x * 16} ${z * 16} ${Math.min(Math.floor(e.maxX / 16), x + 7) * 16} ${Math.min(Math.floor(e.maxZ / 16), z + 7) * 16}`);
}

// Fills the town's chests as fixture.stockOf plans them: raw goods in the stores, the yards with what the colony
// cannot make and a share of what it can. A chest planned twice gets both, in order.
async function stockTown(server: MinecraftServer, layout: Layout) {
  const plan = stockPlan(layout);
  const byChest = new Map<string, { chest: Vec; entries: Array<[string, number]> }>();
  for (const { chest, entries } of plan) {
    const k = `${chest.x},${chest.y},${chest.z}`;
    const at = byChest.get(k) ?? { chest, entries: [] };
    at.entries.push(...entries);
    byChest.set(k, at);
  }
  let stacks = 0, dropped = 0;
  const totals: Record<string, number> = {};
  // A double chest is kept at one half: its first twenty-seven stacks go there and the rest in the other half.
  const otherHalf = new Map(layout.chestHalves.map((h) => [`${h.anchor.x},${h.anchor.y},${h.anchor.z}`, h.other]));
  for (const { chest, entries } of byChest.values()) {
    const other = otherHalf.get(`${chest.x},${chest.y},${chest.z}`);
    for (const [slot, [item, count]] of entries.entries()) {
      const into = slot < 27 ? chest : slot < 54 ? other : undefined;
      if (!into) { dropped++; continue; }
      await tryCommand(server, `item replace block ${commandPos(into)} container.${slot % 27} with ${item} ${count}`);
      stacks++;
      totals[item] = (totals[item] ?? 0) + count;
    }
  }
  if (dropped) throw new Error(`${dropped} stacks of the town's stock have no chest to go in`);
  return {
    placed: {
      cookers: layout.cookers.length, stonecutters: layout.stonecutters.length, tables: layout.tables.length,
      chests: layout.chests.length, beds: layout.beds.length, campfires: layout.campfires.length,
    },
    stocked: { chests: byChest.size, stacks, items: totals },
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

function tradeOf(index: number): Trade {
  for (const segment of STAFF) {
    if (index < segment.start + segment.size) return segment.trade;
  }
  return STAFF[STAFF.length - 1].trade;
}

function residentNbt(uuid: string, colonyIA: string, trade: Trade, equipped: boolean): string {
  const priorities = Object.entries(trade.priorities).map(([vocation, p]) => `"${vocation}":${p}`).join(",");
  const withheld = ALL_VOCATIONS.filter((v) => !trade.priorities[v]).map((v) => `"${v}"`).join(",");
  // Each comes with its trade's tools and three days' bread (LivingPresence.RATION_DAYS at Hunger.FOOD_PER_DAY).
  const items = equipped
    ? [...trade.tools.map((tool, slot) => `{Slot:${slot}b,Stack:{id:"${tool}",count:1}}`),
      `{Slot:${trade.tools.length}b,Stack:{id:"minecraft:bread",count:${STARTING_BREAD}}}`].join(",")
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
  for (const { trade, start, size } of STAFF) {
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

// Every house, each cell of its blueprint against the block standing there (dug cells against air).
async function probeHouses(server: MinecraftServer, layout: Layout) {
  const out: any[] = [];
  for (const h of layout.houses) out.push({ lot: h.lot, style: h.style, ...await probeConstruction(server, h.anchor, h.blocks) });
  return { houses: out, built: out.reduce((n, h) => n + h.built, 0), total: out.reduce((n, h) => n + h.total, 0) };
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

// Runs 40 ticks on the real clock and says which game times they were; the tick meter kept each of them.
async function tickCostAt(server: MinecraftServer, uuid: string | null) {
  const from = (await world.time(server)).gameTime;
  const advanced = await advance(server, 40);
  const to = (await world.time(server)).gameTime;
  const vanilla = uuid ? await serverTickNanos(server, uuid) : { method: null, value: null, mspt: null, note: "no usable entity entry point" };
  return { from, to, ticks_advanced: advanced.actual, wall_ms: advanced.wall_ms, mspt_vanilla: vanilla.mspt, method: vanilla.method };
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

// Runs the world on the real clock until `ticks` have passed, then freezes it. All this asks of the server meanwhile
// is the game time, every couple of seconds, to know when to stop and that it still moves. A stop file ends it early.
async function runFor(server: MinecraftServer, ticks: number) {
  const gameTime = async () => (await world.time(server).catch(() => null))?.gameTime ?? null;
  const start = (await world.time(server)).gameTime;
  const wall0 = Date.now();
  let now = start;
  let lastMoveAt = wall0;
  let completed = true, stopped = false;
  rmSync(STOP_FILE, { force: true });
  await tick.freeze(server, false);
  try {
    while (now - start < ticks) {
      const left = (ticks - (now - start)) * 50;
      await new Promise((resolve) => setTimeout(resolve, Math.max(50, Math.min(POLL_MS, left))));
      const read = await gameTime();
      if (read != null && read > now) {
        now = read;
        lastMoveAt = Date.now();
      } else if (Date.now() - lastMoveAt > STALL_MS) {
        throw new Error(
          `on the real clock the world sat at gameTime=${now} for ${Math.round(STALL_MS / 1000)}s without advancing - the server isn't running`
            + " (deadlock, watchdog, or the control channel dropped), it's not just slow",
        );
      }
      if (existsSync(STOP_FILE)) {
        rmSync(STOP_FILE, { force: true });
        completed = false;
        stopped = true;
        break;
      }
      if (Date.now() - wall0 > RUN_BUDGET_MS) {
        completed = false;
        break;
      }
    }
  } finally {
    await tick.freeze(server, true);
  }
  const end = (await gameTime()) ?? now;
  return { start, end, ran: end - start, requested_ticks: ticks, completed, stopped, wall_ms: Date.now() - wall0 };
}

const STORES = { seed: "seedChest", smelt: "smeltChest", food: "foodChest", dairy: "dairyChest" } as const;

// What the world holds, read once before the run and once after it, with the world frozen both times.
async function worldState(server: MinecraftServer, layout: Layout, integrations: any, uuids: string[],
                          trains: Array<{ line: Line; rail: RailResult }>) {
  const stores: Record<string, any> = {};
  for (const [name, role] of Object.entries(STORES)) stores[name] = await probeChest(server, layout[role]);
  const orderOutputs: any[] = [];
  for (const d of layout.demands) orderOutputs.push(await probeChest(server, d.into));
  const bodies = await snapshotBodies(server, COLONY_ID, uuids).catch((error: unknown) => ({ error: errorSummary(error) }) as any);
  return {
    gameTime: (await world.time(server).catch(() => null))?.gameTime ?? null,
    bodies_alive: bodies.loaded_alive ?? null,
    bodies_missing: bodies.workers ? uuids.filter((id) => !bodies.workers[id]) : null,
    stores,
    order_outputs: orderOutputs,
    livestock: await probeLivestock(server),
    shearables: await probeShearables(server),
    houses: await probeHouses(server, layout),
    build: await runColonyTask(server, BUILD_PROGRESS, { colony_id: COLONY_ID }).catch((error: unknown) => ({ error: errorSummary(error) })),
    integrations: await sampleIntegrations(server, integrations).catch((error: unknown) => ({ error: errorSummary(error) })),
    rail: await Promise.all(trains.map(async ({ line, rail }) => ({
      name: line.name,
      train: await sampleTrain(server, rail.carriageUuid!).catch((error: unknown) => ({ error: errorSummary(error) })),
      conductor: await conductorAboard(server, rail.carriageUuid!).catch(() => null),
    }))),
  };
}

// Every build site the colony keeps (blueprints and commissioned work), how far each has come; and every growing
// pattern (the mine), which round it is on and what holds it up.
const BUILD_PROGRESS = defineTask<{ colony_id: string }, Record<string, any>>({
  name: "folkways.bench.build-progress",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["io.github.izakyl.folkways.front.engine.colony.ColonyGround", "io.github.izakyl.folkways.plugins.build.BuildContent"],
    body: `
var colony = ColonyGround.of(ctx.server(), UUID.fromString(Args.of(ctx).string("colony_id"))).orElseThrow();
var presence = colony.service(BuildContent.ID, Object.class).orElseThrow();
var sites = new ArrayList<Object>();
var sitesField = presence.getClass().getDeclaredField("sites"); sitesField.setAccessible(true);
for (var site : ((Map<?, ?>) sitesField.get(presence)).values()) {
  var progress = site.getClass().getDeclaredMethod("progress"); progress.setAccessible(true);
  var seen = (Optional<?>) progress.invoke(site);
  var order = site.getClass().getDeclaredMethod("order"); order.setAccessible(true);
  var row = new LinkedHashMap<String, Object>();
  row.put("order", String.valueOf(order.invoke(site)));
  seen.ifPresent(v -> row.put("view", String.valueOf(v)));
  sites.add(row);
}
var growing = new ArrayList<Object>();
var growingField = presence.getClass().getDeclaredField("growing"); growingField.setAccessible(true);
for (var one : ((Map<?, ?>) growingField.get(presence)).values()) growing.add(String.valueOf(one).substring(0, Math.min(400, String.valueOf(one).length())));
return Map.of("ok", true, "sites", sites, "growing", growing);
`,
  }),
});

// Blockwright's own 15 s performance trace, run after the colony's run, for entity, chunk and memory counts.
async function performanceTrace(server: MinecraftServer) {
  await tick.freeze(server, false);
  try {
    return { trace: await world.performance(server, { durationMs: 15_000, sampleIntervalMs: 250 }),
      note: "15 s more on the real clock after the run, its samples as Blockwright took them. mspt is MC's own tickTimes, "
        + "which leave out the Post phase where Folkways labor runs." };
  } catch (error) {
    return { error: errorSummary(error) };
  }
}

const TALLY_NOTE = "World-wide item tally in four groups: packs / containers / ground / players, read with the world frozen "
  + "before and after the run. If any read hits the query cap, capped is set.";

function numeric(node: any): number | null {
  if (typeof node === "number") return node;
  if (node && typeof node === "object" && typeof node.value === "number") return node.value;
  return null;
}
