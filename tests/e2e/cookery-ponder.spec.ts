import { expect, test } from "@playwright/test";
import { readFileSync, writeFileSync } from "node:fs";
import { defineTask, input, javaTask, media, player, screen, tick, world } from "@izakyl/blockwright-minecraft";
import { clickElement, hoverElement, panelElements } from "../ldlib2";
import { actsForColony, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { frames, safeScreen, screenName } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";

const SCENES = ["cookery_orders", "cookery_tools", "cookery_heat", "cookery_millstone", "cookery_spit", "cookery_steamer",
  "cookery_pots", "cookery_rice", "cookery_equipment", "cookery_seeds", "cookery_tray", "cookery_teapot", "cookery_chain"];

// Presentation seeking replays Ponder's own schedule only. Real production is tested separately.
const READ_SCENE = defineTask<{ seek: number }, { id: string; title: string; time: number; total: number; keyframes: number[] }>({
  name: "folkways.cookery.ponder-read-scene", side: "client", timeoutMs: 20_000,
  source: javaTask({ imports: ["net.minecraft.client.Minecraft", "net.createmod.ponder.foundation.ui.PonderUI"], body: `
var minecraft=(Minecraft)ctx.client();
if(!(minecraft.screen instanceof PonderUI ui))throw new IllegalStateException("not a Ponder screen");
var seek=Args.of(ctx).value("seek");
if(seek instanceof Number number && number.intValue()>=0)ui.seekToTime(number.intValue());
var scene=ui.getActiveScene();var keyframes=new ArrayList<Object>();
for(int i=0;i<scene.getKeyframeCount();i++)keyframes.add(scene.getKeyframeTime(i));
return Map.of("id",scene.getId().toString(),"title",scene.getTitle(),"time",scene.getCurrentTime(),"total",scene.getTotalTime(),"keyframes",keyframes);
` }),
});
const READ_MODS = defineTask<void, { native: boolean; extension: boolean; nativePot: boolean }>({
  name: "folkways.cookery.ponder-read-mods", side: "any", timeoutMs: 20_000,
  source: javaTask({ imports: ["net.neoforged.fml.ModList", "net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.resources.ResourceLocation"], body: `
return Map.of("native",ModList.get().isLoaded("kaleidoscope_cookery"),
"extension",ModList.get().isLoaded("folkways_cookery"),
"nativePot",BuiltInRegistries.BLOCK.containsKey(ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery","pot")));
` }),
});
const READ_FOLDERS = defineTask<void, { folders: string[]; scenes: string[] }>({
  name: "folkways.cookery.ponder-read-folders", side: "client", timeoutMs: 20_000,
  source: javaTask({ imports: ["io.github.izakyl.folkways.front.api.ponder.Scenes"], body: `
return Map.of("folders",Scenes.declared().stream().map(folder->folder.id().toString()).toList(),
"scenes",Scenes.declared().stream().flatMap(folder->folder.scenes().stream()).map(scene->scene.id().toString()).toList());
` }),
});

const SET_LANGUAGE = defineTask<{ locale: string }, string>({
  name: "folkways.cookery.ponder-set-language", side: "client", timeoutMs: 20_000,
  source: javaTask({ imports: ["net.minecraft.client.Minecraft"], body: `
var minecraft=(Minecraft)ctx.client();var locale=Args.of(ctx).string("locale");
minecraft.getLanguageManager().setSelected(locale);minecraft.options.languageCode=locale;
minecraft.reloadResourcePacks();return locale;
` }),
});
const SET_GUI_SCALE = defineTask<{ scale: number }, { scale: number; width: number; height: number; logicalWidth: number; logicalHeight: number }>({
  name: "folkways.cookery.ponder-set-gui-scale", side: "client", timeoutMs: 20_000,
  source: javaTask({ imports: ["net.minecraft.client.Minecraft"], body: `
var minecraft=(Minecraft)ctx.client();int scale=(int)Args.of(ctx).number("scale");
minecraft.options.guiScale().set(scale);minecraft.resizeDisplay();var window=minecraft.getWindow();
return Map.of("scale",window.getGuiScale(),"width",window.getWidth(),"height",window.getHeight(),
"logicalWidth",window.getGuiScaledWidth(),"logicalHeight",window.getGuiScaledHeight());
` }),
});
const READ_LANGUAGE = defineTask<void, { locale: string; title: string }>({
  name: "folkways.cookery.ponder-read-language", side: "client", timeoutMs: 20_000,
  source: javaTask({ imports: ["net.minecraft.client.Minecraft", "net.minecraft.network.chat.Component"], body: `
var minecraft=(Minecraft)ctx.client();return Map.of("locale",minecraft.getLanguageManager().getSelected(),
"title",Component.translatable("folkways.ponder.cookery_orders.header").getString());
` }),
});

const READ_BOOK = defineTask<void, any[]>({
  name: "folkways.cookery.ponder-read-book", side: "server", timeoutMs: 20_000,
  source: javaTask({ imports: ["net.minecraft.core.registries.BuiltInRegistries"], body: `
return ctx.server().getPlayerList().getPlayers().stream().map(player->Map.of(
"held",BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString(),
"menu",player.containerMenu.getClass().getName(),"spectator",player.isSpectator(),"creative",player.isCreative())).toList();
` }),
});

const LOCALES = (process.env.FOLKWAYS_PONDER_LANGUAGES ?? process.env.FOLKWAYS_PONDER_LANGUAGE ?? "en_us").split(",");
const INSTALLATIONS = [
  { kind: "absent", installed: false, native: false, config: "blockwright.toml", title: "optional extension and native mod absent" },
  { kind: "native-only", installed: false, native: true, config: "blockwright.toml#cookery-native", title: "native Cookery remains independent without the optional extension" },
  { kind: "complete", installed: true, native: true, config: "blockwright.toml#cookery", title: "all lessons, UI entries, keyframes and item links" },
] as const;
for (const locale of LOCALES) for (const installation of INSTALLATIONS) test("cookery lessons" + (LOCALES.length > 1 ? " (" + locale + ")" : "") + ": " + installation.title, async ({}, testInfo) => {
  const { installed } = installation;
  test.setTimeout(BUDGET.heavy);
  const runId = newRunId(); let pair: E2EPair | undefined;
  expect(["en_us", "zh_cn", "zh_hk", "zh_tw"]).toContain(locale);
  const guiScale = Number(process.env.FOLKWAYS_PONDER_GUI_SCALE ?? (locale === "en_us" ? "1" : "2"));
  expect([1, 2, 3]).toContain(guiScale);
  const evidence: Record<string, any> = { installed, native: installation.native, installation: installation.kind, profile: installation.config, locale, guiScale, scenes: [], shots: [] };
  try {
    pair = await launchPair({ config: installation.config,
      server: { trace: tracePath("e2e", "cookery-ponder-server-" + runId), instance: seededServer("cookery-ponder-server-" + runId) },
      client: { trace: tracePath("e2e", "cookery-ponder-client-" + runId), instance: "cookery-ponder-client-" + runId } });
    const { server, client } = pair;
    evidence.serverMods = await server.run(READ_MODS); evidence.clientMods = await client.run(READ_MODS);
    for (const loaded of [evidence.serverMods, evidence.clientMods]) {
      expect(loaded.extension, "optional behavior follows the Folkways extension mod id on both sides").toBe(installed);
      expect(loaded.native, "the native kitchen mod has its own independent installation state").toBe(installation.native);
      expect(loaded.nativePot, "native cookware remains registered with or without the extension").toBe(installation.native);
    }
    const grounded = await waitForGroundedPlayer(server); const name = grounded.name;
    if (locale !== "en_us") {
      await client.run(SET_LANGUAGE, { locale });
      const catalog = JSON.parse(readFileSync("mods/core/src/main/resources/assets/folkways/lang/" + locale + ".json", "utf8"));
      await expect.poll(async () => client.run(READ_LANGUAGE), { timeout: 30_000 }).toEqual({
        locale, title: catalog["folkways.ponder.cookery_orders.header"],
      });
      await frames(client, 5);
    }
    evidence.guiWindow = await client.run(SET_GUI_SCALE, { scale: guiScale });
    expect(evidence.guiWindow.scale).toBe(guiScale); await frames(client, 5);
    await world.command(server, "difficulty peaceful");
    await world.command(server, "ponder folkways:colony_book " + name); await frames(client, 5);
    expect(screenName(await safeScreen(client))).toMatch(/PonderUI/);
    evidence.folders = await client.run(READ_FOLDERS);
    expect(evidence.folders.folders.includes("folkways:cookery")).toBe(installed);
    expect(evidence.folders.scenes.filter((id: string) => id.startsWith("folkways:cookery_"))).toEqual(installed ? SCENES.map(scene => "folkways:" + scene) : []);
    await screen.dismiss(client);
    await world.command(server, "give " + name + " folkways:colony_book");
    await world.command(server, "item replace entity " + name + " weapon.mainhand with folkways:colony_book");
    await tick.sprint(server, 5);
    await world.command(server, "gamemode survival " + name);
    const openBook = async () => {
      await screen.dismiss(client);
      evidence.serverBooks ??= []; evidence.serverBooks.push(await server.run(READ_BOOK));
      const use = await player.useItem(client);
      evidence.bookUses ??= []; evidence.bookUses.push(use);
      await tick.sprint(server, 3); await frames(client, 4);
      evidence.serverBooks.push(await server.run(READ_BOOK));
      await screen.waitFor(client, "ModularUIContainerScreen", { timeoutMs: 10_000 });
      return safeScreen(client);
    };
    await openBook();
    await clickElement(client, { id: "folkways.colony.create" });
    await tick.sprint(server, 10);
    expect(await actsForColony(server, name)).toBe(true);
    const shelf = async () => {
      await openBook();
      await clickElement(client, { id: "folkways.tab.lessons" }, { scrollIntoView: true });
    };
    await shelf();
    const folders = await panelElements(client);
    expect(folders.some(element => element.id === "folkways.lessons.folder.folkways.cookery")).toBe(installed);
    expect(folders.some(element => element.id === "folkways.page.cookery"), "the kitchen page belongs to the optional Folkways extension").toBe(installed);
    if (!installed) return;
    for (const lesson of SCENES) {
      await shelf(); await clickElement(client, { id: "folkways.lessons.folder.folkways.cookery" }, { scrollIntoView: true });
      await clickElement(client, { id: "folkways.lessons.scene.folkways." + lesson }, { scrollIntoView: true });
      await frames(client, 5); expect(screenName(await safeScreen(client))).toMatch(/LessonScreen/);
      const scene = await client.run(READ_SCENE, { seek: -1 });
      expect(scene.id).toBe("folkways:" + lesson); expect(scene.keyframes.length).toBeGreaterThanOrEqual(4); expect(scene.total).toBeGreaterThan(300);
      evidence.scenes.push(scene);
      for (const [index, time] of scene.keyframes.entries()) {
        await client.run(READ_SCENE, { seek: Math.min(Number(time) + 35, Number(scene.total) - 1) }); await frames(client, 4);
        const file = testInfo.outputPath(lesson + "-keyframe-" + index + ".png");
        const shot = await media.screenshot(client, { path: file }); evidence.shots.push({ scene: lesson, index, time, file, sha256: shot.sha256 });
      }
      await input.key(client, { keyCode: 256, action: "press" }); await input.key(client, { keyCode: 256, action: "release" }); await frames(client, 3);
    }
    await shelf(); await clickElement(client, { id: "folkways.lessons.folder.folkways.cookery" }, { scrollIntoView: true });
    await clickElement(client, { id: "folkways.lessons.play.folkways.cookery" }, { scrollIntoView: true }); await frames(client, 5);
    expect((await client.run(READ_SCENE, { seek: -1 })).id).toBe("folkways:cookery_orders");
    await openBook();
    await hoverElement(client, { id: "folkways.page.cookery" }, { scrollIntoView: true });
    await input.key(client, { keybind: "key.ponder.ponder", action: "press" });
    await new Promise(resolve => setTimeout(resolve, 2500)); await frames(client, 3);
    await input.key(client, { keybind: "key.ponder.ponder", action: "release" });
    expect(screenName(await safeScreen(client))).toMatch(/LessonScreen/);
    expect((await client.run(READ_SCENE, { seek: -1 })).id).toBe("folkways:cookery_orders");
    for (const item of ["millstone", "pot", "teapot", "straw_hat", "kitchenware_racks"]) {
      await screen.dismiss(client); await world.command(server, "ponder kaleidoscope_cookery:" + item + " " + name); await frames(client, 5);
      expect(screenName(await safeScreen(client))).toMatch(/PonderUI/);
      evidence.itemLinks ??= []; evidence.itemLinks.push({ item, firstScene: await client.run(READ_SCENE, { seek: -1 }) });
    }
  } catch (error) { pair?.failing(error); evidence.failure = String(error); throw error; }
  finally {
    const file = testInfo.outputPath("cookery-ponder-evidence.json"); writeFileSync(file, JSON.stringify(evidence, null, 2));
    await testInfo.attach("cookery-ponder-evidence", { path: file, contentType: "application/json" }); await pair?.teardown(testInfo);
  }
});
