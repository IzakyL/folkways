import { expect, test } from "@playwright/test";
import { errorSummary } from "@izakyl/blockwright-client";
import { screen, tick } from "@izakyl/blockwright-minecraft";
import {
  commandPos,
  foundColonyFast,
  foundingBox,
  waitForGroundedPlayer,
} from "../shared/colony/colony-founding";
import {
  aimAndWorldLeftClick,
  mustCommand,
} from "../shared/ui/world-ui";
import { drainActionBar } from "../shared/ui/zone-receipt";
import {
  colonyRegistry,
  createMarkedZone,
  setBookGesture,
  zoneCount,
  zoneKindAt,
  ZONE_KIND_ID,
} from "../shared/ui/zone-tools";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { tryCommand } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { twins, type Stage } from "../shared/harness/stage";

const RESIDENT_COUNT = 3;

for (const stage of twins()) test(stage.title("fishing: player carves a fish zone through the real panel"), async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");
  const runId = newRunId();

  let pair: E2EPair | undefined;
  let server: any;
  let client: any;

  try {
    const clientInstance = stage.name(`fishing-client-${runId}`);
    pair = await launchPair({
      config: stage.config,
      server: { trace: tracePath("e2e", stage.name("fishing-server")), instance: seededServer(stage.name(`fishing-server-${runId}`)) },
      client: { trace: tracePath("e2e", stage.name("fishing-client")), instance: clientInstance },
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
    await stage.lay(server, foundingBox(origin));
    const founding = await foundColonyFast(server, client, playerName, { origin, residentCount: RESIDENT_COUNT, stage });
    expect(founding.residents, `residents=${founding.residents} (expected ${RESIDENT_COUNT})`).toBeGreaterThanOrEqual(RESIDENT_COUNT);

    const registry = await colonyRegistry(server);
    expect(await zoneCount(server, registry), "founding marks no zones, so there must be 0 before zoning").toBe(0);

    const shore = { x: origin.x, y: origin.y - 1, z: origin.z + 8 };
    const water = { x: shore.x, y: shore.y, z: shore.z - 1 };
    // A ship's deck is one block thick: the pool needs a floor there, or it pours out of the ship.
    await tryCommand(server, `setblock ${commandPos(stage.at({ ...water, y: water.y - 1 }))} minecraft:oak_planks`);
    await mustCommand(server, `setblock ${commandPos(stage.at(water))} minecraft:water`);
    await tick.sprint(server, 5);

    const zone = await createFishZoneViaPanel(server, client, playerName, founding.standBlock, shore, stage);
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

async function createFishZoneViaPanel(server: any, client: any, playerName: string, standBlock: any, shore: any, stage: Stage) {
  const evidence: any = { shore };
  try {
    evidence.zone_mode = await setBookGesture(server, client, playerName, "box");
    evidence.corner_1 = await aimAndWorldLeftClick(server, client, playerName, shore, stage);
    await tick.sprint(server, 3);
    evidence.corner_2 = await aimAndWorldLeftClick(server, client, playerName, shore, stage);
    await tick.sprint(server, 5);

    evidence.action_bar_before = await drainActionBar(client);
    evidence.configure = await createMarkedZone(server, client, playerName, standBlock, "fish", stage);
  } catch (error) {
    evidence.error = errorSummary(error);
  } finally {
    await screen.dismiss(client);
  }
  return evidence;
}
