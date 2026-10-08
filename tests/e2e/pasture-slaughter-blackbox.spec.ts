import { expect, test } from "@playwright/test";
import { tick, world } from "@izakyl/blockwright-minecraft";
import {
  foundColonyFast,
  waitForGroundedPlayer,
  type Vec,
} from "./colony-founding";
import { colonyHasItem, mustCommand, tryCommand } from "./world-ui";
import {
  buildPen,
  countAnimalsInPen,
  createPastureZoneViaPanel,
  pastureZoneFailure,
  penLayout,
} from "./pasture-tools";
import { launchPair, type E2EPair } from "../shared/pair";
import { ensureOut, tracePath } from "../out-paths";
import { seededServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { newRunId } from "../shared/run-id";

const CONFIG_PATH = "blockwright.toml";
const RESIDENT_COUNT = 3;

const TARGET = 2;
const STOCKED = 3;
const BEEF = "minecraft:beef";
const LEATHER = "minecraft:leather";

const SLAUGHTER_TICKS = 3_000;
const BURST = 20;

test("pasture slaughter end-to-end: an over-stocked pasture is culled down to its target and the drops are gathered up", async ({}, testInfo) => {
  test.setTimeout(BUDGET.slow);
  ensureOut("e2e");
  const runId = newRunId();

  const serverTrace = tracePath("e2e", "pasture-slaughter-server");
  const clientTrace = tracePath("e2e", "pasture-slaughter-client");

  let pair: E2EPair | undefined;
  let server: any;
  let client: any;

  try {
    const clientInstance = `pasture-slaughter-client-${runId}`;
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: serverTrace, instance: seededServer(`pasture-slaughter-server-${runId}`) },
      client: { trace: clientTrace, instance: clientInstance },
      snapshot: () => ({ entityTypes: ["folkways:resident", "minecraft:cow", "minecraft:item"] }),
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
    const founding = await foundColonyFast(server, client, playerName, { origin, residentCount: RESIDENT_COUNT });
    expect(founding.residents, `residents=${founding.residents} (expected ${RESIDENT_COUNT})`).toBeGreaterThanOrEqual(RESIDENT_COUNT);

    const pen = penLayout(origin, 8, 3);
    await buildPen(server, pen);

    const zone = await createPastureZoneViaPanel(server, client, playerName, founding.standBlock, pen, {
      animal: "cow",
      target: TARGET,
    });
    expect(zone.created, pastureZoneFailure(zone, "cow", TARGET)).toBe(true);

    const spots = [
      { x: pen.min.x + 0.5, z: pen.min.z + 0.5 },
      { x: pen.max.x + 0.5, z: pen.min.z + 0.5 },
      { x: pen.min.x + 0.5, z: pen.max.z + 0.5 },
    ];
    for (const spot of spots) {
      await mustCommand(server, `summon minecraft:cow ${spot.x} ${origin.y} ${spot.z} {Age:0,PersistenceRequired:1b}`);
    }
    await tryCommand(server, `tp ${playerName} ${founding.standBlock.x + 0.5} ${founding.standBlock.y} ${founding.standBlock.z + 1.5} 180 30`);
    await tick.sprint(server, 20);
    const stockedCows = await countCowsInWorld(server);
    expect(stockedCows, `expected ${STOCKED} cows in the world, saw ${stockedCows}`).toBe(STOCKED);

    const culling = await observeCulling(server, pen, [founding.foodChest]);

    expect(
      culling.escaped_from_pen,
      `a cow left the pen without dying (${culling.final_cows} in the pen but ${culling.final_cows_in_world} alive in the world):`
        + ` vanilla entity-push squeezed it through the fence — a test-fixture problem (pen too cramped), not a product bug,`
        + ` but it invalidates the population observation, so this run is reported red rather than guessed at.`,
    ).toBe(false);
    expect(
      culling.min_cows,
      `the surplus cow was never slaughtered: cow count in the pen never dropped below ${culling.min_cows}`
        + ` (expected it to reach ${TARGET}). The pen never opened a CullNode for the surplus cow, or it was never dispatched.`,
    ).toBeLessThanOrEqual(TARGET);
    expect(
      culling.drops_gathered,
      `neither ${BEEF} nor ${LEATHER} ever reached a resident's pack or a colony chest:`
        + ` the destination is decided on the spot (into a chest if the butcher can reach one, otherwise into the pack), so both being empty has only one`
        + ` reading: the carcass drops were never handed over, or they were dropped on the ground after being handed over`,
    ).toBe(true);
    const killed = STOCKED - culling.final_cows_in_world;
    const surplus = STOCKED - TARGET;
    const overshot = !culling.escaped_from_pen && culling.final_cows_in_world < TARGET;
    expect(
      overshot,
      `population control OVERSHOOTS: ${killed} cows were actually killed although only ${surplus} was surplus —`
        + ` the world is down to ${culling.final_cows_in_world} cows, BELOW the target of ${TARGET}.`
        + ` (Pen count and world count agree at every sample, so nothing merely escaped: these were real kills.)`
        + ` A slaughter in progress was not subtracted from the next round's surplus count, so the same surplus condemned one cow in each of two rounds.`,
    ).toBe(false);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

async function observeCulling(server: any, pen: any, stores: Vec[]) {
  const samples: any[] = [];
  let minCows = Number.POSITIVE_INFINITY;
  let dropsGathered = false;
  let cows = 0;

  let worldCows = 0;
  for (let ticks = 0; ticks <= SLAUGHTER_TICKS; ticks += BURST) {
    if (ticks > 0) await tick.sprint(server, BURST);
    cows = await countAnimalsInPen(server, pen, /cow/);
    worldCows = await countCowsInWorld(server);
    const beef = await colonyHasItem(server, BEEF, stores);
    const leather = await colonyHasItem(server, LEATHER, stores);
    minCows = Math.min(minCows, cows);
    dropsGathered = dropsGathered || beef || leather;
    samples.push({ ticks, cows, cows_in_world: worldCows, beef_gathered: beef, leather_gathered: leather });
  }

  const escaped = worldCows > cows;
  return {
    min_cows: minCows,
    final_cows: cows,
    final_cows_in_world: worldCows,
    escaped_from_pen: escaped,
    drops_gathered: dropsGathered,
    samples: samples.filter((s, i) => i % 10 === 0 || s.beef_gathered || s.leather_gathered || s.cows !== STOCKED),
  };
}

async function countCowsInWorld(server: any) {
  const result = await world.entities(server, { dimension: "minecraft:overworld", limit: 1000 })
    .catch(() => ({ entities: [] as world.EntityJson[] }));
  return result.entities.filter((e: any) => /cow/.test(String(e.type ?? ""))).length;
}
