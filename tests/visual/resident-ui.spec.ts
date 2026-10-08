import { expect, test } from "@playwright/test";
import { input, reflect, screen, tick, world } from "@izakyl/blockwright-minecraft";
import { launchPair, type E2EPair } from "../shared/pair";
import { foundColonyFast, optInViaBook, waitForGroundedPlayer } from "../e2e/colony-founding";
import { enterSitePanel, openContainerScreen } from "../e2e/site-panel";
import { setMaintainDemandViaUI } from "../e2e/membership-optin";
import { CONFIG_PATH, VisualRun } from "./visual-kit";
import { tracePath } from "../out-paths";
import { seededOffThreadServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { carriedItem, clickSlot, frames, safeScreen, screenName, waitForElement } from "../shared/bw-helpers";

test("resident names and cards, with an interactive page beside chest slots", async ({}, testInfo) => {
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
    await world.command(server, `item replace block ${chest.x} ${chest.y} ${chest.z} container.0 with minecraft:diamond 3`);
    await world.command(server, `item replace entity ${player} weapon.mainhand with minecraft:air`);
    await world.command(server, `tp ${player} ${chest.x + 0.5} ${chest.y} ${chest.z + 1.5} 180 30`);
    await tick.sprint(server, 5);
    await openContainerScreen(client, chest);
    await enterSitePanel(server, client);
    expect(screenName(await safeScreen(client))).toMatch(/ContainerScreen/);
    expect(screenName(await safeScreen(client))).not.toMatch(/ModularUI/);
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
      const slots = await screen.slots(client);
      expect(panel.x).toBeGreaterThan(slots[8].x + 16);
      expect(panel.x + panel.width).toBeLessThanOrEqual(Math.ceil(1280 / scale));
      expect(panel.y + panel.height).toBeLessThanOrEqual(Math.ceil(720 / scale));
      await clickSlot(client, scale - 1);
      await expect.poll(async () => (await carriedItem(client))?.id).toBe("minecraft:diamond");
      await clickSlot(client, scale);
      await expect.poll(async () => (await carriedItem(client))?.id ?? null).toBeNull();
      await input.move(client, { x: 0, y: 0 });
      await run.shot(client, `02-chest-scale-${scale}`, {
        subject: `Chest contents shown beside the side panel, GUI scale ${scale}; chest slots still take and give items`,
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
