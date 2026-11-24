import { launchPair, type E2EPair } from "../shared/harness/pair";
import { expect, test } from "@playwright/test";
import { input, screen, tick, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { panelElements } from "../ldlib2";
import { createColonyViaBook, optInViaBook, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import {
  colonyRegistry,
  createMarkedZone,
  holdZoneAndOpen,
  setBookGesture,
  zoneCount,
  chooseBookTool,
  useBook,
  zoneKindKey,
  zoneChoiceSettingAt,
} from "../shared/ui/zone-tools";
import { aimAndWorldLeftClick } from "../shared/ui/world-ui";
import { CARD_WINDOW, closeCard, settleCard, waitForPresent } from "../shared/ui/book-cards";
import { cellText, pickChoice } from "../shared/ui/site-panel";
import { captureZoneCards } from "./ui-catalog";
import { ponderCatalog } from "../shared/capture/ponder";
import { CONFIG_PATH, VisualRun } from "../shared/capture/visual-kit";
import { tracePath } from "../shared/harness/out-paths";
import { seededOffThreadServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { commandPos, frames, tryCommand } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";

const BOOK_ITEM = "folkways_core:colony_book";

test("zone cards on camera", async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  const run = new VisualRun("zones-panel");
  const runId = newRunId();
  let pair: E2EPair | undefined;
  let server: MinecraftServer;
  let client: MinecraftClient;
  try {
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: tracePath("visual", "zones-panel-server"), instance: seededOffThreadServer(`visual-zones-panel-server-${runId}`) },
      client: { trace: tracePath("visual", "zones-panel-client"), instance: `visual-zones-panel-client-${runId}` },
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
    await tryCommand(server, `forceload add ${origin.x - 8} ${origin.z - 8} ${origin.x + 12} ${origin.z + 12}`);
    await tryCommand(server, `give ${playerName} ${BOOK_ITEM}`);
    await tryCommand(server, `item replace entity ${playerName} weapon.mainhand with ${BOOK_ITEM}`);
    await tick.sprint(server, 5);

    const standBlock: Vec = { x: origin.x, y: origin.y, z: origin.z };
    await createColonyViaBook(server, client, playerName, standBlock);

    const memberChest: Vec = { x: origin.x - 2, y: origin.y, z: origin.z };
    await tryCommand(server, `setblock ${commandPos(memberChest)} minecraft:chest{Items:[]}`);
    await tick.sprint(server, 3);
    await optInViaBook(server, client, playerName, memberChest, "chest");

    await input.key(client, { keyCode: 86, action: "press" });
    await input.key(client, { keyCode: 86, action: "release" });
    await screen.waitFor(client, "OverheadScreen");
    const catalog = ponderCatalog("en_us");
    const dining = (await screen.widgets(client)).find(widget =>
      widget.label === catalog["folkways.delegation.folkways_vanilla.dining"]);
    expect(dining, "the toolbar lists dining with no table provider").toBeDefined();
    expect(dining!.enabled).toBe(false);
    expect(dining!.tooltip).toContain(catalog["folkways.dining.requires_tables"]);
    await input.move(client, { x: dining!.x + dining!.width / 2, y: dining!.y + dining!.height / 2 });
    await frames(client, 15);
    await run.shot(client, "dining-disabled-tooltip", {
      subject: "The view from above explains why dining is unavailable without a supported table mod",
      worldState: { dining }, hard: true,
    });
    await screen.dismiss(client);
    await setBookGesture(server, client, playerName, "box");

    const mark = async (box: { min: Vec; max: Vec }) => {
      await aimAndWorldLeftClick(server, client, playerName, box.min);
      await tick.sprint(server, 3);
      await aimAndWorldLeftClick(server, client, playerName, box.max);
      await tick.sprint(server, 5);
    };

    const farmBox = {
      min: { x: origin.x + 3, y: groundY, z: origin.z + 3 },
      max: { x: origin.x + 6, y: groundY, z: origin.z + 6 },
    };
    const pastureBox = {
      min: { x: origin.x - 6, y: groundY, z: origin.z + 3 },
      max: { x: origin.x - 3, y: groundY, z: origin.z + 6 },
    };
    await chooseBookTool(server, client, zoneKindKey("farm"));
    await mark(farmBox);
    run.note("farm_created", await createMarkedZone(server, client, playerName, standBlock, "farm"));
    await tick.sprint(server, 5);
    await chooseBookTool(server, client, zoneKindKey("pasture"));
    await mark(pastureBox);
    run.note("pasture_created", await createMarkedZone(server, client, playerName, standBlock, "pasture"));
    await tick.sprint(server, 5);

    const zones = await zoneCount(server, await colonyRegistry(server));
    expect(zones, "both zones should be registered on the server").toBeGreaterThanOrEqual(2);

    run.note("farm_opened", await holdZoneAndOpen(server, client, playerName, farmBox));
    await settleCard(server, client);
    const crop = "folkways.card.choice.crop.open";
    await waitForPresent(server, client, crop);
    await run.shot(client, "02-zone-settings-farm", {
      subject: "Right-clicking a farm with the book opens its facility card",
      worldState: { tokens: await tokensOf(client) }, hard: true,
    });
    await closeCard(server, client);

    const spareBox = {
      min: { x: origin.x + 3, y: groundY, z: origin.z - 6 },
      max: { x: origin.x + 6, y: groundY, z: origin.z - 3 },
    };
    await chooseBookTool(server, client, zoneKindKey("farm"));
    await mark(spareBox);
    await useBook(client);
    await settleCard(server, client);
    const registry = await colonyRegistry(server);
    await expect.poll(() => zoneCount(server, registry)).toBe(zones + 1);
    await waitForPresent(server, client, crop);
    await run.shot(client, "04-new-farm-card", {
      subject: "Completing a farm box opens its card immediately", worldState: { box: spareBox }, hard: true,
    });
    const defaultCrop = await zoneChoiceSettingAt(server, registry, "crop", zones);
    const otherCrop = defaultCrop === "minecraft:carrots" ? "minecraft:potatoes" : "minecraft:carrots";
    await pickChoice(server, client, crop, otherCrop, () => cellText(client, crop),
      () => run.shot(client, "05-choice-picker", {
        subject: "Selecting a crop on the farm card", worldState: { option: otherCrop }, hard: true,
      }));
    await expect.poll(async () => {
      await tick.sprint(server, 3);
      return zoneChoiceSettingAt(server, registry, "crop", zones);
    }).toBe(otherCrop);
    await closeCard(server, client);

    await chooseBookTool(server, client, "kind:folkways_vanilla:patrol");
    const line = { min: { x: origin.x - 5, y: groundY, z: origin.z - 5 },
      max: { x: origin.x - 3, y: groundY, z: origin.z - 5 } };
    await mark(line);
    await frames(client, 5);
    await run.shot(client, "path-world", {
      subject: "Patrol path draft in the world", worldState: { line }, hard: true,
    });
    await useBook(client);
    await tick.sprint(server, 5);
    await waitForPresent(server, client, CARD_WINDOW);
    await settleCard(server, client);
    await run.shot(client, "path-card", {
      subject: "The completed patrol path opens its facility card", worldState: { line }, hard: true,
    });
    await closeCard(server, client);
    await setBookGesture(server, client, playerName, "point");
    await captureZoneCards(server, client, run, playerName);
    run.expectTaken(["dining-disabled-tooltip", "02-zone-settings-farm", "04-new-farm-card", "05-choice-picker", "path-world", "path-card"]);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
    run.write();
  }

});

async function tokensOf(client: MinecraftClient): Promise<string[]> {
  return (await panelElements(client)).filter(one => one.width > 0 && one.height > 0 && one.displayed !== false).map((one) => String(one.id ?? "")).filter(Boolean);
}

async function flattenPlot(server: MinecraftServer, origin: Vec) {
  const minX = origin.x - 8;
  const maxX = origin.x + 10;
  const minZ = origin.z - 8;
  const maxZ = origin.z + 10;
  await tryCommand(server, `fill ${minX} ${origin.y} ${minZ} ${maxX} ${origin.y + 5} ${maxZ} minecraft:air`);
  await tryCommand(server, `fill ${minX} ${origin.y - 1} ${minZ} ${maxX} ${origin.y - 1} ${maxZ} minecraft:smooth_stone`);
  await tryCommand(server, `setblock ${commandPos(origin)} minecraft:smooth_stone`);
}
