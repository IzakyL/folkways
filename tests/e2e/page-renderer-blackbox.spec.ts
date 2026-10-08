import { expect, test } from "@playwright/test";
import { clickElement, panelElements } from "../ldlib2";
import { screen, tick, world } from "@izakyl/blockwright-minecraft";
import {
  foundColonyFast,
  openColonyPanelViaBook,
  tryCommand,
  setBookGesture,
} from "./colony-founding";
import { aimAndLeftClick, colonyRegistry, createMarkedZone, markCorner, zoneCount } from "./zone-tools";
import { FIRST_CORNER, ZONE_MARKED } from "./zone-receipt";
import { launchPair, type E2EPair } from "../shared/pair";
import { ensureOut, tracePath } from "../out-paths";
import { seededServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { waitForElement } from "../shared/bw-helpers";
import { newRunId } from "../shared/run-id";

const CRAFT_PAGE = "folkways.wares";
const CRAFT_TAB = "folkways.page.wares";
const PASTURE_TAB = "folkways.page.pasture";
const CRAFT_ROW = `folkways.board.row.${CRAFT_PAGE}.0`;
const CRAFT_EDIT = `folkways.board.edit.${CRAFT_PAGE}.0.0`;
const PASTURE_BOARD = /^folkways\.board\.[a-z]+\.folkways\.pasture\./;
const CITIZENS_BOARD = /^folkways\.board\.[a-z]+\.folkways\.citizens\./;
const PEN_LINE = /^\d+ of 4 .+ \d+ grown$/;

test("a page a domain draws itself replaces the core's table, and one it does not keeps it", async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");
  const runId = newRunId();
  let pair: E2EPair | undefined;
  let server: any;
  let client: any;
  try {
    pair = await launchPair({
      config: "blockwright.toml",
      server: { trace: tracePath("e2e", "page-renderer-server"), instance: seededServer(`page-renderer-server-${runId}`) },
      client: { trace: tracePath("e2e", "page-renderer-client"), instance: `page-renderer-client-${runId}` },
    });
    ({ server, client } = pair);
    const players = await world.players(server).catch(() => []);
    const playerName = players?.[0]?.name ?? "BlockwrightBot";
    const playerPos = players?.[0]?.position ?? { x: 0, y: 64, z: 0 };
    const origin = {
      x: Math.round(playerPos.x) + 4,
      y: Math.round(playerPos.y),
      z: Math.round(playerPos.z) + 4,
    };
    await tryCommand(server, `gamemode survival ${playerName}`);
    const founding = await foundColonyFast(server, client, playerName, { origin, residentCount: 1 });

    const ground = origin.y - 1;
    const min = { x: origin.x, y: ground, z: origin.z + 8 };
    const max = { x: origin.x + 1, y: ground, z: origin.z + 9 };
    const standInside = { x: max.x, y: origin.y, z: max.z };
    await setBookGesture(server, client, playerName, "box");
    await markCorner(server, client, min, FIRST_CORNER,
      () => aimAndLeftClick(server, client, playerName, standInside, min));
    await markCorner(server, client, max, ZONE_MARKED,
      () => aimAndLeftClick(server, client, playerName, standInside, max));
    await createMarkedZone(server, client, playerName, founding.standBlock, "pasture");
    await tick.sprint(server, 240);
    expect(await zoneCount(server, await colonyRegistry(server))).toBe(1);

    await openColonyPanelViaBook(server, client, playerName, founding.standBlock);
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
    await screen.dismiss(client);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
