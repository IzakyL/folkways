import { expect, test } from "@playwright/test";
import { errorSummary } from "@izakyl/blockwright-client";
import { screen, tick } from "@izakyl/blockwright-minecraft";
import {
  commandPos,
  foundColonyFast,
  waitForGroundedPlayer,
} from "./colony-founding";
import {
  aimAndWorldLeftClick,
  mustCommand,
} from "./world-ui";
import { drainActionBar } from "./zone-receipt";
import {
  colonyRegistry,
  createMarkedZone,
  setBookGesture,
  zoneCount,
  zoneKindAt,
  ZONE_KIND_ID,
} from "./zone-tools";
import { launchPair, type E2EPair } from "../shared/pair";
import { ensureOut, tracePath } from "../out-paths";
import { seededServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { newRunId } from "../shared/run-id";

const CONFIG_PATH = "blockwright.toml";
const RESIDENT_COUNT = 3;

test("fishing: player carves a fish zone through the real panel", async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");
  const runId = newRunId();

  let pair: E2EPair | undefined;
  let server: any;
  let client: any;

  try {
    const clientInstance = `fishing-client-${runId}`;
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: tracePath("e2e", "fishing-server"), instance: seededServer(`fishing-server-${runId}`) },
      client: { trace: tracePath("e2e", "fishing-client"), instance: clientInstance },
      snapshot: () => ({ containers: [], entityTypes: ["folkways:resident"] }),
    });
    ({ server, client } = pair);

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    await mustCommand(server, `gamemode survival ${playerName}`);

    const origin = {
      x: Math.round(grounded.pos.x) + 4,
      y: Math.round(grounded.pos.y),
      z: Math.round(grounded.pos.z) + 4,
    };
    const founding = await foundColonyFast(server, client, playerName, { origin, residentCount: RESIDENT_COUNT });
    expect(founding.residents, `residents=${founding.residents} (expected ${RESIDENT_COUNT})`).toBeGreaterThanOrEqual(RESIDENT_COUNT);

    const registry = await colonyRegistry(server);
    expect(await zoneCount(server, registry), "founding marks no zones, so there must be 0 before zoning").toBe(0);

    const shore = { x: origin.x, y: origin.y - 1, z: origin.z + 8 };
    const water = { x: shore.x, y: shore.y, z: shore.z - 1 };
    await mustCommand(server, `setblock ${commandPos(water)} minecraft:water`);
    await tick.sprint(server, 5);

    const zone = await createFishZoneViaPanel(server, client, playerName, founding.standBlock, shore);
    expect(zone.error, `creating the fishing spot threw: ${JSON.stringify(zone.error)}`).toBeUndefined();

    expect(
      await zoneCount(server, registry),
      "after confirming on the config page, the colony should hold exactly one more zone",
    ).toBe(1);
    expect(
      await zoneKindAt(server, registry, 0),
      "the new zone must be a fishery: the kind built should match the row selected on the config page",
    ).toBe(ZONE_KIND_ID.fish);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

async function createFishZoneViaPanel(server: any, client: any, playerName: string, standBlock: any, shore: any) {
  const evidence: any = { shore };
  try {
    evidence.zone_mode = await setBookGesture(server, client, playerName, "box");
    evidence.corner_1 = await aimAndWorldLeftClick(server, client, playerName, shore);
    await tick.sprint(server, 3);
    evidence.corner_2 = await aimAndWorldLeftClick(server, client, playerName, shore);
    await tick.sprint(server, 5);

    evidence.action_bar_before = await drainActionBar(client);
    evidence.configure = await createMarkedZone(server, client, playerName, standBlock, "fish");
  } catch (error) {
    evidence.error = errorSummary(error);
  } finally {
    await screen.dismiss(client);
  }
  return evidence;
}
