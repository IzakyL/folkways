import { expect, test } from "@playwright/test";
import { tick, world, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import {
  commandPos,
  stockToolsForResidents,
  foundColonyFast,
  foundingBox,
  optInViaBook,
  TRADE_TOOLS,
  waitForGroundedPlayer,
  type Vec,
} from "../shared/colony/colony-founding";
import {
  colonyHasItem,
  mustCommand,
  packHasItem,
  tryCommand,
} from "../shared/ui/world-ui";
import {
  buildPen,
  countAnimalsInPen,
  createPastureZoneViaPanel,
  pastureZoneFailure,
  penLayout,
  type Pen,
} from "../shared/colony/pasture-tools";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { newRunId } from "../shared/harness/run-id";
import { twins } from "../shared/harness/stage";

const RESIDENT_COUNT = 4;

const DEFAULT_PASTURE_TARGET = 4;
const WOOL_ITEM = "minecraft:white_wool";
const HEN_TARGET = 6;
const EGG_ITEM = "minecraft:egg";

// Sheep must be sheared, their wool gathered and a lamb bred within this window.
const HERDING_TICKS = 3_000;
// Vanilla hens lay on their own eggTime; once an egg shows, residents get this long to collect it.
const MAX_LAY_TICKS = 16_000;
const COLLECT_TICKS = 2_400;

for (const stage of twins()) test(stage.title("pasture end-to-end: player carves a sheep pasture and a hen run through the real panel, residents shear, breed and collect eggs"), async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  ensureOut("e2e");
  const runId = newRunId();

  const serverTrace = tracePath("e2e", stage.name("pasture-server"));
  const clientTrace = tracePath("e2e", stage.name("pasture-client"));

  let pair: E2EPair | undefined;
  let server: any;
  let client: any;
  let pen: PastureFixture | undefined;
  let founding: any;

  try {
    const clientInstance = stage.name(`pasture-client-${runId}`);
    pair = await launchPair({
      config: stage.config,
      server: { trace: serverTrace, instance: seededServer(stage.name(`pasture-server-${runId}`)) },
      client: { trace: clientTrace, instance: clientInstance },
      snapshot: () => ({ containers: [pen?.supplyChest, founding?.foodChest].map((chest) => chest && stage.at(chest)),
        entityTypes: ["folkways:resident", "minecraft:sheep", "minecraft:chicken", "minecraft:item"] }),
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
    await stage.lay(server, foundingBox(origin));
    founding = await foundColonyFast(server, client, playerName, { origin, residentCount: RESIDENT_COUNT, stage });
    expect(
      founding.residents,
      `residents=${founding.residents} (expected ${RESIDENT_COUNT})`,
    ).toBeGreaterThanOrEqual(RESIDENT_COUNT);

    pen = { ...penLayout(origin, 8, 2), supplyChest: { x: origin.x + 5, y: origin.y, z: origin.z + 8 } };
    const henRun = penLayout(origin, 14, 3);
    await buildPen(server, pen, stage);
    await buildPen(server, henRun, stage);
    await mustCommand(server, `setblock ${commandPos(stage.at(pen.supplyChest))} minecraft:chest{Items:[]}`);
    await tick.sprint(server, 3);
    await optInViaBook(server, client, playerName, pen.supplyChest, "chest", stage);
    await mustCommand(server, `item replace block ${commandPos(stage.at(pen.supplyChest))} container.0 with minecraft:wheat 8`);
    await tick.sprint(server, 3);

    const zone = await createPastureZoneViaPanel(server, client, playerName, founding.standBlock, pen, {
      animal: "sheep",
      target: DEFAULT_PASTURE_TARGET,
    }, stage);
    expect(zone.created, pastureZoneFailure(zone, "sheep", DEFAULT_PASTURE_TARGET)).toBe(true);
    const henZone = await createPastureZoneViaPanel(server, client, playerName, founding.standBlock, henRun, {
      animal: "chicken",
      target: HEN_TARGET,
      index: 1,
    }, stage);
    expect(henZone.created, pastureZoneFailure(henZone, "chicken", HEN_TARGET)).toBe(true);

    const stores = [pen.supplyChest, founding.foodChest].map((chest) => stage.at(chest));
    const baseline = await observeEggs(server, henRun, stores);
    expect(baseline.stored + baseline.dropped,
      "before the hens lay, neither the site nor colony stock should already hold eggs").toBe(0);

    const sheepPositions = [
      { x: pen.min.x + 0.5, y: origin.y, z: pen.min.z + 0.5 },
      { x: pen.max.x + 0.5, y: origin.y, z: pen.max.z + 0.5 },
    ];
    for (const spot of sheepPositions) {
      await mustCommand(server, `summon minecraft:sheep ${spot.x} ${spot.y} ${spot.z} {Color:0b,Age:0,PersistenceRequired:1b}`);
    }
    const henSpots = [
      { x: henRun.min.x + 0.5, z: henRun.min.z + 0.5 },
      { x: henRun.min.x + 1.5, z: henRun.min.z + 0.5 },
      { x: henRun.max.x + 0.5, z: henRun.min.z + 0.5 },
      { x: henRun.min.x + 0.5, z: henRun.max.z + 0.5 },
      { x: henRun.min.x + 1.5, z: henRun.max.z + 0.5 },
      { x: henRun.max.x + 0.5, z: henRun.max.z + 0.5 },
    ];
    for (const spot of henSpots) {
      await mustCommand(server, `summon minecraft:chicken ${spot.x} ${origin.y} ${spot.z} {Age:0,PersistenceRequired:1b}`);
    }
    await tryCommand(server, `tp ${playerName} ${founding.standBlock.x + 0.5} ${stage.stand(founding.standBlock).y} ${founding.standBlock.z + 1.5} 180 30`);
    await tick.sprint(server, 20);

    await stockToolsForResidents(
      server, stage.at(pen.supplyChest), TRADE_TOOLS.herding, RESIDENT_COUNT, 1);

    const { herding, eggs } = await observePasture(server, pen, henRun, stores);
    const reasons: string[] = [];
    if (!herding.sheared_observed) {
      reasons.push(
        herding.shears_equipped
          ? "no shearing observed, yet shears are in some resident's pack: he has the tool, but the job never got as far as setting Sheared to true"
          : "no shearing observed, and no resident ever picked up the shears: none of them can get the tool, so shearing on this plot can only answer NO_TOOL",
      );
    }
    if (!herding.wool_gathered) {
      reasons.push(`${WOOL_ITEM} is neither in any resident's pack nor in a colony chest: the sheared wool was never collected`);
    }
    if (!herding.bred_observed) {
      reasons.push(`no breeding observed: the pasture stayed at ${herding.max_sheep} sheep (target ${DEFAULT_PASTURE_TARGET}, expected >= 3)`);
    }
    if (!eggs.collected) {
      reasons.push(eggs.firstEggTick === undefined
        ? "no eggs laid in the hen run and no new eggs in stock"
        : `eggs seen in the hen run at ${eggs.firstEggTick} ticks, but no new eggs in packs or chests within ${COLLECT_TICKS} ticks`);
    }
    expect(reasons, reasons.join(" | ")).toEqual([]);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

type PastureFixture = Pen & { supplyChest: Vec };

// The sheep and the hens in one loop: the hens' egg wait overlaps shearing and breeding.
async function observePasture(server: any, pen: PastureFixture, henRun: Pen, stores: Vec[]) {
  let shearedObserved = false;
  let woolGathered = false;
  let shearsEquipped = false;
  let maxSheep = 0;
  let firstEggTick: number | undefined;
  let collected = false;

  for (let ticks = 0; ; ticks += 20) {
    if (ticks > 0) await tick.sprint(server, 20);
    const herding = ticks <= HERDING_TICKS;
    if (herding) {
      const sheep = await countAnimalsInPen(server, pen, /sheep/);
      maxSheep = Math.max(maxSheep, sheep);
      shearedObserved ||= await anySheepSheared(server);
      woolGathered ||= await colonyHasItem(server, WOOL_ITEM, stores);
      shearsEquipped ||= await packHasItem(server, "minecraft:shears");
    }
    const sheepDone = !herding || (shearedObserved && woolGathered && maxSheep >= 3);
    if (!collected) {
      const sample = await observeEggs(server, henRun, stores);
      if (firstEggTick === undefined && (sample.inPen > 0 || sample.stored > 0)) firstEggTick = ticks;
      collected = sample.stored > 0;
    }
    const eggsDone = collected
      || (firstEggTick === undefined ? ticks >= MAX_LAY_TICKS : ticks - firstEggTick >= COLLECT_TICKS);
    if (sheepDone && eggsDone) break;
  }

  return {
    herding: {
      sheared_observed: shearedObserved,
      wool_gathered: woolGathered,
      shears_equipped: shearsEquipped,
      bred_observed: maxSheep >= 3,
      max_sheep: maxSheep,
    },
    eggs: { firstEggTick, collected },
  };
}

async function anySheepSheared(server: any) {
  return world.probe(server, `if entity @e[type=minecraft:sheep,nbt={Sheared:1b}]`);
}

async function observeEggs(server: MinecraftServer, pen: Pen, stores: Vec[]) {
  const result = await world.entities(server, { nbt: true, limit: 1000 });
  expect(result.limited, "drop sampling should not be truncated").toBe(false);
  const entities: any[] = result.entities;
  const drops = entities.filter((e: any) => e.type === "minecraft:item" && e.nbt?.Item?.id === EGG_ITEM)
    .map((e: any) => ({ count: e.nbt.Item.count as number,
      inPen: Math.floor(e.position.x) >= pen.min.x && Math.floor(e.position.x) <= pen.max.x
        && Math.floor(e.position.z) >= pen.min.z && Math.floor(e.position.z) <= pen.max.z
        && Math.floor(e.position.y) >= pen.min.y && Math.floor(e.position.y) <= pen.max.y + 4 }));
  const packed = entities.filter((e: any) => e.type === "folkways:resident")
    .flatMap((e: any) => e.nbt?.["neoforge:attachments"]?.["folkways:body_state"]?.Pack?.Items ?? [])
    .reduce((n: number, item: any) => n + (item.Stack?.id === EGG_ITEM ? item.Stack.count : 0), 0);
  let chested = 0;
  for (const store of stores) {
    const container: any = await world.container(server, store, { nbt: true });
    expect(container.found, `sampling the colony chest must succeed: ${container.reason ?? ""}`).toBe(true);
    expect(container.nbt?.Items, "chest inventory NBT must be readable").toBeDefined();
    chested += container.nbt.Items.reduce((n: number, item: any) => n + (item.id === EGG_ITEM ? item.count : 0), 0);
  }
  return {
    stored: chested + packed,
    dropped: drops.reduce((n, d) => n + d.count, 0),
    inPen: drops.filter((d) => d.inPen).reduce((n, d) => n + d.count, 0),
  };
}
