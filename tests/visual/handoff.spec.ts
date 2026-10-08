import { launchPair, type E2EPair } from "../shared/pair";
import { expect, test } from "@playwright/test";
import { screen, tick, world, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { createColonyViaBook, waitForGroundedPlayer, type Vec } from "../e2e/colony-founding";
import {
  HANDOFF_LABEL,
  SCHEMATIC_ITEM,
  deployedSchematic,
  equipHandoffTool,
  hudTexts,
  HANDOFF_WINDOW,
  openHandoffScreen,
  rightClickWorld,
} from "../e2e/schematic-tools";
import { drainActionBar, readActionBar } from "../e2e/zone-receipt";
import { clickElement, panelElements } from "../ldlib2";
import { CONFIG_PATH, VisualRun } from "./visual-kit";
import { LayoutProbe } from "./layout-lint";
import { tracePath } from "../out-paths";
import { seededOffThreadServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { frames, safeAction, safeScreen, screenName, screenOpen, tryCommand, clickUnverified, softly } from "../shared/bw-helpers";
import { newRunId } from "../shared/run-id";

const BOOK_ITEM = "folkways:colony_book";
const SCHEMATIC_FILE = "folkways-handoff.nbt";

const AIR = "folkways.blueprint.handoff.air";
const CONFIRM = "folkways.blueprint.handoff.confirm";

const LABEL = HANDOFF_LABEL;
const TITLE = "Have the colony build it";
const DESCRIPTION_0 = "Hands this schematic to the colony whose book you hold";
const DESCRIPTION_1 = "Right-Click to place the build order";
const AIR_HEADING = "Cells the blueprint leaves empty";
const AIR_KEEP = "Leave what stands there";
const AIR_EXCAVATE = "Dig out what stands there";
const CONFIRM_LABEL = "Place the build order";
const UNREADABLE = "That schematic file will not read";
const NO_BOOK = "Hold the colony's book in your other hand";

test("the hand-off entry in Create's row of schematic tools", async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  const run = new VisualRun("handoff");
  const runId = newRunId();
  let pair: E2EPair | undefined;
  let server: MinecraftServer;
  let client: MinecraftClient;
  try {
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: tracePath("visual", "handoff-server"), instance: seededOffThreadServer(`visual-handoff-server-${runId}`) },
      client: { trace: tracePath("visual", "handoff-client"), instance: `visual-handoff-client-${runId}` },
    });
    ({ server, client } = pair);

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    const origin: Vec = {
      x: Math.round(grounded.pos.x) + 4,
      y: Math.round(grounded.pos.y),
      z: Math.round(grounded.pos.z) + 4,
    };
    await tryCommand(server, `op ${playerName}`);
    await world.command(server, `gamemode creative ${playerName}`);
    await tryCommand(server, "gamerule doDaylightCycle false");
    await tryCommand(server, "time set day");

    const stand: Vec = { x: origin.x, y: origin.y, z: origin.z };
    await tryCommand(server, `fill ${stand.x - 3} ${stand.y} ${stand.z - 3} ${stand.x + 3} ${stand.y + 4} ${stand.z + 3} minecraft:air`);
    await world.command(server, `tp ${playerName} ${stand.x + 0.5} ${stand.y} ${stand.z + 1.5} 180 10`);
    await tick.sprint(server, 5);

    await tryCommand(server, `give ${playerName} ${BOOK_ITEM}`);
    await world.command(server, `item replace entity ${playerName} weapon.mainhand with ${BOOK_ITEM}`);
    await tick.sprint(server, 5);
    const founding = await createColonyViaBook(server, client, playerName, stand);
    await safeAction(() => screen.dismiss(client));

    await world.command(server, `item replace entity ${playerName} weapon.offhand from entity ${playerName} weapon.mainhand`);
    await world.command(server, `item replace entity ${playerName} weapon.mainhand with minecraft:air`);
    await tick.sprint(server, 3);
    const bookInOffhand = await world.probe(server, `if items entity ${playerName} weapon.offhand ${BOOK_ITEM}`);
    expect(bookInOffhand, "the book did not reach the offhand, so every handoff query would answer \"no book in hand\"").toBe(true);
    run.note("colony", { origin, stand, founding, book_in_offhand: bookInOffhand });

    const anchor: Vec = { x: origin.x + 3, y: origin.y, z: origin.z + 3 };
    const schematic = deployedSchematic(SCHEMATIC_FILE, playerName, anchor, { x: 3, y: 3, z: 3 });
    await world.command(server, `item replace entity ${playerName} weapon.mainhand with ${schematic}`);
    await tick.sprint(server, 10);
    await safeAction(() => frames(client, 20));

    const idle = await hudTexts(client);
    run.note("row_unfocused", { texts: idle });
    await run.shot(client, "01-tool-row", {
      subject: "Holding a placed schematic: Create's tool row is drawn above the bottom of the screen, with the folkways entry last",
      worldState: { gui_scale: 1, schematic_file: SCHEMATIC_FILE, schematic_file_on_disk: false, drawn: idle },
      hard: true,
    });

    let focused: string[] = [];
    const equip = await equipHandoffTool(client, {
      whileFocused: async () => {
        focused = await hudTexts(client);
        await run.shot(client, "02-tool-row-focused", {
          subject: "Holding the tool menu key and scrolling to the folkways entry: it is selected, its name under the icon and its description below the row",
          worldState: { selected: LABEL, drawn: focused },
          hard: true,
        });
      },
    });
    run.note("equip", { ...equip, texts: focused });
    expectDrawn(focused, LABEL, "the row does not show the folkways entry's name: it is not in getTools, or a different entry is selected");
    expectDrawn(focused, DESCRIPTION_0, "the entry is selected, but its first description line is missing below the row");
    expectDrawn(focused, DESCRIPTION_1, "the entry is selected, but its second description line is missing below the row");

    const opened = await openHandoffScreen(client);
    expect(
      opened.window,
      `Right-clicking after selecting the entry did not open the folkways config page: ${HANDOFF_WINDOW} is not on the panel, `
        + `the screen was ${JSON.stringify(screenName(opened.screen))}`,
    ).toBeTruthy();

    const shown = await safeScreen(client);
    const size = screenSize(shown);
    expect(size, "the screen did not report its size, so there is no way to tell whether controls are on screen").toBeTruthy();
    const layout = new LayoutProbe(client);
    const elements = await panelElements(client);
    run.note("screen", {
      name: screenName(opened.screen),
      size,
      elements: elements.map((e) => ({ id: e.id, x: e.x, y: e.y, width: e.width, height: e.height })),
    });
    for (const token of [AIR, CONFIRM]) {
      expect(
        elements.find((entry) => entry.id === token),
        `The config page has no ${token}. It has: ${JSON.stringify(elements.map((e) => e.id).filter(Boolean))}`,
      ).toBeTruthy();
    }
    await run.lint(layout, "panel:handoff");

    const keeping = await panelTexts(client);
    run.note("screen_default", { texts: keeping });
    expectDrawn(keeping, TITLE, "the config page does not draw its title");
    expectDrawn(keeping, AIR_HEADING, "the config page does not draw what the toggle is about");
    expectDrawn(keeping, AIR_KEEP, "the toggle does not default to \"keep as is\"");
    expectDrawn(keeping, CONFIRM_LABEL, "the config page does not draw the confirm button label");
    await run.shot(client, "03-handoff-screen", {
      subject: "Handoff config page: one toggle for whether blocks left empty in the blueprint are dug out or kept, defaulting to keep",
      worldState: { air: AIR_KEEP, drawn: keeping },
      hard: true,
    });

    const toggled = await softly(() => clickElement(client, { id: AIR }));
    await safeAction(() => frames(client, 3));
    const excavating = await panelTexts(client);
    run.note("screen_excavate", { click: toggled, texts: excavating });
    expectDrawn(excavating, AIR_EXCAVATE, "after pressing the toggle it still shows the old line");
    await run.shot(client, "04-handoff-screen-excavate", {
      subject: "Same toggle pressed once: this time blocks left empty in the blueprint get dug out",
      worldState: { air: AIR_EXCAVATE, drawn: excavating },
      hard: true,
    });
    run.expectLinted(["panel:handoff"]);
    run.expectLintClean();

    await drainToZero(client);
    await clickUnverified(client, { id: CONFIRM });
    await safeAction(() => frames(client, 5));
    const afterConfirm = await safeScreen(client);
    expect(screenOpen(afterConfirm), "the config page did not close after confirming").toBe(false);
    const receipt = await readActionBar(client);
    run.note("receipt_with_book", receipt);
    expect(
      receipt.text,
      "folkways did not reply with this line after confirming. Empty string = nobody handled the click; a different line = it was handled but took another branch, " +
        "so read that line itself",
    ).toBe(UNREADABLE);

    await world.command(server, `item replace entity ${playerName} weapon.offhand with minecraft:air`);
    await tick.sprint(server, 10);
    await safeAction(() => frames(client, 10));
    await drainToZero(client);
    await rightClickWorld(client);
    await safeAction(() => frames(client, 5));
    const refused = await readActionBar(client);
    const stillClosed = await safeScreen(client);
    run.note("receipt_without_book", { bar: refused, screen: screenName(stillClosed) });
    expect(
      refused.text,
      `With the offhand empty, right-click did not reply with this line. Replying ${JSON.stringify(UNREADABLE)} = it still read the book`,
    ).toBe(NO_BOOK);
    expect(screenOpen(stillClosed), "no book in hand, but the config page still opened").toBe(false);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

function expectDrawn(texts: string[], line: string, why: string) {
  expect(texts, `${why}. This frame drew: ${JSON.stringify(texts)}`).toContain(line);
}

async function panelTexts(client: MinecraftClient): Promise<string[]> {
  return (await panelElements(client))
    .map((element: any) => String(element.text ?? ""))
    .filter((text: string) => text.length > 0);
}

async function drainToZero(client: MinecraftClient) {
  const drained = await drainActionBar(client, 10_000);
  if (drained.remaining_ticks > 0) {
    throw new Error(`The previous receipt has not expired (${drained.remaining_ticks} ticks left: ${drained.text}), so this one cannot be read`);
  }
}

function screenSize(result: unknown): { width: number; height: number } | null {
  const info = (result as { screen?: { width?: number | null; height?: number | null } } | null)?.screen;
  return typeof info?.width === "number" && typeof info?.height === "number"
    ? { width: info.width, height: info.height }
    : null;
}
