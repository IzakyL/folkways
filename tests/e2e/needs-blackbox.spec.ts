import { expect, test } from "@playwright/test";
import { writeFileSync } from "node:fs";
import { tick, world } from "@izakyl/blockwright-minecraft";
import { foundColonyFast, foundingBox, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { wearResidents } from "../shared/colony/vitals";
import { countInResidentPacks } from "../shared/ui/world-ui";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, outAbs, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { settledAll, unwrapSettled } from "../shared/harness/tick-tools";
import { commandPos, tryCommand } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { twins } from "../shared/harness/stage";

const RESIDENT_TYPE = "folkways:resident";
const ITEM_TYPE = "minecraft:item";
const FOOD_ITEM = "minecraft:bread";
const RESIDENT_COUNT = 3;
const RATION = 8;
const MIN_SLEEPERS = 2;

// Each starts the watch just past both marks: he eats at food 14 and looks for his bed at 800 tired.
const FOOD_AT_START = 12;
const EXHAUSTION_AT_START = 0;
const TIRED_AT_START = 900;
const OBSERVE_TICKS = 36_000;
const BURST_TICKS = 1_200;

for (const stage of twins()) test(stage.title("a resident eats out of his own pack and lies down in his own bed"), async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");

  const runId = newRunId();
  let pair: E2EPair | undefined;
  const serverTrace = tracePath("e2e", stage.name("needs-server"));
  const clientTrace = tracePath("e2e", stage.name("needs-client"));

  let server: any;
  let client: any;
  const evidence: any = {};
  try {
    pair = await launchPair({
      config: stage.config,
      server: { trace: serverTrace, instance: seededServer(stage.name(`needs-server-${runId}`)) },
      client: { trace: clientTrace, instance: stage.name(`needs-client-${runId}`) },
      snapshot: () => ({ entityTypes: [RESIDENT_TYPE] }),
    });
    ({ server, client } = pair);
    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    await tryCommand(server, `gamemode survival ${playerName}`);

    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    await stage.lay(server, foundingBox(origin));
    const founding = await foundColonyFast(server, client, playerName, {
      origin, residentCount: RESIDENT_COUNT, stage,
    });
    const beds = founding.bedFeet;

    await tryCommand(server, "gamerule doDaylightCycle false");
    await tryCommand(server, "time set day");
    await tryCommand(server, `execute positioned ${commandPos(stage.world(origin))} run kill @e[type=${ITEM_TYPE},distance=..24]`);
    await tryCommand(server, `setblock ${commandPos(stage.at(founding.foodChest))} minecraft:chest{Items:[]}`);
    // Bread hauled out of a pack into the member food chest is not bread eaten.
    const held = [stage.at(founding.foodChest)];

    await tryCommand(
      server,
      `execute as @e[type=${RESIDENT_TYPE}] run data merge entity @s `
      + `{"neoforge:attachments":{"folkways:body_state":{Pack:{Items:[`
      + `{Slot:0b,Stack:{id:"${FOOD_ITEM}",count:${RATION}}}]}}}}`,
    );

    evidence.worn = await wearResidents(server, founding.evidence.colonyId, {
      food: FOOD_AT_START, exhaustion: EXHAUSTION_AT_START, tired: TIRED_AT_START,
    });
    expect(evidence.worn.set, "every resident starts the watch hungry and sleepy").toBe(RESIDENT_COUNT);

    const packsBefore = await countInResidentPacks(server);
    evidence.packs_before = packsBefore;
    expect(packsBefore.resident_count, "rations must go to residents that actually exist").toBe(RESIDENT_COUNT);
    expect(packsBefore.totals[FOOD_ITEM] ?? 0, "one ration per resident").toBe(RATION * RESIDENT_COUNT);

    evidence.chests_before = await countInChests(server, held, FOOD_ITEM);
    const foodBefore = (packsBefore.totals[FOOD_ITEM] ?? 0) + evidence.chests_before;
    evidence.food_before = foodBefore;

    let ate = false;
    let mostAsleep = 0;
    const samples: any[] = [];
    for (let ticks = 0; ticks < OBSERVE_TICKS && !(ate && mostAsleep >= MIN_SLEEPERS); ticks += BURST_TICKS) {
      await tick.sprint(server, BURST_TICKS);
      const [packsRead, asleepRead, chestRead] = await settledAll<any>([
        () => countInResidentPacks(server),
        // A sleeper's SleepingY is the bed block's own, where the level stores it.
        () => sleepersInBed(server, stage.at(beds[0]).y, RESIDENT_COUNT),
        () => countInChests(server, held, FOOD_ITEM),
      ]);
      const packs = unwrapSettled(packsRead);
      const asleep = unwrapSettled(asleepRead);
      const foodHeld = (packs.totals[FOOD_ITEM] ?? 0) + unwrapSettled<number>(chestRead);
      samples.push({ ticks: ticks + BURST_TICKS, food_in_packs: packs.totals[FOOD_ITEM] ?? 0, food_held: foodHeld, asleep });
      mostAsleep = Math.max(mostAsleep, asleep);
      if (foodHeld < foodBefore) ate = true;
    }
    evidence.samples = samples;
    evidence.most_asleep = mostAsleep;

    expect(ate, `the total ${FOOD_ITEM} across packs and the food chest did not drop at all: hungry residents did not eat what they carried`).toBe(true);
    expect(mostAsleep, `after ${OBSERVE_TICKS} ticks no resident ever lay down in a bed`).toBeGreaterThan(0);
    expect(
      mostAsleep,
      `at most ${mostAsleep} residents were in bed at once: one bed serving more than one resident was never observed`,
    ).toBeGreaterThanOrEqual(MIN_SLEEPERS);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    writeFileSync(outAbs("e2e", "reports", `${stage.name("needs-evidence")}.json`), JSON.stringify(evidence, null, 2) + "\n");
    testInfo.attach(`${stage.name("needs-evidence")}.json`, {
      body: JSON.stringify(evidence, null, 2),
      contentType: "application/json",
    });
    await pair?.teardown(testInfo);
  }
});

async function countInChests(server: any, chests: any[], item: string) {
  const results = await Promise.all(
    chests.map((chest) => world.container(server, chest, { dimension: "minecraft:overworld" })),
  );
  let total = 0;
  for (const result of results) {
    for (const slot of result.slots ?? []) {
      if (slot.id === item) {
        total += slot.count ?? 1;
      }
    }
  }
  return total;
}

async function sleepersInBed(server: any, bedY: number, most: number) {
  const condition = `execute if entity @e[type=${RESIDENT_TYPE},nbt={SleepingY:${bedY}},limit=${most}]`;
  const outcome = await tryCommand(server, condition);
  if (outcome.status !== "ok") {
    throw new Error(`sleepersInBed probe could not be evaluated: ${condition} -> ${JSON.stringify(outcome)}`);
  }
  return outcome.resultCode ?? 0;
}
