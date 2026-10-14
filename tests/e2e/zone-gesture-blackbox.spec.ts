import { expect, test } from "@playwright/test";
import { clickElement, panelElements } from "../ldlib2";
import { tick } from "@izakyl/blockwright-minecraft";
import {
  commandPos,
  createColonyViaBook,
  tryCommand,
  safeAction,
  waitForGroundedPlayer,
} from "../shared/colony/colony-founding";
import {
  colonyRegistry,
  createMarkedZone,
  ctrlScroll,
  heldScroll,
  holdZoneAndOpen,
  KEY_LEFT_ALT,
  KEY_LEFT_CONTROL,
  markBox,
  openBookPage,
  releaseHeldZone,
  setBookGesture,
  zoneCount,
} from "../shared/ui/zone-tools";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { waitForElement, softly } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { twins, type Stage } from "../shared/harness/stage";

const BOOK_ITEM = "folkways:colony_book";

const MARKED_SIZE = /^(\d+)x(\d+)x(\d+) marked/;

for (const stage of twins()) test(stage.title("zone gesture: mark, push a face, create it, right-click it open, then give it back"), async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");

  const runId = newRunId();
  const clientInstance = stage.name(`zone-gesture-client-${runId}`);

  let pair: E2EPair | undefined;
  let server: any;
  let client: any;
  const evidence: any = {};

  try {
    pair = await launchPair({
      config: stage.config,
      server: { trace: tracePath("e2e", stage.name("zone-gesture-server")), instance: seededServer(stage.name(`zone-gesture-server-${runId}`)) },
      client: { trace: tracePath("e2e", stage.name("zone-gesture-client")), instance: clientInstance },
    });
    ({ server, client } = pair);

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    const origin = {
      x: Math.round(grounded.pos.x) + 4,
      y: Math.round(grounded.pos.y),
      z: Math.round(grounded.pos.z) + 4,
    };
    await flattenPlot(server, origin, stage);
    await tryCommand(server, `gamemode survival ${playerName}`);

    const standBlock = { x: origin.x, y: origin.y, z: origin.z };
    const farmGround = { x: origin.x + 3, y: origin.y - 1, z: origin.z + 3 };
    const standAt = { x: farmGround.x + 0.5, y: origin.y, z: farmGround.z + 0.5 };

    await tryCommand(server, `give ${playerName} ${BOOK_ITEM}`);
    await tryCommand(server, `item replace entity ${playerName} weapon.mainhand with ${BOOK_ITEM}`);
    await tick.sprint(server, 5);

    evidence.found = await createColonyViaBook(server, client, playerName, standBlock, stage);

    evidence.zone_mode = await setBookGesture(server, client, playerName, "box");
    evidence.mark = await markBox(server, client, playerName, standAt, farmGround, farmGround, stage);
    const markedBefore = sizeOf(evidence.mark.marked?.text);
    expect(markedBefore, "after marking both corners the action bar must report the box size; if it does not, neither corner landed").not.toBeNull();
    expect(markedBefore, "both corners on the same block = 1x1x1").toEqual([1, 1, 1]);

    evidence.push_face = await ctrlScroll(server, client, 1);
    const markedAfter = sizeOf(evidence.push_face.bar?.text);
    expect(
      markedAfter,
      "after pushing a face the action bar should report the size again; if not, this Ctrl+scroll was never consumed",
    ).not.toBeNull();
    expect(markedAfter, "pushing the top face out one block turns the box from 1x1x1 into 1x2x1").toEqual([1, 2, 1]);

    evidence.push_far = await heldScroll(server, client, [KEY_LEFT_ALT], -1);
    expect(
      sizeOf(evidence.push_far.bar?.text),
      "Alt+scroll moves the face behind the one aimed at: the bottom, one block away from the player, 1x2x1 into 1x3x1",
    ).toEqual([1, 3, 1]);
    evidence.push_far_back = await heldScroll(server, client, [KEY_LEFT_ALT], 1);
    expect(sizeOf(evidence.push_far_back.bar?.text), "and back towards the player again").toEqual([1, 2, 1]);

    evidence.shift = await heldScroll(server, client, [KEY_LEFT_CONTROL, KEY_LEFT_ALT], 1);
    expect(
      evidence.shift.bar?.text,
      "with both keys the wheel moves the whole box towards the player, one block up, and says where it now starts",
    ).toBe(`Moved to ${farmGround.x}, ${farmGround.y + 1}, ${farmGround.z}`);
    evidence.shift_back = await heldScroll(server, client, [KEY_LEFT_CONTROL, KEY_LEFT_ALT], -1);
    expect(evidence.shift_back.bar?.text).toBe(`Moved to ${farmGround.x}, ${farmGround.y}, ${farmGround.z}`);

    evidence.configure = await createMarkedZone(server, client, playerName, standBlock, "farm", stage);

    evidence.plain_book = await openBookPage(server, client, playerName, standBlock, stage);
    evidence.plain_tokens = (await panelElements(client))
      .filter((element) => element.width > 0 && element.height > 0 && element.displayed !== false)
      .map((element) => element.id)
      .filter((id): id is string => typeof id === "string" && /^folkways\.(zones\.|zoneconfig\.|selection\.)/.test(id));
    expect(evidence.plain_tokens, "opening the book directly should show no zone-related pages").toEqual([]);
    await releaseHeldZone(client);

    const registry = await colonyRegistry(server);
    const box = { min: farmGround, max: { ...farmGround, y: farmGround.y + 1 } };
    evidence.hold = await holdZoneAndOpen(server, client, playerName, box, stage);
    await waitForElement(client, { id: "folkways.selection.release" }, { timeoutMs: 8_000 });
    evidence.click_release = await softly(() => clickElement(client, { id: "folkways.selection.release" }));
    await expect.poll(async () => {
      await tick.sprint(server, 3);
      return zoneCount(server, registry);
    }, { timeout: 8_000, message: "pressing remove on the config page should remove this zone from the colony" }).toBe(0);
    evidence.release = await releaseHeldZone(client);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    testInfo.attach(`${stage.name("zone-gesture-evidence")}.json`, {
      body: JSON.stringify(evidence, null, 2),
      contentType: "application/json",
    });
    await pair?.teardown(testInfo);
  }
});

function sizeOf(text: string | undefined): [number, number, number] | null {
  const match = MARKED_SIZE.exec(text ?? "");
  return match ? [Number(match[1]), Number(match[2]), Number(match[3])] : null;
}

async function flattenPlot(server: any, origin: any, stage: Stage) {
  const min = { x: origin.x - 4, y: origin.y, z: origin.z - 4 };
  const max = { x: origin.x + 6, y: origin.y + 5, z: origin.z + 6 };
  await stage.lay(server, { min, max });
  await tryCommand(server, `fill ${commandPos(stage.at(min))} ${commandPos(stage.at(max))} minecraft:air`);
  await tryCommand(server,
    `fill ${commandPos(stage.at({ ...min, y: origin.y - 1 }))} ${commandPos(stage.at({ ...max, y: origin.y - 1 }))} minecraft:smooth_stone`);
  await tryCommand(server, `setblock ${commandPos(stage.at(origin))} minecraft:smooth_stone`);
}
