import { expect, test } from "@playwright/test";
import { tick, world } from "@izakyl/blockwright-minecraft";
import {
  commandPos,
  stockToolsForResidents,
  foundColonyFast,
  optInViaBook,
  TRADE_TOOLS,
  waitForGroundedPlayer,
  type Vec,
} from "./colony-founding";
import {
  colonyHasItem,
  mustCommand,
  packHasItem,
  tryCommand,
} from "./world-ui";
import {
  buildPen,
  countAnimalsInPen,
  createPastureZoneViaPanel,
  pastureZoneFailure,
  penLayout,
  type Pen,
} from "./pasture-tools";
import { launchPair, type E2EPair } from "../shared/pair";
import { ensureOut, tracePath } from "../out-paths";
import { seededServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { newRunId } from "../shared/run-id";

const CONFIG_PATH = "blockwright.toml";
const RESIDENT_COUNT = 3;

const DEFAULT_PASTURE_TARGET = 4;
const WOOL_ITEM = "minecraft:white_wool";

test("pasture end-to-end: player carves a sheep pasture through the real panel, residents shear + breed", async ({}, testInfo) => {
  test.setTimeout(BUDGET.standard);
  ensureOut("e2e");
  const runId = newRunId();

  const serverTrace = tracePath("e2e", "pasture-server");
  const clientTrace = tracePath("e2e", "pasture-client");

  let pair: E2EPair | undefined;
  let server: any;
  let client: any;
  let pen: PastureFixture | undefined;
  let founding: any;

  try {
    const clientInstance = `pasture-client-${runId}`;
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: serverTrace, instance: seededServer(`pasture-server-${runId}`) },
      client: { trace: clientTrace, instance: clientInstance },
      snapshot: () => ({ containers: [pen?.supplyChest, founding?.foodChest], entityTypes: ["folkways:resident", "minecraft:sheep"] }),
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
    expect(
      founding.residents,
      `residents=${founding.residents} (expected ${RESIDENT_COUNT})`,
    ).toBeGreaterThanOrEqual(RESIDENT_COUNT);

    pen = { ...penLayout(origin, 8, 2), supplyChest: { x: origin.x + 5, y: origin.y, z: origin.z + 8 } };
    await buildPen(server, pen);
    await mustCommand(server, `setblock ${commandPos(pen.supplyChest)} minecraft:chest{Items:[]}`);
    await tick.sprint(server, 3);
    await optInViaBook(server, client, playerName, pen.supplyChest, "chest");
    await mustCommand(server, `item replace block ${commandPos(pen.supplyChest)} container.0 with minecraft:wheat 8`);
    await tick.sprint(server, 3);

    const zone = await createPastureZoneViaPanel(server, client, playerName, founding.standBlock, pen, {
      animal: "sheep",
      target: DEFAULT_PASTURE_TARGET,
    });
    expect(zone.created, pastureZoneFailure(zone, "sheep", DEFAULT_PASTURE_TARGET)).toBe(true);

    const sheepPositions = [
      { x: pen.min.x + 0.5, y: origin.y, z: pen.min.z + 0.5 },
      { x: pen.max.x + 0.5, y: origin.y, z: pen.max.z + 0.5 },
    ];
    for (const spot of sheepPositions) {
      await mustCommand(server, `summon minecraft:sheep ${spot.x} ${spot.y} ${spot.z} {Color:0b,Age:0,PersistenceRequired:1b}`);
    }
    await tryCommand(server, `tp ${playerName} ${founding.standBlock.x + 0.5} ${founding.standBlock.y} ${founding.standBlock.z + 1.5} 180 30`);
    await tick.sprint(server, 20);

    await stockToolsForResidents(
      server, pen.supplyChest, TRADE_TOOLS.herding, RESIDENT_COUNT, 1);

    const herding = await observeHerding(server, pen, [pen.supplyChest, founding.foodChest]);
    const reasons: string[] = [];
    if (!herding.sheared_observed) {
      reasons.push(
        herding.shears_equipped
          ? "no shearing observed, yet shears are in some resident's pack: he has the tool, but the job never got as far as setting Sheared to true"
          : "no shearing observed, and no resident ever picked up the shears: none of them can get the tool, so shearing on this plot can only answer NO_TOOL",
      );
    }
    if (!herding.wool_gathered) {
      reasons.push(
        `${WOOL_ITEM} is neither in any resident's pack nor in a colony chest: the sheared wool was never collected. `
        + `The destination is decided on the spot (into a chest if one is in reach, otherwise into the pack), so both being empty has only one reading: `
        + `the wool was never handed over, or it was dropped on the ground after being handed over`,
      );
    }
    if (!herding.bred_observed) {
      reasons.push(`no breeding observed: the pasture stayed at ${herding.max_sheep} sheep (target ${DEFAULT_PASTURE_TARGET}, expected >= 3)`);
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

async function observeHerding(server: any, pen: PastureFixture, stores: Vec[]) {
  const samples: any[] = [];
  let shearedObserved = false;
  let woolGathered = false;
  let shearsEquipped = false;
  let maxSheep = 0;

  for (let iteration = 0; iteration <= 150; iteration++) {
    if (iteration > 0) await tick.sprint(server, 20);
    const sheep = await countAnimalsInPen(server, pen, /sheep/);
    const sheared = await anySheepSheared(server);
    const shearsHeld = await packHasItem(server, "minecraft:shears");
    const wool = await colonyHasItem(server, WOOL_ITEM, stores);
    maxSheep = Math.max(maxSheep, sheep);
    shearedObserved = shearedObserved || sheared;
    woolGathered = woolGathered || wool;
    shearsEquipped = shearsEquipped || shearsHeld;
    samples.push({
      order: iteration,
      ticks: iteration * 20,
      sheep,
      sheared,
      shears_in_a_pack: shearsHeld,
      wool_gathered: wool,
    });
    if (shearedObserved && woolGathered && maxSheep >= 3) break;
  }

  return {
    sheared_observed: shearedObserved,
    wool_gathered: woolGathered,
    shears_equipped: shearsEquipped,
    bred_observed: maxSheep >= 3,
    max_sheep: maxSheep,
    samples,
  };
}

async function anySheepSheared(server: any) {
  return world.probe(server, `if entity @e[type=minecraft:sheep,nbt={Sheared:1b}]`);
}
