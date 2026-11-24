import { expect, test } from "@playwright/test";
import { screen, tick, world } from "@izakyl/blockwright-minecraft";
import { createColonyViaBook, isColonyMember, optInViaBook, setBookGesture, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { facilityAt, openBlockCard } from "../shared/ui/book-cards";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { commandPos, tryCommand } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { around, twins } from "../shared/harness/stage";

const BOOK_ITEM = "folkways_core:colony_book";

for (const stage of twins()) test(stage.title("both halves open the same chest card and point mode toggles membership"), async ({}, testInfo) => {
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
      server: { trace: tracePath("e2e", stage.name("member-toggle-server")), instance: seededServer(stage.name(`member-toggle-server-${runId}`)) },
      client: { trace: tracePath("e2e", stage.name("member-toggle-client")), instance: stage.name(`member-toggle-client-${runId}`) },
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

    const otherHalf: Vec = { x: origin.x + 1, y: origin.y, z: origin.z };
    await tryCommand(server, `setblock ${commandPos(stage.at(chest))} minecraft:chest[facing=south,type=right]`);
    await tryCommand(server, `setblock ${commandPos(stage.at(otherHalf))} minecraft:chest[facing=south,type=left]`);
    await tick.sprint(server, 5);

    evidence.member_initial = await isColonyMember(server, chest, stage);
    expect(evidence.member_initial).toBe(false);
    // A chest outside the colony has no window of its own: the book takes it in from the world first.
    evidence.opt_in = await optInViaBook(server, client, playerName, chest, "chest", stage);
    expect(await isColonyMember(server, chest, stage)).toBe(true);

    const facility = await facilityAt(server, playerName, stage.at(chest));
    expect(facility).toBeTruthy();
    expect(await facilityAt(server, playerName, stage.at(otherHalf))).toBe(facility);
    for (const half of [otherHalf, chest]) {
      await openBlockCard(server, client, playerName, half, stage);
    }
    await screen.dismiss(client);
    await setBookGesture(server, client, playerName, "point");
    for (const [half, member] of [[otherHalf, false], [chest, true]] as const) {
      const seen = stage.stand(half);
      await tryCommand(server, `tp ${playerName} ${seen.x + 0.5} ${seen.y} ${seen.z + 1.5} 180 30`);
      await tick.sprint(server, 5);
      await stage.click({ server, client }, half, { player: playerName, button: "left" });
      await expect.poll(async () => {
        await tick.sprint(server, 5);
        return isColonyMember(server, chest!, stage);
      }).toBe(member);
    }

    await screen.dismiss(client);
    testInfo.attach(stage.name("member-toggle"), { body: JSON.stringify(evidence, null, 2), contentType: "application/json" });
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
