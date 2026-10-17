import { expect, test } from "@playwright/test";
import { media, tick } from "@izakyl/blockwright-minecraft";
import { clickElement } from "../ldlib2";
import { commandPos, createColonyViaBook, isColonyMember, tryCommand, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { markBox, openBookPage, setBookGesture } from "../shared/ui/zone-tools";
import { drainActionBar, readActionBar } from "../shared/ui/zone-receipt";
import { settlePanel } from "../shared/ui/site-panel";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, outAbs, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { frames, safeScreen, screenOpen, waitForElement } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { around, twins } from "../shared/harness/stage";

const BOOK_ITEM = "folkways:colony_book";
const OFFER = "folkways.zoneconfig.offer.folkways.sweep";
const STORES = "folkways.sweep.kind.folkways.store";
const STATIONS = "folkways.sweep.kind.folkways.station";
const TAKE = "folkways.sweep.take";

for (const stage of twins()) test(stage.title("a marked box takes in every block of the kinds left ticked, and only those"), async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");

  const runId = newRunId();
  let pair: E2EPair | undefined;
  const evidence: any = {};

  try {
    pair = await launchPair({
      config: stage.config,
      server: { trace: tracePath("e2e", stage.name("block-sweep-server")), instance: seededServer(stage.name(`block-sweep-server-${runId}`)) },
      client: { trace: tracePath("e2e", stage.name("block-sweep-client")), instance: stage.name(`block-sweep-client-${runId}`) },
    });
    const { server, client } = pair;

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    const plot = around([origin], 8, 4);
    await stage.lay(server, plot);
    await tryCommand(server, `fill ${commandPos(stage.at(plot.min))} ${commandPos(stage.at(plot.max))} minecraft:air`);
    await tryCommand(server, `fill ${commandPos(stage.at({ ...plot.min, y: origin.y - 1 }))} ${commandPos(stage.at({ ...plot.max, y: origin.y - 1 }))} minecraft:smooth_stone`);
    await tryCommand(server, `setblock ${commandPos(stage.at(origin))} minecraft:smooth_stone`);
    await tryCommand(server, `gamemode survival ${playerName}`);
    await tryCommand(server, `item replace entity ${playerName} weapon.mainhand with ${BOOK_ITEM}`);
    await tick.sprint(server, 5);
    evidence.found = await createColonyViaBook(server, client, playerName, origin, stage);

    // Two barrels stand at the box's corners, to be clicked on, and a furnace between them. Each is a whole block, so
    // the click aimed at its top lands on it.
    const first = { x: origin.x + 2, y: origin.y, z: origin.z + 2 };
    const furnace = { x: origin.x + 3, y: origin.y, z: origin.z + 2 };
    const last = { x: origin.x + 4, y: origin.y, z: origin.z + 2 };
    for (const [at, block] of [[first, "minecraft:barrel"], [furnace, "minecraft:furnace"], [last, "minecraft:barrel"]] as const) {
      await tryCommand(server, `setblock ${commandPos(stage.at(at))} ${block}`);
    }
    await tick.sprint(server, 5);

    evidence.mode = await setBookGesture(server, client, playerName, "box");
    const standAt = { x: origin.x + 3.5, y: origin.y, z: origin.z - 0.5 };
    evidence.mark = await markBox(server, client, playerName, standAt, first, last, stage);
    expect(evidence.mark.marked.text, "the two barrels mark the box between them").toMatch(/marked$/);

    await openBookPage(server, client, playerName, origin, stage);
    await waitForElement(client, { id: OFFER }, { timeoutMs: 8_000 });
    await clickElement(client, { id: OFFER });
    await settlePanel(server, client);
    await waitForElement(client, { id: STORES }, { timeoutMs: 8_000 });
    await waitForElement(client, { id: STATIONS }, { timeoutMs: 8_000 });
    await frames(client, 4);
    await media.screenshot(client, { path: outAbs("e2e", "shots", `${stage.name("block-sweep")}.png`) });

    // The furnace's kind is unticked; the barrels' stays as it opened.
    evidence.untick = await clickElement(client, { id: STATIONS });
    await settlePanel(server, client);
    await drainActionBar(client);
    evidence.take = await clickElement(client, { id: TAKE });
    await expect.poll(async () => {
      await tick.sprint(server, 3);
      return screenOpen(await safeScreen(client));
    }, { timeout: 8_000, message: "taking them in closes the window" }).toBe(false);
    evidence.told = await readActionBar(client);
    expect(evidence.told.text, "the player is told how many were taken in").toBe("Took in 2 blocks");

    expect(await isColonyMember(server, first, stage), "the first barrel is taken in").toBe(true);
    expect(await isColonyMember(server, last, stage), "the last barrel is taken in").toBe(true);
    expect(await isColonyMember(server, furnace, stage), "the furnace, its kind unticked, stays out").toBe(false);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    testInfo.attach(`${stage.name("block-sweep-evidence")}.json`, {
      body: JSON.stringify(evidence, null, 2),
      contentType: "application/json",
    });
    await pair?.teardown(testInfo);
  }
});
