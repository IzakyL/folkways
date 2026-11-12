import { expect, test } from "@playwright/test";
import { defineTask, input, javaTask, screen, tick, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { clickElement, panelElements } from "../ldlib2";
import { createColonyViaBook, foundColonyFast, openColonyPanelViaBook, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { BUDGET } from "../shared/harness/budgets";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { clickTab } from "../shared/ui/colony-pages";
import { frames } from "../shared/harness/bw-helpers";
import { mustCommand } from "../shared/ui/world-ui";
import { foundBookColony, layFloor, pickRtsTool, tapV } from "../shared/combat/overhead";

type Vec = { x: number; y: number; z: number };
type CameraReading = {
  active: boolean;
  tactical: boolean;
  tool: string;
  gesture: string;
  x: number;
  y: number;
  z: number;
  focusPresent: boolean;
  focusX: number;
  focusY: number;
  focusZ: number;
  yaw: number;
  pitch: number;
  account: string;
  global: boolean;
};

const SETTINGS_PAGE = "folkways.tab.settings";
const GLOBAL_SETTING = "folkways.settings.global_tactical_controls";
const COMBAT_TOOL = "folkways_core:combat";
const PERSONAL_TOGGLE = /Use tactical camera controls for all overhead tools|全局战斗视角操作逻辑/i;

test("the native config screen and shortcut set the camera account toggle, and the RTS tool routes middle drags to tactical controls", async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  const run = newRunId();
  let pair: E2EPair | undefined;
  let testOtherUuid = "";
  let testOtherBefore = false;

  try {
    pair = await launchPair({
      config: "blockwright.toml#rts",
      server: { instance: `combat-camera-input-server-${run}` },
      client: { instance: `combat-camera-input-client-${run}` },
      snapshot: { entityTypes: ["folkways_vanilla:resident"] },
    });
    const { server, client } = pair;
    const player = await waitForGroundedPlayer(server);
    const origin: Vec = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    await prepareGround(server, origin);
    const { colony: colonyId } = await foundBookColony(server, client, player.name, origin, 2);
    const residents = await residentIds(server, colonyId);
    expect(residents).toHaveLength(2);

    // The registered native config screen edits the current account's toggle only.
    const prepared = await client.run(PREPARE_NATIVE_CONFIG_TEST);
    testOtherUuid = prepared.otherUuid;
    testOtherBefore = prepared.otherBefore;
    expect(prepared.account).not.toBe("");
    expect(prepared.otherUuid).not.toBe(prepared.account);
    expect(prepared.otherEnabled).toBe(true);
    expect(prepared.initialPreference, "a fresh client fixture starts with the personal control disabled").toBe(false);

    const opened = await client.run(OPEN_REGISTERED_CONFIG_FACTORY);
    expect(opened.factoryRegistered).toBe(true);
    expect(opened.screen).toContain("ConfigurationScreen");
    expect(opened.keyUnbound, "the newly registered shortcut must start unbound").toBe(true);
    await openPersonalSettings(client);
    expect((await nativeWidgets(client)).some(widget => PERSONAL_TOGGLE.test(widget.label ?? "")),
      "the registered Client config screen must expose the real current-account toggle").toBe(true);

    await clickNativeLabel(client, PERSONAL_TOGGLE);
    await expect.poll(async () => (await camera(client)).global, { timeout: 10_000 }).toBe(true);
    await clickNativeLabel(client, /\bReset\b|重置/i);
    const resetState = await client.run(READ_CONFIG_TEST_ACCOUNTS, { otherUuid: testOtherUuid });
    expect(resetState.currentPreference).toBe(false);
    expect(resetState.otherPreference, "Reset must not clear another profile's setting").toBe(true);

    await clickNativeLabel(client, /\bUndo\b|撤销/i);
    const undoState = await client.run(READ_CONFIG_TEST_ACCOUNTS, { otherUuid: testOtherUuid });
    expect(undoState.currentPreference).toBe(true);
    expect(undoState.otherPreference).toBe(true);
    await leaveConfig(client);
    const afterDone = await client.run(READ_CONFIG_TEST_ACCOUNTS, { otherUuid: testOtherUuid });
    expect(afterDone.currentPreference).toBe(true);
    expect(afterDone.otherPreference).toBe(true);

    const reopen = await client.run(OPEN_REGISTERED_CONFIG_FACTORY);
    expect(reopen.currentPreference).toBe(true);
    await openPersonalSettings(client);
    expect(nativeControl(await nativeWidgets(client), PERSONAL_TOGGLE)?.value,
      "reopening the native config page must show the saved current-account value").toBe("true");
    await leaveConfig(client);

    await client.run(BIND_CAMERA_SHORTCUT, { keyCode: KEY_TAB });
    await tapV(client);
    const overhead = await screen.waitFor(client, "OverheadScreen", { timeoutMs: 8_000 });
    const beforeShortcut = await camera(client);
    await input.key(client, { keyCode: KEY_TAB, action: "press" });
    await frames(client, 3);
    const afterShortcutPress = await camera(client);
    await input.key(client, { keyCode: KEY_TAB, action: "repeat" });
    await frames(client, 2);
    const afterShortcutRepeat = await camera(client);
    await input.key(client, { keyCode: KEY_TAB, action: "release" });
    await frames(client, 2);
    const afterShortcut = await camera(client);
    expect(beforeShortcut.global).toBe(true);
    expect(afterShortcutPress.global).toBe(false);
    expect(afterShortcutRepeat.global, "a held-key repeat must not toggle a second time").toBe(false);
    expect(afterShortcut.global).toBe(false);
    expect(afterShortcut.tool).toBe(beforeShortcut.tool);
    expect(afterShortcut.gesture, "a bound Tab shortcut must not also cycle the old book gesture").toBe(beforeShortcut.gesture);
    expect((await screen.current(client))?.className).toContain("OverheadScreen");

    // A profile change waits for an in-flight middle gesture to release: its pivot must not disappear.
    await client.run(SET_PERSONAL_PREFERENCE, { on: true });
    await frames(client, 2);
    const center = { x: Math.floor(overhead.width / 2), y: Math.floor(overhead.height / 2) };
    await input.move(client, center);
    await input.key(client, { keyCode: 342, action: "press" });
    try {
      await input.button(client, { button: "middle", action: "press", modifiers: ["alt"] });
      const started = await camera(client);
      await input.key(client, { keyCode: KEY_TAB, action: "press" });
      await input.key(client, { keyCode: KEY_TAB, action: "release" });
      await frames(client, 3);
      const toggledDuringDrag = await camera(client);
      expect(toggledDuringDrag.global).toBe(false);
      expect(toggledDuringDrag.tactical, "the in-flight orbit keeps its camera policy until release").toBe(true);
      await input.move(client, { x: center.x + 40, y: center.y + 15 });
      await frames(client, 3);
      const continued = await camera(client);
      expect(distance3(continued, started), "the orbit must continue after toggling the global setting off").toBeGreaterThan(0.2);
      expect(focusDistance(continued, started)).toBeLessThan(0.35);
    } finally {
      await input.button(client, { button: "middle", action: "release", modifiers: ["alt"] });
      await input.key(client, { keyCode: 342, action: "release" });
    }
    await frames(client, 3);
    expect((await camera(client)).tactical, "the ordinary tool uses its new policy after release").toBe(false);

    await client.run(BIND_CAMERA_SHORTCUT, { keyCode: KEY_K });
    await screen.dismiss(client);
    await input.key(client, { keybind: "key.chat" });
    await screen.waitFor(client, "ChatScreen", { timeoutMs: 8_000 });
    const chatBefore = await camera(client);
    await input.key(client, { keyCode: KEY_K, action: "press" });
    await input.key(client, { keyCode: KEY_K, action: "release" });
    await input.key(client, { text: "k" });
    await frames(client, 2);
    const chatWidgets = await nativeWidgets(client);
    expect((await camera(client)).global, "a shortcut key typed into chat must not toggle the account setting").toBe(chatBefore.global);
    expect(chatWidgets.some(widget => /EditBox/.test(widget.className + " " + ("superClassName" in widget ? widget.superClassName : ""))
      && (widget.value ?? "").toLowerCase().includes("k")),
      "the same real key press should remain ordinary chat text while chat has focus").toBe(true);
    await screen.dismiss(client);

    // With the personal setting off again, the ordinary V view keeps its legacy camera.
    await tapV(client);
    await frames(client, 4);
    const initial = await camera(client);
    expect(initial.active).toBe(true);
    expect(initial.account).not.toBe("");
    expect(initial.global).toBe(false);
    expect(initial.tactical, "the ordinary V view stays in its legacy camera mode by default").toBe(false);
    expect(initial.tool).toBe("");

    const viewport = await screen.waitFor(client, "OverheadScreen", { timeoutMs: 5_000 });
    const middle = { x: Math.floor(viewport.width / 2), y: Math.floor(viewport.height / 2) };
    const legacyDrag = await input.drag(client, {
      from: { x: middle.x - 35, y: middle.y - 18 },
      to: { x: middle.x + 35, y: middle.y + 18 },
      steps: 8,
      button: "middle",
    });
    await frames(client, 3);
    const legacyAfter = await camera(client);
    expect(legacyDrag.pressArmed).toBe(true);
    expect(legacyAfter.tactical).toBe(false);
    expect(Math.hypot(legacyAfter.x - initial.x, legacyAfter.y - initial.y, legacyAfter.z - initial.z),
      "MMB on the ordinary V view rotates without panning the camera").toBeLessThan(0.08);
    expect(Math.abs(angleDelta(legacyAfter.yaw, initial.yaw)) + Math.abs(legacyAfter.pitch - initial.pitch),
      "the real MMB drag must change the legacy view orientation").toBeGreaterThan(8);

    await pickRtsTool(client);
    await frames(client, 3);
    const selected = await camera(client);
    expect(selected.tool).toBe(COMBAT_TOOL);
    expect(selected.global).toBe(false);
    expect(selected.tactical, "the combat tool requests tactical camera controls independently of the personal setting").toBe(true);

    // The removed combat page and forged colony requests must leave the RTS overhead screen intact.
    for (const request of [{ colony: colonyId, page: COMBAT_TOOL },
      { colony: "00000000-0000-0000-0000-000000000001", page: COMBAT_TOOL }]) {
      await client.run(ATTEMPT_OPEN_PAGE, request);
      await tick.sprint(server, 2);
      await frames(client, 4);
      expect((await screen.current(client))?.className, `${JSON.stringify(request)} must be refused`).toContain("OverheadScreen");
      expect((await camera(client)).tool).toBe(COMBAT_TOOL);
    }

    const beforePan = await camera(client);
    const pan = await dragMiddle(client, middle, false);
    await frames(client, 3);
    const afterPan = await camera(client);
    expect(pan.pressArmed).toBe(true);
    expect(distance3(afterPan, beforePan), "combat MMB must translate the overhead camera").toBeGreaterThan(0.2);
    expect(Math.abs(angleDelta(afterPan.yaw, beforePan.yaw)) + Math.abs(afterPan.pitch - beforePan.pitch),
      "plain combat MMB pan must keep the camera orientation").toBeLessThan(0.25);
    expect(afterPan.focusPresent, "tactical drag must acquire a real world focus").toBe(true);

    const orbitStart = await camera(client);
    const orbit = await dragMiddle(client, middle, true);
    await frames(client, 3);
    const orbitEnd = await camera(client);
    expect(orbit.pressArmed).toBe(true);
    expect(Math.abs(angleDelta(orbitEnd.yaw, orbitStart.yaw)) + Math.abs(orbitEnd.pitch - orbitStart.pitch),
      "Alt+MMB must orbit the camera around its acquired focus").toBeGreaterThan(8);
    expect(distance3(orbitEnd, orbitStart), "orbiting changes the camera position").toBeGreaterThan(0.2);
    expect(focusDistance(orbitEnd, orbitStart), "the actual world focus stays fixed while orbiting").toBeLessThan(0.35);
    expect(Math.abs(focusRadius(orbitEnd) - focusRadius(orbitStart)),
      "orbiting preserves the camera's radius around its real focus").toBeLessThan(0.35);

    // No overhead camera gesture may become a colony click: the same two real resident IDs remain registered.
    expect(await residentIds(server, colonyId)).toEqual(residents);

    await mustCommand(server, `item replace entity ${player.name} weapon.mainhand with minecraft:air`);
    await tick.sprint(server, 2);
    await expect.poll(async () => (await camera(client)).active, {
      timeout: 10_000,
      message: "losing the held colony book must end the overhead camera",
    }).toBe(false);
    await frames(client, 3);
    expect(await screen.current(client)).toBeNull();
    expect(await residentIds(server, colonyId)).toEqual(residents);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    if (pair) {
      try { await pair.client.run(RESTORE_CAMERA_SHORTCUT); } catch { /* Preserve primary failure evidence. */ }
      try { await pair.client.run(SET_PERSONAL_PREFERENCE, { on: false }); } catch { /* Preserve primary failure evidence. */ }
      if (testOtherUuid) {
        try { await pair.client.run(RESTORE_CONFIG_TEST_UUID, { otherUuid: testOtherUuid, on: testOtherBefore }); } catch { /* Preserve primary failure evidence. */ }
      }
    }
    await pair?.teardown(testInfo);
  }
});

test("the personal tactical camera preference follows the account across colonies and client restart", async ({}, testInfo) => {
  test.setTimeout(540_000);
  const run = newRunId();
  const serverOptions = { instance: `combat-camera-persistence-server-${run}`, keepInstanceDir: true };
  const clientOptions = { instance: `combat-camera-persistence-client-${run}`, keepInstanceDir: true };
  let pair: E2EPair | undefined;
  let initialPreference = false;

  try {
    pair = await launchPair({
      config: "blockwright.toml#rts",
      server: serverOptions, client: clientOptions, snapshot: { entityTypes: ["folkways_vanilla:resident"] },
    });
    let { server, client } = pair;
    const player = await waitForGroundedPlayer(server);
    const origin: Vec = { x: Math.round(player.pos.x) + 4, y: Math.round(player.pos.y), z: Math.round(player.pos.z) + 4 };
    await prepareGround(server, origin);
    const first = await foundColonyFast(server, client, player.name, { origin, residentCount: 1 });
    const firstId = String(first.evidence.colonyId);
    const initial = await camera(client);
    initialPreference = initial.global;
    expect(initial.account).not.toBe("");
    expect(initialPreference, "fresh client instances should have global tactical controls off").toBe(false);

    // Use the actual colony-book Settings page toggle and wait for its client-local state to change.
    await openColonyPanelViaBook(server, client, player.name, first.standBlock);
    await clickTab(client, SETTINGS_PAGE);
    const settingBefore = (await panelElements(client)).find(element => element.id === GLOBAL_SETTING);
    expect(settingBefore?.displayed && settingBefore.active,
      "the Settings page must expose an actionable personal switch").toBe(true);
    await clickElement(client, { id: GLOBAL_SETTING });
    await expect.poll(async () => (await camera(client)).global, {
      timeout: 10_000,
      message: "clicking the real book setting must update the personal camera flag immediately",
    }).toBe(true);

    await screen.dismiss(client);
    await createColonyViaBook(server, client, player.name, first.standBlock);
    const secondId = await currentColonyId(server, player.name);
    expect(secondId).not.toBe(firstId);
    await openColonyPanelViaBook(server, client, player.name, first.standBlock);
    await screen.dismiss(client);
    await tapV(client);
    await frames(client, 4);
    const secondColonyCamera = await camera(client);
    expect(secondColonyCamera.global).toBe(true);
    expect(secondColonyCamera.tactical, "the personal setting applies to the ordinary V tool while acting for another colony").toBe(true);
    const viewport = await screen.waitFor(client, "OverheadScreen", { timeoutMs: 5_000 });
    const center = { x: Math.floor(viewport.width / 2), y: Math.floor(viewport.height / 2) };
    await dragMiddle(client, center, false);
    await frames(client, 3);
    const globalAfterDrag = await camera(client);
    expect(distance3(globalAfterDrag, secondColonyCamera), "the global account preference makes ordinary-tool MMB pan").toBeGreaterThan(0.2);
    expect(Math.abs(angleDelta(globalAfterDrag.yaw, secondColonyCamera.yaw)) + Math.abs(globalAfterDrag.pitch - secondColonyCamera.pitch)).toBeLessThan(0.25);
    await tapV(client);
    await frames(client, 4);

    // Close and relaunch the same client and server instance directories; the account preference must come from disk.
    await pair.game.close();
    pair = undefined;
    pair = await launchPair({
      config: "blockwright.toml#rts",
      server: { ...serverOptions, reuseInstanceDir: true },
      client: { ...clientOptions, reuseInstanceDir: true },
      snapshot: { entityTypes: ["folkways_vanilla:resident"] },
    });
    server = pair.server;
    client = pair.client;
    const restarted = await camera(client);
    expect(restarted.account, "the same client instance must load the same Minecraft profile").toBe(initial.account);
    expect(restarted.global, "the saved per-profile preference must survive a real client restart").toBe(true);
    await tapV(client);
    await frames(client, 4);
    const restartedCamera = await camera(client);
    expect(restartedCamera.active).toBe(true);
    expect(restartedCamera.tactical).toBe(true);
    expect(restartedCamera.tool).toBe("");

    // Restore the fresh-instance default before leaving the preserved fixture behind.
    await client.run(SET_PERSONAL_PREFERENCE, { on: initialPreference });
    expect((await camera(client)).global).toBe(initialPreference);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    if (pair) {
      try { await pair.client.run(SET_PERSONAL_PREFERENCE, { on: initialPreference }); } catch { /* Keep primary test evidence. */ }
    }
    await pair?.teardown(testInfo);
  }
});

async function prepareGround(server: MinecraftServer, origin: Vec) {
  await mustCommand(server, "difficulty peaceful");
  await mustCommand(server, "gamerule doMobSpawning false");
  await layFloor(server, { x: origin.x - 12, z: origin.z - 8 }, { x: origin.x + 14, z: origin.z + 18 }, origin.y,
    "minecraft:smooth_stone");
}

async function camera(client: MinecraftClient): Promise<CameraReading> {
  return client.run(READ_CAMERA);
}

async function dragMiddle(client: MinecraftClient, center: { x: number; y: number }, alt: boolean) {
  return input.drag(client, {
    from: { x: center.x - 38, y: center.y - 20 },
    to: { x: center.x + 38, y: center.y + 20 },
    steps: 8,
    button: "middle",
    ...(alt ? { modifiers: ["alt"] as const } : {}),
  });
}

async function residentIds(server: MinecraftServer, colony: string): Promise<string[]> {
  return server.run(RESIDENT_IDS, { colony });
}

async function currentColonyId(server: MinecraftServer, player: string): Promise<string> {
  return server.run(CURRENT_COLONY, { player });
}

// From the config-type screen the factory opens: its Client section, then the Personal settings page.
async function openPersonalSettings(client: MinecraftClient) {
  await clickNativeLabel(client, /\bClient\b/i);
  await frames(client, 3);
  await clickNativeLabel(client, /Personal settings|个人设置/i);
  await frames(client, 3);
}

// A section's Done returns to the config-type screen and its Done to the parent: leave every config screen.
async function leaveConfig(client: MinecraftClient) {
  for (let depth = 0; depth < 3; depth++) {
    const current = await screen.current(client);
    if (!/(?:ConfigurationScreen|PersonalCameraConfigScreen)/.test(current?.className ?? "")) return;
    if (!(await nativeWidgets(client)).some(widget => /\bDone\b|完成/i.test(widget.label ?? ""))) return;
    await clickNativeLabel(client, /\bDone\b|完成/i);
    await frames(client, 3);
  }
}

function distance3(a: Pick<CameraReading, "x" | "y" | "z">, b: Pick<CameraReading, "x" | "y" | "z">) {
  return Math.hypot(a.x - b.x, a.y - b.y, a.z - b.z);
}

function focusDistance(a: CameraReading, b: CameraReading) {
  if (!a.focusPresent || !b.focusPresent) return Number.POSITIVE_INFINITY;
  return Math.hypot(a.focusX - b.focusX, a.focusY - b.focusY, a.focusZ - b.focusZ);
}

function focusRadius(reading: CameraReading) {
  if (!reading.focusPresent) return Number.POSITIVE_INFINITY;
  return Math.hypot(reading.x - reading.focusX, reading.y - reading.focusY, reading.z - reading.focusZ);
}

const KEY_K = 75;
const KEY_TAB = 258;

async function nativeWidgets(client: MinecraftClient) {
  // Native configuration pages nest buttons inside list entries; direct children still expose
  // footer tooltips and the chat field. Both observers are read-only; clicks use MouseHandler.
  return [...await screen.widgets(client), ...await client.run(READ_NATIVE_DESCENDANTS)];
}

async function clickNativeLabel(client: MinecraftClient, wanted: RegExp) {
  const widgets = await nativeWidgets(client);
  const widget = nativeControl(widgets, wanted);
  expect(widget, `Expected an enabled native config control matching ${wanted}`).toBeTruthy();
  await input.click(client, { x: widget!.x + widget!.width / 2, y: widget!.y + widget!.height / 2 });
  await frames(client, 2);
}

function nativeControl(widgets: Awaited<ReturnType<typeof nativeWidgets>>, wanted: RegExp) {
  const labels = widgets.filter(candidate => candidate.visible && wanted.test(candidate.label ?? ""));
  const direct = labels.find(candidate => candidate.enabled);
  if (direct) return direct;
  // NeoForge renders an inactive StringWidget label beside the separately labelled ON/OFF cycle.
  return labels.flatMap(label => widgets.filter(candidate => candidate.enabled && candidate.visible
    && /CycleButton/.test(candidate.className) && candidate.x >= label.x
    && Math.abs(candidate.y + candidate.height / 2 - label.y - label.height / 2) < candidate.height / 2))[0];
}

const READ_NATIVE_DESCENDANTS = defineTask<void, Array<{ label: string; value: string; enabled: boolean; visible: boolean;
  className: string; x: number; y: number; width: number; height: number }>>({
  name: "folkways.combat-camera.read-native-gui-children",
  side: "client",
  source: javaTask({
    imports: ["java.util.ArrayList", "java.util.Collections", "java.util.IdentityHashMap", "java.util.Map",
      "net.minecraft.client.Minecraft", "net.minecraft.client.gui.components.AbstractWidget", "net.minecraft.client.gui.components.CycleButton",
      "net.minecraft.client.gui.components.events.ContainerEventHandler", "net.minecraft.client.gui.components.events.GuiEventListener"],
    body: `var mc=Minecraft.getInstance();var result=new ArrayList<Map<String,Object>>();
if(mc.screen==null)return result;
var pending=new ArrayList<GuiEventListener>();pending.add(mc.screen);
var seen=Collections.newSetFromMap(new IdentityHashMap<GuiEventListener,Boolean>());
for(int i=0;i<pending.size();i++){var child=pending.get(i);if(!seen.add(child))continue;
if(child instanceof AbstractWidget w)result.add(Map.of("label",w.getMessage().getString(),"value",w instanceof CycleButton<?> cycle?String.valueOf(cycle.getValue()):"",
"enabled",w.active,"visible",w.visible,"className",w.getClass().getName(),"x",w.getX(),"y",w.getY(),
"width",w.getWidth(),"height",w.getHeight()));
if(child instanceof ContainerEventHandler container)pending.addAll(container.children());}
return result;`,
  }),
});

function angleDelta(a: number, b: number) {
  return ((a - b + 540) % 360) - 180;
}

const READ_CAMERA = defineTask<void, CameraReading>({
  name: "folkways.combat-camera.read-public-state",
  side: "client",
  source: javaTask({
    imports: [
      "java.util.LinkedHashMap",
      "net.minecraft.client.Minecraft",
      "io.github.izakyl.folkways.core.front.engine.item.HeldBook",
      "io.github.izakyl.folkways.core.front.client.Overhead",
      "io.github.izakyl.folkways.core.front.client.PersonalCameraControls",
    ],
    body: `
var state=Overhead.camera().orElse(null);var result=new LinkedHashMap<String,Object>();
result.put("active",state!=null);result.put("account",String.valueOf(PersonalCameraControls.currentUserUUID()));
result.put("global",PersonalCameraControls.globalTacticalControls());
var mc=Minecraft.getInstance();result.put("gesture",mc.player==null?"":HeldBook.bound(mc.player).map(book->book.gesture().getSerializedName()).orElse(""));
if(state==null){result.put("tactical",false);result.put("tool","");result.put("x",0.0);result.put("y",0.0);result.put("z",0.0);
result.put("focusPresent",false);result.put("focusX",0.0);result.put("focusY",0.0);result.put("focusZ",0.0);result.put("yaw",0.0);result.put("pitch",0.0);}
else{var p=state.position();result.put("tactical",state.tactical());result.put("tool",state.tool().map(Object::toString).orElse(""));
result.put("x",p.x);result.put("y",p.y);result.put("z",p.z);result.put("yaw",state.yaw());result.put("pitch",state.pitch());
var focus=state.focus().orElse(null);result.put("focusPresent",focus!=null);
result.put("focusX",focus==null?0.0:focus.x);result.put("focusY",focus==null?0.0:focus.y);result.put("focusZ",focus==null?0.0:focus.z);}
return result;`,
  }),
});

const ATTEMPT_OPEN_PAGE = defineTask<{ colony: string; page: string }, { sent: true }>({
  name: "folkways.combat-camera.send-page-request",
  side: "client",
  source: javaTask({
    imports: [
      "java.util.UUID",
      "java.util.Map",
      "net.minecraft.resources.ResourceLocation",
      "net.neoforged.neoforge.network.PacketDistributor",
      "io.github.izakyl.folkways.core.front.engine.net.OpenBookPagePacket",
    ],
    body: `var a=Args.of(ctx);PacketDistributor.sendToServer(new OpenBookPagePacket(UUID.fromString(a.string("colony")),ResourceLocation.parse(a.string("page"))));return Map.of("sent",true);`,
  }),
});

const SET_PERSONAL_PREFERENCE = defineTask<{ on: boolean }, { on: boolean }>({
  name: "folkways.combat-camera.restore-personal-preference",
  side: "client",
  source: javaTask({
    imports: ["io.github.izakyl.folkways.core.front.client.PersonalCameraControls"],
    body: `var on=Args.of(ctx).flag("on",false);PersonalCameraControls.globalTacticalControls(on);return Map.of("on",PersonalCameraControls.globalTacticalControls());`,
  }),
});

const PREPARE_NATIVE_CONFIG_TEST = defineTask<void, { account: string; otherUuid: string; initialPreference: boolean; otherBefore: boolean; otherEnabled: boolean }>({
  name: "folkways.combat-camera.native-config-test-account",
  side: "client",
  source: javaTask({
    imports: ["java.util.UUID", "java.util.Map", "io.github.izakyl.folkways.core.FolkwaysClientConfig", "io.github.izakyl.folkways.core.front.client.PersonalCameraControls"],
    body: `var current=PersonalCameraControls.currentUserUUID();if(current==null)throw new IllegalStateException("Minecraft account UUID is unavailable");
UUID other=UUID.randomUUID();while(other.equals(current))other=UUID.randomUUID();boolean initial=FolkwaysClientConfig.globalTacticalControls(current);
boolean otherBefore=FolkwaysClientConfig.globalTacticalControls(other);FolkwaysClientConfig.globalTacticalControls(other,true);
return Map.of("account",current.toString(),"otherUuid",other.toString(),"initialPreference",initial,"otherBefore",otherBefore,"otherEnabled",FolkwaysClientConfig.globalTacticalControls(other));`,
  }),
});

const OPEN_REGISTERED_CONFIG_FACTORY = defineTask<void, { factoryRegistered: boolean; screen: string; account: string; currentPreference: boolean; keyUnbound: boolean }>({
  name: "folkways.combat-camera.open-registered-native-config-factory",
  side: "client",
  source: javaTask({
    imports: ["java.util.Map", "net.minecraft.client.Minecraft", "net.minecraft.client.KeyMapping", "net.neoforged.fml.ModList",
      "net.neoforged.neoforge.client.gui.IConfigScreenFactory", "io.github.izakyl.folkways.core.front.client.PersonalCameraControls",
      "io.github.izakyl.folkways.core.front.client.PersonalCameraKeyEvents"],
    body: `var mc=Minecraft.getInstance();var mod=ModList.get().getModContainerById("folkways_core").orElseThrow();
var factory=IConfigScreenFactory.getForMod(mod.getModInfo()).orElseThrow(()->new IllegalStateException("registered Folkways config screen factory is missing"));
mc.setScreen(factory.createScreen(mod,mc.screen));return Map.of("factoryRegistered",true,"screen",mc.screen.getClass().getName(),
"account",String.valueOf(PersonalCameraControls.currentUserUUID()),"currentPreference",PersonalCameraControls.globalTacticalControls(),
"keyUnbound",PersonalCameraKeyEvents.KEY.isUnbound());`,
  }),
});

const READ_CONFIG_TEST_ACCOUNTS = defineTask<{ otherUuid: string }, { currentPreference: boolean; otherPreference: boolean }>({
  name: "folkways.combat-camera.read-config-test-accounts",
  side: "client",
  source: javaTask({
    imports: ["java.util.Map", "java.util.UUID", "io.github.izakyl.folkways.core.FolkwaysClientConfig", "io.github.izakyl.folkways.core.front.client.PersonalCameraControls"],
    body: `var a=Args.of(ctx);return Map.of("currentPreference",PersonalCameraControls.globalTacticalControls(),
"otherPreference",FolkwaysClientConfig.globalTacticalControls(UUID.fromString(a.string("otherUuid"))));`,
  }),
});

const RESTORE_CONFIG_TEST_UUID = defineTask<{ otherUuid: string; on: boolean }, { restored: boolean }>({
  name: "folkways.combat-camera.restore-config-test-uuid",
  side: "client",
  source: javaTask({
    imports: ["java.util.Map", "java.util.UUID", "io.github.izakyl.folkways.core.FolkwaysClientConfig"],
    body: `var a=Args.of(ctx);FolkwaysClientConfig.globalTacticalControls(UUID.fromString(a.string("otherUuid")),a.flag("on",false));return Map.of("restored",true);`,
  }),
});

const BIND_CAMERA_SHORTCUT = defineTask<{ keyCode: number }, { key: string }>({
  name: "folkways.combat-camera.bind-test-shortcut",
  side: "client",
  source: javaTask({
    imports: ["java.util.Map", "net.minecraft.client.KeyMapping", "com.mojang.blaze3d.platform.InputConstants",
      "io.github.izakyl.folkways.core.front.client.PersonalCameraKeyEvents"],
    body: `var key=Args.of(ctx).integer("keyCode");PersonalCameraKeyEvents.KEY.setKey(InputConstants.Type.KEYSYM.getOrCreate((int)key));
KeyMapping.resetMapping();return Map.of("key",PersonalCameraKeyEvents.KEY.getKey().toString());`,
  }),
});

const RESTORE_CAMERA_SHORTCUT = defineTask<void, { unbound: boolean }>({
  name: "folkways.combat-camera.restore-shortcut-binding",
  side: "client",
  source: javaTask({
    imports: ["java.util.Map", "net.minecraft.client.Minecraft", "net.minecraft.client.KeyMapping", "com.mojang.blaze3d.platform.InputConstants",
      "io.github.izakyl.folkways.core.front.client.PersonalCameraKeyEvents"],
    body: `PersonalCameraKeyEvents.KEY.setKey(InputConstants.UNKNOWN);KeyMapping.resetMapping();Minecraft.getInstance().options.save();
return Map.of("unbound",PersonalCameraKeyEvents.KEY.isUnbound());`,
  }),
});

const RESIDENT_IDS = defineTask<{ colony: string }, string[]>({
  name: "folkways.combat-camera.read-residents",
  side: "server",
  source: javaTask({
    imports: ["java.util.UUID", "net.minecraft.server.MinecraftServer", "io.github.izakyl.folkways.core.colony.api.Colonies", "io.github.izakyl.folkways.vanilla.person.PersonBody"],
    body: `
var server=(MinecraftServer)ctx.server();var colony=Colonies.of(server,UUID.fromString(Args.of(ctx).string("colony"))).orElseThrow();
return colony.residents().stream().filter(r->r.kind().equals(PersonBody.ID)).map(r->r.id().toString()).sorted().toList();`,
  }),
});

const CURRENT_COLONY = defineTask<{ player: string }, string>({
  name: "folkways.combat-camera.current-colony",
  side: "server",
  source: javaTask({
    imports: ["net.minecraft.server.MinecraftServer", "io.github.izakyl.folkways.core.front.engine.charter.CurrentColony"],
    body: `var player=((MinecraftServer)ctx.server()).getPlayerList().getPlayerByName(Args.of(ctx).string("player"));if(player==null)throw new IllegalStateException("test player is offline");return CurrentColony.of(player).map(c->c.id().toString()).orElse("");`,
  }),
});
