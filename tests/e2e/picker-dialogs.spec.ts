import { expect, test } from "@playwright/test";
import { defineTask, javaTask, media, tick, world } from "@izakyl/blockwright-minecraft";
import { clickElement, panelElements, typeIntoElement, visibleBox } from "../ldlib2";
import {
  foundColonyFast, foundingBox, openColonyPanelViaBook, optInViaBook, waitForGroundedPlayer,
} from "../shared/colony/colony-founding";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { seededServer } from "../shared/harness/mod-settings";
import { newRunId } from "../shared/harness/run-id";
import { GROUND } from "../shared/harness/stage";
import { ORDER_ITEM, ORDER_MAINTAIN, openOrderDraft } from "../shared/colony/membership-optin";
import { commandPos, frames, waitForElement } from "../shared/harness/bw-helpers";
import { openEditor } from "../shared/ui/colony-pages";

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

const DROP = defineTask<void, string>({
  name: "folkways.picker-dialogs.drop", side: "client",
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.core.item.api.ItemFilter",
    "io.github.izakyl.folkways.core.front.api.ui.FilterStacks",
    "io.github.izakyl.folkways.core.front.api.ui.ItemDrops",
    "com.lowdragmc.lowdraglib2.gui.ui.ModularUI",
    "net.minecraft.client.Minecraft",
    "net.minecraft.core.registries.BuiltInRegistries",
    "net.minecraft.resources.ResourceLocation",
  ], body: `
var stack=FilterStacks.of(ItemFilter.tag(ResourceLocation.parse("minecraft:logs")));
var screen=Minecraft.getInstance().screen;
// The picker's list, not the card's item button, which takes drops of its own.
var target=ItemDrops.shown(ModularUI.of(screen)).stream().filter(one->one.drop().accepts(stack))
 .filter(one->one.element().getId()==null||!one.element().getId().startsWith("folkways.card."))
 .findFirst().orElseThrow();
target.drop().take(stack);
return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
` }),
});

// An item dragged from a recipe viewer onto the card's item button, with no picker open.
const DROP_ON_CARD = defineTask<{ item: string }, boolean>({
  name: "folkways.picker-dialogs.drop-on-card", side: "client",
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.core.front.api.ui.ItemDrops",
    "com.lowdragmc.lowdraglib2.gui.ui.ModularUI",
    "net.minecraft.client.Minecraft",
    "net.minecraft.core.registries.BuiltInRegistries",
    "net.minecraft.resources.ResourceLocation",
    "net.minecraft.world.item.ItemStack",
  ], body: `
var stack=new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(Args.of(ctx).string("item"))));
var target=ItemDrops.shown(ModularUI.of(Minecraft.getInstance().screen)).stream()
 .filter(one->"${ORDER_ITEM}".equals(one.element().getId())).findFirst().orElseThrow();
if(!target.drop().accepts(stack))return false;
target.drop().take(stack);
return true;
` }),
});

const ORDER_SELECTION = defineTask<void, string[]>({
  name: "folkways.picker-dialogs.order-selection", side: "server",
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.core.colony.api.Colonies",
    "io.github.izakyl.folkways.core.front.api.Fronts",
    "io.github.izakyl.folkways.core.item.api.Goods",
    "net.minecraft.core.registries.BuiltInRegistries",
    "net.minecraft.resources.ResourceLocation",
  ], body: `
var colony=Colonies.all(ctx.server()).getFirst();
var settings=Fronts.of(colony).settings(ResourceLocation.parse("folkways_vanilla:orders"));
return settings.items("item").map(Goods::members).orElse(Set.of()).stream()
 .map(item->BuiltInRegistries.ITEM.getKey(item).toString()).toList();
` }),
});

test("the looks editor stages its pool until confirm", async ({}, testInfo) => {
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
    const editor = "folkways_vanilla.looks";
    const window = `folkways.window.${editor}`;
    expect((await panelElements(client)).some(element => element.id === window)).toBe(false);
    await openEditor(client, editor);
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
    await clickElement(client, { id: `folkways.window.close.${editor}` });
    await openEditor(client, editor);
    expect((await panelElements(client)).filter(element => element.id === window)).toHaveLength(1);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});

test("the single-item picker selects the concrete item from a dropped tag stack", async ({}, testInfo) => {
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
    await foundColonyFast(server, client, player.name, { origin, residentCount: 1 });
    const chest = { ...origin, z: origin.z + 6 };
    await world.command(server, `setblock ${commandPos(chest)} minecraft:chest`);
    expect((await optInViaBook(server, client, player.name, chest, "chest")).member).toBe(true);
    await openOrderDraft(server, client, player.name, chest);
    // With no item chosen yet, an order is refused, saying why.
    expect(await server.run(ORDER_SELECTION, undefined)).toEqual([]);
    await clickElement(client, { id: ORDER_MAINTAIN }, { scrollIntoView: true });
    await expect.poll(async () => {
      await tick.sprint(server, 2);
      await frames(client, 2);
      return (await panelElements(client)).find(element => element.id === "folkways.card.refusal"
        && element.displayed !== false)?.text ?? "";
    }).toContain("Choose an item for the order first");
    await clickElement(client, { id: ORDER_ITEM });
    await waitForElement(client, { id: "folkways.selector.close" });
    const before = await server.run(ORDER_SELECTION, undefined);
    const droppedItem = await client.run(DROP, undefined);
    await frames(client, 3);
    expect(await server.run(ORDER_SELECTION, undefined)).toEqual(before);
    await clickElement(client, { id: "folkways.selector.confirm" });
    await expect.poll(async () => {
      await tick.sprint(server, 3);
      return server.run(ORDER_SELECTION, undefined);
    }).toEqual([droppedItem]);
    // The card's item button takes a dragged item as it is, without opening the picker.
    await waitForElement(client, { id: "folkways.selector.close" }, { state: "absent" });
    expect(await client.run(DROP_ON_CARD, { item: "minecraft:oak_planks" })).toBe(true);
    await expect.poll(async () => {
      await tick.sprint(server, 3);
      return server.run(ORDER_SELECTION, undefined);
    }).toEqual(["minecraft:oak_planks"]);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
