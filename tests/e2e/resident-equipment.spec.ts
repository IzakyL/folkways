import { expect, test, type TestInfo } from "@playwright/test";
import { defineTask, input, javaTask, media, screen, tick, type MinecraftClient } from "@izakyl/blockwright-minecraft";
import { clickElement, hoverElement, panelElements, scrollElementIntoView, waitForElement, type Element } from "../ldlib2";
import { foundColonyFast, openColonyPanelViaBook, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { setVisualLanguage } from "../shared/capture/ponder";
import { frames } from "../shared/harness/bw-helpers";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { clickTab } from "../shared/ui/colony-pages";
import { mustCommand } from "../shared/ui/world-ui";

const E = "folkways.equipment.";
type Scene = { colony: string; player: string; resident: string; standBlock: Vec };
type Reading = {
  player: string[]; pack: string[]; worn: string[];
  assigned: string; head: string; sourceSword: number; sourceHelmet: number; sourceBow: number;
  swords: number; helmets: number; bows: number; swordWear: number; helmetWear: number;
  autoEnabled: boolean; savedDisabled: boolean;
};
type Evidence = Array<{ step: string; value: unknown }>;

test("resident equipment uses real item drags; invalid drops and closing a held drag preserve both inventories", async ({}, testInfo) => {
  test.setTimeout(360_000);
  await withScene(testInfo, async (pair, scene, evidence) => {
    const { server, client } = pair;
    const before = await server.run(READ, scene);

    await dragTo(client, `${E}player.11`, `${E}open`, evidence);
    await sync(pair);
    expect(inventories(await server.run(READ, scene)), "dropping a real held bow on the equipment heading has no inventory side effect")
      .toEqual(inventories(before));
    evidence.push({ step: "invalid drop preserved inventories", value: await server.run(READ, scene) });

    await beginDrag(client, `${E}player.11`, evidence);
    try {
      // Escape is delivered through the actual screen input route while left remains pressed.
      await input.key(client, { keyCode: 256 });
      await frames(client, 3);
    } finally {
      await input.button(client, { button: "left", action: "release" });
    }
    await sync(pair);
    expect(inventories(await server.run(READ, scene)), "closing during an actual drag must neither equip nor remove its source")
      .toEqual(inventories(before));
    await openEquipment(pair, scene);
    expect((await client.run(DRAG_STATE)).dragging, "a reopened detail cannot inherit the previous screen's drag").toBe(false);

    await dragTo(client, `${E}player.9`, `${E}slot.mainhand`, evidence);
    await expect.poll(async () => {
      await sync(pair);
      return (await server.run(READ, scene)).assigned;
    }, { timeout: 15_000, message: "the dropped player sword must become this resident's actual designated main hand" }).toBe("UI drag sword");
    let reading = await server.run(READ, scene);
    expect(reading.sourceSword).toBe(0);
    expect(reading.swords, "one sword across the player's real inventory, the resident pack and all worn slots").toBe(1);
    expect(reading.swordWear, "components must travel with the source item").toBe(7);
    expect(await textOf(client, `${E}worn.mainhand`)).toContain("UI drag sword");
    evidence.push({ step: "real mouse drop assigned unique sword", value: reading });

    await dragTo(client, `${E}player.10`, `${E}slot.head`, evidence);
    await expect.poll(async () => {
      await sync(pair);
      return (await server.run(READ, scene)).head;
    }, { timeout: 15_000, message: "dropping onto HEAD must put the same helmet in the native armor slot" }).toBe("UI drag helmet");
    reading = await server.run(READ, scene);
    expect(reading.sourceHelmet).toBe(0);
    expect(reading.helmets).toBe(1);
    expect(reading.helmetWear).toBe(4);
    expect(reading.swords).toBe(1);
    expect(reading.sourceBow, "the two cancelled gestures leave their spare bow in its original player cell").toBe(1);
    expect(reading.bows).toBe(1);
    expect(await textOf(client, `${E}worn.head`)).toContain("UI drag helmet");
    evidence.push({ step: "real mouse drop wore unique helmet", value: reading });
    await capture(client, testInfo, "resident-equipment-after-drags.png");
  });
});

test("resident equipment auto ammunition is clickable and persists when its detail is closed and reopened", async ({}, testInfo) => {
  test.setTimeout(360_000);
  await withScene(testInfo, async (pair, scene, evidence) => {
    const { server, client } = pair;
    const before = await server.run(READ, scene);
    expect(before.autoEnabled).toBe(true);
    expect(before.savedDisabled).toBe(false);
    await clickElement(client, { id: `${E}ammo_auto` }, { scrollIntoView: true });
    await expect.poll(async () => {
      await sync(pair);
      const reading = await server.run(READ, scene);
      return { enabled: reading.autoEnabled, savedDisabled: reading.savedDisabled };
    }, { timeout: 15_000 }).toEqual({ enabled: false, savedDisabled: true });
    await expect.poll(() => textOf(client, `${E}ammo_auto`)).toContain("Auto ammunition: off");
    evidence.push({ step: "UI switched off and colony save contains resident", value: await server.run(READ, scene) });
    await clickElement(client, { id: "folkways.window.close.resident" });
    await waitForElement(client, { id: "folkways.window.resident" }, { state: "absent" });
    await openEquipment(pair, scene);
    await scrollElementIntoView(client, { id: `${E}ammo_auto` });
    expect(await textOf(client, `${E}ammo_auto`), "a new detail screen must read the persisted disabled value").toContain("Auto ammunition: off");
    expect((await server.run(READ, scene)).savedDisabled).toBe(true);
    await capture(client, testInfo, "resident-equipment-auto-off-reopened.png");

    await clickElement(client, { id: `${E}ammo_auto` });
    await expect.poll(async () => {
      await sync(pair);
      const reading = await server.run(READ, scene);
      return { enabled: reading.autoEnabled, savedDisabled: reading.savedDisabled };
    }, { timeout: 15_000 }).toEqual({ enabled: true, savedDisabled: false });
    await openEquipment(pair, scene);
    await scrollElementIntoView(client, { id: `${E}ammo_auto` });
    expect(await textOf(client, `${E}ammo_auto`)).toContain("Auto ammunition: on");
    const after = await server.run(READ, scene);
    expect(inventories(after), "toggling maintenance must not itself create, equip or consume items").toEqual(inventories(before));
    evidence.push({ step: "UI switched on and fresh detail read it back", value: after });
  });
});

async function withScene(testInfo: TestInfo, act: (pair: E2EPair, scene: Scene, evidence: Evidence) => Promise<void>) {
  let pair: E2EPair | undefined;
  let scene: Scene | undefined;
  const evidence: Evidence = [];
  try {
    const run = newRunId();
    pair = await launchPair({
      server: { instance: `resident-equipment-server-${run}` },
      client: { instance: `resident-equipment-client-${run}` },
      snapshot: { entityTypes: ["folkways:resident"] },
    });
    const { server, client } = pair;
    await setVisualLanguage(client, "en_us");
    const viewport = await client.run(GUI_SCALE, {});
    expect(viewport.width).toBeGreaterThan(600);
    expect(viewport.height).toBeGreaterThan(460);
    const grounded = await waitForGroundedPlayer(server);
    await mustCommand(server, `gamemode survival ${grounded.name}`);
    await mustCommand(server, "difficulty peaceful");
    await mustCommand(server, "gamerule doMobSpawning false");
    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    await mustCommand(server, `fill ${origin.x - 9} ${origin.y - 1} ${origin.z - 9} ${origin.x + 9} ${origin.y - 1} ${origin.z + 9} minecraft:smooth_stone`);
    const founding = await foundColonyFast(server, client, grounded.name, { origin, residentCount: 1 });
    const setup = await server.run(SETUP, { colony: String(founding.evidence.colonyId), player: grounded.name, standBlock: founding.standBlock });
    scene = { ...setup, standBlock: founding.standBlock };
    evidence.push({ step: "server-only fixture before UI actions", value: await server.run(READ, scene) });
    await openEquipment(pair, scene);
    const elements = await panelElements(client);
    const detail = elements.find(element => element.id === "folkways.window.resident")!;
    expect(detail.width, "resident equipment gets its dedicated 420 GUI-pixel detail window").toBeCloseTo(420, 1);
    expect(detail.x).toBeGreaterThanOrEqual(0);
    expect(detail.x + detail.width).toBeLessThanOrEqual(viewport.width + .1);
    evidence.push({ step: "420-wide real resident equipment detail", value: detail });
    await capture(client, testInfo, "resident-equipment-open.png");
    await act(pair, scene, evidence);
  } catch (error) {
    pair?.failing(error);
    if (pair) {
      try {
        evidence.push({ step: "failure live UI", value: await panelElements(pair.client) });
        await capture(pair.client, testInfo, "resident-equipment-failure.png");
      } catch { /* Keep the original failure when the screen itself has closed. */ }
    }
    throw error;
  } finally {
    if (pair) {
      try { await input.button(pair.client, { button: "left", action: "release" }); await screen.dismiss(pair.client); } catch { /* Preserve evidence. */ }
      if (scene) {
        try { await pair.server.run(CLEANUP, scene); } catch { /* Preserve the primary assertion. */ }
      }
    }
    await testInfo.attach("resident-equipment-evidence.json", { body: JSON.stringify(evidence, null, 2), contentType: "application/json" });
    await pair?.teardown(testInfo);
  }
}

async function openEquipment(pair: E2EPair, scene: Scene) {
  await openColonyPanelViaBook(pair.server, pair.client, scene.player, scene.standBlock);
  await clickTab(pair.client, "folkways.tab.residents");
  await clickElement(pair.client, { id: `folkways.resident.name.${scene.resident.slice(0, 8)}` }, { scrollIntoView: true });
  await waitForElement(pair.client, { id: "folkways.window.resident" });
  await clickElement(pair.client, { id: `${E}open` }, { scrollIntoView: true });
  await scrollElementIntoView(pair.client, { id: `${E}slot.mainhand` });
  await waitForElement(pair.client, { id: `${E}slot.mainhand` });
}

async function sync(pair: E2EPair) { await tick.sprint(pair.server, 4); await frames(pair.client, 3); }
function center(element: Element) { return { x: element.x + element.width / 2, y: element.y + element.height / 2 }; }
function inventories(reading: Reading) { return { player: reading.player, pack: reading.pack, worn: reading.worn }; }

async function beginDrag(client: MinecraftClient, source: string, evidence: Evidence) {
  await scrollElementIntoView(client, { id: source });
  const cell = (await waitForElement(client, { id: source }))!;
  await hoverElement(client, { id: source });
  const at = center(cell);
  const pressed = await input.button(client, { button: "left", action: "press" });
  const moved = await input.move(client, { x: at.x + 8, y: at.y });
  await frames(client, 2);
  const state = await client.run(DRAG_STATE);
  evidence.push({ step: `real mouse starts ${source}`, value: { pressed, moved, state } });
  expect(moved.dragged, "MouseHandler must deliver an actual held-button move").toBe(true);
  expect(state.dragging,
    "LDLib2 must really enter the equipment drag before any drop; if MouseHandler is held but GLFW leftDown is false, inspect the input-state boundary instead of synthesizing a payload")
    .toBe(true);
}

async function dragTo(client: MinecraftClient, source: string, destination: string, evidence: Evidence) {
  try {
    await beginDrag(client, source, evidence);
    // Slots and source inventory are vertically separated. Wheel the actual viewport while holding
    // the mouse; never call DRAG_PERFORM or drop at a hidden slot's unclipped layout coordinates.
    await scrollElementIntoView(client, { id: destination });
    const target = (await waitForElement(client, { id: destination }))!;
    await input.move(client, center(target));
    await hoverElement(client, { id: destination });
    evidence.push({ step: `held drag reaches ${destination}`, value: { target, state: await client.run(DRAG_STATE) } });
  } finally {
    await input.button(client, { button: "left", action: "release" });
  }
  await frames(client, 3);
}

async function textOf(client: MinecraftClient, id: string) {
  const elements = await panelElements(client);
  const root = elements.find(element => element.id === id);
  if (!root) return "";
  const words = [root.text ?? ""];
  for (let at = root.index + 1; at < elements.length && elements[at].depth > root.depth; at++) {
    if (elements[at].displayed && elements[at].text) words.push(elements[at].text!);
  }
  return words.join(" ");
}

async function capture(client: MinecraftClient, testInfo: TestInfo, name: string) {
  const path = testInfo.outputPath(name);
  await media.screenshot(client, { path });
  await testInfo.attach(name, { path, contentType: "image/png" });
}

const GUI_SCALE = defineTask<Record<string, never>, { width: number; height: number }>({
  name: "folkways.equipment-e2e.gui-scale", side: "client",
  source: javaTask({ imports: ["net.minecraft.client.Minecraft"], body: `
var mc=Minecraft.getInstance();mc.options.guiScale().set(1);mc.resizeDisplay();
return Map.of("width",mc.getWindow().getGuiScaledWidth(),"height",mc.getWindow().getGuiScaledHeight());` }),
});

const DRAG_STATE = defineTask<void, { dragging: boolean; glfwLeft: boolean; handlerLeft: boolean; activeButton: number; payload: string }>({
  name: "folkways.equipment-e2e.read-drag", side: "client",
  source: javaTask({ imports: ["net.minecraft.client.Minecraft", "org.lwjgl.glfw.GLFW", "dev.blockwright.api.access.MouseHandlerAccessor", "com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolder"], body: `
var mc=Minecraft.getInstance();boolean left=GLFW.glfwGetMouseButton(mc.getWindow().getWindow(),0)==GLFW.GLFW_PRESS;
boolean handlerLeft=mc.mouseHandler.isLeftPressed();int active=((MouseHandlerAccessor)mc.mouseHandler).blockwright$activeButton();
if(mc.player==null||!(mc.player.containerMenu instanceof IModularUIHolder holder)||holder.getModularUI()==null)
  return Map.of("dragging",false,"glfwLeft",left,"handlerLeft",handlerLeft,"activeButton",active,"payload","");
var drag=holder.getModularUI().getDragHandler();var payload=drag.getDraggingObject();
return Map.of("dragging",drag.isDragging(),"glfwLeft",left,"handlerLeft",handlerLeft,"activeButton",active,"payload",payload==null?"":payload.getClass().getName());` }),
});

const IMPORTS = ["java.util.UUID", "java.util.ArrayList", "java.util.Map", "net.minecraft.server.MinecraftServer",
  "net.minecraft.world.item.ItemStack", "net.minecraft.world.item.Items", "net.minecraft.world.entity.EquipmentSlot",
  "net.minecraft.core.component.DataComponents", "net.minecraft.network.chat.Component", "net.minecraft.nbt.Tag",
  "io.github.izakyl.folkways.core.api.colony.Colonies", "io.github.izakyl.folkways.core.api.terms.WorldSpaces",
  "io.github.izakyl.folkways.plugins.person.PersonBody", "io.github.izakyl.folkways.plugins.person.ResidentEntity",
  "io.github.izakyl.folkways.plugins.person.equipment.ResidentEquipment", "io.github.izakyl.folkways.plugins.person.equipment.EquipmentPlans",
  "io.github.izakyl.folkways.plugins.combat.command.ResidentCommands"];

// Fixture preparation may use server APIs. No task below invokes the UI's act/equip/toggle endpoint.
const SETUP = defineTask<{ colony: string; player: string; standBlock: Vec }, Omit<Scene, "standBlock">>({
  name: "folkways.equipment-e2e.fixture", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var a=Args.of(ctx);var server=(MinecraftServer)ctx.server();var level=server.overworld();
var colony=Colonies.of(server,UUID.fromString(a.string("colony"))).orElseThrow();
var player=server.getPlayerList().getPlayerByName(a.string("player"));
var resident=colony.residents().stream().filter(r->r.kind().equals(PersonBody.ID))
  .map(r->level.getEntity(r.id())).filter(e->e instanceof ResidentEntity).map(e->(ResidentEntity)e).findFirst().orElseThrow();
resident.stopWeaponSession();resident.setNoAi(true);resident.pack().clearContent();
for(var slot:ResidentEquipment.SLOTS)resident.setItemSlot(slot,ItemStack.EMPTY);
var at=a.requireObject("standBlock");resident.moveTo(at.number("x")+2.5,at.number("y"),at.number("z")+1.5);resident.setOnGround(true);
ResidentCommands.of(colony).hold(resident,WorldSpaces.at(resident));
var sword=new ItemStack(Items.IRON_SWORD);sword.setDamageValue(7);sword.set(DataComponents.CUSTOM_NAME,Component.literal("UI drag sword"));
var helmet=new ItemStack(Items.IRON_HELMET);helmet.setDamageValue(4);helmet.set(DataComponents.CUSTOM_NAME,Component.literal("UI drag helmet"));
var bow=new ItemStack(Items.BOW);bow.set(DataComponents.CUSTOM_NAME,Component.literal("UI cancelled bow"));
player.getInventory().setItem(9,sword);player.getInventory().setItem(10,helmet);player.getInventory().setItem(11,bow);
player.inventoryMenu.broadcastChanges();
return Map.of("colony",colony.id().toString(),"player",player.getGameProfile().getName(),"resident",resident.id().toString());` }),
});

const READ = defineTask<Scene, Reading>({
  name: "folkways.equipment-e2e.read-real-inventories", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var a=Args.of(ctx);var server=(MinecraftServer)ctx.server();var level=server.overworld();
var colony=Colonies.of(server,UUID.fromString(a.string("colony"))).orElseThrow();
var resident=(ResidentEntity)level.getEntity(UUID.fromString(a.string("resident")));
var player=server.getPlayerList().getPlayerByName(a.string("player"));
var playerCells=new ArrayList<String>();var pack=new ArrayList<String>();var worn=new ArrayList<String>();var all=new ArrayList<ItemStack>();
for(int i=0;i<36;i++){var stack=player.getInventory().getItem(i);playerCells.add(stack.saveOptional(level.registryAccess()).toString());all.add(stack);}
for(int i=0;i<resident.pack().getContainerSize();i++){var stack=resident.pack().getItem(i);pack.add(stack.saveOptional(level.registryAccess()).toString());all.add(stack);}
for(var slot:ResidentEquipment.SLOTS){var stack=resident.getItemBySlot(slot);worn.add(stack.saveOptional(level.registryAccess()).toString());all.add(stack);}
var assigned=ResidentEquipment.assigned(resident);var head=resident.getItemBySlot(EquipmentSlot.HEAD);
boolean disabled=false;for(var tag:colony.kept(EquipmentPlans.ID).getList("ammunitionDisabled",Tag.TAG_COMPOUND)){
  var row=(net.minecraft.nbt.CompoundTag)tag;if(row.hasUUID("resident")&&row.getUUID("resident").equals(resident.id()))disabled=true;
}
var result=new java.util.LinkedHashMap<String,Object>();
result.put("player",playerCells);result.put("pack",pack);result.put("worn",worn);
result.put("assigned",assigned.isEmpty()?"":assigned.getHoverName().getString());result.put("head",head.isEmpty()?"":head.getHoverName().getString());
result.put("sourceSword",player.getInventory().getItem(9).getCount());result.put("sourceHelmet",player.getInventory().getItem(10).getCount());result.put("sourceBow",player.getInventory().getItem(11).getCount());
result.put("swords",all.stream().filter(s->s.is(Items.IRON_SWORD)).mapToInt(ItemStack::getCount).sum());
result.put("helmets",all.stream().filter(s->s.is(Items.IRON_HELMET)).mapToInt(ItemStack::getCount).sum());
result.put("bows",all.stream().filter(s->s.is(Items.BOW)).mapToInt(ItemStack::getCount).sum());
result.put("swordWear",assigned.isEmpty()?0:assigned.getDamageValue());result.put("helmetWear",head.isEmpty()?0:head.getDamageValue());
result.put("autoEnabled",EquipmentPlans.ammoEnabled(colony,resident.id()));result.put("savedDisabled",disabled);return result;` }),
});

const CLEANUP = defineTask<Scene, boolean>({
  name: "folkways.equipment-e2e.cleanup", side: "server",
  source: javaTask({ imports: IMPORTS, body: `
var a=Args.of(ctx);var server=(MinecraftServer)ctx.server();var entity=server.overworld().getEntity(UUID.fromString(a.string("resident")));
if(entity!=null)entity.discard();Colonies.of(server,UUID.fromString(a.string("colony"))).ifPresent(colony->colony.raze());return true;` }),
});
