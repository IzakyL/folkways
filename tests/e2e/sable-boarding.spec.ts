import { expect, test } from "@playwright/test";
import { tick, world } from "@izakyl/blockwright-minecraft";
import { foundColonyFast, optInViaBook, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { deliverOrderViaSitePanel } from "../shared/colony/membership-optin";
import { residentsAboard, sableHops } from "../shared/colony/sable-probe";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { commandPos, tryCommand } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { aboard, GROUND, SABLE_CONFIG } from "../shared/harness/stage";

const ITEM = "minecraft:cobblestone";
const STOCK = 16;
const ORDERED = 8;
const DELIVERY_TICKS = 2_400;
const SAMPLE_TICKS = 20;
// Past the passage's 0.95 floor on how upright a deck must stand to be stepped onto (about 18 degrees).
const TILT_DEGREES = 25;

test("residents step between the ground and a docked Sable ship, and only while it lies level and flush", async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");

  const runId = newRunId();
  let pair: E2EPair | undefined;
  const ship = aboard();
  let shipChest: Vec | undefined;
  let groundChest: Vec | undefined;
  const evidence: any = {};
  try {
    pair = await launchPair({
      config: SABLE_CONFIG,
      server: { trace: tracePath("e2e", "sable-boarding-server"), instance: seededServer(`sable-boarding-server-${runId}`) },
      client: { trace: tracePath("e2e", "sable-boarding-client"), instance: `sable-boarding-client-${runId}` },
      snapshot: () => ({
        containers: [shipChest && ship.structure ? ship.at(shipChest) : undefined, groundChest],
        entityTypes: ["folkways:resident"],
      }),
    });
    const { server, client } = pair;

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    await tryCommand(server, `gamemode survival ${playerName}`);
    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    const founding = await foundColonyFast(server, client, playerName, { origin, residentCount: 2 });
    const colonyId = String(founding.evidence.colonyId);

    // The colony lives on the ground; its one stocked store is on a small ship docked flush with that ground.
    await ship.lay(server, { min: { x: origin.x + 6, y: origin.y, z: origin.z + 6 }, max: { x: origin.x + 10, y: origin.y + 3, z: origin.z + 10 } });
    evidence.structure = ship.structure;
    shipChest = { x: origin.x + 8, y: origin.y, z: origin.z + 8 };
    groundChest = { x: origin.x + 2, y: origin.y, z: origin.z + 8 };
    await tryCommand(server, `setblock ${commandPos(ship.at(shipChest))} minecraft:chest`);
    await tryCommand(server, `item replace block ${commandPos(ship.at(shipChest))} container.0 with ${ITEM} ${STOCK}`);
    await tryCommand(server, `setblock ${commandPos(groundChest)} minecraft:chest`);
    await tick.sprint(server, 5);
    evidence.opt_in_ship = await optInViaBook(server, client, playerName, shipChest, "chest", ship);
    evidence.opt_in_ground = await optInViaBook(server, client, playerName, groundChest, "chest", GROUND);

    evidence.hops_docked = await sableHops(server, colonyId);
    expect(evidence.hops_docked, "a ship docked flush with the ground offers steps on and off along its edge").toBeGreaterThan(0);

    // The goods are only on the ship, the order is on the ground: someone has to go aboard and come back.
    await deliverOrderViaSitePanel(server, client, playerName, groundChest, ITEM, ORDERED, GROUND);
    let boarded = false;
    let delivered = 0;
    const samples: any[] = [];
    for (let ticks = 0; ticks < DELIVERY_TICKS && !(boarded && delivered >= ORDERED); ticks += SAMPLE_TICKS) {
      await tick.sprint(server, SAMPLE_TICKS);
      const residents = await residentsAboard(server, colonyId);
      boarded ||= residents.some((one) => one.frame === ship.structure);
      delivered = await countIn(server, groundChest);
      samples.push({ ticks, aboard: residents.filter((one) => one.frame).length, delivered });
    }
    evidence.samples = samples;
    expect(boarded, "no resident ever stood aboard the ship: the store on it was never reached").toBe(true);
    expect(delivered, `the ground chest holds ${delivered} of the ${ORDERED} ordered from the ship's store`).toBe(ORDERED);

    const id = ship.structure!;
    await tryCommand(server, `sable physics rotation ${id} set axis 1 0 0 ${TILT_DEGREES}`);
    await tick.sprint(server, 2);
    evidence.hops_tilted = await sableHops(server, colonyId);
    expect(evidence.hops_tilted, `a deck tilted ${TILT_DEGREES} degrees is no floor to step onto`).toBe(0);

    await tryCommand(server, `sable physics rotation ${id} set axis 1 0 0 0`);
    await tick.sprint(server, 2);
    evidence.hops_levelled = await sableHops(server, colonyId);
    expect(evidence.hops_levelled, "levelled again, the ship can be boarded again").toBeGreaterThan(0);

    await tryCommand(server, `sable physics translation ${id} add 0 3 0`);
    await tick.sprint(server, 2);
    evidence.hops_lifted = await sableHops(server, colonyId);
    expect(evidence.hops_lifted, "a deck three blocks above the ground is out of a step's reach").toBe(0);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await testInfo.attach("sable-boarding", { body: JSON.stringify(evidence, null, 2), contentType: "application/json" });
    await pair?.teardown(testInfo);
  }
});

async function countIn(server: any, chest: Vec) {
  const read = await world.container(server, chest, { dimension: "minecraft:overworld" });
  return (read.slots ?? []).filter((slot: any) => slot.id === ITEM).reduce((sum: number, slot: any) => sum + slot.count, 0);
}
