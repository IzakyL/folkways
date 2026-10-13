import { expect, test } from "@playwright/test";
import { input, reflect, screen, tick, world } from "@izakyl/blockwright-minecraft";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { foundColonyFast, optInViaBook, waitForGroundedPlayer } from "../shared/colony/colony-founding";
import { enterSitePanel, openContainerScreen } from "../shared/ui/site-panel";
import { setMaintainDemandViaUI } from "../shared/colony/membership-optin";
import { CONFIG_PATH, VisualRun } from "../shared/capture/visual-kit";
import { tracePath } from "../shared/harness/out-paths";
import { seededOffThreadServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { frames, safeScreen, screenName, waitForElement } from "../shared/harness/bw-helpers";

test("resident names and cards, and a chest window that fits every GUI scale", async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  const run = new VisualRun("resident-ui");
  const runId = Date.now();
  let pair: E2EPair | undefined;
  try {
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: tracePath("visual", "resident-ui-server"), instance: seededOffThreadServer(`resident-ui-server-${runId}`) },
      client: { trace: tracePath("visual", "resident-ui-client"), instance: `resident-ui-client-${runId}` },
    });
    const { server, client } = pair;
    const grounded = await waitForGroundedPlayer(server);
    const player = grounded.name;
    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    const founded = await foundColonyFast(server, client, player, { origin, residentCount: 2 });
    await screen.dismiss(client);
    await world.command(server, `tp @e[type=folkways:resident,limit=1] ${origin.x + 4} ${origin.y} ${origin.z + 4}`);
    await world.command(server, `tp ${player} ${origin.x + 4} ${origin.y} ${origin.z + 9} 180 0`);
    await tick.sprint(server, 8);
    await frames(client, 30);
    await run.shot(client, "01-resident-card", {
      subject: "Resident nameplate: the name and job lines should render in front of the backdrop", worldState: { origin }, hard: true,
    });

    const chest = { x: founded.origin.x + 4, y: origin.y, z: origin.z };
    await world.command(server, `setblock ${chest.x} ${chest.y} ${chest.z} minecraft:chest`);
    await optInViaBook(server, client, player, chest, "chest");
    await world.command(server, `tp ${player} ${chest.x + 0.5} ${chest.y} ${chest.z + 1.5} 180 30`);
    await tick.sprint(server, 5);
    await openContainerScreen(client, chest);
    await enterSitePanel(server, client);
    expect(screenName(await safeScreen(client))).toMatch(/ModularUI/);
    for (const scale of [1, 2, 3]) {
      const integer = await reflect.invoke(client, {
        target: { kind: "static", className: "java.lang.Integer" },
        method: "valueOf", args: [scale], argTypes: ["int"], returnHandle: true,
      });
      await reflect.invoke(client, { target: { kind: "static", className: "net.minecraft.client.Minecraft" },
        path: "getInstance().options.guiScale()", method: "set", args: [{ $handle: integer.handle }], argTypes: ["java.lang.Object"] });
      await reflect.invoke(client, { target: { kind: "static", className: "net.minecraft.client.Minecraft" },
        path: "getInstance()", method: "resizeDisplay" });
      await frames(client, 8);
      const panel = await waitForElement(client, { id: "folkways.site.panel" });
      expect(panel.x).toBeGreaterThanOrEqual(0);
      expect(panel.y).toBeGreaterThanOrEqual(0);
      expect(panel.x + panel.width).toBeLessThanOrEqual(Math.ceil(1280 / scale));
      expect(panel.y + panel.height).toBeLessThanOrEqual(Math.ceil(720 / scale));
      await input.move(client, { x: 0, y: 0 });
      await run.shot(client, `02-chest-scale-${scale}`, {
        subject: `The chest's own window, GUI scale ${scale}: the board stays inside the screen`,
        worldState: { chest, scale, panel }, hard: true,
      });
    }
    await screen.dismiss(client);
    await setMaintainDemandViaUI(server, client, player, chest, "minecraft:cobblestone", 1);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
