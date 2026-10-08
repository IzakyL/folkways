import type { PromoPair } from "./promo-kit";
import { test } from "@playwright/test";
import { screen, tick, world } from "@izakyl/blockwright-minecraft";
import { waitForGroundedPlayer, type Vec } from "../e2e/colony-founding";
import { countInResidentPacks } from "../e2e/world-ui";
import {
  armWork,
  castGolems,
  castPatrol,
  census,
  residentPositions,
  crewTotal,
  holdWorkers,
  releaseSight,
  stageSettlement,
  TRACK_SPEED,
  type Held,
  type SiteLayout,
  type Sight,
} from "./scene";
import {
  cinematicWorld,
  hideHud,
  launchPromoPair,
  promoClip,
  setKey,
  setTickRate,
  setWalkSpeed,
  sleepMs,
  take,
  writeLook,
} from "./promo-kit";
import { shaderEvidence } from "./shaders";
import { varyVitals } from "./vitals";
import { BUDGET } from "../shared/budgets";
import { tryCommand, frames, safeAction } from "../shared/bw-helpers";
import { runColonyTask } from "../e2e/colony-tasks";
import { GOLEM_WORK } from "../shared/integration-tasks";

const RESIDENTS = crewTotal() + 5;
const CLIP_SECONDS = 15;
const TRACK_OVERRUN_S = 1.5;
const POLL_MS = 120;
const STOP_LEAD = 0.8;
const END_HOLD = 4;
const CENSUS_AHEAD = 18;
const WARM_TICKS = 200;
const WARM_LAST = "farm";
const WARM_LAST_TICKS = 160;
// Short wards, laid close together, so a patroller sets out again as soon as it is back and keeps walking on camera.
const PATROL_ZONES = { patrolWardRadius: 6, patrolWardTicks: 200 };

test("lateral tour of a working colony", async ({}, testInfo) => {
  test.setTimeout(BUDGET.promo);
  const run = take("tour");
  let pair: PromoPair | undefined;
  let server: any;
  let client: any;
  let clientInstance = "";
  try {
    pair = await launchPromoPair("tour", { zones: PATROL_ZONES });
    ({ server, client, clientInstance } = pair);

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    await tryCommand(server, `op ${playerName}`);
    await cinematicWorld(server);
    await tryCommand(server, "gamerule fallDamage false");
    await tryCommand(server, `effect give ${playerName} minecraft:fire_resistance infinite 0 true`);
    await world.command(server, `gamemode survival ${playerName}`);
    const walk = await setWalkSpeed(server, playerName, TRACK_SPEED);

    const scene = await stageSettlement(server, client, playerName, {
      residents: RESIDENTS,
      note: (key, value) => run.note(key, value),
    });
    const layout = scene.layout;
    const track = layout.track;
    run.note("scene", {
      origin: layout.origin,
      residents: scene.residents,
      biome: scene.evidence.biome?.biome ?? null,
      planted: scene.evidence.planted,
      demands: scene.evidence.demands,
      track: { start: track.start, stop: track.stop, yaw: track.yaw, pitch: track.pitch, ...walk },
      sights: track.sights.map((sight) => ({ name: sight.name, aim: sight.aim })),
    });

    run.note("armed", await armWork(server, layout));

    await world.command(server, `tp ${playerName} ${track.start.x + 0.5} ${track.start.y} ${track.start.z + 0.5} ${track.yaw} ${track.pitch}`);
    await tick.sprint(server, 20);
    const staffed = await holdWorkers(server, layout, scene.evidence.outfit.handed);
    const held = staffed.held;
    run.note("held", staffed);
    run.note("vitals", await varyVitals(server, scene.colonyId));
    const golems = await castGolems(server, layout, playerName, scene.colonyId);
    run.note("golems", golems);
    const patrol = await castPatrol(server, layout, playerName, scene.colonyId);
    run.note("patrol", patrol);

    // Everyone is at work before the camera rolls: the casts and patrol laps are only offered on a colony sweep, so
    // a crew let go as the camera reaches it stands idle on screen. The farm goes last, as the shot opens on it, so
    // a tree is still coming down there when filming starts.
    const report: any = { passes: [], released: [] };
    for (const sight of track.sights.filter((one) => one.name !== WARM_LAST)) {
      report.released.push(await releaseSight(server, layout, sight, held));
    }
    await tick.sprint(server, WARM_TICKS);
    for (const sight of track.sights.filter((one) => one.name === WARM_LAST)) {
      report.released.push(await releaseSight(server, layout, sight, held));
    }
    await tick.sprint(server, WARM_LAST_TICKS);
    report.warmed = await probed("warmed", () => runColonyTask(server, GOLEM_WORK, {
      uuids: [...held.map((one) => one.uuid), ...patrol.uuids],
      target: layout.camp.demands[1].at,
    }));

    await safeAction(() => frames(client, 10));
    await safeAction(() => screen.dismiss(client));
    run.note("hud", await hideHud(client));
    await writeLook(client, { yaw: track.yaw, pitch: track.pitch });
    await sleepMs(6_000);

    const gates = await promoClip(run, client, server, "01-colony", {
      subject: "Long tracking shot: camera locked facing north, panning west to east along the elevated walkway past farms, pastures, fishery and workshops where golems craft beside the residents, while patrol golems walk the near edge of each",
      worldState: {
        start: track.start,
        stop: track.stop,
        speed: track.speed,
        residents: scene.residents,
        world_rate: track.rate,
        sights: track.sights.map((sight) => sight.name),
      },
      seconds: CLIP_SECONDS,
      during: () => rollTrack(client, server, playerName, layout, held, report),
    });
    await setTickRate(server, 20);

    run.note("tour", {
      ...gates,
      ...report,
      residents: await probed("residents", () => residentPositions(server)),
      on_fire: await probed("on_fire", () => Promise.all(
        layout.camp.fires.map((fire) => tryCommand(server, `data get block ${fire.x} ${fire.y} ${fire.z} Items`)),
      )),
      felled: await probed("felled", () => loggedByGroveCrew(server, scene.evidence.outfit)),
      golems: await probed("golems", () => runColonyTask(server, GOLEM_WORK, {
        uuids: golems.uuids,
        target: layout.camp.demands[1].at,
      })),
      patrol: await probed("patrol", () => runColonyTask(server, GOLEM_WORK, {
        uuids: patrol.uuids,
        target: layout.camp.demands[1].at,
      })),
    });
    run.note("shaders", shaderEvidence(clientInstance));
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

async function rollTrack(
  client: any,
  server: any,
  playerName: string,
  layout: SiteLayout,
  held: Held[],
  report: any,
) {
  const track = layout.track;
  const pending: Array<Promise<unknown>> = [];
  const counted = new Set<string>();
  const releaseAt = track.stop.x - STOP_LEAD;
  const budgetS = (releaseAt - track.start.x) / track.speed + TRACK_OVERRUN_S;
  let here = (await playerPosition(server, playerName)) ?? track.start;
  let blindReads = 0;
  await setTickRate(server, track.rate);
  await setKey(client, "key.right", true);

  const started = Date.now();
  let polls = 0;
  try {
    while (here.x < releaseAt && (Date.now() - started) / 1000 < budgetS) {
      for (const sight of track.sights) {
        if (counted.has(sight.name) || sight.aim.x - here.x > CENSUS_AHEAD) continue;
        counted.add(sight.name);
        pending.push(countWorkers(server, sight, held, here.x, report.passes));
      }
      const left = releaseAt - here.x;
      await sleepMs(Math.max(20, Math.min(POLL_MS, (left / track.speed) * 1000)));
      const read = await playerPosition(server, playerName);
      if (read) {
        here = read;
      } else {
        blindReads++;
        here = { ...here, x: here.x + (track.speed * POLL_MS) / 1000 };
      }
      polls++;
    }
  } finally {
    await setKey(client, "key.right", false);
  }
  const rolled = (Date.now() - started) / 1000;
  await sleepMs(END_HOLD * 1000);

  const end = (await playerPosition(server, playerName)) ?? here;
  await Promise.all(pending);
  report.roll = {
    rolled_s: round(rolled),
    polls,
    blind_reads: blindReads,
    overrun: round(end.x - track.stop.x),
    measured_speed: round((end.x - track.start.x) / Math.max(0.001, rolled)),
    end,
    drift_z: round(end.z - track.stop.z),
  };
}

async function countWorkers(server: any, sight: Sight, held: Held[], atX: number, passes: any[]) {
  const at_x = round(atX);
  const staged = held.filter((one) => one.sight === sight.name).length;
  try {
    const standing = await residentPositions(server);
    passes.push({ sight: sight.name, at_x, staged, standing: census(standing, [sight])[sight.name] });
  } catch (error) {
    passes.push({ sight: sight.name, at_x, staged, error: String(error) });
  }
}

function round(value: number) {
  return Math.round(value * 100) / 100;
}

async function probed<T>(name: string, read: () => Promise<T>): Promise<T | { probe: string; error: string }> {
  try {
    return await read();
  } catch (error) {
    return { probe: name, error: String(error) };
  }
}

async function loggedByGroveCrew(server: any, outfit: any) {
  const crew = new Set<string>(
    (outfit?.handed ?? [])
      .filter((one: any) => one.trade === "grove")
      .map((one: any) => String(one.uuid)),
  );
  const packs = await countInResidentPacks(server);
  let logs = 0;
  let readable = 0;
  for (const one of packs.per_resident) {
    if (!crew.has(one.uuid)) continue;
    readable++;
    logs += one.counts["minecraft:oak_log"] ?? 0;
  }
  return { crew: crew.size, read: readable, oak_logs: logs };
}

async function playerPosition(server: any, playerName: string): Promise<Vec | null> {
  const result = await safeAction(() => world.players(server)).catch(() => null);
  const players: world.Player[] = Array.isArray(result) ? result : [];
  const mine = players.find((one) => one?.name === playerName) ?? players[0];
  return mine?.position ?? null;
}
