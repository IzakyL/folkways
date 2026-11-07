import { test } from "@playwright/test";
import { defineTask, javaTask, input, screen, tick, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { foundColonyFast, foundingBox, openColonyPanelViaBook, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { CONFIG_PATH, VisualRun } from "../shared/capture/visual-kit";
import { QUIET_TOASTS } from "../shared/capture/ponder";
import { clickTab, OPEN_ZONE, ZONE_FIXTURES } from "./ui-catalog";
import { clickElement, hoverElement } from "../ldlib2";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { tracePath } from "../shared/harness/out-paths";
import { seededOffThreadServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { frames, safeAction, tryCommand, waitForElement } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";

const OPEN_SELECTOR = defineTask<void, boolean>({
  name: "folkways.visual.picker.open-independent", side: "client",
  source: javaTask({
    imports: ["io.github.izakyl.folkways.front.client.SelectorWindow"],
    body: `SelectorWindow.open(null, stack -> {}); return true;`,
  }),
});

const OPEN_CHOICE = defineTask<void, boolean>({
  name: "folkways.visual.picker.open-choice", side: "client",
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.front.client.SelectorWindow",
    "io.github.izakyl.folkways.front.api.ui.Picker",
    "net.minecraft.network.chat.Component",
    "net.minecraft.world.item.ItemStack",
    "net.minecraft.world.item.Items",
  ], body: `
SelectorWindow.open(null,Component.literal("Choice"),root->Picker.open(root,Component.literal("Choice"),List.of(
 new Picker.Row("archer",Component.literal("Archer"),new ItemStack(Items.BOW)),
 new Picker.Row("builder",Component.literal("Builder"),new ItemStack(Items.BRICKS))),"archer",picked->{}));
return true;
` }),
});
const NARROW = defineTask<void, boolean>({
  name: "folkways.visual.picker.narrow", side: "client",
  source: javaTask({ imports: ["net.minecraft.client.Minecraft"], body: `
var mc=Minecraft.getInstance();mc.getWindow().setWindowed(640,800);mc.options.guiScale().set(2);mc.resizeDisplay();
return true;
` }),
});

// A tour of the item picker in its states, for a reader to look at.
test("item picker states", async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  const run = new VisualRun("picker");
  let pair: E2EPair | undefined;
  try {
    const runId = newRunId();
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: tracePath("visual", "picker-server"), instance: seededOffThreadServer(`visual-picker-server-${runId}`) },
      client: { trace: tracePath("visual", "picker-client"), instance: `visual-picker-client-${runId}` },
    });
    const { server, client } = pair;
    const grounded = await waitForGroundedPlayer(server);
    const player = grounded.name;
    const origin: Vec = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    await tryCommand(server, `gamemode survival ${player}`);
    const { GROUND } = await import("../shared/harness/stage");
    await GROUND.lay(server, foundingBox(origin));
    const founding = await foundColonyFast(server, client, player, { origin, residentCount: 2 });

    const settle = async (n = 3) => {
      await tick.sprint(server, 3);
      await safeAction(() => frames(client, n));
    };
    const shot = (name: string, subject: string) => run.shot(client, name, { subject, worldState: {}, hard: true });
    const click = (id: string) => clickElement(client, { id }, { scrollIntoView: true });
    const type = async (text: string) => {
      await click("folkways.selector.search");
      await safeAction(() => input.key(client, { text }));
      await settle();
    };

    await client.run(QUIET_TOASTS);
    await client.run(OPEN_SELECTOR);
    await settle();
    await input.move(client, { x: 0, y: 0 });
    await shot("00-independent-placeholder", "Independent selector: the empty search field displays its parchment-colored placeholder");
    await hoverElement(client, { id: "folkways.selector.search" }, { hoverFrames: 15 });
    await shot("00-independent-search-help", "Independent selector: search syntax help tooltip over the empty search field");
    await click("folkways.selector.close");
    await settle();

    // The colony's tool list: rules held, every item filed by mod and tag.
    await openColonyPanelViaBook(server, client, player, founding.standBlock);
    await clickTab(client, "folkways.page.build");
    await settle();
    await click("folkways.settings.open.tool.folkways.building.folkways.build");
    await waitForElement(client, { id: "folkways.selector.close" }, { timeoutMs: 8_000 });
    await settle();
    await shot("01-many-tree", "Tools: a folder per mod, its count of items taken in; the rules held below");

    await click("folkways.selector.folder.minecraft");
    await settle();
    await shot("02-mod-open", "Minecraft opened: a folder per tag its items carry, and one for those carrying none");

    await click("folkways.selector.folder.minecraft.minecraft.axes");
    await settle();
    await shot("03-tag-open", "#axes opened: each axe has a box, dim since the #axes rule already takes it in");

    await click("folkways.selector.folder.minecraft.minecraft.arrows");
    await settle();
    await click("folkways.selector.result.minecraft.arrow");
    await settle();
    await shot("04-item-ticked", "#arrows opened, Arrow ticked: an item rule of its own, added below");

    await click("folkways.selector.folder_rule.minecraft.minecraft.arrows");
    await settle();
    await shot("05-folder-ticked", "The #arrows folder ticked: one rule, @minecraft #minecraft:arrows, its count filled");

    await type("@minecraft *_planks");
    await shot("06-search", "A search: mod, and a pattern over ids; Add as a rule shows how many it takes in");

    await click("folkways.selector.add_rule");
    await settle();
    await shot("07-search-ruled", "The search kept as a rule, written as it was typed, with the count it takes in");

    await click("folkways.selector.close");
    await screen.dismiss(client);
    await settle();

    // A groundwork zone's fill block: one block, picked.
    const fixtures = await server.run(ZONE_FIXTURES, { player });
    const ground = fixtures.zones.find(zone => zone.type === "folkways:ground");
    if (!ground) throw new Error("The groundwork fixture is missing");
    await server.run(OPEN_ZONE, { player, zone: ground.id });
    await waitForElement(client, { id: "folkways.zoneconfig.value.block" });
    await settle();
    await shot("08-zone-settings", "A groundwork zone's settings: the fill block worn on its button");

    await click("folkways.zoneconfig.value.block");
    await waitForElement(client, { id: "folkways.selector.close" }, { timeoutMs: 8_000 });
    await settle();
    await shot("09-one-tree", "One block: the same folders, blocks only, no boxes and no rules");

    await type("minecraft:cobblestone");
    await click("folkways.selector.result.minecraft.cobblestone");
    await settle();
    await shot("10-one-picked", "Cobblestone picked: marked, and Confirm lit");

    await click("folkways.selector.confirm");
    await settle(5);
    await shot("11-zone-after", "Confirmed: the zone's button wears cobblestone");
    await client.run(OPEN_CHOICE);
    await settle();
    await shot("12-compact-choice", "A short Choice dialog keeps its search field and follows the row count");
    await click("folkways.choice.cancel");
    await client.run(NARROW);
    await client.run(OPEN_CHOICE);
    await settle();
    await shot("13-phone-choice", "Choice at a phone-narrow window and GUI scale two: search, rows and buttons fit");
    await click("folkways.choice.cancel");
    await client.run(OPEN_SELECTOR);
    await settle();
    await shot("14-phone-items", "The full item registry at a phone-narrow GUI scale stays inside the screen");
    await click("folkways.selector.close");
    run.expectTaken(["00-independent-placeholder", "00-independent-search-help", "01-many-tree", "02-mod-open",
      "03-tag-open", "04-item-ticked", "05-folder-ticked", "06-search", "07-search-ruled", "08-zone-settings",
      "09-one-tree", "10-one-picked", "11-zone-after", "12-compact-choice", "13-phone-choice", "14-phone-items"]);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
