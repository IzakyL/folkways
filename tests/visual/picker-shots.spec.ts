import { test } from "@playwright/test";
import { input, screen, tick, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { foundColonyFast, foundingBox, openColonyPanelViaBook, setBookGesture, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { createMarkedZone, holdZoneAndOpen } from "../shared/ui/zone-tools";
import { aimAndWorldLeftClick } from "../shared/ui/world-ui";
import { CONFIG_PATH, VisualRun } from "../shared/capture/visual-kit";
import { clickTab } from "./ui-catalog";
import { clickElement } from "../ldlib2";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { tracePath } from "../shared/harness/out-paths";
import { seededOffThreadServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { frames, safeAction, softly, tryCommand, waitForElement } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";

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
    const click = (id: string) => softly(() => clickElement(client, { id }, { scrollIntoView: true }));
    const type = async (text: string) => {
      await click("folkways.selector.search");
      await safeAction(() => input.key(client, { text }));
      await settle();
    };

    // The colony's tool list: rules held, every item filed by mod and tag.
    await openColonyPanelViaBook(server, client, player, founding.standBlock);
    await clickTab(client, "folkways.tab.settings");
    await settle();
    await click("folkways.settings.open.tool");
    await softly(() => waitForElement(client, { id: "folkways.selector.close" }, { timeoutMs: 8_000 }));
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
    await setBookGesture(server, client, player, "box");
    const min = { x: origin.x + 4, y: origin.y - 1, z: origin.z + 6 };
    const max = { x: origin.x + 6, y: origin.y - 1, z: origin.z + 8 };
    for (const corner of [min, max]) {
      await tryCommand(server, `setblock ${corner.x} ${corner.y} ${corner.z} minecraft:stone`);
      await tick.sprint(server, 2);
      await aimAndWorldLeftClick(server, client, player, corner, GROUND);
      await tick.sprint(server, 3);
    }
    await createMarkedZone(server, client, player, founding.standBlock, "ground");
    await holdZoneAndOpen(server, client, player, { min, max });
    await settle();
    await shot("08-zone-settings", "A groundwork zone's settings: the fill block worn on its button");

    await click("folkways.zoneconfig.value.block");
    await softly(() => waitForElement(client, { id: "folkways.selector.close" }, { timeoutMs: 8_000 }));
    await settle();
    await shot("09-one-tree", "One block: the same folders, blocks only, no boxes and no rules");

    await type("stone");
    await click("folkways.selector.result.minecraft.cobblestone");
    await settle();
    await shot("10-one-picked", "Cobblestone picked: marked, and Confirm lit");

    await click("folkways.selector.confirm");
    await settle(5);
    await shot("11-zone-after", "Confirmed: the zone's button wears cobblestone");
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
