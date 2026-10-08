import { expect, test } from "@playwright/test";
import { writeFileSync } from "node:fs";
import { screen, tick, world } from "@izakyl/blockwright-minecraft";
import { foundColonyFast, optInViaBook, waitForGroundedPlayer,
  entityTypeMatches,
} from "./colony-founding";
import { aimAndWorldLeftClick, countInResidentPacks, mustCommand } from "./world-ui";
import {
  colonyRegistry,
  configureZone,
  createMarkedZone,
  setBookGesture,
  zoneCellsAt,
  zoneChoiceSettingAt,
  zoneKindAt,
  ZONE_KIND_ID,
} from "./zone-tools";
import { launchPair, type E2EPair } from "../shared/pair";
import { ensureOut, outAbs, tracePath } from "../out-paths";
import { seededServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { settledAll, unwrapSettled } from "./tick-tools";
import { commandPos, distance, tryCommand } from "../shared/bw-helpers";
import { newRunId } from "../shared/run-id";

const CONFIG_PATH = "blockwright.toml";

const RESIDENT_TYPE = "folkways:resident";
const ITEM_TYPE = "minecraft:item";
const FOOD_ITEM = "minecraft:bread";
const RESIDENT_COUNT = 3;
const RATION = 8;
const BED_NEAR_DISTANCE = 2.75;
const MIN_SLEEPERS = 2;

const LABOR_TICKS_TO_SLEEPY = 12_000;
const OBSERVE_TICKS = 36_000;
const BURST_TICKS = 1_200;

const FORESTRY_CROP = "minecraft:oak_sapling";
const LOG_ITEM = "minecraft:oak_log";
const WOODLOT_SIDE = 32;
const WOODLOT_MARGIN = 2;
const WOODLOT_HEIGHT = 9;

test("a resident eats out of his own pack and lies down in his own bed", async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");

  const runId = newRunId();
  let pair: E2EPair | undefined;
  const serverTrace = tracePath("e2e", "needs-server");
  const clientTrace = tracePath("e2e", "needs-client");

  let server: any;
  let client: any;
  const evidence: any = {};
  try {
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: serverTrace, instance: seededServer(`needs-server-${runId}`) },
      client: { trace: clientTrace, instance: `needs-client-${runId}` },
      snapshot: () => ({ entityTypes: [RESIDENT_TYPE] }),
    });
    ({ server, client } = pair);
    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    await tryCommand(server, `gamemode survival ${playerName}`);

    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    const founding = await foundColonyFast(server, client, playerName, {
      origin, residentCount: RESIDENT_COUNT,
    });
    const beds = founding.bedFeet;
    evidence.beds = await readBlocks(server, beds);

    await tryCommand(server, "gamerule doDaylightCycle false");
    await tryCommand(server, "time set day");
    await tryCommand(server, `execute positioned ${commandPos(origin)} run kill @e[type=${ITEM_TYPE},distance=..24]`);
    await tryCommand(server, `setblock ${commandPos(founding.foodChest)} minecraft:chest{Items:[]}`);

    const woodlot = raiseWoodlot(origin);
    evidence.woodlot = await buildWoodlot(server, woodlot);
    evidence.depots = await enrolDepots(server, client, playerName, woodlot);
    evidence.zone = await armWoodlotZone(server, client, playerName, founding.standBlock, woodlot);
    expect(evidence.zone.armed, `woodlot was not created: ${JSON.stringify(evidence.zone)}`).toBe(true);
    const held = [...woodlot.depots];

    await tryCommand(
      server,
      `execute as @e[type=${RESIDENT_TYPE}] run data merge entity @s `
      + `{"neoforge:attachments":{"folkways:body_state":{Pack:{Items:[`
      + `{Slot:0b,Stack:{id:"${FOOD_ITEM}",count:${RATION}}}]}}}}`,
    );

    const packsBefore = await countInResidentPacks(server);
    evidence.packs_before = packsBefore;
    expect(packsBefore.resident_count, "rations must go to residents that actually exist").toBe(RESIDENT_COUNT);
    expect(packsBefore.totals[FOOD_ITEM] ?? 0, "one ration per resident").toBe(RATION * RESIDENT_COUNT);

    evidence.chests_before = await countInChests(server, held, FOOD_ITEM);
    const foodBefore = (packsBefore.totals[FOOD_ITEM] ?? 0) + evidence.chests_before;
    evidence.food_before = foodBefore;

    let ate = false;
    let slept: any = null;
    let mostAsleep = 0;
    const samples: any[] = [];
    for (let ticks = 0; ticks < OBSERVE_TICKS && !(ate && mostAsleep >= MIN_SLEEPERS); ticks += BURST_TICKS) {
      await tick.sprint(server, BURST_TICKS);
      const [packsRead, bedsideRead, asleepRead, chestRead] = await settledAll<any>([
        () => countInResidentPacks(server),
        () => bedsideSnapshot(server, beds),
        () => sleepersInBed(server, beds[0].y, RESIDENT_COUNT),
        () => countInChests(server, held, FOOD_ITEM),
      ]);
      const packs = unwrapSettled(packsRead);
      const bedside = unwrapSettled(bedsideRead);
      const asleep = unwrapSettled(asleepRead);
      const foodHeld = (packs.totals[FOOD_ITEM] ?? 0) + unwrapSettled<number>(chestRead);
      samples.push({
        ticks: ticks + BURST_TICKS,
        food_in_packs: packs.totals[FOOD_ITEM] ?? 0,
        food_held: foodHeld,
        logs_in_packs: packs.totals[LOG_ITEM] ?? 0,
        near_beds: bedside.near.length,
        asleep,
      });
      mostAsleep = Math.max(mostAsleep, asleep);
      if (foodHeld < foodBefore) {
        ate = true;
        evidence.packs_after_eating = packs;
      }
      if (asleep > 0) {
        slept = bedside;
      }
    }
    evidence.samples = samples;
    evidence.slept = slept;
    evidence.most_asleep = mostAsleep;

    expect(ate, `the total ${FOOD_ITEM} across packs and member chests did not drop at all: hungry residents did not eat what they carried`).toBe(true);
    expect(
      slept !== null,
      `after ${OBSERVE_TICKS} ticks and ${evidence.woodlot?.logs} logs placed`
        + ` (the sleepy threshold sits at a windup of ${LABOR_TICKS_TO_SLEEPY} ticks worked), `
        + "no resident ever lay down in a bed (SleepingX never appeared on any entity)",
    ).toBe(true);
    expect(
      mostAsleep,
      `at most ${mostAsleep} residents were in bed at once: one bed serving more than one resident was never observed`,
    ).toBeGreaterThanOrEqual(MIN_SLEEPERS);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    writeFileSync(outAbs("e2e", "reports", "needs-evidence.json"), JSON.stringify(evidence, null, 2) + "\n");
    testInfo.attach("needs-evidence.json", {
      body: JSON.stringify(evidence, null, 2),
      contentType: "application/json",
    });
    await pair?.teardown(testInfo);
  }
});

type Woodlot = {
  min: { x: number; y: number; z: number };
  max: { x: number; y: number; z: number };
  logRows: number[];
  logSpan: { from: number; to: number };
  depots: { x: number; y: number; z: number }[];
};

function raiseWoodlot(origin: { x: number; y: number; z: number }): Woodlot {
  const min = { x: origin.x + 8, y: origin.y, z: origin.z + 8 };
  const max = { x: min.x + WOODLOT_SIDE - 1, y: origin.y, z: min.z + WOODLOT_SIDE - 1 };
  const logRows: number[] = [];
  for (let z = min.z + WOODLOT_MARGIN; z <= max.z - WOODLOT_MARGIN; z += 2) {
    logRows.push(z);
  }
  return {
    min,
    max,
    logRows,
    logSpan: { from: min.x + WOODLOT_MARGIN, to: max.x - WOODLOT_MARGIN },
    depots: [
      { x: min.x - 2, y: origin.y + 1, z: min.z },
      { x: min.x - 2, y: origin.y + 1, z: min.z + 2 },
    ],
  };
}

async function buildWoodlot(server: any, woodlot: Woodlot) {
  const evidence: any = { min: woodlot.min, max: woodlot.max, rows: woodlot.logRows.length };
  await mustCommand(server, `fill ${commandPos(woodlot.min)} ${commandPos(woodlot.max)} minecraft:dirt`);
  await mustCommand(
    server,
    `fill ${woodlot.min.x} ${woodlot.min.y + 1} ${woodlot.min.z}`
    + ` ${woodlot.max.x} ${woodlot.min.y + WOODLOT_HEIGHT + 2} ${woodlot.max.z} minecraft:air`,
  );
  const wide = woodlot.logSpan.to - woodlot.logSpan.from + 1;
  for (const z of woodlot.logRows) {
    await mustCommand(
      server,
      `fill ${woodlot.logSpan.from} ${woodlot.min.y + 1} ${z}`
      + ` ${woodlot.logSpan.to} ${woodlot.min.y + WOODLOT_HEIGHT} ${z} ${LOG_ITEM}`,
    );
  }
  evidence.logs = woodlot.logRows.length * wide * WOODLOT_HEIGHT;
  evidence.labor_ticks = evidence.logs * 12;
  return evidence;
}

async function enrolDepots(server: any, client: any, playerName: string, woodlot: Woodlot) {
  const evidence: any[] = [];
  for (const depot of woodlot.depots) {
    await mustCommand(server, `fill ${commandPos({ ...depot, y: depot.y - 1 })}`
      + ` ${commandPos({ x: depot.x, y: depot.y - 1, z: depot.z + 1 })} minecraft:dirt`);
    await mustCommand(server, `setblock ${commandPos(depot)} minecraft:chest{Items:[]}`);
    await tick.sprint(server, 3);
    evidence.push(await optInViaBook(server, client, playerName, depot, "chest"));
  }
  return evidence;
}

async function armWoodlotZone(
  server: any, client: any, playerName: string, standBlock: any, woodlot: Woodlot,
) {
  const evidence: any = { crop: FORESTRY_CROP };
  const cornerA = { ...woodlot.min };
  const cornerB = { ...woodlot.max };
  evidence.zone_mode = await setBookGesture(server, client, playerName, "box");
  evidence.corner_1 = await aimAndWorldLeftClick(server, client, playerName, cornerA);
  await tick.sprint(server, 3);
  evidence.corner_2 = await aimAndWorldLeftClick(server, client, playerName, cornerB);
  await tick.sprint(server, 5);
  evidence.create = await createMarkedZone(server, client, playerName, standBlock, "farm");
  evidence.set_crop = await configureZone(server, client, playerName, standBlock,
    { min: cornerA, max: cornerB },
    [{ key: "crop", option: FORESTRY_CROP }]);

  const registry = await colonyRegistry(server);
  evidence.zone_kind = await zoneKindAt(server, registry, 0);
  evidence.zone_crop = await zoneChoiceSettingAt(server, registry, "crop", 0);
  evidence.zone_cells = await zoneCellsAt(server, registry, 0);
  evidence.armed = evidence.zone_kind === ZONE_KIND_ID.farm
    && evidence.zone_crop === FORESTRY_CROP
    && evidence.zone_cells >= WOODLOT_SIDE * WOODLOT_SIDE;
  await screen.dismiss(client);
  await tick.sprint(server, 10);
  return evidence;
}

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

async function readBlocks(server: any, positions: any[]) {
  const reads = [];
  for (const position of positions) {
    const result = await world.block(server, position, { dimension: "minecraft:overworld" });
    reads.push({ position, block: { id: result.id, state: result.state } });
  }
  return reads;
}

async function sleepersInBed(server: any, bedY: number, most: number) {
  const condition = `execute if entity @e[type=${RESIDENT_TYPE},nbt={SleepingY:${bedY}},limit=${most}]`;
  const outcome = await tryCommand(server, condition);
  if (outcome.status !== "ok") {
    throw new Error(`sleepersInBed probe could not be evaluated: ${condition} -> ${JSON.stringify(outcome)}`);
  }
  return outcome.resultCode ?? 0;
}

async function bedsideSnapshot(server: any, beds: any[]) {
  const entitiesResult = await world.entities(server, { dimension: "minecraft:overworld", limit: 1000 });
  const residents = entitiesResult.entities
    .filter((entity: any) => entityTypeMatches(entity.type, RESIDENT_TYPE))
    .map((entity: any) => ({
      uuid: entity.uuid,
      position: entity.position,
      nearest_bed_distance: nearestBedDistance(entity.position, beds),
    }));
  return {
    near_distance: BED_NEAR_DISTANCE,
    residents,
    near: residents.filter((resident: any) => resident.nearest_bed_distance <= BED_NEAR_DISTANCE),
  };
}

function nearestBedDistance(position: any, beds: any[]) {
  return Math.min(...beds.map((bed) => distance(position, bedCenter(bed))));
}

function bedCenter(bed: any) {
  return { x: bed.x + 0.5, y: bed.y + 0.5, z: bed.z + 0.5 };
}
