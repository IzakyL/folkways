import { expect, test } from "@playwright/test";
import { tick } from "@izakyl/blockwright-minecraft";
import { foundColonyFast, foundingBox, isColonyMember, optInViaBook, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { readColony } from "../shared/colony/sable-probe";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { commandPos, tryCommand } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { aboard, SABLE_CONFIG } from "../shared/harness/stage";

const ITEM = "minecraft:cobblestone";
const STOCK = 32;

test("a store assembled off a Sable ship into a ship of its own stays the colony's, at the new ship's addresses", async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");

  const runId = newRunId();
  let pair: E2EPair | undefined;
  const stage = aboard();
  let store: Vec | undefined;
  const evidence: any = {};
  try {
    pair = await launchPair({
      config: SABLE_CONFIG,
      server: { trace: tracePath("e2e", "sable-split-server"), instance: seededServer(`sable-split-server-${runId}`) },
      client: { trace: tracePath("e2e", "sable-split-client"), instance: `sable-split-client-${runId}` },
      snapshot: () => ({ containers: [store && stage.structure ? stage.at(store) : undefined], entityTypes: ["folkways:resident"] }),
    });
    const { server, client } = pair;

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    await tryCommand(server, `gamemode survival ${playerName}`);
    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    await stage.lay(server, foundingBox(origin));
    const founding = await foundColonyFast(server, client, playerName, { origin, residentCount: 2, stage });
    const colonyId = String(founding.evidence.colonyId);

    store = { x: origin.x + 8, y: origin.y, z: origin.z + 8 };
    await tryCommand(server, `setblock ${commandPos(stage.at(store))} minecraft:chest`);
    await tryCommand(server, `item replace block ${commandPos(stage.at(store))} container.0 with ${ITEM} ${STOCK}`);
    await tick.sprint(server, 5);
    evidence.opt_in = await optInViaBook(server, client, playerName, store, "chest", stage);
    await tryCommand(server, `tp ${playerName} ${origin.x + 0.5} ${origin.y} ${origin.z - 30.5}`);

    const before = await readColony(server, colonyId, ITEM);
    evidence.before = before;
    const storeAt = stage.at(store);
    const heldStore = before.cells.find((cell) => cell.x === storeAt.x && cell.y === storeAt.y && cell.z === storeAt.z);
    expect(heldStore?.frame, "the store is held aboard the first ship").toBe(stage.structure);

    // The store and the deck under it, cut out of the ship where the level stores it, become a ship of their own.
    const from = stage.at({ x: store.x - 1, y: store.y - 1, z: store.z - 1 });
    const to = stage.at({ x: store.x + 1, y: store.y, z: store.z + 1 });
    evidence.split = await tryCommand(server, `sable assemble area ${commandPos(from)} ${commandPos(to)}`);
    await tick.sprint(server, 5);

    const after = await readColony(server, colonyId, ITEM);
    evidence.after = after;
    const moved = after.cells.filter((cell) => cell.owner === heldStore!.owner && cell.frame && cell.frame !== stage.structure);
    expect(moved.length, `the store's holding moved to the new ship: ${JSON.stringify(after.cells)}`).toBe(1);
    expect(moved[0].unplaced, "its new address leads to a block the level holds").toBeUndefined();
    expect(after.cells.filter((cell) => cell.unplaced), "no held cell points at a block that is gone").toEqual([]);
    expect(after.cells.length, "nothing the colony held was dropped by the split").toBe(before.cells.length);
    expect(after.stock, "the colony still counts the store's stock on its new ship").toBe(STOCK);
    expect(await isColonyMember(server, { x: moved[0].x, y: moved[0].y, z: moved[0].z }), "the member mark went with the store").toBe(true);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await testInfo.attach("sable-split", { body: JSON.stringify(evidence, null, 2), contentType: "application/json" });
    await pair?.teardown(testInfo);
  }
});
