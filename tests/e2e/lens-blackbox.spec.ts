import { expect, test } from "@playwright/test";
import { media, player, tick } from "@izakyl/blockwright-minecraft";
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
  boxIsAt,
  drawEntries,
  filledBoxes,
  hueScale,
  lineBoxes,
  sleepMs,
  summarizeBox,
  worldItems,
  worldTexts,
  type DrawCaptureResult,
} from "./draw-capture";
import { launchPair, type E2EPair } from "../shared/pair";
import { ensureOut, outAbs, tracePath } from "../out-paths";
import { seededServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { commandPos, frames, tryCommand } from "../shared/bw-helpers";
import { newRunId } from "../shared/run-id";

const CONFIG_PATH = "blockwright.toml";

const BOOK_ITEM = "folkways:colony_book";

const OUTLINE_RANGE = 160.0;
const FILL_RANGE = 128.0;
const CARD_RANGE = 128.0;
const MEMBER_RANGE = 48.0;

const ZONE_LABEL = "Farm";
const CARD_HEAD_ROOM = 0.6;
const CARD_NEAR = 12.0;
const CARD_SCALE = 0.025;
const CARD_GROWTH_CAP = 6.0;
const CARD_MAX_WIDTH_PX = 160;
const EYE_HEIGHT = 1.62;

const FARM_RGB = [0.559, 0.92, 0.35] as const;
const CONTAINER_RGB = [0.3, 0.6, 0.95] as const;
const SHADE_FACTORS = [1.0, 0.72];

const OUTLINE_INFLATE = 0.02;
const FILL_ALPHA = 0.16;
const MIN_OUTLINE_ALPHA = 0.35;

test("held-book lens: the outline reaches 160 blocks and the volume fill and card stop at 128", async ({}, testInfo) => {
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
      server: { trace: tracePath("e2e", "lens-server"), instance: seededServer(`lens-server-${runId}`) },
      client: { trace: tracePath("e2e", "lens-client"), instance: `lens-client-${runId}` },
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
    await tryCommand(server, `forceload add ${origin.x} ${origin.z - 180} ${origin.x + 8} ${origin.z + 8}`);

    await tryCommand(server, `give ${playerName} ${BOOK_ITEM}`);
    await tryCommand(server, `item replace entity ${playerName} weapon.mainhand with ${BOOK_ITEM}`);
    await tick.sprint(server, 5);

    const standBlock: Vec = { x: origin.x, y: origin.y, z: origin.z };
    evidence.found = await createColonyViaBook(server, client, playerName, standBlock);

    const memberChest: Vec = { x: origin.x - 2, y: origin.y, z: origin.z };
    await tryCommand(server, `setblock ${commandPos(memberChest)} minecraft:chest{Items:[]}`);
    await tick.sprint(server, 3);
    evidence.opt_in = await optInViaBook(server, client, playerName, memberChest, "chest");

    const zoneMin: Vec = { x: origin.x + 3, y: groundY, z: origin.z + 3 };
    const zoneMax: Vec = { x: origin.x + 5, y: groundY, z: origin.z + 5 };
    evidence.zone_mode = await setBookGesture(server, client, playerName, "box");
    evidence.corner_1 = await aimAndWorldLeftClick(server, client, playerName, zoneMin);
    await tick.sprint(server, 3);
    evidence.corner_2 = await aimAndWorldLeftClick(server, client, playerName, zoneMax);
    await tick.sprint(server, 5);
    evidence.configure_farm = await createMarkedZone(server, client, playerName, standBlock, "farm");
    await tick.sprint(server, 5);

    const zoneBox = {
      min: [zoneMin.x, zoneMin.y, zoneMin.z] as const,
      max: [zoneMax.x + 1, zoneMax.y + 1, zoneMax.z + 1] as const,
    };
    const zoneCenter = {
      x: (zoneBox.min[0] + zoneBox.max[0]) / 2,
      y: (zoneBox.min[1] + zoneBox.max[1]) / 2,
      z: (zoneBox.min[2] + zoneBox.max[2]) / 2,
    };
    const chestBox = {
      min: [memberChest.x, memberChest.y, memberChest.z] as const,
      max: [memberChest.x + 1, memberChest.y + 1, memberChest.z + 1] as const,
    };
    const chestCenter = { x: memberChest.x + 0.5, y: memberChest.y + 0.5, z: memberChest.z + 0.5 };
    const cardAnchor = { x: zoneCenter.x, y: zoneBox.max[1] + CARD_HEAD_ROOM, z: zoneCenter.z };
    evidence.geometry = { zoneBox, zoneCenter, chestBox, chestCenter, groundY, cardAnchor };

    const near = { x: zoneCenter.x, y: origin.y, z: zoneCenter.z - 12 };
    const nearCapture = await standAndCapture(server, client, playerName, near, groundY);
    evidence.near = describe(nearCapture, near, zoneCenter, chestCenter, cardAnchor);

    expect(nearCapture.droppedEntries, "capture is incomplete (hit maxEntriesPerFrame), so none of the counts below mean anything").toBe(0);
    expect(nearCapture.frameCount, "the capture window should hold at least one frame").toBeGreaterThan(0);
    expect(
      lineBoxes(nearCapture).length,
      "the crosshair is on the block underfoot, so vanilla's block outline must be drawn - this proves the capture chain itself is alive",
    ).toBeGreaterThan(0);

    const nearZoneDistance = distance(eyeOf(near), zoneCenter);
    const nearChestDistance = distance(eyeOf(near), chestCenter);
    expect(nearZoneDistance, "phase 1 must sit inside the fill band").toBeLessThan(FILL_RANGE);
    expect(nearChestDistance, "phase 1 must sit inside the member highlight band").toBeLessThan(MEMBER_RANGE);

    const nearOutline = findZoneOutline(nearCapture, zoneBox, OUTLINE_INFLATE);
    expect(nearOutline.boxes.length, "up close, exactly one outline for this zone should be drawn").toBe(1);
    expect(SHADE_FACTORS, "the outline color should be the farm base color times one of the two shaded factors")
      .toContain(round2(nearOutline.scales[0]));
    expect(nearOutline.boxes[0].color.a, "an unselected zone's outline alpha fades with distance from the camera")
      .toBeCloseTo(outlineAlpha(nearZoneDistance), 2);

    const nearFill = findZoneFill(nearCapture, zoneBox);
    expect(nearFill.length, "within 128 blocks the volume fill should be drawn").toBe(1);
    expect(nearFill[0].color.a, "fill alpha is a fixed 0.16 and does not change with distance").toBeCloseTo(FILL_ALPHA, 4);
    expect(hueScale(nearFill[0].color, FARM_RGB), "fill has the same hue as the outline").not.toBeNull();

    const nearMember = memberBoxes(nearCapture, chestBox);
    expect(nearMember.length, "within 48 blocks the member container highlight should be drawn").toBe(1);
    expect(nearMember[0].color.a, "member highlight alpha is always 1.0 and does not fade with distance").toBeCloseTo(1.0, 4);
    expect(hueScale(nearMember[0].color, CONTAINER_RGB, 0.001), "member containers are blue and not shaded")
      .toBeCloseTo(1.0, 3);

    expect(distance(eyeOf(near), cardAnchor), "phase 1 must sit inside the card band").toBeLessThan(CARD_RANGE);
    expect(zoneCards(nearCapture, near, cardAnchor).length, "within 128 blocks the zone's card, headed by its kind, should be drawn").toBe(1);

    const beyondMember = { x: zoneCenter.x, y: origin.y, z: zoneCenter.z - 60 };
    const beyondMemberCapture = await standAndCapture(server, client, playerName, beyondMember, groundY);
    evidence.beyond_member = describe(beyondMemberCapture, beyondMember, zoneCenter, chestCenter, cardAnchor);

    expect(beyondMemberCapture.droppedEntries).toBe(0);
    const beyondMemberZoneDistance = distance(eyeOf(beyondMember), zoneCenter);
    expect(distance(eyeOf(beyondMember), chestCenter), "phase 2 must sit outside the member highlight band").toBeGreaterThan(MEMBER_RANGE);
    expect(beyondMemberZoneDistance, "phase 2 must still sit inside the fill band").toBeLessThan(FILL_RANGE);
    expect(distance(eyeOf(beyondMember), cardAnchor), "phase 2 must still sit inside the card band").toBeLessThan(CARD_RANGE);

    const beyondMemberOutline = findZoneOutline(beyondMemberCapture, zoneBox, OUTLINE_INFLATE);
    expect(beyondMemberOutline.boxes.length, "at 60 blocks the outline is still there").toBe(1);
    expect(beyondMemberOutline.boxes[0].color.a, "at 60 blocks the outline alpha is still fading, not yet at its floor")
      .toBeCloseTo(outlineAlpha(beyondMemberZoneDistance), 2);
    expect(findZoneFill(beyondMemberCapture, zoneBox).length, "at 60 blocks the volume fill is still there").toBe(1);
    expect(zoneCards(beyondMemberCapture, beyondMember, cardAnchor).length, "at 60 blocks the card is still there").toBe(1);
    expect(memberBoxes(beyondMemberCapture, chestBox).length, "beyond 48 blocks no member highlight should be drawn").toBe(0);

    const beyondFill = { x: zoneCenter.x, y: origin.y, z: zoneCenter.z - 140 };
    const beyondFillCapture = await standAndCapture(server, client, playerName, beyondFill, groundY);
    evidence.beyond_fill = describe(beyondFillCapture, beyondFill, zoneCenter, chestCenter, cardAnchor);

    expect(beyondFillCapture.droppedEntries).toBe(0);
    const beyondFillZoneDistance = distance(eyeOf(beyondFill), zoneCenter);
    expect(beyondFillZoneDistance, "phase 3 must sit outside the fill band and inside the outline band").toBeGreaterThan(FILL_RANGE);
    expect(beyondFillZoneDistance).toBeLessThan(OUTLINE_RANGE);
    expect(distance(eyeOf(beyondFill), cardAnchor), "phase 3 must sit outside the card band").toBeGreaterThan(CARD_RANGE);

    const beyondFillOutline = findZoneOutline(beyondFillCapture, zoneBox, OUTLINE_INFLATE);
    expect(beyondFillOutline.boxes.length, "at 140 blocks the outline is still there (OUTLINE_RANGE = 160)").toBe(1);
    expect(beyondFillOutline.boxes[0].color.a, "at 140 blocks alpha has bottomed out at 0.35")
      .toBeCloseTo(MIN_OUTLINE_ALPHA, 4);
    expect(findZoneFill(beyondFillCapture, zoneBox).length, "beyond 128 blocks no volume fill should be drawn").toBe(0);
    expect(zoneCards(beyondFillCapture, beyondFill, cardAnchor).length, "beyond 128 blocks no card should be drawn").toBe(0);

    const far = { x: zoneCenter.x, y: origin.y, z: zoneCenter.z - 170 };
    const farCapture = await standAndCapture(server, client, playerName, far, groundY);
    evidence.far = describe(farCapture, far, zoneCenter, chestCenter, cardAnchor);

    expect(farCapture.droppedEntries).toBe(0);
    expect(distance(eyeOf(far), zoneCenter), "phase 4 must sit outside the outline band").toBeGreaterThan(OUTLINE_RANGE);
    expect(
      lineBoxes(farCapture).length,
      "the crosshair is still on the block underfoot: this frame did draw boxes, just none for this zone",
    ).toBeGreaterThan(0);
    expect(findZoneOutline(farCapture, zoneBox, OUTLINE_INFLATE).boxes.length, "beyond 160 blocks no outline should be drawn").toBe(0);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    const body = JSON.stringify(evidence, null, 2);
    writeFileSync(outAbs("e2e", "reports", "lens-evidence.json"), body);
    testInfo.attach("lens-evidence", { body, contentType: "application/json" });
    await pair?.teardown(testInfo);
  }
});

async function standAndCapture(server: any, client: any, playerName: string, at: any, groundY: number) {
  await tryCommand(server, `tp ${playerName} ${at.x} ${at.y} ${at.z} 0 60`);
  await tick.sprint(server, 3);
  await player.aim(server, { x: Math.floor(at.x), y: groundY, z: Math.floor(at.z) });
  // player.aim runs no ticks after its teleport; let the world settle before capturing.
  await tick.sprint(server, 5 + 2);
  return captureWorld(server, client, { syncRounds: 2, renderFrames: 6 });
}

async function captureWorld(
  server: any,
  client: any,
  options: { syncRounds: number; renderFrames: number },
): Promise<DrawCaptureResult> {
  for (let i = 0; i < options.syncRounds; i++) {
    await sleepMs(1200);
    await tick.sprint(server, 5);
  }
  const capture = await media.startDraws(client, { channel: "world", maxFrames: 30 });
  await frames(client, options.renderFrames);
  return (await capture.stop()) as DrawCaptureResult;
}

function eyeOf(at: any) {
  return { x: at.x, y: at.y + EYE_HEIGHT, z: at.z };
}

function outlineAlpha(cameraDistance: number) {
  return Math.max(MIN_OUTLINE_ALPHA, 1 - cameraDistance / OUTLINE_RANGE);
}

function distance(a: any, b: any) {
  return Math.sqrt((a.x - b.x) ** 2 + (a.y - b.y) ** 2 + (a.z - b.z) ** 2);
}

function round2(value: number) {
  return Math.round(value * 100) / 100;
}

function findZoneOutline(
  capture: DrawCaptureResult,
  box: { min: readonly [number, number, number]; max: readonly [number, number, number] },
  inflate: number,
) {
  const min = box.min.map((v) => v - inflate) as unknown as readonly [number, number, number];
  const max = box.max.map((v) => v + inflate) as unknown as readonly [number, number, number];
  const matches = lineBoxes(capture)
    .filter((entry) => boxIsAt(entry, min, max))
    .filter((entry) => hueScale(entry.color, FARM_RGB) !== null);
  const unique = dedupe(matches);
  return { boxes: unique, scales: unique.map((entry) => hueScale(entry.color, FARM_RGB) ?? 0) };
}

function findZoneFill(
  capture: DrawCaptureResult,
  box: { min: readonly [number, number, number]; max: readonly [number, number, number] },
) {
  return dedupe(
    filledBoxes(capture)
      .filter((entry) => boxIsAt(entry, box.min, box.max))
      .filter((entry) => hueScale(entry.color, FARM_RGB) !== null),
  );
}

// A card's text is laid out across a camera-facing billboard that grows with distance, so its entries
// land up to half a card's width from the anchor; match the heading by text within that reach.
function zoneCards(capture: DrawCaptureResult, stand: any, anchor: { x: number; y: number; z: number }) {
  const growth = Math.min(Math.max(distance(eyeOf(stand), anchor) / CARD_NEAR, 1), CARD_GROWTH_CAP);
  const reach = 2 + CARD_SCALE * growth * CARD_MAX_WIDTH_PX;
  const seen = new Map<string, ReturnType<typeof worldTexts>[number]>();
  for (const entry of worldTexts(capture)) {
    if (entry.text !== ZONE_LABEL || distance(entry.position, anchor) > reach) {
      continue;
    }
    const key = `${entry.position.x.toFixed(2)}/${entry.position.y.toFixed(2)}/${entry.position.z.toFixed(2)}`;
    if (!seen.has(key)) {
      seen.set(key, entry);
    }
  }
  return [...seen.values()];
}

function memberBoxes(
  capture: DrawCaptureResult,
  box: { min: readonly [number, number, number]; max: readonly [number, number, number] },
) {
  const min = box.min.map((v) => v - OUTLINE_INFLATE) as unknown as readonly [number, number, number];
  const max = box.max.map((v) => v + OUTLINE_INFLATE) as unknown as readonly [number, number, number];
  return dedupe(
    lineBoxes(capture)
      .filter((entry) => boxIsAt(entry, min, max))
      .filter((entry) => hueScale(entry.color, CONTAINER_RGB, 0.001) !== null),
  );
}

function dedupe<T extends { min: any; max: any; color: any }>(boxes: T[]): T[] {
  const seen = new Map<string, T>();
  for (const box of boxes) {
    const key = [box.min.x, box.min.y, box.min.z, box.max.x, box.max.y, box.max.z,
      box.color.r, box.color.g, box.color.b, box.color.a]
      .map((value: number) => value.toFixed(3)).join("/");
    if (!seen.has(key)) {
      seen.set(key, box);
    }
  }
  return [...seen.values()];
}

function describe(capture: DrawCaptureResult, at: any, zoneCenter: any, chestCenter: any, cardAnchor: any) {
  return {
    stand: at,
    distance_to_zone: distance(eyeOf(at), zoneCenter),
    distance_to_chest: distance(eyeOf(at), chestCenter),
    distance_to_card: distance(eyeOf(at), cardAnchor),
    frameCount: capture.frameCount,
    entryCount: capture.entryCount,
    droppedEntries: capture.droppedEntries,
    kinds: countKinds(capture),
    boxes: dedupe([...lineBoxes(capture), ...filledBoxes(capture)] as any).map(summarizeBox),
    world_texts: worldTexts(capture).map((entry) => ({ text: entry.text, position: entry.position })),
    world_items: worldItems(capture).map((entry) => ({ id: entry.id, position: entry.position })),
  };
}

function countKinds(capture: DrawCaptureResult) {
  const counts: Record<string, number> = {};
  for (const entry of drawEntries(capture)) {
    counts[entry.kind] = (counts[entry.kind] ?? 0) + 1;
  }
  return counts;
}

async function flattenPlot(server: any, origin: Vec) {
  const minX = origin.x - 6;
  const maxX = origin.x + 8;
  const minZ = origin.z - 6;
  const maxZ = origin.z + 8;
  await tryCommand(server, `fill ${minX} ${origin.y} ${minZ} ${maxX} ${origin.y + 5} ${maxZ} minecraft:air`);
  await tryCommand(
    server,
    `fill ${minX} ${origin.y - 1} ${minZ} ${maxX} ${origin.y - 1} ${maxZ} minecraft:smooth_stone`,
  );
  await tryCommand(server, `setblock ${commandPos(origin)} minecraft:smooth_stone`);
}
