import { expect, test } from "@playwright/test";
import { defineTask, input, javaTask, media, screen, tick, world, type MinecraftClient } from "@izakyl/blockwright-minecraft";
import { clickElement, panelElements, waitForElement, type Element } from "../ldlib2";
import { foundColonyFast, foundingBox, isColonyMember, openColonyPanelViaBook, optInViaBook, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { commandPos, frames } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { twins, type Vec } from "../shared/harness/stage";

const SET_CHINESE = defineTask<void, string>({
  name: "folkways.ui-feedback.set-chinese", side: "client",
  source: javaTask({ imports: ["net.minecraft.client.Minecraft"], body: `
var mc=(Minecraft)ctx.client();mc.getLanguageManager().setSelected("zh_cn");mc.options.languageCode="zh_cn";
mc.reloadResourcePacks();return "zh_cn";
` }),
});
const READ_TRANSLATION = defineTask<void, Record<string, string>>({
  name: "folkways.ui-feedback.read-translations", side: "client",
  source: javaTask({ imports: ["net.minecraft.network.chat.Component"], body: `
var result=new LinkedHashMap<String,String>();
for(var key:List.of("item.minecraft.diamond","block.kaleidoscope_cookery.tea_tree","folkways.doing.folkways.taking_supplies.about","folkways.doing.folkways.leaving_millstone"))
 result.put(key,Component.translatable(key,Component.translatable("item.minecraft.diamond")).getString());
return result;
` }),
});
const COLORS = ["white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"];
const FIXTURE = ["wool", "concrete", "terracotta", "stained_glass", "candle"]
  .flatMap(kind => COLORS.map(color => "minecraft:" + color + "_" + kind)).concat("minecraft:diamond");
const PREFIX = "folkways.metrics.stock.";
const SUPPLY = "folkways.page.supply";

for (const stage of twins()) test(stage.title("book feedback: bounded stock search in client language and supply membership visibility"), async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  const progress = (phase: string) => console.log("book-feedback phase: " + phase);
  let pair: E2EPair | undefined; const evidence: Record<string, any> = { stage: stage.kind };
  const run = newRunId();
  try {
    pair = await launchPair({ config: stage.config,
      server: { trace: tracePath("e2e", stage.name("book-feedback-server-" + run)), instance: seededServer(stage.name("book-feedback-server-" + run)) },
      client: { trace: tracePath("e2e", stage.name("book-feedback-client-" + run)), instance: stage.name("book-feedback-client-" + run) } });
    const { server, client } = pair;
    const grounded = await waitForGroundedPlayer(server); const name = grounded.name;
    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    await stage.lay(server, foundingBox(origin));
    const founded = await foundColonyFast(server, client, name, { origin, residentCount: 1, stage });
    await world.command(server, "gamerule doDaylightCycle false"); await world.command(server, "time set day");
    progress("founded");
    const boxes: Vec[] = Array.from({ length: 3 }, (_, i) => ({ x: origin.x + 3 + i, y: origin.y, z: origin.z + 6 }));
    for (let box = 0; box < boxes.length; box++) {
      const items = FIXTURE.slice(box * 27, box * 27 + 27).map((id, slot) => `{Slot:${slot}b,id:"${id}",count:${id.endsWith(":diamond") ? 13 : 7}}`);
      await world.command(server, `setblock ${commandPos(stage.at(boxes[box]!))} minecraft:barrel{Items:[${items.join(",")}]}`);
      expect((await optInViaBook(server, client, name, boxes[box]!, "chest", stage)).member).toBe(true);
    }
    progress("fixture-stores-claimed");
    const source = { x: origin.x - 6, y: origin.y, z: origin.z + 5 };
    await openColonyPanelViaBook(server, client, name, founded.standBlock, stage);
    await expect.poll(async () => shown(await panelElements(client), SUPPLY), { timeout: 10_000 }).toBe(false);
    evidence.beforeBox = await panelElements(client);
    await screen.dismiss(client);
    await world.command(server, `setblock ${commandPos(stage.at(source))} folkways:creative_supply_box`);
    await openColonyPanelViaBook(server, client, name, founded.standBlock, stage);
    expect(shown(await panelElements(client), SUPPLY), "a placed but unclaimed source stays hidden").toBe(false);
    await screen.dismiss(client);
    expect((await optInViaBook(server, client, name, source, "worksite", stage)).member).toBe(true);
    await openColonyPanelViaBook(server, client, name, founded.standBlock, stage);
    await clickElement(client, { id: SUPPLY }, { scrollIntoView: true });
    evidence.claimed = await panelElements(client);
    progress("supply-claimed-and-open");
    // The book's native point gesture removes the same actual membership.
    await screen.dismiss(client);
    const stand = stage.stand(source);
    await world.command(server, `tp ${name} ${stand.x + 0.5} ${stand.y} ${stand.z + 1.5} 180 30`);
    await tick.sprint(server, 5);
    await stage.click({ server, client }, source, { player: name, button: "left" });
    await tick.sprint(server, 5);
    expect(await isColonyMember(server, source, stage)).toBe(false);
    await screen.dismiss(client);
    await openColonyPanelViaBook(server, client, name, founded.standBlock, stage);
    await expect.poll(async () => shown(await panelElements(client), SUPPLY), { timeout: 10_000 }).toBe(false);
    progress("open-storage");
    await clickElement(client, { id: "folkways.tab.metrics" });
    await clickElement(client, { id: "folkways.metrics.refresh" });
    await expect.poll(async () => (await stock(client)).length, { timeout: 15_000 }).toBe(60);
    progress("first-stock-page");
    const first = await stock(client);
    await clickElement(client, { id: "folkways.metrics.next" });
    await expect.poll(async () => (await stock(client)).some(id => !first.includes(id)), { timeout: 10_000 }).toBe(true);
    const second = await stock(client);
    expect(second.length).toBeLessThanOrEqual(60);
    expect(second.every(id => !first.includes(id)), "server pages do not repeat the first sixty rows").toBe(true);
    evidence.pages = [first, second];
    await search(client, "minecraft:");
    await expect.poll(() => stock(client), { timeout: 10_000 }).toEqual(first);
    progress("search-registry");
    await search(client, "concrete");
    await expect.poll(async () => (await stock(client)).length, { timeout: 10_000 }).toBe(16);
    expect((await stock(client)).every(id => id.endsWith("_concrete"))).toBe(true);
    await search(client, "MINECRAFT:DIAMOND");
    await expect.poll(() => stock(client), { timeout: 10_000 }).toEqual([PREFIX + "minecraft.diamond"]);
    const original = await Promise.all(boxes.map(async at => (await world.container(server, stage.at(at), { nbt: true })).nbt));
    await search(client, "this-item-does-not-exist");
    await expect.poll(async () => (await stock(client)).length, { timeout: 10_000 }).toBe(0);
    await waitForElement(client, { id: "folkways.metrics.empty" });
    await search(client, "");
    await expect.poll(() => stock(client), { timeout: 10_000 }).toEqual(first);
    await screen.dismiss(client);
    progress("switch-to-chinese");
    await client.run(SET_CHINESE, undefined);
    await expect.poll(async () => (await client.run(READ_TRANSLATION, undefined))["item.minecraft.diamond"], { timeout: 30_000 }).toBe("钻石");
    const words = await client.run(READ_TRANSLATION, undefined); evidence.translations = words;
    expect(words["block.kaleidoscope_cookery.tea_tree"]).toBe("茶树");
    expect(words["folkways.doing.folkways.taking_supplies.about"]).toBe("正在领取补给中的钻石");
    expect(words["folkways.doing.folkways.leaving_millstone"]).toBe("正在离开石磨");
    await openColonyPanelViaBook(server, client, name, founded.standBlock, stage);
    progress("open-storage");
    await clickElement(client, { id: "folkways.tab.metrics" });
    await search(client, "钻石");
    await expect.poll(() => stock(client), { timeout: 10_000 }).toEqual([PREFIX + "minecraft.diamond"]);
    expect(await Promise.all(boxes.map(async at => (await world.container(server, stage.at(at), { nbt: true })).nbt)), "searching never moves or manufactures stock").toEqual(original);
    await world.command(server, `item replace block ${commandPos(stage.at(boxes[2]!))} container.26 with minecraft:diamond 23`);
    await clickElement(client, { id: "folkways.metrics.refresh" });
    await expect.poll(async () => {
      const els = await panelElements(client); const root = els.find(e => e.id === PREFIX + "minecraft.diamond");
      return root ? els.slice(root.index + 1).filter(e => e.depth > root.depth).some(e => e.text === "23") : false;
    }, { timeout: 10_000 }).toBe(true);
    evidence.filtered = await panelElements(client);
    await media.screenshot(client, { path: testInfo.outputPath("inventory-search-zh-cn.png") });
    await search(client, "");
    await expect.poll(async () => (await stock(client)).length, { timeout: 10_000 }).toBe(60);
  } catch (error) { pair?.failing(error); evidence.failure = String(error); throw error; }
  finally { await testInfo.attach("book-feedback-evidence.json", { body: JSON.stringify(evidence, null, 2), contentType: "application/json" }); await pair?.teardown(testInfo); }
});

function shown(elements: Element[], id: string) { return elements.some(e => e.id === id && e.displayed && e.visible && e.width > 0); }
async function stock(client: MinecraftClient) {
  return (await panelElements(client)).filter(e => e.id?.startsWith(PREFIX) && e.displayed).map(e => e.id!);
}
async function search(client: MinecraftClient, value: string) {
  await clickElement(client, { id: "folkways.metrics.search" });
  await input.key(client, { keyCode: 65, modifiers: ["ctrl"] });
  await input.key(client, { keyCode: 259 });
  if (value) await input.key(client, { text: value });
  await frames(client, 3);
}
