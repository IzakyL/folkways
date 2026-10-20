import { expect, test } from "@playwright/test";
import { openOrderDraft } from "../shared/colony/membership-optin";
import { input, media, screen, tick } from "@izakyl/blockwright-minecraft";
import { clickElement, panelElements } from "../ldlib2";
import { boardActs } from "../shared/ui/site-panel";
import { writeFileSync } from "node:fs";
import { createColonyViaBook, optInViaBook, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { drawTexts, sleepMs, type DrawCaptureResult } from "../shared/capture/draw-capture";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, outAbs, shotPath, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { frames, safeAction, safeScreen, screenOpen, commandPos, tryCommand } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";

const CONFIG_PATH = "blockwright.toml";

const SYNC_ROUNDS = 12;

test("dragging an EMI index cell onto the order draft's item button names that item", async ({}, testInfo) => {
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
      server: { trace: tracePath("e2e", "emi-drag-server"), instance: seededServer(`emi-drag-server-${runId}`) },
      client: { trace: tracePath("e2e", "emi-drag-client"), instance: `emi-drag-client-${runId}` },
      snapshot: () => ({ containers: chest ? [chest] : [] }),
    });
    ({ server, client } = pair);

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    const origin: Vec = {
      x: Math.round(grounded.pos.x) + 4,
      y: Math.round(grounded.pos.y),
      z: Math.round(grounded.pos.z) + 4,
    };
    await flattenPlot(server, origin);
    await tryCommand(server, `gamemode survival ${playerName}`);

    const standBlock: Vec = { x: origin.x, y: origin.y, z: origin.z };
    await tryCommand(server, `give ${playerName} folkways:colony_book`);
    await tryCommand(server, `item replace entity ${playerName} weapon.mainhand with folkways:colony_book`);
    await tick.sprint(server, 5);
    evidence.found = await createColonyViaBook(server, client, playerName, standBlock);

    chest = { x: origin.x - 2, y: origin.y, z: origin.z };
    await tryCommand(server, `setblock ${commandPos(chest)} minecraft:chest{Items:[]}`);
    await tick.sprint(server, 3);
    evidence.opt_in = await optInViaBook(server, client, playerName, chest, "chest");

    await tryCommand(server, `tp ${playerName} ${chest.x + 0.5} ${chest.y} ${chest.z + 1.5} 180 30`);
    await tick.sprint(server, 5);
    await screen.dismiss(client);
    evidence.enter = await openOrderDraft(server, client, chest);
    expect(screenOpen(await safeScreen(client)), "this site's panel should be open").toBe(true);
    evidence.panel_elements = (await panelElements(client).catch(() => []))
      .map((element) => element.id ?? element.className);
    const elements = await panelElements(client);
    const button = elements.find((element) => element.id === evidence.enter.edit_item);
    expect(button, "the order draft should have an item button").toBeTruthy();
    const draftRow = elements.find((element) => element.id === evidence.enter.row_id);
    expect(draftRow, "the order draft's row should be on the panel").toBeTruthy();
    const sitePanel = elements.find((element) => element.id === "folkways.site.panel");
    expect(sitePanel, "this site's panel should be in its window").toBeTruthy();
    const dropTarget = {
      x: button!.x + button!.width / 2,
      y: button!.y + button!.height / 2,
    };
    evidence.drop_target = dropTarget;

    evidence.row_text_before = await controlText(client, evidence.enter.edit_item);

    const found = await findHoveredIndexCell(client, sitePanel!.x + sitePanel!.width, dropTarget);
    evidence.cell_search = found.search;
    expect(found.cell, "EMI's index should have at least one cell that shows a tooltip on hover").toBeTruthy();
    const dragSource = found.cell!;
    evidence.drag_source = dragSource;
    evidence.hovered_tooltip = found.tooltip;
    const draggedId = `minecraft:${found.tooltip[0].toLowerCase().replace(/ /g, "_")}`;
    const draggedName = found.tooltip[0];
    evidence.dragged_id = draggedId;
    expect(evidence.row_text_before, "the draft should not name the item yet").not.toContain(draggedName);

    evidence.shot_before_drag = await shoot(client, shotPath("e2e", "emi-drag-before"));

    const dragReach = await input.drag(client, { from: dragSource, to: dropTarget, steps: 12 });
    evidence.drag_reach = dragReach;
    expect(dragReach.pressArmed, "the press must set MouseHandler's drag state").toBe(true);
    expect(dragReach.dispatched, "every step must actually move the cursor").toBeGreaterThan(0);

    // The readable order form paints the selection as text; its old icon is deliberately hidden.
    const named = await waitForDraftText(server, client, evidence.enter.edit_item, draggedName);
    evidence.row_text_after = named;
    expect(named, "after the EMI drop the order draft should name the dragged item").toContain(draggedName);
    evidence.shot_after_drag = await shoot(client, shotPath("e2e", "emi-drag-after"));

    const submit = (await boardActs(client, "do", "folkways.orders"))
      .find((act) => act.row > evidence.enter.row && act.act === 0);
    expect(submit, "the draft should offer a Maintain action").toBeTruthy();
    await clickElement(client, { id: submit!.id }, { scrollIntoView: true });
    await expect.poll(async () => {
      await tick.sprint(server, 10);
      await frames(client, 3);
      const actions = (await boardActs(client, "do", "folkways.orders")).filter(act => act.row > submit!.row);
      const words = (await Promise.all(actions.map(act =>
        controlText(client, `folkways.board.row.folkways.orders.${act.row}`)))).flat();
      evidence.order_text = words;
      return words;
    }, { message: "the dropped item must be filed as an order", timeout: 10_000 }).toContain(draggedName);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    const body = JSON.stringify(evidence, null, 2);
    writeFileSync(outAbs("e2e", "reports", "emi-drag-evidence.json"), body);
    testInfo.attach("emi-drag-evidence", { body, contentType: "application/json" });
    await pair?.teardown(testInfo);
  }
});

async function shoot(client: any, rel: string) {
  const shot = await safeAction(async () => {
    await media.screenshot(client, { path: rel });
    return { status: "captured" };
  }).catch((error: any) => ({ status: "error", reason: String(error?.message ?? error) }));
  return { path: rel, status: (shot as any)?.status };
}

async function captureHud(client: any): Promise<DrawCaptureResult> {
  const recording = await media.startDraws(client, { channel: "hud", maxFrames: 4 });
  await frames(client, 3);
  return (await recording.stop()) as DrawCaptureResult;
}

async function findHoveredIndexCell(
  client: any,
  panelRight: number,
  parkAt: { x: number; y: number },
) {
  const parked = await input.move(client, parkAt);
  const width = parked.cursor.guiScaledWidth;
  const baseline = textLines(await captureHud(client));
  const search: any[] = [];
  for (let y = 40; y <= 220; y += 18) {
    for (let x = width - 14; x > panelRight + 18; x -= 18) {
      await input.move(client, { x, y });
      const capture = await captureHud(client);
      const added = [...textLines(capture)].filter((line) => !baseline.has(line));
      search.push({ x, y, added: added.slice(0, 3) });
      if (added.length > 0) {
        return { cell: { x, y }, tooltip: added, search };
      }
    }
  }
  return { cell: undefined, tooltip: [], search };
}

function textLines(capture: DrawCaptureResult): Set<string> {
  return new Set(drawTexts(capture).map((entry) => entry.text));
}

async function controlText(client: any, id: string): Promise<string[]> {
  const elements = await panelElements(client);
  const root = elements.find(element => element.id === id);
  if (!root) return [];
  const words: string[] = [];
  for (let at = root.index + 1; at < elements.length && elements[at].depth > root.depth; at++) {
    const element = elements[at];
    if (element.displayed && element.text?.trim()) words.push(element.text);
  }
  return words;
}

async function waitForDraftText(
  server: any, client: any, id: string, name: string,
) {
  let words: string[] = [];
  for (let round = 0; round < SYNC_ROUNDS; round++) {
    await input.move(client, { x: 0, y: 0 });
    words = await controlText(client, id);
    if (words.includes(name)) {
      return words;
    }
    await sleepMs(500);
    await tick.sprint(server, 5);
  }
  return words;
}

async function flattenPlot(server: any, origin: Vec) {
  const minX = origin.x - 6;
  const maxX = origin.x + 6;
  const minZ = origin.z - 6;
  const maxZ = origin.z + 6;
  await tryCommand(server, `fill ${minX} ${origin.y} ${minZ} ${maxX} ${origin.y + 5} ${maxZ} minecraft:air`);
  await tryCommand(
    server,
    `fill ${minX} ${origin.y - 1} ${minZ} ${maxX} ${origin.y - 1} ${maxZ} minecraft:smooth_stone`,
  );
  await tryCommand(server, `setblock ${commandPos(origin)} minecraft:smooth_stone`);
}
