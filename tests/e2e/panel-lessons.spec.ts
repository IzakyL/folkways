import { expect, test } from "@playwright/test";
import {
  foundColonyFast,
  openColonyPanelViaBook,
  tryCommand,
  safeScreen,
  screenName,
  setBookGesture,
  waitForGroundedPlayer,
} from "../shared/colony/colony-founding";
import { aimAndLeftClick, colonyRegistry, createMarkedZone, markCorner, zoneCount } from "../shared/ui/zone-tools";
import { FIRST_CORNER, ZONE_MARKED } from "../shared/ui/zone-receipt";
import { input, screen, tick } from "@izakyl/blockwright-minecraft";
import { clickElement, hoverElement, panelElements, elementLabel, elementsAtPoint } from "../ldlib2";
import { frames, waitForElement } from "../shared/harness/bw-helpers";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { newRunId } from "../shared/harness/run-id";

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
  folder: "folkways.lessons.folder.folkways_vanilla.farming",
  scene: "folkways.lessons.scene.folkways_vanilla.farming",
} as const;

// A page a domain draws itself replaces the core's table; one it does not keeps it.
const CRAFT_PAGE = "folkways_vanilla.wares";
const CRAFT_TAB = "folkways.page.wares";
const PASTURE_TAB = "folkways.page.pasture";
const CRAFT_ROW = `folkways.board.row.${CRAFT_PAGE}.0`;
const CRAFT_EDIT = `folkways.board.edit.${CRAFT_PAGE}.0.0`;
const PASTURE_BOARD = /^folkways\.board\.[a-z]+\.folkways\.pasture\./;
const CITIZENS_BOARD = /^folkways\.board\.[a-z]+\.folkways\.citizens\./;
const PEN_LINE = /^\d+ total · retain 4 · \d+ grown$/;

test("every colony panel tab draws its own page and opens its own ponder lesson", async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");

  const runId = newRunId();
  let pair: E2EPair | undefined;
  let server: any;
  let client: any;
  try {
    // The base profile: the optional Cookery extension and its native mod are absent.
    pair = await launchPair({
      config: "blockwright.toml",
      server: { trace: tracePath("e2e", "ponder-probe-server"), instance: seededServer(`ponder-server-${runId}`) },
      client: { trace: tracePath("e2e", "ponder-probe-client"), instance: `ponder-client-${runId}` },
    });
    ({ server, client } = pair);

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    await tryCommand(server, "difficulty peaceful");
    await tryCommand(server, `gamemode survival ${playerName}`);
    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    const founding = await foundColonyFast(server, client, playerName, { origin, residentCount: 1 });
    const standBlock = founding.standBlock;

    // One pasture, so its page has a pen to draw.
    const ground = origin.y - 1;
    const min = { x: origin.x, y: ground, z: origin.z + 8 };
    const max = { x: origin.x + 1, y: ground, z: origin.z + 9 };
    const standInside = { x: max.x, y: origin.y, z: max.z };
    await setBookGesture(server, client, playerName, "box");
    await markCorner(server, client, min, FIRST_CORNER,
      () => aimAndLeftClick(server, client, playerName, standInside, min));
    await markCorner(server, client, max, ZONE_MARKED,
      () => aimAndLeftClick(server, client, playerName, standInside, max));
    await createMarkedZone(server, client, playerName, standBlock, "pasture");
    await tick.sprint(server, 240);
    expect(await zoneCount(server, await colonyRegistry(server))).toBe(1);

    await openColonyPanelViaBook(server, client, playerName, standBlock);
    await clickElement(client, { id: CRAFT_TAB }, { scrollIntoView: true });
    await waitForElement(client, { id: CRAFT_ROW }, { timeoutMs: 15_000 });
    await clickElement(client, { id: PASTURE_TAB }, { scrollIntoView: true });
    const penLine = await waitForElement(client, { text: "grown" }, { timeoutMs: 15_000 });
    const tokens = (await panelElements(client))
      .map((element) => element.id)
      .filter((id): id is string => typeof id === "string");
    expect(tokens).toContain(CRAFT_ROW);
    expect(tokens).toContain(CRAFT_EDIT);
    expect(tokens.filter((token) => PASTURE_BOARD.test(token)).length).toBeGreaterThan(0);
    expect(penLine?.text ?? "").toMatch(PEN_LINE);
    expect(tokens.filter((token) => CITIZENS_BOARD.test(token))).toEqual([]);

    for (const { lesson, tab: token } of LESSONS) {
      await screen.dismiss(client);
      await tick.sprint(server, 5);
      await openColonyPanelViaBook(server, client, playerName, standBlock);

      const hover = await hoverNavPage(client, token);
      await input.key(client, { keybind: PONDER_KEYBIND, action: "press" });
      await realWait(2_500);
      await frames(client, 2);
      const afterHold = screenName(await safeScreen(client));
      await input.key(client, { keybind: PONDER_KEYBIND, action: "release" });
      await realWait(1_500);
      await frames(client, 2);
      const settled = screenName(await safeScreen(client));

      expect(hover.landed_on,
        `${token}: the cursor is not over this row; that pixel belongs to ${JSON.stringify(hover.owners)}. `
          + `Leave the lesson side alone`)
        .toContain(token);
      expect(hover.landed_on.filter((id: string) => id !== token),
        `${token}: this pixel also lands on other nav entries (${JSON.stringify(hover.landed_on)}): `
          + `clipping is already accounted for, so two rows really overlap on screen; the coordinates are not lying. `
          + `Hit: ${JSON.stringify(hover)}`)
        .toEqual([]);
      expect(afterHold,
        `${token}: holding the ponder key did not open a lesson (got ${afterHold}). `
          + `folkways_vanilla:${lesson} has no scene bound: the page names no lesson and its plugin declared no folder under the page id, or the folder is empty.`)
        .toMatch(/LessonScreen/);
      expect(settled,
        `${token}: releasing the ponder key closed the lesson; once a scene opens it should stay open`).toMatch(/LessonScreen/);
    }

    await screen.dismiss(client);
    await tick.sprint(server, 5);
    await openColonyPanelViaBook(server, client, playerName, standBlock);
    await clickElement(client, { id: SHELF.tab }, { scrollIntoView: true });
    await frames(client, 2);
    // Without the optional extension there is no kitchen: no lesson folder, no page.
    const shelf = await panelElements(client);
    expect(shelf.some((element) => element.id === "folkways.lessons.folder.folkways_cookery.cookery")).toBe(false);
    expect(shelf.some((element) => element.id === "folkways.page.cookery"),
      "the kitchen page belongs to the optional Folkways extension").toBe(false);
    await clickElement(client, { id: SHELF.folder }, { scrollIntoView: true });
    await frames(client, 2);
    await clickElement(client, { id: SHELF.scene }, { scrollIntoView: true });
    await realWait(2_500);
    await frames(client, 2);
    const opened = screenName(await safeScreen(client));
    expect(opened,
      `Lessons page: clicking ${SHELF.scene} in the farming folder did not open a lesson (got ${opened}).`)
      .toMatch(/LessonScreen/);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
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
    hover_at: { x: hover.x, y: hover.y },
    hovered: hover.hovered,
    owners,
    landed_on: owners.filter((owner) => NAV_TOKEN.test(owner)),
  };
}

function realWait(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
