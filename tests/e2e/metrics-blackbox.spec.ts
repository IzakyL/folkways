import { expect, test } from "@playwright/test";
import { writeFileSync } from "node:fs";
import {
  commandPos,
  foundColonyFast,
  foundingBox,
  openColonyPanelViaBook,
  optInViaBook,
  safeAction,
  waitForGroundedPlayer,
  type Vec,
} from "../shared/colony/colony-founding";
import { clickElement, type Element, panelElements } from "../ldlib2";
import { screen, tick } from "@izakyl/blockwright-minecraft";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { ensureOut, outAbs, tracePath } from "../shared/harness/out-paths";
import { seededServer } from "../shared/harness/mod-settings";
import { BUDGET } from "../shared/harness/budgets";
import { frames, tryCommand, waitForElement, softly } from "../shared/harness/bw-helpers";
import { newRunId } from "../shared/harness/run-id";
import { twins, type Stage } from "../shared/harness/stage";

const RESIDENT_COUNT = 2;

for (const stage of twins()) test(stage.title("the storage tab reads the stock of the colony the player is standing in"), async ({}, testInfo) => {
  test.setTimeout(BUDGET.quick);
  const runId = newRunId();
  let pair: E2EPair | undefined;
  const evidence: any = { resident_count: RESIDENT_COUNT };
  let server: any;
  let client: any;
  try {
    pair = await launchPair({
      config: stage.config,
      server: { trace: tracePath("e2e", stage.name("metrics-server")), instance: seededServer(stage.name(`metrics-server-${runId}`)) },
      client: { trace: tracePath("e2e", stage.name("metrics-client")), instance: stage.name(`metrics-client-${runId}`) },
    });
    ({ server, client } = pair);

    const grounded = await waitForGroundedPlayer(server);
    const playerName = grounded.name;
    const origin: Vec = {
      x: Math.round(grounded.pos.x) + 4,
      y: Math.round(grounded.pos.y),
      z: Math.round(grounded.pos.z) + 4,
    };
    await tryCommand(server, `op ${playerName}`);
    await tryCommand(server, `gamemode creative ${playerName}`);

    await stage.lay(server, foundingBox(origin));
    const founding = await foundColonyFast(server, client, playerName, { origin, residentCount: RESIDENT_COUNT, stage });
    evidence.founding = { origin, residents: founding.residents, stand: founding.standBlock };

    const before = await readStorage(server, client, playerName, founding.standBlock, stage);
    evidence.before = before;

    const chest: Vec = { x: origin.x + 6, y: origin.y, z: origin.z + 6 };
    await tryCommand(server, `setblock ${commandPos(stage.at(chest))} minecraft:chest`);
    await tryCommand(server, `item replace block ${commandPos(stage.at(chest))} container.0 with minecraft:cooked_beef 64`);
    await tick.sprint(server, 3);
    evidence.opt_in = await optInViaBook(server, client, playerName, chest, "chest", stage);
    await tick.sprint(server, 10);

    const after = await readStorage(server, client, playerName, founding.standBlock, stage);
    evidence.after = after;
    expect(after.steak ?? figureMissing(after.drawn, STEAK), "the 64 cooked beef in the new member chest did not show up in the Steak slot")
      .toBeGreaterThanOrEqual((before.steak ?? 0) + 60);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    ensureOut("e2e");
    const body = JSON.stringify(evidence, null, 2);
    writeFileSync(outAbs("e2e", "reports", `${stage.name("metrics-evidence")}.json`), body);
    testInfo.attach(stage.name("metrics-evidence"), { body, contentType: "application/json" });
    await pair?.teardown(testInfo);
  }
});

const STEAK = "folkways.metrics.stock.minecraft.cooked_beef";

async function readStorage(server: any, client: any, playerName: string, standBlock: Vec, stage: Stage) {
  await openColonyPanelViaBook(server, client, playerName, standBlock, stage);
  const tab = await softly(() => clickElement(client, { id: "folkways.tab.metrics" }));
  await softly(() => waitForElement(client, { id: "folkways.metrics.refresh" }, { timeoutMs: 8_000 }));
  const refresh = await softly(() => clickElement(client, { id: "folkways.metrics.refresh" }));
  await tick.sprint(server, 5);
  await safeAction(() => frames(client, 3));
  const drawn = await panelElements(client);
  const texts = [...new Set(drawn.map((element) => element.text).filter((text): text is string => !!text))];
  await screen.dismiss(client);
  return { tab, refresh, texts, steak: figureOf(drawn, STEAK), drawn: describe(drawn) };
}

// A Storage slot is the item's icon with its count tucked under it, compacted past a thousand ("1.2k", "15k").
function figureOf(drawn: Element[], slotId: string): number | undefined {
  const slot = drawn.find((element) => element.id === slotId && legible(element));
  if (!slot) {
    return undefined;
  }
  const figures = subtreeOf(drawn, slot)
    .filter((element) => legible(element) && COMPACT.test(element.text?.trim() ?? ""));
  if (figures.length > 1) {
    return undefined;
  }
  return figures.length === 0 ? 1 : compacted(figures[0].text!.trim());
}

const COMPACT = /^\d+(\.\d)?k?$/;

function compacted(text: string): number {
  return text.endsWith("k") ? Math.round(Number(text.slice(0, -1)) * 1_000) : Number(text);
}

function figureMissing(drawn: string[], slotId: string): never {
  throw new Error(`could not read a count from the ${slotId} slot. `
    + `A Storage slot is an element with that id holding the item icon and at most one count label, `
    + `so either no shown element has that id, or it holds more than one count. `
    + `Every element with text on the panel right now (text@widthxheight@depth): `
    + JSON.stringify(drawn));
}

function describe(drawn: Element[]): string[] {
  return drawn
    .filter((element) => !!element.text || element.id?.startsWith("folkways.metrics.stock."))
    .map((element) => `${element.id ?? element.text}@${element.width}x${element.height}@${element.depth}`);
}

function subtreeOf(drawn: Element[], root: Element): Element[] {
  const inside: Element[] = [];
  for (let at = root.index + 1; at < drawn.length && drawn[at].depth > root.depth; at++) {
    inside.push(drawn[at]);
  }
  return inside;
}

function legible(element: Element): boolean {
  return element.displayed && element.visible && element.height > 0;
}
