import { expect, test } from "@playwright/test";
import { errorSummary } from "@izakyl/blockwright-client";
import { reflect, screen, tick, world, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { clickElement } from "../ldlib2";
import {
  commandPos,
  foundColonyFast,
  openColonyPanelViaBook,
  stockToolsForResidents,
  waitForGroundedPlayer,
  type Vec,
} from "./colony-founding";
import { aimAndWorldLeftClick, mustCommand, packHasItem } from "./world-ui";
import { drainActionBar } from "./zone-receipt";
import { colonyFront, colonyRegistry, markCorner, setBookGesture } from "./zone-tools";
import { settlePanel } from "./site-panel";
import { waitForElement, safeAction } from "../shared/bw-helpers";
import { launchPair, type E2EPair } from "../shared/pair";
import { ensureOut, tracePath } from "../out-paths";
import { seededServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { newRunId } from "../shared/run-id";

const CONFIG_PATH = "blockwright.toml";
const RESIDENT_COUNT = 2;

// A short ward, so a patroller has to walk the route again within the test or the wards on it lapse.
const WARD_TICKS = 400;
const WARD_RADIUS = 8;

const PATROL = "folkways:patrol";
const PATROL_KIND = "folkways.zoneconfig.kind.folkways.patrol";
const WEAPON = "minecraft:stone_sword";

const WARDS = "io.github.izakyl.folkways.plugins.patrol.Wards";

test("patrol: player draws a route with the book, a patroller wards it lap after lap and strikes a monster on it", async ({}, testInfo) => {
  test.setTimeout(BUDGET.standard);
  ensureOut("e2e");
  const runId = newRunId();

  let pair: E2EPair | undefined;
  let server: any;
  let client: any;
  let founding: any;

  try {
    pair = await launchPair({
      config: CONFIG_PATH,
      server: {
        trace: tracePath("e2e", "patrol-server"),
        instance: seededServer(`patrol-server-${runId}`, { patrolWardTicks: WARD_TICKS, patrolWardRadius: WARD_RADIUS }),
      },
      client: { trace: tracePath("e2e", "patrol-client"), instance: `patrol-client-${runId}` },
      snapshot: () => ({ containers: [founding?.foodChest], entityTypes: ["folkways:resident", "minecraft:husk"] }),
    });
    ({ server, client } = pair);

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    await mustCommand(server, `gamemode survival ${playerName}`);

    const origin: Vec = {
      x: Math.round(grounded.pos.x) + 4,
      y: Math.round(grounded.pos.y),
      z: Math.round(grounded.pos.z) + 4,
    };
    founding = await foundColonyFast(server, client, playerName, { origin, residentCount: RESIDENT_COUNT });
    expect(founding.residents, `residents=${founding.residents} (expected ${RESIDENT_COUNT})`)
      .toBeGreaterThanOrEqual(RESIDENT_COUNT);
    await stockToolsForResidents(server, founding.foodChest, [WEAPON], RESIDENT_COUNT, 2);

    // Two clicked points on the ground, 24 blocks apart along x: the route gets a stop at each end and
    // one between, and with an 8-block ward the ends are warded only by a patroller standing there.
    const route: Vec[] = [
      { x: origin.x - 12, y: origin.y - 1, z: origin.z + 10 },
      { x: origin.x + 12, y: origin.y - 1, z: origin.z + 10 },
    ];
    const ends = route.map((point) => ({ x: point.x, y: point.y + 1, z: point.z }));
    const offRoute: Vec = { x: origin.x - 12, y: origin.y, z: origin.z - 10 };

    const drawn = await drawPatrolRoute(server, client, playerName, founding.standBlock, route);
    expect(drawn.error, `drawing the route threw: ${JSON.stringify(drawn.error)}`).toBeUndefined();

    const registry = await colonyRegistry(server);
    expect(await pathCount(server, registry), "confirming the route on the book page should leave exactly one path")
      .toBe(1);
    expect(await pathKindAt(server, registry, 0), "the path built must be the patrol route picked on the page")
      .toBe(PATROL);

    const watch = await watchRoute(server, playerName, ends, offRoute);
    const reasons: string[] = [];
    if (!watch.armed) {
      reasons.push(`no resident ever took up ${WEAPON} from the food chest: a watch needs a melee weapon, so no lap could start`);
    }
    for (const [index, at] of watch.firstWarded.entries()) {
      if (at === undefined) {
        reasons.push(`the route's end ${commandPos(ends[index])} was never warded: no patroller stood watch there`);
      }
    }
    if (watch.armed && watch.firstWarded.every((at) => at !== undefined) && !watch.heldAcrossLaps) {
      reasons.push(`both ends were warded once, but within ${WATCH_SAMPLES * SAMPLE_TICKS} ticks one was never `
        + `warded again after its ${WARD_TICKS}-tick ward ran out: no later lap came back to it`);
    }
    if (watch.offRouteWarded) {
      reasons.push(`${commandPos(offRoute)} is ${20} blocks off the route, past the ${WARD_RADIUS}-block ward, yet read as warded`);
    }
    expect(reasons, reasons.join(" | ")).toEqual([]);

    // A monster standing still near the far end of the route: the patroller, on duty from its last watch, goes for it.
    const foeAt = { x: ends[1].x - 3, y: ends[1].y, z: ends[1].z - 2 };
    await mustCommand(server,
      `summon minecraft:husk ${foeAt.x + 0.5} ${foeAt.y} ${foeAt.z + 0.5} {NoAI:1b,PersistenceRequired:1b,Health:6f}`);
    const struck = await waitForFoeGone(server);
    expect(struck.gone, `the husk at ${commandPos(foeAt)} was still standing after ${struck.ticks} ticks: `
      + `no patroller on duty struck it down`).toBe(true);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

async function drawPatrolRoute(
  server: MinecraftServer, client: MinecraftClient, playerName: string, standBlock: Vec, route: Vec[],
) {
  const evidence: any = { route, points: [] };
  try {
    evidence.mode = await setBookGesture(server, client, playerName, "line");
    await drainActionBar(client);
    for (const [index, point] of route.entries()) {
      evidence.points.push(await markCorner(server, client, point, new RegExp(`^Point ${index + 1} set down`),
        () => aimAndWorldLeftClick(server, client, playerName, point)));
    }
    evidence.open = await openColonyPanelViaBook(server, client, playerName, standBlock);
    await settlePanel(server, client);
    await waitForElement(client, { id: PATROL_KIND }, { timeoutMs: 8_000 });
    evidence.pick = await clickElement(client, { id: PATROL_KIND });
    await settlePanel(server, client);
    await waitForElement(client, { id: "folkways.zoneconfig.confirm" }, { timeoutMs: 8_000 });
    evidence.confirm = await clickElement(client, { id: "folkways.zoneconfig.confirm" });
    await tick.sprint(server, 10);
  } catch (error) {
    evidence.error = errorSummary(error);
  } finally {
    await safeAction(() => screen.dismiss(client));
    await tick.sprint(server, 5);
  }
  return evidence;
}

const WATCH_SAMPLES = 120;
const SAMPLE_TICKS = 20;

// A lap walks out and the next walks back, so the end a lap reached first is the last the next one
// reaches, and its ward may lapse for a moment in between. What must hold is that every end is warded
// again after its first ward ran out: the route is walked lap after lap.
async function watchRoute(server: MinecraftServer, playerName: string, ends: Vec[], offRoute: Vec) {
  const firstWarded: (number | undefined)[] = ends.map(() => undefined);
  const renewed = ends.map(() => false);
  let armed = false;
  let offRouteWarded = false;
  const samples: any[] = [];
  for (let sample = 0; sample < WATCH_SAMPLES && !renewed.every(Boolean); sample++) {
    await tick.sprint(server, SAMPLE_TICKS);
    const now = (sample + 1) * SAMPLE_TICKS;
    armed ||= await packHasItem(server, WEAPON) || await anyResidentHolds(server, WEAPON);
    const warded = [];
    for (const end of ends) warded.push(await isWarded(server, playerName, end));
    warded.forEach((on, index) => {
      const first = firstWarded[index];
      if (on && first === undefined) firstWarded[index] = now;
      if (on && first !== undefined && now > first + WARD_TICKS) renewed[index] = true;
    });
    offRouteWarded ||= await isWarded(server, playerName, offRoute);
    samples.push({ ticks: now, warded });
  }
  return { armed, firstWarded, heldAcrossLaps: renewed.every(Boolean), offRouteWarded, samples };
}

async function waitForFoeGone(server: MinecraftServer) {
  let ticks = 0;
  for (let sample = 0; sample < 60; sample++) {
    await tick.sprint(server, SAMPLE_TICKS);
    ticks += SAMPLE_TICKS;
    if (!(await world.probe(server, "if entity @e[type=minecraft:husk]"))) return { gone: true, ticks };
  }
  return { gone: false, ticks };
}

async function anyResidentHolds(server: MinecraftServer, itemId: string) {
  return world.probe(server, `as @e[type=folkways:resident] if data entity @s HandItems[{id:"${itemId}"}]`);
}

async function isWarded(server: MinecraftServer, playerName: string, at: Vec): Promise<boolean> {
  const players = await world.players(server);
  const uuid = players.find((one: any) => one.name === playerName)?.uuid ?? players[0]?.uuid;
  const level = await reflect.invoke(server, {
    target: { kind: "entity", uuid },
    method: "serverLevel",
    returnHandle: true,
    limits: { maxDepth: 1, maxFields: 1, maxNodes: 8 },
  });
  const pos = await reflect.invoke(server, {
    target: { kind: "static", className: "net.minecraft.core.BlockPos" },
    method: "containing",
    args: [at.x, at.y, at.z],
    argTypes: ["double", "double", "double"],
    returnHandle: true,
    limits: { maxDepth: 1, maxFields: 1, maxNodes: 8 },
  });
  if (typeof level?.handle !== "string" || typeof pos?.handle !== "string") {
    throw new Error(`isWarded: no level or BlockPos handle (${JSON.stringify({ level, pos }).slice(0, 300)})`);
  }
  return reflect.boolean(await reflect.invoke(server, {
    target: { kind: "static", className: WARDS },
    method: "warded",
    args: [{ $handle: level.handle }, { $handle: pos.handle }],
    argTypes: ["net.minecraft.server.level.ServerLevel", "net.minecraft.core.BlockPos"],
    returnType: "boolean",
  }));
}

async function pathCount(server: MinecraftServer, registry: string): Promise<number> {
  const result = await reflect.invoke(server, {
    target: { kind: "handle", handle: await colonyFront(server, registry) },
    method: "paths",
    limits: { maxDepth: 1, maxItems: 64, maxNodes: 256 },
  });
  const tree: any = result.returned;
  if (!Array.isArray(tree)) {
    throw new Error(`ColonyFront.paths() did not return a list: ${JSON.stringify(tree).slice(0, 300)}`);
  }
  return tree.length;
}

async function pathKindAt(server: MinecraftServer, registry: string, index: number): Promise<string> {
  const result = await reflect.invoke(server, {
    target: { kind: "handle", handle: await colonyFront(server, registry) },
    path: `paths()[${index}].delegation()`,
    method: "toString",
    returnType: "java.lang.String",
  });
  return reflect.string(result);
}
