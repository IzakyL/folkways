import { launchPair, type E2EPair } from "../shared/pair";
import { expect, test } from "@playwright/test";
import { input, player, reflect, screen, tick, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { clickElement, panelElements } from "../ldlib2";
import { foundColonyFast, waitForGroundedPlayer, type Vec } from "../e2e/colony-founding";
import { setBookGesture } from "../e2e/zone-tools";
import { LayoutProbe } from "./layout-lint";
import { CONFIG_PATH, VisualRun } from "./visual-kit";
import { tracePath } from "../out-paths";
import { seededOffThreadServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { frames, safeAction, safeScreen, screenName, screenOpen, tryCommand, softly } from "../shared/bw-helpers";
import { newRunId } from "../shared/run-id";

const BOOK_ITEM = "folkways:colony_book";
const TARGET_RESIDENTS = 2;

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
      await clickElement(client, { id: "folkways.tab.settings" }, { scrollIntoView: true });
      await frames(client, 4);
      await clickElement(client, { id: "folkways.settings.reset_layout" });
      await frames(client, 4);
      await clickElement(client, { id: "folkways.tab.metrics" }, { scrollIntoView: true });
      await frames(client, 4);
      await input.move(client, { x: 0, y: 0 });
      await frames(client, 3);
      await run.shot(client, `04-overview-scale-${scale}`, {
        subject: `Colony overview at GUI scale ${scale}`,
        worldState: { scale }, hard: true,
      });
      await clickElement(client, { id: "folkways.tab.residents" }, { scrollIntoView: true });
    }
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
