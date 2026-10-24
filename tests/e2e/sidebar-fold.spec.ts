import { expect, test } from "@playwright/test";
import { defineTask, input, javaTask, media, player, screen, tick, type MinecraftClient } from "@izakyl/blockwright-minecraft";
import { ancestorsOf, clickElement, panelElements, type Element } from "../ldlib2";
import { clickTab } from "../shared/ui/colony-pages";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, reportPath, shotPath, tracePath } from "../shared/harness/out-paths";
import { newRunId } from "../shared/harness/run-id";
import { BUDGET } from "../shared/harness/budgets";
import { frames } from "../shared/harness/bw-helpers";
import { writeFileSync } from "node:fs";

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

test("folded navigation replaces the page at Auto and 4x and restores both columns when widened", async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  ensureOut("e2e");
  const runId = newRunId();
  const reports: unknown[] = [];
  let pair: E2EPair | undefined;
  try {
    pair = await launchPair({
      server: { instance: `sidebar-server-${runId}`, trace: tracePath("e2e", "sidebar-server") },
      client: { instance: `sidebar-client-${runId}`, trace: tracePath("e2e", "sidebar-client") },
    });
    const { server, client } = pair;
    await server.run(SETUP, {});
    await tick.sprint(server, 15);
    await client.run(LANGUAGE, {});
    for (let attempt = 0; attempt < 120; attempt++) {
      if (!(await client.run(DISPLAY, {})).loading) break;
      await frames(client, 5);
    }
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
      await media.screenshot(client, { path: shotPath("e2e", `sidebar-${item.name}-page`) });
      if (!item.folded) {
        reports.push({ ...item, display, ...columns(elements) });
        continue;
      }
      const control = element(elements, "folkways.settings.invert_wheel");
      const pane = element(elements, "folkways.page.content");
      for (let cycle = 0; cycle < 12; cycle++) {
        await clickElement(client, { id: menu.id });
        await frames(client, 3);
        elements = await panelElements(client);
        expect(element(elements, pane.id!).displayed).toBe(false);
        expect(element(elements, "folkways.navigation").width).toBeCloseTo(pane.width, 0);
        expect(ancestorsOf(elements, element(elements, control.id!)).some(parent => !parent.displayed)).toBe(true);
        if (cycle === 0) {
          await media.screenshot(client, { path: shotPath("e2e", `sidebar-${item.name}-navigation`) });
          await expect(clickElement(client, { id: control.id }, { timeoutMs: 1000 })).rejects.toThrow();
          reports.push({ ...item, display, nav: element(elements, "folkways.navigation"), pane: element(elements, pane.id!), cycles: 12 });
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
    writeFileSync(reportPath("e2e", "sidebar-fold"), JSON.stringify(reports, null, 2));
    await pair?.teardown(testInfo);
  }
});
