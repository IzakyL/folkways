import { AIRPORT_TRACKING } from "./airport-tracking";
import { waitForAirportLods } from "./airport-dh";
import { dockObserve } from "./airport-dock-observe";
import { landingMotion } from "./landing-motion";
import { copyFileSync, existsSync, writeFileSync } from "node:fs";
import { instanceDirOf } from "@izakyl/blockwright-client";
import { expect, test } from "@playwright/test";
import {
  defineTask, input, javaTask, screen, tick, world, type MinecraftClient, type MinecraftServer,
} from "@izakyl/blockwright-minecraft";
import { clickElement, panelElements, waitForElement } from "../ldlib2";
import { waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { BUDGET } from "../shared/harness/budgets";
import { commandPos, frames, softly, tryCommand } from "../shared/harness/bw-helpers";
import { PROJECT } from "../shared/combat/rts-fixtures";
import { pickRtsTool, tapV } from "../shared/combat/overhead";
import { admitPromoSettlersFast, foundPromoColonyFast, licenseCrewFast } from "./fast-setup";
import { cinematicWorld, hideHud, hudShown, launchPromoPair, promoClip, setFov, sleepMs, take, type PromoPair } from "./promo-kit";
import { rigMove, rigStop, type RigMove } from "./debug-rig";
import {
  AIRPORT_NAME, BUILD, CARRIER, CARRIER_AT, CARRIER_BASE, CARRIER_NAME, CARRIER_TURN, CATAPULT, CLIENT_SETUP, DECK,
  FLEET, FLEET_TASK, FLOOR, OPEN_TOWER, ORIGIN, SURVEY, TARGETS, TARGETS_LEFT, TOWER, TOWER_HEIGHT,
  CONTROLLER, WORLD_RULES, airportReady, carrierReady, clientClear, pickerRow, readFleet, type Vec,
} from "./airport-stage";

// The airport and its fleet, with the original shot numbers (4 and 8 removed):
//   1. the whole field from the air: parallel runways 09L/27R and 09R/27L with their parallel taxiways and rapid
//      exits, the terminal and its apron of six stands, the helipad area, the crosswind runway 04/22, the motor
//      pool and the tower;
//   2. the tower's own page, opened by right-clicking it, with every runway, pad and stand it surveyed;
//   3. one tank picked in the tactical view, crewed with one press of Board, sent at the range with Attack and then
//      moved on, all in one take (4, the armour's separate attack, is folded into it);
//   5. two helicopters set Low and sent at targets past their pads, lifting off on the order alone, and one
//      returning to an airport-assigned pad;
//   6. an A-10 ordered to loiter, taking off first from an airport-assigned suitable runway;
//   7. a Ju-87 launched by the catapult on runway 27R;
// then the carrier, built after the field's shots (a second airport would put a picker on every Land order):
//   9. a carrier floating above the plains, its runway, island and tower surveyed as an airport of its own;
//  10. a helicopter sent from the field's pad to land on the carrier's deck pad;
//  11. a Ju-87 launched off the bow by the deck catapult;
//  12. (not filmed) the same Ju-87 called back to the deck's runway: a test of its length.
// Only the world is staged: every selection and order is real input on the command card, except shot 11's (below).
// Each shot is one camera: a shot that gives its order in the tactical view glides out of that exact view into its
// own framing (glideFromTactical), so nothing in a clip cuts.
//
// Known defects this take films around (not fixed yet):
//   - Any block change within about 515 blocks of the tower marks the airport stale: until the next survey
//     finishes (up to 60 s plus the survey) it offers no runways or pads. Opening the tower page requests a
//     survey too. Every shot that requests a flight waits on airportReady() first, and nothing changes blocks on film.
//   - The tactical view cannot pick a vehicle standing on a Sable structure's deck: it projects the hull from the
//     structure's plot, so a click or box misses it. Shot 11 gives its Loiter order through the vehicle provider.
//   - Residents board only from footing in the world, not on a deck: the deck's Ju-87 is crewed on the grass below
//     and then set on the deck.
//   - The rig's camera flies free of the player, but the server sends hulls by where the player stands: a camera
//     far from the player shows empty ground. Shot 10 parks the player under the deck when it glides to the deck,
//     and its live position check fails the take if the client loses a deck hull again.
//   - Runway numbers on a structure are read in its own frame: the deck lane is 09/27 whichever way the carrier
//     heads.

const CONFIG = "blockwright.toml#promo-airport";
const GUI_SCALE = 2;
const FOV = 70;
const CREW = 16;
/** The command card's hotkeys: each presses its button while the card shows it. */
const KEY = { G: 71, L: 76, M: 77, O: 79 };
/** The runway end the tower page hovers and zooms: 09L's threshold. */
const RUNWAY_END = { x: -180, z: -140 };

type Run = ReturnType<typeof take>;
type Mark = (label: string) => void;
type Stage = Awaited<ReturnType<typeof stage>>;

test("airport: the tower, the fleet and every way it moves", async ({}, testInfo) => {
  test.setTimeout(BUDGET.promo * 2);
  const run = take("airport");
  run.note("verified", false);
  let pair: PromoPair | undefined;
  try {
    pair = await launchPromoPair("airport", {
      config: CONFIG, shaders: false,
      files: {
        client: { "config/DistantHorizons.toml": "tests/promo/airport-dh.toml" },
        server: { "config/DistantHorizons.toml": "tests/promo/airport-dh.toml" },
      },
    });
    const s = await stage(pair, run);
    const from = Number(process.env.FOLKWAYS_PROMO_FROM ?? 1);
    const to = Number(process.env.FOLKWAYS_PROMO_TO ?? 12);
    const want = (n: number) => from <= n && n <= to;
    const evidence: Record<string, any> = {};
    if (want(1)) evidence.airfield = await shotAirfield(run, pair, s);
    if (want(2)) evidence.tower = await shotTower(run, pair, s);
    if (want(3)) evidence.armour = await shotArmour(run, pair, s);
    if (want(5)) evidence.helicopters = await shotHelicopters(run, pair, s);
    if (want(6)) {
      evidence.takeoff = await shotTakeoff(run, pair, s);
    }
    if (want(7)) evidence.catapult = await shotCatapult(run, pair, s);
    if (want(9)) evidence.carrier = await shotCarrier(run, pair, s);
    if (want(10)) evidence.deckHelicopter = await shotDeckHelicopter(run, pair, s);
    if (want(11)) evidence.deckCatapult = await shotDeckCatapult(run, pair, s);
    if (want(12)) evidence.deckLanding = await shotDeckLanding(run, pair, s);
    await rigStop(pair.client);
    run.note("evidence", evidence);
    run.note("verified", from <= 1 && to >= 11
      && evidence.deckHelicopter?.landed?.deck === true && evidence.deckHelicopter?.clientPositionsAgree === true
      && evidence.deckCatapult?.launched === true);
    if (want(10)) {
      expect(evidence.deckHelicopter.clientPositionsAgree,
        "both AH-6s must exist on the ticking client within 1.5 blocks of their server positions").toBe(true);
    }
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    if (pair) {
      const log = `${instanceDirOf(pair.clientInstance, process.cwd())}/logs/latest.log`;
      const dhConfig = `${instanceDirOf(pair.clientInstance, process.cwd())}/config/DistantHorizons.toml`;
      if (existsSync(dhConfig)) {
        copyFileSync(dhConfig, `${run.dir}/DistantHorizons.toml`);
      }
      if (existsSync(log)) {
        copyFileSync(log, `${run.dir}/client-latest.log`);
      }
    }
    await pair?.teardown(testInfo);
  }
});

// ---------------------------------------------------------------------------------------------------------------
// The world

async function stage(pair: PromoPair, run: Run) {
  const { server, client } = pair;
  const grounded = await waitForGroundedPlayer(server);
  const player = grounded.name;
  await world.command(server, `op ${player}`);
  await world.command(server, `gamemode creative ${player}`);
  await cinematicWorld(server);
  for (const rule of ["doMobSpawning false", "fallDamage false", "doDaylightCycle false", "doWeatherCycle false"]) {
    await tryCommand(server, `gamerule ${rule}`);
  }
  await tryCommand(server, "time set 6000");
  run.note("rules", await server.run(WORLD_RULES, {}));
  // The field, from Airline's own patterns.
  run.note("build", await server.run(BUILD, { ...ORIGIN, tower: TOWER_HEIGHT }));
  await tick.sprint(server, 5);

  // The barracks, behind the tower and out of every shot: beds and a larder that let the crews in.
  const beds: Vec[] = [];
  for (let i = 0; i < CREW; i++) beds.push({ x: -20 + i * 2, y: FLOOR, z: 192 });
  for (const bed of beds) {
    await world.command(server, `setblock ${commandPos(bed)} minecraft:red_bed[part=foot,facing=north]`);
    await world.command(server, `setblock ${bed.x} ${bed.y} ${bed.z - 1} minecraft:red_bed[part=head,facing=north]`);
  }
  const larder = { x: 22, y: FLOOR, z: 192 };
  await world.command(server, `setblock ${commandPos(larder)} minecraft:chest[facing=north]`);
  await world.command(server, `tp ${player} 0.5 ${FLOOR} 186.5 180 0`);
  await world.command(server, `item replace entity ${player} hotbar.0 with folkways_core:colony_book`);
  await tick.sprint(server, 10);
  const founded = await foundPromoColonyFast(server, player, [...beds, larder]);
  const colony = founded.colony_id;
  await world.command(server, `item replace block ${commandPos(larder)} container.0 with minecraft:bread 64`);
  await world.command(server, `item replace block ${commandPos(larder)} container.1 with minecraft:cooked_beef 64`);
  // The settler queue can stall a head or two short of the count; the film needs fourteen.
  const admitted = await admitPromoSettlersFast(server, player, colony, CREW).catch(error => ({ stalled: String(error) }));
  run.note("admitted", admitted);
  const residents = (await world.entities(server, { types: ["folkways_vanilla:resident"], limit: 64 }))
    .entities.map(one => one.uuid);
  if (residents.length < 14) throw new Error(`Expected ${CREW} residents, found ${residents.length}`);
  for (const uuid of residents) await world.command(server, `data merge entity ${uuid} {Invulnerable:1b}`);
  // Crew only: nothing else on the field calls them away between shots.
  run.note("licensed", await licenseCrewFast(server, residents.map(uuid => ({ uuid, trades: ["vehicle_crew"] }))));

  // The fleet, then the catapult on runway 18.
  const spawned = await server.run(FLEET_TASK, { op: "spawn",
    vehicles: FLEET.map(one => ({ key: one.key, type: one.type, x: one.x, y: FLOOR, z: one.z, yaw: one.yaw })) });
  const ids: Record<string, string> = Object.fromEntries(Object.entries(spawned).map(([k, v]: any) => [k, v.uuid]));
  run.note("fleet", spawned);
  run.note("catapult", await server.run(FLEET_TASK, { op: "catapult", ...CATAPULT }));

  // Crews for every performer but the tank the film boards; a controller in the tower's seat; the rest of the people
  // waiting by the motor pool for the boarding shot.
  const pool = [...residents];
  const controller = pool.shift()!;
  const crews: Record<string, any> = {};
  for (const one of FLEET.filter(v => v.crew)) {
    const people = pool.splice(0, 2);
    crews[one.key] = await server.run(FLEET_TASK, { op: "crew", vehicle: ids[one.key], residents: people });
    // Whoever the hull did not need goes back to the pool.
    const used = crews[one.key].boarded.length;
    pool.unshift(...people.slice(used));
  }
  run.note("crews", crews);
  const waiting = pool;
  for (const [i, uuid] of waiting.entries()) {
    await world.command(server, `tp ${uuid} ${-58 + (i % 4) * 2.5} ${FLOOR} ${60.5 + Math.floor(i / 4) * 2}`);
  }
  run.note("tower", await server.run(SURVEY, { ...TOWER, colony, name: AIRPORT_NAME, timeoutMs: 300_000 }));
  run.note("controller", await server.run(SEAT, { resident: controller, ...TOWER }));
  await tick.freeze(server, false);
  // The crews walk to their seats and the airport settles.
  await waitSeated(server, ids, FLEET.filter(v => v.crew).map(v => v.key));
  run.note("airport", await airportReady(server));

  await setFov(client, FOV);
  run.note("client", await client.run(CLIENT_SETUP, { guiScale: GUI_SCALE }));
  // No hotbar or hand anywhere in the film: screens and the tactical view draw over a bare world.
  if (hudShown(client)) await hideHud(client);
  holder = { server, player, run };
  return { player, colony, residents, waiting, ids, controller };
}

async function waitSeated(server: MinecraftServer, ids: Record<string, string>, keys: string[]) {
  const t0 = Date.now();
  let read: any;
  while (Date.now() - t0 < 60_000) {
    read = await readFleet(server, keys.map(k => ids[k]));
    if (keys.every(k => read[ids[k]].aboard > 0)) return read;
    await sleepMs(1_000);
  }
  throw new Error(`Crews never seated: ${JSON.stringify(read)}`);
}

// ---------------------------------------------------------------------------------------------------------------
// 1 — the field from the air

async function shotAirfield(run: Run, pair: PromoPair, s: Stage) {
  const { server, client } = pair;
  await quiet(pair, s);
  // A slow pass from the south-west, high over the motor pool, round past the terminal to the helipads, with both
  // parallel runways and the crosswind runway beyond.
  const eye = [{ x: -150, y: FLOOR + 75, z: 95 }, { x: -20, y: FLOOR + 80, z: 105 }, { x: 120, y: FLOOR + 72, z: 85 }];
  const look = [{ x: -70, y: FLOOR, z: -60 }, { x: 0, y: FLOOR, z: -70 }, { x: 80, y: FLOOR, z: -60 }];
  // A quick pass first, so every chunk along the path is meshed before the take rolls.
  // The player rides along, so the chunks around the camera are the ones loaded and drawn.
  await rigMove(client, { eye, look, seconds: 6, carry: true });
  await sleepMs(7_000);
  await rigMove(client, { eye: [eye[0]], look: [look[0]], carry: true });
  run.note("dh-ready", await waitForAirportLods(client));
  await clientClear(client);
  const shot = await clip(run, pair, "01-airfield", "The whole airport from the air: parallel runways 09L/27R and "
    + "09R/27L with full-length parallel taxiways and diagonal rapid exits, the glass terminal with aircraft nose-in "
    + "at its six stands, four helipads, the diagonal crosswind runway 04/22, the motor pool of tanks and fighting "
    + "vehicles, and the control tower to the south", async () => {
      await rigMove(client, { eye, look, seconds: 16, carry: true });
      await sleepMs(16_500);
    });
  return { shot };
}

// ---------------------------------------------------------------------------------------------------------------
// 2 — the tower's page

async function shotTower(run: Run, pair: PromoPair, s: Stage) {
  const { server, client } = pair;
  await quiet(pair, s);
  await rigStop(client);
  // In the glass cab's south-east corner, beside and above the controller: the tower block he faces and the field
  // past it to the north, neither hidden behind his back.
  const eye = { x: TOWER.x + 2.55, y: TOWER.y + 2.1, z: TOWER.z + 2.55 };
  const look = { x: TOWER.x - 0.6, y: TOWER.y + 0.3, z: TOWER.z - 1.4 };
  // The player stands under the camera, invisible: the free camera would draw its body in the frame.
  await world.command(server, `tp ${s.player} ${eye.x} ${TOWER.y} ${eye.z} 135 25`);
  await world.command(server, `effect give ${s.player} minecraft:invisibility infinite 0 true`);
  await rigMove(client, { eye: [eye], look: [look] });
  await frames(client, 30);
  await sleepMs(2_500);
  const opened: any = {};
  const shot = await clip(run, pair, "02-tower", "From the corner of the tower's cab, the controller seated at the tower "
    + "block with the field below beyond the glass; the tower's full-page airport map shows every runway, helipad, "
    + "stand and taxiway; hover tooltips identify the main tower and runway end, then the player zooms toward the "
    + "runway and pans the map", async (mark) => {
      await rigMove(client, { eye: [eye, { ...eye, x: eye.x - 0.3, y: eye.y + 0.3 }],
        look: [look, { ...look, x: look.x + 0.2, z: look.z - 0.6 }], seconds: 3.5 });
      await sleepMs(3_500);
      // Right-clicking the tower opens this page; the take opens it for the player the same way the block does.
      opened.open = await server.run(OPEN_TOWER, TOWER);
      await screen.waitFor(client, "Modular", { timeoutMs: 6_000 });
      mark("opened");
      await frames(client, 30);
      await input.move(client, { x: 4, y: 120 });
      await sleepMs(2_000);
      for (const [name, cell] of Object.entries({ tower: TOWER, end: RUNWAY_END })) {
        const at = await client.run(MAP_SPOT, cell);
        await glide(client, at);
        await sleepMs(2_000);
        mark(`hover-${name}`);
      }
      const end = await client.run(MAP_SPOT, RUNWAY_END);
      await input.scroll(client, { at: end, yOffset: 1, count: 3 });
      mark("zoom");
      await sleepMs(1_500);
      await input.drag(client, { from: end, to: { x: end.x + 60, y: end.y + 30 }, steps: 16 });
      await input.move(client, { x: 4, y: 120 });
      mark("pan");
      await sleepMs(2_000);
      await input.key(client, { keyCode: 256 });
      await sleepMs(1_200);
    });
  await world.command(server, `effect clear ${s.player} minecraft:invisibility`);
  return { shot, opened };
}

// ---------------------------------------------------------------------------------------------------------------
// 3 — one tank, crewed, sent at the range and moved on

async function shotArmour(run: Run, pair: PromoPair, s: Stage) {
  const { server, client } = pair;
  await quiet(pair, s);
  const m1 = s.ids.m1;
  const targets: string[] = [];
  // The tactical view from north of the motor pool: the empty tank and the crews waiting beside it in front, the
  // range beyond it to the south and the ground it drives on to.
  await openTactical(pair, s, { x: -36, y: FLOOR, z: 71 }, { x: -38, y: FLOOR + 26, z: 34 });
  const boarding: any[] = [];
  const firing: any[] = [];
  const driving: any[] = [];
  const goal = { x: -18, y: FLOOR, z: 84 };
  const shot = await clip(run, pair, "03-armour", "Tactical view over the motor pool: the player clicks an empty M1A2 "
    + "and presses Board; the nearest idle residents run over and climb into its driver's and gunner's seats; with "
    + "the same tank still selected the player presses Attack and clicks the range, and it opens fire on the targets "
    + "until none is left; then a Move order drives it across the range", async (mark) => {
      // Only the last stage's "done" is the clip's: the film cuts a little after it.
      const stage = (name: string): Mark => label => mark(label === "done" ? name : label);
      await sleepMs(1_200);
      await clickEntity(client, m1);
      mark("selected");
      await waitForElement(client, { id: "folkways.rts.unit.board" });
      await sleepMs(1_200);
      await clickElement(client, { id: "folkways.rts.unit.board" });
      mark("board");
      await input.move(client, { x: 4, y: 120 });
      await sampleUntil(boarding, 45_000, async () => (await readFleet(server, [m1]))[m1], one => one.aboard >= 2,
        stage("aboard"));
      await sleepMs(1_500);
      await input.key(client, { keyCode: KEY.G });
      await sleepMs(700);
      await clickGround(client, { x: -45, y: FLOOR, z: 76 });
      mark("attack");
      // The targets walk on only now, in front of the tank, so nothing fires on them before the order.
      targets.push(...await server.run(TARGETS, { health: 400, points: [
        { x: -56, y: FLOOR, z: 96 }, { x: -48, y: FLOOR, z: 100 }, { x: -40, y: FLOOR, z: 95 }, { x: -32, y: FLOOR, z: 99 },
      ] }));
      await input.move(client, { x: 4, y: 120 });
      await sampleUntil(firing, 60_000, async () => ({ left: await server.run(TARGETS_LEFT, { ids: targets }) }),
        one => one.left === 0, stage("cleared"), (one, prev) => { if (prev && one.left < prev.left) mark(`hit-${one.left}`); });
      await sleepMs(1_500);
      await orderMove(client, goal);
      mark("move");
      await input.move(client, { x: 4, y: 120 });
      await sampleUntil(driving, 40_000, async () => (await readFleet(server, [m1]))[m1],
        one => Math.hypot(one.x - goal.x, one.z - goal.z) < 5 && one.speed < 0.05, mark);
      await sleepMs(1_500);
    });
  run.note("armour-boarding", boarding.filter((_, i) => i % 5 === 0 || i === boarding.length - 1));
  run.note("armour-driving", driving.filter((_, i) => i % 5 === 0 || i === driving.length - 1));
  return { shot, aboard: boarding.at(-1)?.aboard ?? 0, targets: targets.length, left: firing.at(-1)?.left,
    drove: driving.at(-1) };
}

// ---------------------------------------------------------------------------------------------------------------
// 5 — helicopters

async function shotHelicopters(run: Run, pair: PromoPair, s: Stage) {
  const { server, client } = pair;
  await quiet(pair, s);
  await airportReady(server);
  const [ah6, mi28] = [s.ids.ah6, s.ids.mi28];
  const targets: string[] = [];
  await openTactical(pair, s, { x: 130, y: FLOOR, z: -70 }, { x: 130, y: FLOOR + 32, z: -14 });
  const samples: any[] = [];
  const shot = await clip(run, pair, "05-helicopters", "Two helicopters on their pads: the player selects both, sets "
    + "their altitude to Low and orders an attack on targets past the pads; they lift off on the order alone, strike "
    + "the targets, then the AH-6 alone is told to land and the airport assigns a free pad for its return",
    async (mark) => {
      await sleepMs(800);
      // The AH-6 on group 2 first, so it can be picked out alone once it is in the air.
      await clickEntity(client, ah6);
      await sleepMs(500);
      await input.key(client, { keyCode: 50, modifiers: ["ctrl"] });
      await sleepMs(600);
      await dragAround(client, [ah6, mi28]);
      await sleepMs(900);
      // Low for the strike run: the band each helicopter flies over the terrain.
      await setAltitude(client, "low");
      mark("low");
      await sleepMs(900);
      await input.key(client, { keyCode: KEY.G });
      await sleepMs(700);
      await clickGround(client, { x: 134, y: FLOOR, z: -122 });
      mark("attack");
      targets.push(...await server.run(TARGETS, { health: 120, points: [
        { x: 126, y: FLOOR, z: -121 }, { x: 134, y: FLOOR, z: -124 }, { x: 142, y: FLOOR, z: -121 },
      ] }));
      await input.move(client, { x: 4, y: 120 });
      await sampleUntil(samples, 45_000, async () => ({ left: await server.run(TARGETS_LEFT, { ids: targets }) }),
        one => one.left === 0, mark);
      await sleepMs(1_500);
      // The AH-6 alone, by its group, then Land at its home airport.
      await input.key(client, { keyCode: 50 });
      await sleepMs(1_200);
      await input.key(client, { keyCode: KEY.L });
      mark("land");
      await input.move(client, { x: 4, y: 120 });
      await sampleUntil(samples, 40_000, async () => (await readFleet(server, [ah6]))[ah6],
        one => one.ground && one.speed < 0.05, mark);
      await sleepMs(2_000);
    });
  return { shot, landed: samples.at(-1) };
}

// ---------------------------------------------------------------------------------------------------------------
// 6 — the A-10 off runway 09R

async function shotTakeoff(run: Run, pair: PromoPair, s: Stage) {
  const { server, client } = pair;
  await quiet(pair, s);
  await airportReady(server);
  const a10 = s.ids.a10;
  // Keep the gunship parked beside 09R clear of the lens's path down to the runway.
  await world.command(server, `tp ${s.ids.ac130} 30.5 ${FLOOR} -250.5`);
  await openTactical(pair, s, { x: -140, y: FLOOR, z: 0 }, { x: -132, y: FLOOR + 12, z: 20 });
  const samples: any[] = [];
  const fly = async (mark: Mark) => {
    await sleepMs(1_000);
    await clickEntity(client, a10);
    await sleepMs(1_000);
    // A loiter order lifts a parked aircraft: the airport assigns its runway first.
    await orderLoiter(client, { x: -150, y: FLOOR, z: -40 });
    mark("ordered");
    await input.move(client, { x: 4, y: 120 });
    await sleepMs(1_500);
    // Out of the tactical view in one glide, down beside the runway, as it rolls past and lifts.
    run.note("takeoff-glide", await glideFromTactical(pair,
      { follow: a10, offset: { x: -24, y: 9, z: 36 }, lookAt: a10, omega: 2.2 }, 3.5));
    await sampleUntil(samples, 40_000, async () => (await readFleet(server, [a10]))[a10],
      one => one.y > FLOOR + 25, mark);
    await sleepMs(3_000);
  };
  const shot = await clip(run, pair, "06-takeoff", "An A-10 at the end of runway 09R: the player clicks it and orders it "
      + "to loiter over the field; the airport first assigns a runway fitting its takeoff roll and span; the camera "
      + "rides beside it as it rolls down the runway and climbs out", fly);
  await rigStop(client);
  return { shot, climbed: samples.at(-1) };
}

// ---------------------------------------------------------------------------------------------------------------
// 7 — the catapult

async function shotCatapult(run: Run, pair: PromoPair, s: Stage) {
  const { server, client } = pair;
  await quiet(pair, s);
  await airportReady(server);
  const ju87 = s.ids.ju87;
  await openTactical(pair, s, { x: 130, y: FLOOR, z: -140 }, { x: 150, y: FLOOR + 14, z: -126 });
  const samples: any[] = [];
  run.note("ground-catapult-needs", await server.run(LANDING_ASSIGNMENT, { id: ju87 }));
  // Complete chunk delivery and entity pairing before the clip rolls.
  await projectEntity(client, ju87, { server, player: s.player });
  await frames(client, 20);
  await sleepMs(3_000);
  const shot = await clip(run, pair, "07-catapult", "A Ju-87 at the east end of runway 27R, a catapult "
    + "set into the runway ahead of it: the player orders it to loiter and the airport assigns a catapult "
    + "departure; the aircraft taxis onto the shuttle and is flung down the rails into the air", async (mark) => {
      await sleepMs(1_000);
      run.note("ground-catapult-where", { fleet: (await readFleet(server, [ju87]))[ju87],
        player: await world.command(server, `data get entity ${s.player} Pos`) });
      await clickEntity(client, ju87, { server, player: s.player });
      await sleepMs(1_900);
      await orderLoiter(client, { x: 95, y: FLOOR, z: -145 });
      mark("ordered");
      let controller: any;
      await expect.poll(async () => {
        controller = await server.run(CONTROLLER, { id: ju87 });
        return controller.launch;
      }, { timeout: 5_000, message: "The Loiter order must acquire the ground catapult" }).not.toBe("null");
      expect(controller.departure).toContain("catapults=[Catapult[");
      run.note("ground-catapult-order", controller);
      if (controller.launch !== "null") {
        mark("catapult");
      }
      await input.move(client, { x: 4, y: 120 });
      // Reach the rails during the hookup: waiting here hides the short tow stroke behind the tactical view.
      run.note("catapult-glide", await glideFromTactical(pair,
        { eye: [{ x: CATAPULT.x + 4, y: FLOOR + 3, z: CATAPULT.z + 10 }], lookAt: ju87, omega: 3 }, .6));
      await sampleUntil(samples, 40_000, async () => (await readFleet(server, [ju87]))[ju87],
        one => one.y > FLOOR + 15, mark, (one, prev) => {
          if (String(one.action).endsWith("catapult") && !String(prev?.action).endsWith("catapult")) mark("catapult");
        });
      await sleepMs(2_500);
    });
  await rigStop(client);
  return { shot, climbed: samples.at(-1) };
}

// ---------------------------------------------------------------------------------------------------------------
// The carrier

type Carrier = {
  structure: string; origin: Vec; tower: Vec; airport: string; sites: string[];
  ju87: string; shuttle: string; decor: string[];
};
let carrier: Carrier | undefined;

/** Lays the carrier out, assembles it, floats it, staffs and surveys its tower and parks its deck. Once per take. */
async function ensureCarrier(run: Run, pair: PromoPair, s: Stage): Promise<Carrier> {
  if (carrier) return carrier;
  const { server } = pair;
  // Each filmed clip leaves the server frozen; the carrier's crew has to walk and board, so it is set up running.
  await tick.freeze(server, false);
  try {
    return await buildCarrier(run, pair, s);
  } finally {
    await tick.freeze(server, true);
  }
}

async function buildCarrier(run: Run, pair: PromoPair, s: Stage): Promise<Carrier> {
  const { server } = pair;
  const built = await server.run(CARRIER, { op: "build", ...CARRIER_BASE });
  run.note("carrier-build", built);
  run.note("carrier-assemble", await world.command(server,
    `sable assemble area ${built.min.join(" ")} ${built.max.join(" ")}`));
  const floated = await server.run(CARRIER, { op: "float", turn: CARRIER_TURN, at: CARRIER_AT });
  run.note("carrier-float", floated);
  const tower: Vec = floated.tower;
  // The deck's people: a controller for the island's chair, a pilot and gunner for the Ju-87 on the catapult.
  const pool = [...s.waiting];
  const controller = pool.pop()!;
  run.note("carrier-tower", await server.run(SURVEY, { ...tower, colony: s.colony, name: CARRIER_NAME, timeoutMs: 200_000 }));
  run.note("carrier-controller", await server.run(CARRIER, { op: "seat", resident: controller }));
  const parked = await server.run(CARRIER, { op: "spawn", vehicles: [
    // Parked aft, out of the runway: the deck at rest. A helicopter stands on the after pad; the landing helicopter
    // takes the forward one.
    { key: "a10", type: "superbwarfare:a_10a", x: 12, z: -11, heading: 0 },
    { key: "ah6", type: "superbwarfare:ah_6", x: DECK.pads[1].x, z: DECK.pads[1].z, heading: 0 },
  ] });
  run.note("carrier-parked", parked);
  const shuttle = await server.run(CARRIER, { op: "shuttle" });
  run.note("carrier-shuttle", shuttle);
  // Residents board only from footing in the world, not on a structure's deck: the Ju-87 is crewed on the grass
  // below, then set on the deck behind the catapult, crew and all.
  const spawned = await server.run(FLEET_TASK, { op: "spawn", vehicles: [{ key: "ju87", type: "superbwarfare:ju_87",
    x: CARRIER_AT.x + 0.5, y: FLOOR, z: CARRIER_AT.z + 60.5, yaw: 0 }] });
  const ju87 = spawned.ju87.uuid;
  const crew = await server.run(FLEET_TASK, { op: "crew", vehicle: ju87, residents: [...pool].reverse() });
  run.note("carrier-crew", crew);
  const left = pool.filter(id => !crew.used.includes(id));
  s.waiting.splice(0, s.waiting.length, ...left);
  await waitSeated(server, { ju87 }, ["ju87"]);
  run.note("carrier-ju87", await server.run(CARRIER, { op: "place", id: ju87, ...DECK.ju87, heading: 0 }));
  await sleepMs(2_000);
  const ready = await carrierReady(server, tower);
  run.note("carrier-airport", ready);
  carrier = { structure: floated.structure,
    origin: { x: floated.origin[0], y: floated.origin[1], z: floated.origin[2] },
    tower, airport: ready.airport, sites: ready.sites, ju87, shuttle: shuttle.shuttle,
    decor: [parked.a10.uuid, parked.ah6.uuid] };
  // The command card learns of the new airport with the next roster: give it time before any Land order.
  await sleepMs(8_000);
  return carrier;
}

/** World points over the deck: `x` along it from the stern, `z` across (starboard +), `y` above it. */
async function deck(server: MinecraftServer, points: { x: number; z: number; y?: number }[]): Promise<Vec[]> {
  return server.run(CARRIER, { op: "where", points });
}

// 9 — the carrier

async function shotCarrier(run: Run, pair: PromoPair, s: Stage) {
  const { server, client } = pair;
  await quiet(pair, s);
  const c = await ensureCarrier(run, pair, s);
  // From low off the starboard beam, round under the island and up astern, ending on the deck's whole length with
  // the field beyond the bow.
  const [a, b, d, e, f, g] = await deck(server, [
    { x: 70, z: 75, y: 6 }, { x: -20, z: 55, y: 22 }, { x: -95, z: 0, y: 40 },
    { x: 80, z: 0 }, { x: 105, z: 0 }, { x: 150, z: 0 }]);
  const eye = [a, b, d];
  const look = [e, f, g];
  // Two passes first, so the client has meshed every chunk along the path before the take rolls.
  for (let pass = 0; pass < 2; pass++) {
    await rigMove(client, { eye, look, seconds: 8, carry: true });
    await sleepMs(9_000);
  }
  await rigMove(client, { eye: [eye[0]], look: [look[0]], carry: true });
  await sleepMs(10_000);
  const shot = await clip(run, pair, "09-carrier", "A carrier floats high over the plains on its levitite keel: a "
    + "straight flight deck with a runway, two helipads and a catapult toward the bow, the island on the starboard "
    + "side crowned by the airport tower's glass cab; the camera sweeps from the stern quarter up over the deck",
    async () => {
      await rigMove(client, { eye, look, seconds: 16, carry: true });
      await sleepMs(16_500);
    });
  return { shot, airport: c.airport, sites: c.sites };
}

// 10 — a helicopter onto the deck

const DECK_CLIENT_POSITIONS = defineTask<{ ids: string[] }, Record<string, any>>({
  name: "folkways.airport.deck-client-positions", side: "client",
  source: javaTask({
    imports: ["net.minecraft.client.Minecraft", "dev.ryanhcode.sable.Sable",
      "dev.ryanhcode.sable.mixinterface.entity.entities_stick_sublevels.EntityStickExtension"],
    body: `
var ids = Args.of(ctx).strings("ids");
var out = new LinkedHashMap<String, Object>();
for (String id : ids) {
    out.put(id, Map.of("exists", false));
}
for (var entity : Minecraft.getInstance().level.entitiesForRendering()) {
    String id = entity.getUUID().toString();
    if (ids.contains(id)) {
        var actual = Minecraft.getInstance().level.getEntity(entity.getId());
        var row = new LinkedHashMap<String, Object>();
        row.put("exists", true);
        row.put("world", List.of(entity.getX(), entity.getY(), entity.getZ()));
        row.put("tick", entity.tickCount);
        row.put("chunkLoaded", Minecraft.getInstance().level.hasChunkAt(entity.blockPosition()));
        row.put("interpolated", entity.getPosition(0.5f).toString());
        var tracking = Sable.HELPER.getTrackingSubLevel(entity);
        var containing = Sable.HELPER.getContaining(entity);
        row.put("tracking", tracking == null ? "none" : tracking.getUniqueId().toString());
        row.put("containing", containing == null ? "none" : containing.getUniqueId().toString());
        row.put("plot", String.valueOf(((EntityStickExtension) entity).sable$getPlotPosition()));
        row.put("invisible", entity.isInvisible());
        row.put("entityId", entity.getId());
        row.put("tracked", actual == entity);
        row.put("old", List.of(entity.xOld, entity.yOld, entity.zOld));
        row.put("bounds", entity.getBoundingBox().toString());
        row.put("positionO", entity instanceof com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity v
            ? v.getPositionO().toString() : "other");
        if (actual == entity) {
            out.put(id, row);
        }
    }
}
out.put("player", Minecraft.getInstance().player.position().toString());
out.put("time", Minecraft.getInstance().level.getGameTime());
return out;`,
  }),
});

async function shotDeckHelicopter(run: Run, pair: PromoPair, s: Stage) {
  const { server, client } = pair;
  await quiet(pair, s);
  const c = await ensureCarrier(run, pair, s);
  const ah6 = s.ids.ah6;
  const observeServer = dockObserve("server"), observeClient = dockObserve("client");
  await server.run(observeServer, { action: "start", ids: [...c.decor, ah6, c.ju87] });
  await client.run(observeClient, { action: "start", ids: [...c.decor, ah6, c.ju87] });
  const start = (await readFleet(server, [ah6]))[ah6];
  const pad = DECK.pads[0];
  const [padEye, padLook, padBelow] = await deck(server, [{ x: pad.x + 22, z: pad.z + 26, y: 9 },
    { x: pad.x, z: pad.z, y: 1 }, { x: pad.x, z: pad.z, y: -30 }]);
  await openTactical(pair, s, { x: start.x, y: FLOOR, z: start.z }, { x: start.x, y: FLOOR + 22, z: start.z + 24 });
  const samples: any[] = [];
  let clientPositionsAgree = false;
  const shot = await clip(run, pair, "10-deck-helicopter", "The player picks the AH-6 on its pad at the field and "
    + "orders it to land at the carrier, chosen in the card's picker; it lifts off on the order, crosses to the "
    + "floating carrier and settles on a deck helipad",
    async (mark) => {
      await sleepMs(1_000);
      await clickEntity(client, ah6);
      run.note("card-airports", await cardKnows(client, c.airport));
      await sleepMs(1_200);
      // Land, choosing the carrier in the card's picker: it lifts off its pad on the order.
      await orderLand(client, c.airport, mark);
      mark("ordered");
      await sleepMs(500);
      run.note("deck-helicopter-controller", await server.run(CONTROLLER, { id: ah6 }));
      if (process.env.FOLKWAYS_CARRIER_PROBE) {
        run.note("deck-helicopter-probe", await server.run(CONTROLLER, { id: ah6, order: "LAND", airport: c.airport }));
      }
      await input.move(client, { x: 4, y: 120 });
      await sleepMs(1_000);
      // Out of the tactical view in one glide to the helicopter's side. The player rides the lens from here, so it
      // is first set where the lens starts (it stands well below the tactical eye), while the picture still holds.
      run.note("deck-helicopter-glide", await glideFromTactical(pair,
        { follow: ah6, offset: { x: -14, y: 6, z: 16 }, lookAt: ah6, carry: true, omega: 3 }, 5, async ({ eye }) => {
          await world.command(server, `tp ${s.player} ${eye.x} ${eye.y - 1.62} ${eye.z}`);
          await sleepMs(500);
        }));
      await sampleUntil(samples, 90_000, async () => {
        const one = (await readFleet(server, [ah6]))[ah6];
        const at = (await server.run(CARRIER, { op: "track", ids: [ah6] }))[ah6];
        return { ...one, deck: at };
      // A hull docked on the deck rides the structure and does not report ground: landed and still is enough.
      }, one => (one.ground || String(one.action).endsWith("landed")) && one.speed < 0.05
        && Math.abs(one.deck?.y ?? 99) < 2, mark, async (one, prev) => {
        // Over the deck means above its plan as well as near its height: climbing out of the field the helicopter
        // passes the deck's height long before it reaches the carrier.
        const over = (one: any) => Math.abs(one?.deck?.y ?? 99) < 12 && one.deck.x > -10 && one.deck.x < 210
          && Math.abs(one.deck.z) < 40;
        if (over(one) && !(prev && over(prev))) {
          mark("over-deck");
          // Bring the camera to the pad's corner so the live probe can compare both AH-6s from one viewpoint.
          // The camera flies free, but what the server sends depends on where the player is: left at the field,
          // the deck's hulls are out of tracking range. The player waits under the deck, out of the frame.
          // The lens glides there from the follow, one camera throughout.
          await world.command(server, `tp ${s.player} ${padBelow.x} ${padBelow.y} ${padBelow.z}`);
          await rigMove(client, { eye: [padEye], look: [padLook], glide: 4 });
        }
      });
      await sleepMs(2_500);
      // Sample while ticking: freezing the server can re-pair missing hulls and hide a tracking failure.
      const ids = [...c.decor, ah6, c.ju87];
      const serverPositions = await server.run(CARRIER, { op: "track", ids });
      const clientPositions = await client.run(DECK_CLIENT_POSITIONS, { ids });
      run.note("deck-helicopter-live-server", serverPositions);
      run.note("deck-helicopter-live-client", clientPositions);
      const positions = compareDeckPositions(ids, serverPositions, clientPositions);
      run.note("deck-helicopter-live-position-agreement", positions);
      // The decoration AH-6 is the second decoration; the A-10 and Ju-87 remain diagnostic controls.
      clientPositionsAgree = [c.decor[1], ah6].every(id => positions[id].client?.exists === true
        && positions[id].error !== null && positions[id].error <= 1.5);
      run.note("deck-helicopter-client-lifecycle", await client.run(observeClient, { action: "stop" }));
      run.note("deck-helicopter-server-lifecycle", await server.run(observeServer, { action: "stop" }));
    });
  await rigStop(client);
  const ids = [...c.decor, ah6];
  const serverPositions = await server.run(CARRIER, { op: "track", ids });
  const clientPositions = await client.run(DECK_CLIENT_POSITIONS, { ids });
  const positions = compareDeckPositions(ids, serverPositions, clientPositions);
  run.note("deck-helicopter-decor", serverPositions);
  run.note("deck-helicopter-client-positions", positions);
  const last = samples.at(-1);
  return { shot, clientPositionsAgree,
    // Same test as the wait above: a hull docked on the deck reports landed, not ground.
    landed: { ...last, deck: Math.abs(last?.deck?.y ?? 99) < 2
      && (last?.ground === true || String(last?.action).endsWith("landed")) } };
}

function compareDeckPositions(ids: string[], server: Record<string, any>, client: Record<string, any>) {
  return Object.fromEntries(ids.map(id => {
    const serverWorld = server[id]?.world;
    const clientWorld = client[id]?.world;
    const error = serverWorld && clientWorld
      ? Math.hypot(...serverWorld.map((n: number, i: number) => n - clientWorld[i])) : null;
    return [id, { server: server[id], client: client[id], error }];
  }));
}

// 11 — the deck catapult
//
// The tactical view cannot pick a hull standing on a structure's deck (it projects the hull from the structure's
// frame), so shots 11 and 12 give their order through the vehicle provider, the same order the card sends.

async function shotDeckCatapult(run: Run, pair: PromoPair, s: Stage) {
  const { server, client } = pair;
  await quiet(pair, s);
  const c = await ensureCarrier(run, pair, s);
  const [beside, along, ahead] = await deck(server, [{ x: DECK.ju87.x - 10, z: 16, y: 4 },
    { x: DECK.catapult.x + 20, z: DECK.runway.z, y: 2 }, { x: 175, z: DECK.runway.z }]);
  await rigMove(client, { eye: [beside], look: [along], carry: true });
  await sleepMs(6_000);
  const samples: any[] = [];
  let launched = false;
  const shot = await clip(run, pair, "11-deck-catapult", "The Ju-87 on the carrier's deck, the catapult set into "
    + "the runway ahead of it: ordered to loiter, the carrier's tower assigns its catapult, the aircraft taxis onto "
    + "the shuttle and is flung off the bow", async (mark) => {
      await sleepMs(1_500);
      run.note("deck-catapult-before", await server.run(CONTROLLER, { id: c.ju87 }));
      run.note("deck-catapult-shuttle", await server.run(CARRIER, { op: "track", ids: [c.shuttle] }));
      // High clears this elevated deck; Cruise is measured from the terrain below it.
      const controller = await server.run(CONTROLLER,
        { id: c.ju87, order: "LOITER", point: ahead, altitude: "HIGH" });
      run.note("deck-catapult-order", controller);
      expect(controller.departure).toContain("catapults=[Catapult[");
      expect(controller.launch).not.toBe("null");
      mark("ordered");
      await sampleUntil(samples, 45_000, async () => {
        const one = (await readFleet(server, [c.ju87]))[c.ju87];
        const at = (await server.run(CARRIER, { op: "track", ids: [c.ju87] }))[c.ju87];
        const shuttleServer = (await server.run(CARRIER, { op: "track", ids: [c.shuttle] }))[c.shuttle];
        const shuttleClient = (await client.run(DECK_CLIENT_POSITIONS, { ids: [c.shuttle] }))[c.shuttle];
        return { ...one, deck: at, shuttleServer, shuttleClient };
      }, one => one.deck?.y > 15, mark, (one, prev) => {
        if (String(one.action).endsWith("catapult") && !String(prev?.action).endsWith("catapult")) mark("catapult");
      });
      launched = samples.at(-1)?.deck?.y > 15;
      run.note("deck-catapult-after", await server.run(CONTROLLER, { id: c.ju87 }));
      await sleepMs(2_500);
    });
  await rigStop(client);
  const shuttleStart = samples[0]?.shuttleClient?.world;
  const shuttleEnd = samples.at(-1);
  const shuttleTravel = shuttleStart ? Math.max(...samples.map(one => one.shuttleClient?.world
    ? Math.hypot(...one.shuttleClient.world.map((n: number, i: number) => n - shuttleStart[i])) : 0)) : 0;
  const shuttleReturnError = shuttleEnd?.shuttleClient?.world && shuttleEnd?.shuttleServer?.world
    ? Math.hypot(...shuttleEnd.shuttleClient.world.map((n: number, i: number) => n - shuttleEnd.shuttleServer.world[i]))
    : Infinity;
  expect(shuttleTravel, "the client shuttle travels down the deck rail").toBeGreaterThan(2);
  expect(shuttleReturnError, "the client shuttle returns to the server's rail position").toBeLessThan(0.1);
  const result = { shot, launched, climbed: samples.at(-1), path: samples, shuttleTravel, shuttleReturnError };
  run.note("deck-catapult", result);
  return result;
}

// 12 — back onto the deck: a test of the deck's runway length, not filmed

async function shotDeckLanding(run: Run, pair: PromoPair, s: Stage) {
  const { server } = pair;
  await quiet(pair, s);
  const c = await ensureCarrier(run, pair, s);
  // VisualRun.clip freezes the whole server after shot 11. This unfilmed measurement owns its tick lifecycle.
  run.note("deck-landing-before", await server.run(CONTROLLER, { id: c.ju87 }));
  const probe = landingMotion("server");
  let observing = false;
  await tick.freeze(server, false);
  try {
    // Operating the catapult lever invalidates the airport survey, just like a player changing a block.
    run.note("deck-landing-airport", await carrierReady(server, c.tower));
    // Not filmed, but the player rides along: an aircraft out of simulation distance stops ticking and never lands.
    // The client has lost it by now, so the player is first put beside it on the server.
    const away = (await readFleet(server, [c.ju87]))[c.ju87];
    await world.command(server, `tp ${s.player} ${away.x.toFixed(1)} ${(away.y + 10).toFixed(1)} ${away.z.toFixed(1)}`);
    await sleepMs(3_000);
    await rigMove(pair.client, { follow: c.ju87, offset: { x: 0, y: 8, z: 0 }, lookAt: c.ju87, carry: true });
    await sleepMs(3_000);
    await server.run(probe, { action: "start", id: c.ju87, frame: c.structure, origin: c.origin });
    observing = true;
    const order = await server.run(CONTROLLER, { id: c.ju87, order: "LAND", airport: c.airport });
    const assignment = await server.run(LANDING_ASSIGNMENT, { id: c.ju87 });
    const samples: any[] = [];
    if (String(order.result).includes("accepted=true")) {
      await sampleUntil(samples, 150_000, async () => {
        const one = (await readFleet(server, [c.ju87]))[c.ju87];
        const at = (await server.run(CARRIER, { op: "track", ids: [c.ju87] }))[c.ju87];
        return { ...one, deck: at, controller: await server.run(CONTROLLER, { id: c.ju87 }) };
      }, one => one.wreck || one.gone || String(one.action).endsWith("landed") && one.speed < 0.05, () => {},
      async one => {
        if (!one.gone) {
          await world.command(server, `tp ${s.player} ${one.x} ${one.y + 8} ${one.z}`);
        }
      });
    }
    // Measure contact and stopping on server ticks, rather than the sparse camera samples.
    const motion = await server.run(probe, { action: "stop" });
    observing = false;
    writeFileSync(`${run.dir}/deck-landing-motion.json`, JSON.stringify(motion));
    const contact = motion.find((one: any) => one.groundContactTicks > 0 || one.touchdown === true);
    const stopped = motion.find((one: any) => one.touchdown === true && one.speed < 0.05);
    const touchdown = contact?.deck?.x;
    const stop = stopped?.deck?.x;
    const result = { order: order.result, needs: assignment.needs, runway: DECK.runway,
      length: DECK.runway.to - DECK.runway.from + 1, needsLanding: assignment.landing,
      touchdown: touchdown ?? null, stop: stop ?? null,
      rollout: touchdown === undefined || stop === undefined ? null : Math.abs(stop - touchdown),
      completed: stopped !== undefined, last: samples.at(-1),
      contact: contact ?? null, stopped: stopped ?? null, ticksObserved: motion.length,
      controller: await server.run(CONTROLLER, { id: c.ju87 }),
      path: samples.filter((_, i) => i % 3 === 0).map(one => ({ t: one.t, action: String(one.action).split(".").at(-1),
        speed: one.speed, ground: one.ground, tickCount: one.controller?.tickCount,
        gameTime: one.controller?.gameTime, frozen: one.controller?.frozen,
        deck: one.deck && { x: one.deck.x, y: one.deck.y, z: one.deck.z } })) };
    run.note("deck-landing", result);
    return result;
  } finally {
    if (observing) {
      await server.run(probe, { action: "stop" });
    }
    await rigStop(pair.client);
    await tick.freeze(server, true);
  }
}

// ---------------------------------------------------------------------------------------------------------------
// Aircraft orders on the command card. There is no take-off step: a Move or Attack order lifts a parked helicopter
// and a Loiter order a parked fixed wing, the airport assigning the runway, pad or catapult first.

/** Loiter over `point`: Loiter (O), then a click on the ground there. */
async function orderLoiter(client: MinecraftClient, point: Vec) {
  await input.key(client, { keyCode: KEY.O });
  await sleepMs(600);
  await clickGround(client, point);
}

/** Move to `point`: Move (M), then a click on the ground there. */
async function orderMove(client: MinecraftClient, point: Vec) {
  await input.key(client, { keyCode: KEY.M });
  await sleepMs(600);
  await clickGround(client, point);
}

/** The card's altitude band for every selected aircraft: low, cruise or high. */
async function setAltitude(client: MinecraftClient, band: "low" | "cruise" | "high") {
  const id = `folkways.rts.unit.altitude.${band}`;
  await waitForElement(client, { id });
  await clickElement(client, { id });
}

/** The airports the command card knows of: its roster carries the landing sites it may offer. */
const CARD_AIRPORTS = defineTask<Record<string, never>, string[]>({
  name: "folkways.promo.airport.card-airports", side: "client",
  source: javaTask({ imports: ["io.github.izakyl.folkways.core.command.combat.net.ClientUnits"], body: `
return ClientUnits.snapshot().map(snapshot -> snapshot.landingSites().stream().map(site -> site.airport().id())
    .distinct().toList()).orElse(List.of());` }),
});

/** Waits until the card's roster offers `airport`, so its Land picker lists it. */
async function cardKnows(client: MinecraftClient, airport: string, timeoutMs = 60_000) {
  const t0 = Date.now();
  let seen: string[] = [];
  while (Date.now() - t0 < timeoutMs) {
    seen = await client.run(CARD_AIRPORTS, {});
    if (seen.includes(airport)) return seen;
    await sleepMs(1_000);
  }
  throw new Error(`The command card never offered ${airport}: ${JSON.stringify(seen)}`);
}

/** Land: with more than one airport the card asks where; `airport` picks that airport's row. */
async function orderLand(client: MinecraftClient, airport: string | undefined, mark: Mark) {
  await input.key(client, { keyCode: KEY.L });
  if (!airport) return;
  const row = pickerRow(airport);
  await waitForElement(client, { id: row });
  mark("picker");
  await sleepMs(1_200);
  await clickElement(client, { id: row });
  await sleepMs(700);
  await clickElement(client, { id: "folkways.choice.confirm" });
}

// ---------------------------------------------------------------------------------------------------------------
// The tactical view and its clicks

/** Opens the tactical view with its camera at `eye`, looking at `focus`. */
async function openTactical(pair: PromoPair, s: Stage, focus: Vec, eye: Vec) {
  const { server, client } = pair;
  await rigStop(client);
  await world.command(server, `item replace entity ${s.player} weapon.mainhand with folkways_core:colony_book`);
  await world.command(server, `tp ${s.player} ${eye.x} ${eye.y - 12} ${eye.z} 180 0`);
  await frames(client, 10);
  if (!(await client.run(TACTICAL, { action: "state" })).on) {
    await tapV(client);
    await screen.waitFor(client, "OverheadScreen", { timeoutMs: 8_000 });
    await pickRtsTool(client);
  }
  await client.run(TACTICAL, { action: "place", eye, focus });
  await frames(client, 20);
  await clientClear(client);
  await sleepMs(1_500);
}

/** Who holds the book: set once the stage is up, so leaving the tactical view can put the book away. */
let holder: { server: MinecraftServer; player: string; run: Run } | undefined;
const projectionEvidence: any[] = [];

async function leaveTactical(client: MinecraftClient) {
  // Leave the tactical view before setting up the next shot.
  if ((await client.run(TACTICAL, { action: "state" })).on) {
    await client.run(TACTICAL, { action: "leave" });
    await frames(client, 5);
  }
  if (holder) {
    await world.command(holder.server, `item replace entity ${holder.player} weapon.mainhand with minecraft:air`);
  }
  await clientClear(client);
}

/**
 * Leaves the tactical view without a cut. The rig first holds the world view exactly where the tactical view draws
 * it, so leaving takes away only the view's UI; `move` then glides from that pose into its own framing over
 * `seconds`. `between` runs once the view is left and before the glide, while the picture still holds.
 */
async function glideFromTactical(pair: PromoPair, move: RigMove, seconds: number, between?: (pose: { eye: Vec; look: Vec }) => Promise<void>) {
  const { client } = pair;
  const pose = await client.run(TACTICAL, { action: "pose", reach: 24 });
  if (!pose.on) throw new Error("The tactical view is not open to glide out of");
  await rigMove(client, { eye: [pose.eye], look: [pose.look] });
  await frames(client, 3);
  await leaveTactical(client);
  await between?.(pose);
  await frames(client, 2);
  const moved = await rigMove(client, { ...move, glide: seconds });
  return { pose, moved };
}

async function clickEntity(client: MinecraftClient, entity: string,
  resync?: { server: MinecraftServer; player: string }) {
  const at = await projectEntity(client, entity, resync);
  await glide(client, { x: at.x, y: at.y });
  await input.click(client, { x: at.x, y: at.y }, { button: "left" });
}

/** Where `entity` is on screen, once the client tracks it. */
async function projectEntity(client: MinecraftClient, entity: string,
  resync?: { server: MinecraftServer; player: string }) {
  const context = resync ?? holder;
  if (!context) {
    throw new Error("Entity projection needs the staged player for tracking readiness");
  }
  const args = { entity, player: context.player };
  const before = await context.server.run(AIRPORT_TRACKING, args);
  // Observe normal pairing after chunk delivery; the mod's player heartbeat must drive it.
  await expect.poll(async () => {
    const tracking = await context.server.run(AIRPORT_TRACKING, args);
    return tracking.ready && tracking.paired;
  }, { timeout: 10_000, message: "normal tracking must pair the hull after chunk delivery" }).toBe(true);
  const paired = await context.server.run(AIRPORT_TRACKING, args);
  await frames(client, 10);
  const at = await client.run(PROJECT, { x: 0, y: 0, z: 0, entity });
  const evidence = { entity, before, paired, projectionRetries: 0, teleportRetries: 0 };
  projectionEvidence.push(evidence);
  holder?.run.note("entity-projections", projectionEvidence);
  console.log(`projectEntity: ${JSON.stringify(evidence)}`);
  return at;
}

async function clickGround(client: MinecraftClient, point: Vec) {
  const at = await client.run(PROJECT, { x: Math.round(point.x), y: Math.round(point.y), z: Math.round(point.z),
    entity: "" });
  await glide(client, { x: at.x, y: at.y });
  await input.click(client, { x: at.x, y: at.y }, { button: "left" });
}

async function dragAround(client: MinecraftClient, entities: string[]) {
  const boxes = await Promise.all(entities.map(entity => client.run(PROJECT, { x: 0, y: 0, z: 0, entity })));
  const from = { x: Math.min(...boxes.map(box => box.left)) - 8, y: Math.min(...boxes.map(box => box.top)) - 8 };
  const to = { x: Math.max(...boxes.map(box => box.right)) + 8, y: Math.max(...boxes.map(box => box.bottom)) + 8 };
  await glide(client, from);
  await input.drag(client, { from, to, steps: 16, button: "left" });
}

let pointer = { x: 4, y: 120 };
/** The pointer travels to where it clicks, so the film shows the hand that gives each order. */
async function glide(client: MinecraftClient, to: { x: number; y: number }, ms = 600) {
  const steps = 12;
  const from = pointer;
  for (let i = 1; i <= steps; i++) {
    const t = i / steps;
    const e = t * t * (3 - 2 * t);
    await input.move(client, { x: from.x + (to.x - from.x) * e, y: from.y + (to.y - from.y) * e });
    await sleepMs(ms / steps);
  }
  pointer = to;
}

/** Between shots: no screen, no rig, no messages, and the people who wait for the boarding shot back in place. */
async function quiet(pair: PromoPair, s: Stage) {
  await leaveTactical(pair.client);
  await softly(() => screen.dismiss(pair.client));
  await rigStop(pair.client);
  // The book stays out of the free-camera shots: held, it draws the colony's bounds across the sky.
  await world.command(pair.server, `item replace entity ${s.player} weapon.mainhand with minecraft:air`);
  await clientClear(pair.client);
}

async function clip(run: Run, pair: PromoPair, name: string, subject: string, during: (mark: Mark) => Promise<void>) {
  const marks: Record<string, number> = {};
  let t0 = Date.now();
  const gates = await promoClip(run, pair.client, pair.server, name, {
    subject, worldState: {}, seconds: 1,
    during: async () => {
      t0 = Date.now();
      await during((label) => { marks[label] ??= (Date.now() - t0) / 1000; });
    },
  });
  run.note(`marks-${name}`, marks);
  return { gates, marks };
}

async function sampleUntil(samples: any[], timeoutMs: number, read: () => Promise<any>, done: (one: any) => boolean,
  mark: Mark, changed?: (one: any, prev: any) => void | Promise<void>) {
  const t0 = Date.now();
  while (Date.now() - t0 < timeoutMs) {
    const one = await read().catch((error: any) => ({ error: String(error) }));
    one.t = Math.round((Date.now() - t0) / 100) / 10;
    await changed?.(one, samples.at(-1));
    samples.push(one);
    if (done(one)) {
      mark("done");
      return true;
    }
    await sleepMs(700);
  }
  return false;
}

/** The tactical view's eye, placed exactly: Overhead keeps it private, so it is reached the way a test reaches it. */
const TACTICAL = defineTask<Record<string, any>, any>({
  name: "folkways.promo.airport.tactical", side: "client",
  source: javaTask({
    imports: ["net.minecraft.client.Minecraft", "net.minecraft.world.phys.Vec3"],
    body: `
var a = Args.of(ctx);
var type = Class.forName("io.github.izakyl.folkways.core.front.client.Overhead");
var on = type.getDeclaredMethod("on"); on.setAccessible(true);
if (a.string("action").equals("state")) return Map.of("on", (Boolean) on.invoke(null));
if (a.string("action").equals("pose")) {
  // Where the picture is drawn from right now: the camera the frame was rendered with, not the view's own fields.
  var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
  var at = camera.getPosition();
  var look = at.add(Vec3.directionFromRotation(camera.getXRot(), camera.getYRot()).scale(a.number("reach")));
  return Map.of("on", (Boolean) on.invoke(null), "eye", Map.of("x", at.x, "y", at.y, "z", at.z),
    "look", Map.of("x", look.x, "y", look.y, "z", look.z), "yaw", camera.getYRot(), "pitch", camera.getXRot(),
    "fov", Minecraft.getInstance().options.fov().get());
}
if (a.string("action").equals("leave")) {
  var leave = type.getDeclaredMethod("leave", Minecraft.class); leave.setAccessible(true);
  leave.invoke(null, Minecraft.getInstance());
  return Map.of("on", (Boolean) on.invoke(null));
}
var eye = a.object("eye"); var focus = a.object("focus");
var from = new Vec3(eye.number("x"), eye.number("y"), eye.number("z"));
var to = new Vec3(focus.number("x") + 0.5, focus.number("y"), focus.number("z") + 0.5);
var d = to.subtract(from);
float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
var place = type.getDeclaredMethod("place", Vec3.class, float.class, float.class); place.setAccessible(true);
place.invoke(null, from, yaw, pitch);
var focusField = type.getDeclaredField("focus"); focusField.setAccessible(true); focusField.set(null, null);
return Map.of("yaw", yaw, "pitch", pitch);`,
  }),
});

/** Seats a resident in the tower's chair, facing its window: the airport page then counts the tower as staffed. */
const SEAT = defineTask<Vec & { resident: string }, any>({
  name: "folkways.promo.airport.seat", side: "server",
  source: javaTask({
    imports: ["com.simibubi.create.content.contraptions.actors.seat.SeatBlock", "net.minecraft.core.BlockPos",
      "net.minecraft.server.MinecraftServer"],
    body: `
var a = Args.of(ctx);
var level = ((MinecraftServer) ctx.server()).overworld();
var seat = new BlockPos((int) a.integer("x"), (int) a.integer("y"), (int) a.integer("z") + 1);
var body = level.getEntity(UUID.fromString(a.string("resident")));
body.moveTo(seat.getX() + 0.5, seat.getY() + 0.5, seat.getZ() + 0.5, 180, 0);
SeatBlock.sitDown(level, seat, body);
return Map.of("seat", seat.toShortString(), "riding", body.isPassenger(), "block", level.getBlockState(seat).toString());`,
  }),
});

const MAP_SPOT = defineTask<{ x: number; z: number }, { x: number; y: number }>({
  name: "folkways.promo.airport.mapSpot", side: "client",
  source: javaTask({ imports: ["net.minecraft.client.Minecraft",
    "com.lowdragmc.lowdraglib2.gui.holder.ModularUIContainerMenu"], body: `
var args = Args.of(ctx);
var minecraft = (Minecraft) ctx.client();
var screen = (net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) minecraft.screen;
var map = ((ModularUIContainerMenu) screen.getMenu()).getModularUI()
    .getElementById("folkways.airline.airport.map");
java.util.function.Function<String, Float> field = name -> {
    try {
        var f = map.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.getFloat(map);
    } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
    }
};
float scale = field.apply("scale");
float x = field.apply("originX") + ((float) args.integer("x") + 0.5f - field.apply("minX")) * scale;
float y = field.apply("originY") + ((float) args.integer("z") + 0.5f - field.apply("minZ")) * scale;
return Map.of("x", (double) x, "y", (double) y);
` }),
});

const LANDING_ASSIGNMENT = defineTask<{ id: string }, any>({
  name: "folkways.promo.airport.landingAssignment", side: "server",
  source: javaTask({ imports: ["net.minecraft.server.MinecraftServer",
    "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity",
    "io.github.izakyl.folkways.core.combat.api.LandingSites", "net.minecraft.world.phys.AABB"], body: `
var level = ((MinecraftServer) ctx.server()).overworld();
var vehicle = (VehicleEntity) level.getEntity(UUID.fromString(Args.of(ctx).string("id")));
var type = Class.forName("io.github.izakyl.folkways.core.shell.warfare.DirectVehicleMotion");
var method = type.getDeclaredMethod("runway", VehicleEntity.class); method.setAccessible(true);
var rows = new ArrayList<Object>();
for (var site : LandingSites.in(level)) {
    if (site.kind() != LandingSites.Kind.RUNWAY_END) { continue; }
    var pose = site.pose(level).orElseThrow();
    var end = pose.position().add(pose.forward().scale(site.length()));
    var bounds = new AABB(pose.position(), end).inflate(site.width() / 2, vehicle.getBbHeight(), site.width() / 2);
    rows.add(Map.of("site", site.id(), "width", site.width(), "length", site.length(), "blockers",
        level.getEntitiesOfClass(VehicleEntity.class, bounds, other -> other != vehicle && !other.isWreck())
            .stream().map(other -> other.getType().toString() + " " + other.position()).toList()));
}
var needs = (io.github.izakyl.folkways.core.combat.api.Vehicles.RunwayNeeds)
    ((Optional<?>) method.invoke(null, vehicle)).orElseThrow();
return Map.of("needs", needs.toString(), "landing", needs.landing(), "sites", rows);` }),
});
