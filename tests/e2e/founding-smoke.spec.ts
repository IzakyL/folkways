import { expect, test } from "@playwright/test";
import { panelElements } from "../ldlib2";
import { screen, world } from "@izakyl/blockwright-minecraft";
import {
  foundColonyWithResidents,
  openColonyPanelViaBook,
  countResidents,
  screenName,
  tryCommand,
} from "./colony-founding";
import { launchPair, type E2EPair } from "../shared/pair";
import { ensureOut, tracePath } from "../out-paths";
import { seededServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { newRunId } from "../shared/run-id";

const CONFIG_PATH = "blockwright.toml";
const TARGET_RESIDENTS = 3;

test("book founds a colony and the Household page lets people in one by one", async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");

  const runId = newRunId();
  let pair: E2EPair | undefined;
  const serverTrace = tracePath("e2e", "founding-server");
  const clientTrace = tracePath("e2e", "founding-client");

  let server: any;
  let client: any;
  let founding: any;
  try {
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: serverTrace, instance: seededServer(`founding-server-${runId}`) },
      client: { trace: clientTrace, instance: `founding-client-${runId}` },
      snapshot: () => ({ containers: [founding?.foodChest], entityTypes: ["folkways:resident"] }),
    });
    ({ server, client } = pair);

    const players = await world.players(server).catch(() => []);
    const playerName = players?.[0]?.name ?? "BlockwrightBot";
    const playerPos = players?.[0]?.position ?? { x: 0, y: 64, z: 0 };

    const origin = { x: Math.round(playerPos.x) + 4, y: Math.round(playerPos.y), z: Math.round(playerPos.z) + 4 };
    await tryCommand(server, `gamemode survival ${playerName}`);

    founding = await foundColonyWithResidents(server, client, playerName, { origin, residentCount: TARGET_RESIDENTS });

    const residents = await countResidents(server);
    expect(residents).toBeGreaterThanOrEqual(TARGET_RESIDENTS);

    const panel = await openColonyPanelViaBook(server, client, playerName, founding.standBlock);
    const tokens = (await panelElements(client)).map((element) => element.id).filter(Boolean);
    expect(screenName(panel)).toMatch(/ModularUIContainerScreen/);
    expect(tokens).toContain("folkways.tab.residents");
    expect(tokens).not.toContain("folkways.tab.zones");
    expect(tokens).toContain("folkways.tab.metrics");
    expect(tokens).toContain("folkways.tab.settings");
    await screen.dismiss(client);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
