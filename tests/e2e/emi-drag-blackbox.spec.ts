import { expect, test } from "@playwright/test";
import { openOrderDraft } from "./membership-optin";
import { input, media, screen, tick } from "@izakyl/blockwright-minecraft";
import { panelElements } from "../ldlib2";
import { writeFileSync } from "node:fs";
import { createColonyViaBook, optInViaBook, waitForGroundedPlayer, type Vec } from "./colony-founding";
import { drawItems, drawTexts, sleepMs, type DrawCaptureResult } from "./draw-capture";
import { launchPair, type E2EPair } from "../shared/pair";
import { ensureOut, outAbs, shotPath, tracePath } from "../out-paths";
import { seededServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { frames, safeAction, safeScreen, screenOpen, commandPos, tryCommand } from "../shared/bw-helpers";
import { newRunId } from "../shared/run-id";

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

    await tryCommand(server, `item replace entity ${playerName} weapon.mainhand with minecraft:air`);
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
    const rowBounds = { x: draftRow!.x, y: draftRow!.y, width: draftRow!.width, height: draftRow!.height };
    const sitePanel = elements.find((element) => element.id === "folkways.site.panel");
    expect(sitePanel, "this site's panel should be next to the chest").toBeTruthy();
    const dropTarget = {
      x: button!.x + button!.width / 2,
      y: button!.y + button!.height / 2,
    };
    evidence.drop_target = dropTarget;

    const before = await captureHud(client);
    evidence.row_icons_before = iconsIn(before, rowBounds);

    const found = await findHoveredIndexCell(client, sitePanel!.x + sitePanel!.width, dropTarget);
    evidence.cell_search = found.search;
    expect(found.cell, "EMI's index should have at least one cell that shows a tooltip on hover").toBeTruthy();
    const dragSource = found.cell!;
    evidence.drag_source = dragSource;
    evidence.hovered_tooltip = found.tooltip;
    const draggedId = `minecraft:${found.tooltip[0].toLowerCase().replace(/ /g, "_")}`;
    evidence.dragged_id = draggedId;
    expect(evidence.row_icons_before.map((icon: any) => icon.id), "the draft should not name the item yet")
      .not.toContain(draggedId);

    evidence.shot_before_drag = await shoot(client, shotPath("e2e", "emi-drag-before"));

    const dragReach = await input.drag(client, { from: dragSource, to: dropTarget, steps: 12 });
    evidence.drag_reach = dragReach;
    expect(dragReach.pressArmed, "the press must set MouseHandler's drag state").toBe(true);
    expect(dragReach.dispatched, "every step must actually move the cursor").toBeGreaterThan(0);

    evidence.shot_after_drag = await shoot(client, shotPath("e2e", "emi-drag-after"));

    const named = await waitForDraftIcon(server, client, rowBounds, draggedId);
    evidence.row_icons_after = named;
    expect(named.map((icon) => icon.id), "after the EMI drop the order draft should name the dragged item")
      .toContain(draggedId);
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

function iconsIn(capture: DrawCaptureResult, rect: { x: number; y: number; width: number; height: number }) {
  const seen = new Map<string, { id: string; x: number; y: number }>();
  for (const item of drawItems(capture)) {
    const inside = item.x >= rect.x && item.x < rect.x + rect.width
      && item.y >= rect.y && item.y < rect.y + rect.height;
    if (inside && !seen.has(item.id)) {
      seen.set(item.id, { id: item.id, x: item.x, y: item.y });
    }
  }
  return [...seen.values()];
}

async function waitForDraftIcon(
  server: any, client: any, rect: { x: number; y: number; width: number; height: number }, id: string,
) {
  let icons: { id: string; x: number; y: number }[] = [];
  for (let round = 0; round < SYNC_ROUNDS; round++) {
    await input.move(client, { x: 0, y: 0 });
    icons = iconsIn(await captureHud(client), rect);
    if (icons.some((icon) => icon.id === id)) {
      return icons;
    }
    await sleepMs(500);
    await tick.sprint(server, 5);
  }
  return icons;
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
