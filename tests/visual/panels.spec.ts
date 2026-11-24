import { launchPair, type E2EPair } from "../shared/harness/pair";
import { expect, test } from "@playwright/test";
import { camera, defineTask, input, javaTask, screen, tick, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { clickElement, panelElements } from "../ldlib2";
import { foundColonyFast, openColonyPanelViaBook, optInViaBook, setBookGesture, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { cardRows, facilityAt, openBlockCard, openFacilityInBook, waitForPresent } from "../shared/ui/book-cards";
import { MARK_MEMBERS, runColonyTask } from "../shared/colony/colony-tasks";
import { ORDERS_HERE, setMaintainDemandViaUI } from "../shared/colony/membership-optin";
import { showVocationColumn } from "../shared/ui/vocation-groups";
import { CONFIG_PATH, VisualRun } from "../shared/capture/visual-kit";
import { BOOK_PAGES, capturePageWindows, clickTab, openSettingsScope, ZONE_FIXTURES } from "./ui-catalog";
import { LayoutProbe } from "../shared/capture/layout-lint";
import { tracePath } from "../shared/harness/out-paths";
import { seededOffThreadServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { frames, safeAction, waitForAnyScreen, clickSlot, safeScreen, screenName, screenOpen, commandPos, tryCommand, waitForElement, clickUnverified, softly } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { waitForResourceReload } from "../shared/harness/resource-reload";
import { stockDispatch, stockRail } from "./colony-stock";
import { openEditor } from "../shared/ui/colony-pages";

const BOOK_ITEM = "folkways_core:colony_book";
const TARGET_RESIDENTS = 6;
// Long enough for residents to take up the work the stocked colony offers, so "Current work" is not all unassigned.
const SETTLE_TICKS = 200;
const CITIZEN_ROW = "folkways.resident.row.";
const FARMING = "folkways_vanilla.farming";
const BREAD_RESULT = "folkways.selector.result.minecraft.bread";
const LOCALES = ["en_us", "zh_cn"] as const;
const GUI_SCALE = 3;

const LANGUAGE = defineTask<{ code: string }, { ok: true }>({
  name: "folkways.panel-layout.language",
  side: "client",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.client.Minecraft"],
    body: `
var mc = Minecraft.getInstance();
String code = Args.of(ctx).string("code");
mc.getLanguageManager().setSelected(code);
mc.options.languageCode = code;
mc.reloadResourcePacks();
return Map.of("ok", true);
`,
  }),
});

// An equipment profile carrying a bow, so the equipment editor has an entry to open and a profile to assign.
const EQUIPMENT_PROFILE = defineTask<void, { profile: string }>({
  name: "folkways.panel-layout.equipment-profile",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["io.github.izakyl.folkways.core.colony.api.Colonies",
      "io.github.izakyl.folkways.core.equipment.api.EquipmentProfile",
      "io.github.izakyl.folkways.core.equipment.api.EquipmentProfiles",
      "io.github.izakyl.folkways.core.item.api.ItemSpec", "net.minecraft.world.item.Items"],
    body: `
var profiles = EquipmentProfiles.of(Colonies.all(ctx.server()).getFirst());
var profile = profiles.create("Archers");
profiles.add(profile.id(), EquipmentProfile.Entry.carried(ItemSpec.of(Items.BOW), 1, 1));
return Map.of("profile", profile.id().toString());
`,
  }),
});

const SETTLED = defineTask<{ code: string; scale: number }, { settled: boolean; width: number }>({
  name: "folkways.panel-layout.settled",
  side: "client",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.client.Minecraft"],
    body: `
var mc = Minecraft.getInstance();
Args args = Args.of(ctx);
int scale = (int) args.integer("scale");
if (mc.options.guiScale().get() != scale) {
    mc.options.guiScale().set(scale);
    mc.resizeDisplay();
}
boolean settled = mc.getOverlay() == null && mc.getLanguageManager().getSelected().equals(args.string("code"));
return Map.of("settled", settled, "width", mc.getWindow().getGuiScaledWidth());
`,
  }),
});


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

    await openColonyPanelViaBook(server, client, playerName, origin);
    await waitForElement(client, { id: "folkways.colony.create" }, { timeoutMs: 8000 });
    await run.shot(client, "founding", {
      subject: "Blank colony book: create a colony", worldState: {}, hard: true,
    });
    await screen.dismiss(client);

    const founding = await foundColonyFast(server, client, playerName, { origin, residentCount: TARGET_RESIDENTS });
    run.note("colony", { origin: founding.origin, residents: founding.residents, stand: founding.standBlock });
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

    const colonyId = String(founding.evidence.colonyId);
    const stocked = await stockBookPages(server, client, playerName, colonyId, founding.origin);
    run.note("stocked", stocked);
    run.note("dispatch", await stockDispatch(server, playerName, colonyId, founding.origin));
    const rail = await stockRail(server, client, playerName, founding.origin, founding.standBlock);
    run.note("rail", { carriage: rail.carriageUuid, stations: rail.stations, steps: rail.steps });
    await tick.sprint(server, SETTLE_TICKS);
    await screen.dismiss(client);
    const eye = { x: founding.origin.x + 2, y: founding.origin.y + 30, z: founding.origin.z - 1 };
    await run.shot(client, "04-colony-overview", {
      subject: "Top-down: a founded colony with beds, stores, stations, and transport facilities",
      worldState: { origin: founding.origin, residents: founding.residents },
      camera: { dimension: "minecraft:overworld", position: eye, yaw: 0, pitch: 90, fov: 70 },
    });
    await safeAction(() => camera.reset(client));

    run.note("equipment", await server.run(EQUIPMENT_PROFILE, undefined));
    await openColonyPanelViaBook(server, client, playerName, founding.standBlock);
    await clickTab(client, "folkways.tab.settings");
    await safeAction(() => frames(client, 2));
    await clickElement(client, { id: "folkways.settings.reset_layout" }, { scrollIntoView: true });
    await safeAction(() => frames(client, 3));
    run.note("panel_tokens", await tokensOf(client));
    const expectedWindows = (await panelElements(client)).map(element => element.id)
      .filter((id): id is string => !!id?.startsWith("folkways.window.close."));
    const probe = new LayoutProbe(client);
    const tabs = BOOK_PAGES;
    run.note("book_pages", tabs);
    for (const tab of tabs) {
      await clickTab(client, tab.token);
      await tick.sprint(server, 5);
      await safeAction(() => frames(client, 3));
      await run.shot(client, `page-${tab.name}`, {
        subject: `Colony panel · ${tab.name} tab layout`,
        worldState: { tab: tab.name, token: tab.token, tokens: await tokensOf(client) },
        hard: true,
      });
      await run.lint(probe, `tab:${tab.name}`);
      await capturePageWindows(server, client, run, tab);
      if (tab.token === "folkways.tab.facilities") {
        const facility = await facilityAt(server, playerName, stocked.stores[0]);
        expect(facility, "the stocked chest must have a facility card").not.toBeNull();
        await openFacilityInBook(server, client, facility!);
        await run.shot(client, "page-facilities-store-card", {
          subject: "The Facilities page opens a store's card beside the book",
          worldState: { facility, tokens: await tokensOf(client) }, hard: true,
        });
        await clickUnverified(client, { id: "folkways.window.close.book_card" });
        await frames(client, 4);
      }
    }

    if (await run.hover(client, "folkways.tab.metrics")) {
      await run.shot(client, "05-0-tab-hover", {
        subject: "Hovering a tab: page name and ponder hint on one line, drawn at the cursor",
        worldState: { token: "folkways.tab.metrics" },
      });
    }

    await clickTab(client, "folkways.tab.residents");
    await safeAction(() => frames(client, 3));
    const citizen = firstToken(await tokensOf(client), CITIZEN_ROW);
    const citizenId = citizen ? citizen.slice(CITIZEN_ROW.length) : null;

    const farmingCell = citizenId ? `folkways.resident.vocation.${FARMING}.${citizenId}` : null;
    const gatheringCell = citizenId ? `folkways.resident.group.folkways_core.gathering.${citizenId}` : null;
    if (gatheringCell && (await run.hover(client, gatheringCell))) {
      await run.shot(client, "05-1a0-citizens-group-hover", {
        subject: "Citizens · hovering a vocation group's cell: how each of its vocations stands, and that a click or the wheel sets them all",
        worldState: { token: gatheringCell },
      });
    }
    if (farmingCell) {
      await safeAction(() => showVocationColumn(server, client, "folkways.resident", FARMING.replace(".", ":"), farmingCell));
    }
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

    // The Residents page opens the colony-wide editors, each in a window beside the book.
    await clickTab(client, "folkways.tab.residents");
    await safeAction(() => frames(client, 3));
    await run.shot(client, "05-2-residents-editors", {
      subject: "Residents page: a button for each colony-wide editor (default work, equipment and vehicle profiles, name and look pools) above the roster",
      worldState: { tokens: await tokensOf(client) }, hard: true,
    });
    await openEditor(client, "folkways_vanilla.looks");
    await clickElement(client, { id: "folkways.pool.looks.choose" });
    await frames(client, 3);
    await run.shot(client, "05-6-person-looks", {
      subject: "Look pool window: a face and a toggle per entry, with the Residents page still behind it so both can be seen side by side",
      worldState: { tokens: await tokensOf(client) },
      hard: true,
    });
    await run.lint(probe, "window:looks");
    await clickElement(client, { id: "folkways.pool.looks.close" });
    await closeWindow(server, client, "folkways_vanilla.looks");

    await openEditor(client, "folkways_vanilla.names");
    await run.shot(client, "05-6b-person-names", {
      subject: "Name pool window: given names or surnames a page at a time with a delete button each, a field to add one, and the data pack sets that replace the pool",
      worldState: { tokens: await tokensOf(client) },
    });
    await run.lint(probe, "window:names");
    await closeWindow(server, client, "folkways_vanilla.names");

    await openEditor(client, "folkways_core.equipment");
    await clickElement(client, { id: "folkways.equipment.profile.0" }, { scrollIntoView: true });
    await tick.sprint(server, 3);
    await waitForPresent(server, client, "folkways.equipment.carried.0");
    await clickElement(client, { id: "folkways.equipment.carried.0" }, { scrollIntoView: true });
    await frames(client, 4);
    await run.shot(client, "05-6c-equipment-entry", {
      subject: "Equipment profiles: a profile's carried bow opened in the entry window beside the editor",
      worldState: { tokens: await tokensOf(client) }, hard: true,
    });
    await run.lint(probe, "window:equipment");
    await closeWindow(server, client, "equipment_entry");
    await clickElement(client, { id: "folkways.equipment.assign_residents" }, { scrollIntoView: true });
    await frames(client, 4);
    await run.shot(client, "05-6d-equipment-residents", {
      subject: "Equipment profiles: every resident with the profile they wear, each a button choosing another",
      worldState: { tokens: await tokensOf(client) },
    });
    await closeWindow(server, client, "equipment_residents");
    await closeWindow(server, client, "folkways_core.equipment");

    await clickTab(client, "folkways.tab.settings");
    await safeAction(() => frames(client, 2));
    if (await run.hover(client, "folkways.metrics.raze")) {
      await run.shot(client, "05-5a-metrics-raze-hover", {
        subject: "Settings · hovering Delete colony: the only warning-colored button on the panel",
        worldState: { token: "folkways.metrics.raze" },
      });
    }
    await clickElement(client, { id: "folkways.metrics.raze" }, { scrollIntoView: true });
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

    await openSettingsScope(server, client, "folkways_vanilla:composting", "folkways.card.flag.enabled");
    await tick.sprint(server, 5);
    await safeAction(() => frames(client, 3));
    await run.shot(client, "05-8-settings-compost", {
      subject: "Settings · Composting: the switch and default seed reserve, then a row per seed kind with its own reserve",
      worldState: { tokens: await tokensOf(client) }, hard: true,
    });

    const tools = await openSettingsScope(server, client, "folkways_vanilla:building", "folkways.card.items.tool");
    await clickElement(client, tools, { scrollIntoView: true });
    await softly(() => waitForElement(client, { id: "folkways.selector.close" }, { timeoutMs: 8_000 }));
    await tick.sprint(server, 5);
    await safeAction(() => frames(client, 3));
    await run.shot(client, "05-7-settings-tool", {
      subject: "Building tools: the item picker, a folder for each mod and, in it, each tag; the rules held below",
      worldState: { tokens: await tokensOf(client) },
    });
    await run.lint(probe, "window:selector-tree");

    await clickUnverified(client, { id: "folkways.selector.search" });
    await safeAction(() => input.key(client, { text: "bread" }));
    await safeAction(() => frames(client, 3));
    await run.shot(client, "06-item-selector", {
      subject: "Shared item picker: a search over every item, and the search kept as a rule; every item setting uses it",
      worldState: { query: "bread", tokens: await tokensOf(client) },
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
    await openBlockCard(server, client, playerName, chest);
    await run.shot(client, "08-container-card", {
      subject: "Using the book on a colony chest opens its facility card with storage controls",
      worldState: { chest, tokens: await tokensOf(client) }, hard: true,
    });
    await run.lint(probe, "page:site");
    await screen.dismiss(client);

    // The first store holds three orders, each listed on its card.
    const store = stocked.stores[0];
    await openBlockCard(server, client, playerName, store);
    await waitForPresent(server, client, `folkways.card.rows.${ORDERS_HERE}.2`);
    expect(await cardRows(client, ORDERS_HERE)).toHaveLength(3);
    await run.shot(client, "08c-store-card-orders", {
      subject: "A store with three orders: its card lists each order",
      worldState: { store, screen: screenName(await safeScreen(client)), tokens: await tokensOf(client) },
      hard: true,
    });
    await run.lint(probe, "page:site-orders");
    await screen.dismiss(client);

    await tryCommand(server, `give ${playerName} ${BOOK_ITEM}`);
    await tick.sprint(server, 3);
    await openInventory(client);
    await run.shot(client, "09-inventory", {
      subject: "Inventory screen: the book's 2D sprite in a slot",
      worldState: { screen: screenName(await safeScreen(client)), tokens: await tokensOf(client) },
    });
    await screen.dismiss(client);

    // The tabs above are linted in English at the default scale. Players run Chinese, whose words do not break, on
    // a large interface: 1280x720 at scale 3 is 427 wide, about what 1920x1080 at its automatic scale 4 leaves.
    // There the list of pages folds behind the menu button and the cards go one to a line, and a label that only
    // fits in English must still fit beside its neighbours. Advance time to fill the Residents page queue.
    await tryCommand(server, "time add 240000");
    await tick.sprint(server, 200);
    for (const code of LOCALES) {
      await client.run(LANGUAGE, { code });
      await waitForResourceReload(client);
      await settle(client, code);
      await openColonyPanelViaBook(server, client, playerName, founding.standBlock);
      // A probe of its own: it keeps the screen size and text widths it measured.
      const scaled = new LayoutProbe(client);
      for (const tab of tabs) {
        await clickTab(client, tab.token);
        await safeAction(() => frames(client, 3));
        await run.lint(scaled, `${code}@${GUI_SCALE}:${tab.name}`);
      }
      await screen.dismiss(client);
    }
    run.expectTaken([
      ...tabs.map((tab) => `page-${tab.name}`),
      "05-1c-citizen-window", "05-2-residents-editors", "05-6-person-looks", "05-6b-person-names",
      "05-6c-equipment-entry", "05-6d-equipment-residents", "05-5b-metrics-raze-confirm", "05-8-settings-compost",
      "05-7-settings-tool", "06-item-selector", "06a-item-selector-hover",
      "page-facilities-store-card", "08-container-card", "08c-store-card-orders",
    ]);
    run.expectLinted([
      ...tabs.map((tab) => `tab:${tab.name}`),
      "window:citizen", "window:looks", "window:names", "window:equipment", "window:selector-tree", "window:selector",
      "page:raze-confirm", "page:site",
      ...LOCALES.flatMap((code) => tabs.map((tab) => `${code}@${GUI_SCALE}:${tab.name}`)),
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

async function stockBookPages(
  server: MinecraftServer, client: MinecraftClient, player: string, colonyId: string, origin: Vec,
) {
  const at = (x: number, z: number) => ({ x: origin.x + x, y: origin.y, z: origin.z + z });
  const stores = [at(0, 3), at(2, 3), at(4, 3)];
  const stations = [at(0, 6), at(2, 6)];
  for (const [block, state] of [
    [stores[0], `minecraft:chest{Items:[{Slot:0b,id:"minecraft:bread",count:8}]}`],
    [stores[1], `minecraft:chest{Items:[{Slot:0b,id:"minecraft:oak_planks",count:64}]}`],
    [stores[2], `minecraft:chest{Items:[{Slot:0b,id:"minecraft:iron_ingot",count:16}]}`],
    [stations[0], "minecraft:furnace"],
    [stations[1], "minecraft:crafting_table"],
  ] as const) {
    await tryCommand(server, `setblock ${commandPos(block)} ${state}`);
  }
  await runColonyTask(server, MARK_MEMBERS, {
    player, colony_id: colonyId, cells: [...stores, ...stations].flatMap(block => [block.x, block.y, block.z]),
  });
  for (const [store, item, total, low] of [
    [stores[0], "minecraft:bread", 64, 16],
    [stores[1], "minecraft:oak_planks", 192, 64],
    [stores[2], "minecraft:iron_ingot", 32, 8],
    [stores[0], "minecraft:cooked_mutton", 32, 8],
    [stores[0], "minecraft:baked_potato", 48, 12],
  ] as const) {
    await setMaintainDemandViaUI(server, client, player, store, item, total, undefined, low);
  }
  await screen.dismiss(client);
  await tryCommand(server, `tp ${player} ${commandPos(at(-12, -10))}`);
  await tick.sprint(server, 5);
  const { zones } = await runColonyTask(server, ZONE_FIXTURES, { player });
  return { stores, stations, zones };
}

async function closeWindow(server: MinecraftServer, client: MinecraftClient, id: string) {
  await clickUnverified(client, { id: `folkways.window.close.${id}` });
  await expect.poll(async () => (await panelElements(client))
    .find(element => element.id === `folkways.window.${id}`)?.displayed ?? false, { timeout: 5_000 }).toBe(false);
  await tick.sprint(server, 3);
  await safeAction(() => frames(client, 3));
}

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

async function settle(client: MinecraftClient, code: string) {
  for (let attempt = 0; attempt < 120; attempt++) {
    const state = await client.run(SETTLED, { code, scale: GUI_SCALE });
    if (state.settled) {
      return;
    }
    await frames(client, 5);
  }
  throw new Error(`the client never finished switching to ${code} at scale ${GUI_SCALE}`);
}
