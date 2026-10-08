import { expect, test } from "@playwright/test";
import { player, screen, tick, world } from "@izakyl/blockwright-minecraft";
import { createColonyViaBook, isColonyMember, waitForGroundedPlayer, type Vec } from "./colony-founding";
import { launchPair, type E2EPair } from "../shared/pair";
import { ensureOut, tracePath } from "../out-paths";
import { seededServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { waitForAnyScreen, waitForElement, safeAction, safeScreen, screenOpen, commandPos, tryCommand, softly } from "../shared/bw-helpers";
import { newRunId } from "../shared/run-id";

const CONFIG_PATH = "blockwright.toml";

const BOOK_ITEM = "folkways:colony_book";
const TOGGLE_WIDGET = "folkways.member.toggle";
const PANEL_WIDGET = "folkways.site.panel";
const TOGGLE_TICKS = 200;

test("panel member toggle joins a container to the colony and leaves it again", async ({}, testInfo) => {
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

    await tryCommand(server, `item replace entity ${playerName} weapon.offhand from entity ${playerName} weapon.mainhand`);
    await tryCommand(server, `item replace entity ${playerName} weapon.mainhand with minecraft:air`);
    await tick.sprint(server, 5);
    evidence.book_in_offhand = await world.probe(server, `if items entity ${playerName} weapon.offhand ${BOOK_ITEM}`);
    evidence.mainhand_empty = !(await world.probe(server, `if items entity ${playerName} weapon.mainhand *`));
    expect(evidence.book_in_offhand).toBe(true);
    expect(evidence.mainhand_empty).toBe(true);

    evidence.member_initial = await isColonyMember(server, chest);
    expect(evidence.member_initial).toBe(false);

    await tryCommand(server, `tp ${playerName} ${chest.x + 0.5} ${chest.y} ${chest.z + 1.5} 180 30`);
    await tick.sprint(server, 5);
    await screen.dismiss(client);
    evidence.open = await safeAction(() =>
      player.useBlock(client, { x: chest!.x, y: chest!.y, z: chest!.z }, { face: "up" }),
    );
    await waitForAnyScreen(client, 10_000);
    if (!screenOpen(await safeScreen(client))) {
      throw new Error(`member-toggle: chest screen did not open at ${commandPos(chest)}`);
    }

    await screen.waitForWidget(client, { id: TOGGLE_WIDGET }, { timeoutMs: 10_000 });
    evidence.tokens_before = await widgetIds(client);
    expect(evidence.tokens_before).toContain(TOGGLE_WIDGET);
    await waitForElement(client, { id: PANEL_WIDGET }, { timeoutMs: 10_000 });

    evidence.click_join = await softly(() => screen.clickWidget(client, { id: TOGGLE_WIDGET }));
    await safeAction(() => world.waitFor(server, memberCondition(chest!, true), { maxTicks: TOGGLE_TICKS, stepTicks: 5 }));
    evidence.member_after_join = await isColonyMember(server, chest);
    expect(evidence.member_after_join).toBe(true);

    await waitForElement(client, { id: PANEL_WIDGET }, { timeoutMs: 10_000 });
    evidence.tokens_after_join = await widgetIds(client);
    expect(evidence.tokens_after_join).toContain(TOGGLE_WIDGET);

    evidence.click_leave = await softly(() => screen.clickWidget(client, { id: TOGGLE_WIDGET }));
    await safeAction(() => world.waitFor(server, memberCondition(chest!, false), { maxTicks: TOGGLE_TICKS, stepTicks: 5 }));
    evidence.member_after_leave = await isColonyMember(server, chest);
    expect(evidence.member_after_leave).toBe(false);

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

async function widgetIds(client: any): Promise<string[]> {
  return (await screen.widgets(client)).map((widget) => widget.id);
}
