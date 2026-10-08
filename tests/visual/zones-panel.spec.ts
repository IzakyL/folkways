import { launchPair, type E2EPair } from "../shared/pair";
import { expect, test } from "@playwright/test";
import { input, screen, tick, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { clickElement, panelElements } from "../ldlib2";
import { createColonyViaBook, openColonyPanelViaBook, optInViaBook, waitForGroundedPlayer, type Vec } from "../e2e/colony-founding";
import {
  colonyRegistry,
  createMarkedZone,
  holdZoneAndOpen,
  openBookPage,
  setBookGesture,
  zoneCount,
  zoneChoiceSettingAt,
} from "../e2e/zone-tools";
import { aimAndWorldLeftClick } from "../e2e/world-ui";
import { buttonText, pickChoice } from "../e2e/site-panel";
import { captureZoneEditors } from "./ui-catalog";
import { CONFIG_PATH, VisualRun } from "./visual-kit";
import { tracePath } from "../out-paths";
import { seededOffThreadServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { commandPos, frames, safeAction, tryCommand, clickUnverified } from "../shared/bw-helpers";
import { newRunId } from "../shared/run-id";

const BOOK_ITEM = "folkways:colony_book";

test("the zone panel on camera", async ({}, testInfo) => {
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
    await mark(farmBox);
    run.note("farm_created", await createMarkedZone(server, client, playerName, standBlock, "farm"));
    await tick.sprint(server, 5);
    await mark(pastureBox);
    run.note("pasture_created", await createMarkedZone(server, client, playerName, standBlock, "pasture"));
    await tick.sprint(server, 5);

    const zones = await zoneCount(server, await colonyRegistry(server));
    expect(zones, "both zones should be registered on the server").toBeGreaterThanOrEqual(2);

    await openBookPage(server, client, playerName, standBlock);
    await tick.sprint(server, 5);
    await safeAction(() => frames(client, 3));
    const plain = await tokensOf(client);
    expect(plain.filter((id) => /^folkways\.(zones\.|zoneconfig\.|selection\.)/.test(id)),
      "opening the book directly should not include zone pages").toEqual([]);
    await safeAction(() => screen.dismiss(client));

    run.note("farm_opened", await holdZoneAndOpen(server, client, playerName, farmBox));
    await expect.poll(async () => (await tokensOf(client)).includes("folkways.selection.back"),
      { timeout: 8000 }).toBe(true);
    expect((await tokensOf(client)).includes("folkways.tab.zones")).toBe(false);
    await tick.sprint(server, 5);
    await safeAction(() => frames(client, 3));
    const settings = (await tokensOf(client)).filter((id) => id.startsWith("folkways.zoneconfig.setting."));
    run.note("farm_settings", settings);
    expect(settings.length, "the farm zone should have at least one settings row (crop)").toBeGreaterThan(0);
    await run.shot(client, "02-zone-settings-farm", {
      subject: "Settings page opened by right-clicking a farm with the book: key on the left, current value on the right (click to cycle); remove the zone at the top",
      worldState: { settings, tokens: await tokensOf(client) },
      hard: true,
    });
    await safeAction(() => screen.dismiss(client));

    const spareBox = {
      min: { x: origin.x + 3, y: groundY, z: origin.z - 6 },
      max: { x: origin.x + 6, y: groundY, z: origin.z - 3 },
    };
    await mark(spareBox);
    await openBookPage(server, client, playerName, standBlock);
    await tick.sprint(server, 5);
    await safeAction(() => frames(client, 3));
    const offered = await tokensOf(client);
    expect(offered.includes("folkways.tab.zones")).toBe(false);
    run.note("kind_offers", offered.filter((id) => id.startsWith("folkways.zoneconfig.")));
    expect(
      offered.some((id) => id.startsWith("folkways.zoneconfig.kind.")),
      `After marking a box and opening the book, the new zone page should show a row for each kind. The page had: ${JSON.stringify(offered)}`,
    ).toBe(true);
    await run.shot(client, "03-drawing-question", {
      subject: "Box marked but not confirmed: the book's new zone page asks 'what is this zone' at the top, one row per kind below, size and confirm/cancel at the end",
      worldState: { box: spareBox, tokens: offered },
      hard: true,
    });

    const kinds = offered.filter(id => id.startsWith("folkways.zoneconfig.kind."));
    run.note("discovered_zone_types", kinds);
    for (const id of kinds) {
      await clickElement(client, { id });
      await frames(client, 5);
      await input.move(client, { x: 0, y: 0 });
      await run.shot(client, `parameters-${id}`, {
        subject: `Zone purpose options: ${id}`, worldState: { type: id, tokens: await tokensOf(client) }, hard: true,
      });
    }

    await clickElement(client, { id: "folkways.zoneconfig.kind.folkways.farm" });
    await safeAction(() => frames(client, 4));
    await expect.poll(async () => (await tokensOf(client)).includes("folkways.draft.value.crop"),
      { timeout: 8000 }).toBe(true);
    await run.shot(client, "04-draft-parameters", {
      subject: "New zone: choose type and options before confirming", worldState: {}, hard: true,
    });

    const registry = await colonyRegistry(server);
    const defaultCrop = await zoneChoiceSettingAt(server, registry, "crop", 0);
    const otherCrop = defaultCrop === "minecraft:carrots" ? "minecraft:potatoes" : "minecraft:carrots";
    const picked = await pickChoice(server, client, "folkways.draft.value.crop", otherCrop,
      () => buttonText(client, "folkways.draft.value.crop"),
      () => run.shot(client, "05-choice-picker", {
        subject: "Choice picker: every option listed, the pick highlighted, applied only on Confirm",
        worldState: { option: otherCrop }, hard: true,
      }));
    expect(picked.changes).toBe(1);
    await clickUnverified(client, { id: "folkways.zoneconfig.confirm" });
    await expect.poll(() => zoneCount(server, registry), { timeout: 8000 }).toBe(3);
    expect(await zoneChoiceSettingAt(server, registry, "crop", 2)).not.toBe(defaultCrop);
    await expect.poll(async () => (await tokensOf(client)).includes("folkways.zoneconfig.value.crop"),
      { timeout: 8000 }).toBe(true);
    await clickElement(client, { id: "folkways.selection.back" });
    await expect.poll(async () => (await tokensOf(client)).includes("folkways.tab.residents"),
      { timeout: 8000 }).toBe(true);
    expect((await tokensOf(client)).includes("folkways.tab.zones")).toBe(false);

    await screen.dismiss(client);
    await setBookGesture(server, client, playerName, "line");
    await aimAndWorldLeftClick(server, client, playerName, spareBox.min);
    await aimAndWorldLeftClick(server, client, playerName, spareBox.max);
    await frames(client, 5);
    await run.shot(client, "path-world", {
      subject: "Path draft in the world", worldState: { box: spareBox }, hard: true,
    });
    await openColonyPanelViaBook(server, client, playerName, standBlock);
    await frames(client, 5);
    await run.shot(client, "path-types", {
      subject: "Path purpose picker (empty state when no purposes are registered)", worldState: {}, hard: true,
    });
    const paths = (await tokensOf(client)).filter(id => id.startsWith("folkways.zoneconfig.kind."));
    for (const id of paths) {
      await clickElement(client, { id });
      await frames(client, 5);
      await run.shot(client, `path-parameters-${id}`, {
        subject: `Path purpose options: ${id}`, worldState: { type: id }, hard: true,
      });
    }
    await screen.dismiss(client);
    await setBookGesture(server, client, playerName, "point");
    await captureZoneEditors(server, client, run, playerName);
    run.expectTaken(["02-zone-settings-farm", "03-drawing-question",
      "04-draft-parameters", "05-choice-picker", "path-world", "path-types",
      ...kinds.map(id => `parameters-${id}`), ...paths.map(id => `path-parameters-${id}`)]);
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
