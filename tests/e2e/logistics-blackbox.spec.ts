import { expect, test } from "@playwright/test";
import { MinecraftTimeoutError, tick, world } from "@izakyl/blockwright-minecraft";
import { deliverOrderViaSitePanel, optInContainerViaUI } from "./membership-optin";
import { foundColonyFast, waitForGroundedPlayer,
  entityTypeMatches,
} from "./colony-founding";
import { prepareSafeWalkway } from "./world-ui";
import { launchPair, type E2EPair } from "../shared/pair";
import { ensureOut, tracePath } from "../out-paths";
import { seededServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { commandPos, distance, tryCommand } from "../shared/bw-helpers";
import { newRunId } from "../shared/run-id";

const CONFIG_PATH = "blockwright.toml";

const ITEM_TYPE = "minecraft:item";
const PROVIDE_ITEM = "minecraft:cobblestone";
const PROVIDE_COUNT = 8;
const SOURCE_STOCK = 32;

test("a one-shot order delivers colony stock into the container it was filed on", async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");

  const runId = newRunId();
  let pair: E2EPair | undefined;
  const serverTrace = tracePath("e2e", "logistics-server");
  const clientTrace = tracePath("e2e", "logistics-client");

  let server: any;
  let client: any;
  let sourceChest: any;
  let targetChest: any;
  try {
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: serverTrace, instance: seededServer(`logistics-server-${runId}`) },
      client: { trace: clientTrace, instance: `logistics-client-${runId}` },
      snapshot: () => ({ containers: [sourceChest, targetChest], entityTypes: ["folkways:resident", ITEM_TYPE] }),
    });
    ({ server, client } = pair);

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    const playerPos = grounded.pos;
    const origin = { x: Math.round(playerPos.x) + 4, y: Math.round(playerPos.y), z: Math.round(playerPos.z) + 4 };
    const standPoint = { x: origin.x + 1, y: origin.y, z: origin.z + 9 };
    sourceChest = { x: origin.x + 2, y: origin.y, z: origin.z + 2 };
    targetChest = { x: standPoint.x, y: standPoint.y, z: standPoint.z - 2 };

    await prepareSafeWalkway(server, origin, standPoint);
    await tryCommand(server, `gamemode survival ${playerName}`);
    await foundColonyFast(server, client, playerName, { origin, residentCount: 3 });

    await tryCommand(server, `clear ${playerName} ${PROVIDE_ITEM}`);
    await tryCommand(server, `tp ${playerName} ${standPoint.x + 0.5} ${standPoint.y} ${standPoint.z + 0.5} 180 20`);
    await tryCommand(server, `execute positioned ${commandPos(origin)} run kill @e[type=${ITEM_TYPE},distance=..48]`);

    await tryCommand(server, `setblock ${commandPos(sourceChest)} minecraft:chest`);
    await tryCommand(server, `item replace block ${commandPos(sourceChest)} container.0 with ${PROVIDE_ITEM} ${SOURCE_STOCK}`);
    await tick.sprint(server, 20);
    const sourceStocked = (await readChest(server, sourceChest, PROVIDE_ITEM)).hasStack(SOURCE_STOCK);
    expect(sourceStocked).toBe(true);
    await optInContainerViaUI(server, client, playerName, sourceChest);
    await tryCommand(server, `setblock ${commandPos(targetChest)} minecraft:chest`);
    await tick.sprint(server, 5);
    await optInContainerViaUI(server, client, playerName, targetChest);
    expect((await readChest(server, targetChest, PROVIDE_ITEM)).total).toBe(0);

    await tryCommand(server, `item replace entity ${playerName} inventory.26 from entity ${playerName} weapon.mainhand`);
    await tryCommand(server, `item replace entity ${playerName} weapon.mainhand with minecraft:air`);
    await deliverOrderViaSitePanel(server, client, playerName, targetChest, PROVIDE_ITEM, PROVIDE_COUNT);

    const final = await pollDelivery(server, sourceChest, targetChest);

    expect(final.targetTotal).toBe(PROVIDE_COUNT);
    expect(final.chestHasRemainder).toBe(true);
    expect(final.itemsAtTarget).toBe(0);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

async function pollDelivery(server: any, sourceChest: any, targetChest: any) {
  await world.waitFor(
    server,
    `if items block ${commandPos(targetChest)} container.* ${PROVIDE_ITEM}`,
    { maxTicks: 1200, stepTicks: 5 },
  ).catch((error: unknown) => {
    // Running out is fine here: the assertions below say what did or did not arrive.
    if (!(error instanceof MinecraftTimeoutError)) throw error;
  });
  await tick.sprint(server, 200);
  const [entitiesResult, source, target] = await Promise.all([
    world.entities(server, { dimension: "minecraft:overworld", limit: 1000 }),
    readChest(server, sourceChest, PROVIDE_ITEM),
    readChest(server, targetChest, PROVIDE_ITEM),
  ]);
  const itemsAtTarget = entitiesResult.entities
    .filter((entity: any) => entityTypeMatches(entity.type, ITEM_TYPE))
    .filter((entity: any) => itemEntityIsSpec(entity, PROVIDE_ITEM))
    .filter((entity: any) => entity.position && distance(entity.position, targetChest) <= 3).length;
  return {
    targetTotal: target.total,
    chestHasRemainder: source.hasStack(SOURCE_STOCK - PROVIDE_COUNT),
    chestActual: source.total,
    itemsAtTarget,
  };
}

async function readChest(server: any, chest: any, itemId: string) {
  const result = await world.container(server, chest, { dimension: "minecraft:overworld" });
  if (result.found === false || result.isContainer === false) {
    throw new Error(`source chest at ${commandPos(chest)} is not a readable container: ${JSON.stringify(result)}`);
  }
  const matching = (result.slots ?? []).filter((slot: any) => slot.id === itemId);
  return {
    total: matching.reduce((sum: number, slot: any) => sum + slot.count, 0),
    hasStack: (count: number) => matching.some((slot: any) => slot.count === count),
  };
}

function itemEntityIsSpec(entity: any, itemId: string) {
  const id = entity?.item?.id ?? entity?.itemId ?? entity?.stack?.id;
  if (typeof id !== "string") {
    return false;
  }
  return (id.includes(":") ? id : `minecraft:${id}`) === itemId;
}
