import { expect, test } from "@playwright/test";
import { defineTask, input, javaTask, media, screen, world, type MinecraftClient } from "@izakyl/blockwright-minecraft";
import { clickElement, panelElements } from "../ldlib2";
import { foundColonyFast, foundingBox, openColonyPanelViaBook, optInViaBook, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { commandPos, frames, waitForElement } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { GROUND } from "../shared/harness/stage";
import { clickTab } from "../shared/ui/colony-pages";

const CHINESE = defineTask<void, string>({
  name: "folkways.jech.chinese", side: "client",
  source: javaTask({ imports: ["net.minecraft.client.Minecraft"], body: `
var mc=(Minecraft)ctx.client();mc.getLanguageManager().setSelected("zh_cn");mc.options.languageCode="zh_cn";
mc.reloadResourcePacks();return "zh_cn";
` }),
});
const TRANSLATION = defineTask<void, string>({
  name: "folkways.jech.translation", side: "client",
  source: javaTask({ imports: ["net.minecraft.network.chat.Component"], body: `
return Component.translatable("item.minecraft.iron_ingot").getString();
` }),
});
const MATCHING = defineTask<void, Record<string, boolean>>({
  name: "folkways.jech.matching", side: "client",
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.front.api.ui.TextSearch",
    "io.github.izakyl.folkways.front.ui.screen.RosterQuery",
  ], body: `
var fields=new RosterQuery.Fields(List.of("name","姓名"),List.of("doing","工作"));
var entry=new RosterQuery.Entry("张三","正在采矿",List.of(new RosterQuery.Trade(List.of("mining","采矿"),1)));
return Map.of("full",TextSearch.contains("铁锭","tieding"),"initials",TextSearch.contains("鐵錠","td"),
 "mixed",TextSearch.contains("铁锭","tie锭"),"case",TextSearch.contains("铁锭","TIEDING"),
 "literal",TextSearch.contains("铁锭 Iron Ingot","IRON"),
 "roster",RosterQuery.parse("name=zhangsan doing=caikuang mining=1",fields).test(entry),
 "negated",RosterQuery.parse("-name=zhangsan",fields).test(entry),
 "operators",RosterQuery.parse("name=不存在 | mining<=2",fields).test(entry));
` }),
});
const FOLDER = defineTask<void, boolean>({
  name: "folkways.jech.folder", side: "client",
  source: javaTask({ imports: [
    "com.lowdragmc.lowdraglib2.gui.ui.ModularUI",
    "com.lowdragmc.lowdraglib2.gui.ui.elements.Button",
    "com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog",
    "io.github.izakyl.folkways.front.api.ui.FolderPicker",
    "io.github.izakyl.folkways.front.api.ui.Tokens",
    "net.minecraft.client.Minecraft",
    "net.minecraft.network.chat.Component",
  ], body: `
var picker=new FolderPicker("folkways.jech.fixture",false,(word,key)->{}).openRoots()
 .naming(folder->Component.literal("建筑"));
picker.fill(List.of(
 new FolderPicker.Leaf("iron","test:build/iron",Component.literal("铁锭"),"folkways.jech.fixture.iron"),
 new FolderPicker.Leaf("stone","test:build/stone",Component.literal("石头"),"folkways.jech.fixture.stone")));
var dialog=new Dialog();dialog.setTitle("JEC search fixture");
dialog.layout(layout->layout.width(460).height(300));dialog.style(style->style.zIndex(10000));
dialog.addContent(picker.list());
var close=new Button().setText("Close").setOnClick(event->dialog.close());
Tokens.name(close,"folkways.jech.fixture.close");dialog.addButton(close);
dialog.show(ModularUI.of(((Minecraft)ctx.client()).screen));return true;
` }),
});
const SETTINGS = defineTask<void, Record<string, boolean | number>>({
  name: "folkways.jech.settings", side: "client",
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.front.api.ui.TextSearch",
    "me.towdium.jecharacters.config.JechConfig",
    "me.towdium.jecharacters.utils.Match",
  ], body: `
var keyboard=JechConfig.enumKeyboard;boolean fuzzy=JechConfig.enableFSh2s;
var extension=TextSearch.class.getDeclaredField("extension");extension.setAccessible(true);
var original=(java.util.function.BiPredicate<String,String>)extension.get(null);
var result=new LinkedHashMap<String,Object>();
try {
 JechConfig.enumKeyboard=JechConfig.Spell.QUANPIN;JechConfig.enableFSh2s=false;Match.onConfigChange();
 var prepared=TextSearch.query("sitou");result.put("strict",prepared.test("石头"));
 JechConfig.enableFSh2s=true;Match.onConfigChange();result.put("fuzzy",prepared.test("石头"));
 var shuangpin=TextSearch.query("uitz");result.put("quanpin",shuangpin.test("石头"));
 JechConfig.enumKeyboard=JechConfig.Spell.XIAOHE;Match.onConfigChange();result.put("shuangpin",shuangpin.test("石头"));
 JechConfig.enumKeyboard=JechConfig.Spell.QUANPIN;Match.onConfigChange();
 var calls=new java.util.concurrent.atomic.AtomicInteger();
 TextSearch.install((text,query)->{calls.incrementAndGet();return Match.contains(text,query);});
 TextSearch.contains("铁锭","铁");TextSearch.contains("iron ingot","no-match");
 result.put("fastPathCalls",calls.get());
 result.put("delegated",TextSearch.contains("铁锭","tieding"));result.put("extendedCalls",calls.get());
} finally {
 TextSearch.install(original);JechConfig.enumKeyboard=keyboard;JechConfig.enableFSh2s=fuzzy;Match.onConfigChange();
}
return result;
` }),
});

for (const installed of [false, true]) test(`display search with JEC ${installed ? "installed" : "absent"}`, async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  ensureOut("e2e");
  const run = newRunId();
  const evidence: Record<string, unknown> = { installed };
  let pair: E2EPair | undefined;
  try {
    pair = await launchPair({
      config: installed ? "blockwright.toml#jech" : "blockwright.toml",
      server: { instance: seededServer(`jech-server-${run}`), trace: tracePath("e2e", `jech-server-${run}`) },
      client: { instance: `jech-client-${run}`, trace: tracePath("e2e", `jech-client-${run}`) },
    });
    const { server, client } = pair;
    await client.run(CHINESE, undefined);
    await expect.poll(() => client.run(TRANSLATION, undefined), { timeout: 30_000 }).toBe("铁锭");
    const matching = await client.run(MATCHING, undefined);
    evidence.matching = matching;
    expect(matching).toEqual({ full: installed, initials: installed, mixed: installed, case: installed,
      literal: true, roster: installed, negated: !installed, operators: true });
    if (installed) {
      evidence.settings = await client.run(SETTINGS, undefined);
      expect(evidence.settings).toEqual({ strict: false, fuzzy: true, quanpin: false, shuangpin: true,
        fastPathCalls: 0, delegated: true, extendedCalls: 1 });
    }

    const grounded = await waitForGroundedPlayer(server);
    const player = grounded.name;
    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    await world.command(server, `gamemode survival ${player}`);
    await GROUND.lay(server, foundingBox(origin));
    const founded = await foundColonyFast(server, client, player, { origin, residentCount: 1 });
    const barrel = { x: origin.x - 4, y: origin.y, z: origin.z + 4 };
    await world.command(server, `setblock ${commandPos(barrel)} minecraft:barrel{Items:[{Slot:0b,id:"minecraft:iron_ingot",count:13}]}`);
    expect((await optInViaBook(server, client, player, barrel, "chest")).member).toBe(true);
    await openColonyPanelViaBook(server, client, player, founded.standBlock);
    await clickTab(client, "folkways.page.build");
    await clickElement(client, { id: "folkways.settings.open.tool.folkways.building.folkways.build" }, { scrollIntoView: true });
    await waitForElement(client, { id: "folkways.selector.search" });
    for (const query of ["tieding", "td", "tie锭", "@minecraft TIEDING"]) {
      await search(client, "folkways.selector.search", query);
      expect(await shown(client, "folkways.selector.result.minecraft.iron_ingot"), query).toBe(installed);
    }
    for (const query of ["铁锭", "minecraft:iron_ingot", "*_ingot"]) {
      await search(client, "folkways.selector.search", query);
      expect(await shown(client, "folkways.selector.result.minecraft.iron_ingot"), query).toBe(true);
    }
    await search(client, "folkways.selector.search", "#minecraft:logs tieding");
    expect(await shown(client, "folkways.selector.result.minecraft.iron_ingot")).toBe(false);
    await media.screenshot(client, { path: testInfo.outputPath("item-picker.png") });
    await clickElement(client, { id: "folkways.selector.close" });
    await client.run(FOLDER, undefined);
    for (const query of ["td", "jianzhu", "铁锭", "TEST:BUILD/IRON"]) {
      await search(client, "folkways.jech.fixture.search", query);
      expect(await shown(client, "folkways.jech.fixture.iron"), query)
        .toBe(query === "铁锭" || query === "TEST:BUILD/IRON" || installed);
      expect(await shown(client, "folkways.jech.fixture.stone"), query).toBe(query === "jianzhu" && installed);
    }
    await media.screenshot(client, { path: testInfo.outputPath("folder-picker.png") });
    await clickElement(client, { id: "folkways.jech.fixture.close" });
    await clickTab(client, "folkways.tab.metrics");
    await clickElement(client, { id: "folkways.metrics.refresh" });
    const stock = "folkways.metrics.stock.minecraft.iron_ingot";
    await expect.poll(() => shown(client, stock), { timeout: 15_000 }).toBe(true);
    await search(client, "folkways.metrics.search", "tieding");
    await expect.poll(() => shown(client, stock), { timeout: 10_000 }).toBe(installed);
    await search(client, "folkways.metrics.search", "铁锭");
    await expect.poll(() => shown(client, stock), { timeout: 10_000 }).toBe(true);
    await media.screenshot(client, { path: testInfo.outputPath("stock.png") });
    evidence.inventory = await world.container(server, barrel, { nbt: true });
    await screen.dismiss(client);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await testInfo.attach("jech-search.json", { body: JSON.stringify(evidence, null, 2), contentType: "application/json" });
    await pair?.teardown(testInfo);
  }
});

async function shown(client: MinecraftClient, id: string) {
  return (await panelElements(client)).some(e => e.id === id && e.displayed && e.visible && e.width > 0);
}

async function search(client: MinecraftClient, id: string, value: string) {
  await clickElement(client, { id });
  await input.key(client, { keyCode: 65, modifiers: ["ctrl"] });
  await input.key(client, { keyCode: 259 });
  if (value) await input.key(client, { text: value });
  await frames(client, 3);
}
