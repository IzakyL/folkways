import { expect, test } from "@playwright/test";
import { defineTask, javaTask, media, tick } from "@izakyl/blockwright-minecraft";
import { clickElement, panelElements, typeIntoElement, visibleBox } from "../ldlib2";
import {
  foundColonyFast, foundingBox, openColonyPanelViaBook, waitForGroundedPlayer,
} from "../shared/colony/colony-founding";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { seededServer } from "../shared/harness/mod-settings";
import { newRunId } from "../shared/harness/run-id";
import { GROUND } from "../shared/harness/stage";
import { frames, waitForElement } from "../shared/harness/bw-helpers";
import { clickTab } from "../shared/ui/colony-pages";
import { boardActs } from "../shared/ui/site-panel";

const POOL = defineTask<void, string>({
  name: "folkways.picker-dialogs.pool", side: "server",
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.core.colony.api.Colonies",
    "io.github.izakyl.folkways.vanilla.person.look.LookPool",
  ], body: `return LookPool.of(Colonies.all(ctx.server()).getFirst()).save().toString();` }),
});
const FIRST_LOOK = defineTask<void, string>({
  name: "folkways.picker-dialogs.first-look", side: "client",
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.vanilla.person.look.ResidentLooks",
    "io.github.izakyl.folkways.vanilla.person.look.LookPicker",
    "net.minecraft.client.Minecraft",
  ], body: `
return ResidentLooks.pool(Minecraft.getInstance().level.registryAccess()).keySet().stream()
 .filter(id->LookPicker.disabledReason(List.of(id)).isEmpty()).sorted().findFirst().orElseThrow().toString();
` }),
});

const DROP = defineTask<{ action: string }, string>({
  name: "folkways.picker-dialogs.drop", side: "client",
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.core.item.api.ItemFilter",
    "io.github.izakyl.folkways.core.front.api.ui.FilterStacks",
    "io.github.izakyl.folkways.core.front.api.ui.ItemDrops",
    "io.github.izakyl.folkways.core.front.client.SelectorWindow",
    "com.lowdragmc.lowdraglib2.gui.ui.ModularUI",
    "net.minecraft.client.Minecraft",
    "net.minecraft.core.registries.BuiltInRegistries",
    "net.minecraft.resources.ResourceLocation",
  ], body: `
var mc=Minecraft.getInstance();String key="folkways.picker-dialogs.chosen";
var stack=FilterStacks.of(ItemFilter.tag(ResourceLocation.parse("minecraft:logs")));
return switch(Args.of(ctx).string("action")) {
 case "open" -> {
  System.clearProperty(key);
  SelectorWindow.open(mc.screen,selected->System.setProperty(key,BuiltInRegistries.ITEM.getKey(selected.getItem()).toString()));
  yield BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
 }
 case "drop" -> {
  var target=ItemDrops.shown(ModularUI.of(mc.screen)).stream().filter(one->one.drop().accepts(stack))
   .findFirst().orElseThrow();
  target.drop().take(stack);yield "dropped";
 }
 default -> {String chosen=System.getProperty(key,"");System.clearProperty(key);yield chosen;}
};
` }),
});

test("the looks page stages its pool in a dialog until confirm", async ({}, testInfo) => {
  test.setTimeout(240_000);
  let pair: E2EPair | undefined;
  try {
    const run = newRunId();
    pair = await launchPair({ server: { instance: seededServer(`picker-dialogs-server-${run}`) },
      client: { instance: `picker-dialogs-client-${run}` } });
    const { server, client } = pair;
    const player = await waitForGroundedPlayer(server);
    const origin = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    await GROUND.lay(server, foundingBox(origin));
    const founded = await foundColonyFast(server, client, player.name, { origin, residentCount: 1 });
    await openColonyPanelViaBook(server, client, player.name, founded.standBlock);
    await clickTab(client, "folkways.page.person");
    const opens = await boardActs(client, "open", "folkways_vanilla.person");
    expect(opens.length).toBeGreaterThan(1);
    await clickElement(client, { id: opens[1].id });
    await waitForElement(client, { id: "folkways.pool.looks.choose" });
    await expect.poll(async () => (await panelElements(client))
      .find(element => element.id === "folkways.pool.looks.count")?.text ?? "").toMatch(/\d/);
    const before = await server.run(POOL);
    const look = await client.run(FIRST_LOOK);
    const row = `folkways.pool.looks.look.${look.replace(/[:/]/g, ".")}`;
    const open = async () => {
      await clickElement(client, { id: "folkways.pool.looks.choose" });
      await typeIntoElement(client, { id: "folkways.pool.looks.search" }, look);
      await frames(client, 3);
    };
    await open();
    await clickElement(client, { id: row });
    await tick.sprint(server, 5);
    expect(await server.run(POOL), "a tick alone leaves the server pool untouched").toBe(before);
    await media.screenshot(client, { path: testInfo.outputPath("looks-dialog.png") });
    const elements = await panelElements(client);
    expect(visibleBox(elements, elements.find(element => element.id === "folkways.pool.looks.confirm")!)).toBeTruthy();
    await clickElement(client, { id: "folkways.pool.looks.close" });
    expect(await server.run(POOL), "cancel discards the staged change").toBe(before);
    await open();
    await clickElement(client, { id: row });
    await clickElement(client, { id: "folkways.pool.looks.confirm" });
    await expect.poll(() => server.run(POOL), { timeout: 8_000 }).not.toBe(before);

    const droppedItem = await client.run(DROP, { action: "open" });
    await frames(client, 3);
    await client.run(DROP, { action: "drop" });
    await clickElement(client, { id: "folkways.selector.confirm" });
    expect(await client.run(DROP, { action: "read" }), "single selection takes the dragged item itself")
      .toBe(droppedItem);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
