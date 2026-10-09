import { launchPair, type E2EPair } from "../shared/pair";
import { test } from "@playwright/test";
import { player, tick, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { createColonyViaBook, openColonyPanelViaBook, optInViaBook, waitForGroundedPlayer, type Vec } from "../e2e/colony-founding";
import { createMarkedZone, ctrlScroll, setBookGesture } from "../e2e/zone-tools";
import { aimAndWorldLeftClick } from "../e2e/world-ui";
import { sleepMs } from "../e2e/draw-capture";
import { CONFIG_PATH, VisualRun } from "./visual-kit";
import { tracePath } from "../out-paths";
import { seededOffThreadServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { commandPos, tryCommand } from "../shared/bw-helpers";
import { newRunId } from "../shared/run-id";

const BOOK_ITEM = "folkways:colony_book";

test("world rendering on camera", async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  const run = new VisualRun("zones");
  const runId = newRunId();
  let pair: E2EPair | undefined;
  let server: MinecraftServer;
  let client: MinecraftClient;
  try {
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: tracePath("visual", "zones-server"), instance: seededOffThreadServer(`visual-zones-server-${runId}`) },
      client: { trace: tracePath("visual", "zones-client"), instance: `visual-zones-client-${runId}` },
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
    await tryCommand(server, `gamemode creative ${playerName}`);
    await tryCommand(server, `forceload add ${origin.x} ${origin.z - 80} ${origin.x + 8} ${origin.z + 8}`);

    await tryCommand(server, `give ${playerName} ${BOOK_ITEM}`);
    await tryCommand(server, `item replace entity ${playerName} weapon.mainhand with ${BOOK_ITEM}`);
    await tick.sprint(server, 5);

    const standBlock: Vec = { x: origin.x, y: origin.y, z: origin.z };
    await createColonyViaBook(server, client, playerName, standBlock);

    const memberChest: Vec = { x: origin.x - 2, y: origin.y, z: origin.z };
    await tryCommand(server, `setblock ${commandPos(memberChest)} minecraft:chest{Items:[]}`);
    await tick.sprint(server, 3);
    await optInViaBook(server, client, playerName, memberChest, "chest");

    const zoneMin: Vec = { x: origin.x + 3, y: groundY, z: origin.z + 3 };
    const zoneMax: Vec = { x: origin.x + 5, y: groundY, z: origin.z + 5 };
    await setBookGesture(server, client, playerName, "box");
    await aimAndWorldLeftClick(server, client, playerName, zoneMin);
    await tick.sprint(server, 3);
    await aimAndWorldLeftClick(server, client, playerName, zoneMax);
    await tick.sprint(server, 5);
    await createMarkedZone(server, client, playerName, standBlock, "farm");
    await tick.sprint(server, 5);

    const zoneCentre = { x: (zoneMin.x + zoneMax.x + 1) / 2, y: groundY + 1, z: (zoneMin.z + zoneMax.z + 1) / 2 };
    run.note("scene", { origin, groundY, zoneMin, zoneMax, zoneCentre, memberChest });

    const card = await standLookingAt(server, client, playerName, zoneCentre, 3, groundY, { aimBlock: zoneMin, elevation: 1 });
    await run.shot(client, "01-look-card", {
      subject: "Crosshair on a zone: no card hangs under the crosshair; the zone's own world card names it",
      worldState: { ...card, zoneMin, zoneMax, aimedAt: zoneMin },
      hard: true,
    });

    const bands = [
      { at: 12, name: "02-lens-12-blocks", subject: "Book lens at 12 blocks: outline, translucent volume, crop icon and name all present" },
      { at: 30, name: "03-lens-30-blocks", subject: "Book lens at 30 blocks: past the label band (24), icon and name gone, outline and volume remain" },
      { at: 56, name: "04-lens-56-blocks", subject: "Book lens at 56 blocks: past the fill band (48), only a fading outline remains" },
      { at: 70, name: "05-lens-70-blocks", subject: "Book lens at 70 blocks: past the outline limit (64), the zone is not drawn at all" },
    ];
    for (const band of bands) {
      const stance = await standLookingAt(server, client, playerName, zoneCentre, band.at, groundY);
      await run.shot(client, band.name, { subject: band.subject, worldState: { ...stance, zoneMin, zoneMax }, hard: true });
    }

    await aimAndWorldLeftClick(server, client, playerName, zoneMin);
    await tick.sprint(server, 2);
    const selected = await standLookingAt(server, client, playerName, zoneCentre, 70, groundY);
    await run.shot(client, "06-lens-70-selected", {
      subject: "Same 70 blocks, but the zone is selected: the outline ignores the limit, does not fade, and is thicker",
      worldState: { ...selected, selected: true, zoneMin, zoneMax },
      hard: true,
    });

    await standLookingAt(server, client, playerName, zoneCentre, 10, groundY);
    const eye = { x: zoneCentre.x, y: groundY + 18, z: zoneCentre.z + 0.1 };
    await run.shot(client, "06-zone-overview", {
      subject: "Top-down: the zone's green box and the member chests' blue boxes in one frame",
      worldState: { zoneMin, zoneMax, memberChest },
      camera: { dimension: "minecraft:overworld", position: eye, yaw: 0, pitch: 90, fov: 70 },
    });

    const boxMin: Vec = { x: origin.x + 3, y: groundY, z: origin.z - 6 };
    const boxMax: Vec = { x: origin.x + 6, y: groundY, z: origin.z - 3 };
    const marked = await markedBoxOnScreen(server, client, playerName, boxMin, boxMax);
    const boxCentre = {
      x: (boxMin.x + boxMax.x + 1) / 2,
      y: groundY + 1,
      z: (boxMin.z + boxMax.z + 1) / 2,
    };
    const boxEye = { x: boxCentre.x + 9, y: groundY + 7, z: boxCentre.z + 9 };
    await run.shot(client, "07-marked-box", {
      subject: "Unconfirmed selection box: white outline, plus a highlight slab on the face under the crosshair",
      worldState: { ...marked, boxMin, boxMax },
      camera: { dimension: "minecraft:overworld", position: boxEye, ...cameraLookingAt(boxEye, boxCentre), fov: 70 },
      hard: true,
    });

    const clipEye = { x: zoneCentre.x - 8, y: groundY + 6, z: zoneCentre.z - 8 };
    await run.clip(client, server, "08-lens-live", {
      subject: "Book lens live footage: outline, volume and labels redrawn every frame; they should stay pinned to the zone as the view moves",
      worldState: { zoneMin, zoneMax, memberChest },
      camera: { dimension: "minecraft:overworld", position: clipEye, ...cameraLookingAt(clipEye, zoneCentre), fov: 70 },
      seconds: 8,
    });
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

async function standLookingAt(
  server: MinecraftServer,
  client: MinecraftClient,
  playerName: string,
  target: { x: number; y: number; z: number },
  distance: number,
  groundY: number,
  options?: { aimBlock?: Vec; elevation?: number },
) {
  const elevation = options?.elevation ?? 10;
  const stand = { x: target.x, y: groundY + 1 + elevation, z: target.z - distance };
  await tryCommand(server, `setblock ${Math.floor(stand.x)} ${stand.y - 1} ${Math.floor(stand.z)} minecraft:smooth_stone`);
  const eye = { x: stand.x, y: stand.y + 1.62, z: stand.z };
  const facing = cameraLookingAt(eye, options?.aimBlock ? target : { ...target, x: target.x + 6 });
  await tryCommand(server, `tp ${playerName} ${stand.x} ${stand.y} ${stand.z} ${facing.yaw} ${facing.pitch}`);
  await tick.sprint(server, 3);
  if (options?.aimBlock) {
    await player.aim(server, options.aimBlock, { player: playerName });
    await tick.sprint(server, 2);
  }
  await syncClient(server, 2);
  return {
    horizontal: distance,
    elevation,
    distance: Math.round(Math.sqrt(distance ** 2 + (stand.y - target.y) ** 2) * 10) / 10,
    stand,
  };
}

async function syncClient(server: MinecraftServer, rounds: number) {
  for (let i = 0; i < rounds; i++) {
    await sleepMs(1200);
    await tick.sprint(server, 5);
  }
}

async function flattenPlot(server: MinecraftServer, origin: Vec) {
  const minX = origin.x - 6;
  const maxX = origin.x + 8;
  const minZ = origin.z - 6;
  const maxZ = origin.z + 8;
  await tryCommand(server, `fill ${minX} ${origin.y} ${minZ} ${maxX} ${origin.y + 5} ${maxZ} minecraft:air`);
  await tryCommand(server, `fill ${minX} ${origin.y - 1} ${minZ} ${maxX} ${origin.y - 1} ${maxZ} minecraft:smooth_stone`);
  await tryCommand(server, `setblock ${commandPos(origin)} minecraft:smooth_stone`);
}

async function markedBoxOnScreen(server: MinecraftServer, client: MinecraftClient, playerName: string, min: Vec, max: Vec) {
  const evidence: any = { min, max };
  evidence.corner_1 = await aimAndWorldLeftClick(server, client, playerName, min);
  await tick.sprint(server, 3);
  evidence.corner_2 = await aimAndWorldLeftClick(server, client, playerName, max);
  await tick.sprint(server, 5);
  evidence.push_face = await ctrlScroll(server, client, 1);
  return evidence;
}
