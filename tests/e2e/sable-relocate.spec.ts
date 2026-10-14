import { expect, test } from "@playwright/test";
import { tick } from "@izakyl/blockwright-minecraft";
import { foundColonyFast, isColonyMember, optInViaBook, waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { cellLines, readColony } from "../shared/colony/sable-probe";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { commandPos, tryCommand } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { SABLE_CONFIG, shipAt } from "../shared/harness/stage";

const STOCK_ITEM = "minecraft:cobblestone";
const STOCK = 32;
// The founding pad's reach round its origin, walls included, and its height: the whole colony goes aboard.
const PAD = 20;
const HEIGHT = 6;
const SETTLE_TICKS = 200;

test("a colony assembled into a Sable ship keeps every block it held, at the ship's own addresses", async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  ensureOut("e2e");

  const runId = newRunId();
  let pair: E2EPair | undefined;
  let store: Vec | undefined;
  let offset: Vec | undefined;
  const evidence: any = {};
  try {
    pair = await launchPair({
      config: SABLE_CONFIG,
      server: { trace: tracePath("e2e", "sable-relocate-server"), instance: seededServer(`sable-relocate-server-${runId}`) },
      client: { trace: tracePath("e2e", "sable-relocate-client"), instance: `sable-relocate-client-${runId}` },
      snapshot: () => ({ containers: [store && offset ? shifted(store, offset) : store], entityTypes: ["folkways:resident"] }),
    });
    const { server, client } = pair;

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    await tryCommand(server, `gamemode survival ${playerName}`);
    const origin = { x: Math.round(grounded.pos.x) + 4, y: Math.round(grounded.pos.y), z: Math.round(grounded.pos.z) + 4 };
    const founded = await foundColonyFast(server, client, playerName, { origin, residentCount: 2 });
    const colonyId = String(founded.evidence.colonyId);

    // A store taken in through the book, as a player would, and stocked.
    store = { x: origin.x + 2, y: origin.y, z: origin.z + 4 };
    await tryCommand(server, `setblock ${commandPos(store)} minecraft:chest`);
    await tryCommand(server, `item replace block ${commandPos(store)} container.0 with ${STOCK_ITEM} ${STOCK}`);
    await tick.sprint(server, 5);
    evidence.opt_in = await optInViaBook(server, client, playerName, store, "chest");

    const before = await readColony(server, colonyId, STOCK_ITEM);
    evidence.before = before;
    expect(before.cells.length, "the colony holds its beds, food chest and store before it goes aboard").toBeGreaterThan(0);
    expect(before.cells.filter((cell) => cell.frame), "nothing is aboard before the assembly").toEqual([]);
    expect(before.stock).toBe(STOCK);

    const min = { x: origin.x - PAD, y: origin.y - 1, z: origin.z - PAD };
    const max = { x: origin.x + PAD, y: origin.y + HEIGHT, z: origin.z + PAD };
    evidence.assemble = await tryCommand(server, `sable assemble area ${commandPos(min)} ${commandPos(max)}`);
    const ship = await shipAt(server, { x: origin.x, y: origin.y - 1, z: origin.z }, `assemble said ${JSON.stringify(evidence.assemble)}`);
    offset = ship.offset;
    evidence.ship = ship;

    const expected = before.cells.map((cell) => ({ ...cell, frame: ship.structure, ...shifted(cell, ship.offset) }));
    const after = await readColony(server, colonyId, STOCK_ITEM);
    evidence.after = after;
    expect(cellLines(after.cells), "every held block moved to the same cell of the ship (SableAssemblyMixin -> ColonyData.relocate)")
      .toEqual(cellLines(expected));
    expect(await isColonyMember(server, shifted(store, ship.offset)), "the store's member mark went aboard with it").toBe(true);
    expect(after.stock, "the colony still counts the store's stock aboard").toBe(STOCK);

    // The colony reconciles its holdings against the world as it ticks: none aboard may be dropped as gone.
    await tick.sprint(server, SETTLE_TICKS);
    const settled = await readColony(server, colonyId, STOCK_ITEM);
    evidence.settled = settled;
    expect(cellLines(settled.cells), "the holdings aboard survive the colony's own reconciling").toEqual(cellLines(expected));
    expect(settled.stock).toBe(STOCK);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await testInfo.attach("sable-relocate", { body: JSON.stringify(evidence, null, 2), contentType: "application/json" });
    await pair?.teardown(testInfo);
  }
});

function shifted(cell: Vec, by: Vec): Vec {
  return { x: cell.x + by.x, y: cell.y + by.y, z: cell.z + by.z };
}
