import { expect, test } from "@playwright/test";
import {
  foundColonyFast,
  openColonyPanelViaBook,
  tryCommand,
  safeScreen,
  screenName,
  waitForGroundedPlayer,
} from "../shared/colony/colony-founding";
import { input, screen, tick } from "@izakyl/blockwright-minecraft";
import { clickElement, hoverElement, panelElements, elementLabel, elementsAtPoint } from "../ldlib2";
import { BOOK_PAGES } from "../shared/ui/colony-pages";
import { frames } from "../shared/harness/bw-helpers";
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
  { lesson: "colony_panel", tab: "folkways.tab.overview" },
  { lesson: "colony_panel", tab: "folkways.tab.facilities" },
  { lesson: "colony_panel", tab: "folkways.tab.settings" },
  { lesson: "colony_panel", tab: "folkways.tab.members" },
] as const;

// The Lessons page is a tree; farming's folder holds one scene, so it is a row of its own under its open category.
const SHELF = {
  tab: "folkways.tab.lessons",
  category: "folkways.lessons.category.folkways_core.production",
  scene: "folkways.lessons.scene.folkways_vanilla.farming",
} as const;

test("fixed colony book tabs and the Lessons tree open their Ponder scenes", async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");

  const runId = newRunId();
  let pair: E2EPair | undefined;
  let server: any;
  let client: any;
  try {
    // The base profile: Kaleidoscope Cookery is absent, so Vanilla's cookery integration and its lessons are off.
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

    await openColonyPanelViaBook(server, client, playerName, standBlock);
    expect((await panelElements(client)).filter(element => element.id?.startsWith("folkways.tab."))
      .map(element => element.id)).toEqual(BOOK_PAGES.map(page => page.token));

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
    // Without Kaleidoscope Cookery there is no kitchen: no kitchen lesson folder.
    const shelf = await panelElements(client);
    expect(shelf.some((element) => element.id === "folkways.lessons.folder.folkways_vanilla.cookery")).toBe(false);
    expect(shelf.some((element) => element.id === "folkways.page.cookery"),
      "the kitchen lesson needs Kaleidoscope Cookery installed").toBe(false);
    expect(shelf.some((element) => element.id === SHELF.category)).toBe(true);
    expect(shelf.some((element) => element.id === "folkways.lessons.folder.folkways_vanilla.farming"),
      "a folder of one scene is no folder to unfold").toBe(false);
    await clickElement(client, { id: SHELF.scene }, { scrollIntoView: true });
    await realWait(2_500);
    await frames(client, 2);
    const opened = screenName(await safeScreen(client));
    expect(opened,
      `Lessons page: clicking ${SHELF.scene} in the production category did not open a lesson (got ${opened}).`)
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
