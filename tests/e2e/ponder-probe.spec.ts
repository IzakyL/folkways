import { expect, test } from "@playwright/test";
import { existsSync, statSync, writeFileSync } from "node:fs";
import path from "node:path";
import {
  createColonyViaBook,
  openColonyPanelViaBook,
  tryCommand,
  safeScreen,
  screenName,
  waitForGroundedPlayer,
} from "./colony-founding";
import { input, media, reflect, screen, tick } from "@izakyl/blockwright-minecraft";
import { clickElement, hoverElement, panelElements, elementLabel, elementsAtPoint, LDLIB2_SYMBOLS } from "../ldlib2";
import { frames } from "../shared/bw-helpers";
import { launchPair, type E2EPair } from "../shared/pair";
import { REPO_ROOT, ensureOut, reportPath, shotPath, tracePath } from "../out-paths";
import { seededServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { newRunId } from "../shared/run-id";

const CONFIG_PATH = "blockwright.toml";
const REPORT_PATH = reportPath("e2e", "ponder-probe-report");
const PONDER_KEYBIND = "key.ponder.ponder";

// A tab plays the lesson its page names, or else the folder its plugin enrolled under the page's own id.
const LESSONS = [
  { lesson: "residents", tab: "folkways.tab.residents" },
  { lesson: "colony_panel", tab: "folkways.tab.metrics" },
  { lesson: "basics", tab: "folkways.tab.lessons" },
  { lesson: "person", tab: "folkways.page.person" },
  { lesson: "wares", tab: "folkways.page.wares" },
  { lesson: "build", tab: "folkways.page.build" },
  { lesson: "orders", tab: "folkways.panel.orders" },
  { lesson: "pasture", tab: "folkways.page.pasture" },
] as const;

// The Lessons page: a folder per plugin, a row per scene in it.
const SHELF = {
  tab: "folkways.tab.lessons",
  folder: "folkways.lessons.folder.folkways.farming",
  scene: "folkways.lessons.scene.folkways.farming",
} as const;

test("every colony panel tab opens its own ponder lesson", async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");

  const runId = newRunId();
  let pair: E2EPair | undefined;
  const report: any = {
    generated_at: new Date().toISOString(),
    scope: "panel tab hold-to-ponder: hover prompt + PonderUI opening on the tab's scene",
    traces: { server: tracePath("e2e", "ponder-probe-server"), client: tracePath("e2e", "ponder-probe-client") },
    shots: {},
  };

  let server: any;
  let client: any;
  try {
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: report.traces.server, instance: seededServer(`ponder-server-${runId}`) },
      client: { trace: report.traces.client, instance: `ponder-client-${runId}` },
    });
    ({ server, client } = pair);

    const symbols = await reflect.symbols(client, LDLIB2_SYMBOLS);
    report.ldlib2_symbols = symbols.missing === 0 ? "ok" : symbols.symbols.filter((symbol) => !symbol.present);
    expect(symbols.missing, `LDLib2 symbols missing from this build: ${JSON.stringify(report.ldlib2_symbols)}`).toBe(0);

    const grounded = await waitForGroundedPlayer(server);
    report.player = { name: grounded.name, pos: grounded.pos };
    await tryCommand(server, "difficulty peaceful");

    report.open_command = await tryCommand(server, `ponder folkways:colony_book ${grounded.name}`);
    await realWait(3_000);
    await frames(client, 2);
    report.screen_book = screenName(await safeScreen(client));
    report.shots.book = await shot(client, shotPath("e2e", "ponder-book"));
    await screen.dismiss(client);

    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    const standBlock = { x: origin.x - 4, y: origin.y, z: origin.z };
    report.stand_block = standBlock;
    await tryCommand(server, `fill ${origin.x - 8} ${origin.y} ${origin.z - 8} ${origin.x + 8} ${origin.y + 4} ${origin.z + 8} minecraft:air`);
    await tryCommand(server, `give ${grounded.name} folkways:colony_book`);
    await tryCommand(server, `item replace entity ${grounded.name} weapon.mainhand with folkways:colony_book`);
    await tick.sprint(server, 5);
    report.create = await createColonyViaBook(server, client, grounded.name, standBlock);
    report.lessons = [];
    for (const { lesson, tab: token } of LESSONS) {
      const slug = token.replace(/^folkways\./, "").replace(/\./g, "-");
      const entry: any = { lesson, tab: token, shots: {} };
      report.lessons.push(entry);

      await screen.dismiss(client);
      await tick.sprint(server, 5);
      await openColonyPanelViaBook(server, client, grounded.name, standBlock);

      entry.screen_panel = screenName(await safeScreen(client));
      const dumpStarted = Date.now();
      const dump = await panelElements(client);
      entry.panel_dump = { nodes: dump.length, ms: Date.now() - dumpStarted };

      entry.hover = await hoverNavPage(client, token);
      entry.tab_bounds = entry.hover.entry;
      entry.shots.hover = await shot(client, shotPath("e2e", `ponder-hint-hover-${slug}`));

      await input.key(client, { keybind: PONDER_KEYBIND, action: "press" });
      await realWait(2_500);
      await frames(client, 2);
      entry.screen_after_hold = screenName(await safeScreen(client));
      entry.shots.opened = await shot(client, shotPath("e2e", `ponder-hint-opened-${slug}`));
      await input.key(client, { keybind: PONDER_KEYBIND, action: "release" });

      await realWait(1_500);
      await frames(client, 2);
      entry.screen_settled = screenName(await safeScreen(client));
      writeFileSync(path.join(REPO_ROOT, REPORT_PATH), JSON.stringify(report, null, 2) + "\n");

      expect(entry.shots.hover.bytes, `${token}: hover screenshot is empty`).toBeGreaterThan(0);
      expect(entry.hover.landed_on,
        `${token}: the cursor is not over this row; that pixel belongs to ${JSON.stringify(entry.hover.owners)}. `
          + `Read the hover image first; leave the lesson side alone`)
        .toContain(token);
      expect(entry.hover.landed_on.filter((id: string) => id !== token),
        `${token}: this pixel also lands on other nav entries (${JSON.stringify(entry.hover.landed_on)}): `
          + `clipping is already accounted for, so two rows really overlap on screen; the coordinates are not lying. `
          + `Hit: ${JSON.stringify(entry.hover)}`)
        .toEqual([]);
      expect(entry.screen_after_hold,
        `${token}: holding the ponder key did not open a lesson (got ${entry.screen_after_hold}). `
          + `folkways:${lesson} has no scene bound: the page names no lesson and its plugin declared no folder under the page id, or the folder is empty.`)
        .toMatch(/LessonScreen/);
      expect(entry.screen_settled,
        `${token}: releasing the ponder key closed the lesson; once a scene opens it should stay open`).toMatch(/LessonScreen/);
    }

    await screen.dismiss(client);
    await tick.sprint(server, 5);
    await openColonyPanelViaBook(server, client, grounded.name, standBlock);
    const shelf: any = { shots: {} };
    report.shelf = shelf;
    shelf.tab = await clickElement(client, { id: SHELF.tab }, { scrollIntoView: true });
    await frames(client, 2);
    shelf.shots.folders = await shot(client, shotPath("e2e", "ponder-shelf-folders"));
    shelf.folder = await clickElement(client, { id: SHELF.folder }, { scrollIntoView: true });
    await frames(client, 2);
    shelf.shots.scenes = await shot(client, shotPath("e2e", "ponder-shelf-scenes"));
    shelf.scene = await clickElement(client, { id: SHELF.scene }, { scrollIntoView: true });
    await realWait(2_500);
    await frames(client, 2);
    shelf.screen = screenName(await safeScreen(client));
    shelf.shots.opened = await shot(client, shotPath("e2e", "ponder-shelf-opened"));
    writeFileSync(path.join(REPO_ROOT, REPORT_PATH), JSON.stringify(report, null, 2) + "\n");
    expect(shelf.screen,
      `Lessons page: clicking ${SHELF.scene} in the farming folder did not open a lesson (got ${shelf.screen}). `
        + `Look at the folders and scenes shots before the lesson side.`)
      .toMatch(/LessonScreen/);

    const ponderWidgets = await screen.widgets(client).catch(() => []);
    report.ponder_widgets = ponderWidgets
      .map((w) => ({ id: w.id, label: w.label, className: w.className, x: w.x, y: w.y, width: w.width, height: w.height }));
    const back = ponderWidgets[0];
    if (back) {
      await input.click(client, { x: back.x + back.width / 2, y: back.y + back.height / 2 });
      await realWait(1_500);
      await frames(client, 2);
      report.screen_after_back = screenName(await safeScreen(client));
      report.shots.back = await shot(client, shotPath("e2e", "ponder-hint-back"));
    }
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    writeFileSync(path.join(REPO_ROOT, REPORT_PATH), JSON.stringify(report, null, 2) + "\n");
    await pair?.teardown(testInfo);
  }
});

const NAV_TOKEN = /^folkways\.(tab|page|panel)\./;

async function hoverNavPage(client: any, token: string) {
  const hover = await hoverElement(client, { id: token }, { scrollIntoView: true });
  // hoverElement reports only what LDLib2 calls hovered, not what is painted under the cursor: read the
  // panel again and label every hittable element whose visible box holds the point.
  const after = await panelElements(client);
  const owners = elementsAtPoint(after, { x: hover.x, y: hover.y }).map((owner) => elementLabel(after, owner));
  return {
    token,
    entry: {
      x: hover.element.x, y: hover.element.y,
      width: hover.element.width, height: hover.element.height,
    },
    hover_at: { x: hover.x, y: hover.y },
    hovered: hover.hovered,
    owners,
    landed_on: owners.filter((owner) => NAV_TOKEN.test(owner)),
  };
}

function realWait(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function shot(client: any, rel: string) {
  let result: any;
  try {
    const captured = await media.screenshot(client, { path: rel });
    result = { status: "captured", width: captured.width, height: captured.height, frame: captured.frame, sha256: captured.sha256 };
  } catch (error) {
    result = { status: "error", reason: error instanceof Error ? error.message : String(error) };
  }
  const abs = path.join(REPO_ROOT, rel);
  return { ...result, path: rel, exists: existsSync(abs), bytes: fileSize(abs) };
}

function fileSize(abs: string) {
  try {
    return statSync(abs).size;
  } catch {
    return 0;
  }
}
