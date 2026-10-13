import { expect, test } from "@playwright/test";
import { player, screen, tick, world } from "@izakyl/blockwright-minecraft";
import { createColonyViaBook, isColonyMember, optInViaBook, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { clickElement } from "../ldlib2";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { waitForAnyScreen, waitForElement, safeAction, safeScreen, screenOpen, commandPos, tryCommand, softly } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";

const CONFIG_PATH = "blockwright.toml";

const BOOK_ITEM = "folkways:colony_book";
const TOGGLE_WIDGET = "folkways.member.toggle";
const PANEL_WIDGET = "folkways.site.panel";
const TOGGLE_TICKS = 200;

test("the chest window's member toggle takes a container out of the colony and back in", async ({}, testInfo) => {
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
      config: CONFIG_PATH,
      server: { trace: tracePath("e2e", "member-toggle-server"), instance: seededServer(`member-toggle-server-${runId}`) },
      client: { trace: tracePath("e2e", "member-toggle-client"), instance: `member-toggle-client-${runId}` },
      snapshot: () => ({ containers: [chest] }),
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
    await tryCommand(server, "gamerule doDaylightCycle false");
    await tryCommand(server, "time set day");

    await tryCommand(server, `item replace entity ${playerName} weapon.mainhand with ${BOOK_ITEM}`);
    await tick.sprint(server, 5);
    evidence.create = await createColonyViaBook(server, client, playerName, standBlock);

    await tryCommand(server, `setblock ${commandPos(chest)} minecraft:chest{Items:[]}`);
    await tick.sprint(server, 5);

    evidence.member_initial = await isColonyMember(server, chest);
    expect(evidence.member_initial).toBe(false);
    // A chest outside the colony has no window of its own: the book takes it in from the world first.
    evidence.opt_in = await optInViaBook(server, client, playerName, chest, "chest");
    expect(await isColonyMember(server, chest)).toBe(true);

    await tryCommand(server, `tp ${playerName} ${chest.x + 0.5} ${chest.y} ${chest.z + 1.5} 180 30`);
    await tick.sprint(server, 5);
    await screen.dismiss(client);
    evidence.open = await safeAction(() =>
      player.useBlock(client, { x: chest!.x, y: chest!.y, z: chest!.z }, { face: "up" }),
    );
    await waitForAnyScreen(client, 10_000);
    if (!screenOpen(await safeScreen(client))) {
      throw new Error(`member-toggle: no window opened on the chest at ${commandPos(chest)}`);
    }
    await tick.sprint(server, 5);
    await waitForElement(client, { id: TOGGLE_WIDGET }, { timeoutMs: 10_000 });
    await waitForElement(client, { id: PANEL_WIDGET }, { timeoutMs: 10_000 });

    evidence.click_leave = await softly(() => clickElement(client, { id: TOGGLE_WIDGET }));
    await safeAction(() => world.waitFor(server, memberCondition(chest!, false), { maxTicks: TOGGLE_TICKS, stepTicks: 5 }));
    evidence.member_after_leave = await isColonyMember(server, chest);
    expect(evidence.member_after_leave).toBe(false);
    // Only a block the colony holds has a board: once the chest leaves there is the button alone.
    await waitForElement(client, { id: PANEL_WIDGET }, { state: "absent", timeoutMs: 10_000 });

    evidence.click_join = await softly(() => clickElement(client, { id: TOGGLE_WIDGET }));
    await safeAction(() => world.waitFor(server, memberCondition(chest!, true), { maxTicks: TOGGLE_TICKS, stepTicks: 5 }));
    evidence.member_after_join = await isColonyMember(server, chest);
    expect(evidence.member_after_join).toBe(true);
    await waitForElement(client, { id: PANEL_WIDGET }, { timeoutMs: 10_000 });

    await screen.dismiss(client);
    testInfo.attach("member-toggle", { body: JSON.stringify(evidence, null, 2), contentType: "application/json" });
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
