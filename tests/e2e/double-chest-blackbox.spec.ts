import { expect, test } from "@playwright/test";
import { player, screen, tick, world } from "@izakyl/blockwright-minecraft";
import { createColonyViaBook, isColonyMember, optInViaBook, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { clickElement } from "../ldlib2";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { waitForAnyScreen, waitForElement, safeAction, safeScreen, screenOpen, isSafeFailure, commandPos, tryCommand, softly } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { around, twins } from "../shared/harness/stage";

const BOOK_ITEM = "folkways:colony_book";
const TOGGLE_WIDGET = "folkways.member.toggle";
const PANEL_WIDGET = "folkways.site.panel";
const TOGGLE_TICKS = 200;

for (const stage of twins()) test(stage.title("either half of every double chest the colony holds opens that chest's window"), async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");

  const runId = newRunId();
  let pair: E2EPair | undefined;
  let server: any;
  let client: any;
  let chest: Vec | undefined;
  const evidence: any = {};

  try {
    pair = await launchPair({
      config: stage.config,
      server: { trace: tracePath("e2e", stage.name("double-chest-server")), instance: seededServer(stage.name(`double-chest-server-${runId}`)) },
      client: { trace: tracePath("e2e", stage.name("double-chest-client")), instance: stage.name(`double-chest-client-${runId}`) },
      snapshot: () => ({ containers: [chest && stage.at(chest)] }),
    });
    ({ server, client } = pair);

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    await tryCommand(server, `gamemode survival ${playerName}`);

    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    const standBlock: Vec = { x: origin.x - 4, y: origin.y, z: origin.z };
    chest = { x: origin.x, y: origin.y, z: origin.z };
    evidence.chest = chest;

    await tryCommand(server, `fill ${origin.x - 8} ${origin.y} ${origin.z - 8} ${origin.x + 8} ${origin.y + 4} ${origin.z + 8} minecraft:air`);
    await stage.lay(server, around([origin], 8, 4));
    await tryCommand(server, "gamerule doDaylightCycle false");
    await tryCommand(server, "time set day");

    await tryCommand(server, `item replace entity ${playerName} weapon.mainhand with ${BOOK_ITEM}`);
    await tick.sprint(server, 5);
    evidence.create = await createColonyViaBook(server, client, playerName, standBlock, stage);

    // Two double chests, taken in one after the other: each half of each must open that chest's window.
    const first: Vec[] = [{ x: origin.x, y: origin.y, z: origin.z }, { x: origin.x + 1, y: origin.y, z: origin.z }];
    const second: Vec[] = [{ x: origin.x + 3, y: origin.y, z: origin.z }, { x: origin.x + 4, y: origin.y, z: origin.z }];
    for (const pair of [first, second]) {
      await tryCommand(server, `setblock ${commandPos(stage.at(pair[0]))} minecraft:chest[facing=south,type=right]`);
      await tryCommand(server, `setblock ${commandPos(stage.at(pair[1]))} minecraft:chest[facing=south,type=left]`);
    }
    await tick.sprint(server, 5);
    evidence.opt_in_first = await optInViaBook(server, client, playerName, first[0], "chest", stage);
    evidence.opt_in_second = await optInViaBook(server, client, playerName, second[0], "chest", stage);

    evidence.halves = [];
    for (const half of [...first, ...second]) {
      const seen = stage.stand(half);
      await tryCommand(server, `tp ${playerName} ${seen.x + 0.5} ${seen.y} ${seen.z + 1.5} 180 30`);
      await tick.sprint(server, 5);
      await screen.dismiss(client);
      await safeAction(() => player.useBlock(client, stage.at(half), { face: "up" }));
      await waitForAnyScreen(client, 10_000);
      await tick.sprint(server, 5);
      const shown = await safeAction(() => waitForElement(client, { id: PANEL_WIDGET }, { timeoutMs: 6_000 }));
      evidence.halves.push({ half, member: await isColonyMember(server, half, stage), panel: !isSafeFailure(shown) });
    }
    expect(evidence.halves.map((one: any) => one.panel)).toEqual([true, true, true, true]);

    await screen.dismiss(client);
    testInfo.attach(stage.name("double-chest"), { body: JSON.stringify(evidence, null, 2), contentType: "application/json" });
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

function memberCondition(chest: Vec, member: boolean): string {
  return `${member ? "if" : "unless"} data block ${commandPos(chest)} "neoforge:attachments"."folkways:colony_member"`;
}
