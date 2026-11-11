import { expect, test } from "@playwright/test";
import { defineTask, javaTask, tick, world, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { MARK_MEMBERS, runColonyTask } from "../shared/colony/colony-tasks";
import { buildRailLine, conductorAboard, LOST_CARRIAGE, seatedAt, type RailResult } from "../shared/rail/rail-fixture";
import { shaderEvidence } from "../shared/capture/shaders";
import { BUDGET } from "../shared/harness/budgets";
import { commandPos, frames, safeAction, tryCommand } from "../shared/harness/bw-helpers";
import { applyCookeryLooks, prepareCookeryModels, WINE_FOX_MODELS } from "./cookery-cast";
import { COOKERY_CONFIG, setVitals } from "./cookery-scene";
import { buildElevator, readElevator } from "./elevator-stage";
import { admitPromoSettlersFast, foundPromoColonyFast, licenseCrewFast, setMaintainDemandsFast } from "./fast-setup";
import {
  cinematicWorld, launchPromoPair, promoClip, setFov, setTickRate, sleepMs, take, type PromoPair,
} from "./promo-kit";
import {
  buildSubwayStation, CUT_DEPTH, decoratePromoTrain, deliveryChests, fenceTheCut, PROMO_TRAIN_BOUNDS, PROMO_TRAIN_SEATS,
} from "./rail-scenery";
import { enableDebug, prepareClient, readOverlay, readWayGraph, rigMove, rigStop, type RigMove } from "./debug-rig";

// A tour of a small colony with the debug overlay on: a farm, a workshop and its stores, a sleeping loft reached only
// by a Create elevator, and a short subway line whose far station only the train reaches. The camera stops on each
// thing the overlay explains — a resident's card and route, a store's card and its assignees, the stores' outlines,
// and the colony's waypoint graph with the elevator's and the train's crossings in it — while the colony goes about
// its day.

const GUI_SCALE = 3;
const FOV = 70;
const RAIL_LINE = 60;
const LINE_ATTEMPTS = 2;
const RISE = 6;
const GOODS = ["minecraft:pumpkin", "minecraft:melon", "minecraft:hay_block", "minecraft:bricks"];
const CREW: Array<{ role: string; trades: string[] }> = [
  { role: "conductor", trades: ["conducting"] },
  { role: "crafter", trades: ["crafting", "hauling"] },
  { role: "farmer", trades: ["farming", "hauling"] },
  { role: "hungry", trades: ["farming", "hauling"] },
  { role: "rider", trades: ["hauling"] },
  { role: "rider2", trades: ["hauling"] },
  { role: "sleeper", trades: ["hauling"] },
  { role: "sleeper2", trades: ["hauling"] },
];
const STOCK: Array<[string, number]> = [["minecraft:oak_planks", 16], ["minecraft:stick", 16]];

type Crew = Record<string, string>;
type Staged = Awaited<ReturnType<typeof stage>>;

test("debug overlay: a tour of a working colony", async ({}, testInfo) => {
  test.setTimeout(BUDGET.promo);
  const run = take("debug-overlay");
  run.note("verified", false);
  let pair: PromoPair | undefined;
  try {
    let staged!: Staged;
    const retried: RailResult[] = [];
    prepareCookeryModels();
    for (let attempt = 1; ; attempt++) {
      pair = await launchPromoPair(attempt === 1 ? "debug-overlay" : `debug-overlay-${attempt}`,
        { config: COOKERY_CONFIG, models: WINE_FOX_MODELS });
      staged = await stage(pair, run);
      if (staged.rail.ok || staged.rail.error !== LOST_CARRIAGE || attempt === LINE_ATTEMPTS) break;
      retried.push(staged.rail);
      await pair.teardown();
    }
    if (retried.length) run.note("retried", retried);
    expect(staged.rail.ok, JSON.stringify(staged.rail.steps)).toBe(true);
    await tour(run, pair!, staged);
    run.note("shaders", shaderEvidence(pair!.clientInstance));
    run.note("verified", true);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

// ---------------------------------------------------------------------------------------------------------------
// The colony

async function stage(pair: PromoPair, run: ReturnType<typeof take>) {
  const { server, client } = pair;
  const grounded = await waitForGroundedPlayer(server);
  const player = grounded.name;
  await world.command(server, `op ${player}`);
  await cinematicWorld(server);
  await tryCommand(server, "gamerule randomTickSpeed 40");
  await tryCommand(server, "gamerule fallDamage false");
  const o: Vec = { x: Math.round(grounded.pos.x), y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) };
  const at = (x: number, z: number, dy = 0): Vec => ({ x: o.x + x, y: o.y + dy, z: o.z + z });
  const set = (p: Vec, block: string) => world.command(server, `setblock ${commandPos(p)} ${block}`);
  const fill = (a: Vec, b: Vec, block: string) => tryCommand(server, `fill ${commandPos(a)} ${commandPos(b)} ${block}`);
  await tryCommand(server, `forceload add ${o.x - 40} ${o.z - 80} ${o.x + 32} ${o.z + 32}`);
  await tryCommand(server, `kill @e[type=!minecraft:player,x=${o.x},y=${o.y},z=${o.z},distance=..120]`);

  // The elevator's ground origin; its shaft stands at E(-2..2, -6..-2), the loft west of it at E(-10..-3, -8..-1).
  const E = at(8, -2);
  const e = (x: number, z: number, dy = 0): Vec => ({ x: E.x + x, y: E.y + dy, z: E.z + z });
  const floor = RISE - 1;
  await fill(e(-10, -8, floor), e(-3, -1, floor), "minecraft:spruce_planks");
  for (const [x, z] of [[-10, -8], [-10, -1], [-4, -8], [-4, -1]] as const) {
    await fill(e(x, z, 0), e(x, z, floor - 1), "minecraft:stripped_spruce_log[axis=y]");
  }
  await fill(e(-10, -8, floor + 1), e(-10, -1, floor + 1), "minecraft:spruce_fence");
  await fill(e(-10, -1, floor + 1), e(-4, -1, floor + 1), "minecraft:spruce_fence");
  const beds: Vec[] = [];
  for (const x of [-5, -8]) for (const z of [-7, -6, -3, -2]) beds.push(e(x, z, RISE));
  for (const bed of beds) {
    await set(bed, "minecraft:red_bed[part=foot,facing=west]");
    await set({ ...bed, x: bed.x - 1 }, "minecraft:red_bed[part=head,facing=west]");
  }
  await set(e(-4, -1, RISE + 1), "minecraft:lantern");

  // The workshop: a table, the store of planks and sticks, a chest for what is made.
  const table = at(13, 7), store = at(13, 9), output = at(13, 5);
  await fill(at(12, 4, -1), at(14, 10, -1), "minecraft:coarse_dirt");
  await set(table, "minecraft:crafting_table");
  await set(store, "minecraft:chest[facing=west]");
  await set(output, "minecraft:chest[facing=west]");
  await set(at(14, 7), "minecraft:barrel[facing=up]");
  // The larder, under the loft's edge.
  const larder = at(3, 6);
  await set(larder, "minecraft:chest[facing=north]");
  await set(at(4, 6), "minecraft:chest[facing=north]");
  const pantry = at(4, 6);
  // The field, watered from a ditch along each long side, and its seed chest.
  const field = { min: at(-6, 10, -1), max: at(1, 15, -1) };
  await fill(at(-6, 9, -1), at(1, 9, -1), "minecraft:water");
  await fill(at(-6, 16, -1), at(1, 16, -1), "minecraft:water");
  const barn = at(3, 12);
  await set(barn, "minecraft:chest[facing=west]");
  // Where the goods for the far station come from: beside the top of Alpha's stairs.
  const supply = [at(-8, 20), at(-8, 21)];
  for (const chest of supply) await set(chest, "minecraft:chest[facing=west]");

  // A diagnostic, out of every shot: a chest already holding goods when it joins the colony.
  const probe = at(16, -14);
  await set(probe, `minecraft:chest[facing=west]{Items:[{Slot:0b,id:"minecraft:iron_ingot",count:16}]}`);

  await world.command(server, `tp ${player} ${o.x + 0.5} ${o.y} ${o.z + 0.5}`);
  await world.command(server, `item replace entity ${player} weapon.mainhand with folkways_core:colony_book`);
  await tick.sprint(server, 10);
  const founded = await foundPromoColonyFast(server, player,
    [...beds, table, store, output, larder, pantry, barn, ...supply, probe]);
  const colony_id = founded.colony_id;
  // Stock goes in once the chests belong to the colony: a chest filled before it joined is not read until it
  // changes. Settlers are only admitted with food in store.
  for (const chest of [larder, pantry]) for (const slot of [0, 1]) {
    await world.command(server, `item replace block ${commandPos(chest)} container.${slot} with minecraft:bread 64`);
  }
  await tick.sprint(server, 5);
  const admitted = await admitPromoSettlersFast(server, player, colony_id, CREW.length);
  const uuids = (await world.entities(server, { types: ["folkways_vanilla:resident"], limit: 64 })).entities.map(one => one.uuid);
  if (uuids.length !== CREW.length) throw new Error(`Expected ${CREW.length} residents, found ${uuids.length}`);
  const crew: Crew = Object.fromEntries(CREW.map((one, i) => [one.role, uuids[i]]));
  const looks = await applyCookeryLooks(server, client, uuids);
  const licences = await licenseCrewFast(server, CREW.map((one, i) => ({ uuid: uuids[i], trades: one.trades })));
  for (const uuid of uuids) await world.command(server, `data merge entity ${uuid} {Invulnerable:1b}`);
  const emptied = await server.run(EMPTY_PACKS, { uuids });
  run.note("colony", { o, colony_id, founded, admitted, crew, looks, licences, emptied });

  // Everyone set down on the ground before the line goes in; the conductor waits by Alpha.
  const posts: Record<string, [number, number]> = {
    conductor: [-6, 20], crafter: [10, 7], farmer: [-2, 7], hungry: [0, 7], rider: [-6, 18], rider2: [-5, 19],
    sleeper: [6, 2], sleeper2: [4, 3],
  };
  for (const [role, [x, z]] of Object.entries(posts)) {
    const p = at(x, z);
    await world.command(server, `tp ${crew[role]} ${p.x + 0.5} ${p.y} ${p.z + 0.5}`);
  }

  // The line: Alpha west of the field, Beta sixty blocks north, whose platform has no stairs up: only the train
  // reaches it.
  const railOrigin: Vec = { x: o.x - 20, y: o.y - CUT_DEPTH, z: o.z + 4 };
  await world.command(server, `item replace entity ${player} inventory.0 from entity ${player} weapon.mainhand`);
  await fenceTheCut(server, railOrigin, RAIL_LINE);
  const rail = await buildRailLine(server, client, player, await playerUuid(server, player), railOrigin, RAIL_LINE,
    "inventory.0", {
      decorate: decoratePromoTrain, decorateBounds: PROMO_TRAIN_BOUNDS, cyclic: true, passengerSeats: PROMO_TRAIN_SEATS,
      dwell: { Alpha: 12, Beta: 12 }, clickThrough: true, anchor: at(0, 0),
    });
  run.note("rail", rail);
  if (!rail.ok) return { rail } as any;
  return { ...await finish(pair, run, { player, o, colony_id, crew, uuids, railOrigin, rail, E, beds, field, barn,
    table, store, output, larder, pantry, supply, probe }), rail };
}

async function finish(pair: PromoPair, run: ReturnType<typeof take>, s: {
  player: string; o: Vec; colony_id: string; crew: Crew; uuids: string[]; railOrigin: Vec; rail: RailResult; E: Vec;
  beds: Vec[]; field: { min: Vec; max: Vec }; barn: Vec; table: Vec; store: Vec; output: Vec; larder: Vec; pantry: Vec;
  supply: Vec[]; probe: Vec;
}) {
  const { server, client } = pair;
  const { player, colony_id, railOrigin, rail } = s;
  run.note("station", await buildSubwayStation(server, railOrigin, rail.stationB));
  // Beta's stairs barred: the far platform is reached by train or not at all.
  const beta = rail.stationB.z + 2;
  for (const dz of [9, 10]) {
    await tryCommand(server, `fill ${railOrigin.x + 7} ${railOrigin.y + CUT_DEPTH - 1} ${beta + dz} ${railOrigin.x + 7} ${railOrigin.y + CUT_DEPTH + 1} ${beta + dz} minecraft:barrier`);
  }
  run.note("opened", await openTheLine(server, railOrigin, rail.stationB));
  const wanted = deliveryChests(railOrigin, rail.stationB, GOODS.length);
  for (const chest of wanted) await world.command(server, `setblock ${commandPos(chest)} minecraft:chest[facing=west]`);
  await runColonyTask(server, MARK_MEMBERS, { player, colony_id, cells: wanted.flatMap(c => [c.x, c.y, c.z]) });
  const demands = await setMaintainDemandsFast(server, player, wanted.map((at, i) => ({ at, item: GOODS[i], count: 32, low: 32 })));
  run.note("beta", { wanted, demands });

  await world.command(server, `item replace entity ${player} weapon.offhand with minecraft:air`);
  await world.command(server, `item replace entity ${player} weapon.mainhand from entity ${player} inventory.0`);
  const elevator = await buildElevator(server, player, colony_id, s.E);
  run.note("elevator", elevator);
  // Nothing in hand: the colony book would float its own cards over every head, and this film is about the debug ones.
  await world.command(server, `item replace entity ${player} weapon.mainhand with minecraft:air`);

  await world.command(server, `item replace block ${commandPos(s.barn)} container.0 with minecraft:wheat_seeds 64`);
  run.note("zones", await server.run(MAKE_ZONES, { colony_id, zones: [
    { kind: "folkways_vanilla:farm", ...s.field, crop: "minecraft:wheat" },
  ] }));
  run.note("vitals", await setVitals(server, colony_id, s.uuids.map(uuid => ({ uuid, food: 19, tired: 100 }))));

  const conductor = s.crew.conductor;
  const post = { x: railOrigin.x + 5, y: railOrigin.y + 2, z: railOrigin.z + 10 };
  if (await seatedAt(server, rail.carriageUuid!, conductor) === undefined) {
    await world.command(server, `tp ${conductor} ${post.x + 0.5} ${post.y} ${post.z + 0.5}`);
  }
  await world.command(server, `gamemode spectator ${player}`);
  await setFov(client, FOV);
  run.note("client", await prepareClient(client, GUI_SCALE));
  run.note("debug", await enableDebug(server, player));
  return { ...s, wanted, elevator };
}

// ---------------------------------------------------------------------------------------------------------------
// The tour

async function tour(run: ReturnType<typeof take>, pair: PromoPair, s: Staged) {
  const { server, client } = pair;
  const { o, crew, colony_id, rail } = s;
  const at = (x: number, z: number, dy = 0): Vec => ({ x: o.x + x, y: o.y + dy, z: o.z + z });
  const carriage = rail.carriageUuid!;
  const centre = at(-4, -18, 14);
  const evidence: Record<string, any> = {};

  // The colony settles in and the train makes its first runs, so the timetable knows the line.
  await setTickRate(server, 20);
  await tick.freeze(server, false);
  const warm: any[] = [];
  // The graph shots hold the world still, so the walking net does not grow past the cap while they roll.
  const hold = () => tick.freeze(server, true);
  const started = Date.now();
  let beta = false, aboard = false;
  while (Date.now() - started < 150_000) {
    await sleepMs(2_000);
    const car = await carriagePos(server, carriage);
    beta ||= !!car && car.z < rail.stationB.z + 20;
    aboard ||= await conductorAboard(server, carriage);
    const graph = await readWayGraph(server, colony_id, centre).catch((e: any) => ({ error: String(e) }));
    warm.push({ t: Math.round((Date.now() - started) / 1000), car, beta, aboard, edges: graph.edges, points: graph.points });
    const sorts = Object.keys(graph.edges ?? {}).filter(k => k.startsWith("CROSSING"));
    // The graph is filmed as soon as both crossings are in it: the overlay keeps at most 4000 edges and adds the
    // crossings after every walking link, so once the walking net outgrows that the crossings are no longer sent.
    if (aboard && sorts.some(k => k.includes("train")) && sorts.some(k => k.includes("elevator"))
      && Date.now() - started > 4_000) break;
  }
  await tick.freeze(server, true);
  run.note("warm", warm);
  const graph = await readWayGraph(server, colony_id, centre);
  run.note("waygraph", graph);
  // Was the chest that joined already full read? Its card compares the colony's record with the live contents.
  run.note("probe", await server.run(PROBE, { player: s.player, colony_id, at: s.probe }).catch((e: any) => String(e)));
  evidence.waygraph = graph;

  // 01 — the whole colony from above: the waypoint graph and the stores' outlines.
  await park(server, s.player, centre);
  await rigMove(client, { eye: [at(26, 30, 26)], look: [at(-6, -12, 0)], carry: false });
  await waitOverlay(client, (ov) => ov.freshWaypoints && Object.keys(ov.edges).length > 0, 10_000);
  const overview = await shot(run, pair, "01-overview", "The colony from above with its waypoint graph: walking links, "
    + "residents' feet, and the elevator's and the train's crossings, the stores outlined by how current their record is", 14,
    async () => {
      await hold();
      await rigMove(client, { eye: [at(26, 30, 26), at(14, 28, 30), at(0, 30, 28)], look: [at(-6, -12, 0), at(-4, -16, 0), at(-2, -14, 0)], seconds: 13, carry: false });
    });
  evidence.overview = overview;
  expect(Object.keys(overview.overlay.edges).some((k: string) => k.startsWith("CROSSING") && k.includes("train")),
    "the overview must draw the train's crossing").toBe(true);
  expect(Object.keys(overview.overlay.edges).some((k: string) => k.startsWith("CROSSING") && k.includes("elevator")),
    "the overview must draw the elevator's crossing").toBe(true);

  const crewNow = () => server.run(CREW_STATE, { crew }) as Promise<Record<string, any>>;
  const centreOf = (p: Vec) => ({ x: p.x + 0.5, y: p.y + 0.5, z: p.z + 0.5 });
  const near = (p: Vec, dx: number, dy: number, dz: number): Vec => ({ x: p.x + 0.5 + dx, y: p.y + dy, z: p.z + 0.5 + dz });
  // Runs the world until ready() holds on the crew, sampling every second; the world is frozen again on return.
  const until = async (label: string, timeoutMs: number, ready: (c: Record<string, any>) => boolean) => {
    await tick.freeze(server, false);
    const t0 = Date.now();
    let c: Record<string, any> = {};
    try {
      while (Date.now() - t0 < timeoutMs) {
        c = await crewNow();
        if (ready(c)) return { label, ready: true, seconds: (Date.now() - t0) / 1000, crew: c };
        await sleepMs(1_000);
      }
      return { label, ready: false, seconds: (Date.now() - t0) / 1000, crew: c };
    } finally {
      await tick.freeze(server, true);
      run.note(`until-${label}`, { ready: ready(c), seconds: (Date.now() - t0) / 1000, crew: c });
    }
  };
  // Aims the carried player before a card shot and waits until the client shows the card for that target.
  const aim = async (move: RigMove, target: string) => {
    await rigMove(client, { ...move, carry: true });
    return waitOverlay(client, (ov) => ov.fresh && ov.target === target && ov.card.length > 0, 8_000);
  };

  // 02 — the elevator in the graph: crossings between its ground landing and the loft, walking links on each floor.
  // The graph shots come first: once the walking links pass the overlay's 4000-edge cap the crossings are dropped.
  const E = s.E;
  const liftEyes = [{ x: E.x - 7.5, y: E.y + 9, z: E.z - 14 }, { x: E.x - 1.5, y: E.y + 9.5, z: E.z - 15.5 }];
  const liftLook = [{ x: E.x - 3, y: E.y + 3, z: E.z - 6.5 }, { x: E.x - 2.5, y: E.y + 3, z: E.z - 6.5 }];
  await rigMove(client, { eye: [liftEyes[0]], look: [liftLook[0]], carry: true });
  await sleepMs(2_500);
  evidence.liftGraph = await shot(run, pair, "02-lift-graph", "The elevator in the waypoint graph: the crossings between "
    + "its ground landing and the loft, walking links on each floor and the residents' feet", 9, async () => {
      await hold();
      await rigMove(client, { eye: liftEyes, look: liftLook, seconds: 9, carry: true });
    });

  // 03 — the line from above: the train's crossing drawn between the two platforms as the train runs it.
  const ro = s.railOrigin;
  const lineEyes = [{ x: ro.x + 2.5, y: ro.y + 9.5, z: ro.z - 4 }, { x: ro.x + 2.5, y: ro.y + 10, z: ro.z - 24 }];
  const lineLook = [{ x: ro.x + 3.5, y: ro.y + 2.5, z: ro.z - 20 }, { x: ro.x + 3.5, y: ro.y + 2.5, z: ro.z - 44 }];
  await rigMove(client, { eye: [lineEyes[0]], look: [lineLook[0]], carry: true });
  await sleepMs(2_500);
  evidence.line = await shot(run, pair, "03-line", "The subway line from above: the waypoint graph's crossing runs from "
    + "Alpha's platform to Beta's beside the track, the world held still", 11, async () => {
      await hold();
      await rigMove(client, { eye: lineEyes, look: lineLook, seconds: 11, carry: true });
    }, async () => ({ car: await carriagePos(server, carriage) }));

  // 04 — the workshop: planks and sticks go into the store; its card shows them free, then reserved and to be drawn
  // once the crafter takes on the hoes, with a line to the crafter; the camera then turns to the crafter's own card.
  for (const [slot, [item, count]] of STOCK.entries()) {
    await world.command(server, `item replace block ${commandPos(s.store)} container.${slot} with ${item} ${count}`);
  }
  const storeTarget = `block:${s.store.x},${s.store.y},${s.store.z}`;
  const crafterTarget = `resident:${crew.crafter}`;
  const storeEye = [near(s.store, 5.0, 3.0, 4.0), near(s.store, 4.0, 2.8, 4.6)];
  evidence.storeReady = await aim({ eye: [storeEye[0]], look: [centreOf(s.store)] }, storeTarget);
  await tick.freeze(server, false);
  evidence.planned = await waitOverlay(client, (ov) => ov.target === storeTarget
    && ov.links.some((l: string) => l.startsWith("ASSIGNEE")), 90_000);
  await tick.freeze(server, true);
  const workshop = await shot(run, pair, "04-workshop", "Looking at the workshop store: its card shows the record against "
    + "the live contents, goods reserved and to be drawn, and the hoes planned there with a line to the crafter; the camera "
    + "turns to the crafter, whose card shows the crafting, its stage and its route", 17, async () => {
      await rigMove(client, { eye: storeEye, look: [centreOf(s.store)], seconds: 5, carry: true });
      // The turn to the crafter runs beside the sampling loop.
      void sleepMs(5_000).then(() => rigMove(client, { follow: crew.crafter, offset: { x: 3.6, y: 2.4, z: 3.8 },
        lookAt: crew.crafter, omega: 2.0, carry: true }));
    }, async () => (await crewNow()).crafter);
  evidence.store = { ...workshop, samples: workshop.samples.filter((one: any) => one.t < 5) };
  evidence.crafter = { ...workshop, samples: workshop.samples.filter((one: any) => one.t >= 6.5) };

  // 06 — a farmer with a new hoe, on the way to the field.
  evidence.hoe = await until("hoe", 90_000, (c) => (c.farmer?.pack ?? []).some((i: string) => i.includes("_hoe")));
  evidence.farmerReady = await aim({ follow: crew.farmer, offset: { x: -5.2, y: 3.6, z: -4.6 }, lookAt: crew.farmer, omega: 2.5 }, `resident:${crew.farmer}`);
  evidence.farmer = await shot(run, pair, "06-farmer", "Following a farmer with the hoe just made for it: its card and the "
    + "numbered route lines to the field", 10, async () => {}, async () => (await crewNow()).farmer);

  // 07 — hunger breaks into work: the urge preempts the farming and the resident goes to the larder to eat.
  // The same farmer, now hungry: hunger breaks into its work.
  run.note("hungry-vitals", await setVitals(server, colony_id, [{ uuid: crew.farmer, food: 3, tired: 150 }]));
  evidence.hungryReady = await aim({ follow: crew.farmer, offset: { x: -3.4, y: 2.8, z: -3.0 }, lookAt: crew.farmer, omega: 2.5 }, `resident:${crew.farmer}`);
  evidence.hungry = await shot(run, pair, "07-hungry", "A hungry resident: the hunger urge preempts its work, the card shows "
    + "the urge, the recent events and the hunger watermarks as it walks to the larder and eats", 14, async () => {
      await rigMove(client, { follow: crew.farmer, offset: { x: -3.4, y: 2.8, z: -3.0 }, lookAt: crew.farmer, omega: 2.5, carry: true,
        scroll: { from: 0, to: 400, delay: 7, seconds: 4 } });
    }, async () => (await crewNow()).farmer);

  // 08 — goods for the far station: a rider waits on Alpha's platform, the train comes in and it boards.
  const supplyStacks = GOODS.flatMap((item) => [item, item]);
  for (const [i, item] of supplyStacks.entries()) {
    await world.command(server, `item replace block ${commandPos(s.supply[Math.floor(i / 27)])} container.${i % 27} with ${item} 32`);
  }
  // A load for Beta: the shot starts once a rider's own card puts its next work at the far station.
  const betaZ = s.rail.stationB.z + 20;
  const cardOf = async (uuid: string) => (await server.run(PROBE, { player: s.player, colony_id, uuid })).card as string[];
  const bound = (card: string[]) => card.slice(0, 8).some((line) => {
    const at = /@ (-?\d+), (-?\d+), (-?\d+)/.exec(line);
    return !!at && Number(at[3]) < betaZ && /存放|Stow/i.test(line);
  });
  await tick.freeze(server, false);
  let rider = "";
  const t0 = Date.now();
  while (!rider && Date.now() - t0 < 150_000) {
    for (const role of ["rider", "rider2"]) if (!rider && bound(await cardOf(crew[role]))) rider = role;
    if (!rider) await sleepMs(1_000);
  }
  await tick.freeze(server, true);
  run.note("rider-bound", { rider, seconds: (Date.now() - t0) / 1000, card: rider ? await cardOf(crew[rider]) : [] });
  expect(rider, "a rider must take on work at the far station").not.toBe("");
  evidence.rider = rider;
  const ro2 = s.railOrigin;
  // Above the cut on the far side from the stairs, looking down across the platform: the rider is in view from the
  // street down to its seat.
  const platformEyes = [{ x: ro2.x + 1.5, y: ro2.y + 11, z: ro2.z + 22 }, { x: ro2.x + 1, y: ro2.y + 11.5, z: ro2.z + 18 }];
  evidence.riderReady = await aim({ eye: [platformEyes[0]], lookAt: crew[rider] }, `resident:${crew[rider]}`);
  const rode: Record<string, number> = {};
  const marks: Record<string, number> = {};
  const clipStart = { at: 0 };
  evidence.boarding = await shot(run, pair, "08-platform", "A rider carries goods for the far station down to the platform: "
    + "its card's work is at Beta, which only the train reaches; the train pulls in and it boards", 60, async () => {
      clipStart.at = Date.now();
      await rigMove(client, { eye: platformEyes, lookAt: crew[rider], seconds: 30, carry: true });
    }, async () => {
      const seat = await seatedAt(server, carriage, crew[rider]);
      const me = (await crewNow())[rider];
      const t = (Date.now() - clipStart.at) / 1000;
      if (me && Math.abs(me.y - (ro2.y + 2)) < 0.6 && marks.platform === undefined) marks.platform = t;
      if (seat !== undefined && me?.vehicle) { rode[rider] = seat; marks.seated ??= t; }
      return { car: await carriagePos(server, carriage), seat, rider: me };
    }, (one) => one.world?.seat !== undefined && !!one.world?.rider?.vehicle, 4);
  marks.end = (Date.now() - clipStart.at) / 1000;
  run.note("platform-marks", marks);
  evidence.rode = rode;

  // 09 — worn out: a sleeper walks into the elevator, rides up to the loft and goes to bed.
  run.note("tired-vitals", await setVitals(server, colony_id, [
    { uuid: crew.sleeper, food: 19, tired: 1100 }, { uuid: crew.sleeper2, food: 19, tired: 1100 }]));
  const lift = { min: { x: E.x - 2, z: E.z - 6 }, max: { x: E.x + 3, z: E.z - 1 } };
  const inCabin = (one: any) => one && one.x >= lift.min.x && one.x <= lift.max.x && one.z >= lift.min.z && one.z <= lift.max.z;
  evidence.boardingLift = await until("lift", 90_000, (c) => inCabin(c.sleeper) || inCabin(c.sleeper2));
  const sleeper = inCabin(evidence.boardingLift.crew.sleeper) ? "sleeper" : "sleeper2";
  evidence.sleeper = sleeper;
  const liftEye = [{ x: E.x - 3.5, y: E.y + 7.5, z: E.z - 15.5 }, { x: E.x - 6.5, y: E.y + 9.5, z: E.z - 15 }];
  evidence.sleeperReady = await aim({ eye: [liftEye[0]], lookAt: crew[sleeper] }, `resident:${crew[sleeper]}`);
  const lifted: any[] = [];
  evidence.elevator = await shot(run, pair, "09-elevator", "A worn-out resident rides the Create elevator up to the loft "
    + "and goes to bed; its card shows the sleep urge, its home bed and the tiredness watermarks", 18, async () => {
      await rigMove(client, { eye: liftEye, lookAt: crew[sleeper], seconds: 18, carry: true,
        scroll: { from: 0, to: 600, delay: 9, seconds: 5 } });
    }, async () => {
      const state = await readElevator(server, colony_id, E);
      lifted.push(...state.passengers.map((p: any) => p.uuid));
      return { lift: state.passengers.length, sleeper: (await crewNow())[sleeper] };
    });
  evidence.lifted = [...new Set(lifted)];

  await rigStop(client);

  // What the film claims, checked against what the world did and what the overlay drew.
  const sawCard = (beat: any, target: string) => beat.samples.filter((one: any) => one.target === target && one.card > 0).length;
  expect(sawCard(evidence.store, storeTarget), "the store's card must be on screen").toBeGreaterThanOrEqual(3);
  expect(evidence.store.samples.some((one: any) => one.links.some((l: string) => l.startsWith("ASSIGNEE"))),
    "the store must link to an assigned resident").toBe(true);
  expect(sawCard(evidence.crafter, crafterTarget), "the crafter's card must be on screen").toBeGreaterThanOrEqual(4);
  expect(sawCard(evidence.farmer, `resident:${crew.farmer}`), "the farmer's card must be on screen").toBeGreaterThanOrEqual(5);
  expect(sawCard(evidence.hungry, `resident:${crew.farmer}`), "the hungry resident's card must be on screen").toBeGreaterThanOrEqual(6);
  expect(evidence.hungry.samples.some((one: any) => /eat/i.test(String(one.world?.what))), "the hungry resident must eat on camera").toBe(true);
  expect(Object.keys(evidence.rode).length, "a rider must board the train").toBeGreaterThanOrEqual(1);
  expect(evidence.lifted.length, "a resident must ride the elevator on camera").toBeGreaterThanOrEqual(1);
  expect(evidence.elevator.samples.some((one: any) => one.world?.sleeper?.what?.includes("sleep")), "the sleeper must lie down on camera").toBe(true);

  run.note("evidence", evidence);
}

// One clip, sampling the overlay as the client holds it every second.
async function shot(run: ReturnType<typeof take>, pair: PromoPair, name: string, subject: string, seconds: number,
  during: () => Promise<void>, sample?: () => Promise<any>, done?: (one: any) => boolean, tail = 3) {
  const samples: any[] = [];
  let overlay: any;
  // The clip lasts as long as the loop below runs: a shot can end early once what it is for has happened.
  const gates = await promoClip(run, pair.client, pair.server, name, {
    subject, worldState: {}, seconds: 1,
    during: async () => {
      const t0 = Date.now();
      await during();
      while (Date.now() - t0 < seconds * 1000 - 500) {
        const ov = await readOverlay(pair.client).catch((e: any) => ({ error: String(e) }));
        overlay ??= ov;
        if (Object.keys(ov.edges ?? {}).length > Object.keys(overlay.edges ?? {}).length) overlay = ov;
        samples.push({ t: Math.round((Date.now() - t0) / 100) / 10, target: ov.target, card: ov.card?.length,
          head: ov.card?.slice(0, 3), links: ov.links, edges: ov.edges, points: ov.points, stores: ov.stores,
          differ: ov.storesDiffer, ...(sample ? { world: await sample() } : {}) });
        if (done && done(samples.at(-1))) {
          await sleepMs(tail * 1000);
          break;
        }
        await sleepMs(1_000);
      }
    },
  });
  run.note(`shot-${name}`, { gates, samples });
  return { gates, samples, overlay };
}

async function waitOverlay(client: MinecraftClient, ready: (ov: any) => boolean, timeoutMs: number) {
  const started = Date.now();
  let ov: any;
  while (Date.now() - started < timeoutMs) {
    ov = await readOverlay(client);
    if (ready(ov)) return ov;
    await sleepMs(250);
  }
  return ov;
}

async function park(server: MinecraftServer, player: string, p: Vec) {
  await world.command(server, `tp ${player} ${p.x + 0.5} ${p.y} ${p.z + 0.5} 0 -90`);
  await sleepMs(500);
}

async function playerUuid(server: MinecraftServer, name: string): Promise<string> {
  const found = (await world.players(server)).find((p) => p.name === name)?.uuid;
  if (!found) throw new Error("Could not read the player uuid");
  return found;
}

async function carriagePos(server: MinecraftServer, uuid: string): Promise<Vec | null> {
  const listed = await world.entities(server, { typePatterns: ["*carriage_contraption*"] })
    .catch(() => ({ entities: [] as world.EntityJson[] }));
  return listed.entities.find((e) => e.uuid === uuid)?.position ?? null;
}

// The overlay's lines are drawn with depth, and the train's crossing runs from platform to platform along the kerb's
// line, through the cut's side and the tunnels. The tunnels come out and a slot is cut along that line, so the
// crossing shows from above; barriers (which draw nothing) keep the platforms' ends shut.
async function openTheLine(server: MinecraftServer, ro: Vec, stationB: Vec) {
  const street = ro.y + CUT_DEPTH;
  const beta = stationB.z + 2;
  const out: any[] = [];
  const fill = async (x1: number, y1: number, z1: number, x2: number, y2: number, z2: number, block: string) =>
    out.push((await tryCommand(server, `fill ${ro.x + x1} ${y1} ${z1} ${ro.x + x2} ${y2} ${z2} ${block}`))?.success);
  for (const [near, far] of [[ro.z - 14, ro.z - 4], [beta + 17, beta + 27]]) {
    await fill(-4, ro.y + 1, near, 4, street - 1, far, "minecraft:air");
    await fill(-7, street, near, 7, street + 8, far, "minecraft:air");
    for (const x of [-5, 5]) await fill(x, street, near - 1, x, street, far + 1, "minecraft:oak_fence");
    for (const z of [near - 1, far + 1]) {
      await fill(-5, street, z, -3, street, z, "minecraft:oak_fence");
      await fill(3, street, z, 5, street, z, "minecraft:oak_fence");
    }
  }
  await fill(3, ro.y + 2, beta + 15, 3, ro.y + 3, ro.z + 1, "minecraft:air");
  for (const z of [beta + 15, ro.z + 1]) await fill(3, ro.y + 2, z, 3, ro.y + 3, z, "minecraft:barrier");
  return { ok: out.every((one) => one !== false), out };
}

const CREW_STATE = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.debug-crew",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["io.github.izakyl.folkways.core.resident.api.Bodies", "net.minecraft.core.registries.BuiltInRegistries"],
    body: `
var level=ctx.server().overworld();var out=new LinkedHashMap<String,Object>();
var crew=(Map<?,?>)Args.of(ctx).value("crew");
for(var e:crew.entrySet()){
  var entity=level.getEntity(UUID.fromString(String.valueOf(e.getValue())));
  if(entity==null){out.put(String.valueOf(e.getKey()),Map.of("gone",true));continue;}
  var body=Bodies.of(entity);
  var one=new LinkedHashMap<String,Object>();
  one.put("x",Math.round(entity.getX()*100)/100.0);one.put("y",Math.round(entity.getY()*100)/100.0);one.put("z",Math.round(entity.getZ()*100)/100.0);
  one.put("what",body.flatMap(b->b.doing()).map(d->d.what().toString()).orElse("idle"));
  one.put("toward",body.flatMap(b->b.doing()).flatMap(d->d.toward()).map(Object::toString).orElse(""));
  one.put("vehicle",entity.getVehicle()==null?"":BuiltInRegistries.ENTITY_TYPE.getKey(entity.getVehicle().getType()).toString());
  one.put("pack",body.map(b->b.pack().contents().stream().filter(st->!st.isEmpty())
    .map(st->BuiltInRegistries.ITEM.getKey(st.getItem()).getPath()+"x"+st.getCount()).toList()).orElse(List.of()));
  out.put(String.valueOf(e.getKey()),one);
}
return out;`,
  }),
});

const PROBE = defineTask<Record<string, any>, any>({
  name: "folkways.promo.debug-probe",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.colony.api.Colonies", "io.github.izakyl.folkways.core.front.api.notice.Line",
      "io.github.izakyl.folkways.core.front.engine.colony.DebugFocus", "io.github.izakyl.folkways.core.front.engine.net.DebugTarget",
      "net.minecraft.core.BlockPos",
    ],
    body: `
var args=Args.of(ctx);
var player=ctx.server().getPlayerList().getPlayerByName(args.string("player"));
var colony=Colonies.of(ctx.server(),UUID.fromString(args.string("colony_id"))).orElseThrow();
BlockPos pos=BlockPos.ZERO;DebugTarget target;
if(args.has("uuid")){
  var entity=ctx.server().overworld().getEntity(UUID.fromString(args.string("uuid")));
  if(entity==null)return Map.of("card",List.of(),"stores",List.of());
  target=new DebugTarget.Resident(entity.getId());
}else{
  var p=(Map<?,?>)args.value("at");
  pos=new BlockPos(((Number)p.get("x")).intValue(),((Number)p.get("y")).intValue(),((Number)p.get("z")).intValue());
  target=new DebugTarget.Block(pos);
}
final BlockPos at=pos;
var packet=DebugFocus.read(player,colony,target);
var lines=new ArrayList<String>();
for(var line:packet.card())lines.add(switch(line){
  case Line.Said said->said.notice().component().getString();
  case Line.Literal literal->literal.text();
  case Line.Text text->text.component().getString();
  default->line.toString();
});
return Map.of("card",lines,"stores",packet.stores().stream().filter(m->m.pos().equals(at)).map(Object::toString).toList());`,
  }),
});

const EMPTY_PACKS = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.debug-empty-packs",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["io.github.izakyl.folkways.core.resident.api.Bodies"],
    body: `
var level=ctx.server().overworld();var out=new ArrayList<Object>();
for(var raw:Args.of(ctx).list("uuids")){
  var body=Bodies.of(level.getEntity(UUID.fromString(String.valueOf(raw)))).orElseThrow();
  out.add(String.valueOf(raw).substring(0,8)+":"+body.pack().contents().stream().filter(s->!s.isEmpty()).map(Object::toString).toList());
  body.pack().clearContent();
}
return Map.of("emptied",out);`,
  }),
});

const MAKE_ZONES = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.debug-zones",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.front.engine.colony.*",
      "net.minecraft.core.BlockPos",
      "net.minecraft.resources.ResourceLocation",
    ],
    body: `
var server=ctx.server();var level=server.overworld();var args=Args.of(ctx);
var colony=ColonyGround.of(server,UUID.fromString(args.string("colony_id"))).orElseThrow();
var zones=new ArrayList<Object>();
for(var raw:args.list("zones")){
  var z=(Map<?,?>)raw;var min=(Map<?,?>)z.get("min");var max=(Map<?,?>)z.get("max");
  var chosen=ColonySettings.empty();
  if(z.containsKey("crop"))chosen=chosen.with("crop",new ColonySettings.Value.Choice(ResourceLocation.parse(String.valueOf(z.get("crop")))));
  var zone=ZoneDrafts.zone(level,colony,ResourceLocation.parse(String.valueOf(z.get("kind"))),
    new BlockPos(((Number)min.get("x")).intValue(),((Number)min.get("y")).intValue(),((Number)min.get("z")).intValue()),
    new BlockPos(((Number)max.get("x")).intValue(),((Number)max.get("y")).intValue(),((Number)max.get("z")).intValue()),chosen)
    .orElseThrow(()->new IllegalStateException("Zone refused: "+z));
  zones.add(zone.id().toString());
}
return Map.of("ok",true,"zones",zones);`,
  }),
});

