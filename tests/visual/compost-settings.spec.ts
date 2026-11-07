import { expect, test } from "@playwright/test";
import { defineTask, input, javaTask, screen, world } from "@izakyl/blockwright-minecraft";
import { foundColonyFast, openColonyPanelViaBook, optInViaBook, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { clickElement, panelElements, scrollElementIntoView } from "../ldlib2";
import { settlePanel, toggleCell, typeCell } from "../shared/ui/site-panel";
import { VisualRun } from "../shared/capture/visual-kit";
import { SET_GUI_SCALE, setVisualLanguage } from "../shared/capture/ponder";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { seededOffThreadServer } from "../shared/harness/mod-settings";
import { tracePath } from "../shared/harness/out-paths";
import { BUDGET } from "../shared/harness/budgets";
import { commandPos, frames } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";

const RICE = "kaleidoscope_cookery:rice";
const WILD_RICE = "kaleidoscope_cookery:wild_rice";

const DISCOVERED = defineTask<void, string[]>({
  name: "folkways.compost-shots.discovered", side: "server",
  source: javaTask({ imports: ["io.github.izakyl.folkways.core.colony.api.Colonies", "io.github.izakyl.folkways.plugins.compost.CompostSettings", "net.minecraft.core.registries.BuiltInRegistries"], body: `
return CompostSettings.discovered(Colonies.all(ctx.server()).getFirst(), ctx.server()).stream().map(item -> BuiltInRegistries.ITEM.getKey(item).toString()).toList();
` }),
});

test("compost settings: the independent entry, one shared rice row and individual reserves (zh_cn)", async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  const run = new VisualRun("compost-settings");
  const runId = newRunId();
  let pair: E2EPair | undefined;
  try {
    pair = await launchPair({
      config: "blockwright.toml#cookery",
      server: { trace: tracePath("visual", "compost-settings-server"), instance: seededOffThreadServer(`visual-compost-settings-server-${runId}`) },
      client: { trace: tracePath("visual", "compost-settings-client"), instance: `visual-compost-settings-client-${runId}` },
    });
    const { server, client } = pair;
    const player = await waitForGroundedPlayer(server);
    await setVisualLanguage(client, "zh_cn");
    await client.run(SET_GUI_SCALE, { scale: 2 });
    const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    const founded = await foundColonyFast(server, client, player.name, { origin, residentCount: 1 });
    const chest: Vec = { x: origin.x + 3, y: origin.y, z: origin.z + 3 };
    const compost: Vec = { x: origin.x + 5, y: origin.y, z: origin.z + 3 };
    await world.command(server, `setblock ${commandPos(chest)} minecraft:chest`);
    await world.command(server, `setblock ${commandPos(compost)} minecraft:composter`);
    await optInViaBook(server, client, player.name, chest, "chest");
    await optInViaBook(server, client, player.name, compost, "worksite");
    await screen.dismiss(client);
    for (const [slot, item] of ["minecraft:beetroot_seeds", "minecraft:carrot", "minecraft:wheat_seeds", WILD_RICE, RICE].entries()) {
      await world.command(server, `item replace block ${commandPos(chest)} container.${slot + 20} with ${item} 64`);
    }
    await openColonyPanelViaBook(server, client, player.name, founded.standBlock);
    await scrollElementIntoView(client, { id: "folkways.page.compost_settings" });
    const shot = async (name: string, subject: string) => {
      await input.move(client, { x: 0, y: 0 });
      await frames(client, 5);
      await run.shot(client, name, { subject, worldState: { origin, locale: "zh_cn" }, hard: true });
    };
    await shot("01-independent-entry", "Colony panel: compost settings as its own page entry");
    await clickElement(client, { id: "folkways.page.compost_settings" });
    await settlePanel(server, client);
    const discovered = await server.run(DISCOVERED);
    expect(discovered, "beetroot, carrot, wheat and one rice row are discovered").toHaveLength(4);
    expect(discovered.filter(id => id === WILD_RICE || id === RICE), "wild rice and rice share one row").toHaveLength(1);
    expect((await panelElements(client)).some(element => element.text?.includes("野生稻种和稻米共享")),
      "the shared row needs no explanatory note").toBe(false);
    await shot("02-default-reserves", "Compost settings: default reserves for every discovered seed");
    await toggleCell(server, client, "folkways.settings.compost.enabled");
    await typeCell(server, client, "folkways.settings.compost.default_reserve", "96");
    await typeCell(server, client, "folkways.settings.compost.reserve." + discovered.indexOf("minecraft:beetroot_seeds"), "128");
    await shot("03-individual-reserve", "Compost settings: automatic composting off, default 96, beetroot overridden to 128");
    run.expectTaken(["01-independent-entry", "02-default-reserves", "03-individual-reserve"]);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
