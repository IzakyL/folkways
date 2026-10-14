import { expect, test } from "@playwright/test";
import { tick } from "@izakyl/blockwright-minecraft";
import { countResidents, foundColonyFast, foundingBox, optInViaBook, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { cellLines, readColony, residentsAboard } from "../shared/colony/sable-probe";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { commandPos, tryCommand } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { aboard, SABLE_CONFIG } from "../shared/harness/stage";

const RESIDENTS = 2;
const ITEM = "minecraft:cobblestone";
const STOCK = 32;

test("a colony aboard a Sable ship comes back whole after the server saves, stops and starts again", async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");

  const runId = newRunId();
  const serverInstance = seededServer(`sable-reload-server-${runId}`);
  let pair: E2EPair | undefined;
  const stage = aboard();
  const evidence: any = {};
  try {
    pair = await launchPair({
      config: SABLE_CONFIG,
      // The world has to outlive this first server for the second to load it.
      server: { trace: tracePath("e2e", "sable-reload-server"), instance: serverInstance, keepInstanceDir: true },
      client: { trace: tracePath("e2e", "sable-reload-client"), instance: `sable-reload-client-${runId}` },
      snapshot: { entityTypes: ["folkways:resident"] },
    });
    let { server, client } = pair;

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    await tryCommand(server, `gamemode survival ${playerName}`);
    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    await stage.lay(server, foundingBox(origin));
    const founding = await foundColonyFast(server, client, playerName, { origin, residentCount: RESIDENTS, stage });
    const colonyId = String(founding.evidence.colonyId);

    const store: Vec = { x: origin.x + 8, y: origin.y, z: origin.z + 8 };
    await tryCommand(server, `setblock ${commandPos(stage.at(store))} minecraft:chest`);
    await tryCommand(server, `item replace block ${commandPos(stage.at(store))} container.0 with ${ITEM} ${STOCK}`);
    await tick.sprint(server, 5);
    evidence.opt_in = await optInViaBook(server, client, playerName, store, "chest", stage);
    // The player waits on the ground, so the next start does not set them down on the ship before it has loaded.
    await tryCommand(server, `tp ${playerName} ${origin.x + 0.5} ${origin.y} ${origin.z - 30.5}`);
    await tick.sprint(server, 20);

    const before = await readColony(server, colonyId, ITEM);
    evidence.before = before;
    evidence.aboard_before = await residentsAboard(server, colonyId);
    expect(before.cells.filter((cell) => cell.frame).length, "the colony holds its beds and stores aboard").toBeGreaterThan(0);
    await tryCommand(server, "save-all flush");
    await pair.teardown();

    pair = await launchPair({
      config: SABLE_CONFIG,
      server: { trace: tracePath("e2e", "sable-reload-server-again"), instance: serverInstance },
      client: { trace: tracePath("e2e", "sable-reload-client-again"), instance: `sable-reload-client-again-${runId}` },
      snapshot: { entityTypes: ["folkways:resident"] },
    });
    ({ server, client } = pair);
    await waitForGroundedPlayer(server);
    await tick.sprint(server, 40);

    const after = await readColony(server, colonyId, ITEM);
    evidence.after = after;
    expect(cellLines(after.cells), "every held cell came back aboard the same ship, at the same block").toEqual(cellLines(before.cells));
    expect(after.cells.filter((cell) => cell.unplaced), "no held cell points at a block the level no longer holds").toEqual([]);
    expect(after.stock, "the store's stock is counted again").toBe(STOCK);
    expect(await countResidents(server), "the residents came back").toBe(RESIDENTS);
    evidence.aboard_after = await residentsAboard(server, colonyId);
    expect(evidence.aboard_after.filter((one: any) => one.frame === stage.structure).length,
      `the residents are aboard the ship again: ${JSON.stringify(evidence.aboard_after)}`).toBe(RESIDENTS);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await testInfo.attach("sable-reload", { body: JSON.stringify(evidence, null, 2), contentType: "application/json" });
    await pair?.teardown(testInfo);
  }
});
