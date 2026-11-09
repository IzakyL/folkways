import { test } from "@playwright/test";
import { defineTask, input, javaTask, player as players, screen, tick, world } from "@izakyl/blockwright-minecraft";
import { copyFileSync, mkdirSync } from "node:fs";
import path from "node:path";
import { ancestorsOf, clickElement, hoverElement, panelElements, typeIntoElement } from "../ldlib2";
import {
  foundColonyFast, foundingBox, openColonyPanelViaBook, setBookGesture, waitForGroundedPlayer, type Vec,
} from "../shared/colony/colony-founding";
import { CONFIG_PATH, VisualRun } from "../shared/capture/visual-kit";
import { QUIET_TOASTS } from "../shared/capture/ponder";
import { clickTab, discoverPages, OPEN_ZONE, ZONE_FIXTURES } from "./ui-catalog";
import { markBox } from "../shared/ui/zone-tools";
import { boardActs, settlePanel } from "../shared/ui/site-panel";
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

// Two loadout profiles, one assigned, and a patrol route whose staffing editor is left open.
const LOADOUTS = defineTask<{ player: string }, { route: string }>({
  name: "folkways.visual.picker-review.loadouts", side: "server",
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.core.front.api.ui.Pages",
    "io.github.izakyl.folkways.core.front.ui.screen.ColonyShell",
    "io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfiles",
    "io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfile",
    "io.github.izakyl.folkways.vanilla.patrol.PatrolContent",
    "io.github.izakyl.folkways.core.api.colony.Held",
    "io.github.izakyl.folkways.core.api.terms.WorldPos",
    "io.github.izakyl.folkways.core.api.terms.ItemSpec", "net.minecraft.world.item.Items",
  ], body: `
var player=ctx.server().getPlayerList().getPlayerByName(Args.of(ctx).string("player"));
var colony=Pages.colonyOf(player).orElseThrow();var level=player.serverLevel();
var profiles=LoadoutProfiles.of(colony);
var melee=profiles.create("Melee guard");var ranged=profiles.create("Bow guard");
profiles.add(melee.id(),LoadoutProfile.Entry.weapon(ItemSpec.of(Items.IRON_SWORD)));
profiles.add(ranged.id(),LoadoutProfile.Entry.weapon(ItemSpec.of(Items.BOW)));
var residents=colony.residents().stream().map(resident->resident.id()).sorted().toList();
profiles.assign(List.of(residents.get(0)),Optional.of(melee.id()));
var at=player.blockPosition().offset(3,0,-3);
var route=colony.hold(PatrolContent.BEAT,new Held.Line(List.of(WorldPos.of(level,at),
 WorldPos.of(level,at.offset(8,0,0))))).orElseThrow();
ColonyShell.editSelection(player,route.id());
return Map.of("route",route.id().toString());
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
      await safeAction(() => screen.dismiss(client));
    });

    // Every page's own openers: settings of items or of a choice, and the board's edit acts.
    let toolTaken = false;
    const pages = await discoverPages(server, player);
    run.note("pages", pages);
    for (const page of pages) {
      await scene(`page-${page.name}`, async () => {
        await panel();
        await clickTab(client, page.token);
        await settle(5);
        const openers = (await shown()).filter(element => element.className.endsWith(".Button")
          && /^folkways\.(settings\.(open|value)\.|board\.edit\.)/.test(element.id!));
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
      await clickTab(client, "folkways.page.build");
      await settle();
      await click("folkways.settings.open.tool.folkways_vanilla.building.folkways_vanilla.build");
      await expectPicker("tools");
      await click("folkways.selector.folder.minecraft");
      await shot("settings-tools-folder", "SettingsPage tools: the minecraft folder opened, a folder per tag");
      await typeIntoElement(client, { id: "folkways.selector.search" }, "@minecraft *_planks");
      await settle();
      await shot("settings-tools-search", "SettingsPage tools: a search with Add as a rule");
    });

    await scene("rule-tooltip", async () => {
      await panel();
      await clickTab(client, "folkways.page.wares");
      await settle();
      await click("folkways.board.edit.folkways_vanilla.wares.0.0");
      await expectPicker("fuel");
      await hoverElement(client, { id: "folkways.selector.rule.0" }, { hoverFrames: 15 });
      await run.shot(client, "rule-tooltip", { subject: "A rule held: hovering it names the item it is", worldState: {}, hard: true });
    });

    await scene("blueprints-page", async () => {
      await panel();
      await clickTab(client, "folkways.page.build");
      await click("folkways.board.open.folkways_vanilla.build.0.0");
      await waitForElement(client, { id: "folkways.blueprints.choose" }, { timeoutMs: 8_000 });
      await click("folkways.blueprints.choose");
      await expectPicker("blueprints");
      await shot("blueprints-page", "BlueprintsPage: the shelf, own and shared roots, a preview beside");
      await typeIntoElement(client, { id: "folkways.blueprints.search" }, "hut");
      await settle();
      await shot("blueprints-page-search", "BlueprintsPage: a search for hut");
    });

    await scene("looks-page", async () => {
      await panel();
      await clickTab(client, "folkways.page.person");
      const opens = await boardActs(client, "open", "folkways_vanilla.person");
      await click(opens[1].id);
      await waitForElement(client, { id: "folkways.pool.looks.choose" });
      await click("folkways.pool.looks.choose");
      await expectPicker("looks pool");
      await shot("looks-page", "LooksPage: the look pool, a box per look, folders per source, a model beside");
      await typeIntoElement(client, { id: "folkways.pool.looks.search" }, "steve");
      await settle();
      await shot("looks-page-search", "LooksPage: a search for steve; ticks staged until Confirm (LookPoolEdits)");
    });

    await scene("person-inlay", async () => {
      await panel();
      await clickTab(client, "folkways.tab.residents");
      const row = (await shown()).find(element => element.id!.startsWith("folkways.resident.name."));
      if (!row) throw new Error("no resident name to open");
      await click(row.id!);
      await waitForElement(client, { id: "folkways.resident.look.change" }, { timeoutMs: 8_000 });
      await click("folkways.resident.look.change");
      await expectPicker("look change");
      await shot("person-inlay-look", "PersonInlay: Change look opens LookPicker, one look, a model beside");
    });

    let route = "";
    await scene("staffing", async () => {
      await panel();
      route = (await server.run(LOADOUTS, { player })).route;
      await waitForElement(client, { id: "folkways.staffing.allowed" }, { timeoutMs: 8_000 });
      await settle();
      await click("folkways.staffing.allowed");
      await expectPicker("staffing");
      await shot("staffing-allowed", "StaffingPanel: loadouts allowed on a patrol route, several ticked");
    });
    run.note("route", route);

    const loadouts = async () => {
      await panel();
      await clickTab(client, "folkways.loadouts.title");
      await settle(5);
      const profile = (await shown()).find(element => element.id === "folkways.loadouts.profile.0");
      if (profile) await click(profile.id!);
    };
    const adding = async (option?: string) => {
      await click("folkways.loadouts.add");
      await expectPicker("loadouts add");
      if (option) {
        await click(`folkways.loadouts.${option}`);
        await expectPicker(`loadouts ${option}`);
      }
    };
    await scene("loadouts-add", async () => {
      await loadouts();
      await adding();
      await shot("loadouts-add", "LoadoutPage: the Add menu (migrated in the rebase), one immediate pick");
    });
    await scene("loadouts-weapon", async () => {
      await loadouts();
      await adding("weapon");
      await shot("loadouts-add-weapon", "LoadoutPage: Add weapon opens the item picker for rules");
    });
    await scene("loadouts-weapon-variants", async () => {
      await loadouts();
      await adding("weapon_variants");
      await shot("loadouts-add-weapon-variants", "LoadoutPage: weapon variants, many ticked from what the colony knows");
    });
    await scene("loadouts-observation", async () => {
      await loadouts();
      await adding("observation_gear");
      await shot("loadouts-add-observation", "LoadoutPage: observation gear, many ticked");
    });
    await scene("loadouts-assignment", async () => {
      await loadouts();
      await click("folkways.loadouts.assign_residents");
      await waitForElement(client, { id: "folkways.loadouts.resident.0" }, { timeoutMs: 8_000 });
      await click("folkways.loadouts.resident.0");
      await expectPicker("loadouts assignment");
      await shot("loadouts-assignment", "LoadoutPage: a resident's loadout (migrated in the rebase), one immediate pick");
    });

    await scene("compost-page", async () => {
      await panel();
      await clickTab(client, "folkways.page.compost_settings");
      await settle(5);
      await shot("compost-page", "CompostPage: no dialog of its own; resets ride Picker.Asking");
    });

    await scene("zone-settings", async () => {
      await panel();
      const fixtures = await server.run(ZONE_FIXTURES, { player });
      const taken = new Set<string>();
      for (const zone of fixtures.zones) {
        await server.run(OPEN_ZONE, { player, zone: zone.id });
        await waitForElement(client, { id: "folkways.selection.back" }, { timeoutMs: 8_000 });
        await settle(5);
        const openers = (await shown()).filter(element => element.className.endsWith(".Button")
          && element.id!.startsWith("folkways.zoneconfig.value."));
        for (const opener of openers) {
          if (taken.has(opener.id!)) continue;
          await click(opener.id!);
          const picker = await openPicker();
          if (picker) {
            taken.add(opener.id!);
            await shot(`zone-settings-${zone.type.replace(/[:/]/g, ".")}-${token(opener.id!)}`,
              `ZoneSettings of ${zone.type}: ${opener.id} opens ${picker}`, { zone });
            await closePickers();
          }
        }
        await safeAction(() => screen.dismiss(client));
        await tick.sprint(server, 3);
      }
      if (taken.size === 0) throw new Error("no zone setting opened a picker");
    });

    await scene("drafts", async () => {
      await safeAction(() => screen.dismiss(client));
      const min: Vec = { x: origin.x + 4, y: origin.y - 1, z: origin.z - 16 };
      const max: Vec = { x: origin.x + 12, y: origin.y + 3, z: origin.z - 8 };
      await tryCommand(server, `fill ${commandPos({ ...min, x: min.x - 2, z: min.z - 2 })} ${commandPos({ ...max, x: max.x + 2, z: max.z + 2 })} minecraft:air`);
      await tryCommand(server, `fill ${commandPos({ ...min, x: min.x - 2, z: min.z - 2 })} ${commandPos({ ...max, x: max.x + 2, y: min.y, z: max.z + 2 })} minecraft:smooth_stone`);
      await tryCommand(server, `item replace entity ${player} weapon.mainhand with ${BOOK_ITEM}`);
      await tick.sprint(server, 3);
      await setBookGesture(server, client, player, "box");
      const post = { ...max, y: origin.y };
      await tryCommand(server, `fill ${commandPos(post)} ${commandPos(max)} minecraft:glass`);
      await markBox(server, client, player, { x: min.x - 1.5, y: origin.y, z: max.z + 2.5 }, min, max);
      await tryCommand(server, `fill ${commandPos(post)} ${commandPos(max)} minecraft:air`);
      await openColonyPanelViaBook(server, client, player, founding.standBlock);
      await settlePanel(server, client);
      await waitForElement(client, { id: "folkways.zoneconfig.kind.folkways_vanilla.farm" }, { timeoutMs: 8_000 });
      await click("folkways.zoneconfig.kind.folkways_vanilla.farm");
      await waitForElement(client, { id: "folkways.draft.value.crop" }, { timeoutMs: 8_000 });
      await click("folkways.draft.value.crop");
      await expectPicker("draft crop");
      await shot("draft-settings-crop", "DraftSettings: a new farm's crop, chosen before the zone is made");
      await closePickers();
      await click("folkways.zoneconfig.offer.folkways_vanilla.draft");
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
    await scene("narrow-looks", async () => {
      await panel();
      await clickTab(client, "folkways.page.person");
      const opens = await boardActs(client, "open", "folkways_vanilla.person");
      await click(opens[1].id);
      await waitForElement(client, { id: "folkways.pool.looks.choose" });
      await click("folkways.pool.looks.choose");
      await expectPicker("narrow looks");
      await shot("narrow-looks-page", "Narrow: LooksPage pool, the preview beside the list");
    });
    await scene("narrow-loadouts-add", async () => {
      await loadouts();
      await adding();
      await shot("narrow-loadouts-add", "Narrow: LoadoutPage Add menu");
    });
    await scene("narrow-staffing", async () => {
      await panel();
      await server.run(OPEN_ZONE, { player, zone: route });
      await waitForElement(client, { id: "folkways.staffing.allowed" }, { timeoutMs: 8_000 });
      await click("folkways.staffing.allowed");
      await expectPicker("narrow staffing");
      await shot("narrow-staffing-allowed", "Narrow: StaffingPanel allowed loadouts");
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
