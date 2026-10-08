import { expect, test } from "@playwright/test";
import { errorSummary } from "@izakyl/blockwright-client";
import { MinecraftTimeoutError, tick, world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import {
  commandPos,
  foundColonyFast,
  optInViaBook,
  waitForGroundedPlayer,
  type Vec,
} from "./colony-founding";
import { setMaintainDemandViaUI } from "./membership-optin";
import { readDoing } from "./resident-probe";
import { mustCommand, packHasItem } from "./world-ui";
import {
  blockHasItem,
  buildPen,
  countAnimalsInPen,
  createPastureZoneViaPanel,
  looseItemExists,
  pastureZoneFailure,
  penLayout,
  purgeLooseItem,
} from "./pasture-tools";
import { launchPair, type E2EPair } from "../shared/pair";
import { ensureOut, tracePath } from "../out-paths";
import { seededServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { tryCommand } from "../shared/bw-helpers";
import { newRunId } from "../shared/run-id";

const CONFIG_PATH = "blockwright.toml";
const RESIDENT_COUNT = 3;

const TARGET = 2;
const BUCKET = "minecraft:bucket";
const MILK = "minecraft:milk_bucket";

const BLOCKED_PHASE_TICKS = 800;
const ORDER_ALIVE_TICKS = 2_400;
const MILK_PHASE_TICKS = 6_000;
const BURST = 20;

test("pasture milk end-to-end: a standing order for milk stays unfilled until the colony actually owns an empty bucket", async ({}, testInfo) => {
  test.setTimeout(BUDGET.slow);
  ensureOut("e2e");
  const runId = newRunId();

  const serverTrace = tracePath("e2e", "pasture-milk-server");
  const clientTrace = tracePath("e2e", "pasture-milk-client");

  let pair: E2EPair | undefined;
  let server: any;
  let client: any;
  let demandChest: Vec | undefined;
  let supplyChest: Vec | undefined;

  try {
    const clientInstance = `pasture-milk-client-${runId}`;
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: serverTrace, instance: seededServer(`pasture-milk-server-${runId}`) },
      client: { trace: clientTrace, instance: clientInstance },
      snapshot: () => ({ containers: [demandChest, supplyChest], entityTypes: ["folkways:resident", "minecraft:cow"] }),
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

    const pen = penLayout(origin, 8);
    demandChest = { x: origin.x + 5, y: origin.y, z: origin.z + 8 };
    supplyChest = { x: origin.x + 7, y: origin.y, z: origin.z + 8 };
    await buildPen(server, pen);
    await mustCommand(server, `setblock ${commandPos(demandChest)} minecraft:chest{Items:[]}`);
    await mustCommand(server, `setblock ${commandPos(supplyChest)} minecraft:chest{Items:[]}`);
    await tick.sprint(server, 3);
    await optInViaBook(server, client, playerName, demandChest, "chest");
    await optInViaBook(server, client, playerName, supplyChest, "chest");

    const zone = await createPastureZoneViaPanel(server, client, playerName, founding.standBlock, pen, {
      animal: "cow",
      target: TARGET,
    });
    expect(zone.created, pastureZoneFailure(zone, "cow", TARGET)).toBe(true);

    for (const spot of [
      { x: pen.min.x + 0.5, z: pen.min.z + 0.5 },
      { x: pen.max.x + 0.5, z: pen.max.z + 0.5 },
    ]) {
      await mustCommand(server, `summon minecraft:cow ${spot.x} ${origin.y} ${spot.z} {Age:0,PersistenceRequired:1b}`);
    }
    await tryCommand(server, `tp ${playerName} ${founding.standBlock.x + 0.5} ${founding.standBlock.y} ${founding.standBlock.z + 1.5} 180 30`);
    await tick.sprint(server, 20);

    await mustCommand(server, `item replace entity ${playerName} weapon.mainhand with minecraft:air`);
    await tick.sprint(server, 3);

    await setMaintainDemandViaUI(server, client, playerName, demandChest, MILK, 1);
    await purgeLooseItem(server, playerName, MILK);
    await purgeLooseItem(server, playerName, BUCKET);
    await tick.sprint(server, 20);

    const strayMilk = await looseItemExists(server, MILK);
    const milkAlreadyInChest = await blockHasItem(server, demandChest, MILK);
    const cleanSlate = !strayMilk && !milkAlreadyInChest;
    expect(
      cleanSlate,
      `the negative phase did not start from a clean slate (${MILK} on the ground: ${strayMilk}; already in the demand chest: ${milkAlreadyInChest}): `
        + `a stray milk bucket on the ground gets picked up by Gathering, so "milk showed up" holds without any milking and this phase proves nothing`,
    ).toBe(true);

    const blocked = await pollForItem(server, demandChest, MILK, BLOCKED_PHASE_TICKS);
    expect(
      blocked.found,
      `the colony has no empty buckets, yet ${MILK} appeared at tick ${blocked.ticks}: the milking recipe's empty-bucket input is not gating anything, `
        + `so a green "milk in the chest" proves nothing`,
    ).toBe(false);

    await mustCommand(server, `item replace block ${commandPos(supplyChest)} container.0 with ${MILK} 1`);
    await tick.sprint(server, 20);
    const standing = await pollForItem(server, demandChest, MILK, ORDER_ALIVE_TICKS);
    expect(
      standing.found,
      `a ready milk bucket put in the member supply chest did not reach the demand chest within ${ORDER_ALIVE_TICKS} ticks: the order pinned to that chest`
      + ` is not standing at all, so phase B cannot prove anything about milking`,
    ).toBe(true);
    const beforeClear = await chestItems(server, demandChest);
    await emptyChest(server, demandChest);
    await purgeLooseItem(server, playerName, MILK);
    await tick.sprint(server, 20);
    const afterTicks = await chestItems(server, demandChest);
    expect(
      await blockHasItem(server, demandChest, MILK),
      "the milk bucket in the demand chest was not fully cleared, and it would turn phase B's check green. "
        + `before clearing=${beforeClear}; after clearing and running 25 ticks=${afterTicks}. `
        + "(if it reappears after clearing, the colony brought it back; check whose pack is still holding a bucket)",
    ).toBe(false);

    for (let slot = 0; slot < 4; slot++) {
      await mustCommand(server, `item replace block ${commandPos(supplyChest)} container.${slot} with ${BUCKET} 1`);
    }
    await tick.sprint(server, 20);
    const milking = await pollForItem(server, demandChest, MILK, MILK_PHASE_TICKS);
    const doing: any[] = [];
    for (let round = 0; round < 5; round++) {
      await tick.sprint(server, 40);
      doing.push(await whatEachResidentIsDoing(server));
    }
    const diagnosis = {
      bucket_still_in_supply: await blockHasItem(server, supplyChest, BUCKET),
      supply_is_member: await world.probe(server, `if data block ${commandPos(supplyChest)} "neoforge:attachments"."folkways:colony_member"`),
      demand_is_member: await world.probe(server, `if data block ${commandPos(demandChest)} "neoforge:attachments"."folkways:colony_member"`),
      bucket_in_a_pack: await packHasItem(server, BUCKET),
      milk_in_a_pack: await packHasItem(server, MILK),
      cows_in_pen: await countAnimalsInPen(server, pen, /cow/),
      doing,
    };
    expect(
      milking.found,
      `${JSON.stringify(diagnosis)} — `
      + `${MILK} never reached the member demand chest within ${MILK_PHASE_TICKS} ticks after 4 empty buckets were stocked:`
        + ` the order pinned to this chest never drove the bucket x1 -> milk_bucket x1 recipe on MilkHost`,
    ).toBe(true);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

async function pollForItem(server: MinecraftServer, chest: Vec, itemId: string, maxTicks: number) {
  try {
    const r = await world.waitFor(
      server,
      `if items block ${commandPos(chest)} container.* ${itemId}`,
      { maxTicks, stepTicks: BURST },
    );
    return { found: true, ticks: r.ticks as number | null };
  } catch (error) {
    if (!(error instanceof MinecraftTimeoutError)) throw error;
    return { found: false, ticks: null };
  }
}

const CHEST_SLOTS = 27;

async function emptyChest(server: any, chest: Vec) {
  for (let slot = 0; slot < CHEST_SLOTS; slot++) {
    await tryCommand(server, `item replace block ${commandPos(chest)} container.${slot} with minecraft:air`);
  }
  await tick.sprint(server, 5);
}

async function chestItems(server: any, at: Vec) {
  const said = await tryCommand(server, `data get block ${commandPos(at)} Items`)
    .catch((error: unknown) => ({ output: [errorSummary(error)] }));
  return (said?.output ?? []).join(" ").slice(0, 400) || "(unreadable)";
}

async function whatEachResidentIsDoing(server: any) {
  const listed = await world
    .entities(server, { dimension: "minecraft:overworld", types: ["folkways:resident"], limit: 100 })
    .catch(() => ({ entities: [] as world.EntityJson[] }));
  const out: any[] = [];
  for (const resident of listed.entities) {
    try {
      out.push(await readDoing(server, resident.uuid));
    } catch (error) {
      out.push({ uuid: resident.uuid, doing_error: errorSummary(error) });
    }
  }
  return out;
}
