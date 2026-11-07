import { expect, test } from "@playwright/test";
import { chat, defineTask, input, javaTask, player, screen, tick, type MinecraftClient } from "@izakyl/blockwright-minecraft";
import { ancestorsOf, clickElement, panelElements, waitForElement, type Element } from "../ldlib2";
import { clickTab } from "../shared/ui/colony-pages";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, tracePath } from "../shared/harness/out-paths";
import { newRunId } from "../shared/harness/run-id";
import { waitForResourceReload } from "../shared/harness/resource-reload";
import { BUDGET } from "../shared/harness/budgets";
import { frames } from "../shared/harness/bw-helpers";

const SETUP = defineTask<Record<string, never>, boolean>({
  name: "folkways.sidebar.setup", side: "server",
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.front.engine.charter.Charters",
    "io.github.izakyl.folkways.front.engine.charter.CurrentColony",
    "io.github.izakyl.folkways.front.engine.registry.FolkwaysItems",
    "net.minecraft.world.item.ItemStack", "net.minecraft.world.InteractionHand",
  ], body: `
var player = ctx.server().getPlayerList().getPlayers().getFirst();
var colony = Charters.found(player, "侧栏回归测试的殖民地");
CurrentColony.choose(player, colony);
player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(FolkwaysItems.COLONY_BOOK.get()));
return true;
` }),
});

const ENABLED = defineTask<Record<string, never>, boolean>({
  name: "folkways.debug.enabled", side: "server",
  source: javaTask({ imports: ["io.github.izakyl.folkways.front.engine.colony.LaborDebug"], body: `
return LaborDebug.enabled(ctx.server().getPlayerList().getPlayers().getFirst());
` }),
});

const INCIDENT = defineTask<{ count: number; key: string; other: boolean }, boolean>({
  name: "folkways.debug.incident", side: "server",
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.front.api.ui.Pages",
    "io.github.izakyl.folkways.core.work.internal.LaborIncident",
    "net.neoforged.neoforge.common.NeoForge", "net.minecraft.network.chat.Component",
  ], body: `
var p=ctx.server().getPlayerList().getPlayers().getFirst();
var colony=Pages.colonyOf(p).orElseThrow().id();
if(((Boolean)ctx.arg("other")).booleanValue())colony=UUID.randomUUID();
for(int i=0;i<((Number)ctx.arg("count")).intValue();i++)NeoForge.EVENT_BUS.post(new LaborIncident(colony,
 p.serverLevel().dimension(),(String)ctx.arg("key"),Component.translatable("folkways.debug.incident.refused",
 Component.literal((String)ctx.arg("key")))));
return true;
` }),
});

// Queue and unsubscribe in one server call, so UI navigation cannot advance past the repeat deadline first.
const CLEAR_QUEUED = defineTask<Record<string, never>, boolean>({
  name: "folkways.debug.clearQueued", side: "server",
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.front.api.ui.Pages",
    "io.github.izakyl.folkways.front.engine.colony.LaborDebug",
    "io.github.izakyl.folkways.core.work.internal.LaborIncident",
    "net.neoforged.neoforge.common.NeoForge", "net.minecraft.network.chat.Component",
  ], body: `
var p=ctx.server().getPlayerList().getPlayers().getFirst();
LaborDebug.set(p,true);
for(int i=0;i<2;i++)NeoForge.EVENT_BUS.post(new LaborIncident(Pages.colonyOf(p).orElseThrow().id(),
 p.serverLevel().dimension(),"discard-probe",Component.literal("discard-probe")));
LaborDebug.set(p,false);return !LaborDebug.enabled(p);
` }),
});

// Supply known counters to check the real chat packet and client translation, without pretending that an empty
// fixture colony has a busy workforce. The periodic, unsubscribed and stopped paths use the actual server tick.
const SAMPLE = defineTask<Record<string, never>, boolean>({
  name: "folkways.debug.sample", side: "server",
  source: javaTask({ imports: [
    "io.github.izakyl.folkways.front.engine.colony.LaborDebug",
    "io.github.izakyl.folkways.core.runtime.LaborCensus",
    "io.github.izakyl.folkways.core.runtime.LaborSnapshot",
    "io.github.izakyl.folkways.core.work.api.NodeState", "net.minecraft.server.level.ServerPlayer",
  ], body: `
var p=ctx.server().getPlayerList().getPlayers().getFirst();
var census=new LaborCensus(100,Map.of(NodeState.READY,5),5,Map.of("PACK_BUSY",3),Map.of("minecraft:wheat",8L),Map.of("idle",2,"working",1));
var snapshot=new LaborSnapshot(census,0,12,2,Map.of("NO_ROOM",1),
 List.of("PACK_BUSY/folkways:haul/TransferNode@test"),List.of());
var m=LaborDebug.class.getDeclaredMethod("report",ServerPlayer.class,LaborSnapshot.class);m.setAccessible(true);m.invoke(null,p,snapshot);return true;
` }),
});

const LANGUAGE = defineTask<Record<string, never>, boolean>({
  name: "folkways.sidebar.language", side: "client",
  source: javaTask({ imports: ["net.minecraft.client.Minecraft"], body: `
var mc = Minecraft.getInstance();
mc.getLanguageManager().setSelected("zh_cn");
mc.options.languageCode = "zh_cn";
mc.reloadResourcePacks();
return true;
` }),
});

const WINDOW = defineTask<{ width: number; height: number }, boolean>({
  name: "folkways.sidebar.window", side: "client",
  source: javaTask({ imports: ["net.minecraft.client.Minecraft"], body: `
Args args = Args.of(ctx);
Minecraft.getInstance().getWindow().setWindowed((int) args.integer("width"), (int) args.integer("height"));
return true;
` }),
});

const SCALE = defineTask<{ scale: number }, boolean>({
  name: "folkways.sidebar.scale", side: "client",
  source: javaTask({ imports: ["net.minecraft.client.Minecraft"], body: `
var mc = Minecraft.getInstance();
mc.options.guiScale().set((int) Args.of(ctx).integer("scale"));
mc.resizeDisplay();
return true;
` }),
});

interface Display {
  width: number; height: number; guiWidth: number; guiHeight: number;
  configuredScale: number; effectiveScale: number; language: string; loading: boolean; invertWheel: boolean;
}
const DISPLAY = defineTask<Record<string, never>, Display>({
  name: "folkways.sidebar.display", side: "client",
  source: javaTask({ imports: ["net.minecraft.client.Minecraft", "io.github.izakyl.folkways.FolkwaysClientConfig"], body: `
var mc = Minecraft.getInstance(); var window = mc.getWindow();
return Map.of("width", window.getWidth(), "height", window.getHeight(),
    "guiWidth", window.getGuiScaledWidth(), "guiHeight", window.getGuiScaledHeight(),
    "configuredScale", mc.options.guiScale().get(), "effectiveScale", window.getGuiScale(),
    "language", mc.getLanguageManager().getSelected(), "loading", mc.getOverlay() != null,
    "invertWheel", FolkwaysClientConfig.invertWheel());
` }),
});

const CASES = [
  { name: "normal", width: 1920, height: 1080, scale: 2, folded: false },
  { name: "auto-small", width: 1280, height: 720, scale: 0, folded: true },
  { name: "4x", width: 1920, height: 1080, scale: 4, folded: true },
  { name: "auto-large", width: 1920, height: 1080, scale: 0, folded: true },
  { name: "narrow-window", width: 960, height: 720, scale: 4, folded: true },
  { name: "wide-recovery", width: 1920, height: 1080, scale: 1, folded: false },
];

const element = (elements: Element[], id: string) => {
  const found = elements.find(value => value.id === id);
  expect(found, `missing ${id}`).toBeTruthy();
  return found!;
};

function columns(elements: Element[]) {
  const nav = element(elements, "folkways.navigation");
  const pane = element(elements, "folkways.page.content");
  expect(nav.displayed && pane.displayed).toBe(true);
  expect(nav.x + nav.width).toBeLessThanOrEqual(pane.x);
  return { nav, pane };
}

async function resize(client: MinecraftClient, width: number, height: number, scale: number) {
  // Minecraft clamps a requested scale to the current window's maximum. Resize before choosing 4x.
  await client.run(WINDOW, { width, height });
  await frames(client, 8);
  await client.run(SCALE, { scale });
  await frames(client, 8);
}

// One colony for both: the debug toggle reads English chat first, then the panel is folded in Chinese.
test("the book's debug toggle sends bounded diagnostics only while enabled, and folded navigation replaces the page "
  + "at Auto and 4x and restores both columns when widened", async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  ensureOut("e2e");
  const runId = newRunId();
  let pair: E2EPair | undefined;
  try {
    pair = await launchPair({
      server: { instance: `sidebar-server-${runId}`, trace: tracePath("e2e", "sidebar-server") },
      client: { instance: `sidebar-client-${runId}`, trace: tracePath("e2e", "sidebar-client") },
    });
    const { server, client } = pair;
    const messages = () => chat.messages(client, { limit: 200 });
    const waiting = (lines: string[]) => lines.filter(line => line.includes("No active planning site")).length;
    const openSettings = async () => {
      await player.useItem(client);
      await screen.waitFor(client, "ModularUIContainerScreen", { timeoutMs: 15_000 });
      await clickTab(client, "folkways.tab.settings");
      await waitForElement(client, { id: "folkways.settings.debug_logs" }, { timeoutMs: 10_000 });
    };
    await server.run(SETUP, {});
    await tick.step(server, 3);
    await tick.sprint(server, 210);
    expect(await server.run(ENABLED, {})).toBe(false);
    expect((await messages()).some(line => line.includes("Folkways Debug"))).toBe(false);
    await server.run(INCIDENT, { count: 1, key: "disabled-path-probe", other: false });
    expect((await messages()).some(line => line.includes("disabled-path-probe"))).toBe(false);
    await openSettings();
    await clickElement(client, { id: "folkways.settings.debug_logs" });
    await expect.poll(() => server.run(ENABLED, {})).toBe(true);
    await chat.waitFor(client, "No active planning site", { includeExisting: true, timeoutMs: 15_000 });
    const before = waiting(await messages());
    await tick.sprint(server, 40);
    expect(waiting(await messages())).toBe(before);
    await screen.dismiss(client);
    await tick.sprint(server, 210);
    expect(await server.run(ENABLED, {})).toBe(true);
    expect(waiting(await messages())).toBeGreaterThan(before);
    await server.run(SAMPLE, {});
    await chat.waitFor(client, "fallbacks 2", { includeExisting: true, timeoutMs: 10_000 });
    expect((await messages()).some(line => line.includes("PACK_BUSY=3") && line.includes("NO_ROOM=1"))).toBe(true);
    await server.run(INCIDENT, { count: 19, key: "live-path-probe", other: false });
    await chat.waitFor(client, "Work could not continue: live-path-probe", { includeExisting: true, timeoutMs: 10_000 });
    expect((await messages()).filter(line => line.includes("live-path-probe"))).toHaveLength(1);
    await server.run(INCIDENT, { count: 1, key: "other-colony-probe", other: true });
    expect((await messages()).some(line => line.includes("other-colony-probe"))).toBe(false);
    await tick.sprint(server, 100);
    await chat.waitFor(client, "grouped occurrences: 18", { includeExisting: true, timeoutMs: 10_000 });
    await openSettings();
    await clickElement(client, { id: "folkways.settings.debug_logs" });
    await expect.poll(() => server.run(ENABLED, {})).toBe(false);
    const stopped = waiting(await messages());
    await tick.sprint(server, 210);
    expect(waiting(await messages())).toBe(stopped);
    await server.run(INCIDENT, { count: 1, key: "stopped-path-probe", other: false });
    expect((await messages()).filter(line => line.includes("live-path-probe"))).toHaveLength(2);
    expect((await messages()).some(line => line.includes("stopped-path-probe"))).toBe(false);
    expect(await server.run(CLEAR_QUEUED, {})).toBe(true);
    await tick.sprint(server, 110);
    await chat.waitFor(client, "discard-probe", { includeExisting: true, timeoutMs: 10_000 });
    expect((await messages()).filter(line => line.includes("discard-probe"))).toHaveLength(1);
    await screen.dismiss(client);

    await client.run(LANGUAGE, {});
    await waitForResourceReload(client);
    expect((await client.run(DISPLAY, {})).language).toBe("zh_cn");
    await player.useItem(client);
    await screen.waitFor(client, "ModularUIContainerScreen", { timeoutMs: 15000 });

    for (const item of CASES) {
      await resize(client, item.width, item.height, item.scale);
      const display = await client.run(DISPLAY, {});
      expect(display.width).toBe(item.width);
      expect(display.height).toBe(item.height);
      if (item.name === "4x") {
        expect(display.configuredScale).toBe(4);
        expect(display.effectiveScale).toBe(4);
      }
      await clickTab(client, "folkways.tab.settings");
      let elements = await panelElements(client);
      const menu = element(elements, "folkways.navigation.menu");
      expect(menu.displayed).toBe(item.folded);
      if (!item.folded) {
        columns(elements);
        continue;
      }
      const control = element(elements, "folkways.settings.invert_wheel");
      const pane = element(elements, "folkways.page.content");
      for (let cycle = 0; cycle < 2; cycle++) {
        await clickElement(client, { id: menu.id });
        await frames(client, 3);
        elements = await panelElements(client);
        expect(element(elements, pane.id!).displayed).toBe(false);
        expect(element(elements, "folkways.navigation").width).toBeCloseTo(pane.width, 0);
        expect(ancestorsOf(elements, element(elements, control.id!)).some(parent => !parent.displayed)).toBe(true);
        if (cycle === 0) {
          await expect(clickElement(client, { id: control.id }, { timeoutMs: 1000 })).rejects.toThrow();
        }
        await clickElement(client, { id: menu.id });
        await frames(client, 3);
        elements = await panelElements(client);
        expect(element(elements, pane.id!).displayed).toBe(true);
        expect(element(elements, "folkways.navigation").displayed).toBe(false);
      }
      // A real pointer press at the old body control must not toggle it through the navigation.
      const before = (await client.run(DISPLAY, {})).invertWheel;
      await clickElement(client, { id: menu.id });
      await frames(client, 3);
      await input.click(client, { x: control.x + control.width / 2, y: control.y + control.height / 2 });
      await frames(client, 3);
      expect((await client.run(DISPLAY, {})).invertWheel).toBe(before);
      await clickTab(client, "folkways.tab.residents");
      await clickTab(client, "folkways.tab.settings");
      elements = await panelElements(client);
      expect(element(elements, pane.id!).displayed).toBe(true);
      expect(element(elements, "folkways.navigation").displayed).toBe(false);
      await clickElement(client, { id: menu.id });
      await resize(client, 1920, 1080, 1);
      columns(await panelElements(client));
    }
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
