import { expect, test } from "@playwright/test";
import { screen, tick, world, type MinecraftClient, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { clickElement, panelElements, waitForElement } from "../ldlib2";
import { waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { VisualRun } from "../shared/capture/visual-kit";
import { BUDGET } from "../shared/harness/budgets";
import { frames, tryCommand } from "../shared/harness/bw-helpers";
import { seededServer } from "../shared/harness/mod-settings";
import { tracePath } from "../shared/harness/out-paths";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { newRunId } from "../shared/harness/run-id";
import { openTomsTerminal, orderAtTomsTerminal, stageTomsNetwork, ORDER_BUTTON, TOMS_ORDERS } from "../shared/ui/toms-orders";

const INGOT = "minecraft:iron_ingot";
const BREAD = "minecraft:bread";

// A Tom's terminal that is a colony store orders from the colony: the "Order…" button, the picker and the
// requests it files, read back from the colony's order book.
test("ordering smelted goods from the colony at a Tom's storage terminal", async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  const run = new VisualRun("toms-orders");
  const runId = newRunId();
  let pair: E2EPair | undefined;
  let server: MinecraftServer;
  let client: MinecraftClient;
  try {
    pair = await launchPair({
      config: "blockwright.toml#storage_toms",
      server: { trace: tracePath("visual", "toms-orders-server"), instance: seededServer(`visual-toms-orders-server-${runId}`) },
      client: { trace: tracePath("visual", "toms-orders-client"), instance: `visual-toms-orders-client-${runId}` },
    });
    ({ server, client } = pair);
    const grounded = await waitForGroundedPlayer(server);
    const name = grounded.name;
    const origin: Vec = { x: Math.round(grounded.pos.x), y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) };
    await tryCommand(server, `op ${name}`);
    await world.command(server, `gamemode creative ${name}`);
    await tryCommand(server, "gamerule doDaylightCycle false");
    await tryCommand(server, "time set day");
    const terminal: Vec = { x: origin.x, y: origin.y, z: origin.z + 3 };
    await tryCommand(server, `fill ${terminal.x - 5} ${terminal.y} ${terminal.z - 3} ${terminal.x + 4} ${terminal.y + 3} ${terminal.z + 3} minecraft:air`);
    await world.command(server, `tp ${name} ${terminal.x + 0.5} ${terminal.y} ${terminal.z - 1.5} 0 35`);
    await tick.sprint(server, 5);
    const staged = await stageTomsNetwork(server, name, terminal, { "minecraft:raw_iron": 32, [BREAD]: 12 });
    run.note("staged", staged);
    expect(staged.enrolled.terminal, "the book enrols the terminal and its network").toBe("Went[]");

    await openTomsTerminal(client, server, terminal);
    await run.shot(client, "01-terminal-button", {
      subject: "A Tom's storage terminal that is a colony store wears an \"Order…\" button above its top-right corner",
      worldState: { terminal, colony: staged.colony_id },
      hard: true,
    });

    let picker: unknown;
    await orderAtTomsTerminal(client, server, {
      amount: 16,
      catalog: { toggle: "Craftable", item: "Iron Ingot" },
      beforeConfirm: async () => {
        const elements = await panelElements(client);
        picker = elements.filter(element => element.id?.startsWith("folkways.toms.orders")).map(element =>
          ({ id: element.id, text: element.text }));
        await run.shot(client, "02-order-picker", {
          subject: "The order panel: iron ingots selected directly in the terminal, 16 set with +10 and +1",
          worldState: { picker },
          hard: true,
        });
      },
    });
    run.note("picker", picker);
    const confirmation = picker as Array<{ id?: string; text?: string }>;
    expect(confirmation).toContainEqual({ id: TOMS_ORDERS.chosen, text: "Iron Ingot" });
    for (const id of [TOMS_ORDERS.search, TOMS_ORDERS.offers, TOMS_ORDERS.requests]) {
      expect(confirmation.some(element => element.id === id)).toBe(false);
    }

    await screen.clickWidget(client, { label: ORDER_BUTTON });
    await tick.sprint(server, 1);
    const status = (await waitForElement(client, { id: TOMS_ORDERS.status(INGOT) })).text;
    expect(["Waiting", "In progress", "Stalled"]).toContain(status);
    const row = (await panelElements(client)).filter(element => (element.text ?? "").includes("16 × Iron Ingot"));
    expect(row.length, "the pending request names its count and goods").toBeGreaterThan(0);
    run.note("ordered", { status });
    await run.shot(client, "03-order-pending", {
      subject: "After Order: the request for 16 iron ingots is listed for this network with its status and a cancel button",
      worldState: { status },
      hard: true,
    });

    await clickElement(client, { id: TOMS_ORDERS.cancel(INGOT) });
    let gone = false;
    for (let attempt = 0; attempt < 40 && !gone; attempt++) {
      await tick.sprint(server, 1);
      await frames(client, 2);
      gone = !(await panelElements(client)).some(element => element.id === TOMS_ORDERS.request(INGOT));
    }
    expect(gone, "cancelling withdraws the request").toBe(true);
    await run.shot(client, "04-order-cancelled", {
      subject: "After cancel: the request is withdrawn and the list is empty again",
      worldState: {},
      hard: true,
    });
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }
});
