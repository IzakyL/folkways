import type { PromoPair } from "./promo-kit";
import { expect, test } from "@playwright/test";
import { defineTask, javaTask, screen, tick, world } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import {
  foundColonyFast, setVocationPriorityViaPanel, waitForGroundedPlayer, entityTypeMatches, type Vec,
} from "../e2e/colony-founding";
import { buildRailLine, conductorAboard, LOST_CARRIAGE, seatedAt, type RailResult } from "../e2e/rail-fixture";
import { MARK_MEMBERS, runColonyTask } from "../e2e/colony-tasks";
import { setMaintainDemandsFast } from "./fast-setup";
import {
  cinematicWorld, hideHud, launchPromoPair, promoClip, setTickRate, sleepMs, take, writeLook,
} from "./promo-kit";
import {
  buildSubwayStation, CUT_DEPTH, decoratePromoTrain, deliveryChests, fenceTheCut, holdOnPlatform, platformSpots,
  PROMO_TRAIN_BOUNDS, PROMO_TRAIN_SEATS, CHEST_SLOTS, supplyChests,
} from "./rail-scenery";
import { shaderEvidence } from "./shaders";
import { BUDGET } from "../shared/budgets";
import { commandPos, tryCommand, frames, safeAction } from "../shared/bw-helpers";

const RIDERS = 3;
const RESIDENTS = RIDERS + 1;
const RAIL_LINE = 200;
const TRACK_RATE = 20;
const EXIT_DRIFT = 40;
const ARRIVAL_CUE = 60;
const ALPHA_DWELL = 15;
// Now and then Create assembles the train and no carriage entity turns up (LOST_CARRIAGE); that one failure is
// staged again on a fresh pair, and every other is not.
const LINE_ATTEMPTS = 2;
// More goods than the riders can carry between them, none of which the colony can make or find anywhere but the
// platform chests: however the colony shares them out, it takes every rider with a full pack to move the first of them.
const GOODS = [
  "minecraft:pumpkin", "minecraft:melon", "minecraft:hay_block", "minecraft:bricks", "minecraft:white_wool",
  "minecraft:terracotta", "minecraft:carrot", "minecraft:potato", "minecraft:sugar_cane", "minecraft:apple",
  "minecraft:cactus", "minecraft:beetroot", "minecraft:sand", "minecraft:gravel", "minecraft:clay_ball",
  "minecraft:flint", "minecraft:feather", "minecraft:leather", "minecraft:string", "minecraft:bone",
  "minecraft:glowstone_dust", "minecraft:redstone", "minecraft:lapis_lazuli", "minecraft:slime_ball",
  "minecraft:prismarine_shard", "minecraft:nautilus_shell", "minecraft:amethyst_shard", "minecraft:honeycomb",
  "minecraft:ink_sac", "minecraft:glow_ink_sac", "minecraft:rabbit_hide", "minecraft:phantom_membrane",
  "minecraft:blaze_rod", "minecraft:prismarine_crystals", "minecraft:quartz", "minecraft:magma_cream",
].slice(0, RIDERS * 6);
const PACK_SLOTS = 12;
const STACK = 64;
const WANTED = 2 * STACK;

// The riders are set down on Alpha's platform while the train is away, and only then do the goods wanted at Beta
// turn up in the chest there: each picks up a load, waits for the train with the book showing where they are
// taking it, boards when it comes in, and rides off into the tunnel.
test("a crowd of residents boards the subway and rides off", async ({}, testInfo) => {
  test.setTimeout(BUDGET.promo);
  const run = take("rail");
  let pair: PromoPair | undefined;
  try {
    let staged!: Staged;
    const retried: RailResult[] = [];
    for (let attempt = 1; ; attempt++) {
      pair = await launchPromoPair(attempt === 1 ? "rail" : `rail-${attempt}`);
      staged = await stageLine(pair, run);
      if (staged.rail.ok || staged.rail.error !== LOST_CARRIAGE || attempt === LINE_ATTEMPTS) break;
      retried.push(staged.rail);
      await pair.teardown();
    }
    if (retried.length) run.note("retried", retried);
    const { server, client, clientInstance, playerName, origin, founding, conductorUuid, riderUuids, railOrigin, rail } = staged;
    run.note("rail", rail);
    expect(rail.ok, JSON.stringify(rail.steps)).toBe(true);
    const carriageUuid = rail.carriageUuid!;
    await world.command(server, `item replace entity ${playerName} weapon.mainhand from entity ${playerName} inventory.0`);
    run.note("station", await buildSubwayStation(server, railOrigin, rail.stationB));
    run.note("scenery", await plantTreeline(server, railOrigin));
    run.note("goods", await stockTheLine(server, playerName, founding.evidence.colonyId, railOrigin, rail.stationB));

    // Stage the driver on the accessible platform too. Closing the stairs while the
    // driver is still above ground can prevent the very first departure indefinitely.
    const driverPost = platformSpots(railOrigin, 1)[0];
    if (await seatedAt(server, carriageUuid, conductorUuid) === undefined)
      await world.command(server, `tp ${conductorUuid} ${driverPost.x + 0.5} ${driverPost.y} ${driverPost.z + 0.5}`);
    run.note("conductor_post", driverPost);

    await tick.freeze(server, false);
    const readyAt = Date.now();
    while (!(await conductorAboard(server, carriageUuid)) && Date.now() - readyAt < 120_000) await sleepMs(500);
    expect(await seatedAt(server, carriageUuid, conductorUuid), "the assigned conductor should be boarded by the colony").toBeDefined();
    run.note("held", await holdOnPlatform(server, railOrigin));

    // From above the far side of the platform, level with the middle of the car, looking straight across at its doors.
    // The colony's beds lie east of the station, behind the camera.
    const aim: Vec = { x: railOrigin.x + 1, y: railOrigin.y + 2, z: railOrigin.z + 7 };
    const eye: Vec = { x: railOrigin.x + 11, y: origin.y + 6, z: railOrigin.z + 8 };
    const look = cameraLookingAt(eye, aim);
    // The colony's own book stays in hand: holding it is what floats each resident's card over their head.
    await world.command(server, `execute if items entity ${playerName} weapon.mainhand folkways:colony_book`);
    await world.command(server, `gamemode spectator ${playerName}`);
    await world.command(server, `tp ${playerName} ${eye.x} ${eye.y - 1.62} ${eye.z} ${look.yaw} ${look.pitch}`);
    await safeAction(() => screen.dismiss(client));
    await hideHud(client);
    await writeLook(client, look);
    await safeAction(() => frames(client, 20));
    await setTickRate(server, TRACK_RATE);

    const cues: any = {};
    // Out past the platform's nose: a train settling as it pulls in also rolls a little that way.
    const pullingOut = (at: Vec, last: Vec) => at.z < last.z - 0.2 && at.z < railOrigin.z;
    // The timetable only knows a run once the train has driven it, so it laps the line once before anyone is sent
    // down: from then on a rider on Alpha's platform can see the next train to Beta.
    cues.warmup = await awaitTrain(server, carriageUuid, pullingOut);
    cues.lapped = await awaitTrain(server, carriageUuid, (at, last) => at.z > last.z && railOrigin.z - at.z < 10);
    cues.leaving = await awaitTrain(server, carriageUuid, pullingOut);
    // No rations for the length of the shot: a resident who eats into theirs has it made up from someone's pack or
    // the pantry, and on a platform with its stairs barred every such errand runs the long way round through Beta.
    run.note("rations", await runColonyTask(server, NO_RATIONS, { colony_id: founding.evidence.colonyId }));
    const spots = platformSpots(railOrigin, RIDERS);
    for (let i = 0; i < RIDERS; i++) {
      await world.command(server, `tp ${riderUuids[i]} ${spots[i].x + 0.5} ${spots[i].y} ${spots[i].z + 0.5}`);
    }
    run.note("platform", spots);
    const away: any[] = [];
    const watching = (async () => {
      while (!cues.homing) {
        away.push(await runColonyTask(server, RIDERS_NOW, { riders: [...riderUuids, conductorUuid] }).catch((e: any) => String(e)));
        await sleepMs(1_000);
      }
    })();
    const stacks = GOODS.flatMap((item) => Array.from({ length: WANTED / STACK }, () => item));
    const supply = supplyChests(railOrigin, stacks.length);
    for (const [i, item] of stacks.entries()) {
      const chest = supply[Math.floor(i / CHEST_SLOTS)];
      await world.command(server, `item replace block ${commandPos(chest)} container.${i % CHEST_SLOTS} with ${item} ${STACK}`);
    }
    cues.homing = await awaitTrain(server, carriageUuid, (at, last) => at.z > last.z && railOrigin.z - at.z < ARRIVAL_CUE);
    await watching;
    run.note("cues", cues);
    run.note("away", away);

    const trail: any[] = [];
    let allAboard = false;
    let exited = false;
    let departure: Vec | null = null;
    const gates = await promoClip(run, client, server, "01-rail", {
      subject: "Residents carrying goods wait on the subway platform, each card saying what they will do at the far end, the train pulls in, they take their seats and it pulls away into the tunnel",
      worldState: { camera: { eye, aim, ...look }, conductorUuid, riderUuids, carriage: carriageUuid },
      seconds: 8,
      during: async () => {
        // Standing at Alpha: the car is reckoned from the bogey under the station, which on a turnback line stands
        // past the car's far end rather than near the rail origin.
        await awaitTrain(server, carriageUuid, (at, last) => Math.abs(at.z - rail.stationA.z) < 10 && Math.abs(at.z - last.z) < 0.01);
        const start = Date.now();
        while (Date.now() - start < 60_000) {
          const at = await carriagePos(server, carriageUuid);
          const conductorSeat = await seatedAt(server, carriageUuid, conductorUuid);
          const passengerSeats = await Promise.all(riderUuids.map((rider) => seatedAt(server, carriageUuid, rider)));
          const together = conductorSeat !== undefined && passengerSeats.every((seat) => seat !== undefined);
          if (together && !allAboard) departure = at;
          allAboard ||= together;
          const drift = at && departure ? Math.hypot(at.x - departure.x, at.z - departure.z) : 0;
          const riders = !allAboard && trail.length % 4 === 0
            ? await runColonyTask(server, RIDERS_NOW, { riders: riderUuids }).catch((e: any) => String(e)) : undefined;
          trail.push({ second: (Date.now() - start) / 1000, conductorSeat, passengerSeats, at, drift, riders });
          if (allAboard && drift >= EXIT_DRIFT) {
            expect(together, "after departure every rider must still be on the same car").toBe(true);
            exited = true;
            break;
          }
          await sleepMs(250);
        }
        run.note("journey", { trail, allAboard, exited });
        expect(allAboard, "every rider and the conductor should be seated together").toBe(true);
        expect(exited, "the train carrying the riders must leave the platform").toBe(true);
        await sleepMs(1_500);
      },
    });
    run.note("shot", { ...gates, allAboard, exited });
    run.note("shaders", shaderEvidence(clientInstance));
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

type Staged = Awaited<ReturnType<typeof stageLine>>;

// Founds the colony, sets the crew to their posts and builds the line with its train, on a pair just launched.
async function stageLine(pair: PromoPair, run: ReturnType<typeof take>) {
  const { server, client, clientInstance } = pair;
  const grounded = await waitForGroundedPlayer(server);
  const playerName = grounded.name;
  await world.command(server, `op ${playerName}`);
  await cinematicWorld(server);
  const origin: Vec = {
    x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4,
  };
  const founding = await foundColonyFast(server, client, playerName, { origin, residentCount: RESIDENTS });
  run.note("colony", { origin, residents: founding.residents, colonyId: founding.evidence.colonyId });
  const crew = await residents(server);
  expect(crew).toHaveLength(RESIDENTS);
  expect(GOODS.length * WANTED / STACK, "more stacks than every rider's pack holds").toBeGreaterThanOrEqual(RIDERS * PACK_SLOTS);
  const conductorUuid = crew[0].uuid;
  const riderUuids = crew.slice(1).map((one) => one.uuid);
  const roles: any[] = [];
  for (const rider of riderUuids) {
    roles.push(await setVocationPriorityViaPanel(server, client, playerName, founding.standBlock, rider, "folkways:conducting", 0));
  }
  run.note("crew", { conductorUuid, riderUuids, roles });
  await world.command(server, `item replace entity ${playerName} inventory.0 from entity ${playerName} weapon.mainhand`);
  const railOrigin: Vec = { x: origin.x - 14, y: origin.y - CUT_DEPTH, z: origin.z + 8 };
  await world.command(server, `fill ${origin.x - 20} ${origin.y} ${origin.z - 20} ${origin.x + 20} ${origin.y + 3} ${origin.z + 20} minecraft:air replace minecraft:smooth_stone`);
  await fenceTheCut(server, railOrigin, RAIL_LINE);
  const rail = await buildRailLine(server, client, playerName, await playerUuid(server, playerName), railOrigin,
    RAIL_LINE, "inventory.0", {
      decorate: decoratePromoTrain, decorateBounds: PROMO_TRAIN_BOUNDS, cyclic: true, passengerSeats: PROMO_TRAIN_SEATS, dwell: { Alpha: ALPHA_DWELL }, clickThrough: true,
    });
  return { server, client, clientInstance, playerName, origin, founding, conductorUuid, riderUuids, railOrigin, rail };
}

const RIDERS_NOW = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.rail-riders",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["io.github.izakyl.folkways.core.api.resident.body.Bodies"],
    body: `
var level=ctx.server().overworld();var args=Args.of(ctx);var riders=new ArrayList<Object>();
for(var id:args.list("riders")){
  var entity=level.getEntity(UUID.fromString(String.valueOf(id)));if(entity==null){riders.add("gone "+id);continue;}
  var body=Bodies.of(entity).orElseThrow();
  var feet=entity.position();
  riders.add(Map.of("at",String.format("%.2f, %.2f, %.2f",feet.x,feet.y,feet.z),"doing",body.doing().map(Object::toString).orElse("idle"),
    "pack",body.pack().contents().stream().map(Object::toString).toList()));
}
return Sync.stamp(Map.of("riders",riders));
`,
  }),
});

const NO_RATIONS = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.rail-no-rations",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.front.engine.colony.ColonyFront",
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.front.engine.colony.ColonySettings.Value",
      "io.github.izakyl.folkways.plugins.person.living.LivingContent",
    ],
    body: `
var args=Args.of(ctx);
var colony=ColonyGround.of(ctx.server(),UUID.fromString(args.string("colony_id"))).orElseThrow();
var front=ColonyFront.of(colony);
if(!front.setSetting(LivingContent.ID,LivingContent.FOOD.key(),new Value.Items(List.of())))
  throw new TaskException("refused","the colony would not take an empty food list");
return Sync.stamp(Map.of("ok",true,"food",front.settings(LivingContent.ID).items(LivingContent.FOOD.key()).isPresent()));
`,
  }),
});

async function residents(server: any) {
  const listed = (await world.entities(server, { dimension: "minecraft:overworld", limit: 1000 })).entities;
  return listed
    .filter((e: any) => entityTypeMatches(e.type, "folkways:resident"))
    .map((e: any) => ({ uuid: e.uuid, position: e.position }));
}

async function playerUuid(server: any, playerName: string): Promise<string> {
  const found = (await world.players(server)).find((p) => p.name === playerName)?.uuid;
  if (!found) throw new Error("Could not read the player uuid, so the assembly step cannot be attributed");
  return found;
}

async function carriagePos(server: any, carriageUuid: string): Promise<Vec | null> {
  const listed = await world.entities(server, { typePatterns: ["*carriage_contraption*"] })
    .catch(() => ({ entities: [] as world.EntityJson[] }));
  return listed.entities.find((e) => e.uuid === carriageUuid)?.position ?? null;
}

async function awaitTrain(server: any, carriageUuid: string, reached: (at: Vec, last: Vec) => boolean) {
  const started = Date.now();
  let last: Vec | null = null;
  while (Date.now() - started < 300_000) {
    const at = await carriagePos(server, carriageUuid);
    if (at && last && reached(at, last)) return { at, seconds: (Date.now() - started) / 1000 };
    last = at ?? last;
    await sleepMs(250);
  }
  throw new Error(`the train never reached its cue within five minutes; last seen at ${JSON.stringify(last)}`);
}

async function plantTreeline(server: any, railOrigin: Vec) {
  const trees = ["minecraft:oak", "minecraft:birch", "minecraft:spruce", "minecraft:fancy_oak"];
  const ground = railOrigin.y + CUT_DEPTH;
  const spots: Vec[] = [];
  for (let i = 0; i < 6; i++) {
    spots.push({ x: railOrigin.x - 9 - (i % 2) * 6, y: ground, z: railOrigin.z - 1 + i * 6 });
  }
  for (let i = 0; i < 4; i++) {
    spots.push({ x: railOrigin.x + 14 + (i % 2) * 6, y: ground, z: railOrigin.z + 28 + i * 5 });
  }
  const planted: any[] = [];
  for (let i = 0; i < spots.length; i++) {
    const feature = trees[i % trees.length];
    const result = await tryCommand(server, `place feature ${feature} ${commandPos(spots[i])}`);
    const ok = Boolean(result?.success);
    if (!ok) {
      await tryCommand(server, `setblock ${spots[i].x} ${spots[i].y - 1} ${spots[i].z} minecraft:grass_block`);
    }
    planted.push({ at: spots[i], feature, ok });
  }
  return { planted: planted.filter((one) => one.ok).length, of: planted.length, trees: planted };
}

// Chests of goods on Alpha's platform, empty until the riders are down there, and a chest at Beta for each kind,
// kept stocked by a standing order: the only way to fill them is to carry the goods down the line.
async function stockTheLine(server: any, playerName: string, colonyId: string, railOrigin: Vec, stationB: Vec) {
  const supply = supplyChests(railOrigin, GOODS.length * WANTED / STACK);
  const wanted = deliveryChests(railOrigin, stationB, GOODS.length);
  for (const chest of [...supply, ...wanted]) {
    await world.command(server, `setblock ${commandPos(chest)} minecraft:chest[facing=west]`);
  }
  const members = await runColonyTask(server, MARK_MEMBERS, {
    player: playerName, colony_id: colonyId, cells: [...supply, ...wanted].flatMap((cell) => [cell.x, cell.y, cell.z]),
  });
  const demands = await setMaintainDemandsFast(server, playerName,
    wanted.map((at, i) => ({ at, item: GOODS[i], count: WANTED, low: WANTED })));
  return { supply, wanted, members, demands };
}
