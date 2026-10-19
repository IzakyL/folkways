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
const READ_SCENE = defineTask<{ seek: number }, { id: string; title: string; time: number; total: number; keyframes: number[]; scenes: string[]; devices: any[]; residents: any[]; mills: any[] }>({
  name: "folkways.cookery.ponder-read-scene", side: "client", timeoutMs: 20_000,
  source: javaTask({ imports: ["net.minecraft.client.Minecraft", "net.createmod.ponder.foundation.ui.PonderUI", "net.createmod.ponder.foundation.PonderScene",
    "net.minecraft.core.BlockPos", "net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.world.entity.LivingEntity"], body: `
var minecraft=(Minecraft)ctx.client();
if(!(minecraft.screen instanceof PonderUI ui))throw new IllegalStateException("not a Ponder screen");
var seek=Args.of(ctx).value("seek");
if(seek instanceof Number number && number.intValue()>=0)ui.seekToTime(number.intValue());
var scene=ui.getActiveScene();var keyframes=new ArrayList<Object>();
for(int i=0;i<scene.getKeyframeCount();i++)keyframes.add(scene.getKeyframeTime(i));
var visibleField=PonderUI.class.getDeclaredField("scenes");visibleField.setAccessible(true);
var visible=(List<PonderScene>)visibleField.get(ui);
var devices=new ArrayList<Object>();var mills=new ArrayList<Object>();var level=scene.getWorld();
for(int x=0;x<16;x++)for(int y=0;y<16;y++)for(int z=0;z<16;z++){
 var pos=new BlockPos(x,y,z);var state=level.getBlockState(pos);var id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
 if(id.startsWith("kaleidoscope_cookery:")||Set.of("minecraft:redstone_block","minecraft:magma_block","minecraft:campfire").contains(id))
  devices.add(Map.of("id",id,"at",List.of(x,y,z),"state",state.toString()));
 if(id.equals("kaleidoscope_cookery:millstone")&&level.getBlockEntity(pos)!=null){
  var tile=level.getBlockEntity(pos);if(tile.getClass().getSimpleName().equals("MillstoneBlockEntity")){
   var rotation=tile.getClass().getMethod("getCacheRot").invoke(tile);
   mills.add(Map.of("at",List.of(x,y,z),"angle",rotation));
  }
 }
}
var residents=new ArrayList<Object>();
scene.forEachWorldEntity(LivingEntity.class,entity->{
 if(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals("folkways:resident"))
  residents.add(Map.of("at",List.of(entity.getX(),entity.getY(),entity.getZ()),"yaw",entity.getYRot(),"head",entity.getYHeadRot(),"body",entity.yBodyRot));
});
return Map.of("id",scene.getId().toString(),"title",scene.getTitle(),"time",scene.getCurrentTime(),"total",scene.getTotalTime(),"keyframes",keyframes,
 "scenes",visible.stream().map(value->value.getId().toString()).toList(),"devices",devices,"residents",residents,"mills",mills);
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

const READ_REGISTRATION = defineTask<void, any>({
  name: "folkways.cookery.ponder-read-registration", side: "client", timeoutMs: 20_000,
  source: javaTask({ imports: ["io.github.izakyl.folkways.front.api.ponder.Scenes", "io.github.izakyl.folkways.front.client.Lessons",
    "net.createmod.ponder.foundation.PonderIndex", "net.createmod.ponder.api.registration.StoryBoardEntry", "net.minecraft.resources.ResourceLocation"], body: `
var rows=new ArrayList<Object>();var access=PonderIndex.getSceneAccess();
for(var folder:Scenes.declared())if(folder.id().toString().equals("folkways:cookery"))for(var scene:folder.scenes()){
 var schematic=ResourceLocation.fromNamespaceAndPath("folkways",folder.id().getPath()+"/"+scene.id().getPath());
 var registered=new ArrayList<Object>();
 for(var pair:access.getRegisteredEntries())if(pair.getValue().getSchematicLocation().equals(schematic)){
  var entry=pair.getValue();registered.add(Map.of("component",pair.getKey().toString(),"entryComponent",entry.getComponent().toString(),
   "schematic",entry.getSchematicLocation().toString(),"sameBoard",entry.getBoard()==scene.board()));
 }
 rows.add(Map.of("id",scene.id().toString(),"schematic",schematic.toString(),"items",Scenes.itemsFor(scene).stream().map(Object::toString).toList(),
  "registered",registered,"bound",Lessons.scenesFor(scene.id()).stream().map(entry->Map.of("component",entry.getComponent().toString(),
   "schematic",entry.getSchematicLocation().toString(),"sameBoard",entry.getBoard()==scene.board())).toList()));
}
return Map.of("scenes",rows,"folder",Lessons.scenesFor(ResourceLocation.fromNamespaceAndPath("folkways","cookery")).stream()
 .map(entry->Map.of("component",entry.getComponent().toString(),"schematic",entry.getSchematicLocation().toString())).toList());
` }),
});
const RELOAD_PONDER = defineTask<void, boolean>({
  name: "folkways.cookery.ponder-reload-registration", side: "client", timeoutMs: 20_000,
  source: javaTask({ imports: ["net.createmod.ponder.foundation.PonderIndex"], body: "PonderIndex.reload(); return true;" }),
});

function assertRegistration(registry: any, installed: boolean) {
  expect(registry.scenes.map((scene: any) => scene.id)).toEqual(installed ? SCENES.map(scene => "folkways:" + scene) : []);
  expect(registry.folder.map((entry: any) => entry.schematic)).toEqual(installed ? SCENES.map(scene => "folkways:cookery/" + scene) : []);
  expect(registry.folder.every((entry: any) => entry.component === "folkways:colony_book")).toBe(true);
  for (const scene of registry.scenes) {
    const components = [...new Set([...scene.items, "folkways:colony_book"])].sort();
    expect(scene.registered.map((entry: any) => entry.component).sort(), scene.id + " has one true Ponder entry per item").toEqual(components);
    expect(scene.registered.every((entry: any) => entry.component === entry.entryComponent && entry.sameBoard && entry.schematic === scene.schematic)).toBe(true);
    expect(scene.bound, scene.id + " has exactly one canonical playback entry").toEqual([
      { component: "folkways:colony_book", schematic: scene.schematic, sameBoard: true },
    ]);
  }
}

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
minecraft.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
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
    evidence.registration = await client.run(READ_REGISTRATION); assertRegistration(evidence.registration, installed);
    await client.run(RELOAD_PONDER);
    evidence.reloadedRegistration = await client.run(READ_REGISTRATION); assertRegistration(evidence.reloadedRegistration, installed);
    evidence.guiWindow = await client.run(SET_GUI_SCALE, { scale: guiScale });
    expect(evidence.guiWindow.scale).toBe(guiScale); await frames(client, 5);
    await world.command(server, "difficulty peaceful");
    await world.command(server, "ponder folkways:colony_book " + name); await frames(client, 5);
    expect(screenName(await safeScreen(client))).toMatch(/PonderUI/);
    evidence.bookQueue = await client.run(READ_SCENE, { seek: -1 });
    expect(evidence.bookQueue.scenes.filter((id: string) => id.startsWith("folkways:cookery_"))).toEqual(installed ? SCENES.map(scene => "folkways:" + scene) : []);
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
      expect(scene.id).toBe("folkways:" + lesson); expect(scene.scenes, "the visible lesson list contains the chosen scene once").toEqual(["folkways:" + lesson]); expect(scene.keyframes.length).toBeGreaterThanOrEqual(4); expect(scene.total).toBeGreaterThan(300);
      evidence.scenes.push(scene);
      for (const [index, time] of scene.keyframes.entries()) {
        const frame = await client.run(READ_SCENE, { seek: Math.min(Number(time) + 35, Number(scene.total) - 1) }); await frames(client, 4);
        evidence.frameWorlds ??= []; evidence.frameWorlds.push({ scene: lesson, index, ...frame });
        if (lesson === "cookery_spit") {
          const powered = index !== 2;
          expect(frame.devices.filter(device => device.id === "minecraft:redstone_block").map(device => device.at), "one lower-half power source drives both native halves").toEqual(powered ? [[2, 1, 3]] : []);
          const halves = frame.devices.filter(device => device.id === "kaleidoscope_cookery:shawarma_spit");
          expect(halves).toHaveLength(2); expect(halves.every(device => device.state.includes("powered=" + powered))).toBe(true);
        }
        if ((lesson === "cookery_chain" && index === 1) || (lesson === "cookery_millstone" && index === 2)) {
          expect(frame.residents, "the mill demonstration has one actual animated resident").toHaveLength(1);
          const mill = frame.mills.find(tile => tile.at[0] === (lesson === "cookery_chain" ? 2 : 4) && tile.at[2] === (lesson === "cookery_chain" ? 2 : 4));
          expect(mill, "the visible native mill has a recorded rotation").toBeDefined();
          const actor = frame.residents[0]; const radians = mill.angle * Math.PI / 180;
          expect(actor.at[0]).toBeCloseTo(mill.at[0] + 0.5 + 2 * Math.sin(radians), 3);
          expect(actor.at[2]).toBeCloseTo(mill.at[2] + 0.5 + 2 * Math.cos(radians), 3);
          const turn = (yaw: number) => ((yaw + mill.angle + 90) % 360 + 540) % 360 - 180;
          expect(Math.abs(turn(actor.yaw)), "the actor faces the same tangent as the native motor").toBeLessThan(0.1);
          expect(Math.abs(turn(actor.body))).toBeLessThan(0.1);
          expect(Math.abs(turn(actor.head))).toBeLessThan(0.1);
        }
        if (lesson === "cookery_chain" && index === 3) {
          expect(frame.residents).toHaveLength(1);
          expect(frame.residents[0].yaw, "the chopping resident stands west and faces east toward the board").toBeCloseTo(-90, 3);
        }
        if (["cookery_orders", "cookery_pots", "cookery_chain", "cookery_rice", "cookery_steamer", "cookery_teapot"].includes(lesson)) {
          const stoves = frame.devices.filter(device => device.id === "kaleidoscope_cookery:stove");
          expect(stoves, "ordinary heated demonstrations use one native stove per appliance").toHaveLength(lesson === "cookery_pots" ? 2 : 1);
          expect(stoves.every(device => device.at[1] >= 1), "stoves are above the ground plane").toBe(true);
          expect(frame.devices.filter(device => ["minecraft:magma_block", "minecraft:campfire"].includes(device.id))).toHaveLength(0);
        }
        const file = testInfo.outputPath(lesson + "-keyframe-" + index + ".png");
        const shot = await media.screenshot(client, { path: file }); evidence.shots.push({ scene: lesson, index, time, file, sha256: shot.sha256 });
      }
      await input.key(client, { keyCode: 256, action: "press" }); await input.key(client, { keyCode: 256, action: "release" }); await frames(client, 3);
    }
    await shelf(); await clickElement(client, { id: "folkways.lessons.folder.folkways.cookery" }, { scrollIntoView: true });
    await clickElement(client, { id: "folkways.lessons.play.folkways.cookery" }, { scrollIntoView: true }); await frames(client, 5);
    const folderQueue = await client.run(READ_SCENE, { seek: -1 });
    expect(folderQueue.id).toBe("folkways:cookery_orders"); expect(folderQueue.scenes).toEqual(SCENES.map(scene => "folkways:" + scene));
    await openBook();
    await hoverElement(client, { id: "folkways.page.cookery" }, { scrollIntoView: true });
    await input.key(client, { keybind: "key.ponder.ponder", action: "press" });
    await new Promise(resolve => setTimeout(resolve, 2500)); await frames(client, 3);
    await input.key(client, { keybind: "key.ponder.ponder", action: "release" });
    expect(screenName(await safeScreen(client))).toMatch(/LessonScreen/);
    const hoverQueue = await client.run(READ_SCENE, { seek: -1 });
    expect(hoverQueue.id).toBe("folkways:cookery_orders"); expect(hoverQueue.scenes).toEqual(SCENES.map(scene => "folkways:" + scene));
    const linkedItems = [...new Set<string>(evidence.registration.scenes.flatMap((scene: any) => scene.items))];
    for (const item of linkedItems) {
      await screen.dismiss(client); await world.command(server, "ponder " + item + " " + name); await frames(client, 5);
      expect(screenName(await safeScreen(client))).toMatch(/PonderUI/);
      const linked = await client.run(READ_SCENE, { seek: -1 });
      const expected = evidence.registration.scenes.filter((scene: any) => scene.items.includes(item)).map((scene: any) => scene.id);
      expect(linked.scenes.filter(id => id.startsWith("folkways:cookery_")), "actual item UI keeps every distinct linked lesson once: " + item).toEqual(expected);
      evidence.itemLinks ??= []; evidence.itemLinks.push({ item, expected, firstScene: linked });
    }
  } catch (error) { pair?.failing(error); evidence.failure = String(error); throw error; }
  finally {
    const file = testInfo.outputPath("cookery-ponder-evidence.json"); writeFileSync(file, JSON.stringify(evidence, null, 2));
    await testInfo.attach("cookery-ponder-evidence", { path: file, contentType: "application/json" }); await pair?.teardown(testInfo);
  }
});
