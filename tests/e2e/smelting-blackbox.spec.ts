import { expect, test } from "@playwright/test";
import {
  errorSummary,
} from "@izakyl/blockwright-client";
import { screen, tick, world } from "@izakyl/blockwright-minecraft";
import { writeFileSync } from "node:fs";
import { clickElement } from "../ldlib2";
import {
  commandPos,
  foundColonyFast,
  openColonyPanelViaBook,
  optInViaBook,
  pickViaSelector,
  safeAction,
  waitForGroundedPlayer,
  type Vec,
} from "./colony-founding";
import { boardAct, boardRows, firstEmptyGridSlot, slotItem } from "./site-panel";
import { readDoing } from "./resident-probe";
import { countInResidentPacks, mustCommand } from "./world-ui";
import { launchPair, type E2EPair } from "../shared/pair";
import { ensureOut, outAbs, tracePath } from "../out-paths";
import { seededServer } from "../shared/mod-settings";
import { BUDGET } from "../shared/budgets";
import { sampleWhileSprinting, settledAll, unwrapSettled } from "./tick-tools";
import { frames, waitForElement, softly } from "../shared/bw-helpers";
import { newRunId } from "../shared/run-id";

const CONFIG_PATH = "blockwright.toml";
const RESIDENT_COUNT = 3;

const ORDER_COUNT = 8;
const SMELT_INPUT = "minecraft:raw_iron";
const SMELT_OUTPUT = "minecraft:iron_ingot";
const POLICY_FUEL = "minecraft:oak_planks";
const CRAFT_TAB = "folkways.page.wares";
const CRAFT_PAGE = "folkways.wares";
const BLOCKED_PHASE_TICKS = 800;
const SMELT_PHASE_TICKS = 12_000;

test("smelting end-to-end: the fuel policy set through the real panel is what unblocks the furnace", async ({}, testInfo) => {
  test.setTimeout(BUDGET.standard);
  ensureOut("e2e");
  const runId = newRunId();

  const serverTrace = tracePath("e2e", "smelting-server");
  const clientTrace = tracePath("e2e", "smelting-client");

  let pair: E2EPair | undefined;
  let server: any;
  let client: any;
  let chest: any;
  let outputChest: any;

  try {
    const clientInstance = `smelting-client-${runId}`;
    pair = await launchPair({
      config: CONFIG_PATH,
      server: { trace: serverTrace, instance: seededServer(`smelting-server-${runId}`) },
      client: { trace: clientTrace, instance: clientInstance },
      snapshot: () => ({ containers: [chest, outputChest], entityTypes: ["folkways:resident", "minecraft:item"] }),
    });
    ({ server, client } = pair);

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    await mustCommand(server, `gamemode survival ${playerName}`);

    const origin: Vec = {
      x: Math.round(grounded.pos.x) + 4,
      y: Math.round(grounded.pos.y),
      z: Math.round(grounded.pos.z) + 4,
    };
    const founding = await foundColonyFast(server, client, playerName, { origin, residentCount: RESIDENT_COUNT });
    expect(founding.residents, `residents=${founding.residents} (expected ${RESIDENT_COUNT})`).toBeGreaterThanOrEqual(RESIDENT_COUNT);

    const furnace: Vec = { x: origin.x + 6, y: origin.y, z: origin.z + 6 };
    chest = { x: origin.x + 4, y: origin.y, z: origin.z + 6 };
    outputChest = { x: origin.x + 8, y: origin.y, z: origin.z + 6 };
    await mustCommand(server, `setblock ${commandPos(furnace)} minecraft:furnace`);
    await mustCommand(server, `setblock ${commandPos(chest)} minecraft:chest{Items:[]}`);
    await mustCommand(server, `setblock ${commandPos(outputChest)} minecraft:chest{Items:[]}`);
    await tick.sprint(server, 3);
    await optInViaBook(server, client, playerName, chest, "chest");
    await optInViaBook(server, client, playerName, outputChest, "chest");
    await optInViaBook(server, client, playerName, furnace, "worksite");
    await mustCommand(server, `item replace block ${commandPos(chest)} container.0 with ${POLICY_FUEL} 32`);
    await mustCommand(server, `item replace block ${commandPos(furnace)} container.0 with ${SMELT_INPUT} ${ORDER_COUNT}`);
    await tick.sprint(server, 5);

    const watched = [furnace, chest, outputChest];

    const blocked = await pollForOutput(server, watched, BLOCKED_PHASE_TICKS);
    expect(
      blocked.found,
      `${SMELT_OUTPUT} appeared after ${blocked.ticks} ticks while the colony's fuel list still held only`
        + ` coal and charcoal and neither was in storage — the furnace path is ignoring WaresContent.FUEL`,
    ).toBe(false);

    const policy = await allowFuelViaPanel(server, client, playerName, founding.standBlock);
    const smelted = await pollForOutput(server, watched, SMELT_PHASE_TICKS);
    writeFileSync(outAbs("e2e", "reports", "smelting-evidence.json"), JSON.stringify({
      policy,
      blocked,
      smelted,
      source_chest: await readContainer(server, chest),
      output_chest: await readContainer(server, outputChest),
      furnace: await readContainer(server, furnace),
      packs: await countInResidentPacks(server),
      stations: await tabRows(server, client, playerName, founding.standBlock, CRAFT_TAB, CRAFT_PAGE),
      doing: await residentsDoing(server),
    }, null, 2) + "\n");

    expect(
      policy.entry_visible,
      "the oak_planks entry never came back in the fuel grid — the item action did not reach the server (or was rejected)",
    ).toBe(true);
    expect(
      smelted.found,
      `${SMELT_OUTPUT} never appeared anywhere after oak_planks was put into the fuel grid: nobody`
        + " carried a plank to the loaded, cold furnace. The same furnace lights within ~520 ticks when a"
        + " coal is dropped into the member chest while the fuel list is left untouched, so the chore and"
        + " the dispatch both work — what did not arrive is the edit: the grid slot syncs back holding"
        + " oak_planks, and the colony still burns by the untouched default.",
    ).toBe(true);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    await pair?.teardown(testInfo);
  }

});

async function allowFuelViaPanel(server: any, client: any, playerName: string, standBlock: Vec) {
  const evidence: any = { item: POLICY_FUEL };
  evidence.open_panel = await openColonyPanelViaBook(server, client, playerName, standBlock);
  evidence.click_craft_tab = await softly(() => clickElement(client, { id: CRAFT_TAB }));
  await waitForElement(client, { idPattern: `folkways.board.edit.${CRAFT_PAGE}.*` }, { timeoutMs: 8_000 });
  const fuel = await fuelEditToken(client);
  evidence.fuel_edit = fuel;
  evidence.open_fuel_grid = await softly(() => clickElement(client, { id: fuel }));
  await tick.sprint(server, 5);
  await safeAction(() => frames(client, 3));

  const slot = await firstEmptyGridSlot(client);
  evidence.slot = slot;
  evidence.pick_fuel = await pickViaSelector(client, slot, POLICY_FUEL, "oak_planks");
  await tick.sprint(server, 10);
  const settled = await slotItem(client, slot);
  evidence.settled = settled;
  evidence.entry_visible = settled?.id === POLICY_FUEL;
  await screen.dismiss(client);
  await tick.sprint(server, 10);
  return evidence;
}

async function fuelEditToken(client: any): Promise<string> {
  const rows = (await boardRows(client)).filter((row) => row.page === CRAFT_PAGE);
  if (rows.length === 0) {
    throw new Error("smelting: the Craft page has no rows");
  }
  return (await boardAct(client, "edit", CRAFT_PAGE, rows[0].row, 0)).id;
}

async function tabRows(
  server: any, client: any, playerName: string, standBlock: Vec, tab: string, page: string,
) {
  await openColonyPanelViaBook(server, client, playerName, standBlock);
  await softly(() => clickElement(client, { id: tab }));
  await tick.sprint(server, 30);
  await safeAction(() => frames(client, 5));
  const rows = (await boardRows(client)).filter((row) => row.page === page);
  await screen.dismiss(client);
  return rows;
}

async function residentsDoing(server: any) {
  const listed = await world
    .entities(server, { dimension: "minecraft:overworld", types: ["folkways:resident"], limit: 100 })
    .catch(() => ({ entities: [] as world.EntityJson[] }));
  const out: any[] = [];
  for (const resident of listed.entities) {
    try {
      out.push({ uuid: resident.uuid, doing: await readDoing(server, resident.uuid) });
    } catch (error) {
      out.push({ uuid: resident.uuid, doing_error: errorSummary(error) });
    }
  }
  return out;
}

async function readContainer(server: any, at: Vec) {
  const result = await world
    .container(server, at, { dimension: "minecraft:overworld" })
    .catch((error: any) => ({ error: String(error) }));
  return { at, slots: (result as any).slots ?? [], raw: (result as any).error };
}

async function pollForOutput(server: any, watched: Vec[], maxTicks: number) {
  const step = 20;
  const outcome = await sampleWhileSprinting(server, {
    maxTicks,
    stepTicks: step,
    read: async (ticks) => {
      const reads = await settledAll(
        watched.map((at) => () =>
          world.probe(server, `if items block ${commandPos(at)} container.* ${SMELT_OUTPUT}`)),
      );
      for (let index = 0; index < reads.length; index++) {
        if (unwrapSettled(reads[index])) {
          return { ticks, at: watched[index] as Vec | undefined };
        }
      }
      return { ticks, at: undefined as Vec | undefined };
    },
    done: (sample) => sample.at !== undefined,
  });
  if (outcome.matched) {
    return { found: true, ticks: outcome.sample.ticks, at: outcome.sample.at };
  }
  return { found: false, ticks: maxTicks, reason: outcome.reason };
}
