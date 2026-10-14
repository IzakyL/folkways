import { expect, test } from "@playwright/test";
import { tick, world } from "@izakyl/blockwright-minecraft";
import { foundColonyFast, foundingBox, optInViaBook, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { deliverOrderViaSitePanel } from "../shared/colony/membership-optin";
import { addressRoundTrip, driveShip, residentsAboard } from "../shared/colony/sable-probe";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { commandPos, tryCommand } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { aboard, SABLE_CONFIG } from "../shared/harness/stage";

const RESIDENTS = 2;
const ITEM = "minecraft:cobblestone";
const STOCK = 16;
const ORDERED = 8;
const RISE = 3;
const SAIL = 30;
const DELIVERY_TICKS = 2_400;

test("a colony aboard a Sable ship keeps everyone aboard and its work going while the ship rises and sails", async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");

  const runId = newRunId();
  let pair: E2EPair | undefined;
  const stage = aboard();
  let source: Vec | undefined;
  let target: Vec | undefined;
  const evidence: any = {};
  try {
    pair = await launchPair({
      config: SABLE_CONFIG,
      server: { trace: tracePath("e2e", "sable-moving-server"), instance: seededServer(`sable-moving-server-${runId}`) },
      client: { trace: tracePath("e2e", "sable-moving-client"), instance: `sable-moving-client-${runId}` },
      snapshot: () => ({
        containers: stage.structure ? [source, target].map((chest) => chest && stage.at(chest)) : [],
        entityTypes: ["folkways:resident"],
      }),
    });
    const { server, client } = pair;

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    await tryCommand(server, `gamemode survival ${playerName}`);
    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    await stage.lay(server, foundingBox(origin));
    const founding = await foundColonyFast(server, client, playerName, { origin, residentCount: RESIDENTS, stage });
    const colonyId = String(founding.evidence.colonyId);

    source = { x: origin.x + 6, y: origin.y, z: origin.z + 6 };
    target = { x: origin.x - 6, y: origin.y, z: origin.z + 6 };
    await tryCommand(server, `setblock ${commandPos(stage.at(source))} minecraft:chest`);
    await tryCommand(server, `item replace block ${commandPos(stage.at(source))} container.0 with ${ITEM} ${STOCK}`);
    await tryCommand(server, `setblock ${commandPos(stage.at(target))} minecraft:chest`);
    await tick.sprint(server, 5);
    await optInViaBook(server, client, playerName, source, "chest", stage);
    await optInViaBook(server, client, playerName, target, "chest", stage);
    await deliverOrderViaSitePanel(server, client, playerName, target, ITEM, ORDERED, stage);

    // The player steps off onto the ground and watches the ship go.
    await tryCommand(server, `tp ${playerName} ${origin.x + 0.5} ${origin.y} ${origin.z - 30.5}`);
    evidence.before = await residentsAboard(server, colonyId);
    expect(evidence.before.filter((one: any) => one.frame === stage.structure).length, "everyone starts aboard").toBe(RESIDENTS);

    const sprint = (ticks: number) => tick.sprint(server, ticks);
    evidence.rise = await driveShip(server, stage.structure!, { x: 0, y: RISE, z: 0 }, sprint);
    evidence.sail = await driveShip(server, stage.structure!, { x: SAIL, y: 0, z: 0 }, sprint);
    expect(evidence.sail.end.x - evidence.rise.start.x, `the ship sailed ${SAIL} blocks`).toBeGreaterThan(SAIL - 1);

    evidence.after = await residentsAboard(server, colonyId);
    expect(evidence.after.filter((one: any) => one.frame === stage.structure).length,
      `every resident is still aboard after the ship rose ${RISE} and sailed ${SAIL}: ${JSON.stringify(evidence.after)}`).toBe(RESIDENTS);

    let delivered = 0;
    for (let ticks = 0; ticks < DELIVERY_TICKS && delivered < ORDERED; ticks += 20) {
      await tick.sprint(server, 20);
      delivered = await countIn(server, stage.at(target));
    }
    expect(delivered, `the order filed before the ship moved was delivered aboard once it had: ${delivered} of ${ORDERED}`).toBe(ORDERED);

    // Turned a quarter round, the ship's addresses still lead back to its blocks and to where Sable shows them.
    await tryCommand(server, `sable physics rotation ${stage.structure} set axis 0 1 0 90`);
    await tick.sprint(server, 2);
    evidence.round_trip = await addressRoundTrip(server, stage.at(target));
    expect(evidence.round_trip.aboard, "the chest's address is aboard the ship").toBe(true);
    expect(evidence.round_trip.back_to_storage, "the address leads back to the stored chest").toBe(true);
    expect(evidence.round_trip.world_off_by, "the address names where Sable shows the chest").toBeLessThan(1e-6);
    expect(evidence.round_trip.world_off_by).toBeGreaterThanOrEqual(0);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await testInfo.attach("sable-moving", { body: JSON.stringify(evidence, null, 2), contentType: "application/json" });
    await pair?.teardown(testInfo);
  }
});

async function countIn(server: any, chest: Vec) {
  const read = await world.container(server, chest, { dimension: "minecraft:overworld" });
  return (read.slots ?? []).filter((slot: any) => slot.id === ITEM).reduce((sum: number, slot: any) => sum + slot.count, 0);
}
