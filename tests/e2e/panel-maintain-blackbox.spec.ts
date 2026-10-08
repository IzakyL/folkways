import { expect, test } from "@playwright/test";
import { tick, world } from "@izakyl/blockwright-minecraft";
import { setMaintainDemandViaUI } from "./membership-optin";
import { foundColonyFast, optInViaBook, waitForGroundedPlayer,
  entityTypeMatches,
} from "./colony-founding";
import { prepareSafeWalkway } from "./world-ui";
import { launchPair, type E2EPair } from "../shared/pair";
import { ensureOut, tracePath } from "../out-paths";
import { seededServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { commandPos, safeAction, tryCommand } from "../shared/bw-helpers";
import { newRunId } from "../shared/run-id";

const CONFIG_PATH = "blockwright.toml";

const RESIDENT_TYPE = "folkways:resident";
const ITEM_TYPE = "minecraft:item";
const DEMAND_ITEM = "minecraft:cobblestone";
const MAINTAIN_COUNT = 1;
const SOURCE_STOCK = 16;

test("container panel carry-set MAINTAIN stock replenishes the bound chest", async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");

  const runId = newRunId();
  let pair: E2EPair | undefined;
  const serverTrace = tracePath("e2e", "panel-server");
  const clientTrace = tracePath("e2e", "panel-client");

  let server: any;
  let client: any;
  let targetChest: any;
  let sourceChest: any;
  try {
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: serverTrace, instance: seededServer(`panel-server-${runId}`) },
      client: { trace: clientTrace, instance: `panel-client-${runId}` },
      snapshot: () => ({ containers: [targetChest, sourceChest], entityTypes: [RESIDENT_TYPE, ITEM_TYPE] }),
    });
    ({ server, client } = pair);
    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;

    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    await foundColonyFast(server, client, playerName, { origin, residentCount: 3 });
    targetChest = { x: origin.x + 5, y: origin.y, z: origin.z - 2 };
    sourceChest = { x: origin.x - 5, y: origin.y, z: origin.z + 5 };
    await tryCommand(server, "gamerule doDaylightCycle false");
    await tryCommand(server, "time set day");

    await prepareSafeWalkway(server, origin, targetChest, sourceChest);
    await tryCommand(server, `gamemode survival ${playerName}`);
    await tryCommand(server, `execute positioned ${commandPos(origin)} run kill @e[type=${ITEM_TYPE},distance=..48]`);
    for (const chest of [targetChest, sourceChest]) {
      await tryCommand(server, `setblock ${commandPos(chest)} minecraft:chest{Items:[]}`);
    }
    await tick.sprint(server, 3);
    for (const chest of [targetChest, sourceChest]) {
      await optInViaBook(server, client, playerName, chest, "chest");
    }
    await tryCommand(server, `item replace block ${commandPos(sourceChest)} container.0 with ${DEMAND_ITEM} ${SOURCE_STOCK}`);
    for (let slot = 0; slot < 9; slot++) {
      await tryCommand(server, `item replace entity ${playerName} inventory.${slot} with ${DEMAND_ITEM} 1`);
    }
    await tick.sprint(server, 20);
    const sourceStocked = await chestHasAtLeast(server, sourceChest, DEMAND_ITEM, SOURCE_STOCK);
    expect(sourceStocked).toBe(true);

    await tryCommand(server, `item replace entity ${playerName} weapon.mainhand with minecraft:air`);
    await tick.sprint(server, 3);

    await setMaintainDemandViaUI(server, client, playerName, targetChest, DEMAND_ITEM);

    const final = await pollReplenish(server, targetChest, sourceChest);
    expect(final.targetHasStock).toBe(true);
    expect(final.sourceHasRemainder).toBe(true);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

async function pollReplenish(server: any, targetChest: any, sourceChest: any) {
  await safeAction(() => world.waitFor(
    server,
    `if items block ${commandPos(targetChest)} container.* ${DEMAND_ITEM}`
      + ` if items block ${commandPos(sourceChest)} container.* ${DEMAND_ITEM}`,
    { maxTicks: 2400, stepTicks: 20 },
  ));
  return await replenishSnapshot(server, targetChest, sourceChest, 0, "after_predicate_sprint");
}

async function replenishSnapshot(server: any, targetChest: any, sourceChest: any, order: number, label: string) {
  const [entitiesResult, targetHas, sourceRemainder] = await Promise.all([
    world.entities(server, { dimension: "minecraft:overworld", limit: 1000 }),
    chestHasAtLeast(server, targetChest, DEMAND_ITEM, MAINTAIN_COUNT),
    chestHasAtLeast(server, sourceChest, DEMAND_ITEM, 1),
  ]);
  const residents = (entitiesResult.entities ?? []).filter((e: any) => entityTypeMatches(e.type, RESIDENT_TYPE)).length;
  return { order, label, targetHasStock: targetHas, sourceHasRemainder: sourceRemainder, residents };
}

async function chestItemCount(server: any, chest: any, itemId: string) {
  const result: any = await world.container(server, chest, { dimension: "minecraft:overworld" });
  if (!result || result.found === false || result.isContainer === false) {
    throw new Error(`container read at ${commandPos(chest)} is not usable: ${JSON.stringify(result)}`);
  }
  let total = 0;
  for (const slot of result.slots ?? []) {
    if (slot?.id === itemId) total += slot.count;
  }
  return total;
}

async function chestHasAtLeast(server: any, chest: any, itemId: string, count: number) {
  return (await chestItemCount(server, chest, itemId)) >= count;
}
