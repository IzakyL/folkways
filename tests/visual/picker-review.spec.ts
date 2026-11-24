import { test } from "@playwright/test";
import { defineTask, input, javaTask, player as players, screen, tick, world } from "@izakyl/blockwright-minecraft";
import { copyFileSync, mkdirSync } from "node:fs";
import path from "node:path";
import { ancestorsOf, clickElement, hoverElement, panelElements, typeIntoElement } from "../ldlib2";
import {
  chooseBookTool, foundColonyFast, foundingBox, openColonyPanelViaBook, waitForGroundedPlayer, type Vec,
} from "../shared/colony/colony-founding";
import { CONFIG_PATH, VisualRun } from "../shared/capture/visual-kit";
import { QUIET_TOASTS } from "../shared/capture/ponder";
import { BOOK_PAGES, clickTab, openSettingsScope, ZONE_FIXTURES } from "./ui-catalog";
import { openCardOf, waitForPresent } from "../shared/ui/book-cards";
import { openResidentDetail } from "../shared/ui/colony-pages";
import { markCorner, useBook } from "../shared/ui/zone-tools";
import { FIRST_CORNER, ZONE_MARKED, drainActionBar } from "../shared/ui/zone-receipt";
import { aimAndWorldLeftClick } from "../shared/ui/world-ui";
import { settlePanel } from "../shared/ui/site-panel";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { tracePath } from "../shared/harness/out-paths";
import { seededOffThreadServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { GROUND } from "../shared/harness/stage";
import { commandPos, frames, safeAction, tryCommand, waitForElement } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";

// Every dialog the unified picker draws, one shot each with the page that opened it behind: a reader's tour after
// a change to Picker, not an assertion of layout. A scene that cannot be reached is reported at the end.

const BOOK_ITEM = "folkways_core:colony_book";
const HUT_ASSET = "tests/shared/assets/ghost-hut.nbt";

const OPEN_SELECTOR = defineTask<void, boolean>({
  name: "folkways.visual.picker-review.selector", side: "client",
  source: javaTask({
    imports: ["io.github.izakyl.folkways.core.front.client.SelectorWindow"],
    body: `SelectorWindow.open(null, stack -> {}); return true;`,
  }),
});

// The blueprint shelf the overhead placing tool opens on a screen of its own.
const OPEN_HELD_BLUEPRINTS = defineTask<void, boolean>({
  name: "folkways.visual.picker-review.held-blueprints", side: "client",
  source: javaTask({ body: `
var held = Class.forName("io.github.izakyl.folkways.vanilla.build.HeldBlueprints");
var pick = held.getDeclaredMethod("pick"); pick.setAccessible(true); pick.invoke(null); return true;
` }),
});

// A list of options some of which wear an item and some of which name none: the names stand in one column.
const OPEN_MIXED = defineTask<void, boolean>({
  name: "folkways.visual.picker-review.mixed", side: "client",
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.core.front.client.SelectorWindow",
    "io.github.izakyl.folkways.core.front.api.ui.Picker",
    "net.minecraft.network.chat.Component",
    "net.minecraft.world.item.ItemStack",
    "net.minecraft.world.item.Items",
  ], body: `
SelectorWindow.open(null,Component.literal("Mixed"),root->Picker.openMany(root,Component.literal("Mixed"),List.of(
 new Picker.Row("folkways_vanilla:archer",Component.literal("Archer (bow)"),new ItemStack(Items.BOW)),
 new Picker.Row("guard",Component.literal("Guard (no item)"),ItemStack.EMPTY),
 new Picker.Row("folkways_vanilla:builder",Component.literal("Builder (bricks)"),new ItemStack(Items.BRICKS)),
 new Picker.Row("scout",Component.literal("Scout (no item)"),ItemStack.EMPTY)),java.util.Set.of("guard"),picked->{}));
return true;
` }),
});

const NARROW = defineTask<void, boolean>({
  name: "folkways.visual.picker-review.narrow", side: "client",
  source: javaTask({ imports: ["net.minecraft.client.Minecraft"], body: `
var mc=Minecraft.getInstance();mc.getWindow().setWindowed(640,800);mc.options.guiScale().set(2);mc.resizeDisplay();
return true;
` }),
});

test("every picker dialog on camera", async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy * 2);
  const run = new VisualRun("picker-review");
  const failures: string[] = [];
  let pair: E2EPair | undefined;
  try {
    const runId = newRunId();
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: tracePath("visual", "picker-review-server"), instance: seededOffThreadServer(`visual-picker-review-server-${runId}`) },
      client: { trace: tracePath("visual", "picker-review-client"), instance: `visual-picker-review-client-${runId}` },
    });
    const { server, client } = pair;
    const grounded = await waitForGroundedPlayer(server);
    const player = grounded.name;
    const origin: Vec = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    await tryCommand(server, `op ${player}`);
    await tryCommand(server, `gamemode survival ${player}`);
    await GROUND.lay(server, foundingBox(origin));
    const founding = await foundColonyFast(server, client, player, { origin, residentCount: 2 });
    await client.run(QUIET_TOASTS);

    const settle = async (n = 3) => {
      await tick.sprint(server, 3);
      await safeAction(() => frames(client, n));
    };
    const shown = async () => {
      let elements: Awaited<ReturnType<typeof panelElements>>;
      try {
        elements = await panelElements(client);
      } catch {
        return [];
      }
      return elements.filter(element => element.id && element.width > 0
        && [element, ...ancestorsOf(elements, element)].every(one => one.displayed !== false && one.visible !== false));
    };
    // A picker is open when its search field and its cancel button both show; the token is what they share.
    const openPicker = async () => {
      const ids = new Set((await shown()).map(element => element.id!));
      for (const id of ids) {
        if (!id.endsWith(".search")) continue;
        const token = id.slice(0, -".search".length);
        if (ids.has(`${token}.close`) || ids.has(`${token}.cancel`)) return token;
      }
      return null;
    };
    const closePickers = async () => {
      for (let n = 0; n < 4; n++) {
        const token = await openPicker();
        if (!token) return;
        const ids = new Set((await shown()).map(element => element.id!));
        await safeAction(() => clickElement(client, { id: ids.has(`${token}.close`) ? `${token}.close` : `${token}.cancel` }));
        await settle();
      }
    };
    const closeWindows = async () => {
      const windows = (await shown()).filter(element => element.id!.startsWith("folkways.window.close.")
        && element.id !== "folkways.window.close.colony");
      for (const window of windows.reverse()) {
        await safeAction(() => clickElement(client, { id: window.id! }));
        await settle();
      }
    };
    const shot = async (name: string, subject: string, worldState: Record<string, unknown> = {}) => {
      await input.move(client, { x: 0, y: 0 });
      await safeAction(() => frames(client, 3));
      await run.shot(client, name, { subject, worldState: { ...worldState, picker: await openPicker() }, hard: true });
    };
    const click = async (id: string) => {
      await clickElement(client, { id }, { scrollIntoView: true });
      await settle();
    };
    const expectPicker = async (opener: string) => {
      for (let n = 0; n < 20; n++) {
        const token = await openPicker();
        if (token) return token;
        await settle(2);
      }
      throw new Error(`${opener} opened no picker`);
    };
    const panel = async () => {
      await safeAction(() => screen.dismiss(client));
      await settle();
      await tryCommand(server, `item replace entity ${player} weapon.mainhand with ${BOOK_ITEM}`);
      await openColonyPanelViaBook(server, client, player, founding.standBlock);
      await settle();
    };
    const scene = async (name: string, body: () => Promise<void>) => {
      try {
        await body();
      } catch (error) {
        failures.push(`${name}: ${error instanceof Error ? error.message : String(error)}`);
        await safeAction(() => run.shot(client, `failed-${name}`, { subject: `Scene ${name} failed here`, worldState: {} }));
      } finally {
        await closePickers();
      }
    };
    const token = (id: string) => id.replace(/^folkways\./, "").replace(/[^a-z0-9_.-]/gi, "_");

    await scene("selector-window", async () => {
      await client.run(OPEN_SELECTOR);
      await settle();
      await expectPicker("SelectorWindow");
      await shot("selector-window", "SelectorWindow: the independent item picker, every item filed by mod");
      await typeIntoElement(client, { id: "folkways.selector.search" }, "oak");
      await settle();
      await shot("selector-window-search", "SelectorWindow: a search for oak");
      await click("folkways.selector.close");
    });

    await scene("mixed-icons", async () => {
      await client.run(OPEN_MIXED);
      await settle();
      await expectPicker("mixed icons");
      await shot("picker-mixed-icons", "Options with and without an item: blank icon places keep every name in one column");
      await click("folkways.choice.cancel");
    });

    await scene("held-blueprints", async () => {
      const shelf = path.join((server as any).instance.instanceDir, "folkways", "blueprint");
      mkdirSync(shelf, { recursive: true });
      copyFileSync(HUT_ASSET, path.join(shelf, "hut.nbt"));
      await world.command(server, "reload");
      await tick.sprint(server, 20);
      await client.run(OPEN_HELD_BLUEPRINTS);
      await settle(5);
      await expectPicker("HeldBlueprints");
      await shot("held-blueprints", "HeldBlueprints: the blueprint shelf on a screen of its own, from the placing tool");
      await typeIntoElement(client, { id: "folkways.blueprints.search" }, "hut");
      await settle();
      await shot("held-blueprints-search", "Blueprint library filtered to hut");
      await safeAction(() => screen.dismiss(client));
    });

    // Every page's own openers: item and choice settings.
    let toolTaken = false;
    const pages = BOOK_PAGES;
    run.note("pages", pages);
    for (const page of pages) {
      await scene(`page-${page.name}`, async () => {
        await panel();
        await clickTab(client, page.token);
        await settle(5);
        const openers = (await shown()).filter(element => element.className.endsWith(".Button")
          && /^folkways\.card\.(items\.|choice\..*\.open$)/.test(element.id!));
        const taken: Record<string, number> = {};
        for (const opener of openers) {
          const id = opener.id!;
          const kind = id.startsWith("folkways.settings.open.tool.") ? "tool"
            : id.replace(/\.\d+\.\d+$/, "").replace(/^(folkways\.settings\.(open|value))\..*$/, "$1");
          if (kind === "tool" && toolTaken || (taken[kind] ?? 0) >= 2) continue;
          await click(id);
          const picker = await openPicker();
          if (picker) {
            taken[kind] = (taken[kind] ?? 0) + 1;
            toolTaken ||= kind === "tool";
            await shot(`page-${page.name}-${token(id)}`, `${page.name} page: ${id} opens ${picker}`, { opener: id });
            await closePickers();
          }
          await closeWindows();
        }
      });
    }

    await scene("settings-tools", async () => {
      await panel();
      await clickTab(client, "folkways.tab.settings");
      await settle();
      await openSettingsScope(server, client, "folkways_vanilla:building", "folkways.card.items.tool");
      await click("folkways.card.items.tool");
      await expectPicker("tools");
      await click("folkways.selector.folder.minecraft");
      await shot("settings-tools-folder", "SettingsPage tools: the minecraft folder opened, a folder per tag");
      await typeIntoElement(client, { id: "folkways.selector.search" }, "@minecraft *_planks");
      await settle();
      await shot("settings-tools-search", "SettingsPage tools: a search with Add as a rule");
    });

    await scene("rule-tooltip", async () => {
      await panel();
      await clickTab(client, "folkways.tab.settings");
      await settle();
      await openSettingsScope(server, client, "folkways_vanilla:wares", "folkways.card.items.fuel");
      await click("folkways.card.items.fuel");
      await expectPicker("fuel");
      await hoverElement(client, { id: "folkways.selector.rule.0" }, { hoverFrames: 15 });
      await run.shot(client, "rule-tooltip", { subject: "A rule held: hovering it names the item it is", worldState: {}, hard: true });
    });

    await scene("person-inlay", async () => {
      await panel();
      await clickTab(client, "folkways.tab.residents");
      const row = (await shown()).find(element => element.id!.startsWith("folkways.resident.name."));
      if (!row) throw new Error("no resident name to open");
      await openResidentDetail(server, client, row.id!);
      await waitForPresent(server, client, "folkways.resident.look.change");
      await click("folkways.resident.look.change");
      await expectPicker("look change");
      await shot("person-inlay-look", "PersonInlay: Change look opens LookPicker, one look, a model beside");
    });

    await scene("zone-settings", async () => {
      await screen.dismiss(client);
      const { zones } = await server.run(ZONE_FIXTURES, { player });
      const controls = [
        ["folkways_vanilla:farm", "folkways.card.choice.crop.open"],
        ["folkways_vanilla:ground", "folkways.card.items.block"],
      ] as const;
      for (const [kind, opener] of controls) {
        const zone = zones.find(zone => zone.type === kind);
        if (!zone) {
          throw new Error(`no fixture for ${kind}`);
        }
        await openCardOf(server, client, player, zone.id);
        await waitForPresent(server, client, opener);
        await click(opener);
        const picker = await expectPicker(opener);
        await shot(`zone-settings-${token(kind)}-${token(opener)}`,
          `Facility card for ${kind}: ${opener} opens ${picker}`, { zone });
        await closePickers();
        await screen.dismiss(client);
        await settle();
      }
    });

    await scene("drafts", async () => {
      await safeAction(() => screen.dismiss(client));
      const min: Vec = { x: origin.x + 4, y: origin.y - 1, z: origin.z - 16 };
      const max: Vec = { x: origin.x + 12, y: origin.y + 3, z: origin.z - 8 };
      await tryCommand(server, `fill ${commandPos({ ...min, x: min.x - 2, z: min.z - 2 })} ${commandPos({ ...max, x: max.x + 2, z: max.z + 2 })} minecraft:air`);
      await tryCommand(server, `fill ${commandPos({ ...min, x: min.x - 2, z: min.z - 2 })} ${commandPos({ ...max, x: max.x + 2, y: min.y, z: max.z + 2 })} minecraft:smooth_stone`);
      await tryCommand(server, `item replace entity ${player} weapon.mainhand with ${BOOK_ITEM}`);
      await tick.sprint(server, 3);
      await chooseBookTool(server, client, "offer:folkways_vanilla:draft");
      const post = { ...max, y: origin.y };
      await tryCommand(server, `fill ${commandPos(post)} ${commandPos(max)} minecraft:glass`);
      await drainActionBar(client);
      await markCorner(server, client, min, FIRST_CORNER, () => aimAndWorldLeftClick(server, client, player, min));
      await markCorner(server, client, max, ZONE_MARKED, () => aimAndWorldLeftClick(server, client, player, max));
      await tryCommand(server, `fill ${commandPos(post)} ${commandPos(max)} minecraft:air`);
      await useBook(client);
      await settlePanel(server, client);
      await waitForElement(client, { id: "folkways.draft.choose" }, { timeoutMs: 8_000 });
      await click("folkways.draft.choose");
      await expectPicker("draft patterns");
      await shot("draft-panel-patterns", "DraftPanel: the patterns that can be drawn in the marked box");
      await click("folkways.draft.pattern.folkways_vanilla.terraced_field");
      await waitForElement(client, { id: "folkways.draft.preview" }, { timeoutMs: 8_000 });
      const knobs = (await shown()).filter(element => element.className.endsWith(".Button")
        && element.id!.startsWith("folkways.draft.knob."));
      run.note("draft_knobs", knobs.map(knob => knob.id));
      let opened = 0;
      for (const knob of knobs) {
        await click(knob.id!);
        if (await openPicker()) {
          opened++;
          await shot(`draft-panel-${token(knob.id!)}`, `DraftPanel: knob ${knob.id}`);
          await closePickers();
        }
      }
      if (opened === 0) throw new Error(`no draft knob opened a picker: ${JSON.stringify(knobs.map(knob => knob.id))}`);
      if (!knobs.some(knob => knob.id === "folkways.draft.knob.steps")) throw new Error("the steps knob is missing");
    });

    await scene("supply-panel", async () => {
      await safeAction(() => screen.dismiss(client));
      await tryCommand(server, `gamemode creative ${player}`);
      const box = { x: origin.x + 2, y: origin.y, z: origin.z + 3 };
      await world.command(server, `setblock ${commandPos(box)} folkways_vanilla:creative_supply_box`);
      // An empty hand: with the book in it, the use would open the colony panel instead.
      await tryCommand(server, `item replace entity ${player} weapon.mainhand with minecraft:air`);
      await tick.sprint(server, 3);
      await players.useBlock(client, box, { face: "up" });
      await waitForElement(client, { id: "folkways.supply.mode" }, { timeoutMs: 8_000 });
      await settle();
      // The whitelist is only open to edit once the box stops supplying every item.
      const whitelist = (await shown()).find(element => element.id === "folkways.supply.whitelist");
      if (!whitelist?.active) await click("folkways.supply.mode");
      await shot("supply-panel-mode", "SupplyPanel: Whitelist only is a tick now, its label toggles it too");
      await click("folkways.supply.whitelist");
      await expectPicker("supply whitelist");
      await shot("supply-panel-whitelist", "SupplyPanel: the creative supply box's whitelist");
      await safeAction(() => screen.dismiss(client));
      await tryCommand(server, `gamemode survival ${player}`);
    });

    // A phone-narrow window at GUI scale two.
    await client.run(NARROW);
    await settle(5);
    await scene("narrow-selector", async () => {
      await client.run(OPEN_SELECTOR);
      await settle();
      await expectPicker("narrow selector");
      await shot("narrow-selector-window", "Narrow: SelectorWindow inside a 640x800 window at GUI scale 2");
      await click("folkways.selector.close");
    });
    run.note("failures", failures);
    if (failures.length > 0) throw new Error(`Scenes not reached:\n${failures.join("\n")}`);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
