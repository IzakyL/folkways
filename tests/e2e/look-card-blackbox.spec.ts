import { expect, test } from "@playwright/test";
import { input, media, player, screen, tick } from "@izakyl/blockwright-minecraft";
import { writeFileSync } from "node:fs";
import {
  createColonyViaBook,
  optInViaBook,
  waitForGroundedPlayer,
  type Vec,
} from "./colony-founding";
import { createMarkedZone, setBookGesture } from "./zone-tools";
import { aimAndWorldLeftClick } from "./world-ui";
import {
  drawItems,
  drawTexts,
  sleepMs,
  type DrawCaptureResult,
  type DrawText,
} from "./draw-capture";
import { launchPair, type E2EPair } from "../shared/pair";
import { ensureOut, outAbs, tracePath } from "../out-paths";
import { seededServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { frames, safeScreen, commandPos, tryCommand } from "../shared/bw-helpers";
import { newRunId } from "../shared/run-id";

const CONFIG_PATH = "blockwright.toml";

const BOOK_ITEM = "folkways:colony_book";

const PADDING = 3;
// A line told in signs is an item tall, its words centred on it, and its first sign the delegation's 10px glyph.
const SIGNS_ROW = 18;
const SIGN_TEXT_DROP = 4;
const GLYPH_STEP = 12;
const CROSSHAIR_OFFSET_Y = 12;
const TITLE_COLOR = 0xffffffff | 0;
const TEXT_COLOR = 0xffaaaaaa | 0;

const FARM_ZONE_NAME = "Farm";
const MEMBER_LINE = "In the colony";
const NON_MEMBER_LINE = "Not in colony — right-click to add";

test("look cards: a zone card and a non-zone server snapshot both hang under the crosshair and stay live", async ({}, testInfo) => {
  test.setTimeout(BUDGET.standard);
  ensureOut("e2e");

  const runId = newRunId();
  let pair: E2EPair | undefined;

  let server: any;
  let client: any;
  const evidence: any = {};

  try {
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: tracePath("e2e", "look-card-server"), instance: seededServer(`look-card-server-${runId}`) },
      client: { trace: tracePath("e2e", "look-card-client"), instance: `look-card-client-${runId}` },
    });
    ({ server, client } = pair);

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    const origin: Vec = {
      x: Math.round(grounded.pos.x) + 4,
      y: Math.round(grounded.pos.y),
      z: Math.round(grounded.pos.z) + 4,
    };
    const groundY = origin.y - 1;

    await flattenPlot(server, origin);
    await tryCommand(server, `gamemode survival ${playerName}`);
    await tryCommand(server, `give ${playerName} ${BOOK_ITEM}`);
    await tryCommand(server, `item replace entity ${playerName} weapon.mainhand with ${BOOK_ITEM}`);
    await tick.sprint(server, 5);

    const standBlock: Vec = { x: origin.x, y: origin.y, z: origin.z };
    evidence.found = await createColonyViaBook(server, client, playerName, standBlock);

    const memberChest: Vec = { x: origin.x - 4, y: origin.y, z: origin.z };
    const otherChest: Vec = { x: origin.x - 4, y: origin.y, z: origin.z + 4 };
    for (const chest of [memberChest, otherChest]) {
      await tryCommand(server, `setblock ${commandPos(chest)} minecraft:chest{Items:[]}`);
    }
    await tick.sprint(server, 3);
    evidence.opt_in = await optInViaBook(server, client, playerName, memberChest, "chest");

    const zoneMin: Vec = { x: origin.x + 4, y: groundY, z: origin.z + 4 };
    const zoneMax: Vec = { x: origin.x + 6, y: groundY, z: origin.z + 6 };
    evidence.zone_mode = await setBookGesture(server, client, playerName, "box");
    evidence.corner_1 = await aimAndWorldLeftClick(server, client, playerName, zoneMin);
    await tick.sprint(server, 3);
    evidence.corner_2 = await aimAndWorldLeftClick(server, client, playerName, zoneMax);
    await tick.sprint(server, 5);
    evidence.configure_farm = await createMarkedZone(server, client, playerName, standBlock, "farm");
    await tick.sprint(server, 5);

    const guiHeight = await readGuiHeight(client);
    evidence.gui_height = guiHeight;
    expect(guiHeight, "could not read the GUI height, so none of the y assertions below make sense").toBeGreaterThan(0);
    const crosshairCardY = Math.floor(guiHeight / 2) + CROSSHAIR_OFFSET_Y + PADDING;

    const zoneAim = { x: zoneMin.x + 1, y: groundY, z: zoneMin.z + 1 };
    await tryCommand(server, `tp ${playerName} ${zoneAim.x + 0.5} ${origin.y} ${zoneAim.z - 6.5} 0 20`);
    await tick.sprint(server, 3);
    await player.aim(server, zoneAim);
    await tick.sprint(server, 3);
    await screen.dismiss(client);

    const aimedCapture = await captureHud(server, client, { rounds: 1 });
    evidence.zone_aimed = describe(aimedCapture);

    expect(aimedCapture.droppedEntries, "capture is incomplete, so none of the counts below mean anything").toBe(0);
    const aimedTitle = findText(aimedCapture, FARM_ZONE_NAME);
    expect(aimedTitle, "aiming at a zone should draw the zone card title line").not.toBeUndefined();
    expect(aimedTitle!.y, "the zone card hangs below the crosshair").toBe(crosshairCardY + SIGN_TEXT_DROP);
    expect(aimedTitle!.color | 0, "the title line is white").toBe(TITLE_COLOR);

    const aimedDims = findText(aimedCapture, "3x3");
    expect(aimedDims, "the second line is the zone's dx x dz").not.toBeUndefined();
    expect(aimedDims!.y, "the title is a line of signs").toBe(crosshairCardY + SIGNS_ROW);
    expect(aimedDims!.color | 0, "body lines are darker than the title").toBe(TEXT_COLOR);
    expect(aimedTitle!.x, "the zone's name follows its delegation's glyph").toBe(aimedDims!.x + GLYPH_STEP);

    await lookAtBlock(server, client, playerName, memberChest, origin.y);
    const memberCapture = await captureHud(server, client, { rounds: 2 });
    evidence.member_chest = describe(memberCapture);

    expect(memberCapture.droppedEntries).toBe(0);
    const member = findText(memberCapture, MEMBER_LINE);
    expect(member, "a member container's card says it has joined").not.toBeUndefined();
    expect(member!.y, "non-zone cards also hang below the crosshair").toBe(crosshairCardY);
    expect(member!.color | 0).toBe(TITLE_COLOR);
    expect(
      findText(memberCapture, NON_MEMBER_LINE),
      "a chest that has joined should not also be judged a non-member",
    ).toBeUndefined();

    await sleepMs(1500);
    const heldCapture = await captureHud(server, client, { rounds: 2 });
    evidence.held_target = describe(heldCapture);

    expect(heldCapture.droppedEntries).toBe(0);
    expect(
      drawItems(heldCapture).length,
      "the HUD is still drawing (the held book is drawn on the hotbar)",
    ).toBeGreaterThan(0);
    expect(findText(heldCapture, MEMBER_LINE), "while staring at a still target, the card should stay up").not.toBeUndefined();

    await lookAtBlock(server, client, playerName, otherChest, origin.y);
    const otherCapture = await captureHud(server, client, { rounds: 2 });
    evidence.other_chest = describe(otherCapture);

    expect(otherCapture.droppedEntries).toBe(0);
    const nonMember = findText(otherCapture, NON_MEMBER_LINE);
    expect(nonMember, "a container that has not joined shows the join hint").not.toBeUndefined();
    expect(nonMember!.y).toBe(crosshairCardY);
    expect(findText(otherCapture, MEMBER_LINE), "a non-member container should not read as the colony's").toBeUndefined();

  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    const body = JSON.stringify(evidence, null, 2);
    writeFileSync(outAbs("e2e", "reports", "look-card-evidence.json"), body);
    testInfo.attach("look-card-evidence", { body, contentType: "application/json" });
    await pair?.teardown(testInfo);
  }
});

async function lookAtBlock(server: any, client: any, playerName: string, block: Vec, standY: number) {
  await screen.dismiss(client);
  await tryCommand(server, `tp ${playerName} ${block.x + 0.5} ${standY} ${block.z + 3.5} 180 20`);
  await tick.sprint(server, 3);
  await player.aim(server, block);
  await tick.sprint(server, 3);
  await screen.dismiss(client);
}

async function captureHud(
  server: any,
  client: any,
  options: { rounds: number },
): Promise<DrawCaptureResult> {
  const recording = await media.startDraws(client, { channel: "hud", maxFrames: 240 });
  for (let i = 0; i < options.rounds; i++) {
    await sleepMs(400);
    await tick.sprint(server, 5);
    await sleepMs(300);
  }
  await frames(client, 5);
  return (await recording.stop()) as DrawCaptureResult;
}

function findText(capture: DrawCaptureResult, text: string): DrawText | undefined {
  return drawTexts(capture).find((entry) => entry.text === text);
}

async function readGuiHeight(client: any): Promise<number> {
  await screen.dismiss(client);
  // A tap: press and release, answered after the client tick that acted on it.
  await input.key(client, { keybind: "key.inventory" });
  await waitForContainerSlots(client, 15_000);
  const seen = await safeScreen(client);
  const height = (seen as any)?.screen?.height;
  await screen.dismiss(client);
  return typeof height === "number" ? height : 0;
}

async function waitForContainerSlots(client: any, timeoutMs: number) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const slots = await screen.slots(client).catch(() => []);
    if (slots.length > 0) {
      return slots;
    }
    if (Date.now() >= deadline) {
      throw new Error(`the inventory screen showed no slots within ${timeoutMs}ms`);
    }
    await sleepMs(200);
  }
}

function describe(capture: DrawCaptureResult) {
  return {
    frameCount: capture.frameCount,
    entryCount: capture.entryCount,
    droppedEntries: capture.droppedEntries,
    texts: [...new Set(drawTexts(capture).map((entry) => `${entry.text}@${entry.x},${entry.y}`))].slice(0, 40),
  };
}

async function flattenPlot(server: any, origin: Vec) {
  const minX = origin.x - 8;
  const maxX = origin.x + 9;
  const minZ = origin.z - 8;
  const maxZ = origin.z + 9;
  await tryCommand(server, `fill ${minX} ${origin.y} ${minZ} ${maxX} ${origin.y + 5} ${maxZ} minecraft:air`);
  await tryCommand(
    server,
    `fill ${minX} ${origin.y - 1} ${minZ} ${maxX} ${origin.y - 1} ${maxZ} minecraft:smooth_stone`,
  );
  await tryCommand(server, `setblock ${commandPos(origin)} minecraft:smooth_stone`);
}
