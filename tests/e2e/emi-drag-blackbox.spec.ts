import { expect, test } from "@playwright/test";
import { filedOrdersAt, ORDER_COUNT, ORDER_ITEM, ORDER_LOW, ORDER_MAINTAIN, ORDERS_HERE } from "../shared/colony/membership-optin";
import { defineTask, input, javaTask, media, tick } from "@izakyl/blockwright-minecraft";
import { ancestorsOf, clickElement, panelElements, visibleBox } from "../ldlib2";
import { cardRowsIn, facilityAt, openFacilityInBook, textUnder, waitForPresent } from "../shared/ui/book-cards";
import { typeCell } from "../shared/ui/site-panel";
import { createColonyViaBook, openColonyPanelViaBook, optInViaBook, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, outAbs, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { frames, commandPos, tryCommand, waitForElement } from "../shared/harness/bw-helpers";
import { mkdirSync } from "node:fs";
import { newRunId } from "../shared/harness/run-id";

const CONFIG_PATH = "blockwright.toml";

type IndexCell = { x: number; y: number; id: string; name: string };

const EMI_INDEX_CELL = defineTask<Record<string, never>, { cell?: IndexCell }>({
  name: "folkways.emi.index-cell",
  side: "client",
  source: javaTask({
    imports: ["dev.emi.emi.screen.EmiScreenManager", "net.minecraft.client.Minecraft",
      "net.minecraft.core.registries.BuiltInRegistries"],
    body: `
if (EmiScreenManager.isDisabled()) {
    return Map.of();
}
var window = Minecraft.getInstance().getWindow();
int width = window.getGuiScaledWidth();
for (int y = 28; y < window.getGuiScaledHeight() - 20; y += 8) {
    for (int x = width - 10; x > width * 2 / 3; x -= 8) {
        var hovered = EmiScreenManager.getHoveredStack(x, y, false, true);
        if (hovered.isEmpty() || hovered.getStack().getEmiStacks().size() != 1) {
            continue;
        }
        var stack = hovered.getStack().getEmiStacks().getFirst().getItemStack();
        if (!stack.isEmpty()) {
            return Map.of("cell", Map.of("x", x, "y", y,
                "id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                "name", stack.getHoverName().getString()));
        }
    }
}
return Map.of();
`,
  }),
});

test("dragging an EMI index cell into the order item picker files that item", async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");

  const runId = newRunId();
  let pair: E2EPair | undefined;

  let server: any;
  let client: any;
  let chest: Vec | undefined;
  const evidence: any = {};

  try {
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: tracePath("e2e", "emi-drag-server"), instance: seededServer(`emi-drag-server-${runId}`) },
      client: { trace: tracePath("e2e", "emi-drag-client"), instance: `emi-drag-client-${runId}` },
      snapshot: () => ({ containers: chest ? [chest] : [] }),
    });
    ({ server, client } = pair);

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    const origin: Vec = {
      x: Math.round(grounded.pos.x) + 4,
      y: Math.round(grounded.pos.y),
      z: Math.round(grounded.pos.z) + 4,
    };
    await flattenPlot(server, origin);
    await tryCommand(server, `gamemode survival ${playerName}`);

    const standBlock: Vec = { x: origin.x, y: origin.y, z: origin.z };
    await tryCommand(server, `give ${playerName} folkways_core:colony_book`);
    await tryCommand(server, `item replace entity ${playerName} weapon.mainhand with folkways_core:colony_book`);
    await tick.sprint(server, 5);
    evidence.found = await createColonyViaBook(server, client, playerName, standBlock);

    chest = { x: origin.x - 2, y: origin.y, z: origin.z };
    await tryCommand(server, `setblock ${commandPos(chest)} minecraft:chest{Items:[]}`);
    await tick.sprint(server, 3);
    evidence.opt_in = await optInViaBook(server, client, playerName, chest, "chest");

    const facility = await facilityAt(server, playerName, chest);
    expect(facility, "the chest should be a held storage facility").toBeTruthy();
    await openColonyPanelViaBook(server, client, playerName, standBlock);
    await openFacilityInBook(server, client, facility!);
    await waitForPresent(server, client, ORDER_ITEM);
    evidence.item_before = textUnder(await panelElements(client), ORDER_ITEM);
    await clickElement(client, { id: ORDER_ITEM });
    await waitForElement(client, { id: "folkways.selector.close" });
    const tree = await panelElements(client);
    const list = tree.find(element => element.id === "folkways.selector.tree")!;
    const box = ancestorsOf(tree, list).find(element => element.className.endsWith(".ScrollerView"))!;
    const visible = visibleBox(tree, box)!;
    const dropTarget = { x: visible.x + visible.width / 2, y: visible.y + visible.height / 2 };
    evidence.drop_target = dropTarget;

    let cell: IndexCell | undefined;
    await expect.poll(async () => {
      await frames(client, 3);
      ({ cell } = await client.run(EMI_INDEX_CELL, {}));
      return cell;
    }, { message: "EMI should expose an item in its visible index" }).toBeDefined();
    const { x, y, id: draggedId, name: draggedName } = cell!;
    evidence.drag_source = { x, y, id: draggedId, name: draggedName };
    expect(evidence.item_before, "the draft should not name the item yet").not.toContain(draggedName);

    const dragReach = await input.drag(client, { from: { x, y }, to: dropTarget, steps: 12 });
    evidence.drag_reach = dragReach;
    expect(dragReach.pressArmed, "the press must set MouseHandler's drag state").toBe(true);
    expect(dragReach.dispatched, "every step must actually move the cursor").toBeGreaterThan(0);

    await clickElement(client, { id: "folkways.selector.confirm" });
    await waitForElement(client, { id: "folkways.selector.close" }, { state: "absent" });
    await expect.poll(async () => {
      await tick.sprint(server, 5);
      return textUnder(await panelElements(client), ORDER_ITEM);
    }).toContain(draggedName);

    for (const id of [ORDER_LOW, ORDER_COUNT]) {
      await waitForPresent(server, client, id);
      await typeCell(server, client, id, "1");
    }
    await waitForPresent(server, client, ORDER_MAINTAIN);
    await clickElement(client, { id: ORDER_MAINTAIN });
    await expect.poll(async () => {
      await tick.sprint(server, 10);
      const tree = await panelElements(client);
      return cardRowsIn(tree, ORDERS_HERE).map(row => textUnder(tree, row.id));
    }, { message: "the container card must list the new order" }).toEqual([expect.stringContaining(draggedName)]);
    expect(await filedOrdersAt(server, playerName, chest, draggedId)).toEqual([{ high: 1, standing: true }]);
  } catch (error) {
    if (client) {
      const shots = outAbs("e2e", "shots", "emi-drag-blackbox");
      mkdirSync(shots, { recursive: true });
      await media.screenshot(client, { path: `${shots}/failure.png` }).catch(() => undefined);
    }
    pair?.failing(error);
    throw error;
  } finally {
    testInfo.attach("emi-drag-evidence", { body: JSON.stringify(evidence, null, 2), contentType: "application/json" });
    await pair?.teardown(testInfo);
  }
});

async function flattenPlot(server: any, origin: Vec) {
  const minX = origin.x - 6;
  const maxX = origin.x + 6;
  const minZ = origin.z - 6;
  const maxZ = origin.z + 6;
  await tryCommand(server, `fill ${minX} ${origin.y} ${minZ} ${maxX} ${origin.y + 5} ${maxZ} minecraft:air`);
  await tryCommand(
    server,
    `fill ${minX} ${origin.y - 1} ${minZ} ${maxX} ${origin.y - 1} ${maxZ} minecraft:smooth_stone`,
  );
  await tryCommand(server, `setblock ${commandPos(origin)} minecraft:smooth_stone`);
}
