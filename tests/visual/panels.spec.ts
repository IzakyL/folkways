import { launchPair, type E2EPair } from "../shared/pair";
import { expect, test } from "@playwright/test";
import { camera, input, player, screen, tick, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { clickElement, panelElements } from "../ldlib2";
import { foundColonyFast, openColonyPanelViaBook, optInViaBook, setBookGesture, waitForGroundedPlayer, type Vec } from "../e2e/colony-founding";
import { boardActs, enterSitePanel, firstEmptyGridSlot } from "../e2e/site-panel";
import { CONFIG_PATH, VisualRun } from "./visual-kit";
import { discoverPages, capturePageWindows, clickTab } from "./ui-catalog";
import { LayoutProbe } from "./layout-lint";
import { tracePath } from "../out-paths";
import { seededOffThreadServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { frames, safeAction, waitForAnyScreen, clickSlot, safeScreen, screenName, screenOpen, commandPos, tryCommand, waitForElement, clickUnverified, softly } from "../shared/bw-helpers";
import { newRunId } from "../shared/run-id";
import { stockColony, stockDispatch, stockRail } from "./colony-stock";

const BOOK_ITEM = "folkways:colony_book";
const TARGET_RESIDENTS = 6;
// Long enough for residents to take up the work the stocked colony offers, so "Current work" is not all unassigned.
const SETTLE_TICKS = 200;
const CITIZEN_ROW = "folkways.resident.row.";
const FARMING = "folkways.farming";
const PERSON_PAGE = "folkways.person";
const BREAD_RESULT = "folkways.selector.result.minecraft.bread";


test("panels and item models on camera", async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  const run = new VisualRun("panels");
  const runId = newRunId();
  let pair: E2EPair | undefined;
  let server: MinecraftServer;
  let client: MinecraftClient;
  try {
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: tracePath("visual", "panels-server"), instance: seededOffThreadServer(`visual-panels-server-${runId}`) },
      client: { trace: tracePath("visual", "panels-client"), instance: `visual-panels-client-${runId}` },
    });
    ({ server, client } = pair);

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    const origin: Vec = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    await tryCommand(server, `gamemode survival ${playerName}`);

    const wall: Vec = { x: origin.x, y: origin.y, z: origin.z };
    await tryCommand(server, `fill ${wall.x - 3} ${wall.y} ${wall.z - 3} ${wall.x + 3} ${wall.y + 4} ${wall.z + 3} minecraft:air`);
    await tryCommand(server, `fill ${wall.x - 3} ${wall.y - 1} ${wall.z - 2} ${wall.x + 3} ${wall.y + 4} ${wall.z - 2} minecraft:smooth_stone`);
    await tryCommand(server, `give ${playerName} ${BOOK_ITEM} 9`);
    await tryCommand(server, `item replace entity ${playerName} weapon.mainhand with ${BOOK_ITEM}`);
    await tryCommand(server, `tp ${playerName} ${wall.x + 0.5} ${wall.y} ${wall.z} 180 -5`);
    await tick.sprint(server, 10);
    await screen.dismiss(client);
    await safeAction(() => frames(client, 3));
    await run.shot(client, "01-book-first-person", {
      subject: "Colony book held in hand: viewed straight on",
      worldState: { item: BOOK_ITEM, pitch: -5 },
      hard: true,
    });

    await tryCommand(server, `tp ${playerName} ${wall.x + 0.5} ${wall.y} ${wall.z} 180 25`);
    await tick.sprint(server, 6);
    await safeAction(() => frames(client, 3));
    await run.shot(client, "02-book-first-person-down", {
      subject: "Colony book held in hand: looking down, the open spread is clearer",
      worldState: { item: BOOK_ITEM, pitch: 25 },
    });

    await setBookGesture(server, client, playerName, "box");
    await tryCommand(server, `tp ${playerName} ${wall.x + 0.5} ${wall.y} ${wall.z} 180 25`);
    await tick.sprint(server, 10);
    await screen.dismiss(client);
    await safeAction(() => frames(client, 30));
    await run.shot(client, "03-1-book-zone-first-person-down", {
      subject: "Same pose in box mode: identical cover, the page shows a boxed area instead of a pointer",
      worldState: { item: BOOK_ITEM, mode: "zone", pitch: 25 },
    });

    await setBookGesture(server, client, playerName, "line");
    await tryCommand(server, `tp ${playerName} ${wall.x + 0.5} ${wall.y} ${wall.z} 180 25`);
    await tick.sprint(server, 10);
    await screen.dismiss(client);
    await safeAction(() => frames(client, 30));
    await run.shot(client, "03-2-book-line-first-person-down", {
      subject: "Same pose in line mode: the page shows the clicked points and the line through them",
      worldState: { item: BOOK_ITEM, mode: "line", pitch: 25 },
    });
    await setBookGesture(server, client, playerName, "point");

    await openColonyPanelViaBook(server, client, playerName, origin);
    await waitForElement(client, { id: "folkways.colony.create" }, { timeoutMs: 8000 });
    await run.shot(client, "founding", {
      subject: "Blank colony book: create a colony", worldState: {}, hard: true,
    });
    await screen.dismiss(client);

    const founding = await foundColonyFast(server, client, playerName, { origin, residentCount: TARGET_RESIDENTS });
    run.note("colony", { origin: founding.origin, residents: founding.residents, stand: founding.standBlock });
    const colonyId = String(founding.evidence.colonyId);
    run.note("stocked", await stockColony(server, playerName, colonyId, founding.origin));
    run.note("dispatch", await stockDispatch(server, playerName, colonyId, founding.origin));
    const rail = await stockRail(server, client, playerName, founding.origin, founding.standBlock);
    run.note("rail", { carriage: rail.carriageUuid, stations: rail.stations, steps: rail.steps });
    await tick.sprint(server, SETTLE_TICKS);
    await screen.dismiss(client);
    const eye = { x: founding.origin.x + 2, y: founding.origin.y + 30, z: founding.origin.z - 1 };
    await run.shot(client, "04-colony-overview", {
      subject: "Top-down: a small founded colony with beds, stores, stations, fields, pens, a pond and two build sites",
      worldState: { origin: founding.origin, residents: founding.residents },
      camera: { dimension: "minecraft:overworld", position: eye, yaw: 0, pitch: 90, fov: 70 },
    });
    await safeAction(() => camera.reset(client));

    await openColonyPanelViaBook(server, client, playerName, founding.standBlock);
    await clickTab(client, "folkways.tab.settings");
    await safeAction(() => frames(client, 2));
    await clickElement(client, { id: "folkways.settings.reset_layout" });
    await safeAction(() => frames(client, 3));
    run.note("panel_tokens", await tokensOf(client));
    const expectedWindows = (await panelElements(client)).map(element => element.id)
      .filter((id): id is string => !!id?.startsWith("folkways.window.close."));
    const probe = new LayoutProbe(client);
    const tabs = await discoverPages(server);
    run.note("discovered_pages", tabs);
    for (const [index, tab] of tabs.entries()) {
      await clickTab(client, tab.token);
      await safeAction(() => frames(client, 3));
      await run.shot(client, `page-${tab.name}`, {
        subject: `Colony panel · ${tab.name} tab layout`,
        worldState: { tab: tab.name, token: tab.token, tokens: await tokensOf(client) },
        hard: true,
      });
      await run.lint(probe, `tab:${tab.name}`);
      await capturePageWindows(server, client, run, tab);
    }

    if (await run.hover(client, "folkways.panel.orders")) {
      await run.shot(client, "05-0-tab-hover", {
        subject: "Hovering a tab: page name and ponder hint on one line, drawn at the cursor",
        worldState: { token: "folkways.panel.orders" },
      });
    }

    await clickTab(client, "folkways.tab.residents");
    await safeAction(() => frames(client, 3));
    const citizen = firstToken(await tokensOf(client), CITIZEN_ROW);
    const citizenId = citizen ? citizen.slice(CITIZEN_ROW.length) : null;

    const farmingCell = citizenId ? `folkways.resident.vocation.${FARMING}.${citizenId}` : null;
    if (farmingCell && (await run.hover(client, farmingCell))) {
      await run.shot(client, "05-1a-citizens-priority-hover", {
        subject: "Citizens · hovering a priority cell: a centered inset square in the cell, plus everything that job means for this person: level, and each perk in the pool with its tier and effect",
        worldState: { token: farmingCell },
      });
    }

    if (citizen && (await run.hover(client, citizen))) {
      await run.shot(client, "05-1b-citizens-row-hover", {
        subject: "Citizens · hovering a resident row: the name opens details, priority cells adjust work assignment",
        worldState: { token: citizen },
      });
      await clickElement(client, { id: citizen.replace("resident.row.", "resident.name.") });
      await softly(() => waitForElement(client, { id: "folkways.resident.names.apply" }, { timeoutMs: 8_000 }));
      await safeAction(() => frames(client, 3));
      await run.shot(client, "05-1c-citizen-window", {
        subject: "The resident's own window: given name and surname fields, look < >, and each growth track's level with the tier and effect of every perk in its pool",
        worldState: { citizen: citizenId, tokens: await tokensOf(client) },
      });
      await run.lint(probe, "window:citizen");
      await clickElement(client, { id: "folkways.resident.back" });
      await safeAction(() => frames(client, 2));
    }

    await clickTab(client, "folkways.tab.settings");
    await safeAction(() => frames(client, 2));
    if (await run.hover(client, "folkways.metrics.raze")) {
      await run.shot(client, "05-5a-metrics-raze-hover", {
        subject: "Settings · hovering Delete colony: the only warning-colored button on the panel",
        worldState: { token: "folkways.metrics.raze" },
      });
    }
    await clickElement(client, { id: "folkways.metrics.raze" });
    await softly(() => waitForElement(client, { id: "folkways.metrics.raze.confirm" }, { timeoutMs: 8_000 }));
    await safeAction(() => frames(client, 3));
    await run.shot(client, "05-5b-metrics-raze-confirm", {
      subject: "Delete colony confirmation: what it costs, the phrase to type, and the red button that is still disabled",
      worldState: { tokens: await tokensOf(client) },
      hard: true,
    });
    await run.lint(probe, "page:raze-confirm");
    await clickElement(client, { id: "folkways.metrics.raze.back" });
    await safeAction(() => frames(client, 2));

    await clickTab(client, "folkways.page.person");
    await tick.sprint(server, 5);
    await safeAction(() => frames(client, 3));
    const opens = await boardActs(client, "open", PERSON_PAGE);
    run.note("person_acts", { open: opens.map((act) => act.id) });
    expect(
      opens.length,
      "the human residents page should have Open buttons for the name pool and then the look pool (PersonPresence always draws both). "
        + `There are fewer, so the board act token no longer matches. The page had: ${JSON.stringify(await tokensOf(client))}`,
    ).toBeGreaterThan(1);
    const looksOpen = opens[1];
    await clickElement(client, { id: looksOpen.id });
    await tick.sprint(server, 5);
    await safeAction(() => frames(client, 3));
    await run.shot(client, "05-6-person-looks", {
      subject: "Look pool window: a face and a toggle per entry, with the person page still behind it so both can be seen side by side",
      worldState: { via: looksOpen.id, tokens: await tokensOf(client) },
      hard: true,
    });
    await run.lint(probe, "window:looks");
    await clickUnverified(client, { id: "folkways.window.close.folkways.looks" });
    await expect.poll(async () => (await panelElements(client))
      .find(element => element.id === "folkways.window.folkways.looks")?.displayed,
      { timeout: 5_000 }).toBe(false);
    await tick.sprint(server, 5);
    await safeAction(() => frames(client, 3));

    await clickElement(client, { id: opens[0].id });
    await tick.sprint(server, 5);
    await safeAction(() => frames(client, 3));
    await run.shot(client, "05-7-person-names", {
      subject: "Name pool window: given names or surnames a page at a time with a delete button each, a field to add one, and the data pack sets that replace the pool",
      worldState: { via: opens[0].id, tokens: await tokensOf(client) },
    });
    await run.lint(probe, "window:names");
    await clickUnverified(client, { id: "folkways.window.close.folkways.names" });
    await expect.poll(async () => (await panelElements(client))
      .find(element => element.id === "folkways.window.folkways.names")?.displayed,
      { timeout: 5_000 }).toBe(false);
    await tick.sprint(server, 5);
    await safeAction(() => frames(client, 3));

    await clickTab(client, "folkways.tab.settings");
    await safeAction(() => frames(client, 2));
    await clickElement(client, { id: "folkways.settings.open.tool" });
    await softly(() => waitForElement(client, { id: "folkways.settings.back" }, { timeoutMs: 8_000 }));
    await tick.sprint(server, 5);
    await safeAction(() => frames(client, 3));
    await run.shot(client, "05-7-settings-tool", {
      subject: "Settings · tool list: the grid is the list, and whatever a slot holds is one entry",
      worldState: { tokens: await tokensOf(client) },
    });
    await run.lint(probe, "window:item-list");

    const emptyGridSlot = await firstEmptyGridSlot(client);
    await softly(() => clickSlot(client, emptyGridSlot));
    await softly(() => waitForElement(client, { id: "folkways.selector.close" }, { timeoutMs: 8_000 }));
    await clickUnverified(client, { id: "folkways.selector.search" });
    await safeAction(() => input.key(client, { text: "bread" }));
    await safeAction(() => frames(client, 3));
    await run.shot(client, "06-item-selector", {
      subject: "Shared item picker: search box plus result rows; every item list and the zone config slots use this same picker",
      worldState: { query: "bread", slot: emptyGridSlot, tokens: await tokensOf(client) },
    });
    await run.lint(probe, "window:selector");
    await waitForElement(client, { id: BREAD_RESULT }, { timeoutMs: 8_000 });
    await run.hover(client, BREAD_RESULT);
    await run.shot(client, "06a-item-selector-hover", {
      subject: "Picker · hovering a result row: the row highlight, plus the item's own name",
      worldState: { token: BREAD_RESULT },
      hard: true,
    });
    await screen.dismiss(client);

    await openColonyPanelViaBook(server, client, playerName, founding.standBlock);
    await run.clip(client, server, "07-panel-tour", {
      subject: "Panel live footage: walk the tab strip end to end, watching layout switches and element rebuilds",
      worldState: { tabs: tabs.map((tab) => tab.token) },
      seconds: 20,
      during: async () => {
        for (const tab of tabs) {
          await clickTab(client, tab.token);
          await safeAction(() => frames(client, 12));
        }
      },
    });
    await screen.dismiss(client);

    const chest: Vec = { x: founding.origin.x + 4, y: founding.origin.y, z: founding.origin.z + 10 };
    await tryCommand(server, `setblock ${commandPos(chest)} minecraft:chest{Items:[]}`);
    await tick.sprint(server, 3);
    await optInViaBook(server, client, playerName, chest, "chest");
    await tryCommand(server, `item replace entity ${playerName} weapon.mainhand with minecraft:air`);
    await tryCommand(server, `tp ${playerName} ${chest.x + 0.5} ${chest.y} ${chest.z + 1.5} 180 30`);
    await tick.sprint(server, 5);
    await screen.dismiss(client);
    await safeAction(() =>
      player.useBlock(client, { x: chest.x, y: chest.y, z: chest.z }, { face: "up" }),
    );
    await waitForAnyScreen(client, 8_000);
    await safeAction(() => frames(client, 3));
    await run.shot(client, "08-container-node-entry", {
      subject: "The vanilla chest screen opens as usual, with this chest's colony panel shown right beside it",
      worldState: { chest, screen: screenName(await safeScreen(client)), tokens: await tokensOf(client) },
      hard: true,
    });
    await enterSitePanel(server, client);
    await safeAction(() => frames(client, 3));
    await run.shot(client, "08b-container-site-panel", {
      subject: "Side panel next to the chest slots: edit this chest's settings directly, no button needed",
      worldState: { chest, screen: screenName(await safeScreen(client)), tokens: await tokensOf(client) },
      hard: true,
    });
    await run.lint(probe, "page:site");
    await screen.dismiss(client);

    await tryCommand(server, `give ${playerName} ${BOOK_ITEM}`);
    await tick.sprint(server, 3);
    await openInventory(client);
    await run.shot(client, "09-inventory", {
      subject: "Inventory screen: the book's 2D sprite in a slot",
      worldState: { screen: screenName(await safeScreen(client)), tokens: await tokensOf(client) },
    });
    await screen.dismiss(client);
    run.expectTaken([
      ...tabs.map((tab, index) => `page-${tab.name}`),
      "05-1c-citizen-window", "05-5b-metrics-raze-confirm", "05-6-person-looks",
      "05-7-settings-tool", "06-item-selector", "06a-item-selector-hover",
      "08b-container-site-panel",
    ]);
    run.expectLinted([
      ...tabs.map((tab) => `tab:${tab.name}`),
      "window:citizen", "window:looks", "window:names", "window:item-list", "window:selector",
      "page:raze-confirm", "page:site",
    ]);
    run.expectWindows(expectedWindows);
    run.expectLintClean();
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

function firstToken(tokens: string[], prefix: string): string | null {
  return tokens.find((token) => token.startsWith(prefix) && token.split(".").length === prefix.split(".").length) ?? null;
}

async function tokensOf(client: MinecraftClient): Promise<string[]> {
  const elements = await softly(() => panelElements(client));
  const widgets = await softly(() => screen.widgets(client));
  return [
    ...(Array.isArray(elements) ? elements : []).map((element) => element.id),
    ...(Array.isArray(widgets) ? widgets : []).map((widget) => widget.id),
  ].filter((id: unknown): id is string => typeof id === "string");
}

async function openInventory(client: MinecraftClient) {
  await screen.dismiss(client);
  await safeAction(() => input.key(client, { keybind: "key.inventory", action: "press" }));
  await safeAction(() => input.key(client, { keybind: "key.inventory", action: "release" }));
  await waitForAnyScreen(client, 8_000);
  await safeAction(() => frames(client, 3));
}
