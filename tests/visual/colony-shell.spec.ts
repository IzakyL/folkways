import { launchPair, type E2EPair } from "../shared/harness/pair";
import { expect, test } from "@playwright/test";
import { input, player, reflect, screen, tick, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { ancestorsOf, clickElement, panelElements, scrollElementIntoView } from "../ldlib2";
import { foundColonyFast, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { setBookGesture } from "../shared/ui/zone-tools";
import { LayoutProbe } from "../shared/capture/layout-lint";
import { clickTab } from "../shared/ui/colony-pages";
import { CONFIG_PATH, VisualRun } from "../shared/capture/visual-kit";
import { tracePath } from "../shared/harness/out-paths";
import { seededOffThreadServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { frames, safeAction, safeScreen, screenName, screenOpen, tryCommand, softly } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { runColonyTask } from "../shared/colony/colony-tasks";
import { defineTask, javaTask } from "@izakyl/blockwright-minecraft";

const BOOK_ITEM = "folkways_core:colony_book";
const TARGET_RESIDENTS = 2;

// A farm marked out beside the colony, so the facilities page lists something whose card can open.
const MARK_FARM = defineTask<{ player: string; x: number; y: number; z: number }, { ok: boolean; listed: number }>({
  name: "folkways.visual.colony_shell.farm",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.server.MinecraftServer", "net.minecraft.core.BlockPos",
      "net.minecraft.resources.ResourceLocation", "io.github.izakyl.folkways.core.front.api.ui.Pages",
      "io.github.izakyl.folkways.core.front.engine.colony.ZoneDrafts",
      "io.github.izakyl.folkways.core.front.engine.colony.ColonySettings"],
    body: `
var a = Args.of(ctx);
var player = ((MinecraftServer)ctx.server()).getPlayerList().getPlayerByName(a.string("player"));
var colony = Pages.colonyOf(player).orElseThrow();
var at = new BlockPos((int) a.integer("x"), (int) a.integer("y"), (int) a.integer("z"));
var zone = ZoneDrafts.zone(player.serverLevel(), colony, ResourceLocation.parse("folkways_vanilla:farm"),
    at, at.offset(3, 0, 3), ColonySettings.empty());
return Map.of("ok", zone.isPresent(), "listed",
    io.github.izakyl.folkways.core.facility.api.Operations.all(colony).size());
`,
  }),
});

test("the colony panel on LDLib2, on camera", async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  const run = new VisualRun("colony-shell");
  const runId = newRunId();
  let pair: E2EPair | undefined;
  let server: MinecraftServer;
  let client: MinecraftClient;
  try {
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: tracePath("visual", "colony-shell-server"), instance: seededOffThreadServer(`visual-colony-shell-server-${runId}`) },
      client: { trace: tracePath("visual", "colony-shell-client"), instance: `visual-colony-shell-client-${runId}` },
    });
    ({ server, client } = pair);

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    const origin: Vec = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    await tryCommand(server, `gamemode survival ${playerName}`);

    const founding = await foundColonyFast(server, client, playerName, { origin, residentCount: TARGET_RESIDENTS });
    run.note("colony", { origin: founding.origin, residents: founding.residents });

    await screen.dismiss(client);
    await tick.sprint(server, 5);

    await safeAction(() => player.useItem(client));
    await screen.waitFor(client, "ModularUIContainerScreen", { timeoutMs: 15_000 });

    await tick.sprint(server, 20);
    await safeAction(() => frames(client, 4));
    const shown = await safeScreen(client);
    const vanilla = await softly(() => screen.widgets(client));
    const elements = await softly(() => panelElements(client));
    run.note("screen", {
      name: screenName(shown),
      vanillaWidgets: Array.isArray(vanilla) ? vanilla.length : null,
      elements: Array.isArray(elements) ? elements.length : null,
    });

    await run.shot(client, "01-panel-default-tab", {
      subject: "Colony panel on LDLib2: the tab strip and the page it opens on",
      worldState: { origin: founding.origin, residents: founding.residents },
      hard: true,
    });
    for (const scale of [2, 3, 1]) {
      const integer = await reflect.invoke(client, {
        target: { kind: "static", className: "java.lang.Integer" },
        method: "valueOf", args: [scale], argTypes: ["int"], returnHandle: true,
      });
      expect(integer.handle).toBeTruthy();
      await reflect.invoke(client, {
        target: { kind: "static", className: "net.minecraft.client.Minecraft" },
        path: "getInstance().options.guiScale()", method: "set",
        args: [{ $handle: integer.handle }], argTypes: ["java.lang.Object"],
      });
      await reflect.invoke(client, {
        target: { kind: "static", className: "net.minecraft.client.Minecraft" },
        path: "getInstance()", method: "resizeDisplay",
      });
      await frames(client, 8);
      const elements = await panelElements(client);
      const frame = elements.find(element => element.id === "folkways.window.colony");
      const close = elements.find(element => element.id === "folkways.window.close.colony");
      expect(frame).toBeTruthy();
      expect(close?.width).toBeGreaterThanOrEqual(18);
      expect(frame!.x).toBeGreaterThanOrEqual(0);
      expect(frame!.y).toBeGreaterThanOrEqual(0);
      const gui = await new LayoutProbe(client).screen();
      expect(gui.width).toBe(Math.ceil(1280 / scale));
      expect(frame!.x + frame!.width).toBeLessThanOrEqual(gui.width);
      expect(frame!.y + frame!.height).toBeLessThanOrEqual(gui.height);
      expect(frame!.width).toBe(Math.min(500, gui.width));
      expect(frame!.height).toBe(Math.min(310, gui.height - 24));
      await input.move(client, { x: 0, y: 0 });
      await frames(client, 3);
      await run.shot(client, `02-panel-scale-${scale}`, {
        subject: `Colony workspace at GUI scale ${scale}`,
        worldState: { scale, frame, close }, hard: true,
      });
      expect(elements.some(element => element.id === "folkways.tab.zones")).toBe(false);
      await clickTab(client, "folkways.tab.settings");
      await frames(client, 4);
      await clickElement(client, { id: "folkways.settings.reset_layout" }, { scrollIntoView: true });
      await frames(client, 4);
      await clickTab(client, "folkways.tab.metrics");
      await frames(client, 4);
      await input.move(client, { x: 0, y: 0 });
      await frames(client, 3);
      await run.shot(client, `04-overview-scale-${scale}`, {
        subject: `Colony overview at GUI scale ${scale}`,
        worldState: { scale }, hard: true,
      });
      await clickTab(client, "folkways.tab.residents");
    }
    // The book's own pages: the overview it opens on, the facilities list, and a facility's card beside the panel.
    const farm = await runColonyTask(server, MARK_FARM,
      { player: playerName, x: founding.origin.x + 6, y: founding.origin.y - 1, z: founding.origin.z - 6 });
    run.note("farm", { farm });
    await clickTab(client, "folkways.tab.overview");
    await tick.sprint(server, 25);
    await frames(client, 4);
    expect((await panelElements(client)).some(element => element.id === "folkways.book.figures")).toBe(true);
    await input.move(client, { x: 0, y: 0 });
    await run.shot(client, "06-overview", { subject: "Overview: key figures and what needs attention", worldState: {}, hard: true });
    await clickTab(client, "folkways.tab.facilities");
    await tick.sprint(server, 25);
    await frames(client, 4);
    await input.move(client, { x: 0, y: 0 });
    await run.shot(client, "07-facilities", { subject: "Facilities: every facility, troubled first", worldState: {}, hard: true });
    const listed = await panelElements(client);
    const list = listed.find(element => element.id === "folkways.book.facilities.list");
    const facility = list && listed.find(element => element.id?.startsWith("folkways.book.facility.") && !element.id.includes(".show.")
      && ancestorsOf(listed, element).some(ancestor => ancestor.id === list.id));
    run.note("facility", { listed: facility?.id ?? null });
    if (facility) {
      await clickElement(client, { id: facility.id! });
      await tick.sprint(server, 25);
      await frames(client, 6);
      expect((await panelElements(client)).some(element => element.id === "folkways.window.book_card"
        && element.displayed)).toBe(true);
      await input.move(client, { x: 0, y: 0 });
      await run.shot(client, "08-facility-card", { subject: "A facility's card beside the book", worldState: { facility: facility.id }, hard: true });
    }
    await clickTab(client, "folkways.tab.settings");
    await tick.sprint(server, 25);
    await scrollElementIntoView(client, { id: "folkways.book.scopes" });
    await frames(client, 4);
    await input.move(client, { x: 0, y: 0 });
    await run.shot(client, "09-settings", { subject: "Settings: the colony's scopes as cards", worldState: {}, hard: true });
    await screen.dismiss(client);
    for (const mode of ["point", "box", "line"] as const) {
      await setBookGesture(server, client, playerName, mode);
      await tick.sprint(server, 15);
      await safeAction(() => client.settle({ requireNoScreen: true, timeoutMs: 6000 }));
      await frames(client, 30);
      await run.shot(client, `05-book-${mode}`, {
        subject: `Held book symbol: ${mode}`, worldState: { mode }, hard: true,
      });
    }
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
